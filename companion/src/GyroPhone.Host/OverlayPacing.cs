namespace GyroPhone.Host;

/// <summary>
/// The overlay windows' pacing sums (the tray's OverlayPacer and OverlayForm), here so they can be
/// tested: a frame-rate cap in whole screen refreshes, and when a new phone frame shows nothing new.
/// </summary>
public static class OverlayPacing
{
    /// <summary>Movement below this (pixels at the window's size) is not drawn: gyro noise on a still phone.</summary>
    public const float StillPx = 0.25f;

    /// <summary>A window faded out by "Fade when phone rests" draws at most this often (ms): a few pictures a second.</summary>
    public const int RestedFrameMs = 250;

    /// <summary>With no window busy for this long (ms), the pacer ticks only every <see cref="IdleTickMs"/>.</summary>
    public const int IdleAfterMs = 1000;

    /// <summary>The pacer's tick while no window is busy (ms): 20 a second, enough for the FPS overlay's 4 pictures.</summary>
    public const int IdleTickMs = 50;

    /// <summary>
    /// A window last busy (a new phone frame, something moving, fading or changed) at <paramref name="busyAtMs"/> still
    /// keeps the pacer at the screen's rate at <paramref name="nowMs"/>.
    /// </summary>
    public static bool Busy(long nowMs, long busyAtMs) => nowMs - busyAtMs < IdleAfterMs;

    /// <summary>
    /// Screen refreshes per picture for frame-rate cap <paramref name="capFps"/> (0 = every refresh): the
    /// nearest whole number, at least 1, so pictures stay tied to the screen. A cap of 60 draws every
    /// 2nd refresh at 144 Hz (72 fps), every 3rd at 165 Hz (55 fps), every one at 60 Hz.
    /// </summary>
    public static int RefreshesPerPicture(int refreshHz, int capFps) =>
        capFps <= 0 || refreshHz <= 0 ? 1 : Math.Max(1, (int)Math.Round(refreshHz / (double)capFps));

    /// <summary>
    /// The shortest time (ms) between a capped window's pictures: its whole refreshes, less half a
    /// refresh so a tick that comes a little early never skips one. 0 without a cap.
    /// </summary>
    public static double MinGapMs(int refreshHz, int capFps)
    {
        if (capFps <= 0) return 0;
        double period = 1000.0 / (refreshHz > 0 ? refreshHz : 60);
        return RefreshesPerPicture(refreshHz > 0 ? refreshHz : 60, capFps) * period - period * 0.5;
    }

    /// <summary>
    /// A turn of <paramref name="turnRad"/> (radians) moves something drawn in a window of
    /// <paramref name="sizePx"/> (its longer side), the turn shown <paramref name="scale"/> times, by
    /// <see cref="StillPx"/> or more. Measured from the window's whole side (not half) for a margin.
    /// </summary>
    public static bool TurnShows(float turnRad, int sizePx, float scale) =>
        !float.IsFinite(turnRad) || turnRad * Math.Max(1, sizePx) * Math.Max(1f, scale) >= StillPx;
}
