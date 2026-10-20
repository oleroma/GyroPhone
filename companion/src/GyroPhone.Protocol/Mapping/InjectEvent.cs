namespace GyroPhone.Protocol.Mapping;

public enum InjectKind : byte
{
    Key,
    Mouse,
}

/// <summary>
/// One keyboard or mouse event, already in SendInput terms so the Windows sink only copies fields.
/// Flag values are the Win32 KEYEVENTF_* / MOUSEEVENTF_* constants.
/// </summary>
public struct InjectEvent
{
    public const uint KeyExtended = 0x0001;    // KEYEVENTF_EXTENDEDKEY
    public const uint KeyUpFlag = 0x0002;      // KEYEVENTF_KEYUP
    public const uint KeyScancode = 0x0008;    // KEYEVENTF_SCANCODE

    public const uint MouseMove = 0x0001;
    public const uint MouseLeftDown = 0x0002;
    public const uint MouseLeftUp = 0x0004;
    public const uint MouseRightDown = 0x0008;
    public const uint MouseRightUp = 0x0010;
    public const uint MouseMiddleDown = 0x0020;
    public const uint MouseMiddleUp = 0x0040;
    public const uint MouseXDown = 0x0080;
    public const uint MouseXUp = 0x0100;
    public const uint MouseWheel = 0x0800;
    public const int XButton1 = 1;
    public const int XButton2 = 2;
    public const int WheelDelta = 120;

    public const ushort VkPause = 0x13;

    public InjectKind Kind;
    /// <summary>HID usage of a key event (diagnostics and the held-keys marker).</summary>
    public byte Usage;
    public ushort Scan;
    public ushort Vk;
    /// <summary>KEYEVENTF_* for keys, MOUSEEVENTF_* for mouse.</summary>
    public uint Flags;
    public int Dx;
    public int Dy;
    /// <summary>mouseData: XBUTTON1/2 or wheel amount.</summary>
    public int MouseData;

    public readonly bool IsKeyUp => Kind == InjectKind.Key && (Flags & KeyUpFlag) != 0;

    public static InjectEvent Key(byte usage, in ScanCode sc, bool up)
    {
        var e = new InjectEvent { Kind = InjectKind.Key, Usage = usage };
        if (sc.Vk != 0)
        {
            e.Vk = sc.Vk;
        }
        else
        {
            e.Scan = sc.Scan;
            e.Flags = KeyScancode | (sc.Extended ? KeyExtended : 0);
        }
        if (up) e.Flags |= KeyUpFlag;
        return e;
    }

    /// <summary>Button index is the STATE mouseButtons bit: 0 L, 1 R, 2 M, 3 BACK (X1), 4 FORWARD (X2).</summary>
    public static InjectEvent MouseButton(int button, bool up)
    {
        var e = new InjectEvent { Kind = InjectKind.Mouse };
        switch (button)
        {
            case 0: e.Flags = up ? MouseLeftUp : MouseLeftDown; break;
            case 1: e.Flags = up ? MouseRightUp : MouseRightDown; break;
            case 2: e.Flags = up ? MouseMiddleUp : MouseMiddleDown; break;
            case 3: e.Flags = up ? MouseXUp : MouseXDown; e.MouseData = XButton1; break;
            case 4: e.Flags = up ? MouseXUp : MouseXDown; e.MouseData = XButton2; break;
            default: throw new ArgumentOutOfRangeException(nameof(button));
        }
        return e;
    }

    public static InjectEvent Move(int dx, int dy) =>
        new() { Kind = InjectKind.Mouse, Flags = MouseMove, Dx = dx, Dy = dy };

    /// <summary>Wheel steps in detents, + = away from the user; mouseData = steps * 120.</summary>
    public static InjectEvent Wheel(int steps) =>
        new() { Kind = InjectKind.Mouse, Flags = MouseWheel, MouseData = steps * WheelDelta };
}
