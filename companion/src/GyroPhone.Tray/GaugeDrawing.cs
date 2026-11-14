using System.Drawing;
using GyroPhone.Host;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The Ratchet ring and Speed graph overlays (docs/spec/pc-overlays.md), drawn as the phone's displays
/// of the same name: the ring's yaw, pitch and roll arcs lit while that axis moves the mouse; the graph's
/// scrolling yaw, pitch and roll speed, shaded while a ratchet is held.
/// </summary>
internal static class GaugeDrawing
{
    private static readonly string[] Keys = ["yaw", "pitch", "roll"];
    private static readonly string[] Letters = ["Y", "P", "R"];
    /// <summary>The axes' bits in OVERLAY's axes byte, in the shown order (yaw, pitch, roll), as the phone's gyroBit of MotionAxis.shown.</summary>
    private static readonly int[] Bits = [4, 2, 1];
    private const int ArcSteps = 24;

    /// <summary>The ring: three arcs, yaw on top, pitch lower right, roll lower left; dim while that axis doesn't move the mouse.</summary>
    public static void DrawRing(ICanvas g, RectangleF area, OverlaySettings s, int liveAxes, DrawBuffers buf)
    {
        float r = Math.Min(area.Width, area.Height) / 2f * 0.8f;
        var c = new PointF(area.X + area.Width / 2f, area.Y + area.Height / 2f);
        float w = r * 0.28f;
        var letters = Color.FromArgb(s.Color("text"));
        var pts = DrawBuffers.Sized(ref buf.Arc, ArcSteps + 1);
        for (int i = 0; i < 3; i++)
        {
            bool on = (liveAxes & Bits[i]) != 0;
            var col = Color.FromArgb(s.Color(Keys[i]));
            // As the phone's drawArc: 0° at three o'clock, clockwise, each arc 110° round its middle.
            double start = (-90.0 - 55.0 + i * 120.0) * Math.PI / 180.0;
            for (int k = 0; k <= ArcSteps; k++)
            {
                double a = start + 110.0 * Math.PI / 180.0 * k / ArcSteps;
                pts[k] = new PointF(c.X + r * (float)Math.Cos(a), c.Y + r * (float)Math.Sin(a));
            }
            g.DrawLines(Color.FromArgb((int)(col.A * (on ? 1f : 0.18f)), col), w, pts);
            double mid = (-90.0 + i * 120.0) * Math.PI / 180.0;
            float px = r * 0.55f;
            var at = new PointF(c.X + r * 0.45f * (float)Math.Cos(mid), c.Y + r * 0.45f * (float)Math.Sin(mid));
            g.DrawText(Letters[i], px, false, Color.FromArgb((int)(letters.A * (on ? 1f : 0.35f)), letters),
                new RectangleF(at.X - px, at.Y - px, px * 2, px * 2), TextAlign.Center, TextAlign.Center);
        }
    }

    /// <summary>The graph: the last Seconds of yaw, pitch and roll speed, ± Range °/s full height, newest at the right.</summary>
    public static void DrawGraph(ICanvas g, RectangleF area, OverlaySettings s, SpeedTrace? trace, DrawBuffers buf)
    {
        var back = Color.FromArgb(s.Color("back"));
        if (back.A > 0)
        {
            var pts = buf.GraphBack.Get(area.Width, area.Height, Math.Min(8f, Math.Min(area.Width, area.Height) / 2f), 6);
            var poly = DrawBuffers.Sized(ref buf.GraphBackPoly, pts.Length);
            float cx = area.X + area.Width / 2f, cy = area.Y + area.Height / 2f;
            for (int i = 0; i < pts.Length; i++) poly[i] = new PointF(cx + pts[i].X, cy + pts[i].Y);
            g.FillPolygon(back, poly);
        }
        float mid = area.Y + area.Height / 2f;
        g.DrawLine(Color.FromArgb(51, 255, 255, 255), 1f, new PointF(area.Left, mid), new PointF(area.Right, mid));
        if (trace == null) return;
        int want = Math.Clamp(s.GraphSeconds * SpeedTrace.RateHz, 2, SpeedTrace.Capacity);
        int n = trace.Read(want, buf.Yaw, buf.Pitch, buf.Roll, buf.Held);
        if (n < 2) return;
        // The newest bucket sits at the right edge; a part-full window starts mid-way.
        float step = area.Width / (want - 1);
        float x0 = area.Right - (n - 1) * step;
        var held = Color.FromArgb(s.Color("held"));
        for (int j = 0; j < n;)
        {
            if (!buf.Held[j]) { j++; continue; }
            int st = j;
            while (j < n && buf.Held[j]) j++;
            g.FillRectangle(held, new RectangleF(x0 + st * step, area.Top, Math.Max(step, (j - st) * step), area.Height));
        }
        float range = Math.Max(30, s.GraphRange);
        float half = area.Height / 2f * 0.92f;
        var line = DrawBuffers.Sized(ref buf.GraphLine, n);
        float[][] data = [buf.Yaw, buf.Pitch, buf.Roll];
        for (int i = 0; i < 3; i++)
        {
            var d = data[i];
            for (int k = 0; k < n; k++) line[k] = new PointF(x0 + k * step, mid - Math.Clamp(d[k] / range, -1f, 1f) * half);
            g.DrawLines(Color.FromArgb(s.Color(Keys[i])), 1.6f, line);
        }
    }
}
