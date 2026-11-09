namespace GyroPhone.Host;

/// <summary>
/// What a laser pointer overlay remembers between frames: in Draw mode, the strokes the beam
/// painted on the sphere's inside (they fade after a while, or stay), a ring of unit directions in the shown frame (x right, y up, z toward the viewer) with their
/// times; a point starts a new line after a gap (a held ratchet, Draw just turned on). Strokes keep
/// a point each time the beam moved <see cref="StrokeStepDeg"/>° or more, up to <see cref="StrokeMax"/>
/// (the oldest go first), so drawing them costs the same however long you paint. Same as the phone's
/// LaserTrace. One thread at a time.
/// </summary>
public sealed class LaserTrace
{
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
        private readonly bool[] _fire = new bool[capacity];
        private int _head;

        /// <summary>Points kept now.</summary>
        public int Count { get; private set; }

        public void Add(float x, float y, float z, long ms, bool startsLine, bool firing = false)
        {
            int i = (_head + Count) % capacity;
            if (Count == capacity)
            {
                _head = (_head + 1) % capacity;
                _gap[_head] = true; // the oldest left has no line from before it
            }
            else Count++;
            _x[i] = x; _y[i] = y; _z[i] = z; _t[i] = ms; _gap[i] = startsLine; _fire[i] = firing;
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

        /// <summary>Point <paramref name="i"/> was painted while firing (the line to it takes the Fire colours).</summary>
        public bool Fire(int i) => _fire[(_head + i) % capacity];
    }

    /// <summary>Draw mode's strokes.</summary>
    public readonly Ring Strokes = new(StrokeMax);

    private bool _strokeGap = true;
    private float _lastX, _lastY, _lastZ;
    private bool _wasHeld, _lastFire;

    /// <summary>
    /// The beam now points at (x, y, z) (any length) at <paramref name="nowMs"/>. While a ratchet is
    /// <paramref name="held"/> it doesn't paint (the pose eases back then, which isn't aiming); with
    /// <paramref name="clearOnPress"/> pressing one clears the strokes. With <paramref name="draw"/> it
    /// paints; turning Draw off clears the strokes. While <paramref name="fire"/> (the overlay's fire output is held)
    /// it paints in the Fire colours; with <paramref name="fireOnly"/> only then.
    /// </summary>
    public void Add(float x, float y, float z, long nowMs, bool draw, bool held, bool clearOnPress = false, bool fire = false, bool fireOnly = false)
    {
        bool pressed = clearOnPress && held && !_wasHeld;
        _wasHeld = held;
        if ((!draw || pressed) && Strokes.Count > 0) Clear();
        float l = MathF.Sqrt(x * x + y * y + z * z);
        if (!draw || l < 1e-6f || held || !float.IsFinite(l) || (fireOnly && !fire))
        {
            _strokeGap = true;
            return;
        }
        float dx = x / l, dy = y / l, dz = z / l;
        // A new point once the beam moved a little (or the firing started or stopped): a still beam adds nothing.
        if (!_strokeGap && fire == _lastFire && _lastX * dx + _lastY * dy + _lastZ * dz > StrokeStepCos) return;
        Strokes.Add(dx, dy, dz, nowMs, _strokeGap, fire);
        _strokeGap = false;
        _lastFire = fire;
        _lastX = dx; _lastY = dy; _lastZ = dz;
    }

    /// <summary>With <paramref name="fadeMs"/> &gt; 0, drops strokes older than it.</summary>
    public void Expire(long nowMs, int fadeMs)
    {
        if (fadeMs > 0) Strokes.Expire(nowMs, fadeMs);
    }

    /// <summary>Some stroke is still fading (draw again while it fades).</summary>
    public bool Fading(int fadeMs) => fadeMs > 0 && Strokes.Count > 0;

    /// <summary>Clears the strokes.</summary>
    public void Clear()
    {
        Strokes.Clear();
        _strokeGap = true;
    }

    /// <summary>How far round from straight ahead (into the screen) shown-frame direction z is: 0 ahead .. 1 straight behind.</summary>
    public static float Behind(float z) => Math.Clamp((1f + z) / 2f, 0f, 1f);

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

    /// <summary>
    /// The rifle look's model in the phone's frame (x right, y up, z toward the player), the muzzle out of
    /// the phone's back as the beam: boxes as centre x, y, z and half sizes, unit radius. As the phone's.
    /// </summary>
    private static readonly float[] Rifle =
    [
        // stock, buffer tube, receiver, carry rail, handguard, barrel, muzzle, front sight, magazine, grip
        0f, -0.02f, 0.68f, 0.05f, 0.09f, 0.2f,
        0f, 0.03f, 0.42f, 0.03f, 0.03f, 0.08f,
        0f, 0.03f, 0.12f, 0.055f, 0.075f, 0.22f,
        0f, 0.13f, 0.1f, 0.028f, 0.025f, 0.18f,
        0f, 0.03f, -0.32f, 0.055f, 0.055f, 0.2f,
        0f, 0.03f, -0.7f, 0.018f, 0.018f, 0.18f,
        0f, 0.03f, -0.9f, 0.026f, 0.026f, 0.03f,
        0f, 0.1f, -0.5f, 0.012f, 0.06f, 0.015f,
        0f, -0.17f, 0.0f, 0.035f, 0.12f, 0.06f,
        0f, -0.14f, 0.28f, 0.03f, 0.09f, 0.045f,
    ];

    /// <summary>One face of the rifle in the picture: its corners (x, y, unit radius), how lit it is (0..1) and its depth.</summary>
    public readonly record struct Face((float X, float Y)[] Corners, float Light, float Depth);

    /// <summary>
    /// The rifle turned by pose <paramref name="m"/>, seen as <see cref="ToView"/> sees it: the faces toward
    /// the viewer, farthest first, lit from above left.
    /// </summary>
    public static List<Face> RifleFaces(float[] m, bool axon, float[]? turned)
    {
        var faces = new List<Face>(32);
        var p = new (float X, float Y, float Z)[8];
        const float lx = -0.35f, ly = 0.6f, lz = 0.72f;
        (float X, float Y, float Z) Turn(float x, float y, float z) =>
            ToView(m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z, axon, turned);
        for (int o = 0; o < Rifle.Length; o += 6)
        {
            for (int k = 0; k < 8; k++)
                p[k] = Turn(Rifle[o] + ((k & 1) != 0 ? Rifle[o + 3] : -Rifle[o + 3]),
                    Rifle[o + 1] + ((k & 2) != 0 ? Rifle[o + 4] : -Rifle[o + 4]),
                    Rifle[o + 2] + ((k & 4) != 0 ? Rifle[o + 5] : -Rifle[o + 5]));
            for (int f = 0; f < 6; f++)
            {
                int axis = f / 2;
                float sign = f % 2 == 0 ? -1f : 1f;
                var n = Turn(axis == 0 ? sign : 0f, axis == 1 ? sign : 0f, axis == 2 ? sign : 0f);
                if (n.Z <= 1e-4f) continue;
                int on = sign > 0 ? 1 << axis : 0;
                // The face's corners, in order round it.
                int a = 1 << ((axis + 1) % 3), b = 1 << ((axis + 2) % 3);
                int[] ring = [on, a | on, a | b | on, b | on];
                var corners = new (float X, float Y)[4];
                float depth = 0f;
                for (int i = 0; i < 4; i++)
                {
                    corners[i] = (p[ring[i]].X, p[ring[i]].Y);
                    depth += p[ring[i]].Z;
                }
                float light = Math.Clamp(0.45f + 0.55f * Math.Max(0f, n.X * lx + n.Y * ly + n.Z * lz), 0f, 1f);
                faces.Add(new Face(corners, light, depth / 4f));
            }
        }
        faces.Sort((x, y) => x.Depth.CompareTo(y.Depth));
        return faces;
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
    /// The gimbal angles of pose <paramref name="m"/> (row-major, as Ry(yaw)·Rx(pitch)·Rz(roll), radians): of the two
    /// ways to read them, the one with the <paramref name="locks"/> axes (bit 0 yaw, 1 pitch, 2 roll) nearest straight,
    /// so a locked axis reads 0 even past the poles. As the phone's LaserTrace.gimbalAngles.
    /// </summary>
    public static float[] GimbalAngles(float[] m, int locks)
    {
        float a = MathF.Atan2(m[2], m[8]);
        float b = MathF.Asin(Math.Clamp(-m[5], -1f, 1f));
        float c = MathF.Atan2(m[3], m[4]);
        static float Wrap(float x) => x > MathF.PI ? x - 2 * MathF.PI : x < -MathF.PI ? x + 2 * MathF.PI : x;
        float[] here = [a, b, c];
        float[] alt = [Wrap(a + MathF.PI), Wrap(MathF.PI - b), Wrap(c + MathF.PI)];
        float h = 0f, t = 0f;
        for (int i = 0; i < 3; i++)
            if ((locks & (1 << i)) != 0) { h += MathF.Abs(here[i]); t += MathF.Abs(alt[i]); }
        return t < h - 1e-4f ? alt : here;
    }

    /// <summary>
    /// Gimbal ring <paramref name="i"/> (0 yaw, 1 pitch, 2 roll) for <paramref name="angles"/> (<see cref="GimbalAngles"/>),
    /// nested as a real gimbal's: the yaw ring turns about the vertical and holds the pitch ring's pivots; the pitch
    /// ring tilts about the yaw ring's right and holds the roll ring's; the roll ring rolls about where it faces.
    /// As the phone's LaserTrace.gimbalRing.
    /// </summary>
    public static (float X, float Y, float Z)[] GimbalRing(int i, float[] angles, int steps = 64)
    {
        float ca = MathF.Cos(angles[0]), sa = MathF.Sin(angles[0]);
        float b = i >= 1 ? angles[1] : 0f, c = i >= 2 ? angles[2] : 0f;
        float cb = MathF.Cos(b), sb = MathF.Sin(b), cc = MathF.Cos(c), sc = MathF.Sin(c);
        float[] ry = [ca, 0f, sa, 0f, 1f, 0f, -sa, 0f, ca];
        float[] rx = [1f, 0f, 0f, 0f, cb, -sb, 0f, sb, cb];
        float[] rz = [cc, -sc, 0f, sc, cc, 0f, 0f, 0f, 1f];
        var f = Mul(Mul(ry, rx), rz);
        float rho = GimbalRadii[i];
        var o = new (float X, float Y, float Z)[steps + 1];
        for (int j = 0; j <= steps; j++)
        {
            float th = j * 2f * MathF.PI / steps;
            float p = rho * MathF.Cos(th), q = rho * MathF.Sin(th);
            // Yaw: its XY plane (round the vertical and the pitch pivots); pitch: XZ; roll: YZ.
            var (x, y, z) = i switch { 0 => (p, q, 0f), 1 => (p, 0f, q), _ => (0f, p, q) };
            o[j] = (f[0] * x + f[1] * y + f[2] * z, f[3] * x + f[4] * y + f[5] * z, f[6] * x + f[7] * y + f[8] * z);
        }
        return o;
    }

    private static float[] Mul(float[] x, float[] y)
    {
        var r = new float[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) r[i * 3 + j] = x[i * 3] * y[j] + x[i * 3 + 1] * y[3 + j] + x[i * 3 + 2] * y[6 + j];
        return r;
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
    public static Crosshair CrosshairOn(float[] m, (float X, float Y, float Z) beam, int shape, float size, float gap, bool dot)
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
        float dotRad = shape == 4 ? size * 0.35f : dot ? size * 0.12f : 0f;
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
