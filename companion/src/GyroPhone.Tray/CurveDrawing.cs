using System.Drawing;
using GyroPhone.Protocol;

namespace GyroPhone.Tray;

/// <summary>
/// A curve frame's graph on the phone overlay's screen, drawn as the pad draws it (the phone's CurveFrameGraph):
/// a grid with each multiplier line labelled just under it (what it gives at the sensitivity in effect, the unit only
/// on 1×), the curve as the gyro uses it (Smooth included), its points (hollow while locked; filled, with a border
/// round the frame, while unlocked), a tooltip on a point being dragged, the live speed as a yellow line with what it
/// gives at the top right, and the highest speed reached as a dimmer dashed one. Grayed while the frame's curve isn't
/// the one in effect. Sizes in pad pixels (the phone's dp), flat round the frame's middle under the projection there.
/// </summary>
internal static class CurveDrawing
{
    private const float SideDp = 12f, BandDp = 16f, TextDp = 10f;
    private static readonly Color Curve = Color.FromArgb(0xFF, 0x7C, 0xC4, 0xFF);
    private static readonly Color Live = Color.FromArgb(0xFF, 0xFF, 0xC8, 0x57);

    /// <summary>
    /// Draws <paramref name="c"/> over its control's <paramref name="box"/> (pad pixels); <paramref name="speeds"/> and
    /// <paramref name="peaks"/> the turning speeds now and the highest (yaw, pitch, roll, the whole phone; negative: none).
    /// </summary>
    public static void Draw(ICanvas g, OverlayCurve c, RectangleF box, float dp, float pxW, float pxH,
        Func<float, float, PointF> project, float[] speeds, float[] peaks, float opacity)
    {
        if (dp <= 0f || box.Width <= 0f || box.Height <= 0f) return;
        float tpx = TextDp * dp;
        float cx = box.X + box.Width / 2, cy = box.Y + box.Height / 2;
        if (OverlayRenderer.PadAffine(cx / pxW, cy / pxH, pxW, pxH, project) is not { } m) return;
        if (MathF.Abs(m.GetDeterminant()) * tpx * tpx < 6f) return; // edge on: too thin to read
        float w = box.Width, h = box.Height;
        float l = -w / 2 + SideDp * dp, r = w / 2 - SideDp * dp, t = -h / 2 + BandDp * dp, b = h / 2 - BandDp * dp;
        if (r <= l || b <= t) return;
        float maxS = Math.Max(1f, c.MaxSpeed), maxG = Math.Max(1.01f, c.MaxGain);
        float a = Math.Clamp(opacity, 0.5f, 1f) * (c.InEffect ? 1f : 0.35f);
        Color A(Color col, float f = 1f) => Color.FromArgb((int)Math.Clamp(col.A * a * f, 0f, 255f), col);
        float X(float s) => l + Math.Clamp(s, 0f, maxS) / maxS * (r - l);
        float Y(float gain) => t + (1f - (gain - 1f) / (maxG - 1f)) * (b - t);
        string Mult(float gain) => gain.ToString("0.##", System.Globalization.CultureInfo.InvariantCulture) + "×";
        string Value(float gain) => (c.OneX * gain).ToString(c.Rws ? "0.00" : "0.0", System.Globalization.CultureInfo.InvariantCulture);
        string Label(float gain) => c.ShowValues ? Mult(gain) + " " + Value(gain) : Mult(gain);
        var text = Color.FromArgb(204, 255, 255, 255);

        g.PushTransform(m);
        // The grid: across every 50 or 100 deg/s, up every multiplier step.
        float stepX = maxS > 400f ? 100f : 50f;
        for (float sx = stepX; sx < maxS; sx += stepX) g.DrawLine(A(Color.White, 0.10f), 1f, new PointF(X(sx), t), new PointF(X(sx), b));
        float span = maxG - 1f;
        float step = span <= 1f ? 0.25f : span <= 2.5f ? 0.5f : span <= 5f ? 1f : 2f;
        for (float gy = 1f + step; gy < maxG + 0.001f; gy += step)
            g.DrawPolygon(A(Color.White, 0.18f), 1f, [new PointF(l, Y(gy)), new PointF(r, Y(gy))], dashed: true);
        g.DrawLine(A(Color.White, 0.5f), 2f, new PointF(l, b), new PointF(r, b));

        // Labels: each just under its line; 1× in the band under the graph, with the unit; every second one (or more)
        // where lines are too close for theirs.
        float gap = (b - t) * step / span;
        int every = Math.Max(1, (int)MathF.Ceiling((tpx * 1.3f + 3f * dp) / gap));
        string one = c.ShowValues ? Label(1f) + (c.Rws ? " RWS" : "") : Mult(1f);
        float oneW = g.MeasureText(one, tpx, false).Width;
        g.DrawText(one, tpx, false, A(text), new RectangleF(l, b + 2f * dp, oneW + tpx, tpx * 1.4f), TextAlign.Near, TextAlign.Near);
        string end = maxS.ToString("0", System.Globalization.CultureInfo.InvariantCulture) + "°/s";
        float endW = g.MeasureText(end, tpx, false).Width;
        if (l + oneW + 4f * dp < r - endW)
            g.DrawText(end, tpx, false, A(text), new RectangleF(r - endW - tpx, b + 2f * dp, endW + tpx, tpx * 1.4f), TextAlign.Far, TextAlign.Near);
        for (int k = every; 1f + k * step < maxG + 0.001f; k += every)
        {
            float ly = 1f + k * step;
            string s = Label(ly);
            g.DrawText(s, tpx, false, A(text), new RectangleF(l + 2f * dp, Y(ly) + 2f * dp, g.MeasureText(s, tpx, false).Width + tpx, tpx * 1.4f), TextAlign.Near, TextAlign.Near);
        }

        // The curve, as the gyro works it out.
        int n = c.Points.Length;
        var flat = new float[Math.Max(2, n * 2)];
        for (int i = 0; i < n; i++) { flat[2 * i] = c.Points[i].Speed; flat[2 * i + 1] = c.Points[i].Gain; }
        if (n > 0)
        {
            int steps = Math.Clamp((int)((r - l) / 3f), 2, 400);
            var line = new PointF[steps + 1];
            for (int i = 0; i <= steps; i++)
            {
                float s = maxS * i / steps;
                line[i] = new PointF(X(s), Y(Math.Clamp(GyroMath.Gain(flat, n, s, c.Smooth), 1f, maxG)));
            }
            g.DrawLines(A(Curve), 4f, line);
        }
        // The highest speed reached, then the live one.
        int axis = c.AxisSpeed && c.Axis is >= 0 and < 3 ? c.Axis : 3;
        float pk = peaks[axis];
        if (pk > 0f)
        {
            float px = X(pk);
            g.DrawPolygon(A(Live, 0.4f), 2f, [new PointF(px, t), new PointF(px, b)], dashed: true);
            string ms = "max " + pk.ToString("0", System.Globalization.CultureInfo.InvariantCulture) + "°/s";
            float mw = g.MeasureText(ms, tpx, false).Width;
            float mx = px - mw - 3f * dp < l ? px + 3f * dp : px - mw - 3f * dp;
            g.DrawText(ms, tpx, false, Color.FromArgb((int)(115 * a), 255, 255, 255), new RectangleF(Math.Min(mx, r - mw), b - tpx * 1.4f - 2f * dp, mw + tpx, tpx * 1.4f), TextAlign.Near, TextAlign.Near);
        }
        float v = speeds[axis];
        float nowW = 0f;
        if (v >= 0f)
        {
            float gain = n == 0 ? 1f : GyroMath.Gain(flat, n, v, c.Smooth);
            float lx = X(v);
            g.DrawLine(A(Live), 2f, new PointF(lx, t), new PointF(lx, b));
            g.FillEllipse(A(Live), new RectangleF(lx - 7f, Y(Math.Min(gain, maxG)) - 7f, 14f, 14f));
            string now = v.ToString("0", System.Globalization.CultureInfo.InvariantCulture) + "°/s " + Label(gain);
            nowW = g.MeasureText(now, tpx, false).Width;
            g.DrawText(now, tpx, false, A(text), new RectangleF(r - nowW - tpx, t - tpx * 1.4f - 1f * dp, nowW + tpx, tpx * 1.4f), TextAlign.Far, TextAlign.Near);
        }
        // Its points: hollow while locked, filled while unlocked; unlocked, a border round the frame too.
        for (int i = 0; i < n; i++)
        {
            var o = new PointF(X(c.Points[i].Speed), Y(c.Points[i].Gain));
            if (c.Unlocked) g.FillEllipse(A(Curve), new RectangleF(o.X - 9f, o.Y - 9f, 18f, 18f));
            else g.DrawEllipse(A(Curve), 2.5f, new RectangleF(o.X - 7f, o.Y - 7f, 14f, 14f));
        }
        if (c.Unlocked)
        {
            float bw = 1.5f * dp;
            g.DrawPolygon(A(Curve), 2 * bw, MarkDrawing.Rounded(-w / 2 + bw, -h / 2 + bw, w - 2 * bw, h - 2 * bw, Math.Max(0f, Math.Min(w, h) * 0.12f - bw)));
        }
        // A point being dragged: a ring and a small tooltip with where it lands, kept inside the frame.
        if (c.Drag >= 0 && c.Drag < n)
        {
            var q = c.Points[c.Drag];
            var o = new PointF(X(q.Speed), Y(q.Gain));
            g.DrawEllipse(Color.White, 3f, new RectangleF(o.X - 12f, o.Y - 12f, 24f, 24f));
            string tip = q.Speed.ToString("0", System.Globalization.CultureInfo.InvariantCulture) + "°/s · " + Label(q.Gain);
            float pad = 4f * dp;
            float tw = g.MeasureText(tip, tpx, false).Width + 2 * pad, th = tpx * 1.3f + 2 * pad;
            float tl = Math.Clamp(o.X - tw / 2, -w / 2, Math.Max(-w / 2, w / 2 - tw));
            float above = o.Y - 18f * dp - th;
            float tt = above >= -h / 2 ? above : Math.Min(o.Y + 18f * dp, h / 2 - th);
            g.FillPolygon(Color.FromArgb(0xE6, 0x20, 0x24, 0x28), MarkDrawing.Rounded(tl, tt, tw, th, 6f * dp));
            g.DrawText(tip, tpx, false, Color.White, new RectangleF(tl + pad, tt + pad, tw, tpx * 1.3f), TextAlign.Near, TextAlign.Near);
        }
        g.Pop();
    }
}
