using System.ComponentModel;
using System.Diagnostics;
using GyroPhone.Protocol;

namespace GyroPhone.Windows;

/// <summary>
/// Windows Firewall rule for the companion via netsh. The rule is only ever added for the Private and
/// Domain profiles; Public networks stay closed.
/// </summary>
public static class FirewallHelper
{
    public const string RuleName = "GyroPhone Companion";

    /// <summary>True when a rule with our name exists. netsh exits with 1 when no rule matches.</summary>
    public static bool RuleExists()
    {
        try
        {
            var psi = new ProcessStartInfo("netsh", $"advfirewall firewall show rule name=\"{RuleName}\"")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            using var p = Process.Start(psi);
            if (p == null) return false;
            p.StandardOutput.ReadToEnd();
            p.StandardError.ReadToEnd();
            if (!p.WaitForExit(10_000)) return false;
            return p.ExitCode == 0;
        }
        catch (Exception e) when (e is Win32Exception or InvalidOperationException)
        {
            return false;
        }
    }

    /// <summary>The netsh arguments that add the rule for this executable.</summary>
    public static string AddRuleArguments(string exePath) =>
        $"advfirewall firewall add rule name=\"{RuleName}\" dir=in action=allow program=\"{exePath}\" "
        + $"protocol=UDP localport={Wire.Port} profile=private,domain";

    /// <summary>
    /// Adds the rule through an elevated netsh (UAC prompt). Returns false when the user declined or
    /// netsh failed. Blocks until netsh exits, so call it off the UI thread.
    /// </summary>
    public static bool AddRuleElevated(string exePath)
    {
        try
        {
            var psi = new ProcessStartInfo("netsh", AddRuleArguments(exePath))
            {
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            using var p = Process.Start(psi);
            if (p == null) return false;
            if (!p.WaitForExit(30_000)) return false;
            return p.ExitCode == 0;
        }
        catch (Win32Exception)
        {
            return false; // UAC declined (ERROR_CANCELLED)
        }
    }
}
