using GyroPhone.Host;

namespace GyroPhone.Tests;

/// <summary>The crosshair's fire kick, as the phone's CrosshairKickTest.</summary>
public class CrosshairKickTests
{
    [Fact]
    public void CurveRisesBouncesAndSettles()
    {
        Assert.Equal(0f, CrosshairKick.At(0f, 0f));
        Assert.Equal(1f, CrosshairKick.At(CrosshairKick.RiseMs, 0f), 0.001f);
        float low = Enumerable.Range(30, 171).Min(ms => CrosshairKick.At(ms, 0f));
        Assert.InRange(low, -0.25f, -0.02f);
        Assert.Equal(0f, CrosshairKick.At(CrosshairKick.LengthMs, 0f));
        Assert.Equal(0f, CrosshairKick.Curve[^1]);
    }

    [Fact]
    public void OnceKicksOncePerPress()
    {
        var s = new CrosshairKick.State();
        Assert.False(s.Step(0, false, CrosshairKick.Once, 100));
        s.Step(10, true, CrosshairKick.Once, 100);
        s.Step(34, true, CrosshairKick.Once, 100);
        Assert.Equal(1f, s.Value, 0.05f);
        Assert.False(s.Step(400, true, CrosshairKick.Once, 100));
        Assert.Equal(0f, s.Value);
        s.Step(410, false, CrosshairKick.Once, 100);
        s.Step(420, true, CrosshairKick.Once, 100);
        s.Step(440, true, CrosshairKick.Once, 100);
        Assert.True(s.Value > 0.3f);
    }

    [Fact]
    public void AutoRifleKeepsKickingWhileHeld()
    {
        var s = new CrosshairKick.State();
        s.Step(0, true, CrosshairKick.Auto, 100);
        int peaks = 0;
        long t = 0;
        float was = 0f;
        bool rising = true;
        while (t < 1000)
        {
            t += 8;
            Assert.True(s.Step(t, true, CrosshairKick.Auto, 100));
            if (rising && s.Value < was) { peaks++; rising = false; }
            if (s.Value > was) rising = true;
            was = s.Value;
        }
        Assert.InRange(peaks, 9, 11);
        Assert.True(s.Step(t + 8, false, CrosshairKick.Auto, 100));
        Assert.False(s.Step(t + 400, false, CrosshairKick.Auto, 100));
    }
}
