using System.Drawing;
using GyroPhone.Host;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The laser pointer overlay (the phone's laser pointer display, ported): a see-through sphere seen
/// from a fixed angle (30° round, 30° above, no perspective), its 15° grid and axes, the beam from
/// its centre out of the phone's back to the inside, a dot and a roll tick where it hits; the fading
/// trail and Draw mode's paint (<see cref="LaserTrace"/>). Far half first, then the beam, then the
/// near half (fainter, seen through the shell).
/// </summary>
internal static class LaserDrawing
{
    /// <summary>A trail's hops longer than this are bent along the sphere in pieces.</summary>
    private static readonly float TrailStep = 4f * MathF.PI / 180f;

    public static void Draw(ICanvas g, RectangleF area, OverlaySettings s, float[] m, LaserTrace? trace, long nowMs, bool held)
    {
        float r = Math.Min(area.Width, area.Height) / 2f * 0.95f;
        var c = new PointF(area.X + area.Width / 2f, area.Y + area.Height / 2f);
        float px = Math.Clamp(r * 0.006f, 1f, 3f);
        int fadeMs = s.LaserFadeS * 1000;
        int trailMs = Math.Clamp(s.LaserTrailMs, 0, 3000);

        (PointF P, float Z) At(float x, float y, float z)
        {
            var v = LaserTrace.ToView(x, y, z);
            return (new PointF(c.X + v.X * r, c.Y - v.Y * r), v.Z);
        }
        Color Fade(Color col, float k) => Color.FromArgb(Math.Clamp((int)(col.A * k), 0, 255), col);

        var beam = LaserTrace.Beam(m);
        var sphere = Color.FromArgb(s.Color("sphere"));
        var lit = Color.FromArgb(sphere.A, sphere.R + (int)((255 - sphere.R) * 0.35f), sphere.G + (int)((255 - sphere.G) * 0.35f), sphere.B + (int)((255 - sphere.B) * 0.35f));
        g.FillBall(new RectangleF(c.X - r, c.Y - r, 2 * r, 2 * r), new PointF(c.X - r * 0.35f, c.Y - r * 0.4f), lit, sphere);
        var grid = Color.FromArgb(s.Color("grid"));
        Color[] axisColors = [Color.FromArgb(s.Color("x")), Color.FromArgb(s.Color("y")), Color.FromArgb(s.Color("z"))];

        void Grid(List<(float X, float Y)[]> lines, Color col)
        {
            foreach (var l in lines)
            {
                var pts = new PointF[l.Length];
                for (int i = 0; i < l.Length; i++) pts[i] = new PointF(c.X + l[i].X * r, c.Y - l[i].Y * r);
                g.DrawLines(col, px, pts);
            }
        }

        void Axes(bool front)
        {
            if (!s.LaserAxes) return;
            for (int i = 0; i < 3; i++)
                foreach (int sign in new[] { 1, -1 })
                {
                    var (p, z) = At(i == 0 ? sign : 0, i == 1 ? sign : 0, i == 2 ? sign : 0);
                    if (z > 0f != front) continue;
                    var a = axisColors[i];
                    g.DrawLine(Fade(a, (sign > 0 ? 1f : 0.4f) * (front ? 0.7f : 1f)), px * 1.5f, c, p, round: true);
                    if (sign > 0) g.FillEllipse(a, new RectangleF(p.X - px * 2.5f, p.Y - px * 2.5f, px * 5f, px * 5f));
                }
        }

        void Paint(bool front)
        {
            if (trace is not { Painted: > 0 }) return;
            var pc = Color.FromArgb(s.Color("paint"));
            var pts = new List<PointF>(64);
            trace.ForEachRun(nowMs, fadeMs, (row, c0, c1, level, f) =>
            {
                if (f != front) return;
                float lat0 = LaserTrace.RowLat(row) - LaserTrace.CellRad / 2, lat1 = lat0 + LaserTrace.CellRad;
                pts.Clear();
                for (int k = c0; k <= c1 + 1; k++)
                {
                    var d = LaserTrace.Dir(lat1, LaserTrace.ColLon(k) - LaserTrace.CellRad / 2);
                    pts.Add(At(d.X, d.Y, d.Z).P);
                }
                for (int k = c1 + 1; k >= c0; k--)
                {
                    var d = LaserTrace.Dir(lat0, LaserTrace.ColLon(k) - LaserTrace.CellRad / 2);
                    pts.Add(At(d.X, d.Y, d.Z).P);
                }
                g.FillPolygon(Fade(pc, level / (float)LaserTrace.Levels * (front ? 0.45f : 0.9f)), pts.ToArray());
            });
        }

        void Trail(bool front)
        {
            if (trace == null || trailMs <= 0) return;
            var tc = Color.FromArgb(s.Color("trail"));
            for (int i = 1; i < trace.TrailCount; i++)
            {
                if (trace.TrailGap(i)) continue;
                var a = trace.TrailPoint(i - 1, nowMs, trailMs);
                var b = trace.TrailPoint(i, nowMs, trailMs);
                if (b.Life <= 0f) continue;
                float dot = Math.Clamp(a.X * b.X + a.Y * b.Y + a.Z * b.Z, -1f, 1f);
                int n = Math.Clamp((int)(MathF.Acos(dot) / TrailStep), 1, 32);
                var prev = At(a.X, a.Y, a.Z).P;
                for (int k = 1; k <= n; k++)
                {
                    float t = k / (float)n;
                    float x = a.X + (b.X - a.X) * t, y = a.Y + (b.Y - a.Y) * t, z = a.Z + (b.Z - a.Z) * t;
                    float l = Math.Max(1e-4f, MathF.Sqrt(x * x + y * y + z * z));
                    var (p, depth) = At(x / l, y / l, z / l);
                    if (depth > 0f == front)
                        g.DrawLine(Fade(tc, b.Life * (front ? 0.6f : 1f)), px * (1f + 2f * b.Life), prev, p, round: true);
                    prev = p;
                }
            }
        }

        var (hit, hitZ) = At(beam.X, beam.Y, beam.Z);
        bool hitFront = hitZ > 0f;
        var beamCol = Color.FromArgb(s.Color("beam"));
        if (held) beamCol = Fade(beamCol, 0.35f);

        void Spot()
        {
            var dotCol = Color.FromArgb(s.Color("dot"));
            if (held) dotCol = Fade(dotCol, 0.35f);
            // Foreshortened toward the rim (seen edge-on there).
            float size = r * 0.06f * (0.5f + 0.5f * MathF.Abs(hitZ));
            g.FillEllipse(Fade(beamCol, 0.35f), new RectangleF(hit.X - size * 2, hit.Y - size * 2, size * 4, size * 4));
            g.FillEllipse(dotCol, new RectangleF(hit.X - size, hit.Y - size, size * 2, size * 2));
            if (LaserTrace.RollTick(m, beam) is { } t)
            {
                const float k = 0.22f;
                var tip = At(beam.X + t.X * k, beam.Y + t.Y * k, beam.Z + t.Z * k).P;
                g.DrawLine(dotCol, px * 2f, hit, tip, round: true);
            }
        }

        if (s.LaserGrid) Grid(LaserTrace.Grid.Back, grid);
        Axes(false);
        Paint(false);
        Trail(false);
        if (!hitFront) Spot();
        g.DrawLine(beamCol, px * 2.5f, c, hit, round: true);
        g.FillEllipse(beamCol, new RectangleF(c.X - px * 3, c.Y - px * 3, px * 6, px * 6));
        Paint(true);
        if (s.LaserGrid) Grid(LaserTrace.Grid.Front, Fade(grid, 0.6f));
        Axes(true);
        Trail(true);
        if (hitFront) Spot();
        g.DrawEllipse(grid, px * 1.5f, new RectangleF(c.X - r, c.Y - r, 2 * r, 2 * r));
    }
}
