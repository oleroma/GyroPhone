using System.ComponentModel;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using GyroPhone.Host;
using GyroPhone.Host.Settings;

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
    private readonly Action<string> _log;
    private readonly OverlayScene _scene = new();
    private DibSurface? _surface;
    // Drawing runs on the pacer's thread (so the main window never waits for it), or on the UI
    // thread in Edit (the window moves and resizes there) and for settings changes: one at a time.
    private readonly object _renderLock = new();
    // The window, for the pacer's thread (Handle is the UI thread's); zero once destroyed.
    private IntPtr _hwnd;
    private readonly int _uiThread = Environment.CurrentManagedThreadId;
    // A tick posted to the UI thread (Edit) and not run yet.
    private int _uiTickQueued;
    // UpdateLayeredWindow refused a call from the pacer's thread: this window draws on the UI thread.
    private bool _uiOnly;
    // The graphics card draws every window unless it failed once in this run; then they draw with the CPU.
    private static bool _gpuBroken;
    // The swap chain (picture shown straight from the graphics card) failed once in this run: every
    // window copies its picture through the CPU (UpdateLayeredWindow) instead.
    private static bool _directBroken;
    // The window shows a swap chain (constant alpha + extended frame), not UpdateLayeredWindow pictures.
    private bool _direct;
    private byte _directAlpha;
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
    private uint _lastTime;
    private ushort _lastLayout;
    private long _lastDrawTicks;
    private bool _dirty = true;

    public OverlayForm(HostEngine engine, string id, Action<string> log, Action<string, Point> clicked, Action<string> picked)
    {
        _engine = engine;
        _id = id;
        _log = log;
        _clicked = clicked;
        _picked = picked;
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
            cp.ExStyle |= WsExLayered | WsExToolWindow | WsExNoActivate | WsExTopmost;
            if (ClickThrough) cp.ExStyle |= WsExTransparent;
            return cp;
        }
    }

    public string Id => _id;

    /// <summary>Its frame-rate cap (0: every screen refresh).</summary>
    public int MaxFps => Settings?.MaxFps ?? 60;

    /// <summary>This window's settings, or null once removed.</summary>
    // The base Form constructor reads CreateParams before this constructor has set _engine.
    private OverlaySettings? Settings => _engine?.Settings.Overlays.Find(o => o.Id == _id);

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

    /// <summary>Settings changed (background, scale, dots, Edit): applied at the next frame.</summary>
    public void SettingsChanged()
    {
        if (!IsHandleCreated) return;
        if (_direct && WindowAlpha() is var a && a != _directAlpha)
        {
            _directAlpha = a;
            SetLayeredWindowAttributes(Handle, 0, a, LwaAlpha);
        }
        long ex = GetWindowLongPtr(Handle, GwlExStyle).ToInt64();
        long want = ClickThrough ? ex | WsExTransparent : ex & ~WsExTransparent;
        if (want != ex) SetWindowLongPtr(Handle, GwlExStyle, new IntPtr(want));
        _dirty = true;
        Render();
    }

    /// <summary>Level: the way the phone is held now shows face-on.</summary>
    public void Level()
    {
        _engine.LevelOverlay(_id);
        _dirty = true;
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        lock (_renderLock)
        {
            _hwnd = Handle;
            _direct = false;
            if (!_gpuBroken && !_directBroken)
            {
                try
                {
                    // The layered window shows the swap chain's alpha: constant window alpha, frame over it all.
                    _directAlpha = WindowAlpha();
                    if (!SetLayeredWindowAttributes(Handle, 0, _directAlpha, LwaAlpha)) throw new Win32Exception();
                    var margins = new Margins { Left = -1, Right = -1, Top = -1, Bottom = -1 };
                    int hr = DwmExtendFrameIntoClientArea(Handle, ref margins);
                    if (hr < 0) throw new Win32Exception(hr);
                    _gpuSurface = new GpuSurface(Handle, Width, Height);
                    _direct = true;
                }
                catch (Exception ex)
                {
                    _log($"overlay: showing straight from the graphics card failed, copying through the CPU: {ex.GetType().Name}: {ex.Message}");
                    _directBroken = true;
                    // Out of constant-alpha mode, so UpdateLayeredWindow works again.
                    long ex0 = GetWindowLongPtr(Handle, GwlExStyle).ToInt64();
                    SetWindowLongPtr(Handle, GwlExStyle, new IntPtr(ex0 & ~WsExLayered));
                    SetWindowLongPtr(Handle, GwlExStyle, new IntPtr(ex0));
                }
            }
            if (!_direct && !_gpuBroken)
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
        _dirty = true;
        if (IsHandleCreated) Render();
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
    /// changed, right there, or on the UI thread while in Edit (one tick queued at most).
    /// </summary>
    public void Tick()
    {
        if (IsDisposed) return;
        if (ClickThrough && !_uiOnly)
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

    private void TickCore()
    {
        // A new OVERLAY, a new layout, or now and then for the status: draw again.
        long age = _engine.OverlaySnapshot(_scene.Frame, _id, out var layout);
        bool fresh = age >= 0 && _scene.Frame.PhoneTimeUs != _lastTime;
        long now = Environment.TickCount64;
        if (fresh) _trails.Add(_scene.Frame, now);
        UpdateStats();
        bool changed = _dirty || fresh || age >= 0 && _scene.Frame.LayoutVersion != _lastLayout ||
            !ReferenceEquals(layout, _scene.Layout) || _trails.Fading(now);
        if (!changed && now - _lastDrawTicks < 500) return;
        // Frame-rate cap (a little early, so a 60 Hz cap on a 60 Hz screen never skips a refresh).
        int fps = MaxFps;
        if (fps > 0 && !_dirty && _clock.Elapsed.TotalMilliseconds - _lastDrawAt < 1000.0 / fps - 3) return;
        _scene.FrameAgeUs = age;
        _scene.Layout = layout;
        Render();
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
        if (hwnd == IntPtr.Zero || !GetWindowRect(hwnd, out var wr)) return;
        int width = wr.Right - wr.Left, height = wr.Bottom - wr.Top;
        if (width <= 0 || height <= 0) return;
        _dirty = false;
        _lastTime = _scene.Frame.PhoneTimeUs;
        _lastLayout = _scene.Frame.LayoutVersion;
        _lastDrawTicks = Environment.TickCount64;
        _scene.Connected = _engine.Snapshot().Connected;
        _scene.Arranging = !ClickThrough;
        _scene.Selected = _selected;
        _scene.Title = _title;
        double start = _clock.Elapsed.TotalMilliseconds;
        if (_gpuSurface != null)
        {
            try
            {
                if (Settings is { } gs)
                {
                    _scene.Trails = gs.FingerTrails ? _trails : null;
                    _scene.NowTicks = _lastDrawTicks;
                    var size = new Size(width, height);
                    _gpuSurface.Draw(width, height, gs.Quality, c => OverlayRenderer.Draw(c, size, _scene, gs), dc => Present(hwnd, dc, width, height));
                }
            }
            catch (Exception e)
            {
                GpuFailed(e);
                _dirty = true;
                return;
            }
            Count(start);
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
                _scene.NowTicks = _lastDrawTicks;
                OverlayRenderer.Draw(new GdiCanvas(g, settings.Quality, _fonts), new Size(width, height), _scene, settings);
            }
        }
        catch (Exception e)
        {
            // A drawing failure must never take the companion down: log it and keep the last picture.
            _log($"overlay: drawing failed: {e.Message}");
        }
        g.Flush(System.Drawing.Drawing2D.FlushIntention.Sync);
        Present(hwnd, _surface.Dc, width, height);
        Count(start);
    }

    /// <summary>The graphics card can't draw: log it, and from now on every window draws with the CPU.</summary>
    private void GpuFailed(Exception e)
    {
        _log($"overlay: graphics card drawing failed, drawing with the CPU: {e.GetType().Name}: {e.Message}");
        _gpuBroken = true;
        try { _gpuSurface?.Dispose(); } catch (Exception) { }
        _gpuSurface = null;
        if (_direct)
        {
            // Made again (UI thread) without the swap chain: UpdateLayeredWindow pictures from then on.
            _direct = false;
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
    private const int WsExToolWindow = 0x00000080;
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
