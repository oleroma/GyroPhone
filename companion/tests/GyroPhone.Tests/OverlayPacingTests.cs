using GyroPhone.Host;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>The overlay windows' pacing: frame-rate caps in whole refreshes, and frames that show nothing new.</summary>
public class OverlayPacingTests
{
    [Theory]
    [InlineData(60, 60, 1)]
    [InlineData(144, 60, 2)]
    [InlineData(165, 60, 3)]
    [InlineData(120, 60, 2)]
    [InlineData(240, 60, 4)]
    [InlineData(144, 30, 5)]
    [InlineData(60, 30, 2)]
    [InlineData(60, 120, 1)]
    [InlineData(144, 0, 1)]
    [InlineData(0, 60, 1)]
    public void A_cap_is_a_whole_number_of_refreshes(int hz, int cap, int every)
    {
        Assert.Equal(every, OverlayPacing.RefreshesPerPicture(hz, cap));
    }

    [Fact]
    public void A_capped_gap_is_its_refreshes_less_half_of_one()
    {
        Assert.Equal(0, OverlayPacing.MinGapMs(144, 0));
        // 60 on 144 Hz: every 2nd refresh, 13.9 ms, with half a refresh to spare.
        Assert.Equal(2 * 1000.0 / 144 - 0.5 * 1000.0 / 144, OverlayPacing.MinGapMs(144, 60), 6);
        // Never longer than the refreshes it waits for: a pacer ticking at that spacing draws every tick.
        foreach (int hz in new[] { 60, 75, 120, 144, 165, 240 })
            foreach (int cap in new[] { 30, 60 })
                Assert.True(OverlayPacing.MinGapMs(hz, cap) < OverlayPacing.RefreshesPerPicture(hz, cap) * 1000.0 / hz);
        // An unknown rate counts as 60 Hz.
        Assert.Equal(OverlayPacing.MinGapMs(60, 60), OverlayPacing.MinGapMs(0, 60));
    }

    [Fact]
    public void A_tiny_turn_shows_only_on_a_big_window_or_scaled_up()
    {
        float noise = 0.0002f; // radians: a still phone's gyro noise
        Assert.False(OverlayPacing.TurnShows(noise, 420, 1f));
        Assert.True(OverlayPacing.TurnShows(noise, 2000, 1f));
        Assert.True(OverlayPacing.TurnShows(noise, 420, 4f));
        Assert.True(OverlayPacing.TurnShows(0.01f, 120, 0.25f)); // a smaller turn amount still counts as 1
        Assert.True(OverlayPacing.TurnShows(float.NaN, 420, 1f));
    }

    private static OverlayFrame Turned(float deg)
    {
        float h = deg * MathF.PI / 360f;
        return new OverlayFrame { Qw = MathF.Cos(h), Qx = 0, Qy = MathF.Sin(h), Qz = 0 };
    }

    [Fact]
    public void Turn_between_poses_is_exact_for_tiny_and_big_turns()
    {
        var a = Turned(10f);
        Assert.Equal(0f, OverlayFrame.TurnBetween(a, a), 6);
        Assert.Equal(0.001f, OverlayFrame.TurnBetween(a, Turned(10f + 0.001f * 180f / MathF.PI)), 5);
        Assert.Equal(MathF.PI / 2f, OverlayFrame.TurnBetween(a, Turned(100f)), 4);
        // q and −q are the same pose.
        var b = Turned(30f);
        var nb = new OverlayFrame { Qw = -b.Qw, Qx = -b.Qx, Qy = -b.Qy, Qz = -b.Qz };
        Assert.Equal(OverlayFrame.TurnBetween(a, b), OverlayFrame.TurnBetween(a, nb), 5);
    }

    [Fact]
    public void Same_shown_ignores_the_time_and_motion_but_not_what_is_drawn()
    {
        var a = new OverlayFrame { FingerCount = 1, StickCount = 1 };
        a.Fingers[0] = 0.5f;
        a.StickIndex[0] = 3;
        a.StickXY[0] = 0.25f;
        var b = new OverlayFrame();
        a.CopyTo(b);
        b.PhoneTimeUs = 12345;
        b.TurnYaw = 1f;
        b.Qw = 0.9f;
        b.Fingers[4] = 0.7f; // past the finger count
        Assert.True(a.SameShown(b));

        void Differs(Action<OverlayFrame> change)
        {
            var c = new OverlayFrame();
            a.CopyTo(c);
            change(c);
            Assert.False(a.SameShown(c));
        }
        Differs(c => c.Fingers[1] = 0.1f);
        Differs(c => c.FingerCount = 0);
        Differs(c => c.Pressed[2] = 1);
        Differs(c => c.Latched[0] = 4);
        Differs(c => c.StickXY[1] = -0.5f);
        Differs(c => c.LayoutVersion = 9);
        Differs(c => c.Flags = OverlayFrame.FlagRatchetHeld);
        Differs(c => c.Axes = 2);
        Differs(c => c.PoseActive = 1);
        Differs(c => c.PadButtons = OverlayFrame.PadSouth);
        Differs(c => c.PadSticks[2] = 0.3f);
        Differs(c => c.PadR2Pull = 0.5f);
    }
}
