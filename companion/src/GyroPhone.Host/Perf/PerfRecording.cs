using System.Globalization;
using System.Text;

namespace GyroPhone.Host.Perf;

/// <summary>
/// How busy the PC's processors were over one second: all of them together, the time they spent in drivers
/// (DPCs and interrupts, where network and Wi-Fi drivers work), and the busiest single processor's driver time.
/// </summary>
public readonly record struct LoadSample(double CpuPct, double DpcPct, double InterruptPct, double BusiestCoreDriverPct)
{
    public double DriverPct => DpcPct + InterruptPct;
}

/// <summary>A finished test record: one row of the Performance page's results.</summary>
public sealed record PerfRun(
    string Name,
    string Link,
    DateTime Start,
    double Seconds,
    string Game,
    double GameSeconds,
    double AvgFps,
    double Low1Fps,
    int Jumps,
    double WorstMs,
    int Lost,
    int Late,
    double MaxLateMs,
    int Gaps,
    int Releases,
    double CpuPct,
    double DriverPct,
    double BusiestCoreDriverPct,
    string? File = null)
{
    /// <summary>Jumps per minute of the game in front.</summary>
    public double JumpsPerMin => GameSeconds > 0 ? Jumps * 60 / GameSeconds : 0;
}

/// <summary>
/// One test record in progress (docs/spec/pc-companion.md, "Performance"): the frames the game showed while
/// it was in front, the PC's load each second, and at the end the phone link's seconds over the same time.
/// Thread-safe: frames come from the frame watcher, load from the monitor's timer.
/// </summary>
public sealed class PerfRecording
{
    private readonly object _lock = new();
    private readonly List<float> _ms = [];
    private readonly SortedDictionary<long, Second> _seconds = [];
    private int _jumps;
    private float _worst;
    private double _sumMs;

    private sealed class Second
    {
        public int Frames, Jumps;
        public double SumMs;
        public float WorstMs;
        public LoadSample? Load;
    }

    public PerfRecording(string name, string link, long startUs, DateTime start, int seconds)
    {
        Name = name;
        Link = link;
        StartUs = startUs;
        Start = start;
        Seconds = seconds;
    }

    public string Name { get; }
    public string Link { get; }
    public long StartUs { get; }
    public DateTime Start { get; }
    /// <summary>How long it runs, 0 until stopped.</summary>
    public int Seconds { get; }
    /// <summary>The game's name, kept up to date by the monitor.</summary>
    public string Game { get; set; } = "";

    /// <summary>How long the game was in front and showing frames so far, s.</summary>
    public double GameSeconds
    {
        get { lock (_lock) return _sumMs / 1000; }
    }

    public void Add(Frame f)
    {
        lock (_lock)
        {
            _ms.Add(f.Ms);
            _sumMs += f.Ms;
            if (f.Jump) _jumps++;
            if (f.Ms > _worst) _worst = f.Ms;
            var s = SecondAt(f.AtUs);
            s.Frames++;
            s.SumMs += f.Ms;
            if (f.Jump) s.Jumps++;
            if (f.Ms > s.WorstMs) s.WorstMs = f.Ms;
        }
    }

    public void AddLoad(LoadSample load, long atUs)
    {
        lock (_lock) SecondAt(atUs).Load = load;
    }

    private Second SecondAt(long atUs)
    {
        long k = Math.Max(0, (atUs - StartUs) / 1_000_000);
        if (!_seconds.TryGetValue(k, out var s)) _seconds[k] = s = new Second();
        return s;
    }

    /// <summary>The record's row and its per-second text (the file the Results table links to).</summary>
    public (PerfRun Run, string Text) Finish(long endUs, IReadOnlyList<LinkSecond> link)
    {
        lock (_lock)
        {
            double fps = _sumMs > 0 ? _ms.Count * 1000 / _sumMs : 0;
            var loads = _seconds.Values.Where(s => s.Load != null).Select(s => s.Load!.Value).ToList();
            double Avg(Func<LoadSample, double> f) => loads.Count > 0 ? loads.Average(f) : 0;
            var linkBySecond = new Dictionary<long, LinkSecond>();
            foreach (var l in link) linkBySecond[Math.Max(0, (l.AtUs - 1 - StartUs) / 1_000_000)] = l;
            var run = new PerfRun(Name, Link, Start, (endUs - StartUs) / 1e6, Game, _sumMs / 1000, fps, FrameStats.Low1Fps([.. _ms]),
                _jumps, _worst, link.Sum(l => l.Lost), link.Sum(l => l.Late), link.Count > 0 ? link.Max(l => l.MaxLateMs) : 0,
                link.Sum(l => l.Gaps), link.Sum(l => l.Releases), Avg(l => l.CpuPct), Avg(l => l.DriverPct), Avg(l => l.BusiestCoreDriverPct));

            var sb = new StringBuilder();
            var c = CultureInfo.InvariantCulture;
            sb.AppendLine(c, $"GyroPhone performance test '{Name}', {Start:yyyy-MM-dd HH:mm:ss}, link {Link}, game {Game}");
            sb.AppendLine(c, $"{run.Seconds:0} s recorded, game in front {run.GameSeconds:0} s: avg {run.AvgFps:0.0} fps, 1% low {run.Low1Fps:0.0} fps, " +
                $"jumps {run.Jumps} ({run.JumpsPerMin:0.0}/min), worst frame {run.WorstMs:0.0} ms");
            sb.AppendLine(c, $"link: lost {run.Lost}, late {run.Late} (worst {run.MaxLateMs:0} ms), phone pauses {run.Gaps}, releases {run.Releases}");
            sb.AppendLine(c, $"PC: CPU {run.CpuPct:0}%, driver time {run.DriverPct:0.0}%, busiest core driver time {run.BusiestCoreDriverPct:0.0}%");
            sb.AppendLine();
            sb.AppendLine("  s  frames   fps  worst_ms jumps |   rx lost late maxlate_ms gaps rel |  cpu  dpc  int core_drv");
            long last = Math.Max(_seconds.Count > 0 ? _seconds.Keys.Max() : 0, linkBySecond.Count > 0 ? linkBySecond.Keys.Max() : 0);
            for (long k = 0; k <= last; k++)
            {
                _seconds.TryGetValue(k, out var s);
                bool hasLink = linkBySecond.TryGetValue(k, out var l);
                if (s == null && !hasLink) continue;
                sb.Append(c, $"{k,3} ");
                if (s != null && s.Frames > 0)
                    sb.Append(c, $"{s.Frames,6} {s.Frames * 1000 / s.SumMs,5:0} {s.WorstMs,9:0.0} {s.Jumps,5}");
                else sb.Append("     -     -         -     -");
                sb.Append(" | ");
                if (hasLink) sb.Append(c, $"{l.Rx,4} {l.Lost,4} {l.Late,4} {l.MaxLateMs,10:0} {l.Gaps,4} {l.Releases,3}");
                else sb.Append("   -    -    -          -    -   -");
                sb.Append(" | ");
                if (s?.Load is { } ld) sb.Append(c, $"{ld.CpuPct,4:0} {ld.DpcPct,4:0.0} {ld.InterruptPct,4:0.0} {ld.BusiestCoreDriverPct,8:0.0}");
                sb.AppendLine();
            }
            return (run, sb.ToString());
        }
    }
}
