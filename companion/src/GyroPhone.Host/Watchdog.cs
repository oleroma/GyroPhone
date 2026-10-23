using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// 50 ms timer driving HostEngine.OnTick, plus the work that must stay off the rx thread: environment
/// probes (1 Hz, on the thread pool so a slow COM or service call never holds up OnTick's release of
/// held input), the held-keys marker and settings writes. No timeBeginPeriod.
/// </summary>
public sealed class Watchdog : IDisposable
{
    private const long ProbeIntervalUs = 1_000_000;
    /// <summary>A failed settings write is tried again after this long.</summary>
    private const long SaveRetryUs = 1_000_000;

    private readonly HostEngine _engine;
    private readonly IClock _clock;
    private readonly HeldKeysMarker? _marker;
    private readonly SettingsStore? _store;
    private readonly Func<bool>? _elevatedProbe;
    private readonly Func<bool>? _publicProbe;
    private readonly Action<string> _log;
    private readonly byte[] _held = new byte[StateFrame.KeyBitmapSize];
    private Timer? _timer;
    private int _running;
    private int _probing;
    private long _lastProbeUs = -ProbeIntervalUs;
    private long _saveRetryAfterUs = long.MinValue;

    public Watchdog(HostEngine engine, IClock clock, HeldKeysMarker? marker = null, SettingsStore? store = null,
        Func<bool>? elevatedForegroundProbe = null, Func<bool>? networkPublicProbe = null, Action<string>? log = null)
    {
        _engine = engine;
        _clock = clock;
        _marker = marker;
        _store = store;
        _elevatedProbe = elevatedForegroundProbe;
        _publicProbe = networkPublicProbe;
        _log = log ?? (_ => { });
    }

    public void Start() =>
        _timer = new Timer(_ => Tick(), null, Wire.WatchdogTickMs, Wire.WatchdogTickMs);

    /// <summary>One tick. Re-entrant calls (a slow tick overlapping the next) are skipped.</summary>
    public void Tick()
    {
        if (Interlocked.Exchange(ref _running, 1) == 1) return;
        try
        {
            long now = _clock.NowUs;
            _engine.OnTick(now);
            if (now - _lastProbeUs >= ProbeIntervalUs && (_elevatedProbe != null || _publicProbe != null) &&
                Interlocked.Exchange(ref _probing, 1) == 0)
            {
                _lastProbeUs = now;
                ThreadPool.UnsafeQueueUserWorkItem(_ => RunProbes(), null);
            }
            if (_marker != null)
            {
                int version = _engine.HeldSnapshot(_held, out byte mb);
                _marker.Update(version, _held, mb);
            }
            if (now >= _saveRetryAfterUs) SaveSettings();
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

    private void RunProbes()
    {
        try
        {
            bool elevated = Probe(_elevatedProbe);
            bool pub = Probe(_publicProbe);
            _engine.SetEnvironment(elevated, pub);
        }
        finally
        {
            Volatile.Write(ref _probing, 0);
        }
    }

    /// <summary>Writes settings if the engine changed them. Also called on exit.</summary>
    public void SaveSettings()
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

    public void Dispose()
    {
        _timer?.Dispose();
        _timer = null;
    }
}
