using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Numerics;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

internal enum TextAlign { Near, Center, Far }

/// <summary>
/// What the overlay drawings draw with: flat colours, lines, polygons, ellipses, text, clips and
/// transforms, in pixels. Two backends: <see cref="D2DCanvas"/> (the graphics card, Direct2D) and
/// <see cref="GdiCanvas"/> (the CPU, GDI+), used when the graphics card can't be used.
/// Push/Pop calls nest (last pushed, first popped), clips and transforms together.
/// </summary>
internal interface ICanvas
{
    void Clear(Color color);
    void FillPolygon(Color color, PointF[] points);
    void DrawPolygon(Color color, float width, PointF[] points, bool dashed = false);
    void DrawLine(Color color, float width, PointF a, PointF b, bool round = false);
    /// <summary>An open polyline with round joins and round caps, or flat ones with <paramref name="flat"/>.</summary>
    void DrawLines(Color color, float width, PointF[] points, bool flat = false);
    void FillEllipse(Color color, RectangleF r);
    void DrawEllipse(Color color, float width, RectangleF r);
    void FillRectangle(Color color, RectangleF r);
    void DrawRectangle(Color color, float width, RectangleF r, bool dashed = false);
    /// <summary>A disk lit from <paramref name="litAt"/> (colour <paramref name="lit"/>) to <paramref name="body"/> at its rim.</summary>
    void FillBall(RectangleF r, PointF litAt, Color lit, Color body);
    void PushClip(PointF[] polygon);
    void PushClipEllipse(RectangleF r);
    void PushClipRect(RectangleF r);
    /// <summary>Applies <paramref name="m"/> before the current transform (as GDI+ MultiplyTransform).</summary>
    void PushTransform(Matrix3x2 m);
    /// <summary>Undoes the last PushClip* or PushTransform.</summary>
    void Pop();
    /// <summary>Text in <paramref name="r"/>, one line, cut at the box's edges.</summary>
    void DrawText(string text, float px, bool bold, Color color, RectangleF r, TextAlign h, TextAlign v);
    SizeF MeasureText(string text, float px, bool bold);
}

/// <summary>The CPU backend: GDI+ on a Graphics (premultiplied DIB of the layered window).</summary>
internal sealed class GdiCanvas : ICanvas
{
    private readonly Graphics _g;
    private readonly Stack<GraphicsState> _states = new();
    private readonly Dictionary<(float, bool), Font> _fonts;

    public GdiCanvas(Graphics g, OverlayQuality quality, Dictionary<(float, bool), Font> fonts)
    {
        _g = g;
        _fonts = fonts;
        g.CompositingMode = CompositingMode.SourceOver;
        switch (quality)
        {
            case OverlayQuality.Fast:
                g.SmoothingMode = SmoothingMode.None;
                g.TextRenderingHint = TextRenderingHint.SingleBitPerPixelGridFit;
                g.CompositingQuality = CompositingQuality.HighSpeed;
                g.PixelOffsetMode = PixelOffsetMode.HighSpeed;
                break;
            case OverlayQuality.Balanced:
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.TextRenderingHint = TextRenderingHint.SingleBitPerPixelGridFit;
                g.CompositingQuality = CompositingQuality.HighSpeed;
                g.PixelOffsetMode = PixelOffsetMode.HighSpeed;
                break;
            default:
                g.SmoothingMode = SmoothingMode.AntiAlias;
                g.TextRenderingHint = TextRenderingHint.AntiAlias;
                g.CompositingQuality = CompositingQuality.HighQuality;
                g.PixelOffsetMode = PixelOffsetMode.Default;
                break;
        }
    }

    public void Clear(Color color) => _g.Clear(color);

    public void FillPolygon(Color color, PointF[] points)
    {
        using var b = new SolidBrush(color);
        _g.FillPolygon(b, points);
    }

    public void DrawPolygon(Color color, float width, PointF[] points, bool dashed = false)
    {
        using var p = new Pen(color, width) { LineJoin = LineJoin.Round };
        if (dashed) p.DashStyle = DashStyle.Dash;
        _g.DrawPolygon(p, points);
    }

    public void DrawLine(Color color, float width, PointF a, PointF b, bool round = false)
    {
        using var p = new Pen(color, width);
        if (round) p.StartCap = p.EndCap = LineCap.Round;
        _g.DrawLine(p, a, b);
    }

    public void DrawLines(Color color, float width, PointF[] points, bool flat = false)
    {
        var cap = flat ? LineCap.Flat : LineCap.Round;
        using var p = new Pen(color, width) { StartCap = cap, EndCap = cap, LineJoin = LineJoin.Round };
        _g.DrawLines(p, points);
    }

    public void FillEllipse(Color color, RectangleF r)
    {
        using var b = new SolidBrush(color);
        _g.FillEllipse(b, r);
    }

    public void DrawEllipse(Color color, float width, RectangleF r)
    {
        using var p = new Pen(color, width);
        _g.DrawEllipse(p, r);
    }

    public void FillRectangle(Color color, RectangleF r)
    {
        using var b = new SolidBrush(color);
        _g.FillRectangle(b, r);
    }

    public void DrawRectangle(Color color, float width, RectangleF r, bool dashed = false)
    {
        using var p = new Pen(color, width);
        if (dashed) p.DashStyle = DashStyle.Dash;
        _g.DrawRectangle(p, r.X, r.Y, r.Width, r.Height);
    }

    public void FillBall(RectangleF r, PointF litAt, Color lit, Color body)
    {
        using var path = new GraphicsPath();
        path.AddEllipse(r);
        using var brush = new PathGradientBrush(path) { CenterPoint = litAt, CenterColor = lit, SurroundColors = [body] };
        _g.FillPath(brush, path);
    }

    public void PushClip(PointF[] polygon)
    {
        _states.Push(_g.Save());
        using var path = new GraphicsPath();
        path.AddPolygon(polygon);
        _g.SetClip(path, CombineMode.Intersect);
    }

    public void PushClipEllipse(RectangleF r)
    {
        _states.Push(_g.Save());
        using var path = new GraphicsPath();
        path.AddEllipse(r);
        _g.SetClip(path, CombineMode.Intersect);
    }

    public void PushClipRect(RectangleF r)
    {
        _states.Push(_g.Save());
        _g.SetClip(r, CombineMode.Intersect);
    }

    public void PushTransform(Matrix3x2 m)
    {
        _states.Push(_g.Save());
        using var gm = new Matrix(m.M11, m.M12, m.M21, m.M22, m.M31, m.M32);
        _g.MultiplyTransform(gm);
    }

    public void Pop()
    {
        if (_states.Count > 0) _g.Restore(_states.Pop());
    }

    private Font FontOf(float px, bool bold)
    {
        px = MathF.Max(1f, MathF.Round(px * 4f) / 4f);
        if (_fonts.TryGetValue((px, bold), out var f)) return f;
        if (_fonts.Count >= 32)
        {
            foreach (var old in _fonts.Values) old.Dispose();
            _fonts.Clear();
        }
        return _fonts[(px, bold)] = new Font(FontFamily.GenericSansSerif, px, bold ? FontStyle.Bold : FontStyle.Regular, GraphicsUnit.Pixel);
    }

    private static StringAlignment Align(TextAlign a) =>
        a == TextAlign.Near ? StringAlignment.Near : a == TextAlign.Far ? StringAlignment.Far : StringAlignment.Center;

    public void DrawText(string text, float px, bool bold, Color color, RectangleF r, TextAlign h, TextAlign v)
    {
        using var fmt = new StringFormat
        {
            Alignment = Align(h),
            LineAlignment = Align(v),
            Trimming = StringTrimming.EllipsisCharacter,
            FormatFlags = StringFormatFlags.NoWrap,
        };
        using var b = new SolidBrush(color);
        _g.DrawString(text, FontOf(px, bold), b, r, fmt);
    }

    public SizeF MeasureText(string text, float px, bool bold) => _g.MeasureString(text, FontOf(px, bold));
}
