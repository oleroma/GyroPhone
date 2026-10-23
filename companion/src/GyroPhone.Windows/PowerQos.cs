using System.Runtime.InteropServices;
using static GyroPhone.Windows.NativeMethods;

namespace GyroPhone.Windows;

/// <summary>
/// Opts out of execution-speed power throttling: Windows may otherwise treat the companion, which
/// never has focus, as background work and run it at low QoS (efficient cores, low clocks). No
/// timer resolution is requested, so IGNORE_TIMER_RESOLUTION is left alone.
/// </summary>
public static class PowerQos
{
    private static int _loggedProcess, _loggedThread;

    /// <summary>The whole process; call once at startup. Returns false (logging once) when unsupported.</summary>
    public static bool TryDisableForProcess(Action<string>? log = null) => Try(log, process: true);

    /// <summary>The calling thread (gp-rx, right after MMCSS). Returns false (logging once) when unsupported.</summary>
    public static bool TryDisableForThread(Action<string>? log = null) => Try(log, process: false);

    private static bool Try(Action<string>? log, bool process)
    {
        try
        {
            var state = new POWER_THROTTLING_STATE
            {
                Version = PowerThrottlingCurrentVersion,
                ControlMask = PowerThrottlingExecutionSpeed,
                StateMask = 0, // managed by us, and off
            };
            uint size = (uint)Marshal.SizeOf<POWER_THROTTLING_STATE>();
            bool ok = process
                ? SetProcessInformation(GetCurrentProcess(), ProcessPowerThrottling, ref state, size)
                : SetThreadInformation(GetCurrentThread(), ThreadPowerThrottling, ref state, size);
            if (!ok) LogOnce(log, process, $"{(process ? "process" : "thread")} power throttling not changed (error {Marshal.GetLastPInvokeError()})");
            return ok;
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            LogOnce(log, process, "power throttling control not present");
            return false;
        }
    }

    /// <summary>Once for the process call and once for the thread call (on Windows 10 only the thread call fails).</summary>
    private static void LogOnce(Action<string>? log, bool process, string message)
    {
        if (Interlocked.Exchange(ref process ? ref _loggedProcess : ref _loggedThread, 1) == 0) log?.Invoke(message);
    }
}
