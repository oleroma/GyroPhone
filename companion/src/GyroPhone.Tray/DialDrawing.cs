using System.Drawing;
using System.Drawing.Drawing2D;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The flat overlays (the phone's displays, ported): the attitude indicator (a horizon that rolls and
/// pitches, with a heading tape) and the bubble disk (a card that spins with the heading, a dot
/// showing the tilt). <c>m</c> is the levelled pose, <c>raw</c> the pose before levelling (the heading).
/// </summary>
internal static class DialDrawing
{
    private const float Tape = 0.3f;
    private const float TapeGap = 0.12f;

    private static float HeadingDeg(float[] raw) =>
        raw[0] * raw[0] + raw[6] * raw[6] > 0.04f ? MathF.Atan2(raw[6], raw[0]) * 180f / MathF.PI : 0f;

    private static Color Col(OverlaySettings s, string key) => Color.FromArgb(s.Color(key));

    public static void DrawHorizon(Graphics g, RectangleF area, OverlaySettings s, float[] m, float[] raw)
    {
        // The tape on top, the dial under it: fit both in the box.
        float r = Math.Min(area.Width / 2f, area.Height / (2f + Tape + TapeGap)) * 0.97f;
        float tape = r * Tape, gap = r * TapeGap;
        var c = new PointF(area.X + area.Width / 2f, area.Y + area.Height / 2f + (tape + gap) / 2);
        float roll = MathF.Atan2(m[3], m[4]) * 180f / MathF.PI;
        float pitch = MathF.Asin(Math.Clamp(-m[5], -1f, 1f)) * 180f / MathF.PI;
        float k = r / 40f; // px per degree of pitch
        float px = Math.Max(1f, r / 80f);
        using (var clip = new GraphicsPath())
        {
            clip.AddEllipse(c.X - r, c.Y - r, 2 * r, 2 * r);
            var saved = g.Save();
            g.SetClip(clip, CombineMode.Intersect);
            using var turn = new Matrix();
            turn.RotateAt(roll, c);
            g.MultiplyTransform(turn);
            float hy = c.Y + pitch * k;
            using (var sky = new SolidBrush(Col(s, "sky"))) g.FillRectangle(sky, c.X - 2 * r, hy - 4 * r, 4 * r, 4 * r);
            using (var ground = new SolidBrush(Col(s, "ground"))) g.FillRectangle(ground, c.X - 2 * r, hy, 4 * r, 4 * r);
            using (var line = new Pen(Col(s, "line"), 1.5f * px)) g.DrawLine(line, c.X - 2 * r, hy, c.X + 2 * r, hy);
            using (var ladder = new Pen(Col(s, "ladder"), px))
                for (int a = -60; a <= 60; a += 10)
                {
                    if (a == 0) continue;
                    float y = hy - a * k, w = a % 20 == 0 ? r * 0.4f : r * 0.22f;
                    g.DrawLine(ladder, c.X - w, y, c.X + w, y);
                }
            g.Restore(saved);
        }
        using (var rim = new Pen(Col(s, "rim"), 1.5f * px)) g.DrawEllipse(rim, c.X - r, c.Y - r, 2 * r, 2 * r);
        var symColor = Col(s, "aircraft");
        using (var sym = new Pen(symColor, 2.5f * px) { StartCap = LineCap.Round, EndCap = LineCap.Round })
        {
            g.DrawLine(sym, c.X - r * 0.55f, c.Y, c.X - r * 0.18f, c.Y);
            g.DrawLine(sym, c.X + r * 0.18f, c.Y, c.X + r * 0.55f, c.Y);
        }
        using (var dot = new SolidBrush(symColor)) g.FillEllipse(dot, c.X - 2.5f * px, c.Y - 2.5f * px, 5 * px, 5 * px);
        // Heading tape: ticks every 10°, numbers every 30°, the heading under the middle mark.
        var t = new RectangleF(c.X - r, c.Y - r - gap - tape, 2 * r, tape);
        using (var tb = new SolidBrush(Col(s, "tape"))) g.FillRectangle(tb, t);
        float heading = (HeadingDeg(raw) + 360f) % 360f;
        float pxPerDeg = r / 45f;
        var numbers = Col(s, "text");
        var state = g.Save();
        g.SetClip(t, CombineMode.Intersect);
        using (var tick = new Pen(Color.FromArgb((int)(numbers.A * 0.8f), numbers), px))
        using (var font = new Font(FontFamily.GenericSansSerif, Math.Max(6f, tape * 0.48f), FontStyle.Regular, GraphicsUnit.Pixel))
        using (var nb = new SolidBrush(numbers))
        {
            var fmt = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            for (int deg = (int)MathF.Round(heading / 10f) * 10 - 50; deg <= heading + 50; deg += 10)
            {
                float x = c.X + (deg - heading) * pxPerDeg;
                bool big = deg % 30 == 0;
                g.DrawLine(tick, x, t.Bottom, x, t.Bottom - tape * (big ? 0.35f : 0.2f));
                if (big) g.DrawString($"{((deg % 360) + 360) % 360}", font, nb, x, t.Top + tape * 0.4f, fmt);
            }
        }
        g.Restore(state);
        using (var mark = new Pen(symColor, 2 * px)) g.DrawLine(mark, c.X, t.Top, c.X, t.Bottom);
    }

    public static void DrawDisk(Graphics g, RectangleF area, OverlaySettings s, float[] m, float[] raw)
    {
        float r = Math.Min(area.Width, area.Height) / 2f * 0.95f;
        var c = new PointF(area.X + area.Width / 2f, area.Y + area.Height / 2f);
        float px = Math.Max(1f, r / 80f);
        using (var face = new SolidBrush(Col(s, "face"))) g.FillEllipse(face, c.X - r, c.Y - r, 2 * r, 2 * r);
        using (var rim = new Pen(Col(s, "rim"), 1.5f * px)) g.DrawEllipse(rim, c.X - r, c.Y - r, 2 * r, 2 * r);
        // The card spins against the heading, as a compass card does: the 12 o'clock mark stays put in the world.
        var state = g.Save();
        using (var turn = new Matrix())
        {
            turn.RotateAt(-HeadingDeg(raw), c);
            g.MultiplyTransform(turn);
        }
        var marks = Col(s, "marks");
        for (int i = 0; i < 12; i++)
        {
            double a = (i * 30.0 - 90.0) * Math.PI / 180;
            float inner = i % 3 == 0 ? 0.74f : 0.84f, ca = (float)Math.Cos(a), sa = (float)Math.Sin(a);
            using var pen = new Pen(i == 0 ? Col(s, "north") : marks, (i == 0 ? 3.5f : i % 3 == 0 ? 2.5f : 1.5f) * px)
            {
                StartCap = LineCap.Round,
                EndCap = LineCap.Round,
            };
            g.DrawLine(pen, c.X + ca * r * inner, c.Y + sa * r * inner, c.X + ca * r * 0.95f, c.Y + sa * r * 0.95f);
        }
        if (s.Crosshair)
        {
            using var cross = new Pen(Col(s, "cross"), px);
            g.DrawLine(cross, c.X - r * 0.66f, c.Y, c.X + r * 0.66f, c.Y);
            g.DrawLine(cross, c.X, c.Y - r * 0.66f, c.X, c.Y + r * 0.66f);
            g.DrawEllipse(cross, c.X - r * 0.33f, c.Y - r * 0.33f, r * 0.66f, r * 0.66f);
        }
        g.Restore(state);
        // The dot: where the screen points, off centre by the tilt, on the rim at the range.
        float nx = m[2], ny = m[5];
        float flat = MathF.Sqrt(nx * nx + ny * ny);
        float tilt = MathF.Acos(Math.Clamp(m[8], -1f, 1f)) * 180f / MathF.PI;
        float off = Math.Min(tilt / Math.Max(s.DiskRange, 5), 1f) * r * 0.86f;
        var p = flat > 1e-4f ? new PointF(c.X + nx / flat * off, c.Y - ny / flat * off) : c;
        var dotColor = Col(s, "dot");
        float d = r * 0.09f;
        using (var dot = new SolidBrush(dotColor)) g.FillEllipse(dot, p.X - d, p.Y - d, 2 * d, 2 * d);
        using (var ring = new Pen(Color.FromArgb((int)(0.4f * dotColor.A), 0, 0, 0), px)) g.DrawEllipse(ring, p.X - d, p.Y - d, 2 * d, 2 * d);
    }
}
