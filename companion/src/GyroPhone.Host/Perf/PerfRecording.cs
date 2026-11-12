using System.Globalization;
using System.Text;

namespace GyroPhone.Host.Perf;

/// <summary>
/// How busy the PC's processors were over one second: all of them together, the time they spent in drivers
/// (DPCs and interrupts, where network and Wi-Fi drivers work), and the busiest single processor's driver time.
/// </summary>
public readonly record struct LoadSample(double CpuPct, double DpcPct, double InterruptPct, double BusiestCoreDriverPct, ProgramLoad[]? Top = null)
{
    public double DriverPct => DpcPct + InterruptPct;
}

/// <summary>One program's share of the whole PC's processors over a second (all its processes together), %.</summary>
public readonly record struct ProgramLoad(string Name, double Pct);

/// <summary>
/// Whether programs other than the game and GyroPhone keep the PC busy, such as a build, a virus scan or an
/// update: they can cause hitches a link test would wrongly blame on the link.
/// </summary>
public static class PcBusy
{
    /// <summary>Other programs together use this share of the whole PC or more: busy.</summary>
    public const double Pct = 20;

    /// <summary>Not counted: the idle time, the game, GyroPhone itself and the desktop compositor (it shows the game).</summary>
    private static bool Skip(string name, string? game, string? self) =>
        name is "_Total" or "Idle" ||
        string.Equals(name, "dwm", StringComparison.OrdinalIgnoreCase) ||
        (!string.IsNullOrEmpty(game) && string.Equals(name, game, StringComparison.OrdinalIgnoreCase)) ||
        (!string.IsNullOrEmpty(self) && string.Equals(name, self, StringComparison.OrdinalIgnoreCase));

    /// <summary>The busy programs ("java 45%, dotnet 12%") and the busiest one's name, or null when the PC isn't busy.</summary>
    public static (string Text, string Top)? Of(LoadSample load, string? game, string? self)
    {
        if (load.Top is not { Length: > 0 } top) return null;
        var others = top.Where(p => !Skip(p.Name, game, self)).OrderByDescending(p => p.Pct).ToList();
        if (others.Sum(p => p.Pct) < Pct) return null;
        return (string.Join(", ", others.Where(p => p.Pct >= 2).Take(3).Select(p =>
            string.Create(CultureInfo.InvariantCulture, $"{p.Name} {p.Pct:0}%"))), others[0].Name);
    }
}

/// <summary>
/// A finished test record: one row of the Performance page's results. <see cref="Text"/> is its
/// second-by-second text, shown in results.html; <see cref="File"/> is that page (older versions wrote a text
/// file per record instead).
/// </summary>
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
    string? File = null,
    string Path = "",
    double LatP50Ms = -1,
    double LatP99Ms = -1,
    double JitterMs = 0,
    int Bursts = 0,
    bool Frames = true,
    int BusySeconds = 0,
    string BusyTop = "",
    string? Text = null)
{
    /// <summary>Jumps per minute of the game in front.</summary>
    public double JumpsPerMin => GameSeconds > 0 ? Jumps * 60 / GameSeconds : 0;
}

/// <summary>
/// A stretch of link trouble in a record: seconds in a row (a quiet second between two still joins them)
/// with lost or late packets, phone pauses or let-go keys.
/// </summary>
public sealed record LinkBurst(long FromS, long ToS, DateTime At, int Lost, int Late, double MaxLateMs, int Gaps, int Releases, int Jumps, string Busy = "")
{
    public long Seconds => ToS - FromS + 1;
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
        /// <summary>The busy programs that second (<see cref="PcBusy"/>), or null.</summary>
        public string? Busy, BusyTop;
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

    private readonly List<string> _paths = [];

    /// <summary>The way the phone's packets went (detected), noted once a second: each change is kept.</summary>
    public void NotePath(string? path)
    {
        if (string.IsNullOrEmpty(path)) return;
        lock (_lock)
            if (_paths.Count == 0 || _paths[^1] != path) _paths.Add(path);
    }

    /// <summary>The paths seen, in order ("Phone hotspot", or "Router Wi-Fi → Phone hotspot" when it changed).</summary>
    public string Path
    {
        get { lock (_lock) return string.Join(" → ", _paths); }
    }

    /// <summary>The FPS overlay watched the game's frames at some point of the record.</summary>
    public bool HadFrames
    {
        get { lock (_lock) return _ms.Count > 0; }
    }

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

    /// <summary>GyroPhone's own process name, left out of <see cref="PcBusy"/>.</summary>
    public string? Self { get; set; }

    /// <summary>Notes a second's load; <paramref name="front"/> is the program in front (the game when known), not counted as busy.</summary>
    public void AddLoad(LoadSample load, long atUs, string? front = null)
    {
        lock (_lock)
        {
            var s = SecondAt(atUs);
            s.Load = load;
            var busy = PcBusy.Of(load, Game.Length > 0 ? Game : front, Self);
            s.Busy = busy?.Text;
            s.BusyTop = busy?.Top;
        }
    }

    private Second SecondAt(long atUs)
    {
        long k = Math.Max(0, (atUs - StartUs) / 1_000_000);
        if (!_seconds.TryGetValue(k, out var s)) _seconds[k] = s = new Second();
        return s;
    }

    /// <summary>The link trouble in <paramref name="bySecond"/> as bursts, oldest first.</summary>
    private List<LinkBurst> Bursts(SortedDictionary<long, LinkSecond> bySecond)
    {
        var list = new List<LinkBurst>();
        LinkBurst? cur = null;
        foreach (var (k, l) in bySecond)
        {
            if (!l.Trouble) continue;
            int jumps = _seconds.TryGetValue(k, out var fs) ? fs.Jumps : 0;
            if (cur != null && k - cur.ToS <= 2)
                cur = cur with
                {
                    ToS = k, Lost = cur.Lost + l.Lost, Late = cur.Late + l.Late, MaxLateMs = Math.Max(cur.MaxLateMs, l.MaxLateMs),
                    Gaps = cur.Gaps + l.Gaps, Releases = cur.Releases + l.Releases, Jumps = cur.Jumps + jumps,
                };
            else
            {
                if (cur != null) list.Add(cur);
                cur = new LinkBurst(k, k, Start.AddSeconds(k), l.Lost, l.Late, l.MaxLateMs, l.Gaps, l.Releases, jumps);
            }
        }
        if (cur != null) list.Add(cur);
        // The busy programs in or just before each burst (a build's spike can start a moment earlier).
        for (int i = 0; i < list.Count; i++)
        {
            var b = list[i];
            var busy = _seconds.Where(x => x.Key >= b.FromS - 1 && x.Key <= b.ToS && x.Value.Busy != null).Select(x => x.Value.Busy!).FirstOrDefault();
            if (busy != null) list[i] = b with { Busy = busy };
        }
        return list;
    }

    private static double Median(List<double> v)
    {
        if (v.Count == 0) return -1;
        v.Sort();
        return v[v.Count / 2];
    }

    /// <summary>The record's row and its per-second text (the file the Results table links to).</summary>
    public (PerfRun Run, string Text) Finish(long endUs, IReadOnlyList<LinkSecond> link)
    {
        lock (_lock)
        {
            double fps = _sumMs > 0 ? _ms.Count * 1000 / _sumMs : 0;
            bool frames = _ms.Count > 0;
            var loads = _seconds.Values.Where(s => s.Load != null).Select(s => s.Load!.Value).ToList();
            double Avg(Func<LoadSample, double> f) => loads.Count > 0 ? loads.Average(f) : 0;
            var linkBySecond = new SortedDictionary<long, LinkSecond>();
            // A link second ends about a second after its start: keyed by the nearest whole second, minus that one.
            foreach (var l in link) linkBySecond[Math.Max(0, (long)Math.Round((l.AtUs - StartUs) / 1e6) - 1)] = l;
            var bursts = Bursts(linkBySecond);
            var p50 = link.Where(l => l.LatP50Ms >= 0).Select(l => (double)l.LatP50Ms).ToList();
            var p99 = link.Where(l => l.LatP99Ms >= 0).Select(l => (double)l.LatP99Ms).ToList();
            double jitter = link.Count > 0 ? link.Average(l => l.JitterMs) : 0;
            string path = string.Join(" → ", _paths);
            var busySeconds = _seconds.Values.Where(x => x.Busy != null).ToList();
            // The program busy most often.
            string busyTop = busySeconds.GroupBy(x => x.BusyTop!).OrderByDescending(g => g.Count()).Select(g => g.Key).FirstOrDefault() ?? "";
            var run = new PerfRun(Name, Link, Start, (endUs - StartUs) / 1e6, Game, _sumMs / 1000, fps, FrameStats.Low1Fps([.. _ms]),
                _jumps, _worst, link.Sum(l => l.Lost), link.Sum(l => l.Late), link.Count > 0 ? link.Max(l => l.MaxLateMs) : 0,
                link.Sum(l => l.Gaps), link.Sum(l => l.Releases), Avg(l => l.CpuPct), Avg(l => l.DriverPct), Avg(l => l.BusiestCoreDriverPct),
                null, path, Median(p50), Median(p99), jitter, bursts.Count, frames, busySeconds.Count, busyTop);

            var sb = new StringBuilder();
            var c = CultureInfo.InvariantCulture;
            sb.AppendLine(c, $"GyroPhone link test '{Name}', {Start:yyyy-MM-dd HH:mm:ss}, {run.Seconds:0} s");
            sb.AppendLine(c, $"path: {(path.Length > 0 ? path : Link)}");
            sb.AppendLine(c, $"link: lost {run.Lost}, late {run.Late} (worst {run.MaxLateMs:0} ms), phone pauses {run.Gaps}, let go {run.Releases}, trouble bursts {bursts.Count}");
            if (run.LatP50Ms >= 0)
                sb.AppendLine(c, $"latency: typical {run.LatP50Ms:0.0} ms, slowest 1% {run.LatP99Ms:0.0} ms, jitter {run.JitterMs:0.0} ms");
            if (frames)
                sb.AppendLine(c, $"game {Game}, in front {run.GameSeconds:0} s: avg {run.AvgFps:0.0} fps, 1% low {run.Low1Fps:0.0} fps, " +
                    $"jumps {run.Jumps} ({run.JumpsPerMin:0.0}/min), worst frame {run.WorstMs:0.0} ms");
            else sb.AppendLine("game frames: not watched (the FPS overlay was off)");
            sb.AppendLine(c, $"PC: CPU {run.CpuPct:0}%, driver time {run.DriverPct:0.0}%, busiest core driver time {run.BusiestCoreDriverPct:0.0}%");
            if (busySeconds.Count > 0)
                sb.AppendLine(c, $"WARNING: the PC was busy with other programs (together {PcBusy.Pct:0}% of the PC or more) for {busySeconds.Count} s, mostly {busyTop}. A build, scan or update can cause hitches: see the busy column.");
            sb.AppendLine();
            if (bursts.Count > 0)
            {
                sb.AppendLine("Link trouble (at = clock time, :ss = second of the minute, to spot a repeating pattern):");
                sb.AppendLine("  at        :ss  from_s  secs  lost  late maxlate_ms pauses let_go game_jumps  pc_busy");
                foreach (var b in bursts)
                    sb.AppendLine(c, $"  {b.At:HH:mm:ss}  :{b.At:ss} {b.FromS,7} {b.Seconds,5} {b.Lost,5} {b.Late,5} {b.MaxLateMs,10:0} {b.Gaps,6} {b.Releases,6} {(frames ? b.Jumps.ToString(c) : "-"),10}  {(b.Busy.Length > 0 ? b.Busy : "-")}");
                sb.AppendLine();
            }
            else sb.AppendLine("Link trouble: none.").AppendLine();
            sb.AppendLine("Per second. fps: while the game was in front, with the FPS overlay on (a second it was in front only partly has few frames).");
            sb.AppendLine("  s  :ss |   rx lost late maxlate_ms gaps rel  lat_ms p99_ms jitter |  frames   fps  worst_ms jumps |  cpu  dpc  int core_drv | busy");
            long last = Math.Max(_seconds.Count > 0 ? _seconds.Keys.Max() : 0, linkBySecond.Count > 0 ? linkBySecond.Keys.Max() : 0);
            for (long k = 0; k <= last; k++)
            {
                _seconds.TryGetValue(k, out var s);
                bool hasLink = linkBySecond.TryGetValue(k, out var l);
                if (s == null && !hasLink) continue;
                sb.Append(c, $"{k,3}  :{Start.AddSeconds(k):ss} | ");
                if (hasLink)
                {
                    sb.Append(c, $"{l.Rx,4} {l.Lost,4} {l.Late,4} {l.MaxLateMs,10:0} {l.Gaps,4} {l.Releases,3} ");
                    sb.Append(l.LatP50Ms >= 0 ? l.LatP50Ms.ToString("0.0", c).PadLeft(7) : "      -").Append(' ');
                    sb.Append(l.LatP99Ms >= 0 ? l.LatP99Ms.ToString("0.0", c).PadLeft(6) : "     -").Append(' ');
                    sb.Append(l.JitterMs.ToString("0.0", c).PadLeft(6));
                }
                else sb.Append("   -    -    -          -    -   -       -      -      -");
                sb.Append(" | ");
                if (s != null && s.Frames > 0)
                    sb.Append(c, $"{s.Frames,7} {s.Frames * 1000 / s.SumMs,5:0} {s.WorstMs,9:0.0} {s.Jumps,5}");
                else sb.Append("      -     -         -     -");
                sb.Append(" | ");
                if (s?.Load is { } ld) sb.Append(c, $"{ld.CpuPct,4:0} {ld.DpcPct,4:0.0} {ld.InterruptPct,4:0.0} {ld.BusiestCoreDriverPct,8:0.0}");
                if (s?.Busy != null) sb.Append(" | ").Append(s.Busy);
                sb.AppendLine();
            }
            return (run, sb.ToString());
        }
    }
}
