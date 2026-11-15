using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// The companion's side of the overlays: their settings (with the tray's own, saved in companion.json, see
/// <see cref="Ui"/>), the overlay thread (<see cref="OverlayWorker"/>) and what the overlay windows read every frame.
/// The phone's overlay data comes from the input process through <see cref="SharedRegion"/> (the overlay ring and
/// <see cref="HeldMirror"/>); what the overlays ask of the phone goes back through <c>tellInput</c>
/// (<see cref="HostEngine.SetOverlay"/>). Nothing here runs in the input process.
/// <para>
/// Settings change on the window thread; the overlay thread works on copies (<see cref="OverlayWorker.SetSettings"/>)
/// and hands Level back through <see cref="OverlayWorker.SaveLevel"/>. A lock keeps the two apart.
/// </para>
/// </summary>
public sealed class OverlayHost
{
    /// <summary>What the overlays ask of the phone: on (the phone sends OVERLAY), compass readings, each gyro sample.</summary>
    public delegate void TellInput(bool on, bool compass, bool samples);

    private readonly object _gate = new();
    private readonly HostSettings _ui;
    private readonly SharedRegion _region;
    private readonly IClock _clock;
    private readonly TellInput _tellInput;
    private readonly OverlayView _view = new();
    private readonly OverlayWorker _worker;
    private bool _on;
    private int _askedAsks = -1;
    private bool _dirty;

    [ThreadStatic] private static StateFrame? t_scratch;

    /// <param name="ui">The companion's settings (companion.json): overlays, presets, shortcuts, Fade when phone rests, tray click.</param>
    /// <param name="wake">Set by the input side when it put something in the overlay ring.</param>
    public OverlayHost(HostSettings ui, SharedRegion region, EventWaitHandle wake, TellInput tellInput, IClock? clock = null)
    {
        _ui = ui;
        _region = region;
        _clock = clock ?? StopwatchClock.Instance;
        _tellInput = tellInput;
        _worker = new OverlayWorker(region.Overlays, _view, SaveLevel, wake);
        Push();
    }

    /// <summary>The companion's settings; read on the window thread, changed only through this class (and <see cref="Change"/>).</summary>
    public HostSettings Ui => _ui;

    /// <summary>Runs the overlay side on its own thread (gp-overlay). Without it, <see cref="Pump"/> does the work (tests).</summary>
    public void Start() => _worker.Start();

    /// <summary>Something went into the ring: the overlay thread does it (without <see cref="Start"/>: now, on the caller's thread).</summary>
    public void Signal() => _worker.Signal();

    /// <summary>Does the waiting overlay work now, on the caller's thread (tests, without <see cref="Start"/>).</summary>
    public void Pump() => _worker.Pump();

    /// <summary>A companion attaching to a running input process: drop what its ring holds and start again.</summary>
    public void StartAgain() => _worker.SkipWaiting();

    /// <summary>Overlay thread failures (a bad frame), and the last one's type.</summary>
    public (long Count, string? Last) Failures => (_worker.Failures, _worker.LastFailure);

    // =====================================================================================
    // settings (window thread)
    // =====================================================================================

    /// <summary>Changes the companion's own settings (tray click and the like) and saves them.</summary>
    public void Change(Action<HostSettings> change)
    {
        lock (_gate)
        {
            change(_ui);
            _dirty = true;
        }
    }

    /// <summary>The settings to save when they changed since the last call (companion.json), else null.</summary>
    public string? TakeDirty()
    {
        lock (_gate)
        {
            if (!_dirty) return null;
            _dirty = false;
            return SettingsStore.Serialize(_ui);
        }
    }

    /// <summary>A save of what <see cref="TakeDirty"/> returned failed: it is saved again later.</summary>
    public void MarkDirty()
    {
        lock (_gate) _dirty = true;
    }

    /// <summary>Changes overlay window <paramref name="id"/>'s settings and saves them; false when there is none.</summary>
    public bool UpdateOverlaySettings(string id, Action<OverlaySettings> change)
    {
        lock (_gate)
        {
            var o = _ui.Overlays.Find(x => x.Id == id);
            if (o == null) return false;
            change(o);
            o.Id = id;
            Changed();
            return true;
        }
    }

    /// <summary>A new overlay window of <paramref name="kind"/> (shown), turning as the first one does; returns its id.</summary>
    public string AddOverlay(OverlayKind kind)
    {
        lock (_gate)
        {
            var o = new OverlaySettings { Kind = kind, Shown = true };
            if (_ui.Overlays.Count > 0)
            {
                var f = _ui.Overlays[0];
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
            // As the phone's new displays: the ring small, the graph wide and low.
            if (kind == OverlayKind.Ring) o.Width = o.Height = 120;
            else if (kind == OverlayKind.Graph) (o.Width, o.Height) = (420, 115);
            else if (kind != OverlayKind.Phone) o.Width = o.Height = 260;
            _ui.Overlays.Add(o);
            Changed();
            return o.Id;
        }
    }

    /// <summary>Saves overlay <paramref name="id"/> as a preset named <paramref name="name"/> (all but its place); returns the preset's id.</summary>
    public string? SaveOverlayPreset(string id, string name)
    {
        lock (_gate)
        {
            var o = _ui.Overlays.Find(x => x.Id == id);
            if (o == null) return null;
            var p = o.Copy();
            p.Label = name.Trim();
            p.Shown = false;
            p.X = p.Y = -1;
            _ui.OverlayPresets.Add(p);
            _dirty = true;
            return p.Id;
        }
    }

    /// <summary>A new overlay window (shown) from preset <paramref name="presetId"/>, placed as a new one is; returns its id.</summary>
    public string? AddOverlayFromPreset(string presetId)
    {
        lock (_gate)
        {
            var p = _ui.OverlayPresets.Find(x => x.Id == presetId);
            if (p == null) return null;
            var o = p.Copy();
            o.Shown = true;
            o.X = o.Y = -1;
            _ui.Overlays.Add(o);
            Changed();
            return o.Id;
        }
    }

    public void RenameOverlayPreset(string presetId, string name)
    {
        lock (_gate)
        {
            if (_ui.OverlayPresets.Find(x => x.Id == presetId) is not { } p) return;
            p.Label = name.Trim();
            _dirty = true;
        }
    }

    public void RemoveOverlayPreset(string presetId)
    {
        lock (_gate)
            if (_ui.OverlayPresets.RemoveAll(x => x.Id == presetId) > 0) _dirty = true;
    }

    /// <summary>Removes overlay window <paramref name="id"/> and its settings.</summary>
    public void RemoveOverlay(string id)
    {
        lock (_gate)
        {
            if (_ui.Overlays.RemoveAll(x => x.Id == id) == 0) return;
            Changed();
        }
    }

    /// <summary>Edit: overlay windows can be moved and resized (off: the mouse goes through them).</summary>
    public void SetOverlayEdit(bool on)
    {
        lock (_gate)
        {
            if (_ui.OverlayEdit == on) return;
            _ui.OverlayEdit = on;
            _dirty = true;
        }
    }

    /// <summary>Hide all: every overlay window is hidden, each keeping its own Show.</summary>
    public void SetOverlaysHidden(bool hidden)
    {
        lock (_gate)
        {
            if (_ui.OverlaysHidden == hidden) return;
            _ui.OverlaysHidden = hidden;
            _dirty = true;
        }
    }

    /// <summary>
    /// Sets shortcut <paramref name="action"/> ("" = none). Another action that had the same keys
    /// loses them, so one press never does two things.
    /// </summary>
    public void SetOverlayShortcut(OverlayAction action, string keys)
    {
        lock (_gate)
        {
            var s = _ui.OverlayShortcuts;
            keys ??= "";
            if (keys.Length > 0)
                foreach (var other in OverlayShortcuts.All)
                    if (other != action && string.Equals(s.Get(other), keys, StringComparison.OrdinalIgnoreCase)) s.Set(other, "");
            s.Set(action, keys);
            _dirty = true;
        }
    }

    /// <summary>Changes the "Fade when phone rests" settings.</summary>
    public void UpdateOverlayRestFade(Action<OverlayRestFade> change)
    {
        lock (_gate)
        {
            change(_ui.OverlayRestFade);
            _ui.OverlayRestFade.Normalize();
            Changed();
        }
    }

    /// <summary>
    /// The phone overlay is shown (a window showing the phone's pose is open): the input process asks the phone for its
    /// pose, layout and presses, and they come through the ring. Off: the phone stops sending them.
    /// </summary>
    public void SetOverlay(bool on)
    {
        lock (_gate)
        {
            if (on == _on) return;
            _on = on;
            if (on) Push();
            Ask();
        }
    }

    /// <summary>Tells the input process again what the overlays ask (it started again).</summary>
    public void TellAgain()
    {
        lock (_gate)
        {
            _askedAsks = -1;
            Ask();
        }
    }

    /// <summary>
    /// Level: the way the phone is held now becomes overlay <paramref name="id"/>'s resting pose, shown face-on (done on
    /// the overlay thread, which has the pose).
    /// </summary>
    public void LevelOverlay(string id) => _worker.Level(id);

    // Under _gate: a setting the overlay thread or the phone uses changed.
    private void Changed()
    {
        _dirty = true;
        Push();
        Ask();
    }

    // Under _gate: hands the overlay thread a copy of the overlay settings.
    private void Push()
    {
        var copies = new List<OverlaySettings>(_ui.Overlays.Count);
        foreach (var o in _ui.Overlays)
        {
            var c = o.Copy();
            c.Id = o.Id;
            copies.Add(c);
        }
        var fade = new OverlayRestFade();
        fade.CopyFrom(_ui.OverlayRestFade);
        _worker.SetSettings(copies, fade);
        _worker.Signal();
    }

    // Under _gate: tells the input process what the overlays ask of the phone, when that changed.
    private void Ask()
    {
        bool compass = false, samples = false;
        foreach (var o in _ui.Overlays)
        {
            if (!o.Shown) continue;
            compass |= o.NeedsCompass;
            samples |= o.WantsSamples;
        }
        int asks = (_on ? 1 : 0) | (_on && compass ? 2 : 0) | (_on && samples ? 4 : 0);
        if (asks == _askedAsks) return;
        _askedAsks = asks;
        _tellInput(_on, _on && compass, _on && samples);
    }

    /// <summary>What Level or Set orientation changed in overlay <paramref name="id"/> (gp-overlay): saved.</summary>
    private void SaveLevel(string id, float[]? levelUp, bool setRest, float[]? rest)
    {
        lock (_gate)
        {
            var o = _ui.Overlays.Find(x => x.Id == id);
            if (o == null) return;
            if (levelUp != null) o.LevelUp = levelUp;
            if (setRest) o.Rest = rest;
            _dirty = true;
            Push();
        }
    }

    // =====================================================================================
    // what the windows read, every frame, from any thread, without a lock
    // =====================================================================================

    /// <summary>A phone is connected (the input process says).</summary>
    public bool Connected => _region.Held.Connected;

    /// <summary>
    /// Copies the newest OVERLAY into <paramref name="into"/> with overlay window <paramref name="id"/>'s pose (straight
    /// until it has one), and returns how long ago it came (us), or -1 when none came;
    /// <paramref name="layout"/> is the newest complete layout.
    /// </summary>
    public long OverlaySnapshot(OverlayFrame into, string? id, out OverlayLayout? layout)
    {
        long rx = _view.Read(into, id, out layout, out bool pcHandles);
        if (rx == long.MinValue) return -1;
        if (pcHandles) PcPadOnHandles(into);
        return Math.Max(0, _clock.NowUs - rx);
    }

    public long OverlaySnapshot(OverlayFrame into, out OverlayLayout? layout) => OverlaySnapshot(into, null, out layout);

    /// <summary>
    /// A window whose handles show the PC controller: its buttons, triggers and sticks in place of the
    /// phone's controller, none while the phone's "Use PC controller for gyro ratchet" is off.
    /// </summary>
    private void PcPadOnHandles(OverlayFrame f)
    {
        Span<float> axes = stackalloc float[6];
        bool on = _region.Held.PcPad(out uint held, axes);
        f.PadFlags = on ? OverlayFrame.PadAttachedFlag : (byte)0;
        f.PadButtons = held;
        f.PadL2Pull = axes[4];
        f.PadR2Pull = axes[5];
        for (int i = 0; i < 4; i++) f.PadSticks[i] = axes[i];
    }

    /// <summary>
    /// A laser pointer overlay's output <paramref name="code"/> (LaserFire, LaserAim) is held: sent by the phone now, or
    /// pressed on the PC's controller with "Use PC controller for gyro ratchet" on.
    /// </summary>
    public bool OutputHeld(int code) => _region.Held.Held(code, t_scratch ??= new StateFrame());

    /// <summary>"Fade when phone rests": overlay <paramref name="id"/> should be faded now (the phone lies still at its Level, untouched).</summary>
    public bool OverlayResting(string id) => _view.Resting(id, _clock.NowUs, _ui.OverlayRestFade);

    /// <summary>The phone's turning speed now (deg/s) from its overlay frames, or null without any lately: shown beside "Still below".</summary>
    public float? OverlayStillnessDps() => _view.LiveDps(_clock.NowUs);

    /// <summary>Overlay window <paramref name="id"/>'s poses sample by sample, or null while it has none.</summary>
    public PoseHistory? OverlayHistory(string id) => _view.HistoryOf(id);

    /// <summary>The Speed graph's yaw, pitch and roll speeds.</summary>
    public SpeedTrace OverlaySpeeds => _worker.Speeds;

    /// <summary>Overlay window <paramref name="id"/>'s pose now (w, x, y, z), or null before it has one.</summary>
    public float[]? OverlayPoseOf(string id) => _view.PoseOf(id);
}
