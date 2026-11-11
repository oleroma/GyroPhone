using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Crypto;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Host;

/// <summary>One latency row for the headless --latency-csv log.</summary>
public readonly record struct LatencySample(uint Seq, uint PhoneUs, long HostRecvUs, long LatencyUs, long InjectUs);

/// <summary>What the tray and status window show. Taken under the engine lock.</summary>
public sealed record HostSnapshot(
    string? PhoneName,
    bool Connected,
    bool Silent,
    OutputMode OutputMode,
    bool DriverOk,
    bool PadPlugged,
    bool Paused,
    bool ElevatedForeground,
    bool NetworkPublic,
    int PairingWindowS,
    (uint AttemptId, string PhoneName, string Sas)? PairPrompt,
    ushort RxPerSec,
    ushort LossPermille,
    uint LatP50Us,
    uint LatP99Us,
    uint JitterUs,
    LinkInfo? PhoneLink,
    bool AnyDatagramSeen,
    IReadOnlyList<PairedPhoneInfo> PairedPhones,
    HostCounters Counters,
    string? HostKeyFingerprint = null,
    int QrPairingS = 0,
    string? QrEnrolledPhone = null,
    // "Use PC controller for gyro ratchet": null while the phone's switch is off, "" while no controller is found.
    string? PcController = null,
    // The optional GyroPhone pad driver (driver/) is installed, so the DualSense output can plug.
    bool DualSenseOk = false)
{
    /// <summary>The driver the chosen output needs works: ours for the DualSense, ViGEmBus for the others.</summary>
    public bool OutputDriverOk => OutputMode == OutputMode.DualSense ? DualSenseOk : DriverOk;
}

/// <summary>"Measure buttons": the middle of the last <paramref name="Count"/> presses from input to game, its phone part, and the newest press (all µs).</summary>
public sealed record ButtonLatency(long TotalUs, long PhoneUs, int Count, long LastUs);

/// <summary>A paired phone for the tray: code-paired (in settings) or key-paired (a file in authorized_phones).</summary>
public sealed record PairedPhoneInfo(byte[] PhoneId, string Name, DateTime LastSeenUtc, bool ByKey = false,
    string? KeyFile = null, string? Fingerprint = null);

/// <summary>Diagnostic counters. Never contain key material.</summary>
public sealed record HostCounters(long Datagrams, long Dropped, long BadAuth, long States, long Injections,
    long UnknownUsages, long Errors, long Exceptions);

/// <summary>
/// The host protocol engine (SPEC 3.2). All state is guarded by one engine lock. Entry points:
/// OnDatagram (rx thread: control packets inline, newest STATE kept, the one before it injected first
/// when its keys or buttons differ), Flush (rx thread: inject once),
/// OnTick (watchdog), AnswerPair (UI). All I/O goes through the abstractions so it runs on Linux.
/// </summary>
public sealed class HostEngine
{
    private const long ReleaseTimeoutUs = Wire.ReleaseTimeoutMs * 1000L;
    private const long SessionTimeoutUs = Wire.SessionTimeoutMs * 1000L;
    private const long StatusIntervalUs = Wire.StatusIntervalMs * 1000L;
    private const long RumbleMinIntervalUs = 10_000; // at most 100 Hz
    private const int RumbleDirty = 1 << 16;
    private const long EndedSidQuietUs = 1_000_000; // stray BYE/STATE copies after an end draw no ERROR
    /// <summary>How long a pad button pressed and released inside one receive burst stays visible to polling games.</summary>
    private const long PadEdgeHoldUs = 20_000;
    private const long PadRetryUs = 100_000; // a broken ViGEm bus is retried at 10 Hz, not per packet
    /// <summary>The pad stays plugged this long after a session ends, so a reconnect finds the same controller.</summary>
    private const long PadIdleUnplugUs = 5 * 60 * 1_000_000L;

    private readonly object _lock = new();
    // Who holds the engine lock (for the input log: what held up a slow hand-off), and how deep the holder is in.
    private const string ByInput = "input", ByWatchdog = "watchdog", ByUi = "ui", BySettings = "settings save", ByProbes = "probes", ByPad = "pad";
    private volatile string _holder = "";
    private int _lockDepth;
    /// <summary>gp-rx's wait for the lock to take its newest datagram (Stopwatch ticks), and who had it.</summary>
    private (long Ticks, string? Holder) _datagramWait;
    private long _datagramGcPause, _handOffGcPause;
    /// <summary>What held up the pending STATE's hand-off so far (<see cref="InputRecorder.Inject"/>).</summary>
    private HandOffCauses _handOff;
    /// <summary>What the overlays, the UI and the held-keys marker read without the lock (<see cref="PublishView"/>).</summary>
    private readonly OverlayView _view = new();
    private bool _overlayDirty, _heldOutputsDirty;
    private readonly IKeyMouseSink _keyMouse;
    private readonly IKeyProtector _protector;
    private readonly IClock _clock;
    private readonly IHostEvents _events;
    private readonly HostSettings _settings;
    private readonly Dictionary<string, (PairedPhone Info, byte[] PairKey)> _phones = new();
    // Set at construction, or once later by SetHostKey when host.key was locked at start-up.
    private volatile P256? _hostKey;
    /// <summary>Key-paired phones by hex keyPhoneId; rebuilt from authorized_phones, never stored in settings.</summary>
    private readonly Dictionary<string, KeyPhone> _keyPhones = new();
    /// <summary>Hex KeyId of every authorized phone key, for ANNOUNCE KnowsYourKey.</summary>
    private readonly HashSet<string> _authorizedKeyIds = new();
    private byte[]? _hostKeyId;
    private string? _hostKeyFingerprint;
    /// <summary>The last list from authorized_phones, so a QR enrolment can add to it.</summary>
    private IReadOnlyList<AuthorizedPhone> _authorizedList = [];
    private QrSession? _qr;
    private readonly SessionManager _sessions;
    private readonly PairingManager _pairing;
    private readonly RateLimiter _errorLimiter = new(Wire.ErrorRatePerIp, Wire.ErrorRateGlobal);
    private readonly LinkStats _stats = new();
    private IPadSink _pad;
    // Plugging and unplugging the pad block in the driver, so they run on a pad thread outside the lock
    // (RequestPad). While one runs nothing is sent to the pad (PadOut is None); keys and mouse go on.
    /// <summary>What the pad thread makes the pad: the output mode, or None to unplug.</summary>
    private OutputMode _padWant;
    /// <summary>A plug or unplug is running on the pad thread.</summary>
    private bool _padBusy;
    /// <summary>Pad sinks replaced by <see cref="SetPadSink"/>: the pad thread unplugs and disposes them.</summary>
    private readonly List<IPadSink> _padRetired = [];
    private readonly ManualResetEventSlim _padIdle = new(true);
    /// <summary>Host time the last session ended: the pad is unplugged <see cref="PadIdleUnplugUs"/> later if none started.</summary>
    private long _padIdleFromUs;
    /// <summary>The phone of the last session: forgetting it with no session up unplugs the pad it would come back to.</summary>
    private byte[]? _padPhoneId;
    private IDatagramSender? _sender;

    // rx scratch, reused so the STATE path allocates nothing
    private readonly byte[] _plain = new byte[Wire.MaxDatagram];
    private readonly byte[] _reply = new byte[Wire.MaxDatagram];
    private readonly StateFrame _rxFrame = new();

    // "Use PC controller for gyro ratchet": the phone's controller gyro buttons, the controller plugged into
    // this PC, the gate that reads it, and the gyro run here on the phone's raw samples (STATE_GYRO).
    private IPcPad _pcPad = NullPcPad.Instance;
    private readonly PadGyroConfig _padGyro = new();
    private readonly GyroGate _gate = new();
    private readonly PcGyro _pcGyro = new();
    // MOTION: the phone's raw motion for the DualShock 4's gyro and accelerometer.
    private readonly MotionFrame _motionFrame = new();
    private readonly Ds4MotionFeed _ds4Motion = new();
    private readonly Ds4Motion[] _motionOut = new Ds4Motion[MotionFrame.MaxSamples];
    private readonly Func<long, uint> _pcHeldAt;
    private readonly uint[] _pcPresses = new uint[PadGyroConfig.Places];
    private bool _pcReading;
    private readonly StateFrame _pending = new();
    private bool _hasPending;
    private readonly MouseDeltaTracker _mouse = new();
    /// <summary>Wheel motion since the last Flush, both in 1/120 notch.</summary>
    private int _accWheel, _accHWheel;
    /// <summary>Mouse motion since the last Flush; a lost or late stretch catches up over a few ms instead of jumping.</summary>
    private readonly MouseCatchUp _catchUp = new();
    private uint _lastStatePhoneUs;
    private bool _hasStatePhone;
    private long _lostAtState;

    // injected state
    private readonly byte[] _injectedKeys = new byte[StateFrame.KeyBitmapSize];
    private byte _injectedMouse;
    private int _heldVersion;
    // Ups injected while UIPI or the secure desktop may have dropped them; re-sent when that ends.
    private readonly byte[] _blockedUps = new byte[StateFrame.KeyBitmapSize];
    private byte _blockedMouseUps;
    private readonly InjectEvent[] _inject = new InjectEvent[InputDiff.MaxEvents];
    private readonly KeyRepeat _keyRepeat = new();
    private readonly InjectEvent[] _releaseEvents = new InjectEvent[256 + 8];
    private X360Frame _lastX360;
    private Ds4Frame _lastDs4;
    private bool _padFrameValid;
    /// <summary>After a failed pad submit, SubmitPad waits until then instead of failing on every packet.</summary>
    private long _padRetryAfterUs = long.MinValue;
    // Pad buttons and hat from a frame injected mid-burst, kept pressed until _padHoldUntilUs.
    private ushort _padHoldButtons;
    private byte _padHoldHat = StateFrame.HatCenter;
    private long _padHoldUntilUs = long.MinValue;
    /// <summary>The last pad submit carried held buttons the newest STATE no longer has.</summary>
    private bool _padHeld;
    private readonly StateFrame _padMerged = new();
    // Raw pad buttons and hat of the last frame Flush submitted, and when each was pressed:
    // only a press younger than PadEdgeHoldUs is held, never a button held from long before.
    private ushort _lastPendingButtons;
    private byte _lastPendingHat = StateFrame.HatCenter;
    private readonly long[] _padPressUs = NewPressTimes();
    private long _hatPressUs = long.MinValue;

    private bool _paused, _locked;
    private bool _releasedForSilence;
    private bool _elevatedForeground, _networkPublic;
    /// <summary>The sink took fewer events than given (UIPI or the secure desktop): resynced once it takes them again.</summary>
    private bool _injectFailed;

    // Phone overlay: asked for in HOST_STATUS while on; the newest OVERLAY and layout from the active phone.
    private bool _overlayOn;
    // Latency tests: "Measure buttons" (PRESS_TIME) and the latency test window (the phone's markers).
    private readonly PressLatency _press = new();
    private bool MeasuringButtons => _settings.MeasureButtons;
    private bool _latencyScreen;
    /// <summary>Host time a Link test ends; long.MinValue: none.</summary>
    private long _linkTestUntilUs = long.MinValue;
    /// <summary>HOST_STATUSes still to carry a TEST_MODE of 0 after the last test ended.</summary>
    private int _testModeOffRepeats;
    private readonly OverlayFrame _overlayFrame = new();
    /// <summary>The last OVERLAY totals of this session, for the motion since then.</summary>
    private readonly OverlayTotals _overlayTotals = new();
    private readonly OverlayLayoutAssembler _overlayLayout = new();
    // Each overlay window's pose, by its settings id, turned by every OVERLAY while it is shown.
    private readonly Dictionary<string, OverlayPose> _overlayPoses = [];
    // The phone's Level displays presses last seen (-1 = none yet: the first count levels nothing).
    private int _levelPresses = -1;
    private long _overlayRxUs = long.MinValue;
    // "Fade when phone rests": whether the phone lies still at each overlay's Level, untouched.
    private readonly OverlayRest _overlayRest = new();
    private long _lastStatusUs;
    private KeyLabels? _keyLabels;
    private int _rumbleNext; // (large << 8) | small, plus RumbleDirty while unsent
    private int _lastRumble;
    private long _lastRumbleUs = -RumbleMinIntervalUs;
    private bool _settingsDirty;
    private bool _anyDatagram;
    private bool _loggedX360Dropped, _loggedPadFailure;
    private LatencySample _pendingSample;
    private bool _hasPendingSample;

    private readonly uint[] _endedSids = new uint[4];
    private readonly long[] _endedUntilUs = new long[4];
    private int _endedNext;

    /// <summary>Token ids of the last QR codes this PC showed: only these get an (unauthenticated) EXPIRED.</summary>
    private readonly byte[]?[] _issuedTokenIds = new byte[4][];
    private int _issuedNext;

    private long _datagrams, _dropped, _badAuth, _states, _injections, _unknownUsages, _errors, _exceptions;

    public HostEngine(HostSettings settings, IPadSink pad, IKeyMouseSink keyMouse, IPairPrompt prompt,
        IKeyProtector protector, IClock? clock = null, IHostEvents? events = null,
        Func<int, byte[]>? random = null, Func<P256>? newPairKey = null, P256? hostKey = null)
    {
        _settings = settings;
        _settings.Normalize();
        _catchUp.MaxUs = _settings.CatchUpMs * 1000L;
        _hostKey = hostKey;
        _hostKeyId = hostKey == null ? null : KeyPairing.KeyId(hostKey.PublicKey);
        _hostKeyFingerprint = hostKey == null ? null : KeyPairing.Fingerprint(hostKey.PublicKey);
        _pad = pad;
        _keyMouse = keyMouse;
        _protector = protector;
        _clock = clock ?? StopwatchClock.Instance;
        _pcHeldAt = us => _pcPad.HeldAt(us);
        Debug = new DebugTrace(() => _clock.NowUs);
        _events = new TracedEvents(events ?? NullHostEvents.Instance, Debug);
        _sessions = new SessionManager(random);
        _pairing = new PairingManager(prompt, () => (_settings.HostId, _settings.HostName), OnPaired, random, newPairKey);
        _pad.Rumble += OnRumble;
        foreach (var p in settings.PairedPhones)
        {
            try
            {
                _phones[Convert.ToHexString(p.PhoneId)] = (p, _protector.Unprotect(p.ProtectedKey));
            }
            catch (Exception e) when (e is CryptographicException or FormatException or ArgumentException)
            {
                _events.Log($"paired phone '{p.Name}' key unreadable; pair it again");
            }
        }
    }

    /// <summary>The debug log (see <see cref="DebugTrace"/>); the Debug window reads it.</summary>
    public DebugTrace Debug { get; }

    /// <summary>
    /// The phone asked for a debug recording (LINK_INFO's save counter changed). Called on the rx
    /// thread, under the engine lock: hand the work to another thread.
    /// </summary>
    public Action? DebugSaveRequested { get; set; }

    /// <summary>The save counter of the last LINK_INFO with the debug flag; -1 until one in this session.</summary>
    private int _debugSaves = -1;

    /// <summary>Optional hook for the headless latency log; called on the rx thread after injection.</summary>
    public Action<LatencySample>? LatencySampleHook { get; set; }

    public HostSettings Settings => _settings;

    /// <summary>The input log (docs/spec/pc-companion.md, "Input log"); null = none. Set before Start. Written only while <see cref="HostSettings.InputLog"/>.</summary>
    public InputRecorder? Recorder
    {
        get => _recorder;
        set
        {
            _recorder = value;
            // Its lines are written once the engine lock is free (Unlock), never under it.
            if (value != null) value.Deferred = true;
        }
    }

    private InputRecorder? _recorder;
    private InputRecorder? Rec => _settings.InputLog ? Recorder : null;

    /// <summary>Runs a pad plug or unplug job; null: on a new "gp-pad" thread. Tests run it at once.</summary>
    internal Action<Action>? PadWorker { get; init; }

    /// <summary>"Record input log": on, a session already up starts its log now; off, it ends it.</summary>
    public void SetInputLog(bool on)
    {
        using (Lock(ByUi))
        {
            if (on == _settings.InputLog) return;
            if (!on) Recorder?.SessionEnded("input log turned off", _clock.NowUs);
            _settings.InputLog = on;
            _settingsDirty = true;
            if (on && _sessions.Active is { } a) Recorder?.SessionStarted(a.PhoneName, _clock.NowUs);
        }
    }
    private long _pendingRecvUs = -1;

    public void AttachSender(IDatagramSender sender)
    {
        using (Lock(ByUi)) _sender = sender;
    }

    /// <summary>Call once after construction: opens the pairing window on first run when nothing is paired.</summary>
    public void Start(long nowUs)
    {
        using (Lock(ByUi))
        {
            if (_phones.Count == 0 && _keyPhones.Count == 0) _pairing.Open(nowUs);
        }
    }

    /// <summary>
    /// Takes the engine lock for <paramref name="role"/> (the input log names it when gp-rx had to wait).
    /// Re-entrant. When the outermost holder lets go, what changed is published for the lock-free readers,
    /// and the input log's new lines are written after the lock is free.
    /// </summary>
    private Scope Lock(string role)
    {
        if (!Monitor.TryEnter(_lock))
        {
            string by = _holder;
            long t0 = System.Diagnostics.Stopwatch.GetTimestamp();
            Monitor.Enter(_lock);
            if (_lockDepth == 0 && role == ByInput)
            {
                long waited = System.Diagnostics.Stopwatch.GetTimestamp() - t0;
                _datagramWait = (waited, by);
                // A wait while a STATE is pending holds up its hand-off too.
                if (_hasPending) _handOff.AddLockWait(waited, by);
            }
        }
        else if (_lockDepth == 0 && role == ByInput)
        {
            _datagramWait = (0, null);
        }
        if (_lockDepth++ == 0) _holder = role;
        return new Scope(this);
    }

    private void Unlock()
    {
        bool outer = --_lockDepth == 0;
        try
        {
            if (outer) PublishView();
        }
        finally
        {
            Monitor.Exit(_lock);
        }
        if (outer) Recorder?.WritePending();
    }

    private readonly struct Scope(HostEngine engine) : IDisposable
    {
        public void Dispose() => engine.Unlock();
    }

    /// <summary>
    /// Copies what changed into the lock-free view (under the lock, as its one writer). The copies wait while a
    /// STATE is pending: the Flush right after the burst publishes them once the input is handed off.
    /// </summary>
    private void PublishView()
    {
        bool connected = _sessions.Active != null;
        if (_view.Connected != connected) _view.Connected = connected;
        if (_view.PadGyroOn != _padGyro.Enabled) _view.PadGyroOn = _padGyro.Enabled;
        if (_catchingUp != _catchUp.CatchingUp) _catchingUp = _catchUp.CatchingUp;
        if (_hasPending) return;
        if (_heldVersion != _view.HeldVersion) _view.PublishInjected(_injectedKeys, _injectedMouse, _heldVersion);
        // What a laser pointer's Fire and Aim displays read: after the hand-off (or a release that dropped the
        // pending STATE, such as the release after silence), so it never delays it.
        if (_heldOutputsDirty)
        {
            _heldOutputsDirty = false;
            _view.PublishHeldOutputs(_pending);
        }
        if (_overlayDirty)
        {
            _overlayDirty = false;
            _view.PublishOverlay(_overlayFrame, _overlayRxUs, _overlayLayout.Layout, _overlayRest, _overlayPoses, _settings.Overlays);
        }
    }

    // =====================================================================================
    // rx path
    // =====================================================================================

    /// <summary>Validates and handles one datagram (SPEC 1.7 order). Control replies go out inline; a STATE is kept for Flush.</summary>
    public void OnDatagram(ReadOnlySpan<byte> d, SocketAddress from, long nowUs)
    {
        // For the input log's slow hand-offs: the garbage collector's pauses so far, before waiting for the lock.
        long gcPause = Recorder != null ? GC.GetTotalPauseDuration().Ticks : 0;
        using (Lock(ByInput))
        {
            _datagramGcPause = gcPause;
            _datagrams++;
            if (!_anyDatagram)
            {
                _anyDatagram = true;
                _events.StateChanged();
            }
            try
            {
                Handle(d, from, nowUs);
            }
            catch (Exception e) when (e is not OutOfMemoryException)
            {
                _exceptions++;
                _events.Log($"packet handling failed: {e.GetType().Name}");
            }
        }
    }

    private void Handle(ReadOnlySpan<byte> d, SocketAddress from, long nowUs)
    {
        if (d.Length < Wire.Header || d[0] != Wire.Magic0 || d[1] != Wire.Magic1)
        {
            _dropped++;
            return;
        }
        if (d[2] != Wire.Version)
        {
            _dropped++;
            SendError(ErrorCode.Version, d.Length, from, nowUs);
            return;
        }
        byte type = d[3];
        if (PacketType.Size(type) != d.Length)
        {
            _dropped++;
            return;
        }

        switch (type)
        {
            case PacketType.Discover:
                if (WireCodec.TryDecodeDiscover(d, out var disc)) Reply(Announce(disc, nowUs), from);
                return;
            case PacketType.PairStart:
                if (WireCodec.TryDecodePairStart(d, out var ps)) ReplyPairing(_pairing.OnStart(ps, nowUs, _reply), from);
                else _dropped++;
                return;
            case PacketType.PairReveal:
                if (WireCodec.TryDecodePairReveal(d, out var pr)) ReplyPairing(_pairing.OnReveal(pr, nowUs, _reply), from);
                return;
            case PacketType.PairConfirm:
                if (WireCodec.TryDecodePairConfirm(d, out var pc)) ReplyPairing(_pairing.OnConfirm(pc, nowUs, _reply), from);
                return;
            case PacketType.Hello:
                OnHello(d, from, nowUs);
                return;
            case PacketType.LinkTestCtl:
                // Only a program on this PC may start one (tools/link-test.sh); anything else is dropped.
                if (from.Family == AddressFamily.InterNetwork && from[4] == 127) SetLinkTest(Le.U16(d, Wire.Header), "asked on this PC");
                else _dropped++;
                return;
            case PacketType.KeyEnroll:
                if (WireCodec.TryDecodeKeyEnroll(d, out var ke)) OnKeyEnroll(d, ke, from, nowUs);
                else _dropped++;
                return;
        }

        if (!PacketType.IsPhoneToHostSealed(type))
        {
            _dropped++; // host-to-phone types are never accepted here
            return;
        }

        uint sid = Le.U32(d, 4);
        var s = _sessions.Find(sid);
        if (s == null)
        {
            _dropped++;
            // A BYE needs no answer, and the trailing copies of a just-ended session are expected.
            if (type == PacketType.Bye || RecentlyEnded(sid, nowUs)) return;
            SendError(ErrorCode.SessionUnknown, d.Length, from, nowUs);
            return;
        }
        if (!s.TryOpen(d, _plain))
        {
            _badAuth++; // stale seq or failed tag: silence
            return;
        }
        s.UpdateEndpoint(from);
        s.LastRxUs = nowUs;
        uint seq = Le.U32(d, 8);
        var plain = _plain.AsSpan(0, PacketType.PlainSize(type));

        if (!s.Promoted)
        {
            if (type != PacketType.State && type != PacketType.StateHires && type != PacketType.StateGyro)
            {
                HandlePendingControl(s, type, plain, nowUs);
                return;
            }
            Promote(s, nowUs);
        }

        long lostBefore = _stats.TotalLost;
        _stats.OnSealed(seq);
        if (_stats.TotalLost != lostBefore) Rec?.Lost(_stats.TotalLost - lostBefore, seq, nowUs);
        switch (type)
        {
            case PacketType.State:
            case PacketType.StateHires:
            case PacketType.StateGyro:
                OnState(plain, seq, nowUs);
                break;
            case PacketType.PadGyro:
                OnPadGyro(plain);
                break;
            case PacketType.PadGyroSet:
                _padGyro.DecodeHeld(plain);
                break;
            case PacketType.Ping:
                SendPong(s, plain, nowUs);
                break;
            case PacketType.LinkInfo:
                var info = WireCodec.DecodeLinkInfo(plain);
                _stats.OnLinkInfo(info);
                OnDebugFlags(info.Flags, nowUs);
                break;
            case PacketType.Overlay:
                if (!_overlayOn || _overlayTotals.IsLate(plain)) break;
                _overlayFrame.Decode(plain);
                _overlayTotals.ToDeltas(_overlayFrame);
                if (_padGyro.Enabled) GateOverlay(_overlayFrame);
                foreach (var o in _settings.Overlays)
                {
                    if (!o.Shown) continue;
                    if (!_overlayPoses.TryGetValue(o.Id, out var pose)) _overlayPoses[o.Id] = pose = new OverlayPose();
                    pose.Feed(_overlayFrame, o);
                }
                // Set orientation on the phone: every overlay shown starts again from its new base orientation.
                if (_levelPresses >= 0 && _overlayFrame.LevelPresses != _levelPresses)
                    foreach (var o in _settings.Overlays)
                        if (o.Shown) StartOverOverlay(o);
                _levelPresses = _overlayFrame.LevelPresses;
                _overlayRest.Feed(_overlayFrame, nowUs, _settings);
                _overlayRxUs = nowUs;
                _overlayDirty = true;
                break;
            case PacketType.OverlayLayout:
                if (_overlayOn)
                {
                    _overlayLayout.Add(plain);
                    _overlayDirty = true;
                }
                break;
            case PacketType.Motion:
                OnMotion(plain, nowUs);
                break;
            case PacketType.PressTime:
                if (MeasuringButtons)
                    _press.OnPressTime(WireCodec.DecodePressTime(plain), nowUs, ClockOffsetUs, _stats.LastLinkInfo != null);
                break;
            case PacketType.Bye:
                _events.Log($"phone said bye (reason {plain[0]})");
                RememberEnded(s.SessionId);
                EndActive(hostByeReason: null, why: $"phone said bye ({plain[0]})");
                break;
        }
    }

    /// <summary>PAD_GYRO: which controller buttons ratchet or toggle the gyro; reads the PC controller while the phone's switch is on.</summary>
    private void OnPadGyro(ReadOnlySpan<byte> plain)
    {
        bool was = _padGyro.Enabled;
        _padGyro.Decode(plain);
        if (_padGyro.Enabled == was) return;
        if (_padGyro.Enabled) StartPcPad();
        else
        {
            StopPcPad();
            _gate.ClearPc(); // off: no PC toggle or ratchet is left turning the gyro over
        }
        _events.StateChanged();
    }

    private void StartPcPad()
    {
        if (_pcReading) return;
        _pcReading = true;
        _pcPad.Start(() => _pad.XInputSlot);
        _events.Log("PC controller for gyro ratchet: on");
    }

    private void StopPcPad()
    {
        if (!_pcReading) return;
        _pcReading = false;
        _pcPad.Stop();
        _events.Log("PC controller for gyro ratchet: off");
    }

    private void ReadPcPad()
    {
        bool connected = _pcPad.Read(_pcPresses, out uint held);
        _gate.Update(new PcPadState { Connected = connected, Held = held, Presses = _pcPresses }, _padGyro);
    }

    /// <summary>
    /// A laser pointer overlay's fire output <paramref name="code"/> (LaserFire) is held: sent by the phone now, or
    /// pressed on the PC's controller with "Use PC controller for gyro ratchet" on.
    /// Read without the engine lock (the overlay windows ask every frame), the PC controller from its reader's own copy.
    /// </summary>
    public bool OutputHeld(int code)
    {
        if (code == 0) return false;
        Span<float> axes = stackalloc float[6];
        uint held = 0;
        bool pc = _view.PadGyroOn && Volatile.Read(ref _pcPad).Peek(out held, axes);
        return _view.Held(code, pc ? held : 0, pc ? axes[4] : 0f, pc ? axes[5] : 0f);
    }

    /// <summary>The overlays turn with the axes on after the PC controller, and its ratchets count for "On ratchet".</summary>
    private void GateOverlay(OverlayFrame f)
    {
        ReadPcPad();
        int eff = _gate.EffectiveOf(f.Axes, _pcGyro.MayMove);
        f.Axes = (byte)eff;
        f.Flags = (byte)(eff != 0 ? f.Flags | OverlayFrame.FlagGyroActive : f.Flags & ~OverlayFrame.FlagGyroActive);
        if (_gate.Flips != 0) f.Flags |= OverlayFrame.FlagRatchetHeld;
        f.RatchetPresses = unchecked((byte)(f.RatchetPresses + _gate.RatchetPresses));
    }

    /// <summary>
    /// A window whose handles show the PC controller: its buttons, triggers and sticks in place of the
    /// phone's controller, none while the phone's "Use PC controller for gyro ratchet" is off.
    /// </summary>
    private void PcPadOnHandles(OverlayFrame f)
    {
        Span<float> axes = stackalloc float[6];
        uint held = 0;
        bool on = _view.PadGyroOn && Volatile.Read(ref _pcPad).Peek(out held, axes);
        f.PadFlags = on ? OverlayFrame.PadAttachedFlag : (byte)0;
        f.PadButtons = on ? held : 0;
        f.PadL2Pull = on ? axes[4] : 0f;
        f.PadR2Pull = on ? axes[5] : 0f;
        for (int i = 0; i < 4; i++) f.PadSticks[i] = on ? axes[i] : 0f;
    }

    /// <summary>Uses <paramref name="pad"/> as the controller plugged into this PC (Windows: XInput).</summary>
    public void SetPcPad(IPcPad pad)
    {
        using (Lock(ByUi))
        {
            bool reading = _pcReading;
            StopPcPad();
            Volatile.Write(ref _pcPad, pad);
            if (reading) StartPcPad();
        }
    }

    private void OnDebugFlags(byte flags, long nowUs)
    {
        if ((flags & LinkInfoFlags.DebugTrace) == 0) return;
        Debug.PhoneAsked(nowUs);
        int saves = (flags & LinkInfoFlags.DebugSaveMask) >> LinkInfoFlags.DebugSaveShift;
        bool asked = _debugSaves >= 0 && saves != _debugSaves;
        _debugSaves = saves;
        if (!asked) return;
        Debug.Add(DebugTrace.Kind.Note, "the phone asked for a recording", nowUs);
        DebugSaveRequested?.Invoke();
    }

    /// <summary>One line of link statistics for the debug log.</summary>
    private string DebugLinkLine()
    {
        string lat = _stats.LatP50Us == HostStatus.Unknown ? "latency unknown" :
            $"latency {_stats.LatP50Us / 1000.0:0.0} / {_stats.LatP99Us / 1000.0:0.0} ms (p50/p99)";
        return $"got {_stats.RxPerSec}/s · loss {_stats.LossPermille / 10.0:0.0}% · {lat} · jitter {_stats.JitterUs / 1000.0:0.0} ms" +
            $" · output {PadOut}" + (_paused ? " · paused" : "") + (_locked ? " · locked" : "") +
            (_elevatedForeground || _injectFailed ? " · an admin window has focus (input blocked)" : "");
    }

    private void HandlePendingControl(HostSession s, byte type, ReadOnlySpan<byte> plain, long nowUs)
    {
        if (type == PacketType.Ping) SendPong(s, plain, nowUs);
        else if (type == PacketType.Bye)
        {
            RememberEnded(s.SessionId);
            _sessions.Remove(s);
        }
    }

    private void OnState(ReadOnlySpan<byte> plain, uint seq, long nowUs)
    {
        _states++;
        // What holds up this STATE's hand-off from here (the input log names it when slow): the wait for the
        // engine lock to take this packet, then every lock wait and driver call until it is injected.
        _handOff = HandOffCauses.Waited(_datagramWait.Ticks, _datagramWait.Holder);
        _handOffGcPause = _datagramGcPause;
        _rxFrame.Decode(plain);
        if (_padGyro.Enabled) ReadPcPad();
        // The PC controller ratchets each sample by what it held then (same clock as nowUs).
        _pcGyro.Apply(_rxFrame, nowUs, _gate, _padGyro, _padGyro.Enabled ? _pcHeldAt : null);
        // A burst can hold a press and its release: inject the older frame first so no edge is lost.
        // Frames that differ only in motion still merge into one injection.
        // Flush re-enters the engine lock OnDatagram holds.
        if (_hasPending && EdgesDiffer(_pending, _rxFrame)) Flush(nowUs, edge: true);
        _pending.CopyFrom(_rxFrame);
        _hasPending = true;
        _heldOutputsDirty = true;
        _releasedForSilence = false;
        _mouse.Apply(_rxFrame.MouseTotalX, _rxFrame.MouseTotalY, _rxFrame.WheelTotal, _rxFrame.HWheelTotal,
            _rxFrame.Hires, nowUs, out int dx, out int dy, out int dw, out int dh);
        // Packets lost since the STATE before (seq is shared by every type) mean this delta
        // also carries their motion, over the phone time since that STATE.
        long span = _hasStatePhone ? unchecked((int)(_rxFrame.PhoneTimeUs - _lastStatePhoneUs)) : 0;
        bool lost = _hasStatePhone && _stats.TotalLost != _lostAtState;
        _lastStatePhoneUs = _rxFrame.PhoneTimeUs;
        _lostAtState = _stats.TotalLost;
        _hasStatePhone = true;
        _catchUp.Add(dx, dy, span, lost);
        _pendingRecvUs = nowUs;
        if (Rec is { } rec)
        {
            bool moving = dx != 0 || dy != 0;
            bool held = _rxFrame.MouseButtons != 0 || _rxFrame.Buttons != 0 || _rxFrame.Hat != StateFrame.HatCenter || _rxFrame.Keys.AsSpan().IndexOfAnyExcept((byte)0) >= 0;
            rec.State(_rxFrame.PhoneTimeUs, nowUs, moving, moving || held, lost);
        }
        // Plain STATE counts whole notches.
        _accWheel += _rxFrame.Hires ? dw : dw * InjectEvent.WheelDelta;
        _accHWheel += dh;
        long lat = _stats.OnState(_rxFrame.PhoneTimeUs, nowUs);
        if (LatencySampleHook != null)
        {
            _pendingSample = new LatencySample(seq, _rxFrame.PhoneTimeUs, nowUs, lat == long.MinValue ? -1 : lat, 0);
            _hasPendingSample = true;
        }
    }

    private void OnHello(ReadOnlySpan<byte> d, SocketAddress from, long nowUs)
    {
        if (!WireCodec.TryDecodeHello(d, out var hello)) return;
        var hex = Convert.ToHexString(hello.PhoneId);
        bool byCode = _phones.TryGetValue(hex, out var phone);
        bool byKey = _keyPhones.TryGetValue(hex, out var keyPhone);
        if (!byCode && !byKey)
        {
            SendError(ErrorCode.NotPaired, d.Length, from, nowUs);
            return;
        }
        byte[] pairKey;
        string name;
        if (byCode && WireCodec.VerifyMac(phone.PairKey, d)) (pairKey, name) = (phone.PairKey, phone.Info.Name);
        else if (byKey && WireCodec.VerifyMac(keyPhone!.PairKey, d)) (pairKey, name) = (keyPhone.PairKey, keyPhone.Name);
        else
        {
            _badAuth++; // bad mac: no reply at all
            return;
        }
        // A retransmitted HELLO gets the same WELCOME and sid; it never disturbs the active session.
        var existing = _sessions.FindByHello(hello.PhoneId, hello.NP);
        if (existing != null)
        {
            Reply(existing.Welcome, from);
            return;
        }
        var s = _sessions.CreatePending(hello, name, pairKey, _settings.HostId,
            (byte)_settings.OutputMode, (byte)((PadDriverOk ? WelcomeFlags.VigemOk : 0) | WelcomeFlags.HiresWheel | WelcomeFlags.PcGyro | WelcomeFlags.Motion),
            from, nowUs);
        Reply(s.Welcome, from);
    }

    private void Promote(HostSession s, long nowUs)
    {
        _debugSaves = -1;
        var old = _sessions.Promote(s);
        // A new session's OVERLAY totals start from a new base.
        _overlayTotals.Reset();
        _overlayDirty = true;
        if (old != null)
        {
            if (old.SamePhone(s.PhoneId))
            {
                // The same phone moved to a new session (network change, HOST_SILENT recovery): what it
                // holds stays injected and its first STATE is diffed against it. The old session's
                // last STATE goes in first; its late copies draw no ERROR.
                if (_hasPending) Flush(nowUs, edge: false);
                RememberEnded(old.SessionId);
                // Its app may have restarted: the next layout is taken even with the version shown now,
                // and its press count starts again.
                _overlayLayout.Renew();
                foreach (var pose in _overlayPoses.Values) pose.ForgetPresses();
                _levelPresses = -1;
            }
            else
            {
                ReleaseAllLocked();
                SendHostBye(old, HostByeReason.Replaced);
                // Nothing of the old phone's overlay or PC controller carries over.
                _overlayLayout.Reset();
                _overlayPoses.Clear();
                _levelPresses = -1;
                _overlayRxUs = long.MinValue;
                _overlayRest.Clear(_clock.NowUs);
                StopPcPad();
                _padGyro.Enabled = false;
                _gate.Reset();
                _pcGyro.Reset();
            }
            old.Dispose();
        }
        // The phone restarts its totals and gyro samples at 0 for a new session; the PC controller's toggle stays.
        _mouse.Reset();
        _pcGyro.Rebase();
        _accWheel = _accHWheel = 0;
        _catchUp.Clear();
        _hasStatePhone = false;
        _stats.Reset();
        _press.Reset();
        _releasedForSilence = false;
        _hasPending = false;
        _padPhoneId = s.PhoneId;
        PlugPad();
        if (_phones.TryGetValue(Convert.ToHexString(s.PhoneId), out var phone))
        {
            phone.Info.LastSeenUtc = DateTime.UtcNow;
            _settingsDirty = true;
        }
        else if (_keyPhones.TryGetValue(Convert.ToHexString(s.PhoneId), out var keyPhone))
        {
            keyPhone.LastSeenUtc = DateTime.UtcNow;
        }
        _phoneList = null;
        _events.Log($"session started with '{s.PhoneName}'");
        Rec?.SessionStarted(s.PhoneName, nowUs);
        SendStatus(s, nowUs);
        _events.StateChanged();
    }

    /// <summary>
    /// A lost or late stretch of mouse motion is still catching up: <see cref="Flush(long)"/> should run within a
    /// millisecond, even without packets. Read without the engine lock (set as each locked call ends).
    /// </summary>
    public bool MouseCatchingUp => _catchingUp;

    private volatile bool _catchingUp;

    /// <summary>Injects the newest STATE received since the last Flush: pad only when the mapped frame changed, SendInput only with events.</summary>
    public void Flush(long nowUs) => Flush(nowUs, edge: false);

    /// <summary><paramref name="edge"/>: a newer STATE with other keys or buttons replaces this one in the same burst.</summary>
    private void Flush(long nowUs, bool edge)
    {
        using (Lock(ByInput))
        {
            FlushLocked(nowUs, edge);
        }
    }

    private void FlushLocked(long nowUs, bool edge)
    {
        SendPendingRumble(nowUs);
        // Without a new STATE, only a running catch-up has something to move.
        bool frame = _hasPending;
        if (!frame && !_catchUp.CatchingUp) return;
        _hasPending = false;
        _catchUp.Take(nowUs, out int dx, out int dy);
        if (_catchUp.BacklogTaken) Rec?.CatchUp(_catchUp.BacklogSpanUs, _catchUp.BacklogPx, _catchUp.BacklogCatchUpUs, nowUs);
        int dw = _accWheel, dh = _accHWheel;
        _accWheel = _accHWheel = 0;
        if ((_pending.Flags & StateFlags.LinkTest) != 0)
        {
            // Link test input is counted (input log, catch-up), never moved.
            dx = dy = dw = dh = 0;
        }
        if (_paused || _locked)
        {
            _catchUp.Clear();
            return; // baseline already updated, nothing injected
        }
        if (!frame && dx == 0 && dy == 0) return;

        if (frame)
        {
            SubmitPad(_pending, nowUs);
            NotePadPresses(nowUs);
            if (edge) HoldPadEdge(nowUs);
        }

        bool blocked = _elevatedForeground;
        Span<byte> before = stackalloc byte[StateFrame.KeyBitmapSize];
        byte beforeMouse = _injectedMouse;
        _injectedKeys.CopyTo(before);
        int unknown = 0;
        int n = InputDiff.Diff(_injectedKeys, _pending.Keys, ref _injectedMouse, _pending.MouseButtons,
            Math.Clamp(dx, -Wire.MaxMouseDelta, Wire.MaxMouseDelta),
            Math.Clamp(dy, -Wire.MaxMouseDelta, Wire.MaxMouseDelta),
            Math.Clamp(dw, -Wire.MaxWheelDeltaHires, Wire.MaxWheelDeltaHires),
            Math.Clamp(dh, -Wire.MaxWheelDeltaHires, Wire.MaxWheelDeltaHires),
            _inject, ref unknown);
        _unknownUsages += unknown;
        if (blocked) NoteBlockedUps(before, beforeMouse);
        if (n > 0)
        {
            long t0 = System.Diagnostics.Stopwatch.GetTimestamp();
            bool took = _keyMouse.Inject(_inject.AsSpan(0, n));
            _handOff.SendInputTicks += System.Diagnostics.Stopwatch.GetTimestamp() - t0;
            if (!took)
            {
                // Windows dropped them: the ups are sent again once it takes input.
                if (!blocked) NoteBlockedUps(before, beforeMouse);
                InjectFailed();
            }
            if (Rec is { } rec)
            {
                if (frame) _handOff.GcPauseUs = (GC.GetTotalPauseDuration().Ticks - _handOffGcPause) / TimeSpan.TicksPerMicrosecond;
                rec.Inject(dx, dy, frame ? _pendingRecvUs : -1, _clock.NowUs, !took, _handOff);
            }
            _injections++;
            if (Debug.Enabled) Debug.OnInject(_inject.AsSpan(0, n), nowUs);
            for (int i = 0; i < n; i++)
            {
                if (_inject[i].Kind == InjectKind.Key || (_inject[i].Flags & ~(InjectEvent.MouseMove | InjectEvent.MouseWheel | InjectEvent.MouseHWheel)) != 0)
                {
                    _heldVersion++;
                    break;
                }
            }
        }
        if (frame && MeasuringButtons) _press.OnInjected(_pending.PhoneTimeUs, _clock.NowUs, ClockOffsetUs, _stats.LastLinkInfo != null);
        if (frame && _hasPendingSample)
        {
            _hasPendingSample = false;
            LatencySampleHook?.Invoke(_pendingSample with { InjectUs = _clock.NowUs });
        }
    }

    /// <summary>Discrete input differs: what one injection per burst could lose (a press and its release).</summary>
    private static bool EdgesDiffer(StateFrame a, StateFrame b) =>
        a.Buttons != b.Buttons || a.Hat != b.Hat || a.MouseButtons != b.MouseButtons || !a.Keys.AsSpan().SequenceEqual(b.Keys);

    private static long[] NewPressTimes()
    {
        var t = new long[16];
        Array.Fill(t, long.MinValue);
        return t;
    }

    /// <summary>
    /// Records when each pad button and hat direction of the frame just submitted was pressed.
    /// </summary>
    private void NotePadPresses(long nowUs)
    {
        int pressed = _pending.Buttons & ~_lastPendingButtons;
        for (int i = 0; pressed != 0; i++, pressed >>= 1)
            if ((pressed & 1) != 0) _padPressUs[i] = nowUs;
        byte hat = PadMapper.HatOf(_pending);
        if (hat != _lastPendingHat && hat != StateFrame.HatCenter) _hatPressUs = nowUs;
        _lastPendingButtons = _pending.Buttons;
        _lastPendingHat = hat;
    }

    /// <summary>
    /// The pending frame was just injected with a newer one right behind it. SendInput queues both
    /// edges, but a game polling the pad would miss a button held for microseconds, so a button or
    /// hat direction pressed within the last <see cref="PadEdgeHoldUs"/> that the newer frame drops
    /// stays pressed for <see cref="PadEdgeHoldUs"/>. Anything held longer lets go at once.
    /// </summary>
    private void HoldPadEdge(long nowUs)
    {
        long recent = nowUs - PadEdgeHoldUs;
        int released = _pending.Buttons & ~_rxFrame.Buttons;
        ushort fresh = 0;
        for (int i = 0; released != 0; i++, released >>= 1)
            if ((released & 1) != 0 && _padPressUs[i] > recent) fresh |= (ushort)(1 << i);
        byte hat = _lastPendingHat;
        if (hat == PadMapper.HatOf(_rxFrame) || _hatPressUs <= recent) hat = StateFrame.HatCenter;
        if (fresh == 0 && hat == StateFrame.HatCenter) return;
        if (nowUs >= _padHoldUntilUs)
        {
            _padHoldButtons = 0;
            _padHoldHat = StateFrame.HatCenter;
        }
        _padHoldButtons |= fresh;
        if (hat != StateFrame.HatCenter) _padHoldHat = hat;
        _padHoldUntilUs = nowUs + PadEdgeHoldUs;
    }

    private void SubmitPad(StateFrame s, long nowUs)
    {
        _padHeld = false;
        if (nowUs < _padHoldUntilUs)
        {
            ushort buttons = (ushort)(s.Buttons | _padHoldButtons);
            byte hat = PadMapper.HatOf(s) == StateFrame.HatCenter ? _padHoldHat : s.Hat;
            if (buttons != s.Buttons || hat != s.Hat)
            {
                _padMerged.CopyFrom(s);
                _padMerged.Buttons = buttons;
                _padMerged.Hat = hat;
                s = _padMerged;
                _padHeld = true;
            }
        }
        if (!_padFrameValid && nowUs < _padRetryAfterUs) return;
        switch (PadOut)
        {
            case OutputMode.Xbox360:
                var x = PadMapper.ToX360(s);
                if ((s.Buttons & PadMapper.X360DroppedButtons) != 0 && !_loggedX360Dropped)
                {
                    _loggedX360Dropped = true;
                    _events.Log("M1-M4/SHARE have no Xbox 360 button; dropped");
                }
                if (_padFrameValid && x == _lastX360) return;
                if (!TrySubmit(x)) { _padRetryAfterUs = nowUs + PadRetryUs; return; } // the next flush after it sends it again
                _lastX360 = x;
                if (Debug.Enabled) Debug.OnX360(x, nowUs);
                _padFrameValid = true;
                break;
            case OutputMode.DualShock4:
            case OutputMode.DualSense: // the same buttons, sticks and motion
                var f = PadMapper.ToDs4(s);
                if (_padFrameValid && f == _lastDs4) return;
                if (!TrySubmit(f, _ds4Motion.Current)) { _padRetryAfterUs = nowUs + PadRetryUs; return; }
                _lastDs4 = f;
                if (Debug.Enabled) Debug.OnDs4(f, nowUs);
                _padFrameValid = true;
                break;
        }
    }

    // A pad failure (bus handle gone after a driver reset, say) must never stop keyboard, mouse or a release.
    private bool TrySubmit(in X360Frame f)
    {
        try
        {
            long t0 = System.Diagnostics.Stopwatch.GetTimestamp();
            _pad.Submit(f);
            _handOff.PadTicks += System.Diagnostics.Stopwatch.GetTimestamp() - t0;
            _loggedPadFailure = false;
            return true;
        }
        catch (Exception e) when (e is not OutOfMemoryException)
        {
            PadFailed(e);
            return false;
        }
    }

    private bool TrySubmit(in Ds4Frame f, in Ds4Motion m)
    {
        try
        {
            long t0 = System.Diagnostics.Stopwatch.GetTimestamp();
            _pad.Submit(f, m);
            _handOff.PadTicks += System.Diagnostics.Stopwatch.GetTimestamp() - t0;
            _loggedPadFailure = false;
            return true;
        }
        catch (Exception e) when (e is not OutOfMemoryException)
        {
            PadFailed(e);
            return false;
        }
    }

    /// <summary>
    /// MOTION: each new sample goes to the DualShock 4 or DualSense as its own report, with the buttons and sticks
    /// it shows now, so the game sees every turn at the time it was measured.
    /// </summary>
    private void OnMotion(ReadOnlySpan<byte> plain, long nowUs)
    {
        if (!PadTakesMotion || _paused || _locked || _releasedForSilence) return;
        _motionFrame.Decode(plain);
        int n = _ds4Motion.Take(_motionFrame, _motionOut, nowUs);
        SubmitMotion(n);
    }

    /// <summary>Sends the first <paramref name="n"/> readings of <see cref="_motionOut"/> with the pad's current frame.</summary>
    private void SubmitMotion(int n)
    {
        var f = _padFrameValid ? _lastDs4 : Ds4Frame.Neutral;
        for (int i = 0; i < n; i++)
            if (!TrySubmit(f, _motionOut[i])) return;
    }

    private void PadFailed(Exception e)
    {
        _exceptions++;
        _padFrameValid = false;
        if (_loggedPadFailure) return;
        _loggedPadFailure = true;
        _events.Log($"pad submit failed: {e.GetType().Name}");
    }

    // =====================================================================================
    // watchdog
    // =====================================================================================

    /// <summary>Watchdog tick (every WATCHDOG_TICK_MS): silence release, session drop, pending expiry, pairing timers, 1 Hz HOST_STATUS.</summary>
    public void OnTick(long nowUs)
    {
        using (Lock(ByWatchdog))
        {
            if (_linkTestUntilUs != long.MinValue && nowUs >= _linkTestUntilUs) SetLinkTest(0, "time is up");
            var a = _sessions.Active;
            if (a != null)
            {
                long silent = nowUs - a.LastRxUs;
                if (silent >= SessionTimeoutUs)
                {
                    _events.Log($"session with '{a.PhoneName}' timed out");
                    EndActive(hostByeReason: null, why: "timed out");
                }
                else if (silent >= ReleaseTimeoutUs && !_releasedForSilence)
                {
                    _releasedForSilence = true;
                    _hasPending = false;
                    Rec?.ReleasedForSilence(HeldCount(), nowUs);
                    ReleaseAllLocked();
                    _events.StateChanged();
                }
            }
            // Ups Windows dropped are sent again as soon as it takes input.
            if (_injectFailed && !Blocked) ResyncAfterBlock();
            // Held volume keys repeat, as a keyboard's do.
            if (!_paused && !Blocked)
            {
                int r = _keyRepeat.Due(_injectedKeys, nowUs, _inject);
                if (r > 0 && !_keyMouse.Inject(_inject.AsSpan(0, r))) InjectFailed();
            }
            // A lost stop (or the phone gone quiet): the pad's gyro comes to rest.
            if (PadTakesMotion && _ds4Motion.IsStale(nowUs)) SubmitMotion(_ds4Motion.Rest(_motionOut, nowUs));
            // A pad button held from a burst is let go even when no STATE follows.
            if (_padHeld && nowUs >= _padHoldUntilUs && !_hasPending && !_paused && !_locked) SubmitPad(_pending, nowUs);
            _sessions.ExpirePending(nowUs);
            if (_pairing.Tick(nowUs)) _events.StateChanged();
            // No session for a long while: the pad is unplugged until the next one.
            if (_sessions.Active == null && _padWant != OutputMode.None && nowUs - _padIdleFromUs >= PadIdleUnplugUs)
            {
                _events.Log("no session for 5 minutes: pad unplugged");
                RequestPad(OutputMode.None);
            }

            SendPendingRumble(nowUs);
            a = _sessions.Active;
            if (a != null && nowUs - _lastStatusUs >= StatusIntervalUs)
            {
                _stats.Roll();
                Rec?.Second(_stats, nowUs);
                SendStatus(a, nowUs);
                if (Debug.Enabled) Debug.SecondTick(DebugLinkLine(), nowUs);
            }
        }
    }

    /// <summary>The PC keyboard layout's key labels from the watchdog's probe; sent with every HOST_STATUS, at once when they change.</summary>
    public void SetKeyLabels(KeyLabels? labels)
    {
        using (Lock(ByProbes))
        {
            if (labels == null || labels.SameAs(_keyLabels)) return;
            _keyLabels = labels;
            DebugTrace.SetLayout(labels);
            if (_sessions.Active is { } a) SendStatus(a, _clock.NowUs);
        }
    }

    /// <summary>Environment flags from the watchdog's probes (taken outside the lock).</summary>
    public void SetEnvironment(bool elevatedForeground, bool networkPublic)
    {
        using (Lock(ByProbes))
        {
            if (elevatedForeground == _elevatedForeground && networkPublic == _networkPublic) return;
            bool wasBlocked = Blocked;
            _elevatedForeground = elevatedForeground;
            _networkPublic = networkPublic;
            if (wasBlocked && !Blocked) ResyncAfterBlock();
            _events.StateChanged();
        }
    }

    /// <summary>The injected held set and its version, for the held-keys marker. Read without the engine lock.</summary>
    public int HeldSnapshot(Span<byte> keys, out byte mouseButtons) => _view.Injected(keys, out mouseButtons);

    /// <summary>
    /// Returns the settings as JSON if they changed (written by the caller). The JSON is made after the engine
    /// lock is let go, so a slow serialization never holds up input: a change made meanwhile marks them changed
    /// again and is saved on the next call, and one that trips the serializer (a list changed under it) is retried then.
    /// </summary>
    public string? TakeDirtySettings()
    {
        using (Lock(BySettings))
        {
            if (!_settingsDirty) return null;
            _settingsDirty = false;
        }
        try
        {
            return SettingsStore.Serialize(_settings);
        }
        catch (Exception e) when (e is not OutOfMemoryException)
        {
            MarkSettingsDirty();
            return null;
        }
    }

    /// <summary>A write of what <see cref="TakeDirtySettings"/> returned failed: it is written again later.</summary>
    public void MarkSettingsDirty()
    {
        using (Lock(BySettings)) _settingsDirty = true;
    }

    // =====================================================================================
    // UI commands
    // =====================================================================================

    public void AnswerPair(uint attemptId, bool allow)
    {
        using (Lock(ByUi))
        {
            _pairing.Answer(attemptId, allow, _clock.NowUs);
            _events.StateChanged();
        }
    }

    /// <summary>
    /// Shows a QR code: returns its one-time token (null without a host key). The first phone that
    /// sends KEY_ENROLL with it is authorized: <paramref name="save"/> writes its key file and
    /// returns the path, or null when that failed. Lasts <see cref="Wire.QrPairingS"/> or until
    /// <see cref="CloseQrPairing"/>; opening again replaces the token.
    /// </summary>
    public byte[]? OpenQrPairing(Func<PubKeyFile, string?> save)
    {
        using (Lock(ByUi))
        {
            if (_hostKey == null) return null;
            _qr?.Clear();
            var token = QrPairing.NewToken();
            _qr = new QrSession(token, QrPairing.TokenId(token), _clock.NowUs + Wire.QrPairingS * 1_000_000L, save);
            _issuedTokenIds[_issuedNext] = _qr.TokenId;
            _issuedNext = (_issuedNext + 1) % _issuedTokenIds.Length;
            _events.StateChanged();
            return token;
        }
    }

    public void CloseQrPairing()
    {
        using (Lock(ByUi))
        {
            _qr?.Clear();
            _qr = null;
            _events.StateChanged();
        }
    }

    /// <summary>
    /// The host key, when it could not be read at start-up (host.key held by another program) and a later
    /// try got it: key file and QR code pairing start working. Ignored once there is a key.
    /// </summary>
    public bool SetHostKey(P256 key)
    {
        using (Lock(ByUi))
        {
            if (_hostKey != null) return false;
            _hostKeyId = KeyPairing.KeyId(key.PublicKey);
            _hostKeyFingerprint = KeyPairing.Fingerprint(key.PublicKey);
            _hostKey = key;
            _events.StateChanged();
            return true;
        }
    }

    /// <summary>This host's public key, for the QR code (null without key pairing).</summary>
    public byte[]? HostPublicKey => _hostKey?.PublicKey;

    public void OpenPairingWindow()
    {
        using (Lock(ByUi))
        {
            _pairing.Open(_clock.NowUs);
            _events.StateChanged();
        }
    }

    public void ClosePairingWindow()
    {
        using (Lock(ByUi))
        {
            _pairing.Close();
            _events.StateChanged();
        }
    }

    public void SetOutputMode(OutputMode mode)
    {
        using (Lock(ByUi))
        {
            if (mode == _settings.OutputMode) return;
            ReleaseAllLocked();
            _settings.OutputMode = mode;
            _settingsDirty = true;
            // The old pad goes and, with a session, the new one comes, on the pad thread: the menu never waits.
            if (_sessions.Active != null) PlugPad();
            else RequestPad(OutputMode.None);
            _events.StateChanged();
        }
    }

    /// <summary>"Start with Windows" preference; the caller updates the HKCU Run entry.</summary>
    public void SetStartWithWindows(bool enabled)
    {
        using (Lock(ByUi))
        {
            if (enabled == _settings.StartWithWindows) return;
            _settings.StartWithWindows = enabled;
            _settingsDirty = true;
        }
    }

    /// <summary>Changes overlay window <paramref name="id"/>'s settings and saves them; false when there is none.</summary>
    public bool UpdateOverlaySettings(string id, Action<OverlaySettings> change)
    {
        using (Lock(ByUi))
        {
            var o = _settings.Overlays.Find(x => x.Id == id);
            if (o == null) return false;
            bool compass = OverlayCompassLocked();
            var kind = o.Kind;
            change(o);
            o.Id = id;
            if (o.Kind != kind) _overlayPoses.Remove(id);
            _settingsDirty = true;
            _overlayDirty = true;
            OverlayCompassChanged(compass);
            return true;
        }
    }

    /// <summary>A new overlay window of <paramref name="kind"/> (shown), turning as the first one does; returns its id.</summary>
    public string AddOverlay(OverlayKind kind)
    {
        using (Lock(ByUi))
        {
            bool compass = OverlayCompassLocked();
            var o = new OverlaySettings { Kind = kind, Shown = true };
            if (_settings.Overlays.Count > 0)
            {
                var f = _settings.Overlays[0];
                o.Gravity = f.Gravity; o.Compass = f.Compass; o.EaseHeadingOnly = f.EaseHeadingOnly;
                o.Ease = f.Ease; o.EaseOnRatchet = f.EaseOnRatchet; o.EaseDelayMs = f.EaseDelayMs; o.EaseBySpeed = f.EaseBySpeed;
                o.EaseSeconds = f.EaseSeconds; o.EaseSpeed = f.EaseSpeed; o.MotionScale = f.MotionScale;
                if (f.Kind == kind)
                {
                    // The same kind also gets its controller handles and their colours.
                    o.Handles = f.Handles; o.HandleSource = f.HandleSource;
                    foreach (var key in (string[])["handles", "handlesLit"])
                        if (f.Colors.TryGetValue(key, out var c)) o.Colors[key] = c;
                }
            }
            if (kind != OverlayKind.Phone) o.Width = o.Height = 260;
            _settings.Overlays.Add(o);
            _settingsDirty = true;
            _overlayDirty = true;
            OverlayCompassChanged(compass);
            return o.Id;
        }
    }

    /// <summary>Saves overlay <paramref name="id"/> as a preset named <paramref name="name"/> (all but its place); returns the preset's id.</summary>
    public string? SaveOverlayPreset(string id, string name)
    {
        using (Lock(ByUi))
        {
            var o = _settings.Overlays.Find(x => x.Id == id);
            if (o == null) return null;
            var p = o.Copy();
            p.Label = name.Trim();
            p.Shown = false;
            p.X = p.Y = -1;
            _settings.OverlayPresets.Add(p);
            _settingsDirty = true;
            return p.Id;
        }
    }

    /// <summary>A new overlay window (shown) from preset <paramref name="presetId"/>, placed as a new one is; returns its id.</summary>
    public string? AddOverlayFromPreset(string presetId)
    {
        using (Lock(ByUi))
        {
            var p = _settings.OverlayPresets.Find(x => x.Id == presetId);
            if (p == null) return null;
            bool compass = OverlayCompassLocked();
            var o = p.Copy();
            o.Shown = true;
            o.X = o.Y = -1;
            _settings.Overlays.Add(o);
            _settingsDirty = true;
            _overlayDirty = true;
            OverlayCompassChanged(compass);
            return o.Id;
        }
    }

    public void RenameOverlayPreset(string presetId, string name)
    {
        using (Lock(ByUi))
        {
            if (_settings.OverlayPresets.Find(x => x.Id == presetId) is not { } p) return;
            p.Label = name.Trim();
            _settingsDirty = true;
        }
    }

    public void RemoveOverlayPreset(string presetId)
    {
        using (Lock(ByUi))
        {
            if (_settings.OverlayPresets.RemoveAll(x => x.Id == presetId) > 0) _settingsDirty = true;
        }
    }

    /// <summary>Removes overlay window <paramref name="id"/> and its settings.</summary>
    public void RemoveOverlay(string id)
    {
        using (Lock(ByUi))
        {
            bool compass = OverlayCompassLocked();
            if (_settings.Overlays.RemoveAll(x => x.Id == id) == 0) return;
            _overlayPoses.Remove(id);
            _settingsDirty = true;
            _overlayDirty = true;
            OverlayCompassChanged(compass);
        }
    }

    private bool OverlayCompassLocked()
    {
        foreach (var o in _settings.Overlays) if (o.Shown && o.NeedsCompass) return true;
        return false;
    }

    // The phone sends compass readings only while asked.
    private void OverlayCompassChanged(bool before)
    {
        if (_overlayOn && before != OverlayCompassLocked() && _sessions.Active is { } a) SendStatus(a, _clock.NowUs);
    }

    /// <summary>"Catch-up time" (ms; 0 = off): the longest a lost or late stretch of mouse motion is spread over.</summary>
    public void SetCatchUpMs(int ms)
    {
        using (Lock(ByUi))
        {
            ms = HostSettings.ClampCatchUpMs(ms);
            if (ms == _settings.CatchUpMs) return;
            _settings.CatchUpMs = ms;
            _catchUp.MaxUs = ms * 1000L;
            _settingsDirty = true;
        }
    }

    /// <summary>What a left click on the tray icon does; the tray reads it from <see cref="Settings"/>.</summary>
    public void SetTrayClick(TrayClickAction action)
    {
        using (Lock(ByUi))
        {
            if (action == _settings.TrayClick) return;
            _settings.TrayClick = action;
            _settingsDirty = true;
        }
    }

    public void SetUsbDirect(bool on)
    {
        using (Lock(ByUi))
        {
            if (on == _settings.UsbDirect) return;
            _settings.UsbDirect = on;
            _settingsDirty = true;
        }
    }

    /// <summary>
    /// Replaces the pad sink (e.g. "Retry driver" after installing ViGEmBus). The engine owns the old one:
    /// the pad thread unplugs and disposes it.
    /// </summary>
    public void SetPadSink(IPadSink pad)
    {
        using (Lock(ByUi))
        {
            _pad.Rumble -= OnRumble;
            _padRetired.Add(_pad);
            _pad = pad;
            _pad.Rumble += OnRumble;
            _padFrameValid = false;
            RequestPad(_sessions.Active != null ? _settings.OutputMode : OutputMode.None);
            _events.StateChanged();
        }
    }

    /// <summary>"Pause input": releases everything; STATEs keep updating the baseline but inject nothing.</summary>
    /// <summary>The connected phone's address as the PC sees it (loopback over the direct USB cable), or null.</summary>
    public System.Net.IPAddress? PhoneAddress()
    {
        using (Lock(ByUi))
        {
            if (_sessions.Active is not { } a) return null;
            try
            {
                return ((System.Net.IPEndPoint)new System.Net.IPEndPoint(System.Net.IPAddress.Any, 0).Create(a.Endpoint)).Address;
            }
            catch (Exception)
            {
                return null;
            }
        }
    }

    /// <summary>
    /// Buzzes the connected phone once (a Performance record ended), whatever the output mode. Sent
    /// twice against a lost packet; the phone plays one buzz per second at most.
    /// </summary>
    public void Buzz()
    {
        using (Lock(ByUi))
        {
            var a = _sessions.Active;
            if (a == null) return;
            Span<byte> p = stackalloc byte[Wire.RumblePlain];
            WireCodec.EncodeRumble(new Rumble(0, 0, RumblePattern.Buzz), p);
            for (int i = 0; i < 2; i++) a.SendSealed(PacketType.Rumble, p, _sender);
        }
    }

    public void SetPaused(bool paused)
    {
        using (Lock(ByUi))
        {
            if (paused == _paused) return;
            _paused = paused;
            if (paused) ReleaseAllLocked();
            _events.StateChanged();
        }
    }

    /// <summary>Workstation locked or suspending: behaves like Pause and is reported as INPUT_PAUSED.</summary>
    public void SetLocked(bool locked)
    {
        using (Lock(ByUi))
        {
            if (locked == _locked) return;
            bool wasBlocked = Blocked;
            _locked = locked;
            if (locked) ReleaseAllLocked();
            else if (wasBlocked && !Blocked) ResyncAfterBlock();
            _events.StateChanged();
        }
    }

    /// <summary>Edit: overlay windows can be moved and resized (off: the mouse goes through them).</summary>
    public void SetOverlayEdit(bool on)
    {
        using (Lock(ByUi))
        {
            if (_settings.OverlayEdit == on) return;
            _settings.OverlayEdit = on;
            _settingsDirty = true;
        }
    }

    /// <summary>Hide all: every overlay window is hidden, each keeping its own Show.</summary>
    public void SetOverlaysHidden(bool hidden)
    {
        using (Lock(ByUi))
        {
            if (_settings.OverlaysHidden == hidden) return;
            _settings.OverlaysHidden = hidden;
            _settingsDirty = true;
        }
    }

    /// <summary>
    /// Sets shortcut <paramref name="action"/> ("" = none). Another action that had the same keys
    /// loses them, so one press never does two things.
    /// </summary>
    public void SetOverlayShortcut(OverlayAction action, string keys)
    {
        using (Lock(ByUi))
        {
            var s = _settings.OverlayShortcuts;
            keys ??= "";
            if (keys.Length > 0)
                foreach (var other in OverlayShortcuts.All)
                    if (other != action && string.Equals(s.Get(other), keys, StringComparison.OrdinalIgnoreCase)) s.Set(other, "");
            s.Set(action, keys);
            _settingsDirty = true;
        }
    }

    /// <summary>
    /// The phone overlay is shown: HOST_STATUS asks the phone for its pose, layout and presses
    /// (told at once), and they are kept for <see cref="OverlaySnapshot"/>. Off: the phone stops sending them.
    /// </summary>
    public void SetOverlay(bool on)
    {
        using (Lock(ByUi))
        {
            if (on == _overlayOn) return;
            _overlayOn = on;
            _overlayRxUs = long.MinValue;
            _overlayRest.Clear(_clock.NowUs);
            _overlayPoses.Clear();
            _levelPresses = -1;
            _overlayTotals.Reset();
            if (!on) _overlayLayout.Reset();
            _overlayDirty = true;
            if (_sessions.Active is { } a) SendStatus(a, _clock.NowUs);
        }
    }

    /// <summary>
    /// "Measure buttons": the phone sends PRESS_TIME for each press and release (told at once), and
    /// <see cref="PressLatencyNow"/> has the newest presses. Off: the phone stops and they are forgotten.
    /// </summary>
    public void SetMeasureButtons(bool on)
    {
        using (Lock(ByUi))
        {
            if (on == MeasuringButtons) return;
            _settings.MeasureButtons = on;
            _settingsDirty = true;
            _press.Reset();
            _testModeOffRepeats = TestModeFlagsNow() == 0 ? 3 : 0;
            if (_sessions.Active is { } a) SendTestMode(a);
        }
    }

    /// <summary>The latency test window is open: the phone's pad shows its tracking markers (told at once).</summary>
    public void SetLatencyScreen(bool on)
    {
        using (Lock(ByUi))
        {
            if (on == _latencyScreen) return;
            _latencyScreen = on;
            _testModeOffRepeats = TestModeFlagsNow() == 0 ? 3 : 0;
            if (_sessions.Active is { } a) SendTestMode(a);
        }
    }

    /// <summary>A Link test runs (<see cref="SetLinkTest"/>).</summary>
    public bool LinkTesting
    {
        get { using (Lock(ByUi)) return _linkTestUntilUs != long.MinValue; }
    }

    /// <summary>
    /// Starts a Link test for <paramref name="seconds"/> (0 stops one): the phone sends fake moving
    /// input at its full rate, which the input log counts like real input but which is never injected.
    /// </summary>
    public void SetLinkTest(int seconds, string why = "")
    {
        using (Lock(ByUi))
        {
            long now = _clock.NowUs;
            bool was = _linkTestUntilUs != long.MinValue;
            _linkTestUntilUs = seconds > 0 ? now + Math.Min(seconds, 3600) * 1_000_000L : long.MinValue;
            if (seconds > 0)
            {
                _events.Log($"link test started for {Math.Min(seconds, 3600)} s" + (why.Length > 0 ? $" ({why})" : ""));
                Rec?.Note("link test", $"started for {Math.Min(seconds, 3600)} s", now);
            }
            else if (was)
            {
                _events.Log("link test stopped" + (why.Length > 0 ? $" ({why})" : ""));
                Rec?.Note("link test", "stopped", now);
            }
            else return;
            _testModeOffRepeats = TestModeFlagsNow() == 0 ? 3 : 0;
            if (_sessions.Active is { } a) SendTestMode(a);
        }
    }

    /// <summary>"Measure buttons": the middle of the last presses (total and the phone's part, µs), how many, and the newest; null while off or before any.</summary>
    public ButtonLatency? PressLatencyNow()
    {
        using (Lock(ByUi))
        {
            if (!MeasuringButtons || _press.Median() is not { } m) return null;
            return new ButtonLatency(m.TotalUs, m.PhoneUs, _press.Count, _press.LastTotalUs);
        }
    }

    private long ClockOffsetUs => _stats.LastLinkInfo?.ClockOffsetUs ?? 0;

    /// <summary>
    /// Copies the newest OVERLAY into <paramref name="into"/> and returns how long ago it came (us),
    /// or -1 when none came in this session; <paramref name="layout"/> is the newest complete layout.
    /// </summary>
    public long OverlaySnapshot(OverlayFrame into, out OverlayLayout? layout) => OverlaySnapshot(into, null, out layout);

    /// <summary>
    /// As above, with overlay window <paramref name="id"/>'s pose in <paramref name="into"/> (straight until it has one).
    /// Read without the engine lock: the overlay windows ask every frame, and must never keep gp-rx waiting.
    /// </summary>
    public long OverlaySnapshot(OverlayFrame into, string? id, out OverlayLayout? layout)
    {
        long rx = _view.Read(into, id, out layout, out bool pcHandles);
        if (rx == long.MinValue) return -1;
        if (pcHandles) PcPadOnHandles(into);
        return Math.Max(0, _clock.NowUs - rx);
    }

    /// <summary>
    /// Level: the way the phone is held now becomes overlay <paramref name="id"/>'s resting pose, shown
    /// face-on. With gravity that is the tilt now (saved as <see cref="OverlaySettings.Rest"/>); without
    /// (turned by the gyro since the last reset, or a trackball) the pose simply starts again from straight.
    /// </summary>
    public void LevelOverlay(string id)
    {
        using (Lock(ByUi))
        {
            var o = _settings.Overlays.Find(x => x.Id == id);
            if (o == null) return;
            _overlayPoses.TryGetValue(id, out var pose);
            // Which way is up now: "Fade when phone rests" counts the phone flat near this tilt.
            if (_overlayRxUs != long.MinValue && _overlayFrame.HasUp &&
                MathF.Sqrt(_overlayFrame.UpX * _overlayFrame.UpX + _overlayFrame.UpY * _overlayFrame.UpY + _overlayFrame.UpZ * _overlayFrame.UpZ) is var g and > 0f)
                o.LevelUp = [_overlayFrame.UpX / g, _overlayFrame.UpY / g, _overlayFrame.UpZ / g];
            if (o.Gravity && !o.FollowsCamera)
            {
                if (pose == null) return;
                o.Rest = pose.TiltOnly();
            }
            else
            {
                pose?.Reset();
                o.Rest = null;
            }
            _settingsDirty = true;
            _overlayDirty = true;
        }
    }

    /// <summary>
    /// Set orientation on the phone: overlay <paramref name="o"/> starts again from the phone's new base
    /// orientation, which the frames now come in: level (no saved grip), facing ahead, and "Fade when
    /// phone rests" counts the phone flat near the way it is held now.
    /// </summary>
    private void StartOverOverlay(OverlaySettings o)
    {
        using (Lock(ByUi))
        {
            if (_overlayFrame.HasUp &&
                MathF.Sqrt(_overlayFrame.UpX * _overlayFrame.UpX + _overlayFrame.UpY * _overlayFrame.UpY + _overlayFrame.UpZ * _overlayFrame.UpZ) is var g and > 0f)
                o.LevelUp = [_overlayFrame.UpX / g, _overlayFrame.UpY / g, _overlayFrame.UpZ / g];
            o.Rest = null;
            if (_overlayPoses.TryGetValue(o.Id, out var pose)) pose.Reset();
            _settingsDirty = true;
            _overlayDirty = true;
        }
    }

    /// <summary>"Fade when phone rests": overlay <paramref name="id"/> should be faded now (the phone lies still at its Level, untouched).</summary>
    public bool OverlayResting(string id)
    {
        return _view.Resting(id, _clock.NowUs, _settings.OverlayRestFade);
    }

    /// <summary>The phone's turning speed now (deg/s) from its overlay frames, or null without any lately: shown beside "Still below".</summary>
    public float? OverlayStillnessDps()
    {
        return _view.LiveDps(_clock.NowUs);
    }

    /// <summary>Changes the "Fade when phone rests" settings.</summary>
    public void UpdateOverlayRestFade(Action<OverlayRestFade> change)
    {
        using (Lock(ByUi))
        {
            change(_settings.OverlayRestFade);
            _settings.OverlayRestFade.Normalize();
            _settingsDirty = true;
        }
    }

    /// <summary>Overlay window <paramref name="id"/>'s pose now (w, x, y, z), or null before it has one.</summary>
    public float[]? OverlayPoseOf(string id)
    {
        return _view.PoseOf(id);
    }

    /// <summary>Release-all for external triggers (session switch, suspend, exit).</summary>
    public void ReleaseAll()
    {
        using (Lock(ByUi)) ReleaseAllLocked();
    }

    public bool Forget(byte[] phoneId)
    {
        using (Lock(ByUi))
        {
            var hex = Convert.ToHexString(phoneId);
            if (!_phones.Remove(hex, out var entry)) return false;
            CryptographicOperations.ZeroMemory(entry.PairKey);
            _settings.PairedPhones.RemoveAll(p => p.PhoneId.AsSpan().SequenceEqual(phoneId));
            _settingsDirty = true;
            _phoneList = null;
            _sessions.RemovePending(phoneId);
            if (_sessions.Active is { } a && a.SamePhone(phoneId)) EndActive(HostByeReason.Forgotten);
            ForgotPadPhone(phoneId);
            _events.StateChanged();
            return true;
        }
    }

    /// <summary>
    /// The phone keys in authorized_phones (from the folder watcher). New keys are paired at once;
    /// a phone whose file is gone is dropped, live session included.
    /// </summary>
    public void SetAuthorizedPhones(IReadOnlyList<AuthorizedPhone> phones)
    {
        using (Lock(ByUi))
        {
            if (_hostKey == null) return;
            _authorizedList = phones;
            var next = new Dictionary<string, KeyPhone>();
            // Known keys need no ECDH again.
            var known = new Dictionary<string, KeyPhone>();
            foreach (var kp in _keyPhones.Values) known[Convert.ToHexString(kp.PublicKey)] = kp;
            _authorizedKeyIds.Clear();
            foreach (var p in phones)
            {
                var keyId = KeyPairing.KeyId(p.Key.PublicKey);
                _authorizedKeyIds.Add(Convert.ToHexString(keyId));
                var name = p.Key.Name.Length > 0 ? p.Key.Name : Path.GetFileNameWithoutExtension(p.File);
                if (known.TryGetValue(Convert.ToHexString(p.Key.PublicKey), out var old))
                {
                    // Same key: keep the array sessions may still hold, take the (maybe renamed) file.
                    next.TryAdd(Convert.ToHexString(old.PhoneId), new KeyPhone(p.File, name, old.PhoneId, old.PairKey, old.Fingerprint, old.KeyId, old.PublicKey) { LastSeenUtc = old.LastSeenUtc });
                    continue;
                }
                var (id, pairKey) = KeyPairing.HostSecrets(_hostKey, p.Key.PublicKey);
                var hex = Convert.ToHexString(id);
                if (next.ContainsKey(hex))
                {
                    CryptographicOperations.ZeroMemory(pairKey); // the same key in two files
                    continue;
                }
                next[hex] = new KeyPhone(p.File, name, id, pairKey, p.Key.Fingerprint, keyId, p.Key.PublicKey) { LastSeenUtc = DateTime.MinValue };
            }
            foreach (var (hex, old) in _keyPhones)
            {
                if (next.ContainsKey(hex)) continue;
                _events.Log($"key of '{old.Name}' removed; phone no longer authorized");
                _sessions.RemovePending(old.PhoneId);
                if (_sessions.Active is { } a && a.SamePhone(old.PhoneId)) EndActive(HostByeReason.Forgotten);
                ForgotPadPhone(old.PhoneId);
                CryptographicOperations.ZeroMemory(old.PairKey);
            }
            foreach (var (hex, kp) in next)
            {
                if (!_keyPhones.ContainsKey(hex)) _events.Log($"authorized '{kp.Name}' by key {kp.Fingerprint}");
            }
            _keyPhones.Clear();
            foreach (var (hex, kp) in next) _keyPhones[hex] = kp;
            _phoneList = null;
            _events.StateChanged();
        }
    }

    /// <summary>Exit: release-all, then HOST_BYE(EXIT) and unplug (waits up to 5 s for the pad thread).</summary>
    public void Shutdown()
    {
        using (Lock(ByUi))
        {
            ReleaseAllLocked();
            if (_sessions.Active != null) EndActive(HostByeReason.Exit);
            RequestPad(OutputMode.None);
        }
        _padIdle.Wait(TimeSpan.FromSeconds(5));
    }

    /// <summary>A session is active. Read without the engine lock, so cheap enough for every overlay frame, unlike <see cref="Snapshot"/>.</summary>
    public bool Connected => _view.Connected;

    /// <summary>
    /// What the tray and the main window show. The lock is held only to copy plain values; the paired phones list
    /// is kept ready (made again only when a phone is paired, forgotten or seen) and the record is made after.
    /// </summary>
    public HostSnapshot Snapshot()
    {
        string? phoneName;
        bool connected, silent, driverOk, plugged, paused, blocked, networkPublic, anyDatagram, dualSenseOk, padGyro;
        OutputMode mode;
        int pairingS, qrS;
        (uint, string, string)? prompt;
        ushort rx, loss;
        uint p50, p99, jitter;
        LinkInfo? link;
        IReadOnlyList<PairedPhoneInfo> phones;
        HostCounters counters;
        string? qrName;
        IPcPad pcPad;
        using (Lock(ByUi))
        {
            long now = _clock.NowUs;
            var a = _sessions.Active;
            phoneName = a?.PhoneName;
            connected = a != null;
            silent = _releasedForSilence;
            mode = _settings.OutputMode;
            driverOk = _pad.DriverOk;
            plugged = PadOut != OutputMode.None;
            paused = _paused || _locked;
            blocked = _elevatedForeground || _injectFailed;
            networkPublic = _networkPublic;
            pairingS = _pairing.WindowRemainingS(now);
            prompt = _pairing.PromptInfo;
            (rx, loss, p50, p99, jitter) = (_stats.RxPerSec, _stats.LossPermille, _stats.LatP50Us, _stats.LatP99Us, _stats.JitterUs);
            link = _stats.LastLinkInfo;
            anyDatagram = _anyDatagram;
            phones = _phoneList ??= PairedPhoneList();
            counters = new HostCounters(_datagrams, _dropped, _badAuth, _states, _injections, _unknownUsages, _errors, _exceptions);
            qrS = _qr != null && now < _qr.ExpiresUs ? (int)((_qr.ExpiresUs - now + 999_999) / 1_000_000) : 0;
            qrName = _qr?.EnrolledName;
            padGyro = _padGyro.Enabled;
            pcPad = _pcPad;
            dualSenseOk = _pad.DualSenseOk;
        }
        return new HostSnapshot(
            phoneName, connected, silent, mode, driverOk, plugged, paused, blocked, networkPublic,
            pairingS, prompt, rx, loss, p50, p99, jitter, link, anyDatagram, phones, counters,
            _hostKeyFingerprint, qrS, qrName,
            padGyro ? pcPad.Name ?? "" : null,
            dualSenseOk);
    }

    /// <summary>The paired phones for <see cref="Snapshot"/>; null after a change, made again on the next one.</summary>
    private IReadOnlyList<PairedPhoneInfo>? _phoneList;

    private List<PairedPhoneInfo> PairedPhoneList()
    {
        var list = new List<PairedPhoneInfo>(_phones.Count + _keyPhones.Count);
        foreach (var p in _phones.Values) list.Add(new PairedPhoneInfo(p.Info.PhoneId, p.Info.Name, p.Info.LastSeenUtc));
        foreach (var k in _keyPhones.Values) list.Add(new PairedPhoneInfo(k.PhoneId, k.Name, k.LastSeenUtc, true, k.File, k.Fingerprint));
        return list;
    }

    // =====================================================================================
    // internals
    // =====================================================================================

    /// <summary>
    /// KEY_ENROLL: a phone scanned the QR code. Only the open token's holder gets an answer it can
    /// check; the first valid key is saved and authorized, and repeats of it are acked again. The
    /// phone also broadcasts it, so a token this PC never issued gets no answer at all: an
    /// unauthenticated refusal from another PC would end the real pairing.
    /// </summary>
    private void OnKeyEnroll(ReadOnlySpan<byte> d, KeyEnroll e, SocketAddress from, long nowUs)
    {
        var q = _qr;
        Span<byte> ack = stackalloc byte[Wire.KeyEnrollAckSize];
        if (_hostKey == null || q == null || nowUs >= q.ExpiresUs || !e.TokenId.AsSpan().SequenceEqual(q.TokenId))
        {
            _dropped++;
            // Without a host key no token was ever issued, so no NO_KEY either.
            if (!IssuedToken(e.TokenId) || !_errorLimiter.TryAcquire(from, nowUs)) return;
            WireCodec.EncodeKeyEnrollAck(e.ReqId, e.TokenId, EnrollStatus.Expired, default, default, ack);
            Reply(ack, from);
            return;
        }
        if (!QrPairing.CheckEnroll(d, q.Token) || !P256.IsValidPublic(e.PkP))
        {
            _badAuth++; // not from the QR code: silence
            return;
        }
        if (q.EnrolledKey == null)
        {
            var key = new PubKeyFile(e.PkP, e.Name.Length > 0 ? e.Name : "Phone");
            string? file;
            try
            {
                file = q.Save(key);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                file = null;
                _events.Log($"could not save the key of '{key.Name}': {ex.GetType().Name}");
            }
            if (file == null) return; // the phone retries; nothing was authorized
            q.EnrolledKey = e.PkP;
            q.EnrolledName = key.Name;
            _events.Log($"'{key.Name}' paired by QR code");
            SetAuthorizedPhones([.. _authorizedList.Where(a => !a.Key.PublicKey.AsSpan().SequenceEqual(e.PkP)), new AuthorizedPhone(file, key)]);
        }
        byte status = q.EnrolledKey.AsSpan().SequenceEqual(e.PkP) ? EnrollStatus.Ok : EnrollStatus.Used;
        WireCodec.EncodeKeyEnrollAck(e.ReqId, e.TokenId, status, q.Token, e.PkP, ack);
        Reply(ack, from);
    }

    private bool IssuedToken(ReadOnlySpan<byte> tokenId)
    {
        foreach (var t in _issuedTokenIds)
            if (t != null && tokenId.SequenceEqual(t)) return true;
        return false;
    }

    private void OnPaired(byte[] phoneId, string name, byte[] pairKey)
    {
        var hex = Convert.ToHexString(phoneId);
        _settings.PairedPhones.RemoveAll(p => p.PhoneId.AsSpan().SequenceEqual(phoneId));
        var now = DateTime.UtcNow;
        var info = new PairedPhone
        {
            PhoneId = phoneId,
            Name = name,
            ProtectedKey = _protector.Protect(pairKey),
            PairedUtc = now,
            LastSeenUtc = now,
        };
        _settings.PairedPhones.Add(info);
        _phones[hex] = (info, pairKey);
        _settingsDirty = true;
        _phoneList = null;
        _events.Log($"paired with '{name}'");
        _events.StateChanged();
    }

    private void EndActive(byte? hostByeReason, string why = "ended")
    {
        var a = _sessions.Active;
        if (a == null) return;
        Rec?.SessionEnded(hostByeReason == null ? why : $"{why} by the PC ({hostByeReason})", _clock.NowUs);
        ReleaseAllLocked();
        if (hostByeReason is { } r)
        {
            SendHostBye(a, r);
            RememberEnded(a.SessionId); // in-flight packets cross the HOST_BYE
        }
        _sessions.DropActive();
        StopPcPad();
        _padGyro.Enabled = false;
        _gate.Reset();
        _pcGyro.Reset();
        _overlayLayout.Reset();
        _overlayRxUs = long.MinValue;
        _overlayRest.Clear(_clock.NowUs);
        _overlayPoses.Clear();
        _levelPresses = -1;
        _overlayTotals.Reset();
        _overlayDirty = true;
        _ds4Motion.Reset();
        // The pad stays plugged, at rest (ReleaseAllLocked sent it neutral), so the game keeps its
        // controller; a session within PadIdleUnplugUs reuses it.
        _padIdleFromUs = _clock.NowUs;
        _hasPending = false;
        _releasedForSilence = false;
        _events.StateChanged();
    }

    /// <summary>Keys and mouse buttons injected and held now (a count only, for the input log).</summary>
    private int HeldCount()
    {
        int n = System.Numerics.BitOperations.PopCount(_injectedMouse);
        foreach (var b in _injectedKeys) n += System.Numerics.BitOperations.PopCount(b);
        return n;
    }

    private void ReleaseAllLocked()
    {
        _hasPending = false;
        _catchUp.Clear();
        _padHoldButtons = 0;
        _padHoldHat = StateFrame.HatCenter;
        _padHoldUntilUs = long.MinValue;
        _padHeld = false;
        _lastPendingButtons = 0;
        _lastPendingHat = StateFrame.HatCenter;
        Array.Fill(_padPressUs, long.MinValue);
        _hatPressUs = long.MinValue;
        Span<byte> held = stackalloc byte[StateFrame.KeyBitmapSize];
        _injectedKeys.CopyTo(held);
        byte heldMouse = _injectedMouse;
        bool delivered = true;
        while (InputDiff.AnyHeld(_injectedKeys, _injectedMouse))
        {
            int n = InputDiff.ReleaseAll(_injectedKeys, ref _injectedMouse, _releaseEvents);
            if (n > 0) delivered &= _keyMouse.Inject(_releaseEvents.AsSpan(0, n));
            _heldVersion++;
        }
        if (Blocked || !delivered)
        {
            for (int i = 0; i < held.Length; i++) _blockedUps[i] |= held[i];
            _blockedMouseUps |= heldMouse;
        }
        if (!delivered) InjectFailed();
        // Pad to neutral but stay plugged.
        switch (PadOut)
        {
            case OutputMode.Xbox360:
                if (!TrySubmit(X360Frame.Neutral)) break;
                _lastX360 = X360Frame.Neutral;
                _padFrameValid = true;
                break;
            case OutputMode.DualShock4:
            case OutputMode.DualSense:
                _ds4Motion.Reset();
                if (!TrySubmit(Ds4Frame.Neutral, _ds4Motion.Current)) break;
                _lastDs4 = Ds4Frame.Neutral;
                _padFrameValid = true;
                break;
        }
    }

    /// <summary>SendInput may be silently dropped: an elevated window is in front (UIPI) or the secure desktop is up.</summary>
    private bool Blocked => _elevatedForeground || _locked;

    private void InjectFailed()
    {
        if (_injectFailed) return;
        _injectFailed = true;
        _events.StateChanged();
    }

    private void NoteBlockedUps(ReadOnlySpan<byte> before, byte beforeMouse)
    {
        for (int i = 0; i < _injectedKeys.Length; i++) _blockedUps[i] |= (byte)(before[i] & ~_injectedKeys[i]);
        _blockedMouseUps |= (byte)(beforeMouse & ~_injectedMouse);
    }

    /// <summary>
    /// Input is deliverable again: re-sends the ups injected while it was blocked (for usages not
    /// held now), then re-sends downs for the held set, so the system key state matches ours.
    /// </summary>
    private void ResyncAfterBlock()
    {
        Span<byte> ups = stackalloc byte[StateFrame.KeyBitmapSize];
        for (int i = 0; i < ups.Length; i++) ups[i] = (byte)(_blockedUps[i] & ~_injectedKeys[i]);
        byte upsMouse = (byte)(_blockedMouseUps & ~_injectedMouse);
        Span<byte> retry = stackalloc byte[StateFrame.KeyBitmapSize];
        ups.CopyTo(retry);
        byte retryMouse = upsMouse;
        Array.Clear(_blockedUps);
        _blockedMouseUps = 0;
        bool wasFailed = _injectFailed;
        _injectFailed = false;
        bool delivered = true;
        while (InputDiff.AnyHeld(ups, upsMouse))
        {
            int n = InputDiff.ReleaseAll(ups, ref upsMouse, _releaseEvents);
            if (n > 0) delivered &= _keyMouse.Inject(_releaseEvents.AsSpan(0, n));
        }

        Span<byte> sent = stackalloc byte[StateFrame.KeyBitmapSize];
        byte sentMouse = 0;
        int unknown = 0;
        while (true)
        {
            int n = InputDiff.Diff(sent, _injectedKeys, ref sentMouse, _injectedMouse, 0, 0, 0, _inject, ref unknown);
            if (n == 0) break;
            delivered &= _keyMouse.Inject(_inject.AsSpan(0, n));
        }
        if (!delivered)
        {
            // Still dropped: try again later.
            for (int i = 0; i < retry.Length; i++) _blockedUps[i] |= retry[i];
            _blockedMouseUps |= retryMouse;
            _injectFailed = true;
        }
        if (wasFailed != _injectFailed) _events.StateChanged();
    }

    private void RememberEnded(uint sid)
    {
        _endedSids[_endedNext] = sid;
        _endedUntilUs[_endedNext] = _clock.NowUs + EndedSidQuietUs;
        _endedNext = (_endedNext + 1) % _endedSids.Length;
    }

    private bool RecentlyEnded(uint sid, long nowUs)
    {
        for (int i = 0; i < _endedSids.Length; i++)
            if (_endedSids[i] == sid && nowUs < _endedUntilUs[i]) return true;
        return false;
    }

    /// <summary>The driver the chosen output needs works (what the phone's "driver ok" flags say).</summary>
    /// <summary>The plugged pad has a gyro and accelerometer (DualShock 4 or DualSense).</summary>
    private bool PadTakesMotion => PadOut is OutputMode.DualShock4 or OutputMode.DualSense;

    /// <summary>The pad reports may go to: what is plugged, None while the pad thread plugs or unplugs it.</summary>
    private OutputMode PadOut => _padBusy ? OutputMode.None : _pad.Plugged;

    private bool PadDriverOk => _settings.OutputMode == OutputMode.DualSense ? _pad.DualSenseOk : _pad.DriverOk;

    /// <summary>
    /// A phone was forgotten: with no session up and that phone the last to use the pad, no one is coming
    /// back to it, so it is unplugged. Another phone's pad, kept for its reconnect, stays.
    /// </summary>
    private void ForgotPadPhone(byte[] phoneId)
    {
        if (_sessions.Active != null || _padPhoneId == null || !_padPhoneId.AsSpan().SequenceEqual(phoneId)) return;
        _padPhoneId = null;
        RequestPad(OutputMode.None);
    }

    /// <summary>A session starts: the pad of the output mode, reused when it is still plugged.</summary>
    private void PlugPad()
    {
        _padFrameValid = false;
        RequestPad(_settings.OutputMode);
    }

    /// <summary>
    /// Has the pad thread make the pad <paramref name="want"/> (None: unplugged). Returns at once; until the
    /// job is done <see cref="PadOut"/> is None, so STATEs go on with keys and mouse and skip the pad.
    /// </summary>
    private void RequestPad(OutputMode want)
    {
        if (want != OutputMode.None && !_pad.CanPlug(want)) want = OutputMode.None;
        _padWant = want;
        if (_padBusy) return; // the running job takes the new wish when it is done
        if (_pad.Plugged == want && _padRetired.Count == 0) return;
        _padBusy = true;
        _padFrameValid = false;
        _padIdle.Reset();
        if (PadWorker != null) PadWorker(PadJob);
        else new Thread(PadJob) { IsBackground = true, Name = "gp-pad" }.Start();
    }

    /// <summary>The pad thread: plugs or unplugs outside the lock until the pad is what was last asked for.</summary>
    private void PadJob()
    {
        // Run at once from inside the lock (tests): the caller goes on as with a plug done in place.
        bool inline = Monitor.IsEntered(_lock);
        IPadSink? pad = null;
        var want = OutputMode.None;
        string? failed = null;
        while (true)
        {
            IPadSink[] retired;
            using (Lock(ByPad))
            {
                if (failed != null) _events.Log(failed);
                failed = null;
                if (pad != null && pad == _pad && want == _padWant && _padRetired.Count == 0)
                {
                    PadJobDone(inline);
                    return;
                }
                retired = [.. _padRetired];
                _padRetired.Clear();
                pad = _pad;
                want = _padWant;
            }
            foreach (var r in retired)
            {
                try
                {
                    r.Unplug();
                    (r as IDisposable)?.Dispose();
                }
                catch (Exception e) when (e is not OutOfMemoryException)
                {
                    failed = $"old pad unplug failed: {e.GetType().Name}";
                }
            }
            try
            {
                if (want == OutputMode.None) pad.Unplug();
                else if (pad.Plugged != want && !pad.Plug(want)) failed = $"could not plug {want} pad";
            }
            catch (Exception e) when (e is not OutOfMemoryException)
            {
                failed = $"pad {(want == OutputMode.None ? "unplug" : "plug")} failed: {e.GetType().Name}";
            }
        }
    }

    /// <summary>Under the lock: the pad is what was asked for. A live session's pad gets its current frame and the phone hears of it.</summary>
    private void PadJobDone(bool inline)
    {
        _padBusy = false;
        _padFrameValid = false;
        _padIdle.Set();
        if (inline) return;
        if (_sessions.Active is { } a)
        {
            long now = _clock.NowUs;
            // The STATE already handled while plugging is sent now (a newer one waits for Flush).
            if (_hasStatePhone && !_hasPending && !_paused && !_locked && !_releasedForSilence) SubmitPad(_pending, now);
            SendStatus(a, now); // PAD_PLUGGED: the phone sends MOTION from now on
        }
        _events.StateChanged();
    }

    private byte[] Announce(Discover disc, long nowUs)
    {
        ushort flags = 0;
        if (_pairing.IsOpen(nowUs)) flags |= AnnounceFlags.PairingOpen;
        if (PadDriverOk) flags |= AnnounceFlags.VigemOk;
        if (_phones.ContainsKey(Convert.ToHexString(disc.PhoneId)) || _keyPhones.ContainsKey(Convert.ToHexString(disc.PhoneId)))
            flags |= AnnounceFlags.PairedWithYou;
        if (_hostKeyId != null && disc.HostKeyId is { } hk && hk.AsSpan().SequenceEqual(_hostKeyId)) flags |= AnnounceFlags.KeyMatch;
        if (disc.PhoneKeyId is { } pk && pk.AsSpan().ContainsAnyExcept((byte)0) && _authorizedKeyIds.Contains(Convert.ToHexString(pk)))
            flags |= AnnounceFlags.KnowsYourKey;
        if (_sessions.Active is { } a && !IsSessionPhone(a, disc)) flags |= AnnounceFlags.Busy;
        var b = new byte[Wire.AnnounceSize];
        WireCodec.EncodeAnnounce(new Announce(disc.ReqId, _settings.HostId, flags, _settings.HostName), b);
        return b;
    }

    /// <summary>
    /// The DISCOVER comes from the phone holding <paramref name="a"/>: the session's phone id (a code
    /// session, or a key session's own flow), or for a key session the phone's key id next to its store id.
    /// </summary>
    private bool IsSessionPhone(HostSession a, Discover disc)
    {
        if (a.SamePhone(disc.PhoneId)) return true;
        return disc.PhoneKeyId is { } k && _keyPhones.TryGetValue(Convert.ToHexString(a.PhoneId), out var kp)
            && k.AsSpan().SequenceEqual(kp.KeyId);
    }

    private void ReplyPairing(int n, SocketAddress to)
    {
        if (n > 0) Reply(_reply.AsSpan(0, n), to);
        _events.StateChanged();
    }

    private void Reply(ReadOnlySpan<byte> datagram, SocketAddress to) => _sender?.Send(datagram, to);

    /// <summary>Advisory ERROR: only for requests of at least 16 bytes, 10/s per IP and 50/s overall.</summary>
    private void SendError(ushort code, int requestLength, SocketAddress to, long nowUs)
    {
        if (requestLength < Wire.ErrorSize || !_errorLimiter.TryAcquire(to, nowUs)) return;
        Span<byte> b = stackalloc byte[Wire.ErrorSize];
        WireCodec.EncodeError(new ErrorPacket(code, Wire.Version), b);
        _errors++;
        Reply(b, to);
    }

    private void SendPong(HostSession s, ReadOnlySpan<byte> plain, long recvUs)
    {
        var ping = WireCodec.DecodePing(plain);
        Span<byte> p = stackalloc byte[Wire.PongPlain];
        WireCodec.EncodePong(new Pong(ping.T1, (ulong)recvUs, (ulong)_clock.NowUs), p);
        s.SendSealed(PacketType.Pong, p, _sender);
    }

    private void SendStatus(HostSession s, long nowUs)
    {
        _lastStatusUs = nowUs;
        byte flags = 0;
        if (PadDriverOk) flags |= HostStatusFlags.VigemOk;
        if (PadOut != OutputMode.None) flags |= HostStatusFlags.PadPlugged;
        if (_paused || _locked) flags |= HostStatusFlags.InputPaused;
        if (_elevatedForeground) flags |= HostStatusFlags.ElevatedForeground;
        if (_networkPublic) flags |= HostStatusFlags.NetworkPublic;
        if (_overlayOn) flags |= HostStatusFlags.Overlay | HostStatusFlags.OverlayTotals;
        if (_overlayOn && OverlayCompassLocked()) flags |= HostStatusFlags.OverlayCompass;
        Span<byte> p = stackalloc byte[Wire.HostStatusPlain];
        WireCodec.EncodeHostStatus(new HostStatus(s.HighestRxSeq, _stats.RxPerSec, _stats.LossPermille,
            _stats.LatP50Us, _stats.LatP99Us, _stats.JitterUs, (byte)_settings.OutputMode, flags), p);
        // TEST_MODE first, so HOST_STATUS stays the newest status the phone holds. While no test
        // runs it goes only with the few statuses after one ended (a lost "off" comes again).
        if (TestModeFlagsNow() != 0 || _testModeOffRepeats > 0)
        {
            if (TestModeFlagsNow() == 0) _testModeOffRepeats--;
            SendTestMode(s);
        }
        s.SendSealed(PacketType.HostStatus, p, _sender);
        if (_keyLabels is { } labels)
        {
            Span<byte> k = stackalloc byte[Wire.KeyLabelsPlain];
            WireCodec.EncodeKeyLabels(labels, k);
            s.SendSealed(PacketType.KeyLabels, k, _sender);
        }
    }

    private byte TestModeFlagsNow()
    {
        byte flags = 0;
        if (MeasuringButtons) flags |= TestModeFlags.PressTimes;
        if (_latencyScreen) flags |= TestModeFlags.LatencyScreen;
        if (_linkTestUntilUs != long.MinValue) flags |= TestModeFlags.LinkTest;
        return flags;
    }

    /// <summary>TEST_MODE: on change, and with HOST_STATUS while a test runs. Older phones drop it.</summary>
    private void SendTestMode(HostSession s)
    {
        byte flags = TestModeFlagsNow();
        Span<byte> t = stackalloc byte[Wire.TestModePlain];
        WireCodec.EncodeTestMode(flags, t);
        s.SendSealed(PacketType.TestMode, t, _sender);
    }

    private void SendHostBye(HostSession s, byte reason)
    {
        Span<byte> p = stackalloc byte[Wire.ByePlain];
        WireCodec.EncodeReason(reason, p);
        for (int i = 0; i < 3; i++) s.SendSealed(PacketType.HostBye, p, _sender);
    }

    /// <summary>
    /// ViGEm feedback (driver thread). Only records the newest value: the driver thread never takes
    /// the engine lock, so it can never stall behind (or deadlock with) a SubmitReport made under it.
    /// </summary>
    private void OnRumble(byte large, byte small) =>
        Interlocked.Exchange(ref _rumbleNext, RumbleDirty | (large << 8) | small);

    /// <summary>Sends a recorded rumble change as RUMBLE, at most 100 Hz. Called under the lock from Flush and OnTick.</summary>
    private void SendPendingRumble(long nowUs)
    {
        int v = Volatile.Read(ref _rumbleNext);
        if ((v & RumbleDirty) == 0 || nowUs - _lastRumbleUs < RumbleMinIntervalUs) return;
        if (Interlocked.CompareExchange(ref _rumbleNext, v & 0xFFFF, v) != v) return; // newer value: next call
        var a = _sessions.Active;
        int packed = v & 0xFFFF;
        if (a == null || packed == _lastRumble) return;
        _lastRumble = packed;
        _lastRumbleUs = nowUs;
        Span<byte> p = stackalloc byte[Wire.RumblePlain];
        WireCodec.EncodeRumble(new Rumble((byte)(packed >> 8), (byte)packed), p);
        a.SendSealed(PacketType.Rumble, p, _sender);
    }

    private sealed class KeyPhone(string file, string name, byte[] phoneId, byte[] pairKey, string fingerprint, byte[] keyId, byte[] publicKey)
    {
        public string File { get; } = file;
        public string Name { get; } = name;
        public byte[] PhoneId { get; } = phoneId;
        public byte[] PairKey { get; } = pairKey;
        public string Fingerprint { get; } = fingerprint;
        /// <summary>The phone key's id, as the phone's DISCOVER carries it.</summary>
        public byte[] KeyId { get; } = keyId;
        /// <summary>The phone's public key, so a reload of the same key needs no ECDH.</summary>
        public byte[] PublicKey { get; } = publicKey;
        public DateTime LastSeenUtc { get; set; }
    }
}

/// <summary>The QR code on screen: its one-time token and the phone that used it.</summary>
internal sealed class QrSession(byte[] token, byte[] tokenId, long expiresUs, Func<PubKeyFile, string?> save)
{
    public byte[] Token { get; } = token;
    public byte[] TokenId { get; } = tokenId;
    public long ExpiresUs { get; } = expiresUs;
    public Func<PubKeyFile, string?> Save { get; } = save;
    public byte[]? EnrolledKey { get; set; }
    public string? EnrolledName { get; set; }

    public void Clear() => System.Security.Cryptography.CryptographicOperations.ZeroMemory(Token);
}

/// <summary>Passes session events on and copies them into the debug log while it logs.</summary>
internal sealed class TracedEvents(IHostEvents inner, DebugTrace debug) : IHostEvents
{
    public void StateChanged() => inner.StateChanged();

    public void Log(string message)
    {
        if (debug.Enabled) debug.Add(DebugTrace.Kind.State, message);
        inner.Log(message);
    }
}
