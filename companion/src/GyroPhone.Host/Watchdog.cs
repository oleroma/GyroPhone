using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// The 50 ms tick driving HostEngine.OnTick, on its own thread (gp-watchdog, AboveNormal, an exact timer
/// where the system has one) so the release of held input never waits behind other work. Environment
/// probes (1 Hz) go to the thread pool, and the files (the held-keys marker and settings) to a writer
/// thread (gp-save, BelowNormal) at most twice a second: a slow disk or a virus scan never holds up a
/// tick. No timeBeginPeriod.
/// </summary>
public sealed class Watchdog : IDisposable
{
    private const long ProbeIntervalUs = 1_000_000;
    /// <summary>A failed settings write is tried again after this long.</summary>
    private const long SaveRetryUs = 1_000_000;
    /// <summary>How often the writer looks for changes: held.json and settings are written at most this often.</summary>
    public const int WriteIntervalMs = 500;

    private readonly HostEngine _engine;
    private readonly IClock _clock;
    private readonly HeldKeysMarker? _marker;
    private readonly SettingsStore? _store;
    private readonly Func<bool>? _elevatedProbe;
    private readonly Func<bool>? _publicProbe;
    private readonly Func<KeyLabels?>? _keyLabelsProbe;
    private readonly Func<System.Net.IPAddress?, byte>? _pcSetupProbe;
    private readonly Func<ITickSleep> _sleep;
    private readonly Action<string> _log;
    private readonly byte[] _held = new byte[StateFrame.KeyBitmapSize];
    private readonly object _writeLock = new();
    private readonly ManualResetEventSlim _stopWrites = new(false);
    private Thread? _tickThread, _writeThread, _keepAwakeThread;
    private volatile bool _stop;
    private int _running;
    private int _probing;
    private long _lastProbeUs = -ProbeIntervalUs;
    private long _saveRetryAfterUs = long.MinValue;

    public Watchdog(HostEngine engine, IClock clock, HeldKeysMarker? marker = null, SettingsStore? store = null,
        Func<bool>? elevatedForegroundProbe = null, Func<bool>? networkPublicProbe = null, Action<string>? log = null,
        Func<KeyLabels?>? keyLabelsProbe = null, Func<ITickSleep>? tickSleep = null,
        Func<System.Net.IPAddress?, byte>? pcSetupProbe = null)
    {
        _engine = engine;
        _clock = clock;
        _marker = marker;
        _store = store;
        _elevatedProbe = elevatedForegroundProbe;
        _publicProbe = networkPublicProbe;
        _keyLabelsProbe = keyLabelsProbe;
        _pcSetupProbe = pcSetupProbe;
        _sleep = tickSleep ?? (() => new PlainTickSleep());
        _log = log ?? (_ => { });
    }

    public void Start()
    {
        if (_tickThread != null) return;
        _tickThread = new Thread(TickLoop) { Name = "gp-watchdog", IsBackground = true, Priority = ThreadPriority.AboveNormal };
        _writeThread = new Thread(WriteLoop) { Name = "gp-save", IsBackground = true, Priority = ThreadPriority.BelowNormal };
        _keepAwakeThread = new Thread(KeepAwakeLoop) { Name = "gp-keepawake", IsBackground = true, Priority = ThreadPriority.AboveNormal };
        _tickThread.Start();
        _writeThread.Start();
        _keepAwakeThread.Start();
    }

    /// <summary>Ticks every 50 ms from a fixed start, so a late tick doesn't push the later ones back; one that is a whole tick late is skipped.</summary>
    private void TickLoop()
    {
        using var sleep = _sleep();
        var clock = System.Diagnostics.Stopwatch.StartNew(); // real time, whatever clock the engine is given
        const long periodUs = Wire.WatchdogTickMs * 1000L;
        long next = periodUs;
        while (!_stop)
        {
            long wait = next - (long)clock.Elapsed.TotalMicroseconds;
            if (wait > 0) sleep.Sleep(wait);
            if (_stop) return;
            Tick();
            next += periodUs;
            long now = (long)clock.Elapsed.TotalMicroseconds;
            if (next <= now) next = now + periodUs;
        }
    }

    /// <summary>KEEP_AWAKE every <see cref="Wire.KeepAwakeMs"/> (HostEngine.KeepAwake decides whether to send), on its own exact timer.</summary>
    private void KeepAwakeLoop()
    {
        using var sleep = _sleep();
        while (!_stop)
        {
            sleep.Sleep(Wire.KeepAwakeMs * 1000L);
            if (_stop) return;
            try
            {
                _engine.KeepAwake();
            }
            catch (Exception e)
            {
                _log($"keep-awake error: {e.GetType().Name}");
                Thread.Sleep(1000);
            }
        }
    }

    private void WriteLoop()
    {
        while (!_stopWrites.Wait(WriteIntervalMs))
        {
            try
            {
                WriteFiles();
            }
            catch (Exception e)
            {
                _log($"watchdog write error: {e.GetType().Name}");
            }
        }
    }

    /// <summary>One tick: only in-memory work and posting probes. Re-entrant calls (a slow tick overlapping the next) are skipped.</summary>
    public void Tick()
    {
        if (Interlocked.Exchange(ref _running, 1) == 1) return;
        try
        {
            long now = _clock.NowUs;
            _engine.OnTick(now);
            if (now - _lastProbeUs >= ProbeIntervalUs && (_elevatedProbe != null || _publicProbe != null || _keyLabelsProbe != null) &&
                Interlocked.Exchange(ref _probing, 1) == 0)
            {
                _lastProbeUs = now;
                ThreadPool.UnsafeQueueUserWorkItem(_ => RunProbes(), null);
            }
        }
        catch (Exception e)
        {
            _log($"watchdog error: {e.GetType().Name}");
        }
        finally
        {
            Volatile.Write(ref _running, 0);
        }
    }

    /// <summary>
    /// The writer's turn (gp-save, every <see cref="WriteIntervalMs"/>): held.json when the held set changed,
    /// and settings when the engine changed them (all the changes since the last turn in one write).
    /// </summary>
    public void WriteFiles()
    {
        lock (_writeLock)
        {
            if (_marker != null)
            {
                int version = _engine.HeldSnapshot(_held, out byte mb);
                _marker.Update(version, _held, mb);
            }
            if (_clock.NowUs >= _saveRetryAfterUs) SaveSettingsLocked();
        }
    }

    private void RunProbes()
    {
        try
        {
            bool elevated = Probe(_elevatedProbe);
            bool pub = Probe(_publicProbe);
            _engine.SetEnvironment(elevated, pub);
            if (_keyLabelsProbe != null)
            {
                KeyLabels? labels = null;
                try { labels = _keyLabelsProbe(); } catch (Exception) { }
                _engine.SetKeyLabels(labels);
            }
            if (_pcSetupProbe != null)
            {
                byte setup = 0;
                try { setup = _pcSetupProbe(_engine.PhoneAddress()); } catch (Exception) { }
                _engine.SetPcSetup(setup);
            }
        }
        finally
        {
            Volatile.Write(ref _probing, 0);
        }
    }

    /// <summary>Writes settings if the engine changed them. Also called on exit.</summary>
    public void SaveSettings()
    {
        lock (_writeLock) SaveSettingsLocked();
    }

    private void SaveSettingsLocked()
    {
        if (_store == null) return;
        var json = _engine.TakeDirtySettings();
        if (json == null) return;
        try
        {
            _store.WriteAtomic(json);
            _saveRetryAfterUs = long.MinValue;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // Still to be written (a new pairing, say): try again shortly, since a scanner or backup
            // tool may hold the file for a moment.
            _engine.MarkSettingsDirty();
            if (_saveRetryAfterUs == long.MinValue) _log($"settings save failed: {e.GetType().Name}; retrying");
            _saveRetryAfterUs = _clock.NowUs + SaveRetryUs;
        }
    }

    private bool Probe(Func<bool>? probe)
    {
        if (probe == null) return false;
        try
        {
            return probe();
        }
        catch (Exception e)
        {
            _log($"probe failed: {e.GetType().Name}");
            return false;
        }
    }

    /// <summary>Stops both threads (waiting a moment for a write in progress); the caller then saves what is left.</summary>
    public void Dispose()
    {
        _stop = true;
        _stopWrites.Set();
        if (_tickThread is { } t && t != Thread.CurrentThread) t.Join(1000);
        if (_writeThread is { } w && w != Thread.CurrentThread) w.Join(2000);
        if (_keepAwakeThread is { } k && k != Thread.CurrentThread) k.Join(1000);
    }
}
