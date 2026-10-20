namespace GyroPhone.Protocol.Mapping;

/// <summary>Xbox 360 report as ViGEm takes it: XUSB button word, signed sticks (+Y = up), byte triggers.</summary>
public readonly record struct X360Frame(ushort Buttons, short Lx, short Ly, short Rx, short Ry, byte Lt, byte Rt)
{
    public static readonly X360Frame Neutral = default;
}

/// <summary>
/// DualShock 4 report in ViGEm terms. Buttons use <see cref="Ds4Buttons"/>; Special uses
/// <see cref="Ds4Special"/>; Hat indexes the 9-entry table in <see cref="PadMapper.Ds4HatNames"/>.
/// Sticks are bytes with 128 = centre and no Y flip.
/// </summary>
public readonly record struct Ds4Frame(ushort Buttons, byte Special, byte Hat, byte Lx, byte Ly, byte Rx, byte Ry, byte Lt, byte Rt)
{
    public static readonly Ds4Frame Neutral = new(0, 0, PadMapper.Ds4HatNone, 128, 128, 128, 128, 0, 0);
}

/// <summary>XUSB button bits.</summary>
public static class XusbButtons
{
    public const ushort DpadUp = 0x0001;
    public const ushort DpadDown = 0x0002;
    public const ushort DpadLeft = 0x0004;
    public const ushort DpadRight = 0x0008;
    public const ushort Start = 0x0010;
    public const ushort Back = 0x0020;
    public const ushort LeftThumb = 0x0040;
    public const ushort RightThumb = 0x0080;
    public const ushort LeftShoulder = 0x0100;
    public const ushort RightShoulder = 0x0200;
    public const ushort Guide = 0x0400;
    public const ushort A = 0x1000;
    public const ushort B = 0x2000;
    public const ushort X = 0x4000;
    public const ushort Y = 0x8000;
}

/// <summary>DS4 button bits, matching the ViGEm DualShock4Button values.</summary>
public static class Ds4Buttons
{
    public const ushort ThumbRight = 1 << 15;
    public const ushort ThumbLeft = 1 << 14;
    public const ushort Options = 1 << 13;
    public const ushort Share = 1 << 12;
    public const ushort TriggerRight = 1 << 11;
    public const ushort TriggerLeft = 1 << 10;
    public const ushort ShoulderRight = 1 << 9;
    public const ushort ShoulderLeft = 1 << 8;
    public const ushort Triangle = 1 << 7;
    public const ushort Circle = 1 << 6;
    public const ushort Cross = 1 << 5;
    public const ushort Square = 1 << 4;
}

/// <summary>DS4 special button bits, matching the ViGEm DualShock4SpecialButton values.</summary>
public static class Ds4Special
{
    public const byte Ps = 1 << 0;
    public const byte Touchpad = 1 << 1;
}

/// <summary>Pure STATE to pad mapping (SPEC 3.3).</summary>
public static class PadMapper
{
    public const byte Ds4HatNone = 8;

    /// <summary>Index order of the DS4 hat table; the Windows sink maps each to a DualShock4DPadDirection.</summary>
    public static readonly string[] Ds4HatNames =
        ["North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest", "None"];

    // Phone GamepadButton bit -> XUSB. Bits 11..15 (M1..M4, SHARE) have no X360 button.
    private static readonly ushort[] X360ButtonMap =
    [
        XusbButtons.A, XusbButtons.B, XusbButtons.X, XusbButtons.Y,
        XusbButtons.LeftShoulder, XusbButtons.RightShoulder, XusbButtons.Back, XusbButtons.Start,
        XusbButtons.LeftThumb, XusbButtons.RightThumb, XusbButtons.Guide,
    ];

    /// <summary>Phone buttons X360 cannot show (M1..M4, SHARE); the host logs these once.</summary>
    public const ushort X360DroppedButtons = 0xF800;

    // Hat 0..7 (UP, UP_RIGHT, ... UP_LEFT), 8 = centre.
    private static readonly ushort[] X360HatMap =
    [
        XusbButtons.DpadUp,
        XusbButtons.DpadUp | XusbButtons.DpadRight,
        XusbButtons.DpadRight,
        XusbButtons.DpadDown | XusbButtons.DpadRight,
        XusbButtons.DpadDown,
        XusbButtons.DpadDown | XusbButtons.DpadLeft,
        XusbButtons.DpadLeft,
        XusbButtons.DpadUp | XusbButtons.DpadLeft,
        0,
    ];

    // Phone bit -> DS4 button (A..RS); GUIDE and SHARE go to the special byte.
    private static readonly ushort[] Ds4ButtonMap =
    [
        Ds4Buttons.Cross, Ds4Buttons.Circle, Ds4Buttons.Square, Ds4Buttons.Triangle,
        Ds4Buttons.ShoulderLeft, Ds4Buttons.ShoulderRight, Ds4Buttons.Share, Ds4Buttons.Options,
        Ds4Buttons.ThumbLeft, Ds4Buttons.ThumbRight,
    ];

    private const int GuideBit = 10;
    private const int ShareBit = 15;

    public static X360Frame ToX360(StateFrame s)
    {
        ushort b = 0;
        for (int i = 0; i < X360ButtonMap.Length; i++)
            if ((s.Buttons & (1 << i)) != 0) b |= X360ButtonMap[i];
        b |= X360HatMap[HatOf(s)];
        return new X360Frame(b,
            Stick(s.Lx), Negate(Stick(s.Ly)), Stick(s.Rx), Negate(Stick(s.Ry)),
            TriggerByte(s.Lt), TriggerByte(s.Rt));
    }

    public static Ds4Frame ToDs4(StateFrame s)
    {
        ushort b = 0;
        for (int i = 0; i < Ds4ButtonMap.Length; i++)
            if ((s.Buttons & (1 << i)) != 0) b |= Ds4ButtonMap[i];
        byte special = 0;
        if ((s.Buttons & (1 << GuideBit)) != 0) special |= Ds4Special.Ps;
        if ((s.Buttons & (1 << ShareBit)) != 0) special |= Ds4Special.Touchpad;
        byte lt = TriggerByte(s.Lt), rt = TriggerByte(s.Rt);
        if (lt >= 1) b |= Ds4Buttons.TriggerLeft;
        if (rt >= 1) b |= Ds4Buttons.TriggerRight;
        return new Ds4Frame(b, special, HatOf(s),
            Ds4Axis(s.Lx), Ds4Axis(s.Ly), Ds4Axis(s.Rx), Ds4Axis(s.Ry), lt, rt);
    }

    /// <summary>Hat values above 8 are treated as centre.</summary>
    public static byte HatOf(StateFrame s) => s.Hat > StateFrame.HatCenter ? StateFrame.HatCenter : s.Hat;

    /// <summary>-32768 is mapped to -32767 so the axis is symmetric and negation cannot overflow.</summary>
    public static short Stick(short v) => v == short.MinValue ? (short)-32767 : v;

    private static short Negate(short v) => (short)-v;

    /// <summary>0..1023 to 0..255 with rounding: 0 -> 0, 512 -> 128, 1023 -> 255.</summary>
    public static byte TriggerByte(ushort v) => (byte)((Math.Min((int)v, 1023) * 255 + 511) / 1023);

    /// <summary>Signed stick to DS4 byte: -32767 -> 0, 0 -> 128, 32767 -> 255.</summary>
    public static byte Ds4Axis(short v) => (byte)((Math.Clamp((int)v, -32767, 32767) + 32768) >> 8);
}
