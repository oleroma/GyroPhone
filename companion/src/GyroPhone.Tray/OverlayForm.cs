using System.ComponentModel;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using GyroPhone.Host;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Tray;

/// <summary>
/// One overlay window (main window "Overlays"): a borderless, always-on-top, see-through window that
/// draws the phone in 3D with its layout and presses, a 3D shape, an attitude indicator or a bubble
/// disk (<see cref="OverlayRenderer"/>), turned by its own pose. A per-pixel alpha layered window,
/// so it shows over games running windowed or borderless and OBS Window Capture records it. It
/// never takes focus. The mouse goes past it, except in Edit (all windows at once): then it can be dragged and
/// resized, and a click (no drag) picks it, or where windows overlap the next one under the cursor
/// (<see cref="TrayApp"/> decides).
/// </summary>
internal sealed class OverlayForm : Form
{
    private readonly HostEngine _engine;
    private readonly string _id;
    private readonly Action<string, Point> _clicked;
    private readonly Action<string> _picked;
    private readonly FingerTrails _trails = new();
    // The laser pointer's trail and paint; the phone's Clear displays presses last seen (-1 = none yet).
    private readonly LaserTrace _laser = new();
    private int _clearPresses = -1;
    // The Aim display's ease: last stepped at this tick.
    private long _aimTicks;
    /// <summary>How long the Aim display takes to ease fully in or out (as the phone's).</summary>
    private const float AimEaseMs = 120f;
    private readonly CrosshairKick.State _kick = new();
    private readonly Action<string> _log;
    private readonly OverlayScene _scene = new();
    private DibSurface? _surface;
    // Drawing runs on the pacer's thread (so the main window never waits for it), Edit or not, settings
    // changes too, or on the UI thread for a copied window in Edit (it moves and resizes there) and a
    // swap-chain window whose size changed (its swap chain is resized there): one at a time.
    private readonly object _renderLock = new();
    // The window, for the pacer's thread (Handle is the UI thread's); zero once destroyed.
    private IntPtr _hwnd;
    private readonly int _uiThread = Environment.CurrentManagedThreadId;
    // A tick posted to the UI thread and not run yet.
    private int _uiTickQueued;
    // The window's size changed: its next picture is drawn on the UI thread, which resizes the swap
    // chain there once (not on every mouse move of an edge drag, and never on the pacer's thread).
    private volatile bool _resizePending;
    // UpdateLayeredWindow refused a call from the pacer's thread: this window draws on the UI thread.
    private bool _uiOnly;
    // The graphics card draws every window. A window whose graphics card surface fails makes a new one
    // (a lost device after sleep, a driver update); after 3 failures of its own in a minute it draws
    // with the CPU for 5 minutes, then tries the graphics card again. Counted per window. The windows
    // share one device (GpuDevice): a lost one counts in the window that found it, and the others
    // just make new surfaces on the new device. Guarded by _renderLock.
    private readonly Queue<long> _gpuFailures = new();
    private long _gpuOffUntil;
    // This window's next try at a new graphics card surface, and whether its handle is being made
    // again for one (a swap-chain window).
    private long _gpuRetryAt;
    private bool _recreating;
    // This window showed a swap chain before its surface failed: its next try makes its handle
    // again for a new swap chain, not a copied surface.
    private bool _wasDirect;
    // The swap chain (picture shown straight from the graphics card) failed: every window copies its
    // picture through the CPU (UpdateLayeredWindow) until this time (2 s after a failure, 5 minutes
    // after 3 in a row; 0 = none). Then one copied window tries a swap chain again (it takes the next
    // 2 s so the others wait), and once that works the others follow.
    private static long _directRetryAt;
    private static int _directFailures;
    // This window makes its handle again to try the swap chain (it took the try).
    private bool _directTry;
    // The window shows a swap chain (constant alpha + extended frame), not UpdateLayeredWindow pictures.
    private bool _direct;
    private byte _directAlpha;
    // A swap-chain window starts fully see-through and shows once its first pictures are on screen:
    // shown at once, it flashed its empty (white) surface for a moment.
    private bool _revealed;
    private GpuSurface? _gpuSurface;
    private readonly Dictionary<(float, bool), Font> _fonts = [];
    // Draw times and pictures in the last second, for the Debug window.
    private readonly System.Diagnostics.Stopwatch _clock = System.Diagnostics.Stopwatch.StartNew();
    private double _statStart, _statSumMs, _statMaxMs, _lastDrawAt;
    private int _statFrames;
    private string _stats = "";
    private bool _selected;
    private string _title = "";
    // A left press in Edit: where the cursor and the window were, and whether it has become a drag.
    private Point? _pressAt;
    private Point _pressWindow;
    private bool _dragging;
    private uint _lastTime, _lastTrailTime;
    private ushort _lastLayout;
    private long _lastDrawTicks;
    private bool _dirty = true;
    // What the last picture showed, live from the phone (_drawnLive): a new frame that shows nothing
    // new (the pose moved under OverlayPacing.StillPx, the rest the same) draws nothing.
    private readonly OverlayFrame _drawnFrame = new();
    private bool _drawnLive, _drawnFiring;
    private int _drawnSize;
    private int _drawnLaser;
    // The refresh rate of the screen this window is on (0 = not known yet), read every 2 s.
    private volatile int _screenHz;
    private long _screenHzAt;
    // "Fade when phone rests": this window's share of its opacity now (1 = not faded), and when it was last stepped.
    private float _restFade = 1f;
    private long _restFadeAt;
    private int _alphaQueued;

    public OverlayForm(HostEngine engine, string id, Action<string> log, Action<string, Point> clicked, Action<string> picked)
    {
        _engine = engine;
        _id = id;
        _log = log;
        _clicked = clicked;
        _picked = picked;
        _drawPicture = DrawPicture;
        _presentPicture = PresentPicture;
        var s = Settings ?? new OverlaySettings();
        Text = $"{Program.AppName} {OverlaySettings.KindName(s.Kind).ToLowerInvariant()} overlay";
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        StartPosition = FormStartPosition.Manual;
        MinimumSize = new Size(120, 90);
        var area = Screen.PrimaryScreen?.WorkingArea ?? new Rectangle(0, 0, 1280, 720);
        var size = new Size(Math.Max(120, s.Width), Math.Max(90, s.Height));
        var at = s.X >= 0 && s.Y >= 0 ? new Point(s.X, s.Y) : new Point(area.Right - size.Width - 24, area.Bottom - size.Height - 24);
        // Back on a screen if the one it was on is gone.
        if (!Screen.AllScreens.Any(sc => sc.WorkingArea.IntersectsWith(new Rectangle(at, size))))
            at = new Point(area.Right - size.Width - 24, area.Bottom - size.Height - 24);
        Bounds = new Rectangle(at, size);
    }

    protected override bool ShowWithoutActivation => true;

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            // Not a tool window: OBS's Window Capture lists only windows without WS_EX_TOOLWINDOW.
            // It stays off the taskbar anyway: ShowInTaskbar = false gives it a hidden owner window.
            cp.ExStyle |= WsExLayered | WsExNoActivate | WsExTopmost;
            cp.ExStyle &= ~WsExAppWindow;
            if (ClickThrough) cp.ExStyle |= WsExTransparent;
            return cp;
        }
    }

    public string Id => _id;

    /// <summary>The window's handle for other threads (the wheel hook's); zero while it has none.</summary>
    public IntPtr Hwnd => Volatile.Read(ref _hwnd);

    /// <summary>Its frame-rate cap (0: every screen refresh), in whole refreshes (<see cref="OverlayPacing.RefreshesPerPicture"/>).</summary>
    public int MaxFps => Settings?.MaxFps ?? 60;

    /// <summary>The refresh rate of the screen it is on (Hz), or 0 before it is known.</summary>
    public int ScreenHz => _screenHz;

    /// <summary>This window's settings, or null once removed.</summary>
    // The base Form constructor reads CreateParams before this constructor has set _engine.
    // A plain loop: looked up several times a frame, a lambda would be a new object each time.
    private OverlaySettings? Settings
    {
        get
        {
            if (_engine == null) return null;
            foreach (var o in _engine.Settings.Overlays)
                if (o.Id == _id) return o;
            return null;
        }
    }

    private bool ClickThrough => !(_engine?.Settings.OverlayEdit ?? false);

    /// <summary>The picked window in Edit (also picked in the main window's list): drawn with a solid frame.</summary>
    public bool Selected
    {
        get => _selected;
        set
        {
            if (_selected == value) return;
            _selected = value;
            if (!ClickThrough) SettingsChanged();
        }
    }

    /// <summary>Its name, shown in Edit.</summary>
    public string Title
    {
        get => _title;
        set
        {
            if (_title == value) return;
            _title = value;
            if (!ClickThrough) SettingsChanged();
        }
    }

    /// <summary>On top of the other overlay windows, without taking focus.</summary>
    public void BringUp()
    {
        if (IsHandleCreated) SetWindowPos(Handle, HwndTopmost, 0, 0, 0, 0, SwpNoMove | SwpNoSize | SwpNoActivate);
    }

    /// <summary>
    /// Settings changed (background, scale, dots, Edit): the window style and alpha change now, the picture
    /// at the next frame (the pacer draws a dirty window at once, whatever its frame cap), so a slider drag
    /// never draws on the UI thread.
    /// </summary>
    public void SettingsChanged()
    {
        if (!IsHandleCreated) return;
        if (_direct && _revealed && WindowAlpha() is var a && a != _directAlpha)
        {
            _directAlpha = a;
            SetLayeredWindowAttributes(Handle, 0, a, LwaAlpha);
        }
        long ex = GetWindowLongPtr(Handle, GwlExStyle).ToInt64();
        long want = ClickThrough ? ex | WsExTransparent : ex & ~WsExTransparent;
        if (want != ex) SetWindowLongPtr(Handle, GwlExStyle, new IntPtr(want));
        _dirty = true;
    }

    /// <summary>Level: the way the phone is held now shows face-on.</summary>
    public void Level()
    {
        _engine.LevelOverlay(_id);
        _dirty = true;
    }

    /// <summary>Clears the laser pointer's paint and trail.</summary>
    public void ClearPaint()
    {
        lock (_renderLock) _laser.Clear();
        _dirty = true;
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        lock (_renderLock)
        {
            _hwnd = Handle;
            _direct = false;
            _recreating = false;
            bool gpuOff = GpuOff;
            bool tryDirect = !gpuOff && (_directTry || !DirectBroken);
            _directTry = false;
            if (tryDirect)
            {
                try
                {
                    // The layered window shows the swap chain's alpha: constant window alpha, frame over it all.
                    _directAlpha = 0;
                    _revealed = false;
                    if (!SetLayeredWindowAttributes(Handle, 0, 0, LwaAlpha)) throw new Win32Exception();
                    var margins = new Margins { Left = -1, Right = -1, Top = -1, Bottom = -1 };
                    int hr = DwmExtendFrameIntoClientArea(Handle, ref margins);
                    if (hr < 0) throw new Win32Exception(hr);
                    _gpuSurface = new GpuSurface(Handle, Width, Height);
                    _direct = true;
                    _wasDirect = true;
                    _uiOnly = false; // that was UpdateLayeredWindow's, which a swap chain doesn't use
                    if (Interlocked.Exchange(ref _directRetryAt, 0) != 0)
                        _log("overlay: showing straight from the graphics card works again");
                    Volatile.Write(ref _directFailures, 0);
                }
                catch (Exception ex)
                {
                    int failures = Interlocked.Increment(ref _directFailures);
                    long wait = failures >= 3 ? 5 * 60_000 : 2000;
                    Interlocked.Exchange(ref _directRetryAt, Environment.TickCount64 + wait);
                    _log($"overlay: showing straight from the graphics card failed, copying through the CPU, trying again in {wait / 1000} s: {ex.GetType().Name}: {ex.Message}");
                    // Out of constant-alpha mode, so UpdateLayeredWindow works again.
                    long ex0 = GetWindowLongPtr(Handle, GwlExStyle).ToInt64();
                    SetWindowLongPtr(Handle, GwlExStyle, new IntPtr(ex0 & ~WsExLayered));
                    SetWindowLongPtr(Handle, GwlExStyle, new IntPtr(ex0));
                }
            }
            if (!_direct && !gpuOff)
            {
                try
                {
                    _gpuSurface = new GpuSurface();
                }
                catch (Exception ex)
                {
                    GpuFailed(ex);
                }
            }
        }
        Render();
        // Made again while showing (OnShown won't come again): show it once it has drawn.
        if (_direct && Visible) BeginInvoke(ScheduleReveal);
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        ScheduleReveal();
    }

    private void ScheduleReveal()
    {
        if (!_direct || _revealed || !IsHandleCreated) return;
        // Draw now that the window is on screen, and show it a few frames later, when the
        // compositor has the picture.
        Render();
        var reveal = new System.Windows.Forms.Timer { Interval = 50 };
        reveal.Tick += (_, _) =>
        {
            reveal.Dispose();
            if (!IsHandleCreated || IsDisposed || !_direct) return;
            Render();
            _revealed = true;
            _directAlpha = WindowAlpha();
            SetLayeredWindowAttributes(Handle, 0, _directAlpha, LwaAlpha);
        };
        reveal.Start();
    }

    protected override void OnHandleDestroyed(EventArgs e)
    {
        lock (_renderLock)
        {
            _hwnd = IntPtr.Zero;
            _surface?.Dispose();
            _surface = null;
            _gpuSurface?.Dispose();
            _gpuSurface = null;
        }
        base.OnHandleDestroyed(e);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        SaveBounds();
        base.OnFormClosing(e);
    }

    protected override void OnResizeEnd(EventArgs e)
    {
        base.OnResizeEnd(e);
        SaveBounds();
    }

    protected override void OnSizeChanged(EventArgs e)
    {
        base.OnSizeChanged(e);
        // Drawn at the next tick, on the UI thread: an edge drag moves many times a frame.
        _dirty = true;
        _resizePending = true;
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e);
        if (e.Button != MouseButtons.Left || ClickThrough) return;
        _pressAt = Cursor.Position;
        _pressWindow = Location;
        _dragging = false;
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        if (_pressAt is not { } at || ClickThrough) return;
        if ((e.Button & MouseButtons.Left) == 0)
        {
            // The release was missed (capture taken by another window): keep where it got to.
            _pressAt = null;
            if (_dragging) SaveBounds();
            _dragging = false;
            return;
        }
        var now = Cursor.Position;
        int dx = now.X - at.X, dy = now.Y - at.Y;
        var drag = SystemInformation.DragSize;
        if (!_dragging && Math.Abs(dx) < drag.Width && Math.Abs(dy) < drag.Height) return;
        if (!_dragging)
        {
            // A drag moves the window in front (the one that got the press) and picks it.
            _dragging = true;
            _picked(_id);
        }
        Location = new Point(_pressWindow.X + dx, _pressWindow.Y + dy);
    }

    protected override void OnMouseUp(MouseEventArgs e)
    {
        base.OnMouseUp(e);
        if (e.Button != MouseButtons.Left || _pressAt == null) return;
        _pressAt = null;
        if (_dragging)
        {
            _dragging = false;
            SaveBounds();
        }
        else if (!ClickThrough)
        {
            _clicked(_id, Cursor.Position);
        }
    }

    /// <summary>
    /// In Edit, the wheel over a window (<see cref="OverlayWheel"/>) picks it and resizes it, keeping
    /// its shape and centre: 10% a notch (<paramref name="delta"/> 120), 2% with Shift held.
    /// </summary>
    public void ScaleBy(int delta, bool fine)
    {
        if (IsDisposed || ClickThrough || _dragging || delta == 0) return;
        _picked(_id);
        var b = Bounds;
        double scale = Math.Pow(fine ? 1.02 : 1.10, delta / 120.0);
        // Not below the smallest size, with both sides shrinking together.
        scale = Math.Max(scale, Math.Max((double)MinimumSize.Width / b.Width, (double)MinimumSize.Height / b.Height));
        int w = (int)Math.Round(b.Width * scale), h = (int)Math.Round(b.Height * scale);
        // A small window would round a 2% step to nothing: grow or shrink by at least a pixel.
        if (w == b.Width && h == b.Height)
        {
            int d = delta > 0 ? 1 : -1;
            if (d < 0 && (b.Width <= MinimumSize.Width || b.Height <= MinimumSize.Height)) return;
            w = b.Width + d;
            h = (int)Math.Round((double)w * b.Height / b.Width);
        }
        if (w == b.Width && h == b.Height) return;
        int cx = b.X + b.Width / 2, cy = b.Y + b.Height / 2;
        Bounds = new Rectangle(cx - w / 2, cy - h / 2, w, h);
        SaveBounds();
    }

    private void SaveBounds()
    {
        var b = Bounds;
        _engine.UpdateOverlaySettings(_id, o =>
        {
            o.X = b.X; o.Y = b.Y; o.Width = b.Width; o.Height = b.Height;
        });
    }

    protected override void WndProc(ref Message m)
    {
        switch (m.Msg)
        {
            case WmMouseActivate:
                m.Result = new IntPtr(MaNoActivate);
                return;
            case WmNcHitTest when !ClickThrough:
                var p = PointToClient(new Point(unchecked((short)(long)m.LParam), unchecked((short)((long)m.LParam >> 16))));
                const int grip = 14;
                bool right = p.X >= Width - grip, bottom = p.Y >= Height - grip, left = p.X < grip, top = p.Y < grip;
                // Edges resize; inside is ours (OnMouseDown/Move/Up): a drag moves, a click picks.
                m.Result = new IntPtr(
                    right && bottom ? HtBottomRight : left && top ? HtTopLeft : right && top ? HtTopRight : left && bottom ? HtBottomLeft :
                    right ? HtRight : left ? HtLeft : bottom ? HtBottom : top ? HtTop : HtClient);
                return;
        }
        base.WndProc(ref m);
    }

    /// <summary>Draw times over the last second, e.g. "Phone 640×480: 2.1 ms avg, 4.0 max, 60 fps".</summary>
    public string Stats => _stats;

    /// <summary>
    /// One screen refresh, on the pacer's thread (<see cref="OverlayPacer"/>): draws again if anything
    /// changed, right there, Edit or not (moving a swap-chain window needs no new picture). On the UI
    /// thread instead (one tick queued at most) right after a size change, for a copied window in Edit
    /// (its UpdateLayeredWindow would race the move and resize there) and for one whose
    /// UpdateLayeredWindow refused the pacer's thread.
    /// </summary>
    public void Tick()
    {
        if (IsDisposed) return;
        if (!_uiOnly && !_resizePending && (ClickThrough || _direct))
        {
            TickLocked();
            return;
        }
        if (Interlocked.Exchange(ref _uiTickQueued, 1) != 0) return;
        try
        {
            BeginInvoke(() =>
            {
                Volatile.Write(ref _uiTickQueued, 0);
                // A new size is drawn now, whatever else changed.
                if (_resizePending) _dirty = true;
                TickLocked();
            });
        }
        catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException)
        {
            Volatile.Write(ref _uiTickQueued, 0); // closing
        }
    }

    private void TickLocked()
    {
        try
        {
            lock (_renderLock) TickCore();
        }
        catch (Exception e)
        {
            _log($"overlay: {e.GetType().Name}: {e.Message}");
        }
    }

    /// <summary>The paint map's width for Paint detail low, normal, high (as the phone's).</summary>
    private static readonly int[] PaintWidths = [512, 1024, 2048];
    private readonly uint[] _moveColors = new uint[6];

    private void TickCore()
    {
        // A new OVERLAY that shows something new, a new layout, or now and then for the status: draw again.
        long age = _engine.OverlaySnapshot(_scene.Frame, _id, out var layout);
        bool fresh = age >= 0 && _scene.Frame.PhoneTimeUs != _lastTime;
        long now = Environment.TickCount64;
        if (now - _screenHzAt >= 2000)
        {
            _screenHzAt = now;
            if (ScreenRate.Of(_hwnd) is > 0 and var hz) _screenHz = hz;
        }
        // Each frame goes into the trails once: a frame-capped tick leaves it fresh for the next one.
        if (fresh && _scene.Frame.PhoneTimeUs != _lastTrailTime)
        {
            _lastTrailTime = _scene.Frame.PhoneTimeUs;
            _trails.Add(_scene.Frame, now);
            // The laser pointer: where the beam points now, as drawn.
            if (Settings is { Kind: OverlayKind.Laser } ls)
            {
                var beam = LaserTrace.Beam(LaserDrawing.Aimed(OverlayRenderer.PoseMatrix(_scene.Frame, ls), ls));
                bool fire = (ls.LaserDraw || ls.LaserSnake) && _engine.OutputHeld(ls.LaserFire);
                bool aim = (ls.LaserDraw || ls.LaserSnake) && _engine.OutputHeld(ls.LaserAim);
                for (int i = 0; i < 6; i++) _moveColors[i] = unchecked((uint)ls.Color(OverlaySettings.LaserMoves[i]));
                _laser.Add(beam.X, beam.Y, beam.Z, now, ls.LaserDraw, _scene.Frame.RatchetHeld, ls.LaserClearOnRatchet, fire, ls.LaserFireOnly,
                    ls.LaserSmoothColours, ls.LaserLineSmoothing,
                    brushDeg: Math.Clamp(ls.LaserBrush, 1, 20) * 0.6f,
                    snakeDeg: ls.LaserSnake ? ls.LaserSnakeDeg : 0f,
                    wayColors: ls.LaserColourByWay ? _moveColors : null,
                    mapWidth: PaintWidths[Math.Clamp(ls.LaserPaintDetail, 0, 2)],
                    soft: ls.LaserSoftness,
                    aim: aim,
                    fireBrushDeg: Math.Clamp(ls.LaserFireBrush, 1, 20) * 0.6f,
                    aimBrushDeg: Math.Clamp(ls.LaserAimBrush, 1, 20) * 0.6f);
            }
        }
        // A Clear displays mapping on the phone: the paint goes (the first count seen clears nothing).
        if (age >= 0)
        {
            int presses = _scene.Frame.ClearPresses;
            if (_clearPresses >= 0 && presses != _clearPresses)
            {
                _laser.Clear();
                _dirty = true;
            }
            _clearPresses = presses;
        }
        bool laserFading = false;
        if (Settings is { Kind: OverlayKind.Laser } lf)
        {
            _scene.Firing = _engine.OutputHeld(lf.LaserFire);
            // Aiming eases in and out over AimEaseMs.
            float goal = _engine.OutputHeld(lf.LaserAim) ? 1f : 0f;
            float dt = _aimTicks == 0 ? 0f : Math.Clamp(now - _aimTicks, 0L, 100L);
            _aimTicks = now;
            if (_scene.Aim != goal)
            {
                float step = dt <= 0f ? 1f : dt / AimEaseMs;
                _scene.Aim = goal > _scene.Aim ? Math.Min(goal, _scene.Aim + step) : Math.Max(goal, _scene.Aim - step);
                laserFading = true;
            }
            // A crosshair or gun kicks on each shot with the gun's recoil and bounces back, its muzzle flash in time.
            bool kicks = lf.LaserCrosshair || lf.LaserRifle;
            int kickMode = kicks ? lf.CrosshairKick : CrosshairKick.Off;
            var gun = lf.Gun();
            if (_kick.Step(now, _scene.Firing, kickMode, CrosshairKick.PeriodMs(lf.RpmOf(gun)), kicks && lf.MuzzleFlash, gun.Recoil)) laserFading = true;
            _scene.Kick = _kick.Value;
            _scene.ShotAt = _kick.ShotAt;
            // A gun lowered when idle comes up while aiming or firing.
            float up = !lf.LaserRifle || !lf.GunLowered || _scene.Firing || _kick.ShotAt >= 0 || goal > 0f ? 1f : 0f;
            float raised = 1f - _scene.Lower;
            if (raised != up)
            {
                float step = dt <= 0f ? 1f : dt / AimEaseMs;
                raised = up > raised ? Math.Min(up, raised + step) : Math.Max(up, raised - step);
                _scene.Lower = 1f - raised;
                laserFading = true;
            }
        }
        bool rested = StepRestFade(now);
        UpdateStats();
        // A frame showing nothing new (a still phone's gyro noise) is passed over as drawn.
        bool shows = fresh && ShowsNew(age);
        if (fresh && !shows) _lastTime = _scene.Frame.PhoneTimeUs;
        bool changed = _dirty || shows || age >= 0 && _scene.Frame.LayoutVersion != _lastLayout ||
            !ReferenceEquals(layout, _scene.Layout) || _trails.Fading(now) || laserFading ||
            _scene.Firing != _drawnFiring || LaserStamp() != _drawnLaser;
        if (!changed && now - _lastDrawTicks < 500) return;
        // Faded out by "Fade when phone rests": a few pictures a second at most. Any input or movement
        // ends the rest, and the next tick draws at once.
        if (rested && !_dirty && !laserFading && now - _lastDrawTicks < OverlayPacing.RestedFrameMs) return;
        // Frame-rate cap, in whole refreshes of this window's screen (a little early, so it never skips one).
        int fps = MaxFps;
        if (fps > 0 && !_dirty && _clock.Elapsed.TotalMilliseconds - _lastDrawAt < OverlayPacing.MinGapMs(_screenHz, fps)) return;
        _scene.FrameAgeUs = age;
        _scene.Layout = layout;
        Render();
    }

    /// <summary>
    /// Whether the new frame in <see cref="_scene"/> shows anything the last picture didn't: what it
    /// draws besides the pose (<see cref="OverlayFrame.SameShown"/>), or a pose turned by
    /// <see cref="OverlayPacing.StillPx"/> or more at the window's size.
    /// </summary>
    private bool ShowsNew(long age)
    {
        bool live = _engine.Connected && age is >= 0 and < 1_500_000;
        if (!live || !_drawnLive || !_scene.Frame.SameShown(_drawnFrame)) return true;
        var s = Settings;
        float scale = s == null || s.FollowsCamera ? 1f : Math.Clamp(s.MotionScale, 0.25f, 4f);
        return OverlayPacing.TurnShows(OverlayFrame.TurnBetween(_drawnFrame, _scene.Frame), _drawnSize, scale);
    }

    /// <summary>The laser pointer's trail and paint as they are now: it changes when they do.</summary>
    private int LaserStamp()
    {
        var st = _laser.Strokes;
        long newest = st.Count > 0 ? st.Point(st.Count - 1).T : 0;
        return HashCode.Combine(st.Count, newest, _laser.Version, _laser.Moving);
    }

    /// <summary>
    /// "Fade when phone rests": fades toward the faded opacity over the fade time while the phone rests,
    /// and back to full at once when it doesn't (never in Edit). A swap-chain window just gets its
    /// new constant alpha; a copied one draws again with it. True while it rests and has faded all the way.
    /// </summary>
    private bool StepRestFade(long now)
    {
        var rf = _engine.Settings.OverlayRestFade;
        bool resting = ClickThrough && _engine.OverlayResting(_id);
        float target = resting ? Math.Clamp(rf.FadeTo, 0.05f, 1f) : 1f;
        float was = _restFade;
        long dtMs = _restFadeAt == 0 ? 0 : Math.Clamp(now - _restFadeAt, 0, 100);
        _restFadeAt = now;
        if (target >= _restFade) _restFade = target;
        else _restFade = rf.FadeMs <= 0 ? target : Math.Max(target, _restFade - (1f - target) * dtMs / rf.FadeMs);
        bool fadedOut = resting && target < 1f && _restFade <= target + 1e-4f;
        if (Math.Abs(_restFade - was) < 1e-4f) return fadedOut;
        if (!_direct)
        {
            _dirty = true;
            return fadedOut;
        }
        if (!_revealed || WindowAlpha() == _directAlpha) return fadedOut;
        // Set on the UI thread (the window's), one at a time; it reads the newest fade when it runs.
        if (Interlocked.Exchange(ref _alphaQueued, 1) != 0) return fadedOut;
        try
        {
            BeginInvoke(() =>
            {
                Volatile.Write(ref _alphaQueued, 0);
                if (!IsHandleCreated || !_direct || !_revealed) return;
                byte a = WindowAlpha();
                if (a == _directAlpha) return;
                _directAlpha = a;
                SetLayeredWindowAttributes(Handle, 0, a, LwaAlpha);
            });
        }
        catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException)
        {
            Volatile.Write(ref _alphaQueued, 0);
        }
        return fadedOut;
    }

    private void Render()
    {
        try
        {
            lock (_renderLock) RenderCore();
        }
        catch (Exception e)
        {
            _log($"overlay: {e.GetType().Name}: {e.Message}");
        }
    }

    private void RenderCore()
    {
        // The window's size as Windows has it now (no message to the UI thread): drawing at exactly
        // this size, UpdateLayeredWindow never resizes the window from the pacer's thread.
        IntPtr hwnd = _hwnd;
        bool onUi = Environment.CurrentManagedThreadId == _uiThread;
        // Read before the size: a size change after this draws again.
        if (onUi) _resizePending = false;
        if (hwnd == IntPtr.Zero || !GetWindowRect(hwnd, out var wr)) return;
        int width = wr.Right - wr.Left, height = wr.Bottom - wr.Top;
        if (width <= 0 || height <= 0) return;
        // A swap chain is resized on the UI thread (the window's), at most once a frame: the next tick goes there.
        if (!onUi && _gpuSurface is { } sized && sized.NeedsResize(width, height))
        {
            _resizePending = true;
            _dirty = true;
            return;
        }
        _dirty = false;
        _lastTime = _scene.Frame.PhoneTimeUs;
        _lastLayout = _scene.Frame.LayoutVersion;
        _lastDrawTicks = Environment.TickCount64;
        _scene.Connected = _engine.Connected;
        // What this picture shows, for ShowsNew.
        _drawnLive = _scene.Connected && _scene.FrameAgeUs is >= 0 and < 1_500_000;
        if (_drawnLive) _scene.Frame.CopyTo(_drawnFrame);
        _drawnSize = Math.Max(width, height);
        _drawnFiring = _scene.Firing;
        _drawnLaser = LaserStamp();
        _scene.Arranging = !ClickThrough;
        _scene.Selected = _selected;
        _scene.Title = _title;
        double start = _clock.Elapsed.TotalMilliseconds;
        // Another window found the shared device lost: move to the new one now (not a failure of this window's).
        if (_gpuSurface is { Lost: true })
        {
            DropGpuSurface();
            _gpuRetryAt = 0;
            if (_recreating)
            {
                _dirty = true;
                return;
            }
        }
        // A window drawing with the CPU (its surface failed, or the graphics card was off) tries the
        // graphics card again now and then. A window that showed a swap chain makes its handle again
        // for a new one (OnHandleCreated); the others make a new copied surface here.
        if (_gpuSurface == null && !_direct && !_recreating && _lastDrawTicks >= _gpuRetryAt && !GpuOff)
        {
            if (_wasDirect && !DirectBroken)
            {
                _recreating = true;
                try { BeginInvoke(RecreateHandle); } catch (Exception) { _recreating = false; }
            }
            else
            {
                try
                {
                    _gpuSurface = new GpuSurface();
                }
                catch (Exception e)
                {
                    GpuFailed(e);
                }
            }
        }
        if (_gpuSurface != null)
        {
            try
            {
                if (Settings is { } gs)
                {
                    _scene.Trails = gs.FingerTrails ? _trails : null;
                    _scene.Laser = _laser;
                    _scene.NowTicks = _lastDrawTicks;
                    // What the two callbacks (made once, not per picture) draw and present.
                    (_drawSize, _drawSettings, _drawHwnd) = (new Size(width, height), gs, hwnd);
                    _gpuSurface.Draw(width, height, gs.Quality, _drawPicture, _presentPicture);
                }
            }
            catch (Exception e)
            {
                GpuFailed(e);
                _dirty = true;
                return;
            }
            Count(start);
            if (!_gpuSurface.Direct) TryDirectAgain();
            return;
        }
        // One picture in the format the layered window takes (premultiplied, top-down DIB), kept
        // between frames: no new bitmap, copy or conversion per frame.
        if (_surface == null || _surface.Width != width || _surface.Height != height)
        {
            _surface?.Dispose();
            _surface = new DibSurface(width, height);
        }
        var g = _surface.Graphics;
        try
        {
            if (Settings is { } settings)
            {
                _scene.Trails = settings.FingerTrails ? _trails : null;
                _scene.Laser = _laser;
                _scene.NowTicks = _lastDrawTicks;
                OverlayRenderer.Draw(new GdiCanvas(g, settings.Quality, _fonts), new Size(width, height), _scene, settings);
            }
        }
        catch (Exception e)
        {
            // A drawing failure must never take the companion down: log it and keep the last picture.
            _log($"overlay: drawing failed: {e.Message}");
            // The Graphics is kept for the next frame: drop the clips and transforms left pushed.
            g.ResetClip();
            g.ResetTransform();
        }
        g.Flush(System.Drawing.Drawing2D.FlushIntention.Sync);
        Present(hwnd, _surface.Dc, width, height);
        Count(start);
    }

    /// <summary>The swap chain failed lately: windows copy their pictures for now (see <see cref="_directRetryAt"/>).</summary>
    private static bool DirectBroken => Environment.TickCount64 < Volatile.Read(ref _directRetryAt);

    /// <summary>
    /// A copied window that has just drawn (so the graphics card works) tries the swap chain again once
    /// its time has come: it makes its handle again (UI thread). Only one window takes each try; the
    /// others follow once it worked. Called with _renderLock held.
    /// </summary>
    private void TryDirectAgain()
    {
        if (_recreating || GpuOff) return;
        long at = Volatile.Read(ref _directRetryAt), now = Environment.TickCount64;
        if (now < at) return;
        // A try after a failure: this window takes it, and the others wait for it (2 s).
        if (at != 0)
        {
            if (Interlocked.CompareExchange(ref _directRetryAt, now + 2000, at) != at) return;
            _directTry = true;
        }
        _recreating = true;
        try
        {
            BeginInvoke(RecreateHandle);
        }
        catch (Exception)
        {
            _recreating = false;
            _directTry = false;
        }
    }

    // GpuSurface.Draw's callbacks and what they draw: set just before each call, under _renderLock.
    private readonly Action<ICanvas> _drawPicture;
    private readonly Action<IntPtr> _presentPicture;
    private Size _drawSize;
    private OverlaySettings? _drawSettings;
    private IntPtr _drawHwnd;

    private void DrawPicture(ICanvas c)
    {
        try
        {
            OverlayRenderer.Draw(c, _drawSize, _scene, _drawSettings!);
        }
        catch (Exception e) when (e is not SharpGen.Runtime.SharpGenException)
        {
            // A bug in the drawing, not the graphics card: keep the surface (its End
            // undoes any clips and transforms left pushed).
            _log($"overlay: drawing failed: {e.GetType().Name}: {e.Message}");
        }
    }

    private void PresentPicture(IntPtr dc) => Present(_drawHwnd, dc, _drawSize.Width, _drawSize.Height);

    /// <summary>This window draws with the CPU for now: its graphics card surface failed 3 times in a minute.</summary>
    private bool GpuOff => Environment.TickCount64 < _gpuOffUntil;

    /// <summary>
    /// The graphics card couldn't draw: log it and drop this window's surface. It makes a new one
    /// in 2 seconds; after 3 failures in a minute this window draws with the CPU for 5 minutes.
    /// Called with _renderLock held.
    /// </summary>
    private void GpuFailed(Exception e)
    {
        long now = Environment.TickCount64;
        // Its device already found lost by another window: just move to the new one.
        if (_gpuSurface is { Lost: true })
        {
            _log($"overlay: graphics card device lost, making a new surface: {e.GetType().Name}: {e.Message}");
            _gpuRetryAt = 0;
            DropGpuSurface();
            return;
        }
        _gpuSurface?.CheckLost(e);
        _gpuFailures.Enqueue(now);
        while (_gpuFailures.Count > 0 && now - _gpuFailures.Peek() > 60_000) _gpuFailures.Dequeue();
        if (_gpuFailures.Count >= 3)
        {
            _gpuFailures.Clear();
            _gpuOffUntil = now + 5 * 60_000;
        }
        bool off = now < _gpuOffUntil;
        _log(off
            ? $"overlay: graphics card drawing failed again, drawing with the CPU for 5 minutes: {e.GetType().Name}: {e.Message}"
            : $"overlay: graphics card drawing failed, trying a new surface: {e.GetType().Name}: {e.Message}");
        _gpuRetryAt = now + 2000;
        DropGpuSurface();
    }

    /// <summary>Disposes this window's surface (its device goes with the last window's); a swap-chain window makes its handle again. Called with _renderLock held.</summary>
    private void DropGpuSurface()
    {
        try { _gpuSurface?.Dispose(); } catch (Exception) { }
        _gpuSurface = null;
        if (_direct)
        {
            // Made again (UI thread): with a new swap chain, or with UpdateLayeredWindow pictures
            // while the graphics card is off.
            _direct = false;
            _recreating = true;
            try { BeginInvoke(RecreateHandle); } catch (Exception) { }
        }
    }

    private void Count(double start)
    {
        double end = _clock.Elapsed.TotalMilliseconds;
        _lastDrawAt = start;
        double took = end - start;
        _statSumMs += took;
        _statMaxMs = Math.Max(_statMaxMs, took);
        _statFrames++;
    }

    private void UpdateStats()
    {
        double now = _clock.Elapsed.TotalMilliseconds;
        if (now - _statStart < 1000) return;
        double secs = (now - _statStart) / 1000.0;
        string by = _gpuSurface == null ? "CPU" : _gpuSurface.Direct ? "GPU" : "GPU, copied";
        _stats = _statFrames == 0
            ? $"{_title} {Width}×{Height} ({by}): idle"
            : $"{_title} {Width}×{Height} ({by}): {_statSumMs / _statFrames:0.0} ms avg, {_statMaxMs:0.0} max, {_statFrames / secs:0} fps";
        _statStart = now;
        _statSumMs = _statMaxMs = 0;
        _statFrames = 0;
    }

    /// <summary>The whole window's opacity (setting): Windows applies it while compositing, at no cost. Kept visible while arranging.</summary>
    private byte WindowAlpha()
    {
        float op = Math.Clamp(Settings?.Opacity ?? 1f, 0.1f, 1f);
        if (!ClickThrough) op = Math.Max(op, 0.6f);
        else op = Math.Max(op * _restFade, 0.02f);
        return (byte)Math.Round(op * 255);
    }

    // A swap chain fills the window: WinForms must not paint its background over it.
    protected override void OnPaintBackground(PaintEventArgs e)
    {
        if (!_direct) base.OnPaintBackground(e);
    }

    /// <summary>Hands the picture, with its alpha, to the layered window.</summary>
    private void Present(IntPtr hwnd, IntPtr dc, int width, int height)
    {
        var size = new NativeSize { Cx = width, Cy = height };
        var src = new NativePoint();
        var blend = new BlendFunction { BlendOp = AcSrcOver, SourceConstantAlpha = WindowAlpha(), AlphaFormat = AcSrcAlpha };
        if (UpdateLayeredWindow(hwnd, IntPtr.Zero, IntPtr.Zero, ref size, dc, ref src, 0, ref blend, UlwAlpha)) return;
        string why = new Win32Exception().Message;
        if (Environment.CurrentManagedThreadId != _uiThread && !_uiOnly)
        {
            // Not allowed from this thread here: draw this window on the UI thread from now on.
            _uiOnly = true;
            _dirty = true;
            _log($"overlay: UpdateLayeredWindow failed off the UI thread ({why}), drawing on the UI thread");
            return;
        }
        _log($"overlay: UpdateLayeredWindow failed ({why})");
    }

    /// <summary>
    /// A top-down 32-bit DIB selected into its own memory DC, with a GDI+ Graphics drawing straight
    /// into its pixels as premultiplied ARGB: what UpdateLayeredWindow reads, with nothing in between.
    /// </summary>
    private sealed class DibSurface : IDisposable
    {
        public readonly int Width, Height;
        public readonly IntPtr Dc;
        public readonly Graphics Graphics;
        private readonly IntPtr _dib, _old;
        private readonly Bitmap _bitmap;

        public DibSurface(int width, int height)
        {
            Width = width;
            Height = height;
            var info = new BitmapInfoHeader
            {
                Size = Marshal.SizeOf<BitmapInfoHeader>(),
                Width = width,
                Height = -height, // top-down, as a GDI+ bitmap's rows
                Planes = 1,
                BitCount = 32,
            };
            Dc = CreateCompatibleDC(IntPtr.Zero);
            _dib = CreateDIBSection(Dc, ref info, 0, out var bits, IntPtr.Zero, 0);
            if (_dib == IntPtr.Zero)
            {
                DeleteDC(Dc);
                throw new Win32Exception();
            }
            _old = SelectObject(Dc, _dib);
            _bitmap = new Bitmap(width, height, width * 4, PixelFormat.Format32bppPArgb, bits);
            Graphics = Graphics.FromImage(_bitmap);
        }

        public void Dispose()
        {
            Graphics.Dispose();
            _bitmap.Dispose();
            SelectObject(Dc, _old);
            DeleteObject(_dib);
            DeleteDC(Dc);
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            lock (_renderLock)
            {
                _hwnd = IntPtr.Zero;
                _surface?.Dispose();
                _surface = null;
                _gpuSurface?.Dispose();
                _gpuSurface = null;
                foreach (var f in _fonts.Values) f.Dispose();
                _fonts.Clear();
            }
        }
        base.Dispose(disposing);
    }

    // ---- Win32 ----

    private const int GwlExStyle = -20;
    private const int WsExLayered = 0x00080000;
    private const int WsExTransparent = 0x00000020;
    private const int WsExAppWindow = 0x00040000;
    private const int WsExNoActivate = 0x08000000;
    private const int WsExTopmost = 0x00000008;
    private const int WmNcHitTest = 0x0084;
    private const int WmMouseActivate = 0x0021;
    private const int MaNoActivate = 3;
    private const int HtClient = 1, HtLeft = 10, HtRight = 11, HtTop = 12, HtTopLeft = 13, HtTopRight = 14;
    private const int HtBottom = 15, HtBottomLeft = 16, HtBottomRight = 17;
    private static readonly IntPtr HwndTopmost = new(-1);
    private const uint SwpNoSize = 0x1, SwpNoMove = 0x2, SwpNoActivate = 0x10;
    private const byte AcSrcOver = 0, AcSrcAlpha = 1;
    private const int UlwAlpha = 2;

    [StructLayout(LayoutKind.Sequential)]
    private struct BitmapInfoHeader
    {
        public int Size, Width, Height;
        public short Planes, BitCount;
        public int Compression, SizeImage, XPelsPerMeter, YPelsPerMeter, ClrUsed, ClrImportant;
    }

    [DllImport("gdi32.dll", SetLastError = true)]
    private static extern IntPtr CreateDIBSection(IntPtr hdc, ref BitmapInfoHeader info, uint usage, out IntPtr bits, IntPtr section, uint offset);

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeSize { public int Cx, Cy; }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativePoint { public int X, Y; }

    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    private struct BlendFunction { public byte BlendOp, BlendFlags, SourceConstantAlpha, AlphaFormat; }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UpdateLayeredWindow(IntPtr hwnd, IntPtr hdcDst, IntPtr pptDst, ref NativeSize psize,
        IntPtr hdcSrc, ref NativePoint pprSrc, int crKey, ref BlendFunction pblend, int dwFlags);

    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateCompatibleDC(IntPtr hDc);

    [DllImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DeleteDC(IntPtr hDc);

    [DllImport("gdi32.dll")]
    private static extern IntPtr SelectObject(IntPtr hDc, IntPtr hObject);

    [DllImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DeleteObject(IntPtr hObject);

    private const uint LwaAlpha = 0x2;

    [StructLayout(LayoutKind.Sequential)]
    private struct Margins { public int Left, Right, Top, Bottom; }

    [DllImport("dwmapi.dll")]
    private static extern int DwmExtendFrameIntoClientArea(IntPtr hwnd, ref Margins margins);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetLayeredWindowAttributes(IntPtr hwnd, uint key, byte alpha, uint flags);

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeRect { public int Left, Top, Right, Bottom; }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetWindowRect(IntPtr hwnd, out NativeRect rect);

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern IntPtr GetWindowLongPtr(IntPtr hWnd, int nIndex);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW")]
    private static extern IntPtr SetWindowLongPtr(IntPtr hWnd, int nIndex, IntPtr value);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
