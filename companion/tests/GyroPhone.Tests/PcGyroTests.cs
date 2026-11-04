using GyroPhone.Protocol;

namespace GyroPhone.Tests;

/// <summary>"Use PC controller for gyro ratchet" with the gyro run on the PC: STATE_GYRO's raw samples to mouse, wheel and pad.</summary>
public class PcGyroTests
{
    private const int L1 = 4, North = 3;
    private const long T0 = 1_000_000_000;

    /// <summary>The phone's side (PcGyroFeed): seq, ring of the last 8, running totals.</summary>
    private sealed class Phone
    {
        private readonly List<(ushort Dt, short Y, short P, short R, byte Axes)> _all = [];
        public readonly long[] Totals = new long[3];
        public ushort Seq;
        public uint Time = (uint)T0;
        public readonly GyroMath.Config Config = new();

        public Phone() => Config.Routes.Add(new GyroMath.Route(0, GyroMath.Route.MouseX, 1f));

        /// <summary>One sample: yaw in deg/s (×16 on the wire) over dt µs.</summary>
        public void Add(float yaw, ushort dt = 10_000, byte axes = 7, int count = 1)
        {
            for (int k = 0; k < count; k++)
            {
                short q = (short)MathF.Round(yaw * 16f);
                Seq++;
                Time += dt;
                Totals[0] = unchecked(Totals[0] + (long)q * dt);
                _all.Add((dt, q, 0, 0, axes));
            }
        }

        public StateFrame Frame(bool mayMove = true, bool clock = true, int touchX = 0)
        {
            var f = new StateFrame { Hires = true, HasPcGyro = true, MouseTotalX = touchX };
            var t = f.Tail;
            t.Flags = (byte)((mayMove ? PcGyroTail.FlagMayMove : 0) | (clock ? PcGyroTail.FlagClock : 0));
            int n = Math.Min(PcGyroTail.MaxSamples, _all.Count);
            t.Count = n;
            t.NewestSeq = Seq;
            t.NewestTimeUs = Time;
            for (int i = 0; i < n; i++)
            {
                var s = _all[_all.Count - n + i];
                t.DtUs[i] = s.Dt;
                t.Rate[i * 3] = s.Y; t.Rate[i * 3 + 1] = s.P; t.Rate[i * 3 + 2] = s.R;
                t.Axes[i] = s.Axes;
            }
            Totals.CopyTo(t.Totals, 0);
            t.Config.CopyFrom(Config);
            // Through the wire, so the codec is what the PC sees.
            var b = new byte[Wire.StateGyroPlain];
            f.Encode(b);
            t.Encode(b.AsSpan(Wire.StateHiresPlain));
            var g = new StateFrame();
            g.Decode(b);
            return g;
        }
    }

    private readonly PcGyro _gyro = new();
    private readonly GyroGate _gate = new();
    private readonly PadGyroConfig _cfg = Config();
    private Func<long, uint>? _heldAt;

    private static PadGyroConfig Config()
    {
        var c = new PadGyroConfig { Enabled = true };
        c.Kind[L1] = 7; // Ratchet
        c.Kind[North] = PadGyroConfig.KindToggle;
        return c;
    }

    /// <summary>Applies <paramref name="f"/> received at the phone's newest sample time; returns its mouse x total.</summary>
    private int Run(StateFrame f, long? nowUs = null)
    {
        _gyro.Apply(f, nowUs ?? (f.HasPcGyro ? T0 + (f.Tail.NewestTimeUs - (uint)T0) : T0), _gate, _cfg, _heldAt);
        return f.MouseTotalX;
    }

    [Fact]
    public void EachSampleIsRunOnce()
    {
        var p = new Phone();
        p.Add(100f, count: 3); // 1° each: 16 counts
        Assert.Equal(48, Run(p.Frame()));
        // A repeat moves nothing more.
        Assert.Equal(48, Run(p.Frame()));
        p.Add(100f, count: 2);
        Assert.Equal(80, Run(p.Frame()));
        Assert.Equal(5, _gyro.Samples);
        Assert.Equal(0, _gyro.GapFills);
    }

    [Fact]
    public void AnOlderPacketMovesNothing()
    {
        var p = new Phone();
        p.Add(100f, count: 3);
        var old = p.Frame();
        p.Add(100f, count: 2);
        Assert.Equal(80, Run(p.Frame()));
        Assert.Equal(80, Run(old));
        p.Add(100f);
        Assert.Equal(96, Run(p.Frame()));
    }

    [Fact]
    public void AGapIsFilledFromTheTotals()
    {
        var p = new Phone();
        p.Add(100f, count: 3);
        Run(p.Frame());
        // 20 more, of which the packet carries the last 8: 12 lost.
        p.Add(100f, count: 20);
        Assert.Equal(23 * 16, Run(p.Frame()));
        Assert.Equal(1, _gyro.GapFills);
        Assert.Equal(3 + 1 + 8, _gyro.Samples);
    }

    [Fact]
    public void AGapOfUnevenMotionStillAddsUpToItsAngle()
    {
        var p = new Phone();
        p.Add(100f, count: 2);
        Run(p.Frame());
        p.Add(250f, count: 5, dt: 4_000); // 1° each
        p.Add(-50f, count: 7, dt: 20_000); // -1° each
        p.Add(100f, count: 8); // 1° each, in the packet
        // 2 + 5 - 7 + 8 degrees at 16 counts each.
        Assert.Equal(8 * 16, Run(p.Frame()));
    }

    [Fact]
    public void AfterALongSilenceTheGapIsNotFilled()
    {
        var p = new Phone();
        p.Add(100f, count: 3);
        Run(p.Frame());
        p.Add(100f, count: 20);
        // 200 ms worth of samples, but a second passed on the PC: the host let go meanwhile.
        Assert.Equal(48 + 8 * 16, Run(p.Frame(), nowUs: T0 + 2_000_000));
        Assert.Equal(0, _gyro.GapFills);
    }

    [Fact]
    public void RatchetCutsAtTheSampleItWasHeldFor()
    {
        var p = new Phone();
        // Samples at T0 + 10, 20, .. 80 ms; L1 held from 35 to 65 ms takes out 40, 50 and 60.
        _heldAt = us => us >= T0 + 35_000 && us < T0 + 65_000 ? 1u << L1 : 0;
        p.Add(100f, count: 8);
        Assert.Equal(5 * 16, Run(p.Frame()));
        Assert.Equal(7, _gyro.Effective);
    }

    [Fact]
    public void WithoutTheClockSamplesAreTimedByArrival()
    {
        var p = new Phone();
        p.Add(100f, count: 8);
        // Arrival at T0 + 1 s: the samples ran from 930 ms to 1000 ms after T0; held from 955 ms takes out the last 5.
        _heldAt = us => us >= T0 + 955_000 ? 1u << L1 : 0;
        Assert.Equal(3 * 16, Run(p.Frame(clock: false), nowUs: T0 + 1_000_000));
        Assert.Equal(0, _gyro.Effective);
    }

    [Fact]
    public void RatchetEnablesTurnsAxesOn()
    {
        var p = new Phone();
        _heldAt = _ => 1u << L1;
        p.Add(100f, count: 2, axes: 0);
        Assert.Equal(32, Run(p.Frame()));
        _heldAt = _ => 0;
        p.Add(100f, count: 2, axes: 0);
        Assert.Equal(32, Run(p.Frame()));
        Assert.Equal(0, _gyro.Effective);
    }

    [Fact]
    public void ToggleFlipsEveryAxis()
    {
        var p = new Phone();
        var presses = new uint[PadGyroConfig.Places];
        _gate.Update(new PcPadState { Connected = true, Presses = presses }, _cfg);
        presses[North]++;
        _gate.Update(new PcPadState { Connected = true, Presses = presses }, _cfg);
        Assert.True(_gate.Toggled);
        p.Add(100f, count: 3);
        var f = p.Frame();
        Assert.Equal(0, Run(f));
        Assert.Equal(0, f.Flags & StateFlags.GyroActive);
        p.Add(100f, count: 2, axes: 0);
        f = p.Frame();
        Assert.Equal(32, Run(f));
        Assert.NotEqual(0, f.Flags & StateFlags.GyroActive);
    }

    [Fact]
    public void NothingMovesWhileTheGyroMayNot()
    {
        var p = new Phone();
        p.Add(100f, count: 2);
        Assert.Equal(32, Run(p.Frame()));
        p.Add(100f, count: 3);
        var f = p.Frame(mayMove: false);
        Assert.Equal(32, Run(f));
        Assert.False(_gyro.MayMove);
        Assert.Equal(0, f.Flags & StateFlags.GyroActive);
        // Those samples are not run later, nor taken for a gap.
        p.Add(100f, count: 1);
        Assert.Equal(48, Run(p.Frame()));
        Assert.Equal(0, _gyro.GapFills);
    }

    [Fact]
    public void SeqAndTotalsWrap()
    {
        var p = new Phone { Seq = 65530 };
        p.Totals[0] = long.MaxValue - 5_000_000;
        p.Add(100f, count: 3);
        Assert.Equal(48, Run(p.Frame()));
        p.Add(100f, count: 4); // seq past 65535, totals past long.MaxValue
        Assert.Equal(112, Run(p.Frame()));
        p.Add(100f, count: 15); // and a gap across it
        Assert.Equal(22 * 16, Run(p.Frame()));
        Assert.Equal(1, _gyro.GapFills);
        Assert.True(p.Seq < 100);
    }

    [Fact]
    public void FramesWithoutTheTailKeepTheOffset()
    {
        var p = new Phone();
        p.Add(100f, count: 2);
        Assert.Equal(1032, Run(p.Frame(touchX: 1000)));
        var plain = new StateFrame { Hires = true, MouseTotalX = 1010 };
        Assert.Equal(1042, Run(plain));
    }

    [Fact]
    public void RebaseStartsTheSeqAndTotalsAgain()
    {
        var p = new Phone();
        p.Add(100f, count: 5);
        Run(p.Frame());
        _gyro.Rebase();
        var q = new Phone();
        q.Add(100f, count: 2);
        Assert.Equal(32, Run(q.Frame()));
        Assert.Equal(0, _gyro.GapFills);
    }

    [Fact]
    public void APhoneCountingAgainIsANewBase()
    {
        var p = new Phone { Seq = 30000 };
        p.Add(100f, count: 5);
        Assert.Equal(80, Run(p.Frame()));
        var q = new Phone();
        q.Add(100f, count: 2);
        Assert.Equal(112, Run(q.Frame()));
    }

    [Fact]
    public void PadMergesLikeThePhone()
    {
        var p = new Phone();
        p.Config.Routes.Clear();
        p.Config.Routes.Add(new GyroMath.Route(0, GyroMath.Route.Pad + 2, 1f)); // yaw to RX
        p.Config.Routes.Add(new GyroMath.Route(0, GyroMath.Route.Pad + 4, 1f)); // and LT
        p.Add(45f); // half of full deflection
        var f = p.Frame();
        f.Rx = 5000;
        f.Lt = 100;
        Run(f);
        Assert.Equal(16383, f.Rx);
        Assert.Equal(511, f.Lt);
        // The stick pushed further than the gyro wins.
        p.Add(45f);
        f = p.Frame();
        f.Rx = -20000;
        f.Lt = 800;
        Run(f);
        Assert.Equal(-20000, f.Rx);
        Assert.Equal(800, f.Lt);
        // Ratcheted off: the gyro's part goes.
        _heldAt = _ => 1u << L1;
        p.Add(45f);
        f = p.Frame();
        f.Rx = 5000;
        Run(f);
        Assert.Equal(5000, f.Rx);
        Assert.Equal(0, f.Lt);
    }

    [Fact]
    public void GlideRunsOnAfterARatchet()
    {
        var p = new Phone();
        p.Config.Inertia[0] = true;
        p.Add(100f, count: 8);
        int before = Run(p.Frame());
        _heldAt = _ => 1u << L1;
        p.Add(100f, count: 8);
        int after = Run(p.Frame());
        // Off, but gliding on from its speed.
        Assert.True(after > before + 16, $"{before} -> {after}");
        Assert.True(after < before + 8 * 16);
    }

    [Fact]
    public void HeldHistoryAnswersByTime()
    {
        var h = new PadHeldHistory();
        Assert.Equal(0u, h.At(5));
        h.Add(100, 1);
        h.Add(200, 3);
        h.Add(250, 3); // no change
        h.Add(300, 0);
        Assert.Equal(1u, h.At(50)); // before anything kept: the oldest
        Assert.Equal(1u, h.At(100));
        Assert.Equal(1u, h.At(199));
        Assert.Equal(3u, h.At(260));
        Assert.Equal(0u, h.At(300));
        Assert.Equal(0u, h.At(10_000));
        for (int i = 0; i < PadHeldHistory.Capacity * 2; i++) h.Add(1000 + i, (uint)(i & 1) + 1);
        Assert.Equal(2u, h.At(1000 + PadHeldHistory.Capacity * 2 - 1));
        h.Clear();
        Assert.Equal(0u, h.At(5000));
    }
}
