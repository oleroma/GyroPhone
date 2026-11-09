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

    /// <summary>The axis letters, X, Y, Z: pitch, yaw, roll as the laser aims (facing −Z).</summary>
    private static readonly string[] AxisLetters = ["P", "Y", "R"];

    /// <summary>Strokes fade in this many steps.</summary>
    private const int Levels = 8;

    /// <summary>Strokes blend from Paint ahead to Paint behind in this many steps.</summary>
    private const int Hues = 8;

    /// <summary>The pose as the laser aims: turned by the phone, with its roll axis (<see cref="LaserTrace.Aim"/>); a trackball the way picked (<see cref="LaserTrace.Faces"/>); a game camera already aims ahead.</summary>
    public static float[] Aimed(float[] m, OverlaySettings s) => s.TurnsWith switch
    {
        OverlayTurnsWith.Phone => LaserTrace.Aim(m),
        OverlayTurnsWith.Trackball => LaserTrace.Faces(m, s.LaserFaces),
        _ => m,
    };

    public static void Draw(ICanvas g, RectangleF area, OverlaySettings s, float[] m, LaserTrace? trace, long nowMs, bool held, int active = 0)
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
        bool crosshair = s.LaserCrosshair && !s.LaserRifle;
        if (!s.LaserRifle && !crosshair) g.FillBall(new RectangleF(c.X - r, c.Y - r, 2 * r, 2 * r), new PointF(c.X - r * 0.35f, c.Y - r * 0.4f), lit, sphere);
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

        // Held (None) gimbal axes and those the gyro is turning now (bit 0 yaw, 1 pitch, 2 roll); a laser axis i
        // (X pitch, Y yaw, Z roll) is gimbal axis GimbalOf[i].
        int locks = s.GimbalLocks;
        if (!s.IsTrackball) active = 0;
        int[] gimbalOf = [1, 0, 2];
        // A dashed line: every other piece of it.
        void Dashed(Color col, float w, PointF[] pts)
        {
            float dash = px * 4f, on = 0f;
            bool draw = true;
            var run = new List<PointF> { pts[0] };
            for (int k = 1; k < pts.Length; k++)
            {
                var a = pts[k - 1];
                var b = pts[k];
                float len = MathF.Sqrt((b.X - a.X) * (b.X - a.X) + (b.Y - a.Y) * (b.Y - a.Y));
                float done = 0f;
                while (len - done > dash - on)
                {
                    done += dash - on;
                    var q = new PointF(a.X + (b.X - a.X) * done / len, a.Y + (b.Y - a.Y) * done / len);
                    if (draw) { run.Add(q); if (run.Count >= 2) g.DrawLines(col, w, run.ToArray()); }
                    run.Clear();
                    run.Add(q);
                    draw = !draw;
                    on = 0f;
                }
                on += len - done;
                run.Add(b);
            }
            if (draw && run.Count >= 2) g.DrawLines(col, w, run.ToArray());
        }
        void Padlock(PointF p, Color col)
        {
            float k = px * 3.5f;
            g.FillRectangle(col, new RectangleF(p.X - k, p.Y - k * 0.4f, 2 * k, 1.6f * k));
            g.DrawEllipse(col, px * 1.3f, new RectangleF(p.X - k * 0.65f, p.Y - k * 1.5f, 1.3f * k, 2.2f * k));
        }
        var lockedColor = Color.FromArgb(0xFF, 0x8A, 0x8F, 0x98);

        void Axes(bool front)
        {
            if (!s.LaserAxes) return;
            for (int i = 0; i < 3; i++)
            {
                // Turning with the laser: its own axis i, column i of its pose.
                float ax = m[i], ay = m[3 + i], az = m[6 + i];
                bool locked = (locks & (1 << gimbalOf[i])) != 0, lit = (active & (1 << gimbalOf[i])) != 0;
                foreach (int sign in new[] { 1, -1 })
                {
                    var (p, z) = At(sign * ax, sign * ay, sign * az);
                    if (z > 0f != front) continue;
                    var a = locked ? lockedColor : axisColors[i];
                    var lineCol = Fade(a, (sign > 0 ? 1f : 0.4f) * (front ? 0.7f : 1f));
                    float lw = px * (lit ? 3f : 1.5f);
                    if (locked) Dashed(lineCol, lw, [c, p]);
                    else g.DrawLine(lineCol, lw, c, p, round: true);
                    if (sign > 0)
                    {
                        if (locked) Padlock(p, a);
                        else
                        {
                            float rr = px * (lit ? 3.5f : 2.5f);
                            g.FillEllipse(a, new RectangleF(p.X - rr, p.Y - rr, rr * 2, rr * 2));
                        }
                        // Its letter just beyond the tip: P (X), Y (Y), R (Z), as the laser aims.
                        float k = 1f + 12f * px / r;
                        var q = At(k * ax, k * ay, k * az).P;
                        float size = 11f * px;
                        g.DrawText(AxisLetters[i], size, true, Fade(a, front ? 0.8f : 1f), new RectangleF(q.X - size, q.Y - size, size * 2, size * 2), TextAlign.Center, TextAlign.Center);
                    }
                }
            }
        }

        // The Sphere look's gimbal rings, turning with the laser's axes, coloured as their axes (yaw Y, pitch X, roll Z).
        (float X, float Y, float Z)[][]? gimbal = null;
        if (s.LaserGimbal && !s.LaserRifle && !crosshair)
            gimbal = [LaserTrace.GimbalRing(0, m), LaserTrace.GimbalRing(1, m), LaserTrace.GimbalRing(2, m)];
        void Rings(bool front)
        {
            if (gimbal == null) return;
            int[] colorOf = [1, 0, 2];
            for (int i = 0; i < 3; i++)
            {
                bool locked = (locks & (1 << i)) != 0, lit = (active & (1 << i)) != 0;
                var col = Fade(axisColors[colorOf[i]], (locked ? 0.25f : lit ? 1f : 0.75f) * (front ? 1f : 0.45f));
                float w = px * (lit ? 3.5f : front ? 2.5f : 1.5f);
                var run = new List<PointF>(66);
                void Flush()
                {
                    if (run.Count >= 2)
                    {
                        if (locked) Dashed(col, w, run.ToArray());
                        else g.DrawLines(col, w, run.ToArray());
                    }
                    run.Clear();
                }
                foreach (var pt in gimbal[i])
                {
                    var (p, z) = At(pt.X, pt.Y, pt.Z);
                    if (z > 0f == front) run.Add(p); else Flush();
                }
                Flush();
            }
        }

        // Strokes: each piece of the way between two points (long hops bent along the
        // sphere), drawn on its side, older pieces darker and fainter.
        // Their colour goes from ahead (straight ahead) to behind (turned all the way round).
        void Lines(LaserTrace.Ring ring, bool front, Func<int, long, float> life, float width, bool glow, Color ahead, Color behind, Color fireAhead, Color fireBehind)
        {
            if (ring.Count < 2) return;
            float side = front ? 0.5f : 1f;
            float w = width * (front ? 0.6f : 1f);
            var run = new List<PointF>(64);
            int runLevel = -1, runHue = -1;
            bool runFire = false;
            void Flush()
            {
                if (run.Count >= 2)
                {
                    float k = runLevel / (float)Levels;
                    float h = runHue / (Hues - 1f);
                    // Painted while firing: the Fire colours.
                    var (a0, a1) = runFire ? (fireAhead, fireBehind) : (ahead, behind);
                    var color = Color.FromArgb((int)(a0.A + (a1.A - a0.A) * h), (int)(a0.R + (a1.R - a0.R) * h),
                        (int)(a0.G + (a1.G - a0.G) * h), (int)(a0.B + (a1.B - a0.B) * h));
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
                bool fire = ring.Fire(i);
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
                        if (level != runLevel || hue != runHue || fire != runFire) Flush();
                        if (run.Count == 0)
                        {
                            run.Add(prev);
                            runLevel = level;
                            runHue = hue;
                            runFire = fire;
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
            Lines(trace.Strokes, front, (i, t) => LaserTrace.StrokeLife(i, n, t, nowMs, fadeMs), brushPx, true, Color.FromArgb(s.Color("paint")), Color.FromArgb(s.Color("paint_behind")),
                Color.FromArgb(s.Color("fire")), Color.FromArgb(s.Color("fire_behind")));
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

        if (s.LaserRifle)
        {
            // The rifle look: no sphere or beam; the paint still lands where it aims.
            Axes(false);
            Paint(false);
            var body = Color.FromArgb(s.Color("rifle"));
            float edge = Math.Max(1f, px * 0.75f);
            foreach (var f in LaserTrace.RifleFaces(m, s.LaserAxonometric, turned))
            {
                var pts = new PointF[4];
                for (int i = 0; i < 4; i++) pts[i] = new PointF(c.X + f.Corners[i].X * r, c.Y - f.Corners[i].Y * r);
                g.FillPolygon(Color.FromArgb(body.A, (int)(body.R * f.Light), (int)(body.G * f.Light), (int)(body.B * f.Light)), pts);
                g.DrawPolygon(Color.FromArgb(body.A, body.R / 2, body.G / 2, body.B / 2), edge, pts);
            }
            Paint(true);
            Axes(true);
            return;
        }
        var gridLines = LaserTrace.GridFor(s.LaserAxonometric, turned);
        if (crosshair)
        {
            // The crosshair look: no sphere or beam; a crosshair lying on the sphere where it aims.
            if (s.LaserGrid) Grid(gridLines.Back, grid);
            Axes(false);
            Paint(false);
            Crosshair();
            Paint(true);
            if (s.LaserGrid) Grid(gridLines.Front, Fade(grid, 0.6f));
            Axes(true);
            return;
        }

        void Crosshair()
        {
            const float rad = MathF.PI / 180f;
            var ch = LaserTrace.CrosshairOn(m, beam, s.CrosshairShape, s.CrosshairSize * rad, s.CrosshairGap * rad, s.CrosshairDot);
            Color ahead = Color.FromArgb(s.Color("crosshair")), behind = Color.FromArgb(s.Color("crosshair_behind"));
            float h = LaserTrace.Behind(beam.Z);
            var color = Color.FromArgb((int)(ahead.A + (behind.A - ahead.A) * h), (int)(ahead.R + (behind.R - ahead.R) * h),
                (int)(ahead.G + (behind.G - ahead.G) * h), (int)(ahead.B + (behind.B - ahead.B) * h));
            if (held) color = Fade(color, 0.35f);
            var o = Color.FromArgb(s.Color("crosshair_outline"));
            var outline = Color.FromArgb(Math.Min(o.A, color.A), o);
            float w = Math.Clamp(s.CrosshairThickness, 0.5f, 8f) * px;
            PointF[] Pts((float X, float Y, float Z)[] l)
            {
                var pts = new PointF[l.Length];
                for (int i = 0; i < l.Length; i++) pts[i] = At(l[i].X, l[i].Y, l[i].Z).P;
                return pts;
            }
            var dot = ch.Dot is { } d ? Pts(d) : null;
            if (s.CrosshairOutline)
            {
                foreach (var l in ch.Lines) g.DrawLines(outline, w + 2f * px, Pts(l));
                if (dot != null) g.DrawPolygon(outline, 2f * px, dot);
            }
            foreach (var l in ch.Lines) g.DrawLines(color, w, Pts(l));
            if (dot != null) g.FillPolygon(color, dot);
        }

        if (s.LaserGrid) Grid(gridLines.Back, grid);
        Rings(false);
        Axes(false);
        Paint(false);
        if (!hitFront) Spot();
        g.DrawLine(beamCol, px * 2.5f, c, hit, round: true);
        g.FillEllipse(beamCol, new RectangleF(c.X - px * 3, c.Y - px * 3, px * 6, px * 6));
        Paint(true);
        if (s.LaserGrid) Grid(gridLines.Front, Fade(grid, 0.6f));
        Rings(true);
        Axes(true);
        if (hitFront) Spot();
        g.DrawEllipse(grid, px * 1.5f, new RectangleF(c.X - r, c.Y - r, 2 * r, 2 * r));
    }
}
