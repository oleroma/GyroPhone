using System.Runtime.InteropServices;

namespace GyroPhone.Tray;

/// <summary>
/// The mouse wheel over an overlay window in Edit. Overlays never take focus, and Windows sends the
/// wheel to the focused window when "Scroll inactive windows" is off, so a low-level mouse hook
/// (only while Edit is on and an overlay is open) catches it over an overlay and keeps it from the
/// window behind. Away from every overlay it resizes the picked one, so the wheel then reaches no
/// other window while Edit is on and one is picked, except the companion's own windows.
/// <para>
/// Windows sends every mouse event on the PC (moves too) to the thread that installed the hook and
/// waits for it, so the hook runs on its own small thread with its own message loop, never on the
/// busy UI thread: only a wheel resize goes to the UI thread, posted.
/// </para>
/// </summary>
internal sealed class OverlayWheel : IDisposable
{
    private readonly Func<IntPtr, OverlayForm?> _formAt;
    private readonly Func<OverlayForm?> _picked;
    private readonly HookProc _proc;
    private IntPtr _hook;
    private Thread? _thread;
    private uint _threadId;

    /// <param name="formAt">The open overlay window with this handle, or null (called on the hook's thread).</param>
    /// <param name="picked">The picked overlay window, or null (called on the hook's thread).</param>
    public OverlayWheel(Func<IntPtr, OverlayForm?> formAt, Func<OverlayForm?> picked)
    {
        _formAt = formAt;
        _picked = picked;
        _proc = OnHook; // kept alive while the hook is installed
    }

    /// <summary>Installs or removes the hook (with its thread).</summary>
    public bool Enabled
    {
        get => _thread != null;
        set
        {
            if (value == Enabled) return;
            if (value)
            {
                var started = new ManualResetEventSlim(false);
                _thread = new Thread(() => Run(started)) { IsBackground = true, Name = "overlay wheel", Priority = ThreadPriority.Highest };
                _thread.Start();
                // No hook (SetWindowsHookEx refused): its thread has ended, and the next Enabled = true tries again.
                if (!started.Wait(2000)) return; // still starting: the thread sets it later
                if (_hook == IntPtr.Zero)
                {
                    _thread.Join(1000);
                    _thread = null;
                    _threadId = 0;
                }
            }
            else
            {
                // WM_QUIT ends its message loop, which removes the hook.
                PostThreadMessage(_threadId, WmQuit, IntPtr.Zero, IntPtr.Zero);
                _thread?.Join(1000);
                _thread = null;
                _threadId = 0;
            }
        }
    }

    /// <summary>The hook's thread: installs it, then pumps messages (the hook is called from there) until WM_QUIT.</summary>
    private void Run(ManualResetEventSlim started)
    {
        _threadId = GetCurrentThreadId();
        // The thread's message queue, before anyone posts WM_QUIT to it.
        PeekMessage(out _, IntPtr.Zero, 0, 0, PmNoRemove);
        _hook = SetWindowsHookEx(WhMouseLl, _proc, GetModuleHandle(null), 0);
        started.Set();
        if (_hook == IntPtr.Zero) return;
        try
        {
            while (GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0)
            {
                TranslateMessage(ref msg);
                DispatchMessage(ref msg);
            }
        }
        finally
        {
            UnhookWindowsHookEx(_hook);
            _hook = IntPtr.Zero;
        }
    }

    private IntPtr OnHook(int code, IntPtr wParam, IntPtr lParam)
    {
        if (code >= 0 && (int)wParam == WmMouseWheel)
        {
            var info = Marshal.PtrToStructure<MsLlHookStruct>(lParam);
            var root = GetAncestor(WindowFromPoint(info.Pt), GaRoot);
            // Over the companion's own windows (settings, Debug) the wheel scrolls them as usual.
            var form = _formAt(root) ?? (IsOurs(root) ? null : _picked());
            if (form != null)
            {
                int delta = unchecked((short)(info.MouseData >> 16));
                bool fine = (GetAsyncKeyState(VkShift) & 0x8000) != 0;
                // Resizing happens on the UI thread (the window's): after the hook returns, not inside it.
                try { form.BeginInvoke(() => form.ScaleBy(delta, fine)); }
                catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException) { } // closing
                return new IntPtr(1);
            }
        }
        return CallNextHookEx(_hook, code, wParam, lParam);
    }

    private static readonly uint OwnProcess = (uint)Environment.ProcessId;

    private static bool IsOurs(IntPtr hwnd) =>
        hwnd != IntPtr.Zero && GetWindowThreadProcessId(hwnd, out uint pid) != 0 && pid == OwnProcess;

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

    [StructLayout(LayoutKind.Sequential)]
    private struct Msg
    {
        public IntPtr Hwnd;
        public uint Message;
        public IntPtr WParam, LParam;
        public uint Time;
        public Point Pt;
        public uint Private;
    }

    private const int WhMouseLl = 14;
    private const uint WmQuit = 0x0012;
    private const uint PmNoRemove = 0;
    private const int WmMouseWheel = 0x020A;
    private const int VkShift = 0x10;
    private const uint GaRoot = 2;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowsHookEx(int idHook, HookProc lpfn, IntPtr hMod, uint dwThreadId);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [DllImport("user32.dll")]
    private static extern int GetMessage(out Msg msg, IntPtr hwnd, uint min, uint max);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PeekMessage(out Msg msg, IntPtr hwnd, uint min, uint max, uint remove);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool TranslateMessage(ref Msg msg);

    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessage(ref Msg msg);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PostThreadMessage(uint threadId, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();

    [DllImport("user32.dll")]
    private static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? lpModuleName);

    [DllImport("user32.dll")]
    private static extern IntPtr WindowFromPoint(Point p);

    [DllImport("user32.dll")]
    private static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);

    [DllImport("user32.dll")]
    private static extern short GetAsyncKeyState(int vKey);
}
