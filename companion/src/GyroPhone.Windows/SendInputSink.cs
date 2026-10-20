using System.Runtime.InteropServices;
using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol.Mapping;
using static GyroPhone.Windows.NativeMethods;

namespace GyroPhone.Windows;

/// <summary>
/// Keyboard and mouse through SendInput. Keys go as Set 1 scan codes (Pause on the VK path), mouse
/// moves are relative. One SendInput call per Inject, from a preallocated INPUT buffer.
/// </summary>
public sealed unsafe class SendInputSink : IKeyMouseSink
{
    // Release-all can produce one event per usage plus mouse buttons; anything larger is chunked.
    private const int Capacity = 272;

    private readonly INPUT[] _buf = new INPUT[Capacity];
    private readonly Action<string>? _log;
    private long _calls, _shortWrites;
    private bool _loggedFailure;

    public SendInputSink(Action<string>? log = null)
    {
        _log = log;
        int expected = IntPtr.Size == 8 ? 40 : 28;
        if (sizeof(INPUT) != expected)
            throw new InvalidOperationException($"INPUT is {sizeof(INPUT)} bytes, expected {expected}");
    }

    public long Calls => Interlocked.Read(ref _calls);

    /// <summary>SendInput calls that inserted fewer events than asked (UIPI block, secure desktop).</summary>
    public long ShortWrites => Interlocked.Read(ref _shortWrites);

    public void Inject(ReadOnlySpan<InjectEvent> events)
    {
        while (!events.IsEmpty)
        {
            int n = Math.Min(events.Length, Capacity);
            for (int i = 0; i < n; i++) Fill(ref _buf[i], events[i]);
            uint sent;
            fixed (INPUT* p = _buf) sent = SendInput((uint)n, p, sizeof(INPUT));
            Interlocked.Increment(ref _calls);
            if (sent != n)
            {
                Interlocked.Increment(ref _shortWrites);
                if (!_loggedFailure)
                {
                    _loggedFailure = true;
                    _log?.Invoke($"SendInput inserted {sent} of {n} events (error {Marshal.GetLastPInvokeError()}); "
                        + "an elevated window or the lock screen is in front");
                }
            }
            events = events[n..];
        }
    }

    private static void Fill(ref INPUT input, in InjectEvent e)
    {
        input = default;
        if (e.Kind == InjectKind.Key)
        {
            input.type = InputKeyboard;
            input.u.ki.wVk = e.Vk;
            input.u.ki.wScan = e.Scan;
            input.u.ki.dwFlags = e.Flags;
        }
        else
        {
            input.type = InputMouse;
            input.u.mi.dx = e.Dx;
            input.u.mi.dy = e.Dy;
            input.u.mi.mouseData = e.MouseData;
            input.u.mi.dwFlags = e.Flags;
        }
    }
}
