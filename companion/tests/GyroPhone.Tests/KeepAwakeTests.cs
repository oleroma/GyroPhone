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

    [Fact]
    public void NoPhoneMeansNoKeepAwakeAndAnIdleWatchdog()
    {
        var h = new Harness();
        Assert.False(h.Engine.KeepAwake());
        h.Tick(50);
        Assert.True(h.Engine.WatchdogIdle);
        var phone = h.NewPhone();
        h.Connect(phone);
        // A session ends both rests at once.
        Assert.False(h.Engine.WatchdogIdle);
        h.Sender.Clear();
        Assert.True(h.Engine.KeepAwake());
        Assert.Equal(PacketType.KeepAwake, phone.TryOpen(h.Sender.Last!, out _));
        h.Tick(50);
        Assert.False(h.Engine.WatchdogIdle);
    }

    [Fact]
    public void ARestingKeepAwakeWakesWhenAPhoneConnects()
    {
        var h = new Harness();
        var sw = System.Diagnostics.Stopwatch.StartNew();
        var rest = new Thread(h.Engine.RestKeepAwake);
        rest.Start();
        Thread.Sleep(50);
        Assert.True(rest.IsAlive);
        h.Connect(h.NewPhone());
        Assert.True(rest.Join(HostEngine.KeepAwakeIdleWaitMs * 3));
        Assert.True(sw.ElapsedMilliseconds < HostEngine.KeepAwakeIdleWaitMs, $"woke after {sw.ElapsedMilliseconds} ms");
        // With the session up there is no rest.
        sw.Restart();
        h.Engine.RestKeepAwake();
        Assert.True(sw.ElapsedMilliseconds < HostEngine.KeepAwakeIdleWaitMs / 2);
    }

    [Fact]
    public void SkippedWhereThePcIsNoWifiClient()
    {
        // A cable to the router, or the PC's own hotspot: the phone is on Wi-Fi, but no radio of the PC sleeps.
        Assert.False(HostEngine.KeepAwakeWanted(Link(path: LinkPath.Wifi), At("192.168.137.212"), pcWifiClient: false));
        Assert.False(HostEngine.KeepAwakeWanted(null, At("192.168.1.20"), pcWifiClient: false));
        Assert.True(HostEngine.KeepAwakeWanted(Link(path: LinkPath.Wifi), At("192.168.1.20"), pcWifiClient: true));
        Assert.True(HostEngine.KeepAwakeWanted(Link(path: LinkPath.Hotspot), At("10.0.0.5"), pcWifiClient: null));
        Assert.False(HostEngine.KeepAwakeWanted(Link(path: LinkPath.UsbTether), At("192.168.42.129"), pcWifiClient: true));
    }

    [Fact]
    public void ThePcAdapterDecidesOnlyForThePhoneItWasFoundFor()
    {
        var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        var at = h.Engine.PhoneAddress()!;
        Assert.True(h.Engine.KeepAwake());
        // The PC's hotspot (not a Wi-Fi card that joins networks): none sent.
        h.Engine.SetPcAdapter(at, new GyroPhone.Host.Perf.PcAdapter("Local Area Connection* 10", Hotspot: true, Wireless: true));
        Assert.False(h.Engine.KeepAwake());
        // Ethernet toward the router.
        h.Engine.SetPcAdapter(at, new GyroPhone.Host.Perf.PcAdapter("Ethernet", Hotspot: false));
        Assert.False(h.Engine.KeepAwake());
        // The PC's Wi-Fi card, also on a router that uses the hotspot's subnet.
        h.Engine.SetPcAdapter(at, new GyroPhone.Host.Perf.PcAdapter("Wi-Fi", Hotspot: true, Wireless: true, Station: true));
        Assert.True(h.Engine.KeepAwake());
        // Found for another address, or not found: as before.
        var other = new IPAddress(at.GetAddressBytes().Select((b, i) => i == 3 ? (byte)(b ^ 1) : b).ToArray());
        h.Engine.SetPcAdapter(other, new GyroPhone.Host.Perf.PcAdapter("Ethernet", Hotspot: false));
        Assert.True(h.Engine.KeepAwake());
        h.Engine.SetPcAdapter(at, null);
        Assert.True(h.Engine.KeepAwake());
    }

    [Fact]
    public void ARestingKeepAwakeWakesWhenThePcTurnsOutToBeAWifiClient()
    {
        var h = new Harness();
        h.Connect(h.NewPhone());
        var at = h.Engine.PhoneAddress()!;
        h.Engine.SetPcAdapter(at, new GyroPhone.Host.Perf.PcAdapter("Ethernet", Hotspot: false));
        var sw = System.Diagnostics.Stopwatch.StartNew();
        var rest = new Thread(h.Engine.RestKeepAwake);
        rest.Start();
        Thread.Sleep(50);
        Assert.True(rest.IsAlive);
        h.Engine.SetPcAdapter(at, new GyroPhone.Host.Perf.PcAdapter("Wi-Fi", Hotspot: false, Wireless: true, Station: true));
        Assert.True(rest.Join(HostEngine.KeepAwakeIdleWaitMs * 3));
        Assert.True(sw.ElapsedMilliseconds < HostEngine.KeepAwakeIdleWaitMs, $"woke after {sw.ElapsedMilliseconds} ms");
    }
}
