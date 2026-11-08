using System.Drawing;
using GyroPhone.Host;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The laser pointer overlay (the phone's laser pointer display, ported): a see-through sphere seen
/// from a fixed angle (30° round, 30° above, no perspective), its 15° grid and axes, the beam from
/// its centre out of the phone's back to the inside, a dot and a roll tick where it hits; Draw mode's
/// fading paint (<see cref="LaserTrace"/>). Far half first, then the beam, then the
/// near half (fainter, seen through the shell).
/// </summary>
internal static class LaserDrawing
{
    /// <summary>A stroke's hops longer than this are bent along the sphere in pieces.</summary>
    private static readonly float StrokeStep = 4f * MathF.PI / 180f;

    /// <summary>Strokes fade in this many steps.</summary>
    private const int Levels = 8;

    /// <summary>Strokes blend from Paint ahead to Paint behind in this many steps.</summary>
    private const int Hues = 8;

    public static void Draw(ICanvas g, RectangleF area, OverlaySettings s, float[] m, LaserTrace? trace, long nowMs, bool held)
    {
        float r = Math.Min(area.Width, area.Height) / 2f * 0.95f;
        var c = new PointF(area.X + area.Width / 2f, area.Y + area.Height / 2f);
        float px = Math.Clamp(r * 0.006f, 1f, 3f);
        int fadeMs = s.LaserFadeMs;

        // Sphere turns: everything on it is seen from the pose, so the beam stays ahead.
        float[]? turned = s.LaserSphereTurns ? m : null;
        (PointF P, float Z) At(float x, float y, float z)
        {
            var v = LaserTrace.ToView(x, y, z, s.LaserAxonometric, turned);
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

        // Strokes: each piece of the way between two points (long hops bent along the
        // sphere), drawn on its side, older pieces darker and fainter.
        // Their colour goes from ahead (straight ahead) to behind (turned all the way round).
        void Lines(LaserTrace.Ring ring, bool front, Func<int, long, float> life, float width, bool glow, Color ahead, Color behind)
        {
            if (ring.Count < 2) return;
            float side = front ? 0.5f : 1f;
            float w = width * (front ? 0.6f : 1f);
            var run = new List<PointF>(64);
            int runLevel = -1, runHue = -1;
            void Flush()
            {
                if (run.Count >= 2)
                {
                    float k = runLevel / (float)Levels;
                    float h = runHue / (Hues - 1f);
                    var color = Color.FromArgb((int)(ahead.A + (behind.A - ahead.A) * h), (int)(ahead.R + (behind.R - ahead.R) * h),
                        (int)(ahead.G + (behind.G - ahead.G) * h), (int)(ahead.B + (behind.B - ahead.B) * h));
                    float shade = 0.35f + 0.65f * k;
                    var col = Color.FromArgb(Math.Clamp((int)(color.A * (0.35f + 0.65f * k) * side), 0, 255),
                        (int)(color.R * shade), (int)(color.G * shade), (int)(color.B * shade));
                    var pts = run.ToArray();
                    if (glow) g.DrawLines(Fade(col, 0.22f), w * 3f, pts);
                    g.DrawLines(col, w, pts);
                }
                run.Clear();
                runLevel = -1;
                runHue = -1;
            }
            for (int i = 1; i < ring.Count; i++)
            {
                if (ring.Gap(i))
                {
                    Flush();
                    continue;
                }
                var a = ring.Point(i - 1);
                var b = ring.Point(i);
                float k = life(i, b.T);
                if (k <= 0f)
                {
                    Flush();
                    continue;
                }
                int level = Math.Clamp((int)MathF.Ceiling(k * Levels), 1, Levels);
                float dot = Math.Clamp(a.X * b.X + a.Y * b.Y + a.Z * b.Z, -1f, 1f);
                int n = Math.Clamp((int)(MathF.Acos(dot) / StrokeStep), 1, 32);
                var (prev, prevZ) = At(a.X, a.Y, a.Z);
                for (int j = 1; j <= n; j++)
                {
                    float t = j / (float)n;
                    float x = a.X + (b.X - a.X) * t, y = a.Y + (b.Y - a.Y) * t, z = a.Z + (b.Z - a.Z) * t;
                    float l = Math.Max(1e-4f, MathF.Sqrt(x * x + y * y + z * z));
                    var (p, depth) = At(x / l, y / l, z / l);
                    if (depth > 0f == front && prevZ > 0f == front)
                    {
                        int hue = Math.Clamp((int)(LaserTrace.Behind(z / l) * (Hues - 1) + 0.5f), 0, Hues - 1);
                        if (level != runLevel || hue != runHue) Flush();
                        if (run.Count == 0)
                        {
                            run.Add(prev);
                            runLevel = level;
                            runHue = hue;
                        }
                        run.Add(p);
                    }
                    else Flush();
                    prev = p;
                    prevZ = depth;
                }
            }
            Flush();
        }

        float brushPx = Math.Max(px * 1.5f, r * Math.Clamp(s.LaserBrush, 1, 20) * MathF.PI / 180f * 0.6f);
        void Paint(bool front)
        {
            if (trace == null) return;
            int n = trace.Strokes.Count;
            Lines(trace.Strokes, front, (i, t) => LaserTrace.StrokeLife(i, n, t, nowMs, fadeMs), brushPx, true, Color.FromArgb(s.Color("paint")), Color.FromArgb(s.Color("paint_behind")));
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

        var gridLines = LaserTrace.GridFor(s.LaserAxonometric, turned);
        if (s.LaserGrid) Grid(gridLines.Back, grid);
        Axes(false);
        Paint(false);
        if (!hitFront) Spot();
        g.DrawLine(beamCol, px * 2.5f, c, hit, round: true);
        g.FillEllipse(beamCol, new RectangleF(c.X - px * 3, c.Y - px * 3, px * 6, px * 6));
        Paint(true);
        if (s.LaserGrid) Grid(gridLines.Front, Fade(grid, 0.6f));
        Axes(true);
        if (hitFront) Spot();
        g.DrawEllipse(grid, px * 1.5f, new RectangleF(c.X - r, c.Y - r, 2 * r, 2 * r));
    }
}
