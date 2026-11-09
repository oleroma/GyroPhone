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
/// Each pointer keeps its pictures and draws them again only when something they show changed.
/// </summary>
internal static class PaintRenderer
{
    /// <summary>The picture's most pixels across for Paint detail low, normal, high.</summary>
    private static readonly int[] MaxSize = [160, 256, 420];

    /// <summary>The scene's light, a unit direction in the picture (x right, y up, z toward you): upper left, above, upper right, front.</summary>
    private static readonly (float X, float Y, float Z)[] Lights =
        [LaserTrace.Unit(-0.5f, 0.6f, 0.6f), LaserTrace.Unit(0f, 0.8f, 0.6f), LaserTrace.Unit(0.5f, 0.6f, 0.6f), (0f, 0f, 1f)];

    public static (float X, float Y, float Z) LightDir(int from) => Lights[Math.Clamp(from, 0, 3)];

    private sealed class State
    {
        public int[] Near = [], Far = [];
        public object? NearKey, FarKey;
        public long NearStamp, FarStamp;
        public float[] Cov = [];
        public int[] CovCol = [];
    }

    private static readonly ConditionalWeakTable<LaserTrace, State> States = new();

    /// <summary>What a half's picture shows: when any of it changes it is drawn again.</summary>
    private readonly record struct Key(
        long PaintVersion, int StrokeCount, double Newest, long TraceVersion, int Size, float R,
        (float, float, float) ColX, (float, float, float) ColY, (float, float, float) ColZ,
        (float, float, float) SceneX, (float, float, float) SceneY, (float, float, float) SceneZ, int SettingsHash);

    /// <summary>
    /// Draws the paint and Snake of <paramref name="trace"/> on its <paramref name="front"/> (near) or far half, on
    /// the sphere of radius <paramref name="r"/> at <paramref name="c"/>; <paramref name="turned"/> as
    /// <see cref="LaserTrace.ToView"/> takes it.
    /// </summary>
    public static void Draw(ICanvas g, LaserTrace trace, OverlaySettings s, bool front, PointF c, float r, float[]? turned, uint[] moveColors)
    {
        var lp = s.LaserDraw ? trace.Paint : null;
        bool snake = s.LaserSnake && trace.Strokes.Count >= 2;
        if (lp == null && !snake) return;
        var st = States.GetOrCreateValue(trace);
        float e = r * 1.05f;
        int size = Math.Max(16, Math.Min((int)MathF.Ceiling(2 * e), MaxSize[Math.Clamp(s.LaserPaintDetail, 0, 2)]));
        var colX = LaserTrace.ToView(1f, 0f, 0f, s.LaserAxonometric, turned);
        var colY = LaserTrace.ToView(0f, 1f, 0f, s.LaserAxonometric, turned);
        var colZ = LaserTrace.ToView(0f, 0f, 1f, s.LaserAxonometric, turned);
        var scX = LaserTrace.ToView(1f, 0f, 0f, s.LaserAxonometric);
        var scY = LaserTrace.ToView(0f, 1f, 0f, s.LaserAxonometric);
        var scZ = LaserTrace.ToView(0f, 0f, 1f, s.LaserAxonometric);
        int hash = HashCode.Combine(
            HashCode.Combine(s.Perspective, s.LaserColourByWay, s.LaserGlow, s.LaserOutline, s.LaserShade, s.LaserInside, s.LaserLight, s.LaserLightFrom),
            HashCode.Combine(s.LaserShine, s.LaserSnake, s.LaserSnakeDeg, s.LaserBrush, s.LaserDraw, s.Color("fire"), s.Color("fire_behind"), s.Color("outline")),
            HashCode.Combine(moveColors[0], moveColors[1], moveColors[2], moveColors[3], moveColors[4], moveColors[5], s.LaserSmoothColours));
        var key = new Key(lp?.Version ?? -1, trace.Strokes.Count, trace.Strokes.Count > 0 ? trace.Strokes.Along(trace.Strokes.Count - 1) : 0,
            trace.Version, size, r, colX, colY, colZ, scX, scY, scZ, hash);
        ref int[] buf = ref front ? ref st.Near : ref st.Far;
        object? had = front ? st.NearKey : st.FarKey;
        if (buf.Length != size * size || had is not Key k || k != key)
        {
            if (buf.Length != size * size) buf = new int[size * size];
            else Array.Clear(buf);
            if (lp != null) Paint(buf, size, lp, s, front, colX, colY, colZ, scX, scY, scZ, moveColors);
            if (snake) Snake(st, buf, size, e, trace, s, front, r, turned, moveColors);
            if (front) { st.NearKey = key; st.NearStamp++; } else { st.FarKey = key; st.FarStamp++; }
        }
        g.DrawPixels(new RectangleF(c.X - e, c.Y - e, 2 * e, 2 * e), size, size, buf, front ? st.NearStamp : st.FarStamp);
    }

    private static float Smooth(float a, float b, float x)
    {
        float t = Math.Clamp((x - a) / (b - a), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static void Paint(int[] buf, int size, LaserPaint lp, OverlaySettings s, bool front,
        (float X, float Y, float Z) cx, (float X, float Y, float Z) cy, (float X, float Y, float Z) cz,
        (float X, float Y, float Z) sx, (float X, float Y, float Z) sy, (float X, float Y, float Z) sz, uint[] moves)
    {
        float eye = s.Perspective ? LaserTrace.CameraDistance : 0f;
        int w = lp.Width, h = lp.Height;
        var amount = lp.Amount;
        var fire = lp.Fire;
        var glow = lp.Glow;
        var way = s.LaserColourByWay ? lp.Way : null;
        float moveAlpha = 0f;
        foreach (var m in moves) moveAlpha += (m >> 24) / 255f;
        moveAlpha /= 6f;
        var fa = Color.FromArgb(s.Color("fire"));
        var fb = Color.FromArgb(s.Color("fire_behind"));
        var oc = Color.FromArgb(s.Color("outline"));
        var light = LightDir(s.LaserLightFrom);
        var hv = LaserTrace.Unit(light.X, light.Y, light.Z + 1f);
        float glowK = s.LaserGlow, shade = s.LaserShade, inside = s.LaserInside, lightK = s.LaserLight, shine = s.LaserShine;
        bool outline = s.LaserOutline;
        float e = 1.05f;
        // Rows in parallel: each writes only its own pixels.
        Parallel.For(0, size, iy =>
        {
            float qy = e - (iy + 0.5f) * 2f * e / size;
            for (int ix = 0; ix < size; ix++)
            {
                float qx = -e + (ix + 0.5f) * 2f * e / size;
                float px, py, pz, facing;
                if (eye > 0f)
                {
                    var d = LaserTrace.Unit(qx, qy, -eye);
                    float b = eye * d.Z;
                    float disc = b * b - (eye * eye - 1f);
                    if (disc < 0f) continue;
                    float t = front ? -b - MathF.Sqrt(disc) : -b + MathF.Sqrt(disc);
                    px = d.X * t; py = d.Y * t; pz = eye + d.Z * t;
                    facing = MathF.Abs(px * d.X + py * d.Y + pz * d.Z);
                }
                else
                {
                    float rr = qx * qx + qy * qy;
                    if (rr >= 1f) continue;
                    float z = MathF.Sqrt(1f - rr);
                    px = qx; py = qy; pz = front ? z : -z;
                    facing = z;
                }
                // The point in the shown frame, and where it is in the map.
                float vx = cx.X * px + cx.Y * py + cx.Z * pz;
                float vy = cy.X * px + cy.Y * py + cy.Z * pz;
                float vz = cz.X * px + cz.Y * py + cz.Z * pz;
                float lon = MathF.Atan2(vx, -vz), lat = MathF.Asin(Math.Clamp(vy, -1f, 1f));
                float u = (lon / (2f * MathF.PI) + 0.5f) * w - 0.5f, v = (0.5f - lat / MathF.PI) * h - 0.5f;
                int i0 = (int)MathF.Floor(u), j0 = (int)MathF.Floor(v);
                float fx = u - i0, fy = v - j0;
                int ia = ((i0 % w) + w) % w, ib = (ia + 1) % w;
                int ja = Math.Clamp(j0, 0, h - 1), jb = Math.Clamp(j0 + 1, 0, h - 1);
                int k00 = ja * w + ia, k10 = ja * w + ib, k01 = jb * w + ia, k11 = jb * w + ib;
                float w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy), w01 = (1 - fx) * fy, w11 = fx * fy;
                float c = (amount[k00] * w00 + amount[k10] * w10 + amount[k01] * w01 + amount[k11] * w11) / 255f;
                float gl = (glow[k00] * w00 + glow[k10] * w10 + glow[k01] * w01 + glow[k11] * w11) / 255f;
                if (c < 0.004f && gl < 0.004f) continue;
                float cs = Math.Max(c, 0.004f);
                float fired = Math.Clamp((fire[k00] * w00 + fire[k10] * w10 + fire[k01] * w01 + fire[k11] * w11) / 255f / cs, 0f, 1f);
                // The point in the scene's frame: what its colour goes by.
                float gx = sx.X * px + sx.Y * py + sx.Z * pz;
                float gy = sy.X * px + sy.Y * py + sy.Z * pz;
                float gz = sz.X * px + sz.Y * py + sz.Z * pz;
                float br, bg, bb, ba;
                if (way != null)
                {
                    float Ch(int sh) => ((way[k00] >> sh & 0xFF) * w00 + (way[k10] >> sh & 0xFF) * w10 + (way[k01] >> sh & 0xFF) * w01 + (way[k11] >> sh & 0xFF) * w11) / 255f / cs;
                    br = Math.Min(Ch(16), 1f); bg = Math.Min(Ch(8), 1f); bb = Math.Min(Ch(0), 1f); ba = moveAlpha;
                }
                else
                {
                    uint argb = LaserTrace.DirectionColor((gx, gy, gz), moves);
                    br = (argb >> 16 & 0xFF) / 255f; bg = (argb >> 8 & 0xFF) / 255f; bb = (argb & 0xFF) / 255f; ba = (argb >> 24) / 255f;
                }
                float kb = LaserTrace.Behind(gz);
                float fr = (fa.R + (fb.R - fa.R) * kb) / 255f, fg = (fa.G + (fb.G - fa.G) * kb) / 255f;
                float fbl = (fa.B + (fb.B - fa.B) * kb) / 255f, fal = (fa.A + (fb.A - fa.A) * kb) / 255f;
                float cr = br + (fr - br) * fired, cg = bg + (fg - bg) * fired, cb = bb + (fbl - bb) * fired, ca = ba + (fal - ba) * fired;
                // Facing shade, the far half (seen from inside) darker, and the scene's light on the side you see.
                float kk = (1f + (0.25f + 0.75f * facing - 1f) * shade) * (front ? 1f : inside);
                float nx = front ? px : -px, ny = front ? py : -py, nz = front ? pz : -pz;
                float diffuse = Math.Max(nx * light.X + ny * light.Y + nz * light.Z, 0f);
                kk *= 1f + (0.3f + 0.9f * diffuse - 1f) * lightK;
                float spec = shine * MathF.Pow(Math.Max(nx * hv.X + ny * hv.Y + nz * hv.Z, 0f), 24f);
                cr = Math.Min(cr * kk + spec, 1f); cg = Math.Min(cg * kk + spec, 1f); cb = Math.Min(cb * kk + spec, 1f);
                // Glow under an outline under the paint (premultiplied).
                float pa = c * ca, ga = glowK * 0.45f * gl * ca;
                float or = cr * ga, og = cg * ga, ob = cb * ga, oa = ga;
                if (outline)
                {
                    float la = Smooth(0.2f, 0.4f, gl) * (1f - c) * oc.A / 255f;
                    or = oc.R / 255f * la + or * (1 - la); og = oc.G / 255f * la + og * (1 - la); ob = oc.B / 255f * la + ob * (1 - la); oa = la + oa * (1 - la);
                }
                or = cr * pa + or * (1 - pa); og = cg * pa + og * (1 - pa); ob = cb * pa + ob * (1 - pa); oa = pa + oa * (1 - pa);
                buf[iy * size + ix] = Pack(or, og, ob, oa);
            }
        });
    }

    private static int Pack(float r, float g, float b, float a) =>
        (int)(a * 255f + 0.5f) << 24 | (int)(r * 255f + 0.5f) << 16 | (int)(g * 255f + 0.5f) << 8 | (int)(b * 255f + 0.5f);

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
        var light = LightDir(s.LaserLightFrom);
        var hv = LaserTrace.Unit(light.X, light.Y, light.Z + 1f);
        int reach = s.LaserSmoothColours ? LaserTrace.SmoothReach : 0;
        (float X, float Y, float Z) Pic(float x, float y, float z) => LaserTrace.ToView(x, y, z, s.LaserAxonometric, turned);
        float bx0 = 0, by0 = 0;
        bool any = false;
        for (int i = 0; i < n; i++)
        {
            var p = strokes.Point(i);
            var v = Pic(p.X, p.Y, p.Z);
            float k = s.Perspective ? LaserTrace.Perspective(v.Z) : 1f;
            float bx = (v.X * k + e) * r * toBuf, by = (e - v.Y * k) * r * toBuf;
            if (i > 0 && !strokes.Gap(i) && v.Z > 0f == front)
            {
                // Its colour: where it is in the scene, or the way it moved; shots in the Fire colours.
                var gs = turned == null ? (p.X, p.Y, p.Z) : (
                    turned[0] * p.X + turned[3] * p.Y + turned[6] * p.Z,
                    turned[1] * p.X + turned[4] * p.Y + turned[7] * p.Z,
                    turned[2] * p.X + turned[5] * p.Y + turned[8] * p.Z);
                float cr, cg, cb;
                if (strokes.Fire(i))
                {
                    float kb = LaserTrace.Behind(gs.Item3);
                    cr = (fa.R + (fb.R - fa.R) * kb) / 255f; cg = (fa.G + (fb.G - fa.G) * kb) / 255f; cb = (fa.B + (fb.B - fa.B) * kb) / 255f;
                }
                else
                {
                    uint argb = LaserTrace.DirectionColor(s.LaserColourByWay ? strokes.Direction(i, reach) : gs, moves);
                    cr = (argb >> 16 & 0xFF) / 255f; cg = (argb >> 8 & 0xFF) / 255f; cb = (argb & 0xFF) / 255f;
                }
                float facing = Math.Min(MathF.Abs(v.Z), 1f);
                float kk = (1f + (0.25f + 0.75f * facing - 1f) * s.LaserShade) * (front ? 1f : s.LaserInside);
                float nx = front ? v.X : -v.X, ny = front ? v.Y : -v.Y, nz = front ? v.Z : -v.Z;
                kk *= 1f + (0.3f + 0.9f * Math.Max(nx * light.X + ny * light.Y + nz * light.Z, 0f) - 1f) * s.LaserLight;
                float spec = s.LaserShine * MathF.Pow(Math.Max(nx * hv.X + ny * hv.Y + nz * hv.Z, 0f), 24f);
                int col = Pack(Math.Min(cr * kk + spec, 1f), Math.Min(cg * kk + spec, 1f), Math.Min(cb * kk + spec, 1f), 1f);
                float tail = Math.Clamp(1f - strokes.BehindNewest(i) / len, 0f, 1f);
                float half = width * (0.2f + 0.8f * tail) * k / 2f;
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
