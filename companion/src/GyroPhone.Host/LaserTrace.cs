namespace GyroPhone.Host;

/// <summary>
/// What a laser pointer overlay remembers between frames: the beam's recent hit points on the
/// sphere (the trail) and, in Draw mode, the strokes it painted on the sphere's inside. Both are
/// rings of unit directions in the shown frame (x right, y up, z toward the viewer) with their
/// times; a point starts a new line after a gap (a held ratchet, Draw just turned on). Strokes keep
/// a point each time the beam moved <see cref="StrokeStepDeg"/>° or more, up to <see cref="StrokeMax"/>
/// (the oldest go first), so drawing them costs the same however long you paint. Same as the phone's
/// LaserTrace. One thread at a time.
/// </summary>
public sealed class LaserTrace
{
    public const int TrailMax = 512;
    public const int StrokeMax = 4096;
    public const float StrokeStepDeg = 0.5f;
    public const int GridDeg = 15;
    private const int GridSteps = 96;
    private static readonly float StrokeStepCos = MathF.Cos(StrokeStepDeg * MathF.PI / 180f);

    /// <summary>A ring of directions with times, oldest first.</summary>
    public sealed class Ring(int capacity)
    {
        private readonly float[] _x = new float[capacity], _y = new float[capacity], _z = new float[capacity];
        private readonly long[] _t = new long[capacity];
        private readonly bool[] _gap = new bool[capacity];
        private int _head;

        /// <summary>Points kept now.</summary>
        public int Count { get; private set; }

        public void Add(float x, float y, float z, long ms, bool startsLine)
        {
            int i = (_head + Count) % capacity;
            if (Count == capacity)
            {
                _head = (_head + 1) % capacity;
                _gap[_head] = true; // the oldest left has no line from before it
            }
            else Count++;
            _x[i] = x; _y[i] = y; _z[i] = z; _t[i] = ms; _gap[i] = startsLine;
        }

        /// <summary>Drops points older than <paramref name="ms"/> at <paramref name="nowMs"/>.</summary>
        public void Expire(long nowMs, int ms)
        {
            while (Count > 0 && nowMs - _t[_head] > ms)
            {
                _head = (_head + 1) % capacity;
                Count--;
            }
            if (Count > 0) _gap[_head] = true;
        }

        public void Clear()
        {
            Count = 0;
            _head = 0;
        }

        /// <summary>Point <paramref name="i"/> (0 oldest): its direction and time (ms).</summary>
        public (float X, float Y, float Z, long T) Point(int i)
        {
            int k = (_head + i) % capacity;
            return (_x[k], _y[k], _z[k], _t[k]);
        }

        /// <summary>Point <paramref name="i"/> starts a new line (none drawn from the point before it).</summary>
        public bool Gap(int i) => _gap[(_head + i) % capacity];
    }

    /// <summary>The trail's hit points.</summary>
    public readonly Ring Trail = new(TrailMax);

    /// <summary>Draw mode's strokes.</summary>
    public readonly Ring Strokes = new(StrokeMax);

    private bool _trailGap = true, _strokeGap = true;
    private float _lastX, _lastY, _lastZ;

    /// <summary>
    /// The beam now points at (x, y, z) (any length) at <paramref name="nowMs"/>. While a ratchet is
    /// <paramref name="held"/> it neither trails nor paints (the pose eases back then, which isn't
    /// aiming). With <paramref name="draw"/> it paints; turning Draw off clears the strokes.
    /// </summary>
    public void Add(float x, float y, float z, long nowMs, int trailMs, bool draw, bool held)
    {
        if (!draw && Strokes.Count > 0) Clear();
        float l = MathF.Sqrt(x * x + y * y + z * z);
        if (l < 1e-6f || held || !float.IsFinite(l))
        {
            _trailGap = _strokeGap = true;
            return;
        }
        float dx = x / l, dy = y / l, dz = z / l;
        if (trailMs > 0)
        {
            Trail.Add(dx, dy, dz, nowMs, _trailGap);
            _trailGap = false;
        }
        else
        {
            Trail.Clear();
            _trailGap = true;
        }
        if (!draw)
        {
            _strokeGap = true;
            return;
        }
        // A new point once the beam moved a little: a still beam adds nothing.
        if (!_strokeGap && _lastX * dx + _lastY * dy + _lastZ * dz > StrokeStepCos) return;
        Strokes.Add(dx, dy, dz, nowMs, _strokeGap);
        _strokeGap = false;
        _lastX = dx; _lastY = dy; _lastZ = dz;
    }

    /// <summary>Drops trail points older than <paramref name="trailMs"/> and, with <paramref name="fadeMs"/> &gt; 0, strokes older than it.</summary>
    public void Expire(long nowMs, int trailMs, int fadeMs)
    {
        Trail.Expire(nowMs, trailMs);
        if (fadeMs > 0) Strokes.Expire(nowMs, fadeMs);
    }

    /// <summary>Some trail point or fading stroke is left (draw again while it fades).</summary>
    public bool Fading(int fadeMs) => Trail.Count > 0 || (fadeMs > 0 && Strokes.Count > 0);

    /// <summary>Clears the strokes and the trail.</summary>
    public void Clear()
    {
        Strokes.Clear();
        Trail.Clear();
        _trailGap = _strokeGap = true;
    }

    /// <summary>How bright stroke point <paramref name="i"/> of <paramref name="count"/> made at <paramref name="t"/> is: 1 new .. 0 gone (<paramref name="fadeMs"/>), or by its place without fading.</summary>
    public static float StrokeLife(int i, int count, long t, long nowMs, int fadeMs) =>
        fadeMs > 0 ? Math.Clamp(1f - (nowMs - t) / (float)fadeMs, 0f, 1f)
        : count <= 1 ? 1f : i / (count - 1f);

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

    /// <summary><paramref name="m"/> (row-major 3×3) seen through the axonometric <see cref="View"/>: View · m.</summary>
    public static float[] Axonometric(float[] m)
    {
        var v = View;
        var r = new float[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i * 3 + j] = v[i * 3] * m[j] + v[i * 3 + 1] * m[3 + j] + v[i * 3 + 2] * m[6 + j];
        return r;
    }

    /// <summary>Picture x, y, z (toward the viewer +) of shown-frame point (x, y, z).</summary>
    public static (float X, float Y, float Z) ToView(float x, float y, float z)
    {
        var v = View;
        return (v[0] * x + v[1] * y + v[2] * z, v[3] * x + v[4] * y + v[5] * z, v[6] * x + v[7] * y + v[8] * z);
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
