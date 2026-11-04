namespace GyroPhone.Protocol;

/// <summary>
/// The STATE_GYRO tail after STATE_HIRES: what the phone's gyro moved and each gyro axis's share,
/// so a controller plugged into the host can ratchet the gyro ("Use PC controller for gyro ratchet").
/// Mouse and wheel shares are cumulative wrapping i16 totals; pad shares are absolute.
/// </summary>
public sealed class GyroShares
{
    /// <summary>u8 flags, u8 axes, i16 × 4 gyro totals, 3 × 28 bytes per axis, i16 × 6 pad without the gyro.</summary>
    public const int Size = 10 + 3 * AxisSize + 12;
    private const int AxisSize = 28;
    public const byte FlagMayMove = 1;

    public byte Flags;
    /// <summary>The gyro axes on at the phone (bit 0 yaw, 1 pitch, 2 roll).</summary>
    public byte Axes;
    /// <summary>The gyro's own motion totals: x, y, wheel, horizontal wheel (1/120 notch).</summary>
    public readonly short[] Gyro = new short[4];
    /// <summary>[axis * 4 + k]: what each axis would move with it on, totals like <see cref="Gyro"/>.</summary>
    public readonly short[] On = new short[12];
    /// <summary>[axis * 4 + k]: what each axis's glide moves while it is off, totals like <see cref="Gyro"/>.</summary>
    public readonly short[] Glide = new short[12];
    /// <summary>[axis * 6 + k]: each axis's game pad share with it on (lx, ly, rx, ry, lt, rt; ±32767 = full).</summary>
    public readonly short[] Pad = new short[18];
    /// <summary>The pad without the gyro: lx, ly, rx, ry (±32767), lt, rt (0..1023).</summary>
    public readonly short[] NonGyro = new short[6];

    /// <summary>The gyro may move now (on, "Gyro off" not set, input allowed): a PC ratchet may turn axes on.</summary>
    public bool MayMove => (Flags & FlagMayMove) != 0;

    public void Decode(ReadOnlySpan<byte> b)
    {
        Flags = b[0];
        Axes = (byte)(b[1] & 7);
        for (int k = 0; k < 4; k++) Gyro[k] = Le.I16(b, 2 + k * 2);
        int o = 10;
        for (int a = 0; a < 3; a++)
        {
            for (int k = 0; k < 4; k++) On[a * 4 + k] = Le.I16(b, o + k * 2);
            for (int k = 0; k < 4; k++) Glide[a * 4 + k] = Le.I16(b, o + 8 + k * 2);
            for (int k = 0; k < 6; k++) Pad[a * 6 + k] = Le.I16(b, o + 16 + k * 2);
            o += AxisSize;
        }
        for (int k = 0; k < 6; k++) NonGyro[k] = Le.I16(b, o + k * 2);
    }

    /// <summary>Writes <see cref="Size"/> bytes (tests and tools).</summary>
    public void Encode(Span<byte> b)
    {
        b[0] = Flags;
        b[1] = Axes;
        for (int k = 0; k < 4; k++) Le.PutI16(b, 2 + k * 2, Gyro[k]);
        int o = 10;
        for (int a = 0; a < 3; a++)
        {
            for (int k = 0; k < 4; k++) Le.PutI16(b, o + k * 2, On[a * 4 + k]);
            for (int k = 0; k < 4; k++) Le.PutI16(b, o + 8 + k * 2, Glide[a * 4 + k]);
            for (int k = 0; k < 6; k++) Le.PutI16(b, o + 16 + k * 2, Pad[a * 6 + k]);
            o += AxisSize;
        }
        for (int k = 0; k < 6; k++) Le.PutI16(b, o + k * 2, NonGyro[k]);
    }

    public void CopyFrom(GyroShares o)
    {
        Flags = o.Flags;
        Axes = o.Axes;
        o.Gyro.CopyTo(Gyro, 0);
        o.On.CopyTo(On, 0);
        o.Glide.CopyTo(Glide, 0);
        o.Pad.CopyTo(Pad, 0);
        o.NonGyro.CopyTo(NonGyro, 0);
    }
}

/// <summary>
/// PAD_GYRO: the phone's controller buttons that ratchet or toggle the gyro, by place
/// (<see cref="OverlayFrame"/>'s Pad bits: bit index 0 = south .. 17 = D-pad right).
/// </summary>
public sealed class PadGyroConfig
{
    public const int MaxButtons = 18;
    public const int Places = 18;
    public const byte FlagEnabled = 1;
    /// <summary>A kind bit: the button toggles the gyro (the low 3 bits are the axes its ratchets flip).</summary>
    public const byte KindToggle = 8;

    /// <summary>"Use PC controller for gyro ratchet" is on at the phone.</summary>
    public bool Enabled;
    /// <summary>Changes when the phone's own Toggle gyro resets (workspace or its gyro changed).</summary>
    public byte Epoch;
    /// <summary>By place: the axes its ratchets flip (bits 0..2) and <see cref="KindToggle"/>.</summary>
    public readonly byte[] Kind = new byte[Places];

    /// <summary>The places that do something, as a mask of place bits.</summary>
    public uint Used
    {
        get
        {
            uint m = 0;
            for (int i = 0; i < Places; i++) if (Kind[i] != 0) m |= 1u << i;
            return m;
        }
    }

    public void Decode(ReadOnlySpan<byte> b)
    {
        Enabled = (b[0] & FlagEnabled) != 0;
        Epoch = b[1];
        int n = Math.Min((int)b[2], MaxButtons);
        Array.Clear(Kind);
        for (int i = 0; i < n; i++)
        {
            int place = b[4 + i * 2];
            if (place < Places) Kind[place] = (byte)(b[5 + i * 2] & 0x0F);
        }
    }

    public int Encode(Span<byte> b)
    {
        b[..Wire.PadGyroPlain].Clear();
        b[0] = Enabled ? FlagEnabled : (byte)0;
        b[1] = Epoch;
        int n = 0;
        for (int i = 0; i < Places && n < MaxButtons; i++)
        {
            b[4 + n * 2] = (byte)(Kind[i] == 0 ? 0xFF : i);
            if (Kind[i] == 0) continue;
            b[5 + n * 2] = Kind[i];
            n++;
        }
        for (int i = n; i < MaxButtons; i++) { b[4 + i * 2] = 0xFF; b[5 + i * 2] = 0xFF; }
        b[2] = (byte)n;
        return Wire.PadGyroPlain;
    }
}

/// <summary>
/// A controller plugged into the host, read for the gyro: buttons held by place and how many
/// times each was pressed (wrapping), so no short press between two STATEs is lost.
/// </summary>
public struct PcPadState
{
    public bool Connected;
    public uint Held;
    /// <summary>Press counts by place (wrapping): a change is a press.</summary>
    public uint[]? Presses;
}

/// <summary>
/// Ratchets the phone's gyro from a controller plugged into the host. Each STATE_GYRO is rewritten
/// in place: the gyro axes the PC controller turns off are taken out of the mouse, wheel and pad,
/// and the ones it turns on are put in. Per axis, the phone's ratchets and the PC's flip it (an odd
/// number of flips turns it over), and a Toggle gyro press flips every axis, like on the phone.
/// Without a PC ratchet or toggle the frame is left exactly as sent.
/// </summary>
public sealed class GyroGate
{
    private const long RebaseGapUs = Wire.ReleaseTimeoutMs * 1000L;

    private readonly GyroShares _last = new();
    private bool _hasBase;
    private long _lastUs;
    private int _adjX, _adjY, _adjW, _adjH;
    private readonly uint[] _seenPresses = new uint[PadGyroConfig.Places];
    private bool _seenValid;
    private int _epoch = -1;

    /// <summary>The PC controller's Toggle gyro presses flip every axis (odd count).</summary>
    public bool Toggled { get; private set; }

    /// <summary>Axes the PC controller's held ratchets flip now (bits 0..2).</summary>
    public int Flips { get; private set; }

    /// <summary>PC ratchet presses so far (wrapping), for the overlays' "On ratchet".</summary>
    public int RatchetPresses { get; private set; }

    /// <summary>The last STATE_GYRO said the gyro may move.</summary>
    public bool MayMove { get; private set; }

    /// <summary>The gyro axes on after the PC controller, as of the last frame.</summary>
    public int Effective { get; private set; }

    /// <summary>A new session: totals start from zero, the toggle is off.</summary>
    public void Reset()
    {
        Rebase();
        ClearPc();
        MayMove = false;
        Effective = 0;
        _epoch = -1;
    }

    /// <summary>The same phone in a new session: its totals start from zero, the PC toggle stays.</summary>
    public void Rebase()
    {
        _hasBase = false;
        _adjX = _adjY = _adjW = _adjH = 0;
    }

    /// <summary>The PC controller is no longer read: its toggle and held ratchets flip nothing.</summary>
    public void ClearPc()
    {
        _seenValid = false;
        Toggled = false;
        Flips = 0;
    }

    /// <summary>
    /// Reads the PC controller against <paramref name="cfg"/>: held ratchets' flips, Toggle gyro
    /// presses since the last call, ratchet presses. Call once per STATE and per OVERLAY is fine.
    /// </summary>
    public void Update(in PcPadState pad, PadGyroConfig cfg)
    {
        if (cfg.Epoch != _epoch)
        {
            _epoch = cfg.Epoch;
            Toggled = false;
        }
        if (!cfg.Enabled || !pad.Connected || pad.Presses == null)
        {
            Flips = 0;
            _seenValid = false;
            return;
        }
        int flips = 0;
        for (int i = 0; i < PadGyroConfig.Places; i++)
        {
            byte kind = cfg.Kind[i];
            uint presses = pad.Presses[i];
            if (_seenValid && kind != 0)
            {
                uint n = unchecked(presses - _seenPresses[i]);
                if ((kind & PadGyroConfig.KindToggle) != 0 && (n & 1) != 0) Toggled = !Toggled;
                if ((kind & 7) != 0) RatchetPresses = unchecked(RatchetPresses + (int)n);
            }
            _seenPresses[i] = presses;
            if ((pad.Held & (1u << i)) != 0) flips ^= kind & 7;
        }
        _seenValid = true;
        Flips = flips;
    }

    /// <summary>The axes on for phone axes <paramref name="phoneAxes"/> after the PC controller.</summary>
    public int EffectiveOf(int phoneAxes, bool mayMove) => mayMove ? (phoneAxes ^ Flips ^ (Toggled ? 7 : 0)) & 7 : 0;

    /// <summary>Rewrites <paramref name="f"/> (a STATE_GYRO) for the PC controller as of the last <see cref="Update"/>.</summary>
    public void Apply(StateFrame f, long nowUs)
    {
        if (!f.HasShares)
        {
            _hasBase = false;
            Offset(f);
            return;
        }
        var s = f.Shares;
        int phone = s.Axes;
        MayMove = s.MayMove;
        int eff = EffectiveOf(phone, s.MayMove);
        Effective = eff;
        if (_hasBase && nowUs - _lastUs <= RebaseGapUs && eff != phone)
        {
            for (int k = 0; k < 4; k++)
            {
                int d = -Delta(s.Gyro[k], _last.Gyro[k]);
                for (int a = 0; a < 3; a++)
                {
                    int bit = 1 << a;
                    if ((eff & bit) != 0) d += Delta(s.On[a * 4 + k], _last.On[a * 4 + k]);
                    else if ((phone & bit) == 0) d += Delta(s.Glide[a * 4 + k], _last.Glide[a * 4 + k]);
                }
                switch (k)
                {
                    case 0: _adjX = unchecked(_adjX + d); break;
                    case 1: _adjY = unchecked(_adjY + d); break;
                    case 2: _adjW = unchecked(_adjW + d); break;
                    default: _adjH = unchecked(_adjH + d); break;
                }
            }
        }
        _last.CopyFrom(s);
        _hasBase = true;
        _lastUs = nowUs;
        Offset(f);
        if (eff != phone) Pad(f, s, eff);
        f.Flags = (ushort)(eff != 0 ? f.Flags | StateFlags.GyroActive : f.Flags & ~StateFlags.GyroActive);
    }

    private void Offset(StateFrame f)
    {
        f.MouseTotalX = unchecked(f.MouseTotalX + _adjX);
        f.MouseTotalY = unchecked(f.MouseTotalY + _adjY);
        f.WheelTotal = unchecked(f.WheelTotal + _adjW);
        f.HWheelTotal = unchecked(f.HWheelTotal + _adjH);
    }

    /// <summary>The pad as the phone makes it, with the gyro's part from the axes in <paramref name="eff"/>.</summary>
    private static void Pad(StateFrame f, GyroShares s, int eff)
    {
        Span<float> g = stackalloc float[6];
        for (int a = 0; a < 3; a++)
        {
            if ((eff & (1 << a)) == 0) continue;
            for (int k = 0; k < 6; k++) g[k] += s.Pad[a * 6 + k] / 32767f;
        }
        f.Lx = Stick(s.NonGyro[0], g[0]);
        f.Ly = Stick(s.NonGyro[1], g[1]);
        f.Rx = Stick(s.NonGyro[2], g[2]);
        f.Ry = Stick(s.NonGyro[3], g[3]);
        f.Lt = Trigger(s.NonGyro[4], g[4]);
        f.Rt = Trigger(s.NonGyro[5], g[5]);
    }

    // Like the phone: the gyro's stick wins when it is pushed further, a trigger takes the larger pull.
    private static short Stick(short nonGyro, float g)
    {
        int v = (int)(Math.Clamp(g, -1f, 1f) * 32767);
        return Math.Abs(v) > Math.Abs((int)nonGyro) ? (short)v : nonGyro;
    }

    private static ushort Trigger(short nonGyro, float g)
    {
        int v = (int)(Math.Min(Math.Abs(g), 1f) * 1023);
        return (ushort)Math.Max(v, Math.Max((int)nonGyro, 0));
    }

    private static int Delta(short now, short before) => unchecked((short)(now - before));
}
