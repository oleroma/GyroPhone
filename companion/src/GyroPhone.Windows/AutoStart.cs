using Microsoft.Win32;

namespace GyroPhone.Windows;

/// <summary>"Start with Windows" through the per-user Run key (no admin rights needed).</summary>
public static class AutoStart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    public const string ValueName = "GyroPhoneCompanion";

    /// <summary>The watcher's Run entry: <c>GyroPhoneCompanion.exe --watch</c>.</summary>
    public const string WatcherValueName = "GyroPhoneWatcher";

    private static string Command(string exePath, string? args) => args == null ? $"\"{exePath}\"" : $"\"{exePath}\" {args}";

    /// <summary>True when the Run entry exists and points at this executable.</summary>
    public static bool IsEnabled(string exePath) => IsEnabled(ValueName, exePath, null);

    public static void Set(bool enabled, string exePath) => Set(ValueName, enabled, exePath, null);

    public static bool IsEnabled(string valueName, string exePath, string? args)
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: false);
        return key?.GetValue(valueName) is string v && string.Equals(v, Command(exePath, args), StringComparison.OrdinalIgnoreCase);
    }

    public static void Set(string valueName, bool enabled, string exePath, string? args)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
        if (enabled) key.SetValue(valueName, Command(exePath, args), RegistryValueKind.String);
        else key.DeleteValue(valueName, throwOnMissingValue: false);
    }
}
