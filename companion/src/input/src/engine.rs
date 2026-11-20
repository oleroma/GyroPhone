//! The host protocol engine (docs/spec/protocol.md 3.2): a port of HostEngine.cs.
//!
//! All of its state sits behind one engine lock. Entry points: `on_datagram` (the rx thread: control packets are
//! answered inline, the newest STATE is kept, the one before it injected first when its keys or buttons differ),
//! `flush` (the rx thread: inject once), `on_tick` (the watchdog) and the commands the companion sends. All I/O goes
//! through the traits in `ports`, so the engine runs and is tested without Windows.
//!
//! Where it differs from the C# engine in shape, never in what goes out:
//! - Nothing outside the engine is called while the lock is held, except the sinks on the input path (keys,
//!   mouse, pad) and `EngineHooks::move_sent`. Log lines, "state changed" notices, the pairing prompt, Diagnostics'
//!   lines and the input log's lines are queued under the lock and handed over right after it is let go, in order.
//! - The lock is not re-entrant: the public methods take it and the work is done by the methods of `Inner`.
//! - Driver failures come back as `Err` from the sinks, where the C# sinks throw.

use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, AtomicU64, AtomicU8, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, TryLockError, Weak};
use std::time::Duration;

use zeroize::{Zeroize, Zeroizing};

use crate::codec::{self, Announce, Discover, ErrorPacket, HostStatus, KeyEnroll, KeyLabels, LinkInfo, PhoneStats, Pong, Rumble};
use crate::crypto::key_pairing::{self, PubKeyFile};
use crate::crypto::{qr_pairing, P256};
use crate::gyro::{GyroGate, PadGyroConfig, PcGyro, PcPadState};
use crate::key_files::AuthorizedPhone;
use crate::le;
use crate::link_stats::LinkStats;
use crate::mapping::{self, input_diff, pad_mapper, Ds4Frame, InjectEvent, InjectKind, KeyRepeat, X360Frame};
use crate::motion::{self, MotionFrame};
use crate::mouse::{MouseCatchUp, MouseDeltaTracker};
use crate::pairing::{KeyFn, PairEvent, PairingManager};
use crate::ports::{
    Clock, HostEvents, KeyMouseListener, KeyMouseSink, KeyProtector, NullKeyMouseListener, NullPcPad, PadSink, PairPrompt, PcPad,
    SinkError, PC_PAD_PLACES,
};
use crate::press::{PressCapture, PressLatency};
use crate::rate_limiter::{ipv4_key, RateLimiter};
use crate::recorder::{ms1, HandOffCauses, InputRecorder, RecorderOut};
use crate::reports::{Ds4Motion, Ds4MotionFeed};
use crate::session::{DatagramSender, HostSession, RandomFn, SessionManager, SessionSender};
use crate::settings::{self, HostSettings, PairedPhone};
use crate::shared::{DebugQueue, DebugRaw, HeldMirror, InjectedView, OverlayInbox, OverlayKind, SharedRegion};
use crate::state_frame::{StateFrame, HAT_CENTER, KEY_BITMAP_SIZE};
use crate::wire::{
    self, announce_flags, enroll_status, error_code, host_bye_reason, host_status_flags, host_status_flags2, link_info_flags,
    link_path, output_mode, packet, rumble_pattern, state_flags, test_mode_flags, welcome_flags, Id, KeyId, PublicKey,
};

const RELEASE_TIMEOUT_US: i64 = wire::RELEASE_TIMEOUT_MS * 1000;
const SESSION_TIMEOUT_US: i64 = wire::SESSION_TIMEOUT_MS * 1000;
const STATUS_INTERVAL_US: i64 = wire::STATUS_INTERVAL_MS * 1000;
/// RUMBLE goes out at most 100 times a second.
const RUMBLE_MIN_INTERVAL_US: i64 = 10_000;
const RUMBLE_DIRTY: i32 = 1 << 16;
const RUMBLE_REPEAT_US: i64 = 1_000_000;
const RUMBLE_OFF_REPEATS: i32 = 3;
/// Stray BYE/STATE copies after a session's end draw no ERROR for this long.
const ENDED_SID_QUIET_US: i64 = 1_000_000;
/// How long a pad button pressed and released inside one receive burst stays visible to polling games.
const PAD_EDGE_HOLD_US: i64 = 20_000;
/// A broken pad bus is retried at 10 Hz, not per packet.
const PAD_RETRY_US: i64 = 100_000;
/// A pad lost underneath is plugged again after 1 s, then less often while that fails, up to every 10 s.
const PAD_REPLUG_US: i64 = 1_000_000;
const PAD_REPLUG_MAX_US: i64 = 10_000_000;
/// The pad stays plugged this long after a session ends, so a reconnect finds the same controller.
const PAD_IDLE_UNPLUG_US: i64 = 5 * 60 * 1_000_000;
/// OVERLAY's gyro axes byte (OverlayFrame.AxesOffset).
const OVERLAY_AXES_OFFSET: usize = 30;

/// How long the keep-awake thread rests while no KEEP_AWAKE is wanted (also the catch for a missed wake).
pub const KEEP_AWAKE_IDLE_WAIT_MS: u64 = 1000;
/// How often the watchdog ticks while `watchdog_idle`.
pub const WATCHDOG_IDLE_TICK_MS: u64 = 250;

/// Who holds the engine lock (the input log names it when the rx thread had to wait).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Role {
    Input = 1,
    Watchdog = 2,
    Ui = 3,
    Settings = 4,
    Probes = 5,
    Pad = 6,
}

fn role_name(v: u8) -> &'static str {
    match v {
        1 => "input",
        2 => "watchdog",
        3 => "ui",
        4 => "settings save",
        5 => "probes",
        6 => "pad",
        _ => "",
    }
}

/// The output mode's name as the C# enum prints it (log lines).
pub fn mode_name(mode: u8) -> &'static str {
    match mode {
        output_mode::XBOX360 => "Xbox360",
        output_mode::DUALSHOCK4 => "DualShock4",
        output_mode::DUALSENSE => "DualSense",
        _ => "None",
    }
}

/// A Diagnostics line's kind (DebugTrace.Kind; the number goes to the companion).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum TraceKind {
    In = 0,
    Out = 1,
    Move = 2,
    State = 3,
    Link = 4,
    Note = 5,
}

/// PHONE_STATS on the engine's clock (Perf.PhoneSecond).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PhoneSecond {
    pub at_us: i64,
    pub states: i32,
    pub packets: i32,
    pub failed: i32,
    pub slow_sends: i32,
    pub send_max_ms: f32,
    pub wake_late_ms: f32,
    pub gap_max_ms: f32,
    pub phone_tx: i64,
}

/// What the engine hands to the rest of the input process. Except `move_sent`, every call comes right after the
/// engine lock is let go, on the thread that held it (maybe the rx thread): hand anything slow to another thread.
pub trait EngineHooks: Send + Sync {
    /// A Diagnostics line (only while Diagnostics is on): the companion keeps the log.
    fn debug_line(&self, _us: i64, _kind: TraceKind, _text: &str) {}
    /// The input log's new lines and seconds, in order.
    fn recorder(&self, _out: &[RecorderOut]) {}
    /// Something went into the overlay ring: wake the companion's overlay side.
    fn overlay_wake(&self) {}
    /// Diagnostics: save now (Save on the PC or the phone, or tools/gp-log.sh).
    fn debug_save_requested(&self) {}
    /// Diagnostics switched (true on). Never on the rx thread.
    fn diagnostics_changed(&self, _on: bool) {}
    /// A PHONE_STATS arrived while Diagnostics is on.
    fn phone_second(&self, _s: &PhoneSecond) {}
    /// The delivery probe (`HostEngine::set_move_watch`): a mouse move's hand-off started at `t0_ticks` on the
    /// clock's own count (`Clock::now_ticks`). Called UNDER the engine lock on the input path, so it must only
    /// store the numbers.
    fn move_sent(&self, _t0_ticks: i64, _dx: i32, _dy: i32) {}
}

/// Hooks that do nothing.
pub struct NullHooks;

impl EngineHooks for NullHooks {}

/// "Measure buttons": the middle of the last `count` presses from input to game, its phone part, and the newest
/// press (all µs).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ButtonLatency {
    pub total_us: i64,
    pub phone_us: i64,
    pub count: usize,
    pub last_us: i64,
}

/// A paired phone for the tray: code-paired (in settings) or key-paired (a file in authorized_phones).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PairedPhoneInfo {
    pub phone_id: Vec<u8>,
    pub name: String,
    pub last_seen_utc: String,
    pub by_key: bool,
    pub key_file: Option<String>,
    pub fingerprint: Option<String>,
}

/// Diagnostic counters. Never contain key material.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct HostCounters {
    pub datagrams: i64,
    pub dropped: i64,
    pub bad_auth: i64,
    pub states: i64,
    pub injections: i64,
    pub unknown_usages: i64,
    pub errors: i64,
    pub exceptions: i64,
}

/// What the tray and status window show.
#[derive(Clone, Debug, PartialEq)]
pub struct HostSnapshot {
    pub phone_name: Option<String>,
    pub connected: bool,
    pub silent: bool,
    pub output_mode: u8,
    pub driver_ok: bool,
    pub pad_plugged: bool,
    pub paused: bool,
    pub elevated_foreground: bool,
    pub network_public: bool,
    pub pairing_window_s: i32,
    /// (attempt id, phone name, SAS) while a pairing waits for the user.
    pub pair_prompt: Option<(u32, String, String)>,
    pub rx_per_sec: u16,
    pub loss_permille: u16,
    pub lat_p50_us: u32,
    pub lat_p99_us: u32,
    pub jitter_us: u32,
    pub phone_link: Option<LinkInfo>,
    pub any_datagram_seen: bool,
    pub paired_phones: Arc<Vec<PairedPhoneInfo>>,
    pub counters: HostCounters,
    pub host_key_fingerprint: Option<String>,
    pub qr_pairing_s: i32,
    pub qr_enrolled_phone: Option<String>,
    /// "Use PC controller for gyro ratchet": None while the phone's switch is off, "" while no controller is found.
    pub pc_controller: Option<String>,
    pub dual_sense_ok: bool,
    /// The phone's "Press a key" is open: this PC's keyboard, mouse and controller are read for it.
    pub capturing: bool,
    /// The connected phone's address as the PC sees it (loopback over the direct USB cable).
    pub phone_address: Option<String>,
}

impl HostSnapshot {
    /// The driver the chosen output needs works: ours for the DualSense, ViGEmBus for the others.
    pub fn output_driver_ok(&self) -> bool {
        if self.output_mode == output_mode::DUALSENSE {
            self.dual_sense_ok
        } else {
            self.driver_ok
        }
    }
}

/// Saves a QR-enrolled phone's key file: Ok(Some(path)), Ok(None) when it could not (the phone retries), or Err
/// with what went wrong for the log.
pub type EnrollSave = Arc<dyn Fn(&PubKeyFile) -> Result<Option<PathBuf>, String> + Send + Sync>;

/// A manual-reset event: `wait` returns at once while set.
#[derive(Default)]
pub struct Signal {
    set: Mutex<bool>,
    cv: Condvar,
}

impl Signal {
    pub fn new(set: bool) -> Signal {
        Signal { set: Mutex::new(set), cv: Condvar::new() }
    }

    pub fn set(&self) {
        *self.set.lock().unwrap_or_else(|e| e.into_inner()) = true;
        self.cv.notify_all();
    }

    pub fn reset(&self) {
        *self.set.lock().unwrap_or_else(|e| e.into_inner()) = false;
    }

    /// Waits up to `ms` for the signal; true when it is set.
    pub fn wait(&self, ms: u64) -> bool {
        let g = self.set.lock().unwrap_or_else(|e| e.into_inner());
        let (g, _) = self.cv.wait_timeout_while(g, Duration::from_millis(ms), |set| !*set).unwrap_or_else(|e| e.into_inner());
        *g
    }
}

/// The input process's side of Diagnostics (DebugTrace in forward mode): raw events go into the queue in shared
/// memory, lines to the companion. The input path never makes text.
pub struct Trace {
    on: AtomicBool,
    queue: DebugQueue,
    // One writer at a time into the queue (the rx thread and the pad thread both write).
    q: Mutex<()>,
}

impl Trace {
    fn new(queue: DebugQueue) -> Trace {
        Trace { on: AtomicBool::new(false), queue, q: Mutex::new(()) }
    }

    /// Logging now: Diagnostics is on.
    #[inline]
    pub fn enabled(&self) -> bool {
        self.on.load(Ordering::Relaxed)
    }

    /// Raw events dropped because the queue was full (the companion's diagnostics thread fell behind).
    pub fn dropped(&self) -> i64 {
        self.queue.dropped()
    }

    fn on_inject(&self, events: &[InjectEvent], now_us: i64) {
        let _g = self.q.lock().unwrap_or_else(|e| e.into_inner());
        for e in events {
            self.queue.push(now_us, &DebugRaw::Inject(*e));
        }
    }

    fn on_x360(&self, f: &X360Frame, now_us: i64) {
        let _g = self.q.lock().unwrap_or_else(|e| e.into_inner());
        self.queue.push(now_us, &DebugRaw::X360(*f));
    }

    fn on_ds4(&self, f: &Ds4Frame, now_us: i64) {
        let _g = self.q.lock().unwrap_or_else(|e| e.into_inner());
        self.queue.push(now_us, &DebugRaw::Ds4(*f));
    }
}

/// What the keep-awake thread needs, kept apart from the engine lock so it never holds up input.
#[derive(Default)]
struct KeepAwakeView {
    tx: Option<SessionSender>,
    link: Option<LinkInfo>,
    sender: Option<Arc<dyn DatagramSender>>,
}

/// The parts read without the engine lock.
struct Free {
    holder: AtomicU8,
    injected: InjectedView,
    catching_up: AtomicBool,
    watchdog_idle: AtomicBool,
    link_test_until_us: AtomicI64,
    capture_on: AtomicBool,
    /// (large << 8) | small, plus RUMBLE_DIRTY while unsent. Written by the pad driver's thread.
    rumble_next: AtomicI32,
    keep_awake_wake: Signal,
    tick_wake: Signal,
    pad_idle: Signal,
    keep_awake: Mutex<KeepAwakeView>,
    /// The PC's adapter toward the phone, from the watchdog's probe: bit 63 known, bit 32 Wi-Fi client, low 32 bits
    /// the phone's IPv4 address.
    pc_side: AtomicU64,
    /// The pad sink in use, for the PC controller reader's "which XInput slot is ours" (asked outside the lock).
    pad_now: Mutex<Arc<dyn PadSink>>,
    diag_apply_queued: AtomicBool,
    /// Mouse moves are told to `EngineHooks::move_sent` (Diagnostics' delivery probe, or a Delivery test).
    move_watch: AtomicBool,
}

const PC_SIDE_KNOWN: u64 = 1 << 63;
const PC_SIDE_WIFI: u64 = 1 << 32;

/// Queued under the lock, done right after it is let go.
enum Effect {
    Log(String),
    StateChanged,
    Trace(i64, TraceKind, String),
    PromptShow(u32, String, String),
    PromptClose(u32),
    DebugSave,
    ApplyDiagnostics,
    PhoneSecond(PhoneSecond),
    StartPadJob,
    StartEnroll(u64, EnrollSave, PubKeyFile),
}

struct CodePhone {
    id: Id,
    name: String,
    pair_key: Zeroizing<Vec<u8>>,
}

struct KeyPhone {
    file: PathBuf,
    name: String,
    phone_id: Id,
    pair_key: Zeroizing<[u8; 32]>,
    fingerprint: String,
    /// The phone key's id, as the phone's DISCOVER carries it.
    key_id: KeyId,
    /// The phone's public key, so a reload of the same key needs no ECDH.
    public_key: PublicKey,
    last_seen_utc: String,
}

/// The QR code on screen: its one-time token and the phone that used it.
struct QrSession {
    serial: u64,
    token: Zeroizing<[u8; wire::QR_TOKEN_SIZE]>,
    token_id: KeyId,
    expires_us: i64,
    save: EnrollSave,
    enrolled_key: Option<PublicKey>,
    /// The key file is being saved off the rx thread.
    saving: bool,
    enrolled_name: Option<String>,
}

/// What a QR enrolment worked out before the lock: the saved file and the phone's secrets.
struct Enrolled {
    file: Option<PathBuf>,
    failed: Option<String>,
    secrets: Option<(Id, Zeroizing<[u8; 32]>)>,
}

/// How the engine is put together; `Default` is what the input process uses except for its ports.
pub struct EngineOptions {
    pub clock: Arc<dyn Clock>,
    pub events: Arc<dyn HostEvents>,
    pub prompt: Arc<dyn PairPrompt>,
    pub hooks: Arc<dyn EngineHooks>,
    /// Random bytes for session ids and nonces; None: the system's generator.
    pub session_random: Option<RandomFn>,
    /// Random bytes for pairing; None: the system's generator.
    pub pairing_random: Option<RandomFn>,
    /// A new pairing key; None: a fresh P-256 key.
    pub new_pair_key: Option<KeyFn>,
    pub host_key: Option<P256>,
    pub shared: Option<SharedRegion>,
    /// Tests: pad plugs and QR key saves run at once, in place, instead of on their own threads.
    pub inline_workers: bool,
}

struct Inner {
    free: Arc<Free>,
    clock: Arc<dyn Clock>,
    hooks: Arc<dyn EngineHooks>,
    trace: Arc<Trace>,
    effects: Vec<Effect>,
    inline_workers: bool,

    /// The rx thread's wait for the lock to take its newest datagram (µs), and who had it.
    datagram_wait: (i64, Option<&'static str>),
    /// What held up the pending STATE's hand-off so far.
    hand_off: HandOffCauses,

    held: HeldMirror,
    connected_shown: bool,
    pad_gyro_shown: bool,
    held_outputs_dirty: bool,
    overlay_inbox: OverlayInbox,
    /// Something went into the inbox: the overlay side is woken once the input is handed off.
    overlay_queued: bool,
    keep_awake_dirty: bool,

    key_mouse: Arc<dyn KeyMouseSink>,
    protector: Arc<dyn KeyProtector>,
    settings: HostSettings,
    phones: Vec<CodePhone>,
    host_key: Option<Arc<P256>>,
    key_phones: Vec<KeyPhone>,
    authorized_key_ids: Vec<KeyId>,
    host_key_id: Option<KeyId>,
    host_key_fingerprint: Option<String>,
    authorized_list: Vec<AuthorizedPhone>,
    qr: Option<QrSession>,
    qr_serial: u64,
    sessions: SessionManager,
    pairing: PairingManager,
    error_limiter: RateLimiter,
    // Decrypt attempts for a session from an address other than its own (a migration, or forged packets that reuse
    // the sid seen in clear): a flood must not reach the cipher.
    foreign_open_limiter: RateLimiter,
    stats: LinkStats,

    pad: Arc<dyn PadSink>,
    /// What the pad thread makes the pad: the output mode, or NONE to unplug.
    pad_want: u8,
    /// A plug or unplug is running on the pad thread.
    pad_busy: bool,
    /// Pad sinks replaced by `set_pad_sink`: the pad thread unplugs and drops them.
    pad_retired: Vec<Arc<dyn PadSink>>,
    /// The pad went away underneath (driver reset, disabled): the watchdog has the pad thread plug it again.
    pad_lost: bool,
    pad_replug_at_us: i64,
    pad_replug_gap_us: i64,
    /// Host time the last session ended: the pad is unplugged PAD_IDLE_UNPLUG_US later if none started.
    pad_idle_from_us: i64,
    /// The phone of the last session: forgetting it with no session up unplugs the pad it would come back to.
    pad_phone_id: Option<Id>,
    sender: Option<Arc<dyn DatagramSender>>,

    rx_frame: StateFrame,
    pc_pad: Arc<dyn PcPad>,
    slot_fn: Arc<dyn Fn() -> i32 + Send + Sync>,
    key_listener: Arc<dyn KeyMouseListener>,
    capture: PressCapture,
    captured_plain: [u8; wire::CAPTURED_PLAIN],
    pad_gyro: PadGyroConfig,
    gate: GyroGate,
    pc_gyro: PcGyro,
    motion_frame: MotionFrame,
    ds4_motion: Ds4MotionFeed,
    motion_out: [Ds4Motion; motion::MAX_SAMPLES],
    pc_presses: [u32; PC_PAD_PLACES],
    pc_reading: bool,
    pending: StateFrame,
    has_pending: bool,
    mouse: MouseDeltaTracker,
    /// Wheel motion since the last flush, both in 1/120 notch.
    acc_wheel: i32,
    acc_hwheel: i32,
    /// Mouse motion since the last flush; a lost or late stretch catches up over a few ms instead of jumping.
    catch_up: MouseCatchUp,
    last_state_phone_us: u32,
    has_state_phone: bool,
    /// The last STATE was a Link test frame (fake totals).
    last_state_link_test: bool,
    /// A MOTION injected the pending STATE mid-burst; the next STATE of the burst still gets the edge hold.
    flushed_for_motion: bool,
    lost_at_state: i64,
    pending_recv_us: i64,

    injected_keys: [u8; KEY_BITMAP_SIZE],
    injected_mouse: u8,
    held_version: i32,
    // Ups injected while UIPI or the secure desktop may have dropped them; sent again when that ends.
    blocked_ups: [u8; KEY_BITMAP_SIZE],
    blocked_mouse_ups: u8,
    inject: [InjectEvent; input_diff::MAX_EVENTS],
    key_repeat: KeyRepeat,
    release_events: [InjectEvent; input_diff::MAX_RELEASE_EVENTS],
    last_x360: X360Frame,
    last_ds4: Ds4Frame,
    pad_frame_valid: bool,
    /// After a failed pad submit, the pad waits until then instead of failing on every packet.
    pad_retry_after_us: i64,
    // Pad buttons and hat from a frame injected mid-burst, kept pressed until pad_hold_until_us.
    pad_hold_buttons: u16,
    pad_hold_hat: u8,
    pad_hold_until_us: i64,
    /// The last pad submit carried held buttons the newest STATE no longer has.
    pad_held: bool,
    pad_merged: StateFrame,
    // Raw pad buttons and hat of the last frame flush submitted, and when each was pressed: only a press younger
    // than PAD_EDGE_HOLD_US is held, never a button held from long before.
    last_pending_buttons: u16,
    last_pending_hat: u8,
    pad_press_us: [i64; 16],
    hat_press_us: i64,

    paused: bool,
    locked: bool,
    released_for_silence: bool,
    elevated_foreground: bool,
    network_public: bool,
    /// HOST_STATUS flags2's setup bits: how the PC's side could be better.
    pc_setup: u8,
    /// The sink took fewer events than given (UIPI or the secure desktop): resynced once it takes them again.
    inject_failed: bool,

    overlay_on: bool,
    overlay_compass: bool,
    overlay_samples: bool,
    overlay_keys: bool,
    press: PressLatency,
    /// Host time a Link test ends; i64::MIN: none.
    link_test_until_us: i64,
    /// HOST_STATUSes still to carry a TEST_MODE of 0 after the last test ended.
    test_mode_off_repeats: i32,
    last_status_us: i64,
    key_labels: Option<KeyLabels>,
    /// The rumble the phone was last sent (a new phone starts at 0).
    last_rumble: i32,
    /// Repeats of `last_rumble` still to send; i32::MAX while it is on.
    rumble_repeats: i32,
    last_rumble_us: i64,
    settings_dirty: bool,
    any_datagram: bool,
    logged_x360_dropped: bool,
    logged_pad_failure: bool,

    ended_sids: [u32; 4],
    ended_until_us: [i64; 4],
    ended_next: usize,
    /// Token ids of the last QR codes this PC showed: only these get an (unauthenticated) EXPIRED.
    issued_token_ids: [Option<KeyId>; 4],
    issued_next: usize,

    datagrams: i64,
    dropped: i64,
    bad_auth: i64,
    states: i64,
    injections: i64,
    unknown_usages: i64,
    errors: i64,
    exceptions: i64,

    /// The save counter of the last LINK_INFO with the debug flag; -1 until one in this session.
    debug_saves: i32,
    /// The phone's Diagnostics bit in the last LINK_INFO this session, or None before the first.
    phone_diag_seen: Option<bool>,
    /// Counts the PC's Saves (3 bits in TEST_MODE): the phone saves its side when it changes.
    diag_saves: u8,
    recorder: Option<InputRecorder>,
    link_watch: bool,
    phone_list: Option<Arc<Vec<PairedPhoneInfo>>>,
}

/// The engine. Shared by the rx thread, the watchdog, the keep-awake thread and the pipe's command thread.
pub struct HostEngine {
    me: Weak<HostEngine>,
    inner: Mutex<Inner>,
    free: Arc<Free>,
    clock: Arc<dyn Clock>,
    events: Arc<dyn HostEvents>,
    prompt: Arc<dyn PairPrompt>,
    hooks: Arc<dyn EngineHooks>,
    trace: Arc<Trace>,
    shared: SharedRegion,
    inline_workers: bool,
    /// The input log's lines on their way out; held while they are handed over, never while waiting for the engine.
    rec_out: Mutex<Vec<RecorderOut>>,
    /// What `EngineHooks::diagnostics_changed` was last told.
    diag_applied: Mutex<bool>,
}

struct Guard<'a> {
    e: &'a HostEngine,
    g: Option<MutexGuard<'a, Inner>>,
    role: Role,
}

impl std::ops::Deref for Guard<'_> {
    type Target = Inner;
    fn deref(&self) -> &Inner {
        self.g.as_ref().expect("held")
    }
}

impl std::ops::DerefMut for Guard<'_> {
    fn deref_mut(&mut self) -> &mut Inner {
        self.g.as_mut().expect("held")
    }
}

impl Drop for Guard<'_> {
    /// Letting go: what changed is published for the lock-free readers; then, with the lock free, the queued
    /// notices and the input log's new lines are handed over.
    fn drop(&mut self) {
        let Some(mut g) = self.g.take() else { return };
        let wake = g.publish_view();
        let effects = if g.effects.is_empty() { Vec::new() } else { std::mem::take(&mut g.effects) };
        let mut lines = None;
        if g.recorder.as_ref().is_some_and(|r| r.has_pending()) {
            // Busy (another thread is handing lines over): they stay queued for the next time.
            if let Ok(mut buf) = self.e.rec_out.try_lock() {
                if let Some(r) = g.recorder.as_mut() {
                    r.take(&mut buf);
                }
                lines = Some(buf);
            }
        }
        drop(g);
        if let Some(mut buf) = lines {
            self.e.hooks.recorder(&buf);
            buf.clear();
        }
        if wake {
            self.e.hooks.overlay_wake();
        }
        for effect in effects {
            self.e.run_effect(effect, self.role);
        }
    }
}

// =====================================================================================================================
// The public face
// =====================================================================================================================

impl HostEngine {
    pub fn new(
        mut settings: HostSettings,
        pad: Arc<dyn PadSink>,
        key_mouse: Arc<dyn KeyMouseSink>,
        protector: Arc<dyn KeyProtector>,
        options: EngineOptions,
    ) -> Arc<HostEngine> {
        let shared = options.shared.unwrap_or_else(SharedRegion::in_process);
        let before = settings.serialize();
        settings.normalize();
        let settings_dirty = settings.serialize() != before;
        let trace = Arc::new(Trace::new(shared.debug()));
        let free = Arc::new(Free {
            holder: AtomicU8::new(0),
            injected: InjectedView::new(),
            catching_up: AtomicBool::new(false),
            watchdog_idle: AtomicBool::new(false),
            link_test_until_us: AtomicI64::new(i64::MIN),
            capture_on: AtomicBool::new(false),
            rumble_next: AtomicI32::new(0),
            keep_awake_wake: Signal::new(false),
            tick_wake: Signal::new(false),
            pad_idle: Signal::new(true),
            keep_awake: Mutex::new(KeepAwakeView::default()),
            pc_side: AtomicU64::new(0),
            pad_now: Mutex::new(pad.clone()),
            diag_apply_queued: AtomicBool::new(false),
            move_watch: AtomicBool::new(false),
        });
        let slot_free = free.clone();
        let slot_fn: Arc<dyn Fn() -> i32 + Send + Sync> = Arc::new(move || {
            // The slot question may block briefly: asked with the sink's own handle, outside every lock.
            let pad = slot_free.pad_now.lock().unwrap_or_else(|e| e.into_inner()).clone();
            pad.xinput_slot()
        });
        let host_key = options.host_key.map(Arc::new);
        let mut catch_up = MouseCatchUp::new();
        catch_up.max_us = settings.catch_up_ms as i64 * 1000;
        let mut inner = Inner {
            free: free.clone(),
            clock: options.clock.clone(),
            hooks: options.hooks.clone(),
            trace: trace.clone(),
            effects: Vec::new(),
            inline_workers: options.inline_workers,
            datagram_wait: (0, None),
            hand_off: HandOffCauses::NONE,
            held: shared.held(),
            connected_shown: false,
            pad_gyro_shown: false,
            held_outputs_dirty: false,
            overlay_inbox: shared.overlays(),
            overlay_queued: false,
            keep_awake_dirty: false,
            key_mouse,
            protector,
            phones: Vec::new(),
            host_key_id: host_key.as_ref().map(|k| key_pairing::key_id(k.public_key())),
            host_key_fingerprint: host_key.as_ref().map(|k| key_pairing::fingerprint(k.public_key())),
            host_key,
            key_phones: Vec::new(),
            authorized_key_ids: Vec::new(),
            authorized_list: Vec::new(),
            qr: None,
            qr_serial: 0,
            sessions: SessionManager::new(options.session_random.unwrap_or_else(crate::session::system_random)),
            pairing: PairingManager::new(
                options.pairing_random.unwrap_or_else(crate::session::system_random),
                options.new_pair_key.unwrap_or_else(|| Box::new(P256::generate)),
            ),
            error_limiter: RateLimiter::new(wire::ERROR_RATE_PER_IP, wire::ERROR_RATE_GLOBAL),
            foreign_open_limiter: RateLimiter::new(wire::ERROR_RATE_PER_IP, wire::ERROR_RATE_GLOBAL),
            stats: LinkStats::new(),
            pad: pad.clone(),
            pad_want: output_mode::NONE,
            pad_busy: false,
            pad_retired: Vec::new(),
            pad_lost: false,
            pad_replug_at_us: 0,
            pad_replug_gap_us: 0,
            pad_idle_from_us: 0,
            pad_phone_id: None,
            sender: None,
            rx_frame: StateFrame::default(),
            pc_pad: Arc::new(NullPcPad),
            slot_fn,
            key_listener: Arc::new(NullKeyMouseListener),
            capture: PressCapture::new(),
            captured_plain: [0; wire::CAPTURED_PLAIN],
            pad_gyro: PadGyroConfig::default(),
            gate: GyroGate::default(),
            pc_gyro: PcGyro::default(),
            motion_frame: MotionFrame::default(),
            ds4_motion: Ds4MotionFeed::new(),
            motion_out: [Ds4Motion::NEUTRAL; motion::MAX_SAMPLES],
            pc_presses: [0; PC_PAD_PLACES],
            pc_reading: false,
            pending: StateFrame::default(),
            has_pending: false,
            mouse: MouseDeltaTracker::new(),
            acc_wheel: 0,
            acc_hwheel: 0,
            catch_up,
            last_state_phone_us: 0,
            has_state_phone: false,
            last_state_link_test: false,
            flushed_for_motion: false,
            lost_at_state: 0,
            pending_recv_us: -1,
            injected_keys: [0; KEY_BITMAP_SIZE],
            injected_mouse: 0,
            held_version: 0,
            blocked_ups: [0; KEY_BITMAP_SIZE],
            blocked_mouse_ups: 0,
            inject: [InjectEvent::default(); input_diff::MAX_EVENTS],
            key_repeat: KeyRepeat::new(),
            release_events: [InjectEvent::default(); input_diff::MAX_RELEASE_EVENTS],
            last_x360: X360Frame::NEUTRAL,
            last_ds4: Ds4Frame::NEUTRAL,
            pad_frame_valid: false,
            pad_retry_after_us: i64::MIN,
            pad_hold_buttons: 0,
            pad_hold_hat: HAT_CENTER,
            pad_hold_until_us: i64::MIN,
            pad_held: false,
            pad_merged: StateFrame::default(),
            last_pending_buttons: 0,
            last_pending_hat: HAT_CENTER,
            pad_press_us: [i64::MIN; 16],
            hat_press_us: i64::MIN,
            paused: false,
            locked: false,
            released_for_silence: false,
            elevated_foreground: false,
            network_public: false,
            pc_setup: 0,
            inject_failed: false,
            overlay_on: false,
            overlay_compass: false,
            overlay_samples: false,
            overlay_keys: false,
            press: PressLatency::new(),
            link_test_until_us: i64::MIN,
            test_mode_off_repeats: 0,
            last_status_us: 0,
            key_labels: None,
            last_rumble: 0,
            rumble_repeats: 0,
            last_rumble_us: -RUMBLE_MIN_INTERVAL_US,
            settings_dirty,
            any_datagram: false,
            logged_x360_dropped: false,
            logged_pad_failure: false,
            ended_sids: [0; 4],
            ended_until_us: [0; 4],
            ended_next: 0,
            issued_token_ids: [None; 4],
            issued_next: 0,
            datagrams: 0,
            dropped: 0,
            bad_auth: 0,
            states: 0,
            injections: 0,
            unknown_usages: 0,
            errors: 0,
            exceptions: 0,
            debug_saves: -1,
            phone_diag_seen: None,
            diag_saves: 0,
            recorder: None,
            link_watch: false,
            phone_list: None,
            settings,
        };
        if inner.settings.diagnostics {
            inner.trace_set_on(true, "on since the last run");
        }
        pad.set_rumble_handler(Some(rumble_handler(&free)));
        for i in 0..inner.settings.paired_phones.len() {
            let p = &inner.settings.paired_phones[i];
            let id: Option<Id> = p.phone_id.as_slice().try_into().ok();
            match (id, inner.protector.unprotect(&p.protected_key)) {
                (Some(id), Some(key)) => {
                    let name = p.name.clone();
                    inner.phones.retain(|c| c.id != id);
                    inner.phones.push(CodePhone { id, name, pair_key: Zeroizing::new(key) });
                }
                _ => {
                    let line = format!("paired phone '{}' key unreadable; pair it again", p.name);
                    inner.log(line);
                }
            }
        }
        let diag = inner.settings.diagnostics;
        let engine = Arc::new_cyclic(|me| HostEngine {
            me: me.clone(),
            inner: Mutex::new(inner),
            free,
            clock: options.clock,
            events: options.events,
            prompt: options.prompt,
            hooks: options.hooks,
            trace,
            shared,
            inline_workers: options.inline_workers,
            rec_out: Mutex::new(Vec::new()),
            diag_applied: Mutex::new(diag),
        });
        // Hands over what the construction queued (unreadable keys, "diagnostics on").
        drop(engine.lock(Role::Ui));
        engine
    }

    /// Takes the engine lock for `role`. When the rx thread had to wait, how long and for whom is kept for the input
    /// log's slow hand-offs.
    fn lock(&self, role: Role) -> Guard<'_> {
        let g = match self.inner.try_lock() {
            Ok(mut g) => {
                if role == Role::Input {
                    g.datagram_wait = (0, None);
                }
                g
            }
            Err(TryLockError::Poisoned(p)) => p.into_inner(),
            Err(TryLockError::WouldBlock) => {
                let by = role_name(self.free.holder.load(Ordering::Relaxed));
                let t0 = self.clock.now_us();
                let mut g = self.inner.lock().unwrap_or_else(|e| e.into_inner());
                if role == Role::Input {
                    let waited = self.clock.now_us().saturating_sub(t0).max(0);
                    g.datagram_wait = (waited, Some(by));
                    // A wait while a STATE is pending holds up its hand-off too.
                    if g.has_pending {
                        g.hand_off.add_lock_wait(waited, Some(by));
                    }
                }
                g
            }
        };
        self.free.holder.store(role as u8, Ordering::Relaxed);
        Guard { e: self, g: Some(g), role }
    }

    fn run_effect(&self, effect: Effect, role: Role) {
        match effect {
            Effect::Log(m) => self.events.log(&m),
            Effect::StateChanged => self.events.state_changed(),
            Effect::Trace(us, kind, text) => self.hooks.debug_line(us, kind, &text),
            Effect::PromptShow(id, name, sas) => self.prompt.show(id, &name, &sas),
            Effect::PromptClose(id) => self.prompt.close(id),
            Effect::DebugSave => self.hooks.debug_save_requested(),
            Effect::PhoneSecond(s) => self.hooks.phone_second(&s),
            Effect::ApplyDiagnostics => {
                // DiagnosticsChanged starts or stops threads (the delivery probe): never on the rx thread.
                if role != Role::Input || self.inline_workers {
                    self.apply_diagnostics();
                } else if !self.free.diag_apply_queued.swap(true, Ordering::SeqCst) {
                    if let Some(me) = self.me.upgrade() {
                        let spawned = std::thread::Builder::new().name("gp-diag-switch".into()).spawn(move || me.apply_diagnostics());
                        if spawned.is_err() {
                            self.free.diag_apply_queued.store(false, Ordering::SeqCst);
                        }
                    }
                }
            }
            Effect::StartPadJob => {
                if let Some(me) = self.me.upgrade() {
                    let spawned = std::thread::Builder::new().name("gp-pad".into()).spawn(move || me.pad_job());
                    if spawned.is_err() {
                        // No thread to be had: the pad stays as it is; the next request tries again.
                        let mut g = self.lock(Role::Pad);
                        g.pad_busy = false;
                        g.free.pad_idle.set();
                        g.log("pad thread could not start");
                    }
                }
            }
            Effect::StartEnroll(serial, save, key) => {
                if let Some(me) = self.me.upgrade() {
                    let (save2, key2) = (save.clone(), key.clone());
                    let spawned = std::thread::Builder::new().name("gp-enroll".into()).spawn(move || me.save_enrolled(serial, save2, key2));
                    if spawned.is_err() {
                        let mut g = self.lock(Role::Ui);
                        if let Some(q) = g.qr.as_mut().filter(|q| q.serial == serial) {
                            q.saving = false; // the phone's next KEY_ENROLL tries again
                        }
                    }
                }
            }
        }
    }

    /// Tells `diagnostics_changed` Diagnostics as it is now; outside the engine lock.
    fn apply_diagnostics(&self) {
        let mut applied = self.diag_applied.lock().unwrap_or_else(|e| e.into_inner());
        self.free.diag_apply_queued.store(false, Ordering::SeqCst);
        let on = self.lock(Role::Ui).settings.diagnostics;
        if on == *applied {
            return;
        }
        *applied = on;
        self.hooks.diagnostics_changed(on);
    }

    /// The pad thread: plugs or unplugs outside the lock until the pad is what was last asked for.
    fn pad_job(&self) {
        let mut done: Option<(Arc<dyn PadSink>, u8)> = None;
        let mut failed: Option<String> = None;
        loop {
            let (retired, pad, want) = {
                let mut g = self.lock(Role::Pad);
                if let Some(f) = failed.take() {
                    g.log(f);
                }
                if let Some((pad, want)) = &done {
                    if Arc::ptr_eq(pad, &g.pad) && *want == g.pad_want && g.pad_retired.is_empty() {
                        g.pad_job_done(false);
                        return;
                    }
                }
                (std::mem::take(&mut g.pad_retired), g.pad.clone(), g.pad_want)
            };
            failed = pad_work(&retired, &pad, want);
            done = Some((pad, want));
        }
    }

    /// The QR enrolment's worker: saves the phone's key file, then authorizes it.
    fn save_enrolled(&self, serial: u64, save: EnrollSave, key: PubKeyFile) {
        let host_key = self.lock(Role::Ui).host_key.clone();
        let enrolled = enroll_work(&save, &key, host_key.as_deref());
        self.lock(Role::Ui).finish_enroll(serial, key, enrolled);
    }

    /// Diagnostics' raw-event side (for the companion's header lines).
    pub fn trace(&self) -> &Trace {
        &self.trace
    }

    /// The memory the companion's side reads.
    pub fn shared(&self) -> &SharedRegion {
        &self.shared
    }

    /// The input log; None = none. Set before `start`.
    pub fn set_recorder(&self, recorder: Option<InputRecorder>) {
        let mut g = self.lock(Role::Ui);
        g.recorder = recorder;
        let writing = g.writing();
        if let Some(r) = g.recorder.as_mut() {
            r.write_lines = writing;
        }
    }

    /// A copy of the settings as they are now.
    pub fn settings(&self) -> HostSettings {
        self.lock(Role::Ui).settings.clone()
    }

    pub fn attach_sender(&self, sender: Arc<dyn DatagramSender>) {
        let mut g = self.lock(Role::Ui);
        g.sender = Some(sender);
        g.keep_awake_dirty = true;
    }

    /// Call once after construction: opens the pairing window on first run when nothing is paired.
    pub fn start(&self, now_us: i64) {
        let mut g = self.lock(Role::Ui);
        if g.phones.is_empty() && g.key_phones.is_empty() {
            g.pairing.open(now_us);
        }
    }

    /// Validates and handles one datagram (SPEC 1.7 order). Control replies go out inline; a STATE is kept for flush.
    pub fn on_datagram(&self, d: &[u8], from: SocketAddr, now_us: i64) {
        let mut g = self.lock(Role::Input);
        g.datagrams += 1;
        if !g.any_datagram {
            g.any_datagram = true;
            g.state_changed();
        }
        g.handle(d, from, now_us);
    }

    /// Injects the newest STATE received since the last flush: pad only when the mapped frame changed, keys and mouse
    /// only with events.
    pub fn flush(&self, now_us: i64) {
        let mut g = self.lock(Role::Input);
        g.flushed_for_motion = false; // the burst ended
        g.flush_locked(now_us, false);
    }

    /// A lost or late stretch of mouse motion is still catching up: `flush` should run within a millisecond, even
    /// without packets. Lock-free.
    pub fn mouse_catching_up(&self) -> bool {
        self.free.catching_up.load(Ordering::Relaxed)
    }

    /// A Link test runs: the input log's lines go to input.log for tools/link-test.sh. Lock-free.
    pub fn link_test_running(&self) -> bool {
        self.free.link_test_until_us.load(Ordering::Relaxed) != i64::MIN
    }

    /// Watchdog tick (every WATCHDOG_TICK_MS): silence release, session drop, pending expiry, pairing timers, 1 Hz
    /// HOST_STATUS.
    pub fn on_tick(&self, now_us: i64) {
        self.lock(Role::Watchdog).tick(now_us);
    }

    /// The Performance page is shown or an FPS overlay is on: the link's seconds are counted for them (nothing is
    /// written without the input log).
    pub fn set_link_watch(&self, on: bool) {
        let mut g = self.lock(Role::Ui);
        if on == g.link_watch {
            return;
        }
        let was = g.rec_on();
        let was_writing = g.writing();
        g.link_watch = on;
        g.recorder_switched(was, was_writing, if on { "link shown" } else { "link no longer shown" });
    }

    /// "Diagnostics": every log at once, told to the phone (whose switch follows) and started at once for a session
    /// already up. Off, all of it stops.
    pub fn set_diagnostics(&self, on: bool) {
        self.lock(Role::Ui).set_diagnostics(on, "switched on the PC");
    }

    /// "Keep" under Diagnostics: how many minutes it keeps and a Save writes.
    pub fn set_diagnostics_minutes(&self, minutes: i32) {
        self.lock(Role::Ui).set_diagnostics_minutes(minutes);
    }

    /// Diagnostics' Save on the PC: tells the phone to save its side (TEST_MODE counter) and saves this side.
    pub fn save_diagnostics(&self) {
        self.lock(Role::Ui).save_diagnostics();
    }

    /// Saves "Read this PC's keys for Press a key"; turned off while the phone listens, the PC stops being read at once.
    pub fn set_press_key_listen(&self, on: bool) {
        let mut g = self.lock(Role::Ui);
        if on == g.settings.press_key_listen {
            return;
        }
        g.settings.press_key_listen = on;
        g.settings_dirty = true;
        if !on {
            g.stop_capture();
        }
    }

    pub fn set_press_key_notice(&self, on: bool) {
        let mut g = self.lock(Role::Ui);
        if on == g.settings.press_key_notice {
            return;
        }
        g.settings.press_key_notice = on;
        g.settings_dirty = true;
    }

    /// Uses `listener` to read this PC's keyboard and mouse for "Press a key" (Windows: raw input).
    pub fn set_key_mouse_listener(&self, listener: Arc<dyn KeyMouseListener>) {
        let mut g = self.lock(Role::Ui);
        let on = g.capture.on();
        if on {
            g.key_listener.stop();
        }
        g.key_listener = listener;
        if on {
            g.key_listener.start();
        }
    }

    /// Uses `pad` as the controller plugged into this PC (Windows: XInput).
    pub fn set_pc_pad(&self, pad: Arc<dyn PcPad>) {
        let mut g = self.lock(Role::Ui);
        let reading = g.pc_reading;
        let capturing = g.capture.on();
        g.stop_pc_pad();
        g.pc_pad.stop();
        g.pc_pad = pad;
        if reading {
            g.start_pc_pad();
        }
        if capturing {
            g.pc_pad.start(g.slot_fn.clone());
        }
    }

    /// The PC keyboard layout's key labels from the watchdog's probe; sent with every HOST_STATUS, at once when they
    /// change. Returns true when they changed (the companion's key names follow).
    pub fn set_key_labels(&self, labels: Option<KeyLabels>) -> bool {
        let Some(labels) = labels else { return false };
        let mut g = self.lock(Role::Probes);
        if g.key_labels.as_ref() == Some(&labels) {
            return false;
        }
        g.key_labels = Some(labels);
        let now = g.clock.now_us();
        g.send_status(now);
        true
    }

    /// Environment flags from the watchdog's probes (taken outside the lock).
    pub fn set_environment(&self, elevated_foreground: bool, network_public: bool) {
        let mut g = self.lock(Role::Probes);
        if elevated_foreground == g.elevated_foreground && network_public == g.network_public {
            return;
        }
        let was_blocked = g.blocked();
        g.elevated_foreground = elevated_foreground;
        g.network_public = network_public;
        if was_blocked && !g.blocked() {
            g.resync_after_block();
        }
        g.state_changed();
    }

    /// The PC's setup bits from the watchdog's probe; sent with the next HOST_STATUS.
    pub fn set_pc_setup(&self, bits: u8) {
        self.lock(Role::Probes).pc_setup = bits & host_status_flags2::SETUP_MASK;
    }

    /// The injected held set into `keys`; returns the mouse buttons and the set's version. Lock-free.
    pub fn held_snapshot(&self, keys: &mut [u8; KEY_BITMAP_SIZE]) -> (u8, i32) {
        self.free.injected.read(keys)
    }

    /// The settings as JSON if they changed (written by the caller). The JSON is made after the lock is let go, so
    /// a slow serialization never holds up input.
    pub fn take_dirty_settings(&self) -> Option<String> {
        let copy = {
            let mut g = self.lock(Role::Settings);
            if !g.settings_dirty {
                return None;
            }
            g.settings_dirty = false;
            g.settings.clone()
        };
        Some(copy.serialize())
    }

    /// A write of what `take_dirty_settings` returned failed: it is written again later.
    pub fn mark_settings_dirty(&self) {
        self.lock(Role::Settings).settings_dirty = true;
    }

    pub fn answer_pair(&self, attempt_id: u32, allow: bool) {
        let mut g = self.lock(Role::Ui);
        let now = g.clock.now_us();
        g.pairing.answer(attempt_id, allow, now);
        g.pair_events();
        g.state_changed();
    }

    /// Shows a QR code: returns its one-time token (None without a host key). The first phone that sends KEY_ENROLL
    /// with it is authorized: `save` writes its key file. Lasts QR_PAIRING_S or until `close_qr_pairing`; opening
    /// again replaces the token.
    pub fn open_qr_pairing(&self, save: EnrollSave) -> Option<[u8; wire::QR_TOKEN_SIZE]> {
        let mut g = self.lock(Role::Ui);
        g.host_key.as_ref()?;
        let token = qr_pairing::new_token();
        let token_id = qr_pairing::token_id(&token);
        g.qr_serial += 1;
        let now = g.clock.now_us();
        g.qr = Some(QrSession {
            serial: g.qr_serial,
            token: Zeroizing::new(token),
            token_id,
            expires_us: now.saturating_add(wire::QR_PAIRING_S * 1_000_000),
            save,
            enrolled_key: None,
            saving: false,
            enrolled_name: None,
        });
        let at = g.issued_next;
        g.issued_token_ids[at] = Some(token_id);
        g.issued_next = (at + 1) % g.issued_token_ids.len();
        g.state_changed();
        Some(token)
    }

    pub fn close_qr_pairing(&self) {
        let mut g = self.lock(Role::Ui);
        g.qr = None;
        g.state_changed();
    }

    /// The host key, when it could not be read at start-up (host.key held by another program) and a later try got
    /// it: key file and QR code pairing start working. Ignored once there is a key.
    pub fn set_host_key(&self, key: P256) -> bool {
        let mut g = self.lock(Role::Ui);
        if g.host_key.is_some() {
            return false;
        }
        g.host_key_id = Some(key_pairing::key_id(key.public_key()));
        g.host_key_fingerprint = Some(key_pairing::fingerprint(key.public_key()));
        g.host_key = Some(Arc::new(key));
        g.state_changed();
        true
    }

    /// This host's public key, for the QR code (None without key pairing).
    pub fn host_public_key(&self) -> Option<PublicKey> {
        self.lock(Role::Ui).host_key.as_ref().map(|k| *k.public_key())
    }

    pub fn open_pairing_window(&self) {
        let mut g = self.lock(Role::Ui);
        let now = g.clock.now_us();
        g.pairing.open(now);
        g.state_changed();
    }

    pub fn close_pairing_window(&self) {
        let mut g = self.lock(Role::Ui);
        g.pairing.close();
        g.pair_events();
        g.state_changed();
    }

    pub fn set_output_mode(&self, mode: u8) {
        let mut g = self.lock(Role::Ui);
        if mode == g.settings.output_mode {
            return;
        }
        g.release_all_locked();
        g.settings.output_mode = mode;
        g.settings_dirty = true;
        // The old pad goes and, with a session, the new one comes, on the pad thread: the menu never waits.
        if g.sessions.active().is_some() {
            g.plug_pad();
        } else {
            g.request_pad(output_mode::NONE);
        }
        g.state_changed();
    }

    /// "Start with Windows" preference; the caller updates the HKCU Run entry.
    pub fn set_start_with_windows(&self, enabled: bool) {
        let mut g = self.lock(Role::Ui);
        if enabled == g.settings.start_with_windows {
            return;
        }
        g.settings.start_with_windows = enabled;
        g.settings_dirty = true;
    }

    /// "Catch-up time" (ms; 0 = off): the longest a lost or late stretch of mouse motion is spread over.
    pub fn set_catch_up_ms(&self, ms: i32) {
        let mut g = self.lock(Role::Ui);
        let ms = settings::clamp_catch_up_ms(ms);
        if ms == g.settings.catch_up_ms {
            return;
        }
        g.settings.catch_up_ms = ms;
        g.catch_up.max_us = ms as i64 * 1000;
        g.settings_dirty = true;
    }

    /// "Use input driver" (Performance): saves it and lets `apply` switch the keyboard and mouse route, under the
    /// engine lock and after letting go of every key and button on the route it leaves; the next STATE presses what
    /// is held again on the new one. `apply` must not call the engine.
    pub fn set_use_input_driver(&self, on: bool, apply: impl FnOnce(bool)) {
        let mut g = self.lock(Role::Ui);
        if on != g.settings.use_input_driver {
            g.settings.use_input_driver = on;
            g.settings_dirty = true;
        }
        g.change_key_mouse_route(|| apply(on), if on { "Use input driver on" } else { "Use input driver off" });
    }

    /// Changes the keyboard and mouse route (the input driver found or lost) under the engine lock, letting go of
    /// everything first. `change` must not call the engine.
    pub fn change_key_mouse_route(&self, change: impl FnOnce(), why: &str) {
        self.lock(Role::Ui).change_key_mouse_route(change, why);
    }

    pub fn set_usb_direct(&self, on: bool) {
        let mut g = self.lock(Role::Ui);
        if on == g.settings.usb_direct {
            return;
        }
        g.settings.usb_direct = on;
        g.settings_dirty = true;
        // Tell the phone at once (HOST_STATUS UsbDirect): turned on, it tries the cable now.
        let now = g.clock.now_us();
        g.send_status(now);
    }

    /// Replaces the pad sink ("Retry driver" after installing a driver). The engine owns the old one: the pad
    /// thread unplugs and drops it.
    pub fn set_pad_sink(&self, pad: Arc<dyn PadSink>) {
        let mut g = self.lock(Role::Ui);
        g.pad.set_rumble_handler(None);
        let old = std::mem::replace(&mut g.pad, pad.clone());
        g.pad_retired.push(old);
        *g.free.pad_now.lock().unwrap_or_else(|e| e.into_inner()) = pad.clone();
        pad.set_rumble_handler(Some(rumble_handler(&g.free)));
        g.pad_frame_valid = false;
        let want = if g.sessions.active().is_some() { g.settings.output_mode } else { output_mode::NONE };
        g.request_pad(want);
        g.state_changed();
    }

    /// The connected phone's address as the PC sees it (loopback over the direct USB cable), or None.
    pub fn phone_address(&self) -> Option<IpAddr> {
        self.lock(Role::Ui).sessions.active().map(|a| a.endpoint().ip())
    }

    /// Buzzes and beeps the connected phone once, whatever the output mode. Sent twice against a lost packet; the
    /// phone plays one buzz per second at most.
    pub fn buzz(&self) {
        let g = self.lock(Role::Ui);
        let mut p = [0u8; wire::RUMBLE_PLAIN];
        codec::encode_rumble(Rumble { large: 0, small: 0, pattern: rumble_pattern::BUZZ }, &mut p);
        for _ in 0..2 {
            g.send_active(packet::RUMBLE, &p);
        }
    }

    /// Sends KEEP_AWAKE to the active session (the keep-awake thread, every KEEP_AWAKE_MS): a Wi-Fi client in power
    /// save wakes only at each beacon, so without it the phone's packets arrive in 100 ms batches while it sends
    /// little. Skipped over USB, Ethernet and loopback, and where the PC's own adapter toward the phone is no Wi-Fi
    /// client. Takes no engine lock, so it never holds up input.
    pub fn keep_awake(&self) -> bool {
        let (tx, sender) = {
            let k = self.free.keep_awake.lock().unwrap_or_else(|e| e.into_inner());
            let Some(tx) = &k.tx else { return false };
            let to = tx.endpoint();
            if !keep_awake_wanted(k.link.as_ref(), to, self.pc_wifi_client_for(to)) {
                return false;
            }
            (tx.clone(), k.sender.clone())
        };
        tx.send_sealed(packet::KEEP_AWAKE, &[0u8; wire::KEEP_AWAKE_PLAIN], sender.as_deref());
        true
    }

    /// The keep-awake thread's rest after `keep_awake` sent nothing: no 100 Hz timer while no phone is connected, or
    /// over a wire. Woken when a session starts or a LINK_INFO comes; else it looks again after
    /// KEEP_AWAKE_IDLE_WAIT_MS.
    pub fn rest_keep_awake(&self) {
        self.free.keep_awake_wake.reset();
        {
            let k = self.free.keep_awake.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(tx) = &k.tx {
                let to = tx.endpoint();
                if keep_awake_wanted(k.link.as_ref(), to, self.pc_wifi_client_for(to)) {
                    return;
                }
            }
        }
        self.free.keep_awake_wake.wait(KEEP_AWAKE_IDLE_WAIT_MS);
    }

    /// Nothing on the PC needs the watchdog's 50 ms tick: no session, pending session, pairing or QR code, Link
    /// test, "Press a key", held input or dropped ups. Set by each tick.
    pub fn watchdog_idle(&self) -> bool {
        self.free.watchdog_idle.load(Ordering::Relaxed)
    }

    /// The watchdog's rest while idle, up to WATCHDOG_IDLE_TICK_MS; a new session ends it at once.
    pub fn rest_watchdog(&self) {
        self.free.tick_wake.reset();
        if !self.watchdog_idle() {
            return;
        }
        self.free.tick_wake.wait(WATCHDOG_IDLE_TICK_MS);
    }

    /// Ends both rests (a session started, the link changed, or the threads stop).
    pub fn wake_timers(&self) {
        self.free.wake_timers();
    }

    /// The PC's adapter on `phone`'s subnet, from the watchdog's probe (every second; `wifi_client` None: not
    /// found). It holds only for that address: a session from another address is "not known" again until the next
    /// probe.
    pub fn set_pc_adapter(&self, phone: Option<IpAddr>, wifi_client: Option<bool>) {
        let side = match (phone, wifi_client) {
            (Some(IpAddr::V4(ip)), Some(wifi)) => PC_SIDE_KNOWN | if wifi { PC_SIDE_WIFI } else { 0 } | u32::from(ip) as u64,
            _ => 0,
        };
        let was = self.free.pc_side.swap(side, Ordering::Relaxed);
        let known_not_wifi = |v: u64| v & PC_SIDE_KNOWN != 0 && v & PC_SIDE_WIFI == 0;
        if !known_not_wifi(side) && known_not_wifi(was) {
            self.free.keep_awake_wake.set();
        }
    }

    /// Whether the PC reaches `to` as a Wi-Fi client; None while not known for that address.
    fn pc_wifi_client_for(&self, to: SocketAddr) -> Option<bool> {
        let v = self.free.pc_side.load(Ordering::Relaxed);
        match to {
            SocketAddr::V4(a) if v & PC_SIDE_KNOWN != 0 && u32::from(*a.ip()) == v as u32 => Some(v & PC_SIDE_WIFI != 0),
            _ => None,
        }
    }

    /// "Pause input": releases everything; STATEs keep updating the baseline but inject nothing.
    pub fn set_paused(&self, paused: bool) {
        let mut g = self.lock(Role::Ui);
        if paused == g.paused {
            return;
        }
        g.paused = paused;
        if paused {
            g.release_all_locked();
        }
        g.state_changed();
    }

    /// Workstation locked or suspending: behaves like Pause and is reported as INPUT_PAUSED.
    pub fn set_locked(&self, locked: bool) {
        let mut g = self.lock(Role::Ui);
        if locked == g.locked {
            return;
        }
        let was_blocked = g.blocked();
        g.locked = locked;
        if locked {
            g.release_all_locked();
        } else if was_blocked && !g.blocked() {
            g.resync_after_block();
        }
        g.state_changed();
    }

    /// The companion's overlays show the phone's pose: HOST_STATUS asks the phone for its pose, layout and presses
    /// (told at once), with its compass readings and each gyro sample when they want those, and they go into the
    /// overlay ring. Off: the phone stops sending them.
    pub fn set_overlay(&self, on: bool, compass: bool, samples: bool, keys: bool) {
        let mut g = self.lock(Role::Ui);
        let (compass, samples, keys) = (compass && on, samples && on, keys && on);
        if keys != g.overlay_keys {
            // TEST_MODE carries it (HOST_STATUS has no room): told at once, an "off" a few times.
            g.overlay_keys = keys;
            g.test_mode_changed();
        }
        if on == g.overlay_on && compass == g.overlay_compass && samples == g.overlay_samples {
            return;
        }
        let now = g.clock.now_us();
        if on != g.overlay_on {
            g.overlay_inbox.mark(OverlayKind::Clear, now, !on);
            g.overlay_queued = true;
        }
        g.overlay_on = on;
        g.overlay_compass = compass;
        g.overlay_samples = samples;
        g.send_status(now);
    }

    /// "Measure buttons": the phone sends PRESS_TIME for each press and release (told at once), and
    /// `press_latency_now` has the newest presses. Off: the phone stops and they are forgotten.
    pub fn set_measure_buttons(&self, on: bool) {
        let mut g = self.lock(Role::Ui);
        if on == g.settings.measure_buttons {
            return;
        }
        g.settings.measure_buttons = on;
        g.settings_dirty = true;
        g.press.reset();
        g.test_mode_changed();
    }

    /// A Link test runs.
    pub fn link_testing(&self) -> bool {
        self.lock(Role::Ui).link_test_until_us != i64::MIN
    }

    /// Starts a Link test for `seconds` (0 stops one): the phone sends fake moving input at its full rate, which the
    /// input log counts like real input but which is never injected.
    pub fn set_link_test(&self, seconds: i32, why: &str) {
        self.lock(Role::Ui).set_link_test(seconds, why);
    }

    /// "Measure buttons": the middle of the last presses, how many, and the newest; None while off or before any.
    pub fn press_latency_now(&self) -> Option<ButtonLatency> {
        let g = self.lock(Role::Ui);
        if !g.settings.measure_buttons {
            return None;
        }
        let (total_us, phone_us) = g.press.median()?;
        Some(ButtonLatency { total_us, phone_us, count: g.press.count(), last_us: g.press.last_total_us() })
    }

    /// Tells each mouse move's hand-off to `EngineHooks::move_sent` (the delivery probe runs) or stops telling.
    pub fn set_move_watch(&self, on: bool) {
        self.free.move_watch.store(on, Ordering::Relaxed);
    }

    /// Release-all for external triggers (session switch, suspend, exit).
    pub fn release_all(&self) {
        self.lock(Role::Ui).release_all_locked();
    }

    /// Forgets a code-paired phone; false when it is not one.
    pub fn forget(&self, phone_id: &[u8]) -> bool {
        let Ok(id) = Id::try_from(phone_id) else { return false };
        let mut g = self.lock(Role::Ui);
        let Some(at) = g.phones.iter().position(|p| p.id == id) else { return false };
        g.phones.remove(at);
        g.settings.paired_phones.retain(|p| p.phone_id != id);
        g.settings_dirty = true;
        g.phone_list = None;
        g.sessions.remove_pending(&id);
        if g.sessions.active().is_some_and(|a| a.same_phone(&id)) {
            g.end_active(Some(host_bye_reason::FORGOTTEN), "ended");
        }
        g.forgot_pad_phone(&id);
        g.state_changed();
        true
    }

    /// The phone keys in authorized_phones (from the folder watcher). New keys are paired at once; a phone whose
    /// file is gone is dropped, live session included.
    pub fn set_authorized_phones(&self, phones: Vec<AuthorizedPhone>) {
        self.lock(Role::Ui).set_authorized_phones(phones, None);
    }

    /// Exit: release-all, then HOST_BYE(EXIT) and unplug (waits up to 5 s for the pad thread).
    pub fn shutdown(&self) {
        {
            let mut g = self.lock(Role::Ui);
            g.release_all_locked();
            if g.sessions.active().is_some() {
                g.end_active(Some(host_bye_reason::EXIT), "ended");
            }
            g.request_pad(output_mode::NONE);
        }
        self.free.pad_idle.wait(5000);
    }

    /// A session is active. Lock-free, so cheap enough for every overlay frame, unlike `snapshot`.
    pub fn connected(&self) -> bool {
        self.shared.held().connected()
    }

    /// The phone's "Press a key" is open. Lock-free.
    pub fn capturing(&self) -> bool {
        self.free.capture_on.load(Ordering::Relaxed)
    }

    /// What the tray and the main window show.
    pub fn snapshot(&self) -> HostSnapshot {
        let (mut snap, pad_gyro, pc_pad) = {
            let mut g = self.lock(Role::Ui);
            let now = g.clock.now_us();
            let a = g.sessions.active();
            let phone_name = a.map(|a| a.phone_name.clone());
            let phone_address = a.map(|a| a.endpoint().ip().to_string());
            let connected = a.is_some();
            let phones = match &g.phone_list {
                Some(p) => p.clone(),
                None => {
                    let list = Arc::new(g.paired_phone_list());
                    g.phone_list = Some(list.clone());
                    list
                }
            };
            let snap = HostSnapshot {
                phone_name,
                connected,
                silent: g.released_for_silence,
                output_mode: g.settings.output_mode,
                driver_ok: g.pad.driver_ok(),
                pad_plugged: g.pad_out() != output_mode::NONE,
                paused: g.paused || g.locked,
                elevated_foreground: g.elevated_foreground || g.inject_failed,
                network_public: g.network_public,
                pairing_window_s: g.pairing.window_remaining_s(now),
                pair_prompt: g.pairing.prompt_info().map(|(id, name, sas)| (id, name.to_owned(), sas.to_owned())),
                rx_per_sec: g.stats.rx_per_sec,
                loss_permille: g.stats.loss_permille,
                lat_p50_us: g.stats.lat_p50_us,
                lat_p99_us: g.stats.lat_p99_us,
                jitter_us: g.stats.jitter_us,
                phone_link: g.stats.last_link_info,
                any_datagram_seen: g.any_datagram,
                paired_phones: phones,
                counters: HostCounters {
                    datagrams: g.datagrams,
                    dropped: g.dropped,
                    bad_auth: g.bad_auth,
                    states: g.states,
                    injections: g.injections,
                    unknown_usages: g.unknown_usages,
                    errors: g.errors,
                    exceptions: g.exceptions,
                },
                host_key_fingerprint: g.host_key_fingerprint.clone(),
                qr_pairing_s: match &g.qr {
                    Some(q) if now < q.expires_us => ((q.expires_us - now).saturating_add(999_999) / 1_000_000) as i32,
                    _ => 0,
                },
                qr_enrolled_phone: g.qr.as_ref().and_then(|q| q.enrolled_name.clone()),
                pc_controller: None,
                dual_sense_ok: g.pad.dual_sense_ok(),
                capturing: g.capture.on(),
                phone_address,
            };
            (snap, g.pad_gyro.enabled, g.pc_pad.clone())
        };
        // The controller's name is asked outside the lock.
        if pad_gyro {
            snap.pc_controller = Some(pc_pad.name().unwrap_or_default());
        }
        snap
    }
}

fn rumble_handler(free: &Arc<Free>) -> Arc<dyn Fn(u8, u8) + Send + Sync> {
    let free = free.clone();
    // The driver's thread only records the newest value: it never takes the engine lock, so it can never stall
    // behind (or deadlock with) a report submitted under it.
    Arc::new(move |large, small| {
        free.rumble_next.swap(RUMBLE_DIRTY | ((large as i32) << 8) | small as i32, Ordering::SeqCst);
    })
}

/// Unplugs the retired sinks, then makes `pad` what is wanted. Returns what failed, for the log.
fn pad_work(retired: &[Arc<dyn PadSink>], pad: &Arc<dyn PadSink>, want: u8) -> Option<String> {
    let mut failed = None;
    for r in retired {
        if let Err(e) = r.unplug() {
            failed = Some(format!("old pad unplug failed: {e}"));
        }
    }
    if want == output_mode::NONE {
        if let Err(e) = pad.unplug() {
            failed = Some(format!("pad unplug failed: {e}"));
        }
    } else if pad.plugged() != want {
        match pad.plug(want) {
            Ok(true) => {}
            Ok(false) => failed = Some(format!("could not plug {} pad", mode_name(want))),
            Err(e) => failed = Some(format!("pad plug failed: {e}")),
        }
    }
    failed
}

/// Saves the key file and works out the phone's secrets: everything a QR enrolment does outside the lock.
fn enroll_work(save: &EnrollSave, key: &PubKeyFile, host_key: Option<&P256>) -> Enrolled {
    match save(key) {
        Err(e) => Enrolled { file: None, failed: Some(e), secrets: None },
        Ok(None) => Enrolled { file: None, failed: None, secrets: None },
        Ok(Some(file)) => {
            // The ECDH too, before the lock (the host key, once set, never changes).
            let secrets = host_key.and_then(|h| key_pairing::host_secrets(h, &key.public_key));
            Enrolled { file: Some(file), failed: None, secrets }
        }
    }
}

/// False over a wire (the phone's LINK_INFO says USB or Ethernet), loopback (USB direct), or when the PC's own
/// adapter toward the phone is known to be no Wi-Fi client (a cable, or the PC's hotspot); true otherwise, also
/// before the first LINK_INFO and while the PC's adapter isn't known.
pub fn keep_awake_wanted(link: Option<&LinkInfo>, to: SocketAddr, pc_wifi_client: Option<bool>) -> bool {
    if pc_wifi_client == Some(false) {
        return false;
    }
    if let Some(l) = link {
        if l.flags & (link_info_flags::USB | link_info_flags::ETHERNET) != 0
            || matches!(l.path, link_path::USB_TETHER | link_path::USB_DIRECT | link_path::ETHERNET)
        {
            return false;
        }
    }
    !is_loopback(to)
}

fn is_loopback(a: SocketAddr) -> bool {
    match a {
        SocketAddr::V4(v4) => v4.ip().octets()[0] == 127,
        SocketAddr::V6(v6) => {
            // ::1, or ::ffff:127.x.x.x
            let b = v6.ip().octets();
            if b[..10].iter().any(|&x| x != 0) {
                return false;
            }
            if b[10] == 0 && b[11] == 0 {
                return b[12] == 0 && b[13] == 0 && b[14] == 0 && b[15] == 1;
            }
            b[10] == 0xFF && b[11] == 0xFF && b[12] == 127
        }
    }
}

/// A control packet only a program on this PC may send (tools/link-test.sh, tools/gp-log.sh).
fn from_this_pc(from: SocketAddr) -> bool {
    matches!(from, SocketAddr::V4(v4) if v4.ip().octets()[0] == 127)
}

impl Free {
    fn wake_timers(&self) {
        self.watchdog_idle.store(false, Ordering::Relaxed);
        self.keep_awake_wake.set();
        self.tick_wake.set();
    }
}

/// Discrete input differs: what one injection per burst could lose (a press and its release).
fn edges_differ(a: &StateFrame, b: &StateFrame) -> bool {
    a.buttons != b.buttons || a.hat != b.hat || a.mouse_buttons != b.mouse_buttons || a.keys != b.keys
}

// =====================================================================================================================
// Under the lock
// =====================================================================================================================

impl Inner {
    fn log(&mut self, message: impl Into<String>) {
        let message = message.into();
        if self.trace.enabled() {
            self.effects.push(Effect::Trace(self.clock.now_us(), TraceKind::State, message.clone()));
        }
        self.effects.push(Effect::Log(message));
    }

    fn state_changed(&mut self) {
        if !matches!(self.effects.last(), Some(Effect::StateChanged)) {
            self.effects.push(Effect::StateChanged);
        }
    }

    fn trace_line(&mut self, kind: TraceKind, text: String, us: i64) {
        self.effects.push(Effect::Trace(us, kind, text));
    }

    /// Diagnostics switched: the companion keeps the log and drains the queue.
    fn trace_set_on(&mut self, on: bool, why: &str) {
        if on == self.trace.enabled() {
            return;
        }
        self.trace.on.store(on, Ordering::Relaxed);
        if on {
            let now = self.clock.now_us();
            self.trace_line(TraceKind::Note, format!("diagnostics on ({why})"), now);
        }
    }

    /// The input log's lines are made: for Diagnostics, or for a Link test's input.log.
    fn writing(&self) -> bool {
        self.settings.diagnostics || self.link_test_until_us != i64::MIN
    }

    /// The recorder counts the link's seconds only when something needs them: Diagnostics, a Link test, or the
    /// Performance page or an FPS overlay showing the link.
    fn rec_on(&self) -> bool {
        self.writing() || self.link_watch
    }

    fn rec(&mut self) -> Option<&mut InputRecorder> {
        if self.rec_on() {
            self.recorder.as_mut()
        } else {
            None
        }
    }

    /// After a switch that feeds `rec_on` changed: starts or ends the recorder's session, and writes lines only with
    /// the input log.
    fn recorder_switched(&mut self, was_on: bool, was_writing: bool, why: &str) {
        let now = self.clock.now_us();
        let on = self.rec_on();
        let writing = self.writing();
        let phone = self.sessions.active().map(|a| a.phone_name.clone());
        let Some(r) = self.recorder.as_mut() else { return };
        if was_on && (!on || was_writing != writing) {
            r.session_ended(why, now);
        }
        r.write_lines = writing;
        if on && (!was_on || was_writing != writing) {
            if let Some(phone) = phone {
                r.session_started(&phone, now);
            }
        }
    }

    /// Copies what changed into the lock-free views (as their one writer). The copies wait while a STATE is
    /// pending: the flush right after the burst publishes them once the input is handed off. True: wake the overlays.
    fn publish_view(&mut self) -> bool {
        let connected = self.sessions.active().is_some();
        if self.connected_shown != connected {
            self.connected_shown = connected;
            self.held.set_connected(connected);
        }
        if self.pad_gyro_shown != self.pad_gyro.enabled {
            self.pad_gyro_shown = self.pad_gyro.enabled;
            self.held.set_pad_gyro(self.pad_gyro_shown);
        }
        let catching = self.catch_up.catching_up();
        if self.free.catching_up.load(Ordering::Relaxed) != catching {
            self.free.catching_up.store(catching, Ordering::Relaxed);
        }
        let capturing = self.capture.on();
        if self.free.capture_on.load(Ordering::Relaxed) != capturing {
            self.free.capture_on.store(capturing, Ordering::Relaxed);
        }
        if self.keep_awake_dirty {
            self.keep_awake_dirty = false;
            let mut k = self.free.keep_awake.lock().unwrap_or_else(|e| e.into_inner());
            k.tx = self.sessions.active().map(|a| a.sender().clone());
            k.link = self.stats.last_link_info;
            k.sender = self.sender.clone();
        }
        if self.has_pending {
            return false;
        }
        if self.held_version != self.free.injected.version() {
            self.free.injected.publish(&self.injected_keys, self.injected_mouse, self.held_version);
        }
        // What a pointer's Fire and Aim displays read: after the hand-off (or a release that dropped the pending
        // STATE, such as the release after silence), so it never delays it.
        if self.held_outputs_dirty {
            self.held_outputs_dirty = false;
            self.held.publish_held_outputs(&self.pending);
        }
        std::mem::take(&mut self.overlay_queued)
    }

    fn reply(&self, datagram: &[u8], to: SocketAddr) {
        if let Some(s) = &self.sender {
            s.send(datagram, to);
        }
    }

    fn send_active(&self, ptype: u8, plain: &[u8]) {
        if let Some(a) = self.sessions.active() {
            a.send_sealed(ptype, plain, self.sender.as_deref());
        }
    }

    // ---- rx path ---------------------------------------------------------------------------------------------

    fn handle(&mut self, d: &[u8], from: SocketAddr, now_us: i64) {
        if d.len() < wire::HEADER || d[0] != wire::MAGIC0 || d[1] != wire::MAGIC1 {
            self.dropped += 1;
            return;
        }
        if d[2] != wire::VERSION {
            self.dropped += 1;
            self.send_error(error_code::VERSION, d.len(), from, now_us);
            return;
        }
        let ptype = d[3];
        if packet::size(ptype) != Some(d.len()) || d.len() > wire::MAX_DATAGRAM {
            self.dropped += 1;
            return;
        }
        let mut reply = [0u8; wire::MAX_DATAGRAM];

        match ptype {
            packet::DISCOVER => {
                if let Some(disc) = codec::decode_discover(d) {
                    let n = self.announce(&disc, now_us, &mut reply);
                    self.reply(&reply[..n], from);
                }
                return;
            }
            packet::PAIR_START => {
                match codec::decode_pair_start(d) {
                    Some(m) => {
                        let n = self.pairing.on_start(&m, &self.settings.host_id, &self.settings.host_name, now_us, &mut reply, ipv4_key(from));
                        self.reply_pairing(&reply[..n], from);
                    }
                    None => self.dropped += 1,
                }
                return;
            }
            packet::PAIR_REVEAL => {
                if let Some(m) = codec::decode_pair_reveal(d) {
                    let n = self.pairing.on_reveal(&m, now_us, &mut reply);
                    self.reply_pairing(&reply[..n], from);
                }
                return;
            }
            packet::PAIR_CONFIRM => {
                if let Some(m) = codec::decode_pair_confirm(d) {
                    let n = self.pairing.on_confirm(&m, now_us, &mut reply);
                    self.reply_pairing(&reply[..n], from);
                }
                return;
            }
            packet::HELLO => {
                self.on_hello(d, from, now_us);
                return;
            }
            packet::LINK_TEST_CTL => {
                // Only a program on this PC may start one (tools/link-test.sh); anything else is dropped.
                if from_this_pc(from) {
                    self.set_link_test(le::u16(d, wire::HEADER) as i32, "asked on this PC");
                } else {
                    self.dropped += 1;
                }
                return;
            }
            packet::LOG_CTL => {
                // Diagnostics' switches, for an agent on this PC (tools/gp-log.sh); loopback only.
                if !from_this_pc(from) {
                    self.dropped += 1;
                    return;
                }
                match d[12] {
                    wire::LOG_CTL_DIAGNOSTICS => self.set_diagnostics(d[13] != 0, "tools/gp-log.sh on this PC"),
                    wire::LOG_CTL_SAVE => self.save_diagnostics(),
                    wire::LOG_CTL_KEEP => self.set_diagnostics_minutes(d[13] as i32),
                    _ => self.dropped += 1,
                }
                return;
            }
            packet::KEY_ENROLL => {
                match codec::decode_key_enroll(d) {
                    Some(e) => self.on_key_enroll(d, &e, from, now_us),
                    None => self.dropped += 1,
                }
                return;
            }
            _ => {}
        }

        if !packet::is_phone_to_host_sealed(ptype) {
            self.dropped += 1; // host-to-phone types are never accepted here
            return;
        }
        let Some(plain_len) = packet::plain_size(ptype) else {
            self.dropped += 1;
            return;
        };

        let sid = le::u32(d, 4);
        let mut plain_buf = [0u8; wire::MAX_DATAGRAM];
        let promoted = {
            let Some(s) = self.sessions.find_mut(sid) else {
                self.dropped += 1;
                // A BYE needs no answer, and the trailing copies of a just-ended session are expected.
                if ptype == packet::BYE || self.recently_ended(sid, now_us) {
                    return;
                }
                self.send_error(error_code::SESSION_UNKNOWN, d.len(), from, now_us);
                return;
            };
            let moved = !s.is_endpoint(from);
            if moved && !self.foreign_open_limiter.try_acquire_from(from, now_us) {
                self.bad_auth += 1; // over the limit for packets from elsewhere: dropped before the decrypt
                return;
            }
            if s.try_open(d, &mut plain_buf) != Some(plain_len) {
                self.bad_auth += 1; // stale seq or failed tag: silence
                return;
            }
            if moved {
                s.update_endpoint(from);
                self.keep_awake_dirty = true;
            }
            s.last_rx_us = now_us;
            s.promoted
        };
        let seq = le::u32(d, 8);
        let plain = &plain_buf[..plain_len];

        if !promoted {
            if !matches!(ptype, packet::STATE | packet::STATE_HIRES | packet::STATE_GYRO) {
                self.handle_pending_control(sid, ptype, plain, now_us);
                return;
            }
            self.promote(sid, now_us);
        }

        let lost_before = self.stats.total_lost;
        self.stats.on_sealed(seq);
        let lost = self.stats.total_lost.wrapping_sub(lost_before);
        if lost != 0 {
            if let Some(r) = self.rec() {
                r.lost(lost, seq, now_us);
            }
        }
        match ptype {
            packet::STATE | packet::STATE_HIRES | packet::STATE_GYRO => self.on_state(plain, now_us),
            packet::PAD_GYRO => self.on_pad_gyro(plain),
            packet::PAD_GYRO_SET => self.pad_gyro.decode_held(plain),
            packet::PING => self.send_pong(sid, plain, now_us),
            packet::LINK_INFO => {
                let info = codec::decode_link_info(plain);
                self.stats.on_link_info(&info);
                self.keep_awake_dirty = true;
                self.free.keep_awake_wake.set(); // the path may have changed between Wi-Fi and a wire
                self.on_debug_flags(info.flags, now_us);
            }
            // Overlay data: copied as it came for the companion's overlay side, which does the rest.
            packet::OVERLAY => {
                if self.overlay_on {
                    let rx_ms = self.clock.tick_ms();
                    if self.pad_gyro.enabled {
                        self.gate_overlay(plain, now_us, rx_ms);
                    } else {
                        self.overlay_inbox.put(OverlayKind::Overlay, plain, now_us, rx_ms);
                    }
                    self.overlay_queued = true;
                }
            }
            packet::OVERLAY_SAMPLES | packet::OVERLAY_LAYOUT | packet::OVERLAY_FRAMES => {
                if self.overlay_on {
                    let kind = match ptype {
                        packet::OVERLAY_SAMPLES => OverlayKind::Samples,
                        packet::OVERLAY_LAYOUT => OverlayKind::Layout,
                        _ => OverlayKind::Frames,
                    };
                    self.overlay_inbox.put(kind, plain, now_us, self.clock.tick_ms());
                    self.overlay_queued = true;
                }
            }
            packet::MOTION => self.on_motion(plain, now_us),
            packet::CAPTURE => {
                // "Read this PC's keys for Press a key" off: the PC is never read, and the phone hears nothing back.
                if self.settings.press_key_listen && self.capture.on_capture(plain[0], now_us) {
                    self.capture_changed();
                }
            }
            packet::PRESS_TIME => {
                if self.settings.measure_buttons {
                    let (offset, have) = self.clock_offset();
                    self.press.on_press_time(codec::decode_press_time(plain), now_us, offset, have);
                }
            }
            packet::PHONE_STATS => {
                if self.settings.diagnostics {
                    let s = self.to_phone_second(&codec::decode_phone_stats(plain), now_us);
                    self.effects.push(Effect::PhoneSecond(s));
                }
            }
            packet::BYE => {
                self.log(format!("phone said bye (reason {})", plain[0]));
                self.remember_ended(sid);
                self.end_active(None, &format!("phone said bye ({})", plain[0]));
            }
            _ => {}
        }
    }

    /// (the phone's clock offset, whether it is known yet).
    fn clock_offset(&self) -> (i64, bool) {
        match &self.stats.last_link_info {
            Some(l) => (l.clock_offset_us, true),
            None => (0, false),
        }
    }

    /// PHONE_STATS on the engine's clock: its end is placed by the phone's time when the clock offset is known, so a
    /// late report keeps its second.
    fn to_phone_second(&self, p: &PhoneStats, now_us: i64) -> PhoneSecond {
        let mut at = now_us;
        let (offset, have) = self.clock_offset();
        if have {
            // How long ago the phone ended that second (its clock's low 32 bits wrap every ~71 min).
            let age = (now_us.wrapping_sub(offset) as u32).wrapping_sub(p.time_us);
            if age < 10_000_000 {
                at = now_us - age as i64;
            }
        }
        PhoneSecond {
            at_us: at,
            states: p.states as i32,
            packets: p.packets as i32,
            failed: p.failed as i32,
            slow_sends: p.slow_sends as i32,
            send_max_ms: p.send_max_us as f32 / 1000.0,
            wake_late_ms: p.wake_late_max_us as f32 / 1000.0,
            gap_max_ms: p.state_gap_max_us as f32 / 1000.0,
            phone_tx: p.phone_tx_packets as i64,
        }
    }

    /// PAD_GYRO: which controller buttons ratchet or toggle the gyro; reads the PC controller while the phone's
    /// switch is on.
    fn on_pad_gyro(&mut self, plain: &[u8]) {
        let was = self.pad_gyro.enabled;
        self.pad_gyro.decode(plain);
        if self.pad_gyro.enabled == was {
            return;
        }
        if self.pad_gyro.enabled {
            self.start_pc_pad();
        } else {
            self.stop_pc_pad();
            self.gate.clear_pc(); // off: no PC toggle or ratchet is left turning the gyro over
        }
        self.state_changed();
    }

    fn start_pc_pad(&mut self) {
        if self.pc_reading {
            return;
        }
        self.pc_reading = true;
        self.pc_pad.start(self.slot_fn.clone());
        self.log("PC controller for gyro ratchet: on");
    }

    fn stop_pc_pad(&mut self) {
        if !self.pc_reading {
            return;
        }
        self.pc_reading = false;
        if !self.capture.on() {
            self.pc_pad.stop();
        }
        self.log("PC controller for gyro ratchet: off");
    }

    /// The phone's "Press a key" screen opened or closed: the PC's keyboard, mouse and controller are read only
    /// meanwhile.
    fn capture_changed(&mut self) {
        if self.capture.on() {
            self.key_listener.start();
            self.pc_pad.start(self.slot_fn.clone());
            self.log("phone listens for a key: reading the PC's keyboard, mouse and controller");
        } else {
            self.key_listener.stop();
            if !self.pc_reading {
                self.pc_pad.stop();
            }
            self.log("phone stopped listening for a key");
        }
        self.state_changed();
    }

    /// Ends a "Press a key" listen (the session ended, or the phone stopped resending CAPTURE).
    fn stop_capture(&mut self) {
        if !self.capture.on() {
            return;
        }
        self.capture.stop();
        self.capture_changed();
    }

    fn read_pc_pad(&mut self) {
        let held = self.pc_pad.read(&mut self.pc_presses);
        self.gate.update(
            &PcPadState { connected: held.is_some(), held: held.unwrap_or(0), presses: Some(&self.pc_presses) },
            &self.pad_gyro,
        );
        // What the overlays show of it (Fire, Aim, the handles): the reader's own copy, into the shared memory.
        let mut axes = [0f32; 6];
        let shown = self.pc_pad.peek(&mut axes);
        self.held.publish_pc_pad(shown.is_some(), shown.unwrap_or(0), &axes);
    }

    /// OVERLAY with the PC controller's gate (its state is the input's): the overlays turn with the axes on after
    /// the PC controller, and its ratchets count for "On ratchet".
    fn gate_overlay(&mut self, plain: &[u8], now_us: i64, rx_ms: i64) {
        self.read_pc_pad();
        let eff = self.gate.effective_of(plain[OVERLAY_AXES_OFFSET], self.pc_gyro.may_move());
        self.overlay_inbox.put_gated(plain, now_us, rx_ms, eff, self.gate.flips() != 0, self.gate.ratchet_presses() as u8);
    }

    fn on_debug_flags(&mut self, flags: u8, now_us: i64) {
        // The PC's switch follows the phone's when it changes; at a session's start, on wins on either side (the
        // phone follows HOST_STATUS the same way), so a misfire right at the start is logged.
        let phone_on = flags & link_info_flags::DEBUG_TRACE != 0;
        let seen = self.phone_diag_seen;
        self.phone_diag_seen = Some(phone_on);
        if (seen.is_none() && phone_on && !self.settings.diagnostics) || seen.is_some_and(|s| s != phone_on) {
            self.set_diagnostics(phone_on, "switched on the phone");
        }
        if !phone_on {
            self.debug_saves = -1;
            return;
        }
        let saves = ((flags & link_info_flags::DEBUG_SAVE_MASK) >> link_info_flags::DEBUG_SAVE_SHIFT) as i32;
        let asked = self.debug_saves >= 0 && saves != self.debug_saves;
        self.debug_saves = saves;
        if !asked {
            return;
        }
        if self.trace.enabled() {
            self.trace_line(TraceKind::Note, "saved (the phone asked)".into(), now_us);
        }
        self.effects.push(Effect::DebugSave);
    }

    /// One line of link statistics for the debug log.
    fn debug_link_line(&self) -> String {
        let s = &self.stats;
        let lat = if s.lat_p50_us == wire::STATUS_UNKNOWN {
            "latency unknown".to_string()
        } else {
            format!("latency {} / {} ms (p50/p99)", ms1(s.lat_p50_us as i64), ms1(s.lat_p99_us as i64))
        };
        format!(
            "got {}/s · loss {}.{}% · {} · jitter {} ms · output {}{}{}{}",
            s.rx_per_sec,
            s.loss_permille / 10,
            s.loss_permille % 10,
            lat,
            ms1(s.jitter_us as i64),
            mode_name(self.pad_out()),
            if self.paused { " · paused" } else { "" },
            if self.locked { " · locked" } else { "" },
            if self.elevated_foreground || self.inject_failed { " · an admin window has focus (input blocked)" } else { "" },
        )
    }

    fn handle_pending_control(&mut self, sid: u32, ptype: u8, plain: &[u8], now_us: i64) {
        if ptype == packet::PING {
            self.send_pong(sid, plain, now_us);
        } else if ptype == packet::BYE {
            self.remember_ended(sid);
            self.sessions.remove(sid);
        }
    }

    fn on_state(&mut self, plain: &[u8], now_us: i64) {
        self.states += 1;
        // What holds up this STATE's hand-off from here (the input log names it when slow): the wait for the engine
        // lock to take this packet, then every lock wait and driver call until it is injected.
        self.hand_off = HandOffCauses::waited(self.datagram_wait.0, self.datagram_wait.1);
        self.rx_frame.decode(plain);
        if self.pad_gyro.enabled {
            self.read_pc_pad();
        }
        // The PC controller ratchets each sample by what it held then (same clock as now_us).
        {
            let pc_pad = &self.pc_pad;
            let held_at = |us: i64| pc_pad.held_at(us);
            let held_at: Option<&dyn crate::gyro::HeldAt> = if self.pad_gyro.enabled { Some(&held_at) } else { None };
            self.pc_gyro.apply(&mut self.rx_frame, now_us, &self.gate, &self.pad_gyro, held_at);
        }
        // A burst can hold a press and its release: inject the older frame first so no edge is lost. Frames that
        // differ only in motion still merge into one injection.
        // A Link test's frames carry fake mouse and wheel totals: where they start or stop, the gap to the real
        // totals is no movement. Inject what is pending on its own side, then rebase.
        let link_test = self.rx_frame.flags & state_flags::LINK_TEST != 0;
        if self.has_state_phone && link_test != self.last_state_link_test {
            if self.has_pending {
                self.flush_locked(now_us, true);
            }
            self.mouse.reset();
            self.catch_up.clear();
            self.acc_wheel = 0;
            self.acc_hwheel = 0;
        }
        self.last_state_link_test = link_test;
        if self.has_pending && edges_differ(&self.pending, &self.rx_frame) {
            self.flush_locked(now_us, true);
        } else if self.flushed_for_motion && !self.has_pending && edges_differ(&self.pending, &self.rx_frame) {
            // A MOTION already injected the frame before this one in the same burst: a pad press it dropped stays
            // visible.
            self.hold_pad_edge(now_us);
        }
        self.flushed_for_motion = false;
        self.pending.clone_from(&self.rx_frame);
        self.has_pending = true;
        self.held_outputs_dirty = true;
        self.released_for_silence = false;
        let f = &self.rx_frame;
        let d = self.mouse.apply(f.mouse_total_x, f.mouse_total_y, f.wheel_total, f.hwheel_total, f.hires, now_us);
        // Packets lost since the STATE before (seq is shared by every type) mean this delta also carries their
        // motion, over the phone time since that STATE.
        let span = if self.has_state_phone { f.phone_time_us.wrapping_sub(self.last_state_phone_us) as i32 as i64 } else { 0 };
        let lost = self.has_state_phone && self.stats.total_lost != self.lost_at_state;
        let phone_time = f.phone_time_us;
        let hires = f.hires;
        self.last_state_phone_us = phone_time;
        self.lost_at_state = self.stats.total_lost;
        self.has_state_phone = true;
        self.catch_up.add(d.dx, d.dy, span, lost);
        self.pending_recv_us = now_us;
        if self.rec_on() && self.recorder.is_some() {
            let f = &self.rx_frame;
            let moving = d.dx != 0 || d.dy != 0;
            let held = f.mouse_buttons != 0 || f.buttons != 0 || f.hat != HAT_CENTER || f.keys.iter().any(|&b| b != 0);
            if let Some(r) = self.recorder.as_mut() {
                r.state(phone_time, now_us, moving, moving || held, lost);
            }
        }
        // Plain STATE counts whole notches.
        self.acc_wheel = self.acc_wheel.wrapping_add(if hires { d.dw } else { d.dw.wrapping_mul(mapping::WHEEL_DELTA) });
        self.acc_hwheel = self.acc_hwheel.wrapping_add(d.dh);
        self.stats.on_state(phone_time, now_us);
    }

    fn on_hello(&mut self, d: &[u8], from: SocketAddr, now_us: i64) {
        let Some(hello) = codec::decode_hello(d) else { return };
        let code = self.phones.iter().position(|p| p.id == hello.phone_id);
        let key = self.key_phones.iter().position(|p| p.phone_id == hello.phone_id);
        if code.is_none() && key.is_none() {
            self.send_error(error_code::NOT_PAIRED, d.len(), from, now_us);
            return;
        }
        let code = code.filter(|&i| codec::verify_mac(&self.phones[i].pair_key, d));
        let key = if code.is_some() { None } else { key.filter(|&i| codec::verify_mac(&self.key_phones[i].pair_key[..], d)) };
        if code.is_none() && key.is_none() {
            self.bad_auth += 1; // bad mac: no reply at all
            return;
        }
        // A retransmitted HELLO gets the same WELCOME and sid; it never disturbs the active session.
        if let Some(existing) = self.sessions.find_by_hello(&hello.phone_id, &hello.n_p) {
            let welcome = existing.welcome;
            self.reply(&welcome, from);
            return;
        }
        let flags = (if self.pad_driver_ok() { welcome_flags::VIGEM_OK } else { 0 })
            | welcome_flags::HIRES_WHEEL
            | welcome_flags::PC_GYRO
            | welcome_flags::MOTION;
        let (pair_key, name): (&[u8], &str) = match (code, key) {
            (Some(i), _) => (&self.phones[i].pair_key, &self.phones[i].name),
            (_, Some(i)) => (&self.key_phones[i].pair_key[..], &self.key_phones[i].name),
            _ => return,
        };
        let sid = self.sessions.create_pending(&hello, name, pair_key, &self.settings.host_id, self.settings.output_mode, flags, from, now_us);
        let Some(sid) = sid else {
            self.dropped += 1; // a replayed HELLO while the phone's newer attempt is pending: no reply
            return;
        };
        if let Some(s) = self.sessions.find(sid) {
            let welcome = s.welcome;
            self.reply(&welcome, from);
        }
    }

    fn promote(&mut self, sid: u32, now_us: i64) {
        let Some(s) = self.sessions.find(sid) else { return };
        let phone_id = s.phone_id;
        let phone_name = s.phone_name.clone();
        self.debug_saves = -1;
        self.phone_diag_seen = None;
        let old = self.sessions.promote(sid);
        self.keep_awake_dirty = true;
        // A new session's OVERLAY totals start from a new base.
        self.overlay_inbox.mark(OverlayKind::TotalsReset, now_us, false);
        self.overlay_queued = true;
        if let Some(old) = old {
            if old.same_phone(&phone_id) {
                // The same phone moved to a new session (network change, HOST_SILENT recovery): what it holds stays
                // injected and its first STATE is diffed against it. The old session's last STATE goes in first; its
                // late copies draw no ERROR.
                if self.has_pending {
                    self.flush_locked(now_us, false);
                }
                self.remember_ended(old.session_id);
                // Its app may have restarted: the next layout is taken even with the version shown now, and its
                // press count starts again.
                self.overlay_inbox.mark(OverlayKind::Renew, now_us, false);
            } else {
                self.release_all_locked();
                self.send_host_bye(&old, host_bye_reason::REPLACED);
                // Nothing of the old phone's overlay or PC controller carries over.
                let now = self.clock.now_us();
                self.overlay_inbox.mark(OverlayKind::Clear, now, true);
                self.stop_capture();
                self.stop_pc_pad();
                self.pad_gyro.enabled = false;
                self.gate.reset();
                self.pc_gyro.reset();
            }
        }
        // The phone restarts its totals and gyro samples at 0 for a new session; the PC controller's toggle stays.
        self.mouse.reset();
        self.pc_gyro.rebase();
        // The phone starts with its rumble off: a game's ongoing rumble goes to it again.
        self.last_rumble = 0;
        self.rumble_repeats = 0;
        self.acc_wheel = 0;
        self.acc_hwheel = 0;
        self.catch_up.clear();
        self.has_state_phone = false;
        self.stats.reset();
        self.press.reset();
        self.released_for_silence = false;
        self.has_pending = false;
        self.pad_phone_id = Some(phone_id);
        self.plug_pad();
        self.free.wake_timers();
        if self.phones.iter().any(|p| p.id == phone_id) {
            if let Some(p) = self.settings.paired_phones.iter_mut().find(|p| p.phone_id == phone_id) {
                p.last_seen_utc = settings::utc_now();
            }
            self.settings_dirty = true;
        } else if let Some(k) = self.key_phones.iter_mut().find(|k| k.phone_id == phone_id) {
            k.last_seen_utc = settings::utc_now();
        }
        self.phone_list = None;
        self.log(format!("session started with '{phone_name}'"));
        if let Some(r) = self.rec() {
            r.session_started(&phone_name, now_us);
        }
        self.send_status(now_us);
        self.state_changed();
    }

    /// `edge`: a newer STATE with other keys or buttons replaces this one in the same burst.
    fn flush_locked(&mut self, now_us: i64, edge: bool) {
        self.send_pending_rumble(now_us, false);
        // Without a new STATE, only a running catch-up has something to move.
        let frame = self.has_pending;
        if !frame && !self.catch_up.catching_up() {
            return;
        }
        self.has_pending = false;
        let (mut dx, mut dy) = self.catch_up.take(now_us);
        if self.catch_up.backlog_taken() {
            let (span, px, spread) = (self.catch_up.backlog_span_us(), self.catch_up.backlog_px(), self.catch_up.backlog_catch_up_us());
            if let Some(r) = self.rec() {
                r.catch_up(span, px, spread, now_us);
            }
        }
        let (mut dw, mut dh) = (self.acc_wheel, self.acc_hwheel);
        self.acc_wheel = 0;
        self.acc_hwheel = 0;
        if self.pending.flags & state_flags::LINK_TEST != 0 {
            // Link test input is counted (input log, catch-up), never moved.
            dx = 0;
            dy = 0;
            dw = 0;
            dh = 0;
        }
        if self.paused || self.locked {
            self.catch_up.clear();
            return; // baseline already updated, nothing injected
        }
        if !frame && dx == 0 && dy == 0 {
            return;
        }

        if frame {
            self.submit_pad(now_us);
            self.note_pad_presses(now_us);
            if edge {
                self.hold_pad_edge(now_us);
            }
        }

        let blocked = self.elevated_foreground;
        let before = self.injected_keys;
        let before_mouse = self.injected_mouse;
        let mut unknown = 0u32;
        let mdx = dx.clamp(-wire::MAX_MOUSE_DELTA, wire::MAX_MOUSE_DELTA);
        let mdy = dy.clamp(-wire::MAX_MOUSE_DELTA, wire::MAX_MOUSE_DELTA);
        let n = input_diff::diff(
            &mut self.injected_keys,
            &self.pending.keys,
            &mut self.injected_mouse,
            self.pending.mouse_buttons,
            mdx,
            mdy,
            dw.clamp(-wire::MAX_WHEEL_DELTA_HIRES, wire::MAX_WHEEL_DELTA_HIRES),
            dh.clamp(-wire::MAX_WHEEL_DELTA_HIRES, wire::MAX_WHEEL_DELTA_HIRES),
            &mut self.inject,
            &mut unknown,
        );
        self.unknown_usages += unknown as i64;
        if blocked {
            self.note_blocked_ups(&before, before_mouse);
        }
        if n > 0 {
            let t0_ticks = self.clock.now_ticks();
            let t0 = self.clock.ticks_to_us(t0_ticks);
            let took = self.key_mouse.inject(&self.inject[..n]);
            let after = self.clock.now_us();
            self.hand_off.send_input_us = self.hand_off.send_input_us.saturating_add(after.saturating_sub(t0).max(0));
            self.hand_off.route = Some(self.key_mouse.route());
            // Diagnostics' delivery probe: when this move's hand-off started (numbers only).
            if (mdx | mdy) != 0 && took && self.free.move_watch.load(Ordering::Relaxed) {
                self.hooks.move_sent(t0_ticks, mdx, mdy);
            }
            if !took {
                // Windows dropped them: the ups are sent again once it takes input.
                if !blocked {
                    self.note_blocked_ups(&before, before_mouse);
                }
                self.inject_failed();
            }
            if self.rec_on() {
                let recv = if frame { Some(self.pending_recv_us) } else { None };
                let causes = self.hand_off;
                if let Some(r) = self.recorder.as_mut() {
                    r.inject(dx, dy, recv, after, !took, &causes);
                }
            }
            self.injections += 1;
            if self.trace.enabled() {
                self.trace.on_inject(&self.inject[..n], now_us);
            }
            let motion_only = mapping::MOUSE_MOVE | mapping::MOUSE_WHEEL | mapping::MOUSE_HWHEEL;
            if self.inject[..n].iter().any(|e| e.kind == InjectKind::Key || e.flags & !motion_only != 0) {
                self.held_version = self.held_version.wrapping_add(1);
            }
        }
        if frame && self.settings.measure_buttons {
            let (offset, have) = self.clock_offset();
            let now = self.clock.now_us();
            self.press.on_injected(self.pending.phone_time_us, now, offset, have);
        }
    }

    /// Records when each pad button and hat direction of the frame just submitted was pressed.
    fn note_pad_presses(&mut self, now_us: i64) {
        let mut pressed = self.pending.buttons & !self.last_pending_buttons;
        let mut i = 0;
        while pressed != 0 {
            if pressed & 1 != 0 {
                self.pad_press_us[i] = now_us;
            }
            i += 1;
            pressed >>= 1;
        }
        let hat = pad_mapper::hat_of(&self.pending);
        if hat != self.last_pending_hat && hat != HAT_CENTER {
            self.hat_press_us = now_us;
        }
        self.last_pending_buttons = self.pending.buttons;
        self.last_pending_hat = hat;
    }

    /// The pending frame was just injected with a newer one right behind it. SendInput queues both edges, but a game
    /// polling the pad would miss a button held for microseconds, so a button or hat direction pressed within the
    /// last PAD_EDGE_HOLD_US that the newer frame drops stays pressed for PAD_EDGE_HOLD_US. Anything held longer
    /// lets go at once.
    fn hold_pad_edge(&mut self, now_us: i64) {
        let recent = now_us.saturating_sub(PAD_EDGE_HOLD_US);
        let mut released = self.pending.buttons & !self.rx_frame.buttons;
        let mut fresh = 0u16;
        let mut i = 0;
        while released != 0 {
            if released & 1 != 0 && self.pad_press_us[i] > recent {
                fresh |= 1 << i;
            }
            i += 1;
            released >>= 1;
        }
        let mut hat = self.last_pending_hat;
        if hat == pad_mapper::hat_of(&self.rx_frame) || self.hat_press_us <= recent {
            hat = HAT_CENTER;
        }
        if fresh == 0 && hat == HAT_CENTER {
            return;
        }
        if now_us >= self.pad_hold_until_us {
            self.pad_hold_buttons = 0;
            self.pad_hold_hat = HAT_CENTER;
        }
        self.pad_hold_buttons |= fresh;
        if hat != HAT_CENTER {
            self.pad_hold_hat = hat;
        }
        self.pad_hold_until_us = now_us.saturating_add(PAD_EDGE_HOLD_US);
    }

    /// Gives the pad the pending frame (with any buttons still held from a burst), when it changed.
    fn submit_pad(&mut self, now_us: i64) {
        self.pad_held = false;
        let mut merged = false;
        if now_us < self.pad_hold_until_us {
            let s = &self.pending;
            let buttons = s.buttons | self.pad_hold_buttons;
            let hat = if pad_mapper::hat_of(s) == HAT_CENTER { self.pad_hold_hat } else { s.hat };
            if buttons != s.buttons || hat != s.hat {
                self.pad_merged.clone_from(&self.pending);
                self.pad_merged.buttons = buttons;
                self.pad_merged.hat = hat;
                merged = true;
                self.pad_held = true;
            }
        }
        if !self.pad_frame_valid && now_us < self.pad_retry_after_us {
            return;
        }
        let s = if merged { &self.pad_merged } else { &self.pending };
        match self.pad_out() {
            output_mode::XBOX360 => {
                let x = pad_mapper::to_x360(s);
                let dropped = s.buttons & pad_mapper::X360_DROPPED_BUTTONS != 0;
                if dropped && !self.logged_x360_dropped {
                    self.logged_x360_dropped = true;
                    self.log("M1-M4/SHARE have no Xbox 360 button; dropped");
                }
                if self.pad_frame_valid && x == self.last_x360 {
                    return;
                }
                if !self.try_submit_x360(&x) {
                    self.pad_retry_after_us = now_us.saturating_add(PAD_RETRY_US); // the next flush after it sends it again
                    return;
                }
                self.last_x360 = x;
                if self.trace.enabled() {
                    self.trace.on_x360(&x, now_us);
                }
                self.pad_frame_valid = true;
            }
            // The DualSense takes the same buttons, sticks and motion.
            output_mode::DUALSHOCK4 | output_mode::DUALSENSE => {
                let f = pad_mapper::to_ds4(s);
                if self.pad_frame_valid && f == self.last_ds4 {
                    return;
                }
                let m = self.ds4_motion.current();
                if !self.try_submit_ds4(&f, &m) {
                    self.pad_retry_after_us = now_us.saturating_add(PAD_RETRY_US);
                    return;
                }
                self.last_ds4 = f;
                if self.trace.enabled() {
                    self.trace.on_ds4(&f, now_us);
                }
                self.pad_frame_valid = true;
            }
            _ => {}
        }
    }

    // A pad failure (bus handle gone after a driver reset, say) must never stop keyboard, mouse or a release.
    fn try_submit_x360(&mut self, f: &X360Frame) -> bool {
        let t0 = self.clock.now_us();
        match self.pad.submit_x360(f) {
            Ok(()) => {
                self.pad_submitted(t0);
                true
            }
            Err(e) => {
                self.pad_failed(e);
                false
            }
        }
    }

    fn try_submit_ds4(&mut self, f: &Ds4Frame, m: &Ds4Motion) -> bool {
        let t0 = self.clock.now_us();
        match self.pad.submit_ds4(f, m) {
            Ok(()) => {
                self.pad_submitted(t0);
                true
            }
            Err(e) => {
                self.pad_failed(e);
                false
            }
        }
    }

    fn pad_submitted(&mut self, t0: i64) {
        let took = self.clock.now_us().saturating_sub(t0).max(0);
        self.hand_off.pad_us = self.hand_off.pad_us.saturating_add(took);
        self.logged_pad_failure = false;
    }

    /// MOTION: each new sample goes to the DualShock 4 or DualSense as its own report, with the buttons and sticks it
    /// shows now, so the game sees every turn at the time it was measured.
    fn on_motion(&mut self, plain: &[u8], now_us: i64) {
        if !self.pad_takes_motion() || self.paused || self.locked || self.released_for_silence {
            return;
        }
        if !self.motion_frame.decode(plain) {
            return;
        }
        let n = self.ds4_motion.take(&self.motion_frame, &mut self.motion_out, now_us);
        // Each reading is a driver call: inject the STATE this burst holds first, so its keys, mouse and pad frame do
        // not wait for them, and the readings carry its buttons.
        if n > 0 && self.has_pending {
            self.flush_locked(now_us, false);
            self.flushed_for_motion = true;
        }
        self.submit_motion(n);
    }

    /// Sends the first `n` readings of `motion_out` with the pad's current frame.
    fn submit_motion(&mut self, n: usize) {
        let f = if self.pad_frame_valid { self.last_ds4 } else { Ds4Frame::NEUTRAL };
        for i in 0..n.min(motion::MAX_SAMPLES) {
            let m = self.motion_out[i];
            if !self.try_submit_ds4(&f, &m) {
                return;
            }
        }
    }

    fn pad_failed(&mut self, e: SinkError) {
        self.exceptions += 1;
        self.pad_frame_valid = false;
        // The sink says the pad is gone: plugging again blocks in the driver, so the watchdog has the pad thread do it.
        if !self.pad_lost && !self.pad_busy && self.pad_want != output_mode::NONE && self.pad.plugged() == output_mode::NONE {
            self.pad_lost = true;
            self.pad_replug_at_us = i64::MIN;
            self.pad_replug_gap_us = PAD_REPLUG_US;
        }
        if self.logged_pad_failure {
            return;
        }
        self.logged_pad_failure = true;
        self.log(format!("pad submit failed: {e}"));
    }

    // ---- watchdog --------------------------------------------------------------------------------------------

    fn tick(&mut self, now_us: i64) {
        if self.link_test_until_us != i64::MIN && now_us >= self.link_test_until_us {
            self.set_link_test(0, "time is up");
        }
        if let Some(a) = self.sessions.active() {
            let silent = now_us.saturating_sub(a.last_rx_us);
            if silent >= SESSION_TIMEOUT_US {
                let line = format!("session with '{}' timed out", a.phone_name);
                self.log(line);
                self.end_active(None, "timed out");
            } else if silent >= RELEASE_TIMEOUT_US && !self.released_for_silence {
                self.released_for_silence = true;
                self.has_pending = false;
                let held = self.held_count();
                if let Some(r) = self.rec() {
                    r.released_for_silence(held, now_us);
                }
                self.release_all_locked();
                self.state_changed();
            }
        }
        // Ups Windows dropped are sent again as soon as it takes input.
        if self.inject_failed && !self.blocked() {
            self.resync_after_block();
        }
        // Held volume keys repeat, as a keyboard's do.
        if !self.paused && !self.blocked() {
            let r = self.key_repeat.due(&self.injected_keys, now_us, &mut self.inject);
            if r > 0 && !self.key_mouse.inject(&self.inject[..r]) {
                self.inject_failed();
            }
        }
        // A lost stop (or the phone gone quiet): the pad's gyro comes to rest.
        if self.pad_takes_motion() && self.ds4_motion.is_stale(now_us) {
            let n = self.ds4_motion.rest(&mut self.motion_out, now_us);
            self.submit_motion(n);
        }
        // A pad button held from a burst is let go even when no STATE follows.
        if self.pad_held && now_us >= self.pad_hold_until_us && !self.has_pending && !self.paused && !self.locked {
            self.submit_pad(now_us);
        }
        self.sessions.expire_pending(now_us);
        let pairing_changed = self.pairing.tick(now_us);
        self.pair_events();
        if pairing_changed {
            self.state_changed();
        }
        // A pad lost underneath is plugged again on the pad thread: at once, then after 1, 2, 4, 8 and every 10 s
        // while that fails.
        if self.pad_lost && !self.pad_busy {
            if self.pad_want == output_mode::NONE || self.pad.plugged() == self.pad_want {
                self.pad_lost = false;
            } else if now_us >= self.pad_replug_at_us {
                if self.pad_replug_at_us == i64::MIN {
                    self.log(format!("{} pad was lost; plugging it again", mode_name(self.pad_want)));
                }
                self.pad_replug_at_us = now_us.saturating_add(self.pad_replug_gap_us);
                self.pad_replug_gap_us = (self.pad_replug_gap_us.saturating_mul(2)).min(PAD_REPLUG_MAX_US);
                self.request_pad(self.pad_want);
            }
        }
        // No session for a long while: the pad is unplugged until the next one.
        if self.sessions.active().is_none()
            && self.pad_want != output_mode::NONE
            && now_us.saturating_sub(self.pad_idle_from_us) >= PAD_IDLE_UNPLUG_US
        {
            self.log("no session for 5 minutes: pad unplugged");
            self.request_pad(output_mode::NONE);
        }

        self.send_pending_rumble(now_us, true);
        let active = self.sessions.active().is_some();
        if self.capture.on() {
            if !active || self.capture.expired(now_us) {
                self.stop_capture();
            } else {
                let n = self.capture.tick(&*self.key_listener, &*self.pc_pad, now_us, &mut self.captured_plain);
                if n > 0 {
                    self.send_active(packet::CAPTURED, &self.captured_plain[..n]);
                }
            }
        }
        if active && now_us.saturating_sub(self.last_status_us) >= STATUS_INTERVAL_US {
            self.stats.roll();
            if self.rec_on() {
                if let Some(r) = self.recorder.as_mut() {
                    r.second(&self.stats, now_us);
                }
            }
            self.send_status(now_us);
            if self.trace.enabled() {
                let line = self.debug_link_line();
                self.trace_line(TraceKind::Link, line, now_us);
            }
        }
        let idle = !active
            && self.sessions.pending().is_empty()
            && !self.pairing.busy(now_us)
            && self.qr.is_none()
            && self.link_test_until_us == i64::MIN
            && !self.capture.on()
            && !self.inject_failed
            && !self.pad_held
            && !self.pad_busy
            && self.held_count() == 0;
        self.free.watchdog_idle.store(idle, Ordering::Relaxed);
    }

    // ---- commands --------------------------------------------------------------------------------------------

    fn set_diagnostics(&mut self, on: bool, why: &str) {
        if on == self.settings.diagnostics {
            return;
        }
        let (was, was_writing) = (self.rec_on(), self.writing());
        self.settings.diagnostics = on;
        self.settings_dirty = true;
        self.trace_set_on(on, why);
        self.recorder_switched(was, was_writing, if on { "diagnostics on" } else { "diagnostics off" });
        self.log(format!("diagnostics {} ({why})", if on { "on" } else { "off" }));
        self.test_mode_changed();
        self.effects.push(Effect::ApplyDiagnostics);
    }

    fn set_diagnostics_minutes(&mut self, minutes: i32) {
        let minutes = minutes.clamp(settings::MIN_KEEP_MINUTES, settings::MAX_KEEP_MINUTES);
        if minutes == self.settings.diagnostics_minutes {
            return;
        }
        self.settings.diagnostics_minutes = minutes;
        self.settings_dirty = true;
    }

    fn save_diagnostics(&mut self) {
        if !self.settings.diagnostics {
            return;
        }
        self.diag_saves = (self.diag_saves + 1) & 7;
        self.send_test_mode();
        self.effects.push(Effect::DebugSave);
    }

    /// A TEST_MODE bit changed: told at once, an "off" a few times with the next statuses.
    fn test_mode_changed(&mut self) {
        self.test_mode_off_repeats = if self.test_mode_flags_now() == 0 { 3 } else { 0 };
        self.send_test_mode();
    }

    fn set_link_test(&mut self, seconds: i32, why: &str) {
        let now = self.clock.now_us();
        let was = self.link_test_until_us != i64::MIN;
        let (rec_was, was_writing) = (self.rec_on(), self.writing());
        let on = seconds > 0;
        let secs = seconds.clamp(0, 3600) as i64;
        self.link_test_until_us = if on { now.saturating_add(secs * 1_000_000) } else { i64::MIN };
        self.free.link_test_until_us.store(self.link_test_until_us, Ordering::Relaxed);
        if was != on {
            self.recorder_switched(rec_was, was_writing, if on { "link test started" } else { "link test ended" });
        }
        let because = if why.is_empty() { String::new() } else { format!(" ({why})") };
        if on {
            self.log(format!("link test started for {secs} s{because}"));
            if let Some(r) = self.rec() {
                r.note("link test", &format!("started for {secs} s"), now);
            }
        } else if was {
            self.log(format!("link test stopped{because}"));
            if let Some(r) = self.rec() {
                r.note("link test", "stopped", now);
            }
        } else {
            return;
        }
        self.test_mode_changed();
    }

    fn change_key_mouse_route(&mut self, change: impl FnOnce(), why: &str) {
        let mut delivered = true;
        while input_diff::any_held(&self.injected_keys, self.injected_mouse) {
            let n = input_diff::release_all(&mut self.injected_keys, &mut self.injected_mouse, &mut self.release_events);
            if n > 0 {
                delivered &= self.key_mouse.inject(&self.release_events[..n]);
            }
            self.held_version = self.held_version.wrapping_add(1);
        }
        if !delivered {
            self.log("keys and mouse: letting go before the route change didn't all arrive");
        }
        change();
        self.log(format!("keys and mouse: {why}"));
        self.state_changed();
    }

    fn set_authorized_phones(&mut self, phones: Vec<AuthorizedPhone>, mut pre: Option<(PublicKey, Id, Zeroizing<[u8; 32]>)>) {
        let Some(host_key) = self.host_key.clone() else { return };
        let mut next: Vec<KeyPhone> = Vec::with_capacity(phones.len());
        // Known keys need no ECDH again.
        let mut known = std::mem::take(&mut self.key_phones);
        self.authorized_key_ids.clear();
        for p in &phones {
            let key_id = key_pairing::key_id(&p.key.public_key);
            if !self.authorized_key_ids.contains(&key_id) {
                self.authorized_key_ids.push(key_id);
            }
            let name = if p.key.name.is_empty() {
                p.file.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default()
            } else {
                p.key.name.clone()
            };
            if let Some(at) = known.iter().position(|k| k.public_key == p.key.public_key) {
                // Same key: keep its secrets, take the (maybe renamed) file.
                let old = &known[at];
                if next.iter().all(|k| k.phone_id != old.phone_id) {
                    next.push(KeyPhone {
                        file: p.file.clone(),
                        name,
                        phone_id: old.phone_id,
                        pair_key: old.pair_key.clone(),
                        fingerprint: old.fingerprint.clone(),
                        key_id: old.key_id,
                        public_key: old.public_key,
                        last_seen_utc: old.last_seen_utc.clone(),
                    });
                }
                continue;
            }
            let secrets = match pre.take() {
                Some((pk, id, key)) if pk == p.key.public_key => Some((id, key)), // taken over
                other => {
                    pre = other;
                    key_pairing::host_secrets(&host_key, &p.key.public_key)
                }
            };
            let Some((id, pair_key)) = secrets else { continue };
            if next.iter().any(|k| k.phone_id == id) {
                continue; // the same key in two files
            }
            next.push(KeyPhone {
                file: p.file.clone(),
                name,
                phone_id: id,
                pair_key,
                fingerprint: p.key.fingerprint(),
                key_id,
                public_key: p.key.public_key,
                last_seen_utc: settings::NEVER_UTC.to_string(),
            });
        }
        self.authorized_list = phones;
        for old in known.iter_mut() {
            if next.iter().any(|k| k.phone_id == old.phone_id) {
                continue;
            }
            self.log(format!("key of '{}' removed; phone no longer authorized", old.name));
            self.sessions.remove_pending(&old.phone_id);
            if self.sessions.active().is_some_and(|a| a.same_phone(&old.phone_id)) {
                self.end_active(Some(host_bye_reason::FORGOTTEN), "ended");
            }
            self.forgot_pad_phone(&old.phone_id);
            old.pair_key.zeroize();
        }
        for k in &next {
            if known.iter().all(|o| o.phone_id != k.phone_id) {
                let line = format!("authorized '{}' by key {}", k.name, k.fingerprint);
                self.log(line);
            }
        }
        self.key_phones = next;
        self.phone_list = None;
        self.state_changed();
    }

    fn paired_phone_list(&self) -> Vec<PairedPhoneInfo> {
        let mut list = Vec::with_capacity(self.phones.len() + self.key_phones.len());
        for p in &self.phones {
            let info = self.settings.paired_phones.iter().find(|i| i.phone_id == p.id);
            list.push(PairedPhoneInfo {
                phone_id: p.id.to_vec(),
                name: p.name.clone(),
                last_seen_utc: info.map(|i| i.last_seen_utc.clone()).unwrap_or_else(|| settings::NEVER_UTC.to_string()),
                by_key: false,
                key_file: None,
                fingerprint: None,
            });
        }
        for k in &self.key_phones {
            list.push(PairedPhoneInfo {
                phone_id: k.phone_id.to_vec(),
                name: k.name.clone(),
                last_seen_utc: k.last_seen_utc.clone(),
                by_key: true,
                key_file: Some(k.file.to_string_lossy().into_owned()),
                fingerprint: Some(k.fingerprint.clone()),
            });
        }
        list
    }

    // ---- internals -------------------------------------------------------------------------------------------

    /// KEY_ENROLL: a phone scanned the QR code. Only the open token's holder gets an answer it can check; the first
    /// valid key is saved and authorized, and repeats of it are acked again. The phone also broadcasts it, so a token
    /// this PC never issued gets no answer at all: an unauthenticated refusal from another PC would end the real
    /// pairing.
    fn on_key_enroll(&mut self, d: &[u8], e: &KeyEnroll, from: SocketAddr, now_us: i64) {
        let mut ack = [0u8; wire::KEY_ENROLL_ACK_SIZE];
        let open = self.host_key.is_some() && self.qr.as_ref().is_some_and(|q| now_us < q.expires_us && e.token_id == q.token_id);
        if !open {
            self.dropped += 1;
            // Without a host key no token was ever issued, so no NO_KEY either.
            let issued = self.issued_token_ids.iter().any(|t| *t == Some(e.token_id));
            if !issued || !self.error_limiter.try_acquire_from(from, now_us) {
                return;
            }
            codec::encode_key_enroll_ack(e.req_id, &e.token_id, enroll_status::EXPIRED, None, &[], &mut ack);
            self.reply(&ack, from);
            return;
        }
        let Some(q) = self.qr.as_mut() else { return };
        if !qr_pairing::check_enroll(d, &q.token[..]) || !P256::is_valid_public(&e.pk_p) {
            self.bad_auth += 1; // not from the QR code: silence
            return;
        }
        if q.enrolled_key.is_none() {
            // Saving the file and the ECDH stay off the rx thread and the lock (a slow disk or a virus scan would
            // freeze a playing phone's input); the phone resends KEY_ENROLL every 250 ms and gets OK once done.
            if q.saving {
                return;
            }
            q.saving = true;
            let key = PubKeyFile { public_key: e.pk_p, name: if e.name.is_empty() { "Phone".to_string() } else { e.name.clone() } };
            let (serial, save) = (q.serial, q.save.clone());
            if self.inline_workers {
                let enrolled = enroll_work(&save, &key, self.host_key.as_deref());
                self.finish_enroll(serial, key, enrolled);
            } else {
                self.effects.push(Effect::StartEnroll(serial, save, key));
            }
        }
        let Some(q) = self.qr.as_ref() else { return };
        let Some(enrolled) = q.enrolled_key else { return };
        let status = if enrolled == e.pk_p { enroll_status::OK } else { enroll_status::USED };
        codec::encode_key_enroll_ack(e.req_id, &e.token_id, status, Some(&q.token[..]), &e.pk_p, &mut ack);
        self.reply(&ack, from);
    }

    /// The key file is saved (or not): authorizes the phone.
    fn finish_enroll(&mut self, serial: u64, key: PubKeyFile, enrolled: Enrolled) {
        if let Some(q) = self.qr.as_mut().filter(|q| q.serial == serial) {
            q.saving = false;
        }
        if let Some(failed) = &enrolled.failed {
            self.log(format!("could not save the key of '{}': {failed}", key.name));
        }
        let Some(file) = enrolled.file else { return }; // the phone retries; nothing was authorized
        if enrolled.failed.is_some() {
            return;
        }
        if let Some(q) = self.qr.as_mut().filter(|q| q.serial == serial) {
            q.enrolled_key = Some(key.public_key);
            q.enrolled_name = Some(key.name.clone());
        }
        self.log(format!("'{}' paired by QR code", key.name));
        let mut list: Vec<AuthorizedPhone> = self.authorized_list.iter().filter(|a| a.key.public_key != key.public_key).cloned().collect();
        let public_key = key.public_key;
        list.push(AuthorizedPhone { file, key });
        let pre = enrolled.secrets.map(|(id, pair_key)| (public_key, id, pair_key));
        self.set_authorized_phones(list, pre);
        self.state_changed();
    }

    /// Hands the pairing manager's notices on: the prompt to show or close, a pairing to store.
    fn pair_events(&mut self) {
        let events: Vec<PairEvent> = self.pairing.drain_events().collect();
        for e in events {
            match e {
                PairEvent::Show { attempt_id, phone_name, sas } => self.effects.push(Effect::PromptShow(attempt_id, phone_name, sas)),
                PairEvent::Close { attempt_id } => self.effects.push(Effect::PromptClose(attempt_id)),
                PairEvent::Paired { phone_id, phone_name, pair_key } => self.on_paired(phone_id, phone_name, pair_key),
            }
        }
    }

    fn on_paired(&mut self, phone_id: Id, name: String, pair_key: Zeroizing<[u8; 32]>) {
        self.settings.paired_phones.retain(|p| p.phone_id != phone_id);
        let now = settings::utc_now();
        self.settings.paired_phones.push(PairedPhone {
            phone_id: phone_id.to_vec(),
            name: name.clone(),
            protected_key: self.protector.protect(&pair_key[..]),
            paired_utc: now.clone(),
            last_seen_utc: now,
        });
        self.phones.retain(|p| p.id != phone_id);
        self.phones.push(CodePhone { id: phone_id, name: name.clone(), pair_key: Zeroizing::new(pair_key.to_vec()) });
        self.settings_dirty = true;
        self.phone_list = None;
        self.log(format!("paired with '{name}'"));
        self.state_changed();
    }

    fn end_active(&mut self, host_bye_reason: Option<u8>, why: &str) {
        if self.sessions.active().is_none() {
            return;
        }
        let now = self.clock.now_us();
        if self.rec_on() {
            let why = match host_bye_reason {
                None => why.to_string(),
                Some(r) => format!("{why} by the PC ({r})"),
            };
            if let Some(r) = self.recorder.as_mut() {
                r.session_ended(&why, now);
            }
        }
        self.release_all_locked();
        if let Some(reason) = host_bye_reason {
            let mut p = [0u8; wire::BYE_PLAIN];
            codec::encode_byte4(reason, &mut p);
            for _ in 0..3 {
                self.send_active(packet::HOST_BYE, &p);
            }
            if let Some(sid) = self.sessions.active().map(|a| a.session_id) {
                self.remember_ended(sid); // in-flight packets cross the HOST_BYE
            }
        }
        self.sessions.drop_active();
        self.keep_awake_dirty = true;
        self.stop_capture();
        self.stop_pc_pad();
        self.pad_gyro.enabled = false;
        self.gate.reset();
        self.pc_gyro.reset();
        let now = self.clock.now_us();
        self.overlay_inbox.mark(OverlayKind::Clear, now, true);
        self.overlay_queued = true;
        self.ds4_motion.reset();
        // The pad stays plugged, at rest (the release sent it neutral), so the game keeps its controller; a session
        // within PAD_IDLE_UNPLUG_US reuses it.
        self.pad_idle_from_us = now;
        self.has_pending = false;
        self.released_for_silence = false;
        self.state_changed();
    }

    /// Keys and mouse buttons injected and held now (a count only, for the input log).
    fn held_count(&self) -> u32 {
        self.injected_mouse.count_ones() + self.injected_keys.iter().map(|b| b.count_ones()).sum::<u32>()
    }

    fn release_all_locked(&mut self) {
        self.has_pending = false;
        self.catch_up.clear();
        self.pad_hold_buttons = 0;
        self.pad_hold_hat = HAT_CENTER;
        self.pad_hold_until_us = i64::MIN;
        self.pad_held = false;
        self.last_pending_buttons = 0;
        self.last_pending_hat = HAT_CENTER;
        self.pad_press_us = [i64::MIN; 16];
        self.hat_press_us = i64::MIN;
        let held = self.injected_keys;
        let held_mouse = self.injected_mouse;
        let mut delivered = true;
        while input_diff::any_held(&self.injected_keys, self.injected_mouse) {
            let n = input_diff::release_all(&mut self.injected_keys, &mut self.injected_mouse, &mut self.release_events);
            if n > 0 {
                delivered &= self.key_mouse.inject(&self.release_events[..n]);
            }
            self.held_version = self.held_version.wrapping_add(1);
        }
        if self.blocked() || !delivered {
            for (b, h) in self.blocked_ups.iter_mut().zip(held.iter()) {
                *b |= h;
            }
            self.blocked_mouse_ups |= held_mouse;
        }
        if !delivered {
            self.inject_failed();
        }
        // Pad to neutral but stay plugged.
        match self.pad_out() {
            output_mode::XBOX360 => {
                if self.try_submit_x360(&X360Frame::NEUTRAL) {
                    self.last_x360 = X360Frame::NEUTRAL;
                    self.pad_frame_valid = true;
                }
            }
            output_mode::DUALSHOCK4 | output_mode::DUALSENSE => {
                self.ds4_motion.reset();
                let m = self.ds4_motion.current();
                if self.try_submit_ds4(&Ds4Frame::NEUTRAL, &m) {
                    self.last_ds4 = Ds4Frame::NEUTRAL;
                    self.pad_frame_valid = true;
                }
            }
            _ => {}
        }
    }

    /// SendInput may be silently dropped: an elevated window is in front (UIPI) or the secure desktop is up.
    fn blocked(&self) -> bool {
        self.elevated_foreground || self.locked
    }

    fn inject_failed(&mut self) {
        if self.inject_failed {
            return;
        }
        self.inject_failed = true;
        self.state_changed();
    }

    fn note_blocked_ups(&mut self, before: &[u8; KEY_BITMAP_SIZE], before_mouse: u8) {
        for i in 0..KEY_BITMAP_SIZE {
            self.blocked_ups[i] |= before[i] & !self.injected_keys[i];
        }
        self.blocked_mouse_ups |= before_mouse & !self.injected_mouse;
    }

    /// Input is deliverable again: sends again the ups injected while it was blocked (for usages not held now), then
    /// the downs for the held set, so the system key state matches ours.
    fn resync_after_block(&mut self) {
        let mut ups = [0u8; KEY_BITMAP_SIZE];
        for i in 0..KEY_BITMAP_SIZE {
            ups[i] = self.blocked_ups[i] & !self.injected_keys[i];
        }
        let mut ups_mouse = self.blocked_mouse_ups & !self.injected_mouse;
        let retry = ups;
        let retry_mouse = ups_mouse;
        self.blocked_ups = [0; KEY_BITMAP_SIZE];
        self.blocked_mouse_ups = 0;
        let was_failed = self.inject_failed;
        self.inject_failed = false;
        let mut delivered = true;
        while input_diff::any_held(&ups, ups_mouse) {
            let n = input_diff::release_all(&mut ups, &mut ups_mouse, &mut self.release_events);
            if n > 0 {
                delivered &= self.key_mouse.inject(&self.release_events[..n]);
            }
        }

        let mut sent = [0u8; KEY_BITMAP_SIZE];
        let mut sent_mouse = 0u8;
        let mut unknown = 0u32;
        loop {
            let n = input_diff::diff(&mut sent, &self.injected_keys, &mut sent_mouse, self.injected_mouse, 0, 0, 0, 0, &mut self.inject, &mut unknown);
            if n == 0 {
                break;
            }
            delivered &= self.key_mouse.inject(&self.inject[..n]);
        }
        if !delivered {
            // Still dropped: try again later.
            for i in 0..KEY_BITMAP_SIZE {
                self.blocked_ups[i] |= retry[i];
            }
            self.blocked_mouse_ups |= retry_mouse;
            self.inject_failed = true;
        }
        if was_failed != self.inject_failed {
            self.state_changed();
        }
    }

    fn remember_ended(&mut self, sid: u32) {
        self.ended_sids[self.ended_next] = sid;
        self.ended_until_us[self.ended_next] = self.clock.now_us().saturating_add(ENDED_SID_QUIET_US);
        self.ended_next = (self.ended_next + 1) % self.ended_sids.len();
    }

    fn recently_ended(&self, sid: u32, now_us: i64) -> bool {
        (0..self.ended_sids.len()).any(|i| self.ended_sids[i] == sid && now_us < self.ended_until_us[i])
    }

    /// The plugged pad has a gyro and accelerometer (DualShock 4 or DualSense).
    fn pad_takes_motion(&self) -> bool {
        matches!(self.pad_out(), output_mode::DUALSHOCK4 | output_mode::DUALSENSE)
    }

    /// The pad reports may go to: what is plugged, NONE while the pad thread plugs or unplugs it.
    fn pad_out(&self) -> u8 {
        if self.pad_busy {
            output_mode::NONE
        } else {
            self.pad.plugged()
        }
    }

    /// The driver the chosen output needs works (what the phone's "driver ok" flags say).
    fn pad_driver_ok(&self) -> bool {
        if self.settings.output_mode == output_mode::DUALSENSE {
            self.pad.dual_sense_ok()
        } else {
            self.pad.driver_ok()
        }
    }

    /// A phone was forgotten: with no session up and that phone the last to use the pad, no one is coming back to
    /// it, so it is unplugged. Another phone's pad, kept for its reconnect, stays.
    fn forgot_pad_phone(&mut self, phone_id: &Id) {
        if self.sessions.active().is_some() || self.pad_phone_id != Some(*phone_id) {
            return;
        }
        self.pad_phone_id = None;
        self.request_pad(output_mode::NONE);
    }

    /// A session starts: the pad of the output mode, reused when it is still plugged.
    fn plug_pad(&mut self) {
        self.pad_frame_valid = false;
        self.request_pad(self.settings.output_mode);
    }

    /// Has the pad thread make the pad `want` (NONE: unplugged). Returns at once; until the job is done `pad_out` is
    /// NONE, so STATEs go on with keys and mouse and skip the pad.
    fn request_pad(&mut self, mut want: u8) {
        if want != output_mode::NONE && !self.pad.can_plug(want) {
            want = output_mode::NONE;
        }
        self.pad_want = want;
        if self.pad_busy {
            return; // the running job takes the new wish when it is done
        }
        if self.pad.plugged() == want && self.pad_retired.is_empty() {
            return;
        }
        self.pad_busy = true;
        self.pad_frame_valid = false;
        self.free.pad_idle.reset();
        if self.inline_workers {
            // Tests: done in place, and the caller goes on as with a plug done at once.
            loop {
                let retired = std::mem::take(&mut self.pad_retired);
                let (pad, want) = (self.pad.clone(), self.pad_want);
                if let Some(failed) = pad_work(&retired, &pad, want) {
                    self.log(failed);
                }
                if want == self.pad_want && self.pad_retired.is_empty() {
                    break;
                }
            }
            self.pad_job_done(true);
        } else {
            self.effects.push(Effect::StartPadJob);
        }
    }

    /// The pad is what was asked for. A live session's pad gets its current frame and the phone hears of it.
    fn pad_job_done(&mut self, inline: bool) {
        self.pad_busy = false;
        self.pad_frame_valid = false;
        self.free.pad_idle.set();
        if inline {
            return;
        }
        if self.sessions.active().is_some() {
            let now = self.clock.now_us();
            // The STATE already handled while plugging is sent now (a newer one waits for flush).
            if self.has_state_phone && !self.has_pending && !self.paused && !self.locked && !self.released_for_silence {
                self.submit_pad(now);
            }
            self.send_status(now); // PAD_PLUGGED: the phone sends MOTION from now on
        }
        self.state_changed();
    }

    fn announce(&self, disc: &Discover, now_us: i64, b: &mut [u8]) -> usize {
        let mut flags = 0u16;
        if self.pairing.is_open(now_us) {
            flags |= announce_flags::PAIRING_OPEN;
        }
        if self.pad_driver_ok() {
            flags |= announce_flags::VIGEM_OK;
        }
        if self.phones.iter().any(|p| p.id == disc.phone_id) || self.key_phones.iter().any(|p| p.phone_id == disc.phone_id) {
            flags |= announce_flags::PAIRED_WITH_YOU;
        }
        if self.host_key_id == Some(disc.host_key_id) {
            flags |= announce_flags::KEY_MATCH;
        }
        if disc.phone_key_id.iter().any(|&x| x != 0) && self.authorized_key_ids.contains(&disc.phone_key_id) {
            flags |= announce_flags::KNOWS_YOUR_KEY;
        }
        if let Some(a) = self.sessions.active() {
            if !self.is_session_phone(a, disc) {
                flags |= announce_flags::BUSY;
            }
        }
        codec::encode_announce(
            &Announce { req_id: disc.req_id, host_id: self.settings.host_id, flags, name: self.settings.host_name.clone() },
            b,
        )
    }

    /// The DISCOVER comes from the phone holding `a`: the session's phone id (a code session, or a key session's own
    /// flow), or for a key session the phone's key id next to its store id.
    fn is_session_phone(&self, a: &HostSession, disc: &Discover) -> bool {
        if a.same_phone(&disc.phone_id) {
            return true;
        }
        self.key_phones.iter().any(|k| k.phone_id == a.phone_id && k.key_id == disc.phone_key_id)
    }

    fn reply_pairing(&mut self, reply: &[u8], to: SocketAddr) {
        self.pair_events();
        if !reply.is_empty() {
            self.reply(reply, to);
        }
        self.state_changed();
    }

    /// Advisory ERROR: only for requests of at least 16 bytes, 10/s per IP and 50/s overall.
    fn send_error(&mut self, code: u16, request_length: usize, to: SocketAddr, now_us: i64) {
        if request_length < wire::ERROR_SIZE || !self.error_limiter.try_acquire_from(to, now_us) {
            return;
        }
        let mut b = [0u8; wire::ERROR_SIZE];
        codec::encode_error(ErrorPacket { code, max_version: wire::VERSION }, &mut b);
        self.errors += 1;
        self.reply(&b, to);
    }

    fn send_pong(&self, sid: u32, plain: &[u8], recv_us: i64) {
        let t1 = codec::decode_ping(plain);
        let mut p = [0u8; wire::PONG_PLAIN];
        codec::encode_pong(Pong { t1, t2: recv_us as u64, t3: self.clock.now_us() as u64 }, &mut p);
        if let Some(s) = self.sessions.find(sid) {
            s.send_sealed(packet::PONG, &p, self.sender.as_deref());
        }
    }

    /// HOST_STATUS (with TEST_MODE before and KEY_LABELS after it) to the active session.
    fn send_status(&mut self, now_us: i64) {
        let Some(a) = self.sessions.active() else { return };
        let last_seq = a.highest_rx_seq();
        self.last_status_us = now_us;
        let mut flags = 0u8;
        if self.pad_driver_ok() {
            flags |= host_status_flags::VIGEM_OK;
        }
        if self.pad_out() != output_mode::NONE {
            flags |= host_status_flags::PAD_PLUGGED;
        }
        if self.paused || self.locked {
            flags |= host_status_flags::INPUT_PAUSED;
        }
        if self.elevated_foreground {
            flags |= host_status_flags::ELEVATED_FOREGROUND;
        }
        if self.network_public {
            flags |= host_status_flags::NETWORK_PUBLIC;
        }
        if self.overlay_on {
            flags |= host_status_flags::OVERLAY | host_status_flags::OVERLAY_TOTALS;
        }
        if self.overlay_compass {
            flags |= host_status_flags::OVERLAY_COMPASS;
        }
        let flags2 = (if self.settings.usb_direct { host_status_flags2::USB_DIRECT } else { 0 })
            | self.pc_setup
            | (if self.overlay_samples { host_status_flags2::OVERLAY_SAMPLES } else { 0 })
            | (if self.settings.diagnostics { host_status_flags2::PC_LOGGING } else { 0 });
        let mut p = [0u8; wire::HOST_STATUS_PLAIN];
        codec::encode_host_status(
            &HostStatus {
                last_seq,
                rx_per_sec: self.stats.rx_per_sec,
                loss_permille: self.stats.loss_permille,
                lat_p50_us: self.stats.lat_p50_us,
                lat_p99_us: self.stats.lat_p99_us,
                jitter_us: self.stats.jitter_us,
                output_mode: self.settings.output_mode,
                flags,
                flags2,
            },
            &mut p,
        );
        // TEST_MODE first, so HOST_STATUS stays the newest status the phone holds. While no test runs it goes only
        // with the few statuses after one ended (a lost "off" comes again).
        let test = self.test_mode_flags_now();
        if test != 0 || self.test_mode_off_repeats > 0 {
            if test == 0 {
                self.test_mode_off_repeats -= 1;
            }
            self.send_test_mode();
        }
        self.send_active(packet::HOST_STATUS, &p);
        if let Some(labels) = &self.key_labels {
            let mut k = [0u8; wire::KEY_LABELS_PLAIN];
            codec::encode_key_labels(labels, &mut k);
            self.send_active(packet::KEY_LABELS, &k);
        }
    }

    fn test_mode_flags_now(&self) -> u8 {
        let mut flags = 0u8;
        if self.settings.measure_buttons {
            flags |= test_mode_flags::PRESS_TIMES;
        }
        if self.link_test_until_us != i64::MIN {
            flags |= test_mode_flags::LINK_TEST;
        }
        if self.settings.diagnostics {
            flags |= test_mode_flags::PHONE_STATS | (self.diag_saves << test_mode_flags::DIAG_SAVE_SHIFT);
        }
        if self.overlay_keys {
            flags |= test_mode_flags::OVERLAY_KEYS;
        }
        flags
    }

    /// TEST_MODE: on change, and with HOST_STATUS while a test runs. Older phones drop it.
    fn send_test_mode(&self) {
        let mut t = [0u8; wire::TEST_MODE_PLAIN];
        codec::encode_byte4(self.test_mode_flags_now(), &mut t);
        self.send_active(packet::TEST_MODE, &t);
    }

    fn send_host_bye(&self, s: &HostSession, reason: u8) {
        let mut p = [0u8; wire::BYE_PLAIN];
        codec::encode_byte4(reason, &mut p);
        for _ in 0..3 {
            s.send_sealed(packet::HOST_BYE, &p, self.sender.as_deref());
        }
    }

    /// Sends a recorded rumble change as RUMBLE, at most 100 Hz. `repeat` (the tick only, never on the input path):
    /// a RUMBLE is one datagram, so the value the phone should have goes again every second while it is on and
    /// RUMBLE_OFF_REPEATS times after it stops; a lost "stop" would leave the phone vibrating.
    fn send_pending_rumble(&mut self, now_us: i64, repeat: bool) {
        let v = self.free.rumble_next.load(Ordering::SeqCst);
        if v & RUMBLE_DIRTY != 0 {
            if now_us.saturating_sub(self.last_rumble_us) < RUMBLE_MIN_INTERVAL_US {
                return;
            }
            if self.free.rumble_next.compare_exchange(v, v & 0xFFFF, Ordering::SeqCst, Ordering::SeqCst).is_err() {
                return; // a newer value: next call
            }
        }
        if self.sessions.active().is_none() {
            return;
        }
        let packed = v & 0xFFFF;
        if packed != self.last_rumble {
            if now_us.saturating_sub(self.last_rumble_us) < RUMBLE_MIN_INTERVAL_US {
                return;
            }
            self.rumble_repeats = if packed == 0 { RUMBLE_OFF_REPEATS } else { i32::MAX };
        } else {
            if !repeat || self.rumble_repeats == 0 || now_us.saturating_sub(self.last_rumble_us) < RUMBLE_REPEAT_US {
                return;
            }
            if self.rumble_repeats != i32::MAX {
                self.rumble_repeats -= 1;
            }
        }
        self.last_rumble = packed;
        self.last_rumble_us = now_us;
        let mut p = [0u8; wire::RUMBLE_PLAIN];
        codec::encode_rumble(Rumble { large: (packed >> 8) as u8, small: packed as u8, pattern: rumble_pattern::MOTORS }, &mut p);
        self.send_active(packet::RUMBLE, &p);
    }
}
