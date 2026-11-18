using System.Runtime.InteropServices;
using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Windows;

/// <summary>
/// "Press a key" on the phone: reads this PC's keyboard and mouse through raw input (RIDEV_INPUTSINK, on a hidden
/// message-only window on its own thread) while the phone listens. Raw input only reads: every key and click still
/// goes to Windows and the game as usual (no hook, nothing held back). Keyboard input with no device behind it (SendInput,
/// ours included) is skipped.
/// </summary>
public sealed class RawInputListener : IKeyMouseListener, IDisposable
{
    private const int WmInput = 0x00FF;
    private const int WmClose = 0x0010;
    private const uint RidInput = 0x10000003;
    private const uint RidevInputSink = 0x00000100;
    private const uint RidevRemove = 0x00000001;
    private const ushort RiKeyBreak = 1, RiKeyE0 = 2;
    private static readonly IntPtr HwndMessage = new(-3);

    private readonly Action<string>? _log;
    private readonly object _gate = new();
    // What is held now, and what was pressed since the last Read (kept until then even if let go).
    private readonly HashSet<PcPress> _held = new();
    private readonly HashSet<PcPress> _pressed = new();
    private int _wheelUp, _wheelDown;
    // The listen under way (null: none), and the thread of the latest one (the next waits for it to have let go).
    private RunState? _run;
    private Thread? _thread;
    private readonly byte[] _buffer = new byte[256];
    // Kept alive while the window class exists: native code calls it.
    private WndProc? _proc;
    private bool _loggedFailure;

    public RawInputListener(Action<string>? log = null) => _log = log;

    /// <summary>One listen: its window once made, and whether it was stopped (both under _gate).</summary>
    private sealed class RunState
    {
        public IntPtr Hwnd;
        public bool Stopped;
    }

    /// <summary>
    /// Starts listening without waiting for the listener's window (the engine calls this under its lock on gp-rx): until
    /// it is up, <see cref="Read"/> has nothing.
    /// </summary>
    public void Start()
    {
        lock (_gate)
        {
            if (_run != null) return;
            var run = new RunState();
            var previous = _thread;
            _run = run;
            _thread = new Thread(() => Run(run, previous)) { IsBackground = true, Name = "gp-press-listen" };
            _thread.Start();
        }
    }

    /// <summary>Stops listening without waiting for the listener's thread (the engine calls this under its lock): it ends on its own.</summary>
    public void Stop()
    {
        IntPtr hwnd;
        lock (_gate)
        {
            _held.Clear();
            _pressed.Clear();
            _wheelUp = _wheelDown = 0;
            var run = _run;
            if (run == null) return;
            _run = null;
            run.Stopped = true;
            hwnd = run.Hwnd;
        }
        if (hwnd != IntPtr.Zero) PostMessage(hwnd, WmClose, IntPtr.Zero, IntPtr.Zero);
    }

    public int Read(Span<PcPress> into)
    {
        lock (_gate)
        {
            int n = 0;
            foreach (var p in _held)
                if (n < into.Length) into[n++] = p;
            foreach (var p in _pressed)
                if (n < into.Length && !_held.Contains(p)) into[n++] = p;
            _pressed.Clear();
            if (_wheelUp > 0 && n < into.Length) into[n++] = new PcPress(PcPress.Wheel, 0, 1);
            if (_wheelDown > 0 && n < into.Length) into[n++] = new PcPress(PcPress.Wheel, 0, -1);
            _wheelUp = _wheelDown = 0;
            return n;
        }
    }

    public void Dispose() => Stop();

    private void Run(RunState run, Thread? previous)
    {
        IntPtr hwnd = IntPtr.Zero;
        try
        {
            // The listen before may still be letting go of raw input: registering before it did would lose ours.
            previous?.Join();
            _proc ??= WindowProc;
            var cls = new WndClassEx
            {
                Size = (uint)Marshal.SizeOf<WndClassEx>(),
                WndProc = Marshal.GetFunctionPointerForDelegate(_proc),
                Instance = GetModuleHandle(null),
                ClassName = "GyroPhonePressListener",
            };
            RegisterClassEx(ref cls); // fails harmlessly when already registered by an earlier Start
            hwnd = CreateWindowEx(0, cls.ClassName, "", 0, 0, 0, 0, 0, HwndMessage, IntPtr.Zero, cls.Instance, IntPtr.Zero);
            if (hwnd == IntPtr.Zero)
            {
                Fail($"no listener window (error {Marshal.GetLastPInvokeError()})");
                return;
            }
            var devices = new[]
            {
                new RawInputDevice { UsagePage = 1, Usage = 6, Flags = RidevInputSink, Target = hwnd }, // keyboard
                new RawInputDevice { UsagePage = 1, Usage = 2, Flags = RidevInputSink, Target = hwnd }, // mouse
            };
            bool registered;
            lock (RawMouseOwner.Gate)
            {
                registered = RegisterRawInputDevices(devices, (uint)devices.Length, (uint)Marshal.SizeOf<RawInputDevice>());
                // The process's raw mouse input is this listen's now: the delivery probe leaves it alone until it ends.
                if (registered) RawMouseOwner.Listener = true;
            }
            if (!registered)
            {
                Fail($"raw input not registered (error {Marshal.GetLastPInvokeError()})");
                DestroyWindow(hwnd);
                return;
            }
            bool stopped;
            lock (_gate)
            {
                stopped = run.Stopped;
                if (!stopped) run.Hwnd = hwnd;
            }
            while (!stopped && GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0)
            {
                if (msg.Message == WmClose && msg.Hwnd == hwnd) break;
                DispatchMessage(ref msg);
            }
            var remove = new[]
            {
                new RawInputDevice { UsagePage = 1, Usage = 6, Flags = RidevRemove, Target = IntPtr.Zero },
                new RawInputDevice { UsagePage = 1, Usage = 2, Flags = RidevRemove, Target = IntPtr.Zero },
            };
            lock (RawMouseOwner.Gate)
            {
                RegisterRawInputDevices(remove, (uint)remove.Length, (uint)Marshal.SizeOf<RawInputDevice>());
                RawMouseOwner.Listener = false;
            }
            DestroyWindow(hwnd);
            // That took the process's raw mouse input away from Diagnostics' delivery probe too: it registers again.
            DeliveryProbe.RawMouseReleased();
        }
        catch (Exception e)
        {
            Fail("listener stopped: " + e.Message);
        }
    }

    private void Fail(string why)
    {
        if (_loggedFailure) return;
        _loggedFailure = true;
        _log?.Invoke("press a key: " + why);
    }

    private IntPtr WindowProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == WmInput)
        {
            try
            {
                OnInput(hwnd, lParam);
            }
            catch (Exception)
            {
                // never let an exception unwind into the window procedure
            }
        }
        return DefWindowProc(hwnd, msg, wParam, lParam);
    }

    private unsafe void OnInput(IntPtr hwnd, IntPtr lParam)
    {
        int header = 8 + 2 * IntPtr.Size;
        uint size = (uint)_buffer.Length;
        fixed (byte* p = _buffer)
        {
            if (GetRawInputData(lParam, RidInput, (IntPtr)p, ref size, (uint)header) == unchecked((uint)-1)) return;
        }
        uint type = BitConverter.ToUInt32(_buffer, 0);
        IntPtr device = IntPtr.Size == 8 ? (IntPtr)BitConverter.ToInt64(_buffer, 8) : BitConverter.ToInt32(_buffer, 8);
        // No device behind it: injected (SendInput, ours too) for a key. A laptop's precision touchpad sends its
        // clicks with no device too, so a mouse button counts anyway: GyroPhone sends no clicks while the phone listens.
        // The input driver's own mouse and keyboard are GyroPhone's output too, never the PC's keys.
        if (OwnInputDevice.Is(device)) return;
        if (type == 1 && device != IntPtr.Zero) Keyboard(hwnd, header);
        else if (type == 0) Mouse(hwnd, header);
    }

    /// <summary>RAWKEYBOARD: MakeCode u16, Flags u16, Reserved u16, VKey u16, ...</summary>
    private void Keyboard(IntPtr hwnd, int at)
    {
        ushort scan = BitConverter.ToUInt16(_buffer, at);
        ushort flags = BitConverter.ToUInt16(_buffer, at + 2);
        ushort vk = BitConverter.ToUInt16(_buffer, at + 6);
        if (vk == 0xFF) return; // the fake shifts some keyboards wrap round other keys
        int usage = HidScanCodes.UsageOf(scan, (flags & RiKeyE0) != 0, vk);
        if (usage == 0) return;
        Edge(hwnd, new PcPress(PcPress.Key, (ushort)usage), (flags & RiKeyBreak) == 0);
    }

    /// <summary>RAWMOUSE: usFlags u16, 2 bytes, usButtonFlags u16, usButtonData u16, ...</summary>
    private void Mouse(IntPtr hwnd, int at)
    {
        ushort buttons = BitConverter.ToUInt16(_buffer, at + 4);
        if (buttons == 0) return;
        for (int b = 0; b < 5; b++)
        {
            if ((buttons & (1 << (2 * b))) != 0) Edge(hwnd, new PcPress(PcPress.Mouse, (ushort)b), true);
            if ((buttons & (2 << (2 * b))) != 0) Edge(hwnd, new PcPress(PcPress.Mouse, (ushort)b), false);
        }
        if ((buttons & 0x0400) != 0)
        {
            short delta = BitConverter.ToInt16(_buffer, at + 6);
            lock (_gate)
            {
                if (!Current(hwnd)) return;
                if (delta > 0) _wheelUp++;
                else if (delta < 0) _wheelDown++;
            }
        }
    }

    /// <summary>Input came to the window of the listen under way (not one stopped and still letting go); under _gate.</summary>
    private bool Current(IntPtr hwnd) => _run != null && _run.Hwnd == hwnd;

    private void Edge(IntPtr hwnd, PcPress p, bool down)
    {
        lock (_gate)
        {
            if (!Current(hwnd)) return;
            if (down)
            {
                _held.Add(p);
                _pressed.Add(p);
            }
            else _held.Remove(p);
        }
    }

    private delegate IntPtr WndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private struct RawInputDevice
    {
        public ushort UsagePage;
        public ushort Usage;
        public uint Flags;
        public IntPtr Target;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WndClassEx
    {
        public uint Size;
        public uint Style;
        public IntPtr WndProc;
        public int ClsExtra;
        public int WndExtra;
        public IntPtr Instance;
        public IntPtr Icon;
        public IntPtr Cursor;
        public IntPtr Background;
        public string? MenuName;
        public string ClassName;
        public IntPtr IconSmall;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Msg
    {
        public IntPtr Hwnd;
        public uint Message;
        public IntPtr WParam;
        public IntPtr LParam;
        public uint Time;
        public int X, Y;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterRawInputDevices(RawInputDevice[] devices, uint count, uint size);

    [DllImport("user32.dll")]
    private static extern uint GetRawInputData(IntPtr rawInput, uint command, IntPtr data, ref uint size, uint headerSize);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern ushort RegisterClassEx(ref WndClassEx cls);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateWindowEx(uint exStyle, string className, string windowName, uint style,
        int x, int y, int width, int height, IntPtr parent, IntPtr menu, IntPtr instance, IntPtr param);

    [DllImport("user32.dll")]
    private static extern bool DestroyWindow(IntPtr hwnd);

    [DllImport("user32.dll")]
    private static extern IntPtr DefWindowProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern int GetMessage(out Msg msg, IntPtr hwnd, uint min, uint max);

    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessage(ref Msg msg);

    [DllImport("user32.dll")]
    private static extern bool PostMessage(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? name);
}
