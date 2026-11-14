using System.Net;
using GyroPhone.Protocol;

namespace GyroPhone.Tests;

public class LogCtlTests
{
    private static byte[] Ctl(byte what, byte value)
    {
        var d = new byte[Wire.LogCtlSize];
        d[0] = Wire.Magic0;
        d[1] = Wire.Magic1;
        d[2] = Wire.Version;
        d[3] = PacketType.LogCtl;
        d[12] = what;
        d[13] = value;
        return d;
    }

    private static SocketAddress Loopback => new IPEndPoint(IPAddress.Loopback, 50000).Serialize();
    private static SocketAddress Lan => new IPEndPoint(IPAddress.Parse("192.168.1.20"), 50000).Serialize();

    [Fact]
    public void LoopbackSwitchesDiagnosticsAndSetsTheMinutes()
    {
        using var h = new Harness();
        h.Engine.OnDatagram(Ctl(Wire.LogCtlDiagnostics, 1), Loopback, h.Clock.NowUs);
        Assert.True(h.Engine.Settings.Diagnostics);
        Assert.True(h.Engine.Debug.Enabled);
        h.Engine.OnDatagram(Ctl(Wire.LogCtlKeep, 12), Loopback, h.Clock.NowUs);
        Assert.Equal(12, h.Engine.Settings.DiagnosticsMinutes);
        h.Engine.OnDatagram(Ctl(Wire.LogCtlKeep, 99), Loopback, h.Clock.NowUs);
        Assert.Equal(30, h.Engine.Settings.DiagnosticsMinutes);
        int saves = 0;
        h.Engine.DebugSaveRequested = () => saves++;
        h.Engine.OnDatagram(Ctl(Wire.LogCtlSave, 0), Loopback, h.Clock.NowUs);
        Assert.Equal(1, saves);
        h.Engine.OnDatagram(Ctl(Wire.LogCtlDiagnostics, 0), Loopback, h.Clock.NowUs);
        Assert.False(h.Engine.Settings.Diagnostics);
        Assert.False(h.Engine.Debug.Enabled);
    }

    [Fact]
    public void FromAnotherMachineItIsDropped()
    {
        using var h = new Harness();
        h.Engine.OnDatagram(Ctl(Wire.LogCtlDiagnostics, 1), Lan, h.Clock.NowUs);
        Assert.False(h.Engine.Settings.Diagnostics);
    }
}
