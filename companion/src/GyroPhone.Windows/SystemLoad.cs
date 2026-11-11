using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Principal;
using GyroPhone.Host.Perf;

namespace GyroPhone.Windows;

/// <summary>
/// The PC's processor load from Windows' performance counters (by their English names, so it works in any
/// language): all processors' busy time, their time in DPCs and interrupts, and the busiest processor's.
/// Needs no admin rights.
/// </summary>
public sealed class SystemLoad : ISystemLoad
{
    private IntPtr _query;
    private IntPtr _cpu, _dpc, _int;
    private bool _primed;

    public SystemLoad(Action<string> log)
    {
        if (PdhOpenQueryW(null, IntPtr.Zero, out _query) != 0)
        {
            _query = IntPtr.Zero;
            log("performance counters unavailable");
            return;
        }
        int e = PdhAddEnglishCounterW(_query, @"\Processor(_Total)\% Processor Time", IntPtr.Zero, out _cpu)
            | PdhAddEnglishCounterW(_query, @"\Processor(*)\% DPC Time", IntPtr.Zero, out _dpc)
            | PdhAddEnglishCounterW(_query, @"\Processor(*)\% Interrupt Time", IntPtr.Zero, out _int);
        if (e != 0)
        {
            log($"performance counters not added ({e:x})");
            Dispose();
        }
    }

    public LoadSample? Sample()
    {
        if (_query == IntPtr.Zero || PdhCollectQueryData(_query) != 0) return null;
        if (!_primed)
        {
            _primed = true; // rates need two samples
            return null;
        }
        if (!Single(_cpu, out double cpu)) return null;
        var dpc = Array(_dpc);
        var irq = Array(_int);
        double dpcTotal = dpc.GetValueOrDefault("_Total"), intTotal = irq.GetValueOrDefault("_Total"), busiest = 0;
        foreach (var (name, v) in dpc)
            if (name != "_Total") busiest = Math.Max(busiest, v + irq.GetValueOrDefault(name));
        return new LoadSample(cpu, dpcTotal, intTotal, busiest);
    }

    private static bool Single(IntPtr counter, out double value)
    {
        value = 0;
        if (PdhGetFormattedCounterValue(counter, FmtDouble, out _, out var v) != 0 || v.Status != 0) return false;
        value = v.Value;
        return true;
    }

    private static unsafe Dictionary<string, double> Array(IntPtr counter)
    {
        var map = new Dictionary<string, double>();
        uint size = 0;
        PdhGetFormattedCounterArrayW(counter, FmtDouble, ref size, out _, IntPtr.Zero);
        if (size == 0) return map;
        var buf = Marshal.AllocHGlobal((int)size);
        try
        {
            if (PdhGetFormattedCounterArrayW(counter, FmtDouble, ref size, out uint count, buf) != 0) return map;
            for (int i = 0; i < count; i++)
            {
                var item = (Item*)(buf + i * sizeof(Item));
                if (item->Value.Status != 0) continue;
                map[Marshal.PtrToStringUni(item->Name) ?? ""] = item->Value.Value;
            }
        }
        finally
        {
            Marshal.FreeHGlobal(buf);
        }
        return map;
    }

    public void Dispose()
    {
        if (_query != IntPtr.Zero) PdhCloseQuery(_query);
        _query = IntPtr.Zero;
    }

    private const uint FmtDouble = 0x00000200;

    [StructLayout(LayoutKind.Sequential)]
    private struct FmtValue
    {
        public uint Status;
        public double Value;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Item
    {
        public IntPtr Name;
        public FmtValue Value;
    }

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern int PdhOpenQueryW(string? source, IntPtr user, out IntPtr query);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern int PdhAddEnglishCounterW(IntPtr query, string path, IntPtr user, out IntPtr counter);

    [DllImport("pdh.dll")]
    private static extern int PdhCollectQueryData(IntPtr query);

    [DllImport("pdh.dll")]
    private static extern int PdhGetFormattedCounterValue(IntPtr counter, uint format, out uint type, out FmtValue value);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern int PdhGetFormattedCounterArrayW(IntPtr counter, uint format, ref uint size, out uint count, IntPtr items);

    [DllImport("pdh.dll")]
    private static extern int PdhCloseQuery(IntPtr query);
}

/// <summary>The window in front and process names.</summary>
public sealed class Foreground : IForeground
{
    public int ForegroundPid()
    {
        nint w = GetForegroundWindow();
        if (w == 0) return 0;
        GetWindowThreadProcessId(w, out uint pid);
        return (int)pid;
    }

    public string? NameOf(int pid)
    {
        try
        {
            using var p = Process.GetProcessById(pid);
            return p.ProcessName;
        }
        catch (Exception e) when (e is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            return null;
        }
    }

    [DllImport("user32.dll")]
    private static extern nint GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(nint hWnd, out uint processId);
}

/// <summary>
/// Watching frames (<see cref="FrameWatch"/>) needs admin rights or Windows' "Performance Log Users" group.
/// Adding the user to the group takes one admin prompt, then works without admin from the next sign-in.
/// </summary>
public static class PerfPermission
{
    /// <summary>The group's well-known SID (its name depends on the Windows language).</summary>
    public const string GroupSid = "S-1-5-32-559";

    /// <summary>This process already has the right: run as admin, or the group was in place at sign-in.</summary>
    public static bool Have
    {
        get
        {
            try
            {
                using var id = WindowsIdentity.GetCurrent();
                return ElevationProbe.IsSelfElevated || id.Groups?.Any(g => g.Value == GroupSid) == true;
            }
            catch (Exception)
            {
                return false;
            }
        }
    }

    /// <summary>
    /// Adds this user to the group with one admin prompt. True when it was added (or already was);
    /// false when the prompt was declined or it failed.
    /// </summary>
    public static bool Grant(Action<string> log)
    {
        try
        {
            using var id = WindowsIdentity.GetCurrent();
            string user = id.User?.Value ?? throw new InvalidOperationException("no user SID");
            // By SIDs, so neither the group's nor the user's name matters.
            string script = "$g=[Security.Principal.SecurityIdentifier]'" + GroupSid + "';" +
                "$u=[Security.Principal.SecurityIdentifier]'" + user + "';" +
                "$gn=$g.Translate([Security.Principal.NTAccount]).Value.Split('\\')[-1];" +
                "$un=$u.Translate([Security.Principal.NTAccount]).Value;" +
                "net localgroup \"$gn\" \"$un\" /add; if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 2) { exit $LASTEXITCODE }; exit 0";
            var psi = new ProcessStartInfo("powershell.exe", $"-NoProfile -NonInteractive -WindowStyle Hidden -Command \"{script.Replace("\"", "\\\"")}\"")
            {
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            using var p = Process.Start(psi);
            if (p == null) return false;
            p.WaitForExit(30_000);
            log($"performance log users: added, exit {p.ExitCode}");
            return p.ExitCode == 0;
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return false; // prompt declined
        }
        catch (Exception e)
        {
            log($"performance log users: {e.GetType().Name}: {e.Message}");
            return false;
        }
    }
}
