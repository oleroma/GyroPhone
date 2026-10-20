using System.Text.RegularExpressions;

namespace GyroPhone.Host.Update;

/// <summary>
/// Version checks for the companion's self-restart. CI stamps the exe with InformationalVersion
/// "0.1.&lt;run&gt;-&lt;commit&gt;", which is also the file's ProductVersion; local builds have no run number.
/// </summary>
public static partial class UpdateInfo
{
    [GeneratedRegex(@"^0\.1\.(\d+)-[0-9a-f]+")]
    private static partial Regex VersionRegex();

    /// <summary>The CI run number from a product version, or null for local builds (which never restart).</summary>
    public static int? BuildNumber(string? productVersion)
    {
        if (productVersion == null) return null;
        var m = VersionRegex().Match(productVersion);
        return m.Success && int.TryParse(m.Groups[1].Value, out int n) ? n : null;
    }

    /// <summary>True when the exe on disk is a different CI build than the running one.</summary>
    public static bool IsReplaced(string? running, string? onDisk) =>
        BuildNumber(running) != null && BuildNumber(onDisk) != null
        && !string.Equals(running, onDisk, StringComparison.OrdinalIgnoreCase);
}
