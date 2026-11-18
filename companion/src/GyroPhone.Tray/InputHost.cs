using System.Diagnostics;
using System.IO.Pipes;
using System.Reflection;
using System.Runtime;
using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Perf;
using GyroPhone.Host.Settings;
using GyroPhone.Host.Split;
using GyroPhone.Protocol;
using GyroPhone.Windows;

namespace GyroPhone.Tray;

/// <summary>
/// GyroPhoneCompanion.exe --input: the input process. No window, no tray, no WinForms or WPF: the phone link
/// (UDP and the USB cable), the engine (mappings, injection, the virtual pad, the input driver), pairing keys,
/// Diagnostics' raw events and the held-keys marker, at High priority. The companion (the control panel) starts it,
/// talks to it over <see cref="PipeChannel"/> (<see cref="IInputControl"/>, events back) and reads the overlay data
/// and held outputs from <see cref="SharedRegion"/>.
/// <para>
/// Lifetime: tray Exit (<see cref="Exit"/>) stops it. When the companion goes away without saying so (it crashed),
/// input keeps going and starts the companion again, which attaches to it. Only one runs per user session.
/// </para>
/// <para>
/// This class must never touch System.Windows.Forms or WPF types: the input process doesn't load them (step 3's test
/// checks the loaded assemblies).
/// </para>
/// </summary>
internal sealed class InputHost : IHostEvents, IPairPrompt, IInputControl, IDisposable
{
    public const string Arg = "--input";
    private const string MutexName = SplitNames.InputMutex;
    /// <summary>How long after the companion went away (not by Exit) it is started again.</summary>
    private const int RelaunchDelayMs = 2000;
    /// <summary>A companion that keeps crashing (more than 5 starts within 10 minutes) is started again only once a minute.</summary>
    private const int SlowRelaunchDelayMs = 60_000;
    private readonly Queue<long> _relaunches = new(); // the accept loop's only
    private const int FastBindMs = 5000;

    /// <summary>The pipe a companion connects to: one per user and Windows session.</summary>
    public static string PipeName => SplitNames.Pipe(Process.GetCurrentProcess().SessionId);

    public static string Version =>
        typeof(InputHost).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "?";

    /// <summary>True while an input process runs in this session.</summary>
    public static bool IsRunning()
    {
        try
        {
            if (!Mutex.TryOpenExisting(MutexName, out var m)) return false;
            m.Dispose();
            return true;
        }
        catch (UnauthorizedAccessException)
        {
            return true; // an elevated one owns it
        }
    }

    private readonly FileLog _log;
    private readonly FileLog _inputLog;
    private readonly string _exePath;
    private readonly IClock _clock = StopwatchClock.Instance;
    private readonly KeyMouseRouter _keyMouse;
    private readonly HeldKeysMarker _marker;
    private readonly HostEngine _engine;
    private readonly UdpHostServer _server;
    private readonly Watchdog _watchdog;
    private readonly KeyFiles _keyFiles;
    private AuthorizedPhonesWatcher? _authorized;
    private readonly AoaUsbLink _usb;
    private readonly SharedRegion _region;
    private readonly string _regionName;
    private readonly EventWaitHandle _overlayWake;
    private readonly string _overlayWakeName;
    private readonly EventOutbox _out = new();
    private IPadSink _pad;
    private volatile string? _padError;
    private volatile string? _keyError;
    private volatile bool _keyRetrying;
    private volatile bool _bindRetrying, _bindWaitLong;
    private bool _inputDriverInstalled;
    private DeliveryProbe? _probe;
    private readonly object _probeGate = new();
    private bool _diagOn, _testOn;
    private int _diagSaved;
    private readonly ManualResetEventSlim _exit = new();
    private int _exited;
    private volatile bool _exitAsked;
    private int _statusQueued;
    private readonly AutoResetEvent _statusWake = new(false);
    private volatile int _companionPid;
    private System.Threading.Timer? _driverTimer;

    public static int Run(string[] args)
    {
        using var mutex = new Mutex(initiallyOwned: true, MutexName, out bool owned);
        if (!owned) return 0; // one input process per session
        // The input process's own priority: above the game's normal-priority threads, so it is never queued behind them.
        try { Process.GetCurrentProcess().PriorityClass = ProcessPriorityClass.High; } catch (Exception) { }
        GCSettings.LatencyMode = GCLatencyMode.SustainedLowLatency;
        InputHost host;
        try
        {
            host = new InputHost(args.Contains(Program.WokenArg) || args.Contains(Program.UpdatedArg));
        }
        catch (IOException)
        {
            return 1; // settings.json stayed locked: the companion says so
        }
        // Windows shutting down or logging off: let go of everything and say goodbye, as Exit does.
        AppDomain.CurrentDomain.ProcessExit += (_, _) => host.Shutdown();
        using (host)
        {
            host.Serve();
            host._exit.Wait();
            host.Shutdown();
        }
        mutex.ReleaseMutex();
        return 0;
    }

    private InputHost(bool longWait)
    {
        _log = new FileLog(echo: null);
        _inputLog = new FileLog(fileName: "input.log", maxBytes: 5_000_000);
        _exePath = Environment.ProcessPath ?? "";
        _log.Write($"input {Version} starting, elevated {ElevationProbe.IsSelfElevated}, pid {Environment.ProcessId}");
        int lockedWaitMs = longWait ? SettingsStore.LongLockedWaitMs : SettingsStore.DefaultLockedWaitMs;

        _marker = new HeldKeysMarker(HeldKeysMarker.DefaultPath());
        var store = new SettingsStore(SettingsStore.DefaultPath(), lockedWaitMs);
        var settings = store.Load(Environment.MachineName, _log.Write);
        _keyMouse = new KeyMouseRouter(new SendInputSink(_log.Write), settings.UseInputDriver, _log.Write);
        _pad = PadSinks.Create(_log.Write, out var padError);
        _padError = padError;
        var protector = new DpapiKeyProtector();
        _keyFiles = new KeyFiles(KeyFiles.DefaultDirectory(), lockedWaitMs);
        GyroPhone.Protocol.Crypto.P256? hostKey = null;
        try
        {
            hostKey = _keyFiles.LoadOrCreateHostKey(protector, settings.HostName, _log.Write);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Security.Cryptography.CryptographicException)
        {
            _keyError = $"{e.GetType().Name}: {e.Message}";
            _keyRetrying = e is IOException;
            _log.Write($"key file pairing off: {_keyError}{(_keyRetrying ? "; retrying in the background" : "")}");
        }

        // The memory the companion maps (kept from a run that crashed while the companion held it: nothing held now),
        // and the event that wakes its overlay thread.
        _regionName = SplitNames.Region;
        _region = SharedRegion.CreateOrOpen(_regionName);
        _region.Held.Reset();
        _overlayWakeName = SplitNames.OverlayWake;
        _overlayWake = new EventWaitHandle(false, EventResetMode.AutoReset, _overlayWakeName);

        _engine = new HostEngine(settings, _pad, _keyMouse, this, protector, _clock, this, hostKey: hostKey,
            shared: _region, debugForward: (us, kind, text) => _out.Post("debug", new DebugLine(us, kind, text)))
        {
            Recorder = new InputRecorder(RecordLine),
            OverlayWake = () => _overlayWake.Set(),
        };
        _engine.SetPcPad(new XInputPcPad(_log.Write, _clock));
        _engine.SetKeyMouseListener(new RawInputListener(_log.Write));
        // The Performance monitor is the companion's: each second of the link and the phone goes there.
        _engine.Recorder!.Timeline = new LinkTimeline { Added = s => _out.Post("link-second", s) };
        _engine.PhoneTimeline = new PhoneTimeline { Added = s => _out.Post("phone-second", s) };
        _engine.Recorder!.SecondNote = () =>
        {
            string? text = _probe?.SecondText();
            return _diagOn ? text : null; // a Delivery test alone adds nothing to the log
        };
        _engine.DiagnosticsChanged = on =>
        {
            SetDeliveryProbe(on);
            StateChanged();
        };
        _engine.DebugSaveRequested = () => _out.Post("debug-save");
        SetDeliveryProbe(settings.Diagnostics);
        if (hostKey != null) _authorized = new AuthorizedPhonesWatcher(_keyFiles, _engine.SetAuthorizedPhones, _log.Write);
        PowerQos.TryDisableForProcess(_log.Write);
        _server = new UdpHostServer(_engine, _clock,
            onRxThreadStart: () =>
            {
                Mmcss.TryEnter("Games", Mmcss.AvrtPriorityHigh, _log.Write);
                PowerQos.TryDisableForThread(_log.Write);
            },
            log: _log.Write,
            catchUpWait: new ExactCatchUpWait(_log.Write));
        _usb = new AoaUsbLink(Wire.Port, settings.UsbDirect, _log.Write);
        _watchdog = new Watchdog(_engine, _clock, _marker, store,
            networkPublicProbe: NetworkCategory.IsAnyConnectedPublic, log: _log.Write,
            keyLabelsProbe: KeyboardLayoutProbe.Read, tickSleep: () => new HighResolutionSleep(),
            pcSetupProbe: PcSetupProbe.Read);

        var status = new Thread(StatusLoop) { Name = "gp-status", IsBackground = true, Priority = ThreadPriority.BelowNormal };
        status.Start();
        StartServer();
        if (_keyRetrying) RetryHostKey(protector, settings.HostName);
    }

    // =====================================================================================
    // the companion's line
    // =====================================================================================

    /// <summary>Takes companions on the pipe, one at a time, on its own thread (gp-pipe-accept).</summary>
    private void Serve()
    {
        var t = new Thread(AcceptLoop) { Name = "gp-pipe-accept", IsBackground = true, Priority = ThreadPriority.BelowNormal };
        t.Start();
    }

    private void AcceptLoop()
    {
        while (Volatile.Read(ref _exited) == 0)
        {
            NamedPipeServerStream? pipe = null;
            try
            {
                pipe = new NamedPipeServerStream(PipeName, PipeDirection.InOut, 1, PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                pipe.WaitForConnection();
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                pipe?.Dispose();
                _log.Write($"input: pipe failed: {e.GetType().Name}: {e.Message}");
                Thread.Sleep(1000);
                continue;
            }
            if (Volatile.Read(ref _exited) != 0)
            {
                pipe.Dispose();
                return;
            }
            using var gone = new ManualResetEventSlim();
            var channel = new PipeChannel(pipe, PipeChannel.Dispatch<IInputControl>(this), null, _log.Write, "gp-pipe");
            channel.Closed += gone.Set;
            // A companion that died at once: its reader closed the channel before Closed had a handler.
            if (channel.IsClosed) gone.Set();
            _out.Channel = channel;
            gone.Wait();
            if (ReferenceEquals(_out.Channel, channel)) _out.Channel = null;
            int pid = _companionPid;
            _companionPid = 0;
            if (_exitAsked || Volatile.Read(ref _exited) != 0) return;
            int delay = RelaunchDelay();
            _log.Write($"input: the companion (pid {pid}) went away without Exit; starting it again in {delay / 1000} s");
            RelaunchCompanionSoon(pid, delay);
        }
    }

    /// <summary>Quick at first; once a minute when it keeps crashing, so a crash loop doesn't load the PC during play.</summary>
    private int RelaunchDelay()
    {
        long now = Environment.TickCount64;
        while (_relaunches.Count > 0 && now - _relaunches.Peek() > 600_000) _relaunches.Dequeue();
        _relaunches.Enqueue(now);
        return _relaunches.Count > 5 ? SlowRelaunchDelayMs : RelaunchDelayMs;
    }

    /// <summary>The companion crashed or was killed: start it again once it is surely gone, unless a new one attached.</summary>
    private void RelaunchCompanionSoon(int pid, int delayMs)
    {
        ThreadPool.QueueUserWorkItem(_ =>
        {
            try
            {
                if (pid > 0)
                    using (var p = Process.GetProcessById(pid))
                        p.WaitForExit(5000);
            }
            catch (Exception e) when (e is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception)
            {
                // already gone
            }
            Thread.Sleep(delayMs);
            if (_exitAsked || Volatile.Read(ref _exited) != 0 || _out.Channel != null || Program.CompanionRunning()) return;
            try
            {
                var psi = new ProcessStartInfo(_exePath, Program.ReattachArg)
                {
                    UseShellExecute = false,
                    WorkingDirectory = Path.GetDirectoryName(_exePath) ?? "",
                };
                psi.Environment.Remove("DOTNET_GCgen0size"); // the input process's own
                using var p = Process.Start(psi);
            }
            catch (Exception e) when (e is System.ComponentModel.Win32Exception or IOException)
            {
                _log.Write($"input: starting the companion failed: {e.GetType().Name}: {e.Message}");
            }
        });
    }

    // =====================================================================================
    // status for the companion (gp-status: coalesced, and once a second)
    // =====================================================================================

    public void StateChanged()
    {
        if (Interlocked.Exchange(ref _statusQueued, 1) == 0) _statusWake.Set();
    }

    private void StatusLoop()
    {
        while (Volatile.Read(ref _exited) == 0)
        {
            _statusWake.WaitOne(1000);
            Volatile.Write(ref _statusQueued, 0);
            if (_out.Channel == null) continue;
            try
            {
                _out.Post("status", Status());
            }
            catch (Exception e) when (e is not OutOfMemoryException)
            {
                _log.Write($"input: status failed: {e.GetType().Name}: {e.Message}");
            }
            // At most 10 a second: a burst of changes is one status (each takes the engine lock for a moment).
            Thread.Sleep(100);
        }
    }

    private InputStatus Status()
    {
        var s = _engine.Snapshot();
        return new InputStatus(s, InputSettingsView.Of(_engine.Settings), _server.State.ToString(), _bindRetrying, _bindWaitLong,
            _keyError, _keyRetrying, _padError, _keyMouse.DriverReady && _keyMouse.UseInputDriver, _inputDriverInstalled,
            _usb.Enabled, _usb.State.ToString(), _usb.Detail, _authorized?.LastScan,
            _keyMouse.SendInput.ShortWrites, _server.RxExceptions, _engine.PressLatencyNow(), ElevationProbe.IsSelfElevated,
            _keyFiles.Directory, _log.LastError, Volatile.Read(ref _diagSaved));
    }

    // =====================================================================================
    // engine callbacks (rx or watchdog thread, under the engine lock): queue only
    // =====================================================================================

    public void Log(string message) => _log.Write(message);

    public void Show(uint attemptId, string phoneName, string sasDisplay)
    {
        _out.Post("pair-show", new PairShow(attemptId, phoneName, sasDisplay));
        StateChanged();
    }

    public void Close(uint attemptId)
    {
        _out.Post("pair-close", attemptId);
        StateChanged();
    }

    public sealed record PairShow(uint AttemptId, string PhoneName, string Sas);

    /// <summary>The input recorder's lines: into Diagnostics (the companion's), and into input.log only during a Link test (its script reads it).</summary>
    private void RecordLine(string line)
    {
        if (_engine.Debug.Enabled) _engine.Debug.Add(DebugTrace.Kind.Link, line);
        if (_engine.LinkTestRunning) _inputLog.Write(line);
    }

    // =====================================================================================
    // start-up: the port, the key
    // =====================================================================================

    private void StartServer()
    {
        if (Watcher.IsRunning() && Watcher.AskToYieldPort()) _log.Write($"watcher running: asked it to let go of port {Wire.Port}");
        if (_server.Start())
        {
            ServerStarted();
            return;
        }
        if (_server.State != ServerState.PortInUse)
        {
            _log.Write("UDP server failed to start");
            return;
        }
        _log.Write($"port {Wire.Port} in use: retrying");
        _bindRetrying = true;
        new Thread(BindLoop) { Name = "gp-bind", IsBackground = true }.Start();
    }

    private void BindLoop()
    {
        var clock = Stopwatch.StartNew();
        while (Volatile.Read(ref _exited) == 0)
        {
            bool fast = clock.ElapsedMilliseconds < FastBindMs;
            Thread.Sleep(fast ? 25 : 1000);
            if (Volatile.Read(ref _exited) != 0) return;
            Watcher.AskToYieldPort();
            if (_server.Start(logFailure: false))
            {
                _log.Write($"port {Wire.Port} free after {clock.ElapsedMilliseconds} ms");
                ServerStarted();
                return;
            }
            if (_server.State != ServerState.PortInUse)
            {
                _log.Write("UDP server failed to start");
                _bindRetrying = false;
                StateChanged();
                return;
            }
            if (!fast && !_bindWaitLong)
            {
                _bindWaitLong = true;
                _log.Write($"port {Wire.Port} still in use by another program: retrying every second");
                StateChanged();
            }
        }
    }

    /// <summary>The port is ours: the rest of the start-up (owning the port is what makes this the one that runs).</summary>
    private void ServerStarted()
    {
        _bindRetrying = false;
        if (Volatile.Read(ref _exited) != 0)
        {
            _server.Stop();
            return;
        }
        // Keys a crashed run left held went through SendInput (a crashed run's driver devices are gone, and Windows let
        // go of what they held), so they are let go through SendInput.
        int recovered = HeldKeysMarker.Recover(_marker.Path, _keyMouse.SendInput);
        if (recovered > 0) _log.Write($"released {recovered} inputs left held by a previous run");
        CheckInputDriver();
        _engine.Start(_clock.NowUs);
        _watchdog.Start();
        _usb.Start();
        _log.Write($"listening on UDP {Wire.Port} as '{_engine.Settings.HostName}'");
        // The input driver: looked for every 5 s (installed later, or back after it went away).
        _driverTimer = new System.Threading.Timer(_ => CheckInputDriver(), null, 5000, 5000);
        StateChanged();
    }

    /// <summary>
    /// Notes whether the input driver is installed (Performance shows "Use input driver" only then) and, while that switch
    /// is on, plugs its mouse and keyboard when they aren't plugged (off the input path).
    /// </summary>
    private void CheckInputDriver()
    {
        if (Volatile.Read(ref _exited) != 0 || _keyMouse.DriverReady) return;
        bool installed = HidInputSink.Installed;
        if (installed != _inputDriverInstalled)
        {
            _inputDriverInstalled = installed;
            StateChanged();
        }
        if (_server.State != ServerState.Running || !installed || !_keyMouse.UseInputDriver) return;
        // Plugged here, off the engine lock; the route changes (letting go of everything held first) only once it did,
        // so a driver that won't plug costs input nothing. The same failure is logged once, not every 5 s.
        var sink = HidInputSink.Create(LogPlugOnce);
        if (sink == null) return;
        _lastPlugLine = null;
        bool ok = false;
        _engine.ChangeKeyMouseRoute(() => ok = _keyMouse.Adopt(sink), "input driver plugged");
        if (ok) StateChanged();
    }

    // The last line the input driver's plug logged, so a plug that keeps failing logs once.
    private string? _lastPlugLine;

    private void LogPlugOnce(string line)
    {
        if (line == _lastPlugLine) return;
        _lastPlugLine = line;
        _log.Write(line);
    }

    private void RetryHostKey(DpapiKeyProtector protector, string hostName)
    {
        var t = new Thread(() =>
        {
            while (Volatile.Read(ref _exited) == 0)
            {
                Thread.Sleep(5000);
                try
                {
                    var key = _keyFiles.LoadOrCreateHostKey(protector, hostName, _log.Write);
                    if (Volatile.Read(ref _exited) != 0 || !_engine.SetHostKey(key)) return;
                    _authorized ??= new AuthorizedPhonesWatcher(_keyFiles, _engine.SetAuthorizedPhones, _log.Write);
                    _keyError = null;
                    _keyRetrying = false;
                    _log.Write("host key read: key file pairing on");
                    StateChanged();
                    return;
                }
                catch (IOException e)
                {
                    _keyError = $"{e.GetType().Name}: {e.Message}";
                }
                catch (Exception e) when (e is UnauthorizedAccessException or System.Security.Cryptography.CryptographicException)
                {
                    _keyError = $"{e.GetType().Name}: {e.Message}";
                    _keyRetrying = false;
                    _log.Write($"key file pairing off: {_keyError}");
                    StateChanged();
                    return;
                }
            }
        }) { Name = "gp-key", IsBackground = true, Priority = ThreadPriority.BelowNormal };
        t.Start();
    }

    /// <summary>Starts or stops Diagnostics' delivery probe with Diagnostics (any thread).</summary>
    private void SetDeliveryProbe(bool on)
    {
        lock (_probeGate)
        {
            _diagOn = on;
            UpdateProbe();
        }
    }

    /// <summary>
    /// The probe runs while Diagnostics is on or a Delivery test runs; the engine's moves go to it, and to the shared
    /// ring the test window reads while a test runs. Off, the input path stores nothing. Under <see cref="_probeGate"/>.
    /// </summary>
    private void UpdateProbe()
    {
        bool want = _diagOn || _testOn;
        if (want && _probe == null)
        {
            _probe = new DeliveryProbe(_log.Write);
            _probe.Start();
        }
        if (_probe != null)
            _probe.SlowLine = _diagOn ? text => { if (_engine.Debug.Enabled) _engine.Debug.Add(DebugTrace.Kind.Note, text); } : null;
        var probe = want ? _probe : null;
        var ring = _region.Moves;
        _engine.MoveSent = probe == null ? null
            : _testOn ? (t, x, y) => { probe.Sent(t, x, y); ring.Put(t, x, y); }
            : probe.Sent;
        if (!want && _probe != null)
        {
            _probe.Dispose();
            _probe = null;
        }
    }

    public void SetDeliveryTest(bool on, bool counting)
    {
        lock (_probeGate)
        {
            if (on != _testOn)
            {
                _testOn = false;
                UpdateProbe(); // the ring gets nothing while it is emptied
                if (on) _region.Moves.Reset();
                _testOn = on;
                UpdateProbe();
                if (on) _probe!.StartTest(counting);
                else _probe?.StopTest();
                _log.Write($"input: delivery test {(on ? "started" : "stopped")}");
            }
            else if (on) _probe?.CountTest(counting);
        }
    }

    public DeliveryNumbers? DeliveryTestNumbers()
    {
        lock (_probeGate) return _testOn ? _probe?.TestNumbers() : null;
    }

    // =====================================================================================
    // IInputControl: the companion's calls (pipe thread)
    // =====================================================================================

    public InputHello Attach(int companionPid)
    {
        _companionPid = companionPid;
        _log.Write($"input: companion attached (pid {companionPid})");
        SetDeliveryTest(false, false); // a test window went with the companion before
        StateChanged();
        return new InputHello(Version, Environment.ProcessId, _regionName, SharedRegion.LayoutVersion, _overlayWakeName, Status());
    }

    public HostSnapshot Snapshot() => _engine.Snapshot();

    public void Exit()
    {
        _exitAsked = true;
        _log.Write("input: exit asked");
        _exit.Set();
    }

    public void SetOutputMode(OutputMode mode) => _engine.SetOutputMode(mode);

    public void SetPaused(bool paused) => _engine.SetPaused(paused);

    public void SetLocked(bool locked) => _engine.SetLocked(locked);

    public string? RetryDriver()
    {
        var pad = PadSinks.Create(_log.Write, out var error);
        if (error != null && !pad.DualSenseOk)
        {
            _padError = error;
            (pad as IDisposable)?.Dispose();
            StateChanged();
            return error;
        }
        // The engine unplugs and disposes the old sink on its pad thread.
        _engine.SetPadSink(pad);
        _pad = pad;
        _padError = error;
        _log.Write("gamepad driver found");
        StateChanged();
        return "";
    }

    public void SetCatchUpMs(int ms) => _engine.SetCatchUpMs(ms);

    public void SetUseInputDriver(bool on) => _engine.SetUseInputDriver(on, _keyMouse.SetUseInputDriver);

    public void OpenPairingWindow() => _engine.OpenPairingWindow();

    public void AnswerPair(uint attemptId, bool allow) => _engine.AnswerPair(attemptId, allow);

    public byte[]? OpenQrPairing() => _engine.OpenQrPairing(_keyFiles.AddAuthorized);

    public void CloseQrPairing() => _engine.CloseQrPairing();

    public byte[]? HostPublicKey() => _engine.HostPublicKey;

    public void Forget(PairedPhoneInfo phone)
    {
        if (!phone.ByKey)
        {
            _engine.Forget(phone.PhoneId);
            return;
        }
        try
        {
            if (phone.KeyFile is { } f && Path.GetDirectoryName(Path.GetFullPath(f)) is { } dir
                && string.Equals(Path.GetFullPath(dir).TrimEnd('\\'), Path.GetFullPath(_keyFiles.AuthorizedDirectory).TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                File.Delete(f);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _log.Write($"could not delete key file: {e.GetType().Name}");
        }
        _authorized?.Reload();
    }

    public void SetUsbDirect(bool on)
    {
        _usb.Enabled = on;
        _engine.SetUsbDirect(on);
        _log.Write($"usb direct {(on ? "on" : "off")}");
        StateChanged();
    }

    public void TakeUsbFromAdb() => _usb.TakeFromAdb();

    public void SetStartWithWindows(bool on) => _engine.SetStartWithWindows(on);

    public void SetDiagnostics(bool on) => _engine.SetDiagnostics(on);

    public void SetDiagnosticsMinutes(int minutes) => _engine.SetDiagnosticsMinutes(minutes);

    public void SaveDiagnostics()
    {
        Interlocked.Increment(ref _diagSaved);
        _engine.SaveDiagnostics();
    }

    public void SetPressKeyListen(bool on) => _engine.SetPressKeyListen(on);

    public void SetPressKeyNotice(bool on) => _engine.SetPressKeyNotice(on);

    public void SetMeasureButtons(bool on) => _engine.SetMeasureButtons(on);


    public void SetLinkWatch(bool on) => _engine.SetLinkWatch(on);

    public void SetOverlay(bool on, bool compass, bool samples, bool keys = false) => _engine.SetOverlay(on, compass, samples, keys);

    // =====================================================================================
    // exit
    // =====================================================================================

    /// <summary>Release-all, HOST_BYE(EXIT), unplug, save, clear the held-keys marker. Runs once.</summary>
    private void Shutdown()
    {
        if (Interlocked.Exchange(ref _exited, 1) != 0) return;
        try
        {
            _driverTimer?.Dispose();
            _authorized?.Dispose();
            _watchdog.Dispose();
            _engine.Shutdown();
            _server.Stop();
            _usb.Dispose();
            _watchdog.SaveSettings();
            var held = new byte[StateFrame.KeyBitmapSize];
            int version = _engine.HeldSnapshot(held, out byte mb);
            _marker.Update(version, held, mb);
            (_pad as IDisposable)?.Dispose();
            SetDeliveryProbe(false);
            _keyMouse.Dispose();
            _out.Flush();
            _out.Channel?.Close();
            _log.Write("input: stopped");
        }
        catch (Exception e)
        {
            _log.Write($"input: shutdown error: {e.GetType().Name}");
        }
    }

    public void Dispose()
    {
        Shutdown();
        _out.Dispose();
        _inputLog.Dispose();
        _log.Dispose();
    }
}
