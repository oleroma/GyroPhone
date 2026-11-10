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
        public float[] Cov = [];
        public int[] CovCol = [];
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
            return;
        }
        var st = States.GetOrCreateValue(trace);
        float e = r * 1.05f;
        int size = Math.Max(16, Math.Min((int)MathF.Ceiling(2 * e), MaxSize[Math.Clamp(s.LaserPaintDetail, 0, 2)]));
        var colX = LaserTrace.ToView(1f, 0f, 0f, s.LaserAxonometric, turned);
        var colY = LaserTrace.ToView(0f, 1f, 0f, s.LaserAxonometric, turned);
        var colZ = LaserTrace.ToView(0f, 0f, 1f, s.LaserAxonometric, turned);
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
            HashCode.Combine(s.Perspective, s.LaserColourByWay, s.LaserShade, s.LaserInside, s.LaserLight, s.LaserLightFrom, s.LaserShine, s.LaserAxonometric),
            HashCode.Combine(s.LaserSnakeDeg, s.LaserBrush, s.Color("fire"), s.Color("fire_behind"), s.Color("aim"), s.Color("aim_behind"), s.LaserFireBrush, s.LaserAimBrush),
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
    /// Snake: the line along the newest stretch of the strokes, its near or far pieces, Brush wide at its head thinning
    /// to a fifth at its tail, coloured and shaded as the paint; each pixel takes the strongest piece over it, then the
    /// line goes over the picture at the colours' opacity, so overlaps don't darken. As the phone's snake.
    /// </summary>
    private static void Snake(State st, int[] buf, int size, float e, LaserTrace trace, OverlaySettings s, bool front, float r,
        float[]? turned, uint[] moves)
    {
        var strokes = trace.Strokes;
        int n = strokes.Count;
        if (st.Cov.Length != size * size)
        {
            st.Cov = new float[size * size];
            st.CovCol = new int[size * size];
        }
        else Array.Clear(st.Cov);
        var cov = st.Cov;
        var covCol = st.CovCol;
        float toBuf = size / (2f * e);
        float width = Math.Max(1.5f, r * Math.Clamp(s.LaserBrush, 1, 20) * MathF.PI / 180f * 0.6f) * toBuf;
        float len = Math.Max(1f, s.LaserSnakeDeg);
        float alpha = 0f;
        foreach (var m in moves) alpha += (m >> 24) / 255f;
        alpha /= 6f;
        var fa = Color.FromArgb(s.Color("fire"));
        var fb = Color.FromArgb(s.Color("fire_behind"));
        var aa = Color.FromArgb(s.Color("aim"));
        var ab = Color.FromArgb(s.Color("aim_behind"));
        int brush = Math.Clamp(s.LaserBrush, 1, 20);
        // A Fire or Aim line's width as a share of the beam's.
        float fireW = Math.Clamp(s.LaserFireBrush, 1, 20) / (float)brush, aimW = Math.Clamp(s.LaserAimBrush, 1, 20) / (float)brush;
        var light = LightDir(s.LaserLightFrom);
        var hv = LaserTrace.Unit(light.X, light.Y, light.Z + 1f);
        // The way colours' middle, worked out once for all the points.
        var mix = new LaserTrace.WayMix(moves);
        int reach = s.LaserSmoothColours ? LaserTrace.SmoothReach : 0;
        (float X, float Y, float Z) Pic(float x, float y, float z) => LaserTrace.ToView(x, y, z, s.LaserAxonometric, turned);
        float bx0 = 0, by0 = 0;
        bool any = false;
        for (int i = 0; i < n; i++)
        {
            var p = strokes.Point(i);
            var v = Pic(p.X, p.Y, p.Z);
            float k = s.Perspective ? LaserTrace.Perspective(v.Z) : 1f;
            float bx = (v.X * k * r + e) * toBuf, by = (e - v.Y * k * r) * toBuf;
            if (i > 0 && !strokes.Gap(i) && v.Z > 0f == front)
            {
                // Its colour: where it is in the scene, or the way it moved; shots in the Fire colours.
                var gs = turned == null ? (p.X, p.Y, p.Z) : (
                    turned[0] * p.X + turned[3] * p.Y + turned[6] * p.Z,
                    turned[1] * p.X + turned[4] * p.Y + turned[7] * p.Z,
                    turned[2] * p.X + turned[5] * p.Y + turned[8] * p.Z);
                float cr, cg, cb;
                int kind = strokes.Kind(i);
                if (kind != LaserPaint.PaintKind)
                {
                    float kb = LaserTrace.Behind(gs.Item3);
                    var (ca, cz) = kind == LaserPaint.FireKind ? (fa, fb) : (aa, ab);
                    cr = (ca.R + (cz.R - ca.R) * kb) / 255f; cg = (ca.G + (cz.G - ca.G) * kb) / 255f; cb = (ca.B + (cz.B - ca.B) * kb) / 255f;
                }
                else
                {
                    uint argb = mix.Of(s.LaserColourByWay ? strokes.Direction(i, reach) : gs);
                    cr = (argb >> 16 & 0xFF) / 255f; cg = (argb >> 8 & 0xFF) / 255f; cb = (argb & 0xFF) / 255f;
                }
                float facing = Math.Min(MathF.Abs(v.Z), 1f);
                float kk = (1f + (0.25f + 0.75f * facing - 1f) * s.LaserShade) * (front ? 1f : s.LaserInside);
                float nx = front ? v.X : -v.X, ny = front ? v.Y : -v.Y, nz = front ? v.Z : -v.Z;
                kk *= 1f + (0.3f + 0.9f * Math.Max(nx * light.X + ny * light.Y + nz * light.Z, 0f) - 1f) * s.LaserLight;
                float spec = s.LaserShine * MathF.Pow(Math.Max(nx * hv.X + ny * hv.Y + nz * hv.Z, 0f), 24f);
                int col = Pack(Math.Min(cr * kk + spec, 1f), Math.Min(cg * kk + spec, 1f), Math.Min(cb * kk + spec, 1f), 1f);
                float tail = Math.Clamp(1f - strokes.BehindNewest(i) / len, 0f, 1f);
                float kw = kind == LaserPaint.FireKind ? fireW : kind == LaserPaint.AimKind ? aimW : 1f;
                float half = width * kw * (0.2f + 0.8f * tail) * k / 2f;
                Capsule(cov, covCol, size, bx0, by0, bx, by, half, col);
                any = true;
            }
            bx0 = bx; by0 = by;
        }
        if (!any) return;
        // Over the paint at the colours' opacity.
        for (int j = 0; j < cov.Length; j++)
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

    /// <summary>The round-ended piece from (ax, ay) to (bx, by), <paramref name="half"/> pixels each side: each pixel keeps its strongest cover and that one's colour.</summary>
    private static void Capsule(float[] cov, int[] covCol, int size, float ax, float ay, float bx, float by, float half, int col)
    {
        float reach = half + 1f;
        int x0 = Math.Max(0, (int)MathF.Floor(Math.Min(ax, bx) - reach)), x1 = Math.Min(size - 1, (int)MathF.Ceiling(Math.Max(ax, bx) + reach));
        int y0 = Math.Max(0, (int)MathF.Floor(Math.Min(ay, by) - reach)), y1 = Math.Min(size - 1, (int)MathF.Ceiling(Math.Max(ay, by) + reach));
        float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        for (int y = y0; y <= y1; y++)
        {
            for (int x = x0; x <= x1; x++)
            {
                float px = x + 0.5f - ax, py = y + 0.5f - ay;
                float t = l2 < 1e-6f ? 0f : Math.Clamp((px * dx + py * dy) / l2, 0f, 1f);
                float ex = px - dx * t, ey = py - dy * t;
                float d = MathF.Sqrt(ex * ex + ey * ey);
                float a = Math.Clamp(half - d + 0.5f, 0f, 1f);
                int k = y * size + x;
                if (a > cov[k]) { cov[k] = a; covCol[k] = col; }
            }
        }
    }
}
