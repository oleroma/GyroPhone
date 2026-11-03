using System.Collections;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using GyroPhone.Host;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Crypto;

namespace GyroPhone.Ui;

/// <summary>
/// "Pair new phone": a QR code for GyroPhone's "Scan QR code" (key pairing with a one-time token,
/// renewed when it expires, gone when the window closes), and for code pairing this PC's name,
/// addresses and port with the pairing window countdown, then "Pair &lt;phone&gt;? Code 400 588" with
/// Allow and Deny once a phone has revealed its commitment.
/// The engine snapshot is the single source of truth; the window only reads it and answers through
/// HostEngine.AnswerPair. Closing the window denies an open prompt; the pairing window itself stays
/// open until it expires or a pairing succeeds, so an allowed attempt can still finish on the phone.
/// </summary>
public sealed class PairWindow : Window
{
    private readonly HostEngine _engine;
    private readonly string _hostName;
    private readonly KeyFiles? _keyFiles;
    private readonly Image _qr;
    private readonly FrameworkElement _qrPanel;
    private bool _qrShown;
    private readonly TextBlock _countdown;
    private readonly FrameworkElement _waitPanel, _promptPanel;
    private readonly TextBlock _question, _code, _result;
    private readonly Border _resultBox;
    private readonly Button _reopen;
    private readonly DispatcherTimer _timer;
    private uint _promptAttempt;
    private DateTime? _allowedUtc;

    public PairWindow(HostEngine engine, string hostName, KeyFiles? keyFiles, string title, ImageSource? icon)
    {
        _engine = engine;
        _hostName = hostName;
        _keyFiles = keyFiles;
        Title = title;
        Icon = icon;
        SizeToContent = SizeToContent.WidthAndHeight;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Topmost = true;
        FontFamily = (FontFamily)FindResource("UiFont");
        FontSize = 14;
        UseLayoutRounding = true;
        SetResourceReference(BackgroundProperty, "BgBrush");
        SetResourceReference(ForegroundProperty, "TextBrush");

        var root = new StackPanel { Margin = new Thickness(28, 22, 28, 22), Width = 560 };

        // Waiting for a phone: the QR code, and where to find this PC for code pairing.
        var wait = new StackPanel();
        wait.Children.Add(Parts.Text("Pair a new phone", "H1"));
        var (qrCard, qb) = Parts.Card();
        var qrRow = new Grid();
        qrRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        qrRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _qr = new Image { Width = 196, Height = 196, Stretch = Stretch.Uniform };
        RenderOptions.SetBitmapScalingMode(_qr, BitmapScalingMode.NearestNeighbor);
        var qrFrame = new Border { Background = Brushes.White, CornerRadius = new CornerRadius(6), Padding = new Thickness(10), Child = _qr, Margin = new Thickness(0, 0, 20, 0) };
        qrRow.Children.Add(qrFrame);
        var steps = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        steps.Children.Add(Parts.Text("Scan with the phone", "H2"));
        ((FrameworkElement)steps.Children[0]).Margin = new Thickness(0, 0, 0, 10);
        steps.Children.Add(Step("1", "Open GyroPhone and go to the Connect tab."));
        steps.Children.Add(Step("2", "Choose Wi‑Fi and tap \"Scan QR code\"."));
        steps.Children.Add(Step("3", "Point the camera here. The phone connects by itself."));
        Grid.SetColumn(steps, 1);
        qrRow.Children.Add(steps);
        qb.Children.Add(qrRow);
        _qrPanel = qrCard;
        wait.Children.Add(qrCard);

        var (codeCard, cb) = Parts.Card("Or pair with a code");
        cb.Children.Add(Parts.Text("In the Connect tab tap this PC under \"Found on this network\". If it isn't listed, use \"Enter PC address\".", "Hint"));
        cb.Children.Add(Parts.Labelled("PC name", Parts.Text(hostName), 110));
        cb.Children.Add(Parts.Labelled("Address", Parts.Text(LocalAddresses()), 110));
        cb.Children.Add(Parts.Labelled("UDP port", Parts.Text(Wire.Port.ToString()), 110));
        _countdown = Parts.Text("", "Sub");
        cb.Children.Add(_countdown);
        wait.Children.Add(codeCard);
        _waitPanel = wait;
        root.Children.Add(wait);

        // A phone wants to pair: compare the code and decide.
        var prompt = new StackPanel { Visibility = Visibility.Collapsed };
        _question = Parts.Text("", "H1");
        prompt.Children.Add(_question);
        var (codeBox, pb) = Parts.Card();
        pb.Children.Add(Parts.Text("Code on this PC", "Caps"));
        _code = new TextBlock
        {
            FontFamily = (FontFamily)FindResource("MonoFont"),
            FontSize = 44,
            FontWeight = FontWeights.Bold,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 8, 0, 8),
        };
        _code.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
        pb.Children.Add(_code);
        pb.Children.Add(Parts.Text("Allow only if the phone shows exactly the same code, then tap Confirm on the phone.", "Hint"));
        var allow = Parts.Button("Allow", () => Answer(true), "Accent");
        allow.MinWidth = 110;
        var deny = Parts.Button("Deny", () => Answer(false));
        deny.MinWidth = 110;
        pb.Children.Add(Parts.Row(allow, deny));
        prompt.Children.Add(codeBox);
        _promptPanel = prompt;
        root.Children.Add(prompt);

        _result = Parts.Text("");
        _result.SetResourceReference(TextBlock.ForegroundProperty, "OkBrush");
        _resultBox = new Border { Child = _result, Margin = new Thickness(2, 0, 0, 12), Visibility = Visibility.Collapsed };
        root.Children.Add(_resultBox);

        var bottom = new DockPanel { LastChildFill = false };
        var close = Parts.Button("Close", Close);
        close.MinWidth = 96;
        close.IsCancel = true;
        DockPanel.SetDock(close, Dock.Right);
        bottom.Children.Add(close);
        _reopen = Parts.Button("Open pairing again", () =>
        {
            _engine.OpenPairingWindow();
            RefreshNow();
        }, "Accent");
        _reopen.Margin = new Thickness(0, 0, 8, 0);
        DockPanel.SetDock(_reopen, Dock.Right);
        bottom.Children.Add(_reopen);
        root.Children.Add(bottom);

        Content = root;

        _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        _timer.Tick += (_, _) => RefreshNow();
        SourceInitialized += (_, _) => Theme.TitleBar(this);
        Loaded += (_, _) =>
        {
            ShowNewQr();
            RefreshNow();
            _timer.Start();
        };
    }

    private static Grid Step(string n, string text)
    {
        var g = new Grid { Margin = new Thickness(0, 0, 0, 8) };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var badge = new Border { Width = 22, Height = 22, CornerRadius = new CornerRadius(11), Margin = new Thickness(0, 0, 10, 0), VerticalAlignment = VerticalAlignment.Top };
        badge.SetResourceReference(Border.BackgroundProperty, "CardHoverBrush");
        var num = Parts.Text(n);
        num.FontSize = 12;
        num.FontWeight = FontWeights.SemiBold;
        num.HorizontalAlignment = HorizontalAlignment.Center;
        num.VerticalAlignment = VerticalAlignment.Center;
        num.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
        badge.Child = num;
        g.Children.Add(badge);
        var t = Parts.Text(text);
        t.FontSize = 13.5;
        Grid.SetColumn(t, 1);
        g.Children.Add(t);
        return g;
    }

    /// <summary>Re-reads the engine: prompt, countdown and result.</summary>
    public void RefreshNow()
    {
        if (!IsLoaded) return;
        var s = _engine.Snapshot();
        if (s.QrEnrolledPhone is { } qrPhone)
        {
            ShowResult($"Paired with {qrPhone} by QR code. You can close this window.");
            ShowQr(false);
        }
        else if (_qrShown && s.QrPairingS == 0)
        {
            ShowNewQr(); // expired: a fresh token
        }
        if (s.PairPrompt is { } p)
        {
            bool fresh = p.AttemptId != _promptAttempt;
            _promptAttempt = p.AttemptId;
            _question.Text = $"Pair {p.PhoneName}?";
            _code.Text = p.Sas;
            Parts.Show(_promptPanel, true);
            Parts.Show(_waitPanel, false);
            Parts.Show(_resultBox, false);
            if (fresh)
            {
                Activate();
                _allowedUtc = null;
            }
        }
        else
        {
            _promptAttempt = 0;
            Parts.Show(_promptPanel, false);
            Parts.Show(_waitPanel, true);
            // A pairing stamps the phone's LastSeenUtc, so a phone seen after the Allow is the new one.
            if (_allowedUtc is { } since && s.PairedPhones.FirstOrDefault(x => x.LastSeenUtc >= since) is { Name: { } name })
            {
                ShowResult($"Paired with {name}. You can close this window.");
                _allowedUtc = null;
            }
        }
        int left = s.PairingWindowS;
        _countdown.Text = left > 0 ? $"Pairing is open for {left / 60}:{left % 60:00}." : "Pairing is closed.";
        Parts.Show(_reopen, left == 0);
    }

    private void ShowResult(string text)
    {
        _result.Text = text;
        Parts.Show(_resultBox, true);
    }

    private void Answer(bool allow)
    {
        uint id = _promptAttempt;
        if (id == 0) return;
        _allowedUtc = allow ? DateTime.UtcNow.AddSeconds(-1) : null;
        _engine.AnswerPair(id, allow);
        _promptAttempt = 0;
        RefreshNow();
        ShowResult(allow
            ? "Allowed. Finishing on the phone (tap Confirm there if you haven't yet)."
            : "Denied. The phone was not paired.");
    }

    /// <summary>A new one-time QR code (hidden when this PC has no key).</summary>
    private void ShowNewQr()
    {
        var token = _keyFiles == null ? null : _engine.OpenQrPairing(_keyFiles.AddAuthorized);
        if (token == null || _engine.HostPublicKey is not { } hostKey)
        {
            ShowQr(false);
            return;
        }
        var text = QrPairing.Format(new QrInvite(hostKey, token, _hostName, LocalAddressList()));
        _qr.Source = Render(QrCode.Modules(text));
        ShowQr(true);
    }

    private void ShowQr(bool on)
    {
        _qrShown = on;
        Parts.Show(_qrPanel, on);
    }

    /// <summary>One pixel per module, scaled up without smoothing by the Image.</summary>
    private static BitmapSource Render(IReadOnlyList<BitArray> modules)
    {
        int n = modules.Count;
        var pixels = new byte[n * n];
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                pixels[y * n + x] = modules[y][x] ? (byte)0 : (byte)255;
        var bmp = BitmapSource.Create(n, n, 96, 96, PixelFormats.Gray8, null, pixels, n);
        bmp.Freeze();
        return bmp;
    }

    protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
    {
        _timer.Stop();
        _engine.CloseQrPairing();
        if (_promptAttempt != 0) _engine.AnswerPair(_promptAttempt, false);
        _promptAttempt = 0;
        base.OnClosing(e);
    }

    /// <summary>IPv4 addresses of the adapters that are up (no loopback or tunnels), for typing into the phone.</summary>
    private static string LocalAddresses()
    {
        var list = LocalAddressList();
        return list.Count == 0 ? "no network" : string.Join(", ", list);
    }

    /// <summary>Same, as a list (at most four go into the QR code).</summary>
    private static List<string> LocalAddressList()
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
        return list.Take(4).ToList();
    }
}
