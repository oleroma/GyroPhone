using GyroPhone.Host;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Tray;

/// <summary>What the main window shows besides the engine snapshot; TrayApp builds it on each refresh.</summary>
internal sealed record MainView(
    HostSnapshot Status,
    string Header,
    string HostKey,
    bool AutoStart,
    bool Watcher,
    bool ShowUipi,
    bool ShowNetwork,
    bool ShowFirewall,
    bool ShowUsb,
    bool ShowDriver,
    bool ShowRestartAdmin,
    bool UsbDirect,
    string UsbDirectText);

/// <summary>
/// The main window: every setting in the left column (the tray menu has only the quick ones), the status text
/// (refreshed at 2 Hz) in the right one. Check boxes and radio buttons don't check themselves: a click
/// asks TrayApp to change the setting and the next <see cref="RefreshNow"/> shows the result.
/// </summary>
internal sealed class MainForm : Form
{
    private const int ColumnWidth = 300;

    private readonly TrayApp _app;
    private readonly Func<string> _statusText;
    private readonly Label _header;
    private readonly RadioButton _outX360, _outDs4, _outOff;
    private readonly CheckBox _pause, _autoStart, _watcher, _usbDirect;
    private readonly Label _usbDirectText;
    private readonly ListBox _phones;
    private readonly Button _forget;
    private readonly Label _hostKey;
    private readonly Button _uipi, _network, _firewall, _usb, _installDriver, _retryDriver, _restartAdmin;
    private readonly TextBox _box;
    private readonly Font _mono;
    private readonly Font _headerFont;
    private readonly System.Windows.Forms.Timer _timer;
    private IReadOnlyList<PairedPhoneInfo> _phoneList = [];
    private string _phonesKey = "";

    public MainForm(TrayApp app, Func<string> statusText)
    {
        _app = app;
        _statusText = statusText;
        Text = Program.AppName;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(960, 620);
        MinimumSize = new Size(640, 400);
        ShowInTaskbar = true;
        Padding = new Padding(8);

        _headerFont = new Font(Font.FontFamily, Font.Size * 1.2f, FontStyle.Bold);
        _mono = new Font(FontFamily.GenericMonospace, 10f);

        var table = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 2 };
        table.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        table.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        _header = new Label { AutoSize = true, Font = _headerFont, Margin = new Padding(3, 3, 3, 8) };
        table.Controls.Add(_header, 0, 0);
        table.SetColumnSpan(_header, 2);

        // Left column: settings, scrolling when the window is short.
        var settings = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            Dock = DockStyle.Fill,
            Padding = new Padding(0, 0, SystemInformation.VerticalScrollBarWidth, 0),
        };

        var output = Group("Gamepad output", settings);
        _outX360 = Radio("Xbox 360", output, () => _app.SetOutputMode(OutputMode.Xbox360));
        _outDs4 = Radio("DualShock 4", output, () => _app.SetOutputMode(OutputMode.DualShock4));
        _outOff = Radio("Off (keyboard and mouse only)", output, () => _app.SetOutputMode(OutputMode.None));
        _pause = Check("Pause input", output, () => _app.TogglePaused());

        var usb = Group("USB cable", settings);
        _usbDirect = Check("Use the phone over the USB cable", usb, () => _app.ToggleUsbDirect());
        _usbDirectText = NewLabel("");
        usb.Controls.Add(_usbDirectText);

        var startup = Group("Startup", settings);
        _autoStart = Check("Start with Windows", startup, () => _app.ToggleAutoStart());
        _watcher = Check("Start when my phone appears", startup, () => _app.ToggleWatcher());

        var phones = Group("Phones", settings);
        phones.Controls.Add(NewButton("Pair new phone…", () => _app.PairNewPhone()));
        _phones = new ListBox
        {
            Width = ColumnWidth - 24,
            Height = 80,
            IntegralHeight = false,
            Margin = new Padding(3, 6, 3, 3),
        };
        phones.Controls.Add(_phones);
        var phoneButtons = Row();
        _forget = NewButton("Forget…", () =>
        {
            int i = _phones.SelectedIndex;
            if (i >= 0 && i < _phoneList.Count) _app.Forget(_phoneList[i]);
        });
        _forget.Enabled = false;
        _phones.SelectedIndexChanged += (_, _) => _forget.Enabled = _phones.SelectedIndex >= 0 && _phoneList.Count > 0;
        phoneButtons.Controls.Add(_forget);
        phoneButtons.Controls.Add(NewButton("Open keys folder", () => _app.OpenKeysFolder()));
        phones.Controls.Add(phoneButtons);
        _hostKey = NewLabel("");
        phones.Controls.Add(_hostKey);

        var tools = Group("Tools", settings);
        _uipi = NewButton("The game runs as administrator: restart as admin", () => _app.RestartAsAdmin(), tools);
        _network = NewButton("This network is Public: make it Private…", () => _app.OpenNetworkSettings(), tools);
        _firewall = NewButton("Allow through firewall…", () => _app.AllowThroughFirewall(), tools);
        _usb = NewButton("Allow the phone over a USB cable…", () => _app.AllowUsb(), tools);
        _installDriver = NewButton("Install gamepad driver (ViGEmBus)…", () => _app.OpenDriverDownload(), tools);
        _retryDriver = NewButton("Retry driver", () => _app.RetryDriver(), tools);
        _restartAdmin = NewButton("Restart as administrator", () => _app.RestartAsAdmin(), tools);
        NewButton("Open log folder", () => _app.OpenLogFolder(), tools);

        table.Controls.Add(settings, 0, 1);

        // Right column: the status text that used to be the "Status…" window.
        var stats = new GroupBox { Text = "Status", Dock = DockStyle.Fill, Padding = new Padding(8) };
        _box = new TextBox
        {
            Multiline = true,
            ReadOnly = true,
            WordWrap = true,
            ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill,
            BorderStyle = BorderStyle.None,
            BackColor = SystemColors.Window,
            Font = _mono,
            TabStop = false,
        };
        stats.Controls.Add(_box);
        table.Controls.Add(stats, 1, 1);

        Controls.Add(table);

        _timer = new System.Windows.Forms.Timer { Interval = 500 };
        _timer.Tick += (_, _) => UpdateText();
        Load += (_, _) =>
        {
            UpdateText();
            _timer.Start();
        };
    }

    /// <summary>Shows the settings as they are now. Called by TrayApp on every refresh.</summary>
    public void RefreshNow(MainView v)
    {
        if (IsDisposed) return;
        var s = v.Status;
        _header.Text = v.Header;
        _outX360.Checked = s.OutputMode == OutputMode.Xbox360;
        _outDs4.Checked = s.OutputMode == OutputMode.DualShock4;
        _outOff.Checked = s.OutputMode == OutputMode.None;
        _pause.Checked = s.Paused;
        _usbDirect.Checked = v.UsbDirect;
        _usbDirectText.Text = v.UsbDirectText;
        _autoStart.Checked = v.AutoStart;
        _watcher.Checked = v.Watcher;
        _hostKey.Text = v.HostKey;
        _uipi.Visible = v.ShowUipi;
        _network.Visible = v.ShowNetwork;
        _firewall.Visible = v.ShowFirewall;
        _usb.Visible = v.ShowUsb;
        _installDriver.Visible = v.ShowDriver;
        _retryDriver.Visible = v.ShowDriver;
        _restartAdmin.Visible = v.ShowRestartAdmin;
        RebuildPhones(s.PairedPhones);
    }

    private void RebuildPhones(IReadOnlyList<PairedPhoneInfo> phones)
    {
        var key = string.Join("|", phones.Select(p => Convert.ToHexString(p.PhoneId) + p.Name + p.KeyFile));
        if (key == _phonesKey) return;
        _phonesKey = key;
        _phoneList = phones;
        _phones.BeginUpdate();
        _phones.Items.Clear();
        if (phones.Count == 0) _phones.Items.Add("None yet");
        foreach (var p in phones) _phones.Items.Add($"{p.Name} · {(p.ByKey ? "key" : "code")}{(p.Fingerprint != null ? $" · {p.Fingerprint}" : "")}");
        _phones.EndUpdate();
        _phones.Enabled = phones.Count > 0;
        _forget.Enabled = false;
    }

    private void UpdateText()
    {
        var t = _statusText();
        if (t != _box.Text) _box.Text = t; // unchanged text keeps the selection and scroll position
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        _timer.Stop();
        base.OnFormClosed(e);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _timer.Dispose();
            _mono.Dispose();
            _headerFont.Dispose();
        }
        base.Dispose(disposing);
    }

    /// <summary>A titled box in the settings column; returns the panel its rows go into.</summary>
    private static FlowLayoutPanel Group(string title, Control parent)
    {
        var box = new GroupBox
        {
            Text = title,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            MinimumSize = new Size(ColumnWidth, 0),
            Padding = new Padding(8, 4, 8, 4),
            Margin = new Padding(0, 0, 0, 8),
        };
        var inner = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            Dock = DockStyle.Fill,
        };
        box.Controls.Add(inner);
        parent.Controls.Add(box);
        return inner;
    }

    private static FlowLayoutPanel Row() => new()
    {
        FlowDirection = FlowDirection.LeftToRight,
        WrapContents = false,
        AutoSize = true,
        AutoSizeMode = AutoSizeMode.GrowAndShrink,
        Margin = new Padding(0),
    };

    private static RadioButton Radio(string text, Control parent, Action click)
    {
        var r = new RadioButton { Text = text, AutoSize = true, AutoCheck = false };
        r.Click += (_, _) => click();
        parent.Controls.Add(r);
        return r;
    }

    private static CheckBox Check(string text, Control parent, Action click)
    {
        var c = new CheckBox { Text = text, AutoSize = true, AutoCheck = false };
        c.Click += (_, _) => click();
        parent.Controls.Add(c);
        return c;
    }

    private static Button NewButton(string text, Action click, Control? parent = null)
    {
        var b = new Button { Text = text, AutoSize = true };
        b.Click += (_, _) => click();
        parent?.Controls.Add(b);
        return b;
    }

    private static Label NewLabel(string text) => new()
    {
        Text = text,
        AutoSize = true,
        MaximumSize = new Size(ColumnWidth - 24, 0),
        Margin = new Padding(3, 3, 3, 6),
    };
}
