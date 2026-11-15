using System.Collections.Concurrent;
using System.Diagnostics.Tracing;
using System.Runtime.InteropServices;

namespace GyroPhone.Host;

/// <summary>
/// Diagnostics: one line per .NET memory cleanup (garbage collection) in this program, saying how long it stopped
/// every thread, which kind it was (generation, blocking or background), why it started, and which thread
/// started it. Listens to the runtime's own GC events, on the runtime's event thread, only while Diagnostics is
/// on (off: the events are switched off and nothing runs). The input path is untouched.
/// </summary>
public sealed class GcWatch : EventListener
{
    private const string RuntimeSource = "Microsoft-Windows-DotNETRuntime";
    private const EventKeywords GcKeyword = (EventKeywords)0x1;
    private const int GcStart = 1, GcRestartEEEnd = 3, GcSuspendEEBegin = 9;

    private static readonly ConcurrentDictionary<ulong, string> ThreadNames = new();

    private readonly Action<string> _write;
    private EventSource? _runtime;
    private volatile bool _on;

    // The cleanup under way (written and read on the runtime's event thread only).
    private long _suspendTicks = -1;
    private int _gen = -1, _reason = -1, _type = -1;
    private ulong _startedBy;

    public GcWatch(Action<string> write)
    {
        _write = write;
    }

    /// <summary>Names the calling thread in the lines (gp-rx, gp-overlay, the window thread); Windows only.</summary>
    public static void NameCurrentThread(string name)
    {
        if (!OperatingSystem.IsWindows()) return;
        try { ThreadNames[GetCurrentThreadId()] = name; }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException) { }
    }

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();

    public void SetOn(bool on)
    {
        lock (this)
        {
            if (on == _on) return;
            _on = on;
            if (_runtime == null) return;
            if (on) EnableEvents(_runtime, EventLevel.Informational, GcKeyword);
            else DisableEvents(_runtime);
        }
    }

    protected override void OnEventSourceCreated(EventSource source)
    {
        if (source.Name != RuntimeSource) return;
        lock (this)
        {
            _runtime = source;
            if (_on) EnableEvents(source, EventLevel.Informational, GcKeyword);
        }
    }

    protected override void OnEventWritten(EventWrittenEventArgs e)
    {
        if (!_on) return;
        try
        {
            switch (e.EventId)
            {
                case GcStart:
                    _gen = ToInt(e, "Depth");
                    _reason = ToInt(e, "Reason");
                    _type = ToInt(e, "Type");
                    _startedBy = (ulong)e.OSThreadId;
                    break;
                case GcSuspendEEBegin:
                    _suspendTicks = e.TimeStamp.Ticks;
                    break;
                case GcRestartEEEnd:
                    if (_suspendTicks < 0) break;
                    double ms = (e.TimeStamp.Ticks - _suspendTicks) / (double)TimeSpan.TicksPerMillisecond;
                    _suspendTicks = -1;
                    _write(Line(ms));
                    break;
            }
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            // A runtime that names its fields differently loses the details, never the program.
        }
    }

    private string Line(double ms)
    {
        long heapMb = GC.GetGCMemoryInfo().HeapSizeBytes >> 20;
        string who = _startedBy == 0 ? "?" : ThreadNames.TryGetValue(_startedBy, out var n) ? n : $"thread {_startedBy}";
        return $"memory cleanup {ms:0.00} ms: gen {(_gen < 0 ? "?" : _gen)}, {TypeName(_type)}, why: {ReasonName(_reason)}, "
            + $"heap {heapMb} MB, started by {who}";
    }

    private static int ToInt(EventWrittenEventArgs e, string name)
    {
        int i = e.PayloadNames?.IndexOf(name) ?? -1;
        return i >= 0 && e.Payload?[i] is { } v ? Convert.ToInt32(v) : -1;
    }

    private static string TypeName(int t) => t switch
    {
        0 => "blocking",
        1 => "background",
        2 => "blocking during a background one",
        _ => "?",
    };

    private static string ReasonName(int r) => r switch
    {
        0 => "new memory",
        1 => "asked for (GC.Collect)",
        2 => "low memory",
        3 => "empty",
        4 => "large new memory",
        5 => "out of space",
        6 => "out of space (large)",
        7 => "asked for, not forced",
        8 => "internal",
        9 => "asked for (low memory)",
        10 => "asked for (compacting)",
        11 or 13 => "low memory (host)",
        12 => "pinned",
        _ => r.ToString(),
    };
}
