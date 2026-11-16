using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// The overlay side of the phone's data, on its own thread (gp-overlay) in the companion, away from the input
/// process: takes what <see cref="OverlayInbox"/> holds, turns each overlay window's pose (<see cref="OverlayPose"/>),
/// keeps "Fade when phone rests" (<see cref="OverlayRest"/>), the Speed graph (<see cref="SpeedTrace"/>) and the
/// layout, and publishes them for the windows through <see cref="OverlayView"/>. The input process only copies the
/// bytes in and sets <c>wake</c>.
/// <para>
/// It works on its own copies of the overlay settings, handed over whole each time they change
/// (<see cref="SetSettings"/>), so it never reads what a window is changing. What it changes in them itself
/// (Level, Set orientation) goes back through <c>saveLevel</c>, as a window's click would.
/// </para>
/// <para>
/// Without <see cref="Start"/> (tests, the headless host) <see cref="Signal"/> does the work at once on the
/// caller's thread.
/// </para>
/// </summary>
internal sealed class OverlayWorker
{
    /// <summary>What Level or Set orientation changed in overlay <c>id</c>: its Level tilt, and its saved grip when <c>setRest</c>.</summary>
    public delegate void SaveLevel(string id, float[]? levelUp, bool setRest, float[]? rest);

    private readonly OverlayInbox _inbox;
    private readonly OverlayView _view;
    private readonly SaveLevel _saveLevel;
    private readonly EventWaitHandle _wake;
    // Level asked for by a window or a shortcut (the companion's own side, not through the inbox).
    private readonly System.Collections.Concurrent.ConcurrentQueue<string> _levels = new();
    private Thread? _thread;
    private int _pumping;

    private readonly OverlayFrame _frame = new();
    private readonly OverlaySamples _samples = new();
    private readonly OverlayFrame _sub = new();
    private readonly OverlayTotals _totals = new();
    private readonly OverlayLayoutAssembler _layout = new();
    private readonly OverlayMarksAssembler _marks = new();
    private readonly Dictionary<string, OverlayPose> _poses = [];
    private readonly OverlayRest _rest = new();
    // The phone's Level displays presses last seen (-1 = none yet: the first count levels nothing).
    private int _levelPresses = -1;
    private long _rxUs = long.MinValue;

    private sealed record SettingsSet(List<OverlaySettings> Overlays, OverlayRestFade Fade);
    private SettingsSet _settings = new([], new OverlayRestFade());
    private SettingsSet? _nextSettings;

    /// <summary>A worker for <paramref name="inbox"/>, woken by <paramref name="wake"/> (set by whoever puts something in).</summary>
    public OverlayWorker(OverlayInbox inbox, OverlayView view, SaveLevel saveLevel, EventWaitHandle wake)
    {
        _inbox = inbox;
        _view = view;
        _saveLevel = saveLevel;
        _wake = wake;
    }

    /// <summary>The Speed graph overlays' yaw, pitch and roll speeds (fed while one is shown).</summary>
    public SpeedTrace Speeds { get; } = new();

    /// <summary>Runs the work on its own thread from now on.</summary>
    public void Start()
    {
        if (_thread != null) return;
        _thread = new Thread(Run) { Name = "gp-overlay", IsBackground = true, Priority = ThreadPriority.AboveNormal };
        _thread.Start();
    }

    /// <summary>
    /// New overlay settings: <paramref name="overlays"/> and <paramref name="fade"/> are copies the caller never
    /// touches again. Taken before the next packet.
    /// </summary>
    public void SetSettings(List<OverlaySettings> overlays, OverlayRestFade fade)
    {
        Volatile.Write(ref _nextSettings, new SettingsSet(overlays, fade));
    }

    /// <summary>Level overlay <paramref name="id"/> (its Level button, Level all, a shortcut).</summary>
    public void Level(string id)
    {
        _levels.Enqueue(id);
        Signal();
    }

    private int _skip;

    /// <summary>Drops what waits in the inbox and starts again (a companion that attaches to a running input process).</summary>
    public void SkipWaiting()
    {
        Volatile.Write(ref _skip, 1);
        Signal();
    }

    /// <summary>Something waits in the inbox (or new settings).</summary>
    public void Signal()
    {
        if (_thread != null) _wake.Set();
        else Pump();
    }

    private void Run()
    {
        GcWatch.NameCurrentThread("gp-overlay");
        while (true)
        {
            _wake.WaitOne();
            try
            {
                Pump();
            }
            catch (Exception e) when (e is not OutOfMemoryException)
            {
                // A bad frame never stops the overlays for good.
                Failures++;
                LastFailure = e.GetType().Name;
            }
        }
    }

    public long Failures;
    public string? LastFailure;

    /// <summary>Does everything waiting (one caller at a time; a call from inside the work is a no-op).</summary>
    public void Pump()
    {
        if (Interlocked.Exchange(ref _pumping, 1) != 0) return;
        try
        {
            bool changed = false;
            if (Interlocked.Exchange(ref _skip, 0) != 0)
            {
                _inbox.SkipAll();
                Clear(Environment.TickCount64 * 1000, layoutToo: true);
                changed = true;
            }
            while (_levels.TryDequeue(out var level))
            {
                LevelNow(level);
                changed = true;
            }
            while (true)
            {
                if (Interlocked.Exchange(ref _nextSettings, null) is { } next)
                {
                    _settings = next;
                    foreach (var id in _poses.Keys.ToArray())
                        if (_settings.Overlays.Find(o => o.Id == id) is not { } o || PoseKindChanged(id, o)) _poses.Remove(id);
                    changed = true;
                }
                if (_inbox.ClearLost())
                {
                    Clear(Environment.TickCount64 * 1000, layoutToo: true);
                    changed = true;
                }
                if (!_inbox.TryPeek(out var s)) break;
                try
                {
                    changed |= Handle(s);
                }
                finally
                {
                    _inbox.Release();
                }
            }
            if (changed) Publish();
        }
        finally
        {
            Volatile.Write(ref _pumping, 0);
        }
    }

    // Which kind each pose was made for: a window that becomes another kind starts again.
    private readonly Dictionary<string, OverlayKind> _poseKinds = [];

    private bool PoseKindChanged(string id, OverlaySettings o) => _poseKinds.TryGetValue(id, out var k) && k != o.Kind;

    private bool Handle(in OverlayInbox.Slot s)
    {
        var plain = s.Data;
        switch (s.Kind)
        {
            case OverlayInbox.Kind.Overlay:
                return OnOverlay(plain, in s);
            case OverlayInbox.Kind.Samples:
                _samples.Decode(plain);
                return false;
            case OverlayInbox.Kind.Layout:
                _layout.Add(plain);
                return true;
            case OverlayInbox.Kind.Frames:
                return _marks.Add(plain);
            case OverlayInbox.Kind.TotalsReset:
                _totals.Reset();
                return true;
            case OverlayInbox.Kind.Renew:
                _layout.Renew();
                _marks.Renew();
                foreach (var pose in _poses.Values) pose.ForgetPresses();
                _levelPresses = -1;
                return true;
            case OverlayInbox.Kind.Clear:
                Clear(s.RxUs, s.Flag);
                return true;
        }
        return false;
    }

    private void Clear(long nowUs, bool layoutToo)
    {
        _rxUs = long.MinValue;
        _rest.Clear(nowUs);
        _poses.Clear();
        _poseKinds.Clear();
        _levelPresses = -1;
        _totals.Reset();
        if (layoutToo)
        {
            _layout.Reset();
            _marks.Reset();
        }
    }

    private bool OnOverlay(ReadOnlySpan<byte> plain, in OverlayInbox.Slot s)
    {
        if (_totals.IsLate(plain)) return false;
        var f = _frame;
        f.Decode(plain);
        _totals.ToDeltas(f);
        if (s.Gated)
        {
            // The overlays turn with the axes on after the PC controller, and its ratchets count for "On ratchet".
            f.Axes = s.GateAxes;
            f.Flags = (byte)(s.GateAxes != 0 ? f.Flags | OverlayFrame.FlagGyroActive : f.Flags & ~OverlayFrame.FlagGyroActive);
            if (s.GateHeld) f.Flags |= OverlayFrame.FlagRatchetHeld;
            f.RatchetPresses = unchecked((byte)(f.RatchetPresses + s.GatePresses));
        }
        bool graph = false;
        foreach (var o in _settings.Overlays)
        {
            if (!o.Shown) continue;
            if (o.Kind == OverlayKind.Graph) graph = true;
            if (!_poses.TryGetValue(o.Id, out var pose))
            {
                _poses[o.Id] = pose = new OverlayPose();
                _poseKinds[o.Id] = o.Kind;
            }
            // A pointer that paints follows every gyro sample (the samples sent just before this frame).
            if (o.FollowsSamples && _samples.Matches(f)) pose.FeedSamples(f, o, _samples, _sub, s.RxMs);
            else pose.Feed(f, o);
        }
        if (graph) FeedSpeeds(f);
        // Set orientation on the phone: every overlay shown starts again from its new base orientation.
        if (_levelPresses >= 0 && f.LevelPresses != _levelPresses)
            foreach (var o in _settings.Overlays)
                if (o.Shown) StartOver(o);
        _levelPresses = f.LevelPresses;
        _rest.Feed(f, s.RxUs, _settings.Fade, _settings.Overlays);
        _rxUs = s.RxUs;
        return true;
    }

    /// <summary>The Speed graph: each gyro sample's speed (the samples sent with <paramref name="f"/>), or the frame's when they didn't come.</summary>
    private void FeedSpeeds(OverlayFrame f)
    {
        bool held = f.RatchetHeld;
        if (_samples.Matches(f))
        {
            var t = _samples.Turns;
            for (int k = 0; k < _samples.Count; k++)
                Speeds.Add(t[k * 4], t[k * 4 + 1], t[k * 4 + 2], t[k * 4 + 3], held);
        }
        else Speeds.Add(f.TurnYaw, f.TurnPitch, f.TurnRoll, f.TurnSeconds, held);
    }

    /// <summary>Which way is up now, as a unit vector, or null without a frame that says.</summary>
    private float[]? UpNow()
    {
        var f = _frame;
        if (_rxUs == long.MinValue || !f.HasUp) return null;
        float g = MathF.Sqrt(f.UpX * f.UpX + f.UpY * f.UpY + f.UpZ * f.UpZ);
        return g > 0f ? [f.UpX / g, f.UpY / g, f.UpZ / g] : null;
    }

    /// <summary>
    /// Level: the way the phone is held now becomes overlay <paramref name="id"/>'s resting pose, shown face-on.
    /// With gravity that is the tilt now (saved as <see cref="OverlaySettings.Rest"/>); without (turned by the gyro
    /// since the last reset, or a trackball) the pose simply starts again from straight.
    /// </summary>
    private void LevelNow(string id)
    {
        var o = _settings.Overlays.Find(x => x.Id == id);
        if (o == null) return;
        _poses.TryGetValue(id, out var pose);
        // Which way is up now: "Fade when phone rests" counts the phone flat near this tilt.
        var up = UpNow();
        if (up != null) o.LevelUp = up;
        if (o.Gravity && !o.FollowsCamera)
        {
            if (pose == null)
            {
                if (up != null) _saveLevel(id, up, false, null);
                return;
            }
            o.Rest = pose.TiltOnly();
        }
        else
        {
            pose?.Reset();
            o.Rest = null;
        }
        _saveLevel(id, up, true, o.Rest);
    }

    /// <summary>
    /// Set orientation on the phone: overlay <paramref name="o"/> starts again from the phone's new base orientation,
    /// which the frames now come in: level (no saved grip), facing ahead, and "Fade when phone rests" counts the phone
    /// flat near the way it is held now.
    /// </summary>
    private void StartOver(OverlaySettings o)
    {
        var f = _frame;
        float[]? up = null;
        if (f.HasUp && MathF.Sqrt(f.UpX * f.UpX + f.UpY * f.UpY + f.UpZ * f.UpZ) is var g and > 0f)
            o.LevelUp = up = [f.UpX / g, f.UpY / g, f.UpZ / g];
        o.Rest = null;
        if (_poses.TryGetValue(o.Id, out var pose)) pose.Reset();
        _saveLevel(o.Id, up, true, null);
    }

    private void Publish() => _view.PublishOverlay(_frame, _rxUs, _layout.Layout, _marks.Marks, _rest, _poses, _settings.Overlays);
}
