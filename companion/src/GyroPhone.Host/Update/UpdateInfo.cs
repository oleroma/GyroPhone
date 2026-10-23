using System.Text.RegularExpressions;

namespace GyroPhone.Host.Update;

/// <summary>
/// Version checks for the companion's self-restart. update.cmd stamps the exe with InformationalVersion
/// "0.1.&lt;commit count&gt;-&lt;commit&gt;", which is also the file's ProductVersion; other builds have no build number.
/// </summary>
public static partial class UpdateInfo
{
    [GeneratedRegex(@"^0\.1\.(\d+)-[0-9a-f]+")]
    private static partial Regex VersionRegex();

    /// <summary>The build number from a product version, or null for unstamped builds (which never restart).</summary>
    public static int? BuildNumber(string? productVersion)
    {
        if (productVersion == null) return null;
        var m = VersionRegex().Match(productVersion);
        return m.Success && int.TryParse(m.Groups[1].Value, out int n) ? n : null;
    }

    /// <summary>True when the exe on disk is a different stamped build than the running one.</summary>
    public static bool IsReplaced(string? running, string? onDisk) =>
        BuildNumber(running) != null && BuildNumber(onDisk) != null
        && !string.Equals(running, onDisk, StringComparison.OrdinalIgnoreCase);
}
