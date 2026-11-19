//! The small pieces: the clock, exact sleeps, the rx thread's scheduling, DPAPI, SendInput and whether we run
//! elevated.

use std::net::UdpSocket;
use std::os::windows::io::AsRawSocket;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::OnceLock;

use windows_sys::Win32::Foundation::{LocalFree, HANDLE, WAIT_FAILED, WAIT_OBJECT_0};
use windows_sys::Win32::Networking::WinSock as ws;
use windows_sys::Win32::Security::Cryptography::{CryptProtectData, CryptUnprotectData, CRYPT_INTEGER_BLOB};
use windows_sys::Win32::Security::{GetTokenInformation, TokenElevation, TOKEN_ELEVATION, TOKEN_QUERY};
use windows_sys::Win32::System::Performance::{QueryPerformanceCounter, QueryPerformanceFrequency};
use windows_sys::Win32::System::SystemInformation::GetTickCount64;
use windows_sys::Win32::System::Threading as th;
use windows_sys::Win32::UI::Input::KeyboardAndMouse as km;

use super::{last_error, wide, Handle};
use crate::host::SendInput;
use crate::mapping::{InjectEvent, InjectKind};
use crate::ports::{Clock, KeyMouseSink, KeyProtector};
use crate::sys;
use crate::udp::{CatchUpWait, Log};
use crate::watchdog::TickSleep;

// =====================================================================================
// the clock
// =====================================================================================

/// QueryPerformanceCounter as microseconds, exactly as the companion's StopwatchClock counts them (the two
/// processes compare times), and GetTickCount64 for the overlay packets' receive time.
pub struct QpcClock {
    us_per_tick: f64,
}

impl QpcClock {
    pub fn new() -> QpcClock {
        let mut freq = 0i64;
        // SAFETY: a valid pointer to an i64; the call cannot fail on Windows XP and later.
        unsafe {
            QueryPerformanceFrequency(&mut freq);
        }
        QpcClock { us_per_tick: 1_000_000.0 / freq.max(1) as f64 }
    }
}

impl Default for QpcClock {
    fn default() -> Self {
        QpcClock::new()
    }
}

impl Clock for QpcClock {
    fn now_us(&self) -> i64 {
        self.ticks_to_us(self.now_ticks())
    }

    fn tick_ms(&self) -> i64 {
        // SAFETY: no arguments.
        unsafe { GetTickCount64() as i64 }
    }

    fn now_ticks(&self) -> i64 {
        let mut t = 0i64;
        // SAFETY: a valid pointer to an i64.
        unsafe {
            QueryPerformanceCounter(&mut t);
        }
        t
    }

    fn ticks_to_us(&self, ticks: i64) -> i64 {
        (ticks as f64 * self.us_per_tick) as i64
    }
}

// =====================================================================================
// exact waits
// =====================================================================================

const CREATE_WAITABLE_TIMER_HIGH_RESOLUTION: u32 = 0x2;
const TIMER_ALL_ACCESS: u32 = 0x1F_0003;

fn high_resolution_timer() -> Option<Handle> {
    // SAFETY: no attributes, no name; the flags and access are plain numbers.
    Handle::new(unsafe {
        th::CreateWaitableTimerExW(std::ptr::null(), std::ptr::null(), CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS)
    })
}

/// Arms `timer` to fire `us` from now.
fn arm(timer: &Handle, us: i64) -> bool {
    let due: i64 = -us.max(0).saturating_mul(10); // relative, 100 ns units
    // SAFETY: a live timer handle; `due` outlives the call; no completion routine.
    unsafe { th::SetWaitableTimer(timer.raw(), &due, 0, None, std::ptr::null(), 0) != 0 }
}

/// A high-resolution waitable timer (Windows 10 1803 and later) for one thread's ticks: exact to well under a
/// millisecond without raising the system timer resolution. Falls back to a plain sleep.
pub struct HighResolutionSleep {
    timer: Option<Handle>,
}

impl HighResolutionSleep {
    pub fn new() -> HighResolutionSleep {
        HighResolutionSleep { timer: high_resolution_timer() }
    }
}

impl Default for HighResolutionSleep {
    fn default() -> Self {
        HighResolutionSleep::new()
    }
}

impl TickSleep for HighResolutionSleep {
    fn sleep(&mut self, us: i64) {
        if let Some(t) = &self.timer {
            if arm(t, us) {
                let ms = (us / 1000).saturating_add(1000).clamp(0, u32::MAX as i64 - 1) as u32;
                // SAFETY: a live handle.
                unsafe {
                    th::WaitForSingleObject(t.raw(), ms);
                }
                return;
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(((us.max(0) + 999) / 1000).max(1) as u64));
    }
}

/// gp-rx's wait while a mouse catch-up runs, exact to well under a millisecond without asking Windows for a
/// finer timer: the socket's read event (WSAEventSelect) and a high-resolution waitable timer, waited on
/// together. A datagram wakes it at once; only the timeout is exact instead of the 15.6 ms tick. WSAEventSelect
/// makes the socket non-blocking, so each wait ends by undoing it. Anything failing falls back to the system's
/// poll for good. gp-rx only.
pub struct ExactCatchUpWait {
    log: Log,
    /// The socket's read event and the timer.
    handles: Option<(Handle, Handle)>,
    broken: bool,
}

impl ExactCatchUpWait {
    pub fn new(log: Log) -> ExactCatchUpWait {
        ExactCatchUpWait { log, handles: None, broken: false }
    }

    fn broke(&mut self, why: &str) {
        if !self.broken {
            self.broken = true;
            (self.log)(&format!("catch-up wake: {why}; using the system's poll (about 15 ms steps)"));
        }
    }

    fn ready(&mut self) -> bool {
        if self.handles.is_some() {
            return true;
        }
        // SAFETY: no attributes, manual reset, not set, no name.
        let event = Handle::new(unsafe { th::CreateEventW(std::ptr::null(), 1, 0, std::ptr::null()) });
        match (event, high_resolution_timer()) {
            (Some(e), Some(t)) => {
                self.handles = Some((e, t));
                true
            }
            _ => {
                self.broke("no high-resolution timer");
                false
            }
        }
    }

    /// Back to a plain blocking socket: the event selection off first, then blocking (as Winsock wants).
    fn restore(&mut self, s: ws::SOCKET) {
        let mut blocking: u32 = 0;
        // SAFETY: a socket number (a closed one only makes the calls fail) and a valid pointer.
        let ok = unsafe { ws::WSAEventSelect(s, 0, 0) == 0 && ws::ioctlsocket(s, ws::FIONBIO, &mut blocking) == 0 };
        if !ok {
            // SAFETY: no arguments.
            let e = unsafe { ws::WSAGetLastError() };
            // A socket closed by a stop meanwhile is no reason to give up on this for a restart.
            if e != ws::WSAENOTSOCK {
                self.broke(&format!("could not make the socket blocking again ({e})"));
            }
        }
    }
}

impl CatchUpWait for ExactCatchUpWait {
    fn wait(&mut self, socket: &UdpSocket, us: u32) -> bool {
        if self.broken || !self.ready() {
            return sys::poll_readable(socket, us);
        }
        let s = socket.as_raw_socket() as ws::SOCKET;
        let (event, timer) = match &self.handles {
            Some((e, t)) => (e.raw(), t.raw()),
            None => return sys::poll_readable(socket, us),
        };
        // SAFETY: live handles and a socket number throughout this block; the handle array outlives the wait.
        unsafe {
            th::ResetEvent(event);
            if ws::WSAEventSelect(s, event as ws::WSAEVENT, ws::FD_READ as i32) != 0 {
                let e = ws::WSAGetLastError();
                if e != ws::WSAENOTSOCK {
                    self.broke(&format!("WSAEventSelect failed ({e})"));
                }
                self.restore(s);
                return sys::poll_readable(socket, us);
            }
        }
        // A datagram already waiting sets the event too; this only saves the wait.
        let got = if sys::available(socket) {
            true
        } else {
            let due: i64 = -(us as i64) * 10;
            // SAFETY: as above.
            let armed = unsafe { th::SetWaitableTimer(timer, &due, 0, None, std::ptr::null(), 0) != 0 };
            if !armed {
                self.broke(&format!("SetWaitableTimer failed ({})", last_error()));
                self.restore(s);
                return sys::poll_readable(socket, us);
            }
            let handles: [HANDLE; 2] = [event, timer];
            // SAFETY: as above.
            let r = unsafe { th::WaitForMultipleObjects(2, handles.as_ptr(), 0, us / 1000 + 50) };
            if r == WAIT_FAILED {
                self.broke(&format!("WaitForMultipleObjects failed ({})", last_error()));
            }
            r == WAIT_OBJECT_0
        };
        self.restore(s);
        got
    }
}

// =====================================================================================
// the rx thread's scheduling
// =====================================================================================

static MMCSS_LOGGED: AtomicBool = AtomicBool::new(false);
static QOS_PROCESS_LOGGED: AtomicBool = AtomicBool::new(false);
static QOS_THREAD_LOGGED: AtomicBool = AtomicBool::new(false);

fn log_once(flag: &AtomicBool, log: &Log, message: &str) {
    if !flag.swap(true, Ordering::Relaxed) {
        log(message);
    }
}

/// Registers the calling thread with the Multimedia Class Scheduler ("Games" task, high priority). False
/// (logging once) when it can't. The registration lives as long as the thread.
pub fn mmcss_enter(task: &str, log: &Log) -> bool {
    let name = wide(task);
    let mut index = 0u32;
    // SAFETY: a NUL-ended string and a valid pointer, both outliving the call.
    let h = unsafe { th::AvSetMmThreadCharacteristicsW(name.as_ptr(), &mut index) };
    if h.is_null() {
        log_once(&MMCSS_LOGGED, log, &format!("MMCSS '{task}' unavailable (error {})", last_error()));
        return false;
    }
    // SAFETY: the handle just returned.
    if unsafe { th::AvSetMmThreadPriority(h, th::AVRT_PRIORITY_HIGH) } == 0 {
        log_once(&MMCSS_LOGGED, log, &format!("MMCSS priority not set (error {})", last_error()));
    }
    true
}

const POWER_THROTTLING_CURRENT_VERSION: u32 = 1;
const POWER_THROTTLING_EXECUTION_SPEED: u32 = 0x1;

/// Opts the process (or the calling thread) out of execution-speed power throttling: Windows may otherwise
/// treat a process that never has focus as background work and run it on efficient cores at low clocks.
pub fn power_qos_off(process: bool, log: &Log) -> bool {
    // THREAD_POWER_THROTTLING_STATE and PROCESS_POWER_THROTTLING_STATE have the same layout.
    let state = th::THREAD_POWER_THROTTLING_STATE {
        Version: POWER_THROTTLING_CURRENT_VERSION,
        ControlMask: POWER_THROTTLING_EXECUTION_SPEED,
        StateMask: 0, // managed by us, and off
    };
    let size = std::mem::size_of::<th::THREAD_POWER_THROTTLING_STATE>() as u32;
    let p = (&state as *const th::THREAD_POWER_THROTTLING_STATE).cast();
    // SAFETY: the pseudo-handles of this process and thread are always valid; `state` outlives the call.
    let ok = unsafe {
        if process {
            th::SetProcessInformation(th::GetCurrentProcess(), th::ProcessPowerThrottling, p, size) != 0
        } else {
            th::SetThreadInformation(th::GetCurrentThread(), th::ThreadPowerThrottling, p, size) != 0
        }
    };
    if !ok {
        let (flag, what) = if process { (&QOS_PROCESS_LOGGED, "process") } else { (&QOS_THREAD_LOGGED, "thread") };
        log_once(flag, log, &format!("{what} power throttling not changed (error {})", last_error()));
    }
    ok
}

/// The process's own priority: above the game's normal-priority threads, so it is never queued behind them.
pub fn set_process_priority_high() -> bool {
    // SAFETY: the pseudo-handle of this process.
    unsafe { th::SetPriorityClass(th::GetCurrentProcess(), th::HIGH_PRIORITY_CLASS) != 0 }
}

/// Whether this process runs as administrator (its own token only: no other process is ever opened).
pub fn is_self_elevated() -> bool {
    static ELEVATED: OnceLock<bool> = OnceLock::new();
    *ELEVATED.get_or_init(|| {
        let mut token: HANDLE = std::ptr::null_mut();
        // SAFETY: the pseudo-handle of this process and a valid out pointer.
        if unsafe { th::OpenProcessToken(th::GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
            return false;
        }
        let Some(token) = Handle::new(token) else { return false };
        let mut elevation = TOKEN_ELEVATION { TokenIsElevated: 0 };
        let mut returned = 0u32;
        // SAFETY: a live token; the buffer is a TOKEN_ELEVATION of the size given.
        let ok = unsafe {
            GetTokenInformation(
                token.raw(),
                TokenElevation,
                (&mut elevation as *mut TOKEN_ELEVATION).cast(),
                std::mem::size_of::<TOKEN_ELEVATION>() as u32,
                &mut returned,
            )
        };
        ok != 0 && elevation.TokenIsElevated != 0
    })
}

// =====================================================================================
// DPAPI
// =====================================================================================

/// Wraps pairing keys with DPAPI for the current user, entropy "GyroPhone.v1" (what the C# side wrote can be
/// read, and the other way round).
pub struct DpapiKeyProtector;

const ENTROPY: &[u8] = b"GyroPhone.v1";

fn blob(data: &[u8]) -> CRYPT_INTEGER_BLOB {
    CRYPT_INTEGER_BLOB { cbData: data.len() as u32, pbData: data.as_ptr() as *mut u8 }
}

/// Copies what DPAPI returned and frees it.
fn take(out: CRYPT_INTEGER_BLOB) -> Vec<u8> {
    if out.pbData.is_null() {
        return Vec::new();
    }
    // SAFETY: DPAPI returned `cbData` readable bytes at `pbData`, allocated with LocalAlloc; freed exactly once.
    unsafe {
        let v = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
        // The plain key must not stay in freed memory.
        std::ptr::write_bytes(out.pbData, 0, out.cbData as usize);
        LocalFree(out.pbData.cast());
        v
    }
}

impl KeyProtector for DpapiKeyProtector {
    fn protect(&self, plain: &[u8]) -> Vec<u8> {
        let (input, entropy) = (blob(plain), blob(ENTROPY));
        let mut out = CRYPT_INTEGER_BLOB { cbData: 0, pbData: std::ptr::null_mut() };
        // SAFETY: the blobs point at live slices; no description, no prompt; `out` is a valid out pointer.
        let ok = unsafe { CryptProtectData(&input, std::ptr::null(), &entropy, std::ptr::null(), std::ptr::null(), 0, &mut out) };
        if ok == 0 {
            return Vec::new();
        }
        take(out)
    }

    fn unprotect(&self, blob_in: &[u8]) -> Option<Vec<u8>> {
        if blob_in.is_empty() {
            return None;
        }
        let (input, entropy) = (blob(blob_in), blob(ENTROPY));
        let mut out = CRYPT_INTEGER_BLOB { cbData: 0, pbData: std::ptr::null_mut() };
        // SAFETY: as in `protect`.
        let ok = unsafe { CryptUnprotectData(&input, std::ptr::null_mut(), &entropy, std::ptr::null(), std::ptr::null(), 0, &mut out) };
        if ok == 0 {
            return None;
        }
        Some(take(out))
    }
}

// =====================================================================================
// SendInput
// =====================================================================================

/// Release-all can produce one event per usage plus mouse buttons; anything larger is chunked.
const CAPACITY: usize = 272;

/// Keyboard and mouse through SendInput. Keys go as Set 1 scan codes (Pause on the VK path), mouse moves are
/// relative. One SendInput call per inject, from a buffer on the stack: no lock and no allocation, so it is
/// safe on the input path and from the crash hook.
pub struct SendInputSink {
    log: Log,
    calls: AtomicI64,
    short_writes: AtomicI64,
    logged_failure: AtomicBool,
}

impl SendInputSink {
    pub fn new(log: Log) -> SendInputSink {
        SendInputSink { log, calls: AtomicI64::new(0), short_writes: AtomicI64::new(0), logged_failure: AtomicBool::new(false) }
    }

    pub fn calls(&self) -> i64 {
        self.calls.load(Ordering::Relaxed)
    }
}

fn fill(e: &InjectEvent) -> km::INPUT {
    if e.kind == InjectKind::Key {
        km::INPUT {
            r#type: km::INPUT_KEYBOARD,
            Anonymous: km::INPUT_0 { ki: km::KEYBDINPUT { wVk: e.vk, wScan: e.scan, dwFlags: e.flags, time: 0, dwExtraInfo: 0 } },
        }
    } else {
        km::INPUT {
            r#type: km::INPUT_MOUSE,
            Anonymous: km::INPUT_0 {
                mi: km::MOUSEINPUT { dx: e.dx, dy: e.dy, mouseData: e.mouse_data as u32, dwFlags: e.flags, time: 0, dwExtraInfo: 0 },
            },
        }
    }
}

impl KeyMouseSink for SendInputSink {
    fn inject(&self, events: &[InjectEvent]) -> bool {
        // SAFETY: INPUT is plain data; all zero is a valid (empty mouse) INPUT.
        let mut buf: [km::INPUT; CAPACITY] = unsafe { std::mem::zeroed() };
        let mut all = true;
        for chunk in events.chunks(CAPACITY) {
            for (slot, e) in buf.iter_mut().zip(chunk) {
                *slot = fill(e);
            }
            let n = chunk.len() as u32;
            // SAFETY: `buf` holds at least `n` filled INPUTs of the size given.
            let sent = unsafe { km::SendInput(n, buf.as_ptr(), std::mem::size_of::<km::INPUT>() as i32) };
            self.calls.fetch_add(1, Ordering::Relaxed);
            if sent != n {
                all = false;
                self.short_writes.fetch_add(1, Ordering::Relaxed);
                if !self.logged_failure.swap(true, Ordering::Relaxed) {
                    (self.log)(&format!(
                        "SendInput inserted {sent} of {n} events (error {}); an elevated window or the lock screen is in front",
                        last_error()
                    ));
                }
            }
        }
        all
    }
}

impl SendInput for SendInputSink {
    fn short_writes(&self) -> i64 {
        self.short_writes.load(Ordering::Relaxed)
    }
}
