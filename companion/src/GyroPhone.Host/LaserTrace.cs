namespace GyroPhone.Host;

/// <summary>
/// What a laser pointer overlay remembers between frames: the beam's recent hit points on the
/// sphere (the trail) and, in Draw mode, the sphere's painted inside. Directions are unit vectors in
/// the shown frame (x right, y up, z toward the viewer); the sphere is cut into <see cref="CellDeg"/>°
/// cells by latitude (round the up axis) and longitude (0 straight ahead, into the screen). Each
/// frame only the cells under the brush are touched, so painting costs the same however much is
/// painted; the drawing merges neighbouring cells of one row into one strip (<see cref="ForEachRun"/>).
/// Same as the phone's LaserTrace. One thread at a time.
/// </summary>
public sealed class LaserTrace
{
    public const int CellDeg = 2;
    public const int Rows = 180 / CellDeg;
    public const int Cols = 360 / CellDeg;
    public const int Levels = 8;
    public const int TrailMax = 512;
    public const int GridDeg = 15;
    private const int GridSteps = 96;
    public const float CellRad = CellDeg * MathF.PI / 180f;

    // The trail: a ring of hit points, oldest first from _head.
    private readonly float[] _tx = new float[TrailMax], _ty = new float[TrailMax], _tz = new float[TrailMax];
    private readonly long[] _tt = new long[TrailMax];
    private readonly bool[] _tGap = new bool[TrailMax];
    private int _head, _count;
    private bool _gap = true;

    /// <summary>When each cell was last painted (ms, + 1 so 0 = never).</summary>
    private readonly long[] _cells = new long[Rows * Cols];
    private readonly int[] _rowPainted = new int[Rows];

    private float _lastX, _lastY, _lastZ;
    private bool _hasLast;

    /// <summary>Cells painted now.</summary>
    public int Painted { get; private set; }

    /// <summary>Trail points kept now.</summary>
    public int TrailCount => _count;

    /// <summary>
    /// The beam now points at (x, y, z) (any length) at <paramref name="nowMs"/>. While a ratchet is
    /// <paramref name="held"/> it neither trails nor paints (the pose eases back then, which isn't
    /// aiming). With <paramref name="draw"/> the cells within <paramref name="brushDeg"/> of the
    /// beam, and of the way it went since the last call, are painted.
    /// </summary>
    public void Add(float x, float y, float z, long nowMs, int trailMs, bool draw, float brushDeg, bool held)
    {
        float l = MathF.Sqrt(x * x + y * y + z * z);
        if (l < 1e-6f || held || !float.IsFinite(l))
        {
            _gap = true;
            _hasLast = false;
            return;
        }
        float dx = x / l, dy = y / l, dz = z / l;
        if (trailMs > 0)
        {
            int i = (_head + _count) % TrailMax;
            if (_count == TrailMax) _head = (_head + 1) % TrailMax; else _count++;
            _tx[i] = dx; _ty[i] = dy; _tz[i] = dz; _tt[i] = nowMs; _tGap[i] = _gap;
            _gap = false;
        }
        else
        {
            _count = 0;
            _gap = true;
        }
        if (!draw)
        {
            _hasLast = false;
            return;
        }
        float brush = Math.Clamp(brushDeg, 0.5f, 45f) * MathF.PI / 180f;
        if (_hasLast)
        {
            // Along the great circle from the last point, in steps of half the brush.
            float a = MathF.Acos(Math.Clamp(_lastX * dx + _lastY * dy + _lastZ * dz, -1f, 1f));
            int steps = Math.Clamp((int)MathF.Ceiling(a / (brush * 0.5f)), 1, 360);
            float s = MathF.Sin(a);
            for (int k = 1; k <= steps; k++)
            {
                float t = k / (float)steps;
                if (s < 1e-4f) Paint(dx, dy, dz, brush, nowMs);
                else
                {
                    float wa = MathF.Sin((1 - t) * a) / s, wb = MathF.Sin(t * a) / s;
                    Paint(_lastX * wa + dx * wb, _lastY * wa + dy * wb, _lastZ * wa + dz * wb, brush, nowMs);
                }
            }
        }
        else Paint(dx, dy, dz, brush, nowMs);
        _lastX = dx; _lastY = dy; _lastZ = dz;
        _hasLast = true;
    }

    /// <summary>Paints every cell whose centre is within <paramref name="brush"/> radians of unit direction (x, y, z).</summary>
    private void Paint(float x, float y, float z, float brush, long nowMs)
    {
        float lat = MathF.Asin(Math.Clamp(y, -1f, 1f));
        float lon = MathF.Atan2(x, -z);
        float sinLat = MathF.Sin(lat), cosLat = MathF.Cos(lat), cosB = MathF.Cos(brush);
        int r0 = RowOf(lat - brush), r1 = RowOf(lat + brush);
        long stamp = nowMs + 1;
        for (int r = r0; r <= r1; r++)
        {
            float lc = RowLat(r);
            float den = cosLat * MathF.Cos(lc);
            // The longitudes within the brush on this row: cos d = sin a sin b + cos a cos b cos Δ.
            float half;
            if (den < 1e-6f) half = MathF.PI;
            else
            {
                float c = (cosB - sinLat * MathF.Sin(lc)) / den;
                if (c > 1f) continue;
                half = c <= -1f ? MathF.PI : MathF.Acos(c);
            }
            if (half >= MathF.PI)
            {
                for (int c = 0; c < Cols; c++) Mark(r, c, stamp);
                continue;
            }
            int c0 = (int)MathF.Ceiling((lon - half + MathF.PI) / CellRad - 0.5f);
            int c1 = (int)MathF.Floor((lon + half + MathF.PI) / CellRad - 0.5f);
            if (c1 < c0) Mark(r, ColOf(lon), stamp);
            else for (int c = c0; c <= c1; c++) Mark(r, ((c % Cols) + Cols) % Cols, stamp);
        }
        // The cell the beam is in always.
        Mark(RowOf(lat), ColOf(lon), stamp);
    }

    private void Mark(int r, int c, long stamp)
    {
        int i = r * Cols + c;
        if (_cells[i] == 0)
        {
            Painted++;
            _rowPainted[r]++;
        }
        _cells[i] = stamp;
    }

    /// <summary>Drops trail points older than <paramref name="trailMs"/> and, with <paramref name="fadeMs"/> &gt; 0, paint older than it.</summary>
    public void Expire(long nowMs, int trailMs, int fadeMs)
    {
        while (_count > 0 && nowMs - _tt[_head] > trailMs)
        {
            _head = (_head + 1) % TrailMax;
            _count--;
        }
        if (_count > 0) _tGap[_head] = true;
        if (fadeMs <= 0 || Painted == 0) return;
        for (int r = 0; r < Rows; r++)
        {
            if (_rowPainted[r] == 0) continue;
            int b = r * Cols;
            for (int c = 0; c < Cols; c++)
            {
                long s = _cells[b + c];
                if (s != 0 && nowMs - (s - 1) > fadeMs)
                {
                    _cells[b + c] = 0;
                    _rowPainted[r]--;
                    Painted--;
                }
            }
        }
    }

    /// <summary>Some trail point or fading paint is left (draw again while it fades).</summary>
    public bool Fading(int fadeMs) => _count > 0 || (fadeMs > 0 && Painted > 0);

    /// <summary>Clears the paint and the trail.</summary>
    public void Clear()
    {
        Array.Clear(_cells);
        Array.Clear(_rowPainted);
        Painted = 0;
        _count = 0;
        _head = 0;
        _gap = true;
        _hasLast = false;
    }

    /// <summary>Trail point <paramref name="i"/> (0 oldest): its direction, and how fresh it is, 1 (new) .. 0 (gone).</summary>
    public (float X, float Y, float Z, float Life) TrailPoint(int i, long nowMs, int trailMs)
    {
        int k = (_head + i) % TrailMax;
        return (_tx[k], _ty[k], _tz[k], Math.Clamp(1f - (nowMs - _tt[k]) / (float)Math.Max(1, trailMs), 0f, 1f));
    }

    /// <summary>Trail point <paramref name="i"/> starts a new line (none drawn from the point before it).</summary>
    public bool TrailGap(int i) => _tGap[(_head + i) % TrailMax];

    /// <summary>Cell (r, c)'s strength: 0 unpainted, 1..<see cref="Levels"/> fading to full.</summary>
    public int LevelOf(int r, int c, long nowMs, int fadeMs)
    {
        long s = _cells[r * Cols + c];
        if (s == 0) return 0;
        if (fadeMs <= 0) return Levels;
        float left = 1f - (nowMs - (s - 1)) / (float)fadeMs;
        if (left <= 0f) return 0;
        return Math.Clamp((int)MathF.Ceiling(left * Levels), 1, Levels);
    }

    /// <summary>
    /// Each run of neighbouring painted cells on one row with the same strength (1..<see cref="Levels"/>,
    /// by <paramref name="fadeMs"/>; all full without fading) and the same side (<see cref="CellFront"/>):
    /// row, first and last column, strength, near side.
    /// </summary>
    public void ForEachRun(long nowMs, int fadeMs, Action<int, int, int, int, bool> run)
    {
        for (int r = 0; r < Rows; r++)
        {
            if (_rowPainted[r] == 0) continue;
            int start = -1, level = 0;
            bool side = false;
            for (int c = 0; c <= Cols; c++)
            {
                int lv = c == Cols ? 0 : LevelOf(r, c, nowMs, fadeMs);
                bool f = lv != 0 && CellFront[r * Cols + c];
                if (start >= 0 && (lv != level || f != side))
                {
                    run(r, start, c - 1, level, side);
                    start = -1;
                }
                if (lv != 0 && start < 0)
                {
                    start = c;
                    level = lv;
                    side = f;
                }
            }
        }
    }

    /// <summary>Row r's centre latitude (radians, up +).</summary>
    public static float RowLat(int r) => (r + 0.5f) * CellRad - MathF.PI / 2;

    /// <summary>Column c's centre longitude (radians, right +, 0 straight ahead).</summary>
    public static float ColLon(int c) => (c + 0.5f) * CellRad - MathF.PI;

    public static int RowOf(float lat) => Math.Clamp((int)MathF.Floor((lat + MathF.PI / 2) / CellRad), 0, Rows - 1);

    public static int ColOf(float lon) => (((int)MathF.Floor((lon + MathF.PI) / CellRad) % Cols) + Cols) % Cols;

    /// <summary>The unit direction at latitude <paramref name="lat"/>, longitude <paramref name="lon"/>.</summary>
    public static (float X, float Y, float Z) Dir(float lat, float lon)
    {
        float cl = MathF.Cos(lat);
        return (cl * MathF.Sin(lon), MathF.Sin(lat), -cl * MathF.Cos(lon));
    }

    /// <summary>
    /// The fixed axonometric view (row-major 3×3): turned 30° round the vertical, then seen from 30°
    /// above; shown frame to the picture (x right, y up, z toward the viewer).
    /// </summary>
    public static readonly float[] View = MakeView();

    private static float[] MakeView()
    {
        double yaw = -30 * Math.PI / 180, tilt = 30 * Math.PI / 180;
        float cb = (float)Math.Cos(yaw), sb = (float)Math.Sin(yaw), ca = (float)Math.Cos(tilt), sa = (float)Math.Sin(tilt);
        // Rx(tilt) · Ry(yaw)
        return [cb, 0f, sb, sa * sb, ca, -sa * cb, -ca * sb, sa, ca * cb];
    }

    /// <summary>Picture x, y, z (toward the viewer +) of shown-frame point (x, y, z).</summary>
    public static (float X, float Y, float Z) ToView(float x, float y, float z)
    {
        var v = View;
        return (v[0] * x + v[1] * y + v[2] * z, v[3] * x + v[4] * y + v[5] * z, v[6] * x + v[7] * y + v[8] * z);
    }

    /// <summary>Each cell's centre faces the viewer (lies on the sphere's near half), by row * <see cref="Cols"/> + column.</summary>
    public static readonly bool[] CellFront = MakeCellFront();

    private static bool[] MakeCellFront()
    {
        var f = new bool[Rows * Cols];
        for (int r = 0; r < Rows; r++)
            for (int c = 0; c < Cols; c++)
            {
                var (x, y, z) = Dir(RowLat(r), ColLon(c));
                f[r * Cols + c] = ToView(x, y, z).Z > 0f;
            }
        return f;
    }

    /// <summary>The <see cref="GridDeg"/>° grid (latitudes, meridians) in the picture, unit radius, as polylines: near half and far half.</summary>
    public static readonly (List<(float X, float Y)[]> Front, List<(float X, float Y)[]> Back) Grid = MakeGrid();

    private static (List<(float X, float Y)[]>, List<(float X, float Y)[]>) MakeGrid()
    {
        var front = new List<(float X, float Y)[]>();
        var back = new List<(float X, float Y)[]>();
        void Circle(Func<double, (float X, float Y, float Z)> point)
        {
            var line = new List<(float X, float Y)>();
            bool lastFront = false;
            float lx = 0, ly = 0, lz = 0;
            void Flush()
            {
                if (line.Count >= 2) (lastFront ? front : back).Add(line.ToArray());
                line = [];
            }
            for (int i = 0; i <= GridSteps; i++)
            {
                var p = point(i * 2 * Math.PI / GridSteps);
                var q = ToView(p.X, p.Y, p.Z);
                bool f = q.Z >= 0f;
                if (i > 0 && f != lastFront)
                {
                    // Cut where it crosses the rim, pushed onto it.
                    float t = lz / (lz - q.Z);
                    float rx = lx + (q.X - lx) * t, ry = ly + (q.Y - ly) * t;
                    float l = MathF.Sqrt(rx * rx + ry * ry);
                    if (l > 0f) { rx /= l; ry /= l; }
                    line.Add((rx, ry));
                    Flush();
                    line.Add((rx, ry));
                }
                line.Add((q.X, q.Y));
                lastFront = f;
                lx = q.X; ly = q.Y; lz = q.Z;
            }
            Flush();
        }
        for (int lat = -90 + GridDeg; lat < 90; lat += GridDeg)
        {
            float a = lat * MathF.PI / 180f;
            Circle(t => Dir(a, (float)t));
        }
        for (int lon = 0; lon < 180; lon += GridDeg)
        {
            float sb = MathF.Sin(lon * MathF.PI / 180f), cb = MathF.Cos(lon * MathF.PI / 180f);
            // A great circle through both poles, at longitude lon (and lon + 180°).
            Circle(t => ((float)Math.Cos(t) * sb, (float)Math.Sin(t), -(float)Math.Cos(t) * cb));
        }
        return (front, back);
    }

    /// <summary>The beam's direction for pose matrix <paramref name="m"/> (row-major, phone frame to shown): out of the phone's back.</summary>
    public static (float X, float Y, float Z) Beam(float[] m) => (-m[2], -m[5], -m[8]);

    /// <summary>The phone's up for pose matrix <paramref name="m"/>, squared against <paramref name="beam"/> (the roll tick's way); null when none.</summary>
    public static (float X, float Y, float Z)? RollTick(float[] m, (float X, float Y, float Z) beam)
    {
        float ux = m[1], uy = m[4], uz = m[7];
        float d = ux * beam.X + uy * beam.Y + uz * beam.Z;
        float x = ux - d * beam.X, y = uy - d * beam.Y, z = uz - d * beam.Z;
        float l = MathF.Sqrt(x * x + y * y + z * z);
        if (l < 1e-4f || MathF.Abs(d) > 0.9999f) return null;
        return (x / l, y / l, z / l);
    }
}
