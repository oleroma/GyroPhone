using GyroPhone.Host;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;
using Xunit;

namespace GyroPhone.Tests;

/// <summary>OVERLAY_SAMPLES: the PC turns a pointer's pose sample by sample, ending where one whole frame would.</summary>
public class OverlaySamplesTests
{
    private static byte[] Samples(uint time, params (short Y, short P, short R, ushort T)[] s)
    {
        var b = new byte[Wire.OverlaySamplesPlain];
        BitConverter.GetBytes(time).CopyTo(b, 0);
        b[4] = (byte)s.Length;
        for (int k = 0; k < s.Length; k++)
        {
            BitConverter.GetBytes(s[k].Y).CopyTo(b, 8 + k * 8);
            BitConverter.GetBytes(s[k].P).CopyTo(b, 10 + k * 8);
            BitConverter.GetBytes(s[k].R).CopyTo(b, 12 + k * 8);
            BitConverter.GetBytes(s[k].T).CopyTo(b, 14 + k * 8);
        }
        return b;
    }

    [Fact]
    public void SamplesEndWhereTheWholeFrameDoes()
    {
        var o = new OverlaySettings { Kind = OverlayKind.Laser, LaserSnake = true, Gravity = false, Ease = false };
        var f = new OverlayFrame { PhoneTimeUs = 7, TurnYaw = 3f, TurnPitch = 1.5f, TurnSeconds = 0.003f, Axes = 7, Flags = OverlayFrame.FlagPose };
        var smp = new OverlaySamples();
        smp.Decode(Samples(7, (1000, 500, 0, 100), (1000, 500, 0, 100), (1000, 500, 0, 100)));
        Assert.True(smp.Matches(f));
        var whole = new OverlayPose();
        whole.Feed(f, o);
        var steps = new OverlayPose();
        steps.FeedSamples(f, o, smp, new OverlayFrame(), 1000);
        Assert.Equal(whole.W, steps.W, 4);
        Assert.Equal(whole.X, steps.X, 4);
        Assert.Equal(whole.Y, steps.Y, 4);
        Assert.Equal(whole.Z, steps.Z, 4);
        Assert.Equal(3, steps.History!.Count);
        // Their times run up to the frame's.
        var times = new List<long>();
        steps.History.Since(0, ref times, static (ref List<long> t, float w, float x, float y, float z, long ms) => t.Add(ms));
        Assert.Equal([998L, 999L, 1000L], times);
    }

    [Fact]
    public void SamplesOfAnotherFrameDontMatch()
    {
        var f = new OverlayFrame { PhoneTimeUs = 8, TurnYaw = 1f, TurnSeconds = 0.001f };
        var smp = new OverlaySamples();
        smp.Decode(Samples(7, (1000, 0, 0, 100)));
        Assert.False(smp.Matches(f));
        f.PhoneTimeUs = 7;
        f.TurnYaw = 2f;
        Assert.False(smp.Matches(f));
    }
}
