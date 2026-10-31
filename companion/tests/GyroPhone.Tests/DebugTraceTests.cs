using GyroPhone.Host;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

public class DebugTraceTests
{
    private long _now = 1_000_000_000;
    private DebugTrace NewTrace() => new(() => _now, capacity: 100);
    private static List<string> Texts(DebugTrace t) => t.Latest(1000).Select(e => e.Text).ToList();

    [Fact]
    public void LogsOnlyWhileTheWindowIsOpenOrThePhoneAskedRecently()
    {
        var t = NewTrace();
        Assert.False(t.Enabled);
        t.PhoneAsked(_now);
        Assert.True(t.Enabled);
        _now += 6_000_000;
        Assert.False(t.Enabled);
        t.SetWindowOpen(true);
        Assert.True(t.Enabled);
        t.SetWindowOpen(false);
        Assert.False(t.Enabled);
    }

    [Fact]
    public void InjectedKeysButtonsAndSummedMotion()
    {
        var t = NewTrace();
        var w = HidScanCodes.For(0x1A);
        t.OnInject([InjectEvent.Key(0x1A, w, up: false), InjectEvent.MouseButton(0, up: false)], _now);
        t.OnInject([new InjectEvent { Kind = InjectKind.Mouse, Flags = InjectEvent.MouseMove, Dx = 4, Dy = -2 }], _now);
        _now += 120_000;
        t.OnInject([new InjectEvent { Kind = InjectKind.Mouse, Flags = InjectEvent.MouseMove, Dx = 1, Dy = 0 }], _now);
        Assert.Equal(["key W down", "mouse left down", "mouse move 5,-2 in 120 ms"], Texts(t));
    }

    [Fact]
    public void PadButtonEdgesAndThrottledSticks()
    {
        var t = NewTrace();
        t.OnX360(new X360Frame(XusbButtons.A, 16384, 0, 0, 0, 0, 0), _now);
        _now += 10_000;
        t.OnX360(new X360Frame(0, 20000, 0, 0, 0, 0, 0), _now); // stick too soon after the last
        Assert.Equal(["xbox A down", "pad LX 50%", "xbox A up"], Texts(t));
        Assert.StartsWith("xbox no buttons · L 61,0%", t.CurrentPad);
        Assert.Equal(["xbox A down", "xbox A up"], t.Latest(10, k => k != DebugTrace.Kind.Move).Select(e => e.Text));
    }

    [Fact]
    public void SaveKeepsTheLast30sInThePhoneFormat()
    {
        var t = NewTrace();
        t.Add(DebugTrace.Kind.Note, "old", _now);
        _now += 31_000_000;
        t.Add(DebugTrace.Kind.Out, "new", _now);
        var lines = t.Save(["header"]).Split('\n', StringSplitOptions.RemoveEmptyEntries);
        Assert.Equal("# GyroPhone debug recording, side pc", lines[0]);
        Assert.Equal("# header", lines[2]);
        Assert.StartsWith("# clock-pair: wall_ms=", lines[3]);
        Assert.Single(lines.Skip(4));
        Assert.EndsWith("\tpc\tout\tnew", lines[4]);
    }

    [Fact]
    public void LinkInfoDebugBitsDoNotOverlapTheOthers()
    {
        Assert.Equal(0, LinkInfoFlags.DebugTrace & (LinkInfoFlags.LowLatencyLockHeld | LinkInfoFlags.Usb | LinkInfoFlags.UsbDirect));
        Assert.Equal(0, LinkInfoFlags.DebugSaveMask & (LinkInfoFlags.DebugTrace | LinkInfoFlags.UsbDirect));
    }
}
