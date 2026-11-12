using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// The engine's published copy of what the overlay windows, the held-keys marker and the UI read
/// many times a second: the newest OVERLAY and layout, each overlay window's pose, its "Fade when
/// phone rests" state, the outputs a laser pointer shows held, the injected held set, and whether a
/// session is up. The engine writes it under its lock when one of them changed (mostly gp-rx, after
/// each packet and Flush); readers copy it without that lock (<see cref="SeqLock"/>), so a reader
/// paused by Windows mid-copy can never keep the input thread waiting.
/// </summary>
internal sealed class OverlayView
{
    private readonly SeqLock _seq = new();

    // OVERLAY
    private readonly OverlayFrame _frame = new();
    private long _rxUs = long.MinValue;
    private OverlayLayout? _layout;
    private long _busyUs = long.MinValue, _silentSinceUs = long.MinValue;
    private float _liveDps = float.NaN;
    private long _liveAtUs = long.MinValue;
    private Window[] _windows = new Window[8];
    private int _count;

    // What a laser pointer's Fire and Aim displays read: the newest STATE's buttons, keys and triggers.
    private readonly StateFrame _held = new();

    // The injected held set (the held-keys marker, the Debug window).
    private readonly byte[] _injectedKeys = new byte[StateFrame.KeyBitmapSize];
    private byte _injectedMouse;
    private int _heldVersion;

    private volatile bool _connected, _padGyroOn;

    private struct Window
    {
        public string Id;
        public bool HasPose;
        public float W, X, Y, Z;
        public long Active0, Active1, Active2;
        public long OffLevelUs;
        public bool PcHandles;
    }

    // ---- written under the engine lock ----

    public bool Connected
    {
        get => _connected;
        set => _connected = value;
    }

    /// <summary>The phone's "Use PC controller for gyro ratchet" is on (the PC controller counts for Fire, Aim and handles).</summary>
    public bool PadGyroOn
    {
        get => _padGyroOn;
        set => _padGyroOn = value;
    }

    /// <summary>The newest OVERLAY (<paramref name="rxUs"/> long.MinValue: none), layout, rest state and every window's pose.</summary>
    public void PublishOverlay(OverlayFrame f, long rxUs, OverlayLayout? layout, OverlayRest rest,
        Dictionary<string, OverlayPose> poses, List<OverlaySettings> overlays)
    {
        Span<long> active = stackalloc long[3];
        _seq.BeginWrite();
        try
        {
            if (rxUs != long.MinValue) f.CopyTo(_frame);
            _rxUs = rxUs;
            _layout = layout;
            _busyUs = rest.BusyUs;
            _silentSinceUs = rest.SilentSinceUs;
            _liveDps = rest.LiveDpsRaw;
            _liveAtUs = rest.LiveAtUs;
            if (overlays.Count > _windows.Length) _windows = new Window[overlays.Count * 2];
            int n = 0;
            foreach (var o in overlays)
            {
                ref var w = ref _windows[n++];
                w.Id = o.Id;
                w.PcHandles = o.HandleSource == OverlayHandleSource.Pc;
                w.OffLevelUs = rest.OffLevelUs(o.Id);
                w.HasPose = poses.TryGetValue(o.Id, out var p);
                if (p != null)
                {
                    w.W = p.W; w.X = p.X; w.Y = p.Y; w.Z = p.Z;
                    p.CopyActiveAt(active);
                    w.Active0 = active[0]; w.Active1 = active[1]; w.Active2 = active[2];
                }
            }
            _count = n;
        }
        finally
        {
            _seq.EndWrite();
        }
    }

    /// <summary>The newest STATE's buttons, hat, mouse buttons, triggers and keys (what <see cref="LaserFire.Held"/> reads).</summary>
    public void PublishHeldOutputs(StateFrame s)
    {
        _seq.BeginWrite();
        _held.Buttons = s.Buttons;
        _held.Hat = s.Hat;
        _held.MouseButtons = s.MouseButtons;
        _held.Lt = s.Lt;
        _held.Rt = s.Rt;
        s.Keys.AsSpan().CopyTo(_held.Keys);
        _seq.EndWrite();
    }

    public int HeldVersion => _heldVersion;

    public void PublishInjected(byte[] keys, byte mouse, int version)
    {
        _seq.BeginWrite();
        keys.AsSpan().CopyTo(_injectedKeys);
        _injectedMouse = mouse;
        _heldVersion = version;
        _seq.EndWrite();
    }

    // ---- read from any thread, no engine lock ----

    /// <summary>
    /// Copies the newest OVERLAY into <paramref name="into"/> with window <paramref name="id"/>'s pose; returns
    /// when it came (host us, long.MinValue: none, <paramref name="into"/> untouched then).
    /// </summary>
    public long Read(OverlayFrame into, string? id, out OverlayLayout? layout, out bool pcHandles)
    {
        Span<long> active = stackalloc long[3];
        while (true)
        {
            int s = _seq.ReadBegin();
            layout = _layout;
            long rx = _rxUs;
            pcHandles = false;
            bool pose = false;
            if (rx != long.MinValue)
            {
                _frame.CopyTo(into);
                var ws = _windows;
                int n = Math.Min(_count, ws.Length);
                for (int i = 0; id != null && i < n; i++)
                {
                    if (!string.Equals(ws[i].Id, id, StringComparison.Ordinal)) continue;
                    var w = ws[i];
                    pcHandles = w.PcHandles;
                    if (pose = w.HasPose)
                    {
                        into.Qw = w.W; into.Qx = w.X; into.Qy = w.Y; into.Qz = w.Z;
                        active[0] = w.Active0; active[1] = w.Active1; active[2] = w.Active2;
                    }
                    break;
                }
            }
            if (_seq.ReadRetry(s)) continue;
            if (rx == long.MinValue) return rx;
            if (pose) into.PoseActive = OverlayPose.ActiveOf(active, System.Diagnostics.Stopwatch.GetTimestamp());
            else
            {
                into.Qw = 1; into.Qx = into.Qy = into.Qz = 0;
                into.PoseActive = 0;
            }
            return rx;
        }
    }

    /// <summary>Window <paramref name="id"/>'s pose (w, x, y, z), or null before it has one.</summary>
    public float[]? PoseOf(string id)
    {
        while (true)
        {
            int s = _seq.ReadBegin();
            Window w = default;
            var ws = _windows;
            int n = Math.Min(_count, ws.Length);
            for (int i = 0; i < n; i++)
                if (string.Equals(ws[i].Id, id, StringComparison.Ordinal))
                {
                    w = ws[i];
                    break;
                }
            if (_seq.ReadRetry(s)) continue;
            return w.HasPose ? [w.W, w.X, w.Y, w.Z] : null;
        }
    }

    /// <summary>"Fade when phone rests" for window <paramref name="id"/> now (<see cref="OverlayRest.RestingOf"/>).</summary>
    public bool Resting(string id, long nowUs, OverlayRestFade fade)
    {
        while (true)
        {
            int s = _seq.ReadBegin();
            long rx = _rxUs, busy = _busyUs, silent = _silentSinceUs, off = long.MinValue;
            var ws = _windows;
            int n = Math.Min(_count, ws.Length);
            for (int i = 0; i < n; i++)
                if (string.Equals(ws[i].Id, id, StringComparison.Ordinal))
                {
                    off = ws[i].OffLevelUs;
                    break;
                }
            if (_seq.ReadRetry(s)) continue;
            return OverlayRest.RestingOf(fade, nowUs, rx, busy, silent, off);
        }
    }

    public float? LiveDps(long nowUs)
    {
        while (true)
        {
            int s = _seq.ReadBegin();
            float dps = _liveDps;
            long at = _liveAtUs;
            if (_seq.ReadRetry(s)) continue;
            return OverlayRest.LiveDpsOf(dps, at, nowUs);
        }
    }

    /// <summary><see cref="LaserFire.Held"/> on the newest STATE and the PC controller's state given.</summary>
    public bool Held(int code, uint pcHeld, float pcL2, float pcR2)
    {
        while (true)
        {
            int s = _seq.ReadBegin();
            bool held = LaserFire.Held(code, _held, pcHeld, pcL2, pcR2);
            if (!_seq.ReadRetry(s)) return held;
        }
    }

    /// <summary>The injected held set into <paramref name="keys"/>; returns its version.</summary>
    public int Injected(Span<byte> keys, out byte mouseButtons)
    {
        while (true)
        {
            int s = _seq.ReadBegin();
            _injectedKeys.AsSpan().CopyTo(keys);
            mouseButtons = _injectedMouse;
            int v = _heldVersion;
            if (!_seq.ReadRetry(s)) return v;
        }
    }
}
