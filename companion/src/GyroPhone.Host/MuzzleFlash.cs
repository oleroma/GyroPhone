namespace GyroPhone.Host;

/// <summary>
/// The muzzle flash on each Fire display shot (OverlaySettings.MuzzleFlash), in time with the kicks
/// (<see cref="CrosshairKick.State.ShotAt"/>): a burst of white light that cools to the Flash colour (yellow) by
/// <see cref="HotMs"/>, then to Flash end (orange) by <see cref="EndMs"/>, then fades back to the base colour by
/// <see cref="LengthMs"/>. Its glow (<see cref="Glow"/>) is brightest at the shot and gone by <see cref="LengthMs"/>.
/// As the phone's MuzzleFlash.
/// </summary>
public static class MuzzleFlash
{
    public const float HotMs = 30f, EndMs = 110f, LengthMs = 220f;

    /// <summary>The flash's glow <paramref name="ms"/> after a shot: 1 at the shot, easing out to 0 at <see cref="LengthMs"/>; 0 outside it.</summary>
    public static float Glow(float ms)
    {
        if (ms < 0f || ms >= LengthMs) return 0f;
        float u = 1f - ms / LengthMs;
        return u * u;
    }

    /// <summary>
    /// The flash's colour (ARGB) <paramref name="ms"/> after a shot: white, to <paramref name="hot"/> by <see cref="HotMs"/>,
    /// to <paramref name="end"/> by <see cref="EndMs"/>, back to <paramref name="baseColor"/> by <see cref="LengthMs"/>;
    /// <paramref name="baseColor"/> outside it. Alpha blends too.
    /// </summary>
    public static int Color(float ms, int hot, int end, int baseColor)
    {
        if (ms < 0f || ms >= LengthMs) return baseColor;
        if (ms < HotMs) return Mix(unchecked((int)0xFFFFFFFF), hot, ms / HotMs);
        if (ms < EndMs) return Mix(hot, end, (ms - HotMs) / (EndMs - HotMs));
        return Mix(end, baseColor, (ms - EndMs) / (LengthMs - EndMs));
    }

    /// <summary>ARGB colours <paramref name="a"/> to <paramref name="b"/> by <paramref name="t"/> (0 .. 1), per channel.</summary>
    public static int Mix(int a, int b, float t)
    {
        float k = Math.Clamp(t, 0f, 1f);
        int o = 0;
        foreach (int s in new[] { 24, 16, 8, 0 })
        {
            int ca = (int)((uint)a >> s) & 0xFF, cb = (int)((uint)b >> s) & 0xFF;
            o |= Math.Clamp(ca + (int)((cb - ca) * k), 0, 255) << s;
        }
        return o;
    }
}
