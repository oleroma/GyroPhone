using System.Drawing;
using System.Numerics;
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

    private static RectangleF Box(PointF c, float r) => new(c.X - r, c.Y - r, 2 * r, 2 * r);

    public static void DrawHorizon(ICanvas g, RectangleF area, OverlaySettings s, float[] m, float[] raw)
    {
        // The tape on top, the dial under it: fit both in the box.
        float r = Math.Min(area.Width / 2f, area.Height / (2f + Tape + TapeGap)) * 0.97f;
        float tape = r * Tape, gap = r * TapeGap;
        var c = new PointF(area.X + area.Width / 2f, area.Y + area.Height / 2f + (tape + gap) / 2);
        float roll = MathF.Atan2(m[3], m[4]) * 180f / MathF.PI;
        float pitch = MathF.Asin(Math.Clamp(-m[5], -1f, 1f)) * 180f / MathF.PI;
        float k = r / 40f; // px per degree of pitch
        float px = Math.Max(1f, r / 80f);
        g.PushClipEllipse(Box(c, r));
        g.PushTransform(Matrix3x2.CreateRotation(roll * MathF.PI / 180f, new Vector2(c.X, c.Y)));
        float hy = c.Y + pitch * k;
        g.FillRectangle(Col(s, "sky"), new RectangleF(c.X - 2 * r, hy - 4 * r, 4 * r, 4 * r));
        g.FillRectangle(Col(s, "ground"), new RectangleF(c.X - 2 * r, hy, 4 * r, 4 * r));
        g.DrawLine(Col(s, "line"), 1.5f * px, new PointF(c.X - 2 * r, hy), new PointF(c.X + 2 * r, hy));
        var ladder = Col(s, "ladder");
        for (int a = -60; a <= 60; a += 10)
        {
            if (a == 0) continue;
            float y = hy - a * k, w = a % 20 == 0 ? r * 0.4f : r * 0.22f;
            g.DrawLine(ladder, px, new PointF(c.X - w, y), new PointF(c.X + w, y));
        }
        g.Pop();
        g.Pop();
        g.DrawEllipse(Col(s, "rim"), 1.5f * px, Box(c, r));
        var symColor = Col(s, "aircraft");
        g.DrawLine(symColor, 2.5f * px, new PointF(c.X - r * 0.55f, c.Y), new PointF(c.X - r * 0.18f, c.Y), round: true);
        g.DrawLine(symColor, 2.5f * px, new PointF(c.X + r * 0.18f, c.Y), new PointF(c.X + r * 0.55f, c.Y), round: true);
        g.FillEllipse(symColor, Box(c, 2.5f * px));
        // Heading tape: ticks every 10°, numbers every 30°, the heading under the middle mark.
        var t = new RectangleF(c.X - r, c.Y - r - gap - tape, 2 * r, tape);
        g.FillRectangle(Col(s, "tape"), t);
        float heading = (HeadingDeg(raw) + 360f) % 360f;
        float pxPerDeg = r / 45f;
        var numbers = Col(s, "text");
        var tick = Color.FromArgb((int)(numbers.A * 0.8f), numbers);
        float fontPx = Math.Max(6f, tape * 0.48f);
        g.PushClipRect(t);
        for (int deg = (int)MathF.Round(heading / 10f) * 10 - 50; deg <= heading + 50; deg += 10)
        {
            float x = c.X + (deg - heading) * pxPerDeg;
            bool big = deg % 30 == 0;
            g.DrawLine(tick, px, new PointF(x, t.Bottom), new PointF(x, t.Bottom - tape * (big ? 0.35f : 0.2f)));
            if (big)
                g.DrawText($"{((deg % 360) + 360) % 360}", fontPx, false, numbers,
                    new RectangleF(x - fontPx * 2, t.Top + tape * 0.4f - fontPx, fontPx * 4, fontPx * 2), TextAlign.Center, TextAlign.Center);
        }
        g.Pop();
        g.DrawLine(symColor, 2 * px, new PointF(c.X, t.Top), new PointF(c.X, t.Bottom));
    }

    public static void DrawDisk(ICanvas g, RectangleF area, OverlaySettings s, float[] m, float[] raw)
    {
        float r = Math.Min(area.Width, area.Height) / 2f * 0.95f;
        var c = new PointF(area.X + area.Width / 2f, area.Y + area.Height / 2f);
        float px = Math.Max(1f, r / 80f);
        g.FillEllipse(Col(s, "face"), Box(c, r));
        g.DrawEllipse(Col(s, "rim"), 1.5f * px, Box(c, r));
        // The card spins against the heading, as a compass card does: the 12 o'clock mark stays put in the world.
        g.PushTransform(Matrix3x2.CreateRotation(-HeadingDeg(raw) * MathF.PI / 180f, new Vector2(c.X, c.Y)));
        var marks = Col(s, "marks");
        for (int i = 0; i < 12; i++)
        {
            double a = (i * 30.0 - 90.0) * Math.PI / 180;
            float inner = i % 3 == 0 ? 0.74f : 0.84f, ca = (float)Math.Cos(a), sa = (float)Math.Sin(a);
            g.DrawLine(i == 0 ? Col(s, "north") : marks, (i == 0 ? 3.5f : i % 3 == 0 ? 2.5f : 1.5f) * px,
                new PointF(c.X + ca * r * inner, c.Y + sa * r * inner), new PointF(c.X + ca * r * 0.95f, c.Y + sa * r * 0.95f), round: true);
        }
        if (s.Crosshair)
        {
            var cross = Col(s, "cross");
            g.DrawLine(cross, px, new PointF(c.X - r * 0.66f, c.Y), new PointF(c.X + r * 0.66f, c.Y));
            g.DrawLine(cross, px, new PointF(c.X, c.Y - r * 0.66f), new PointF(c.X, c.Y + r * 0.66f));
            g.DrawEllipse(cross, px, Box(c, r * 0.33f));
        }
        g.Pop();
        // The dot: where the screen points, off centre by the tilt, on the rim at the range.
        float nx = m[2], ny = m[5];
        float flat = MathF.Sqrt(nx * nx + ny * ny);
        float tilt = MathF.Acos(Math.Clamp(m[8], -1f, 1f)) * 180f / MathF.PI;
        float off = Math.Min(tilt / Math.Max(s.DiskRange, 5), 1f) * r * 0.86f;
        var p = flat > 1e-4f ? new PointF(c.X + nx / flat * off, c.Y - ny / flat * off) : c;
        var dotColor = Col(s, "dot");
        float d = r * 0.09f;
        g.FillEllipse(dotColor, Box(p, d));
        g.DrawEllipse(Color.FromArgb((int)(0.4f * dotColor.A), 0, 0, 0), px, Box(p, d));
    }
}
