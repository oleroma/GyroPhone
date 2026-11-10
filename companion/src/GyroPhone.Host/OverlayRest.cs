using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// "Fade when phone rests": tells from the phone's OVERLAY frames when it lies on a table. The phone
/// rests for an overlay while, for the whole quiet time, it lies within the set angle of that
/// overlay's Level tilt, turns slower than the stillness threshold, and gets no input (no finger, no
/// control pressed, no stick or controller off centre). Held in a hand it always turns faster than
/// the threshold (a steady hand shakes at 1-3°/s), and a frame without gyro counts as moving, so a
/// held phone never rests. Not thread safe: the engine feeds it under its lock and copies what
/// <see cref="RestingOf"/> needs into its overlay view, which the overlay windows read without that lock.
/// </summary>
public sealed class OverlayRest
{
    /// <summary>The accelerometer this far from 1 g (in g) is the phone being moved.</summary>
    private const float GSlack = 0.1f;
    private const float StickOff = 0.15f;
    private const float PadStickOff = 0.3f;
    private const float TriggerOff = 0.1f;
    private const float DegToRad = MathF.PI / 180f;

    // When the phone last moved or got input (us), and when each overlay last saw it off its Level tilt.
    private long _busyUs = long.MinValue;
    private readonly Dictionary<string, long> _offLevelUs = [];
    // When the frames stopped being counted (no OVERLAY yet): the quiet time of "no frames" runs from here.
    private long _silentSinceUs = long.MinValue;
    private float _liveDps = float.NaN;
    private long _liveAtUs = long.MinValue;

    /// <summary>The phone's turning speed now (deg/s, smoothed), or null without gyro lately.</summary>
    public float? LiveDps(long nowUs) => LiveDpsOf(_liveDps, _liveAtUs, nowUs);

    /// <summary><see cref="LiveDps"/> from a copy of <see cref="LiveDpsRaw"/> and <see cref="LiveAtUs"/>.</summary>
    public static float? LiveDpsOf(float liveDps, long liveAtUs, long nowUs) =>
        float.IsNaN(liveDps) || nowUs - liveAtUs > 500_000 ? null : liveDps;

    // What a copy read without the engine lock needs (HostEngine's overlay view): see Resting.
    public float LiveDpsRaw => _liveDps;
    public long LiveAtUs => _liveAtUs;
    public long BusyUs => _busyUs;
    public long SilentSinceUs => _silentSinceUs;

    /// <summary>When overlay <paramref name="id"/> last saw the phone off its Level tilt (long.MinValue: not yet).</summary>
    public long OffLevelUs(string id) => _offLevelUs.TryGetValue(id, out long off) ? off : long.MinValue;

    /// <summary>No frames now (overlay data off, phone gone): everything starts again.</summary>
    public void Clear(long nowUs)
    {
        _busyUs = long.MinValue;
        _offLevelUs.Clear();
        _silentSinceUs = nowUs;
        _liveDps = float.NaN;
    }

    /// <summary>One OVERLAY at <paramref name="nowUs"/>.</summary>
    public void Feed(OverlayFrame f, long nowUs, HostSettings settings)
    {
        var fade = settings.OverlayRestFade;
        float? dps = Speed(f);
        if (dps is { } d)
        {
            float k = float.IsNaN(_liveDps) ? 1f : 0.2f;
            _liveDps = float.IsNaN(_liveDps) ? d : _liveDps + k * (d - _liveDps);
            _liveAtUs = nowUs;
        }
        // No gyro in this frame: it can't be told still, so it counts as moving (never rests in a hand).
        bool moving = !f.PoseLive || dps is { } s && s >= fade.StillBelowDps;
        if (moving || Shaken(f) || HasInput(f) || _busyUs == long.MinValue) _busyUs = nowUs;
        foreach (var o in settings.Overlays)
        {
            if (!o.Shown) continue;
            if (!AtLevel(f, o, fade.FlatWithinDeg) || !_offLevelUs.ContainsKey(o.Id)) _offLevelUs[o.Id] = nowUs;
        }
    }

    /// <summary>Whether overlay <paramref name="id"/> should be faded now; <paramref name="lastFrameUs"/> is when the newest OVERLAY came (long.MinValue: none).</summary>
    public bool Resting(string id, long nowUs, long lastFrameUs, OverlayRestFade fade) =>
        RestingOf(fade, nowUs, lastFrameUs, _busyUs, _silentSinceUs, OffLevelUs(id));

    /// <summary><see cref="Resting"/> from copies of <see cref="BusyUs"/>, <see cref="SilentSinceUs"/> and the overlay's <see cref="OffLevelUs"/>.</summary>
    public static bool RestingOf(OverlayRestFade fade, long nowUs, long lastFrameUs, long busyUs, long silentSinceUs, long offLevelUs)
    {
        if (!fade.On) return false;
        long quiet = fade.QuietMs * 1000L;
        if (lastFrameUs == long.MinValue)
            return fade.WhenSilent && silentSinceUs != long.MinValue && nowUs - silentSinceUs >= quiet;
        if (nowUs - lastFrameUs >= quiet) return fade.WhenSilent;
        if (busyUs == long.MinValue || offLevelUs == long.MinValue) return false;
        return nowUs - Math.Max(busyUs, offLevelUs) >= quiet;
    }

    /// <summary>The turning speed over this frame (deg/s), or null when it carries no gyro time.</summary>
    private static float? Speed(OverlayFrame f)
    {
        if (!f.PoseLive || f.TurnSeconds <= 0f) return null;
        float y = f.TurnYaw, p = f.TurnPitch, r = f.TurnRoll;
        return MathF.Sqrt(y * y + p * p + r * r) / f.TurnSeconds;
    }

    private static bool Shaken(OverlayFrame f)
    {
        if (!f.HasUp) return false;
        float g = MathF.Sqrt(f.UpX * f.UpX + f.UpY * f.UpY + f.UpZ * f.UpZ);
        return MathF.Abs(g - 1f) > GSlack;
    }

    /// <summary>Any finger, control pressed, ratchet held, stick or controller off centre. A latched control is a state, not input.</summary>
    public static bool HasInput(OverlayFrame f)
    {
        if (f.FingerCount > 0 || f.RatchetHeld) return true;
        foreach (byte b in f.Pressed) if (b != 0) return true;
        for (int i = 0; i < f.StickCount; i++)
        {
            float x = f.StickXY[i * 2], y = f.StickXY[i * 2 + 1];
            if (x * x + y * y > StickOff * StickOff) return true;
        }
        if (f.PadAttached)
        {
            if (f.PadButtons != 0 || f.PadL2Pull > TriggerOff || f.PadR2Pull > TriggerOff) return true;
            for (int i = 0; i < 2; i++)
            {
                float x = f.PadSticks[i * 2], y = f.PadSticks[i * 2 + 1];
                if (x * x + y * y > PadStickOff * PadStickOff) return true;
            }
        }
        return false;
    }

    /// <summary>The phone lies within <paramref name="withinDeg"/> of overlay <paramref name="o"/>'s Level tilt.</summary>
    public static bool AtLevel(OverlayFrame f, OverlaySettings o, float withinDeg)
    {
        if (!f.HasUp || LevelUpOf(o) is not { } up) return false;
        float l = MathF.Sqrt(f.UpX * f.UpX + f.UpY * f.UpY + f.UpZ * f.UpZ);
        if (l == 0f) return false;
        float cos = (f.UpX * up.X + f.UpY * up.Y + f.UpZ * up.Z) / l;
        return cos >= MathF.Cos(withinDeg * DegToRad);
    }

    /// <summary>
    /// Which way was up at overlay <paramref name="o"/>'s Level (phone frame, unit length): saved at
    /// Level, or for an overlay leveled before that was saved, read from its resting tilt; null when never leveled.
    /// </summary>
    public static (float X, float Y, float Z)? LevelUpOf(OverlaySettings o)
    {
        if (o.LevelUp is { Length: 3 } u)
        {
            float l = MathF.Sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]);
            return l > 0f ? (u[0] / l, u[1] / l, u[2] / l) : null;
        }
        if (o.Rest is { Length: 4 } q)
        {
            // The world's up in the phone frame for the pose q (as OverlayPose's gravity pull reads it).
            float w = q[0], x = q[1], y = q[2], z = q[3];
            return (2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x));
        }
        return null;
    }
}
