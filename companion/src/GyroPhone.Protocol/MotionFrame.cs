namespace GyroPhone.Protocol;

/// <summary>
/// MOTION plaintext (phone to PC, only to a host whose WELCOME has <see cref="WelcomeFlags.Motion"/>
/// while HOST_STATUS says its pad is a plugged DualShock 4): the phone's raw motion for the pad's gyro
/// and accelerometer, sent as each gyro sample is read. Fixed size, little-endian: u8 flags
/// (<see cref="FlagMayMove"/>), u8 count (0..8), u16 newest sample's seq (wrapping), u32 newest
/// sample's phone time (µs, low 32 bits), then 8 sample slots, oldest first, each u16 µs since the
/// sample before it (0 for the first after a stop), i16 × 3 gyro in 1/16 deg/s and i16 × 3
/// accelerometer in 1/8192 g. The phone has already mapped them into the DualShock 4's own frame
/// and units (x right, y out of its face, z toward the player; turns by the right-hand rule; +1 g
/// on y lying still and flat) by the workspace's pad axes, so the PC copies them into the report.
/// Each packet repeats the last few samples, so a lost one loses none.
/// </summary>
public sealed class MotionFrame
{
    public const int MaxSamples = 8;
    public const int Size = HeaderSize + MaxSamples * SampleSize;
    public const int HeaderSize = 8;
    public const int SampleSize = 14;
    /// <summary>Gyro units per deg/s.</summary>
    public const float GyroScale = 16f;
    /// <summary>Accelerometer units per g.</summary>
    public const float AccelScale = 8192f;

    /// <summary>Input is allowed on the phone: the samples are live. Off, the pad's gyro rests.</summary>
    public const byte FlagMayMove = 1;

    public byte Flags;
    public int Count;
    public ushort NewestSeq;
    public uint NewestTimeUs;
    public readonly ushort[] DtUs = new ushort[MaxSamples];
    /// <summary>[sample * 3 + axis]: the pad's x, y, z.</summary>
    public readonly short[] Gyro = new short[MaxSamples * 3];
    public readonly short[] Accel = new short[MaxSamples * 3];

    public bool MayMove => (Flags & FlagMayMove) != 0;

    public void Decode(ReadOnlySpan<byte> b)
    {
        Flags = b[0];
        Count = Math.Min((int)b[1], MaxSamples);
        NewestSeq = Le.U16(b, 2);
        NewestTimeUs = Le.U32(b, 4);
        for (int i = 0; i < MaxSamples; i++)
        {
            int o = HeaderSize + i * SampleSize;
            DtUs[i] = Le.U16(b, o);
            for (int a = 0; a < 3; a++)
            {
                Gyro[i * 3 + a] = Le.I16(b, o + 2 + a * 2);
                Accel[i * 3 + a] = Le.I16(b, o + 8 + a * 2);
            }
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
            int o = HeaderSize + i * SampleSize;
            Le.PutU16(b, o, DtUs[i]);
            for (int a = 0; a < 3; a++)
            {
                Le.PutI16(b, o + 2 + a * 2, Gyro[i * 3 + a]);
                Le.PutI16(b, o + 8 + a * 2, Accel[i * 3 + a]);
            }
        }
    }
}
