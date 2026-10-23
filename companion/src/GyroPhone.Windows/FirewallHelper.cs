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

    /// <summary>
    /// True when Windows Firewall lets this executable receive UDP on the networks it is connected to
    /// now: an enabled inbound Allow rule for its path (any name, so the rule Windows adds from its own
    /// first-run prompt counts too) covering UDP and an active profile (Private, Public or Domain), and
    /// no enabled inbound Block rule for it there (Block wins over Allow). Rules are per path, so a copy elsewhere needs its own. Reads the
    /// rules through the firewall's COM API, which needs no elevation; falls back to netsh by our rule
    /// name when that fails.
    /// </summary>
    public static bool RuleExists(string exePath)
    {
        try
        {
            return ComRuleAllows(exePath) ?? NetshRuleExists(exePath);
        }
        catch (Exception e) when (e is Win32Exception or InvalidOperationException)
        {
            return false;
        }
    }

    private const int DirectionIn = 1;
    private const int ActionBlock = 0;
    private const int ActionAllow = 1;
    private const int ProtocolUdp = 17;
    private const int ProtocolAny = 256;
    private const int ProfilePrivate = 2;
    private const int ProfileAll = 0x7FFFFFFF;

    /// <summary>The COM answer, or null when the firewall policy cannot be read that way.</summary>
    private static bool? ComRuleAllows(string exePath)
    {
        try
        {
            var type = Type.GetTypeFromProgID("HNetCfg.FwPolicy2");
            if (type == null) return null;
            dynamic? policy = Activator.CreateInstance(type);
            if (policy == null) return null;
            // The profiles of the connected networks; none connected: judge by Private, where pairing happens.
            int active = (int)policy.CurrentProfileTypes;
            if ((active & ProfileAll) == 0) active = ProfilePrivate;
            bool allowed = false;
            foreach (dynamic rule in policy.Rules)
            {
                string? app = rule.ApplicationName;
                if (app == null || !string.Equals(app, exePath, StringComparison.OrdinalIgnoreCase)) continue;
                if (!(bool)rule.Enabled || (int)rule.Direction != DirectionIn) continue;
                int protocol = rule.Protocol;
                if (protocol != ProtocolUdp && protocol != ProtocolAny) continue;
                if (((int)rule.Profiles & active) == 0) continue;
                int action = rule.Action;
                if (action == ActionBlock) return false;
                if (action == ActionAllow) allowed = true;
            }
            return allowed;
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.COMException or Microsoft.CSharp.RuntimeBinder.RuntimeBinderException
                                       or InvalidCastException or UnauthorizedAccessException or PlatformNotSupportedException)
        {
            return null;
        }
    }

    /// <summary>
    /// netsh by our rule name: exits with 1 when no rule matches; the verbose listing includes each
    /// rule's program path whatever the display language.
    /// </summary>
    private static bool NetshRuleExists(string exePath)
    {
        var psi = new ProcessStartInfo("netsh", $"advfirewall firewall show rule name=\"{RuleName}\" verbose")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        using var p = Process.Start(psi);
        if (p == null) return false;
        string output = p.StandardOutput.ReadToEnd();
        p.StandardError.ReadToEnd();
        if (!p.WaitForExit(10_000)) return false;
        return p.ExitCode == 0 && output.Contains(exePath, StringComparison.OrdinalIgnoreCase);
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
