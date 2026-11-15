using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// The overlay side's published copy of what the overlay windows and the UI read many times a second: the newest
/// OVERLAY and layout, each overlay window's pose and its "Fade when phone rests" state. <see cref="OverlayWorker"/>
/// writes it on its own thread (gp-overlay); readers copy it without a lock (<see cref="SeqLock"/>), so a reader
/// paused by Windows mid-copy never keeps the overlay thread waiting.
/// </summary>
internal sealed class OverlayView
{
    private readonly SeqLock _overlaySeq = new();

    // OVERLAY
    private readonly OverlayFrame _frame = new();
    private long _rxUs = long.MinValue;
    private OverlayLayout? _layout;
    private long _busyUs = long.MinValue, _silentSinceUs = long.MinValue;
    private float _liveDps = float.NaN;
    private long _liveAtUs = long.MinValue;
    private Window[] _windows = new Window[8];
    private int _count;

    private struct Window
    {
        public string Id;
        public bool HasPose;
        public float W, X, Y, Z;
        public float MoveX, MoveY, MoveZ;
        public long Active0, Active1, Active2;
        public long OffLevelUs;
        public bool PcHandles;
        public PoseHistory? History;
    }

    // ---- written by OverlayWorker ----

    /// <summary>The newest OVERLAY (<paramref name="rxUs"/> long.MinValue: none), layout, rest state and every window's pose.</summary>
    public void PublishOverlay(OverlayFrame f, long rxUs, OverlayLayout? layout, OverlayRest rest,
        Dictionary<string, OverlayPose> poses, List<OverlaySettings> overlays)
    {
        Span<long> active = stackalloc long[3];
        _overlaySeq.BeginWrite();
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
                w.History = p?.History;
                if (p != null)
                {
                    w.W = p.W; w.X = p.X; w.Y = p.Y; w.Z = p.Z;
                    w.MoveX = p.Move.X; w.MoveY = p.Move.Y; w.MoveZ = p.Move.Z;
                    p.CopyActiveAt(active);
                    w.Active0 = active[0]; w.Active1 = active[1]; w.Active2 = active[2];
                }
            }
            _count = n;
        }
        finally
        {
            _overlaySeq.EndWrite();
        }
    }

    // ---- read from any thread, no lock ----

    /// <summary>
    /// Copies the newest OVERLAY into <paramref name="into"/> with window <paramref name="id"/>'s pose; returns
    /// when it came (host us, long.MinValue: none, <paramref name="into"/> untouched then).
    /// </summary>
    public long Read(OverlayFrame into, string? id, out OverlayLayout? layout, out bool pcHandles)
    {
        Span<long> active = stackalloc long[3];
        while (true)
        {
            int s = _overlaySeq.ReadBegin();
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
                        into.MoveX = w.MoveX; into.MoveY = w.MoveY; into.MoveZ = w.MoveZ;
                        active[0] = w.Active0; active[1] = w.Active1; active[2] = w.Active2;
                    }
                    break;
                }
            }
            if (_overlaySeq.ReadRetry(s)) continue;
            if (rx == long.MinValue) return rx;
            if (pose) into.PoseActive = OverlayPose.ActiveOf(active, System.Diagnostics.Stopwatch.GetTimestamp());
            else
            {
                into.Qw = 1; into.Qx = into.Qy = into.Qz = 0;
                into.MoveX = into.MoveY = into.MoveZ = 0;
                into.PoseActive = 0;
            }
            return rx;
        }
    }

    /// <summary>Window <paramref name="id"/>'s poses sample by sample (<see cref="OverlayPose.History"/>), or null while it has none.</summary>
    public PoseHistory? HistoryOf(string id)
    {
        while (true)
        {
            int s = _overlaySeq.ReadBegin();
            PoseHistory? h = null;
            var ws = _windows;
            int n = Math.Min(_count, ws.Length);
            for (int i = 0; i < n; i++)
                if (string.Equals(ws[i].Id, id, StringComparison.Ordinal))
                {
                    h = ws[i].History;
                    break;
                }
            if (_overlaySeq.ReadRetry(s)) continue;
            return h;
        }
    }

    /// <summary>Window <paramref name="id"/>'s pose (w, x, y, z), or null before it has one.</summary>
    public float[]? PoseOf(string id)
    {
        while (true)
        {
            int s = _overlaySeq.ReadBegin();
            Window w = default;
            var ws = _windows;
            int n = Math.Min(_count, ws.Length);
            for (int i = 0; i < n; i++)
                if (string.Equals(ws[i].Id, id, StringComparison.Ordinal))
                {
                    w = ws[i];
                    break;
                }
            if (_overlaySeq.ReadRetry(s)) continue;
            return w.HasPose ? [w.W, w.X, w.Y, w.Z] : null;
        }
    }

    /// <summary>"Fade when phone rests" for window <paramref name="id"/> now (<see cref="OverlayRest.RestingOf"/>).</summary>
    public bool Resting(string id, long nowUs, OverlayRestFade fade)
    {
        while (true)
        {
            int s = _overlaySeq.ReadBegin();
            long rx = _rxUs, busy = _busyUs, silent = _silentSinceUs, off = long.MinValue;
            var ws = _windows;
            int n = Math.Min(_count, ws.Length);
            for (int i = 0; i < n; i++)
                if (string.Equals(ws[i].Id, id, StringComparison.Ordinal))
                {
                    off = ws[i].OffLevelUs;
                    break;
                }
            if (_overlaySeq.ReadRetry(s)) continue;
            return OverlayRest.RestingOf(fade, nowUs, rx, busy, silent, off);
        }
    }

    public float? LiveDps(long nowUs)
    {
        while (true)
        {
            int s = _overlaySeq.ReadBegin();
            float dps = _liveDps;
            long at = _liveAtUs;
            if (_overlaySeq.ReadRetry(s)) continue;
            return OverlayRest.LiveDpsOf(dps, at, nowUs);
        }
    }
}
