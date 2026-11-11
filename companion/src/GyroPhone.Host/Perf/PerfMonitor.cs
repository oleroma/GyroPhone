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
/// The Performance page's monitor (docs/spec/pc-companion.md, "Performance"): watches the game's frames while
/// the page is open or a test records, samples the PC's load once a second, and keeps the test records
/// (perf/runs.jsonl, one per-second text file per record).
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
    private bool _watch;
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

    /// <summary>The PC's load in the last second, while watching.</summary>
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

    /// <summary>Watching while the page is open; a test record keeps it on until it ends.</summary>
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
        bool want = _watch || _recording != null;
        if (want && State is FrameWatchState.Off)
        {
            State = _source.Start(Frames.Present, out var error);
            Error = error;
            _log($"frame watch: {State}{(error != null ? $" ({error})" : "")}");
            if (State == FrameWatchState.On) _timer ??= new Timer(_ => Tick(), null, 1000, 1000);
        }
        else if (!want && State == FrameWatchState.On)
        {
            _source.Stop();
            State = FrameWatchState.Off;
            _timer?.Dispose();
            _timer = null;
            _log("frame watch: off");
        }
    }

    /// <summary>Starts a test record of <paramref name="seconds"/> (0: until <see cref="StopRecording"/>).</summary>
    public void StartRecording(string name, string link, int seconds)
    {
        lock (_lock)
        {
            if (_recording != null) return;
            _recording = new PerfRecording(name.Trim().Length > 0 ? name.Trim() : link, link, _clock.NowUs, DateTime.Now, seconds);
            Apply();
            if (State != FrameWatchState.On)
            {
                _recording = null;
                return;
            }
            Frames.Recording = _recording;
            _log($"performance test '{_recording.Name}' started, {seconds} s");
        }
    }

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
        var (run, text) = r.Finish(end, Link.Between(r.StartUs, end + 1_100_000));
        string? file = null;
        try
        {
            Directory.CreateDirectory(Folder);
            file = Path.Combine(Folder, $"{r.Start:yyyyMMdd-HHmmss} {Safe(run.Name)}.txt");
            File.WriteAllText(file, text);
            run = run with { File = file };
            File.AppendAllText(Path.Combine(Folder, "runs.jsonl"), JsonSerializer.Serialize(run, Json) + Environment.NewLine);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _log($"performance test not saved: {e.GetType().Name}: {e.Message}");
        }
        lock (_lock)
        {
            _runs.Insert(0, run);
            if (_runs.Count > MaxRuns) _runs.RemoveAt(_runs.Count - 1);
        }
        _log($"performance test '{run.Name}' ended: {run.AvgFps:0.0} fps, 1% low {run.Low1Fps:0.0}, jumps {run.Jumps}, lost {run.Lost}, late {run.Late}");
    }

    private static string Safe(string name)
    {
        var bad = Path.GetInvalidFileNameChars();
        var s = new string([.. name.Select(ch => bad.Contains(ch) ? '_' : ch)]);
        return s.Length > 40 ? s[..40] : s;
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
            int game = Frames.Tick(_foreground.ForegroundPid(), now);
            var load = _load?.Sample();
            PerfRecording? r;
            lock (_lock)
            {
                _game = game;
                _lastLoad = load;
                r = _recording;
            }
            if (r == null) return;
            if (game != 0 && r.Game.Length == 0) r.Game = Name(game) ?? "";
            if (load is { } l) r.AddLoad(l, now);
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
