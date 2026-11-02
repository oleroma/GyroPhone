using System.ComponentModel;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using GyroPhone.Host;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The phone overlay (tray menu "Phone overlay"): a borderless, always-on-top, see-through window
/// that draws the phone in 3D with its layout and presses (<see cref="PhoneOverlayRenderer"/>). A
/// per-pixel alpha layered window, so it shows over games running windowed or borderless and OBS
/// Window Capture records it. It never takes focus. With click-through the mouse goes past it;
/// without, it can be dragged and resized. Ctrl+Alt+R recentres it while it is shown.
/// </summary>
internal sealed class PhoneOverlayForm : Form
{
    private readonly HostEngine _engine;
    private readonly Action<string> _log;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly OverlayScene _scene = new();
    private Bitmap? _bitmap;
    private bool _hotkey;
    private uint _lastTime;
    private ushort _lastLayout;
    private long _lastDrawTicks;
    private bool _dirty = true;

    public PhoneOverlayForm(HostEngine engine, Action<string> log)
    {
        _engine = engine;
        _log = log;
        var s = engine.Settings.Overlay;
        Text = $"{Program.AppName} phone overlay";
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

    // The base Form constructor reads CreateParams before this constructor has set _engine.
    private bool ClickThrough => _engine?.Settings.Overlay.ClickThrough ?? true;

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

    /// <summary>Takes the phone's pose now as the one shown square on.</summary>
    public void Recenter()
    {
        var f = new GyroPhone.Protocol.OverlayFrame();
        if (_engine.OverlaySnapshot(f, out _) < 0) return;
        _engine.UpdateOverlaySettings(o => o.Center = [f.Qw, f.Qx, f.Qy, f.Qz]);
        _dirty = true;
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        _hotkey = RegisterHotKey(Handle, HotkeyId, ModControl | ModAlt | ModNoRepeat, (uint)Keys.R);
        if (!_hotkey) _log("overlay: Ctrl+Alt+R is taken by another app; recentre from the tray menu");
        _engine.SetOverlay(true);
        _timer.Start();
        Render();
    }

    protected override void OnHandleDestroyed(EventArgs e)
    {
        _timer.Stop();
        if (_hotkey) UnregisterHotKey(Handle, HotkeyId);
        _hotkey = false;
        _engine.SetOverlay(false);
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

    private void SaveBounds()
    {
        var b = Bounds;
        _engine.UpdateOverlaySettings(o =>
        {
            o.X = b.X; o.Y = b.Y; o.Width = b.Width; o.Height = b.Height;
        });
    }

    protected override void WndProc(ref Message m)
    {
        switch (m.Msg)
        {
            case WmHotkey when m.WParam.ToInt32() == HotkeyId:
                Recenter();
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
        long age = _engine.OverlaySnapshot(_scene.Frame, out var layout);
        bool changed = _dirty || age >= 0 && (_scene.Frame.PhoneTimeUs != _lastTime || _scene.Frame.LayoutVersion != _lastLayout) ||
            !ReferenceEquals(layout, _scene.Layout);
        long now = Environment.TickCount64;
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
                PhoneOverlayRenderer.Draw(g, _bitmap.Size, _scene, _engine.Settings.Overlay);
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
        float op = Math.Clamp(_engine?.Settings.Overlay.Opacity ?? 1f, 0.1f, 1f);
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
