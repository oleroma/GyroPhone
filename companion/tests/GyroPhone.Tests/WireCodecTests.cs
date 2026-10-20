using GyroPhone.Protocol;

namespace GyroPhone.Tests;

public class WireCodecTests
{
    [Fact]
    public void SizesMatchTable()
    {
        Assert.Equal(94, PacketType.Size(PacketType.State));
        Assert.Equal(98, PacketType.Size(PacketType.StateHires));
        Assert.Equal(36, PacketType.Size(PacketType.Ping));
        Assert.Equal(50, PacketType.Size(PacketType.LinkInfo));
        Assert.Equal(32, PacketType.Size(PacketType.Bye));
        Assert.Equal(52, PacketType.Size(PacketType.Pong));
        Assert.Equal(50, PacketType.Size(PacketType.HostStatus));
        Assert.Equal(32, PacketType.Size(PacketType.Rumble));
        Assert.Equal(94, PacketType.Size(PacketType.Foreground));
        Assert.Equal(32, PacketType.Size(PacketType.HostBye));
        Assert.Equal(-1, PacketType.Size(0x55));
    }

    [Fact]
    public void ShortOversizeAndNameOverrunRejected()
    {
        var announce = Vectors.Packet("ANNOUNCE").Bytes("hex");
        Assert.False(WireCodec.TryDecodeAnnounce(announce[..63], out _));
        Assert.False(WireCodec.TryDecodeAnnounce([.. announce, 0], out _));
        var overrun = (byte[])announce.Clone();
        overrun[34] = 30; // nameLen > 29
        Assert.False(WireCodec.TryDecodeAnnounce(overrun, out _));
        var start = Vectors.Packet("PAIR_START").Bytes("hex");
        start[125] = 32;
        Assert.False(WireCodec.TryDecodePairStart(start, out _));
        var badMagic = Vectors.Packet("DISCOVER").Bytes("hex");
        badMagic[1] = 0;
        Assert.False(WireCodec.TryDecodeDiscover(badMagic, out _));
    }

    [Fact]
    public void NamesTruncateOnCodePointBoundary()
    {
        var b = new byte[Wire.AnnounceSize];
        // 9 x 3-byte characters = 27 bytes, then a 4-byte emoji that would cross 29.
        var name = new string('€', 9) + "\U0001F3AE";
        WireCodec.EncodeAnnounce(new Announce(1, Hex.Range(0, 16), 0, name), b);
        Assert.Equal(27, b[34]);
        Assert.True(WireCodec.TryDecodeAnnounce(b, out var a));
        Assert.Equal(new string('€', 9), a.Name);
    }

    [Fact]
    public void RoundTripsPairHostWithLongName()
    {
        var b = new byte[Wire.PairHostSize];
        var p = new PairHost(9, Hex.Range(1, 16), Vectors.Pairing.Bytes("pkH"), Hex.Range(2, 16), new string('x', 40));
        WireCodec.EncodePairHost(p, b);
        Assert.True(WireCodec.TryDecodePairHost(b, out var d));
        Assert.Equal(new string('x', 31), d.Name);
    }

    /// <summary>Same bytes as StateFrameTest.stateHiresLayout on the phone.</summary>
    [Fact]
    public void StateHiresLayout()
    {
        var f = new StateFrame
        {
            PhoneTimeUs = 0x01020304, Flags = 1, Buttons = 0x8001, Hat = 2, MouseButtons = 1,
            Lx = -1, Ly = 2, Rx = -3, Ry = 4, Lt = 5, Rt = 1023,
            MouseTotalX = 10, MouseTotalY = -20, WheelTotal = 300, HWheelTotal = -45, Hires = true,
        };
        f.SetKey(0x04, true);
        f.SetKey(0xE2, true);
        var b = new byte[Wire.StateHiresPlain];
        Assert.Equal(Wire.StateHiresPlain, f.Encode(b));
        Assert.Equal("04030201010001800201ffff0200fdff04000500ff0310000000000000000000000000000000000000000000000000000000040000000a000000ecffffff2c010000d3ffffff", Convert.ToHexString(b).ToLowerInvariant());
        var back = new StateFrame();
        back.Decode(b);
        Assert.True(back.Hires);
        Assert.Equal((300, -45), (back.WheelTotal, back.HWheelTotal));
        var plain = new byte[Wire.StatePlain];
        back.Hires = false;
        Assert.Equal(Wire.StatePlain, back.Encode(plain));
        back.Decode(plain);
        Assert.False(back.Hires);
        Assert.Equal(0, back.HWheelTotal);
    }

    [Fact]
    public void StateFrameModifierByteIsBitmapByte28()
    {
        var f = new StateFrame();
        f.SetKey(0xE7, true);
        Assert.Equal(0x80, f.Modifiers);
        var b = new byte[Wire.StatePlain];
        f.Encode(b);
        Assert.Equal(0x80, b[22 + 28]);
    }
}
