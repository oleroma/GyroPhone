using System.Drawing;
using System.Numerics;

namespace GyroPhone.Tray;

/// <summary>
/// A canvas that keeps flat polygons, lines and ellipses as <see cref="Gpu3D.FlatList"/> items for the graphics card to
/// draw in order, so drawing code written for <see cref="ICanvas"/> (the 3D phone's body, its controller handles) can
/// draw through the card unchanged. What it can't keep (text, clips, pictures) throws <see cref="NotSupportedException"/>:
/// the caller then draws on the CPU.
/// </summary>
internal sealed class FlatCanvas : ICanvas
{
    public Gpu3D.FlatList List = new();
    private readonly PointF[] _round = new PointF[32];

    public void Clear(Color color) => List.Clear();

    public void FillPolygon(Color color, PointF[] points) => List.Fill(points, color);

    public void FillQuads(Color color, List<PointF> quads)
    {
        Span<PointF> q = stackalloc PointF[4];
        for (int i = 0; i + 3 < quads.Count; i += 4)
        {
            q[0] = quads[i]; q[1] = quads[i + 1]; q[2] = quads[i + 2]; q[3] = quads[i + 3];
            List.Fill(q, color);
        }
    }

    public void DrawPolygon(Color color, float width, PointF[] points, bool dashed = false)
    {
        if (dashed) throw new NotSupportedException("dashed");
        List.Outline(points, color, width);
    }

    public void DrawLine(Color color, float width, PointF a, PointF b, bool round = false) => List.Line(a, b, color, width);

    public void DrawLines(Color color, float width, PointF[] points, bool flat = false)
    {
        for (int i = 1; i < points.Length; i++) List.Line(points[i - 1], points[i], color, width);
    }

    public void DrawLinesSet(Color color, float width, PointF[][] lines, long stamp)
    {
        foreach (var l in lines) DrawLines(color, width, l);
    }

    private PointF[] Ellipse(RectangleF r)
    {
        float cx = r.X + r.Width / 2f, cy = r.Y + r.Height / 2f;
        for (int i = 0; i < _round.Length; i++)
        {
            float a = i * 2f * MathF.PI / _round.Length;
            _round[i] = new PointF(cx + MathF.Cos(a) * r.Width / 2f, cy + MathF.Sin(a) * r.Height / 2f);
        }
        return _round;
    }

    public void FillEllipse(Color color, RectangleF r) => List.Fill(Ellipse(r), color);

    public void DrawEllipse(Color color, float width, RectangleF r) => List.Outline(Ellipse(r), color, width);

    public void FillRectangle(Color color, RectangleF r) =>
        List.Fill([new PointF(r.Left, r.Top), new PointF(r.Right, r.Top), new PointF(r.Right, r.Bottom), new PointF(r.Left, r.Bottom)], color);

    public void DrawRectangle(Color color, float width, RectangleF r, bool dashed = false) =>
        DrawPolygon(color, width, [new PointF(r.Left, r.Top), new PointF(r.Right, r.Top), new PointF(r.Right, r.Bottom), new PointF(r.Left, r.Bottom)], dashed);

    public void FillBall(RectangleF r, PointF litAt, Color lit, Color body) => throw new NotSupportedException("ball");

    public void DrawPixels(RectangleF dest, int w, int h, int[] pixels, long stamp) => throw new NotSupportedException("pixels");

    public void PushClip(PointF[] polygon) => throw new NotSupportedException("clip");

    public void PushClipEllipse(RectangleF r) => throw new NotSupportedException("clip");

    public void PushClipRect(RectangleF r) => throw new NotSupportedException("clip");

    public void PushTransform(Matrix3x2 m) => throw new NotSupportedException("transform");

    public void Pop() => throw new NotSupportedException("pop");

    public void DrawText(string text, float px, bool bold, Color color, RectangleF r, TextAlign h, TextAlign v) => throw new NotSupportedException("text");

    public SizeF MeasureText(string text, float px, bool bold) => throw new NotSupportedException("text");
}
