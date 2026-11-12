using System.Net;
using GyroPhone.Host;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

public class KeepAwakeTests
{
    private static SocketAddress At(string ip) => new IPEndPoint(IPAddress.Parse(ip), 47800).Serialize();

    private static LinkInfo Link(byte flags = 0, byte path = LinkPath.Unknown) => new(0, 0, 0, 0, 0, 0, 0, flags, path);

    [Fact]
    public void SentOverWifiAndBeforeTheFirstLinkInfo()
    {
        Assert.True(HostEngine.KeepAwakeWanted(null, At("192.168.137.212")));
        Assert.True(HostEngine.KeepAwakeWanted(Link(path: LinkPath.Wifi), At("192.168.137.212")));
        Assert.True(HostEngine.KeepAwakeWanted(Link(path: LinkPath.Hotspot), At("10.0.0.5")));
        Assert.True(HostEngine.KeepAwakeWanted(null, At("fe80::1")));
    }

    [Fact]
    public void SkippedOverWiresAndLoopback()
    {
        Assert.False(HostEngine.KeepAwakeWanted(Link(path: LinkPath.UsbTether), At("192.168.42.129")));
        Assert.False(HostEngine.KeepAwakeWanted(Link(path: LinkPath.UsbDirect), At("192.168.42.129")));
        Assert.False(HostEngine.KeepAwakeWanted(Link(path: LinkPath.Ethernet), At("192.168.1.20")));
        Assert.False(HostEngine.KeepAwakeWanted(Link(flags: LinkInfoFlags.Usb), At("192.168.42.129")));
        Assert.False(HostEngine.KeepAwakeWanted(Link(flags: LinkInfoFlags.Ethernet), At("192.168.1.20")));
        Assert.False(HostEngine.KeepAwakeWanted(null, At("127.0.0.1")));
        Assert.False(HostEngine.KeepAwakeWanted(null, At("::1")));
        Assert.False(HostEngine.KeepAwakeWanted(null, At("::ffff:127.0.0.1")));
    }
}
