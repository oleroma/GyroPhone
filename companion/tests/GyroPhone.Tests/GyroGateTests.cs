using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>"Use PC controller for gyro": STATE_GYRO rewritten for a controller plugged into the PC.</summary>
public class GyroGateTests
{
    private const int L1 = 4, North = 3;
    private const byte Yaw = 1, Pitch = 2, Roll = 4;

    private readonly uint[] _presses = new uint[PadGyroConfig.Places];
    private uint _held;
    private long _now;

    private static PadGyroConfig Config()
    {
        var c = new PadGyroConfig { Enabled = true };
        c.Kind[L1] = 7; // Ratchet
        c.Kind[North] = PadGyroConfig.KindToggle;
        return c;
    }

    private void Press(int place)
    {
        _held |= 1u << place;
        _presses[place]++;
    }

    private void Release(int place) => _held &= ~(1u << place);

    private PcPadState Pad => new() { Connected = true, Held = _held, Presses = _presses };

    /// <summary>A frame where roll moved the cursor <paramref name="totalX"/> so far (gyro on: phone axes all), plus 1000 from the touchpad.</summary>
    private static StateFrame Frame(short gyroX, short rollOnX, byte axes = 7, short rollGlideX = 0)
    {
        var f = new StateFrame { Hires = true, HasShares = true, MouseTotalX = 1000 + gyroX };
        f.Shares.Flags = GyroShares.FlagMayMove;
        f.Shares.Axes = axes;
        f.Shares.Gyro[0] = gyroX;
        f.Shares.On[2 * 4] = rollOnX;
        f.Shares.Glide[2 * 4] = rollGlideX;
        return f;
    }

    private int Run(GyroGate g, PadGyroConfig c, StateFrame f)
    {
        _now += 1000;
        g.Update(Pad, c);
        g.Apply(f, _now);
        return f.MouseTotalX;
    }

    [Fact]
    public void SizesAndRoundTrip()
    {
        Assert.Equal(176, Wire.StateGyroPlain);
        Assert.Equal(204, PacketType.Size(PacketType.StateGyro));
        Assert.True(PacketType.Size(PacketType.StateGyro) <= Wire.MaxDatagram);
        Assert.Equal(40, Wire.PadGyroPlain);
        var s = new GyroShares { Flags = 1, Axes = 5 };
        s.Gyro[1] = -7; s.On[11] = 300; s.Glide[4] = -2; s.Pad[17] = 32767; s.NonGyro[5] = 1023;
        var b = new byte[GyroShares.Size];
        s.Encode(b);
        var d = new GyroShares();
        d.Decode(b);
        Assert.Equal(5, d.Axes);
        Assert.Equal(-7, d.Gyro[1]);
        Assert.Equal(300, d.On[11]);
        Assert.Equal(-2, d.Glide[4]);
        Assert.Equal(32767, d.Pad[17]);
        Assert.Equal(1023, d.NonGyro[5]);
        var c = Config();
        var cb = new byte[Wire.PadGyroPlain];
        c.Encode(cb);
        var c2 = new PadGyroConfig();
        c2.Decode(cb);
        Assert.True(c2.Enabled);
        Assert.Equal(7, c2.Kind[L1]);
        Assert.Equal(PadGyroConfig.KindToggle, c2.Kind[North]);
    }

    [Fact]
    public void UntouchedWithoutAPcRatchet()
    {
        var g = new GyroGate();
        var c = Config();
        Assert.Equal(1000, Run(g, c, Frame(0, 0)));
        Assert.Equal(1100, Run(g, c, Frame(100, 101)));
        Assert.Equal(1250, Run(g, c, Frame(250, 249)));
    }

    [Fact]
    public void HeldRatchetTakesTheGyroOut()
    {
        var g = new GyroGate();
        var c = Config();
        Run(g, c, Frame(0, 0));
        Run(g, c, Frame(100, 100));
        Press(L1);
        // The gyro moved 100 more: the cursor stays where it was.
        Assert.Equal(1100, Run(g, c, Frame(200, 200)));
        Assert.Equal(1100, Run(g, c, Frame(300, 300)));
        Assert.Equal(0, g.Effective);
        Release(L1);
        Assert.Equal(1150, Run(g, c, Frame(350, 350)));
    }

    [Fact]
    public void RatchetEnablesPutsTheGyroIn()
    {
        // "Ratchet enables": the phone has every axis off and sends only what they would move.
        var g = new GyroGate();
        var c = Config();
        Run(g, c, Frame(0, 0, axes: 0));
        Assert.Equal(1000, Run(g, c, Frame(0, 50, axes: 0)));
        Press(L1);
        Assert.Equal(1080, Run(g, c, Frame(0, 130, axes: 0)));
        Assert.Equal(7, g.Effective);
        Release(L1);
        Assert.Equal(1080, Run(g, c, Frame(0, 200, axes: 0)));
    }

    [Fact]
    public void ToggleFlipsUntilTheEpochChanges()
    {
        var g = new GyroGate();
        var c = Config();
        Run(g, c, Frame(0, 0));
        Press(North);
        Release(North);
        Assert.Equal(1000, Run(g, c, Frame(100, 100)));
        Assert.True(g.Toggled);
        c.Epoch++;
        Assert.Equal(1050, Run(g, c, Frame(150, 150)));
        Assert.False(g.Toggled);
    }

    [Fact]
    public void PhoneGlideStaysWhenThePcLeavesTheAxisOff()
    {
        // The phone ratcheted roll (it glides); the PC ratchet flips it back on.
        var g = new GyroGate();
        var c = Config();
        c.Kind[L1] = Roll;
        Run(g, c, Frame(0, 0, axes: Yaw | Pitch));
        Press(L1);
        // Roll on through the PC: its "on" total counts, not the glide the phone put in.
        Assert.Equal(1060, Run(g, c, Frame(20, 60, axes: Yaw | Pitch, rollGlideX: 20)));
    }

    [Fact]
    public void StickShareFollowsTheEffectiveAxes()
    {
        var g = new GyroGate();
        var c = Config();
        var f = Frame(0, 0);
        f.Rx = 20000;
        f.Shares.Pad[2 * 6 + 2] = 20000; // roll pushes RX
        f.Shares.NonGyro[2] = 5000;
        Run(g, c, f);
        Assert.Equal(20000, f.Rx);
        Press(L1);
        var f2 = Frame(0, 0);
        f2.Rx = 20000;
        f2.Shares.Pad[2 * 6 + 2] = 20000;
        f2.Shares.NonGyro[2] = 5000;
        Run(g, c, f2);
        Assert.Equal(5000, f2.Rx);
    }

    [Fact]
    public void NothingMovesWhileTheGyroMayNot()
    {
        var g = new GyroGate();
        var c = Config();
        var f = Frame(0, 0, axes: 0);
        f.Shares.Flags = 0;
        Run(g, c, f);
        Press(North);
        var f2 = Frame(0, 100, axes: 0);
        f2.Shares.Flags = 0;
        Assert.Equal(1000, Run(g, c, f2));
    }
}
