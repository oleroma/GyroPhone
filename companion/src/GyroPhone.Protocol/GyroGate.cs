namespace GyroPhone.Protocol;

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
/// Reads a controller plugged into the host for "Use PC controller for gyro ratchet": which gyro
/// axes its held ratchets flip, its Toggle gyro presses and its ratchet presses. Per axis, the
/// phone's ratchets and the PC's flip it (an odd number of flips turns it over), and a Toggle gyro
/// press flips every axis, like on the phone. <see cref="PcGyro"/> runs the gyro with it; the
/// overlays use <see cref="EffectiveOf"/>.
/// </summary>
public sealed class GyroGate
{
    private readonly uint[] _seenPresses = new uint[PadGyroConfig.Places];
    private bool _seenValid;
    private int _epoch = -1;

    /// <summary>The PC controller's Toggle gyro presses flip every axis (odd count).</summary>
    public bool Toggled { get; private set; }

    /// <summary>Axes the PC controller's held ratchets flip now (bits 0..2).</summary>
    public int Flips { get; private set; }

    /// <summary>PC ratchet presses so far (wrapping), for the overlays' "On ratchet".</summary>
    public int RatchetPresses { get; private set; }

    /// <summary>A new session with another phone: the toggle is off and the epoch unknown.</summary>
    public void Reset()
    {
        ClearPc();
        _epoch = -1;
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
        }
        _seenValid = true;
        Flips = FlipsOf(pad.Held, cfg);
    }

    /// <summary>The axes the ratchets in <paramref name="held"/> (place bits) flip, by <paramref name="cfg"/>.</summary>
    public static int FlipsOf(uint held, PadGyroConfig cfg)
    {
        if (!cfg.Enabled || held == 0) return 0;
        int flips = 0;
        for (int i = 0; i < PadGyroConfig.Places; i++)
        {
            if ((held & (1u << i)) != 0) flips ^= cfg.Kind[i] & 7;
        }
        return flips;
    }

    /// <summary>The axes on for phone axes <paramref name="phoneAxes"/> after the PC controller.</summary>
    public int EffectiveOf(int phoneAxes, bool mayMove) => mayMove ? (phoneAxes ^ Flips ^ (Toggled ? 7 : 0)) & 7 : 0;
}
