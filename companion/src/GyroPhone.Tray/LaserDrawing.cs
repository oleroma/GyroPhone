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

    /// <summary>Fire strokes blend from Fire ahead to Fire behind in this many steps.</summary>
    private const int Hues = 8;

    /// <summary>Each half of a rounded corner (Curve) is drawn as this many straight pieces.</summary>
    private const int CurveSteps = 3;

    /// <summary>The pose as the laser aims: turned by the phone, with its roll axis (<see cref="LaserTrace.Aim"/>); a trackball the way picked (<see cref="LaserTrace.Faces"/>); a game camera already aims ahead.</summary>
    public static float[] Aimed(float[] m, OverlaySettings s) => s.TurnsWith switch
    {
        OverlayTurnsWith.Phone => LaserTrace.Aim(m),
        OverlayTurnsWith.Trackball => LaserTrace.Faces(m, s.LaserFaces),
        _ => m,
    };

    public static void Draw(ICanvas g, RectangleF area, OverlaySettings s, float[] m, LaserTrace? trace, long nowMs, bool held, int active = 0, bool firing = false)
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

        // The Sphere look's gimbal rings, turning with the laser's axes, coloured as their axes (yaw Y green, pitch X red, roll Z blue).
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

        // The way colours, in LaserTrace.DirectionColor's order.
        var moveColors = new uint[6];
        for (int i = 0; i < 6; i++) moveColors[i] = unchecked((uint)s.Color(OverlaySettings.LaserMoves[i]));
        int reach = s.LaserSmoothColours ? LaserTrace.SmoothReach : 0;
        int steps = Math.Clamp(s.LaserColourSteps, 4, 32);
        float curve = Math.Clamp(s.LaserCurve, 0f, 1f);

        // Strokes: each piece of the way between two points (long hops bent along the sphere), drawn on its side,
        // older pieces darker and fainter, in the colour of the way it moves (blended as a normal map, in
        // LaserColourSteps shades a channel), or while firing a Fire colour step (from ahead straight ahead to behind
        // turned all the way round). A line is drawn in pieces of one colour with flat ends, so pieces meet without
        // poking into or overlapping each other; with Curve its corners are rounded. As the phone's lines().
        void Lines(LaserTrace.Ring ring, bool front, Func<int, long, float> life, float width, bool glow, Color fireAhead, Color fireBehind)
        {
            if (ring.Count < 2) return;
            float side = front ? 0.5f : 1f;
            float w = width * (front ? 0.6f : 1f);
            // The visible run of picture points being gathered, with the (level, colour) of the piece ending at each.
            var run = new List<PointF>(64);
            var keys = new List<(int Level, Color Col)>(64);
            var piece = new List<PointF>(64);
            void DrawPiece((int Level, Color Col) key)
            {
                if (piece.Count >= 2)
                {
                    float k = key.Level / (float)Levels;
                    float shade = 0.35f + 0.65f * k;
                    var col = Color.FromArgb(Math.Clamp((int)(key.Col.A * (0.35f + 0.65f * k) * side), 0, 255),
                        (int)(key.Col.R * shade), (int)(key.Col.G * shade), (int)(key.Col.B * shade));
                    var pts = piece.ToArray();
                    if (glow) g.DrawLines(Fade(col, 0.22f), w * 3f, pts, flat: true);
                    g.DrawLines(col, w, pts, flat: true);
                }
                var last = piece.Count > 0 ? piece[^1] : default;
                piece.Clear();
                piece.Add(last);
            }
            void Flush()
            {
                if (run.Count >= 2)
                {
                    var cur = keys[1];
                    piece.Clear();
                    piece.Add(run[0]);
                    void To(int ki, PointF p)
                    {
                        var key = keys[ki];
                        if (key != cur)
                        {
                            DrawPiece(cur);
                            cur = key;
                        }
                        piece.Add(p);
                    }
                    // A quadratic curve from the pen's point through control c to e, as a few straight pieces.
                    void Quad(int ki, PointF a, PointF ctl, PointF e)
                    {
                        for (int t = 1; t <= CurveSteps; t++)
                        {
                            float u = t / (float)CurveSteps, v = 1f - u;
                            To(ki, new PointF(v * v * a.X + 2 * v * u * ctl.X + u * u * e.X, v * v * a.Y + 2 * v * u * ctl.Y + u * u * e.Y));
                        }
                    }
                    int n = run.Count;
                    if (curve <= 0f || n == 2)
                        for (int k = 1; k < n; k++) To(k, run[k]);
                    else
                    {
                        PointF Mid(PointF a, PointF b) => new((a.X + b.X) / 2f, (a.Y + b.Y) / 2f);
                        PointF Lerp(PointF a, PointF b, float f) => new(a.X + (b.X - a.X) * f, a.Y + (b.Y - a.Y) * f);
                        var mPrev = Mid(run[0], run[1]);
                        To(1, mPrev);
                        for (int k = 1; k <= n - 2; k++)
                        {
                            var cpt = run[k];
                            var mNext = Mid(cpt, run[k + 1]);
                            var a = Lerp(cpt, mPrev, curve);
                            var b = Lerp(cpt, mNext, curve);
                            To(k, a);
                            // The corner's curve (a, through cpt, to b) split in two halves, one in each piece.
                            var q = new PointF((a.X + 2f * cpt.X + b.X) / 4f, (a.Y + 2f * cpt.Y + b.Y) / 4f);
                            Quad(k, a, Mid(a, cpt), q);
                            Quad(k + 1, q, Mid(cpt, b), b);
                            To(k + 1, mNext);
                            mPrev = mNext;
                        }
                        To(n - 1, run[n - 1]);
                    }
                    DrawPiece(cur);
                }
                run.Clear();
                keys.Clear();
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
                var moveCol = Color.Empty;
                if (!fire)
                {
                    uint argb = LaserTrace.DirectionColor(ring.Direction(i, reach), moveColors);
                    int Q(int shift) => (int)((((argb >> shift) & 0xFF) / 255f * (steps - 1)) + 0.5f) * 255 / (steps - 1);
                    moveCol = Color.FromArgb((int)(argb >> 24), Q(16), Q(8), Q(0));
                }
                float dot = Math.Clamp(a.X * b.X + a.Y * b.Y + a.Z * b.Z, -1f, 1f);
                int sub = Math.Clamp((int)(MathF.Acos(dot) / StrokeStep), 1, 32);
                if (run.Count == 0)
                {
                    var (p0, z0) = At(a.X, a.Y, a.Z);
                    if (z0 > 0f == front)
                    {
                        run.Add(p0);
                        keys.Add((0, Color.Empty));
                    }
                }
                for (int j = 1; j <= sub; j++)
                {
                    float t = j / (float)sub;
                    float x = a.X + (b.X - a.X) * t, y = a.Y + (b.Y - a.Y) * t, z = a.Z + (b.Z - a.Z) * t;
                    float l = Math.Max(1e-4f, MathF.Sqrt(x * x + y * y + z * z));
                    var (p, depth) = At(x / l, y / l, z / l);
                    if (depth > 0f != front)
                    {
                        Flush();
                        continue;
                    }
                    var col = moveCol;
                    if (fire)
                    {
                        float h = Math.Clamp((int)(LaserTrace.Behind(z / l) * (Hues - 1) + 0.5f), 0, Hues - 1) / (Hues - 1f);
                        col = Color.FromArgb((int)(fireAhead.A + (fireBehind.A - fireAhead.A) * h), (int)(fireAhead.R + (fireBehind.R - fireAhead.R) * h),
                            (int)(fireAhead.G + (fireBehind.G - fireAhead.G) * h), (int)(fireAhead.B + (fireBehind.B - fireAhead.B) * h));
                    }
                    run.Add(p);
                    keys.Add(run.Count == 1 ? (0, Color.Empty) : (level, col));
                }
            }
            Flush();
        }

        float brushPx = Math.Max(px * 1.5f, r * Math.Clamp(s.LaserBrush, 1, 20) * MathF.PI / 180f * 0.6f);
        void Paint(bool front)
        {
            if (trace == null) return;
            int n = trace.Strokes.Count;
            Lines(trace.Strokes, front, (i, t) => LaserTrace.StrokeLife(i, n, t, nowMs, fadeMs), brushPx, true,
                Color.FromArgb(s.Color("fire")), Color.FromArgb(s.Color("fire_behind")));
        }

        // The rifle's muzzle flash: a flickering star at its muzzle tip (LaserTrace.Muzzle) in the Fire colour with a
        // white-hot middle, longest along the barrel. As the phone's muzzleFlash.
        void MuzzleFlash()
        {
            var mz = LaserTrace.Muzzle;
            PointF Along(float k)
            {
                float x = mz.X, y = mz.Y, z = mz.Z - k;
                return At(m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z).P;
            }
            var tip = Along(0f);
            var ahead = Along(0.1f);
            float fx = ahead.X - tip.X, fy = ahead.Y - tip.Y;
            float fl = Math.Max(1e-3f, MathF.Sqrt(fx * fx + fy * fy));
            // Flickers: a new shape every 35 ms.
            long step = nowMs / 35L;
            float Rnd(int i) => Math.Abs(((step * 7919L + i * 104729L) ^ (step >> 3)) % 1000L) / 1000f;
            var fireCol = Color.FromArgb(s.Color("fire"));
            float size = r * (0.07f + 0.04f * Rnd(0));
            float turn = Rnd(1) * 6.2832f;
            const int spikes = 8;
            var star = new PointF[spikes * 2];
            for (int j = 0; j < spikes * 2; j++)
            {
                float a = turn + j * MathF.PI / spikes;
                float ca = MathF.Cos(a), sa = MathF.Sin(a);
                // Along the barrel the star stretches out (up to 2.2×).
                float along = MathF.Abs(ca * fx / fl + sa * fy / fl);
                float len = j % 2 == 0 ? size * (0.6f + 0.6f * Rnd(j + 2)) * (1f + 1.2f * along * along) : size * 0.28f;
                star[j] = new PointF(tip.X + ca * len, tip.Y + sa * len);
            }
            float glowR = size * 1.3f, core = size * 0.25f;
            g.FillEllipse(Fade(fireCol, 0.3f), new RectangleF(tip.X - glowR, tip.Y - glowR, glowR * 2, glowR * 2));
            g.FillPolygon(fireCol, star);
            g.FillEllipse(Color.FromArgb(Math.Clamp((int)(fireCol.A * 0.9f), 0, 255), 255, 255, 255), new RectangleF(tip.X - core, tip.Y - core, core * 2, core * 2));
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
            if (firing) MuzzleFlash();
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
            // The colour of the way the beam last moved (it keeps the last way's when the beam stops).
            var color = Color.FromArgb(unchecked((int)LaserTrace.DirectionColor(trace?.Moving ?? (0f, 0f, 0f), moveColors)));
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
