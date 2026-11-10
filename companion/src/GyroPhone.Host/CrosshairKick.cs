namespace GyroPhone.Host;

/// <summary>
/// The crosshair's fire kick (OverlaySettings.CrosshairKick): each shot it jumps out and bounces back like a rifle's
/// recoil. The bounce is one curve baked into <see cref="Curve"/> once (a sharp rise over <see cref="RiseMs"/>, then a
/// damped spring that dips a little under its rest size and settles by <see cref="LengthMs"/>), so a frame only reads
/// the table. <see cref="Once"/> kicks once per press; <see cref="Auto"/> keeps kicking every period while the Fire
/// display button is held, each shot rising from wherever the last one is. The rifle look kicks back along the same
/// curve, and the muzzle flash (<see cref="MuzzleFlash"/>) times itself by the same shots (<see cref="State.ShotAt"/>).
/// As the phone's CrosshairKick.
/// </summary>
public static class CrosshairKick
{
    public const int Off = 0, Once = 1, Auto = 2;

    /// <summary>The modes' names, by number.</summary>
    public static readonly string[] Modes = ["Off", "Once", "Auto rifle"];

    public const float RiseMs = 24f, LengthMs = 280f;
    private const float DecayMs = 42f, SwingMs = 150f;
    private const int Steps = 128;

    /// <summary>The bounce after the rise, from 1 just at <see cref="RiseMs"/> to 0 at <see cref="LengthMs"/>.</summary>
    public static readonly float[] Curve = Bake();

    private static float[] Bake()
    {
        var c = new float[Steps + 1];
        for (int i = 0; i < Steps; i++)
        {
            float s = i * (LengthMs - RiseMs) / Steps;
            // Fades out over the last fifth so it ends at exactly 0.
            float tail = Math.Clamp((LengthMs - RiseMs - s) / (LengthMs * 0.2f), 0f, 1f);
            c[i] = MathF.Exp(-s / DecayMs) * MathF.Cos(2f * MathF.PI * s / SwingMs) * tail;
        }
        return c;
    }

    /// <summary>The baked curve at <paramref name="ms"/> after a shot that rose from <paramref name="from"/> (0 rest .. 1 full kick).</summary>
    public static float At(float ms, float from)
    {
        if (ms < 0f || ms >= LengthMs) return 0f;
        if (ms < RiseMs)
        {
            // Eases out of the shot: fast at first, like the kick.
            float u = ms / RiseMs;
            float e = 1f - (1f - u) * (1f - u);
            return from + (1f - from) * e;
        }
        float x = (ms - RiseMs) / (LengthMs - RiseMs) * Steps;
        int i = Math.Min((int)x, Steps - 1);
        float f = x - i;
        return Curve[i] + (Curve[i + 1] - Curve[i]) * f;
    }

    /// <summary>Auto rifle's shots per minute to a shot's period in milliseconds.</summary>
    public static long PeriodMs(int rpm) => 60_000L / Math.Clamp(rpm, 60, 1800);

    /// <summary>One crosshair's or rifle's kick: <see cref="Step"/> it each frame with whether it fires; <see cref="Value"/> is −0.2 .. 1 (0 rest).</summary>
    public sealed class State
    {
        private long _shotAt = -1;

        /// <summary>When the last shot was (ms), until its kick has settled (<see cref="LengthMs"/>); −1 none.</summary>
        public long ShotAt => _shotAt;
        private float _from;
        private bool _held;

        public float Value { get; private set; }

        /// <summary>
        /// At <paramref name="t"/> ms, with <paramref name="firing"/> held, in <paramref name="mode"/>: the kick now; true while it
        /// still moves. With <paramref name="flash"/> the shots still count in <see cref="Off"/> (once per press) for the muzzle
        /// flash, without kicking.
        /// </summary>
        public bool Step(long t, bool firing, int mode, long periodMs, bool flash = false)
        {
            if (mode == Off && flash)
            {
                bool moving = Step(t, firing, Once, periodMs);
                Value = 0f;
                return moving;
            }
            if (mode == Off)
            {
                _held = firing;
                _shotAt = -1;
                Value = 0f;
                return false;
            }
            float now = _shotAt < 0 ? 0f : At(t - _shotAt, _from);
            if (firing && !_held) Shoot(t, now);
            else if (firing && mode == Auto && t - _shotAt >= periodMs)
            {
                // Keeps the beat; a long stall (a dropped frame or two) starts it again from now.
                long at = _shotAt + periodMs;
                Shoot(t - at < periodMs ? at : t, now);
            }
            _held = firing;
            if (_shotAt >= 0 && t - _shotAt >= LengthMs) _shotAt = -1;
            Value = _shotAt < 0 ? 0f : At(t - _shotAt, _from);
            return _shotAt >= 0 || (firing && mode == Auto);
        }

        private void Shoot(long t, float now)
        {
            _from = Math.Clamp(now, 0f, 1f);
            _shotAt = t;
        }
    }
}
