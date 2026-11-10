using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

public class Ds4MotionTests
{
    /// <summary>
    /// The MOTION the phone's MotionFeedTest encodes (MotionFeedTest.VECTOR in core): two samples, seq 5,
    /// time 1000 + 2000 µs, then flags MAY_MOVE.
    /// </summary>
    internal const string Vector =
        "01020500d0070000" +
        "0000100020003000f6ffecff0020" +
        "e8030100fefffd0000000000c0df" +
        "0000000000000000000000000000" + "0000000000000000000000000000" + "0000000000000000000000000000" +
        "0000000000000000000000000000" + "0000000000000000000000000000" + "0000000000000000000000000000";

    private static MotionFrame VectorFrame()
    {
        var f = new MotionFrame { Flags = MotionFrame.FlagMayMove, Count = 2, NewestSeq = 5, NewestTimeUs = 2000 };
        f.DtUs[0] = 0; f.Gyro[0] = 16; f.Gyro[1] = 32; f.Gyro[2] = 48; f.Accel[0] = -10; f.Accel[1] = -20; f.Accel[2] = 8192;
        f.DtUs[1] = 1000; f.Gyro[3] = 1; f.Gyro[4] = -2; f.Gyro[5] = 253; f.Accel[3] = 0; f.Accel[4] = 0; f.Accel[5] = -8256;
        return f;
    }

    [Fact]
    public void MotionPacketSizeAndVector()
    {
        Assert.Equal(120, Wire.MotionPlain);
        Assert.Equal(Wire.Header + 120 + Wire.GcmTag, PacketType.Size(PacketType.Motion));
        Assert.True(PacketType.IsPhoneToHostSealed(PacketType.Motion));
        var b = new byte[MotionFrame.Size];
        VectorFrame().Encode(b);
        Assert.Equal(Vector, Hex.S(b));
        var d = new MotionFrame();
        d.Decode(Hex.B(Vector));
        Assert.True(d.MayMove);
        Assert.Equal(2, d.Count);
        Assert.Equal(5, d.NewestSeq);
        Assert.Equal(1000, d.DtUs[1]);
        Assert.Equal(253, d.Gyro[5]);
        Assert.Equal(-8256, d.Accel[5]);
    }

    [Fact]
    public void ReportCarriesButtonsMotionAndNoTouch()
    {
        var r = new byte[Ds4Report.Size];
        var f = new Ds4Frame((ushort)(Ds4Buttons.Cross | Ds4Buttons.ThumbRight), Ds4Special.Ps, 2, 1, 2, 3, 4, 5, 6);
        Ds4Report.Build(r, f, new Ds4Motion(0x12340, 16, -16, 300, -8192, 8192, 7));
        Assert.Equal(new byte[] { 1, 2, 3, 4 }, r[..4]);
        Assert.Equal(Ds4Buttons.Cross | Ds4Buttons.ThumbRight | 2, Le.U16(r, 4));
        Assert.Equal(Ds4Special.Ps, r[6]);
        Assert.Equal(5, r[7]);
        Assert.Equal(6, r[8]);
        Assert.Equal(0x1234, Le.U16(r, 9));
        Assert.Equal(16, Le.I16(r, 12));
        Assert.Equal(-16, Le.I16(r, 14));
        Assert.Equal(300, Le.I16(r, 16));
        Assert.Equal(-8192, Le.I16(r, 18));
        Assert.Equal(8192, Le.I16(r, 20));
        Assert.Equal(7, Le.I16(r, 22));
        Assert.Equal(1, r[32]);
        foreach (int o in new[] { 34, 38, 43, 47, 52, 56 }) Assert.Equal(0x80, r[o]);
        // No hat: the D-pad nibble says none.
        Ds4Report.Build(r, Ds4Frame.Neutral, Ds4Motion.Neutral);
        Assert.Equal(8, Le.U16(r, 4));
        Assert.Equal(8192, Le.I16(r, 20));
    }

    [Fact]
    public void FeedTakesEachSampleOnceWithItsTime()
    {
        var feed = new Ds4MotionFeed();
        var o = new Ds4Motion[MotionFrame.MaxSamples];
        var f = VectorFrame();
        Assert.Equal(2, feed.Take(f, o, 0));
        Assert.Equal(16, o[0].Gx);
        Assert.Equal(253, o[1].Gz);
        // 1000 µs is 187.5 ticks of 16/3 µs.
        Assert.Equal(187, (ushort)(o[1].Ds4Timestamp - o[0].Ds4Timestamp));
        // The DualSense's clock is exact: 1000 µs is 3000 ticks of 1/3 µs.
        Assert.Equal(3000u, o[1].Clock - o[0].Clock);
        // The same packet again (a repeat): nothing new.
        Assert.Equal(0, feed.Take(f, o, 100));
        // The next one repeats sample 5 and adds 6: only 6 is new.
        f.DtUs[0] = 1000; f.Gyro[0] = 1; f.Gyro[1] = -2; f.Gyro[2] = 253;
        f.DtUs[1] = 1000; f.Gyro[3] = 7; f.Gyro[4] = 8; f.Gyro[5] = 9;
        f.NewestSeq = 6;
        Assert.Equal(1, feed.Take(f, o, 1000));
        Assert.Equal(7, o[0].Gx);
        // The remainders carry: two 1000 µs steps are 375 ticks.
        Assert.Equal(7, feed.Current.Gx);
        // An older packet arriving late is dropped.
        f.NewestSeq = 4;
        Assert.Equal(0, feed.Take(f, o, 1100));
    }

    [Fact]
    public void FeedRestsWhenStoppedOrSilent()
    {
        var feed = new Ds4MotionFeed();
        var o = new Ds4Motion[MotionFrame.MaxSamples];
        var f = VectorFrame();
        feed.Take(f, o, 0);
        Assert.True(feed.Current.Turning);
        Assert.False(feed.IsStale(Ds4MotionFeed.StaleUs - 1));
        Assert.True(feed.IsStale(Ds4MotionFeed.StaleUs));
        Assert.Equal(1, feed.Rest(o, Ds4MotionFeed.StaleUs));
        Assert.False(o[0].Turning);
        Assert.Equal(-8256, o[0].Az); // the accelerometer stays
        Assert.False(feed.IsStale(10 * Ds4MotionFeed.StaleUs));
        Assert.Equal(0, feed.Rest(o, 10 * Ds4MotionFeed.StaleUs));
        // Input not allowed on the phone: one resting reading if it was turning.
        f.NewestSeq = 9;
        feed.Take(f, o, 0);
        f.Flags = 0;
        Assert.Equal(1, feed.Take(f, o, 10));
        Assert.False(o[0].Turning);
        Assert.Equal(0, feed.Take(f, o, 20));
    }

    [Fact]
    public void EngineSendsEachSampleToTheDualShock4()
    {
        var h = new Harness(mode: OutputMode.DualShock4);
        var phone = h.NewPhone();
        h.Connect(phone);
        Assert.NotEqual(0, phone.WelcomeHostFlags & WelcomeFlags.Motion);
        h.Send(phone, phone.State(f => f.Lx = 32767));
        int n = h.Pad.Ds4.Count;
        var b = new byte[MotionFrame.Size];
        VectorFrame().Encode(b);
        h.Send(phone, phone.Sealed(PacketType.Motion, b));
        Assert.Equal(n + 2, h.Pad.Ds4.Count);
        // Each with the pad's buttons and sticks as they are.
        Assert.Equal(255, h.Pad.Ds4[^1].Lx);
        Assert.Equal(16, h.Pad.Ds4Motion[^2].Gx);
        Assert.Equal(253, h.Pad.Ds4Motion[^1].Gz);
        // A STATE after it keeps the motion.
        h.Send(phone, phone.State(f => f.Lx = 0));
        Assert.Equal(253, h.Pad.Ds4Motion[^1].Gz);
        // No MOTION for a while: the gyro rests.
        h.Clock.NowUs += Ds4MotionFeed.StaleUs;
        h.Send(phone, phone.State(f => f.MouseTotalX += 1));
        h.Engine.OnTick(h.Clock.NowUs);
        Assert.False(h.Pad.Ds4Motion[^1].Turning);
    }

    [Fact]
    public void EngineIgnoresMotionForAnXboxPad()
    {
        var h = new Harness(mode: OutputMode.Xbox360);
        var phone = h.NewPhone();
        h.Connect(phone);
        var b = new byte[MotionFrame.Size];
        VectorFrame().Encode(b);
        h.Send(phone, phone.Sealed(PacketType.Motion, b));
        Assert.Empty(h.Pad.Ds4);
    }
}
