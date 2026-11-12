using System.Runtime.InteropServices;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Windows;

/// <summary>
/// What each character key types on the keyboard layout in use (the foreground window's), for
/// KEY_LABELS: scan code to virtual key to unshifted character, with MapVirtualKeyEx (no keyboard
/// state, no dead-key side effects). It reads only the foreground window's thread id, never opens
/// its process.
/// </summary>
public static class KeyboardLayoutProbe
{
    private const uint MapvkVkToChar = 2;
    private const uint MapvkVscToVkEx = 3;

    public static KeyLabels Read()
    {
        nint fg = GetForegroundWindow();
        uint thread = fg == 0 ? 0 : GetWindowThreadProcessId(fg, out _);
        nint hkl = GetKeyboardLayout(thread);
        var chars = new ushort[KeyLabels.Usages.Length];
        for (int i = 0; i < chars.Length; i++)
        {
            var sc = HidScanCodes.For(KeyLabels.Usages[i]);
            if (sc.Scan == 0) continue;
            uint vk = MapVirtualKeyExW(sc.Scan, MapvkVscToVkEx, hkl);
            if (vk == 0) continue;
            // The high bit marks a dead key; its character is still what the key shows.
            chars[i] = (ushort)(MapVirtualKeyExW(vk, MapvkVkToChar, hkl) & 0xFFFF);
        }
        return new KeyLabels((uint)(nuint)hkl, chars);
    }

    [DllImport("user32.dll")]
    private static extern nint GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(nint hWnd, out uint processId);

    [DllImport("user32.dll")]
    private static extern nint GetKeyboardLayout(uint threadId);

    [DllImport("user32.dll", EntryPoint = "MapVirtualKeyExW")]
    private static extern uint MapVirtualKeyExW(uint code, uint mapType, nint hkl);
}
