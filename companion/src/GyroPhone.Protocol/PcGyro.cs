namespace GyroPhone.Protocol;

/// <summary>
/// The gyro run on the PC in "Use PC controller for gyro ratchet" mode: takes each STATE_GYRO's raw
/// samples (<see cref="PcGyroTail"/>) once, by seq, fills a gap from the running totals, works out
/// per sample which axes are on (the phone's, flipped by the PC controller's ratchets held at that
/// sample's time and by its Toggle gyro), runs <see cref="GyroMath"/> with the phone's settings and
/// puts the result into the frame: mouse and wheel into its wrapping totals, the game pad axes
/// merged into its pad the way the phone merges them. Frames without the tail keep the totals
/// offset, so the cursor never jumps back. One per session; not thread safe.
/// </summary>
public sealed class PcGyro
{
    /// <summary>After this long without a STATE_GYRO, missing samples are not filled in: the host has let go of input meanwhile.</summary>
    private const long StaleUs = Wire.ReleaseTimeoutMs * 1000L;
    /// <summary>A gap longer than this (or with times from another clock) gets a guessed length.</summary>
    private const long MaxGapUs = 1_000_000;
    private const int GuessDtUs = 1000;
    /// <summary>A packet this many samples behind the last one run is not late but from a phone counting again.</summary>
    private const int RestartSeqs = 1024;

    private readonly GyroMath _math = new();
    private readonly float[] _pad = new float[GyroMath.PadAxes];
    private readonly long[] _own = new long[3];
    private readonly long[] _sum = new long[3];
    private readonly long[] _hostUs = new long[PcGyroTail.MaxSamples];
    private bool _hasBase;
    private ushort _lastSeq;
    private uint _lastTime;
    private bool _lastClock;
    private long _lastRxUs;
    private int _adjX, _adjY, _adjW, _adjH;

    /// <summary>The last STATE_GYRO said the gyro may move.</summary>
    public bool MayMove { get; private set; }

    /// <summary>The gyro axes on (bits 0..2) at the last sample run, after the PC controller.</summary>
    public int Effective { get; private set; }

    /// <summary>The newest STATE_GYRO's phone axes (bits 0..2), before the PC controller.</summary>
    public int PhoneAxes { get; private set; }

    /// <summary>Samples run so far, and synthetic ones made for gaps (both wrapping; for the debug view and tests).</summary>
    public int Samples { get; private set; }
    public int GapFills { get; private set; }

    /// <summary>A new session with another phone: nothing carries over.</summary>
    public void Reset()
    {
        Rebase();
        MayMove = false;
        Effective = 0;
        PhoneAxes = 0;
    }

    /// <summary>The same phone in a new session: its seq and totals start again, and so do the STATE totals.</summary>
    public void Rebase()
    {
        _hasBase = false;
        _adjX = _adjY = _adjW = _adjH = 0;
        _math.Reset();
        Array.Clear(_pad);
    }

    /// <summary>
    /// Runs the new samples of <paramref name="f"/> (a STATE, STATE_HIRES or STATE_GYRO received at
    /// host time <paramref name="nowUs"/>) and puts the gyro's output into it. <paramref name="gate"/>
    /// gives the PC controller's toggle (as read now), <paramref name="cfg"/> its ratchet buttons and
    /// <paramref name="heldAt"/> its buttons held at a host time (null: no PC controller is read).
    /// </summary>
    public void Apply(StateFrame f, long nowUs, GyroGate gate, PadGyroConfig cfg, Func<long, uint>? heldAt)
    {
        if (!f.HasPcGyro)
        {
            // The PC no longer runs the gyro: drop its glide and smoothing, so turning the mode on again starts clean.
            _math.Reset();
            Array.Clear(_pad);
            Effective = 0;
            MayMove = false;
            Offset(f);
            return;
        }
        var t = f.Tail;
        MayMove = t.MayMove;
        PhoneAxes = t.Count > 0 ? t.Axes[t.Count - 1] : 0;
        if (!t.MayMove)
        {
            // Nothing moves; the samples are only remembered, so they are not run later.
            _math.Reset();
            Array.Clear(_pad);
            Effective = 0;
            Remember(t, nowUs);
        }
        else
        {
            Run(t, nowUs, gate, cfg, heldAt);
        }
        Offset(f);
        MergePad(f);
        f.Flags = (ushort)(Effective != 0 ? f.Flags | StateFlags.GyroActive : f.Flags & ~StateFlags.GyroActive);
    }

    private void Run(PcGyroTail t, long nowUs, GyroGate gate, PadGyroConfig cfg, Func<long, uint>? heldAt)
    {
        int n = t.Count;
        int ahead = _hasBase ? (short)(t.NewestSeq - _lastSeq) : n;
        if (ahead < -RestartSeqs)
        {
            // Far behind what was run: the phone counts from the start again. Its samples are a new base.
            _hasBase = false;
            ahead = n;
        }
        if (ahead <= 0) return; // a repeat or an older packet: nothing new
        if (_hasBase && nowUs - _lastRxUs > StaleUs)
        {
            // Nothing new for so long that the host let go of input: no glide or smoothing carries over the silence.
            _math.Reset();
            Array.Clear(_pad);
        }
        int first = Math.Max(0, n - ahead);
        int toggle = gate.Toggled ? 7 : 0;
        HostTimes(t, nowUs);

        for (int a = 0; a < 3; a++) _sum[a] = 0;
        for (int i = first; i < n; i++)
        {
            for (int a = 0; a < 3; a++) _sum[a] = unchecked(_sum[a] + (long)t.Rate[i * 3 + a] * t.DtUs[i]);
        }
        if (!_hasBase)
        {
            // New base: every sample here is new, and the totals before them are what the phone sent before.
            for (int a = 0; a < 3; a++) _own[a] = unchecked(t.Totals[a] - _sum[a]);
        }
        else if (ahead > n && nowUs - _lastRxUs <= StaleUs)
        {
            // Samples were lost: one sample carries the angle they turned, over the time they took.
            long y = unchecked(t.Totals[0] - _own[0] - _sum[0]);
            long p = unchecked(t.Totals[1] - _own[1] - _sum[1]);
            long r = unchecked(t.Totals[2] - _own[2] - _sum[2]);
            if (y != 0 || p != 0 || r != 0)
            {
                long gap = GapUs(t, first, ahead - n);
                long at = n > first ? _hostUs[first] - t.DtUs[first] : nowUs;
                int axes = n > first ? t.Axes[first] : PhoneAxes;
                float dtUs = gap;
                Sample(y / dtUs, p / dtUs, r / dtUs, gap / 1e6f, axes, at, toggle, t, cfg, heldAt);
                GapFills = unchecked(GapFills + 1);
            }
        }
        for (int i = first; i < n; i++)
        {
            Sample(t.Rate[i * 3], t.Rate[i * 3 + 1], t.Rate[i * 3 + 2], t.DtUs[i] / 1e6f, t.Axes[i], _hostUs[i], toggle, t, cfg, heldAt);
        }
        Remember(t, nowUs);
    }

    /// <summary>One sample: rates in 1/16 deg/s over <paramref name="dt"/> seconds, the phone's <paramref name="phoneAxes"/>, at host time <paramref name="atUs"/>.</summary>
    private void Sample(float yawQ, float pitchQ, float rollQ, float dt, int phoneAxes, long atUs, int toggle,
        PcGyroTail t, PadGyroConfig cfg, Func<long, uint>? heldAt)
    {
        int flips = heldAt == null ? 0 : GyroGate.FlipsOf(heldAt(atUs), cfg);
        int eff = (phoneAxes ^ flips ^ toggle) & 7;
        Effective = eff;
        Samples = unchecked(Samples + 1);
        if (eff == 0 && !_math.Coasting)
        {
            _math.Reset();
            Array.Clear(_pad);
            return;
        }
        var o = _math.Step(yawQ / PcGyroTail.RateScale, pitchQ / PcGyroTail.RateScale, rollQ / PcGyroTail.RateScale,
            dt, t.Config, eff, t.Tightening, t.Smoothing);
        _adjX = unchecked(_adjX + o.Dx);
        _adjY = unchecked(_adjY + o.Dy);
        _adjW = unchecked(_adjW + o.Wheel);
        _adjH = unchecked(_adjH + o.HWheel);
        o.Pad.CopyTo(_pad, 0);
    }

    /// <summary>Host time of each sample in <paramref name="t"/> into <see cref="_hostUs"/>: its own clock's when the phone synced, else by arrival.</summary>
    private void HostTimes(PcGyroTail t, long nowUs)
    {
        long newest = t.Clock ? nowUs + unchecked((int)(t.NewestTimeUs - (uint)nowUs)) : nowUs;
        long back = 0;
        for (int i = t.Count - 1; i >= 0; i--)
        {
            _hostUs[i] = newest - back;
            back += t.DtUs[i];
        }
    }

    /// <summary>
    /// The µs between the last sample run and the first new one's start (where the lost ones ran), by
    /// the sample times; guessed from the samples' spacing when the times cannot tell (another clock, or out of range).
    /// </summary>
    private long GapUs(PcGyroTail t, int first, int lost)
    {
        if (t.Clock == _lastClock && t.Count > first)
        {
            uint firstStart = TimeOf(t, first) - t.DtUs[first];
            long gap = unchecked((int)(firstStart - _lastTime));
            if (gap > 0 && gap <= MaxGapUs) return gap;
        }
        int spacing = t.Count > first && t.DtUs[first] > 0 ? t.DtUs[first] : GuessDtUs;
        return (long)lost * spacing;
    }

    /// <summary>Sample <paramref name="i"/>'s time in the tail's own clock (wrapping).</summary>
    private static uint TimeOf(PcGyroTail t, int i)
    {
        uint time = t.NewestTimeUs;
        for (int k = t.Count - 1; k > i; k--) time = unchecked(time - t.DtUs[k]);
        return time;
    }

    private void Remember(PcGyroTail t, long nowUs)
    {
        if (_hasBase && (short)(t.NewestSeq - _lastSeq) <= 0) return; // older than what was run: keep the newer base
        _hasBase = true;
        _lastSeq = t.NewestSeq;
        _lastTime = t.NewestTimeUs;
        _lastClock = t.Clock;
        _lastRxUs = nowUs;
        t.Totals.CopyTo(_own, 0);
    }

    private void Offset(StateFrame f)
    {
        f.MouseTotalX = unchecked(f.MouseTotalX + _adjX);
        f.MouseTotalY = unchecked(f.MouseTotalY + _adjY);
        f.WheelTotal = unchecked(f.WheelTotal + _adjW);
        f.HWheelTotal = unchecked(f.HWheelTotal + _adjH);
    }

    /// <summary>The gyro's pad over the frame's (which carries only the rest in this mode), like the phone merges them.</summary>
    private void MergePad(StateFrame f)
    {
        f.Lx = Stick(f.Lx, _pad[0]);
        f.Ly = Stick(f.Ly, _pad[1]);
        f.Rx = Stick(f.Rx, _pad[2]);
        f.Ry = Stick(f.Ry, _pad[3]);
        f.Lt = Trigger(f.Lt, _pad[4]);
        f.Rt = Trigger(f.Rt, _pad[5]);
    }

    // Like the phone: the gyro's stick wins when it is pushed further, a trigger takes the larger pull.
    private static short Stick(short other, float g)
    {
        int v = (int)(Math.Clamp(g, -1f, 1f) * 32767);
        return Math.Abs(v) > Math.Abs((int)other) ? (short)v : other;
    }

    private static ushort Trigger(ushort other, float g)
    {
        int v = (int)(Math.Min(Math.Abs(g), 1f) * 1023);
        return (ushort)Math.Max(v, other);
    }
}
