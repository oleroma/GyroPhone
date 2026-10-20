namespace GyroPhone.Protocol;

/// <summary>
/// The 66-byte STATE plaintext (SPEC 1.7) as a reusable mutable frame. Pad, keys and mouse buttons
/// are absolute; mouse and wheel are cumulative wrapping totals.
/// </summary>
public sealed class StateFrame
{
    public const int KeyBitmapSize = 32;
    /// <summary>Bitmap byte holding usages 0xE0..0xE7, equal to the HID modifier byte.</summary>
    public const int ModifierByte = 28;
    public const byte HatCenter = 8;

    public uint PhoneTimeUs;
    public ushort Flags;
    public ushort Buttons;
    public byte Hat = HatCenter;
    public byte MouseButtons;
    public short Lx, Ly, Rx, Ry;
    public ushort Lt, Rt;
    public readonly byte[] Keys = new byte[KeyBitmapSize];
    public int MouseTotalX, MouseTotalY, WheelTotal;

    public byte Modifiers
    {
        get => Keys[ModifierByte];
        set => Keys[ModifierByte] = value;
    }

    public bool IsKeyDown(int usage) => usage is >= 0 and < 256 && (Keys[usage >> 3] & (1 << (usage & 7))) != 0;

    public void SetKey(int usage, bool down)
    {
        if (usage is < 0 or > 255) return;
        if (down) Keys[usage >> 3] |= (byte)(1 << (usage & 7));
        else Keys[usage >> 3] &= (byte)~(1 << (usage & 7));
    }

    /// <summary>Neutral pad, nothing held. Totals are left alone.</summary>
    public void ClearInputs()
    {
        Buttons = 0;
        Hat = HatCenter;
        MouseButtons = 0;
        Lx = Ly = Rx = Ry = 0;
        Lt = Rt = 0;
        Array.Clear(Keys);
    }

    public void CopyFrom(StateFrame o)
    {
        PhoneTimeUs = o.PhoneTimeUs;
        Flags = o.Flags;
        Buttons = o.Buttons;
        Hat = o.Hat;
        MouseButtons = o.MouseButtons;
        Lx = o.Lx; Ly = o.Ly; Rx = o.Rx; Ry = o.Ry;
        Lt = o.Lt; Rt = o.Rt;
        o.Keys.CopyTo(Keys, 0);
        MouseTotalX = o.MouseTotalX; MouseTotalY = o.MouseTotalY; WheelTotal = o.WheelTotal;
    }

    public int Encode(Span<byte> b)
    {
        Le.PutU32(b, 0, PhoneTimeUs);
        Le.PutU16(b, 4, Flags);
        Le.PutU16(b, 6, Buttons);
        b[8] = Hat;
        b[9] = MouseButtons;
        Le.PutI16(b, 10, Lx);
        Le.PutI16(b, 12, Ly);
        Le.PutI16(b, 14, Rx);
        Le.PutI16(b, 16, Ry);
        Le.PutU16(b, 18, Lt);
        Le.PutU16(b, 20, Rt);
        Keys.CopyTo(b[22..]);
        Le.PutI32(b, 54, MouseTotalX);
        Le.PutI32(b, 58, MouseTotalY);
        Le.PutI32(b, 62, WheelTotal);
        return Wire.StatePlain;
    }

    /// <summary>Reads a plaintext of exactly <see cref="Wire.StatePlain"/> bytes. Values are taken as sent; clamping is the mapper's job.</summary>
    public void Decode(ReadOnlySpan<byte> b)
    {
        PhoneTimeUs = Le.U32(b, 0);
        Flags = Le.U16(b, 4);
        Buttons = Le.U16(b, 6);
        Hat = b[8];
        MouseButtons = b[9];
        Lx = Le.I16(b, 10);
        Ly = Le.I16(b, 12);
        Rx = Le.I16(b, 14);
        Ry = Le.I16(b, 16);
        Lt = Le.U16(b, 18);
        Rt = Le.U16(b, 20);
        b.Slice(22, KeyBitmapSize).CopyTo(Keys);
        MouseTotalX = Le.I32(b, 54);
        MouseTotalY = Le.I32(b, 58);
        WheelTotal = Le.I32(b, 62);
    }
}
