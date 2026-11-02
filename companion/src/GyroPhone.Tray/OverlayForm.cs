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
/// never takes focus. With click-through the mouse goes past it; without, it can be dragged and
/// resized. One open window holds Ctrl+Alt+R (Level all) for the tray.
/// </summary>
internal sealed class OverlayForm : Form
{
    private readonly HostEngine _engine;
    private readonly string _id;
    private readonly Action _levelAll;
    private readonly FingerTrails _trails = new();
    private readonly Action<string> _log;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly OverlayScene _scene = new();
    private Bitmap? _bitmap;
    private bool _hotkey;
    private uint _lastTime;
    private ushort _lastLayout;
    private long _lastDrawTicks;
    private bool _dirty = true;

    public OverlayForm(HostEngine engine, string id, Action<string> log, Action levelAll)
    {
        _engine = engine;
        _id = id;
        _log = log;
        _levelAll = levelAll;
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
        _timer = new System.Windows.Forms.Timer { Interval = 15 };
        _timer.Tick += (_, _) => Tick();
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

    /// <summary>This window's settings, or null once removed.</summary>
    // The base Form constructor reads CreateParams before this constructor has set _engine.
    private OverlaySettings? Settings => _engine?.Settings.Overlays.Find(o => o.Id == _id);

    private bool ClickThrough => Settings?.ClickThrough ?? true;

    /// <summary>Holds Ctrl+Alt+R (Level all) if no other window does; true when this one has it.</summary>
    public bool TryHotkey()
    {
        if (_hotkey || !IsHandleCreated) return _hotkey;
        _hotkey = RegisterHotKey(Handle, HotkeyId, ModControl | ModAlt | ModNoRepeat, (uint)Keys.R);
        return _hotkey;
    }

    public bool HasHotkey => _hotkey;

    /// <summary>Settings changed (background, scale, dots, click-through): applied at the next frame.</summary>
    public void SettingsChanged()
    {
        if (!IsHandleCreated) return;
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
        _timer.Start();
        Render();
    }

    protected override void OnHandleDestroyed(EventArgs e)
    {
        _timer.Stop();
        if (_hotkey) UnregisterHotKey(Handle, HotkeyId);
        _hotkey = false;
        base.OnHandleDestroyed(e);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // Let go of Ctrl+Alt+R now, so another open window can take it.
        if (_hotkey) UnregisterHotKey(Handle, HotkeyId);
        _hotkey = false;
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
            case WmHotkey when m.WParam.ToInt32() == HotkeyId:
                _levelAll();
                return;
            case WmMouseActivate:
                m.Result = new IntPtr(MaNoActivate);
                return;
            case WmNcHitTest when !ClickThrough:
                var p = PointToClient(new Point(unchecked((short)(long)m.LParam), unchecked((short)((long)m.LParam >> 16))));
                const int grip = 14;
                bool right = p.X >= Width - grip, bottom = p.Y >= Height - grip, left = p.X < grip, top = p.Y < grip;
                m.Result = new IntPtr(
                    right && bottom ? HtBottomRight : left && top ? HtTopLeft : right && top ? HtTopRight : left && bottom ? HtBottomLeft :
                    right ? HtRight : left ? HtLeft : bottom ? HtBottom : top ? HtTop : HtCaption);
                return;
        }
        base.WndProc(ref m);
    }

    private void Tick()
    {
        try
        {
            TickCore();
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
        bool changed = _dirty || fresh || age >= 0 && _scene.Frame.LayoutVersion != _lastLayout ||
            !ReferenceEquals(layout, _scene.Layout) || _trails.Fading(now);
        if (!changed && now - _lastDrawTicks < 500) return;
        _scene.FrameAgeUs = age;
        _scene.Layout = layout;
        Render();
    }

    private void Render()
    {
        try
        {
            RenderCore();
        }
        catch (Exception e)
        {
            _log($"overlay: {e.GetType().Name}: {e.Message}");
        }
    }

    private void RenderCore()
    {
        if (!IsHandleCreated || Width <= 0 || Height <= 0) return;
        _dirty = false;
        _lastTime = _scene.Frame.PhoneTimeUs;
        _lastLayout = _scene.Frame.LayoutVersion;
        _lastDrawTicks = Environment.TickCount64;
        _scene.Connected = _engine.Snapshot().Connected;
        _scene.Arranging = !ClickThrough;
        if (_bitmap == null || _bitmap.Width != Width || _bitmap.Height != Height)
        {
            _bitmap?.Dispose();
            _bitmap = new Bitmap(Width, Height, PixelFormat.Format32bppArgb);
        }
        using (var g = Graphics.FromImage(_bitmap))
        {
            try
            {
                if (Settings is { } settings)
                {
                    _scene.Trails = settings.FingerTrails ? _trails : null;
                    _scene.NowTicks = _lastDrawTicks;
                    OverlayRenderer.Draw(g, _bitmap.Size, _scene, settings);
                }
            }
            catch (Exception e)
            {
                // A drawing failure must never take the companion down: log it and keep the last picture.
                _log($"overlay: drawing failed: {e.Message}");
            }
        }
        Present(_bitmap);
    }

    /// <summary>The whole window's opacity (setting): Windows applies it while compositing, at no cost. Kept visible while arranging.</summary>
    private byte WindowAlpha()
    {
        float op = Math.Clamp(Settings?.Opacity ?? 1f, 0.1f, 1f);
        if (!ClickThrough) op = Math.Max(op, 0.6f);
        return (byte)Math.Round(op * 255);
    }

    /// <summary>Hands the picture, with its alpha, to the layered window.</summary>
    private void Present(Bitmap bmp)
    {
        IntPtr screenDc = GetDC(IntPtr.Zero);
        IntPtr memDc = CreateCompatibleDC(screenDc);
        IntPtr hBitmap = bmp.GetHbitmap(Color.FromArgb(0));
        IntPtr old = SelectObject(memDc, hBitmap);
        try
        {
            var size = new NativeSize { Cx = bmp.Width, Cy = bmp.Height };
            var src = new NativePoint();
            var blend = new BlendFunction { BlendOp = AcSrcOver, SourceConstantAlpha = WindowAlpha(), AlphaFormat = AcSrcAlpha };
            if (!UpdateLayeredWindow(Handle, screenDc, IntPtr.Zero, ref size, memDc, ref src, 0, ref blend, UlwAlpha))
                _log($"overlay: UpdateLayeredWindow failed ({new Win32Exception().Message})");
        }
        finally
        {
            SelectObject(memDc, old);
            DeleteObject(hBitmap);
            DeleteDC(memDc);
            ReleaseDC(IntPtr.Zero, screenDc);
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _timer.Dispose();
            _bitmap?.Dispose();
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
    private const int WmHotkey = 0x0312;
    private const int MaNoActivate = 3;
    private const int HtCaption = 2, HtLeft = 10, HtRight = 11, HtTop = 12, HtTopLeft = 13, HtTopRight = 14;
    private const int HtBottom = 15, HtBottomLeft = 16, HtBottomRight = 17;
    private const int HotkeyId = 0x4750;
    private const uint ModAlt = 0x1, ModControl = 0x2, ModNoRepeat = 0x4000;
    private const byte AcSrcOver = 0, AcSrcAlpha = 1;
    private const int UlwAlpha = 2;

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

    [DllImport("user32.dll")]
    private static extern IntPtr GetDC(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern int ReleaseDC(IntPtr hWnd, IntPtr hDc);

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

    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")]
    private static extern IntPtr GetWindowLongPtr(IntPtr hWnd, int nIndex);

    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW")]
    private static extern IntPtr SetWindowLongPtr(IntPtr hWnd, int nIndex, IntPtr value);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);
}
