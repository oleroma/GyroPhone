using System.Diagnostics;
using System.Runtime.InteropServices;
using GyroPhone.Host.Perf;

namespace GyroPhone.Windows;

/// <summary>
/// The PC's processor load from Windows' performance counters (by their English names, so it works in any
/// language): all processors' busy time, their time in DPCs and interrupts, the busiest processor's, and the
/// busiest programs (the Process counters: Windows' own process list, no program is opened). Needs no admin rights.
/// </summary>
public sealed class SystemLoad : ISystemLoad
{
    private IntPtr _query;
    private IntPtr _cpu, _dpc, _int, _proc;
    private readonly int _cores = Math.Max(1, Environment.ProcessorCount);
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
            return;
        }
        if (PdhAddEnglishCounterW(_query, @"\Process(*)\% Processor Time", IntPtr.Zero, out _proc) != 0)
        {
            _proc = IntPtr.Zero;
            log("process counters unavailable: no busy-program warning");
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
        return new LoadSample(cpu, dpcTotal, intTotal, busiest, Top());
    }

    /// <summary>The 6 busiest programs, % of the whole PC, each program's processes ("java", "java#1") together.</summary>
    private ProgramLoad[]? Top()
    {
        if (_proc == IntPtr.Zero) return null;
        var by = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        foreach (var (instance, v) in Array(_proc))
        {
            if (v <= 0) continue;
            int hash = instance.IndexOf('#');
            string name = hash > 0 ? instance[..hash] : instance;
            if (name is "_Total" or "Idle") continue;
            by[name] = by.GetValueOrDefault(name) + v / _cores;
        }
        return [.. by.OrderByDescending(x => x.Value).Take(6).Select(x => new ProgramLoad(x.Key, x.Value))];
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
        size += 4096; // the process list can grow between the two calls
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
