using System.Text.Json;
using GyroPhone.Host.Abstractions;

namespace GyroPhone.Host.Perf;

/// <summary>Whether the frame watcher runs.</summary>
public enum FrameWatchState
{
    Off,
    On,
    /// <summary>Windows refused: watching frames needs admin rights or the "Performance Log Users" group.</summary>
    NeedsPermission,
    Failed,
}

/// <summary>Reports every frame any program shows with Direct3D (Windows: ETW).</summary>
public interface IFrameSource : IDisposable
{
    /// <summary>Starts calling <paramref name="present"/>(process id, time on the <see cref="IClock"/>, µs) from its own thread.</summary>
    FrameWatchState Start(Action<int, long> present, out string? error);
    void Stop();
}

/// <summary>The PC's processor load (Windows: performance counters).</summary>
public interface ISystemLoad : IDisposable
{
    /// <summary>The load since the call before, or null when it can't be read.</summary>
    LoadSample? Sample();
}

/// <summary>Which program is in front, and program names.</summary>
public interface IForeground
{
    int ForegroundPid();
    /// <summary>The process's name (without .exe), or null when it is gone.</summary>
    string? NameOf(int pid);
}

/// <summary>A test record in progress, for the page.</summary>
public sealed record RecordingView(string Name, double Elapsed, int Seconds, double GameSeconds);

/// <summary>
/// The Performance page's monitor (docs/spec/pc-companion.md, "Performance"): watches the game's frames only
/// while an FPS overlay is on (<see cref="Watch"/>), keeps link test records (the phone link's seconds, the
/// path the packets took, the PC's load, and the frames when watched): the newest <see cref="MaxRuns"/> in
/// perf/runs.jsonl, shown together in perf/results.html (<see cref="PerfReport"/>).
/// </summary>
public sealed class PerfMonitor : IDisposable
{
    public const int MaxRuns = 50;
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };

    private readonly IFrameSource _source;
    private readonly ISystemLoad? _load;
    private readonly IForeground _foreground;
    private readonly IClock _clock;
    private readonly Action<string> _log;
    private readonly object _lock = new();
    private readonly Dictionary<int, string?> _names = [];
    private readonly List<PerfRun> _runs = [];
    private Timer? _timer;
    private bool _watch, _checking;
    private string? _busy;
    private PerfRecording? _recording;
    private LoadSample? _lastLoad;
    private int _game;

    public PerfMonitor(IFrameSource source, ISystemLoad? load, IForeground foreground, IClock clock, string folder, Action<string> log)
    {
        _source = source;
        _load = load;
        _foreground = foreground;
        _clock = clock;
        Folder = folder;
        _log = log;
        int self = Environment.ProcessId;
        Frames = new FrameStats(pid => pid == self || string.Equals(Name(pid), "dwm", StringComparison.OrdinalIgnoreCase));
        LoadRuns();
    }

    public FrameStats Frames { get; }
    /// <summary>The phone link's seconds; the input log fills it.</summary>
    public LinkTimeline Link { get; } = new();
    /// <summary>The phone's side of the link (PHONE_STATS), sent only while a record runs; the engine fills it.</summary>
    public PhoneTimeline Phone { get; } = new();
    public string Folder { get; }
    public FrameWatchState State { get; private set; } = FrameWatchState.Off;
    public string? Error { get; private set; }

    /// <summary>The game's name, or null without one.</summary>
    public string? GameName
    {
        get
        {
            int g;
            lock (_lock) g = _game;
            return g == 0 ? null : Name(g);
        }
    }

    /// <summary>
    /// The programs other than the game and GyroPhone keeping the PC busy now ("java 45%, dotnet 12%"), or null;
    /// known while <see cref="Check"/> is on or a record runs.
    /// </summary>
    public string? Busy
    {
        get { lock (_lock) return _busy; }
    }

    /// <summary>Samples the PC's load once a second for <see cref="Busy"/>: while the Performance page is shown.</summary>
    public void Check(bool on)
    {
        lock (_lock)
        {
            if (_checking == on) return;
            _checking = on;
            if (!on) _busy = null;
            Apply();
        }
    }

    /// <summary>The PC's load in the last second, while checking or recording.</summary>
    public LoadSample? Load
    {
        get { lock (_lock) return _lastLoad; }
    }

    public IReadOnlyList<PerfRun> Runs
    {
        get { lock (_lock) return [.. _runs]; }
    }

    public RecordingView? Recording
    {
        get
        {
            lock (_lock)
                return _recording is { } r ? new RecordingView(r.Name, (_clock.NowUs - r.StartUs) / 1e6, r.Seconds, r.GameSeconds) : null;
        }
    }

    public FrameView View() => Frames.View(_clock.NowUs);

    /// <summary>The way the phone's packets go now, for records ("Phone hotspot · PC Wi-Fi"), or null.</summary>
    public Func<string?>? PathNow { get; set; }

    /// <summary>Watching the game's frames: State is On.</summary>
    public bool Watching => State == FrameWatchState.On;

    /// <summary>For the input log's second line while watching: " fps N jumps J worst W game NAME", else null.</summary>
    public string? SecondNote()
    {
        if (State != FrameWatchState.On) return null;
        var (n, jumps, worst, focused, pid) = Frames.LastSecond(_clock.NowUs);
        if (pid == 0) return " fps - (no game)";
        var name = Name(pid) ?? "?";
        return string.Create(System.Globalization.CultureInfo.InvariantCulture,
            $" fps {n} jumps {jumps} worst {worst:0.0} game {name}{(focused ? "" : " (background)")}");
    }

    /// <summary>Watching while an FPS overlay is on.</summary>
    public void Watch(bool on)
    {
        lock (_lock)
        {
            _watch = on;
            Apply();
        }
    }

    /// <summary>Tries again after the user was given the right (or restarted as administrator).</summary>
    public void Retry()
    {
        lock (_lock)
        {
            if (State == FrameWatchState.On) return;
            State = FrameWatchState.Off;
            Apply();
        }
    }

    private void Apply()
    {
        if (_watch && State is FrameWatchState.Off)
        {
            State = _source.Start(Frames.Present, out var error);
            Error = error;
            _log($"frame watch: {State}{(error != null ? $" ({error})" : "")}");
        }
        else if (!_watch && State != FrameWatchState.Off)
        {
            if (State == FrameWatchState.On) _source.Stop();
            State = FrameWatchState.Off;
            Error = null;
            _log("frame watch: off");
        }
        // The timer runs while watching (picks the game), checking (load) or recording (load, path, the end).
        bool tick = State == FrameWatchState.On || _checking || _recording != null;
        if (tick) _timer ??= new Timer(_ => Tick(), null, 1000, 1000);
        else
        {
            _timer?.Dispose();
            _timer = null;
        }
    }

    /// <summary>Starts a test record of <paramref name="seconds"/> (0: until <see cref="StopRecording"/>).</summary>
    public void StartRecording(string name, string link, int seconds)
    {
        lock (_lock)
        {
            if (_recording != null) return;
            _recording = new PerfRecording(name.Trim().Length > 0 ? name.Trim() : link, link, _clock.NowUs, DateTime.Now, seconds);
            _recording.NotePath(PathNow?.Invoke());
            _recording.Self = Name(Environment.ProcessId);
            _load?.Sample(); // starts its count from now
            Apply();
            Frames.Recording = _recording;
            _log($"performance test '{_recording.Name}' started, {seconds} s");
        }
        try { Started?.Invoke(); }
        catch (Exception e) { _log($"performance test start notice failed: {e.GetType().Name}: {e.Message}"); }
    }

    /// <summary>A test record started (raised on the thread that started it).</summary>
    public event Action? Started;

    /// <summary>A test record ended and was saved (raised on the thread that stopped it).</summary>
    public event Action<PerfRun>? Ended;

    public void StopRecording()
    {
        PerfRecording? r;
        lock (_lock)
        {
            r = _recording;
            if (r == null) return;
            _recording = null;
            Frames.Recording = null;
            Apply();
        }
        long end = _clock.NowUs;
        var (run, text) = r.Finish(end, Link.Between(r.StartUs, end + 1_100_000), Phone.Between(r.StartUs, end + 1_100_000));
        run = run with { Text = text, File = ReportPath };
        List<PerfRun> kept;
        lock (_lock)
        {
            _runs.Insert(0, run);
            if (_runs.Count > MaxRuns) _runs.RemoveRange(MaxRuns, _runs.Count - MaxRuns);
            kept = [.. _runs];
        }
        try
        {
            // Only the kept records stay in the file, oldest first, so it never grows past MaxRuns.
            Directory.CreateDirectory(Folder);
            var sb = new System.Text.StringBuilder();
            for (int i = kept.Count - 1; i >= 0; i--) sb.Append(JsonSerializer.Serialize(kept[i], Json)).Append('\n');
            string jsonl = Path.Combine(Folder, "runs.jsonl"), tmp = jsonl + ".new";
            File.WriteAllText(tmp, sb.ToString());
            File.Move(tmp, jsonl, overwrite: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _log($"performance test not saved: {e.GetType().Name}: {e.Message}");
        }
        WriteReport(kept);
        _log($"performance test '{run.Name}' ended: {run.AvgFps:0.0} fps, 1% low {run.Low1Fps:0.0}, jumps {run.Jumps}, lost {run.Lost}, late {run.Late}");
        try { Ended?.Invoke(run); }
        catch (Exception e) { _log($"performance test end notice failed: {e.GetType().Name}: {e.Message}"); }
    }

    /// <summary>results.html, the page of every kept record.</summary>
    public string ReportPath => Path.Combine(Folder, PerfReport.FileName);

    /// <summary>Writes results.html afresh from the kept records; returns its path, or null when it couldn't.</summary>
    public string? WriteReport() => WriteReport(Runs);

    private string? WriteReport(IReadOnlyList<PerfRun> runs)
    {
        try
        {
            return PerfReport.Write(Folder, runs);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // Open somewhere that locks it: written again next time.
            _log($"performance results page not written: {e.GetType().Name}: {e.Message}");
            return null;
        }
    }

    private void LoadRuns()
    {
        try
        {
            var path = Path.Combine(Folder, "runs.jsonl");
            if (!File.Exists(path)) return;
            foreach (var line in File.ReadLines(path))
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                try
                {
                    if (JsonSerializer.Deserialize<PerfRun>(line, Json) is { } run) _runs.Insert(0, run);
                }
                catch (JsonException)
                {
                }
            }
            if (_runs.Count > MaxRuns) _runs.RemoveRange(MaxRuns, _runs.Count - MaxRuns);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _log($"performance tests not read: {e.GetType().Name}");
        }
    }

    private string? Name(int pid)
    {
        lock (_names)
        {
            if (_names.TryGetValue(pid, out var n)) return n;
            n = _foreground.NameOf(pid);
            if (_names.Count > 500) _names.Clear();
            _names[pid] = n;
            return n;
        }
    }

    /// <summary>Once a second, on the timer: picks the game, samples the load, ends a timed record.</summary>
    internal void Tick()
    {
        try
        {
            long now = _clock.NowUs;
            int game = State == FrameWatchState.On ? Frames.Tick(_foreground.ForegroundPid(), now) : 0;
            PerfRecording? r;
            lock (_lock) r = _recording;
            bool checking;
            lock (_lock) checking = _checking;
            // The PC's load: only for the page or a record.
            var load = r != null || checking ? _load?.Sample() : null;
            // Without the FPS overlay the game isn't known: the program in front stands for it.
            string? front = load != null ? (game != 0 ? Name(game) : Name(_foreground.ForegroundPid())) : null;
            var busy = load is { } ld ? PcBusy.Of(ld, front, Name(Environment.ProcessId))?.Text : null;
            lock (_lock)
            {
                _game = game;
                _lastLoad = load;
                _busy = checking ? busy : null;
            }
            if (r == null) return;
            r.NotePath(PathNow?.Invoke());
            if (game != 0 && r.Game.Length == 0) r.Game = Name(game) ?? "";
            if (load is { } l) r.AddLoad(l, now, front);
            if (r.Seconds > 0 && now - r.StartUs >= r.Seconds * 1_000_000L) StopRecording();
        }
        catch (Exception e)
        {
            _log($"performance monitor tick failed: {e}");
        }
    }

    public void Dispose()
    {
        if (Recording != null) StopRecording();
        lock (_lock)
        {
            _watch = false;
            Apply();
        }
        _source.Dispose();
        _load?.Dispose();
    }
}
