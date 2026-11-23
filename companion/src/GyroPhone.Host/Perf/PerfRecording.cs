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
    string? Text = null,
    double PhoneSendMaxMs = -1,
    double PhoneWakeLateMs = -1,
    double PhoneGapMaxMs = -1,
    int PhoneFailed = 0)
{
    /// <summary>Half the best round trip, ms (the middle of the run's seconds; −1 unknown): the fastest the link went.</summary>
    public double FloorMs { get; init; } = -1;
    /// <summary>The phone's typical round trip, ms (middle of the run's seconds; −1 unknown).</summary>
    public double RttTypMs { get; init; } = -1;
    /// <summary>Over the floor for every packet of the run pooled: middle, slowest 1%, slowest 0.1%, ms (−1 unknown). Needs no clock sync.</summary>
    public double OverP50Ms { get; init; } = -1;
    public double OverP99Ms { get; init; } = -1;
    public double OverP999Ms { get; init; } = -1;
    /// <summary>The same for seconds with movement (most packets moved the mouse or a stick) and still seconds.</summary>
    public double MovingOverP99Ms { get; init; } = -1;
    public double StillOverP99Ms { get; init; } = -1;
    /// <summary>Packets in the run, and those 3 to 15 ms over the floor.</summary>
    public long Packets { get; init; }
    public int Late3 { get; init; }
    /// <summary>Hand-offs to Windows: the slowest, ms; how many took over 2 ms; gp-rx's longest wait for the engine lock, ms.</summary>
    public double HandOffMaxMs { get; init; } = -1;
    public int SlowHandOffs { get; init; }
    public double LockWaitMaxMs { get; init; } = -1;
    /// <summary>The PC stall watch: its latest wake, ms (−1 not running), and wakes 2 ms or more late.</summary>
    public double StallMaxMs { get; init; } = -1;
    public int Stalls { get; init; }
    /// <summary>Late packets by where they waited: on the way, inside the PC, the phone paused.</summary>
    public int HeldAir { get; init; }
    public int HeldPc { get; init; }
    public int HeldPhone { get; init; }
    /// <summary>
    /// Of the ones that waited "on the way", those in a second the whole PC's CPU was at <see cref="PcBusyCpuPct"/>% or more:
    /// with the CPU pinned they most likely waited inside the PC (Windows' network work, the input program's
    /// receive), which the stall watch, at above-normal priority, doesn't see. Not counted in <see cref="HeldAir"/>.
    /// </summary>
    public int HeldPcBusy { get; init; }

    /// <summary>A late packet in a second the whole PC is this busy is put under <see cref="HeldPcBusy"/>, not on the way.</summary>
    public const double PcBusyCpuPct = 95;
    /// <summary>Latencies that came out below zero (a wrong clock offset).</summary>
    public int NegLat { get; init; }
    /// <summary>The setup at Save (<see cref="SetupSnapshot"/>), and its changes during the run ("20:31:05 pc wifi ch 48 → ch 100").</summary>
    public string Setup { get; init; } = "";
    public string SetupChanges { get; init; } = "";
    /// <summary>Set when the floor differs clearly from the last run on the same setup (<see cref="FloorBook"/>).</summary>
    public string FloorWarning { get; init; } = "";
    /// <summary>The game the play tags went by (<see cref="PlayTags"/>), "" none; guessed when the FPS overlay didn't name it.</summary>
    public string PlayGame { get; init; } = "";
    public bool PlayGameGuessed { get; init; }
    /// <summary>The link over the seconds tagged play (the game in front, input moving), game (in front, still) and the rest; null none.</summary>
    public PlaySlice? Playing { get; init; }
    public PlaySlice? GameStill { get; init; }
    public PlaySlice? Other { get; init; }

    /// <summary>Jumps per minute of the game in front.</summary>
    public double JumpsPerMin => GameSeconds > 0 ? Jumps * 60 / GameSeconds : 0;
}

/// <summary>The link numbers over some of a record's seconds (<see cref="PlayTags"/>): the same as the record's own, counted only there.</summary>
public sealed record PlaySlice(int Seconds, long Packets, int Lost, int Late, double MaxLateMs, int Gaps, int Releases, int Late3,
    double OverP50Ms, double OverP99Ms, double OverP999Ms, int HeldAir, int HeldPc, int HeldPhone, double RttTypMs, double CpuPct)
{
    /// <summary>Of the "on the way" ones, those in a second the PC's CPU was pinned (<see cref="PerfRun.HeldPcBusy"/>); not in <see cref="HeldAir"/>.</summary>
    public int HeldPcBusy { get; init; }
}

/// <summary>
/// Tags each second of a record so a game session can be told from the rest: <c>play</c> when the game is in front
/// and the input moves (most packets moved the mouse or a stick), <c>game</c> when it's in front and still,
/// <c>move</c> when the input moves with another program in front, <c>-</c> otherwise.
/// </summary>
public static class PlayTags
{
    public const string Play = "play", Game = "game", Move = "move", None = "-";

    /// <summary>Most packets moved the mouse or a stick (the same "moving" as the over-the-floor split).</summary>
    public static bool Moving(LinkSecond l) => l.Rx > 0 && l.Moving * 2 >= l.Rx;

    /// <summary>Never the game: the desktop and its shell, GyroPhone itself.</summary>
    private static bool NotAGame(string name, string? self) =>
        name is "explorer" or "dwm" or "Idle" or "ShellExperienceHost" or "SearchHost" or "StartMenuExperienceHost" or "LockApp" ||
        name.StartsWith("GyroPhone", StringComparison.OrdinalIgnoreCase) ||
        (!string.IsNullOrEmpty(self) && string.Equals(name, self, StringComparison.OrdinalIgnoreCase));

    /// <summary>The program in front for most of the moving seconds, or null: the game when the FPS overlay didn't name it.</summary>
    public static string? Guess(IEnumerable<(string? Front, bool Moving)> seconds, string? self) =>
        seconds.Where(x => x.Moving && !string.IsNullOrEmpty(x.Front) && !NotAGame(x.Front!, self))
            .GroupBy(x => x.Front!, StringComparer.OrdinalIgnoreCase)
            .OrderByDescending(g => g.Count()).Select(g => g.Key).FirstOrDefault();

    public static string Of(string? front, bool moving, string? game)
    {
        bool inGame = !string.IsNullOrEmpty(game) && string.Equals(front, game, StringComparison.OrdinalIgnoreCase);
        return inGame ? (moving ? Play : Game) : moving ? Move : None;
    }
}

/// <summary>
/// A stretch of link trouble in a record: seconds in a row (a quiet second between two still joins them)
/// with lost or late packets, phone pauses or let-go keys.
/// </summary>
public sealed record LinkBurst(long FromS, long ToS, DateTime At, int Lost, int Late, double MaxLateMs, int Gaps, int Releases, int Jumps, string Busy = "",
    float PhoneGapMs = -1, float PhoneWakeMs = -1, float PhoneSendMs = -1)
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
        /// <summary>The program in front (the game when the FPS overlay knows it), or null.</summary>
        public string? Front;
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
            s.Front = front;
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

    private static string Col(float v, string format, int width) => (v < 0 ? "-" : v.ToString(format, CultureInfo.InvariantCulture)).PadLeft(width);

    private static string Ms2(double v) => v < 0 ? "-" : v.ToString("0.00", CultureInfo.InvariantCulture);

    private static string Per10k(long n, long of) => of <= 0 ? "-" : (n * 10_000.0 / of).ToString("0.0", CultureInfo.InvariantCulture);

    /// <summary>
    /// The run's link numbers from its seconds: the floor's middle, every packet's over-the-floor pooled (and split into
    /// seconds with movement and still ones), hand-offs, the stall watch and where late packets waited.
    /// </summary>
    public static PerfRun WithLinkNumbers(PerfRun run, IReadOnlyList<LinkSecond> link, IReadOnlyList<PhoneSecond> phone)
    {
        var all = new long[OverFloor.Buckets];
        var moving = new long[OverFloor.Buckets];
        var still = new long[OverFloor.Buckets];
        foreach (var l in link)
        {
            if (l.Over == null) continue;
            OverFloor.Add(all, l.Over);
            OverFloor.Add(l.Moving * 2 >= Math.Max(1, l.Rx) ? moving : still, l.Over);
        }
        var floors = link.Where(l => l.FloorMs > 0).Select(l => (double)l.FloorMs).ToList();
        var rttTyp = link.Where(l => l.RttTypMs > 0).Select(l => (double)l.RttTypMs).ToList();
        if (rttTyp.Count == 0) rttTyp = phone.Where(p => p.RttTypMs > 0).Select(p => (double)p.RttTypMs).ToList();
        bool hand = link.Any(l => l.HandOffMaxMs > 0);
        var stall = link.Where(l => l.StallMaxMs >= 0).ToList();
        return run with
        {
            FloorMs = Median(floors),
            RttTypMs = Median(rttTyp),
            OverP50Ms = OverFloor.Pct(all, 0.5),
            OverP99Ms = OverFloor.Pct(all, 0.99),
            OverP999Ms = OverFloor.Pct(all, 0.999),
            MovingOverP99Ms = OverFloor.Pct(moving, 0.99),
            StillOverP99Ms = OverFloor.Pct(still, 0.99),
            Packets = link.Sum(l => (long)l.Rx),
            Late3 = link.Sum(l => l.Late3),
            HandOffMaxMs = hand ? link.Max(l => l.HandOffMaxMs) : -1,
            SlowHandOffs = link.Sum(l => l.SlowHandOffs),
            LockWaitMaxMs = hand ? link.Max(l => l.LockWaitMs) : -1,
            StallMaxMs = stall.Count > 0 ? stall.Max(l => l.StallMaxMs) : -1,
            Stalls = stall.Sum(l => l.Stalls),
            HeldAir = link.Sum(l => l.Air),
            HeldPc = link.Sum(l => l.Pc),
            HeldPhone = link.Sum(l => l.Phone),
            NegLat = link.Sum(l => l.NegLat),
        };
    }

    /// <summary>The link numbers over <paramref name="link"/>'s seconds, with the PC's average CPU over <paramref name="loads"/> (−1 none).</summary>
    public static PlaySlice Slice(IReadOnlyList<LinkSecond> link, IReadOnlyList<LoadSample> loads)
    {
        var all = new long[OverFloor.Buckets];
        foreach (var l in link)
            if (l.Over != null) OverFloor.Add(all, l.Over);
        var rttTyp = link.Where(l => l.RttTypMs > 0).Select(l => (double)l.RttTypMs).ToList();
        return new PlaySlice(link.Count, link.Sum(l => (long)l.Rx), link.Sum(l => l.Lost), link.Sum(l => l.Late),
            link.Count > 0 ? link.Max(l => l.MaxLateMs) : 0, link.Sum(l => l.Gaps), link.Sum(l => l.Releases), link.Sum(l => l.Late3),
            OverFloor.Pct(all, 0.5), OverFloor.Pct(all, 0.99), OverFloor.Pct(all, 0.999),
            link.Sum(l => l.Air), link.Sum(l => l.Pc), link.Sum(l => l.Phone), Median(rttTyp), loads.Count > 0 ? loads.Average(l => l.CpuPct) : -1);
    }

    /// <summary>One line of a <see cref="PlaySlice"/> for the record's text.</summary>
    private static string SliceLine(string what, PlaySlice p)
    {
        var c = CultureInfo.InvariantCulture;
        var sb = new StringBuilder();
        sb.Append(c, $"  {what} {p.Seconds} s: lost {p.Lost}, late {p.Late} (worst {p.MaxLateMs:0} ms), phone pauses {p.Gaps}, let go {p.Releases}");
        if (p.OverP50Ms >= 0)
            sb.Append(c, $"; over the floor {Ms2(p.OverP50Ms)} / {Ms2(p.OverP99Ms)} / {Ms2(p.OverP999Ms)} ms, 3-15 ms over {p.Late3} of {p.Packets} packets");
        if (p.Late > 0 && p.HeldAir + p.HeldPc + p.HeldPhone + p.HeldPcBusy > 0)
        {
            sb.Append(c, $"; held up on the way {p.HeldAir}, inside the PC {p.HeldPc}, phone paused {p.HeldPhone}");
            if (p.HeldPcBusy > 0) sb.Append(c, $", PC busy {p.HeldPcBusy}");
        }
        if (p.RttTypMs > 0) sb.Append(c, $"; round trip typical {Ms2(p.RttTypMs)} ms");
        if (p.CpuPct >= 0) sb.Append(c, $"; PC CPU {p.CpuPct:0}%");
        return sb.ToString();
    }

    /// <summary>A phone number for the burst table, "-" when the phone sent none.</summary>
    private static string Ms(float v, string format) => v < 0 ? "-" : v.ToString(format, CultureInfo.InvariantCulture);

    private static double Median(List<double> v)
    {
        if (v.Count == 0) return -1;
        v.Sort();
        return v[v.Count / 2];
    }

    /// <summary>The record's row and its per-second text (the file the Results table links to).</summary>
    public (PerfRun Run, string Text) Finish(long endUs, IReadOnlyList<LinkSecond> link, IReadOnlyList<PhoneSecond>? phone = null)
    {
        phone ??= [];
        lock (_lock)
        {
            var phoneBySecond = new SortedDictionary<long, PhoneSecond>();
            foreach (var p in phone) phoneBySecond[Math.Max(0, (long)Math.Round((p.AtUs - StartUs) / 1e6) - 1)] = p;
            bool hasPhone = phoneBySecond.Count > 0;
            double fps = _sumMs > 0 ? _ms.Count * 1000 / _sumMs : 0;
            bool frames = _ms.Count > 0;
            var loads = _seconds.Values.Where(s => s.Load != null).Select(s => s.Load!.Value).ToList();
            double Avg(Func<LoadSample, double> f) => loads.Count > 0 ? loads.Average(f) : 0;
            var linkBySecond = new SortedDictionary<long, LinkSecond>();
            // A link second ends about a second after its start: keyed by the nearest whole second, minus that one.
            foreach (var l in link) linkBySecond[Math.Max(0, (long)Math.Round((l.AtUs - StartUs) / 1e6) - 1)] = l;
            var bursts = Bursts(linkBySecond);
            // What the phone saw in each burst (and the second before it, when its trouble began).
            if (hasPhone)
                for (int i = 0; i < bursts.Count; i++)
                {
                    var b = bursts[i];
                    var ps = phoneBySecond.Where(x => x.Key >= b.FromS - 1 && x.Key <= b.ToS).Select(x => x.Value).ToList();
                    if (ps.Count > 0)
                        bursts[i] = b with { PhoneGapMs = ps.Max(p => p.GapMaxMs), PhoneWakeMs = ps.Max(p => p.WakeLateMs), PhoneSendMs = ps.Max(p => p.SendMaxMs) };
                }
            // The middle latency weighted by packets, so a still second (50 packets) counts less than a moving one (1000).
            var p50 = link.Where(l => l.LatP50Ms >= 0).SelectMany(l => Enumerable.Repeat((double)l.LatP50Ms, Math.Clamp(l.Rx / 50, 1, 40))).ToList();
            var p99 = link.Where(l => l.LatP99Ms >= 0).Select(l => (double)l.LatP99Ms).ToList();
            double jitter = link.Count > 0 ? link.Average(l => l.JitterMs) : 0;
            string path = string.Join(" → ", _paths);
            var busySeconds = _seconds.Values.Where(x => x.Busy != null).ToList();
            // The program busy most often.
            string busyTop = busySeconds.GroupBy(x => x.BusyTop!).OrderByDescending(g => g.Count()).Select(g => g.Key).FirstOrDefault() ?? "";
            var run = new PerfRun(Name, Link, Start, (endUs - StartUs) / 1e6, Game, _sumMs / 1000, fps, FrameStats.Low1Fps([.. _ms]),
                _jumps, _worst, link.Sum(l => l.Lost), link.Sum(l => l.Late), link.Count > 0 ? link.Max(l => l.MaxLateMs) : 0,
                link.Sum(l => l.Gaps), link.Sum(l => l.Releases), Avg(l => l.CpuPct), Avg(l => l.DriverPct), Avg(l => l.BusiestCoreDriverPct),
                null, path, Median(p50), Median(p99), jitter, bursts.Count, frames, busySeconds.Count, busyTop, null,
                hasPhone ? phone.Max(p => p.SendMaxMs) : -1, hasPhone ? phone.Max(p => p.WakeLateMs) : -1,
                hasPhone ? phone.Max(p => p.GapMaxMs) : -1, phone.Sum(p => p.Failed));
            run = WithLinkNumbers(run, link, phone);
            // "On the way" in a second the PC's CPU was pinned: most likely the PC, not the network.
            int busyHeld = linkBySecond.Where(x => x.Value.Air > 0 && _seconds.TryGetValue(x.Key, out var ls) && ls.Load is { } ld && ld.CpuPct >= PerfRun.PcBusyCpuPct)
                .Sum(x => x.Value.Air);
            if (busyHeld > 0) run = run with { HeldAir = run.HeldAir - busyHeld, HeldPcBusy = busyHeld };

            // Play tags: which seconds were the game session, and the link over each kind.
            bool MovingAt(long k) => linkBySecond.TryGetValue(k, out var ls) && PlayTags.Moving(ls);
            string? FrontAt(long k) => _seconds.TryGetValue(k, out var fs) ? fs.Front : null;
            bool guessed = Game.Length == 0;
            string? game = guessed ? PlayTags.Guess(linkBySecond.Keys.Select(k => (FrontAt(k), MovingAt(k))), Self) : Game;
            var tags = new Dictionary<long, string>();
            foreach (var k in linkBySecond.Keys.Concat(_seconds.Keys).Distinct()) tags[k] = PlayTags.Of(FrontAt(k), MovingAt(k), game);
            PlaySlice? SliceOf(Func<string, bool> pick)
            {
                var keys = linkBySecond.Keys.Where(k => pick(tags[k])).ToList();
                if (keys.Count == 0) return null;
                var loadsIn = keys.Where(k => _seconds.TryGetValue(k, out var fs) && fs.Load != null).Select(k => _seconds[k].Load!.Value).ToList();
                int busy = keys.Where(k => _seconds.TryGetValue(k, out var fs) && fs.Load is { } ld && ld.CpuPct >= PerfRun.PcBusyCpuPct).Sum(k => linkBySecond[k].Air);
                var slice = Slice(keys.Select(k => linkBySecond[k]).ToList(), loadsIn);
                return busy > 0 ? slice with { HeldAir = slice.HeldAir - busy, HeldPcBusy = busy } : slice;
            }
            run = run with
            {
                PlayGame = game ?? "",
                PlayGameGuessed = game != null && guessed,
                Playing = SliceOf(t => t == PlayTags.Play),
                GameStill = SliceOf(t => t == PlayTags.Game),
                Other = SliceOf(t => t is PlayTags.Move or PlayTags.None),
            };

            var sb = new StringBuilder();
            var c = CultureInfo.InvariantCulture;
            sb.AppendLine(c, $"GyroPhone link test '{Name}', {Start:yyyy-MM-dd HH:mm:ss}, {run.Seconds:0} s");
            sb.AppendLine(c, $"path: {(path.Length > 0 ? path : Link)}");
            sb.AppendLine(c, $"link: lost {run.Lost}, late {run.Late} (worst {run.MaxLateMs:0} ms), phone pauses {run.Gaps}, let go {run.Releases}, trouble bursts {bursts.Count}");
            if (game == null) sb.AppendLine("play tags: no game found (no program other than the desktop and GyroPhone was in front while the input moved)");
            else
            {
                sb.Append("play tags: game ").Append(game)
                  .AppendLine(guessed ? " (a guess: in front most while the input moved; the FPS overlay names it for sure)" : " (the FPS overlay's)");
                if (run.Playing != null) sb.AppendLine(SliceLine("playing (game in front, input moving)", run.Playing));
                if (run.GameStill != null) sb.AppendLine(SliceLine("game in front, input still", run.GameStill));
                if (run.Other != null) sb.AppendLine(SliceLine("everything else", run.Other));
            }
            if (run.FloorMs >= 0 || run.OverP50Ms >= 0)
                sb.AppendLine(c, $"link split: floor (half the best round trip) {Ms2(run.FloorMs)} ms, round trip typical {Ms2(run.RttTypMs)} ms; " +
                    $"over the floor, every packet: {Ms2(run.OverP50Ms)} / {Ms2(run.OverP99Ms)} / {Ms2(run.OverP999Ms)} ms (middle / slowest 1% / 0.1%), " +
                    $"slowest 1% moving {Ms2(run.MovingOverP99Ms)} ms, still {Ms2(run.StillOverP99Ms)} ms; 3-15 ms over: {run.Late3} of {run.Packets} packets");
            if (run.LatP50Ms >= 0)
                sb.Append(c, $"latency (one way, needs the clock guess): typical {run.LatP50Ms:0.0} ms, slowest 1% {run.LatP99Ms:0.0} ms, jitter {run.JitterMs:0.0} ms")
                  .AppendLine(run.NegLat > 0 ? $", {run.NegLat} below zero (the clock guess was off)" : "");
            if (run.HandOffMaxMs >= 0)
                sb.AppendLine(c, $"hand-off to Windows: slowest {run.HandOffMaxMs:0.0} ms, over 2 ms {run.SlowHandOffs} ({Per10k(run.SlowHandOffs, run.Packets)} per 10k packets), longest engine lock wait {run.LockWaitMaxMs:0.0} ms");
            if (run.Late > 0)
            {
                sb.Append(c, $"late packets held up: on the way {run.HeldAir}, inside the PC {run.HeldPc}, phone paused {run.HeldPhone}");
                if (run.HeldPcBusy > 0) sb.Append(c, $", PC busy (CPU {PerfRun.PcBusyCpuPct:0}%+, most likely waited in the PC) {run.HeldPcBusy}");
                sb.AppendLine();
            }
            if (run.StallMaxMs >= 0)
                sb.AppendLine(c, $"PC stall watch: latest wake {run.StallMaxMs:0.0} ms, {run.Stalls} wakes 2 ms or more late");
            if (frames)
                sb.AppendLine(c, $"game {Game}, in front {run.GameSeconds:0} s: avg {run.AvgFps:0.0} fps, 1% low {run.Low1Fps:0.0} fps, " +
                    $"jumps {run.Jumps} ({run.JumpsPerMin:0.0}/min), worst frame {run.WorstMs:0.0} ms");
            else sb.AppendLine("game frames: not watched (the FPS overlay was off)");
            if (hasPhone)
                sb.AppendLine(c, $"phone: longest send {run.PhoneSendMaxMs:0.0} ms ({phone.Sum(p => p.SlowSends)} took 2 ms or more), failed sends {run.PhoneFailed}, " +
                    $"send thread woke up to {run.PhoneWakeLateMs:0.0} ms late, longest gap between its STATEs {run.PhoneGapMaxMs:0} ms");
            else sb.AppendLine("phone: no numbers (a phone app without PHONE_STATS, or no session)");
            sb.AppendLine(c, $"PC: CPU {run.CpuPct:0}%, driver time {run.DriverPct:0.0}%, busiest core driver time {run.BusiestCoreDriverPct:0.0}%");
            if (busySeconds.Count > 0)
                sb.AppendLine(c, $"WARNING: the PC was busy with other programs (together {PcBusy.Pct:0}% of the PC or more) for {busySeconds.Count} s, mostly {busyTop}. A build, scan or update can cause hitches: see the busy column.");
            sb.AppendLine();
            if (bursts.Count > 0)
            {
                sb.AppendLine("Link trouble (at = clock time, :ss = second of the minute, to spot a repeating pattern):");
                sb.AppendLine("  at        :ss  from_s  secs  lost  late maxlate_ms pauses let_go game_jumps ph_gap_ms ph_wake_ms ph_send_ms  pc_busy");
                foreach (var b in bursts)
                    sb.AppendLine(c, $"  {b.At:HH:mm:ss}  :{b.At:ss} {b.FromS,7} {b.Seconds,5} {b.Lost,5} {b.Late,5} {b.MaxLateMs,10:0} {b.Gaps,6} {b.Releases,6} {(frames ? b.Jumps.ToString(c) : "-"),10} " +
                        $"{Ms(b.PhoneGapMs, "0"),9} {Ms(b.PhoneWakeMs, "0.0"),10} {Ms(b.PhoneSendMs, "0.0"),10}  {(b.Busy.Length > 0 ? b.Busy : "-")}");
                sb.AppendLine();
            }
            else sb.AppendLine("Link trouble: none.").AppendLine();
            sb.AppendLine("Per second. fps: while the game was in front, with the FPS overlay on (a second it was in front only partly has few frames).");
            if (hasPhone)
                sb.AppendLine("phone: STATEs and packets it sent, failed and slow (2 ms+) sends, longest send, how late its send thread woke, longest gap between STATEs, packets the whole phone sent.");
            sb.AppendLine("tag: play = the game in front and the input moving, game = in front and still, move = moving with another program in front.");
            sb.AppendLine("over: over the floor, middle / slowest 1%, ms. l3: packets 3-15 ms over. hand: slowest hand-off, ms; slow: over 2 ms. stall: the PC stall watch's latest wake, ms.");
            sb.AppendLine("  s  :ss  tag |   rx lost late maxlate_ms gaps rel  lat_ms p99_ms jitter  floor over50 over99  l3  hand slow stall |" +
                (hasPhone ? " states  pkts fail slow send_ms wake_ms gap_ms  ph_tx |" : "") + "  frames   fps  worst_ms jumps |  cpu  dpc  int core_drv | busy");
            long last = Math.Max(_seconds.Count > 0 ? _seconds.Keys.Max() : 0, linkBySecond.Count > 0 ? linkBySecond.Keys.Max() : 0);
            for (long k = 0; k <= last; k++)
            {
                _seconds.TryGetValue(k, out var s);
                bool hasLink = linkBySecond.TryGetValue(k, out var l);
                if (s == null && !hasLink) continue;
                sb.Append(c, $"{k,3}  :{Start.AddSeconds(k):ss} {(tags.TryGetValue(k, out var tag) ? tag : PlayTags.None),4} | ");
                if (hasLink)
                {
                    sb.Append(c, $"{l.Rx,4} {l.Lost,4} {l.Late,4} {l.MaxLateMs,10:0} {l.Gaps,4} {l.Releases,3} ");
                    sb.Append(l.LatP50Ms >= 0 ? l.LatP50Ms.ToString("0.0", c).PadLeft(7) : "      -").Append(' ');
                    sb.Append(l.LatP99Ms >= 0 ? l.LatP99Ms.ToString("0.0", c).PadLeft(6) : "     -").Append(' ');
                    sb.Append(l.JitterMs.ToString("0.0", c).PadLeft(6)).Append(' ');
                    sb.Append(Col(l.FloorMs, "0.00", 6)).Append(' ').Append(Col(l.OverP50Ms, "0.0", 6)).Append(' ').Append(Col(l.OverP99Ms, "0.0", 6));
                    sb.Append(c, $" {l.Late3,3} ").Append(Col(l.HandOffMaxMs, "0.0", 5)).Append(c, $" {l.SlowHandOffs,4} ").Append(Col(l.StallMaxMs, "0.0", 5));
                }
                else sb.Append("   -    -    -          -    -   -       -      -      -      -      -      -   -     -    -     -");
                sb.Append(" | ");
                if (hasPhone)
                {
                    if (phoneBySecond.TryGetValue(k, out var ph))
                        sb.Append(c, $"{ph.States,6} {ph.Packets,5} {ph.Failed,4} {ph.SlowSends,4} {ph.SendMaxMs,7:0.0} {ph.WakeLateMs,7:0.0} {ph.GapMaxMs,6:0} {ph.PhoneTx,6}");
                    else sb.Append("     -     -    -    -       -       -      -      -");
                    sb.Append(" | ");
                }
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
