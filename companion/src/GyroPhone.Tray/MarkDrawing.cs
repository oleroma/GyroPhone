using System.Drawing;
using System.Numerics;
using GyroPhone.Protocol;

namespace GyroPhone.Tray;

/// <summary>
/// The pad's button frames and bars on the phone overlay's screen, drawn as the pad draws them (the phone's
/// ValueBars): a frame of lines (mappings active now, Toggles on in green or off in grey, sliders' values) on a
/// dark rounded box, in the button's middle or outside it by its top-right corner; a sliders icon in the top-left
/// corner of a button with a Toggle value chip; and each Position bar across its button, with its scale. Sizes
/// come from the phone in pad pixels (its text size and dp), so they match the pad's.
/// </summary>
internal static class MarkDrawing
{
    private static readonly Color FrameBack = Color.FromArgb(unchecked((int)0xEB141418));
    private static readonly Color FrameEdge = Color.FromArgb(unchecked((int)0xFF666666));
    private static readonly Color PlainText = Color.White;
    private static readonly Color ToggleOn = Color.FromArgb(unchecked((int)0xFF8FE39A));
    private static readonly Color ToggleOff = Color.FromArgb(unchecked((int)0xFF9E9E9E));
    private static readonly Color Tick = Color.FromArgb(191, 255, 255, 255);
    private static readonly Color IconBack = Color.FromArgb(unchecked((int)0xB0141418));

    /// <summary>The padding round the frame's text, its corners, the gap to its button (dp), as on the pad.</summary>
    private const float PadDp = 6f, CornerDp = 8f, GapDp = 4f;
    /// <summary>A button's border width (dp): the bars stay inside it.</summary>
    private const float BorderDp = 2f;
    /// <summary>Lines on one row are this far apart.</summary>
    private const string Row = "   ";
    private const float LineDp = 3f, TickStrokeDp = 2f, MinorDp = 8f, MajorDp = 16f, MinTickDp = 6f;

    /// <summary>
    /// <paramref name="mk"/>'s frame by its button <paramref name="box"/> (pad pixels): in its middle when it fits
    /// there (<see cref="OverlayMark.Inside"/>), else outside by its top-right corner; the touchpad's
    /// (<paramref name="framed"/> false) inside its top-right corner.
    /// </summary>
    public static void Frame(ICanvas g, OverlayMark mk, OverlayMarks ms, RectangleF box, bool framed, float pxW, float pxH, Func<float, float, PointF> project)
    {
        if (mk.Lines.Count == 0 || ms.TextPx <= 0f) return;
        float dp = ms.Dp, fpx = ms.TextPx, pad = PadDp * dp;
        // The lines' sizes: stacked, or side by side on one row.
        Span<SizeF> sizes = mk.Lines.Count <= 16 ? stackalloc SizeF[mk.Lines.Count] : new SizeF[mk.Lines.Count];
        float tw = 0f, th = 0f;
        float rowGap = mk.Row ? g.MeasureText(Row, fpx, false).Width : 0f;
        for (int i = 0; i < mk.Lines.Count; i++)
        {
            var sz = sizes[i] = g.MeasureText(mk.Lines[i].Text, fpx, false);
            if (mk.Row)
            {
                tw += sz.Width + (i > 0 ? rowGap : 0f);
                th = Math.Max(th, sz.Height);
            }
            else
            {
                tw = Math.Max(tw, sz.Width);
                th += sz.Height;
            }
        }
        float bw = tw + 2 * pad, bh = th + 2 * pad;
        var (bx, by) = !framed ? (Math.Max(0f, box.Right - bw - GapDp * dp), box.Top + GapDp * dp)
            : mk.Inside ? (box.X + (box.Width - bw) / 2, box.Y + (box.Height - bh) / 2)
            : Outside(box, bw, bh, pxW, pxH, dp);
        // Drawn flat round its middle, under the projection's transform there.
        float cx = bx + bw / 2, cy = by + bh / 2;
        if (OverlayRenderer.PadAffine(cx / pxW, cy / pxH, pxW, pxH, project) is not { } m) return;
        if (MathF.Abs(m.GetDeterminant()) * fpx * fpx < 6f) return; // edge on: too thin to read
        g.PushTransform(m);
        var shape = Rounded(-bw / 2, -bh / 2, bw, bh, Math.Min(CornerDp * dp, Math.Min(bw, bh) / 2));
        g.FillPolygon(FrameBack, shape);
        g.DrawPolygon(FrameEdge, dp, shape);
        float x = -bw / 2 + pad, y = -bh / 2 + pad;
        for (int i = 0; i < mk.Lines.Count; i++)
        {
            var l = mk.Lines[i];
            var color = l.Color switch { OverlayFrameLine.On => ToggleOn, OverlayFrameLine.Off => ToggleOff, _ => PlainText };
            // A little wider than measured, so the text never wraps.
            g.DrawText(l.Text, fpx, false, color, new RectangleF(x, y, sizes[i].Width + fpx, sizes[i].Height), TextAlign.Near, TextAlign.Near);
            if (mk.Row) x += sizes[i].Width + rowGap;
            else y += sizes[i].Height;
        }
        g.Pop();
    }

    /// <summary>
    /// What lies over button <paramref name="mk"/> (its <paramref name="box"/> in pad pixels): its bars, cut by its
    /// <paramref name="outline"/> (the projected button; null for the touchpad, the whole screen), its sliders icon,
    /// and its frame when that sits inside it.
    /// </summary>
    public static void Over(ICanvas g, OverlayMark mk, OverlayMarks ms, RectangleF box, bool framed, float pxW, float pxH,
        Func<float, float, PointF> project, PointF[]? outline)
    {
        if (box.Width <= 0f || box.Height <= 0f) return;
        float dp = ms.Dp;
        if (mk.Bars.Count > 0)
        {
            var color = Color.FromArgb(ms.BarColor);
            // Inside the button's border.
            float inset = framed ? BorderDp * dp : 0f;
            var inner = RectangleF.Inflate(box, -inset, -inset);
            if (outline != null) g.PushClip(outline);
            foreach (var b in mk.Bars) Bar(g, inner, b, color, dp, pxW, pxH, project);
            if (outline != null) g.Pop();
        }
        if (mk.Set) Icon(g, box.X + 6 * dp, box.Y + 6 * dp, Color.FromArgb(ms.BarColor), dp, pxW, pxH, project);
        if (mk.Inside || !framed) Frame(g, mk, ms, box, framed, pxW, pxH, project);
    }

    /// <summary>Where a frame <paramref name="bw"/> × <paramref name="bh"/> goes outside its button (the pad's framePlace).</summary>
    private static (float X, float Y) Outside(RectangleF box, float bw, float bh, float sw, float sh, float dp)
    {
        float gap = GapDp * dp;
        float x, y;
        if (box.Width >= box.Height)
        {
            // A wide (or square) button: above it, lined up with its right edge (below near the top edge).
            y = box.Top - gap - bh;
            if (y < 0f) y = box.Bottom + gap;
            x = box.Right - bw;
        }
        else
        {
            // A tall one: beside it on the right, lined up with its top (on the left near the screen's right edge).
            x = box.Right + gap;
            if (x + bw > sw) x = box.Left - gap - bw;
            y = box.Top;
        }
        return (Math.Clamp(x, 0f, Math.Max(0f, sw - bw)), Math.Clamp(y, 0f, Math.Max(0f, sh - bh)));
    }

    /// <summary>A Position bar on <paramref name="r"/> (pad pixels) at its value: the part from 0 lightly filled, its scale, a line at the value.</summary>
    private static void Bar(ICanvas g, RectangleF r, OverlayBar b, Color c, float dp, float pxW, float pxH, Func<float, float, PointF> project)
    {
        float w = r.Width, h = r.Height, v = Math.Clamp(b.Value, 0f, 1f);
        if (w <= 0f || h <= 0f) return;
        PointF Q(float x, float y) => project(x / pxW, y / pxH);
        // Pad pixels to the picture's, round the button's middle (for line widths).
        float scale = OverlayRenderer.PadAffine((r.X + w / 2) / pxW, (r.Y + h / 2) / pxH, pxW, pxH, project) is { } m
            ? MathF.Sqrt(MathF.Abs(m.GetDeterminant())) : 1f;
        var fill = Color.FromArgb((int)(c.A * 0.18f), c);
        float line = LineDp * dp * scale, stroke = TickStrokeDp * dp * scale;
        float Len(bool major) => (major ? MajorDp : MinorDp) * dp;
        var ticks = Ticks(b.Steps, b.Axis, w, h, dp);
        switch (b.Axis)
        {
            case 0: // X
            {
                float x = r.Left + v * w;
                g.FillPolygon(fill, [Q(r.Left, r.Top), Q(x, r.Top), Q(x, r.Bottom), Q(r.Left, r.Bottom)]);
                foreach (var (p, major) in ticks)
                {
                    float tx = r.Left + p * w;
                    g.DrawLine(Tick, stroke, Q(tx, r.Bottom), Q(tx, r.Bottom - Len(major)), round: true);
                }
                g.DrawLine(c, line, Q(x, r.Top), Q(x, r.Bottom));
                break;
            }
            case 1: // Y
            {
                float y = r.Bottom - v * h;
                g.FillPolygon(fill, [Q(r.Left, y), Q(r.Right, y), Q(r.Right, r.Bottom), Q(r.Left, r.Bottom)]);
                foreach (var (p, major) in ticks)
                {
                    float ty = r.Bottom - p * h;
                    g.DrawLine(Tick, stroke, Q(r.Left, ty), Q(r.Left + Len(major), ty), round: true);
                }
                g.DrawLine(c, line, Q(r.Left, y), Q(r.Right, y));
                break;
            }
            case 2: // distance from the middle
            {
                float cx = r.X + w / 2, cy = r.Y + h / 2, full = Math.Min(w, h) / 2;
                if (v > 0f) g.FillPolygon(fill, Circle(cx, cy, full * v, 48, Q));
                foreach (var (p, major) in ticks)
                    g.DrawPolygon(Color.FromArgb(major ? 128 : 56, 255, 255, 255), stroke, Circle(cx, cy, full * p, 48, Q));
                if (v > 0f) g.DrawPolygon(c, line, Circle(cx, cy, full * v, 48, Q));
                break;
            }
            default: // angle, clockwise from the top
            {
                float cx = r.X + w / 2, cy = r.Y + h / 2, rad = Math.Min(w, h) / 2;
                float sweep = v * 2 * MathF.PI;
                int n = Math.Max(2, (int)MathF.Ceiling(48 * v)) + 1;
                if (v > 0f)
                {
                    var pie = new PointF[n + 1];
                    pie[0] = Q(cx, cy);
                    for (int i = 0; i < n; i++)
                    {
                        float a = -MathF.PI / 2 + sweep * i / (n - 1);
                        pie[i + 1] = Q(cx + MathF.Cos(a) * rad, cy + MathF.Sin(a) * rad);
                    }
                    g.FillPolygon(fill, pie);
                }
                // The top (0 and 100 %) always has the long notch.
                foreach (var (p, major) in ticks.Append((0f, true)))
                {
                    float a = p * 2 * MathF.PI - MathF.PI / 2, l = Len(major);
                    g.DrawLine(Tick, stroke, Q(cx + MathF.Cos(a) * rad, cy + MathF.Sin(a) * rad),
                        Q(cx + MathF.Cos(a) * (rad - l), cy + MathF.Sin(a) * (rad - l)), round: true);
                }
                float at = sweep - MathF.PI / 2;
                g.DrawLine(c, line, Q(cx, cy), Q(cx + MathF.Cos(at) * rad, cy + MathF.Sin(at) * rad), round: true);
                break;
            }
        }
    }

    /// <summary>
    /// The scale's ticks (place 0..1, long): with a Snap of <paramref name="steps"/> one per step (every 2nd, 5th,
    /// 10th… when closer than <see cref="MinTickDp"/>; every 5th shown one long), else every 10 % (long at 50 %).
    /// </summary>
    internal static List<(float P, bool Major)> Ticks(int steps, int axis, float w, float h, float dp)
    {
        var list = new List<(float, bool)>();
        if (steps > 0)
        {
            float length = axis switch { 0 => w, 1 => h, 2 => Math.Min(w, h) / 2, _ => Math.Min(w, h) * MathF.PI };
            float spacing = length / steps;
            if (spacing > 0f)
            {
                float min = MinTickDp * dp;
                int every = 1, k = 0;
                int[] thin = [1, 2, 5];
                while (spacing * every < min && every < steps)
                {
                    k++;
                    every = thin[k % 3] * (int)Math.Pow(10, k / 3);
                }
                // The ends are the button's edges: no tick there.
                for (int i = 1; i < steps; i++)
                    if (i % every == 0) list.Add(((float)i / steps, (i / every) % 5 == 0));
                return list;
            }
        }
        for (int i = 1; i <= 9; i++) list.Add((i / 10f, i == 5));
        return list;
    }

    /// <summary>The sliders icon (three lines with knobs) with its top-left at (x, y) in pad pixels.</summary>
    private static void Icon(ICanvas g, float x, float y, Color c, float dp, float pxW, float pxH, Func<float, float, PointF> project)
    {
        float s = 14 * dp;
        if (OverlayRenderer.PadAffine((x + s / 2) / pxW, (y + s / 2) / pxH, pxW, pxH, project) is not { } m) return;
        g.PushTransform(m);
        // Drawn round its middle.
        float o = -s / 2;
        g.FillPolygon(IconBack, Rounded(o - 3 * dp, o - 3 * dp, s + 6 * dp, s + 6 * dp, 5 * dp));
        ReadOnlySpan<float> knobs = [0.3f, 0.7f, 0.45f];
        for (int i = 0; i < 3; i++)
        {
            float ly = o + s * (i + 0.5f) / 3f;
            g.DrawLine(Color.White, 1.6f * dp, new PointF(o, ly), new PointF(o + s, ly), round: true);
            float kr = 2.4f * dp;
            g.FillEllipse(c, new RectangleF(o + s * knobs[i] - kr, ly - kr, 2 * kr, 2 * kr));
        }
        g.Pop();
    }

    /// <summary>A rounded rectangle at (x, y), <paramref name="w"/> × <paramref name="h"/>, corners <paramref name="r"/>.</summary>
    internal static PointF[] Rounded(float x, float y, float w, float h, float r)
    {
        var pts = OverlayRenderer.RoundedRect(w, h, Math.Max(0f, r), 4);
        var o = new PointF[pts.Length];
        for (int i = 0; i < pts.Length; i++) o[i] = new PointF(x + w / 2 + pts[i].X, y + h / 2 + pts[i].Y);
        return o;
    }

    /// <summary>A circle round (cx, cy) of radius <paramref name="r"/> pad pixels, projected.</summary>
    private static PointF[] Circle(float cx, float cy, float r, int n, Func<float, float, PointF> q)
    {
        var pts = new PointF[n];
        for (int i = 0; i < n; i++)
        {
            float a = i * 2 * MathF.PI / n;
            pts[i] = q(cx + r * MathF.Cos(a), cy + r * MathF.Sin(a));
        }
        return pts;
    }
}
