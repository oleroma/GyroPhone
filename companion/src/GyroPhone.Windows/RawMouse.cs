using System.Runtime.InteropServices;

namespace GyroPhone.Windows;

/// <summary>
/// Reads one WM_INPUT and keeps only a relative mouse move from GyroPhone: SendInput's (no device behind it) or the
/// input driver's own mouse (<see cref="OwnInputDevice"/>), never the PC's own mouse. One per reading thread (it keeps a
/// buffer).
/// </summary>
internal sealed class RawMouse
{
    private const uint RidInput = 0x10000003;
    private readonly byte[] _buffer = new byte[128];

    /// <summary>True for a move from GyroPhone, with its size and whether it came through the input driver.</summary>
    public unsafe bool Read(IntPtr lParam, out int x, out int y, out bool driver)
    {
        x = y = 0;
        driver = false;
        int header = 8 + 2 * IntPtr.Size;
        uint size = (uint)_buffer.Length;
        fixed (byte* p = _buffer)
        {
            if (GetRawInputData(lParam, RidInput, (IntPtr)p, ref size, (uint)header) == unchecked((uint)-1)) return false;
        }
        if (BitConverter.ToUInt32(_buffer, 0) != 0) return false; // mouse only
        IntPtr device = IntPtr.Size == 8 ? (IntPtr)BitConverter.ToInt64(_buffer, 8) : BitConverter.ToInt32(_buffer, 8);
        driver = device != IntPtr.Zero;
        if (driver && !OwnInputDevice.Is(device)) return false; // the PC's own mouse
        // RAWMOUSE: usFlags u16, (pad) u16, ulButtons u32, ulRawButtons u32, lLastX i32, lLastY i32.
        if ((BitConverter.ToUInt16(_buffer, header) & 1) != 0) return false; // absolute: not ours
        x = BitConverter.ToInt32(_buffer, header + 12);
        y = BitConverter.ToInt32(_buffer, header + 16);
        return x != 0 || y != 0;
    }

    [DllImport("user32.dll")]
    private static extern uint GetRawInputData(IntPtr rawInput, uint command, IntPtr data, ref uint size, uint headerSize);
}
