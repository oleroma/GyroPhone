using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

public class HidInputReportsTests
{
    private readonly HidInputReports _r = new();
    private readonly byte[] _buf = new byte[HidInputReports.MaxBytes];
    private readonly byte[] _injected = new byte[StateFrame.KeyBitmapSize];
    private byte _mouse;
    private readonly InjectEvent[] _events = new InjectEvent[InputDiff.MaxEvents];
    private int _unknown;

    private List<byte[]> Reports(ReadOnlySpan<InjectEvent> events)
    {
        int n = _r.Build(events, _buf);
        var list = new List<byte[]>();
        for (int at = 0; at < n;)
        {
            int size = _buf[at] switch
            {
                HidInputReports.MouseId => HidInputReports.MouseSize,
                HidInputReports.KeyboardId => HidInputReports.KeyboardSize,
                HidInputReports.MediaId => HidInputReports.MediaSize,
                _ => throw new InvalidOperationException($"bad id {_buf[at]}"),
            };
            list.Add(_buf[at..(at + size)]);
            at += size;
        }
        return list;
    }

    private List<byte[]> Diff(StateFrame target, int dx = 0, int dy = 0, int wheel = 0, int hwheel = 0)
    {
        int n = InputDiff.Diff(_injected, target.Keys, ref _mouse, target.MouseButtons, dx, dy, wheel, hwheel, _events, ref _unknown);
        return Reports(_events.AsSpan(0, n));
    }

    private static bool KeyBit(byte[] report, int usage) => (report[1 + (usage >> 3)] & (1 << (usage & 7))) != 0;
    private static short S16(byte[] r, int at) => (short)(r[at] | (r[at + 1] << 8));

    [Fact]
    public void MoveIsOneMouseReport()
    {
        var r = Diff(new StateFrame(), 5, -7);
        Assert.Single(r);
        Assert.Equal(HidInputReports.MouseId, r[0][0]);
        Assert.Equal(0, r[0][1]);
        Assert.Equal(5, S16(r[0], 2));
        Assert.Equal(-7, S16(r[0], 4));
    }

    [Fact]
    public void ClickWithMoveAndWheelsSharesOneReport()
    {
        var s = new StateFrame { MouseButtons = 1 };
        var r = Diff(s, 3, 4, 30, -240);
        Assert.Single(r);
        Assert.Equal(1, r[0][1]);
        Assert.Equal(3, S16(r[0], 2));
        Assert.Equal(4, S16(r[0], 4));
        Assert.Equal(30, S16(r[0], 6));
        Assert.Equal(-240, S16(r[0], 8));
    }

    [Fact]
    public void NothingChangedWritesNothing()
    {
        Assert.Empty(Diff(new StateFrame()));
    }

    [Fact]
    public void HeldStateCarriesOverAndReleases()
    {
        var s = new StateFrame { MouseButtons = 2 };
        s.SetKey(0x04, true);
        Diff(s);
        var t = new StateFrame { MouseButtons = 2 };
        t.SetKey(0x04, true);
        t.SetKey(0x05, true);
        var r = Diff(t, 1, 0);
        Assert.Equal(2, r.Count);
        Assert.True(KeyBit(r[0], 0x04));
        Assert.True(KeyBit(r[0], 0x05));
        Assert.Equal(2, r[1][1]); // right button still held in the move's report

        int n = InputDiff.ReleaseAll(_injected, ref _mouse, _events);
        var rel = Reports(_events.AsSpan(0, n));
        Assert.Equal(2, rel.Count);
        Assert.All(rel[0][1..], b => Assert.Equal(0, b));
        Assert.Equal(0, rel[1][1]);
        Assert.False(_r.AnyHeld);
    }

    [Fact]
    public void ModifierGoesDownBeforeAndUpAfterTheKey()
    {
        var s = new StateFrame();
        s.SetKey(0xE1, true); // LShift
        s.SetKey(0x04, true); // A
        var r = Diff(s);
        Assert.Equal(2, r.Count);
        Assert.True(KeyBit(r[0], 0xE1));
        Assert.False(KeyBit(r[0], 0x04));
        Assert.True(KeyBit(r[1], 0xE1));
        Assert.True(KeyBit(r[1], 0x04));

        r = Diff(new StateFrame());
        Assert.Equal(2, r.Count);
        Assert.False(KeyBit(r[0], 0x04));
        Assert.True(KeyBit(r[0], 0xE1));
        Assert.False(KeyBit(r[1], 0xE1));
    }

    [Fact]
    public void KeyChangingTwiceGetsTwoReports()
    {
        var down = InjectEvent.Key(0x04, HidScanCodes.For(0x04), up: false);
        var up = InjectEvent.Key(0x04, HidScanCodes.For(0x04), up: true);
        var r = Reports([down, up]);
        Assert.Equal(2, r.Count);
        Assert.True(KeyBit(r[0], 0x04));
        Assert.False(KeyBit(r[1], 0x04));
    }

    [Fact]
    public void MoveBeforeClickStaysBeforeIt()
    {
        var r = Reports([InjectEvent.Move(2, 0), InjectEvent.MouseButton(0, up: false)]);
        Assert.Equal(2, r.Count);
        Assert.Equal((0, 2), ((int)r[0][1], (int)S16(r[0], 2)));
        Assert.Equal((1, 0), ((int)r[1][1], (int)S16(r[1], 2)));
    }

    [Fact]
    public void MediaKeysGoToTheirOwnReport()
    {
        var s = new StateFrame();
        s.SetKey(0x80, true); // volume up
        s.SetKey(0xE8, true); // play/pause
        var r = Diff(s);
        Assert.Single(r);
        Assert.Equal(HidInputReports.MediaId, r[0][0]);
        Assert.Equal(0b1_0001, r[0][1]);
    }

    [Fact]
    public void BackAndForwardButtons()
    {
        var s = new StateFrame { MouseButtons = 0b1_1000 };
        var r = Diff(s);
        Assert.Single(r);
        Assert.Equal(0b1_1000, r[0][1]);
    }

    [Fact]
    public void ResetForgetsHeldKeys()
    {
        var s = new StateFrame();
        s.SetKey(0x04, true);
        Diff(s);
        Assert.True(_r.AnyHeld);
        _r.Reset();
        Assert.False(_r.AnyHeld);
    }

    [Fact]
    public void ReportsKeepTheEventsOrderAcrossDevices()
    {
        var r = Reports([
            InjectEvent.MouseButton(0, up: false),
            InjectEvent.Key(0x04, HidScanCodes.For(0x04), up: false),
            InjectEvent.MouseButton(0, up: true),
        ]);
        Assert.Equal(3, r.Count);
        Assert.Equal(HidInputReports.MouseId, r[0][0]);
        Assert.Equal(HidInputReports.KeyboardId, r[1][0]);
        Assert.Equal(HidInputReports.MouseId, r[2][0]);
        Assert.Equal(0, r[2][1]);
    }
}
