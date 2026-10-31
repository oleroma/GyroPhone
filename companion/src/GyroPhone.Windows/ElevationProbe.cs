using System.Runtime.InteropServices;
using static GyroPhone.Windows.NativeMethods;

namespace GyroPhone.Windows;

/// <summary>
/// Whether the companion itself runs as administrator (OpenProcessToken on its own process, TokenElevation).
/// It never opens other processes: probing the foreground window touched the game's process, which
/// anti-cheat systems log.
/// </summary>
public static class ElevationProbe
{
    private static readonly Lazy<bool> SelfElevated = new(() => IsProcessElevated(GetCurrentProcess()) == true);

    /// <summary>The companion itself runs as administrator (then UIPI does not apply).</summary>
    public static bool IsSelfElevated => SelfElevated.Value;

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
