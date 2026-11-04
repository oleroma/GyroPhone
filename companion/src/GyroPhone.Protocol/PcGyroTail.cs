namespace GyroPhone.Protocol;

/// <summary>
/// The STATE_GYRO tail after STATE_HIRES ("Use PC controller for gyro ratchet" with a host that
/// takes <see cref="WelcomeFlags.PcGyro"/>): the phone's raw gyro samples and its resolved gyro
/// settings, so the PC runs the gyro math (<see cref="PcGyro"/>). Fixed size, little-endian:
/// u8 flags, u8 count, u16 newest seq, u32 newest time (µs), 8 × 9-byte sample slots (u16 dt µs,
/// i16 yaw, pitch, roll in 1/16 deg/s, u8 axes), 3 × i64 running totals, then the 106-byte config.
/// </summary>
public sealed class PcGyroTail
{
    public const int Size = 210;
    public const int MaxSamples = 8;
    public const int MaxRoutes = 12;
    /// <summary>Rates are sent in 1/16 deg/s.</summary>
    public const float RateScale = 16f;

    /// <summary>The gyro may move (input allowed, not measuring, gyro on, samples flowing).</summary>
    public const byte FlagMayMove = 1;
    /// <summary>Sample times are host time (phone time plus the clock sync offset); else phone time.</summary>
    public const byte FlagClock = 2;

    private const int SampleOff = 8, SampleSize = 9, TotalsOff = 80, ConfigOff = 104, RoutesOff = 138, RouteSize = 6;

    public byte Flags;
    /// <summary>Samples in this packet, oldest first (0..8).</summary>
    public int Count;
    /// <summary>The newest sample's seq (wrapping); the one before it is one less.</summary>
    public ushort NewestSeq;
    /// <summary>The newest sample's time, µs (low 32 bits; host clock with <see cref="FlagClock"/>).</summary>
    public uint NewestTimeUs;
    /// <summary>Per sample: µs since the sample before it (0 for the first after a stop).</summary>
    public readonly ushort[] DtUs = new ushort[MaxSamples];
    /// <summary>[sample * 3 + axis]: yaw, pitch, roll in 1/16 deg/s.</summary>
    public readonly short[] Rate = new short[MaxSamples * 3];
    /// <summary>Per sample: the phone's gyro axes on (bits 0..2).</summary>
    public readonly byte[] Axes = new byte[MaxSamples];
    /// <summary>Per axis: running total of rate × dt µs over every sample sent this session (wrapping).</summary>
    public readonly long[] Totals = new long[3];
    /// <summary>The phone's current resolved gyro settings.</summary>
    public readonly GyroMath.Config Config = new();
    /// <summary>Global jitter tightening and smoothing thresholds (deg/s, 0 = off).</summary>
    public float Tightening, Smoothing;

    public bool MayMove => (Flags & FlagMayMove) != 0;
    public bool Clock => (Flags & FlagClock) != 0;

    public void Decode(ReadOnlySpan<byte> b)
    {
        Flags = b[0];
        Count = Math.Min((int)b[1], MaxSamples);
        NewestSeq = Le.U16(b, 2);
        NewestTimeUs = Le.U32(b, 4);
        for (int i = 0; i < MaxSamples; i++)
        {
            int o = SampleOff + i * SampleSize;
            DtUs[i] = Le.U16(b, o);
            for (int a = 0; a < 3; a++) Rate[i * 3 + a] = Le.I16(b, o + 2 + a * 2);
            Axes[i] = (byte)(b[o + 8] & 7);
        }
        for (int a = 0; a < 3; a++) Totals[a] = Le.I64(b, TotalsOff + a * 8);
        var c = Config;
        for (int a = 0; a < 3; a++)
        {
            c.Sensitivity[a] = F32(b, ConfigOff + a * 4);
            c.GlideMs[a] = F32(b, ConfigOff + 12 + a * 4);
        }
        byte glide = b[ConfigOff + 24];
        for (int a = 0; a < 3; a++)
        {
            c.Inertia[a] = (glide & (1 << a)) != 0;
            c.Linear[a] = (glide & (1 << (3 + a))) != 0;
        }
        int r = Math.Min((int)b[ConfigOff + 25], MaxRoutes);
        Tightening = F32(b, ConfigOff + 26);
        Smoothing = F32(b, ConfigOff + 30);
        c.Routes.Clear();
        for (int i = 0; i < r; i++)
        {
            int o = RoutesOff + i * RouteSize;
            int axis = b[o];
            int target = b[o + 1];
            // A route this host cannot follow (a newer phone) is left out, not guessed.
            if (axis > 2 || target >= GyroMath.Route.Pad + GyroMath.PadAxes) continue;
            c.Routes.Add(new GyroMath.Route(axis, target, F32(b, o + 2)));
        }
    }

    /// <summary>Writes <see cref="Size"/> bytes (tests and tools).</summary>
    public void Encode(Span<byte> b)
    {
        b[..Size].Clear();
        b[0] = Flags;
        b[1] = (byte)Math.Clamp(Count, 0, MaxSamples);
        Le.PutU16(b, 2, NewestSeq);
        Le.PutU32(b, 4, NewestTimeUs);
        for (int i = 0; i < Count && i < MaxSamples; i++)
        {
            int o = SampleOff + i * SampleSize;
            Le.PutU16(b, o, DtUs[i]);
            for (int a = 0; a < 3; a++) Le.PutI16(b, o + 2 + a * 2, Rate[i * 3 + a]);
            b[o + 8] = Axes[i];
        }
        for (int a = 0; a < 3; a++) Le.PutI64(b, TotalsOff + a * 8, Totals[a]);
        var c = Config;
        byte glide = 0;
        for (int a = 0; a < 3; a++)
        {
            PutF32(b, ConfigOff + a * 4, c.Sensitivity[a]);
            PutF32(b, ConfigOff + 12 + a * 4, c.GlideMs[a]);
            if (c.Inertia[a]) glide |= (byte)(1 << a);
            if (c.Linear[a]) glide |= (byte)(1 << (3 + a));
        }
        b[ConfigOff + 24] = glide;
        int r = Math.Min(c.Routes.Count, MaxRoutes);
        b[ConfigOff + 25] = (byte)r;
        PutF32(b, ConfigOff + 26, Tightening);
        PutF32(b, ConfigOff + 30, Smoothing);
        for (int i = 0; i < r; i++)
        {
            int o = RoutesOff + i * RouteSize;
            b[o] = (byte)c.Routes[i].Axis;
            b[o + 1] = (byte)c.Routes[i].Target;
            PutF32(b, o + 2, c.Routes[i].Scale);
        }
    }

    public void CopyFrom(PcGyroTail o)
    {
        Flags = o.Flags;
        Count = o.Count;
        NewestSeq = o.NewestSeq;
        NewestTimeUs = o.NewestTimeUs;
        o.DtUs.CopyTo(DtUs, 0);
        o.Rate.CopyTo(Rate, 0);
        o.Axes.CopyTo(Axes, 0);
        o.Totals.CopyTo(Totals, 0);
        Config.CopyFrom(o.Config);
        Tightening = o.Tightening;
        Smoothing = o.Smoothing;
    }

    private static float F32(ReadOnlySpan<byte> b, int off) => BitConverter.Int32BitsToSingle(Le.I32(b, off));

    private static void PutF32(Span<byte> b, int off, float v) => Le.PutI32(b, off, BitConverter.SingleToInt32Bits(v));
}
