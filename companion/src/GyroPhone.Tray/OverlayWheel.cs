using System.Runtime.InteropServices;

namespace GyroPhone.Tray;

/// <summary>
/// The mouse wheel over an overlay window in Edit. Overlays never take focus, and Windows sends the
/// wheel to the focused window when "Scroll inactive windows" is off, so a low-level mouse hook
/// (only while Edit is on and an overlay is open) catches it over an overlay and keeps it from the
/// window behind. Away from every overlay it resizes the picked one, so the wheel then reaches no
/// other window while Edit is on and one is picked. Runs on the UI thread, which installed it.
/// </summary>
internal sealed class OverlayWheel : IDisposable
{
    private readonly Func<IntPtr, OverlayForm?> _formAt;
    private readonly Func<OverlayForm?> _picked;
    private readonly HookProc _proc;
    private IntPtr _hook;

    /// <param name="formAt">The open overlay window with this handle, or null.</param>
    /// <param name="picked">The picked overlay window, or null.</param>
    public OverlayWheel(Func<IntPtr, OverlayForm?> formAt, Func<OverlayForm?> picked)
    {
        _formAt = formAt;
        _picked = picked;
        _proc = OnHook; // kept alive while the hook is installed
    }

    /// <summary>Installs or removes the hook.</summary>
    public bool Enabled
    {
        get => _hook != IntPtr.Zero;
        set
        {
            if (value == Enabled) return;
            if (value)
                _hook = SetWindowsHookEx(WhMouseLl, _proc, GetModuleHandle(null), 0);
            else
            {
                UnhookWindowsHookEx(_hook);
                _hook = IntPtr.Zero;
            }
        }
    }

    private IntPtr OnHook(int code, IntPtr wParam, IntPtr lParam)
    {
        if (code >= 0 && (int)wParam == WmMouseWheel)
        {
            var info = Marshal.PtrToStructure<MsLlHookStruct>(lParam);
            var form = _formAt(GetAncestor(WindowFromPoint(info.Pt), GaRoot)) ?? _picked();
            if (form != null)
            {
                int delta = unchecked((short)(info.MouseData >> 16));
                bool fine = (GetAsyncKeyState(VkShift) & 0x8000) != 0;
                // Resizing draws the window again: after the hook returns, not inside it.
                form.BeginInvoke(() => form.ScaleBy(delta, fine));
                return new IntPtr(1);
            }
        }
        return CallNextHookEx(_hook, code, wParam, lParam);
    }

    public void Dispose() => Enabled = false;

    private delegate IntPtr HookProc(int code, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct MsLlHookStruct
    {
        public Point Pt;
        public uint MouseData;
        public uint Flags;
        public uint Time;
        public IntPtr ExtraInfo;
    }

    private const int WhMouseLl = 14;
    private const int WmMouseWheel = 0x020A;
    private const int VkShift = 0x10;
    private const uint GaRoot = 2;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowsHookEx(int idHook, HookProc lpfn, IntPtr hMod, uint dwThreadId);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [DllImport("user32.dll")]
    private static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? lpModuleName);

    [DllImport("user32.dll")]
    private static extern IntPtr WindowFromPoint(Point p);

    [DllImport("user32.dll")]
    private static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);

    [DllImport("user32.dll")]
    private static extern short GetAsyncKeyState(int vKey);
}
