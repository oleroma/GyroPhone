using System.Net;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

public class LinkTestTests
{
    private static byte[] Ctl(int seconds)
    {
        var d = new byte[Wire.LinkTestCtlSize];
        d[0] = Wire.Magic0;
        d[1] = Wire.Magic1;
        d[2] = Wire.Version;
        d[3] = PacketType.LinkTestCtl;
        d[12] = (byte)seconds;
        d[13] = (byte)(seconds >> 8);
        return d;
    }

    private static SocketAddress Loopback => new IPEndPoint(IPAddress.Loopback, 50000).Serialize();

    private static int Moved(Harness h) =>
        h.KeyMouse.Calls.SelectMany(c => c).Where(e => (e.Flags & InjectEvent.MouseMove) != 0).Sum(e => Math.Abs(e.Dx));

    [Fact]
    public void LoopbackStartsItAndThePhoneIsTold()
    {
        using var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Sender.Clear();
        h.Engine.OnDatagram(Ctl(600), Loopback, h.Clock.NowUs);
        Assert.True(h.Engine.LinkTesting);
        Assert.Equal(PacketType.TestMode, phone.TryOpen(h.Sender.Last!, out var tm));
        Assert.Equal(TestModeFlags.LinkTest, tm[0]);

        h.Sender.Clear();
        h.Engine.OnDatagram(Ctl(0), Loopback, h.Clock.NowUs);
        Assert.False(h.Engine.LinkTesting);
        Assert.Equal(PacketType.TestMode, phone.TryOpen(h.Sender.Last!, out tm));
        Assert.Equal(0, tm[0]);
    }

    [Fact]
    public void OnlyThisPcMayStartIt()
    {
        using var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Engine.OnDatagram(Ctl(600), phone.Addr, h.Clock.NowUs);
        Assert.False(h.Engine.LinkTesting);
    }

    [Fact]
    public void EndsWhenTimeIsUp()
    {
        using var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Engine.OnDatagram(Ctl(2), Loopback, h.Clock.NowUs);
        h.Tick(1000);
        Assert.True(h.Engine.LinkTesting);
        h.Tick(1100);
        Assert.False(h.Engine.LinkTesting);
    }

    [Fact]
    public void TestInputIsNeverInjected()
    {
        using var h = new Harness();
        var phone = h.NewPhone();
        h.Connect(phone);
        h.Engine.SetLinkTest(60);
        int before = Moved(h);
        for (int i = 1; i <= 50; i++)
        {
            h.Clock.NowUs += 1000;
            h.Send(phone, phone.State(f => { f.Flags = StateFlags.LinkTest; f.MouseTotalX = i * 3; f.PhoneTimeUs = (uint)(i * 1000); }));
        }
        Assert.Equal(before, Moved(h));
        // Real input after the test moves again.
        h.Engine.SetLinkTest(0);
        h.Clock.NowUs += 1000;
        h.Send(phone, phone.State(f => { f.Flags = 0; f.MouseTotalX = 200; f.PhoneTimeUs = 51000; }));
        Assert.Equal(before + 50, Moved(h));
    }
}
