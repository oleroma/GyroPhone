using System.Runtime.InteropServices;
using static GyroPhone.Windows.NativeMethods;

namespace GyroPhone.Windows;

/// <summary>Multimedia Class Scheduler registration for the rx thread ("Games" task, high priority).</summary>
public static class Mmcss
{
    public const int AvrtPriorityHigh = NativeMethods.AvrtPriorityHigh;

    private static int _loggedFailure;

    /// <summary>Registers the calling thread. Returns false (logging once) when MMCSS is unavailable.</summary>
    public static bool TryEnter(string task, int priority, Action<string>? log = null)
    {
        try
        {
            uint index = 0;
            nint h = AvSetMmThreadCharacteristicsW(task, ref index);
            if (h == 0)
            {
                LogOnce(log, $"MMCSS '{task}' unavailable (error {Marshal.GetLastPInvokeError()})");
                return false;
            }
            if (!AvSetMmThreadPriority(h, priority))
                LogOnce(log, $"MMCSS priority not set (error {Marshal.GetLastPInvokeError()})");
            // The registration lives as long as the thread; it is never reverted.
            return true;
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            LogOnce(log, "MMCSS not present");
            return false;
        }
    }

    private static void LogOnce(Action<string>? log, string message)
    {
        if (Interlocked.Exchange(ref _loggedFailure, 1) == 0) log?.Invoke(message);
    }
}
