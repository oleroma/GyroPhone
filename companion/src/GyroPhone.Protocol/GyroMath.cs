namespace GyroPhone.Protocol;

/// <summary>
/// The gyro's math after the motion is known: yaw, pitch and roll (deg/s, already turned for the
/// screen and measured in the gyro space) to mouse counts, wheel units and game pad axes, with glide
/// (inertia) when a ratchet turns an axis off, jitter tightening and smoothing, and the sub-count
/// remainders kept.
/// </summary>
/// <remarks>
/// An exact port of the phone's GyroMath.kt (core/.../engine/GyroMath.kt), which is the reference:
/// in "Use PC controller for gyro ratchet" mode the PC runs this on the samples the phone sends, and
/// both must give the same result for the same input. Every step is float math in the same order as
/// the Kotlin, with the same rounding (see the helpers at the end); the shared test vectors
/// (gyro-math-v1.json) check it. Change one side, change the other and the vectors.
/// </remarks>
public sealed class GyroMath
{
    public const int PadAxes = 6;
    private const int Ly = 1, Ry = 3, Lt = 4, Rt = 5;
    private const int SmoothingWindow = 8;

    /// <summary>The phone's GyroSettings.DEFAULT_SENSITIVITY: counts per degree.</summary>
    public const float DefaultSensitivity = 16f;
    /// <summary>The phone's GyroSettings.DEFAULT_GLIDE_MS.</summary>
    public const float DefaultGlideMs = 325f;
    /// <summary>One wheel notch in wheel units (1/120 notch).</summary>
    public const int WheelUnit = 120;

    /// <summary>Degrees of turn per wheel notch when gyro scrolls.</summary>
    public const float DegPerNotch = 15f;

    /// <summary>Turning this fast (°/s) deflects a game pad axis fully.</summary>
    public const float StickFullDegPerS = 90f;

    /// <summary>The recent speed a glide starts from: an EMA with this time constant (ms).</summary>
    private const float SpeedTauMs = 15f;

    /// <summary>An axis turned off slower than this (deg/s) does not glide; a glide stops below <see cref="GlideStopDegPerS"/>.</summary>
    public const float GlideMinDegPerS = 5f;
    public const float GlideStopDegPerS = 0.5f;
    public const float MinGlideMs = 100f;
    public const float MaxGlideMs = 1000f;

    /// <summary>The phone's GyroCurve.MAX_POINTS.</summary>
    public const int MaxCurvePoints = 6;

    /// <summary>
    /// What one gyro configuration does, resolved on the phone (inheritance and held buttons
    /// applied): per axis (yaw, pitch, roll) its sensitivity (counts per degree) and glide, and
    /// where each axis goes (<see cref="Routes"/>: one gyro mapping each, in mapping order).
    /// </summary>
    public sealed class Config
    {
        public readonly float[] Sensitivity = [DefaultSensitivity, DefaultSensitivity, DefaultSensitivity];
        public readonly bool[] Inertia = new bool[3];
        public readonly float[] GlideMs = [DefaultGlideMs, DefaultGlideMs, DefaultGlideMs];
        public readonly bool[] Linear = new bool[3];
        public readonly List<Route> Routes = [];
        /// <summary>
        /// Per axis its acceleration: <see cref="CurvePoints"/> points, speeds (deg/s) and gains
        /// interleaved in <see cref="Curves"/> (on the wire's grid; none = gain 1), and whether the
        /// speed is the axis's own (<see cref="AxisSpeed"/>) or the whole phone's.
        /// </summary>
        public readonly float[][] Curves = [new float[MaxCurvePoints * 2], new float[MaxCurvePoints * 2], new float[MaxCurvePoints * 2]];
        public readonly int[] CurvePoints = new int[3];
        public readonly bool[] AxisSpeed = new bool[3];

        public void CopyFrom(Config o)
        {
            o.Sensitivity.CopyTo(Sensitivity, 0);
            o.Inertia.CopyTo(Inertia, 0);
            o.GlideMs.CopyTo(GlideMs, 0);
            o.Linear.CopyTo(Linear, 0);
            Routes.Clear();
            Routes.AddRange(o.Routes);
            for (int a = 0; a < 3; a++) o.Curves[a].CopyTo(Curves[a], 0);
            o.CurvePoints.CopyTo(CurvePoints, 0);
            o.AxisSpeed.CopyTo(AxisSpeed, 0);
        }

        /// <summary>Sets axis <paramref name="axis"/>'s curve to <paramref name="flat"/> (speeds and gains interleaved).</summary>
        public void SetCurve(int axis, ReadOnlySpan<float> flat, bool axisSpeed)
        {
            int n = Math.Min(flat.Length / 2, MaxCurvePoints);
            flat[..(n * 2)].CopyTo(Curves[axis]);
            CurvePoints[axis] = n;
            AxisSpeed[axis] = axisSpeed && n > 0;
        }
    }

    /// <summary>One gyro mapping: <paramref name="Axis"/> (0 yaw, 1 pitch, 2 roll) to <paramref name="Target"/> (<see cref="MouseX"/> .. <see cref="Pad"/> + game pad axis), times <paramref name="Scale"/>.</summary>
    public readonly record struct Route(int Axis, int Target, float Scale)
    {
        public const int MouseX = 0;
        public const int MouseY = 1;
        public const int Wheel = 2;
        public const int HWheel = 3;
        /// <summary>Plus the game pad axis: lx, ly, rx, ry, lt, rt.</summary>
        public const int Pad = 4;
    }

    /// <summary>Where <see cref="Step"/> sent one sample: mouse counts, wheel units and game pad axes (lx, ly, rx, ry, lt, rt).</summary>
    public sealed class Routed
    {
        public int Dx, Dy, Wheel, HWheel;
        public readonly float[] Pad = new float[PadAxes];
    }

    private readonly Routed _routed = new();

    private float _remX, _remY, _remWheel, _remHWheel;
    private readonly float[] _bufX = new float[SmoothingWindow];
    private readonly float[] _bufY = new float[SmoothingWindow];
    private int _bufIndex;

    // Inertia, per axis (yaw, pitch, roll): its recent speed (deg/s EMA), its glide speed (deg/s,
    // 0 = none), glide time (ms) and linear slowing (deg/s per ms, 0 = smooth); the axes on last sample.
    private readonly float[] _speed = new float[3];
    private readonly float[] _glideV = new float[3];
    private readonly float[] _glideTau = new float[3];
    private readonly float[] _glideDec = new float[3];
    private readonly float[] _glideOut = new float[3];
    private int _lastAxes;

    // Acceleration, per axis: the gain of this sample; an axis off keeps the one it had when it was last on, so a glide keeps its boost.
    private readonly float[] _gain = [1f, 1f, 1f];

    /// <summary>An axis glides, or one was on last sample (turning it off may start a glide): <see cref="Step"/> wants every sample.</summary>
    public bool Coasting => _lastAxes != 0 || _glideV[0] != 0f || _glideV[1] != 0f || _glideV[2] != 0f;

    /// <summary>
    /// One sample: yaw, pitch, roll (deg/s) over <paramref name="dt"/> seconds with <paramref name="axes"/>
    /// on (bit 0 yaw, 1 pitch, 2 roll), sent where <paramref name="c"/> says: mouse motion at the
    /// axis's sensitivity through the filters (<paramref name="tightening"/> and
    /// <paramref name="smoothing"/> thresholds in deg/s at the default sensitivity, 0 = off),
    /// scrolling at <see cref="DegPerNotch"/> and game pad axes full at <see cref="StickFullDegPerS"/>
    /// at the default sensitivity. An axis off moves only by its glide, and only the mouse and
    /// wheel. The result is reused by the next call.
    /// </summary>
    public Routed Step(float yaw, float pitch, float roll, float dt, Config c, int axes, float tightening, float smoothing)
    {
        Coast(c, yaw, pitch, roll, axes, dt);
        Accelerate(c, yaw, pitch, roll, axes);
        float vx = 0f, vy = 0f, w = 0f, hw = 0f;
        var pad = _routed.Pad;
        Array.Clear(pad);
        var routes = c.Routes;
        for (int i = 0; i < routes.Count; i++)
        {
            var r = routes[i];
            bool on = (axes & (1 << r.Axis)) != 0;
            float g = on ? 0f : _glideOut[r.Axis];
            if (!on && (g == 0f || r.Target >= Route.Pad)) continue;
            float k = c.Sensitivity[r.Axis];
            float v = (on ? r.Axis switch { 0 => yaw, 1 => pitch, _ => roll } : g) * _gain[r.Axis];
            switch (r.Target)
            {
                case Route.MouseX: vx += v * k * r.Scale; break;
                case Route.MouseY: vy += v * k * r.Scale; break;
                case Route.Wheel: w += v * k / DefaultSensitivity * r.Scale; break;
                case Route.HWheel: hw += v * k / DefaultSensitivity * r.Scale; break;
                default:
                    int a = r.Target - Route.Pad;
                    if ((uint)a >= PadAxes) break; // not a game pad axis (the phone never sends one)
                    // Stick Y is + down: pitching up pushes it up.
                    float up = a == Ly || a == Ry ? -1f : 1f;
                    pad[a] += up * v * k / DefaultSensitivity * r.Scale / StickFullDegPerS;
                    break;
            }
        }
        for (int a = 0; a < pad.Length; a++)
        {
            pad[a] = a == Lt || a == Rt ? CoerceAtMost(MathF.Abs(pad[a]), 1f) : CoerceIn(pad[a], -1f, 1f);
        }
        Counts(vx, vy, dt, tightening, smoothing);
        float fw = w * dt * WheelUnit / DegPerNotch + _remWheel;
        float fh = hw * dt * WheelUnit / DegPerNotch + _remHWheel;
        _routed.Wheel = ToInt(fw); _remWheel = fw - _routed.Wheel;
        _routed.HWheel = ToInt(fh); _remHWheel = fh - _routed.HWheel;
        return _routed;
    }

    /// <summary>
    /// Mouse counts of cursor velocity <paramref name="vx0"/> (right +) and <paramref name="vy0"/> (up +)
    /// in counts/s, sensitivity applied, over <paramref name="dt"/>: filtered, the remainder kept. The
    /// filters' thresholds are in °/s at the default sensitivity.
    /// </summary>
    private void Counts(float vx0, float vy0, float dt, float tightening, float smoothing)
    {
        float vx = vx0 / DefaultSensitivity;
        float vy = vy0 / DefaultSensitivity;
        float speed = MathF.Sqrt(vx * vx + vy * vy);
        if (tightening > 0f && speed < tightening)
        {
            float k = speed / tightening;
            vx *= k; vy *= k;
        }
        if (smoothing > 0f)
        {
            // Fully smoothed below threshold/2, fully direct above threshold, blended between. Only
            // the smoothed share enters the window (see the Kotlin side).
            float direct = CoerceIn((speed - smoothing / 2f) / (smoothing / 2f), 0f, 1f);
            _bufX[_bufIndex] = vx * (1f - direct); _bufY[_bufIndex] = vy * (1f - direct);
            _bufIndex = (_bufIndex + 1) % SmoothingWindow;
            vx = vx * direct + Mean(_bufX);
            vy = vy * direct + Mean(_bufY);
        }
        float fx = vx * dt * DefaultSensitivity + _remX;
        float fy = -vy * dt * DefaultSensitivity + _remY;
        int dx = RoundToInt(fx);
        int dy = RoundToInt(fy);
        _remX = fx - dx;
        _remY = fy - dy;
        _routed.Dx = dx; _routed.Dy = dy;
    }

    /// <summary>The mean of <paramref name="b"/>, summed in double like the Kotlin side.</summary>
    /// <summary>
    /// Each axis's acceleration gain for this sample: an axis on takes its curve's gain at its own
    /// speed or the whole phone's; one off keeps its last.
    /// </summary>
    private void Accelerate(Config c, float yaw, float pitch, float roll, int axes)
    {
        float whole = MathF.Sqrt(yaw * yaw + pitch * pitch + roll * roll);
        for (int i = 0; i < 3; i++)
        {
            if ((axes & (1 << i)) == 0) continue;
            int n = c.CurvePoints[i];
            if (n == 0) { _gain[i] = 1f; continue; }
            float speed = c.AxisSpeed[i] ? MathF.Abs(i switch { 0 => yaw, 1 => pitch, _ => roll }) : whole;
            _gain[i] = Gain(c.Curves[i], n, speed);
        }
    }

    /// <summary>
    /// The gain of a curve of <paramref name="n"/> points (speeds and gains interleaved, speeds
    /// rising) at <paramref name="speed"/>: the phone's GyroCurves.gain, float math in the same order.
    /// </summary>
    public static float Gain(float[] c, int n, float speed)
    {
        if (n == 0) return 1f;
        if (speed <= c[0]) return c[1];
        for (int j = 1; j < n; j++)
        {
            float s1 = c[2 * j];
            if (speed <= s1)
            {
                float s0 = c[2 * j - 2];
                float g0 = c[2 * j - 1];
                float t = (speed - s0) / (s1 - s0);
                return g0 + (c[2 * j + 1] - g0) * t;
            }
        }
        return c[2 * n - 1];
    }

    private static float Mean(float[] b)
    {
        double s = 0.0;
        foreach (float v in b) s += v;
        return (float)(s / b.Length);
    }

    public void Reset()
    {
        _remX = 0f; _remY = 0f; _remWheel = 0f; _remHWheel = 0f;
        Array.Clear(_bufX); Array.Clear(_bufY); _bufIndex = 0;
        Array.Clear(_speed); Array.Clear(_glideV); Array.Clear(_glideOut); _lastAxes = 0;
        _gain[0] = 1f; _gain[1] = 1f; _gain[2] = 1f;
    }

    /// <summary>
    /// Inertia for one sample with <paramref name="axes"/> on, over <paramref name="dt"/>: an axis on
    /// follows its speed and never glides; one just turned off with inertia glides on from its recent
    /// speed; a gliding one slows down by its glide time and curve.
    /// </summary>
    private void Coast(Config c, float yaw, float pitch, float roll, int axes, float dt)
    {
        float dtMs = dt * 1000f;
        for (int i = 0; i < 3; i++)
        {
            int bit = 1 << i;
            _glideOut[i] = 0f;
            if ((axes & bit) != 0)
            {
                // A short EMA, so one noisy sample does not set the glide.
                float k = dtMs <= 0f ? 0f : 1f - Exp(-dtMs / SpeedTauMs);
                float m = i switch { 0 => yaw, 1 => pitch, _ => roll };
                _speed[i] += k * (m - _speed[i]);
                _glideV[i] = 0f;
                continue;
            }
            if ((_lastAxes & bit) != 0)
            {
                // Just turned off: glides on from its recent speed, this sample at that speed.
                _glideV[i] = 0f;
                if (c.Inertia[i] && MathF.Abs(_speed[i]) >= GlideMinDegPerS)
                {
                    float tau = CoerceIn(c.GlideMs[i], MinGlideMs, MaxGlideMs);
                    _glideV[i] = _speed[i]; _glideTau[i] = tau;
                    // Linear: stops after 2·T, so it turns as far as the smooth one.
                    _glideDec[i] = c.Linear[i] ? MathF.Abs(_speed[i]) / (2f * tau) : 0f;
                    _glideOut[i] = _speed[i];
                }
                _speed[i] = 0f;
                continue;
            }
            float v = _glideV[i];
            if (v == 0f || dtMs <= 0f) continue;
            // The sample moves by the glide's mean speed over it.
            float next;
            if (_glideDec[i] == 0f)
            {
                float decay = Exp(-dtMs / _glideTau[i]);
                next = v * decay;
                _glideOut[i] = v * _glideTau[i] * (1f - decay) / dtMs;
            }
            else
            {
                float t = Math.Min(dtMs, MathF.Abs(v) / _glideDec[i]);
                next = (MathF.Abs(v) - _glideDec[i] * t) * Sign(v);
                _glideOut[i] = (v + next) / 2f * t / dtMs;
            }
            _glideV[i] = MathF.Abs(next) < GlideStopDegPerS ? 0f : next;
        }
        _lastAxes = axes;
    }

    // Kotlin's rounding and helpers, exactly.

    /// <summary>Kotlin Float.roundToInt (Java Math.round: floor(x + 0.5), saturating); NaN (which Kotlin rejects) gives 0.</summary>
    public static int RoundToInt(float x)
    {
        if (float.IsNaN(x)) return 0;
        double r = Math.Floor((double)x + 0.5);
        return r >= int.MaxValue ? int.MaxValue : r <= int.MinValue ? int.MinValue : (int)r;
    }

    /// <summary>Kotlin Float.toInt: toward zero, saturating, NaN = 0.</summary>
    public static int ToInt(float x)
    {
        if (float.IsNaN(x)) return 0;
        return x >= int.MaxValue ? int.MaxValue : x <= int.MinValue ? int.MinValue : (int)x;
    }

    /// <summary>kotlin.math.exp(Float): computed in double, then rounded to float.</summary>
    private static float Exp(float x) => (float)Math.Exp(x);

    /// <summary>kotlin.math.sign(Float): ±1, and 0 (keeping its sign) or NaN as they are.</summary>
    private static float Sign(float x) => x > 0f ? 1f : x < 0f ? -1f : x;

    /// <summary>Kotlin coerceIn: NaN passes through.</summary>
    private static float CoerceIn(float x, float min, float max) => x < min ? min : x > max ? max : x;

    /// <summary>Kotlin coerceAtMost: NaN passes through.</summary>
    private static float CoerceAtMost(float x, float max) => x > max ? max : x;
}
