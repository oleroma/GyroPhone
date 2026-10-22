using System.Net;
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
    string? HostKeyFingerprint = null);

/// <summary>A paired phone for the tray: code-paired (in settings) or key-paired (a file in authorized_phones).</summary>
public sealed record PairedPhoneInfo(byte[] PhoneId, string Name, DateTime LastSeenUtc, bool ByKey = false,
    string? KeyFile = null, string? Fingerprint = null);

/// <summary>Diagnostic counters. Never contain key material.</summary>
public sealed record HostCounters(long Datagrams, long Dropped, long BadAuth, long States, long Injections,
    long UnknownUsages, long Errors, long Exceptions);

/// <summary>
/// The host protocol engine (SPEC 3.2). All state is guarded by one engine lock. Entry points:
/// OnDatagram (rx thread: control packets inline, newest STATE kept), Flush (rx thread: inject once),
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

    private readonly object _lock = new();
    private readonly IKeyMouseSink _keyMouse;
    private readonly IKeyProtector _protector;
    private readonly IClock _clock;
    private readonly IHostEvents _events;
    private readonly HostSettings _settings;
    private readonly Dictionary<string, (PairedPhone Info, byte[] PairKey)> _phones = new();
    private readonly P256? _hostKey;
    /// <summary>Key-paired phones by hex keyPhoneId; rebuilt from authorized_phones, never stored in settings.</summary>
    private readonly Dictionary<string, KeyPhone> _keyPhones = new();
    /// <summary>Hex KeyId of every authorized phone key, for ANNOUNCE KnowsYourKey.</summary>
    private readonly HashSet<string> _authorizedKeyIds = new();
    private readonly byte[]? _hostKeyId;
    private readonly SessionManager _sessions;
    private readonly PairingManager _pairing;
    private readonly RateLimiter _errorLimiter = new(Wire.ErrorRatePerIp, Wire.ErrorRateGlobal);
    private readonly LinkStats _stats = new();
    private IPadSink _pad;
    private IDatagramSender? _sender;

    // rx scratch, reused so the STATE path allocates nothing
    private readonly byte[] _plain = new byte[Wire.MaxDatagram];
    private readonly byte[] _reply = new byte[Wire.MaxDatagram];
    private readonly StateFrame _rxFrame = new();
    private readonly StateFrame _pending = new();
    private bool _hasPending;
    private readonly MouseDeltaTracker _mouse = new();
    /// <summary>Motion since the last Flush; both wheels in 1/120 notch.</summary>
    private int _accDx, _accDy, _accWheel, _accHWheel;

    // injected state
    private readonly byte[] _injectedKeys = new byte[StateFrame.KeyBitmapSize];
    private byte _injectedMouse;
    private int _heldVersion;
    // Ups injected while UIPI or the secure desktop may have dropped them; re-sent when that ends.
    private readonly byte[] _blockedUps = new byte[StateFrame.KeyBitmapSize];
    private byte _blockedMouseUps;
    private readonly InjectEvent[] _inject = new InjectEvent[InputDiff.MaxEvents];
    private readonly InjectEvent[] _releaseEvents = new InjectEvent[256 + 8];
    private X360Frame _lastX360;
    private Ds4Frame _lastDs4;
    private bool _padFrameValid;

    private bool _paused, _locked;
    private bool _releasedForSilence;
    private bool _elevatedForeground, _networkPublic;
    private long _lastStatusUs;
    private int _rumbleNext; // (large << 8) | small, plus RumbleDirty while unsent
    private int _lastRumble;
    private long _lastRumbleUs = -RumbleMinIntervalUs;
    private bool _settingsDirty;
    private bool _anyDatagram;
    private bool _loggedX360Dropped;
    private LatencySample _pendingSample;
    private bool _hasPendingSample;

    private readonly uint[] _endedSids = new uint[4];
    private readonly long[] _endedUntilUs = new long[4];
    private int _endedNext;

    private long _datagrams, _dropped, _badAuth, _states, _injections, _unknownUsages, _errors, _exceptions;

    public HostEngine(HostSettings settings, IPadSink pad, IKeyMouseSink keyMouse, IPairPrompt prompt,
        IKeyProtector protector, IClock? clock = null, IHostEvents? events = null,
        Func<int, byte[]>? random = null, Func<P256>? newPairKey = null, P256? hostKey = null)
    {
        _settings = settings;
        _hostKey = hostKey;
        _hostKeyId = hostKey == null ? null : KeyPairing.KeyId(hostKey.PublicKey);
        _pad = pad;
        _keyMouse = keyMouse;
        _protector = protector;
        _clock = clock ?? StopwatchClock.Instance;
        _events = events ?? NullHostEvents.Instance;
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

    /// <summary>Optional hook for the headless latency log; called on the rx thread after injection.</summary>
    public Action<LatencySample>? LatencySampleHook { get; set; }

    public HostSettings Settings => _settings;

    public void AttachSender(IDatagramSender sender)
    {
        lock (_lock) _sender = sender;
    }

    /// <summary>Call once after construction: opens the pairing window on first run when nothing is paired.</summary>
    public void Start(long nowUs)
    {
        lock (_lock)
        {
            if (_phones.Count == 0 && _keyPhones.Count == 0) _pairing.Open(nowUs);
        }
    }

    // =====================================================================================
    // rx path
    // =====================================================================================

    /// <summary>Validates and handles one datagram (SPEC 1.7 order). Control replies go out inline; a STATE is kept for Flush.</summary>
    public void OnDatagram(ReadOnlySpan<byte> d, SocketAddress from, long nowUs)
    {
        lock (_lock)
        {
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
            if (type != PacketType.State && type != PacketType.StateHires)
            {
                HandlePendingControl(s, type, plain, nowUs);
                return;
            }
            Promote(s, nowUs);
        }

        _stats.OnSealed(seq);
        switch (type)
        {
            case PacketType.State:
            case PacketType.StateHires:
                OnState(plain, seq, nowUs);
                break;
            case PacketType.Ping:
                SendPong(s, plain, nowUs);
                break;
            case PacketType.LinkInfo:
                _stats.OnLinkInfo(WireCodec.DecodeLinkInfo(plain));
                break;
            case PacketType.Bye:
                _events.Log($"phone said bye (reason {plain[0]})");
                RememberEnded(s.SessionId);
                EndActive(hostByeReason: null);
                break;
        }
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
        _rxFrame.Decode(plain);
        _pending.CopyFrom(_rxFrame);
        _hasPending = true;
        _releasedForSilence = false;
        _mouse.Apply(_rxFrame.MouseTotalX, _rxFrame.MouseTotalY, _rxFrame.WheelTotal, _rxFrame.HWheelTotal,
            _rxFrame.Hires, nowUs, out int dx, out int dy, out int dw, out int dh);
        _accDx += dx;
        _accDy += dy;
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
            (byte)_settings.OutputMode, (byte)((_pad.DriverOk ? WelcomeFlags.VigemOk : 0) | WelcomeFlags.HiresWheel), from, nowUs);
        Reply(s.Welcome, from);
    }

    private void Promote(HostSession s, long nowUs)
    {
        var old = _sessions.Promote(s);
        if (old != null)
        {
            ReleaseAllLocked();
            SendHostBye(old, HostByeReason.Replaced);
            old.Dispose();
        }
        _mouse.Reset();
        _accDx = _accDy = _accWheel = _accHWheel = 0;
        _stats.Reset();
        _releasedForSilence = false;
        _hasPending = false;
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
        _events.Log($"session started with '{s.PhoneName}'");
        SendStatus(s, nowUs);
        _events.StateChanged();
    }

    /// <summary>Injects the newest STATE received since the last Flush: pad only when the mapped frame changed, SendInput only with events.</summary>
    public void Flush(long nowUs)
    {
        lock (_lock)
        {
            SendPendingRumble(nowUs);
            if (!_hasPending) return;
            _hasPending = false;
            int dx = _accDx, dy = _accDy, dw = _accWheel, dh = _accHWheel;
            _accDx = _accDy = _accWheel = _accHWheel = 0;
            if (_paused || _locked) return; // baseline already updated, nothing injected

            SubmitPad(_pending);

            bool blocked = _elevatedForeground;
            Span<byte> before = stackalloc byte[StateFrame.KeyBitmapSize];
            byte beforeMouse = _injectedMouse;
            if (blocked) _injectedKeys.CopyTo(before);
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
                _keyMouse.Inject(_inject.AsSpan(0, n));
                _injections++;
                for (int i = 0; i < n; i++)
                {
                    if (_inject[i].Kind == InjectKind.Key || (_inject[i].Flags & ~(InjectEvent.MouseMove | InjectEvent.MouseWheel | InjectEvent.MouseHWheel)) != 0)
                    {
                        _heldVersion++;
                        break;
                    }
                }
            }
            if (_hasPendingSample)
            {
                _hasPendingSample = false;
                LatencySampleHook?.Invoke(_pendingSample with { InjectUs = _clock.NowUs });
            }
        }
    }

    private void SubmitPad(StateFrame s)
    {
        switch (_pad.Plugged)
        {
            case OutputMode.Xbox360:
                var x = PadMapper.ToX360(s);
                if ((s.Buttons & PadMapper.X360DroppedButtons) != 0 && !_loggedX360Dropped)
                {
                    _loggedX360Dropped = true;
                    _events.Log("M1-M4/SHARE have no Xbox 360 button; dropped");
                }
                if (_padFrameValid && x == _lastX360) return;
                _pad.Submit(x);
                _lastX360 = x;
                _padFrameValid = true;
                break;
            case OutputMode.DualShock4:
                var f = PadMapper.ToDs4(s);
                if (_padFrameValid && f == _lastDs4) return;
                _pad.Submit(f);
                _lastDs4 = f;
                _padFrameValid = true;
                break;
        }
    }

    // =====================================================================================
    // watchdog
    // =====================================================================================

    /// <summary>Watchdog tick (every WATCHDOG_TICK_MS): silence release, session drop, pending expiry, pairing timers, 1 Hz HOST_STATUS.</summary>
    public void OnTick(long nowUs)
    {
        lock (_lock)
        {
            var a = _sessions.Active;
            if (a != null)
            {
                long silent = nowUs - a.LastRxUs;
                if (silent >= SessionTimeoutUs)
                {
                    _events.Log($"session with '{a.PhoneName}' timed out");
                    EndActive(hostByeReason: null);
                }
                else if (silent >= ReleaseTimeoutUs && !_releasedForSilence)
                {
                    _releasedForSilence = true;
                    _hasPending = false;
                    ReleaseAllLocked();
                    _events.StateChanged();
                }
            }
            _sessions.ExpirePending(nowUs);
            if (_pairing.Tick(nowUs)) _events.StateChanged();

            SendPendingRumble(nowUs);
            a = _sessions.Active;
            if (a != null && nowUs - _lastStatusUs >= StatusIntervalUs)
            {
                _stats.Roll();
                SendStatus(a, nowUs);
            }
        }
    }

    /// <summary>Environment flags from the watchdog's probes (taken outside the lock).</summary>
    public void SetEnvironment(bool elevatedForeground, bool networkPublic)
    {
        lock (_lock)
        {
            if (elevatedForeground == _elevatedForeground && networkPublic == _networkPublic) return;
            bool wasBlocked = Blocked;
            _elevatedForeground = elevatedForeground;
            _networkPublic = networkPublic;
            if (wasBlocked && !Blocked) ResyncAfterBlock();
            _events.StateChanged();
        }
    }

    /// <summary>The injected held set and its version, for the held-keys marker.</summary>
    public int HeldSnapshot(Span<byte> keys, out byte mouseButtons)
    {
        lock (_lock)
        {
            _injectedKeys.CopyTo(keys);
            mouseButtons = _injectedMouse;
            return _heldVersion;
        }
    }

    /// <summary>Serializes settings under the lock if changed, and returns the JSON (written by the caller outside the lock).</summary>
    public string? TakeDirtySettings()
    {
        lock (_lock)
        {
            if (!_settingsDirty) return null;
            _settingsDirty = false;
            return SettingsStore.Serialize(_settings);
        }
    }

    // =====================================================================================
    // UI commands
    // =====================================================================================

    public void AnswerPair(uint attemptId, bool allow)
    {
        lock (_lock)
        {
            _pairing.Answer(attemptId, allow, _clock.NowUs);
            _events.StateChanged();
        }
    }

    public void OpenPairingWindow()
    {
        lock (_lock)
        {
            _pairing.Open(_clock.NowUs);
            _events.StateChanged();
        }
    }

    public void ClosePairingWindow()
    {
        lock (_lock)
        {
            _pairing.Close();
            _events.StateChanged();
        }
    }

    public void SetOutputMode(OutputMode mode)
    {
        lock (_lock)
        {
            if (mode == _settings.OutputMode) return;
            ReleaseAllLocked();
            _pad.Unplug();
            _settings.OutputMode = mode;
            _settingsDirty = true;
            if (_sessions.Active != null) PlugPad();
            _events.StateChanged();
        }
    }

    /// <summary>"Start with Windows" preference; the caller updates the HKCU Run entry.</summary>
    public void SetStartWithWindows(bool enabled)
    {
        lock (_lock)
        {
            if (enabled == _settings.StartWithWindows) return;
            _settings.StartWithWindows = enabled;
            _settingsDirty = true;
        }
    }

    /// <summary>Replaces the pad sink (e.g. "Retry driver" after installing ViGEmBus).</summary>
    public void SetPadSink(IPadSink pad)
    {
        lock (_lock)
        {
            _pad.Rumble -= OnRumble;
            _pad.Unplug();
            _pad = pad;
            _pad.Rumble += OnRumble;
            _padFrameValid = false;
            if (_sessions.Active != null) PlugPad();
            _events.StateChanged();
        }
    }

    /// <summary>"Pause input": releases everything; STATEs keep updating the baseline but inject nothing.</summary>
    public void SetPaused(bool paused)
    {
        lock (_lock)
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
        lock (_lock)
        {
            if (locked == _locked) return;
            bool wasBlocked = Blocked;
            _locked = locked;
            if (locked) ReleaseAllLocked();
            else if (wasBlocked && !Blocked) ResyncAfterBlock();
            _events.StateChanged();
        }
    }

    /// <summary>Release-all for external triggers (session switch, suspend, exit).</summary>
    public void ReleaseAll()
    {
        lock (_lock) ReleaseAllLocked();
    }

    public bool Forget(byte[] phoneId)
    {
        lock (_lock)
        {
            var hex = Convert.ToHexString(phoneId);
            if (!_phones.Remove(hex, out var entry)) return false;
            CryptographicOperations.ZeroMemory(entry.PairKey);
            _settings.PairedPhones.RemoveAll(p => p.PhoneId.AsSpan().SequenceEqual(phoneId));
            _settingsDirty = true;
            _sessions.RemovePending(phoneId);
            if (_sessions.Active is { } a && a.SamePhone(phoneId)) EndActive(HostByeReason.Forgotten);
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
        lock (_lock)
        {
            if (_hostKey == null) return;
            var next = new Dictionary<string, KeyPhone>();
            _authorizedKeyIds.Clear();
            foreach (var p in phones)
            {
                _authorizedKeyIds.Add(Convert.ToHexString(KeyPairing.KeyId(p.Key.PublicKey)));
                var (id, pairKey) = KeyPairing.HostSecrets(_hostKey, p.Key.PublicKey);
                var hex = Convert.ToHexString(id);
                if (next.ContainsKey(hex))
                {
                    CryptographicOperations.ZeroMemory(pairKey); // the same key in two files
                    continue;
                }
                var name = p.Key.Name.Length > 0 ? p.Key.Name : Path.GetFileNameWithoutExtension(p.File);
                if (_keyPhones.TryGetValue(hex, out var old))
                {
                    // Same key: keep the array sessions may still hold, take the (maybe renamed) file.
                    CryptographicOperations.ZeroMemory(pairKey);
                    next[hex] = new KeyPhone(p.File, name, old.PhoneId, old.PairKey, old.Fingerprint) { LastSeenUtc = old.LastSeenUtc };
                }
                else
                {
                    next[hex] = new KeyPhone(p.File, name, id, pairKey, p.Key.Fingerprint) { LastSeenUtc = DateTime.MinValue };
                }
            }
            foreach (var (hex, old) in _keyPhones)
            {
                if (next.ContainsKey(hex)) continue;
                _events.Log($"key of '{old.Name}' removed; phone no longer authorized");
                _sessions.RemovePending(old.PhoneId);
                if (_sessions.Active is { } a && a.SamePhone(old.PhoneId)) EndActive(HostByeReason.Forgotten);
                CryptographicOperations.ZeroMemory(old.PairKey);
            }
            foreach (var (hex, kp) in next)
            {
                if (!_keyPhones.ContainsKey(hex)) _events.Log($"authorized '{kp.Name}' by key {kp.Fingerprint}");
            }
            _keyPhones.Clear();
            foreach (var (hex, kp) in next) _keyPhones[hex] = kp;
            _events.StateChanged();
        }
    }

    /// <summary>Exit: release-all, then HOST_BYE(EXIT) and unplug.</summary>
    public void Shutdown()
    {
        lock (_lock)
        {
            ReleaseAllLocked();
            if (_sessions.Active != null) EndActive(HostByeReason.Exit);
            _pad.Unplug();
        }
    }

    public HostSnapshot Snapshot()
    {
        lock (_lock)
        {
            long now = _clock.NowUs;
            var a = _sessions.Active;
            var phones = _phones.Values.Select(p => new PairedPhoneInfo(p.Info.PhoneId, p.Info.Name, p.Info.LastSeenUtc))
                .Concat(_keyPhones.Values.Select(k => new PairedPhoneInfo(k.PhoneId, k.Name, k.LastSeenUtc, true, k.File, k.Fingerprint)))
                .ToList();
            return new HostSnapshot(
                a?.PhoneName, a != null, _releasedForSilence, _settings.OutputMode, _pad.DriverOk,
                _pad.Plugged != OutputMode.None, _paused || _locked, _elevatedForeground, _networkPublic,
                _pairing.WindowRemainingS(now), _pairing.PromptInfo,
                _stats.RxPerSec, _stats.LossPermille, _stats.LatP50Us, _stats.LatP99Us, _stats.JitterUs,
                _stats.LastLinkInfo, _anyDatagram, phones,
                new HostCounters(_datagrams, _dropped, _badAuth, _states, _injections, _unknownUsages, _errors, _exceptions),
                _hostKey == null ? null : KeyPairing.Fingerprint(_hostKey.PublicKey));
        }
    }

    // =====================================================================================
    // internals
    // =====================================================================================

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
        _events.Log($"paired with '{name}'");
        _events.StateChanged();
    }

    private void EndActive(byte? hostByeReason)
    {
        var a = _sessions.Active;
        if (a == null) return;
        ReleaseAllLocked();
        if (hostByeReason is { } r)
        {
            SendHostBye(a, r);
            RememberEnded(a.SessionId); // in-flight packets cross the HOST_BYE
        }
        _sessions.DropActive();
        _pad.Unplug();
        _padFrameValid = false;
        _hasPending = false;
        _releasedForSilence = false;
        _events.StateChanged();
    }

    private void ReleaseAllLocked()
    {
        _hasPending = false;
        if (Blocked)
        {
            for (int i = 0; i < _injectedKeys.Length; i++) _blockedUps[i] |= _injectedKeys[i];
            _blockedMouseUps |= _injectedMouse;
        }
        while (InputDiff.AnyHeld(_injectedKeys, _injectedMouse))
        {
            int n = InputDiff.ReleaseAll(_injectedKeys, ref _injectedMouse, _releaseEvents);
            if (n > 0) _keyMouse.Inject(_releaseEvents.AsSpan(0, n));
            _heldVersion++;
        }
        // Pad to neutral but stay plugged.
        switch (_pad.Plugged)
        {
            case OutputMode.Xbox360:
                _pad.Submit(X360Frame.Neutral);
                _lastX360 = X360Frame.Neutral;
                _padFrameValid = true;
                break;
            case OutputMode.DualShock4:
                _pad.Submit(Ds4Frame.Neutral);
                _lastDs4 = Ds4Frame.Neutral;
                _padFrameValid = true;
                break;
        }
    }

    /// <summary>SendInput may be silently dropped: an elevated window is in front (UIPI) or the secure desktop is up.</summary>
    private bool Blocked => _elevatedForeground || _locked;

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
        Array.Clear(_blockedUps);
        _blockedMouseUps = 0;
        while (InputDiff.AnyHeld(ups, upsMouse))
        {
            int n = InputDiff.ReleaseAll(ups, ref upsMouse, _releaseEvents);
            if (n > 0) _keyMouse.Inject(_releaseEvents.AsSpan(0, n));
        }

        Span<byte> sent = stackalloc byte[StateFrame.KeyBitmapSize];
        byte sentMouse = 0;
        int unknown = 0;
        while (true)
        {
            int n = InputDiff.Diff(sent, _injectedKeys, ref sentMouse, _injectedMouse, 0, 0, 0, _inject, ref unknown);
            if (n == 0) break;
            _keyMouse.Inject(_inject.AsSpan(0, n));
        }
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

    private void PlugPad()
    {
        var mode = _settings.OutputMode;
        _padFrameValid = false;
        if (mode == OutputMode.None || !_pad.DriverOk) return;
        if (_pad.Plugged == mode) return;
        if (!_pad.Plug(mode)) _events.Log($"could not plug {mode} pad");
    }

    private byte[] Announce(Discover disc, long nowUs)
    {
        ushort flags = 0;
        if (_pairing.IsOpen(nowUs)) flags |= AnnounceFlags.PairingOpen;
        if (_pad.DriverOk) flags |= AnnounceFlags.VigemOk;
        if (_phones.ContainsKey(Convert.ToHexString(disc.PhoneId)) || _keyPhones.ContainsKey(Convert.ToHexString(disc.PhoneId)))
            flags |= AnnounceFlags.PairedWithYou;
        if (_hostKeyId != null && disc.HostKeyId is { } hk && hk.AsSpan().SequenceEqual(_hostKeyId)) flags |= AnnounceFlags.KeyMatch;
        if (disc.PhoneKeyId is { } pk && pk.AsSpan().ContainsAnyExcept((byte)0) && _authorizedKeyIds.Contains(Convert.ToHexString(pk)))
            flags |= AnnounceFlags.KnowsYourKey;
        if (_sessions.Active is { } a && !a.SamePhone(disc.PhoneId)) flags |= AnnounceFlags.Busy;
        var b = new byte[Wire.AnnounceSize];
        WireCodec.EncodeAnnounce(new Announce(disc.ReqId, _settings.HostId, flags, _settings.HostName), b);
        return b;
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
        if (_pad.DriverOk) flags |= HostStatusFlags.VigemOk;
        if (_pad.Plugged != OutputMode.None) flags |= HostStatusFlags.PadPlugged;
        if (_paused || _locked) flags |= HostStatusFlags.InputPaused;
        if (_elevatedForeground) flags |= HostStatusFlags.ElevatedForeground;
        if (_networkPublic) flags |= HostStatusFlags.NetworkPublic;
        Span<byte> p = stackalloc byte[Wire.HostStatusPlain];
        WireCodec.EncodeHostStatus(new HostStatus(s.HighestRxSeq, _stats.RxPerSec, _stats.LossPermille,
            _stats.LatP50Us, _stats.LatP99Us, _stats.JitterUs, (byte)_settings.OutputMode, flags), p);
        s.SendSealed(PacketType.HostStatus, p, _sender);
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

    private sealed class KeyPhone(string file, string name, byte[] phoneId, byte[] pairKey, string fingerprint)
    {
        public string File { get; } = file;
        public string Name { get; } = name;
        public byte[] PhoneId { get; } = phoneId;
        public byte[] PairKey { get; } = pairKey;
        public string Fingerprint { get; } = fingerprint;
        public DateTime LastSeenUtc { get; set; }
    }
}
