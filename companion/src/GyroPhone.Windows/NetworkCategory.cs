using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;

namespace GyroPhone.Windows;

/// <summary>
/// Whether a connected network the phone could use is in the Public category, through the late-bound
/// Network List Manager (no interop assembly). Public networks block discovery under the default
/// firewall rules. Only Wi-Fi and wired Ethernet adapters count: USB tethering has its own firewall rule,
/// and virtual (Hyper-V, WSL, VM), VPN and Bluetooth adapters are often Public but never carry the phone.
/// </summary>
public static class NetworkCategory
{
    private static readonly Guid NetworkListManagerClsid = new("DCB00C01-570F-4A9B-8D69-199FDBA5723B");
    private const int NlmNetworkCategoryPublic = 0;
    /// <summary>The category rarely changes, so it is asked again only this often, or when an address changes.</summary>
    private const long RequeryMs = 5_000;

    /// <summary>Adapter descriptions or names that are never the phone's network.</summary>
    private static readonly string[] NotThePhone =
    [
        "Virtual", "Hyper-V", "vEthernet", "VMware", "VirtualBox", "Remote NDIS", "RNDIS", "TAP-", "Wintun",
        "WireGuard", "VPN", "Bluetooth", "Loopback",
    ];

    public const string SettingsUri = "ms-settings:network-status";

    private static readonly Stopwatch Clock = Stopwatch.StartNew();
    private static long _lastQueryMs = long.MinValue;
    private static bool _lastResult;
    private static int _addressChanged, _subscribed;

    /// <summary>True when a connected Wi-Fi or Ethernet network is Public. False when none is or the query fails.</summary>
    public static bool IsAnyConnectedPublic()
    {
        if (Interlocked.Exchange(ref _subscribed, 1) == 0)
        {
            try
            {
                NetworkChange.NetworkAddressChanged += (_, _) => Volatile.Write(ref _addressChanged, 1);
            }
            catch (NetworkInformationException)
            {
                // no change events: the timed re-query still runs
            }
        }
        long now = Clock.ElapsedMilliseconds;
        if (Interlocked.Exchange(ref _addressChanged, 0) == 0 && _lastQueryMs != long.MinValue && now - _lastQueryMs < RequeryMs)
            return _lastResult;
        _lastQueryMs = now;
        _lastResult = Query();
        return _lastResult;
    }

    private static bool Query()
    {
        var type = Type.GetTypeFromCLSID(NetworkListManagerClsid, throwOnError: false);
        if (type == null) return false;
        Dictionary<Guid, NetworkInterface> adapters;
        try
        {
            adapters = [];
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
                if (Guid.TryParse(ni.Id, out var id)) adapters[id] = ni;
        }
        catch (NetworkInformationException)
        {
            adapters = [];
        }
        object? manager = null, connections = null;
        try
        {
            manager = Activator.CreateInstance(type);
            if (manager == null) return false;
            dynamic nlm = manager;
            connections = nlm.GetNetworkConnections();
            foreach (object connection in (dynamic)connections)
            {
                object? network = null;
                try
                {
                    var c = (INetworkConnection)connection;
                    if (c.IsConnected() == 0) continue;
                    // An adapter the list doesn't know is counted, as before.
                    if (adapters.TryGetValue(c.GetAdapterId(), out var ni) && !MayCarryThePhone(ni)) continue;
                    network = c.GetNetwork();
                    if ((int)((dynamic)network).GetCategory() == NlmNetworkCategoryPublic) return true;
                }
                finally
                {
                    Release(network);
                    Release(connection);
                }
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
            Release(connections);
            if (manager != null && Marshal.IsComObject(manager)) Marshal.FinalReleaseComObject(manager);
        }
    }

    /// <summary>Wi-Fi, or wired Ethernet that isn't a virtual, tethering, VPN or Bluetooth adapter.</summary>
    private static bool MayCarryThePhone(NetworkInterface ni)
    {
        if (ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211) return true;
        if (ni.NetworkInterfaceType is not (NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet
            or NetworkInterfaceType.FastEthernetT or NetworkInterfaceType.FastEthernetFx or NetworkInterfaceType.Ethernet3Megabit))
            return false;
        foreach (var word in NotThePhone)
            if (ni.Description.Contains(word, StringComparison.OrdinalIgnoreCase) || ni.Name.Contains(word, StringComparison.OrdinalIgnoreCase))
                return false;
        return true;
    }

    private static void Release(object? o)
    {
        if (o != null && Marshal.IsComObject(o)) Marshal.ReleaseComObject(o);
    }

    /// <summary>The start of INetworkConnection (netlistmgr.h), for the adapter id, which IDispatch can't return.</summary>
    [ComImport, Guid("DCB00005-570F-4A9B-8D69-199FDBA5723B"), InterfaceType(ComInterfaceType.InterfaceIsDual)]
    private interface INetworkConnection
    {
        [return: MarshalAs(UnmanagedType.IDispatch)]
        object GetNetwork();

        short IsConnectedToInternet(); // VARIANT_BOOL

        short IsConnected(); // VARIANT_BOOL

        int GetConnectivity();

        Guid GetConnectionId();

        Guid GetAdapterId();
    }
}
