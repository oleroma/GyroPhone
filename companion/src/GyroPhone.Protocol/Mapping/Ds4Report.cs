namespace GyroPhone.Protocol.Mapping;

/// <summary>
/// The DualShock 4's motion fields: the report timestamp (in 16/3 µs, wrapping), gyro in 1/16 deg/s
/// and accelerometer in 1/8192 g, in the pad's frame (x right, y out of its face, z toward the player).
/// </summary>
public readonly record struct Ds4Motion(ushort Timestamp, short Gx, short Gy, short Gz, short Ax, short Ay, short Az)
{
    /// <summary>Still and flat: no turn, 1 g out of the face.</summary>
    public static readonly Ds4Motion Neutral = new(0, 0, 0, 0, 0, (short)MotionFrame.AccelScale, 0);

    /// <summary>This reading with the gyro at rest (the accelerometer kept), stamped <paramref name="timestamp"/>.</summary>
    public Ds4Motion Resting(ushort timestamp) => this with { Timestamp = timestamp, Gx = 0, Gy = 0, Gz = 0 };

    public bool Turning => Gx != 0 || Gy != 0 || Gz != 0;
}

/// <summary>
/// The full DualShock 4 input report as ViGEm's DS4_REPORT_EX takes it (63 bytes, no report id):
/// sticks, buttons with the D-pad in the low nibble, special buttons, triggers, timestamp, gyro,
/// accelerometer, battery (on cable, full) and the touchpad with no finger on it.
/// </summary>
public static class Ds4Report
{
    public const int Size = 63;

    public static void Build(Span<byte> r, in Ds4Frame f, in Ds4Motion m)
    {
        r[..Size].Clear();
        r[0] = f.Lx;
        r[1] = f.Ly;
        r[2] = f.Rx;
        r[3] = f.Ry;
        // The hat index (N, NE, ... NW, none) is the DS4 D-pad value.
        int hat = f.Hat <= PadMapper.Ds4HatNone ? f.Hat : PadMapper.Ds4HatNone;
        Le.PutU16(r, 4, (ushort)((f.Buttons & 0xFFF0) | hat));
        r[6] = (byte)(f.Special & 0x03);
        r[7] = f.Lt;
        r[8] = f.Rt;
        Le.PutU16(r, 9, m.Timestamp);
        r[11] = 0xFF;
        Le.PutI16(r, 12, m.Gx);
        Le.PutI16(r, 14, m.Gy);
        Le.PutI16(r, 16, m.Gz);
        Le.PutI16(r, 18, m.Ax);
        Le.PutI16(r, 20, m.Ay);
        Le.PutI16(r, 22, m.Az);
        // Cable connected (bit 4), battery full.
        r[29] = 0x1B;
        // One touch packet; every finger slot of it and the two before says "not touching" (bit 7).
        r[32] = 1;
        for (int t = 0; t < 3; t++)
        {
            int o = 33 + t * 9;
            r[o + 1] = 0x80;
            r[o + 5] = 0x80;
        }
    }
}

/// <summary>
/// Turns MOTION packets into the DualShock 4's motion, one reading per new sample, each stamped with
/// its own time so a game adds up the turn exactly. A packet repeats the last few samples: the
/// newest seq says which are new. Not thread safe (the engine lock).
/// </summary>
public sealed class Ds4MotionFeed
{
    /// <summary>The pad's gyro rests when no MOTION came for this long (a lost stop, the phone gone).</summary>
    public const long StaleUs = 60_000;

    private bool _haveSeq;
    private ushort _seq;
    private uint _stamp;
    private int _stampRemUs;
    private long _lastUs = long.MinValue;

    /// <summary>The reading the pad shows now.</summary>
    public Ds4Motion Current { get; private set; } = Ds4Motion.Neutral;

    /// <summary>
    /// Writes the readings <paramref name="f"/> brings, oldest first, into <paramref name="into"/>
    /// (room for <see cref="MotionFrame.MaxSamples"/>) and returns how many; <see cref="Current"/> is
    /// the last. With input not allowed on the phone, one resting reading if the gyro was turning.
    /// </summary>
    public int Take(MotionFrame f, Span<Ds4Motion> into, long nowUs)
    {
        if (!f.MayMove)
        {
            _haveSeq = false;
            return Rest(into, nowUs);
        }
        int fresh;
        if (!_haveSeq) fresh = f.Count;
        else
        {
            int diff = (ushort)(f.NewestSeq - _seq);
            // Nothing new, or an older packet arriving late.
            if (diff == 0 || diff >= 0x8000) return 0;
            fresh = Math.Min(diff, f.Count);
        }
        _haveSeq = true;
        _seq = f.NewestSeq;
        _lastUs = nowUs;
        int n = 0;
        for (int i = f.Count - fresh; i < f.Count; i++)
        {
            Advance(f.DtUs[i]);
            var m = new Ds4Motion((ushort)_stamp,
                f.Gyro[i * 3], f.Gyro[i * 3 + 1], f.Gyro[i * 3 + 2],
                f.Accel[i * 3], f.Accel[i * 3 + 1], f.Accel[i * 3 + 2]);
            into[n++] = m;
            Current = m;
        }
        return n;
    }

    /// <summary>The gyro stops (no MOTION for a while, input paused): one resting reading if it was turning, else none.</summary>
    public int Rest(Span<Ds4Motion> into, long nowUs)
    {
        if (!Current.Turning) return 0;
        long gap = _lastUs == long.MinValue ? 0 : Math.Clamp(nowUs - _lastUs, 0, 1_000_000);
        _lastUs = nowUs;
        Advance((int)gap);
        Current = Current.Resting((ushort)_stamp);
        into[0] = Current;
        return 1;
    }

    /// <summary>No MOTION since <see cref="StaleUs"/>: the gyro should rest.</summary>
    public bool IsStale(long nowUs) => Current.Turning && _lastUs != long.MinValue && nowUs - _lastUs >= StaleUs;

    /// <summary>A new session or pad: still and upright, samples counted afresh.</summary>
    public void Reset()
    {
        _haveSeq = false;
        _lastUs = long.MinValue;
        Current = Ds4Motion.Neutral with { Timestamp = (ushort)_stamp };
    }

    // The DS4 timestamp counts 16/3 µs.
    private void Advance(int us)
    {
        int t = us * 3 + _stampRemUs;
        _stamp += (uint)(t / 16);
        _stampRemUs = t % 16;
    }
}
