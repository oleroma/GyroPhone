namespace GyroPhone.Host.Perf;

/// <summary>One frame the game showed: when, how long after the one before, and whether that was a jump.</summary>
public readonly record struct Frame(long AtUs, float Ms, bool Jump);

/// <summary>The game's frames as the Performance page shows them now.</summary>
/// <param name="Pid">The game's process, 0 when no game shows frames.</param>
/// <param name="Focused">The game is the window in front.</param>
/// <param name="Fps">Frames in the last second.</param>
/// <param name="Low1Fps">The last minute's slowest 1% of frames, as frames per second.</param>
/// <param name="Jumps">Jumps in the last minute.</param>
/// <param name="WorstMs">The longest frame in the last minute.</param>
/// <param name="Recent">The last <see cref="FrameStats.GraphUs"/> of frames, oldest first.</param>
public sealed record FrameView(int Pid, bool Focused, double Fps, double Low1Fps, int Jumps, double WorstMs, Frame[] Recent)
{
    public static readonly FrameView None = new(0, false, 0, 0, 0, 0, []);
}

/// <summary>
/// Follows the frames one game shows (docs/spec/pc-companion.md, "Performance"). Every program that
/// draws with Direct3D reports each frame (<see cref="Present"/>); once a second <see cref="Tick"/> picks
/// the game: the window in front if it shows frames, else the game picked before while it still does,
/// else whichever shows the most. A frame is a jump when it takes at least <see cref="JumpFactor"/> times
/// the middle of the frames before it and at least <see cref="JumpExtraMs"/> more: one rule at any frame rate.
/// A frame after a pause of <see cref="PauseMs"/> or more (the game in the background, a loading screen)
/// starts afresh instead.
/// </summary>
public sealed class FrameStats
{
    public const double JumpFactor = 2.0, JumpExtraMs = 8, PauseMs = 1000;
    /// <summary>The middle frame time is of this many frames before.</summary>
    public const int MiddleOf = 60;
    /// <summary>How much the graph shows.</summary>
    public const long GraphUs = 10_000_000;
    private const long KeepUs = 60_000_000;

    private readonly object _lock = new();
    private readonly Func<int, bool> _skip;
    private Dictionary<int, int> _counts = [], _lastCounts = [];
    private int _game;
    private bool _focused;
    private long _lastUs = -1;
    private readonly float[] _before = new float[MiddleOf];
    private readonly float[] _sorted = new float[MiddleOf];
    private int _beforeCount, _beforeHead;
    private readonly Queue<Frame> _frames = new();
    private PerfRecording? _recording;

    /// <param name="skip">Processes never taken for the game (the companion itself, the desktop compositor).</param>
    public FrameStats(Func<int, bool> skip) => _skip = skip;

    /// <summary>The recording that gets the focused game's frames, or null.</summary>
    public PerfRecording? Recording
    {
        get { lock (_lock) return _recording; }
        set { lock (_lock) _recording = value; }
    }

    /// <summary>Process <paramref name="pid"/> showed a frame at <paramref name="atUs"/>. Any thread (the frame watcher's).</summary>
    public void Present(int pid, long atUs)
    {
        lock (_lock)
        {
            _counts[pid] = _counts.GetValueOrDefault(pid) + 1;
            if (pid != _game) return;
            long last = _lastUs;
            _lastUs = atUs;
            if (last < 0) return;
            double ms = (atUs - last) / 1000.0;
            if (ms <= 0) return;
            if (ms >= PauseMs)
            {
                _beforeCount = 0;
                return;
            }
            var f = new Frame(atUs, (float)ms, IsJump(ms));
            Remember((float)ms);
            _frames.Enqueue(f);
            if (_focused) _recording?.Add(f);
        }
    }

    private bool IsJump(double ms)
    {
        if (_beforeCount < MiddleOf / 2) return false;
        Array.Copy(_before, _sorted, _beforeCount);
        Array.Sort(_sorted, 0, _beforeCount);
        double mid = _sorted[_beforeCount / 2];
        return ms >= mid * JumpFactor && ms >= mid + JumpExtraMs;
    }

    private void Remember(float ms)
    {
        _before[_beforeHead] = ms;
        _beforeHead = (_beforeHead + 1) % MiddleOf;
        if (_beforeCount < MiddleOf) _beforeCount++;
    }

    /// <summary>Once a second: picks the game (see the class) and drops old frames. Returns the game's process, or 0.</summary>
    public int Tick(int foregroundPid, long nowUs)
    {
        lock (_lock)
        {
            (_lastCounts, _counts) = (_counts, _lastCounts);
            _counts.Clear();
            int pick = 0, most = 0;
            if (foregroundPid != 0 && _lastCounts.ContainsKey(foregroundPid) && !_skip(foregroundPid)) pick = foregroundPid;
            else if (_game != 0 && _lastCounts.ContainsKey(_game)) pick = _game;
            else
            {
                foreach (var (pid, n) in _lastCounts)
                    if (n > most && !_skip(pid))
                    {
                        most = n;
                        pick = pid;
                    }
            }
            if (pick != _game)
            {
                _game = pick;
                _lastUs = -1;
                _beforeCount = 0;
                _frames.Clear();
            }
            _focused = pick != 0 && pick == foregroundPid;
            while (_frames.Count > 0 && _frames.Peek().AtUs < nowUs - KeepUs) _frames.Dequeue();
            return _game;
        }
    }

    public FrameView View(long nowUs)
    {
        lock (_lock)
        {
            if (_game == 0) return FrameView.None;
            int fps = 0, jumps = 0;
            double worst = 0;
            var recent = new List<Frame>();
            var all = new List<float>(_frames.Count);
            foreach (var f in _frames)
            {
                if (f.AtUs > nowUs - 1_000_000) fps++;
                if (f.AtUs > nowUs - GraphUs) recent.Add(f);
                if (f.Jump) jumps++;
                if (f.Ms > worst) worst = f.Ms;
                all.Add(f.Ms);
            }
            return new FrameView(_game, _focused, fps, Low1Fps(all), jumps, worst, [.. recent]);
        }
    }

    /// <summary>The slowest 1% of <paramref name="ms"/> (at least one frame), as frames per second; 0 without frames.</summary>
    public static double Low1Fps(List<float> ms)
    {
        if (ms.Count == 0) return 0;
        ms.Sort();
        int n = Math.Max(1, ms.Count / 100);
        double sum = 0;
        for (int i = ms.Count - n; i < ms.Count; i++) sum += ms[i];
        return 1000.0 / (sum / n);
    }
}
