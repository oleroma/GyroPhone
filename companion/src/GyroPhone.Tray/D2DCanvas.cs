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
/// One overlay window's graphics-card surface: a Direct3D device and a GDI-compatible texture that
/// a Direct2D context draws into; the finished picture goes to the layered window by
/// UpdateLayeredWindow from the texture's DC, so the window keeps its per-pixel transparency and
/// click-through exactly as with CPU drawing. Throws when the graphics card can't be used (the
/// window then draws with <see cref="GdiCanvas"/>).
/// </summary>
internal sealed class GpuSurface : IDisposable
{
    private readonly ID3D11Device _d3d;
    private readonly IDXGIDevice _dxgi;
    private readonly ID2D1Factory1 _factory;
    private readonly ID2D1Device _device;
    private readonly ID2D1DeviceContext _dc;
    private readonly IDWriteFactory _write;
    private readonly D2DCanvas _canvas;
    private ID3D11Texture2D? _texture;
    private IDXGISurface1? _surface;
    private ID2D1Bitmap1? _bitmap;
    private int _width, _height;

    public GpuSurface()
    {
        D3D11.D3D11CreateDevice(null, DriverType.Hardware, DeviceCreationFlags.BgraSupport,
            [Vortice.Direct3D.FeatureLevel.Level_11_1, Vortice.Direct3D.FeatureLevel.Level_11_0, Vortice.Direct3D.FeatureLevel.Level_10_1, Vortice.Direct3D.FeatureLevel.Level_10_0],
            out var d3d).CheckError();
        _d3d = d3d ?? throw new InvalidOperationException("no Direct3D device");
        _dxgi = _d3d.QueryInterface<IDXGIDevice>();
        _factory = D2D1.D2D1CreateFactory<ID2D1Factory1>();
        _device = _factory.CreateDevice(_dxgi);
        _dc = _device.CreateDeviceContext(DeviceContextOptions.None);
        _write = DWrite.DWriteCreateFactory<IDWriteFactory>();
        _canvas = new D2DCanvas(_factory, _dc, _write);
    }

    private void Unbind()
    {
        _dc.Target = null;
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
        _texture = _d3d.CreateTexture2D(desc);
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

    public void Dispose()
    {
        Unbind();
        _canvas.Dispose();
        _write.Dispose();
        _dc.Dispose();
        _device.Dispose();
        _factory.Dispose();
        _dxgi.Dispose();
        _d3d.Dispose();
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
    // What each Push did, so Pop undoes it: a transform to restore, or a layer (with its geometry) to pop.
    private readonly Stack<(Matrix3x2? Transform, ID2D1Geometry? Geometry, bool Layer)> _stack = new();

    public D2DCanvas(ID2D1Factory1 factory, ID2D1DeviceContext dc, IDWriteFactory write)
    {
        _factory = factory;
        _dc = dc;
        _write = write;
        _brush = dc.CreateSolidColorBrush(new Color4(1, 1, 1, 1));
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
        _dc.Transform = Matrix3x2.Identity;
        _dc.AntialiasMode = quality == OverlayQuality.Fast ? AntialiasMode.Aliased : AntialiasMode.PerPrimitive;
        _dc.TextAntialiasMode = quality == OverlayQuality.Fast ? Vortice.Direct2D1.TextAntialiasMode.Aliased : Vortice.Direct2D1.TextAntialiasMode.Grayscale;
        _stack.Clear();
    }

    public void End()
    {
        while (_stack.Count > 0) Pop();
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
        using var geo = Path(points, true);
        _dc.FillGeometry(geo, Brush(color));
    }

    public void DrawPolygon(Color color, float width, PointF[] points, bool dashed = false)
    {
        if (points.Length < 2 || color.A == 0) return;
        using var geo = Path(points, true);
        _dc.DrawGeometry(geo, Brush(color), width, dashed ? _dashed : _joined);
    }

    public void DrawLine(Color color, float width, PointF a, PointF b, bool round = false)
    {
        if (color.A == 0) return;
        _dc.DrawLine(V(a), V(b), Brush(color), width, round ? _round : null);
    }

    public void DrawLines(Color color, float width, PointF[] points)
    {
        if (points.Length < 2 || color.A == 0) return;
        using var geo = Path(points, false);
        _dc.DrawGeometry(geo, Brush(color), width, _round);
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

    public void FillBall(RectangleF r, PointF litAt, Color lit, Color body)
    {
        var e = E(r);
        using var stops = _dc.CreateGradientStopCollection([new GradientStop(0f, C4(lit)), new GradientStop(1f, C4(body))]);
        var props = new RadialGradientBrushProperties(e.Point, new Vector2(litAt.X - e.Point.X, litAt.Y - e.Point.Y), e.RadiusX, e.RadiusY);
        using var brush = _dc.CreateRadialGradientBrush(props, stops);
        _dc.FillEllipse(e, brush);
    }

    private void PushMask(ID2D1Geometry geo)
    {
        _dc.PushLayer(new LayerParameters1
        {
            ContentBounds = Infinite,
            GeometricMask = geo,
            MaskAntialiasMode = _dc.AntialiasMode,
            MaskTransform = Matrix3x2.Identity,
            Opacity = 1f,
        }, null!);
        _stack.Push((null, geo, true));
    }

    public void PushClip(PointF[] polygon) => PushMask(Path(polygon, true));

    public void PushClipEllipse(RectangleF r) => PushMask(_factory.CreateEllipseGeometry(E(r)));

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

    public void DrawText(string text, float px, bool bold, Color color, RectangleF r, TextAlign h, TextAlign v)
    {
        if (text.Length == 0 || color.A == 0) return;
        var f = Format(px, bold);
        f.TextAlignment = h == TextAlign.Near ? TextAlignment.Leading : h == TextAlign.Far ? TextAlignment.Trailing : TextAlignment.Center;
        f.ParagraphAlignment = v == TextAlign.Near ? ParagraphAlignment.Near : v == TextAlign.Far ? ParagraphAlignment.Far : ParagraphAlignment.Center;
        _dc.DrawText(text, f, R(r), Brush(color), DrawTextOptions.Clip);
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
        _round.Dispose();
        _joined.Dispose();
        _dashed.Dispose();
        _brush.Dispose();
    }
}
