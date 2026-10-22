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
/// menu, and the pair and status windows. Engine callbacks (IHostEvents, IPairPrompt) arrive on the
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
    private readonly ToolStripMenuItem _uipiItem;
    private readonly ToolStripMenuItem _networkItem;
    private readonly ToolStripMenuItem _firewallItem;
    private readonly ToolStripMenuItem _installDriverItem, _retryDriverItem;
    private readonly ToolStripMenuItem _restartAdminItem;
    private readonly System.Windows.Forms.Timer _uiTimer;
    private readonly CompanionUpdater _updater;
    private readonly ToolStripMenuItem _versionItem;

    private PairForm? _pairForm;
    private StatusForm? _statusForm;
    private int _refreshQueued;
    private bool? _firewallOk;
    private bool _firewallHintShown, _uipiHintShown, _publicHintShown;
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
            _log.Write($"key file pairing off: {e.GetType().Name}");
        }
        _engine = new HostEngine(settings, _pad, _keyMouse, this, protector, _clock, this, hostKey: hostKey);
        if (hostKey != null) _authorized = new AuthorizedPhonesWatcher(_keyFiles, _engine.SetAuthorizedPhones, _log.Write);
        _server = new UdpHostServer(_engine, _clock,
            onRxThreadStart: () => Mmcss.TryEnter("Games", Mmcss.AvrtPriorityHigh, _log.Write), log: _log.Write);
        _watchdog = new Watchdog(_engine, _clock, _marker, store,
            ElevationProbe.IsForegroundElevated, NetworkCategory.IsAnyConnectedPublic, _log.Write);

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
        var statusFormItem = new ToolStripMenuItem("Status…", null, (_, _) => ShowStatusForm());
        _pauseItem = new ToolStripMenuItem("Pause input");
        _pauseItem.Click += (_, _) => _engine.SetPaused(!_pauseItem.Checked);
        _autoStartItem = new ToolStripMenuItem("Start with Windows", null, (_, _) => ToggleAutoStart());
        _uipiItem = new ToolStripMenuItem("The game runs as administrator: restart as administrator", null, (_, _) => RestartAsAdmin());
        _networkItem = new ToolStripMenuItem("This network is Public: make it Private…", null, (_, _) => Open(NetworkCategory.SettingsUri));
        _firewallItem = new ToolStripMenuItem("Allow through firewall…", null, (_, _) => AllowThroughFirewall());
        _installDriverItem = new ToolStripMenuItem("Install gamepad driver (ViGEmBus)…", null, (_, _) => Open(ViGEmPadSink.DriverDownloadUrl));
        _retryDriverItem = new ToolStripMenuItem("Retry driver", null, (_, _) => RetryDriver());
        _restartAdminItem = new ToolStripMenuItem("Restart as administrator", null, (_, _) => RestartAsAdmin());
        var logItem = new ToolStripMenuItem("Open log folder", null, (_, _) => OpenLogFolder());
        var exitItem = new ToolStripMenuItem("Exit", null, (_, _) => ExitApp());
        _versionItem = new ToolStripMenuItem($"{Program.AppName} {Application.ProductVersion}") { Enabled = false };
        _menu.Items.AddRange(new ToolStripItem[]
        {
            _versionItem,
            _statusItem,
            new ToolStripSeparator(),
            outputItem,
            pairItem,
            _phonesItem,
            keysFolderItem,
            _hostKeyItem,
            statusFormItem,
            _pauseItem,
            _autoStartItem,
            new ToolStripSeparator(),
            _uipiItem,
            _networkItem,
            _firewallItem,
            _installDriverItem,
            _retryDriverItem,
            _restartAdminItem,
            logItem,
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
        _tray.DoubleClick += (_, _) => ShowStatusForm();

        SystemEvents.SessionSwitch += OnSessionSwitch;
        SystemEvents.PowerModeChanged += OnPowerModeChanged;
        Application.ApplicationExit += OnApplicationExit;
        AppDomain.CurrentDomain.ProcessExit += OnProcessExit;

        _updater = new CompanionUpdater(_exePath, Application.ProductVersion, _log.Write,
            isIdle: () => !_engine.Snapshot().Connected && (_pairForm == null || _pairForm.IsDisposed),
            exitForUpdate: ExitApp);
        _updater.Changed += Refresh;

        _uiTimer = new System.Windows.Forms.Timer { Interval = 1000 };
        _uiTimer.Tick += (_, _) => Refresh();

        if (_server.Start())
        {
            // Owning the port is the single-instance guard: only now is the marker ours to recover.
            int recovered = HeldKeysMarker.Recover(_marker.Path, _keyMouse);
            if (recovered > 0) _log.Write($"released {recovered} inputs left held by a previous run");
            _engine.Start(_clock.NowUs);
            _watchdog.Start();
            _log.Write($"listening on UDP {Wire.Port} as '{settings.HostName}'");
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
        _uipiItem.Available = s.ElevatedForeground && !ElevationProbe.IsSelfElevated;
        _networkItem.Available = s.NetworkPublic;
        _firewallItem.Available = _firewallOk == false;
        _installDriverItem.Available = !s.DriverOk;
        _retryDriverItem.Available = !s.DriverOk;
        _restartAdminItem.Available = !ElevationProbe.IsSelfElevated;
        RebuildPhones(s.PairedPhones);
        _hostKeyItem.Text = s.HostKeyFingerprint is { } fp ? $"This PC's key: {fp}" : "This PC's key: unavailable (see log)";
        _versionItem.Text = _updater.Pending is { } pending
            ? $"{Program.AppName} {Application.ProductVersion} · {pending} starts when the phone disconnects"
            : $"{Program.AppName} {Application.ProductVersion}";
        if (!s.Connected) _updater.TryRestart();

        if (s.ElevatedForeground && !ElevationProbe.IsSelfElevated && s.Connected && !_uipiHintShown)
        {
            _uipiHintShown = true;
            Balloon("Input blocked for this window", "The window in front runs as administrator, so Windows blocks injected keys and mouse. "
                + "Choose \"Restart as administrator\" in the menu. The game pad still works.", ToolTipIcon.Warning);
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

    private void PairNewPhone()
    {
        _engine.OpenPairingWindow();
        OpenPairForm();
    }

    private void OpenPairForm()
    {
        if (_pairForm == null || _pairForm.IsDisposed)
        {
            var form = new PairForm(_engine, _engine.Settings.HostName);
            form.FormClosed += (_, _) =>
            {
                if (ReferenceEquals(_pairForm, form)) _pairForm = null; // a closed modeless form disposes itself
            };
            _pairForm = form;
        }
        _pairForm.Show();
        _pairForm.Activate();
    }

    private void ShowStatusForm()
    {
        if (_statusForm == null || _statusForm.IsDisposed)
        {
            var form = new StatusForm(StatusText);
            form.FormClosed += (_, _) =>
            {
                if (ReferenceEquals(_statusForm, form)) _statusForm = null;
            };
            _statusForm = form;
        }
        _statusForm.Show();
        _statusForm.Activate();
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
            b.AppendLine($"Phone Wi-Fi:  {band}, {l.FreqMhz} MHz, {l.LinkMbps} Mbps, {l.RssiDbm} dBm");
            b.AppendLine($"Phone sends:  {l.SendRateHz} Hz, RTT min {l.RttMinUs / 1000.0:0.0} ms, low-latency Wi-Fi {lockState}");
        }
        b.AppendLine($"Output:       {OutputName(s.OutputMode)}, pad {(s.PadPlugged ? "plugged" : "not plugged")}");
        b.AppendLine($"Gamepad driver: {(s.DriverOk ? "ViGEmBus ok" : _padError ?? "missing")}");
        b.AppendLine($"Input:        {(s.Paused ? "paused" : "active")}{(s.ElevatedForeground ? ", window in front runs as administrator" : "")}");
        b.AppendLine($"Network:      {(s.NetworkPublic ? "Public (set it to Private)" : "Private or Domain")}, firewall rule "
            + (_firewallOk switch { true => "present", false => "missing", null => "checking" }));
        b.AppendLine($"Server:       {(_server.State == ServerState.Running ? $"UDP {Wire.Port}" : _server.State.ToString())}, "
            + $"this app {(ElevationProbe.IsSelfElevated ? "elevated" : "not elevated")}");
        b.AppendLine($"Pairing:      {(s.PairingWindowS > 0 ? $"open, {s.PairingWindowS} s left" : "closed")}, {s.PairedPhones.Count} paired phone(s)"
            + $" ({s.PairedPhones.Count(p => p.ByKey)} by key)");
        b.AppendLine($"PC key:       {s.HostKeyFingerprint ?? "unavailable"}, {_keyFiles.Directory}");
        var c = s.Counters;
        b.AppendLine();
        b.AppendLine($"datagrams {c.Datagrams}  states {c.States}  injections {c.Injections}  SendInput short {_keyMouse.ShortWrites}");
        b.AppendLine($"dropped {c.Dropped}  bad auth {c.BadAuth}  errors sent {c.Errors}  unknown keys {c.UnknownUsages}  exceptions {c.Exceptions + _server.RxExceptions}");
        return b.ToString();
    }

    private void Forget(PairedPhoneInfo phone)
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

    private void OpenKeysFolder()
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

    private void ToggleAutoStart()
    {
        bool enable = !IsAutoStartOn();
        if (TrySetAutoStart(enable)) _engine.SetStartWithWindows(enable);
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
        Post(() =>
        {
            _firewallOk = ok;
            Refresh();
        });
    });

    private void AllowThroughFirewall() => Task.Run(() =>
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

    private void RetryDriver()
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

    private void RestartAsAdmin()
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

    private void OpenLogFolder()
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
        _statusForm?.Close();
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
            AppDomain.CurrentDomain.ProcessExit -= OnProcessExit;
            _uiTimer.Dispose();
            _pairForm?.Dispose();
            _statusForm?.Dispose();
            _tray.Dispose();
            _menu.Dispose();
            _icons.Dispose();
            _ui.Dispose();
            _log.Dispose();
        }
        base.Dispose(disposing);
    }
}
