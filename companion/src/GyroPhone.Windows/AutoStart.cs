using Microsoft.Win32;

namespace GyroPhone.Windows;

/// <summary>"Start with Windows" through the per-user Run key (no admin rights needed).</summary>
public static class AutoStart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    public const string ValueName = "GyroPhoneCompanion";

    private static string Command(string exePath) => $"\"{exePath}\"";

    /// <summary>True when the Run entry exists and points at this executable.</summary>
    public static bool IsEnabled(string exePath)
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: false);
        return key?.GetValue(ValueName) is string v && string.Equals(v, Command(exePath), StringComparison.OrdinalIgnoreCase);
    }

    public static void Set(bool enabled, string exePath)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
        if (enabled) key.SetValue(ValueName, Command(exePath), RegistryValueKind.String);
        else key.DeleteValue(ValueName, throwOnMissingValue: false);
    }
}
