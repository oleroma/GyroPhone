using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using GyroPhone.Host.Perf;
using Microsoft.Win32;

namespace GyroPhone.Windows;

/// <summary>
/// The PC's side of Diagnostics' setup (<see cref="SetupSnapshot"/>): the adapter that gets the phone's packets
/// (model, driver version, link speed, Wi-Fi band, channel and signal), the power plan and mode, mains or battery,
/// the main display's refresh rate, and which "known suspect" programs run. Read only, on the Performance monitor's
/// timer every few seconds while Diagnostics is on; never on an input thread. Changes nothing on the PC.
/// </summary>
public static class PcSetupReader
{
    /// <summary>Programs known to have disturbed input or timing before, or that commonly do (process names, without .exe), and how they are called.</summary>
    private static readonly (string Process, string Name)[] Suspects =
    [
        ("obs64", "OBS"), ("obs32", "OBS"), ("Discord", "Discord"), ("PowerToys", "PowerToys"), ("PowerToys.KeyboardManagerEngine", "PowerToys keyboard"),
        ("lghub", "Logitech G HUB"), ("lghub_agent", "Logitech G HUB"), ("RazerCentralService", "Razer Synapse"), ("Razer Synapse 3", "Razer Synapse"),
        ("NVIDIA Share", "NVIDIA overlay"), ("nvsphelper64", "NVIDIA ShadowPlay"), ("GameBar", "Xbox Game Bar"), ("Medal", "Medal"),
        ("steamwebhelper", "Steam"), ("chrome", "Chrome"), ("msedge", "Edge"), ("firefox", "Firefox"), ("AutoHotkey", "AutoHotkey"),
        ("AutoHotkey64", "AutoHotkey"), ("MSIAfterburner", "MSI Afterburner"), ("RTSS", "RivaTuner"), ("ArmouryCrate", "Armoury Crate"),
        ("Overwolf", "Overwolf"), ("Wallpaper64", "Wallpaper Engine"), ("Spotify", "Spotify"), ("Teams", "Teams"), ("ms-teams", "Teams"),
    ];

    private static readonly Guid BestEfficiency = new("961cc777-2547-4f9d-8174-7d86181b8a7a");
    private static readonly Guid BestPerformance = new("ded574b5-45a0-4f42-8737-46345c09c238");
    private const string NetClass = @"SYSTEM\CurrentControlSet\Control\Class\{4d36e972-e325-11ce-bfc1-08002be10318}";

    public static List<SetupItem> Read(IPAddress? phone)
    {
        var list = new List<SetupItem>(16);
        Adapter(phone, list);
        Power(list);
        if (RefreshHz() is int hz) list.Add(new("pc display", $"{hz} Hz"));
        list.Add(new("pc suspects", RunningSuspects()));
        return list;
    }

    private static void Adapter(IPAddress? phone, List<SetupItem> list)
    {
        var pc = LinkPaths.AdapterFor(phone);
        if (pc == null)
        {
            list.Add(new("pc adapter", phone != null && IPAddress.IsLoopback(phone) ? "USB cable (local socket)" : "-"));
            return;
        }
        string? desc = null;
        long speed = -1;
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.Id != pc.Id) continue;
                desc = ni.Description;
                speed = ni.Speed;
                break;
            }
        }
        catch (Exception e) when (e is NetworkInformationException or PlatformNotSupportedException)
        {
        }
        list.Add(new("pc adapter", desc ?? pc.Name));
        if (DriverOf(pc.Id) is { } driver) list.Add(new("pc driver", driver));
        // A cable's speed is fixed (a 100 Mb link is a bad cable); Wi-Fi's moves all the time.
        if (speed > 0)
            list.Add(new(pc.Wireless ? "pc wifi rate" : "pc link", speed >= 1_000_000_000
                ? string.Create(CultureInfo.InvariantCulture, $"{speed / 1e9:0.#} Gbps")
                : string.Create(CultureInfo.InvariantCulture, $"{speed / 1_000_000} Mbps")));
        if (pc.Wireless && !pc.Hotspot)
        {
            var radio = PcSetupProbe.RadioOf(pc.Id);
            if (radio.FreqMhz is int f)
                list.Add(new("pc wifi", string.Create(CultureInfo.InvariantCulture, $"{(f < 3000 ? "2.4" : f < 5925 ? "5" : "6")} GHz ch {Channel(f)}")));
            if (radio.RssiDbm is int dbm) list.Add(new("pc wifi signal", $"{dbm} dBm"));
        }
    }

    private static int Channel(int mhz) => mhz switch
    {
        2484 => 14,
        < 3000 => (mhz - 2407) / 5,
        < 5925 => (mhz - 5000) / 5,
        _ => (mhz - 5950) / 5,
    };

    /// <summary>The adapter's driver version and date from the network class's registry entry with its id.</summary>
    private static string? DriverOf(string id)
    {
        try
        {
            using var cls = Registry.LocalMachine.OpenSubKey(NetClass);
            if (cls == null) return null;
            foreach (var sub in cls.GetSubKeyNames())
            {
                if (sub.Length != 4) continue;
                using var k = cls.OpenSubKey(sub);
                if (k?.GetValue("NetCfgInstanceId") is not string cfg || !string.Equals(cfg, id, StringComparison.OrdinalIgnoreCase)) continue;
                string? version = k.GetValue("DriverVersion") as string;
                string? date = k.GetValue("DriverDate") as string;
                return version == null ? null : date != null ? $"{version} ({date})" : version;
            }
        }
        catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException)
        {
        }
        return null;
    }

    private static void Power(List<SetupItem> list)
    {
        try
        {
            if (GetSystemPowerStatus(out var s)) list.Add(new("pc power source", s.ACLineStatus == 0 ? "battery" : s.ACLineStatus == 1 ? "mains" : "-"));
            if (PowerGetActiveScheme(IntPtr.Zero, out IntPtr scheme) == 0)
            {
                try
                {
                    var guid = Marshal.PtrToStructure<Guid>(scheme);
                    list.Add(new("pc power plan", SchemeName(guid) ?? guid.ToString()));
                }
                finally
                {
                    LocalFree(scheme);
                }
            }
            if (PowerGetEffectiveOverlayScheme(out var overlay) == 0)
                list.Add(new("pc power mode", overlay == BestEfficiency ? "best power efficiency" : overlay == BestPerformance ? "best performance" : "balanced"));
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
        }
    }

    private static string? SchemeName(Guid scheme)
    {
        uint size = 0;
        if (PowerReadFriendlyName(IntPtr.Zero, ref scheme, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, ref size) != 0 || size == 0 || size > 4096) return null;
        IntPtr buf = Marshal.AllocHGlobal((int)size);
        try
        {
            if (PowerReadFriendlyName(IntPtr.Zero, ref scheme, IntPtr.Zero, IntPtr.Zero, buf, ref size) != 0) return null;
            return Marshal.PtrToStringUni(buf);
        }
        finally
        {
            Marshal.FreeHGlobal(buf);
        }
    }

    /// <summary>The main display's refresh rate, or null.</summary>
    private static int? RefreshHz()
    {
        var dm = new DevMode { dmSize = (short)Marshal.SizeOf<DevMode>() };
        try
        {
            return EnumDisplaySettings(null, -1, ref dm) && dm.dmDisplayFrequency > 1 ? dm.dmDisplayFrequency : null;
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            return null;
        }
    }

    /// <summary>The suspects running now, by name, sorted ("Discord, OBS"), or "none".</summary>
    private static string RunningSuspects()
    {
        var found = new SortedSet<string>(StringComparer.OrdinalIgnoreCase);
        Process[] all;
        try
        {
            all = Process.GetProcesses();
        }
        catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            return "-";
        }
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var p in all)
        {
            try { names.Add(p.ProcessName); }
            catch (InvalidOperationException) { }
            finally { p.Dispose(); }
        }
        foreach (var (process, name) in Suspects)
            if (names.Contains(process)) found.Add(name);
        return found.Count == 0 ? "none" : string.Join(", ", found);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SystemPowerStatus
    {
        public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag;
        public int BatteryLifeTime, BatteryFullLifeTime;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DevMode
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public short dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
        public int dmFields;
        public int dmPositionX, dmPositionY;
        public int dmDisplayOrientation, dmDisplayFixedOutput;
        public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public short dmLogPixels;
        public int dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
        public int dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight;
    }

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemPowerStatus(out SystemPowerStatus status);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr mem);

    [DllImport("powrprof.dll")]
    private static extern uint PowerGetActiveScheme(IntPtr root, out IntPtr scheme);

    [DllImport("powrprof.dll")]
    private static extern uint PowerReadFriendlyName(IntPtr root, ref Guid scheme, IntPtr subGroup, IntPtr setting, IntPtr buffer, ref uint size);

    [DllImport("powrprof.dll")]
    private static extern uint PowerGetEffectiveOverlayScheme(out Guid overlay);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumDisplaySettings(string? device, int mode, ref DevMode devMode);
}
