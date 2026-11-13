using System.Numerics;
using GyroPhone.Host.Settings;
using Vortice.Direct2D1;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DirectWrite;
using Vortice.DXGI;
using Vortice.Mathematics;
using D2DAlphaMode = Vortice.DCommon.AlphaMode;
using D2DPixelFormat = Vortice.DCommon.PixelFormat;
using Color = System.Drawing.Color;
using Rect = Vortice.Mathematics.Rect;

namespace GyroPhone.Tray;

/// <summary>
/// The graphics card objects every overlay window shares: one Direct3D device, one Direct2D factory
/// and device, one DirectWrite factory. The Direct2D factory is multithreaded, so the pacer's thread
/// and the UI thread can draw different windows at once; direct Direct3D/DXGI calls go between
/// <see cref="Enter"/> and <see cref="Leave"/>. Each window's surface holds a reference and the last
/// to let go disposes them. A lost device (sleep, a driver update) is retired once: the next surface
/// made gets a new device, and the old one goes when every surface on it has let go, so no window's
/// objects are disposed while another window is drawing with them.
/// </summary>
internal sealed class GpuDevice
{
    // Guards _current and every _refs. Never held while calling Enter.
    private static readonly object Gate = new();
    private static GpuDevice? _current;
    private int _refs;
    public readonly ID3D11Device D3D;
    public readonly IDXGIDevice Dxgi;
    public readonly ID2D1Factory1 Factory;
    public readonly ID2D1Device Device;
    public readonly IDWriteFactory Write;
    private readonly ID2D1Multithread _lock;
    // For the frame queue's length (null where it can't be set), and the swap chains on this device.
    private readonly IDXGIDevice1? _dxgi1;
    private int _swapChains;

    private GpuDevice()
    {
        D3D11.D3D11CreateDevice(null, DriverType.Hardware, DeviceCreationFlags.BgraSupport,
            [Vortice.Direct3D.FeatureLevel.Level_11_1, Vortice.Direct3D.FeatureLevel.Level_11_0, Vortice.Direct3D.FeatureLevel.Level_10_1, Vortice.Direct3D.FeatureLevel.Level_10_0],
            out var d3d).CheckError();
        D3D = d3d ?? throw new InvalidOperationException("no Direct3D device");
        try
        {
            Dxgi = D3D.QueryInterface<IDXGIDevice>();
            Factory = D2D1.D2D1CreateFactory<ID2D1Factory1>(Vortice.Direct2D1.FactoryType.MultiThreaded, Vortice.Direct2D1.DebugLevel.None);
            _lock = Factory.QueryInterface<ID2D1Multithread>();
            Device = Factory.CreateDevice(Dxgi);
            Write = DWrite.DWriteCreateFactory<IDWriteFactory>();
            _dxgi1 = D3D.QueryInterfaceOrNull<IDXGIDevice1>();
        }
        catch
        {
            DisposeAll();
            throw;
        }
    }

    /// <summary>The shared device (made if there is none), with one more reference: let go with <see cref="Release"/>.</summary>
    public static GpuDevice Acquire()
    {
        lock (Gate)
        {
            _current ??= new GpuDevice();
            _current._refs++;
            return _current;
        }
    }

    /// <summary>One reference less; the last one disposes it.</summary>
    public void Release()
    {
        lock (Gate)
        {
            if (--_refs > 0) return;
            if (ReferenceEquals(_current, this)) _current = null;
        }
        // No references and no longer current: nobody else can reach it.
        DisposeAll();
    }

    /// <summary>Retired (found lost): surfaces on it make new ones, on the next device.</summary>
    public bool Lost
    {
        get
        {
            lock (Gate) return !ReferenceEquals(_current, this);
        }
    }

    /// <summary>
    /// After a failure: if the device was removed or Direct2D asks for new targets, retires it so
    /// the next surface made gets a new device (once, however many windows saw it fail).
    /// </summary>
    public void CheckLost(Exception? e)
    {
        bool lost = e is SharpGen.Runtime.SharpGenException s && s.ResultCode == Vortice.Direct2D1.ResultCode.RecreateTarget;
        try
        {
            lost |= D3D.DeviceRemovedReason.Failure;
        }
        catch (Exception)
        {
            lost = true;
        }
        if (!lost) return;
        lock (Gate)
        {
            if (ReferenceEquals(_current, this)) _current = null;
        }
    }

    /// <summary>Takes Direct2D's lock for direct Direct3D/DXGI calls on the shared device (another thread may be drawing).</summary>
    public void Enter() => _lock.Enter();

    public void Leave() => _lock.Leave();

    /// <summary>
    /// A swap chain was made (+1) or let go (−1) on this device: at most one picture per swap chain
    /// waits for the graphics card (Windows' default is 3 for the whole device), so on a busy card an
    /// overlay shows its newest picture instead of one 2–3 frames old. Not 1 for the device: the
    /// windows present one after another on the pacer's thread, and each would wait for the one before.
    /// Called between <see cref="Enter"/> and <see cref="Leave"/>.
    /// </summary>
    public void SwapChains(int change)
    {
        _swapChains = Math.Max(0, _swapChains + change);
        try
        {
            _dxgi1?.SetMaximumFrameLatency((uint)Math.Clamp(_swapChains, 1, 3));
        }
        catch (Exception)
        {
            // Not set: Windows' default stays.
        }
    }

    private void DisposeAll()
    {
        Write?.Dispose();
        _dxgi1?.Dispose();
        Device?.Dispose();
        _lock?.Dispose();
        Factory?.Dispose();
        Dxgi?.Dispose();
        D3D.Dispose();
    }
}

/// <summary>
/// One overlay window's graphics-card surface: its own Direct2D context, swap chain or texture and
/// canvas, on the shared <see cref="GpuDevice"/>.
/// Two ways to the screen:
/// <list type="bullet">
/// <item>Swap chain (with a window handle): the picture stays on the graphics card and is presented
/// to the window, which shows its alpha through the desktop compositor (the frame extended over the
/// whole window). No copy through the CPU: the cheap way.</item>
/// <item>Copy (no window handle): a GDI-compatible texture whose DC goes to UpdateLayeredWindow,
/// the whole picture through the CPU every frame (slowed other windows' drawing, such as the main
/// window scrolling).</item>
/// </list>
/// Throws when the graphics card can't be used (the window then draws with <see cref="GdiCanvas"/>).
/// </summary>
internal sealed class GpuSurface : IDisposable
{
    private readonly GpuDevice _shared;
    private readonly ID2D1DeviceContext _dc = null!;
    private readonly D2DCanvas _canvas = null!;
    private ID3D11Texture2D? _texture;
    private IDXGISurface1? _surface;
    private ID2D1Bitmap1? _bitmap;
    private int _width, _height;
    private readonly IDXGISwapChain1? _swap;
    private bool _disposed;

    /// <summary>With <paramref name="hwnd"/>: presented by a swap chain to that window; without: copied (see the class).</summary>
    public GpuSurface(IntPtr hwnd = default, int width = 1, int height = 1)
    {
        _shared = GpuDevice.Acquire();
        try
        {
            _dc = _shared.Device.CreateDeviceContext(DeviceContextOptions.None);
            _canvas = new D2DCanvas(_shared.Factory, _shared.Device, _dc, _shared.Write) { Gpu = _shared };
            if (hwnd == IntPtr.Zero) return;
            // Bit-block transfer model: the one a layered window takes. Its alpha reaches the screen
            // through the window's redirection surface (the extended frame), premultiplied.
            var desc = new SwapChainDescription1
            {
                Width = (uint)Math.Max(1, width),
                Height = (uint)Math.Max(1, height),
                Format = Format.B8G8R8A8_UNorm,
                BufferCount = 1,
                BufferUsage = Usage.RenderTargetOutput,
                SwapEffect = SwapEffect.Discard,
                AlphaMode = Vortice.DXGI.AlphaMode.Unspecified,
                SampleDescription = new SampleDescription(1, 0),
                Scaling = Scaling.Stretch,
            };
            _shared.Enter();
            try
            {
                using var adapter = _shared.Dxgi.GetAdapter();
                using var dxgiFactory = adapter.GetParent<IDXGIFactory2>();
                _swap = dxgiFactory.CreateSwapChainForHwnd(_shared.D3D, hwnd, desc);
                _shared.SwapChains(+1); // counted from here: Dispose lets it go
                // No Alt+Enter full screen, and DXGI never touches the window (it lives on another thread).
                dxgiFactory.MakeWindowAssociation(hwnd, WindowAssociationFlags.IgnoreAll);
                _width = (int)desc.Width;
                _height = (int)desc.Height;
                BindSwap();
            }
            finally
            {
                _shared.Leave();
            }
        }
        catch (Exception e)
        {
            _shared.CheckLost(e);
            Dispose();
            throw;
        }
    }

    /// <summary>Presented by a swap chain (no copy through the CPU).</summary>
    public bool Direct => _swap != null;

    /// <summary>A swap chain whose buffers are not <paramref name="width"/>×<paramref name="height"/>: drawing at that size resizes it first.</summary>
    public bool NeedsResize(int width, int height) => _swap != null && (Math.Max(1, width) != _width || Math.Max(1, height) != _height);

    /// <summary>Its device was found lost (by any window): make a new surface, which gets the new device.</summary>
    public bool Lost => _shared.Lost;

    /// <summary>After a failure of this surface: retires the shared device if it was lost (<see cref="GpuDevice.CheckLost"/>).</summary>
    public void CheckLost(Exception e) => _shared.CheckLost(e);

    private void BindSwap()
    {
        using var buffer = _swap!.GetBuffer<IDXGISurface>(0);
        var props = new BitmapProperties1(new D2DPixelFormat(Format.B8G8R8A8_UNorm, D2DAlphaMode.Premultiplied), 96, 96,
            BitmapOptions.Target | BitmapOptions.CannotDraw);
        _bitmap = _dc.CreateBitmapFromDxgiSurface(buffer, props);
        _dc.Target = _bitmap;
    }

    private void Unbind()
    {
        if (_dc != null) _dc.Target = null;
        _bitmap?.Dispose();
        _bitmap = null;
        _surface?.Dispose();
        _surface = null;
        _texture?.Dispose();
        _texture = null;
    }

    private void Bind(int width, int height)
    {
        Unbind();
        var desc = new Texture2DDescription(Format.B8G8R8A8_UNorm, (uint)width, (uint)height, 1, 1,
            BindFlags.RenderTarget | BindFlags.ShaderResource, ResourceUsage.Default, CpuAccessFlags.None, 1, 0,
            ResourceOptionFlags.GdiCompatible);
        _texture = _shared.D3D.CreateTexture2D(desc);
        _surface = _texture.QueryInterface<IDXGISurface1>();
        var props = new BitmapProperties1(new D2DPixelFormat(Format.B8G8R8A8_UNorm, D2DAlphaMode.Premultiplied), 96, 96,
            BitmapOptions.Target | BitmapOptions.GdiCompatible);
        _bitmap = _dc.CreateBitmapFromDxgiSurface(_surface, props);
        _dc.Target = _bitmap;
        _width = width;
        _height = height;
    }

    /// <summary>
    /// Draws one picture of <paramref name="width"/>×<paramref name="height"/>, then hands
    /// <paramref name="present"/> a DC holding it (valid only during the call).
    /// </summary>
    public void Draw(int width, int height, OverlayQuality quality, Action<ICanvas> draw, Action<IntPtr> present)
    {
        width = Math.Max(1, width);
        height = Math.Max(1, height);
        if (_swap != null)
        {
            if (width != _width || height != _height)
            {
                _shared.Enter();
                try
                {
                    Unbind();
                    _swap.ResizeBuffers(1, (uint)width, (uint)height, Format.B8G8R8A8_UNorm, SwapChainFlags.None).CheckError();
                    _width = width;
                    _height = height;
                    BindSwap();
                }
                finally
                {
                    _shared.Leave();
                }
            }
            _dc.BeginDraw();
            try
            {
                _canvas.Begin(quality);
                draw(_canvas);
                _canvas.End();
            }
            finally
            {
                _dc.EndDraw().CheckError();
            }
            _shared.Enter();
            try
            {
                _swap.Present(0, PresentFlags.None).CheckError();
            }
            finally
            {
                _shared.Leave();
            }
            return;
        }
        if (_bitmap == null || width != _width || height != _height) Bind(width, height);
        _dc.BeginDraw();
        try
        {
            _canvas.Begin(quality);
            draw(_canvas);
            _canvas.End();
        }
        finally
        {
            _dc.EndDraw().CheckError();
        }
        // No other window's Direct3D work on the shared device while the DC is out.
        _shared.Enter();
        try
        {
            IntPtr hdc = _surface!.GetDC(false);
            try
            {
                present(hdc);
            }
            finally
            {
                _surface.ReleaseDC(null);
            }
        }
        finally
        {
            _shared.Leave();
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _shared.Enter();
        try
        {
            Unbind();
            if (_swap != null)
            {
                _swap.Dispose();
                _shared.SwapChains(-1);
            }
            _canvas?.Dispose();
            _dc?.Dispose();
        }
        finally
        {
            _shared.Leave();
            // The last surface on the device disposes it (outside Direct2D's lock).
            _shared.Release();
        }
    }
}

/// <summary>The graphics-card backend of <see cref="ICanvas"/>: Direct2D on a device context.</summary>
internal sealed class D2DCanvas : ICanvas, IDisposable
{
    private readonly ID2D1Factory1 _factory;
    private readonly ID2D1DeviceContext _dc;
    private readonly IDWriteFactory _write;
    private readonly ID2D1SolidColorBrush _brush;
    private readonly ID2D1StrokeStyle _round, _joined, _dashed;
    private readonly Dictionary<(float, bool), IDWriteTextFormat> _formats = [];
    // Text drawn once into a bitmap, then shown (turned and tilted with the phone) as an image: text
    // under a tilt is otherwise built from its glyphs' outlines every frame, the dearest part of a
    // frame. Keyed by everything that changes the picture, the scale rounded to an eighth.
    private readonly Dictionary<TextKey, ID2D1Bitmap1> _texts = [];
    private readonly ID2D1DeviceContext _textDc;
    private readonly ID2D1SolidColorBrush _textBrush;
    // The geometry of the last filled polygon: an outline of the same points reuses it.
    private PointF[]? _lastPoints;
    // Drawing text into a bitmap failed once: text is drawn directly from then on.
    private bool _noTextCache;
    private ID2D1PathGeometry? _lastGeo;

    /// <summary>The shared device this canvas draws on (for <see cref="GpuPaint"/>), or null.</summary>
    public GpuDevice? Gpu { get; init; }

    /// <summary>The device context drawn on (for <see cref="GpuPaint"/>'s pictures).</summary>
    public ID2D1DeviceContext Dc => _dc;

    // The 3D overlay's pictures on the card (<see cref="Gpu3D"/>), let go with this canvas.
    private IDisposable? _scene;

    /// <summary>Lets go of <paramref name="scene"/> (<see cref="Gpu3D"/>'s pictures for this canvas) when this canvas goes.</summary>
    public void OwnScene(IDisposable scene) => _scene = scene;

    /// <summary>The picture's size in pixels (<see cref="Gpu3D"/>'s pictures are as big).</summary>
    public float Width => _dc.PixelSize.Width;

    public float Height => _dc.PixelSize.Height;

    /// <summary>Counts the pictures begun on this canvas.</summary>
    public long Frame { get; private set; }

    private readonly record struct TextKey(string Text, float Px, bool Bold, int Argb, float W, float H, TextAlign Hz, TextAlign Vt, int Scale8);
    // What each Push did, so Pop undoes it: a transform to restore, or a layer (with its geometry) to pop.
    private readonly Stack<(Matrix3x2? Transform, ID2D1Geometry? Geometry, bool Layer)> _stack = new();

    public D2DCanvas(ID2D1Factory1 factory, ID2D1Device device, ID2D1DeviceContext dc, IDWriteFactory write)
    {
        _factory = factory;
        _dc = dc;
        _write = write;
        _brush = dc.CreateSolidColorBrush(new Color4(1, 1, 1, 1));
        _textDc = device.CreateDeviceContext(DeviceContextOptions.None);
        _textBrush = _textDc.CreateSolidColorBrush(new Color4(1, 1, 1, 1));
        _round = factory.CreateStrokeStyle(new StrokeStyleProperties
        {
            StartCap = CapStyle.Round,
            EndCap = CapStyle.Round,
            DashCap = CapStyle.Round,
            LineJoin = LineJoin.Round,
        });
        _joined = factory.CreateStrokeStyle(new StrokeStyleProperties { LineJoin = LineJoin.Round });
        _dashed = factory.CreateStrokeStyle(new StrokeStyleProperties { DashStyle = DashStyle.Dash, LineJoin = LineJoin.Round });
    }

    public void Begin(OverlayQuality quality)
    {
        Frame++;
        _dc.Transform = Matrix3x2.Identity;
        _dc.AntialiasMode = quality == OverlayQuality.Fast ? AntialiasMode.Aliased : AntialiasMode.PerPrimitive;
        _dc.TextAntialiasMode = quality == OverlayQuality.Fast ? Vortice.Direct2D1.TextAntialiasMode.Aliased : Vortice.Direct2D1.TextAntialiasMode.Grayscale;
        _stack.Clear();
    }

    public void End()
    {
        while (_stack.Count > 0) Pop();
        ForgetLast();
    }

    private void ForgetLast()
    {
        _lastGeo?.Dispose();
        _lastGeo = null;
        _lastPoints = null;
    }

    private static readonly Rect Infinite = new(-1e6f, -1e6f, 2e6f, 2e6f);

    private ID2D1SolidColorBrush Brush(Color c)
    {
        _brush.Color = new Color4(c.R / 255f, c.G / 255f, c.B / 255f, c.A / 255f);
        return _brush;
    }

    private static Vector2 V(PointF p) => new(p.X, p.Y);

    private static Rect R(RectangleF r) => new(r.X, r.Y, r.Width, r.Height);

    private ID2D1PathGeometry Path(PointF[] points, bool closed)
    {
        var geo = _factory.CreatePathGeometry();
        using var sink = geo.Open();
        sink.BeginFigure(V(points[0]), closed ? FigureBegin.Filled : FigureBegin.Hollow);
        for (int i = 1; i < points.Length; i++) sink.AddLine(V(points[i]));
        sink.EndFigure(closed ? FigureEnd.Closed : FigureEnd.Open);
        sink.Close();
        return geo;
    }

    public void Clear(Color color) => _dc.Clear(new Color4(color.R / 255f * (color.A / 255f), color.G / 255f * (color.A / 255f), color.B / 255f * (color.A / 255f), color.A / 255f));

    public void FillPolygon(Color color, PointF[] points)
    {
        if (points.Length < 3 || color.A == 0) return;
        ForgetLast();
        _lastGeo = Path(points, true);
        _lastPoints = points;
        _dc.FillGeometry(_lastGeo, Brush(color));
    }

    public void FillQuads(Color color, List<PointF> quads)
    {
        if (quads.Count < 4 || color.A == 0) return;
        using var geo = _factory.CreatePathGeometry();
        using (var sink = geo.Open())
        {
            // Winding: where quads overlap the shape is filled once.
            sink.SetFillMode(Vortice.Direct2D1.FillMode.Winding);
            for (int i = 0; i + 3 < quads.Count; i += 4)
            {
                sink.BeginFigure(V(quads[i]), FigureBegin.Filled);
                sink.AddLine(V(quads[i + 1]));
                sink.AddLine(V(quads[i + 2]));
                sink.AddLine(V(quads[i + 3]));
                sink.EndFigure(FigureEnd.Closed);
            }
            sink.Close();
        }
        _dc.FillGeometry(geo, Brush(color));
    }

    public void DrawPolygon(Color color, float width, PointF[] points, bool dashed = false)
    {
        if (points.Length < 2 || color.A == 0) return;
        if (ReferenceEquals(points, _lastPoints) && _lastGeo != null)
        {
            _dc.DrawGeometry(_lastGeo, Brush(color), width, dashed ? _dashed : _joined);
            return;
        }
        using var geo = Path(points, true);
        _dc.DrawGeometry(geo, Brush(color), width, dashed ? _dashed : _joined);
    }

    public void DrawLine(Color color, float width, PointF a, PointF b, bool round = false)
    {
        if (color.A == 0) return;
        _dc.DrawLine(V(a), V(b), Brush(color), width, round ? _round : null);
    }

    public void DrawLines(Color color, float width, PointF[] points, bool flat = false)
    {
        if (points.Length < 2 || color.A == 0) return;
        using var geo = Path(points, false);
        _dc.DrawGeometry(geo, Brush(color), width, flat ? _joined : _round);
    }

    // Built line sets (DrawLinesSet), by their array, with the stamp they were built for.
    private sealed class LineSet
    {
        public ID2D1PathGeometry?[] Geos = [];
        public long Stamp;
    }

    private readonly Dictionary<PointF[][], LineSet> _lineSets = new(ReferenceEqualityComparer.Instance);

    public void DrawLinesSet(Color color, float width, PointF[][] lines, long stamp)
    {
        if (color.A == 0) return;
        if (!_lineSets.TryGetValue(lines, out var set) || set.Stamp != stamp)
        {
            if (set == null)
            {
                // A window keeps a couple (the grid's two halves): more means old ones are left behind.
                if (_lineSets.Count >= 8) ForgetLineSets();
                _lineSets[lines] = set = new LineSet();
            }
            foreach (var old in set.Geos) old?.Dispose();
            set.Geos = new ID2D1PathGeometry?[lines.Length];
            for (int i = 0; i < lines.Length; i++)
                if (lines[i].Length >= 2) set.Geos[i] = Path(lines[i], false);
            set.Stamp = stamp;
        }
        var brush = Brush(color);
        foreach (var geo in set.Geos)
            if (geo != null) _dc.DrawGeometry(geo, brush, width, _round);
    }

    private void ForgetLineSets()
    {
        foreach (var set in _lineSets.Values)
            foreach (var geo in set.Geos) geo?.Dispose();
        _lineSets.Clear();
    }

    private static Ellipse E(RectangleF r) => new(new Vector2(r.X + r.Width / 2, r.Y + r.Height / 2), r.Width / 2, r.Height / 2);

    public void FillEllipse(Color color, RectangleF r)
    {
        if (color.A != 0) _dc.FillEllipse(E(r), Brush(color));
    }

    public void DrawEllipse(Color color, float width, RectangleF r)
    {
        if (color.A != 0) _dc.DrawEllipse(E(r), Brush(color), width);
    }

    public void FillRectangle(Color color, RectangleF r)
    {
        if (color.A != 0) _dc.FillRectangle(R(r), Brush(color));
    }

    public void DrawRectangle(Color color, float width, RectangleF r, bool dashed = false)
    {
        if (color.A != 0) _dc.DrawRectangle(R(r), Brush(color), width, dashed ? _dashed : _joined);
    }

    private static Color4 C4(Color c) => new(c.R / 255f, c.G / 255f, c.B / 255f, c.A / 255f);

    // The ball's gradient brush, kept for its two colours (its place and size are set each time).
    private ID2D1RadialGradientBrush? _ball;
    private (int Lit, int Body) _ballColors;

    public void FillBall(RectangleF r, PointF litAt, Color lit, Color body)
    {
        var e = E(r);
        var origin = new Vector2(litAt.X - e.Point.X, litAt.Y - e.Point.Y);
        var colors = (lit.ToArgb(), body.ToArgb());
        if (_ball == null || _ballColors != colors)
        {
            _ball?.Dispose();
            _ball = null;
            using var stops = _dc.CreateGradientStopCollection([new GradientStop(0f, C4(lit)), new GradientStop(1f, C4(body))]);
            _ball = _dc.CreateRadialGradientBrush(new RadialGradientBrushProperties(e.Point, origin, e.RadiusX, e.RadiusY), stops);
            _ballColors = colors;
        }
        else
        {
            _ball.Center = e.Point;
            _ball.GradientOriginOffset = origin;
            _ball.RadiusX = e.RadiusX;
            _ball.RadiusY = e.RadiusY;
        }
        _dc.FillEllipse(e, _ball);
    }

    private sealed class Picture { public ID2D1Bitmap1? Bitmap; public long Stamp = long.MinValue; }
    private readonly System.Runtime.CompilerServices.ConditionalWeakTable<int[], Picture> _pictures = new();

    public void DrawPixels(RectangleF dest, int w, int h, int[] pixels, long stamp)
    {
        var pic = _pictures.GetOrCreateValue(pixels);
        if (pic.Bitmap == null || pic.Bitmap.PixelSize.Width != w || pic.Bitmap.PixelSize.Height != h)
        {
            pic.Bitmap?.Dispose();
            pic.Bitmap = _dc.CreateBitmap(new SizeI(w, h), IntPtr.Zero, 0,
                new BitmapProperties1(new D2DPixelFormat(Vortice.DXGI.Format.B8G8R8A8_UNorm, D2DAlphaMode.Premultiplied), 96, 96, BitmapOptions.None));
            pic.Stamp = long.MinValue;
        }
        if (pic.Stamp != stamp)
        {
            var pin = System.Runtime.InteropServices.GCHandle.Alloc(pixels, System.Runtime.InteropServices.GCHandleType.Pinned);
            try { pic.Bitmap.CopyFromMemory(pin.AddrOfPinnedObject(), (uint)(w * 4)); }
            finally { pin.Free(); }
            pic.Stamp = stamp;
        }
        _dc.DrawBitmap(pic.Bitmap, new Rect(dest.X, dest.Y, dest.Width, dest.Height), 1f, BitmapInterpolationMode.Linear, null);
    }

    private void PushMask(ID2D1Geometry geo, Rect bounds)
    {
        _dc.PushLayer(new LayerParameters1
        {
            // Only the mask's box, not the whole window: the layer's own surface stays small.
            ContentBounds = bounds,
            GeometricMask = geo,
            MaskAntialiasMode = _dc.AntialiasMode,
            MaskTransform = Matrix3x2.Identity,
            Opacity = 1f,
        }, null!);
        _stack.Push((null, geo, true));
    }

    public void PushClip(PointF[] polygon)
    {
        float l = float.MaxValue, t = float.MaxValue, r = float.MinValue, b = float.MinValue;
        foreach (var p in polygon)
        {
            l = Math.Min(l, p.X); t = Math.Min(t, p.Y); r = Math.Max(r, p.X); b = Math.Max(b, p.Y);
        }
        var bounds = polygon.Length > 0 ? new Rect(l - 2, t - 2, r - l + 4, b - t + 4) : Infinite;
        PushMask(Path(polygon, true), bounds);
    }

    public void PushClipEllipse(RectangleF r) => PushMask(_factory.CreateEllipseGeometry(E(r)), new Rect(r.X - 2, r.Y - 2, r.Width + 4, r.Height + 4));

    public void PushClipRect(RectangleF r)
    {
        _dc.PushAxisAlignedClip(R(r), _dc.AntialiasMode);
        _stack.Push((null, null, false));
    }

    public void PushTransform(Matrix3x2 m)
    {
        var now = _dc.Transform;
        _stack.Push((now, null, false));
        _dc.Transform = m * now;
    }

    public void Pop()
    {
        if (_stack.Count == 0) return;
        var (transform, geo, layer) = _stack.Pop();
        if (transform is { } t) _dc.Transform = t;
        else if (layer)
        {
            _dc.PopLayer();
            geo?.Dispose();
        }
        else _dc.PopAxisAlignedClip();
    }

    private IDWriteTextFormat Format(float px, bool bold)
    {
        px = MathF.Max(1f, MathF.Round(px * 4f) / 4f);
        if (_formats.TryGetValue((px, bold), out var f)) return f;
        if (_formats.Count >= 32)
        {
            foreach (var old in _formats.Values) old.Dispose();
            _formats.Clear();
        }
        f = _write.CreateTextFormat("Segoe UI", null, bold ? FontWeight.Bold : FontWeight.Normal, Vortice.DirectWrite.FontStyle.Normal, FontStretch.Normal, px, "en-us");
        f.WordWrapping = WordWrapping.NoWrap;
        return _formats[(px, bold)] = f;
    }

    private void Align(IDWriteTextFormat f, TextAlign h, TextAlign v)
    {
        f.TextAlignment = h == TextAlign.Near ? TextAlignment.Leading : h == TextAlign.Far ? TextAlignment.Trailing : TextAlignment.Center;
        f.ParagraphAlignment = v == TextAlign.Near ? ParagraphAlignment.Near : v == TextAlign.Far ? ParagraphAlignment.Far : ParagraphAlignment.Center;
    }

    public void DrawText(string text, float px, bool bold, Color color, RectangleF r, TextAlign h, TextAlign v)
    {
        if (text.Length == 0 || color.A == 0 || r.Width <= 0 || r.Height <= 0) return;
        // The size it shows at: the transform's scale, to an eighth (at most 4×).
        var m = _dc.Transform;
        float scale = MathF.Sqrt(MathF.Abs(m.M11 * m.M22 - m.M12 * m.M21));
        int scale8 = Math.Clamp((int)MathF.Ceiling(scale * 8f), 1, 32);
        float q = scale8 / 8f;
        int bw = (int)MathF.Ceiling(r.Width * q), bh = (int)MathF.Ceiling(r.Height * q);
        var f = Format(px, bold);
        if (bw > 2048 || bh > 2048 || _noTextCache)
        {
            Align(f, h, v);
            _dc.DrawText(text, f, R(r), Brush(color), DrawTextOptions.Clip);
            return;
        }
        var key = new TextKey(text, px, bold, color.ToArgb(), r.Width, r.Height, h, v, scale8);
        if (!_texts.TryGetValue(key, out var bitmap))
        {
            if (_texts.Count >= 256)
            {
                foreach (var old in _texts.Values) old.Dispose();
                _texts.Clear();
            }
            try
            {
                bitmap = TextBitmap(text, f, color, r, h, v, q, bw, bh);
            }
            catch (Exception)
            {
                _noTextCache = true;
                Align(f, h, v);
                _dc.DrawText(text, f, R(r), Brush(color), DrawTextOptions.Clip);
                return;
            }
            _texts[key] = bitmap;
        }
        _dc.DrawBitmap(bitmap, new Vortice.RawRectF(r.X, r.Y, r.X + bw / q, r.Y + bh / q), 1f, Vortice.Direct2D1.InterpolationMode.Linear, null, null);
    }

    /// <summary>The text drawn once, at <paramref name="q"/>× its size, on a separate context (the main one is mid-frame).</summary>
    private ID2D1Bitmap1 TextBitmap(string text, IDWriteTextFormat f, Color color, RectangleF r, TextAlign h, TextAlign v, float q, int bw, int bh)
    {
        {
            var bitmap = _textDc.CreateBitmap(new SizeI(bw, bh), IntPtr.Zero, 0,
                new BitmapProperties1(new D2DPixelFormat(Vortice.DXGI.Format.B8G8R8A8_UNorm, D2DAlphaMode.Premultiplied), 96, 96, BitmapOptions.Target));
            Align(f, h, v);
            _textDc.Target = bitmap;
            _textDc.BeginDraw();
            try
            {
                _textDc.Clear(new Color4(0, 0, 0, 0));
                _textDc.Transform = Matrix3x2.CreateScale(q);
                _textDc.TextAntialiasMode = Vortice.Direct2D1.TextAntialiasMode.Grayscale;
                _textBrush.Color = C4(color);
                _textDc.DrawText(text, f, new Rect(0, 0, r.Width, r.Height), _textBrush, DrawTextOptions.Clip);
            }
            finally
            {
                _textDc.EndDraw().CheckError();
                _textDc.Target = null;
            }
            return bitmap;
        }
    }

    public SizeF MeasureText(string text, float px, bool bold)
    {
        using var layout = _write.CreateTextLayout(text, Format(px, bold), 100_000f, 100_000f);
        var m = layout.Metrics;
        return new SizeF(m.WidthIncludingTrailingWhitespace, m.Height);
    }

    public void Dispose()
    {
        foreach (var f in _formats.Values) f.Dispose();
        _formats.Clear();
        foreach (var b in _texts.Values) b.Dispose();
        _texts.Clear();
        ForgetLast();
        ForgetLineSets();
        _ball?.Dispose();
        _scene?.Dispose();
        _scene = null;
        _textBrush.Dispose();
        _textDc.Dispose();
        _round.Dispose();
        _joined.Dispose();
        _dashed.Dispose();
        _brush.Dispose();
    }
}
