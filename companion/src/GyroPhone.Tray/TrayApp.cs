using System.ComponentModel;
using System.Diagnostics;
using System.Net;
using System.Text;
using System.Text.Json;
using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Perf;
using GyroPhone.Host.Settings;
using GyroPhone.Host.Split;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;
using GyroPhone.Ui;
using GyroPhone.Windows;
using Microsoft.Win32;

namespace GyroPhone.Tray;

/// <summary>
/// The tray application, the control panel: the notification-area icon and its short menu, the pair and main windows
/// (WPF, from GyroPhone.Ui, which reach the app through <see cref="ICompanion"/>), the overlays, the Performance page
/// and Diagnostics' log. The input itself (the phone link, mappings, injection, pairing keys) runs in the input process
/// (<see cref="InputHost"/>), which this starts and talks to through <see cref="InputClient"/>; its overlay data and held
/// outputs come through <see cref="SharedRegion"/>. Events from the input process arrive on the pipe thread and are
/// posted to the UI thread.
/// </summary>
internal sealed class TrayApp : ApplicationContext, ICompanion
{
    private const long FirewallHintAfterUs = 30_000_000;

    private readonly FileLog _log;
    private readonly string _exePath;
    private readonly IClock _clock = StopwatchClock.Instance;
    private readonly long _startUs;
    private readonly Control _ui;
    /// <summary>The input process: its commands (<see cref="In"/>) and its newest status (<see cref="St"/>).</summary>
    private readonly InputClient _input;
    /// <summary>The memory shared with the input process (overlay data, held outputs, Diagnostics' raw events).</summary>
    private readonly SharedRegion _region;
    private readonly EventWaitHandle _overlayWake;
    /// <summary>The overlays' side: their settings (companion.json) and what the overlay windows read.</summary>
    private readonly OverlayHost _overlays;
    private readonly SettingsStore _uiStore;
    /// <summary>Diagnostics' log, kept here: the input process's lines and raw events, the overlays' draw times.</summary>
    private readonly DebugTrace _debug;
    private readonly Pairing _pairing;
    /// <summary>The Performance page's frame watcher and test records (logs\perf).</summary>
    private readonly PerfMonitor _perf;

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
    // Diagnostics as last seen in the input process's status (the log here follows it).
    private bool _diagnosticsOn;
    private bool _padErrorShown;
    private bool _pairShownOnce;
    private bool _firewallHintShown;
    // The phone's "Press a key" was open at the last refresh (its notification shows once per opening).
    private bool _captureShown;
    private bool _sessionLocked, _suspended;
    private int _exited;

    public TrayApp(string inputArgs)
    {
        _log = new FileLog();
        _exePath = Environment.ProcessPath ?? Application.ExecutablePath;
        _startUs = _clock.NowUs;
        _ui = new Control();
        _ = _ui.Handle; // create the window handle so BeginInvoke works from other threads
        _log.Write($"starting {Application.ProductVersion}, elevated {ElevationProbe.IsSelfElevated}");

        // The companion's own settings (overlays, presets, shortcuts, tray click): companion.json, first made from
        // settings.json, which the input process keeps for its own.
        var inputSettingsPath = SettingsStore.DefaultPath();
        _uiStore = new SettingsStore(Path.Combine(Path.GetDirectoryName(inputSettingsPath) ?? "", "companion.json"), Program.LockedFileWaitMs);
        bool fresh = !File.Exists(_uiStore.Path);
        var uiSettings = (fresh ? new SettingsStore(inputSettingsPath, Program.LockedFileWaitMs) : _uiStore).Load(Environment.MachineName, _log.Write);

        _region = SharedRegion.CreateOrOpen(SplitNames.Region);
        _overlayWake = new EventWaitHandle(false, EventResetMode.AutoReset, SplitNames.OverlayWake);
        _input = new InputClient(_exePath, _log.Write, inputArgs);
        _overlays = new OverlayHost(uiSettings, _region, _overlayWake, (on, compass, samples) => In.SetOverlay(on, compass, samples), _clock);
        if (fresh) _overlays.MarkDirty();
        _debug = new DebugTrace(() => _clock.NowUs, queue: _region.Debug);
        _pairing = new Pairing(this);
        try
        {
            DebugTrace.SetLayout(KeyboardLayoutProbe.Read());
        }
        catch (Exception e) when (e is not OutOfMemoryException)
        {
            _log.Write($"key names: {e.GetType().Name}");
        }
        _perf = new PerfMonitor(new FpsHelperSource(_log.Write), new SystemLoad(_log.Write), new Foreground(), _clock,
            Path.Combine(_log.Directory, "perf"), _log.Write);
        _perf.ShowsLinkChanged += () => In.SetLinkWatch(_perf.ShowsLink);
        _perf.PathNow = () => LinkPath()?.Text;
        OverlayForm.FpsSource = () => (_perf.View(), _perf.GameName, _perf.State, _perf.Recording);
        OverlayForm.FpsLinkSource = () =>
        {
            long now = _clock.NowUs;
            return _perf.Link.Between(now - 61_000_000, now + 1_000_000);
        };
        _input.Event += OnInputEvent;
        _input.Connected += OnInputConnected;
        _input.Lost += () => Post(Refresh);


        _menu = new ContextMenuStrip();
        _statusItem = new ToolStripMenuItem("Starting…") { Enabled = false };
        _problemsItem = new ToolStripMenuItem("", null, (_, _) => ShowMainWindow(MainPage.Home)) { Available = false };
        _outX360 = new ToolStripMenuItem("Xbox 360", null, (_, _) => In.SetOutputMode(OutputMode.Xbox360));
        _outDs4 = new ToolStripMenuItem("DualShock 4", null, (_, _) => In.SetOutputMode(OutputMode.DualShock4));
        _outDualSense = new ToolStripMenuItem("DualSense", null, (_, _) => In.SetOutputMode(OutputMode.DualSense)) { Available = false };
        _outOff = new ToolStripMenuItem("Off (keyboard and mouse only)", null, (_, _) => In.SetOutputMode(OutputMode.None));
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

        _overlays.Start();
        StartOverlays();
        _input.Start();
        _uiTimer.Start();
        _updater.Start();
        CheckFirewallAsync();
        Refresh();

        if (Program.JustUpdated)
            Balloon("GyroPhone Companion updated", $"Now running {Application.ProductVersion}. Pairing and settings are kept.", ToolTipIcon.Info);
    }

    /// <summary>The input process's commands; they never throw (nothing happens while it restarts).</summary>
    private IInputControl In => _input.Input;

    private static readonly InputSettingsView NoSettings = InputSettingsView.Of(new HostSettings());

    private static readonly HostSnapshot NoSnapshot = new(null, false, false, OutputMode.None, true, false, false, false, false, 0,
        null, 0, 0, HostStatus.Unknown, HostStatus.Unknown, 0, null, false, [], new HostCounters(0, 0, 0, 0, 0, 0, 0, 0));

    /// <summary>The input process's newest status (null until it first attached).</summary>
    private InputStatus? St => _input.Status;

    private HostSnapshot Snap => St?.Snapshot ?? NoSnapshot;

    private InputSettingsView Set => St?.Settings ?? NoSettings;

    /// <summary>The overlays shown last time, their pacer and their shortcuts (UI thread, at start).</summary>
    private void StartOverlays()
    {
        GcWatch.NameCurrentThread("window thread");
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
    }

    // =====================================================================================
    // the input process's events (pipe thread): post only
    // =====================================================================================

    /// <summary>Attached to an input process (the first, or a new one after a crash): it learns what the overlays want.</summary>
    private void OnInputConnected()
    {
        _overlays.StartAgain();
        _overlays.TellAgain();
        In.SetLinkWatch(_perf.ShowsLink);
        Post(() =>
        {
            ApplyDiagnostics();
            Refresh();
            // First run: the input process opened the pairing window because nothing is paired yet.
            if (!_pairShownOnce)
            {
                if (Snap.PairingWindowS > 0 && Snap.PairedPhones.Count == 0) OpenPairForm();
                // Keep the Run entry pointing at this exe (it may have moved since it was enabled).
                if (Set.StartWithWindows) TrySetAutoStart(true);
            }
            _pairShownOnce = true;
        });
    }

    private void OnInputEvent(string name, JsonElement payload)
    {
        switch (name)
        {
            case "status":
                if (Interlocked.Exchange(ref _refreshQueued, 1) == 0) Post(Refresh);
                break;
            case "debug":
                if (payload.Deserialize<DebugLine>(PipeChannel.Json) is not { } line) break;
                // A line means Diagnostics is on there (its status may come a moment later).
                if (!_debug.Enabled) _debug.SetOn(true, "on in the input process");
                // The input recorder's second line gets this side's part (the game's frames, the PC's load).
                string text = line.Kind == DebugTrace.Kind.Link && line.Text.Contains(" sec ", StringComparison.Ordinal)
                    && _perf.SecondNote() is { } note ? line.Text + note : line.Text;
                _debug.Add(line.Kind, text, line.Us);
                break;
            case "debug-save":
                // Diagnostics' Save, here or on the phone: this PC's side, written off the UI thread (the ring keeps moving).
                ThreadPool.QueueUserWorkItem(_ => SaveDiagnosticsNow());
                break;
            case "link-second":
                _perf.Link.Add(payload.Deserialize<LinkSecond>(PipeChannel.Json));
                break;
            case "phone-second":
                _perf.Phone.Add(payload.Deserialize<PhoneSecond>(PipeChannel.Json));
                break;
            case "pair-show":
                if (payload.Deserialize<InputHost.PairShow>(PipeChannel.Json) is not { } ask) break;
                Post(() =>
                {
                    OpenPairForm();
                    _pairWindow?.RefreshNow();
                    Balloon("Pair a phone?", $"{ask.PhoneName} wants to pair. Code {ask.Sas}", ToolTipIcon.Info);
                });
                break;
            case "pair-close":
                Post(() => _pairWindow?.RefreshNow());
                break;
        }
    }

    public void Log(string message) => _log.Write(message);

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
        SaveUiSettings();
        ApplyDiagnostics();
        var st = St;
        var s = Snap;
        bool serverOk = st?.Server == nameof(ServerState.Running);
        bool padWanted = s.OutputMode != OutputMode.None;

        TrayState state;
        string status;
        if (st == null || !_input.Attached)
        {
            state = TrayState.Waiting;
            status = st == null ? "Starting…" : "Input restarting…";
        }
        else if (!serverOk && st.BindRetrying && !st.BindWaitLong)
        {
            state = TrayState.Waiting;
            status = "Starting…";
        }
        else if (!serverOk)
        {
            state = TrayState.Error;
            status = st.Server == nameof(ServerState.PortInUse) ? $"Port {Wire.Port} in use" : "Network error: restart the app";
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

        // The phone's "Press a key" opened: say once per opening that this PC's keys and buttons count too.
        if (s.Capturing && !_captureShown && Set.PressKeyNotice)
        {
            Balloon("Press a key or button",
                "Your phone is listening. Press a key, mouse button or controller button on this PC to map it.",
                ToolTipIcon.Info);
        }
        _captureShown = s.Capturing;

        if (st?.PadError is { } padError && !_padErrorShown)
            Balloon("Gamepad output is off", $"{padError}. Keyboard and mouse still work. Open GyroPhone and see the Gamepad page.", ToolTipIcon.Warning);
        if (st != null) _padErrorShown = true;

        // A Public network is only a likely cause: the phone often gets through anyway (Windows' own
        // first-run rule, the USB rule on wired adapters), so it is named only once no phone has been seen.
        if (!s.AnyDatagramSeen && serverOk && !_firewallHintShown && _clock.NowUs - _startUs >= FirewallHintAfterUs)
        {
            _firewallHintShown = true;
            if (s.NetworkPublic)
                Balloon("Not seeing your phone?", "This network is Public, so Windows may block the phone. Set it to Private in Settings.", ToolTipIcon.Warning);
            else
                Balloon("Not seeing your phone?", "Open GyroPhone: Home shows what to fix. Check the phone is on the same Wi-Fi.", ToolTipIcon.Info);
        }
    }

    /// <summary>Diagnostics as the input process has it: this side's log, the load each second, the overlay draw times (UI thread).</summary>
    private void ApplyDiagnostics()
    {
        if (St == null) return;
        bool on = Set.Diagnostics;
        if (on == _diagnosticsOn && on == _debug.Enabled) return;
        _diagnosticsOn = on;
        if (on != _debug.Enabled)
        {
            if (on) DebugTrace.SetLayout(KeyboardLayoutProbe.Read());
            _debug.SetOn(on, "switched");
        }
        _perf.SetDiagnostics(on, Set.DiagnosticsMinutes);
        OverlayTiming.On = on;
    }

    /// <summary>companion.json, when the overlays or the tray click changed (UI thread, each refresh).</summary>
    private void SaveUiSettings()
    {
        if (_overlays.TakeDirty() is not { } json) return;
        try
        {
            _uiStore.WriteAtomic(json);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _overlays.MarkDirty();
            _log.Write($"companion.json not saved: {e.GetType().Name}: {e.Message}");
        }
    }

    /// <summary>What stops the phone or the gamepad from working, each with its fix, for Home's warning bars.</summary>
    private List<Problem> Problems(HostSnapshot s)
    {
        var list = new List<Problem>();
        var st = St;
        if (st == null) return list;
        if (st.Server != nameof(ServerState.Running) && !(st.BindRetrying && !st.BindWaitLong))
            list.Add(new Problem(st.Server == nameof(ServerState.PortInUse)
                ? $"Port {Wire.Port} is in use by another program, so the phone can't connect. Close it: GyroPhone keeps trying and starts listening once it is free."
                : "The network server failed to start. Restart GyroPhone; the log has the details.", "Open log folder", OpenLogFolder));
        if (st.KeyError != null)
            list.Add(new Problem(st.KeyRetrying
                ? $"Pairing by QR code and key files is off for now: host.key is held by another program ({st.KeyError}). GyroPhone keeps trying."
                : $"Pairing by QR code and key files is off: host.key could not be read ({st.KeyError}).", "Open log folder", OpenLogFolder));
        if (s.NetworkPublic && _firewallHintShown && !s.AnyDatagramSeen)
            list.Add(new Problem("No phone seen yet, and this network is Public, so Windows may block it.", "Make it Private…", OpenNetworkSettings));
        if (_firewallOk == false)
            list.Add(new Problem("GyroPhone isn't allowed through the firewall yet, so Windows may block the phone.", "Allow…", AllowThroughFirewall));
        else if (_firewallHintShown && !s.AnyDatagramSeen && !s.NetworkPublic)
            list.Add(new Problem("No phone seen yet. Check the phone is on the same Wi-Fi as this PC and GyroPhone is open on it."));
        if (s.OutputMode == OutputMode.DualSense && !s.DualSenseOk)
            list.Add(new Problem("No gamepad: the DualSense needs the GyroPhone pad driver (driver\\install.cmd next to the companion), then restart GyroPhone. Keyboard and mouse still work."));
        else if (s.OutputMode != OutputMode.None && s.OutputMode != OutputMode.DualSense && !s.DriverOk)
            list.Add(new Problem($"No gamepad: {st.PadError ?? "the ViGEmBus driver is missing"}. Keyboard and mouse still work.", "Install driver…", OpenDriverDownload));
        if (s.ElevatedForeground && !st.Elevated)
            list.Add(new Problem("The app in front runs as administrator, so input can't reach it.", "Restart as administrator", RestartAsAdmin));
        if (st.UsbEnabled && UsbState == UsbDirectState.AdbBusy)
            list.Add(new Problem("adb is holding the phone, so the USB cable can't be used.", "Stop adb and use the cable", TakeUsbFromAdb));
        if (st.UsbEnabled && UsbState == UsbDirectState.NeedsDriver)
            list.Add(new Problem("The phone is on the USB cable but Windows needs the WinUSB driver for it.", "How to install…", ShowUsbDriverHelp));
        return list;
    }

    // =====================================================================================
    // ICompanion: what the WPF windows read and ask for
    // =====================================================================================

    public CompanionView View()
    {
        var st = St;
        var s = _refreshing?.Snapshot ?? Snap;
        var set = Set;
        bool usbOn = st?.UsbEnabled ?? false;
        var help = !usbOn ? UsbHelp.None : UsbState switch
        {
            UsbDirectState.AdbBusy => UsbHelp.AdbBusy,
            UsbDirectState.NeedsDriver => UsbHelp.NeedsDriver,
            _ => UsbHelp.None,
        };
        string server = st == null ? "Starting the input process." : !_input.Attached ? "The input process is starting again."
            : st.Server == nameof(ServerState.Running) ? $"Listening on UDP port {Wire.Port}."
            : st.Server == nameof(ServerState.PortInUse) ? $"Port {Wire.Port} is in use{(st.BindWaitLong ? " by another program" : "")}; retrying." : $"Not running ({st.Server}).";
        string hostKey = s.HostKeyFingerprint is { } fp ? $"This PC's key: {fp}"
            : $"This PC has no key{(st?.KeyError != null ? $" ({st.KeyError})" : "")}: pairing by QR code and key files is off.";
        return new CompanionView(s,
            $"{Program.AppName} {Application.ProductVersion}",
            _statusLine,
            _updater.Pending,
            IsAutoStartOn(),
            IsWatcherOn(),
            TrayClick,
            usbOn,
            UsbDirectText(),
            help,
            _firewallOk,
            _usbRuleOk,
            st?.Elevated ?? ElevationProbe.IsSelfElevated,
            server,
            s.DriverOk ? null : st?.PadError,
            hostKey,
            st?.KeysFolder ?? "",
            _log.Path,
            _refreshing?.Problems ?? Problems(s),
            set.MeasureButtons,
            st?.Buttons,
            set.Diagnostics,
            set.CatchUpMs,
            set.PressKeyNotice,
            set.PressKeyListen,
            set.DiagnosticsMinutes,
            Volatile.Read(ref _diagSaved),
            set.UseSendInput,
            st?.InputDriverReady == true ? InputDriverState.Ready : st?.InputDriverInstalled == true ? InputDriverState.NotPlugged : InputDriverState.NotInstalled);
    }


    public void SetCatchUpMs(int ms)
    {
        In.SetCatchUpMs(ms);
        Refresh();
    }

    public void ToggleUseSendInput()
    {
        In.SetUseSendInput(!Set.UseSendInput);
        Refresh();
    }

    public void ToggleDiagnostics()
    {
        In.SetDiagnostics(!Set.Diagnostics);
        Refresh();
    }

    public void SetDiagnosticsMinutes(int minutes)
    {
        In.SetDiagnosticsMinutes(minutes);
        _perf.SetDiagnostics(Set.Diagnostics, minutes);
        Refresh();
    }

    public void SaveDiagnostics() => In.SaveDiagnostics();

    public void TogglePressKeyListen()
    {
        In.SetPressKeyListen(!Set.PressKeyListen);
        Refresh();
    }

    public void TogglePressKeyNotice()
    {
        In.SetPressKeyNotice(!Set.PressKeyNotice);
        Refresh();
    }

    public void ToggleMeasureButtons()
    {
        In.SetMeasureButtons(!Set.MeasureButtons);
        Refresh();
    }

    public string DetailsText() => StatusText();

    public void TakeUsbFromAdb() => In.TakeUsbFromAdb();

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

    public TrayClickAction TrayClick => _overlays.Ui.TrayClick;
    public IReadOnlyList<OverlaySettings> Overlays => _overlays.Ui.Overlays;

    public OverlaySettings? OverlayOf(string id) => _overlays.Ui.Overlays.Find(o => o.Id == id);

    /// <summary>Its label, else its kind's name, numbered when there are several of that kind.</summary>
    public string OverlayName(OverlaySettings o)
    {
        if (!string.IsNullOrWhiteSpace(o.Label)) return o.Label.Trim();
        var same = _overlays.Ui.Overlays.Where(x => x.Kind == o.Kind).ToList();
        var name = OverlaySettings.KindName(o.Kind);
        return same.Count > 1 ? $"{name} {same.IndexOf(o) + 1}" : name;
    }

    public void SetTrayClick(TrayClickAction action)
    {
        _overlays.Change(s => s.TrayClick = action);
        Refresh();
    }

    public void SetOutputMode(OutputMode mode) => In.SetOutputMode(mode);

    public void TogglePaused() => In.SetPaused(!Snap.Paused);

    public void PairNewPhone()
    {
        In.OpenPairingWindow();
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
            var w = new PairWindow(_pairing, Set.HostName, "Pair new phone - " + Program.AppName, WindowIcon());
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
        // Asked again while the window is still opening (a second click, a message handled during Show):
        // WPF then gives the half-made window to two screens at once ("The root Visual of a VisualTarget
        // cannot have a parent"), so the second ask is dropped.
        if (_openingMain)
        {
            _log.Write("main window: asked to open while it is opening; ignored");
            return;
        }
        _openingMain = true;
        try
        {
            OpenMainWindow(page);
        }
        catch (Exception e)
        {
            _log.Write($"main window failed, trying a new one: {e.GetType().Name}: {e.Message}");
            // A window whose Show failed stays broken: drop it and try once with a new one.
            var broken = _mainWindow;
            _mainWindow = null;
            try
            {
                broken?.Close();
            }
            catch (Exception)
            {
            }
            try
            {
                OpenMainWindow(page);
            }
            catch (Exception e2)
            {
                _mainWindow = null;
                WindowFailed(e2);
            }
        }
        finally
        {
            _openingMain = false;
        }
    }

    private bool _openingMain;

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
        foreach (var o in _overlays.Ui.Overlays.ToList())
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
        if (_overlays.Ui.Overlays.Any(o => o.Kind == OverlayKind.Laser))
            menu.DropDownItems.Add(new ToolStripMenuItem("Clear pointers", null, (_, _) => ClearAllLasers()));
        menu.DropDownItems.Add(new ToolStripMenuItem("Overlay settings…", null, (_, _) => ShowMainWindow(MainPage.Overlays)));
    }

    /// <summary>Shows or closes overlay window <paramref name="id"/>, and remembers it for the next start.</summary>
    public void SetOverlayShown(string id, bool shown)
    {
        _overlays.UpdateOverlaySettings(id, o => o.Shown = shown);
        // Showing one while all are hidden: it should appear, so Hide all ends.
        if (shown) _overlays.SetOverlaysHidden(false);
        ApplyOverlays();
        Refresh();
    }

    public bool OverlaysHidden => _overlays.Ui.OverlaysHidden;

    /// <summary>Hide all: every overlay window closes, each keeping its own Show for when this is off.</summary>
    public void SetOverlaysHidden(bool hidden)
    {
        _overlays.SetOverlaysHidden(hidden);
        ApplyOverlays();
        Refresh();
    }

    public OverlayShortcuts Shortcuts => _overlays.Ui.OverlayShortcuts;

    /// <summary>" (Ctrl+Alt+R)" for a menu item or button, or "" when the action has no keys.</summary>
    public string KeysSuffix(OverlayAction a) => Shortcuts.Get(a) is { Length: > 0 } k ? $" ({k})" : "";

    /// <summary>Whether another app holds <paramref name="a"/>'s keys, so they do nothing here.</summary>
    public bool ShortcutTaken(OverlayAction a) => _hotkeys?.IsTaken(a) ?? false;

    /// <summary>Sets <paramref name="a"/>'s keys ("" = none) and holds them at once.</summary>
    public void SetShortcut(OverlayAction a, string keys)
    {
        _overlays.SetOverlayShortcut(a, keys);
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
        var under = _overlays.Ui.Overlays
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

    public bool OverlayEdit => _overlays.Ui.OverlayEdit;

    /// <summary>Edit: every overlay window can be dragged; off, the mouse goes through them all.</summary>
    public void SetOverlayEdit(bool on)
    {
        _overlays.SetOverlayEdit(on);
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
        _overlays.UpdateOverlaySettings(id, change);
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
        var id = _overlays.AddOverlay(kind);
        ApplyOverlays();
        Refresh();
        return id;
    }

    public IReadOnlyList<OverlaySettings> OverlayPresets => _overlays.Ui.OverlayPresets;

    public void SaveOverlayPreset(string id, string name)
    {
        _overlays.SaveOverlayPreset(id, name);
        Refresh();
    }

    public string? AddOverlayFromPreset(string presetId)
    {
        var id = _overlays.AddOverlayFromPreset(presetId);
        ApplyOverlays();
        Refresh();
        return id;
    }

    public void RenameOverlayPreset(string presetId, string name)
    {
        _overlays.RenameOverlayPreset(presetId, name);
        Refresh();
    }

    public void RemoveOverlayPreset(string presetId)
    {
        _overlays.RemoveOverlayPreset(presetId);
        Refresh();
    }

    public void RemoveOverlay(string id)
    {
        if (_overlayForms.Remove(id, out var f)) f.Close();
        _overlays.RemoveOverlay(id);
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

    public OverlayRestFade RestFade => _overlays.Ui.OverlayRestFade;

    public void ChangeRestFade(Action<OverlayRestFade> change)
    {
        _overlays.UpdateOverlayRestFade(change);
        RefreshPageNow();
    }

    public float? StillnessDps => _overlays.OverlayStillnessDps();

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
            if (!f.IsDisposed) f.Signal();
        // The draw times, into Diagnostics: every second while an overlay draws (so a short burst of fast moves
        // can be read back), else now and then.
        long now = Environment.TickCount64;
        if (now - _overlayStatsLoggedAt >= 1_000 && _debug.Enabled)
        {
            bool drawing = _overlayList.Any(f => f.Stats.Length > 0 && !f.Stats.EndsWith(": idle", StringComparison.Ordinal));
            if (_overlayStatsLoggedAt != 0 && (drawing || now - _overlayStatsQuietAt >= 30_000))
            {
                _debug.Add(DebugTrace.Kind.Note, OverlayStats());
                if (!drawing) _overlayStatsQuietAt = now;
            }
            _overlayStatsLoggedAt = now;
        }
    }

    private long _overlayStatsLoggedAt;
    private long _overlayStatsQuietAt;

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
        if (open.Length == 0) return "overlays: none open";
        string paint = GpuPaint.Status, shapes = Gpu3D.Status;
        return "overlays: " + string.Join(" · ", open.Select(f => f.Stats).Where(t => t.Length > 0)) + (paint.Length > 0 ? " · " + paint : "")
            + (shapes.Length > 0 ? " · " + shapes : "");
    }

    /// <summary>An overlay window that shows the phone's pose is open (the FPS overlay needs nothing from the phone).</summary>
    private bool PoseOverlaysOpen() => _overlayForms.Keys.Any(id => OverlayOf(id) is not { Kind: OverlayKind.Fps });

    private void ApplyOverlays()
    {
        foreach (var o in _overlays.Ui.Overlays.ToList())
        {
            try
            {
                ApplyOverlay(o);
            }
            catch (Exception e)
            {
                // An overlay must never take the companion down (it reopens at every start while Shown).
                _log.Write($"overlay failed and was turned off: {e}");
                _overlays.UpdateOverlaySettings(o.Id, x => x.Shown = false);
                if (_overlayForms.Remove(o.Id, out var f))
                    try { f.Dispose(); } catch (Exception) { }
                Balloon("Overlay failed", "It was turned off. The log has the details.", ToolTipIcon.Warning);
            }
        }
        // Windows whose settings are gone.
        foreach (var id in _overlayForms.Keys.Where(id => OverlayOf(id) == null).ToList())
            if (_overlayForms.Remove(id, out var f)) f.Close();
        _overlays.SetOverlay(PoseOverlaysOpen());
        // The game's frames are watched only while an FPS overlay is on.
        _perf.Watch(_overlays.Ui.Overlays.Any(o => o.Kind == OverlayKind.Fps && o.Shown));
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
            var form = new OverlayForm(_overlays, id, _log.Write, OverlayClicked, OverlayPicked);
            form.FormClosed += (_, _) =>
            {
                if (_overlayForms.TryGetValue(id, out var cur) && ReferenceEquals(cur, form))
                {
                    _overlayForms.Remove(id);
                    _overlays.SetOverlay(PoseOverlaysOpen());
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

    /// <summary>Debug recordings: Documents\GyroPhone\Debug, where they are easy to find and send.</summary>
    public static string DebugDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "GyroPhone", "Debug");

    private int _diagSaved;

    /// <summary>Diagnostics' Save (any thread): the PC's event log into the debug folder, and a record into Performance results.</summary>
    private void SaveDiagnosticsNow()
    {
        int minutes = Set.DiagnosticsMinutes;
        string? path = SaveDebug(minutes);
        try { _perf.Save(minutes, $"Diagnostics {DateTime.Now:HH:mm}"); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            _log.Write($"diagnostics record not saved: {e.GetType().Name}: {e.Message}");
        }
        if (path != null) Interlocked.Increment(ref _diagSaved);
        Post(Refresh);
    }

    /// <summary>Writes the last <paramref name="minutes"/> of the event log to the debug folder; returns the path, or null.</summary>
    public string? SaveDebug(int minutes)
    {
        try
        {
            Directory.CreateDirectory(DebugDirectory);
            var path = Path.Combine(DebugDirectory, $"pc-{DateTime.Now:yyyyMMdd-HHmmss}.txt");
            var header = new List<string> { $"{Program.AppName} {Application.ProductVersion}" };
            _debug.Drain();
            header.AddRange(DiagStatus().Split("\r\n"));
            File.WriteAllText(path, _debug.Save(header, minutes));
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

    /// <summary>The link, the pad and what is held now; the header of a PC Diagnostics save.</summary>
    private string DiagStatus()
    {
        var s = In.Snapshot() ?? Snap;
        var sb = new System.Text.StringBuilder();
        sb.Append(s.Connected ? $"phone: {s.PhoneName}{(s.Silent ? " (not answering)" : "")}" : "phone: not connected");
        sb.Append($" · output {s.OutputMode}{(s.PadPlugged ? "" : " (not plugged)")}{(s.DriverOk ? "" : " · no ViGEm driver")}");
        if (s.Paused) sb.Append(" · input paused");
        sb.Append("\r\nlink: ");
        sb.Append($"got {s.RxPerSec}/s · loss {s.LossPermille / 10.0:0.0}%");
        sb.Append(s.LatP50Us == GyroPhone.Protocol.HostStatus.Unknown ? " · latency unknown" :
            $" · latency {s.LatP50Us / 1000.0:0.0} / {s.LatP99Us / 1000.0:0.0} ms (p50/p99) · jitter {s.JitterUs / 1000.0:0.0} ms");
        if (s.PhoneLink is { } l)
            sb.Append($" · phone sends {l.SendRateHz}/s · round trip {l.RttMinUs / 1000.0:0.0} ms · {l.RssiDbm} dBm {l.LinkMbps} Mbps");
        sb.Append($"\r\npad: {_debug.CurrentPad}");
        var held = new List<string>();
        var scratch = new StateFrame();
        for (int u = 0; u < 256; u++)
            if (_region.Held.Held(LaserFire.Key + u, scratch)) held.Add(DebugTrace.UsageName((byte)u));
        sb.Append($"\r\nkeys (the phone's newest state): {(held.Count == 0 ? "none" : string.Join("+", held))}");
        sb.Append($"\r\nlogging: {(_debug.Enabled ? "on" : "off")} · raw events dropped {_debug.Dropped} · counters: {s.Counters}");
        if (Hello() is { } h) sb.Append($"\r\ninput process: pid {h.Pid}, {h.Version}");
        return sb.ToString();
    }

    private InputHello? Hello() => _input.Hello;

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
        var st = St;
        var s = In.Snapshot() ?? Snap;
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
        b.AppendLine($"Gamepad driver: {(s.DriverOk ? "ViGEmBus ok" : st?.PadError ?? "missing")}");
        b.AppendLine($"Input:        {(s.Paused ? "paused" : "active")}");
        b.AppendLine($"Network:      {(!s.NetworkPublic ? "Private or Domain" : s.AnyDatagramSeen ? "Public (the phone gets through)" : "Public (set it to Private if the phone can't connect)")}, firewall rule "
            + (_firewallOk switch { true => "present", false => "missing", null => "checking" })
            + ", USB rule " + (_usbRuleOk switch { true => "present", false => "missing", null => "checking" }));
        b.AppendLine($"Server:       {(st?.Server == nameof(ServerState.Running) ? $"UDP {Wire.Port}" : st?.Server ?? "starting")}, "
            + $"input process {(st?.Elevated == true ? "elevated" : "not elevated")}{(_input.Hello is { } h ? $" (pid {h.Pid})" : "")}");
        b.AppendLine($"Pairing:      {(s.PairingWindowS > 0 ? $"open, {s.PairingWindowS} s left" : "closed")}, {s.PairedPhones.Count} paired phone(s)"
            + $" ({s.PairedPhones.Count(p => p.ByKey)} by key)");
        b.AppendLine($"PC key:       {s.HostKeyFingerprint ?? "unavailable" + (st?.KeyError != null ? $" ({st.KeyError})" : "")}, {st?.KeysFolder}");
        b.AppendLine($"Phone keys:   {st?.AuthorizedScan ?? "not read (no PC key)"}");
        b.AppendLine($"Log:          {_log.Path}{(_log.LastError is { } le ? $" (cannot write: {le})" : "")}{(st?.LogError is { } ie ? $" (input process cannot write: {ie})" : "")}");
        b.AppendLine($"Running as:   {Environment.UserDomainName}\\{Environment.UserName}, {_exePath}");
        var c = s.Counters;
        b.AppendLine();
        b.AppendLine($"datagrams {c.Datagrams}  states {c.States}  injections {c.Injections}  SendInput short {st?.SendInputShortWrites ?? 0}  keys and mouse via {(st?.InputDriverReady == true ? "input driver" : "SendInput")}");
        b.AppendLine($"dropped {c.Dropped}  bad auth {c.BadAuth}  errors sent {c.Errors}  unknown keys {c.UnknownUsages}  exceptions {c.Exceptions + (st?.RxExceptions ?? 0)}");
        return b.ToString();
    }

    public void Forget(PairedPhoneInfo phone)
    {
        var text = phone.ByKey
            ? $"Forget {phone.Name}? This deletes its key file {Path.GetFileName(phone.KeyFile)}; run update.cmd or pair with a code to connect again."
            : $"Forget {phone.Name}? The phone will need to pair again.";
        var r = MessageBox.Show(text, Program.AppName, MessageBoxButtons.OKCancel, MessageBoxIcon.Question);
        if (r != DialogResult.OK) return;
        In.Forget(phone);
    }

    public PerfMonitor Perf => _perf;

    public (string Text, string? Setup)? LinkPath()
    {
        var s = Snap;
        if (!s.Connected || s.PhoneLink is not { } l) return null;
        var pc = LinkPaths.AdapterFor(IPAddress.TryParse(In.PhoneAddress(), out var a) ? a : null);
        return (LinkPaths.Describe(l, pc), LinkPaths.Setup(l, pc));
    }

    public bool SetUpFpsHelper() => FpsHelperSetup.Install(_log.Write);

    public void OpenPerfFolder()
    {
        try
        {
            Directory.CreateDirectory(_perf.Folder);
            using var p = Process.Start(new ProcessStartInfo("explorer.exe", $"\"{_perf.Folder}\"") { UseShellExecute = true });
        }
        catch (Exception e) when (e is Win32Exception or IOException or UnauthorizedAccessException)
        {
            _log.Write($"open performance folder failed: {e.GetType().Name}");
        }
    }

    public void OpenPerfResults()
    {
        if (_perf.WriteReport() is { } page) OpenFile(page);
    }

    public void OpenFile(string path)
    {
        try
        {
            if (File.Exists(path)) using (Process.Start(new ProcessStartInfo(path) { UseShellExecute = true })) { }
        }
        catch (Exception e) when (e is Win32Exception or IOException or UnauthorizedAccessException)
        {
            _log.Write($"open file failed: {e.GetType().Name}");
        }
    }

    public void OpenKeysFolder()
    {
        try
        {
            if (St?.KeysFolder is not { Length: > 0 } folder) return;
            Directory.CreateDirectory(Path.Combine(folder, "authorized_phones"));
            using var p = Process.Start(new ProcessStartInfo("explorer.exe", $"\"{folder}\"") { UseShellExecute = true });
        }
        catch (Exception e) when (e is Win32Exception or IOException or UnauthorizedAccessException)
        {
            _log.Write($"open keys folder failed: {e.GetType().Name}");
        }
    }

    public void ToggleUsbDirect()
    {
        In.SetUsbDirect(!(St?.UsbEnabled ?? false));
        Refresh();
    }

    private UsbDirectState UsbState => Enum.TryParse<UsbDirectState>(St?.UsbState, out var u) ? u : UsbDirectState.Off;

    private string UsbDirectText()
    {
        string detail = St?.UsbDetail is { Length: > 0 } d ? $" ({d})" : "";
        return UsbState switch
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
        if (TrySetAutoStart(enable)) In.SetStartWithWindows(enable);
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
        if (In.RetryDriver() is { } error)
        {
            MessageBox.Show($"{error}.\n\nInstall ViGEmBus from {ViGEmPadSink.DriverDownloadUrl}, then choose Retry driver again.",
                Program.AppName, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
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
        In.SetLocked(_sessionLocked || _suspended);
    }

    private void OnPowerModeChanged(object? sender, PowerModeChangedEventArgs e)
    {
        if (e.Mode == PowerModes.Suspend) _suspended = true;
        else if (e.Mode == PowerModes.Resume) _suspended = false;
        else return;
        In.SetLocked(_sessionLocked || _suspended);
    }

    private void OnApplicationExit(object? sender, EventArgs e) => Shutdown();

    private void OnProcessExit(object? sender, EventArgs e) => Shutdown();

    private void ExitApp()
    {
        _pairWindow?.Close();
        _mainWindow?.Close();
        foreach (var f in _overlayForms.Values.ToList()) f.Close();
        _overlayWheel?.Dispose();
        _overlayWheel = null;
        _hotkeys?.Dispose();
        _hotkeys = null;
        _pacer?.Dispose();
        // The input process stops first (release-all, the goodbye to the phone), and is waited for: an update replaces the exe.
        _input.ExitInput();
        Shutdown();
        ExitThread();
    }

    /// <summary>Saves and stops this side. Runs once. The input process stops only on <see cref="ExitApp"/>.</summary>
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
            _perf.Dispose();
            if (_overlays.TakeDirty() is { } json) _uiStore.WriteAtomic(json);
            _debug.SetOn(false, "exit");
            _input.Dispose();
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
            _overlayWake.Dispose();
            _log.Dispose();
        }
        base.Dispose(disposing);
    }

    /// <summary>Pairing for the pair window: the input process does it, through this companion.</summary>
    private sealed class Pairing(TrayApp app) : IPairing
    {
        public HostSnapshot Snapshot() => app.In.Snapshot() ?? app.Snap;
        public byte[]? HostPublicKey => app.In.HostPublicKey();
        public void OpenPairingWindow() => app.In.OpenPairingWindow();
        public void AnswerPair(uint attemptId, bool allow) => app.In.AnswerPair(attemptId, allow);
        public byte[]? OpenQrPairing() => app.In.OpenQrPairing();
        public void CloseQrPairing() => app.In.CloseQrPairing();
    }
}
