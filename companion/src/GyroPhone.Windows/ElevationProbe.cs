using System.Runtime.InteropServices;
using static GyroPhone.Windows.NativeMethods;

namespace GyroPhone.Windows;

/// <summary>
/// Detects an elevated foreground window, which UIPI shields from SendInput. Foreground window, PID,
/// OpenProcess(QUERY_LIMITED), OpenProcessToken(TOKEN_QUERY), TokenElevation. ACCESS_DENIED at any
/// step counts as elevated.
/// </summary>
public static class ElevationProbe
{
    private static readonly Lazy<bool> SelfElevated = new(() => IsProcessElevated(GetCurrentProcess()) == true);

    /// <summary>The companion itself runs as administrator (then UIPI does not apply).</summary>
    public static bool IsSelfElevated => SelfElevated.Value;

    /// <summary>True when the foreground window belongs to an elevated process the companion cannot inject into.</summary>
    public static bool IsForegroundElevated()
    {
        if (IsSelfElevated) return false;
        nint hwnd = GetForegroundWindow();
        if (hwnd == 0) return false;
        GetWindowThreadProcessId(hwnd, out uint pid);
        if (pid == 0 || pid == (uint)Environment.ProcessId) return false;
        nint process = OpenProcess(ProcessQueryLimitedInformation, false, pid);
        if (process == 0) return Marshal.GetLastPInvokeError() == ErrorAccessDenied;
        try
        {
            return IsProcessElevated(process) ?? true;
        }
        finally
        {
            CloseHandle(process);
        }
    }

    /// <summary>TokenElevation of a process; null when the token cannot be read because of ACCESS_DENIED, false on other errors.</summary>
    private static bool? IsProcessElevated(nint process)
    {
        if (!OpenProcessToken(process, TokenQuery, out nint token))
            return Marshal.GetLastPInvokeError() == ErrorAccessDenied ? null : false;
        try
        {
            if (!GetTokenInformation(token, TokenElevationClass, out uint elevated, sizeof(uint), out _))
                return Marshal.GetLastPInvokeError() == ErrorAccessDenied ? null : false;
            return elevated != 0;
        }
        finally
        {
            CloseHandle(token);
        }
    }
}
