using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

public class PadMapperTests
{
    [Fact]
    public void X360NegatesYAndClampsMinValue()
    {
        var s = new StateFrame { Lx = 1000, Ly = 2000, Rx = short.MinValue, Ry = short.MinValue };
        var f = PadMapper.ToX360(s);
        Assert.Equal(1000, f.Lx);
        Assert.Equal(-2000, f.Ly);
        Assert.Equal(-32767, f.Rx);
        Assert.Equal(32767, f.Ry);
    }

    [Theory]
    [InlineData(0, 0)]
    [InlineData(512, 128)]
    [InlineData(1023, 255)]
    [InlineData(5000, 255)]
    public void Triggers(int v, int expected)
    {
        var s = new StateFrame { Lt = (ushort)v, Rt = (ushort)v };
        Assert.Equal(expected, PadMapper.ToX360(s).Lt);
        Assert.Equal(expected, PadMapper.ToDs4(s).Rt);
        bool digital = (PadMapper.ToDs4(s).Buttons & Ds4Buttons.TriggerRight) != 0;
        Assert.Equal(expected >= 1, digital);
    }

    [Theory]
    [InlineData(0, XusbButtons.DpadUp, 0)]
    [InlineData(1, XusbButtons.DpadUp | XusbButtons.DpadRight, 1)]
    [InlineData(2, XusbButtons.DpadRight, 2)]
    [InlineData(3, XusbButtons.DpadDown | XusbButtons.DpadRight, 3)]
    [InlineData(4, XusbButtons.DpadDown, 4)]
    [InlineData(5, XusbButtons.DpadDown | XusbButtons.DpadLeft, 5)]
    [InlineData(6, XusbButtons.DpadLeft, 6)]
    [InlineData(7, XusbButtons.DpadUp | XusbButtons.DpadLeft, 7)]
    [InlineData(8, 0, 8)]
    [InlineData(200, 0, 8)]
    public void HatsBothModes(int hat, int xusb, int ds4)
    {
        var s = new StateFrame { Hat = (byte)hat };
        Assert.Equal(xusb, PadMapper.ToX360(s).Buttons);
        Assert.Equal(ds4, PadMapper.ToDs4(s).Hat);
    }

    [Fact]
    public void Ds4HatTableOrder()
    {
        Assert.Equal(["North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest", "None"], PadMapper.Ds4HatNames);
    }

    [Theory]
    [InlineData(-32767, 0)]
    [InlineData(short.MinValue, 0)]
    [InlineData(0, 128)]
    [InlineData(32767, 255)]
    public void Ds4AxisBytes(short v, int expected)
    {
        var s = new StateFrame { Lx = v, Ly = v };
        var f = PadMapper.ToDs4(s);
        Assert.Equal(expected, f.Lx);
        Assert.Equal(expected, f.Ly); // no Y flip
    }

    [Fact]
    public void X360Buttons()
    {
        var s = new StateFrame { Buttons = 0x07FF };
        Assert.Equal(0xF7F0, PadMapper.ToX360(s).Buttons);
        s.Buttons = 0x8003; // A, B, SHARE: SHARE dropped
        Assert.Equal(XusbButtons.A | XusbButtons.B, PadMapper.ToX360(s).Buttons);
    }

    [Fact]
    public void Ds4Buttons_()
    {
        var s = new StateFrame { Buttons = (1 << 0) | (1 << 3) | (1 << 6) | (1 << 7) | (1 << 10) | (1 << 15) };
        var f = PadMapper.ToDs4(s);
        Assert.Equal(Ds4Buttons.Cross | Ds4Buttons.Triangle | Ds4Buttons.Share | Ds4Buttons.Options, f.Buttons);
        Assert.Equal(Ds4Special.Ps | Ds4Special.Touchpad, f.Special);
    }

    [Fact]
    public void NeutralFrames()
    {
        Assert.Equal(X360Frame.Neutral, PadMapper.ToX360(new StateFrame()));
        Assert.Equal(Ds4Frame.Neutral, PadMapper.ToDs4(new StateFrame()));
    }

    [Fact]
    public void EngineSubmitsOnlyOnChange()
    {
        var h = new Harness(mode: OutputMode.DualShock4);
        var phone = h.NewPhone();
        h.Connect(phone);
        int n = h.Pad.Ds4.Count;
        h.Send(phone, phone.State(f => f.MouseTotalX += 5)); // pad unchanged
        Assert.Equal(n, h.Pad.Ds4.Count);
        h.Send(phone, phone.State(f => f.Lx = 32767));
        Assert.Equal(n + 1, h.Pad.Ds4.Count);
        Assert.Equal(255, h.Pad.Ds4[^1].Lx);
    }
}
