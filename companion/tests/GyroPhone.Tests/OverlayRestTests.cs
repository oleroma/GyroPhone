using GyroPhone.Host;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>"Fade when phone rests": flat at Level, still, untouched, for the quiet time.</summary>
public class OverlayRestTests
{
    private const long Ms = 1000;

    private static HostSettings Settings(out OverlaySettings o)
    {
        var s = new HostSettings();
        o = new OverlaySettings { Shown = true, LevelUp = [0f, 0f, 1f] };
        s.Overlays.Add(o);
        s.Normalize();
        return s;
    }

    /// <summary>A frame with <paramref name="dps"/> of turn over 16 ms, lying screen up (or tipped <paramref name="tipDeg"/>).</summary>
    private static OverlayFrame Frame(float dps, float tipDeg = 0f, bool gyro = true)
    {
        var f = new OverlayFrame();
        f.Flags = OverlayFrame.FlagUp;
        if (gyro) f.Flags |= OverlayFrame.FlagPose;
        f.TurnSeconds = 0.016f;
        f.TurnPitch = dps * 0.016f;
        float a = tipDeg * MathF.PI / 180f;
        f.UpX = 0f;
        f.UpY = MathF.Sin(a);
        f.UpZ = MathF.Cos(a);
        return f;
    }

    private static long Run(OverlayRest r, HostSettings s, Func<OverlayFrame> frame, long from, long forMs)
    {
        long t = from;
        for (; t < from + forMs * Ms; t += 16 * Ms) r.Feed(frame(), t, s);
        return t;
    }

    [Fact]
    public void RestsAfterTheQuietTimeOnly()
    {
        var s = Settings(out var o);
        var r = new OverlayRest();
        long t = Run(r, s, () => Frame(0.1f), 0, 2900);
        Assert.False(r.Resting(o.Id, t, t - 16 * Ms, s.OverlayRestFade));
        t = Run(r, s, () => Frame(0.1f), t, 200);
        Assert.True(r.Resting(o.Id, t, t - 16 * Ms, s.OverlayRestFade));
        Assert.True(r.LiveDps(t) < 0.2f);
    }

    [Fact]
    public void HeldNeverRests()
    {
        var s = Settings(out var o);
        var r = new OverlayRest();
        // A steady hand: mostly calm, but over the threshold now and then.
        int n = 0;
        long t = Run(r, s, () => Frame(n++ % 20 == 0 ? 1.5f : 0.2f), 0, 10_000);
        Assert.False(r.Resting(o.Id, t, t - 16 * Ms, s.OverlayRestFade));
    }

    [Fact]
    public void NoGyroCountsAsMoving()
    {
        var s = Settings(out var o);
        var r = new OverlayRest();
        long t = Run(r, s, () => Frame(0f, gyro: false), 0, 5000);
        Assert.False(r.Resting(o.Id, t, t - 16 * Ms, s.OverlayRestFade));
    }

    [Fact]
    public void OffLevelOrTouchedDoesNotRest()
    {
        var s = Settings(out var o);
        var r = new OverlayRest();
        long t = Run(r, s, () => Frame(0.1f, tipDeg: 20f), 0, 5000);
        Assert.False(r.Resting(o.Id, t, t - 16 * Ms, s.OverlayRestFade));
        t = Run(r, s, () => Frame(0.1f, tipDeg: 5f), t, 5000);
        Assert.True(r.Resting(o.Id, t, t - 16 * Ms, s.OverlayRestFade));
        // A finger on the screen brings it back at once.
        var touched = Frame(0.1f, tipDeg: 5f);
        touched.FingerCount = 1;
        r.Feed(touched, t, s);
        Assert.False(r.Resting(o.Id, t, t, s.OverlayRestFade));
    }

    [Fact]
    public void NeverLeveledNeverRests()
    {
        var s = Settings(out var o);
        o.LevelUp = null;
        var r = new OverlayRest();
        long t = Run(r, s, () => Frame(0.1f), 0, 5000);
        Assert.False(r.Resting(o.Id, t, t - 16 * Ms, s.OverlayRestFade));
    }

    [Fact]
    public void SilentPhoneFadesOnlyWhenAsked()
    {
        var s = Settings(out var o);
        var r = new OverlayRest();
        long t = Run(r, s, () => Frame(5f), 0, 1000);
        Assert.False(r.Resting(o.Id, t + 2000 * Ms, t, s.OverlayRestFade));
        Assert.True(r.Resting(o.Id, t + 3000 * Ms, t, s.OverlayRestFade));
        s.OverlayRestFade.WhenSilent = false;
        Assert.False(r.Resting(o.Id, t + 3000 * Ms, t, s.OverlayRestFade));
        s.OverlayRestFade.WhenSilent = true;
        s.OverlayRestFade.On = false;
        Assert.False(r.Resting(o.Id, t + 3000 * Ms, t, s.OverlayRestFade));
    }

    [Fact]
    public void LevelUpFromAnOlderRest()
    {
        // Tipped back 90° about the phone's right edge: lying screen up is its level.
        float h = MathF.Sqrt(0.5f);
        var o = new OverlaySettings { Rest = [h, -h, 0f, 0f] };
        var up = OverlayRest.LevelUpOf(o)!.Value;
        Assert.Equal(0f, up.X, 3);
        Assert.Equal(0f, MathF.Abs(up.Y), 3);
        Assert.Equal(1f, MathF.Abs(up.Z), 3);
    }
}
