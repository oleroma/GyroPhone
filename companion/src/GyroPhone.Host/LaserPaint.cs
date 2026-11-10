namespace GyroPhone.Host;

/// <summary>
/// Draw mode's paint as a map of the whole sphere's inside, <see cref="Width"/> × <see cref="Height"/> texels by
/// longitude and latitude: texel (i, j) is the direction at longitude (i + ½)/width · 360° − 180° (0 straight ahead,
/// into the screen) and latitude 90° − (j + ½)/height · 180°, as <see cref="LaserTrace.Dir"/> makes it, in the shown
/// frame. The beam's brush stamps it; the overlay reads it for every pixel of the sphere, so the paint follows the
/// sphere's real shape, depth and perspective. Same as the phone's LaserPaint.
///
/// Paint builds up as real paint does: each line lays a soft-edged amount of paint over what is there
/// ("over": a + b·(1 − a)), its colour mixing in by its share, so crossing lines blend. Within one line the brush's
/// stamps overlap; there a texel takes the line's strongest stamp, not their sum, so a line is even along its length.
///
/// Per texel (0 .. 255), premultiplied by the amount so reading between texels blends: <see cref="Amount"/>,
/// <see cref="Fire"/> (the amount that is shots), <see cref="Aim"/> (the amount painted while aiming) and
/// <see cref="Glow"/> (a wide faint halo, built up the same way), with <see cref="GlowFire"/> and <see cref="GlowAim"/>
/// the glow's shares that are shots' and aiming's (premultiplied by the glow), so theirs can shine stronger.
/// With way colours kept, <see cref="Way"/> holds each texel's colour times its amount (RGB).
/// </summary>
public sealed class LaserPaint
{
    public const int DefaultWidth = 1024;

    /// <summary>What painted a stamp: the beam, the beam while aiming, while firing. As the phone's.</summary>
    public const int PaintKind = 0, AimKind = 1, FireKind = 2;

    /// <summary>How far the glow reaches, in brush half widths.</summary>
    public const float Reach = 3f;

    private const double PieceDeg = 4.0;
    // How finely the place along a line is kept (degrees), and the slack added to "the same pass".
    private const double AlongUnit = 0.1;
    private const int SamePassExtra = 20;

    public int Width { get; }
    public int Height { get; }
    public readonly byte[] Amount, Fire, Aim, Glow, GlowFire, GlowAim;

    /// <summary>Each texel's way colour times its amount (RGB), made at the first stamp that keeps one.</summary>
    public int[]? Way { get; private set; }

    /// <summary>Goes up at each change.</summary>
    public long Version { get; private set; }

    /// <summary>The map is kept track of in square tiles this many texels across (2^<see cref="TileShift"/>).</summary>
    public const int TileShift = 5;

    // Each tile's version: the Version a change to it made (marked before its texels change).
    private readonly int _tilesX;
    private readonly long[] _tileVersion;

    /// <summary>How many tiles the map has.</summary>
    public int TileCount => _tileVersion.Length;

    /// <summary>
    /// The <see cref="Version"/> that last changed a texel of tile <paramref name="tile"/>: one reading the map at
    /// version v needs to read again only the tiles over v. A change marks its tiles before it changes their texels.
    /// </summary>
    public long TileVersion(int tile) => _tileVersion[tile];

    /// <summary>The tile holding texel <paramref name="texel"/> (row × <see cref="Width"/> + column).</summary>
    public int TileOf(int texel) => (texel / Width >> TileShift) * _tilesX + (texel % Width >> TileShift);

    private readonly int _n;
    private readonly byte[] _lineAmount, _lineGlow;
    private readonly short[] _lineTag, _lineAlong;
    private short _line;
    private readonly float[] _sinLat, _cosLat, _sinLon, _cosLon;

    public LaserPaint(int width = DefaultWidth, int height = DefaultWidth / 2)
    {
        Width = width;
        Height = height;
        _n = width * height;
        Amount = new byte[_n];
        Fire = new byte[_n];
        Glow = new byte[_n];
        Aim = new byte[_n];
        GlowFire = new byte[_n];
        GlowAim = new byte[_n];
        _lineAmount = new byte[_n];
        _lineGlow = new byte[_n];
        _lineTag = new short[_n];
        _lineAlong = new short[_n];
        _tilesX = (width + (1 << TileShift) - 1) >> TileShift;
        _tileVersion = new long[_tilesX * ((height + (1 << TileShift) - 1) >> TileShift)];
        _sinLat = new float[height];
        _cosLat = new float[height];
        for (int j = 0; j < height; j++)
        {
            _sinLat[j] = (float)Math.Sin(LatOf(j));
            _cosLat[j] = (float)Math.Cos(LatOf(j));
        }
        _sinLon = new float[width];
        _cosLon = new float[width];
        for (int i = 0; i < width; i++)
        {
            _sinLon[i] = (float)Math.Sin(LonOf(i));
            _cosLon[i] = (float)Math.Cos(LonOf(i));
        }
    }

    private double LatOf(int j) => Math.PI / 2 - (j + 0.5) / Height * Math.PI;
    private double LonOf(int i) => (i + 0.5) / Width * 2 * Math.PI - Math.PI;

    public void Clear()
    {
        Array.Clear(Amount);
        Array.Clear(Fire);
        Array.Clear(Glow);
        Array.Clear(Aim);
        Array.Clear(GlowFire);
        Array.Clear(GlowAim);
        if (Way != null) Array.Clear(Way);
        Array.Clear(_lineAmount);
        Array.Clear(_lineGlow);
        Array.Clear(_lineTag);
        _line = 0;
        Array.Fill(_tileVersion, Version + 1);
        Version++;
    }

    /// <summary>The next stamps start a new line: they lay paint over everything there.</summary>
    public void NewLine()
    {
        _line++;
        if (_line == 0) _line = 1; // 0 is "no line"
    }

    /// <summary>
    /// Paints the line from unit direction a to b (the same point for a dot), <paramref name="alongDeg"/> degrees
    /// along its line at its end, <paramref name="halfDeg"/> degrees each side of its middle, its edge
    /// <paramref name="soft"/> (0 sharp .. 1 soft); <paramref name="kind"/> what painted it (<see cref="PaintKind"/>,
    /// <see cref="AimKind"/> while aiming, <see cref="FireKind"/> while firing); <paramref name="wayRgb"/> its
    /// way colour (RGB, or null).
    /// </summary>
    public void Stamp(float ax, float ay, float az, float bx, float by, float bz, double alongDeg, float halfDeg,
        int kind, float soft = 0.5f, int? wayRgb = null)
    {
        if (_line == 0) NewLine();
        if (wayRgb != null && Way == null) Way = new int[_n];
        float half = MathF.Max(halfDeg * MathF.PI / 180f, 1e-4f);
        var brush = new Brush(half, Math.Clamp(soft, 0f, 1f));
        double hop = Math.Acos(Math.Clamp((double)(ax * bx + ay * by + az * bz), -1.0, 1.0));
        int pieces = Math.Max(1, (int)Math.Ceiling(hop * 180 / Math.PI / PieceDeg));
        int along = (int)((long)Math.Round(alongDeg / AlongUnit) & 0xFFFF);
        for (int k = 0; k < pieces; k++)
        {
            var a = Slerp(ax, ay, az, bx, by, bz, hop, k / (float)pieces);
            var b = Slerp(ax, ay, az, bx, by, bz, hop, (k + 1) / (float)pieces);
            Piece(a, b, brush, kind, wayRgb, along);
        }
        Version++;
    }

    /// <summary>The brush: paint to <c>Half</c> radians from the middle with an edge <c>soft</c>, a glow out to <see cref="Reach"/> half widths.</summary>
    private readonly struct Brush(float half, float soft)
    {
        public readonly float Half = half;
        public readonly float Edge = half * (0.08f + 0.72f * soft);
        public float Extent => MathF.Max(Half * Reach, Half + Edge);

        public float Paint(float d)
        {
            float t = Math.Clamp((d - (Half - Edge)) / (2f * Edge), 0f, 1f);
            return 1f - t * t * (3f - 2f * t);
        }

        public float GlowAt(float d)
        {
            float t = Math.Clamp(1f - d / (Half * Reach), 0f, 1f);
            return t * t;
        }
    }

    private void Piece((float X, float Y, float Z) a, (float X, float Y, float Z) b, Brush brush, int kind, int? wayRgb, int along)
    {
        float abx = b.X - a.X, aby = b.Y - a.Y, abz = b.Z - a.Z;
        float ab2 = abx * abx + aby * aby + abz * abz;
        // The cap round the piece's middle holding everything it can reach.
        float cx = a.X + b.X, cy = a.Y + b.Y, cz = a.Z + b.Z;
        float cl = MathF.Sqrt(cx * cx + cy * cy + cz * cz);
        if (cl < 1e-6f) return;
        cx /= cl; cy /= cl; cz /= cl;
        double reach = brush.Extent;
        double span = Math.Acos(Math.Clamp((double)(a.X * b.X + a.Y * b.Y + a.Z * b.Z), -1.0, 1.0)) / 2 + reach + 1e-3;
        double latC = Math.Asin(Math.Clamp((double)cy, -1.0, 1.0));
        double lonC = Math.Atan2(cx, -cz);
        int j0 = Math.Clamp((int)Math.Floor((Math.PI / 2 - (latC + span)) / Math.PI * Height), 0, Height - 1);
        int j1 = Math.Clamp((int)Math.Floor((Math.PI / 2 - (latC - span)) / Math.PI * Height), 0, Height - 1);
        double cosSpan = Math.Cos(span), sinLatC = Math.Sin(latC), cosLatC = Math.Cos(latC);
        float reachF = brush.Extent;
        // Within the same line, a stamp this far along from the last one at a texel is the same pass over it.
        int samePass = (int)(brush.Extent * 2 * 180 / Math.PI / AlongUnit) + SamePassExtra;
        int w = wayRgb ?? 0;
        float wr = w >> 16 & 0xFF, wg = w >> 8 & 0xFF, wb = w & 0xFF;
        var ways = Way;
        float f = kind == FireKind ? 1f : 0f;
        float ai = kind == AimKind ? 1f : 0f;
        for (int j = j0; j <= j1; j++)
        {
            double lat = LatOf(j);
            // The longitudes of this row within the cap: cos(d) = sin φ sin φc + cos φ cos φc cos Δλ ≥ cos(span).
            double den = Math.Cos(lat) * cosLatC;
            int i0, count;
            if (den < 1e-6) { i0 = 0; count = Width; }
            else
            {
                double q = (cosSpan - Math.Sin(lat) * sinLatC) / den;
                if (q > 1.0) continue;
                if (q <= -1.0) { i0 = 0; count = Width; }
                else
                {
                    double dl = Math.Acos(q);
                    i0 = (int)Math.Floor((lonC - dl + Math.PI) / (2 * Math.PI) * Width);
                    count = Math.Min(Width, (int)Math.Floor((lonC + dl + Math.PI) / (2 * Math.PI) * Width) - i0 + 1);
                }
            }
            MarkTiles(j, i0, count);
            float sl = _sinLat[j], clat = _cosLat[j];
            int row = j * Width;
            for (int s = 0; s < count; s++)
            {
                int i = ((i0 + s) % Width + Width) % Width;
                float px = clat * _sinLon[i], py = sl, pz = -clat * _cosLon[i];
                // The nearest point of the piece (on its chord, then out onto the sphere).
                float t = ab2 < 1e-12f ? 0f : ((px - a.X) * abx + (py - a.Y) * aby + (pz - a.Z) * abz) / ab2;
                t = Math.Clamp(t, 0f, 1f);
                float qx = a.X + abx * t, qy = a.Y + aby * t, qz = a.Z + abz * t;
                float ql = MathF.Sqrt(qx * qx + qy * qy + qz * qz);
                qx /= ql; qy /= ql; qz /= ql;
                float dx = px - qx, dy = py - qy, dz = pz - qz;
                float d = MathF.Sqrt(dx * dx + dy * dy + dz * dz); // the chord: as the angle, this close
                if (d >= reachF) continue;
                int k = row + i;
                float pa = brush.Paint(d), pg = brush.GlowAt(d);
                if (pa <= 0f && pg <= 0f) continue;
                float oldA = Amount[k] / 255f, oldG = Glow[k] / 255f;
                // What was there before this line's pass: take this line's part back out.
                float baseA = oldA, baseG = oldG, keep = 1f, keepG = 1f;
                if (_lineTag[k] == _line && ((along - _lineAlong[k]) & 0xFFFF) <= samePass)
                {
                    float la = _lineAmount[k] / 255f, lg = _lineGlow[k] / 255f;
                    if (pa <= la && pg <= lg) continue;
                    pa = MathF.Max(pa, la);
                    pg = MathF.Max(pg, lg);
                    if (la < 0.995f) baseA = Math.Clamp((oldA - la) / (1f - la), 0f, 1f);
                    if (lg < 0.995f) baseG = Math.Clamp((oldG - lg) / (1f - lg), 0f, 1f);
                    keep = oldA > 1e-4f ? Math.Clamp(baseA / oldA, 0f, 1f) : 0f;
                    keepG = oldG > 1e-4f ? Math.Clamp(baseG / oldG, 0f, 1f) : 0f;
                }
                float newA = baseA + pa * (1f - baseA);
                float newG = baseG + pg * (1f - baseG);
                // Premultiplied colours: the base's part, under the new paint, plus the new paint's.
                float newF = Fire[k] / 255f * keep * (1f - pa) + f * pa;
                float newI = Aim[k] / 255f * keep * (1f - pa) + ai * pa;
                float newGF = GlowFire[k] / 255f * keepG * (1f - pg) + f * pg;
                float newGI = GlowAim[k] / 255f * keepG * (1f - pg) + ai * pg;
                Amount[k] = (byte)(newA * 255f + 0.5f);
                Glow[k] = (byte)(newG * 255f + 0.5f);
                Fire[k] = (byte)(MathF.Min(newF, newA) * 255f + 0.5f);
                Aim[k] = (byte)(MathF.Max(0f, MathF.Min(newI, newA - MathF.Min(newF, newA))) * 255f + 0.5f);
                GlowFire[k] = (byte)(MathF.Min(newGF, newG) * 255f + 0.5f);
                GlowAim[k] = (byte)(MathF.Max(0f, MathF.Min(newGI, newG - MathF.Min(newGF, newG))) * 255f + 0.5f);
                if (ways != null && wayRgb != null)
                {
                    int o = ways[k];
                    float under = keep * (1f - pa);
                    int r = Math.Clamp((int)((o >> 16 & 0xFF) * under + wr * pa), 0, 255);
                    int g = Math.Clamp((int)((o >> 8 & 0xFF) * under + wg * pa), 0, 255);
                    int bl = Math.Clamp((int)((o & 0xFF) * under + wb * pa), 0, 255);
                    ways[k] = r << 16 | g << 8 | bl;
                }
                _lineTag[k] = _line;
                _lineAlong[k] = (short)along;
                _lineAmount[k] = (byte)(pa * 255f + 0.5f);
                _lineGlow[k] = (byte)(pg * 255f + 0.5f);
            }
        }
    }

    /// <summary>Marks the tiles of row <paramref name="j"/>'s texels <paramref name="i0"/> .. + <paramref name="count"/> (round the map) as changing in the next <see cref="Version"/>.</summary>
    private void MarkTiles(int j, int i0, int count)
    {
        long next = Version + 1;
        int row = (j >> TileShift) * _tilesX;
        int first = ((i0 % Width) + Width) % Width;
        int last = first + Math.Max(count, 1) - 1;
        if (count >= Width) (first, last) = (0, Width - 1);
        for (int i = first >> TileShift; i <= last >> TileShift; i++) _tileVersion[row + i % _tilesX] = next;
        // Past the map's right edge it goes on at its left.
        if (last >= Width && _tilesX * (1 << TileShift) != Width)
            for (int i = 0; i <= (last - Width) >> TileShift; i++) _tileVersion[row + i] = next;
    }

    /// <summary>The texel holding unit direction (x, y, z).</summary>
    public int TexelOf(float x, float y, float z)
    {
        double lat = Math.Asin(Math.Clamp((double)y, -1.0, 1.0));
        double lon = Math.Atan2(x, -z);
        int i = Math.Clamp((int)Math.Floor((lon + Math.PI) / (2 * Math.PI) * Width), 0, Width - 1);
        int j = Math.Clamp((int)Math.Floor((Math.PI / 2 - lat) / Math.PI * Height), 0, Height - 1);
        return j * Width + i;
    }

    /// <summary>The unit direction a share <paramref name="f"/> of the way from unit a to unit b (<paramref name="hop"/> radians apart).</summary>
    private static (float X, float Y, float Z) Slerp(float ax, float ay, float az, float bx, float by, float bz, double hop, float f)
    {
        if (hop < 1e-4 || Math.Sin(hop) < 1e-4)
            return LaserTrace.Unit(ax + (bx - ax) * f, ay + (by - ay) * f, az + (bz - az) * f);
        double s = Math.Sin(hop);
        float wa = (float)(Math.Sin((1 - f) * hop) / s), wb = (float)(Math.Sin(f * hop) / s);
        return LaserTrace.Unit(ax * wa + bx * wb, ay * wa + by * wb, az * wa + bz * wb);
    }
}
