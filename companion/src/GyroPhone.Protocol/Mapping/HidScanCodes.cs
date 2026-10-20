namespace GyroPhone.Protocol.Mapping;

/// <summary>A Set 1 scan code, or a virtual key when <see cref="Vk"/> is non-zero (Pause).</summary>
public readonly record struct ScanCode(ushort Scan, bool Extended, ushort Vk = 0)
{
    public bool IsMapped => Scan != 0 || Vk != 0;
}

/// <summary>HID keyboard usage (page 0x07) to Set 1 scan code (SPEC 3.3).</summary>
public static class HidScanCodes
{
    private static readonly ScanCode[] Table = Build();

    /// <summary>Scan code for a usage; <see cref="ScanCode.IsMapped"/> is false for unknown usages.</summary>
    public static ScanCode For(int usage) => usage is >= 0 and < 256 ? Table[usage] : default;

    /// <summary>Modifier usages 0xE0..0xE7.</summary>
    public static bool IsModifier(int usage) => usage is >= 0xE0 and <= 0xE7;

    private static ScanCode[] Build()
    {
        var t = new ScanCode[256];
        void S(int usage, int scan) => t[usage] = new ScanCode((ushort)scan, false);
        void E(int usage, int scan) => t[usage] = new ScanCode((ushort)scan, true);
        void Run(int first, params int[] scans)
        {
            for (int i = 0; i < scans.Length; i++) S(first + i, scans[i]);
        }

        // 0x04..0x1D A..Z
        Run(0x04, 0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,
            0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C);
        // 0x1E..0x27 1..9, 0
        for (int i = 0; i < 10; i++) S(0x1E + i, 0x02 + i);
        Run(0x28, 0x1C, 0x01, 0x0E, 0x0F, 0x39, 0x0C, 0x0D, 0x1A, 0x1B, 0x2B, 0x2B,
            0x27, 0x28, 0x29, 0x33, 0x34, 0x35, 0x3A);
        // 0x3A..0x43 F1..F10, then F11, F12
        for (int i = 0; i < 10; i++) S(0x3A + i, 0x3B + i);
        S(0x44, 0x57);
        S(0x45, 0x58);
        E(0x46, 0x37);                                               // PrintScreen
        S(0x47, 0x46);                                               // ScrollLock
        t[0x48] = new ScanCode(0, false, InjectEvent.VkPause);       // Pause: VK path
        E(0x49, 0x52); E(0x4A, 0x47); E(0x4B, 0x49); E(0x4C, 0x53);  // Insert Home PgUp Delete
        E(0x4D, 0x4F); E(0x4E, 0x51);                                // End PgDn
        E(0x4F, 0x4D); E(0x50, 0x4B); E(0x51, 0x50); E(0x52, 0x48);  // Right Left Down Up
        E(0x53, 0x45);                                               // NumLock (plain 0x45 is Pause)
        E(0x54, 0x35);                                               // KP /
        S(0x55, 0x37); S(0x56, 0x4A); S(0x57, 0x4E);                 // KP * - +
        E(0x58, 0x1C);                                               // KP Enter
        Run(0x59, 0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49, 0x52, 0x53); // KP1..9, KP0, KP.
        S(0x64, 0x56);                                               // Non-US backslash
        E(0x65, 0x5D);                                               // Application
        Run(0x68, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x76); // F13..F24
        S(0xE0, 0x1D); S(0xE1, 0x2A); S(0xE2, 0x38); E(0xE3, 0x5B);  // LCtrl LShift LAlt LWin
        E(0xE4, 0x1D); S(0xE5, 0x36); E(0xE6, 0x38); E(0xE7, 0x5C);  // RCtrl RShift RAlt RWin
        return t;
    }
}
