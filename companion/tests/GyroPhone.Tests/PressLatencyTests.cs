using GyroPhone.Host;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

public class PressLatencyTests
{
    // Host µs = phone µs + Offset.
    private const long Offset = 5_000_000_000;

    [Fact]
    public void PressTimeAfterInjectionMeasuresInputToGame()
    {
        var p = new PressLatency();
        // Input at phone 1_000_000, STATE sent 4 ms later, injected 3 ms after that.
        p.OnInjected(1_004_000, Offset + 1_007_000, Offset, true);
        p.OnPressTime(new PressTime(1_004_000, 1_000_000), Offset + 1_007_100, Offset, true);
        Assert.Equal((7_000L, 4_000L), p.Median());
        Assert.Equal(1, p.Count);
    }

    [Fact]
    public void PressTimeBeforeInjectionWaitsForIt()
    {
        var p = new PressLatency();
        p.OnPressTime(new PressTime(2_002_000, 2_000_000), Offset + 2_004_000, Offset, true);
        Assert.Null(p.Median());
        p.OnInjected(2_002_000, Offset + 2_005_000, Offset, true);
        Assert.Equal((5_000L, 2_000L), p.Median());
    }

    [Fact]
    public void ANewerStateInjectedInTheSameBurstCarriesIt()
    {
        var p = new PressLatency();
        p.OnPressTime(new PressTime(3_001_000, 3_000_000), Offset + 3_002_000, Offset, true);
        // Its STATE merged into the next one (1 ms newer), which went out.
        p.OnInjected(3_002_000, Offset + 3_003_000, Offset, true);
        Assert.Equal((3_000L, 1_000L), p.Median());
    }

    [Fact]
    public void FirstCarryingInjectionCounts()
    {
        var p = new PressLatency();
        p.OnInjected(4_001_000, Offset + 4_002_000, Offset, true);
        p.OnInjected(4_003_000, Offset + 4_004_000, Offset, true);
        p.OnPressTime(new PressTime(4_001_000, 4_000_000), Offset + 4_004_500, Offset, true);
        Assert.Equal(2_000L, p.Median()!.Value.TotalUs);
    }

    [Fact]
    public void PhoneClockWrapsAround()
    {
        var p = new PressLatency();
        uint ev = uint.MaxValue - 500;
        uint st = unchecked(ev + 1_500);
        long hostEv = Offset + (1L << 32) + ev - (1L << 32); // the event's host time
        p.OnInjected(st, hostEv + 2_500, Offset, true);
        p.OnPressTime(new PressTime(st, ev), hostEv + 2_600, Offset, true);
        Assert.Equal((2_500L, 1_500L), p.Median());
    }

    [Fact]
    public void NoClockOffsetMeasuresNothing()
    {
        var p = new PressLatency();
        p.OnInjected(1_004_000, Offset + 1_007_000, Offset, false);
        p.OnPressTime(new PressTime(1_004_000, 1_000_000), Offset + 1_007_100, Offset, false);
        Assert.Null(p.Median());
    }

    [Fact]
    public void MedianOfManyPresses()
    {
        var p = new PressLatency();
        long[] lat = [9_000, 5_000, 7_000, 30_000, 6_000];
        for (int i = 0; i < lat.Length; i++)
        {
            uint ev = (uint)(10_000_000 + i * 100_000);
            p.OnInjected(ev + 1_000, Offset + ev + lat[i], Offset, true);
            p.OnPressTime(new PressTime(ev + 1_000, ev), Offset + ev + lat[i] + 100, Offset, true);
        }
        Assert.Equal(7_000L, p.Median()!.Value.TotalUs);
        Assert.Equal(5, p.Count);
    }

    [Fact]
    public void PressTimeAndTestModeRoundTrip()
    {
        Span<byte> b = stackalloc byte[Wire.PressTimePlain];
        WireCodec.EncodePressTime(new PressTime(0x01020304, 0xA0B0C0D0), b);
        Assert.Equal("04030201d0c0b0a0", Convert.ToHexString(b).ToLowerInvariant());
        Assert.Equal(new PressTime(0x01020304, 0xA0B0C0D0), WireCodec.DecodePressTime(b));
        Span<byte> t = stackalloc byte[Wire.TestModePlain];
        WireCodec.EncodeTestMode(TestModeFlags.PressTimes | TestModeFlags.LatencyScreen, t);
        Assert.Equal("03000000", Convert.ToHexString(t).ToLowerInvariant());
        Assert.Equal(Wire.Header + 8 + Wire.GcmTag, PacketType.Size(PacketType.PressTime));
        Assert.Equal(Wire.Header + 4 + Wire.GcmTag, PacketType.Size(PacketType.TestMode));
    }
}
