using System.Drawing;
using System.Runtime.CompilerServices;
using GyroPhone.Host;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// Draws a pointer's paint map (<see cref="LaserPaint"/>) and Snake on its sphere, one half at a time, into a small
/// picture of premultiplied pixels the canvas then scales up smoothly: every pixel finds the point of the sphere it
/// shows (in perspective or flat, the near or the far half), reads the map round it and shows the paint there, so
/// the paint is smooth, foreshortened and bigger nearer as paint on a ball is. As the phone's PointerPaintShader.
/// Each pointer keeps its pictures and draws again only what changed: a new stamp redraws just the pixels that
/// show the map's tiles it touched (<see cref="PaintPicture"/>).
/// </summary>
internal static class PaintRenderer
{
    /// <summary>The picture's most pixels across for Paint detail low, normal, high.</summary>
    private static readonly int[] MaxSize = [160, 256, 420];

    /// <summary>The light for Light from <paramref name="from"/> (upper left, above, upper right, front).</summary>
    public static (float X, float Y, float Z) LightDir(int from) => PaintPicture.LightDir(from);

    private sealed class State
    {
        // Each half's paint (kept up to date stamp by stamp), and the picture shown with Snake over it.
        public readonly PaintPicture NearPaint = new(), FarPaint = new();
        public int[] Near = [], Far = [];
        public object? NearKey, FarKey;
        public long NearStamp, FarStamp;
        public float[] Cov = [], GlowCov = [];
        public int[] CovCol = [], GlowCol = [];
        // The Snake's pieces this drawing, oldest first (drawn newest first, see Snake).
        public readonly PaintRendererPieces Pieces = new();
    }

    private static readonly ConditionalWeakTable<LaserTrace, State> States = new();

    /// <summary>What a half's Snake shows: when any of it (or the paint under it) changes it is drawn again.</summary>
    private readonly record struct Key(
        long PaintStamp, int StrokeCount, double Newest, long TraceVersion, int Size, float R,
        (float, float, float) ColX, (float, float, float) ColY, (float, float, float) ColZ, int SettingsHash);

    /// <summary>
    /// Draws the paint and Snake of <paramref name="trace"/> on its <paramref name="front"/> (near) or far half, on
    /// the sphere of radius <paramref name="r"/> at <paramref name="c"/>; <paramref name="turned"/> as
    /// <see cref="LaserTrace.ToView"/> takes it.
    /// </summary>
    public static void Draw(ICanvas g, LaserTrace trace, OverlaySettings s, bool front, PointF c, float r, float[]? turned, uint[] moveColors)
    {
        var lp = s.LaserDraw ? trace.Paint : null;
        bool snake = s.LaserSnake && trace.Strokes.Count >= 2;
        if (lp == null && !snake)
        {
            // Nothing to show: let go of the paint's tables until there is.
            if (States.TryGetValue(trace, out var idle)) { idle.NearPaint.DropTables(); idle.FarPaint.DropTables(); }
            GpuPaint.Idle(trace);
            return;
        }
        var st = States.GetOrCreateValue(trace);
        float e = r * 1.05f;
        int size = Math.Max(16, Math.Min((int)MathF.Ceiling(2 * e), MaxSize[Math.Clamp(s.LaserPaintDetail, 0, 2)]));
        var colX = LaserTrace.ToView(1f, 0f, 0f, s.LaserAxonometric, turned);
        var colY = LaserTrace.ToView(0f, 1f, 0f, s.LaserAxonometric, turned);
        var colZ = LaserTrace.ToView(0f, 0f, 1f, s.LaserAxonometric, turned);
        // On the graphics card when it can: the CPU then only works out the Snake's pieces.
        if (g is D2DCanvas d2 && GpuPaint.Draw(d2, trace, lp, snake, s, front, new RectangleF(c.X - e, c.Y - e, 2 * e, 2 * e), size, r, e, turned,
            moveColors, colX, colY, colZ, (sz, ee, rr) => BuildPieces(st, sz, ee, trace, s, front, rr, turned, moveColors), st.Pieces))
        {
            // The CPU's pictures aren't needed meanwhile.
            st.NearPaint.DropTables(); st.FarPaint.DropTables();
            return;
        }
        // The paint: only what changed is drawn again (PaintPicture).
        var paint = front ? st.NearPaint : st.FarPaint;
        if (lp != null)
        {
            var scX = LaserTrace.ToView(1f, 0f, 0f, s.LaserAxonometric);
            var scY = LaserTrace.ToView(0f, 1f, 0f, s.LaserAxonometric);
            var scZ = LaserTrace.ToView(0f, 0f, 1f, s.LaserAxonometric);
            paint.Update(lp, s, front, size, colX, colY, colZ, scX, scY, scZ, moveColors);
        }
        else paint.Empty(size);
        ref long stamp = ref front ? ref st.NearStamp : ref st.FarStamp;
        if (!snake)
        {
            // The paint alone: shown as it is.
            if (front) st.NearKey = null; else st.FarKey = null;
            g.DrawPixels(new RectangleF(c.X - e, c.Y - e, 2 * e, 2 * e), size, size, paint.Pixels, paint.Stamp);
            return;
        }
        int hash = HashCode.Combine(
            HashCode.Combine(s.Perspective, s.SnakeColourByWay, s.SnakeShade, s.SnakeInside, s.SnakeLight, s.SnakeLightFrom, s.SnakeShine, s.LaserAxonometric),
            HashCode.Combine(s.LaserSnakeDeg, s.LaserSnakeBrush, s.Color("fire"), s.Color("fire_behind"), s.Color("aim"), s.Color("aim_behind"), s.SnakeFireBrush, s.SnakeAimBrush),
            HashCode.Combine(s.LaserSnakeTaper, s.LaserSnakeFade, s.LaserSnakeGlow),
            HashCode.Combine(moveColors[0], moveColors[1], moveColors[2], moveColors[3], moveColors[4], moveColors[5], s.LaserSmoothColours));
        var key = new Key(paint.Stamp, trace.Strokes.Count, trace.Strokes.Count > 0 ? trace.Strokes.Along(trace.Strokes.Count - 1) : 0,
            trace.Version, size, r, colX, colY, colZ, hash);
        ref int[] buf = ref front ? ref st.Near : ref st.Far;
        object? had = front ? st.NearKey : st.FarKey;
        if (buf.Length != size * size || had is not Key k || k != key)
        {
            // Snake over a copy of the paint.
            if (buf.Length != size * size) buf = new int[size * size];
            Array.Copy(paint.Pixels, buf, size * size);
            Snake(st, buf, size, e, trace, s, front, r, turned, moveColors);
            if (front) st.NearKey = key; else st.FarKey = key;
            stamp++;
        }
        g.DrawPixels(new RectangleF(c.X - e, c.Y - e, 2 * e, 2 * e), size, size, buf, stamp);
    }

    private static int Pack(float r, float g, float b, float a) => PaintPicture.Pack(r, g, b, a);


    /// <summary>
    /// Snake: the line along the newest stretch of the strokes, its near or far pieces, LaserSnakeBrush wide at its head
    /// (shots and aiming their own brushes), thinning by LaserSnakeTaper and fading by LaserSnakeFade toward its tail,
    /// with a soft LaserSnakeGlow round it, coloured and shaded as the paint. It follows a smooth curve through the
    /// stroke points (<see cref="LaserTrace.Curve"/>), cut into steps of at most <see cref="LaserTrace.CurveStepDeg"/>
    /// with the colour, width and fade blended along each, so a fast turn stays round and no joints show. Each pixel
    /// takes the strongest piece over it (and its glow the strongest glow), then the glow and the line go over the
    /// picture at the colours' opacity, so overlaps don't darken. As the phone's snake.
    /// </summary>
    private static void Snake(State st, int[] buf, int size, float e, LaserTrace trace, OverlaySettings s, bool front, float r,
        float[]? turned, uint[] moves)
    {
        if (!BuildPieces(st, size, e, trace, s, front, r, turned, moves)) return;
        float glow = Math.Clamp(s.LaserSnakeGlow, 0f, 1f);
        float alpha = 0f;
        foreach (var m in moves) alpha += (m >> 24) / 255f;
        alpha /= 6f;
        if (st.Cov.Length != size * size)
        {
            st.Cov = new float[size * size];
            st.CovCol = new int[size * size];
            st.GlowCov = new float[size * size];
            st.GlowCol = new int[size * size];
        }
        else
        {
            Array.Clear(st.Cov);
            if (glow > 0f) Array.Clear(st.GlowCov);
        }
        var pieces = st.Pieces;
        // Each pixel keeps its strongest cover, the oldest piece's colour where pieces tie. Drawn newest first (the
        // strongest, as the tail fades), so an older piece can skip the pixels a newer one already covers more than it
        // ever could; ties go to the later drawn, older piece: the same picture as drawing oldest first.
        // The picture's rows are shared out in bands over the cores: each band runs every piece in the same order,
        // clipped to its rows, so the picture is the same as drawn in one go (a long Snake is hundreds of pieces).
        int bands = size >= 128 ? Math.Clamp(Environment.ProcessorCount, 1, 8) : 1;
        void Band(int b)
        {
            int ya = size * b / bands, yb = size * (b + 1) / bands - 1;
            for (int i = pieces.Count - 1; i >= 0; i--)
            {
                ref var c = ref pieces.Items[i];
                Capsule(st, size, c.Ax, c.Ay, c.Bx, c.By, c.Half, c.Col, c.Op, glow, ya, yb);
            }
            // The glow, then the line, over the paint at the colours' opacity.
            if (glow > 0f) Over(buf, st.GlowCov, st.GlowCol, alpha, ya * size, (yb + 1) * size);
            Over(buf, st.Cov, st.CovCol, alpha, ya * size, (yb + 1) * size);
        }
        if (bands > 1) Parallel.For(0, bands, Band);
        else Band(0);
    }

    /// <summary>
    /// Works out the Snake's pieces on the <paramref name="front"/> or far half into <paramref name="st"/>, in the
    /// picture's pixels (<paramref name="size"/> across, <paramref name="e"/> from the middle to an edge); false when
    /// none shows on this half.
    /// </summary>
    private static bool BuildPieces(State st, int size, float e, LaserTrace trace, OverlaySettings s, bool front, float r, float[]? turned, uint[] moves)
    {
        var strokes = trace.Strokes;
        int n = strokes.Count;
        float toBuf = size / (2f * e);
        float Px(int deg) => Math.Max(1.5f, r * Math.Clamp(deg, 1, 20) * MathF.PI / 180f * 0.6f) * toBuf;
        float paintPx = Px(s.LaserSnakeBrush), firePx = Px(s.SnakeFireBrush), aimPx = Px(s.SnakeAimBrush);
        float taper = Math.Clamp(s.LaserSnakeTaper, 0f, 1f), fade = Math.Clamp(s.LaserSnakeFade, 0f, 1f);
        float len = Math.Max(1f, s.LaserSnakeDeg);
        var fa = Color.FromArgb(s.Color("fire"));
        var fb = Color.FromArgb(s.Color("fire_behind"));
        var aa = Color.FromArgb(s.Color("aim"));
        var ab = Color.FromArgb(s.Color("aim_behind"));
        var light = LightDir(s.SnakeLightFrom);
        var hv = LaserTrace.Unit(light.X, light.Y, light.Z + 1f);
        // The way colours' middle, worked out once for all the points.
        var mix = new LaserTrace.WayMix(moves);
        int reach = s.LaserSmoothColours ? LaserTrace.SmoothReach : 0;
        (float X, float Y, float Z) Pic(float x, float y, float z) => LaserTrace.ToView(x, y, z, s.LaserAxonometric, turned);
        // The colour of the line at shown-frame point p (picture view v), of this kind, going the way given.
        int ColourAt((float X, float Y, float Z) p, (float X, float Y, float Z) v, int kind, (float X, float Y, float Z) way, bool byWay)
        {
            var gs = turned == null ? (p.X, p.Y, p.Z) : (
                turned[0] * p.X + turned[3] * p.Y + turned[6] * p.Z,
                turned[1] * p.X + turned[4] * p.Y + turned[7] * p.Z,
                turned[2] * p.X + turned[5] * p.Y + turned[8] * p.Z);
            float cr, cg, cb;
            if (kind != LaserPaint.PaintKind)
            {
                float kb = LaserTrace.Behind(gs.Item3);
                var (ca, cz) = kind == LaserPaint.FireKind ? (fa, fb) : (aa, ab);
                cr = (ca.R + (cz.R - ca.R) * kb) / 255f; cg = (ca.G + (cz.G - ca.G) * kb) / 255f; cb = (ca.B + (cz.B - ca.B) * kb) / 255f;
            }
            else
            {
                uint argb = mix.Of(byWay ? way : gs);
                cr = (argb >> 16 & 0xFF) / 255f; cg = (argb >> 8 & 0xFF) / 255f; cb = (argb & 0xFF) / 255f;
            }
            float facing = Math.Min(MathF.Abs(v.Z), 1f);
            float kk = (1f + (0.25f + 0.75f * facing - 1f) * s.SnakeShade) * (front ? 1f : s.SnakeInside);
            float nx = front ? v.X : -v.X, ny = front ? v.Y : -v.Y, nz = front ? v.Z : -v.Z;
            kk *= 1f + (0.3f + 0.9f * Math.Max(nx * light.X + ny * light.Y + nz * light.Z, 0f) - 1f) * s.SnakeLight;
            float spec = s.SnakeShine * MathF.Pow(Math.Max(nx * hv.X + ny * hv.Y + nz * hv.Z, 0f), 24f);
            return Pack(Math.Min(cr * kk + spec, 1f), Math.Min(cg * kk + spec, 1f), Math.Min(cb * kk + spec, 1f), 1f);
        }
        var pieces = st.Pieces;
        pieces.Count = 0;
        bool any = false, hasPrev = false, prevFront = false;
        float bx0 = 0, by0 = 0;
        for (int i = 1; i < n; i++)
        {
            if (strokes.Gap(i)) { hasPrev = false; continue; }
            // The span from point i − 1 to i, with its neighbours in the same line (or the end itself).
            int i0 = i >= 2 && !strokes.Gap(i - 1) ? i - 2 : i - 1;
            int i3 = i + 1 < n && !strokes.Gap(i + 1) ? i + 1 : i;
            var q0 = strokes.Point(i0); var q1 = strokes.Point(i - 1); var q2 = strokes.Point(i); var q3 = strokes.Point(i3);
            var p0 = (q0.X, q0.Y, q0.Z); var p1 = (q1.X, q1.Y, q1.Z); var p2 = (q2.X, q2.Y, q2.Z); var p3 = (q3.X, q3.Y, q3.Z);
            float dot = Math.Clamp(p1.X * p2.X + p1.Y * p2.Y + p1.Z * p2.Z, -1f, 1f);
            float deg = MathF.Acos(dot) * 180f / MathF.PI;
            int steps = Math.Clamp((int)MathF.Ceiling(deg / LaserTrace.CurveStepDeg), 1, LaserTrace.CurveMaxSteps);
            int kind = strokes.Kind(i);
            float headPx = kind == LaserPaint.FireKind ? firePx : kind == LaserPaint.AimKind ? aimPx : paintPx;
            float tailA = 1f - strokes.BehindNewest(i - 1) / len, tailB = 1f - strokes.BehindNewest(i) / len;
            bool byWay = kind == LaserPaint.PaintKind && s.SnakeColourByWay;
            (float X, float Y, float Z) wayA = default, wayB = default;
            if (byWay)
            {
                // The way blends from the span before's to this span's.
                wayB = strokes.Direction(i, reach);
                wayA = i0 < i - 1 ? strokes.Direction(i - 1, reach) : wayB;
            }
            if (!hasPrev)
            {
                var v1 = Pic(p1.Item1, p1.Item2, p1.Item3);
                float k1 = s.Perspective ? LaserTrace.Perspective(v1.Z) : 1f;
                bx0 = (v1.X * k1 * r + e) * toBuf; by0 = (e - v1.Y * k1 * r) * toBuf;
                prevFront = v1.Z > 0f;
                hasPrev = true;
            }
            for (int j = 1; j <= steps; j++)
            {
                float f = j / (float)steps;
                var p = j == steps ? p2 : LaserTrace.Curve(p0, p1, p2, p3, f);
                var v = Pic(p.Item1, p.Item2, p.Item3);
                float k = s.Perspective ? LaserTrace.Perspective(v.Z) : 1f;
                float bx = (v.X * k * r + e) * toBuf, by = (e - v.Y * k * r) * toBuf;
                bool isFront = v.Z > 0f;
                if (isFront == front && prevFront == front)
                {
                    var way = byWay ? LaserTrace.Unit(wayA.X + (wayB.X - wayA.X) * f, wayA.Y + (wayB.Y - wayA.Y) * f, wayA.Z + (wayB.Z - wayA.Z) * f) : default;
                    int col = ColourAt(p, v, kind, way, byWay);
                    float tail = Math.Clamp(tailA + (tailB - tailA) * f, 0f, 1f);
                    float half = headPx * (1f - taper * (1f - tail)) * k / 2f;
                    if (pieces.Count == pieces.Items.Length) Array.Resize(ref pieces.Items, pieces.Items.Length * 2);
                    pieces.Items[pieces.Count++] = new SnakePiece { Ax = bx0, Ay = by0, Bx = bx, By = by, Half = half, Col = col, Op = 1f - fade * (1f - tail) };
                    any = true;
                }
                bx0 = bx; by0 = by; prevFront = isFront;
            }
        }
        return any;
    }

    private static void Over(int[] buf, float[] cov, int[] covCol, float alpha, int from, int to)
    {
        for (int j = from; j < to; j++)
        {
            float a = cov[j] * alpha;
            if (a <= 0f) continue;
            int c = covCol[j], o = buf[j];
            float ia = 1f - a;
            float or = (c >> 16 & 0xFF) / 255f * a + (o >> 16 & 0xFF) / 255f * ia;
            float og = (c >> 8 & 0xFF) / 255f * a + (o >> 8 & 0xFF) / 255f * ia;
            float ob = (c & 0xFF) / 255f * a + (o & 0xFF) / 255f * ia;
            float oa = a + (o >> 24 & 0xFF) / 255f * ia;
            buf[j] = Pack(or, og, ob, oa);
        }
    }

    /// <summary>The Snake's glow: copies this many times its width, at this share of the glow's strength. As the phone's.</summary>
    private static readonly (float Wide, float Share)[] GlowSteps = [(3.2f, 0.18f), (2.2f, 0.3f), (1.5f, 0.45f)];

    /// <summary>
    /// The round-ended piece from (ax, ay) to (bx, by), <paramref name="half"/> pixels each side, at opacity
    /// <paramref name="op"/>: each pixel keeps its strongest cover and that one's colour (this piece's when it ties: the
    /// pieces are drawn newest first); with <paramref name="glow"/> its glow (wider, fainter copies) likewise. Only the
    /// pixels within its reach on each row are looked at, and those it can't make stronger are skipped.
    /// </summary>
    private static void Capsule(State st, int size, float ax, float ay, float bx, float by, float half, int col, float op, float glow, int rowFrom, int rowTo)
    {
        var cov = st.Cov;
        var covCol = st.CovCol;
        var glowCov = st.GlowCov;
        // The most this piece can give a pixel: its opacity, and its glow's at the middle (as worked out below).
        float glowMost = 0f;
        if (glow > 0f)
        {
            float clearMost = 1f;
            foreach (var (_, share) in GlowSteps) clearMost *= 1f - share * glow * 1f;
            glowMost = (1f - clearMost) * op;
        }
        float reach = (glow > 0f ? half * GlowSteps[0].Wide : half) + 1f;
        int x0 = Math.Max(0, (int)MathF.Floor(Math.Min(ax, bx) - reach)), x1 = Math.Min(size - 1, (int)MathF.Ceiling(Math.Max(ax, bx) + reach));
        int y0 = Math.Max(rowFrom, (int)MathF.Floor(Math.Min(ay, by) - reach)), y1 = Math.Min(rowTo, (int)MathF.Ceiling(Math.Max(ay, by) + reach));
        if (y0 > y1) return;
        float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        // Pixels whose middle is further than this from the piece take nothing from it.
        float far = reach + 0.5f, far2 = far * far;
        for (int y = y0; y <= y1; y++)
        {
            // The row's pixels within reach: where its middle line crosses the piece's rounded strip (each end's
            // disc and the band between them), a pixel wider each side.
            float cy = y + 0.5f;
            float lo = float.MaxValue, hi = float.MinValue;
            void Disc(float ox, float oy)
            {
                float h2 = far2 - (cy - oy) * (cy - oy);
                if (h2 < 0f) return;
                float h = MathF.Sqrt(h2);
                lo = Math.Min(lo, ox - h); hi = Math.Max(hi, ox + h);
            }
            Disc(ax, ay);
            Disc(bx, by);
            if (l2 >= 1e-6f)
            {
                // The band between the ends: across the line within far (|cross| ≤ far · len), along it 0 .. 1.
                float len = MathF.Sqrt(l2), ry = cy - ay;
                float s0 = float.MinValue, s1 = float.MaxValue;
                void Keep(float k, float c, float min, float max)
                {
                    // min ≤ k · (x − ax) + c ≤ max
                    if (MathF.Abs(k) < 1e-9f) { if (c < min || c > max) (s0, s1) = (1f, 0f); return; }
                    float u = ax + (min - c) / k, v = ax + (max - c) / k;
                    s0 = Math.Max(s0, Math.Min(u, v)); s1 = Math.Min(s1, Math.Max(u, v));
                }
                Keep(dy, -ry * dx, -far * len, far * len);
                Keep(dx, ry * dy, 0f, l2);
                if (s0 <= s1) { lo = Math.Min(lo, s0); hi = Math.Max(hi, s1); }
            }
            if (lo > hi) continue;
            int rx0 = Math.Max(x0, (int)MathF.Floor(lo - 0.5f) - 1), rx1 = Math.Min(x1, (int)MathF.Ceiling(hi - 0.5f) + 1);
            for (int x = rx0; x <= rx1; x++)
            {
                int k = y * size + x;
                bool line = op >= cov[k], halo = glow > 0f && glowMost >= glowCov[k];
                if (!line && !halo) continue;
                float px = x + 0.5f - ax, py = y + 0.5f - ay;
                float t = l2 < 1e-6f ? 0f : Math.Clamp((px * dx + py * dy) / l2, 0f, 1f);
                float ex = px - dx * t, ey = py - dy * t;
                float d = MathF.Sqrt(ex * ex + ey * ey);
                if (line)
                {
                    float a = Math.Clamp(half - d + 0.5f, 0f, 1f) * op;
                    if (a > 0f && a >= cov[k]) { cov[k] = a; covCol[k] = col; }
                }
                if (halo)
                {
                    float clear = 1f;
                    foreach (var (wide, share) in GlowSteps) clear *= 1f - share * glow * Math.Clamp(half * wide - d + 0.5f, 0f, 1f);
                    float g = (1f - clear) * op;
                    if (g > 0f && g >= glowCov[k]) { glowCov[k] = g; st.GlowCol[k] = col; }
                }
            }
        }
    }
}

/// <summary>One piece of the Snake: from (Ax, Ay) to (Bx, By), Half pixels each side, colour Col at opacity Op (picture pixels).</summary>
internal struct SnakePiece
{
    public float Ax, Ay, Bx, By, Half, Op;
    public int Col;
}

/// <summary>A half's Snake pieces, oldest first.</summary>
internal sealed class PaintRendererPieces
{
    public SnakePiece[] Items = new SnakePiece[256];
    public int Count;
}
