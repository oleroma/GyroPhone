using System.Drawing;
using GyroPhone.Host;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tests;

/// <summary>
/// The PC's paint picture (<see cref="PaintPicture"/>), kept up to date stamp by stamp from its tables, must look
/// exactly as drawing every pixel from scratch did (the PaintRenderer before the tables, kept here as
/// <see cref="Reference"/>).
/// </summary>
public class PaintPictureTests
{
    private static (float X, float Y, float Z) At(float deg, float up = 0f) => LaserTrace.Dir(up * MathF.PI / 180f, deg * MathF.PI / 180f);

    // A line from (from, up) to (to, upTo) in 0.5° steps, as the trace stamps it.
    private static void Line(LaserPaint p, float from, float to, float up, float upTo, int kind, float half = 2f, int? way = null)
    {
        p.NewLine();
        double along = 0;
        int steps = (int)MathF.Ceiling(MathF.Abs(to - from) / 0.5f);
        for (int i = 0; i < steps; i++)
        {
            float t0 = i / (float)steps, t1 = (i + 1) / (float)steps;
            var a = At(from + (to - from) * t0, up + (upTo - up) * t0);
            var b = At(from + (to - from) * t1, up + (upTo - up) * t1);
            along += 0.5;
            p.Stamp(a.X, a.Y, a.Z, b.X, b.Y, b.Z, along, half, kind, 0.5f, way);
        }
    }

    private static uint[] Moves(OverlaySettings s)
    {
        var m = new uint[6];
        for (int i = 0; i < 6; i++) m[i] = unchecked((uint)s.Color(OverlaySettings.LaserMoves[i]));
        return m;
    }

    private static float[] Turn(float yawDeg, float pitchDeg)
    {
        float a = yawDeg * MathF.PI / 180f, b = pitchDeg * MathF.PI / 180f;
        float ca = MathF.Cos(a), sa = MathF.Sin(a), cb = MathF.Cos(b), sb = MathF.Sin(b);
        // Yaw about y, then pitch about x (row-major).
        float[] y = [ca, 0, sa, 0, 1, 0, -sa, 0, ca];
        float[] x = [1, 0, 0, 0, cb, -sb, 0, sb, cb];
        var m = new float[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++)
                for (int k = 0; k < 3; k++) m[r * 3 + c] += x[r * 3 + k] * y[k * 3 + c];
        return m;
    }

    private static int Check(PaintPicture pic, LaserPaint lp, OverlaySettings s, bool front, int size, float[]? turned, string what)
    {
        var colX = LaserTrace.ToView(1f, 0f, 0f, s.LaserAxonometric, turned);
        var colY = LaserTrace.ToView(0f, 1f, 0f, s.LaserAxonometric, turned);
        var colZ = LaserTrace.ToView(0f, 0f, 1f, s.LaserAxonometric, turned);
        var scX = LaserTrace.ToView(1f, 0f, 0f, s.LaserAxonometric);
        var scY = LaserTrace.ToView(0f, 1f, 0f, s.LaserAxonometric);
        var scZ = LaserTrace.ToView(0f, 0f, 1f, s.LaserAxonometric);
        var moves = Moves(s);
        pic.Update(lp, s, front, size, colX, colY, colZ, scX, scY, scZ, moves);
        var want = new int[size * size];
        Reference.Paint(want, size, lp, s, front, colX, colY, colZ, scX, scY, scZ, moves);
        int painted = 0;
        for (int i = 0; i < want.Length; i++)
        {
            if (want[i] != 0) painted++;
            if (pic.Pixels[i] != want[i]) Assert.Fail($"{what}: pixel {i % size},{i / size} is {pic.Pixels[i]:X8}, drawn from scratch {want[i]:X8}");
        }
        return painted;
    }

    public static IEnumerable<object[]> Looks()
    {
        // size, perspective, axonometric, colour by way, outline, glow, sphere turned
        yield return [64, true, true, false, false, 0.5f, false];
        yield return [256, true, true, true, true, 0.5f, false];
        yield return [160, false, true, false, true, 2f, true];
        yield return [97, false, false, true, false, 1f, true];
        yield return [420, true, false, false, false, 0.5f, true];
    }

    [Theory]
    [MemberData(nameof(Looks))]
    public void StampByStampLooksAsDrawnFromScratch(int size, bool perspective, bool axon, bool byWay, bool outline, float glow, bool turn)
    {
        var s = new OverlaySettings
        {
            Kind = OverlayKind.Laser, Perspective = perspective, LaserAxonometric = axon, LaserColourByWay = byWay,
            LaserOutline = outline, LaserGlow = glow, LaserFireGlow = 2.5f, LaserAimGlow = 0.5f, LaserShine = 0.3f, LaserLight = 0.5f,
        };
        var lp = new LaserPaint(size >= 256 ? 1024 : 512, size >= 256 ? 512 : 256);
        int? way = byWay ? 0x80C040 : null;
        float[]? turned = turn ? Turn(40f, 25f) : null;
        var near = new PaintPicture();
        var far = new PaintPicture();
        int Both(string what) => Check(near, lp, s, true, size, turned, what + " near") + Check(far, lp, s, false, size, turned, what + " far");
        Assert.Equal(0, Both("empty"));
        // Short strokes as the beam moves, each checked: only their tiles are drawn again.
        for (int k = 0; k < 6; k++)
        {
            Line(lp, -20f + k * 3f, -17f + k * 3f, 5f, 6f, LaserPaint.PaintKind, way: way);
            Assert.True(Both($"stroke {k}") > 0);
        }
        Line(lp, 170f, 195f, -10f, 12f, LaserPaint.FireKind, 3f, way); // round the map's seam, behind
        Both("fire across the seam");
        Line(lp, 30f, 50f, 70f, 85f, LaserPaint.AimKind, 1.5f, way); // near the top
        Both("aim near the pole");
        Line(lp, 0f, 1f, -89f, -88f, LaserPaint.PaintKind, 4f, way); // at the bottom: a whole row of the map
        Both("the pole");
        // The sphere turns: the whole half again.
        turned = Turn(-70f, 10f);
        Both("turned");
        Line(lp, 90f, 120f, 0f, -30f, LaserPaint.PaintKind, way: way);
        Both("turned then stroke");
        s.LaserLightFrom = 2;
        s.LaserOutline = !s.LaserOutline;
        Both("look changed");
        lp.Clear();
        Both("cleared");
        Line(lp, -5f, 5f, 0f, 0f, LaserPaint.FireKind, way: way);
        Assert.True(Both("after clear") > 0);
    }

    [Fact]
    public void DroppedTablesAreMadeAgainAndEmptyClearsThePaint()
    {
        var s = new OverlaySettings { Kind = OverlayKind.Laser };
        var lp = new LaserPaint(512, 256);
        var pic = new PaintPicture();
        // The lines are ahead, into the screen: on the far half.
        Line(lp, -10f, 10f, 0f, 5f, LaserPaint.PaintKind);
        Assert.True(Check(pic, lp, s, false, 96, null, "first") > 0);
        pic.DropTables();
        Line(lp, 20f, 30f, -5f, 0f, LaserPaint.FireKind);
        Assert.True(Check(pic, lp, s, false, 96, null, "after dropping the tables") > 0);
        // Dropped while painted, then shown with no paint (Snake alone): the picture is empty.
        pic.DropTables();
        long stamp = pic.Stamp;
        Assert.True(pic.Empty(96));
        Assert.True(pic.Stamp > stamp);
        Assert.All(pic.Pixels, p => Assert.Equal(0, p));
        Assert.False(pic.Empty(96));
    }

    [Fact]
    public void WayMixIsDirectionColor()
    {
        uint[] colors = [0xFFFF8080, 0x8000FFFF, 0xC080FF80, 0xFF8000FF, 0x404080FF, 0xFF808000];
        var mix = new LaserTrace.WayMix(colors);
        foreach (var d in new[] { (0f, 0f, 0f), (1f, 0f, 0f), (-0.6f, 0.8f, 0f), (0.3f, -0.4f, -0.866f), (0f, 0f, 1f) })
            Assert.Equal(Reference.DirectionColor(d, colors), mix.Of(d));
    }

    [Fact]
    public void TilesAreMarkedWhereAStampChangesTheMap()
    {
        var lp = new LaserPaint(512, 256);
        long before = lp.Version;
        var a = At(0f);
        var b = At(1f);
        lp.Stamp(a.X, a.Y, a.Z, b.X, b.Y, b.Z, 1, 1f, LaserPaint.PaintKind);
        for (int k = 0; k < lp.Amount.Length; k++)
            if (lp.Amount[k] != 0 || lp.Glow[k] != 0) Assert.True(lp.TileVersion(lp.TileOf(k)) > before, $"texel {k} changed, its tile not marked");
        int marked = 0;
        for (int t = 0; t < lp.TileCount; t++) if (lp.TileVersion(t) > before) marked++;
        Assert.InRange(marked, 1, 8); // only round the stamp
    }

    /// <summary>The PC's paint, drawn every pixel from scratch: PaintRenderer.Paint as it was before the tables.</summary>
    private static class Reference
    {
        public static uint DirectionColor((float X, float Y, float Z) d, ReadOnlySpan<uint> colors)
        {
            uint argb = 0;
            Span<float> p = stackalloc float[] { d.X, d.Y, d.Z };
            for (int ch = 0; ch < 4; ch++)
            {
                int shift = ch * 8;
                float mid = 0f;
                for (int i = 0; i < 6; i++) mid += (colors[i] >> shift) & 0xFF;
                mid /= 6f;
                float v = mid;
                for (int a = 0; a < 3; a++)
                {
                    float c = (colors[p[a] >= 0f ? a * 2 : a * 2 + 1] >> shift) & 0xFF;
                    v += MathF.Abs(p[a]) * (c - mid);
                }
                argb |= (uint)Math.Clamp((int)(v + 0.5f), 0, 255) << shift;
            }
            return argb;
        }

        private static readonly (float X, float Y, float Z)[] Lights =
            [LaserTrace.Unit(-0.5f, 0.6f, 0.6f), LaserTrace.Unit(0f, 0.8f, 0.6f), LaserTrace.Unit(0.5f, 0.6f, 0.6f), (0f, 0f, 1f)];

        private static float Smooth(float a, float b, float x)
        {
            float t = Math.Clamp((x - a) / (b - a), 0f, 1f);
            return t * t * (3f - 2f * t);
        }

        private static int Pack(float r, float g, float b, float a) =>
            (int)(a * 255f + 0.5f) << 24 | (int)(r * 255f + 0.5f) << 16 | (int)(g * 255f + 0.5f) << 8 | (int)(b * 255f + 0.5f);

        public static void Paint(int[] buf, int size, LaserPaint lp, OverlaySettings s, bool front,
            (float X, float Y, float Z) cx, (float X, float Y, float Z) cy, (float X, float Y, float Z) cz,
            (float X, float Y, float Z) sx, (float X, float Y, float Z) sy, (float X, float Y, float Z) sz, uint[] moves)
        {
            float eye = s.Perspective ? LaserTrace.CameraDistance : 0f;
            int w = lp.Width, h = lp.Height;
            var amount = lp.Amount;
            var fire = lp.Fire;
            var aimMap = lp.Aim;
            var glow = lp.Glow;
            var glowFire = lp.GlowFire;
            var glowAim = lp.GlowAim;
            var way = s.LaserColourByWay ? lp.Way : null;
            float moveAlpha = 0f;
            foreach (var m in moves) moveAlpha += (m >> 24) / 255f;
            moveAlpha /= 6f;
            var fa = Color.FromArgb(s.Color("fire"));
            var fb = Color.FromArgb(s.Color("fire_behind"));
            var aa = Color.FromArgb(s.Color("aim"));
            var ab = Color.FromArgb(s.Color("aim_behind"));
            var oc = Color.FromArgb(s.Color("outline"));
            var light = Lights[Math.Clamp(s.LaserLightFrom, 0, 3)];
            var hv = LaserTrace.Unit(light.X, light.Y, light.Z + 1f);
            float fireGlowK = s.LaserFireGlow, aimGlowK = s.LaserAimGlow;
            float glowK = s.LaserGlow, shade = s.LaserShade, inside = s.LaserInside, lightK = s.LaserLight, shine = s.LaserShine;
            bool outline = s.LaserOutline;
            float e = 1.05f;
            for (int iy = 0; iy < size; iy++)
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
                    float aimed = Math.Clamp((aimMap[k00] * w00 + aimMap[k10] * w10 + aimMap[k01] * w01 + aimMap[k11] * w11) / 255f / cs, 0f, 1f - fired);
                    float gsum = Math.Max(gl, 0.004f);
                    float gFired = Math.Clamp((glowFire[k00] * w00 + glowFire[k10] * w10 + glowFire[k01] * w01 + glowFire[k11] * w11) / 255f / gsum, 0f, 1f);
                    float gAimed = Math.Clamp((glowAim[k00] * w00 + glowAim[k10] * w10 + glowAim[k01] * w01 + glowAim[k11] * w11) / 255f / gsum, 0f, 1f - gFired);
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
                        uint argb = DirectionColor((gx, gy, gz), moves);
                        br = (argb >> 16 & 0xFF) / 255f; bg = (argb >> 8 & 0xFF) / 255f; bb = (argb & 0xFF) / 255f; ba = (argb >> 24) / 255f;
                    }
                    float kb = LaserTrace.Behind(gz);
                    float fr = (fa.R + (fb.R - fa.R) * kb) / 255f, fg = (fa.G + (fb.G - fa.G) * kb) / 255f;
                    float fbl = (fa.B + (fb.B - fa.B) * kb) / 255f, fal = (fa.A + (fb.A - fa.A) * kb) / 255f;
                    float ar = (aa.R + (ab.R - aa.R) * kb) / 255f, ag = (aa.G + (ab.G - aa.G) * kb) / 255f;
                    float abl = (aa.B + (ab.B - aa.B) * kb) / 255f, aal = (aa.A + (ab.A - aa.A) * kb) / 255f;
                    float pb = 1f - fired - aimed, gb = 1f - gFired - gAimed;
                    float cr = br * pb + fr * fired + ar * aimed, cg = bg * pb + fg * fired + ag * aimed;
                    float cb = bb * pb + fbl * fired + abl * aimed, ca = ba * pb + fal * fired + aal * aimed;
                    float hr = br * gb + fr * gFired + ar * gAimed, hg = bg * gb + fg * gFired + ag * gAimed;
                    float hb = bb * gb + fbl * gFired + abl * gAimed, ha = ba * gb + fal * gFired + aal * gAimed;
                    float gk = glowK * gb + fireGlowK * gFired + aimGlowK * gAimed;
                    float kk = (1f + (0.25f + 0.75f * facing - 1f) * shade) * (front ? 1f : inside);
                    float nx = front ? px : -px, ny = front ? py : -py, nz = front ? pz : -pz;
                    float diffuse = Math.Max(nx * light.X + ny * light.Y + nz * light.Z, 0f);
                    kk *= 1f + (0.3f + 0.9f * diffuse - 1f) * lightK;
                    float spec = shine * MathF.Pow(Math.Max(nx * hv.X + ny * hv.Y + nz * hv.Z, 0f), 24f);
                    cr = Math.Min(cr * kk + spec, 1f); cg = Math.Min(cg * kk + spec, 1f); cb = Math.Min(cb * kk + spec, 1f);
                    hr = Math.Min(hr * kk, 1f); hg = Math.Min(hg * kk, 1f); hb = Math.Min(hb * kk, 1f);
                    float hot = 0.6f * (Math.Clamp((fireGlowK - 1f) / 2f, 0f, 1f) * fired + Math.Clamp((aimGlowK - 1f) / 2f, 0f, 1f) * aimed);
                    cr += (1f - cr) * hot; cg += (1f - cg) * hot; cb += (1f - cb) * hot;
                    float pa = c * ca;
                    float ga = (gk <= 1f ? gk * 0.45f * gl : Math.Min(0.9f, 0.45f * MathF.Pow(gl, 1f / gk) * MathF.Sqrt(gk))) * ha;
                    float or = hr * ga, og = hg * ga, ob = hb * ga, oa = ga;
                    if (outline)
                    {
                        float la = Smooth(0.2f, 0.4f, gl) * (1f - c) * oc.A / 255f;
                        or = oc.R / 255f * la + or * (1 - la); og = oc.G / 255f * la + og * (1 - la); ob = oc.B / 255f * la + ob * (1 - la); oa = la + oa * (1 - la);
                    }
                    or = cr * pa + or * (1 - pa); og = cg * pa + og * (1 - pa); ob = cb * pa + ob * (1 - pa); oa = pa + oa * (1 - pa);
                    buf[iy * size + ix] = Pack(or, og, ob, oa);
                }
            }
        }
    }
}
