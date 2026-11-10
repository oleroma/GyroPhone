using System.ComponentModel;
using System.Diagnostics;
using System.Text;
using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;
using GyroPhone.Ui;
using GyroPhone.Windows;
using Microsoft.Win32;

namespace GyroPhone.Tray;

/// <summary>
/// The tray application: owns the engine, UDP server and watchdog, the notification-area icon and
/// its short menu, and the pair and main windows (WPF, from GyroPhone.Ui, which reach the app through
/// <see cref="ICompanion"/>). Engine callbacks (IHostEvents, IPairPrompt) arrive on the
/// rx or watchdog thread under the engine lock, so they only post to the UI thread.
/// </summary>
internal sealed class TrayApp : ApplicationContext, IHostEvents, IPairPrompt, ICompanion
{
    private const long FirewallHintAfterUs = 30_000_000;

    private readonly FileLog _log;
    /// <summary>The always-on input log (input.log next to companion.log), written by the engine's InputRecorder.</summary>
    private readonly FileLog _inputLog;
    private readonly string _exePath;
    private readonly IClock _clock = StopwatchClock.Instance;
    private readonly long _startUs;
    private readonly Control _ui;
    private readonly SendInputSink _keyMouse;
    private readonly HeldKeysMarker _marker;
    private readonly HostEngine _engine;
    private readonly UdpHostServer _server;
    private readonly Watchdog _watchdog;
    private readonly KeyFiles _keyFiles;
    private AuthorizedPhonesWatcher? _authorized;
    private readonly AoaUsbLink _usb;
    private IPadSink _pad;
    private string? _padError;

    private readonly TrayIcons _icons = new();
    private readonly NotifyIcon _tray;
    private readonly ContextMenuStrip _menu;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _outX360, _outDs4, _outDualSense, _outOff;
    private readonly ToolStripMenuItem _problemsItem;
    private readonly ToolStripMenuItem _pauseItem;
    private readonly System.Windows.Forms.Timer _uiTimer;
    private readonly CompanionUpdater _updater;
    private readonly ToolStripMenuItem _versionItem;

    private PairWindow? _pairWindow;
    private MainWindow? _mainWindow;
    private System.Windows.Media.ImageSource? _windowIcon;
    private string _statusLine = "";
    // While Refresh updates the main window: its snapshot and problems, so View doesn't take them again.
    private (HostSnapshot Snapshot, List<Problem> Problems)? _refreshing;
    private DebugForm? _debugForm;
    // The open overlay windows, by settings id.
    private readonly Dictionary<string, OverlayForm> _overlayForms = [];
    // The open windows, for the pacer thread (the dictionary is the UI thread's).
    private volatile OverlayForm[] _overlayList = [];
    private OverlayWheel? _overlayWheel;
    // The overlays' global shortcuts; null until the server owns the port (one instance holds them).
    private Hotkeys? _hotkeys;
    private OverlayPacer? _pacer;
    // Read by the wheel hook's thread too.
    private volatile string? _selectedOverlay;
    private int _refreshQueued;
    private bool? _firewallOk;
    private bool? _usbRuleOk;
    /// <summary>Why key file pairing is off (the host key could not be loaded), or null.</summary>
    private volatile string? _keyError;
    /// <summary>host.key was held by another program at start-up: it is tried again in the background.</summary>
    private volatile bool _keyRetrying;
    /// <summary>The port was taken at start-up: gp-bind keeps trying; true once the first 5 s have passed.</summary>
    private volatile bool _bindRetrying, _bindWaitLong;
    private bool _firewallHintShown, _publicHintShown;
    private bool _sessionLocked, _suspended;
    private int _exited;

    public TrayApp()
    {
        _log = new FileLog();
        _inputLog = new FileLog(fileName: "input.log", maxBytes: 5_000_000);
        _exePath = Environment.ProcessPath ?? Application.ExecutablePath;
        _startUs = _clock.NowUs;
        _ui = new Control();
        _ = _ui.Handle; // create the window handle so BeginInvoke works from other threads
        _log.Write($"starting {Application.ProductVersion}, elevated {ElevationProbe.IsSelfElevated}");

        _keyMouse = new SendInputSink(_log.Write);
        _marker = new HeldKeysMarker(HeldKeysMarker.DefaultPath());

        var store = new SettingsStore(SettingsStore.DefaultPath(), Program.LockedFileWaitMs);
        var settings = store.Load(Environment.MachineName, _log.Write);
        _pad = PadSinks.Create(_log.Write, out _padError);
        var protector = new DpapiKeyProtector();
        _keyFiles = new KeyFiles(KeyFiles.DefaultDirectory(), Program.LockedFileWaitMs);
        GyroPhone.Protocol.Crypto.P256? hostKey = null;
        try
        {
            hostKey = _keyFiles.LoadOrCreateHostKey(protector, settings.HostName, _log.Write);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Security.Cryptography.CryptographicException)
        {
            _keyError = $"{e.GetType().Name}: {e.Message}";
            // Only held by another program for longer than the wait: keep trying instead of leaving pairing off.
            _keyRetrying = e is IOException;
            _log.Write($"key file pairing off: {_keyError}{(_keyRetrying ? "; retrying in the background" : "")}");
        }
        _engine = new HostEngine(settings, _pad, _keyMouse, this, protector, _clock, this, hostKey: hostKey)
        {
            Recorder = new InputRecorder(_inputLog.Write),
        };
        _engine.SetPcPad(new XInputPcPad(_log.Write, _clock));
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
            keyLabelsProbe: KeyboardLayoutProbe.Read, tickSleep: () => new HighResolutionSleep());

        // Keep the Run entry pointing at this exe (it may have moved since it was enabled).
        if (settings.StartWithWindows) TrySetAutoStart(true);

        _menu = new ContextMenuStrip();
        _statusItem = new ToolStripMenuItem("Starting…") { Enabled = false };
        _problemsItem = new ToolStripMenuItem("", null, (_, _) => ShowMainWindow(MainPage.Home)) { Available = false };
        _outX360 = new ToolStripMenuItem("Xbox 360", null, (_, _) => _engine.SetOutputMode(OutputMode.Xbox360));
        _outDs4 = new ToolStripMenuItem("DualShock 4", null, (_, _) => _engine.SetOutputMode(OutputMode.DualShock4));
        _outDualSense = new ToolStripMenuItem("DualSense", null, (_, _) => _engine.SetOutputMode(OutputMode.DualSense)) { Available = false };
        _outOff = new ToolStripMenuItem("Off (keyboard and mouse only)", null, (_, _) => _engine.SetOutputMode(OutputMode.None));
        var outputItem = new ToolStripMenuItem("Gamepad output");
        outputItem.DropDownItems.AddRange(new ToolStripItem[] { _outX360, _outDs4, _outDualSense, _outOff });
        var pairItem = new ToolStripMenuItem("Pair new phone…", null, (_, _) => PairNewPhone());
        var mainWindowItem = new ToolStripMenuItem("Open GyroPhone", null, (_, _) => ShowMainWindow());
        mainWindowItem.Font = new Font(mainWindowItem.Font, FontStyle.Bold);
        _pauseItem = new ToolStripMenuItem("Pause input", null, (_, _) => TogglePaused());
        // Built each time it opens: one switch per overlay window, then the ones for all of them.
        var overlayItem = new ToolStripMenuItem("Overlays");
        overlayItem.DropDownItems.Add(new ToolStripMenuItem("…"));
        overlayItem.DropDownOpening += (_, _) => BuildOverlayMenu(overlayItem);
        // The phone's Record button: save this PC's side at once (the ring keeps moving).
        _engine.DebugSaveRequested = () => Post(() =>
        {
            var path = SaveDebug();
            if (_debugForm is { IsDisposed: false } f) f.ShowSaved(path);
        });
        var exitItem = new ToolStripMenuItem("Exit", null, (_, _) => ExitApp());
        _versionItem = new ToolStripMenuItem($"{Program.AppName} {Application.ProductVersion}") { Enabled = false };
        // Short: the everyday switches. Everything else is in the main window.
        _menu.Items.AddRange(new ToolStripItem[]
        {
            _versionItem,
            _statusItem,
            _problemsItem,
            new ToolStripSeparator(),
            mainWindowItem,
            _pauseItem,
            outputItem,
            overlayItem,
            pairItem,
            new ToolStripSeparator(),
            exitItem,
        });
        _menu.Opening += (_, _) => Refresh();

        _tray = new NotifyIcon
        {
            Icon = _icons.For(TrayState.Waiting),
            Text = Program.AppName,
            ContextMenuStrip = _menu,
            Visible = true,
        };
        _tray.MouseClick += (_, e) =>
        {
            if (e.Button == MouseButtons.Left) TrayClicked();
        };

        SystemEvents.SessionSwitch += OnSessionSwitch;
        SystemEvents.PowerModeChanged += OnPowerModeChanged;
        Application.ApplicationExit += OnApplicationExit;
        AppDomain.CurrentDomain.ProcessExit += OnProcessExit;

        _updater = new CompanionUpdater(_exePath, Application.ProductVersion, _log.Write,
            exitForUpdate: ExitApp, updateFailed: Program.UpdateFailed);
        _updater.Changed += Refresh;

        _uiTimer = new System.Windows.Forms.Timer { Interval = 1000 };
        _uiTimer.Tick += (_, _) => Refresh();

        StartServer();
        _uiTimer.Start();
        _updater.Start();
        CheckFirewallAsync();
        if (_keyRetrying) RetryHostKey(protector, settings.HostName);
        Refresh();

        if (_padError != null)
            Balloon("Gamepad output is off", $"{_padError}. Keyboard and mouse still work. Open GyroPhone and see the Gamepad page.", ToolTipIcon.Warning);
        if (Program.JustUpdated)
            Balloon("GyroPhone Companion updated", $"Now running {Application.ProductVersion}. Pairing and settings are kept.", ToolTipIcon.Info);
    }

    /// <summary>
    /// The port is ours (UI thread): the rest of the start-up. Owning the port is the single-instance guard,
    /// so only now are the held-keys marker, the engine's timers, the cable and the overlays started.
    /// </summary>
    private void ServerStarted()
    {
        _bindRetrying = false;
        if (Volatile.Read(ref _exited) != 0)
        {
            _server.Stop(); // bound just as the app exited
            return;
        }
        int recovered = HeldKeysMarker.Recover(_marker.Path, _keyMouse);
        if (recovered > 0) _log.Write($"released {recovered} inputs left held by a previous run");
        _engine.Start(_clock.NowUs);
        _watchdog.Start();
        _usb.Start();
        _log.Write($"listening on UDP {Wire.Port} as '{_engine.Settings.HostName}'");
        _pacer = new OverlayPacer(TickOverlays, OverlayWantHz, OverlayScreenHz, _log.Write);
        ApplyOverlays(); // shown last time: shown again
        try
        {
            _hotkeys = new Hotkeys(OnShortcut);
            ApplyShortcuts();
        }
        catch (Exception e)
        {
            _log.Write($"overlay shortcuts off: {e.GetType().Name}: {e.Message}");
        }
        Refresh();
        // First run: the engine opened the pairing window because nothing is paired yet.
        if (_engine.Snapshot().PairingWindowS > 0) OpenPairForm();
    }

    /// <summary>
    /// host.key stayed locked through the start-up wait (gp-key thread): read it again every few seconds,
    /// and once it reads, turn key file and QR code pairing on. Until then Home shows it as a problem.
    /// </summary>
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
                    Post(() =>
                    {
                        if (Volatile.Read(ref _exited) != 0 || !_engine.SetHostKey(key)) return;
                        _authorized ??= new AuthorizedPhonesWatcher(_keyFiles, _engine.SetAuthorizedPhones, _log.Write);
                        _keyError = null;
                        _keyRetrying = false;
                        _log.Write("host key read: key file pairing on");
                        Refresh();
                    });
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
                    Post(Refresh);
                    return;
                }
            }
        }) { Name = "gp-key", IsBackground = true, Priority = ThreadPriority.BelowNormal };
        t.Start();
    }

    // =====================================================================================
    // engine callbacks (rx / watchdog thread, under the engine lock): post only
    // =====================================================================================

    public void StateChanged()
    {
        if (Interlocked.Exchange(ref _refreshQueued, 1) == 0) Post(Refresh);
    }

    public void Log(string message) => _log.Write(message);

    public void Show(uint attemptId, string phoneName, string sasDisplay) => Post(() =>
    {
        OpenPairForm();
        _pairWindow?.RefreshNow();
        Balloon("Pair a phone?", $"{phoneName} wants to pair. Code {sasDisplay}", ToolTipIcon.Info);
    });

    public void Close(uint attemptId) => Post(() => _pairWindow?.RefreshNow());

    private void Post(Action action)
    {
        if (Volatile.Read(ref _exited) != 0) return;
        try
        {
            _ui.BeginInvoke(action);
        }
        catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException)
        {
            // exiting: the marshalling window is gone
        }
    }

    // =====================================================================================
    // UI thread
    // =====================================================================================

    private void Refresh()
    {
        Volatile.Write(ref _refreshQueued, 0);
        if (Volatile.Read(ref _exited) != 0) return;
        var s = _engine.Snapshot();
        bool serverOk = _server.State == ServerState.Running;
        bool padWanted = s.OutputMode != OutputMode.None;

        TrayState state;
        string status;
        if (!serverOk && _bindRetrying && !_bindWaitLong)
        {
            state = TrayState.Waiting;
            status = "Starting…";
        }
        else if (!serverOk)
        {
            state = TrayState.Error;
            status = _server.State == ServerState.PortInUse ? $"Port {Wire.Port} in use" : "Network error: restart the app";
        }
        else if (s.Connected)
        {
            state = padWanted && (!s.OutputDriverOk || !s.PadPlugged) ? TrayState.Warning : TrayState.Connected;
            status = s.Silent ? $"{s.PhoneName} · no signal" : $"{s.PhoneName} · {s.RxPerSec}/s · {s.LossPermille / 10.0:0.0}% · p99 {Ms(s.LatP99Us)}";
            if (s.Paused) status += " · paused";
        }
        else
        {
            state = padWanted && !s.OutputDriverOk ? TrayState.Warning : TrayState.Waiting;
            status = s.PairingWindowS > 0 ? $"Waiting for phone · pairing open {s.PairingWindowS / 60}:{s.PairingWindowS % 60:00}" : "Waiting for phone";
        }
        _statusLine = status;
        _statusItem.Text = status;
        _tray.Icon = _icons.For(state);
        _tray.Text = TrayText(_firewallHintShown && !s.AnyDatagramSeen ? "Not seeing your phone? Open GyroPhone" : status);

        _outX360.Checked = s.OutputMode == OutputMode.Xbox360;
        _outDs4.Checked = s.OutputMode == OutputMode.DualShock4;
        _outDualSense.Checked = s.OutputMode == OutputMode.DualSense;
        _outDualSense.Available = s.DualSenseOk || s.OutputMode == OutputMode.DualSense;
        _outOff.Checked = s.OutputMode == OutputMode.None;
        _pauseItem.Checked = s.Paused;
        _versionItem.Text = _updater.Pending is { } pending
            ? $"{Program.AppName} {Application.ProductVersion} · restarting into {pending}"
            : $"{Program.AppName} {Application.ProductVersion}";
        var problemList = Problems(s);
        int problems = problemList.Count;
        _problemsItem.Available = problems > 0;
        _problemsItem.Text = problems == 1 ? "⚠ 1 thing to fix: open Home" : $"⚠ {problems} things to fix: open Home";
        _refreshing = (s, problemList);
        try
        {
            _mainWindow?.RefreshNow();
        }
        finally
        {
            _refreshing = null;
        }

        if (s.NetworkPublic && !_publicHintShown)
        {
            _publicHintShown = true;
            Balloon("Network is Public", "Windows blocks the phone on Public networks. Set this network to Private in Settings.", ToolTipIcon.Warning);
        }
        if (!s.AnyDatagramSeen && serverOk && !_firewallHintShown && _clock.NowUs - _startUs >= FirewallHintAfterUs)
        {
            _firewallHintShown = true;
            Balloon("Not seeing your phone?", "Open GyroPhone: Home shows what to fix. Check the phone is on the same Wi-Fi.", ToolTipIcon.Info);
        }
    }

    /// <summary>What stops the phone or the gamepad from working, each with its fix, for Home's warning bars.</summary>
    private List<Problem> Problems(HostSnapshot s)
    {
        var list = new List<Problem>();
        if (_server.State != ServerState.Running && !(_bindRetrying && !_bindWaitLong))
            list.Add(new Problem(_server.State == ServerState.PortInUse
                ? $"Port {Wire.Port} is in use by another program, so the phone can't connect. Close it: GyroPhone keeps trying and starts listening once it is free."
                : "The network server failed to start. Restart GyroPhone; the log has the details.", "Open log folder", OpenLogFolder));
        if (_keyError != null)
            list.Add(new Problem(_keyRetrying
                ? $"Pairing by QR code and key files is off for now: host.key is held by another program ({_keyError}). GyroPhone keeps trying."
                : $"Pairing by QR code and key files is off: host.key could not be read ({_keyError}).", "Open log folder", OpenLogFolder));
        if (s.NetworkPublic)
            list.Add(new Problem("This network is Public, so Windows blocks the phone.", "Make it Private…", OpenNetworkSettings));
        if (_firewallOk == false)
            list.Add(new Problem("GyroPhone isn't allowed through the firewall yet, so Windows may block the phone.", "Allow…", AllowThroughFirewall));
        else if (_firewallHintShown && !s.AnyDatagramSeen && !s.NetworkPublic)
            list.Add(new Problem("No phone seen yet. Check the phone is on the same Wi-Fi as this PC and GyroPhone is open on it."));
        if (s.OutputMode == OutputMode.DualSense && !s.DualSenseOk)
            list.Add(new Problem("No gamepad: the DualSense needs the GyroPhone pad driver (driver\\install.cmd next to the companion), then restart GyroPhone. Keyboard and mouse still work."));
        else if (s.OutputMode != OutputMode.None && s.OutputMode != OutputMode.DualSense && !s.DriverOk)
            list.Add(new Problem($"No gamepad: {_padError ?? "the ViGEmBus driver is missing"}. Keyboard and mouse still work.", "Install driver…", OpenDriverDownload));
        if (s.ElevatedForeground && !ElevationProbe.IsSelfElevated)
            list.Add(new Problem("The app in front runs as administrator, so input can't reach it.", "Restart as administrator", RestartAsAdmin));
        if (_usb.Enabled && _usb.State == UsbDirectState.AdbBusy)
            list.Add(new Problem("adb is holding the phone, so the USB cable can't be used.", "Stop adb and use the cable", _usb.TakeFromAdb));
        if (_usb.Enabled && _usb.State == UsbDirectState.NeedsDriver)
            list.Add(new Problem("The phone is on the USB cable but Windows needs the WinUSB driver for it.", "How to install…", ShowUsbDriverHelp));
        return list;
    }

    // =====================================================================================
    // ICompanion: what the WPF windows read and ask for
    // =====================================================================================

    public CompanionView View()
    {
        var s = _refreshing?.Snapshot ?? _engine.Snapshot();
        var help = !_usb.Enabled ? UsbHelp.None : _usb.State switch
        {
            UsbDirectState.AdbBusy => UsbHelp.AdbBusy,
            UsbDirectState.NeedsDriver => UsbHelp.NeedsDriver,
            _ => UsbHelp.None,
        };
        string server = _server.State == ServerState.Running ? $"Listening on UDP port {Wire.Port}."
            : _server.State == ServerState.PortInUse ? $"Port {Wire.Port} is in use{(_bindWaitLong ? " by another program" : "")}; retrying." : $"Not running ({_server.State}).";
        string hostKey = s.HostKeyFingerprint is { } fp ? $"This PC's key: {fp}"
            : $"This PC has no key{(_keyError != null ? $" ({_keyError})" : "")}: pairing by QR code and key files is off.";
        return new CompanionView(s,
            $"{Program.AppName} {Application.ProductVersion}",
            _statusLine,
            _updater.Pending,
            IsAutoStartOn(),
            IsWatcherOn(),
            TrayClick,
            _usb.Enabled,
            UsbDirectText(),
            help,
            _firewallOk,
            _usbRuleOk,
            ElevationProbe.IsSelfElevated,
            server,
            s.DriverOk ? null : _padError,
            hostKey,
            _keyFiles.Directory,
            _log.Path,
            _refreshing?.Problems ?? Problems(s),
            _engine.Settings.MeasureButtons,
            _engine.PressLatencyNow(),
            _engine.Settings.InputLog,
            _engine.Settings.CatchUpMs);
    }

    public void SetLatencyScreen(bool on) => _engine.SetLatencyScreen(on);

    public void SetCatchUpMs(int ms)
    {
        _engine.SetCatchUpMs(ms);
        Refresh();
    }

    public void ToggleInputLog()
    {
        _engine.SetInputLog(!_engine.Settings.InputLog);
        Refresh();
    }

    public void ToggleMeasureButtons()
    {
        _engine.SetMeasureButtons(!_engine.Settings.MeasureButtons);
        Refresh();
    }

    public string DetailsText() => StatusText();

    public void TakeUsbFromAdb() => _usb.TakeFromAdb();

    public void ShowDebugWindow() => ShowDebugForm();

    public string? FormatShortcut(int virtualKey, bool ctrl, bool alt, bool shift)
    {
        var keys = (Keys)virtualKey;
        if (ctrl) keys |= Keys.Control;
        if (alt) keys |= Keys.Alt;
        if (shift) keys |= Keys.Shift;
        return Hotkeys.Format(keys);
    }

    public int? PickColor(int argb, IntPtr owner)
    {
        var now = Color.FromArgb(argb);
        using var dlg = new ColorDialog { Color = Color.FromArgb(255, now), FullOpen = true, AnyColor = true };
        if (dlg.ShowDialog(new OwnerWindow(owner)) != DialogResult.OK) return null;
        // The picker has no alpha: keep the colour's own (some defaults are see-through).
        return (now.A << 24) | (dlg.Color.ToArgb() & 0xFFFFFF);
    }

    /// <summary>A WPF window's handle as a WinForms dialog owner.</summary>
    private sealed class OwnerWindow(IntPtr handle) : IWin32Window
    {
        public IntPtr Handle { get; } = handle;
    }

    /// <summary>A left click on the tray icon: what the "Clicking the tray icon" setting says.</summary>
    private void TrayClicked()
    {
        if (TrayClick == TrayClickAction.PairNewPhone) PairNewPhone();
        else ShowMainWindow();
    }

    public TrayClickAction TrayClick => _engine.Settings.TrayClick;
    public IReadOnlyList<OverlaySettings> Overlays => _engine.Settings.Overlays;

    public OverlaySettings? OverlayOf(string id) => _engine.Settings.Overlays.Find(o => o.Id == id);

    /// <summary>Its kind's name, numbered when there are several of that kind.</summary>
    public string OverlayName(OverlaySettings o)
    {
        var same = _engine.Settings.Overlays.Where(x => x.Kind == o.Kind).ToList();
        var name = OverlaySettings.KindName(o.Kind);
        return same.Count > 1 ? $"{name} {same.IndexOf(o) + 1}" : name;
    }

    public void SetTrayClick(TrayClickAction action)
    {
        _engine.SetTrayClick(action);
        Refresh();
    }

    public void SetOutputMode(OutputMode mode) => _engine.SetOutputMode(mode);

    public void TogglePaused() => _engine.SetPaused(!_engine.Snapshot().Paused);

    public void PairNewPhone()
    {
        _engine.OpenPairingWindow();
        OpenPairForm();
        _pairWindow?.PairAgain();
    }

    private void OpenPairForm()
    {
        try
        {
            OpenPairWindow();
        }
        catch (Exception e)
        {
            WindowFailed(e);
        }
    }

    private void OpenPairWindow()
    {
        if (_pairWindow == null)
        {
            if (!StartWpf()) return;
            var w = new PairWindow(_engine, _engine.Settings.HostName, _engine.HostPublicKey != null ? _keyFiles : null,
                "Pair new phone - " + Program.AppName, WindowIcon());
            System.Windows.Forms.Integration.ElementHost.EnableModelessKeyboardInterop(w);
            w.Closed += (_, _) =>
            {
                if (ReferenceEquals(_pairWindow, w)) _pairWindow = null;
            };
            _pairWindow = w;
        }
        _pairWindow.Show();
        _pairWindow.Activate();
    }

    private void ShowMainWindow(MainPage? page = null)
    {
        try
        {
            OpenMainWindow(page);
        }
        catch (Exception e)
        {
            WindowFailed(e);
        }
    }

    private void WindowFailed(Exception e)
    {
        _log.Write($"window failed: {e}");
        Balloon("GyroPhone window failed", "The tray menu still works. The log has the details.", ToolTipIcon.Warning);
    }

    private void OpenMainWindow(MainPage? page)
    {
        if (_mainWindow == null)
        {
            if (!StartWpf()) return;
            var w = new MainWindow(this, Program.AppName, WindowIcon());
            System.Windows.Forms.Integration.ElementHost.EnableModelessKeyboardInterop(w);
            w.Closed += (_, _) =>
            {
                if (ReferenceEquals(_mainWindow, w)) _mainWindow = null;
            };
            _mainWindow = w;
        }
        if (page is { } p) _mainWindow.Go(p);
        _mainWindow.Show();
        if (_mainWindow.WindowState == System.Windows.WindowState.Minimized) _mainWindow.WindowState = System.Windows.WindowState.Normal;
        _mainWindow.Activate();
        Refresh();
    }

    private bool _wpfStarted;

    /// <summary>
    /// The WPF side, once, before the first window: its application object, styles and colours. An
    /// error in a window is logged and dropped: it must never take the companion (and the input) down.
    /// </summary>
    private bool StartWpf()
    {
        if (_wpfStarted) return true;
        try
        {
            Theme.Start();
            System.Windows.Application.Current.DispatcherUnhandledException += (_, e) =>
            {
                _log.Write($"window error: {e.Exception}");
                e.Handled = true;
            };
            _wpfStarted = true;
        }
        catch (Exception e)
        {
            _log.Write($"windows failed to start: {e}");
            Balloon("GyroPhone window failed", "The tray menu still works. The log has the details.", ToolTipIcon.Warning);
        }
        return _wpfStarted;
    }

    /// <summary>The app's icon for the WPF windows' title bars, made once from the exe's own icon.</summary>
    private System.Windows.Media.ImageSource? WindowIcon()
    {
        if (_windowIcon != null) return _windowIcon;
        try
        {
            using var icon = Icon.ExtractAssociatedIcon(_exePath);
            if (icon == null) return null;
            var src = System.Windows.Interop.Imaging.CreateBitmapSourceFromHIcon(icon.Handle, System.Windows.Int32Rect.Empty,
                System.Windows.Media.Imaging.BitmapSizeOptions.FromEmptyOptions());
            src.Freeze();
            _windowIcon = src;
        }
        catch (Exception e) when (e is IOException or ArgumentException or System.Runtime.InteropServices.COMException)
        {
            _log.Write($"window icon: {e.GetType().Name}");
        }
        return _windowIcon;
    }

    private void BuildOverlayMenu(ToolStripMenuItem menu)
    {
        menu.DropDownItems.Clear();
        foreach (var o in _engine.Settings.Overlays.ToList())
        {
            var id = o.Id;
            menu.DropDownItems.Add(new ToolStripMenuItem($"Show {OverlayName(o)}", null, (_, _) => SetOverlayShown(id, !(OverlayOf(id)?.Shown ?? false)))
            {
                Checked = o.Shown,
            });
        }
        menu.DropDownItems.Add(new ToolStripSeparator());
        menu.DropDownItems.Add(new ToolStripMenuItem("Hide all overlays" + KeysSuffix(OverlayAction.HideAll), null, (_, _) => SetOverlaysHidden(!OverlaysHidden))
        {
            Checked = OverlaysHidden,
        });
        menu.DropDownItems.Add(new ToolStripMenuItem("Edit: drag the windows to move and resize, scroll to scale" + KeysSuffix(OverlayAction.Edit), null, (_, _) => SetOverlayEdit(!OverlayEdit))
        {
            Checked = OverlayEdit,
        });
        menu.DropDownItems.Add(new ToolStripMenuItem("Level all: as held now shows face-on" + KeysSuffix(OverlayAction.LevelAll), null, (_, _) => LevelAll()));
        if (_engine.Settings.Overlays.Any(o => o.Kind == OverlayKind.Laser))
            menu.DropDownItems.Add(new ToolStripMenuItem("Clear pointers", null, (_, _) => ClearAllLasers()));
        menu.DropDownItems.Add(new ToolStripMenuItem("Overlay settings…", null, (_, _) => ShowMainWindow(MainPage.Overlays)));
    }

    /// <summary>Shows or closes overlay window <paramref name="id"/>, and remembers it for the next start.</summary>
    public void SetOverlayShown(string id, bool shown)
    {
        _engine.UpdateOverlaySettings(id, o => o.Shown = shown);
        // Showing one while all are hidden: it should appear, so Hide all ends.
        if (shown) _engine.SetOverlaysHidden(false);
        ApplyOverlays();
        Refresh();
    }

    public bool OverlaysHidden => _engine.Settings.OverlaysHidden;

    /// <summary>Hide all: every overlay window closes, each keeping its own Show for when this is off.</summary>
    public void SetOverlaysHidden(bool hidden)
    {
        _engine.SetOverlaysHidden(hidden);
        ApplyOverlays();
        Refresh();
    }

    public OverlayShortcuts Shortcuts => _engine.Settings.OverlayShortcuts;

    /// <summary>" (Ctrl+Alt+R)" for a menu item or button, or "" when the action has no keys.</summary>
    public string KeysSuffix(OverlayAction a) => Shortcuts.Get(a) is { Length: > 0 } k ? $" ({k})" : "";

    /// <summary>Whether another app holds <paramref name="a"/>'s keys, so they do nothing here.</summary>
    public bool ShortcutTaken(OverlayAction a) => _hotkeys?.IsTaken(a) ?? false;

    /// <summary>Sets <paramref name="a"/>'s keys ("" = none) and holds them at once.</summary>
    public void SetShortcut(OverlayAction a, string keys)
    {
        _engine.SetOverlayShortcut(a, keys);
        ApplyShortcuts();
        Refresh();
    }

    /// <summary>A shortcut field is taking keys: the shortcuts let go meanwhile, so the press reaches it.</summary>
    public void PauseShortcuts(bool paused)
    {
        _hotkeys?.Pause(paused, Shortcuts);
    }

    private void ApplyShortcuts()
    {
        if (_hotkeys == null) return;
        foreach (var a in _hotkeys.Apply(Shortcuts))
            _log.Write($"overlay: {Shortcuts.Get(a)} ({OverlayShortcuts.ActionName(a)}) is taken by another app");
    }

    private void OnShortcut(OverlayAction a)
    {
        switch (a)
        {
            case OverlayAction.LevelAll: LevelAll(); break;
            case OverlayAction.HideAll: SetOverlaysHidden(!OverlaysHidden); break;
            case OverlayAction.Edit: SetOverlayEdit(!OverlayEdit); break;
        }
    }

    /// <summary>The overlay window picked in Edit and in the main window's list.</summary>
    public string? SelectedOverlay => _selectedOverlay;

    public void SelectOverlay(string? id)
    {
        if (_selectedOverlay == id) return;
        _selectedOverlay = id;
        foreach (var f in _overlayForms.Values) f.Selected = f.Id == id;
    }

    /// <summary>
    /// A click (no drag) in Edit on window <paramref name="id"/> at screen point <paramref name="at"/>:
    /// it gets picked; if it already was, the next window under the cursor (in list order, wrapping)
    /// is picked and brought to the front, so clicks cycle through overlapping windows.
    /// </summary>
    private void OverlayClicked(string id, Point at)
    {
        // A second click soon after, close by: a double-click opens the companion window on the picked
        // overlay's settings (or brings it to the front), instead of cycling on to the next window.
        long now = Environment.TickCount64;
        var dbl = SystemInformation.DoubleClickSize;
        bool doubled = _lastOverlayClick is { } last && now - last.Ms <= SystemInformation.DoubleClickTime
            && Math.Abs(at.X - last.At.X) <= dbl.Width && Math.Abs(at.Y - last.At.Y) <= dbl.Height;
        _lastOverlayClick = doubled ? null : (now, at);
        if (doubled && _selectedOverlay != null && _overlayForms.ContainsKey(_selectedOverlay))
        {
            ShowMainWindow(MainPage.Overlays);
            return;
        }
        var under = _engine.Settings.Overlays
            .Where(o => _overlayForms.TryGetValue(o.Id, out var f) && f.Visible && f.Bounds.Contains(at))
            .Select(o => o.Id).ToList();
        string pick = id;
        int i = _selectedOverlay == null ? -1 : under.IndexOf(_selectedOverlay);
        if (_selectedOverlay == id && i >= 0 && under.Count > 1) pick = under[(i + 1) % under.Count];
        SelectOverlay(pick);
        if (_overlayForms.TryGetValue(pick, out var form)) form.BringUp();
        Refresh();
    }

    private (long Ms, Point At)? _lastOverlayClick;

    /// <summary>A window picked by a drag or a wheel notch: refreshes at once only when the pick changed.</summary>
    private void OverlayPicked(string id)
    {
        if (_selectedOverlay == id)
        {
            RefreshSoon(); // another notch on the same window: its new size shows once the wheel stops
            return;
        }
        SelectOverlay(id);
        Refresh();
    }

    public bool OverlayEdit => _engine.Settings.OverlayEdit;

    /// <summary>Edit: every overlay window can be dragged; off, the mouse goes through them all.</summary>
    public void SetOverlayEdit(bool on)
    {
        _engine.SetOverlayEdit(on);
        foreach (var f in _overlayForms.Values.ToList()) f.SettingsChanged();
        UpdateOverlayWheel();
        Refresh();
    }

    /// <summary>
    /// A setting of overlay <paramref name="id"/> changed (a slider step, a toggle): the window draws it at
    /// its next frame, the page shown is refreshed now and everything else once the changes stop.
    /// </summary>
    public void ChangeOverlay(string id, Action<OverlaySettings> change)
    {
        _engine.UpdateOverlaySettings(id, change);
        if (_overlayForms.TryGetValue(id, out var f)) f.SettingsChanged();
        RefreshPageNow();
    }

    /// <summary>
    /// Refreshes only the main window's page shown, and the rest (tray, Home) once changes have
    /// stopped for <see cref="RefreshSoonMs"/>: a slider drag is tens of steps a second.
    /// </summary>
    private void RefreshPageNow()
    {
        _mainWindow?.RefreshShownPage();
        RefreshSoon();
    }

    private const int RefreshSoonMs = 250;
    private System.Windows.Forms.Timer? _refreshSoon;

    /// <summary>A full <see cref="Refresh"/> once nothing has asked for one for <see cref="RefreshSoonMs"/> (UI thread).</summary>
    private void RefreshSoon()
    {
        if (_refreshSoon == null)
        {
            _refreshSoon = new System.Windows.Forms.Timer { Interval = RefreshSoonMs };
            _refreshSoon.Tick += (_, _) =>
            {
                _refreshSoon.Stop();
                Refresh();
            };
        }
        _refreshSoon.Stop();
        _refreshSoon.Start();
    }

    /// <summary>A new overlay window of <paramref name="kind"/>, shown; returns its id.</summary>
    public string AddOverlay(OverlayKind kind)
    {
        var id = _engine.AddOverlay(kind);
        ApplyOverlays();
        Refresh();
        return id;
    }

    public void RemoveOverlay(string id)
    {
        if (_overlayForms.Remove(id, out var f)) f.Close();
        _engine.RemoveOverlay(id);
        ApplyOverlays();
        Refresh();
    }

    /// <summary>Level: overlay <paramref name="id"/>'s pose now shows straight.</summary>
    public void LevelOverlay(string id)
    {
        if (_overlayForms.TryGetValue(id, out var f)) f.Level();
        Refresh();
    }

    /// <summary>Clears laser pointer overlay <paramref name="id"/>'s paint and trail.</summary>
    public void ClearOverlay(string id)
    {
        if (_overlayForms.TryGetValue(id, out var f)) f.ClearPaint();
    }

    /// <summary>Clears every laser pointer overlay's paint and trail.</summary>
    private void ClearAllLasers()
    {
        foreach (var f in _overlayForms.Values.ToList()) f.ClearPaint();
    }

    public OverlayRestFade RestFade => _engine.Settings.OverlayRestFade;

    public void ChangeRestFade(Action<OverlayRestFade> change)
    {
        _engine.UpdateOverlayRestFade(change);
        RefreshPageNow();
    }

    public float? StillnessDps => _engine.OverlayStillnessDps();

    public void LevelAll()
    {
        foreach (var f in _overlayForms.Values.ToList()) f.Level();
        Refresh();
    }

    /// <summary>A screen refresh (pacer thread): each open overlay window draws again if anything changed.</summary>
    private void TickOverlays()
    {
        if (Volatile.Read(ref _exited) != 0) return;
        foreach (var f in _overlayList)
            if (!f.IsDisposed) f.Tick();
        // The draw times in the log now and then, so they can be read without the Debug window.
        long now = Environment.TickCount64;
        if (now - _overlayStatsLoggedAt >= 30_000)
        {
            if (_overlayStatsLoggedAt != 0) _log.Write(OverlayStats());
            _overlayStatsLoggedAt = now;
        }
    }

    private long _overlayStatsLoggedAt;

    /// <summary>The fastest frame rate an open overlay window wants (pacer thread); 0 = every screen refresh.</summary>
    private int OverlayWantHz()
    {
        int want = 1;
        foreach (var f in _overlayList)
        {
            int fps = f.MaxFps;
            if (fps <= 0) return 0;
            want = Math.Max(want, fps);
        }
        return want;
    }

    /// <summary>The fastest refresh rate of the screens the open overlay windows are on (pacer thread); 0 = none known yet.</summary>
    private int OverlayScreenHz()
    {
        int hz = 0;
        foreach (var f in _overlayList) hz = Math.Max(hz, f.ScreenHz);
        return hz;
    }

    /// <summary>The open overlay windows changed (UI thread): the pacer's copy of the list, and whether it runs.</summary>
    private void OverlaysChanged()
    {
        _overlayList = [.. _overlayForms.Values];
        _pacer?.SetRunning(_overlayList.Length > 0);
        UpdateOverlayWheel();
    }

    /// <summary>The wheel resizes overlays only while Edit is on and one is open.</summary>
    private void UpdateOverlayWheel()
    {
        bool on = OverlayEdit && _overlayForms.Count > 0;
        // The hook's own thread calls these: they read only the pacer's copy of the list and the windows' own handles.
        if (on) _overlayWheel ??= new OverlayWheel(
            h =>
            {
                foreach (var f in _overlayList)
                    if (h != IntPtr.Zero && f.Hwnd == h) return f;
                return null;
            },
            () =>
            {
                var id = _selectedOverlay;
                if (id == null) return null;
                foreach (var f in _overlayList)
                    if (f.Id == id && f.Hwnd != IntPtr.Zero && f.Visible) return f;
                return null;
            });
        if (_overlayWheel != null) _overlayWheel.Enabled = on;
    }

    /// <summary>The open overlay windows' draw times, for the Debug window.</summary>
    public string OverlayStats()
    {
        var open = _overlayList;
        return open.Length == 0 ? "overlays: none open" : "overlays: " + string.Join(" · ", open.Select(f => f.Stats).Where(t => t.Length > 0));
    }

    private void ApplyOverlays()
    {
        foreach (var o in _engine.Settings.Overlays.ToList())
        {
            try
            {
                ApplyOverlay(o);
            }
            catch (Exception e)
            {
                // An overlay must never take the companion down (it reopens at every start while Shown).
                _log.Write($"overlay failed and was turned off: {e}");
                _engine.UpdateOverlaySettings(o.Id, x => x.Shown = false);
                if (_overlayForms.Remove(o.Id, out var f))
                    try { f.Dispose(); } catch (Exception) { }
                Balloon("Overlay failed", "It was turned off. The log has the details.", ToolTipIcon.Warning);
            }
        }
        // Windows whose settings are gone.
        foreach (var id in _overlayForms.Keys.Where(id => OverlayOf(id) == null).ToList())
            if (_overlayForms.Remove(id, out var f)) f.Close();
        _engine.SetOverlay(_overlayForms.Count > 0);
        OverlaysChanged();
        foreach (var f in _overlayForms.Values)
        {
            f.Title = OverlayOf(f.Id) is { } o ? OverlayName(o) : "";
            f.Selected = f.Id == _selectedOverlay;
        }
    }

    private void ApplyOverlay(OverlaySettings o)
    {
        _overlayForms.TryGetValue(o.Id, out var open);
        if (open is { IsDisposed: true })
        {
            _overlayForms.Remove(o.Id);
            open = null;
        }
        bool shown = o.Shown && !OverlaysHidden;
        if (shown && open == null)
        {
            var id = o.Id;
            var form = new OverlayForm(_engine, id, _log.Write, OverlayClicked, OverlayPicked);
            form.FormClosed += (_, _) =>
            {
                if (_overlayForms.TryGetValue(id, out var cur) && ReferenceEquals(cur, form))
                {
                    _overlayForms.Remove(id);
                    _engine.SetOverlay(_overlayForms.Count > 0);
                    OverlaysChanged();
                }
            };
            _overlayForms[id] = form;
            OverlaysChanged();
            form.Show();
        }
        else if (!shown && open != null)
        {
            _overlayForms.Remove(o.Id);
            open.Close();
        }
    }

    public void ShowDebugForm()
    {
        if (_debugForm == null || _debugForm.IsDisposed)
        {
            var form = new DebugForm(_engine, SaveDebug, OpenDebugFolder, OverlayStats);
            form.FormClosed += (_, _) =>
            {
                if (ReferenceEquals(_debugForm, form)) _debugForm = null;
            };
            _debugForm = form;
        }
        _debugForm.Show();
        if (_debugForm.WindowState == FormWindowState.Minimized) _debugForm.WindowState = FormWindowState.Normal;
        _debugForm.Activate();
    }

    /// <summary>Debug recordings: Documents\GyroPhone\Debug, where they are easy to find and send.</summary>
    public static string DebugDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "GyroPhone", "Debug");

    /// <summary>Writes the last 30 s of the debug log to the debug folder; returns the path, or null.</summary>
    public string? SaveDebug()
    {
        try
        {
            Directory.CreateDirectory(DebugDirectory);
            var path = Path.Combine(DebugDirectory, $"pc-{DateTime.Now:yyyyMMdd-HHmmss}.txt");
            var header = new List<string> { $"{Program.AppName} {Application.ProductVersion}" };
            header.AddRange(DebugForm.StatusText(_engine).Split("\r\n"));
            File.WriteAllText(path, _engine.Debug.Save(header));
            // Keep the newest 20 recordings.
            foreach (var old in Directory.GetFiles(DebugDirectory, "pc-*.txt").OrderByDescending(f => f).Skip(20)) File.Delete(old);
            _log.Write($"debug recording saved: {Path.GetFileName(path)}");
            return path;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _log.Write($"debug recording failed: {e.GetType().Name}");
            return null;
        }
    }

    public void OpenDebugFolder()
    {
        try
        {
            Directory.CreateDirectory(DebugDirectory);
            using var p = Process.Start(new ProcessStartInfo("explorer.exe", $"\"{DebugDirectory}\"") { UseShellExecute = true });
        }
        catch (Exception e) when (e is Win32Exception or IOException or UnauthorizedAccessException)
        {
            _log.Write($"open debug folder failed: {e.GetType().Name}");
        }
    }

    private string StatusText()
    {
        var s = _engine.Snapshot();
        var b = new StringBuilder();
        b.AppendLine(s.Connected ? $"Phone:        {s.PhoneName}{(s.Silent ? " (no signal)" : "")}" : "Phone:        not connected");
        b.AppendLine($"Received:     {s.RxPerSec}/s, loss {s.LossPermille / 10.0:0.0}%");
        b.AppendLine($"Latency:      p50 {Ms(s.LatP50Us)}, p99 {Ms(s.LatP99Us)}, jitter {s.JitterUs / 1000.0:0.00} ms");
        if (s.PhoneLink is { } l)
        {
            string band = l.Band == 0 ? "unknown band" : $"{l.Band} GHz";
            string lockState = (l.Flags & LinkInfoFlags.LowLatencyLockHeld) != 0 ? "on" : "off";
            if ((l.Flags & LinkInfoFlags.Usb) != 0)
                b.AppendLine((l.Flags & LinkInfoFlags.UsbDirect) != 0 ? "Phone link:   USB cable (direct)" : "Phone link:   USB cable (tethering)");
            else b.AppendLine($"Phone Wi-Fi:  {band}, {l.FreqMhz} MHz, {l.LinkMbps} Mbps, {l.RssiDbm} dBm");
            b.AppendLine($"Phone sends:  {l.SendRateHz} Hz, RTT min {l.RttMinUs / 1000.0:0.0} ms, low-latency Wi-Fi {lockState}");
        }
        b.AppendLine($"USB direct:   {UsbDirectText()}");
        b.AppendLine($"Output:       {OutputName(s.OutputMode)}, pad {(s.PadPlugged ? "plugged" : "not plugged")}");
        b.AppendLine($"Gamepad driver: {(s.DriverOk ? "ViGEmBus ok" : _padError ?? "missing")}");
        b.AppendLine($"Input:        {(s.Paused ? "paused" : "active")}");
        b.AppendLine($"Network:      {(s.NetworkPublic ? "Public (set it to Private)" : "Private or Domain")}, firewall rule "
            + (_firewallOk switch { true => "present", false => "missing", null => "checking" })
            + ", USB rule " + (_usbRuleOk switch { true => "present", false => "missing", null => "checking" }));
        b.AppendLine($"Server:       {(_server.State == ServerState.Running ? $"UDP {Wire.Port}" : _server.State.ToString())}, "
            + $"this app {(ElevationProbe.IsSelfElevated ? "elevated" : "not elevated")}");
        b.AppendLine($"Pairing:      {(s.PairingWindowS > 0 ? $"open, {s.PairingWindowS} s left" : "closed")}, {s.PairedPhones.Count} paired phone(s)"
            + $" ({s.PairedPhones.Count(p => p.ByKey)} by key)");
        b.AppendLine($"PC key:       {s.HostKeyFingerprint ?? "unavailable" + (_keyError != null ? $" ({_keyError})" : "")}, {_keyFiles.Directory}");
        b.AppendLine($"Phone keys:   {(_authorized == null ? "not read (no PC key)" : _authorized.LastScan)}");
        b.AppendLine($"Log:          {_log.Path}{(_log.LastError is { } le ? $" (cannot write: {le})" : "")}");
        b.AppendLine($"Running as:   {Environment.UserDomainName}\\{Environment.UserName}, {_exePath}");
        var c = s.Counters;
        b.AppendLine();
        b.AppendLine($"datagrams {c.Datagrams}  states {c.States}  injections {c.Injections}  SendInput short {_keyMouse.ShortWrites}");
        b.AppendLine($"dropped {c.Dropped}  bad auth {c.BadAuth}  errors sent {c.Errors}  unknown keys {c.UnknownUsages}  exceptions {c.Exceptions + _server.RxExceptions}");
        return b.ToString();
    }

    public void Forget(PairedPhoneInfo phone)
    {
        var text = phone.ByKey
            ? $"Forget {phone.Name}? This deletes its key file {Path.GetFileName(phone.KeyFile)}; run update.cmd or pair with a code to connect again."
            : $"Forget {phone.Name}? The phone will need to pair again.";
        var r = MessageBox.Show(text, Program.AppName, MessageBoxButtons.OKCancel, MessageBoxIcon.Question);
        if (r != DialogResult.OK) return;
        if (!phone.ByKey)
        {
            _engine.Forget(phone.PhoneId);
            return;
        }
        try
        {
            File.Delete(phone.KeyFile!);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _log.Write($"could not delete key file: {e.GetType().Name}");
        }
        _authorized?.Reload();
    }

    public void OpenKeysFolder()
    {
        try
        {
            Directory.CreateDirectory(_keyFiles.AuthorizedDirectory);
            using var p = Process.Start(new ProcessStartInfo("explorer.exe", $"\"{_keyFiles.Directory}\"") { UseShellExecute = true });
        }
        catch (Exception e) when (e is Win32Exception or IOException or UnauthorizedAccessException)
        {
            _log.Write($"open keys folder failed: {e.GetType().Name}");
        }
    }

    /// <summary>How long gp-bind retries quickly (the watcher letting go) before it shows the port as taken.</summary>
    private const int FastBindMs = 5000;

    /// <summary>
    /// Binds UDP 47800. The watcher holds the port whenever no companion runs, so it is asked to let go
    /// before the first try; it answers within milliseconds. If the port is still taken, gp-bind keeps
    /// trying off the UI thread: every 25 ms for 5 s, then every second for as long as the app runs, asking
    /// the watcher each time. The rest of the start-up (<see cref="ServerStarted"/>) runs once it binds.
    /// </summary>
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
        var t = new Thread(BindLoop) { Name = "gp-bind", IsBackground = true };
        t.Start();
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
                Post(ServerStarted);
                return;
            }
            if (_server.State != ServerState.PortInUse)
            {
                _log.Write("UDP server failed to start");
                _bindRetrying = false;
                Post(Refresh);
                return;
            }
            if (!fast && !_bindWaitLong)
            {
                _bindWaitLong = true;
                _log.Write($"port {Wire.Port} still in use by another program: retrying every second");
                Post(Refresh);
            }
        }
    }

    public void ToggleUsbDirect()
    {
        bool on = !_usb.Enabled;
        _usb.Enabled = on;
        _engine.SetUsbDirect(on);
        _log.Write($"usb direct {(on ? "on" : "off")}");
        Refresh();
    }

    private string UsbDirectText()
    {
        string detail = _usb.Detail.Length > 0 ? $" ({_usb.Detail})" : "";
        return _usb.State switch
        {
            UsbDirectState.Off => "off",
            UsbDirectState.NoPhone => "on, no phone with USB debugging on the cable",
            UsbDirectState.AdbBusy => "adb is holding the phone: press \"Stop adb and use the cable\" on the Connection page",
            UsbDirectState.Switching => $"switching the phone to accessory mode{detail}",
            UsbDirectState.WrongUsbMode => "set the phone's USB to No data transfer (tethering and file transfer block the switch), then replug",
            UsbDirectState.NeedsDriver => $"the phone is in accessory mode but needs the WinUSB driver{detail}",
            UsbDirectState.Connected => $"connected{detail}",
            _ => $"failed{detail}",
        };
    }

    /// <summary>The accessory interface has no driver in Windows: the user installs WinUSB once with Zadig.</summary>
    public void ShowUsbDriverHelp()
    {
        var r = MessageBox.Show(
            "Windows needs the WinUSB driver for the phone's accessory mode, once:\n\n"
            + "1. Keep the phone plugged in (it is in accessory mode now).\n"
            + "2. Download and run Zadig (zadig.akeo.ie).\n"
            + "3. Pick \"Android Accessory Interface\" (USB ID 18D1 2D00, or 18D1 2D01 interface 0). If it is not listed, choose Options > List All Devices.\n"
            + "4. Driver: WinUSB, then Install Driver.\n\n"
            + "Leave the adb interface (interface 1) alone. Open the Zadig website now?",
            Program.AppName, MessageBoxButtons.OKCancel, MessageBoxIcon.Information);
        if (r == DialogResult.OK) Open("https://zadig.akeo.ie");
    }

    public void ToggleAutoStart()
    {
        bool enable = !IsAutoStartOn();
        if (TrySetAutoStart(enable)) _engine.SetStartWithWindows(enable);
        Refresh();
    }

    private static bool IsWatcherOn()
    {
        try
        {
            return AutoStart.IsEnabled(AutoStart.WatcherValueName, Watcher.InstalledExe, Watcher.Arg);
        }
        catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException or IOException)
        {
            return false;
        }
    }

    /// <summary>
    /// "Start when my phone appears": a per-user Run entry for the installed exe with --watch, started
    /// now too. Off removes the entry and stops the running watcher.
    /// </summary>
    public void ToggleWatcher()
    {
        bool enable = !IsWatcherOn();
        if (enable && !File.Exists(Watcher.InstalledExe))
        {
            MessageBox.Show($"Install the companion with update.cmd first (it goes to {Path.GetDirectoryName(Watcher.InstalledExe)}).",
                Program.AppName, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        try
        {
            AutoStart.Set(AutoStart.WatcherValueName, enable, Watcher.InstalledExe, Watcher.Arg);
            if (enable)
            {
                using var p = Process.Start(new ProcessStartInfo(Watcher.InstalledExe, Watcher.Arg)
                {
                    UseShellExecute = true,
                    WorkingDirectory = Path.GetDirectoryName(Watcher.InstalledExe) ?? "",
                });
            }
            else
            {
                Watcher.SignalStop();
            }
            _log.Write($"watcher {(enable ? "on" : "off")}");
        }
        catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException or IOException or Win32Exception)
        {
            _log.Write($"watcher change failed: {e.GetType().Name}");
        }
        Refresh();
    }

    private bool IsAutoStartOn()
    {
        try
        {
            return AutoStart.IsEnabled(_exePath);
        }
        catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException or IOException)
        {
            return false;
        }
    }

    private bool TrySetAutoStart(bool enable)
    {
        try
        {
            AutoStart.Set(enable, _exePath);
            return true;
        }
        catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException or IOException)
        {
            _log.Write($"autostart change failed: {e.GetType().Name}");
            return false;
        }
    }

    private void CheckFirewallAsync() => Task.Run(() =>
    {
        bool ok = FirewallHelper.RuleExists(_exePath);
        bool usb = FirewallHelper.UsbRuleExists(_exePath);
        Post(() =>
        {
            _firewallOk = ok;
            _usbRuleOk = usb;
            Refresh();
        });
    });

    public void AllowUsb() => Task.Run(() =>
    {
        bool added = FirewallHelper.AddUsbRuleElevated(_exePath);
        bool ok = FirewallHelper.UsbRuleExists(_exePath);
        _log.Write($"USB firewall rule add {(added ? "ok" : "failed or declined")}, present {ok}");
        Post(() =>
        {
            _usbRuleOk = ok;
            Refresh();
            if (!ok)
                Balloon("USB rule not added", "Windows did not add the rule (the prompt was declined or failed).", ToolTipIcon.Warning);
        });
    });

    public void AllowThroughFirewall() => Task.Run(() =>
    {
        bool added = FirewallHelper.AddRuleElevated(_exePath);
        bool ok = FirewallHelper.RuleExists(_exePath);
        _log.Write($"firewall rule add {(added ? "ok" : "failed or declined")}, present {ok}");
        Post(() =>
        {
            _firewallOk = ok;
            Refresh();
            if (!ok)
                Balloon("Firewall rule not added", "Windows did not add the rule (the prompt was declined or failed).", ToolTipIcon.Warning);
        });
    });

    public void RetryDriver()
    {
        var pad = PadSinks.Create(_log.Write, out var error);
        if (error != null && !pad.DualSenseOk)
        {
            _padError = error;
            MessageBox.Show($"{error}.\n\nInstall ViGEmBus from {ViGEmPadSink.DriverDownloadUrl}, then choose Retry driver again.",
                Program.AppName, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        // The engine unplugs and disposes the old sink on its pad thread.
        _engine.SetPadSink(pad);
        _pad = pad;
        _padError = error;
        _log.Write("gamepad driver found");
        Balloon("Gamepad driver found", "Game pad output is on.", ToolTipIcon.Info);
        Refresh();
    }

    public void RestartAsAdmin()
    {
        try
        {
            var psi = new ProcessStartInfo(_exePath, Program.RestartedArg) { UseShellExecute = true, Verb = "runas" };
            using var p = Process.Start(psi);
            if (p == null) return;
        }
        catch (Win32Exception)
        {
            return; // UAC prompt declined
        }
        ExitApp();
    }

    public void OpenLogFolder()
    {
        try
        {
            Directory.CreateDirectory(_log.Directory);
            using var p = Process.Start(new ProcessStartInfo("explorer.exe", $"\"{_log.Directory}\"") { UseShellExecute = true });
        }
        catch (Exception e) when (e is Win32Exception or IOException or UnauthorizedAccessException)
        {
            _log.Write($"open log folder failed: {e.GetType().Name}");
        }
    }

    public void OpenNetworkSettings() => Open(NetworkCategory.SettingsUri);

    public void OpenDriverDownload() => Open(ViGEmPadSink.DriverDownloadUrl);

    private void Open(string target)
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo(target) { UseShellExecute = true });
        }
        catch (Win32Exception e)
        {
            _log.Write($"open {target} failed: {e.NativeErrorCode}");
        }
    }

    private void Balloon(string title, string text, ToolTipIcon icon)
    {
        if (Volatile.Read(ref _exited) != 0) return;
        _tray.ShowBalloonTip(8000, title, text, icon);
    }

    private static string Ms(uint us) => us == HostStatus.Unknown ? "–" : $"{us / 1000.0:0.0} ms";

    private static string OutputName(OutputMode m) => m switch
    {
        OutputMode.Xbox360 => "Xbox 360",
        OutputMode.DualShock4 => "DualShock 4",
        OutputMode.DualSense => "DualSense",
        _ => "off (keyboard and mouse only)",
    };

    /// <summary>Tooltip text: 63 characters is the limit every NotifyIcon version accepts.</summary>
    private static string TrayText(string status)
    {
        const int max = 63;
        var t = $"{Program.AppName}\n{status}";
        if (t.Length <= max) return t;
        return status.Length <= max ? status : status[..max];
    }

    // =====================================================================================
    // session, power and exit
    // =====================================================================================

    private void OnSessionSwitch(object? sender, SessionSwitchEventArgs e)
    {
        switch (e.Reason)
        {
            case SessionSwitchReason.SessionLock:
            case SessionSwitchReason.SessionLogoff:
            case SessionSwitchReason.ConsoleDisconnect:
            case SessionSwitchReason.RemoteDisconnect:
                _sessionLocked = true;
                break;
            case SessionSwitchReason.SessionUnlock:
            case SessionSwitchReason.SessionLogon:
            case SessionSwitchReason.ConsoleConnect:
            case SessionSwitchReason.RemoteConnect:
                _sessionLocked = false;
                break;
            default:
                return;
        }
        _engine.SetLocked(_sessionLocked || _suspended);
    }

    private void OnPowerModeChanged(object? sender, PowerModeChangedEventArgs e)
    {
        if (e.Mode == PowerModes.Suspend) _suspended = true;
        else if (e.Mode == PowerModes.Resume) _suspended = false;
        else return;
        _engine.SetLocked(_sessionLocked || _suspended);
    }

    private void OnApplicationExit(object? sender, EventArgs e) => Shutdown();

    private void OnProcessExit(object? sender, EventArgs e) => Shutdown();

    private void ExitApp()
    {
        _pairWindow?.Close();
        _mainWindow?.Close();
        _debugForm?.Close();
        foreach (var f in _overlayForms.Values.ToList()) f.Close();
        _overlayWheel?.Dispose();
        _overlayWheel = null;
        _hotkeys?.Dispose();
        _hotkeys = null;
        _pacer?.Dispose();
        Shutdown();
        ExitThread();
    }

    /// <summary>Release-all, HOST_BYE(EXIT), unplug, save, clear the held-keys marker. Runs once.</summary>
    private void Shutdown()
    {
        if (Interlocked.Exchange(ref _exited, 1) != 0) return;
        try
        {
            SystemEvents.SessionSwitch -= OnSessionSwitch;
            SystemEvents.PowerModeChanged -= OnPowerModeChanged;
            Application.ApplicationExit -= OnApplicationExit;
            _uiTimer.Stop();
            _refreshSoon?.Stop();
            _updater.Dispose();
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
            _log.Write("stopped");
        }
        catch (Exception e)
        {
            _log.Write($"shutdown error: {e.GetType().Name}");
        }
        _tray.Visible = false;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            Shutdown();
            _hotkeys?.Dispose();
            AppDomain.CurrentDomain.ProcessExit -= OnProcessExit;
            _uiTimer.Dispose();
            _refreshSoon?.Dispose();
            _tray.Dispose();
            _menu.Dispose();
            _icons.Dispose();
            _ui.Dispose();
            _inputLog.Dispose();
            _log.Dispose();
        }
        base.Dispose(disposing);
    }
}
