using System.Runtime.InteropServices;
using GyroPhone.Host.Abstractions;

namespace GyroPhone.Windows;

/// <summary>
/// "Use PC controller for gyro ratchet": reads the first XInput controller that is not our own virtual
/// pad, every millisecond on its own thread, and counts each button's presses so a short one
/// between two STATEs is not lost. Free slots are looked at only every few seconds (a query on an
/// empty slot is slow), and not while our own pad is plugged but has no slot yet (it could be any). Reading never takes the controller away from games or Steam Input.
/// </summary>
public sealed class XInputPcPad : IPcPad, IDisposable
{
    // OverlayFrame Pad bits by place: south, east, west, north, L1, R1, L2, R2, minus, plus, home,
    // capture, L3, R3, up, down, left, right.
    private const int Places = 18;
    private const uint PadL2 = 1u << 6, PadR2 = 1u << 7;

    /// <summary>A trigger pulled this far counts as pressed (the phone ratchets at half a pull too).</summary>
    private const byte TriggerPressed = 128;

    private const long RescanMs = 1000;
    /// <summary>How long after a scan that found nothing the empty slots are queried again.</summary>
    private const long EmptyRescanMs = 3000;
    private const double PollMs = 1.0;

    private readonly Action<string>? _log;
    private readonly object _gate = new();
    private readonly uint[] _presses = new uint[Places];
    private uint _held;
    private readonly float[] _axes = new float[6];
    private int _slot = -1;
    private Func<int> _ownSlot = () => -1;
    private Thread? _thread;
    /// <summary>The running reader's stop flag: each reader has its own, so a new one never revives an old one.</summary>
    private RunFlag? _run;
    private bool _useEx = true;

    public XInputPcPad(Action<string>? log = null) => _log = log;

    public string? Name
    {
        get
        {
            int s = Volatile.Read(ref _slot);
            return s < 0 ? null : $"Controller {s + 1}";
        }
    }

    public void Start(Func<int> ownSlot)
    {
        lock (_gate)
        {
            _ownSlot = ownSlot;
            if (_thread != null) return;
            var run = new RunFlag();
            _run = run;
            _thread = new Thread(() => Run(run)) { IsBackground = true, Name = "gp-pc-pad", Priority = ThreadPriority.AboveNormal };
            _thread.Start();
        }
    }

    /// <summary>
    /// Stops the reader without waiting for its thread (the engine calls this under its lock, and
    /// the thread may be in a 100 ms wait or a slow scan): it ends on its own, and writes nothing
    /// once stopped.
    /// </summary>
    public void Stop()
    {
        lock (_gate)
        {
            if (_run != null) _run.Stopped = true;
            _run = null;
            _thread = null;
            _held = 0;
            Array.Clear(_axes);
            Volatile.Write(ref _slot, -1);
        }
    }

    private sealed class RunFlag
    {
        public volatile bool Stopped;
    }

    public bool Read(uint[] presses, out uint held)
    {
        lock (_gate)
        {
            held = _held;
            Array.Copy(_presses, presses, Math.Min(presses.Length, Places));
            return _slot >= 0;
        }
    }

    public bool ReadAxes(Span<float> axes)
    {
        lock (_gate)
        {
            _axes.AsSpan(0, Math.Min(axes.Length, _axes.Length)).CopyTo(axes);
            return _slot >= 0;
        }
    }

    public void Dispose() => Stop();

    private void Run(RunFlag run)
    {
        IntPtr timer = CreateWaitableTimerExW(IntPtr.Zero, IntPtr.Zero, CreateWaitableTimerHighResolution, TimerAllAccess);
        long lastScan = -RescanMs;
        long nextScan = 0;
        var clock = System.Diagnostics.Stopwatch.StartNew();
        int slot = -1;
        try
        {
            while (!run.Stopped)
            {
                long now = clock.ElapsedMilliseconds;
                // Our own pad may have taken the slot read (it was plugged after the scan): never read it.
                if (slot >= 0 && now - lastScan >= RescanMs)
                {
                    lastScan = now;
                    if (OwnSlot() == slot)
                    {
                        _log?.Invoke($"PC controller for gyro ratchet: controller {slot + 1} is our own pad, looking again");
                        slot = -1;
                        nextScan = 0;
                    }
                }
                if (slot < 0 && now >= nextScan)
                {
                    lastScan = now;
                    slot = Scan(out bool ownUnknown);
                    nextScan = now + (slot < 0 && !ownUnknown ? EmptyRescanMs : RescanMs);
                    lock (_gate)
                    {
                        if (run.Stopped) return;
                        Volatile.Write(ref _slot, slot);
                    }
                    if (slot >= 0) _log?.Invoke($"PC controller for gyro ratchet: reading XInput controller {slot + 1}");
                }
                if (slot >= 0)
                {
                    if (TryGet(slot, out var g)) Store(run, g);
                    else
                    {
                        _log?.Invoke($"PC controller for gyro ratchet: controller {slot + 1} went away");
                        slot = -1;
                        lock (_gate)
                        {
                            if (run.Stopped) return;
                            _held = 0;
                            Array.Clear(_axes);
                            Volatile.Write(ref _slot, -1);
                        }
                    }
                }
                Wait(timer, slot >= 0 ? PollMs : 100);
            }
        }
        catch (Exception e)
        {
            _log?.Invoke($"PC controller for gyro ratchet stopped: {e.GetType().Name}: {e.Message}");
        }
        finally
        {
            if (timer != IntPtr.Zero) CloseHandle(timer);
        }
    }

    /// <summary>The first connected slot that is not ours, or -1 (also while our own pad's slot is unknown).</summary>
    private int Scan(out bool ownUnknown)
    {
        int own = OwnSlot();
        ownUnknown = own == IPadSink.SlotUnknown;
        if (ownUnknown) return -1;
        for (int i = 0; i < 4; i++)
        {
            if (i == own) continue;
            if (TryGet(i, out _)) return i;
        }
        return -1;
    }

    private int OwnSlot()
    {
        try
        {
            return _ownSlot();
        }
        catch (Exception)
        {
            return -1;
        }
    }

    private void Store(RunFlag run, in XInputGamepad g)
    {
        uint held = PlaceBits(g);
        lock (_gate)
        {
            if (run.Stopped) return;
            uint down = held & ~_held;
            for (int i = 0; i < Places; i++) if ((down & (1u << i)) != 0) _presses[i]++;
            _held = held;
            // XInput sticks are y up; the overlay's are y down.
            _axes[0] = Stick(g.ThumbLX);
            _axes[1] = -Stick(g.ThumbLY);
            _axes[2] = Stick(g.ThumbRX);
            _axes[3] = -Stick(g.ThumbRY);
            _axes[4] = g.LeftTrigger / 255f;
            _axes[5] = g.RightTrigger / 255f;
        }
    }

    private static float Stick(short v) => Math.Max(-1f, v / 32767f);

    private static uint PlaceBits(in XInputGamepad g)
    {
        uint b = g.Buttons;
        uint p = 0;
        if ((b & 0x1000) != 0) p |= 1u << 0; // A: south
        if ((b & 0x2000) != 0) p |= 1u << 1; // B: east
        if ((b & 0x4000) != 0) p |= 1u << 2; // X: west
        if ((b & 0x8000) != 0) p |= 1u << 3; // Y: north
        if ((b & 0x0100) != 0) p |= 1u << 4; // LB
        if ((b & 0x0200) != 0) p |= 1u << 5; // RB
        if (g.LeftTrigger >= TriggerPressed) p |= PadL2;
        if (g.RightTrigger >= TriggerPressed) p |= PadR2;
        if ((b & 0x0020) != 0) p |= 1u << 8; // Back: minus
        if ((b & 0x0010) != 0) p |= 1u << 9; // Start: plus
        if ((b & 0x0400) != 0) p |= 1u << 10; // Guide: home (XInputGetStateEx only)
        if ((b & 0x0040) != 0) p |= 1u << 12; // left stick press
        if ((b & 0x0080) != 0) p |= 1u << 13; // right stick press
        if ((b & 0x0001) != 0) p |= 1u << 14; // D-pad up
        if ((b & 0x0002) != 0) p |= 1u << 15;
        if ((b & 0x0004) != 0) p |= 1u << 16;
        if ((b & 0x0008) != 0) p |= 1u << 17;
        return p;
    }

    private bool TryGet(int slot, out XInputGamepad g)
    {
        uint r;
        if (_useEx)
        {
            try
            {
                r = XInputGetStateEx((uint)slot, out XInputStateEx ex);
                g = ex.Gamepad;
                return r == 0;
            }
            catch (EntryPointNotFoundException)
            {
                _useEx = false;
            }
        }
        r = XInputGetState((uint)slot, out XInputState s);
        g = s.Gamepad;
        return r == 0;
    }

    private static void Wait(IntPtr timer, double ms)
    {
        if (timer != IntPtr.Zero)
        {
            long due = -(long)(ms * 10_000); // relative, 100 ns units
            if (SetWaitableTimer(timer, ref due, 0, IntPtr.Zero, IntPtr.Zero, false))
            {
                WaitForSingleObject(timer, 1000);
                return;
            }
        }
        Thread.Sleep(Math.Max(1, (int)ms));
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct XInputGamepad
    {
        public ushort Buttons;
        public byte LeftTrigger;
        public byte RightTrigger;
        public short ThumbLX, ThumbLY, ThumbRX, ThumbRY;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct XInputState
    {
        public uint PacketNumber;
        public XInputGamepad Gamepad;
    }

    /// <summary>
    /// What ordinal 100 writes: XINPUT_STATE with 4 more bytes at the end of the gamepad
    /// (dwPaddingReserved), 20 bytes in all. Passing the 16-byte <see cref="XInputState"/> let it
    /// write past the end of the variable.
    /// </summary>
    [StructLayout(LayoutKind.Sequential)]
    private struct XInputStateEx
    {
        public uint PacketNumber;
        public XInputGamepad Gamepad;
        public uint PaddingReserved;
    }

    private const uint CreateWaitableTimerHighResolution = 0x2;
    private const uint TimerAllAccess = 0x1F0003;

    [DllImport("xinput1_4.dll", EntryPoint = "XInputGetState")]
    private static extern uint XInputGetState(uint userIndex, out XInputState state);

    /// <summary>Undocumented ordinal 100: like XInputGetState, with the Guide button.</summary>
    [DllImport("xinput1_4.dll", EntryPoint = "#100")]
    private static extern uint XInputGetStateEx(uint userIndex, out XInputStateEx state);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr CreateWaitableTimerExW(IntPtr attributes, IntPtr name, uint flags, uint access);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWaitableTimer(IntPtr timer, ref long dueTime, int period, IntPtr completion, IntPtr arg, bool resume);

    [DllImport("kernel32.dll")]
    private static extern uint WaitForSingleObject(IntPtr handle, uint ms);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);
}
