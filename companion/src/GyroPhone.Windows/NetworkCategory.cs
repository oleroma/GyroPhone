using System.Runtime.InteropServices;

namespace GyroPhone.Windows;

/// <summary>
/// Whether any connected network is in the Public category, through the late-bound Network List
/// Manager (no interop assembly). Public networks block discovery under the default firewall rules.
/// </summary>
public static class NetworkCategory
{
    private static readonly Guid NetworkListManagerClsid = new("DCB00C01-570F-4A9B-8D69-199FDBA5723B");
    private const int NlmEnumNetworkConnected = 0x01;
    private const int NlmNetworkCategoryPublic = 0;

    public const string SettingsUri = "ms-settings:network-status";

    /// <summary>True when a connected network is Public. False when nothing is Public or the query fails.</summary>
    public static bool IsAnyConnectedPublic()
    {
        var type = Type.GetTypeFromCLSID(NetworkListManagerClsid, throwOnError: false);
        if (type == null) return false;
        object? manager = null;
        try
        {
            manager = Activator.CreateInstance(type);
            if (manager == null) return false;
            dynamic nlm = manager;
            foreach (dynamic network in nlm.GetNetworks(NlmEnumNetworkConnected))
            {
                int category = (int)network.GetCategory();
                if (category == NlmNetworkCategoryPublic) return true;
            }
            return false;
        }
        catch (Exception e) when (e is COMException or InvalidCastException or NotSupportedException
            or Microsoft.CSharp.RuntimeBinder.RuntimeBinderException)
        {
            return false;
        }
        finally
        {
            if (manager != null && Marshal.IsComObject(manager)) Marshal.FinalReleaseComObject(manager);
        }
    }
}
