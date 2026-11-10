using System.Drawing;
using GyroPhone.Host.Settings;

namespace GyroPhone.Host;

/// <summary>
/// One half (near or far) of a pointer's paint, as a small square picture of premultiplied pixels (the PC
/// overlay's PaintRenderer shows it): every pixel finds the point of the sphere it shows (in perspective or
/// flat), reads the paint map (<see cref="LaserPaint"/>) round it and shows the paint there.
/// <para>
/// So that a frame only reads the map and mixes colours, what doesn't change from frame to frame is kept in
/// tables: each pixel's point on the sphere, facing, light, highlight and scene colour (until the size, view,
/// perspective or light change), and where it reads the map (until the sphere turns too). When only the paint
/// changed, just the pixels that read the map's changed tiles (<see cref="LaserPaint.TileVersion"/>) are drawn
/// again; anything else draws the whole half again. The picture is the same as drawing every pixel each time.
/// </para>
/// </summary>
public sealed class PaintPicture
{
    /// <summary>The picture's pixels, <see cref="Size"/> × <see cref="Size"/>, premultiplied ARGB.</summary>
    public int[] Pixels { get; private set; } = [];

    public int Size { get; private set; }

    /// <summary>Goes up whenever <see cref="Pixels"/> change.</summary>
    public long Stamp { get; private set; }

    /// <summary>The scene's light, a unit direction in the picture (x right, y up, z toward you): upper left, above, upper right, front.</summary>
    private static readonly (float X, float Y, float Z)[] Lights =
        [LaserTrace.Unit(-0.5f, 0.6f, 0.6f), LaserTrace.Unit(0f, 0.8f, 0.6f), LaserTrace.Unit(0.5f, 0.6f, 0.6f), (0f, 0f, 1f)];

    /// <summary>The light for Light from <paramref name="from"/> (upper left, above, upper right, front).</summary>
    public static (float X, float Y, float Z) LightDir(int from) => Lights[Math.Clamp(from, 0, 3)];

    // How many pixels are worth spreading over the cores (fewer go faster on this thread).
    private const int ParallelMin = 8192;

    /// <summary>A pixel showing the sphere, as long as the view and light stay: what the map's paint is shown with there.</summary>
    private struct Spot
    {
        public int Pixel;
        public float Px, Py, Pz;
        // The facing shade times the scene's light, the highlight, how far behind (Fire/Aim behind colours), the scene colour.
        public float Kk, Spec, Kb;
        public uint Dir;
    }

    /// <summary>Where a pixel reads the map, as long as the sphere doesn't turn: four texels and their weights.</summary>
    private struct Read
    {
        public int K00, K10, K01, K11;
        public float W00, W10, W01, W11;
    }

    private readonly record struct SpotKey(int Size, bool Perspective, bool Front,
        (float, float, float) SceneX, (float, float, float) SceneY, (float, float, float) SceneZ,
        float Shade, float Inside, float Light, int LightFrom, float Shine,
        uint M0, uint M1, uint M2, uint M3, uint M4, uint M5);

    private readonly record struct ReadKey(SpotKey Spots, int MapWidth, int MapHeight,
        (float, float, float) ColX, (float, float, float) ColY, (float, float, float) ColZ);

    private readonly record struct PaintKey(ReadKey Reads, LaserPaint Map, bool HasWay, bool ByWay,
        float Glow, float FireGlow, float AimGlow, bool Outline, int Fire, int FireBehind, int Aim, int AimBehind, int OutlineColor);

    private Spot[] _spots = [];
    private int _spotCount;
    private SpotKey? _spotKey;
    private Read[] _reads = [];
    private ReadKey? _readKey;
    private PaintKey? _paintKey;
    private long _drawnVersion;
    // For each map tile, the spots that read it (built when first needed): _tileSpots[_tileStart[t] .. _tileStart[t + 1]).
    private int[]? _tileStart, _tileSpots;

    /// <summary>
    /// Brings the picture up to date with paint map <paramref name="lp"/> on the <paramref name="front"/> (near) or far
    /// half, <paramref name="size"/> pixels across showing 1.05 sphere radii each side of the middle; the map's frame
    /// seen as <paramref name="cx"/>, <paramref name="cy"/>, <paramref name="cz"/> (its axes in the picture) and the
    /// scene's as <paramref name="sx"/>, <paramref name="sy"/>, <paramref name="sz"/>; <paramref name="moves"/> the way
    /// colours. Returns whether the pixels changed.
    /// </summary>
    public bool Update(LaserPaint lp, OverlaySettings s, bool front, int size,
        (float X, float Y, float Z) cx, (float X, float Y, float Z) cy, (float X, float Y, float Z) cz,
        (float X, float Y, float Z) sx, (float X, float Y, float Z) sy, (float X, float Y, float Z) sz, uint[] moves)
    {
        var spotKey = new SpotKey(size, s.Perspective, front, sx, sy, sz, s.LaserShade, s.LaserInside, s.LaserLight, s.LaserLightFrom, s.LaserShine,
            moves[0], moves[1], moves[2], moves[3], moves[4], moves[5]);
        if (_spotKey != spotKey) BuildSpots(spotKey, moves);
        var readKey = new ReadKey(spotKey, lp.Width, lp.Height, cx, cy, cz);
        if (_readKey != readKey) BuildReads(readKey);
        var look = new Look(lp, s, moves);
        var paintKey = new PaintKey(readKey, lp, lp.Way != null, s.LaserColourByWay, s.LaserGlow, s.LaserFireGlow, s.LaserAimGlow, s.LaserOutline,
            s.Color("fire"), s.Color("fire_behind"), s.Color("aim"), s.Color("aim_behind"), s.Color("outline"));
        // Read before drawing: a stamp that lands meanwhile marks its tiles past it, so they are drawn next time.
        long version = lp.Version;
        if (Pixels.Length != size * size || Size != size || _paintKey != paintKey)
        {
            if (Pixels.Length != size * size) Pixels = new int[size * size];
            else Array.Clear(Pixels);
            Size = size;
            DrawSpots(look, 0, _spotCount, null);
            _paintKey = paintKey;
        }
        else if (version != _drawnVersion)
        {
            if (!DrawChanged(look, lp)) { _drawnVersion = version; return false; }
        }
        else return false;
        _drawnVersion = version;
        Stamp++;
        return true;
    }

    /// <summary>An empty picture <paramref name="size"/> across (no paint). Returns whether the pixels changed.</summary>
    public bool Empty(int size)
    {
        DropTables();
        if (Pixels.Length == size * size && Size == size && _paintKey == null) return false;
        if (Pixels.Length != size * size) Pixels = new int[size * size];
        else Array.Clear(Pixels);
        Size = size;
        _paintKey = null;
        Stamp++;
        return true;
    }

    /// <summary>
    /// Lets go of the tables (several MB at High detail) while there's no paint to show; the next
    /// <see cref="Update"/> makes them again. The pixels are kept (the canvas keeps its copy by their array).
    /// </summary>
    public void DropTables()
    {
        if (_spotKey == null && _readKey == null && _spots.Length == 0 && _reads.Length == 0 && _tileStart == null) return;
        _spots = [];
        _reads = [];
        _spotCount = 0;
        _spotKey = null;
        _readKey = null;
        _tileStart = null;
        _tileSpots = null;
        // _paintKey stays: it tells Empty whether the pixels show paint. Update makes the tables again
        // (no spot key), which draws the whole half.
    }

    private void BuildSpots(SpotKey key, uint[] moves)
    {
        int size = key.Size;
        bool front = key.Front;
        float eye = key.Perspective ? LaserTrace.CameraDistance : 0f;
        var (sx, sy, sz) = (key.SceneX, key.SceneY, key.SceneZ);
        var light = LightDir(key.LightFrom);
        var hv = LaserTrace.Unit(light.X, light.Y, light.Z + 1f);
        float shade = key.Shade, inside = key.Inside, lightK = key.Light, shine = key.Shine;
        var mix = new LaserTrace.WayMix(moves);
        if (_spots.Length != size * size) _spots = new Spot[size * size];
        var spots = _spots;
        // Rows in parallel, each into its own run of the table, then packed together.
        var rowCount = new int[size];
        float e = 1.05f;
        Parallel.For(0, size, iy =>
        {
            float qy = e - (iy + 0.5f) * 2f * e / size;
            int n = iy * size;
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
                // The point in the scene's frame: what its colour goes by.
                float gx = sx.Item1 * px + sx.Item2 * py + sx.Item3 * pz;
                float gy = sy.Item1 * px + sy.Item2 * py + sy.Item3 * pz;
                float gz = sz.Item1 * px + sz.Item2 * py + sz.Item3 * pz;
                // Facing shade, the far half (seen from inside) darker, and the scene's light on the side you see.
                float kk = (1f + (0.25f + 0.75f * facing - 1f) * shade) * (front ? 1f : inside);
                float nx = front ? px : -px, ny = front ? py : -py, nz = front ? pz : -pz;
                float diffuse = Math.Max(nx * light.X + ny * light.Y + nz * light.Z, 0f);
                kk *= 1f + (0.3f + 0.9f * diffuse - 1f) * lightK;
                float spec = shine * MathF.Pow(Math.Max(nx * hv.X + ny * hv.Y + nz * hv.Z, 0f), 24f);
                spots[n++] = new Spot
                {
                    Pixel = iy * size + ix, Px = px, Py = py, Pz = pz,
                    Kk = kk, Spec = spec, Kb = LaserTrace.Behind(gz), Dir = mix.Of((gx, gy, gz)),
                };
            }
            rowCount[iy] = n - iy * size;
        });
        int count = 0;
        for (int iy = 0; iy < size; iy++)
        {
            if (count != iy * size) Array.Copy(spots, iy * size, spots, count, rowCount[iy]);
            count += rowCount[iy];
        }
        // Only the sphere's pixels are kept (it is made again only when the size, view or light change).
        Array.Resize(ref _spots, count);
        _spotCount = count;
        _spotKey = key;
        _readKey = null;
    }

    private void BuildReads(ReadKey key)
    {
        int w = key.MapWidth, h = key.MapHeight, count = _spotCount;
        var (cx, cy, cz) = (key.ColX, key.ColY, key.ColZ);
        if (_reads.Length != count) _reads = new Read[count];
        var spots = _spots;
        var reads = _reads;
        void One(int i)
        {
            ref var p = ref spots[i];
            // The point in the shown frame, and where it is in the map.
            float vx = cx.Item1 * p.Px + cx.Item2 * p.Py + cx.Item3 * p.Pz;
            float vy = cy.Item1 * p.Px + cy.Item2 * p.Py + cy.Item3 * p.Pz;
            float vz = cz.Item1 * p.Px + cz.Item2 * p.Py + cz.Item3 * p.Pz;
            float lon = MathF.Atan2(vx, -vz), lat = MathF.Asin(Math.Clamp(vy, -1f, 1f));
            float u = (lon / (2f * MathF.PI) + 0.5f) * w - 0.5f, v = (0.5f - lat / MathF.PI) * h - 0.5f;
            int i0 = (int)MathF.Floor(u), j0 = (int)MathF.Floor(v);
            float fx = u - i0, fy = v - j0;
            int ia = ((i0 % w) + w) % w, ib = (ia + 1) % w;
            int ja = Math.Clamp(j0, 0, h - 1), jb = Math.Clamp(j0 + 1, 0, h - 1);
            reads[i] = new Read
            {
                K00 = ja * w + ia, K10 = ja * w + ib, K01 = jb * w + ia, K11 = jb * w + ib,
                W00 = (1 - fx) * (1 - fy), W10 = fx * (1 - fy), W01 = (1 - fx) * fy, W11 = fx * fy,
            };
        }
        if (count >= ParallelMin) Parallel.For(0, (count + 4095) / 4096, c => { for (int i = c * 4096, end = Math.Min(count, i + 4096); i < end; i++) One(i); });
        else for (int i = 0; i < count; i++) One(i);
        _readKey = key;
        _tileStart = null;
        _tileSpots = null;
        _paintKey = null;
    }

    /// <summary>Draws again the pixels that read the map's tiles changed since the last drawing. Returns whether there were any.</summary>
    private bool DrawChanged(in Look look, LaserPaint lp)
    {
        if (_tileStart == null) BuildTiles(lp);
        var start = _tileStart!;
        var list = _tileSpots!;
        int tiles = lp.TileCount, total = 0;
        for (int t = 0; t < tiles; t++)
            if (lp.TileVersion(t) > _drawnVersion) total += start[t + 1] - start[t];
        if (total == 0) return false;
        if (total >= ParallelMin)
        {
            // Many: the whole half at once, spread over the cores (each pixel drawn once).
            DrawSpots(look, 0, _spotCount, null);
            return true;
        }
        for (int t = 0; t < tiles; t++)
            if (lp.TileVersion(t) > _drawnVersion) DrawSpots(look, start[t], start[t + 1], list);
        return true;
    }

    /// <summary>For each map tile, the spots reading any of its texels.</summary>
    private void BuildTiles(LaserPaint lp)
    {
        int tiles = lp.TileCount, count = _spotCount;
        var reads = _reads;
        var start = new int[tiles + 1];
        Span<int> four = stackalloc int[4];
        int Tiles(in Read r, Span<int> into)
        {
            int n = 0;
            foreach (int k in (ReadOnlySpan<int>)[r.K00, r.K10, r.K01, r.K11])
            {
                int t = lp.TileOf(k);
                bool had = false;
                for (int j = 0; j < n; j++) had |= into[j] == t;
                if (!had) into[n++] = t;
            }
            return n;
        }
        for (int i = 0; i < count; i++)
        {
            int n = Tiles(reads[i], four);
            for (int j = 0; j < n; j++) start[four[j] + 1]++;
        }
        for (int t = 0; t < tiles; t++) start[t + 1] += start[t];
        var list = new int[start[tiles]];
        var at = (int[])start.Clone();
        for (int i = 0; i < count; i++)
        {
            int n = Tiles(reads[i], four);
            for (int j = 0; j < n; j++) list[at[four[j]]++] = i;
        }
        _tileStart = start;
        _tileSpots = list;
    }

    /// <summary>Draws spots <paramref name="from"/> .. <paramref name="to"/> (of <paramref name="list"/>, or the table itself).</summary>
    private void DrawSpots(Look look, int from, int to, int[]? list)
    {
        var spots = _spots;
        var reads = _reads;
        var buf = Pixels;
        int n = to - from;
        if (n >= ParallelMin && list == null)
        {
            const int chunk = 2048;
            Parallel.For(0, (n + chunk - 1) / chunk, c =>
            {
                for (int i = from + c * chunk, end = Math.Min(to, i + chunk); i < end; i++) buf[spots[i].Pixel] = look.Shade(spots[i], reads[i]);
            });
            return;
        }
        if (list == null) for (int i = from; i < to; i++) buf[spots[i].Pixel] = look.Shade(spots[i], reads[i]);
        else for (int j = from; j < to; j++) { int i = list[j]; buf[spots[i].Pixel] = look.Shade(spots[i], reads[i]); }
    }

    /// <summary>What a frame's pixels are mixed from: the maps and the colours, worked out once per drawing.</summary>
    private readonly struct Look
    {
        private readonly byte[] _amount, _fire, _aim, _glow, _glowFire, _glowAim;
        private readonly int[]? _way;
        private readonly float _moveAlpha;
        // Fire and Aim colours in front and their steps toward the behind colours (0 .. 255).
        private readonly float _fR, _fG, _fB, _fA, _fdR, _fdG, _fdB, _fdA;
        private readonly float _aR, _aG, _aB, _aA, _adR, _adG, _adB, _adA;
        private readonly Color _oc;
        private readonly float _fireGlowK, _aimGlowK, _glowK, _hotFire, _hotAim;
        private readonly bool _outline;

        public Look(LaserPaint lp, OverlaySettings s, uint[] moves)
        {
            _amount = lp.Amount; _fire = lp.Fire; _aim = lp.Aim; _glow = lp.Glow; _glowFire = lp.GlowFire; _glowAim = lp.GlowAim;
            _way = s.LaserColourByWay ? lp.Way : null;
            float moveAlpha = 0f;
            foreach (var m in moves) moveAlpha += (m >> 24) / 255f;
            _moveAlpha = moveAlpha / 6f;
            var fa = Color.FromArgb(s.Color("fire"));
            var fb = Color.FromArgb(s.Color("fire_behind"));
            var aa = Color.FromArgb(s.Color("aim"));
            var ab = Color.FromArgb(s.Color("aim_behind"));
            (_fR, _fG, _fB, _fA) = (fa.R, fa.G, fa.B, fa.A);
            (_fdR, _fdG, _fdB, _fdA) = (fb.R - fa.R, fb.G - fa.G, fb.B - fa.B, fb.A - fa.A);
            (_aR, _aG, _aB, _aA) = (aa.R, aa.G, aa.B, aa.A);
            (_adR, _adG, _adB, _adA) = (ab.R - aa.R, ab.G - aa.G, ab.B - aa.B, ab.A - aa.A);
            _oc = Color.FromArgb(s.Color("outline"));
            _fireGlowK = s.LaserFireGlow;
            _aimGlowK = s.LaserAimGlow;
            _glowK = s.LaserGlow;
            _outline = s.LaserOutline;
            _hotFire = Math.Clamp((_fireGlowK - 1f) / 2f, 0f, 1f);
            _hotAim = Math.Clamp((_aimGlowK - 1f) / 2f, 0f, 1f);
        }

        /// <summary>The pixel at spot <paramref name="p"/> reading the map at <paramref name="r"/> (premultiplied ARGB; 0 where there's no paint).</summary>
        public int Shade(in Spot p, in Read r)
        {
            int k00 = r.K00, k10 = r.K10, k01 = r.K01, k11 = r.K11;
            float w00 = r.W00, w10 = r.W10, w01 = r.W01, w11 = r.W11;
            var amount = _amount;
            var glow = _glow;
            float c = (amount[k00] * w00 + amount[k10] * w10 + amount[k01] * w01 + amount[k11] * w11) / 255f;
            float gl = (glow[k00] * w00 + glow[k10] * w10 + glow[k01] * w01 + glow[k11] * w11) / 255f;
            if (c < 0.004f && gl < 0.004f) return 0;
            float cs = Math.Max(c, 0.004f);
            var fire = _fire;
            var aimMap = _aim;
            var glowFire = _glowFire;
            var glowAim = _glowAim;
            float fired = Math.Clamp((fire[k00] * w00 + fire[k10] * w10 + fire[k01] * w01 + fire[k11] * w11) / 255f / cs, 0f, 1f);
            float aimed = Math.Clamp((aimMap[k00] * w00 + aimMap[k10] * w10 + aimMap[k01] * w01 + aimMap[k11] * w11) / 255f / cs, 0f, 1f - fired);
            // The glow's shares that are shots' and aiming's.
            float gsum = Math.Max(gl, 0.004f);
            float gFired = Math.Clamp((glowFire[k00] * w00 + glowFire[k10] * w10 + glowFire[k01] * w01 + glowFire[k11] * w11) / 255f / gsum, 0f, 1f);
            float gAimed = Math.Clamp((glowAim[k00] * w00 + glowAim[k10] * w10 + glowAim[k01] * w01 + glowAim[k11] * w11) / 255f / gsum, 0f, 1f - gFired);
            float br, bg, bb, ba;
            if (_way is { } way)
            {
                float Ch(int sh) => ((way[k00] >> sh & 0xFF) * w00 + (way[k10] >> sh & 0xFF) * w10 + (way[k01] >> sh & 0xFF) * w01 + (way[k11] >> sh & 0xFF) * w11) / 255f / cs;
                br = Math.Min(Ch(16), 1f); bg = Math.Min(Ch(8), 1f); bb = Math.Min(Ch(0), 1f); ba = _moveAlpha;
            }
            else
            {
                uint argb = p.Dir;
                br = (argb >> 16 & 0xFF) / 255f; bg = (argb >> 8 & 0xFF) / 255f; bb = (argb & 0xFF) / 255f; ba = (argb >> 24) / 255f;
            }
            float kb = p.Kb;
            float fr = (_fR + _fdR * kb) / 255f, fg = (_fG + _fdG * kb) / 255f;
            float fbl = (_fB + _fdB * kb) / 255f, fal = (_fA + _fdA * kb) / 255f;
            float ar = (_aR + _adR * kb) / 255f, ag = (_aG + _adG * kb) / 255f;
            float abl = (_aB + _adB * kb) / 255f, aal = (_aA + _adA * kb) / 255f;
            float pb = 1f - fired - aimed, gb = 1f - gFired - gAimed;
            float cr = br * pb + fr * fired + ar * aimed, cg = bg * pb + fg * fired + ag * aimed;
            float cb = bb * pb + fbl * fired + abl * aimed, ca = ba * pb + fal * fired + aal * aimed;
            // The glow takes the colours of what laid it, each as strong as its own glow (up to 3: a bright bloom).
            float hr = br * gb + fr * gFired + ar * gAimed, hg = bg * gb + fg * gFired + ag * gAimed;
            float hb = bb * gb + fbl * gFired + abl * gAimed, ha = ba * gb + fal * gFired + aal * gAimed;
            float gk = _glowK * gb + _fireGlowK * gFired + _aimGlowK * gAimed;
            // Facing shade, inside and the scene's light (kept in the spot), and its highlight.
            float kk = p.Kk, spec = p.Spec;
            cr = Math.Min(cr * kk + spec, 1f); cg = Math.Min(cg * kk + spec, 1f); cb = Math.Min(cb * kk + spec, 1f);
            hr = Math.Min(hr * kk, 1f); hg = Math.Min(hg * kk, 1f); hb = Math.Min(hb * kk, 1f);
            // A glow over 100% also lights the line's core toward white, as hot light.
            float hot = 0.6f * (_hotFire * fired + _hotAim * aimed);
            cr += (1f - cr) * hot; cg += (1f - cg) * hot; cb += (1f - cb) * hot;
            // Glow under an outline under the paint (premultiplied). Over 100% it blooms: brighter, and wider as its
            // faint edge is lifted (gl^(1/k)).
            float pa = c * ca;
            float ga = (gk <= 1f ? gk * 0.45f * gl : Math.Min(0.9f, 0.45f * MathF.Pow(gl, 1f / gk) * MathF.Sqrt(gk))) * ha;
            float or = hr * ga, og = hg * ga, ob = hb * ga, oa = ga;
            if (_outline)
            {
                var oc = _oc;
                float la = Smooth(0.2f, 0.4f, gl) * (1f - c) * oc.A / 255f;
                or = oc.R / 255f * la + or * (1 - la); og = oc.G / 255f * la + og * (1 - la); ob = oc.B / 255f * la + ob * (1 - la); oa = la + oa * (1 - la);
            }
            or = cr * pa + or * (1 - pa); og = cg * pa + og * (1 - pa); ob = cb * pa + ob * (1 - pa); oa = pa + oa * (1 - pa);
            return Pack(or, og, ob, oa);
        }
    }

    public static float Smooth(float a, float b, float x)
    {
        float t = Math.Clamp((x - a) / (b - a), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /// <summary>Premultiplied (r, g, b, a), each 0 .. 1, as one ARGB pixel.</summary>
    public static int Pack(float r, float g, float b, float a) =>
        (int)(a * 255f + 0.5f) << 24 | (int)(r * 255f + 0.5f) << 16 | (int)(g * 255f + 0.5f) << 8 | (int)(b * 255f + 0.5f);
}
