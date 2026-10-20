using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Tests;

public class InputDiffTests
{
    private readonly byte[] _injected = new byte[32];
    private byte _mouse;
    private readonly InjectEvent[] _out = new InjectEvent[InputDiff.MaxEvents];
    private int _unknown;

    private InjectEvent[] Diff(StateFrame target, int dx = 0, int dy = 0, int wheel = 0, int hwheel = 0)
    {
        int n = InputDiff.Diff(_injected, target.Keys, ref _mouse, target.MouseButtons, dx, dy, wheel, hwheel, _out, ref _unknown);
        return _out[..n];
    }

    [Fact]
    public void OrderIsModDownsKeyDownsKeyUpsModUpsButtonsMoveWheel()
    {
        var s = new StateFrame();
        s.SetKey(0x04, true);      // A
        s.SetKey(0xE0, true);      // LCtrl
        s.MouseButtons = 1;
        Diff(s);

        var t = new StateFrame();
        t.SetKey(0x05, true);      // B down
        t.SetKey(0xE1, true);      // LShift down; LCtrl up; A up
        t.MouseButtons = 2;        // L up, R down
        var e = Diff(t, 3, -4, 240);

        Assert.Equal(8, e.Length);
        Assert.Equal((0xE1, false), (e[0].Usage, e[0].IsKeyUp));
        Assert.Equal((0x05, false), (e[1].Usage, e[1].IsKeyUp));
        Assert.Equal((0x04, true), (e[2].Usage, e[2].IsKeyUp));
        Assert.Equal((0xE0, true), (e[3].Usage, e[3].IsKeyUp));
        Assert.Equal(InjectEvent.MouseLeftUp, e[4].Flags);
        Assert.Equal(InjectEvent.MouseRightDown, e[5].Flags);
        Assert.Equal((InjectEvent.MouseMove, 3, -4), (e[6].Flags, e[6].Dx, e[6].Dy));
        Assert.Equal(InjectEvent.MouseWheel, e[7].Flags);
        Assert.Equal(240, e[7].MouseData);
    }

    [Fact]
    public void ScancodeAndExtendedFlags()
    {
        var s = new StateFrame();
        s.SetKey(0x52, true); // Up arrow: E0 48
        s.SetKey(0x1A, true); // W: 11
        s.SetKey(0xE4, true); // RCtrl: E0 1D
        var e = Diff(s);
        var up = e.Single(x => x.Usage == 0x52);
        Assert.Equal((ushort)0x48, up.Scan);
        Assert.Equal(InjectEvent.KeyScancode | InjectEvent.KeyExtended, up.Flags);
        var w = e.Single(x => x.Usage == 0x1A);
        Assert.Equal((ushort)0x11, w.Scan);
        Assert.Equal(InjectEvent.KeyScancode, w.Flags);
        var rctrl = e.Single(x => x.Usage == 0xE4);
        Assert.Equal((ushort)0x1D, rctrl.Scan);
        Assert.Equal(InjectEvent.KeyScancode | InjectEvent.KeyExtended, rctrl.Flags);

        var released = Diff(new StateFrame());
        Assert.All(released, x => Assert.True(x.IsKeyUp));
        Assert.Equal(InjectEvent.KeyScancode | InjectEvent.KeyExtended | InjectEvent.KeyUpFlag, released.Single(x => x.Usage == 0x52).Flags);
    }

    [Fact]
    public void NumLockIsExtendedAndPauseUsesVk()
    {
        var s = new StateFrame();
        s.SetKey(0x53, true);
        s.SetKey(0x48, true);
        var e = Diff(s);
        var numLock = e.Single(x => x.Usage == 0x53);
        Assert.Equal((ushort)0x45, numLock.Scan);
        Assert.NotEqual(0u, numLock.Flags & InjectEvent.KeyExtended);
        var pause = e.Single(x => x.Usage == 0x48);
        Assert.Equal(InjectEvent.VkPause, pause.Vk);
        Assert.Equal(0u, pause.Flags);
    }

    [Fact]
    public void HiresWheelsPassFractions()
    {
        var e = Diff(new StateFrame(), wheel: 17, hwheel: -5);
        Assert.Equal((InjectEvent.MouseWheel, 17), (e[0].Flags, e[0].MouseData));
        Assert.Equal((InjectEvent.MouseHWheel, -5), (e[1].Flags, e[1].MouseData));
    }

    [Fact]
    public void XButtonsAndWheel()
    {
        var s = new StateFrame { MouseButtons = 0b11000 };
        var e = Diff(s, wheel: -360);
        Assert.Equal((InjectEvent.MouseXDown, InjectEvent.XButton1), (e[0].Flags, e[0].MouseData));
        Assert.Equal((InjectEvent.MouseXDown, InjectEvent.XButton2), (e[1].Flags, e[1].MouseData));
        Assert.Equal(-360, e[2].MouseData);
        var r = Diff(new StateFrame());
        Assert.Equal((InjectEvent.MouseXUp, InjectEvent.XButton1), (r[0].Flags, r[0].MouseData));
        Assert.Equal((InjectEvent.MouseXUp, InjectEvent.XButton2), (r[1].Flags, r[1].MouseData));
    }

    [Fact]
    public void ReservedAndUnknownUsagesAreIgnoredAndCountedOnce()
    {
        var s = new StateFrame();
        s.Keys[0] = 0x0F;          // usages 0..3
        s.SetKey(0x66, true);      // Power: no scan code
        Assert.Empty(Diff(s));
        Assert.Equal(1, _unknown);
        Assert.Empty(Diff(s));
        Assert.Equal(1, _unknown);
        Assert.Empty(Diff(new StateFrame()));
    }

    [Fact]
    public void NoEventsWhenNothingChanged()
    {
        var s = new StateFrame();
        s.SetKey(0x04, true);
        Assert.Single(Diff(s));
        Assert.Empty(Diff(s));
    }

    [Fact]
    public void ReleaseAllOrderKeysThenModifiersThenButtons()
    {
        var s = new StateFrame { MouseButtons = 0b101 };
        s.SetKey(0xE2, true);
        s.SetKey(0x2C, true);
        Diff(s);
        var buf = new InjectEvent[300];
        int n = InputDiff.ReleaseAll(_injected, ref _mouse, buf);
        var e = buf[..n];
        Assert.Equal(4, n);
        Assert.Equal(0x2C, e[0].Usage);
        Assert.Equal(0xE2, e[1].Usage);
        Assert.Equal(InjectEvent.MouseLeftUp, e[2].Flags);
        Assert.Equal(InjectEvent.MouseMiddleUp, e[3].Flags);
        Assert.False(InputDiff.AnyHeld(_injected, _mouse));
    }

    [Fact]
    public void OverflowCarriesToNextCall()
    {
        var s = new StateFrame();
        for (int u = 0x04; u <= 0x73; u++) s.SetKey(u, true); // ~110 keys, more than fit
        s.MouseButtons = 1;
        var first = Diff(s, 1, 1, 120);
        Assert.True(first.Length <= InputDiff.MaxEvents);
        Assert.Contains(first, x => x.Flags == InjectEvent.MouseLeftDown);
        Assert.Contains(first, x => x.Flags == InjectEvent.MouseMove);
        var second = Diff(s);
        Assert.NotEmpty(second);
        Assert.Empty(Diff(s));
    }
}
