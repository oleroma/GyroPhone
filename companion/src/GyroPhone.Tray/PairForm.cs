using System.Net.NetworkInformation;
using System.Net.Sockets;
using GyroPhone.Host;
using GyroPhone.Protocol;

namespace GyroPhone.Tray;

/// <summary>
/// "Pair new phone…": shows this PC's name, addresses and port with the pairing window countdown,
/// then "Pair <phone>? Code 400 588" with Allow and Deny once a phone has revealed its commitment.
/// The engine snapshot is the single source of truth; the form only reads it and answers through
/// HostEngine.AnswerPair. Closing the form denies an open prompt; the pairing window itself stays
/// open until it expires or a pairing succeeds, so an allowed attempt can still finish on the phone.
/// </summary>
internal sealed class PairForm : Form
{
    private readonly HostEngine _engine;
    private readonly Font _titleFont;
    private readonly Font _codeFont;
    private readonly Label _countdown;
    private readonly FlowLayoutPanel _waitPanel;
    private readonly FlowLayoutPanel _promptPanel;
    private readonly Label _question;
    private readonly Label _code;
    private readonly Label _result;
    private readonly Button _reopen;
    private readonly System.Windows.Forms.Timer _timer;
    private uint _promptAttempt;
    private DateTime? _allowedUtc;

    public PairForm(HostEngine engine, string hostName)
    {
        _engine = engine;
        Text = "Pair new phone - " + Program.AppName;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        TopMost = true;
        ShowInTaskbar = true;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        Padding = new Padding(16);

        _titleFont = new Font(Font.FontFamily, Font.Size * 1.4f, FontStyle.Bold);
        _codeFont = new Font(FontFamily.GenericMonospace, 30f, FontStyle.Bold);

        var root = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            Dock = DockStyle.Fill,
        };

        // Waiting for a phone: where to find this PC.
        _waitPanel = Column();
        _waitPanel.Controls.Add(NewLabel("Pair a new phone", _titleFont));
        _waitPanel.Controls.Add(NewLabel("On the phone open GyroPhone, choose Wi-Fi in the Connect tab and tap this PC "
            + "under \"Found on this network\". If it is not listed, use \"Enter PC address\"."));
        _waitPanel.Controls.Add(NewLabel($"PC name: {hostName}"));
        _waitPanel.Controls.Add(NewLabel($"Address: {LocalAddresses()}   UDP port {Wire.Port}"));
        _countdown = NewLabel("");
        _waitPanel.Controls.Add(_countdown);
        root.Controls.Add(_waitPanel);

        // A phone wants to pair: compare the code and decide.
        _promptPanel = Column();
        _question = NewLabel("", _titleFont);
        _code = NewLabel("", _codeFont);
        _code.Margin = new Padding(3, 12, 3, 12);
        _promptPanel.Controls.Add(_question);
        _promptPanel.Controls.Add(_code);
        _promptPanel.Controls.Add(NewLabel("Allow only if the phone shows exactly the same code, then tap Confirm on the phone."));
        var answer = Row();
        var allow = new Button { Text = "Allow", AutoSize = true, MinimumSize = new Size(96, 0) };
        var deny = new Button { Text = "Deny", AutoSize = true, MinimumSize = new Size(96, 0) };
        allow.Click += (_, _) => Answer(true);
        deny.Click += (_, _) => Answer(false);
        answer.Controls.Add(allow);
        answer.Controls.Add(deny);
        _promptPanel.Controls.Add(answer);
        _promptPanel.Visible = false;
        root.Controls.Add(_promptPanel);

        _result = NewLabel("");
        _result.Visible = false;
        root.Controls.Add(_result);

        var bottom = Row();
        _reopen = new Button { Text = "Open pairing again", AutoSize = true };
        _reopen.Click += (_, _) =>
        {
            _engine.OpenPairingWindow();
            RefreshNow();
        };
        var close = new Button { Text = "Close", AutoSize = true, MinimumSize = new Size(96, 0) };
        close.Click += (_, _) => Close();
        bottom.Controls.Add(_reopen);
        bottom.Controls.Add(close);
        root.Controls.Add(bottom);

        Controls.Add(root);
        CancelButton = close;

        _timer = new System.Windows.Forms.Timer { Interval = 500 };
        _timer.Tick += (_, _) => RefreshNow();
        Load += (_, _) =>
        {
            RefreshNow();
            _timer.Start();
        };
    }

    /// <summary>Re-reads the engine: prompt, countdown and result.</summary>
    public void RefreshNow()
    {
        if (IsDisposed) return;
        var s = _engine.Snapshot();
        if (s.PairPrompt is { } p)
        {
            bool fresh = p.AttemptId != _promptAttempt;
            _promptAttempt = p.AttemptId;
            _question.Text = $"Pair {p.PhoneName}?";
            _code.Text = p.Sas;
            _promptPanel.Visible = true;
            _waitPanel.Visible = false;
            _result.Visible = false;
            if (fresh)
            {
                Activate();
                _allowedUtc = null;
            }
        }
        else
        {
            _promptAttempt = 0;
            _promptPanel.Visible = false;
            _waitPanel.Visible = true;
            // A pairing stamps the phone's LastSeenUtc, so a phone seen after the Allow is the new one.
            if (_allowedUtc is { } since && s.PairedPhones.FirstOrDefault(x => x.LastSeenUtc >= since) is { Name: { } name })
            {
                _result.Text = $"Paired with {name}. You can close this window.";
                _allowedUtc = null;
            }
        }
        int left = s.PairingWindowS;
        _countdown.Text = left > 0
            ? $"Pairing is open for {left / 60}:{left % 60:00}."
            : "Pairing is closed.";
        _reopen.Visible = left == 0;
    }

    private void Answer(bool allow)
    {
        uint id = _promptAttempt;
        if (id == 0) return;
        _allowedUtc = allow ? DateTime.UtcNow.AddSeconds(-1) : null;
        _engine.AnswerPair(id, allow);
        _promptAttempt = 0;
        _result.Text = allow
            ? "Allowed. Finishing on the phone (tap Confirm there if you have not yet)."
            : "Denied. The phone was not paired.";
        _result.Visible = true;
        RefreshNow();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        _timer.Stop();
        if (_promptAttempt != 0) _engine.AnswerPair(_promptAttempt, false);
        _promptAttempt = 0;
        base.OnFormClosing(e);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _timer.Dispose();
            _titleFont.Dispose();
            _codeFont.Dispose();
        }
        base.Dispose(disposing);
    }

    private static FlowLayoutPanel Column() => new()
    {
        FlowDirection = FlowDirection.TopDown,
        WrapContents = false,
        AutoSize = true,
        AutoSizeMode = AutoSizeMode.GrowAndShrink,
        Margin = new Padding(0, 0, 0, 8),
    };

    private static FlowLayoutPanel Row() => new()
    {
        FlowDirection = FlowDirection.LeftToRight,
        WrapContents = false,
        AutoSize = true,
        AutoSizeMode = AutoSizeMode.GrowAndShrink,
        Margin = new Padding(0, 8, 0, 0),
    };

    private static Label NewLabel(string text, Font? font = null)
    {
        var l = new Label
        {
            Text = text,
            AutoSize = true,
            MaximumSize = new Size(440, 0),
            Margin = new Padding(3, 3, 3, 6),
        };
        if (font != null) l.Font = font;
        return l;
    }

    /// <summary>IPv4 addresses of the adapters that are up (no loopback or tunnels), for typing into the phone.</summary>
    private static string LocalAddresses()
    {
        var list = new List<string>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up
                    || ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                    continue;
                foreach (var a in ni.GetIPProperties().UnicastAddresses)
                    if (a.Address.AddressFamily == AddressFamily.InterNetwork) list.Add(a.Address.ToString());
            }
        }
        catch (NetworkInformationException)
        {
        }
        return list.Count == 0 ? "no network" : string.Join(", ", list);
    }
}
