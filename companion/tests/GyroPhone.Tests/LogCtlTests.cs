using System.Net;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

public class LogCtlTests
{
    private static byte[] Ctl(byte log, bool on)
    {
        var d = new byte[Wire.LogCtlSize];
        d[0] = Wire.Magic0;
        d[1] = Wire.Magic1;
        d[2] = Wire.Version;
        d[3] = PacketType.LogCtl;
        d[12] = log;
        d[13] = (byte)(on ? 1 : 0);
        return d;
    }

    private static SocketAddress Loopback => new IPEndPoint(IPAddress.Loopback, 50000).Serialize();
    private static SocketAddress Lan => new IPEndPoint(IPAddress.Parse("192.168.1.20"), 50000).Serialize();

    [Fact]
    public void LoopbackSwitchesThePerformanceLogs()
    {
        using var h = new Harness();
        h.Engine.OnDatagram(Ctl(Wire.LogCtlInputLog, true), Loopback, h.Clock.NowUs);
        h.Engine.OnDatagram(Ctl(Wire.LogCtlOverlayTiming, true), Loopback, h.Clock.NowUs);
        Assert.True(h.Engine.Settings.InputLog);
        Assert.True(h.Engine.Settings.OverlayTimingLog);
        h.Engine.OnDatagram(Ctl(Wire.LogCtlInputLog, false), Loopback, h.Clock.NowUs);
        h.Engine.OnDatagram(Ctl(Wire.LogCtlOverlayTiming, false), Loopback, h.Clock.NowUs);
        Assert.False(h.Engine.Settings.InputLog);
        Assert.False(h.Engine.Settings.OverlayTimingLog);
    }

    [Fact]
    public void FromAnotherMachineItIsDropped()
    {
        using var h = new Harness();
        h.Engine.OnDatagram(Ctl(Wire.LogCtlInputLog, true), Lan, h.Clock.NowUs);
        Assert.False(h.Engine.Settings.InputLog);
    }
}
