using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

public class DualSenseReportTests
{
    [Fact]
    public void BuildPutsEachFieldWhereTheDualSenseHasIt()
    {
        var r = new byte[DualSenseReport.Size];
        var f = new Ds4Frame((ushort)(Ds4Buttons.Cross | Ds4Buttons.ShoulderLeft | Ds4Buttons.ThumbRight),
            (byte)(Ds4Special.Ps | Ds4Special.Touchpad), 2, 1, 2, 3, 4, 5, 6);
        DualSenseReport.Build(r, f, new Ds4Motion(0x1234, 16, -16, 300, -8192, 8192, 7));
        Assert.Equal(0x01, r[0]);
        // LX LY RX RY L2 R2
        Assert.Equal(new byte[] { 1, 2, 3, 4, 5, 6 }, r[1..7]);
        // Counter: the driver's.
        Assert.Equal(0, r[7]);
        // Hat 2 (east) in the low nibble, Cross above it; L1 and R3; PS and touchpad click.
        Assert.Equal(0x22, r[8]);
        Assert.Equal(0x81, r[9]);
        Assert.Equal(0x03, r[10]);
        Assert.Equal(16, Le.I16(r, 16));
        Assert.Equal(-16, Le.I16(r, 18));
        Assert.Equal(300, Le.I16(r, 20));
        Assert.Equal(-8192, Le.I16(r, 22));
        Assert.Equal(8192, Le.I16(r, 24));
        Assert.Equal(7, Le.I16(r, 26));
        // No finger on either touch point; on cable, charging.
        Assert.Equal(0x80, r[33]);
        Assert.Equal(0x80, r[37]);
        Assert.Equal(0x1A, r[53]);
    }

    [Fact]
    public void NeutralIsCentredWithNoHat()
    {
        var r = new byte[DualSenseReport.Size];
        DualSenseReport.Build(r, Ds4Frame.Neutral, Ds4Motion.Neutral);
        Assert.Equal(new byte[] { 128, 128, 128, 128, 0, 0 }, r[1..7]);
        Assert.Equal(0x08, r[8]);
        Assert.Equal(0, r[9]);
        Assert.Equal(0, r[10]);
        Assert.Equal(8192, Le.I16(r, 24));
    }
}
