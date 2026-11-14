namespace GyroPhone.Host;

/// <summary>
/// What a laser pointer overlay remembers between frames: in Draw mode, the strokes the beam
/// painted on the sphere's inside, a ring of unit directions in the shown frame (x right, y up, z toward the viewer) with their
/// times; a point starts a new line after a gap (a held ratchet, Draw just turned on). Strokes keep
/// a point each time the beam moved <see cref="StrokeStepDeg"/>° or more, up to <see cref="StrokeMax"/>
/// (the oldest go first); each new point paints the <see cref="Paint"/> map, which is what shows, and Snake keeps
/// only its newest stretch of them. Same as the phone's
/// LaserTrace. One thread at a time.
/// </summary>
public sealed class LaserTrace
{
    public const int StrokeMax = 4096;
    public const float StrokeStepDeg = 0.5f;
    public const int GridDeg = 15;
    public const int GridSteps = 96;
    private static readonly float StrokeStepCos = MathF.Cos(StrokeStepDeg * MathF.PI / 180f);

    /// <summary>A ring of directions with times, oldest first.</summary>
    public sealed class Ring(int capacity)
    {
        private readonly float[] _x = new float[capacity], _y = new float[capacity], _z = new float[capacity];
        private readonly long[] _t = new long[capacity];
        private readonly bool[] _gap = new bool[capacity];
        private readonly byte[] _kind = new byte[capacity];
        /// <summary>Degrees painted up to each point, counted along the lines (a new line adds none).</summary>
        private readonly double[] _along = new double[capacity];
        private int _head;

        /// <summary>Points kept now.</summary>
        public int Count { get; private set; }

        public void Add(float x, float y, float z, long ms, bool startsLine, int paintKind = LaserPaint.PaintKind)
        {
            int i = (_head + Count) % capacity;
            double a = 0;
            if (Count > 0)
            {
                int l = (_head + Count - 1) % capacity;
                a = _along[l];
                if (!startsLine)
                {
                    double dot = Math.Clamp((double)(_x[l] * x + _y[l] * y + _z[l] * z), -1.0, 1.0);
                    a += Math.Acos(dot) * 180.0 / Math.PI;
                }
            }
            if (Count == capacity)
            {
                _head = (_head + 1) % capacity;
                _gap[_head] = true; // the oldest left has no line from before it
            }
            else Count++;
            _x[i] = x; _y[i] = y; _z[i] = z; _t[i] = ms; _gap[i] = startsLine; _kind[i] = (byte)paintKind; _along[i] = a;
        }

        /// <summary>Drops the oldest points more than <paramref name="deg"/> degrees of painted line behind the newest.</summary>
        public void Cut(float deg)
        {
            if (Count == 0) return;
            double newest = _along[(_head + Count - 1) % capacity];
            bool dropped = false;
            while (Count > 1 && newest - _along[_head] > deg + 0.05) // a hair over: sums of steps round
            {
                _head = (_head + 1) % capacity;
                Count--;
                dropped = true;
            }
            if (dropped) _gap[_head] = true;
        }

        /// <summary>Degrees of painted line from point <paramref name="i"/> to the newest.</summary>
        public float BehindNewest(int i) =>
            Count == 0 ? 0f : (float)(_along[(_head + Count - 1) % capacity] - _along[(_head + i) % capacity]);

        public void Clear()
        {
            Count = 0;
            _head = 0;
        }

        /// <summary>Degrees painted up to point <paramref name="i"/> (0 oldest), counted along the lines.</summary>
        public double Along(int i) => _along[(_head + i) % capacity];

        /// <summary>Point <paramref name="i"/> (0 oldest): its direction and time (ms).</summary>
        public (float X, float Y, float Z, long T) Point(int i)
        {
            int k = (_head + i) % capacity;
            return (_x[k], _y[k], _z[k], _t[k]);
        }

        /// <summary>Point <paramref name="i"/> starts a new line (none drawn from the point before it).</summary>
        public bool Gap(int i) => _gap[(_head + i) % capacity];

        /// <summary>Point <paramref name="i"/> was painted while firing (the line to it takes the Fire colours).</summary>
        public bool Fire(int i) => Kind(i) == LaserPaint.FireKind;

        /// <summary>What painted point <paramref name="i"/> (the line to it): LaserPaint.PaintKind, AimKind or FireKind (its colours).</summary>
        public int Kind(int i) => _kind[(_head + i) % capacity];

        /// <summary>
        /// The way the line to point <paramref name="i"/> (from point i − 1) moves, a unit direction (zero if it
        /// doesn't): from up to <paramref name="reach"/> points before it to up to <paramref name="reach"/> after,
        /// within its line, so with <paramref name="reach"/> &gt; 0 small shakes even out. As the phone's Ring.direction.
        /// </summary>
        public (float X, float Y, float Z) Direction(int i, int reach)
        {
            int a = i - 1, steps = 0;
            while (steps < reach && a > 0 && !Gap(a)) { a--; steps++; }
            int b = i;
            steps = 0;
            while (steps < reach && b + 1 < Count && !Gap(b + 1)) { b++; steps++; }
            int ka = (_head + a) % capacity, kb = (_head + b) % capacity;
            return Unit(_x[kb] - _x[ka], _y[kb] - _y[ka], _z[kb] - _z[ka]);
        }
    }

    /// <summary>Draw mode's strokes.</summary>
    public readonly Ring Strokes = new(StrokeMax);

    private bool _strokeGap = true;
    private float _lastX, _lastY, _lastZ;
    private bool _wasHeld;
    private int _lastKind;
    private float _beamX, _beamY, _beamZ;
    private long _beamMs = long.MinValue;
    // Line smoothing: the steadied point the paint follows, the raw beam before, and its evened-out speed.
    private float _steadyX, _steadyY, _steadyZ, _rawX, _rawY, _rawZ, _velX, _velY, _velZ;
    private long _steadyMs;

    /// <summary>
    /// The way the beam last moved (a unit direction in the shown frame, x right, y up, z toward the viewer; zero
    /// before it ever moved): it keeps the last way when the beam stops. The crosshair's colour.
    /// </summary>
    public (float X, float Y, float Z) Moving { get; private set; }

    /// <summary>Points each side a stroke's colour looks over with smoothing (<see cref="Ring.Direction"/>): ±2°.</summary>
    public const int SmoothReach = 4;

    /// <summary>How long the crosshair's colour takes to follow a new way with smoothing (ms, 63% of the way).</summary>
    public const int SmoothMs = 60;

    /// <summary>Line smoothing at full strength: how long a slow move's paint takes to catch up (ms, 63%).</summary>
    public const float LineSmoothMs = 150f;

    /// <summary>Line smoothing eases off with speed: at this many degrees a second it catches up twice as fast.</summary>
    public const float LineSmoothSpeed = 60f;

    // How long line smoothing's speed reading evens out over (ms).
    private const float SpeedMs = 50f;

    // Under this the beam hasn't moved (a radian, about 0.1°).
    private const float MoveMin = 0.002f;

    /// <summary>The assault rifle's muzzle tip in its own frame (x right, y up, z toward the stock).</summary>
    public static readonly (float X, float Y, float Z) Muzzle = (0f, 0f, -0.93f);

    /// <summary>Aiming (RifleFaces' aim 1) the gun is shouldered: pulled back toward the player this far.</summary>
    public const float AimPull = 0.12f;

    /// <summary>
    /// Where <paramref name="gun"/>'s laser sight lands on the sphere (unit, the shown frame) for pose <paramref name="m"/>:
    /// straight out of its muzzle along its barrel, so it kicks with it. As the phone's LaserTrace.sightHit.
    /// </summary>
    public static (float X, float Y, float Z) SightHit(float[] m, float aim, float kick, Guns.Gun gun, float lower, float hip)
    {
        var p = MuzzleAt(aim, kick, gun, lower, hip);
        var d = RifleAt(0f, 0f, -1f, 0f, kick, true, gun, lower);
        float px = m[0] * p.X + m[1] * p.Y + m[2] * p.Z, py = m[3] * p.X + m[4] * p.Y + m[5] * p.Z, pz = m[6] * p.X + m[7] * p.Y + m[8] * p.Z;
        var u = Unit(m[0] * d.X + m[1] * d.Y + m[2] * d.Z, m[3] * d.X + m[4] * d.Y + m[5] * d.Z, m[6] * d.X + m[7] * d.Y + m[8] * d.Z);
        // The ray from the muzzle (inside the sphere) out to the shell.
        float b = px * u.X + py * u.Y + pz * u.Z;
        float t = -b + MathF.Sqrt(Math.Max(0f, b * b - (px * px + py * py + pz * pz - 1f)));
        return Unit(px + u.X * t, py + u.Y * t, pz + u.Z * t);
    }

    /// <summary>With Lowered when idle (RifleFaces' lower 1) the gun rests pitched this many degrees down.</summary>
    public const float LowerDeg = 15f;


    /// <summary>The gun's muzzle tip at aim <paramref name="aim"/> (0 .. 1), kick and lowered, in its own frame.</summary>
    public static (float X, float Y, float Z) MuzzleAt(float aim, float kick = 0f, Guns.Gun? gun = null, float lower = 0f, float hip = 0f)
    {
        var g = gun ?? Guns.AR;
        return RifleAt(0f, 0f, g.MuzzleZ, aim, kick, false, g, lower, hip);
    }

    /// <summary>
    /// A point of <paramref name="gun"/>'s own frame at aim <paramref name="aim"/> (0 .. 1), kick <paramref name="kick"/> (0 rest
    /// .. 1 full, a little under 0 as it springs back) and lowered by <paramref name="lower"/> (0 .. 1): shouldered by
    /// <see cref="AimPull"/>, kicked back by its recoil's Back and its muzzle up by its ClimbDeg, and pitched down by
    /// <see cref="LowerDeg"/>, both about its pivot. <paramref name="dir"/>: a direction (a face's normal), turned only.
    /// As the phone's LaserTrace.rifleAt.
    /// </summary>
    public static (float X, float Y, float Z) RifleAt(float x, float y, float z, float aim, float kick, bool dir = false, Guns.Gun? gun = null, float lower = 0f, float hip = 0f)
    {
        var g = gun ?? Guns.AR;
        float a = (g.Recoil.ClimbDeg * kick - LowerDeg * Math.Clamp(lower, 0f, 1f)) * MathF.PI / 180f, c = MathF.Cos(a), s = MathF.Sin(a);
        // Scaled to fit (Guns.Gun.Fit): a direction stays as it is.
        float f = dir ? 1f : g.Fit, yf = y * f;
        float pz = dir ? 0f : g.FitZ(g.PivotZ), dz = (dir ? z : g.FitZ(z)) - pz;
        return (x * f, yf * c - dz * s, pz + yf * s + dz * c + (dir ? 0f : AimPull * Math.Clamp(aim, 0f, 1f) + -g.FitZ(g.MuzzleZ) * Math.Clamp(hip, 0f, 1f) + g.Recoil.Back * kick));
    }

    /// <summary>
    /// The beam now points at (x, y, z) (any length) at <paramref name="nowMs"/>. While a ratchet is
    /// <paramref name="held"/> it doesn't paint (the pose eases back then, which isn't aiming); with
    /// <paramref name="clearOnPress"/> pressing one clears the paint, with <paramref name="clearSnakeOnPress"/> the Snake. With <paramref name="draw"/> it
    /// paints; turning Draw off clears the strokes. While <paramref name="fire"/> (the overlay's Fire display output is
    /// held) it paints in the Fire colours, <paramref name="fireBrushDeg"/> wide; else while <paramref name="aim"/> (its Aim
    /// display output) in the Aim colours, <paramref name="aimBrushDeg"/> wide (both default <paramref name="brushDeg"/>);
    /// with <paramref name="fireOnly"/> only then. <see cref="Moving"/> follows the
    /// beam, evened out over <see cref="SmoothMs"/> with <paramref name="smooth"/>. With <paramref name="lineSmoothing"/>
    /// (0 off .. 1 strong) the paint follows a steadied beam (<see cref="SteadyToward"/>).
    /// </summary>
    public void Add(float x, float y, float z, long nowMs, bool draw, bool held, bool clearOnPress = false, bool fire = false, bool fireOnly = false,
        bool smooth = true, float lineSmoothing = 0f, float brushDeg = 0f, float snakeDeg = 0f, uint[]? wayColors = null,
        int mapWidth = LaserPaint.DefaultWidth, float soft = 0.5f, bool aim = false, float? fireBrushDeg = null, float? aimBrushDeg = null,
        bool clearSnakeOnPress = false)
    {
        bool pressed = held && !_wasHeld;
        _wasHeld = held;
        bool snake = snakeDeg > 0f;
        if (!draw && _paintUsed) ClearPaint(); // Draw off wipes the paint (a snake goes on)
        if (!(draw || snake) && (Strokes.Count > 0 || _paintUsed)) Clear();
        // A ratchet press: the paint and the Snake each as set.
        // (Without Snake the strokes aren't shown: they go with the paint.)
        if (pressed && clearOnPress && (_paintUsed || !snake && Strokes.Count > 0)) { ClearPaint(); if (!snake) ClearStrokes(); }
        if (pressed && clearSnakeOnPress && snake && Strokes.Count > 0) ClearStrokes();
        float l = MathF.Sqrt(x * x + y * y + z * z);
        if (l > 1e-6f && float.IsFinite(l)) Follow(x / l, y / l, z / l, nowMs, smooth);
        if (!(draw || snake) || l < 1e-6f || held || !float.IsFinite(l) || (fireOnly && !fire && !aim))
        {
            _strokeGap = true;
            return;
        }
        float dx = x / l, dy = y / l, dz = z / l;
        if (lineSmoothing > 0f) (dx, dy, dz) = SteadyToward(dx, dy, dz, nowMs, lineSmoothing);
        int kind = fire ? LaserPaint.FireKind : aim ? LaserPaint.AimKind : LaserPaint.PaintKind;
        float brush = kind == LaserPaint.FireKind ? fireBrushDeg ?? brushDeg : kind == LaserPaint.AimKind ? aimBrushDeg ?? brushDeg : brushDeg;
        // A new point once the beam moved a little (or the firing or aiming started or stopped): a still beam adds nothing.
        if (!_strokeGap && kind == _lastKind && _lastX * dx + _lastY * dy + _lastZ * dz > StrokeStepCos) return;
        Strokes.Add(dx, dy, dz, nowMs, _strokeGap, kind);
        if (draw && brush > 0f) StampNewest(brush / 2f, kind, wayColors, smooth ? SmoothReach : 0, mapWidth, soft);
        if (snake) Strokes.Cut(snakeDeg);
        _strokeGap = false;
        _lastKind = kind;
        _lastX = dx; _lastY = dy; _lastZ = dz;
    }

    /// <summary>The paint map (Draw mode), made at the first painted point.</summary>
    public LaserPaint? Paint { get; private set; }

    // Something is painted on the map (since the last clear).
    private bool _paintUsed;

    /// <summary>Goes up when the strokes or paint are cleared.</summary>
    public long Version { get; private set; }

    private void ClearPaint()
    {
        Paint?.Clear();
        _paintUsed = false;
        HitCount = 0;
        _hitNext = 0;
        Version++;
    }

    /// <summary>The most Draw each shot hits kept (the oldest go).</summary>
    public const int HitMax = 2048;

    /// <summary>Draw each shot's hit looks (OverlaySettings.LaserHitLook).</summary>
    public static readonly string[] HitLooks = ["Dot", "Ring", "Cross", "Bullet hole"];

    /// <summary>Draw each shot's hits: x, y, z (a unit direction, as the strokes), half width (radians) each. The newest <see cref="HitMax"/> are kept.</summary>
    public float[] Hits { get; } = new float[HitMax * 4];

    /// <summary>How many <see cref="Hits"/> there are.</summary>
    public int HitCount { get; private set; }
    private int _hitNext;

    /// <summary>The last shot (CrosshairKick.State.Shots) marked.</summary>
    public long HitShots { get; set; }

    /// <summary>Marks a hit at (<paramref name="x"/>, <paramref name="y"/>, <paramref name="z"/>) (any length), <paramref name="halfRad"/> radians round.</summary>
    public void AddHit(float x, float y, float z, float halfRad)
    {
        float l = MathF.Sqrt(x * x + y * y + z * z);
        if (!(l > 1e-6f)) return;
        int i = _hitNext * 4;
        Hits[i] = x / l; Hits[i + 1] = y / l; Hits[i + 2] = z / l; Hits[i + 3] = halfRad;
        _hitNext = (_hitNext + 1) % HitMax;
        if (HitCount < HitMax) HitCount++;
        Version++;
    }

    /// <summary>
    /// Where a shot lands (unit) off <paramref name="beam"/> by (<paramref name="ox"/>, <paramref name="oy"/>) radians to the right
    /// and up of pose <paramref name="m"/> (the crosshair's right and up, <see cref="RollTick"/>). As the phone's LaserTrace.shotPoint.
    /// </summary>
    public static (float X, float Y, float Z) ShotPoint(float[] m, (float X, float Y, float Z) beam, float ox, float oy)
    {
        var b = Unit(beam.X, beam.Y, beam.Z);
        var v = RollTick(m, b) ?? Square((0f, 0f, -1f), b) ?? Square((1f, 0f, 0f), b) ?? (0f, 1f, 0f);
        var u = (X: v.Y * b.Z - v.Z * b.Y, Y: v.Z * b.X - v.X * b.Z, Z: v.X * b.Y - v.Y * b.X);
        float tx = MathF.Tan(ox), ty = MathF.Tan(oy);
        return Unit(b.X + tx * u.X + ty * v.X, b.Y + tx * u.Y + ty * v.Y, b.Z + tx * u.Z + ty * v.Z);
    }

    /// <summary>
    /// Pose <paramref name="m"/> (row-major, phone to shown) turned by the turn that takes <paramref name="beam"/> onto
    /// <paramref name="to"/> (unit), <paramref name="k"/> of the way: a gun or crosshair kicking toward where a shot lands. A new array.
    /// </summary>
    public static float[] TurnToward(float[] m, (float X, float Y, float Z) beam, (float X, float Y, float Z) to, float k)
    {
        var b = Unit(beam.X, beam.Y, beam.Z);
        float ax = b.Y * to.Z - b.Z * to.Y, ay = b.Z * to.X - b.X * to.Z, az = b.X * to.Y - b.Y * to.X;
        float sinA = MathF.Sqrt(ax * ax + ay * ay + az * az);
        var n = (float[])m.Clone();
        if (k == 0f || sinA < 1e-7f) return n;
        ax /= sinA; ay /= sinA; az /= sinA;
        float angle = MathF.Atan2(sinA, b.X * to.X + b.Y * to.Y + b.Z * to.Z) * k;
        float c = MathF.Cos(angle), s = MathF.Sin(angle), t = 1f - c;
        float[] r =
        [
            t * ax * ax + c, t * ax * ay - s * az, t * ax * az + s * ay,
            t * ax * ay + s * az, t * ay * ay + c, t * ay * az - s * ax,
            t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c,
        ];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                n[i * 3 + j] = r[i * 3] * m[j] + r[i * 3 + 1] * m[3 + j] + r[i * 3 + 2] * m[6 + j];
        return n;
    }

    /// <summary>Paints the newest stroke point into <see cref="Paint"/>, from the point before it unless it starts a line.</summary>
    private void StampNewest(float halfDeg, int kind, uint[]? wayColors, int reach, int mapWidth, float soft)
    {
        // A new map when the detail changed (the paint so far goes).
        var p = Paint is { } old && old.Width == mapWidth ? old : (Paint = new LaserPaint(mapWidth, mapWidth / 2));
        int n = Strokes.Count, b = n - 1;
        int a = Strokes.Gap(b) || n < 2 ? b : b - 1;
        var pa = Strokes.Point(a);
        var pb = Strokes.Point(b);
        int? rgb = null;
        if (wayColors != null)
        {
            var dir = a == b ? (0f, 0f, 0f) : Strokes.Direction(b, reach);
            rgb = (int)(DirectionColor(dir, wayColors) & 0xFFFFFF);
        }
        if (a == b) p.NewLine();
        p.Stamp(pa.X, pa.Y, pa.Z, pb.X, pb.Y, pb.Z, Strokes.Along(b), halfDeg, kind, soft, rgb);
        _paintUsed = true;
    }

    /// <summary>Turns <see cref="Moving"/> toward the way the beam went to unit (x, y, z) at <paramref name="nowMs"/>.</summary>
    private void Follow(float x, float y, float z, long nowMs, bool smooth)
    {
        if (_beamMs == long.MinValue)
        {
            (_beamX, _beamY, _beamZ, _beamMs) = (x, y, z, nowMs);
            return;
        }
        float dx = x - _beamX, dy = y - _beamY, dz = z - _beamZ;
        // Barely moved (a still phone's noise): keep the last way.
        if (dx * dx + dy * dy + dz * dz < MoveMin * MoveMin) return;
        var d = Unit(dx, dy, dz);
        var m = Moving;
        float k = !smooth || (m.X == 0f && m.Y == 0f && m.Z == 0f) ? 1f
            : Math.Clamp(1f - MathF.Exp(-Math.Max(1L, nowMs - _beamMs) / (float)SmoothMs), 0f, 1f);
        Moving = Unit(m.X + (d.X - m.X) * k, m.Y + (d.Y - m.Y) * k, m.Z + (d.Z - m.Z) * k);
        (_beamX, _beamY, _beamZ, _beamMs) = (x, y, z, nowMs);
    }

    /// <summary>
    /// Moves the steadied point toward the beam at unit (x, y, z) at <paramref name="nowMs"/>, as a drawing app's
    /// stabiliser: it eases in over up to <see cref="LineSmoothMs"/> × <paramref name="strength"/> when the beam moves
    /// slowly, much faster when it moves fast (<see cref="LineSmoothSpeed"/>); a new line starts on the beam.
    /// </summary>
    private (float X, float Y, float Z) SteadyToward(float x, float y, float z, long nowMs, float strength)
    {
        if (_strokeGap)
        {
            (_steadyX, _steadyY, _steadyZ) = (x, y, z);
            (_velX, _velY, _velZ) = (0f, 0f, 0f);
        }
        else
        {
            float dt = Math.Clamp(nowMs - _steadyMs, 1L, 200L);
            // The beam's speed, evened out over SpeedMs so a shake back and forth cancels (radians/ms).
            float kv = 1f - MathF.Exp(-dt / SpeedMs);
            _velX += ((x - _rawX) / dt - _velX) * kv;
            _velY += ((y - _rawY) / dt - _velY) * kv;
            _velZ += ((z - _rawZ) / dt - _velZ) * kv;
            float degPerS = MathF.Sqrt(_velX * _velX + _velY * _velY + _velZ * _velZ) * 180f / MathF.PI * 1000f;
            float tau = Math.Clamp(strength, 0f, 1f) * LineSmoothMs / (1f + degPerS / LineSmoothSpeed);
            float k = tau < 0.5f ? 1f : 1f - MathF.Exp(-dt / tau);
            (_steadyX, _steadyY, _steadyZ) = Unit(_steadyX + (x - _steadyX) * k, _steadyY + (y - _steadyY) * k, _steadyZ + (z - _steadyZ) * k);
        }
        (_rawX, _rawY, _rawZ) = (x, y, z);
        _steadyMs = nowMs;
        return (_steadyX, _steadyY, _steadyZ);
    }

    /// <summary>Snake: its curve is cut into steps of at most this many degrees.</summary>
    public const float CurveStepDeg = 0.5f;

    /// <summary>Snake: at most this many steps between two stroke points.</summary>
    public const int CurveMaxSteps = 64;

    /// <summary>
    /// A smooth curve through unit directions <paramref name="p1"/> → <paramref name="p2"/> at <paramref name="t"/> (0..1),
    /// unit: a centripetal Catmull-Rom spline with neighbours <paramref name="p0"/> (before p1) and <paramref name="p3"/>
    /// (after p2), which never loops or overshoots however unevenly the points are spaced. A line's ends pass the end
    /// point itself as its missing neighbour. As the phone's LaserTrace.curve.
    /// </summary>
    public static (float X, float Y, float Z) Curve(
        (float X, float Y, float Z) p0, (float X, float Y, float Z) p1, (float X, float Y, float Z) p2, (float X, float Y, float Z) p3, float t)
    {
        // Centripetal: each span's knot is the square root of its chord.
        static float Knot((float X, float Y, float Z) a, (float X, float Y, float Z) b)
        {
            float dx = b.X - a.X, dy = b.Y - a.Y, dz = b.Z - a.Z;
            return MathF.Sqrt(MathF.Sqrt(dx * dx + dy * dy + dz * dz));
        }
        float d1 = Math.Max(Knot(p1, p2), 1e-6f);
        float d0 = Knot(p0, p1); if (d0 < 1e-6f) d0 = d1;
        float d2 = Knot(p2, p3); if (d2 < 1e-6f) d2 = d1;
        float t2 = t * t, t3 = t2 * t;
        float h1 = 2f * t3 - 3f * t2 + 1f, h2 = t3 - 2f * t2 + t, h3 = -2f * t3 + 3f * t2, h4 = t3 - t2;
        // Hermite form: the tangents at p1 and p2 scaled to the middle span.
        float One(float q0, float q1, float q2, float q3)
        {
            float m1 = d1 * ((q1 - q0) / d0 - (q2 - q0) / (d0 + d1) + (q2 - q1) / d1);
            float m2 = d1 * ((q2 - q1) / d1 - (q3 - q1) / (d1 + d2) + (q3 - q2) / d2);
            return h1 * q1 + h2 * m1 + h3 * q2 + h4 * m2;
        }
        return Unit(One(p0.X, p1.X, p2.X, p3.X), One(p0.Y, p1.Y, p2.Y, p3.Y), One(p0.Z, p1.Z, p2.Z, p3.Z));
    }

    /// <summary>(x, y, z) made unit length (zero when it is).</summary>
    public static (float X, float Y, float Z) Unit(float x, float y, float z)
    {
        float l = MathF.Sqrt(x * x + y * y + z * z);
        return l < 1e-9f || !float.IsFinite(l) ? (0f, 0f, 0f) : (x / l, y / l, z / l);
    }

    /// <summary>
    /// The colour (ARGB) of moving the way of unit direction <paramref name="d"/> (x right, y up, z toward the
    /// viewer), blended as a normal map does: <paramref name="colors"/> holds the colour of each straight way (right,
    /// left, up, down, toward, away; ARGB); the middle is their average, and each part of the way takes its share of
    /// the way from the middle to its colour. With the normal-map colours that is 0.5 + 0.5·d per red, green, blue.
    /// Not moving: the middle. As the phone's LaserTrace.directionColor.
    /// </summary>
    public static uint DirectionColor((float X, float Y, float Z) d, ReadOnlySpan<uint> colors) => new WayMix(colors).Of(d);

    /// <summary>
    /// <see cref="DirectionColor"/> for many directions with the same colours: their middle is worked out once, here,
    /// not for every direction.
    /// </summary>
    public readonly struct WayMix
    {
        private readonly uint[] _colors;
        private readonly float[] _mid;

        public WayMix(ReadOnlySpan<uint> colors)
        {
            _colors = colors[..6].ToArray();
            _mid = new float[4];
            for (int ch = 0; ch < 4; ch++)
            {
                int shift = ch * 8;
                float mid = 0f;
                for (int i = 0; i < 6; i++) mid += (colors[i] >> shift) & 0xFF;
                _mid[ch] = mid / 6f;
            }
        }

        /// <summary>The colour (ARGB) of moving the way of unit direction <paramref name="d"/>.</summary>
        public uint Of((float X, float Y, float Z) d)
        {
            uint argb = 0;
            for (int ch = 0; ch < 4; ch++)
            {
                int shift = ch * 8;
                float mid = _mid[ch];
                float v = mid;
                for (int a = 0; a < 3; a++)
                {
                    float p = a == 0 ? d.X : a == 1 ? d.Y : d.Z;
                    float c = (_colors[p >= 0f ? a * 2 : a * 2 + 1] >> shift) & 0xFF;
                    v += MathF.Abs(p) * (c - mid);
                }
                argb |= (uint)Math.Clamp((int)(v + 0.5f), 0, 255) << shift;
            }
            return argb;
        }
    }

    /// <summary>Clears the strokes (the Snake; the paint stays).</summary>
    private void ClearStrokes()
    {
        Strokes.Clear();
        _strokeGap = true;
        Version++;
    }

    /// <summary>Clears the strokes and the paint.</summary>
    public void Clear()
    {
        Strokes.Clear();
        Paint?.Clear();
        _paintUsed = false;
        HitCount = 0;
        _hitNext = 0;
        _strokeGap = true;
        Version++;
    }

    /// <summary>How far round from straight ahead (into the screen) shown-frame direction z is: 0 ahead .. 1 straight behind.</summary>
    public static float Behind(float z) => Math.Clamp((1f + z) / 2f, 0f, 1f);

    /// <summary>Perspective: the eye's distance from the centre, in sphere radii (as the 3D phone's).</summary>
    public const float CameraDistance = 6f;

    /// <summary>Perspective: how much bigger a picture point at depth <paramref name="z"/> (toward the viewer +, sphere radii) shows.</summary>
    public static float Perspective(float z) => CameraDistance / (CameraDistance - Math.Min(z, CameraDistance - 0.5f));


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

    /// <summary>Picture x, y, z (toward the viewer +) of shown-frame point (x, y, z): through <see cref="View"/>, or as it is (face-on) without <paramref name="axon"/>.</summary>
    /// With <paramref name="turned"/> (a pose matrix, row-major) the point is first seen from that pose (its
    /// transpose times the point): the sphere turned under a beam that stays ahead.
    public static (float X, float Y, float Z) ToView(float x, float y, float z, bool axon = true, float[]? turned = null)
    {
        if (turned is { } t)
            (x, y, z) = (t[0] * x + t[3] * y + t[6] * z, t[1] * x + t[4] * y + t[7] * z, t[2] * x + t[5] * y + t[8] * z);
        if (!axon) return (x, y, z);
        var v = View;
        return (v[0] * x + v[1] * y + v[2] * z, v[3] * x + v[4] * y + v[5] * z, v[6] * x + v[7] * y + v[8] * z);
    }

    /// <summary>The <see cref="GridDeg"/>° grid (latitudes, meridians) in the picture, unit radius, as polylines: near half and far half.</summary>
    public static readonly (List<(float X, float Y)[]> Front, List<(float X, float Y)[]> Back) Grid = MakeGrid(true);

    /// <summary>The same grid seen face-on.</summary>
    public static readonly (List<(float X, float Y)[]> Front, List<(float X, float Y)[]> Back) FlatGrid = MakeGrid(false);

    /// <summary><see cref="Grid"/> or <see cref="FlatGrid"/>.</summary>
    /// With <paramref name="turned"/> (see <see cref="ToView"/>) it is made anew, turned with the sphere.
    public static (List<(float X, float Y)[]> Front, List<(float X, float Y)[]> Back) GridFor(bool axon, float[]? turned = null) =>
        turned != null ? MakeGrid(axon, turned) : axon ? Grid : FlatGrid;

    private static (List<(float X, float Y)[]>, List<(float X, float Y)[]>) MakeGrid(bool axon, float[]? turned = null)
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
                var q = ToView(p.X, p.Y, p.Z, axon, turned);
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

    /// <summary>A round scope's sides: this many.</summary>
    private const int ScopeSides = 12;

    /// <summary>The gun look's picture may reach this far out (sphere radii): its frame's edge.</summary>
    public const float GunFrame = 1f / 0.95f;

    /// <summary>
    /// How much the gun look is drawn smaller so <paramref name="gun"/> fits its frame (<see cref="GunFrame"/>) even fully hip
    /// fired by <paramref name="hip"/> and kicked by <paramref name="kick"/>: measured at rest, aiming straight ahead (pose
    /// <paramref name="m"/>, unturned); 1 when it fits. As the phone's gunFrameScale.
    /// </summary>
    public static float GunFrameScale(float[] m, bool axon, bool persp, Guns.Gun gun, float hip, float kick)
    {
        float e = 0f;
        foreach (var f in RifleFaces(m, axon, null, persp, 0f, kick, gun, 0f, false, hip))
            foreach (var p in f.Corners) e = Math.Max(e, MathF.Sqrt(p.X * p.X + p.Y * p.Y));
        return e <= GunFrame ? 1f : GunFrame / e;
    }

    /// <summary>One face of the gun in the picture: its corners (x, y, unit radius, any number), how lit it is (0..1) and its depth.</summary>
    public readonly record struct Face((float X, float Y)[] Corners, float Light, float Depth);

    /// <summary>
    /// <paramref name="gun"/> turned by pose <paramref name="m"/>, seen as <see cref="ToView"/> sees it: the faces toward the
    /// viewer, farthest first, lit from above left. Aiming (<paramref name="aim"/> 0 .. 1, eased) it is shouldered
    /// (<see cref="AimPull"/>) and, with <paramref name="scope"/>, its round scope grows onto it; firing it kicks
    /// (<paramref name="kick"/>, <see cref="RifleAt"/>); <paramref name="lower"/> pitches it down. As the phone's.
    /// </summary>
    public static List<Face> RifleFaces(float[] m, bool axon, float[]? turned, bool persp = false, float aim = 0f, float kick = 0f,
        Guns.Gun? gun = null, float lower = 0f, bool scope = true, float hip = 0f)
    {
        var g = gun ?? Guns.AR;
        List<Face> shown = new(64);
        const float lx = -0.35f, ly = 0.6f, lz = 0.72f;
        float ak = Math.Clamp(aim, 0f, 1f);
        (float X, float Y, float Z) Turn((float X, float Y, float Z) q) =>
            ToView(m[0] * q.X + m[1] * q.Y + m[2] * q.Z, m[3] * q.X + m[4] * q.Y + m[5] * q.Z, m[6] * q.X + m[7] * q.Y + m[8] * q.Z, axon, turned);
        // One face: its corners in the gun's own frame, and its normal.
        void AddFace((float X, float Y, float Z)[] c, float nx, float ny, float nz)
        {
            var n = Turn(RifleAt(nx, ny, nz, ak, kick, true, g, lower, hip));
            if (n.Z <= 1e-4f) return;
            float light = Math.Clamp(0.45f + 0.55f * Math.Max(0f, n.X * lx + n.Y * ly + n.Z * lz), 0f, 1f);
            var corners = new (float X, float Y)[c.Length];
            float depth = 0f;
            for (int i = 0; i < c.Length; i++)
            {
                var v = Turn(RifleAt(c[i].X, c[i].Y, c[i].Z, ak, kick, false, g, lower, hip));
                float f = persp ? Perspective(v.Z) : 1f;
                corners[i] = (v.X * f, v.Y * f);
                depth += v.Z;
            }
            shown.Add(new Face(corners, light, depth / c.Length));
        }
        void Box(float cx, float cy, float cz, float hx, float hy, float hz)
        {
            float[] ctr = [cx, cy, cz], h = [hx, hy, hz], us = [-1f, 1f, 1f, -1f], ws = [-1f, -1f, 1f, 1f];
            for (int f = 0; f < 6; f++)
            {
                int axis = f / 2, o1 = (axis + 1) % 3, o2 = (axis + 2) % 3;
                float sign = f % 2 == 0 ? -1f : 1f;
                var c = new (float X, float Y, float Z)[4];
                for (int k = 0; k < 4; k++)
                {
                    var p = (float[])ctr.Clone();
                    p[axis] += sign * h[axis];
                    p[o1] += us[k] * h[o1];
                    p[o2] += ws[k] * h[o2];
                    c[k] = (p[0], p[1], p[2]);
                }
                AddFace(c, axis == 0 ? sign : 0f, axis == 1 ? sign : 0f, axis == 2 ? sign : 0f);
            }
        }
        // A round tube along z at height y, from z0 (front) to z1, radius r: its sides and both ends.
        void Tube(float y, float z0, float z1, float r)
        {
            const int k = ScopeSides;
            for (int i = 0; i < k; i++)
            {
                float t0 = 2f * MathF.PI * i / k, t1 = 2f * MathF.PI * (i + 1) / k, tm = (t0 + t1) / 2f;
                AddFace([(r * MathF.Cos(t0), y + r * MathF.Sin(t0), z0), (r * MathF.Cos(t1), y + r * MathF.Sin(t1), z0),
                    (r * MathF.Cos(t1), y + r * MathF.Sin(t1), z1), (r * MathF.Cos(t0), y + r * MathF.Sin(t0), z1)],
                    MathF.Cos(tm), MathF.Sin(tm), 0f);
            }
            foreach (var (z, nz) in new[] { (z0, -1f), (z1, 1f) })
            {
                var c = new (float X, float Y, float Z)[k];
                for (int j = 0; j < k; j++)
                {
                    float t = 2f * MathF.PI * j / k;
                    c[j] = (r * MathF.Cos(t), y + r * MathF.Sin(t), z);
                }
                AddFace(c, 0f, 0f, nz);
            }
        }
        var b = g.Boxes;
        for (int o = 0; o < b.Length; o += 6) Box(b[o], b[o + 1], b[o + 2], b[o + 3], b[o + 4], b[o + 5]);
        if (scope && g.Scope is { } sc && ak > 0.02f)
        {
            // It grows in about its middle.
            float mid = (sc.Front + sc.Back) / 2f;
            float Zz(float z) => mid + (z - mid) * ak;
            float rail = 0f;
            for (int o = 0; o < b.Length; o += 6) rail = Math.Max(rail, b[o + 1] + b[o + 4]);
            // It sits on its mounts, clear of the gun's top.
            float sy = Math.Max(sc.Y, rail + sc.Radius + 0.012f), baseY = sy - sc.Radius;
            foreach (float mz in new[] { sc.Front + 0.06f, sc.Back - 0.08f })
            {
                float h = Math.Max((baseY - rail) / 2f, 0.004f) * ak;
                Box(0f, rail + h, Zz(mz), 0.012f * ak, h, 0.014f);
            }
            Tube(sy, Zz(sc.Front + 0.04f), Zz(sc.Back - 0.03f), sc.Radius * ak);
            Tube(sy, Zz(sc.Front - 0.03f), Zz(sc.Front + 0.04f), sc.Bell * ak);
            Tube(sy, Zz(sc.Back - 0.03f), Zz(sc.Back), sc.Radius * 1.25f * ak);
        }
        shown.Sort((x, y) => x.Depth.CompareTo(y.Depth));
        return shown;
    }

    /// <summary>
    /// Pose matrix <paramref name="m"/> (row-major) seen as the laser aims: the phone held as a gamepad aims
    /// with its roll axis (out of its top edge), so that axis takes the place of the one out of its back,
    /// into the screen at rest. Turning it with the phone (yaw, the turn through its screen) then swings the
    /// beam sideways, and pitch up and down. A · m · Aᵀ with A taking the phone's up into the screen and its
    /// screen's normal up. As the phone's LaserTrace.aim.
    /// </summary>
    public static float[] Aim(float[] m)
    {
        int[] axis = [0, 2, 1];
        float[] sign = [1f, 1f, -1f];
        var r = new float[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i * 3 + j] = sign[i] * sign[j] * m[axis[i] * 3 + axis[j]];
        return r;
    }

    /// <summary>
    /// Pose matrix <paramref name="m"/> (row-major) with the laser facing direction <paramref name="faces"/> of it:
    /// 0 −Z (into the screen at rest), 1 +Z, 2 +X, 3 −X, 4 +Y, 5 −Y. m · F, F turning −Z onto that direction.
    /// As the phone's LaserTrace.faces.
    /// </summary>
    public static float[] Faces(float[] m, int faces)
    {
        if (faces is < 0 or > 5) return m;
        var f = FaceTurns[faces];
        var r = new float[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i * 3 + j] = m[i * 3] * f[j] + m[i * 3 + 1] * f[3 + j] + m[i * 3 + 2] * f[6 + j];
        return r;
    }

    private static readonly float[][] FaceTurns =
    [
        [1, 0, 0, 0, 1, 0, 0, 0, 1],
        [-1, 0, 0, 0, 1, 0, 0, 0, -1],
        [0, 0, -1, 0, 1, 0, 1, 0, 0],
        [0, 0, 1, 0, 1, 0, -1, 0, 0],
        [1, 0, 0, 0, 0, -1, 0, 1, 0],
        [1, 0, 0, 0, 0, 1, 0, -1, 0],
    ];

    /// <summary>The beam's direction for pose matrix <paramref name="m"/> (row-major, phone frame to shown): out of the phone's back.</summary>
    public static (float X, float Y, float Z) Beam(float[] m) => (-m[2], -m[5], -m[8]);

    /// <summary>The crosshair shapes (OverlaySettings.CrosshairShape).</summary>
    /// <summary>The gimbal rings' radii (the sphere's is 1): yaw outside, then pitch, then roll.</summary>
    public static readonly float[] GimbalRadii = [0.9f, 0.8f, 0.7f];

    /// <summary>
    /// Gimbal ring <paramref name="i"/> (0 yaw, 1 pitch, 2 roll) for the laser's pose <paramref name="m"/> (row-major): one
    /// rigid set turning with the laser's axes, each round its own axis (yaw round its up Y, pitch round its right X, roll
    /// round the beam Z), so each ring is the path the other axes sweep turning about it. As the phone's LaserTrace.gimbalRing.
    /// </summary>
    public static (float X, float Y, float Z)[] GimbalRing(int i, float[] m, int steps = 64)
    {
        float rho = GimbalRadii[i];
        var o = new (float X, float Y, float Z)[steps + 1];
        for (int j = 0; j <= steps; j++)
        {
            float th = j * 2f * MathF.PI / steps;
            float p = rho * MathF.Cos(th), q = rho * MathF.Sin(th);
            // Round its axis: yaw XZ (round Y), pitch YZ (round X), roll XY (round Z).
            var (x, y, z) = i switch { 0 => (p, 0f, q), 1 => (0f, p, q), _ => (p, q, 0f) };
            o[j] = (m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z);
        }
        return o;
    }

    public static readonly string[] Crosshairs = ["Cross", "Cross with gap", "Circle", "Circle and cross", "Dot", "T"];

    /// <summary>A crosshair on the sphere: Lines as polylines of points; Dot a closed ring to fill, or null.</summary>
    public sealed record Crosshair(List<(float X, float Y, float Z)[]> Lines, (float X, float Y, float Z)[]? Dot);

    /// <summary>
    /// Crosshair <paramref name="shape"/> (<see cref="Crosshairs"/>) lying on the sphere where <paramref name="beam"/>
    /// lands, facing its centre: arms <paramref name="size"/> radians long from a <paramref name="gap"/> radian gap, a
    /// centre <paramref name="dot"/>. Its up is the pose's up (<paramref name="m"/>), so it turns about the beam only as
    /// the pose does: a trackball's roll axis as mapped (locked, inverted), the phone's own roll when it turns with the
    /// phone. As the phone's LaserTrace.crosshair.
    /// </summary>
    public static Crosshair CrosshairOn(float[] m, (float X, float Y, float Z) beam, int shape, float size, float gap, bool dot, float dotSize = -1f)
    {
        var up = RollTick(m, beam) ?? Square((0f, 0f, -1f), beam) ?? Square((1f, 0f, 0f), beam) ?? (0f, 1f, 0f);
        // Right: up × the outward normal (the beam).
        var u = (X: up.Y * beam.Z - up.Z * beam.Y, Y: up.Z * beam.X - up.X * beam.Z, Z: up.X * beam.Y - up.Y * beam.X);
        (float X, float Y, float Z) At(float a, float b) =>
            (beam.X + a * u.X + b * up.X, beam.Y + a * u.Y + b * up.Y, beam.Z + a * u.Z + b * up.Z);
        (float X, float Y, float Z)[] Line(float a0, float b0, float a1, float b1) => [At(a0, b0), At(a1, b1)];
        (float X, float Y, float Z)[] Ring(float rad)
        {
            const int n = 32;
            var o = new (float X, float Y, float Z)[n + 1];
            for (int i = 0; i <= n; i++)
            {
                float t = i * 2f * MathF.PI / n;
                o[i] = At(rad * MathF.Cos(t), rad * MathF.Sin(t));
            }
            return o;
        }
        var lines = new List<(float X, float Y, float Z)[]>(6);
        float g = Math.Clamp(gap, 0f, size * 0.9f);
        void Arms(bool top)
        {
            lines.Add(Line(g, 0f, size, 0f));
            lines.Add(Line(-g, 0f, -size, 0f));
            lines.Add(Line(0f, -g, 0f, -size));
            if (top) lines.Add(Line(0f, g, 0f, size));
        }
        switch (shape)
        {
            case 0:
                lines.Add(Line(-size, 0f, size, 0f));
                lines.Add(Line(0f, -size, 0f, size));
                break;
            case 2: lines.Add(Ring(size * 0.6f)); break;
            case 3: lines.Add(Ring(size * 0.6f)); Arms(true); break;
            case 4: break;
            case 5: Arms(false); break;
            default: Arms(true); break;
        }
        // The dot is sized from dotSize (by default size), so a kick can grow the lines alone.
        float dotRad = shape == 4 ? size * 0.35f : dot ? (dotSize < 0f ? size : dotSize) * 0.12f : 0f;
        return new Crosshair(lines, dotRad > 0f ? Ring(dotRad) : null);
    }

    /// <summary><paramref name="d"/> squared against <paramref name="beam"/>, normalized; null when they are (nearly) the same way.</summary>
    private static (float X, float Y, float Z)? Square((float X, float Y, float Z) d, (float X, float Y, float Z) beam)
    {
        float k = d.X * beam.X + d.Y * beam.Y + d.Z * beam.Z;
        float x = d.X - k * beam.X, y = d.Y - k * beam.Y, z = d.Z - k * beam.Z;
        float l = MathF.Sqrt(x * x + y * y + z * z);
        if (l < 1e-3f) return null;
        return (x / l, y / l, z / l);
    }

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
