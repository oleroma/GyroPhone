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
    /// The netsh arguments that delete every inbound rule for this executable, including the Block rules
    /// Windows creates when its first-run firewall prompt is dismissed (Block wins over Allow).
    /// </summary>
    public static string DeleteRulesArguments(string exePath) =>
        $"advfirewall firewall delete rule name=all dir=in program=\"{exePath}\"";

    /// <summary>
    /// Replaces this executable's inbound rules with ours through one elevated cmd (UAC prompt): the
    /// delete clears any Block rule, then the add runs whatever the delete returned. Returns false when the
    /// user declined or the add failed. Blocks until it exits, so call it off the UI thread.
    /// </summary>
    public static bool AddRuleElevated(string exePath)
    {
        try
        {
            // cmd /c strips the outer quotes and keeps the inner ones; the exit code is the add's.
            var psi = new ProcessStartInfo("cmd.exe",
                $"/c \"netsh {DeleteRulesArguments(exePath)} & netsh {AddRuleArguments(exePath)}\"")
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
