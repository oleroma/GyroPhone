using System.Windows;
using System.Windows.Controls;
using System.Windows.Shapes;
using GyroPhone.Host;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Ui;

/// <summary>Phones: pair a new one, the paired ones (each with Forget), and this PC's key.</summary>
internal sealed class PhonesPage : Page
{
    private readonly ICompanion _app;
    private readonly StackPanel _root = new();
    private readonly StackPanel _list = new();
    private readonly TextBlock _hostKey, _keysFolder;
    private string _listKey = "\0";

    public override FrameworkElement View => _root;

    public PhonesPage(ICompanion app)
    {
        _app = app;
        _root.Children.Add(Parts.Text("Phones", "H1"));

        var pair = Parts.Button("Pair new phone", app.PairNewPhone, "Accent", "");
        pair.Margin = new Thickness(0, 0, 0, 16);
        _root.Children.Add(pair);

        var (phones, pb) = Parts.Card("Paired phones");
        pb.Children.Add(_list);
        _root.Children.Add(phones);

        var (pc, cb) = Parts.Card("This PC");
        _hostKey = Parts.Text("");
        cb.Children.Add(_hostKey);
        _keysFolder = Parts.Text("", "Sub");
        cb.Children.Add(_keysFolder);
        cb.Children.Add(Parts.Text("Phones paired by key have a file in this folder. update.cmd puts them there; pairing by QR code adds them too.", "Hint"));
        cb.Children.Add(Parts.Button("Open keys folder", app.OpenKeysFolder, glyph: ""));
        _root.Children.Add(pc);
    }

    public override void Refresh(CompanionView v)
    {
        _hostKey.Text = v.HostKey;
        _keysFolder.Text = v.KeysFolder;
        var phones = v.Status.PairedPhones;
        var key = string.Join("|", phones.Select(p => Convert.ToHexString(p.PhoneId) + p.Name + p.KeyFile + p.LastSeenUtc.Ticks));
        if (key == _listKey) return;
        _listKey = key;
        _list.Children.Clear();
        if (phones.Count == 0)
        {
            _list.Children.Add(Parts.Text("No phones yet. Press \"Pair new phone\" and scan the QR code with GyroPhone.", "Sub"));
            return;
        }
        for (int i = 0; i < phones.Count; i++)
        {
            if (i > 0) _list.Children.Add(Parts.Divider());
            _list.Children.Add(PhoneRow(phones[i]));
        }
    }

    private Grid PhoneRow(PairedPhoneInfo p)
    {
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var icon = Parts.Glyph("", "AccentBrush", 20);
        icon.Margin = new Thickness(0, 0, 14, 0);
        g.Children.Add(icon);
        var sub = new List<string> { p.ByKey ? "Paired by key" : "Paired with a code" };
        if (p.Fingerprint != null) sub.Add(p.Fingerprint);
        if (p.LastSeenUtc > DateTime.MinValue.AddDays(1)) sub.Add($"last seen {p.LastSeenUtc.ToLocalTime():d MMM yyyy, HH:mm}");
        var text = Parts.Column(Parts.Text(p.Name), Parts.Text(string.Join(" · ", sub), "Sub"));
        Grid.SetColumn(text, 1);
        g.Children.Add(text);
        var forget = Parts.Button("Forget", () => _app.Forget(p), glyph: "");
        forget.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(forget, 2);
        g.Children.Add(forget);
        return g;
    }
}

/// <summary>Gamepad: what games see (Xbox 360, DualShock 4 or nothing), pause, and the ViGEmBus driver.</summary>
internal sealed class GamepadPage : Page
{
    private readonly StackPanel _root = new();
    private readonly Seg<OutputMode> _output;
    private readonly TextBlock _outputHint;
    private readonly CheckBox _pause;
    private readonly Ellipse _driverDot;
    private readonly TextBlock _driver;
    private readonly WrapPanel _driverButtons;

    public override FrameworkElement View => _root;

    public GamepadPage(ICompanion app)
    {
        _root.Children.Add(Parts.Text("Gamepad", "H1"));

        var (output, ob) = Parts.Card("Output");
        _output = new Seg<OutputMode>(app.SetOutputMode, false,
            ("Xbox 360", OutputMode.Xbox360), ("DualShock 4", OutputMode.DualShock4), ("DualSense", OutputMode.DualSense),
            ("Off", OutputMode.None));
        ob.Children.Add(_output.View);
        _outputHint = Parts.Text("", "Hint");
        ob.Children.Add(_outputHint);
        _root.Children.Add(output);

        var (input, ib) = Parts.Card("Input");
        _pause = Parts.Toggle("Pause input", app.TogglePaused, "Nothing from the phone reaches the PC until you turn this off.");
        ib.Children.Add(_pause);
        _root.Children.Add(input);

        var (driver, db) = Parts.Card("Driver");
        _driverDot = new Ellipse { Width = 9, Height = 9, Margin = new Thickness(0, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center };
        _driver = Parts.Text("");
        var row = new DockPanel();
        row.Children.Add(_driverDot);
        row.Children.Add(_driver);
        db.Children.Add(row);
        db.Children.Add(Parts.Text("The virtual gamepad needs the free ViGEmBus driver. Keyboard and mouse work without it.", "Hint"));
        _driverButtons = Parts.Row(
            Parts.Button("Install driver (ViGEmBus)…", app.OpenDriverDownload, "Accent", ""),
            Parts.Button("Try again", app.RetryDriver, glyph: ""));
        db.Children.Add(_driverButtons);
        _root.Children.Add(driver);
    }

    public override void Refresh(CompanionView v)
    {
        var s = v.Status;
        _output.Set(s.OutputMode);
        _output.Show(OutputMode.DualSense, s.DualSenseOk || s.OutputMode == OutputMode.DualSense);
        _outputHint.Text = s.OutputMode switch
        {
            OutputMode.DualSense => s.DualSenseOk
                ? "Games see a PlayStation DualSense controller (through the GyroPhone pad driver). Buttons and sticks only for now: no gyro, touchpad or rumble yet."
                : "The DualSense needs the GyroPhone pad driver: run driver\\install.cmd in GyroPhone's folder, then restart GyroPhone.",
            OutputMode.Xbox360 => "Games see an Xbox 360 controller: the widest support.",
            OutputMode.DualShock4 => "Games see a PlayStation DualShock 4 controller, with its button prompts.",
            _ => "No gamepad: the phone sends only keyboard and mouse.",
        };
        Parts.SetChecked(_pause, s.Paused);
        _driver.Text = s.DriverOk ? "ViGEmBus is installed and working." : $"Not working: {v.PadError ?? "ViGEmBus is missing"}.";
        _driverDot.SetResourceReference(Shape.FillProperty, s.DriverOk ? "OkBrush" : "WarnBrush");
        Parts.Show(_driverButtons, !s.DriverOk);
    }
}

/// <summary>Connection: network, firewall and USB checks with their fixes, the USB cable, and this app's server.</summary>
internal sealed class ConnectionPage : Page
{
    private readonly StackPanel _root = new();
    private readonly (Ellipse Dot, TextBlock Text, Button Fix) _network, _firewall, _usbRule, _server, _admin;
    private readonly CheckBox _usbDirect;
    private readonly Button _adb, _driverHelp;

    public override FrameworkElement View => _root;

    public ConnectionPage(ICompanion app)
    {
        _root.Children.Add(Parts.Text("Connection", "H1"));

        var (checks, cb) = Parts.Card("Wi‑Fi");
        _network = Check(cb, "Network", "Make it Private…", app.OpenNetworkSettings);
        cb.Children.Add(Parts.Divider());
        _firewall = Check(cb, "Firewall", "Allow…", app.AllowThroughFirewall);
        _root.Children.Add(checks);

        var (usb, ub) = Parts.Card("USB cable");
        _usbDirect = Parts.Toggle("Use the phone over the USB cable", app.ToggleUsbDirect, "");
        ub.Children.Add(_usbDirect);
        _adb = Parts.Button("Stop adb and use the cable", app.TakeUsbFromAdb, "Accent");
        _driverHelp = Parts.Button("How to install the USB driver…", app.ShowUsbDriverHelp, "Accent");
        ub.Children.Add(Parts.Row(_adb, _driverHelp));
        ub.Children.Add(Parts.Divider());
        _usbRule = Check(ub, "USB tethering", "Allow…", app.AllowUsb);
        _root.Children.Add(usb);

        var (server, sb) = Parts.Card("This app");
        _server = Check(sb, "Server", "", () => { });
        sb.Children.Add(Parts.Divider());
        _admin = Check(sb, "Administrator", "Restart as administrator", app.RestartAsAdmin);
        sb.Children.Add(Parts.Text("Games that run as administrator only take input from an app that does too.", "Hint"));
        _root.Children.Add(server);
    }

    /// <summary>One check row: a status dot, its name and state, and the button that fixes it.</summary>
    private static (Ellipse, TextBlock, Button) Check(StackPanel parent, string name, string fixLabel, Action fix)
    {
        var g = new Grid();
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(130) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var dot = new Ellipse { Width = 9, Height = 9, Margin = new Thickness(0, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center };
        g.Children.Add(dot);
        var n = Parts.Text(name);
        n.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(n, 1);
        g.Children.Add(n);
        var t = Parts.Text("", "Sub");
        t.Margin = new Thickness(0, 0, 12, 0);
        t.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(t, 2);
        g.Children.Add(t);
        var b = Parts.Button(fixLabel, fix);
        b.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(b, 3);
        g.Children.Add(b);
        g.MinHeight = 34;
        parent.Children.Add(g);
        return (dot, t, b);
    }

    private static void Set((Ellipse Dot, TextBlock Text, Button Fix) row, bool? ok, string text, bool showFix)
    {
        row.Dot.SetResourceReference(Shape.FillProperty, ok switch { true => "OkBrush", false => "WarnBrush", null => "FaintBrush" });
        row.Text.Text = text;
        Parts.Show(row.Fix, showFix);
    }

    public override void Refresh(CompanionView v)
    {
        var s = v.Status;
        // Public is only a problem when the phone doesn't get through.
        bool publicBlocks = s.NetworkPublic && !s.AnyDatagramSeen;
        Set(_network, !publicBlocks,
            !s.NetworkPublic ? "Private or Domain: the phone can reach this PC."
            : s.AnyDatagramSeen ? "Public, but the phone gets through."
            : "Public: Windows may block the phone. Set this network to Private if it can't connect.",
            publicBlocks);
        Set(_firewall, v.FirewallOk,
            v.FirewallOk switch { true => "GyroPhone is allowed through the firewall.", false => "No firewall rule: Windows may block the phone.", null => "Checking…" },
            v.FirewallOk == false);
        Set(_usbRule, v.UsbRuleOk,
            v.UsbRuleOk switch { true => "Allowed: the phone works over a tethering cable.", false => "Not allowed yet: needed for USB tethering.", null => "Checking…" },
            v.UsbRuleOk == false);
        Parts.SetChecked(_usbDirect, v.UsbDirect);
        if (Parts.HintOf(_usbDirect) is { } hint)
            hint.Text = v.UsbDirect ? char.ToUpperInvariant(v.UsbDirectText[0]) + v.UsbDirectText[1..] + "." : "Lowest latency: the phone talks to this app through the cable, no network. Needs USB debugging on the phone.";
        Parts.Show(_adb, v.UsbHelp == UsbHelp.AdbBusy);
        Parts.Show(_driverHelp, v.UsbHelp == UsbHelp.NeedsDriver);
        Parts.Show((FrameworkElement)_adb.Parent, v.UsbHelp != UsbHelp.None);
        Set(_server, v.ServerText.StartsWith("Listening", StringComparison.Ordinal), v.ServerText, false);
        Set(_admin, v.Elevated ? true : s.ElevatedForeground ? false : null,
            v.Elevated ? "Running as administrator." : s.ElevatedForeground ? "The app in front runs as administrator, so input can't reach it." : "Not running as administrator.",
            !v.Elevated);
    }
}

/// <summary>App: starting with Windows, the tray icon's click, logs and the debug window, and the version.</summary>
internal sealed class AppPage : Page
{
    private readonly StackPanel _root = new();
    private readonly CheckBox _autoStart, _watcher, _inputLog, _pressListen, _pressNotice;
    private readonly Seg<TrayClickAction> _trayClick;
    private readonly Seg<int> _catchUp;
    private readonly TextBlock _log, _version;

    public override FrameworkElement View => _root;

    public AppPage(ICompanion app)
    {
        _root.Children.Add(Parts.Text("App", "H1"));

        var (startup, sb) = Parts.Card("Startup");
        _autoStart = Parts.Toggle("Start with Windows", app.ToggleAutoStart, "The companion starts when you sign in and waits in the tray.");
        sb.Children.Add(_autoStart);
        sb.Children.Add(Parts.Divider());
        _watcher = Parts.Toggle("Start when my phone appears", app.ToggleWatcher,
            "A tiny watcher starts the companion when GyroPhone on your phone looks for this PC.");
        sb.Children.Add(_watcher);
        _root.Children.Add(startup);

        var (tray, tb) = Parts.Card("Tray icon");
        tb.Children.Add(Parts.Text("Clicking the icon"));
        _trayClick = new Seg<TrayClickAction>(app.SetTrayClick, false,
            ("Opens this window", TrayClickAction.OpenMainWindow), ("Pairs a new phone", TrayClickAction.PairNewPhone));
        _trayClick.View.Margin = new Thickness(0, 8, 0, 4);
        tb.Children.Add(_trayClick.View);
        tb.Children.Add(Parts.Text("Right-click the icon for the quick menu: pause, output, overlays.", "Hint"));
        _root.Children.Add(tray);

        var (press, pb) = Parts.Card("Press a key");
        _pressListen = Parts.Toggle("Use this PC's keys for Press a key", app.TogglePressKeyListen,
            "While \"Press a key\" is open on the phone, this PC's keyboard, mouse and controller count too. Only read: every press still reaches Windows and games. Off: only what is attached to the phone counts.");
        pb.Children.Add(_pressListen);
        pb.Children.Add(Parts.Divider());
        _pressNotice = Parts.Toggle("Notify when the phone listens for a key", app.TogglePressKeyNotice,
            "When \"Press a key\" opens on the phone, a notification says you can press a key, mouse button or controller button on this PC too.");
        pb.Children.Add(_pressNotice);
        _root.Children.Add(press);

        var (input, ib) = Parts.Card("Mouse");
        ib.Children.Add(Parts.Text("Catch-up time"));
        _catchUp = new Seg<int>(app.SetCatchUpMs, false, ("Off", 0), ("8 ms", 8), ("16 ms", 16), ("32 ms", 32));
        _catchUp.View.Margin = new Thickness(0, 8, 0, 4);
        ib.Children.Add(_catchUp.View);
        ib.Children.Add(Parts.Text("When Wi-Fi loses or holds up a stretch of gyro or mouse movement, it is played out over up to this long instead of landing as one jump. New movement always goes out at once. Off: the old one-step jump.", "Hint"));
        _root.Children.Add(input);

        var (help, hb) = Parts.Card("Troubleshooting");
        _inputLog = Parts.Toggle("Record input log", app.ToggleInputLog,
            "Notes lost, late and stalled input in input.log in the log folder, so a stutter can be traced after you play. Costs almost nothing.");
        hb.Children.Add(_inputLog);
        hb.Children.Add(Parts.Divider());
        _log = Parts.Text("", "Sub");
        hb.Children.Add(_log);
        hb.Children.Add(Parts.Row(
            Parts.Button("Open log folder", app.OpenLogFolder, glyph: ""),
            Parts.Button("Debug window…", app.ShowDebugWindow, glyph: "")));
        _root.Children.Add(help);

        var (about, ab) = Parts.Card("About");
        _version = Parts.Text("");
        ab.Children.Add(_version);
        ab.Children.Add(Parts.Text("When a newer build is installed, the companion restarts into it by itself. Settings and pairings are kept.", "Hint"));
        _root.Children.Add(about);
    }

    public override void Refresh(CompanionView v)
    {
        Parts.SetChecked(_autoStart, v.AutoStart);
        Parts.SetChecked(_watcher, v.Watcher);
        Parts.SetChecked(_inputLog, v.InputLog);
        Parts.SetChecked(_pressListen, v.PressKeyListen);
        Parts.SetChecked(_pressNotice, v.PressKeyNotice);
        _pressNotice.IsEnabled = v.PressKeyListen;
        _trayClick.Set(v.TrayClick);
        _catchUp.Set(v.CatchUpMs);
        _log.Text = $"Log: {v.LogPath}";
        _version.Text = v.UpdatePending is { } p ? $"{v.Version} · restarting into {p}" : v.Version;
    }
}
