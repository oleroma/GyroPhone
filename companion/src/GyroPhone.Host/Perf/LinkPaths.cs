using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using GyroPhone.Protocol;

namespace GyroPhone.Host.Perf;

/// <summary>The PC's network adapter that a phone's address belongs to.</summary>
/// <param name="Name">The adapter's name as Windows shows it ("Wi-Fi", "Ethernet", "Local Area Connection* 10").</param>
/// <param name="Hotspot">It is Windows' Mobile hotspot (the Wi-Fi Direct virtual adapter, 192.168.137.x).</param>
public sealed record PcAdapter(string Name, bool Hotspot);

/// <summary>
/// The way the phone's packets go (docs/spec/pc-companion.md, "Performance"): the phone's side from LINK_INFO's
/// path, the PC's from the adapter on the phone's subnet. Named as the Record test's Setup picks are.
/// </summary>
public static class LinkPaths
{
    public const string Router = "Router", PhoneHotspot = "Phone hotspot", PcHotspot = "PC hotspot", Usb = "Wired (USB)", Ethernet = "Ethernet";
    public static readonly string[] Setups = [Router, PhoneHotspot, PcHotspot, Usb, Ethernet];

    /// <summary>The Setup this link is (one of <see cref="Setups"/>), or null when it can't tell.</summary>
    public static string? Setup(LinkInfo l, PcAdapter? pc)
    {
        if ((l.Flags & LinkInfoFlags.Ethernet) != 0 || l.Path == LinkPath.Ethernet) return Ethernet;
        if (l.Path is LinkPath.UsbTether or LinkPath.UsbDirect || (l.Path == LinkPath.Unknown && (l.Flags & LinkInfoFlags.Usb) != 0)) return Usb;
        if (l.Path == LinkPath.Hotspot) return PhoneHotspot;
        if (l.Path == LinkPath.Wifi) return pc is { Hotspot: true } ? PcHotspot : Router;
        return null; // an older phone over Wi-Fi: its own hotspot looks the same
    }

    /// <summary>The phone's side in words: "Phone hotspot", "Router Wi-Fi 5 GHz", "USB cable (direct)"…</summary>
    public static string Phone(LinkInfo l, PcAdapter? pc)
    {
        if ((l.Flags & LinkInfoFlags.Ethernet) != 0 || l.Path == LinkPath.Ethernet) return "Ethernet (phone wired)";
        if (l.Path == LinkPath.UsbDirect || (l.Flags & (LinkInfoFlags.Usb | LinkInfoFlags.UsbDirect)) == (LinkInfoFlags.Usb | LinkInfoFlags.UsbDirect))
            return "USB cable (direct)";
        if (l.Path == LinkPath.UsbTether || (l.Path == LinkPath.Unknown && (l.Flags & LinkInfoFlags.Usb) != 0)) return "USB cable (tethering)";
        if (l.Path == LinkPath.Hotspot) return "Phone hotspot";
        string band = l.Band == 0 ? "" : $" {l.Band} GHz" + (Channel(l.FreqMhz) is int ch ? $" ch {ch}" : "");
        if (l.Path == LinkPath.Wifi) return (pc is { Hotspot: true } ? "PC hotspot" : "Router Wi-Fi") + band;
        return "Wi-Fi" + band;
    }

    /// <summary>Both sides: "Phone hotspot · PC Wi-Fi".</summary>
    public static string Describe(LinkInfo l, PcAdapter? pc) => Phone(l, pc) + (pc != null ? $" · PC {pc.Name}" : "");

    private static int? Channel(int mhz) => mhz switch
    {
        >= 2412 and <= 2472 => (mhz - 2407) / 5,
        2484 => 14,
        >= 5160 and <= 5885 => (mhz - 5000) / 5,
        >= 5955 and <= 7115 => (mhz - 5950) / 5,
        _ => null,
    };

    private static readonly object CacheLock = new();
    private static IPAddress? s_for;
    private static PcAdapter? s_adapter;
    private static long s_at;

    /// <summary>The PC adapter on <paramref name="phone"/>'s subnet (kept 10 s), or null (loopback, none found).</summary>
    public static PcAdapter? AdapterFor(IPAddress? phone)
    {
        if (phone == null || IPAddress.IsLoopback(phone) || phone.AddressFamily != AddressFamily.InterNetwork) return null;
        long now = Environment.TickCount64;
        lock (CacheLock)
            if (phone.Equals(s_for) && now - s_at < 10_000) return s_adapter;
        PcAdapter? found = null;
        try
        {
            uint p = Bits(phone);
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                foreach (var u in ni.GetIPProperties().UnicastAddresses)
                {
                    if (u.Address.AddressFamily != AddressFamily.InterNetwork || u.PrefixLength is <= 0 or > 32) continue;
                    uint mask = u.PrefixLength == 32 ? uint.MaxValue : ~(uint.MaxValue >> u.PrefixLength);
                    uint a = Bits(u.Address);
                    if ((a & mask) != (p & mask)) continue;
                    bool hotspot = ni.Description.Contains("Wi-Fi Direct", StringComparison.OrdinalIgnoreCase) || (a & 0xFFFFFF00) == 0xC0A88900;
                    found = new PcAdapter(ni.Name, hotspot);
                    break;
                }
                if (found != null) break;
            }
        }
        catch (Exception e) when (e is NetworkInformationException or PlatformNotSupportedException)
        {
        }
        lock (CacheLock)
        {
            s_for = phone;
            s_adapter = found;
            s_at = now;
        }
        return found;
    }

    private static uint Bits(IPAddress a)
    {
        var b = a.GetAddressBytes();
        return (uint)(b[0] << 24 | b[1] << 16 | b[2] << 8 | b[3]);
    }
}
