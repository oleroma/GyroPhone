using System.Drawing;
using GyroPhone.Host;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The laser pointer overlay (the phone's laser pointer display, ported): a see-through sphere seen
/// from a fixed angle (30° round, 30° above; with Perspective nearer parts bigger), its 15° grid and axes, the beam from
/// its centre out of the phone's back to the inside, a dot and a roll tick where it hits; Draw mode's
/// paint and Snake (<see cref="LaserTrace"/>, drawn by <see cref="PaintRenderer"/>). Far half first, then the beam, then the
/// near half (fainter, seen through the shell).
/// </summary>
internal static class LaserDrawing
{
    /// <summary>The axis letters, X, Y, Z: pitch, yaw, roll as the laser aims (facing −Z).</summary>
    private static readonly string[] AxisLetters = ["P", "Y", "R"];

    /// <summary>The pose as the laser aims: turned by the phone, with its roll axis (<see cref="LaserTrace.Aim"/>); a trackball the way picked (<see cref="LaserTrace.Faces"/>); a game camera already aims ahead.</summary>
    public static float[] Aimed(float[] m, OverlaySettings s) => s.TurnsWith switch
    {
        OverlayTurnsWith.Phone => LaserTrace.Aim(m),
        OverlayTurnsWith.Trackball => LaserTrace.Faces(m, s.LaserFaces),
        _ => m,
    };

    /// <summary>
    /// Draws the pointer posed <paramref name="m"/> (as <see cref="Aimed"/> gives it); <paramref name="raw"/> is the pose
    /// before that, which a trackball's sphere turns by in Fixed pointer mode, so Pointer faces moves only the pointer.
    /// <paramref name="buf"/> (the window's) keeps the grid between pictures.
    /// </summary>
    public static void Draw(ICanvas g, RectangleF area, OverlaySettings s, float[] m, LaserTrace? trace, long nowMs, bool held, int active = 0, bool firing = false, float[]? raw = null, float aim = 0f,
        DrawBuffers? buf = null)
    {
        float r = Math.Min(area.Width, area.Height) / 2f * 0.95f;
        var c = new PointF(area.X + area.Width / 2f, area.Y + area.Height / 2f);
        float px = Math.Clamp(r * 0.006f, 1f, 3f);

        // Sphere turns: everything on it is seen from the pose, so the beam stays ahead.
        float[]? turned = !s.LaserSphereTurns ? null : s.TurnsWith == OverlayTurnsWith.Trackball && raw != null ? raw : m;
        (PointF P, float Z) At(float x, float y, float z)
        {
            var v = LaserTrace.ToView(x, y, z, s.LaserAxonometric, turned);
            float k = s.Perspective ? LaserTrace.Perspective(v.Z) : 1f;
            return (new PointF(c.X + v.X * r * k, c.Y - v.Y * r * k), v.Z);
        }
        Color Fade(Color col, float k) => Color.FromArgb(Math.Clamp((int)(col.A * k), 0, 255), col);

        var beam = LaserTrace.Beam(m);
        var sphere = Color.FromArgb(s.Color("sphere"));
        var lit = Color.FromArgb(sphere.A, sphere.R + (int)((255 - sphere.R) * 0.35f), sphere.G + (int)((255 - sphere.G) * 0.35f), sphere.B + (int)((255 - sphere.B) * 0.35f));
        bool crosshair = s.LaserCrosshair && !s.LaserRifle;
        if (!s.LaserRifle && !crosshair) g.FillBall(new RectangleF(c.X - r, c.Y - r, 2 * r, 2 * r), new PointF(c.X - r * 0.35f, c.Y - r * 0.4f), lit, sphere);
        var grid = Color.FromArgb(s.Color("grid"));
        Color[] axisColors = [Color.FromArgb(s.Color("x")), Color.FromArgb(s.Color("y")), Color.FromArgb(s.Color("z"))];

        // The grid's lines (picture x, y on the sphere), on the near half (front) or the far one: with perspective
        // each point by its depth, ±√(1 − x² − y²). Kept (with their built shapes, by the canvas) until the
        // lines, the window's size or Perspective change: a still sphere's grid is never built again.
        void Grid(List<(float X, float Y)[]> lines, Color col, bool front)
        {
            var memo = buf == null ? new DrawBuffers.GridPoints() : front ? buf.GridFront : buf.GridBack;
            if (!ReferenceEquals(memo.Lines, lines) || memo.Cx != c.X || memo.Cy != c.Y || memo.R != r || memo.Perspective != s.Perspective)
            {
                // The arrays filled again in place when the lines are as many and as long (a turning sphere's grid).
                var all = memo.Points.Length == lines.Count ? memo.Points : new PointF[lines.Count][];
                for (int n = 0; n < lines.Count; n++)
                {
                    var l = lines[n];
                    var pts = all[n] = all[n] is { } kept && kept.Length == l.Length ? kept : new PointF[l.Length];
                    for (int i = 0; i < l.Length; i++)
                    {
                        float k = 1f;
                        if (s.Perspective)
                        {
                            float z = MathF.Sqrt(Math.Max(0f, 1f - l[i].X * l[i].X - l[i].Y * l[i].Y));
                            k = LaserTrace.Perspective(front ? z : -z);
                        }
                        pts[i] = new PointF(c.X + l[i].X * r * k, c.Y - l[i].Y * r * k);
                    }
                }
                (memo.Lines, memo.Cx, memo.Cy, memo.R, memo.Perspective, memo.Points) = (lines, c.X, c.Y, r, s.Perspective, all);
                memo.Stamp++;
            }
            g.DrawLinesSet(col, px, memo.Points, memo.Stamp);
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
                for (int sign = 1; sign >= -1; sign -= 2)
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

        // Paint and Snake: drawn on the sphere's far half under the beam and its near half over it (PaintRenderer).
        void Paint(bool front)
        {
            if (trace != null) PaintRenderer.Draw(g, trace, s, front, c, r, turned, moveColors);
        }

        // The rifle's muzzle flash: a flickering star at its muzzle tip (LaserTrace.Muzzle) in the Fire colour with a
        // white-hot middle, longest along the barrel. As the phone's muzzleFlash.
        void MuzzleFlash()
        {
            var mz = LaserTrace.MuzzleAt(aim);
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

        // The rifle's laser sight while aiming (aim eases it in): a thin line in the Aim colour from its muzzle along
        // the barrel to where it lands on the sphere, and a dot there with a soft halo. As the phone's laserSight.
        void LaserSight()
        {
            var mz = LaserTrace.MuzzleAt(aim);
            var tip = At(m[0] * mz.X + m[1] * mz.Y + m[2] * mz.Z, m[3] * mz.X + m[4] * mz.Y + m[5] * mz.Z, m[6] * mz.X + m[7] * mz.Y + m[8] * mz.Z).P;
            var col = Color.FromArgb(s.Color("aim"));
            g.DrawLine(Fade(col, 0.25f * aim), px * 4f, tip, hit, round: true);
            g.DrawLine(Fade(col, 0.8f * aim), px * 1.2f, tip, hit, round: true);
            float size = r * 0.025f, halo = size * 2.5f, core = size * 0.4f;
            g.FillEllipse(Fade(col, 0.3f * aim), new RectangleF(hit.X - halo, hit.Y - halo, halo * 2, halo * 2));
            g.FillEllipse(Fade(col, aim), new RectangleF(hit.X - size, hit.Y - size, size * 2, size * 2));
            g.FillEllipse(Color.FromArgb(Math.Clamp((int)(col.A * 0.9f * aim), 0, 255), 255, 255, 255), new RectangleF(hit.X - core, hit.Y - core, core * 2, core * 2));
        }
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
            foreach (var f in LaserTrace.RifleFaces(m, s.LaserAxonometric, turned, s.Perspective, aim))
            {
                var pts = new PointF[4];
                for (int i = 0; i < 4; i++) pts[i] = new PointF(c.X + f.Corners[i].X * r, c.Y - f.Corners[i].Y * r);
                g.FillPolygon(Color.FromArgb(body.A, (int)(body.R * f.Light), (int)(body.G * f.Light), (int)(body.B * f.Light)), pts);
                g.DrawPolygon(Color.FromArgb(body.A, body.R / 2, body.G / 2, body.B / 2), edge, pts);
            }
            if (aim > 0f) LaserSight();
            if (firing) MuzzleFlash();
            Paint(true);
            Axes(true);
            return;
        }
        var gridLines = buf != null ? buf.GridFor(s.LaserAxonometric, turned) : LaserTrace.GridFor(s.LaserAxonometric, turned);
        if (crosshair)
        {
            // The crosshair look: no sphere or beam; a crosshair lying on the sphere where it aims.
            if (s.LaserGrid) Grid(gridLines.Back, grid, false);
            Axes(false);
            Paint(false);
            Crosshair();
            Paint(true);
            if (s.LaserGrid) Grid(gridLines.Front, Fade(grid, 0.6f), true);
            Axes(true);
            return;
        }

        void Crosshair()
        {
            const float rad = MathF.PI / 180f;
            // Aiming shrinks it to the Aim display's size.
            float ak = 1f + (Math.Clamp(s.LaserAimSize, 0.1f, 1f) - 1f) * aim;
            var ch = LaserTrace.CrosshairOn(m, beam, s.CrosshairShape, s.CrosshairSize * rad * ak, s.CrosshairGap * rad * ak, s.CrosshairDot);
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

        if (s.LaserGrid) Grid(gridLines.Back, grid, false);
        Rings(false);
        Axes(false);
        Paint(false);
        if (!hitFront) Spot();
        g.DrawLine(beamCol, px * 2.5f, c, hit, round: true);
        g.FillEllipse(beamCol, new RectangleF(c.X - px * 3, c.Y - px * 3, px * 6, px * 6));
        Paint(true);
        if (s.LaserGrid) Grid(gridLines.Front, Fade(grid, 0.6f), true);
        Rings(true);
        Axes(true);
        if (hitFront) Spot();
        g.DrawEllipse(grid, px * 1.5f, new RectangleF(c.X - r, c.Y - r, 2 * r, 2 * r));
    }
}
