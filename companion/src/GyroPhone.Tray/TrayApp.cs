using System.ComponentModel;
using System.Diagnostics;
using System.Text;
using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;
using GyroPhone.Windows;
using Microsoft.Win32;

namespace GyroPhone.Tray;

/// <summary>
/// The tray application: owns the engine, UDP server and watchdog, the notification-area icon and
/// menu, and the pair and main windows. Engine callbacks (IHostEvents, IPairPrompt) arrive on the
/// rx or watchdog thread under the engine lock, so they only post to the UI thread.
/// </summary>
internal sealed class TrayApp : ApplicationContext, IHostEvents, IPairPrompt
{
    private const long FirewallHintAfterUs = 30_000_000;

    private readonly FileLog _log;
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
    private readonly AuthorizedPhonesWatcher? _authorized;
    private readonly AoaUsbLink _usb;
    private IPadSink _pad;
    private string? _padError;

    private readonly TrayIcons _icons = new();
    private readonly NotifyIcon _tray;
    private readonly ContextMenuStrip _menu;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _outX360, _outDs4, _outOff;
    private readonly ToolStripMenuItem _phonesItem;
    private readonly ToolStripMenuItem _hostKeyItem;
    private readonly ToolStripMenuItem _pauseItem;
    private readonly ToolStripMenuItem _autoStartItem;
    private readonly ToolStripMenuItem _watcherItem;
    private readonly ToolStripMenuItem _networkItem;
    private readonly ToolStripMenuItem _firewallItem;
    private readonly ToolStripMenuItem _usbFirewallItem;
    private readonly ToolStripMenuItem _usbDirectItem, _usbDriverItem, _usbTakeItem;
    private readonly ToolStripMenuItem _installDriverItem, _retryDriverItem;
    private readonly ToolStripMenuItem _restartAdminItem;
    private readonly System.Windows.Forms.Timer _uiTimer;
    private readonly CompanionUpdater _updater;
    private readonly ToolStripMenuItem _versionItem;
    private readonly ToolStripMenuItem _clickMainItem, _clickPairItem;

    private PairForm? _pairForm;
    private MainForm? _mainForm;
    private DebugForm? _debugForm;
    // The open overlay windows, by settings id.
    private readonly Dictionary<string, OverlayForm> _overlayForms = [];
    // The open windows, for the pacer thread (the dictionary is the UI thread's).
    private volatile OverlayForm[] _overlayList = [];
    // The overlays' global shortcuts; null until the server owns the port (one instance holds them).
    private Hotkeys? _hotkeys;
    private OverlayPacer? _pacer;
    private string? _selectedOverlay;
    private int _refreshQueued;
    private bool? _firewallOk;
    private bool? _usbRuleOk;
    /// <summary>Why key file pairing is off (the host key could not be loaded), or null.</summary>
    private readonly string? _keyError;
    private bool _firewallHintShown, _publicHintShown;
    private string _phonesKey = "";
    private bool _sessionLocked, _suspended;
    private int _exited;

    public TrayApp()
    {
        _log = new FileLog();
        _exePath = Environment.ProcessPath ?? Application.ExecutablePath;
        _startUs = _clock.NowUs;
        _ui = new Control();
        _ = _ui.Handle; // create the window handle so BeginInvoke works from other threads
        _log.Write($"starting {Application.ProductVersion}, elevated {ElevationProbe.IsSelfElevated}");

        _keyMouse = new SendInputSink(_log.Write);
        _marker = new HeldKeysMarker(HeldKeysMarker.DefaultPath());

        var store = new SettingsStore(SettingsStore.DefaultPath());
        var settings = store.Load(Environment.MachineName, _log.Write);
        _pad = ViGEmPadSink.Create(_log.Write, out _padError);
        var protector = new DpapiKeyProtector();
        _keyFiles = new KeyFiles(KeyFiles.DefaultDirectory());
        GyroPhone.Protocol.Crypto.P256? hostKey = null;
        try
        {
            hostKey = _keyFiles.LoadOrCreateHostKey(protector, settings.HostName, _log.Write);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Security.Cryptography.CryptographicException)
        {
            _keyError = $"{e.GetType().Name}: {e.Message}";
            _log.Write($"key file pairing off: {_keyError}");
        }
        _engine = new HostEngine(settings, _pad, _keyMouse, this, protector, _clock, this, hostKey: hostKey);
        if (hostKey != null) _authorized = new AuthorizedPhonesWatcher(_keyFiles, _engine.SetAuthorizedPhones, _log.Write);
        PowerQos.TryDisableForProcess(_log.Write);
        _server = new UdpHostServer(_engine, _clock,
            onRxThreadStart: () =>
            {
                Mmcss.TryEnter("Games", Mmcss.AvrtPriorityHigh, _log.Write);
                PowerQos.TryDisableForThread(_log.Write);
            },
            log: _log.Write);
        _usb = new AoaUsbLink(Wire.Port, settings.UsbDirect, _log.Write);
        _watchdog = new Watchdog(_engine, _clock, _marker, store,
            networkPublicProbe: NetworkCategory.IsAnyConnectedPublic, log: _log.Write);

        // Keep the Run entry pointing at this exe (it may have moved since it was enabled).
        if (settings.StartWithWindows) TrySetAutoStart(true);

        _menu = new ContextMenuStrip();
        _statusItem = new ToolStripMenuItem("Starting…") { Enabled = false };
        _outX360 = new ToolStripMenuItem("Xbox 360", null, (_, _) => _engine.SetOutputMode(OutputMode.Xbox360));
        _outDs4 = new ToolStripMenuItem("DualShock 4", null, (_, _) => _engine.SetOutputMode(OutputMode.DualShock4));
        _outOff = new ToolStripMenuItem("Off (keyboard and mouse only)", null, (_, _) => _engine.SetOutputMode(OutputMode.None));
        var outputItem = new ToolStripMenuItem("Output");
        outputItem.DropDownItems.AddRange(new ToolStripItem[] { _outX360, _outDs4, _outOff });
        var pairItem = new ToolStripMenuItem("Pair new phone…", null, (_, _) => PairNewPhone());
        _phonesItem = new ToolStripMenuItem("Paired phones");
        _hostKeyItem = new ToolStripMenuItem("This PC's key: none") { Enabled = false };
        var keysFolderItem = new ToolStripMenuItem("Open keys folder", null, (_, _) => OpenKeysFolder());
        var mainFormItem = new ToolStripMenuItem("Open main window", null, (_, _) => ShowMainForm());
        _pauseItem = new ToolStripMenuItem("Pause input", null, (_, _) => TogglePaused());
        _clickMainItem = new ToolStripMenuItem("Opens the main window", null, (_, _) => SetTrayClick(TrayClickAction.OpenMainWindow));
        _clickPairItem = new ToolStripMenuItem("Pairs a new phone", null, (_, _) => SetTrayClick(TrayClickAction.PairNewPhone));
        var trayClickItem = new ToolStripMenuItem("Clicking the tray icon");
        trayClickItem.DropDownItems.AddRange(new ToolStripItem[] { _clickMainItem, _clickPairItem });
        _autoStartItem = new ToolStripMenuItem("Start with Windows", null, (_, _) => ToggleAutoStart());
        _watcherItem = new ToolStripMenuItem("Start when my phone appears", null, (_, _) => ToggleWatcher());
        _networkItem = new ToolStripMenuItem("This network is Public: make it Private…", null, (_, _) => OpenNetworkSettings());
        _firewallItem = new ToolStripMenuItem("Allow through firewall…", null, (_, _) => AllowThroughFirewall());
        _usbFirewallItem = new ToolStripMenuItem("Allow the phone over a USB cable…", null, (_, _) => AllowUsb());
        _usbDirectItem = new ToolStripMenuItem("Use the phone over the USB cable", null, (_, _) => ToggleUsbDirect());
        _usbTakeItem = new ToolStripMenuItem("adb is holding the phone: stop adb and use the cable", null, (_, _) => _usb.TakeFromAdb());
        _usbDriverItem = new ToolStripMenuItem("Direct USB needs a driver: how to install it…", null, (_, _) => ShowUsbDriverHelp());
        _installDriverItem = new ToolStripMenuItem("Install gamepad driver (ViGEmBus)…", null, (_, _) => OpenDriverDownload());
        _retryDriverItem = new ToolStripMenuItem("Retry driver", null, (_, _) => RetryDriver());
        _restartAdminItem = new ToolStripMenuItem("Restart as administrator", null, (_, _) => RestartAsAdmin());
        var logItem = new ToolStripMenuItem("Open log folder", null, (_, _) => OpenLogFolder());
        var debugItem = new ToolStripMenuItem("Debug window…", null, (_, _) => ShowDebugForm());
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
        _menu.Items.AddRange(new ToolStripItem[]
        {
            _versionItem,
            _statusItem,
            new ToolStripSeparator(),
            mainFormItem,
            outputItem,
            pairItem,
            _phonesItem,
            keysFolderItem,
            _hostKeyItem,
            _pauseItem,
            _autoStartItem,
            _watcherItem,
            _usbDirectItem,
            overlayItem,
            trayClickItem,
            new ToolStripSeparator(),
            _networkItem,
            _firewallItem,
            _usbFirewallItem,
            _usbTakeItem,
            _usbDriverItem,
            _installDriverItem,
            _retryDriverItem,
            _restartAdminItem,
            logItem,
            debugItem,
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

        if (StartServer())
        {
            // Owning the port is the single-instance guard: only now is the marker ours to recover.
            int recovered = HeldKeysMarker.Recover(_marker.Path, _keyMouse);
            if (recovered > 0) _log.Write($"released {recovered} inputs left held by a previous run");
            _engine.Start(_clock.NowUs);
            _watchdog.Start();
            _usb.Start();
            _log.Write($"listening on UDP {Wire.Port} as '{settings.HostName}'");
            _pacer = new OverlayPacer(TickOverlays, _log.Write);
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
        else
        {
            _log.Write(_server.State == ServerState.PortInUse ? $"port {Wire.Port} in use" : "UDP server failed to start");
        }
        _uiTimer.Start();
        _updater.Start();
        CheckFirewallAsync();
        Refresh();

        if (_padError != null)
            Balloon("Gamepad output is off", $"{_padError}. Keyboard and mouse still work. Use \"Install gamepad driver\" in the menu.", ToolTipIcon.Warning);
        if (Program.JustUpdated)
            Balloon("GyroPhone Companion updated", $"Now running {Application.ProductVersion}. Pairing and settings are kept.", ToolTipIcon.Info);
        // First run: the engine opened the pairing window because nothing is paired yet.
        if (_engine.Snapshot().PairingWindowS > 0) OpenPairForm();
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
        _pairForm?.RefreshNow();
        Balloon("Pair a phone?", $"{phoneName} wants to pair. Code {sasDisplay}", ToolTipIcon.Info);
    });

    public void Close(uint attemptId) => Post(() => _pairForm?.RefreshNow());

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
        if (!serverOk)
        {
            state = TrayState.Error;
            status = _server.State == ServerState.PortInUse ? $"Port {Wire.Port} in use" : "Network error: restart the app";
        }
        else if (s.Connected)
        {
            state = padWanted && (!s.DriverOk || !s.PadPlugged) ? TrayState.Warning : TrayState.Connected;
            status = s.Silent ? $"{s.PhoneName} · no signal" : $"{s.PhoneName} · {s.RxPerSec}/s · {s.LossPermille / 10.0:0.0}% · p99 {Ms(s.LatP99Us)}";
            if (s.Paused) status += " · paused";
        }
        else
        {
            state = padWanted && !s.DriverOk ? TrayState.Warning : TrayState.Waiting;
            status = s.PairingWindowS > 0 ? $"Waiting for phone · pairing open {s.PairingWindowS / 60}:{s.PairingWindowS % 60:00}" : "Waiting for phone";
        }
        _statusItem.Text = status;
        _tray.Icon = _icons.For(state);
        _tray.Text = TrayText(_firewallHintShown && !s.AnyDatagramSeen ? "Not seeing your phone? Allow through firewall" : status);

        _outX360.Checked = s.OutputMode == OutputMode.Xbox360;
        _outDs4.Checked = s.OutputMode == OutputMode.DualShock4;
        _outOff.Checked = s.OutputMode == OutputMode.None;
        _pauseItem.Checked = s.Paused;
        _autoStartItem.Checked = IsAutoStartOn();
        _watcherItem.Checked = IsWatcherOn();
        _usbDirectItem.Checked = _usb.Enabled;
        _usbDirectItem.ToolTipText = UsbDirectText();
        _usbDriverItem.Available = _usb.State == UsbDirectState.NeedsDriver;
        _usbTakeItem.Available = _usb.State == UsbDirectState.AdbBusy;
        _networkItem.Available = s.NetworkPublic;
        _firewallItem.Available = _firewallOk == false;
        _usbFirewallItem.Available = _usbRuleOk == false;
        _installDriverItem.Available = !s.DriverOk;
        _retryDriverItem.Available = !s.DriverOk;
        _restartAdminItem.Available = !ElevationProbe.IsSelfElevated;
        _clickMainItem.Checked = TrayClick == TrayClickAction.OpenMainWindow;
        _clickPairItem.Checked = TrayClick == TrayClickAction.PairNewPhone;
        RebuildPhones(s.PairedPhones);
        _hostKeyItem.Text = s.HostKeyFingerprint is { } fp ? $"This PC's key: {fp}" : "This PC's key: unavailable (see Status)";
        _versionItem.Text = _updater.Pending is { } pending
            ? $"{Program.AppName} {Application.ProductVersion} · restarting into {pending}"
            : $"{Program.AppName} {Application.ProductVersion}";
        if (_mainForm != null && !_mainForm.IsDisposed)
        {
            _mainForm.RefreshNow(new MainView(s, $"{_versionItem.Text}\n{status}", _hostKeyItem.Text,
                _autoStartItem.Checked, _watcherItem.Checked, _networkItem.Available,
                _firewallOk == false, _usbFirewallItem.Available, _installDriverItem.Available, _restartAdminItem.Available));
        }

        if (s.NetworkPublic && !_publicHintShown)
        {
            _publicHintShown = true;
            Balloon("Network is Public", "Windows blocks the phone on Public networks. Set this network to Private in Settings.", ToolTipIcon.Warning);
        }
        if (!s.AnyDatagramSeen && serverOk && !_firewallHintShown && _clock.NowUs - _startUs >= FirewallHintAfterUs)
        {
            _firewallHintShown = true;
            if (_firewallOk != true) _firewallItem.Available = true;
            Balloon("Not seeing your phone?", "Allow GyroPhone through the firewall (menu), check the network is Private and the phone is on the same Wi-Fi.", ToolTipIcon.Info);
        }
    }

    private void RebuildPhones(IReadOnlyList<PairedPhoneInfo> phones)
    {
        var key = string.Join("|", phones.Select(p => Convert.ToHexString(p.PhoneId) + p.Name + p.KeyFile));
        if (key == _phonesKey) return;
        _phonesKey = key;
        _phonesItem.DropDownItems.Clear();
        if (phones.Count == 0)
        {
            _phonesItem.DropDownItems.Add(new ToolStripMenuItem("None yet") { Enabled = false });
            return;
        }
        foreach (var p in phones)
        {
            var phone = p;
            var item = new ToolStripMenuItem($"{p.Name} · {(p.ByKey ? "key" : "code")}");
            if (p.Fingerprint != null) item.DropDownItems.Add(new ToolStripMenuItem(p.Fingerprint) { Enabled = false });
            item.DropDownItems.Add(new ToolStripMenuItem("Forget", null, (_, _) => Forget(phone)));
            _phonesItem.DropDownItems.Add(item);
        }
    }

    /// <summary>A left click on the tray icon: what the "Clicking the tray icon" setting says.</summary>
    private void TrayClicked()
    {
        if (TrayClick == TrayClickAction.PairNewPhone) PairNewPhone();
        else ShowMainForm();
    }

    internal TrayClickAction TrayClick => _engine.Settings.TrayClick;
    internal IReadOnlyList<OverlaySettings> Overlays => _engine.Settings.Overlays;

    internal OverlaySettings? OverlayOf(string id) => _engine.Settings.Overlays.Find(o => o.Id == id);

    /// <summary>Its kind's name, numbered when there are several of that kind.</summary>
    internal string OverlayName(OverlaySettings o)
    {
        var same = _engine.Settings.Overlays.Where(x => x.Kind == o.Kind).ToList();
        var name = OverlaySettings.KindName(o.Kind);
        return same.Count > 1 ? $"{name} {same.IndexOf(o) + 1}" : name;
    }

    internal void SetTrayClick(TrayClickAction action)
    {
        _engine.SetTrayClick(action);
        Refresh();
    }

    internal void SetOutputMode(OutputMode mode) => _engine.SetOutputMode(mode);

    internal void TogglePaused() => _engine.SetPaused(!_engine.Snapshot().Paused);

    internal void PairNewPhone()
    {
        _engine.OpenPairingWindow();
        OpenPairForm();
    }

    private void OpenPairForm()
    {
        if (_pairForm == null || _pairForm.IsDisposed)
        {
            var form = new PairForm(_engine, _engine.Settings.HostName, _engine.HostPublicKey != null ? _keyFiles : null);
            form.FormClosed += (_, _) =>
            {
                if (ReferenceEquals(_pairForm, form)) _pairForm = null; // a closed modeless form disposes itself
            };
            _pairForm = form;
        }
        _pairForm.Show();
        _pairForm.Activate();
    }

    private void ShowMainForm()
    {
        if (_mainForm == null || _mainForm.IsDisposed)
        {
            var form = new MainForm(this, StatusText);
            form.FormClosed += (_, _) =>
            {
                if (ReferenceEquals(_mainForm, form)) _mainForm = null;
            };
            _mainForm = form;
        }
        Refresh();
        _mainForm.Show();
        if (_mainForm.WindowState == FormWindowState.Minimized) _mainForm.WindowState = FormWindowState.Normal;
        _mainForm.Activate();
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
        menu.DropDownItems.Add(new ToolStripMenuItem("Edit: drag the windows to move and resize" + KeysSuffix(OverlayAction.Edit), null, (_, _) => SetOverlayEdit(!OverlayEdit))
        {
            Checked = OverlayEdit,
        });
        menu.DropDownItems.Add(new ToolStripMenuItem("Level all: as held now shows face-on" + KeysSuffix(OverlayAction.LevelAll), null, (_, _) => LevelAll()));
        menu.DropDownItems.Add(new ToolStripMenuItem("Overlay settings…", null, (_, _) => ShowMainForm()));
    }

    /// <summary>Shows or closes overlay window <paramref name="id"/>, and remembers it for the next start.</summary>
    internal void SetOverlayShown(string id, bool shown)
    {
        _engine.UpdateOverlaySettings(id, o => o.Shown = shown);
        // Showing one while all are hidden: it should appear, so Hide all ends.
        if (shown) _engine.SetOverlaysHidden(false);
        ApplyOverlays();
        Refresh();
    }

    internal bool OverlaysHidden => _engine.Settings.OverlaysHidden;

    /// <summary>Hide all: every overlay window closes, each keeping its own Show for when this is off.</summary>
    internal void SetOverlaysHidden(bool hidden)
    {
        _engine.SetOverlaysHidden(hidden);
        ApplyOverlays();
        Refresh();
    }

    internal OverlayShortcuts Shortcuts => _engine.Settings.OverlayShortcuts;

    /// <summary>" (Ctrl+Alt+R)" for a menu item or button, or "" when the action has no keys.</summary>
    internal string KeysSuffix(OverlayAction a) => Shortcuts.Get(a) is { Length: > 0 } k ? $" ({k})" : "";

    /// <summary>Whether another app holds <paramref name="a"/>'s keys, so they do nothing here.</summary>
    internal bool ShortcutTaken(OverlayAction a) => _hotkeys?.IsTaken(a) ?? false;

    /// <summary>Sets <paramref name="a"/>'s keys ("" = none) and holds them at once.</summary>
    internal void SetShortcut(OverlayAction a, string keys)
    {
        _engine.SetOverlayShortcut(a, keys);
        ApplyShortcuts();
        Refresh();
    }

    /// <summary>A shortcut field is taking keys: the shortcuts let go meanwhile, so the press reaches it.</summary>
    internal void PauseShortcuts(bool paused)
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
    internal string? SelectedOverlay => _selectedOverlay;

    internal void SelectOverlay(string? id)
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

    private void OverlayPicked(string id)
    {
        SelectOverlay(id);
        Refresh();
    }

    internal bool OverlayEdit => _engine.Settings.OverlayEdit;

    /// <summary>Edit: every overlay window can be dragged; off, the mouse goes through them all.</summary>
    internal void SetOverlayEdit(bool on)
    {
        _engine.SetOverlayEdit(on);
        foreach (var f in _overlayForms.Values.ToList()) f.SettingsChanged();
        Refresh();
    }

    internal void ChangeOverlay(string id, Action<OverlaySettings> change)
    {
        _engine.UpdateOverlaySettings(id, change);
        if (_overlayForms.TryGetValue(id, out var f)) f.SettingsChanged();
        Refresh();
    }

    /// <summary>A new overlay window of <paramref name="kind"/>, shown; returns its id.</summary>
    internal string AddOverlay(OverlayKind kind)
    {
        var id = _engine.AddOverlay(kind);
        ApplyOverlays();
        Refresh();
        return id;
    }

    internal void RemoveOverlay(string id)
    {
        if (_overlayForms.Remove(id, out var f)) f.Close();
        _engine.RemoveOverlay(id);
        ApplyOverlays();
        Refresh();
    }

    /// <summary>Level: overlay <paramref name="id"/>'s pose now shows straight.</summary>
    internal void LevelOverlay(string id)
    {
        if (_overlayForms.TryGetValue(id, out var f)) f.Level();
        Refresh();
    }

    internal void LevelAll()
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
    }

    /// <summary>The open overlay windows changed (UI thread): the pacer's copy of the list, and whether it runs.</summary>
    private void OverlaysChanged()
    {
        _overlayList = [.. _overlayForms.Values];
        _pacer?.SetRunning(_overlayList.Length > 0);
    }

    /// <summary>The open overlay windows' draw times, for the Debug window.</summary>
    internal string OverlayStats() =>
        _overlayForms.Count == 0 ? "overlays: none open" : "overlays: " + string.Join(" · ", _overlayForms.Values.Select(f => f.Stats).Where(t => t.Length > 0));

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

    internal void ShowDebugForm()
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
    internal static string DebugDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "GyroPhone", "Debug");

    /// <summary>Writes the last 30 s of the debug log to the debug folder; returns the path, or null.</summary>
    internal string? SaveDebug()
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

    internal void OpenDebugFolder()
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

    internal void Forget(PairedPhoneInfo phone)
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

    internal void OpenKeysFolder()
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

    /// <summary>
    /// Binds UDP 47800. When the watcher holds it, asks it to let go and retries until it has
    /// (the watcher checks once a second).
    /// </summary>
    private bool StartServer()
    {
        if (_server.Start()) return true;
        if (_server.State != ServerState.PortInUse || !Watcher.AskToYieldPort()) return false;
        _log.Write($"port {Wire.Port} held by the watcher: asked it to yield");
        for (int i = 0; i < 20; i++)
        {
            Thread.Sleep(250);
            if (_server.Start()) return true;
            if (_server.State != ServerState.PortInUse) return false;
        }
        return false;
    }

    internal void ToggleUsbDirect()
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
            UsbDirectState.AdbBusy => "adb is holding the phone: choose \"stop adb and use the cable\" in the menu",
            UsbDirectState.Switching => $"switching the phone to accessory mode{detail}",
            UsbDirectState.WrongUsbMode => "set the phone's USB to No data transfer (tethering and file transfer block the switch), then replug",
            UsbDirectState.NeedsDriver => $"the phone is in accessory mode but needs the WinUSB driver{detail}",
            UsbDirectState.Connected => $"connected{detail}",
            _ => $"failed{detail}",
        };
    }

    /// <summary>The accessory interface has no driver in Windows: the user installs WinUSB once with Zadig.</summary>
    internal void ShowUsbDriverHelp()
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

    internal void ToggleAutoStart()
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
    internal void ToggleWatcher()
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

    internal void AllowUsb() => Task.Run(() =>
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

    internal void AllowThroughFirewall() => Task.Run(() =>
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

    internal void RetryDriver()
    {
        var pad = ViGEmPadSink.Create(_log.Write, out var error);
        if (error != null)
        {
            _padError = error;
            MessageBox.Show($"{error}.\n\nInstall ViGEmBus from {ViGEmPadSink.DriverDownloadUrl}, then choose Retry driver again.",
                Program.AppName, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        var old = _pad;
        _engine.SetPadSink(pad);
        _pad = pad;
        _padError = null;
        (old as IDisposable)?.Dispose();
        _log.Write("gamepad driver found");
        Balloon("Gamepad driver found", "Game pad output is on.", ToolTipIcon.Info);
        Refresh();
    }

    internal void RestartAsAdmin()
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

    internal void OpenLogFolder()
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

    internal void OpenNetworkSettings() => Open(NetworkCategory.SettingsUri);

    internal void OpenDriverDownload() => Open(ViGEmPadSink.DriverDownloadUrl);

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
        _pairForm?.Close();
        _mainForm?.Close();
        _debugForm?.Close();
        foreach (var f in _overlayForms.Values.ToList()) f.Close();
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
            _pairForm?.Dispose();
            _mainForm?.Dispose();
            _tray.Dispose();
            _menu.Dispose();
            _icons.Dispose();
            _ui.Dispose();
            _log.Dispose();
        }
        base.Dispose(disposing);
    }
}
