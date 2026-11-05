using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>"Use PC controller for gyro ratchet": the wire parts, and reading the controller plugged into the PC.</summary>
public class GyroGateTests
{
    private const int L1 = 4, North = 3;

    private readonly uint[] _presses = new uint[PadGyroConfig.Places];
    private uint _held;

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

    [Fact]
    public void SizesAndRoundTrip()
    {
        Assert.Equal(285, PcGyroTail.Size);
        Assert.Equal(355, Wire.StateGyroPlain);
        Assert.Equal(383, PacketType.Size(PacketType.StateGyro));
        Assert.True(PacketType.Size(PacketType.StateGyro) <= Wire.MaxDatagram);
        Assert.Equal(16, WelcomeFlags.PcGyro);
        Assert.Equal(40, Wire.PadGyroPlain);

        var t = new PcGyroTail { Flags = PcGyroTail.FlagMayMove | PcGyroTail.FlagClock, Count = 2, NewestSeq = 65535, NewestTimeUs = 0xFEDCBA98 };
        t.DtUs[0] = 1000; t.DtUs[1] = 50000;
        t.Rate[0] = -32768; t.Rate[4] = 32767; t.Rate[5] = -5;
        t.Axes[0] = 7; t.Axes[1] = 4;
        t.Totals[0] = long.MinValue; t.Totals[1] = -1; t.Totals[2] = 123456789012;
        t.Config.Sensitivity[1] = 20.5f; t.Config.GlideMs[2] = 500f;
        t.Config.Inertia[0] = true; t.Config.Linear[2] = true;
        t.Config.Routes.Add(new GyroMath.Route(2, GyroMath.Route.MouseX, -1f));
        t.Config.Routes.Add(new GyroMath.Route(1, GyroMath.Route.Pad + 3, 0.5f));
        t.Tightening = 2.5f; t.Smoothing = 8f;
        var b = new byte[PcGyroTail.Size];
        t.Encode(b);
        // Layout: flags, n, seq, time, then sample 1 at 8 + 9, totals at 80, config at 104, routes at 138.
        Assert.Equal(3, b[0]);
        Assert.Equal(2, b[1]);
        Assert.Equal(0x98, b[4]);
        Assert.Equal(0x50, b[8 + 9]); // 50000 = 0xC350
        Assert.Equal(4, b[8 + 9 + 8]);
        Assert.Equal(0x80, b[87]); // long.MinValue's top byte
        Assert.Equal(1 | 32, b[128]);
        Assert.Equal(2, b[129]);
        Assert.Equal(2, b[138]);
        Assert.Equal(GyroMath.Route.Pad + 3, b[145]);

        var d = new PcGyroTail();
        d.Decode(b);
        Assert.True(d.MayMove);
        Assert.True(d.Clock);
        Assert.Equal(2, d.Count);
        Assert.Equal(65535, d.NewestSeq);
        Assert.Equal(0xFEDCBA98u, d.NewestTimeUs);
        Assert.Equal(t.DtUs, d.DtUs);
        Assert.Equal(t.Rate, d.Rate);
        Assert.Equal(t.Axes, d.Axes);
        Assert.Equal(t.Totals, d.Totals);
        Assert.Equal(t.Config.Sensitivity, d.Config.Sensitivity);
        Assert.Equal(t.Config.GlideMs, d.Config.GlideMs);
        Assert.Equal(t.Config.Inertia, d.Config.Inertia);
        Assert.Equal(t.Config.Linear, d.Config.Linear);
        Assert.Equal(t.Config.Routes, d.Config.Routes);
        Assert.Equal(2.5f, d.Tightening);
        Assert.Equal(8f, d.Smoothing);

        // In a STATE_GYRO, after the 70 STATE_HIRES bytes.
        var f = new StateFrame { Hires = true, MouseTotalX = 5 };
        var plain = new byte[Wire.StateGyroPlain];
        f.Encode(plain);
        b.CopyTo(plain, Wire.StateHiresPlain);
        var g = new StateFrame();
        g.Decode(plain);
        Assert.True(g.HasPcGyro);
        Assert.Equal(5, g.MouseTotalX);
        Assert.Equal(65535, g.Tail.NewestSeq);
        var h = new StateFrame();
        h.Decode(plain.AsSpan(0, Wire.StateHiresPlain));
        Assert.False(h.HasPcGyro);

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
    public void RoutesTheHostCannotFollowAreLeftOut()
    {
        var t = new PcGyroTail();
        t.Config.Routes.Add(new GyroMath.Route(3, 0, 1f));
        t.Config.Routes.Add(new GyroMath.Route(0, GyroMath.Route.Pad + 6, 1f));
        t.Config.Routes.Add(new GyroMath.Route(0, GyroMath.Route.Wheel, 1f));
        var b = new byte[PcGyroTail.Size];
        t.Encode(b);
        var d = new PcGyroTail();
        d.Decode(b);
        Assert.Equal([new GyroMath.Route(0, GyroMath.Route.Wheel, 1f)], d.Config.Routes);
    }

    [Fact]
    public void HeldRatchetFlipsEveryAxis()
    {
        var g = new GyroGate();
        var c = Config();
        g.Update(Pad, c);
        Assert.Equal(7, g.EffectiveOf(7, true));
        Press(L1);
        g.Update(Pad, c);
        Assert.Equal(7, g.Flips);
        Assert.Equal(0, g.EffectiveOf(7, true));
        Assert.Equal(7, g.EffectiveOf(0, true));
        Assert.Equal(1, g.RatchetPresses);
        Release(L1);
        g.Update(Pad, c);
        Assert.Equal(0, g.Flips);
        Assert.Equal(0, g.EffectiveOf(7, false));
    }

    [Fact]
    public void FlipsOfFollowsTheConfig()
    {
        var c = Config();
        c.Kind[0] = 4; // south ratchets roll
        Assert.Equal(7, GyroGate.FlipsOf(1u << L1, c));
        Assert.Equal(3, GyroGate.FlipsOf((1u << L1) | 1u, c));
        Assert.Equal(0, GyroGate.FlipsOf(1u << North, c));
        c.Enabled = false;
        Assert.Equal(0, GyroGate.FlipsOf(1u << L1, c));
    }

    [Fact]
    public void ToggleFlipsUntilTheEpochChanges()
    {
        var g = new GyroGate();
        var c = Config();
        g.Update(Pad, c);
        Press(North);
        Release(North);
        g.Update(Pad, c);
        Assert.True(g.Toggled);
        Assert.Equal(0, g.EffectiveOf(7, true));
        c.Epoch++;
        g.Update(Pad, c);
        Assert.False(g.Toggled);
    }

    [Fact]
    public void ClearPcEndsTheToggle()
    {
        var g = new GyroGate();
        var c = Config();
        g.Update(Pad, c);
        Press(North);
        Release(North);
        g.Update(Pad, c);
        Assert.True(g.Toggled);
        g.ClearPc();
        Assert.False(g.Toggled);
        Assert.Equal(7, g.EffectiveOf(7, true));
        // The first read after clearing counts no press.
        g.Update(Pad, c);
        Assert.False(g.Toggled);
    }
}
