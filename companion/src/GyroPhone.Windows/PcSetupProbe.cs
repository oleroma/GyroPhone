using System.Net;
using System.Runtime.InteropServices;
using GyroPhone.Host.Perf;

namespace GyroPhone.Windows;

/// <summary>
/// The PC's setup bits for HOST_STATUS (<see cref="PcSetup"/>): the adapter on the phone's subnet, that adapter's Wi-Fi
/// band and signal (Native Wi-Fi API, read only), and the PC's power (on battery, energy saver, power mode). Changes
/// nothing on the PC. The band needs the connected access point's address, which Windows 11 24H2 and later give only
/// with location access; without it the band stays unknown and no 2.4 GHz tip shows.
/// </summary>
public static class PcSetupProbe
{
    /// <summary>The Wi-Fi radio is asked again only this often (probes run once a second).</summary>
    private const long RadioRequeryMs = 5_000;
    private const int OpcodeCurrentConnection = 7;
    private const int OpcodeRssi = 0x10000102;
    private const int BssTypeAny = 3;
    /// <summary>sizeof(WLAN_BSS_ENTRY), and the offsets of its BSSID and centre frequency (kHz).</summary>
    private const int BssEntrySize = 360, BssBssidAt = 40, BssFreqAt = 92;
    /// <summary>WLAN_CONNECTION_ATTRIBUTES: the BSSID and the signal quality (0..100) in its association attributes.</summary>
    private const int ConnBssidAt = 560, ConnQualityAt = 576, ConnMinSize = 580;
    /// <summary>Power mode "Best power efficiency" (the power slider's overlay).</summary>
    private static readonly Guid BestEfficiency = new("961cc777-2547-4f9d-8174-7d86181b8a7a");

    private static readonly object Gate = new();
    private static IntPtr s_wlan;
    private static bool s_wlanFailed;
    private static string s_radioFor = "";
    private static long s_radioAt = long.MinValue;
    private static PcWifiRadio s_radio;

    /// <summary>The setup bits for a session with a phone at <paramref name="phone"/> (null: only the PC's power).</summary>
    public static byte Read(IPAddress? phone)
    {
        var adapter = LinkPaths.AdapterFor(phone);
        var radio = adapter is { Wireless: true, Hotspot: false } ? Radio(adapter.Id) : default;
        Power(out bool battery, out bool saving);
        return PcSetup.Bits(adapter, radio, battery, saving);
    }

    private static void Power(out bool onBattery, out bool saving)
    {
        onBattery = saving = false;
        try
        {
            if (GetSystemPowerStatus(out var s))
            {
                onBattery = s.ACLineStatus == 0;
                saving = (s.SystemStatusFlag & 1) != 0;
            }
            if (PowerGetEffectiveOverlayScheme(out var overlay) == 0 && overlay == BestEfficiency) saving = true;
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
        }
    }

    private static PcWifiRadio Radio(string id)
    {
        lock (Gate)
        {
            long now = Environment.TickCount64;
            if (id == s_radioFor && now - s_radioAt < RadioRequeryMs) return s_radio;
            PcWifiRadio r = default;
            try { r = QueryRadio(id); }
            catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException) { s_wlanFailed = true; }
            s_radioFor = id;
            s_radioAt = now;
            s_radio = r;
            return r;
        }
    }

    private static PcWifiRadio QueryRadio(string id)
    {
        if (!Guid.TryParse(id, out var guid)) return default;
        if (s_wlan == IntPtr.Zero)
        {
            if (s_wlanFailed) return default;
            if (WlanOpenHandle(2, IntPtr.Zero, out _, out s_wlan) != 0)
            {
                s_wlan = IntPtr.Zero;
                s_wlanFailed = true; // no WLAN service (a desktop without Wi-Fi)
                return default;
            }
        }
        int? rssi = null, freq = null;
        if (WlanQueryInterface(s_wlan, ref guid, OpcodeRssi, IntPtr.Zero, out int size, out IntPtr data, IntPtr.Zero) == 0)
        {
            if (size >= 4) rssi = Marshal.ReadInt32(data);
            WlanFreeMemory(data);
        }
        var bssid = new byte[6];
        if (WlanQueryInterface(s_wlan, ref guid, OpcodeCurrentConnection, IntPtr.Zero, out size, out data, IntPtr.Zero) == 0)
        {
            if (size >= ConnMinSize)
            {
                Marshal.Copy(data + ConnBssidAt, bssid, 0, 6);
                // Signal quality 0..100 is -100..-50 dBm.
                if (rssi is null or >= 0) rssi = Marshal.ReadInt32(data + ConnQualityAt) / 2 - 100;
            }
            WlanFreeMemory(data);
        }
        if (bssid.Any(b => b != 0) && WlanGetNetworkBssList(s_wlan, ref guid, IntPtr.Zero, BssTypeAny, false, IntPtr.Zero, out IntPtr list) == 0)
        {
            try
            {
                int n = Marshal.ReadInt32(list + 4);
                var entry = new byte[6];
                for (int i = 0; i < n; i++)
                {
                    IntPtr e = list + 8 + i * BssEntrySize;
                    Marshal.Copy(e + BssBssidAt, entry, 0, 6);
                    if (!entry.AsSpan().SequenceEqual(bssid)) continue;
                    freq = Marshal.ReadInt32(e + BssFreqAt) / 1000;
                    break;
                }
            }
            finally
            {
                WlanFreeMemory(list);
            }
        }
        return new PcWifiRadio(freq, rssi is < 0 ? rssi : null);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SystemPowerStatus
    {
        public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
        public int BatteryLifeTime, BatteryFullLifeTime;
    }

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemPowerStatus(out SystemPowerStatus status);

    [DllImport("powrprof.dll")]
    private static extern uint PowerGetEffectiveOverlayScheme(out Guid overlay);

    [DllImport("wlanapi.dll")]
    private static extern uint WlanOpenHandle(uint clientVersion, IntPtr reserved, out uint negotiatedVersion, out IntPtr handle);

    [DllImport("wlanapi.dll")]
    private static extern uint WlanQueryInterface(IntPtr handle, ref Guid iface, int opCode, IntPtr reserved, out int dataSize,
        out IntPtr data, IntPtr valueType);

    [DllImport("wlanapi.dll")]
    private static extern uint WlanGetNetworkBssList(IntPtr handle, ref Guid iface, IntPtr ssid, int bssType,
        [MarshalAs(UnmanagedType.Bool)] bool securityEnabled, IntPtr reserved, out IntPtr list);

    [DllImport("wlanapi.dll")]
    private static extern void WlanFreeMemory(IntPtr memory);
}
