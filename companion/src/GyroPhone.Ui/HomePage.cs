using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Shapes;
using GyroPhone.Protocol;

namespace GyroPhone.Ui;

/// <summary>
/// Home: what's wrong (a warning bar per problem, with its fix), then the live stats in four cards
/// (phone, link, latency with a graph, gamepad with output and pause), and the full status text folded
/// away under "Details".
/// </summary>
internal sealed class HomePage : Page
{
    private readonly ICompanion _app;
    private readonly StackPanel _root = new();
    private readonly StackPanel _problems = new();
    private string _problemsKey = "\0";
    private readonly Ellipse _phoneDot;
    private readonly TextBlock _phoneName, _phoneSub, _linkBig, _linkSub, _latBig, _latSub, _padSub;
    private readonly Button _pair;
    private readonly Sparkline _graph = new() { Margin = new Thickness(0, 10, 0, 0) };
    private readonly Seg<OutputMode> _output;
    private readonly CheckBox _pause;
    private readonly ToggleButton _detailsFold;
    private readonly Border _detailsCard;
    private readonly TextBox _details;
    private bool _wasConnected;
    private long _sampledAt;

    public override FrameworkElement View => _root;

    public HomePage(ICompanion app, Action<MainPage> go)
    {
        _app = app;
        _root.Children.Add(Parts.Text("Home", "H1"));
        _root.Children.Add(_problems);

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(12) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.RowDefinitions.Add(new RowDefinition());
        grid.RowDefinitions.Add(new RowDefinition());

        // Phone
        var (phone, pb) = Parts.Card("Phone");
        _phoneDot = new Ellipse { Width = 9, Height = 9, Margin = new Thickness(0, 2, 9, 0), VerticalAlignment = VerticalAlignment.Center };
        _phoneName = Parts.Text("", "Big");
        _phoneName.TextWrapping = TextWrapping.NoWrap;
        var nameRow = new DockPanel();
        nameRow.Children.Add(_phoneDot);
        nameRow.Children.Add(_phoneName);
        pb.Children.Add(nameRow);
        _phoneSub = Parts.Text("", "Sub");
        pb.Children.Add(_phoneSub);
        _pair = Parts.Button("Pair new phone", app.PairNewPhone, "Accent", "");
        _pair.Margin = new Thickness(0, 10, 0, 0);
        pb.Children.Add(_pair);
        Place(grid, phone, 0, 0);

        // Link
        var (link, lb) = Parts.Card("Link");
        _linkBig = Parts.Text("", "Big");
        _linkSub = Parts.Text("", "Sub");
        lb.Children.Add(_linkBig);
        lb.Children.Add(_linkSub);
        Place(grid, link, 0, 2);

        // Latency
        var (lat, tb) = Parts.Card("Latency");
        var latRow = new WrapPanel();
        _latBig = Parts.Text("", "Big");
        _latBig.Margin = new Thickness(0, 0, 10, 0);
        _latSub = Parts.Text("", "Sub");
        _latSub.VerticalAlignment = VerticalAlignment.Bottom;
        _latSub.Margin = new Thickness(0, 0, 0, 3);
        latRow.Children.Add(_latBig);
        latRow.Children.Add(_latSub);
        tb.Children.Add(latRow);
        tb.Children.Add(_graph);
        var legend = Parts.Text("Last minute: middle (p50) bright, slowest 1% (p99) faint.", "Sub");
        legend.FontSize = 11.5;
        tb.Children.Add(legend);
        Place(grid, lat, 1, 0);

        // Gamepad
        var (pad, gb) = Parts.Card("Gamepad");
        _output = new Seg<OutputMode>(app.SetOutputMode, false,
            ("Xbox 360", OutputMode.Xbox360), ("DualShock 4", OutputMode.DualShock4), ("Off", OutputMode.None));
        gb.Children.Add(_output.View);
        _pause = Parts.Toggle("Pause input", app.TogglePaused);
        gb.Children.Add(_pause);
        _padSub = Parts.Text("", "Sub");
        _padSub.Margin = new Thickness(0, 0, 0, 0);
        gb.Children.Add(_padSub);
        Place(grid, pad, 1, 2);

        _root.Children.Add(grid);

        // Details
        _detailsFold = new ToggleButton { Content = "Details", Style = Parts.Style("Fold"), Margin = new Thickness(2, 4, 0, 10) };
        _detailsFold.Click += (_, _) =>
        {
            Parts.Show(_detailsCard!, _detailsFold.IsChecked == true);
            if (_detailsFold.IsChecked == true) _details!.Text = _app.DetailsText();
        };
        _root.Children.Add(_detailsFold);
        _details = new TextBox
        {
            IsReadOnly = true,
            TextWrapping = TextWrapping.Wrap,
            BorderThickness = new Thickness(0),
            Background = Brushes.Transparent,
            Padding = new Thickness(0),
            FontFamily = (FontFamily)Application.Current.FindResource("MonoFont"),
            FontSize = 12.5,
        };
        _details.SetResourceReference(Control.ForegroundProperty, "MonoBrush");
        var (details, db) = Parts.Card();
        db.Children.Add(_details);
        _detailsCard = details;
        _detailsCard.Visibility = Visibility.Collapsed;
        _root.Children.Add(_detailsCard);
    }

    private static void Place(Grid g, UIElement e, int row, int col)
    {
        Grid.SetRow(e, row);
        Grid.SetColumn(e, col);
        g.Children.Add(e);
    }

    public override void Refresh(CompanionView v)
    {
        var s = v.Status;
        ShowProblems(v.Problems);

        // Phone
        _phoneName.Text = s.Connected ? s.PhoneName ?? "Phone" : "No phone connected";
        _phoneDot.SetResourceReference(Shape.FillProperty, s.Connected ? (s.Silent ? "WarnBrush" : "OkBrush") : "FaintBrush");
        int paired = s.PairedPhones.Count;
        string pairedText = paired == 0 ? "No phones paired yet." : paired == 1 ? "1 phone paired." : $"{paired} phones paired.";
        _phoneSub.Text = s.Connected
            ? (s.Silent ? "Connected, but no signal right now." : "Connected.") + " " + pairedText
            : s.PairingWindowS > 0
                ? $"Pairing is open for {s.PairingWindowS / 60}:{s.PairingWindowS % 60:00}. {pairedText}"
                : $"Open GyroPhone on the phone to connect. {pairedText}";
        Parts.Show(_pair, !s.Connected);

        // Link
        if (s.PhoneLink is { } l && s.Connected)
        {
            bool usb = (l.Flags & LinkInfoFlags.Usb) != 0;
            _linkBig.Text = usb
                ? ((l.Flags & LinkInfoFlags.UsbDirect) != 0 ? "USB cable (direct)" : "USB cable (tethering)")
                : l.Band == 0 ? "Wi‑Fi" : $"Wi‑Fi {l.Band} GHz";
            var parts = new List<string> { $"{s.RxPerSec:N0}/s received", $"loss {s.LossPermille / 10.0:0.0}%" };
            if (!usb) parts.Add($"{l.RssiDbm} dBm · {l.LinkMbps:N0} Mbps");
            parts.Add($"phone sends {l.SendRateHz:N0} Hz");
            parts.Add($"round trip {l.RttMinUs / 1000.0:0.0} ms");
            if (!usb) parts.Add((l.Flags & LinkInfoFlags.LowLatencyLockHeld) != 0 ? "low-latency Wi‑Fi on" : "low-latency Wi‑Fi off");
            _linkSub.Text = string.Join(" · ", parts);
        }
        else if (s.Connected)
        {
            _linkBig.Text = "Connected";
            _linkSub.Text = $"{s.RxPerSec:N0}/s received · loss {s.LossPermille / 10.0:0.0}%";
        }
        else
        {
            _linkBig.Text = "—";
            _linkSub.Text = v.UsbDirect ? $"USB cable: {v.UsbDirectText}" : "Waiting for the phone on Wi‑Fi.";
        }

        // Latency
        bool known = s.Connected && s.LatP50Us != HostStatus.Unknown;
        if (known)
        {
            _latBig.Text = $"{s.LatP50Us / 1000.0:0.0} ms";
            _latSub.Text = $"p50 · p99 {Ms(s.LatP99Us)} · jitter {s.JitterUs / 1000.0:0.00} ms";
            long now = Environment.TickCount64;
            if (now - _sampledAt >= 450)
            {
                _sampledAt = now;
                _graph.Add(s.LatP50Us / 1000.0, s.LatP99Us == HostStatus.Unknown ? s.LatP50Us / 1000.0 : s.LatP99Us / 1000.0);
            }
        }
        else
        {
            _latBig.Text = "—";
            _latSub.Text = s.Connected ? "measuring…" : "no phone";
        }
        if (s.Connected && !_wasConnected) _graph.Clear();
        _wasConnected = s.Connected;

        // Gamepad
        _output.Set(s.OutputMode);
        Parts.SetChecked(_pause, s.Paused);
        _padSub.Text = s.OutputMode == OutputMode.None
            ? "Keyboard and mouse only."
            : !s.DriverOk
                ? $"No gamepad: {v.PadError ?? "the ViGEmBus driver is missing"}."
                : s.PadPlugged ? "Virtual pad plugged in · ViGEmBus ok." : "Virtual pad plugs in when the phone connects · ViGEmBus ok.";

        if (_detailsFold.IsChecked == true)
        {
            var t = _app.DetailsText();
            if (t != _details.Text) _details.Text = t; // unchanged text keeps the selection
        }
    }

    private static string Ms(uint us) => us == HostStatus.Unknown ? "–" : $"{us / 1000.0:0.0} ms";

    private void ShowProblems(IReadOnlyList<Problem> problems)
    {
        var key = string.Join("\n", problems.Select(p => p.Text + "\t" + p.FixLabel));
        if (key == _problemsKey) return;
        _problemsKey = key;
        _problems.Children.Clear();
        foreach (var p in problems)
        {
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var icon = Parts.Glyph("", "WarnBrush", 15);
            icon.Margin = new Thickness(0, 0, 10, 0);
            g.Children.Add(icon);
            var text = Parts.Text(p.Text);
            text.SetResourceReference(TextBlock.ForegroundProperty, "WarnTextBrush");
            text.VerticalAlignment = VerticalAlignment.Center;
            text.FontSize = 13.5;
            Grid.SetColumn(text, 1);
            g.Children.Add(text);
            if (p.Fix is { } fix && p.FixLabel is { } label)
            {
                var b = Parts.Button(label, fix, "WarnButton");
                b.Margin = new Thickness(12, 0, 0, 0);
                b.VerticalAlignment = VerticalAlignment.Center;
                Grid.SetColumn(b, 2);
                g.Children.Add(b);
            }
            _problems.Children.Add(new Border { Style = Parts.Style("Banner"), Child = g, MinHeight = 44 });
        }
        if (_problems.Children.Count > 0) ((FrameworkElement)_problems.Children[^1]).Margin = new Thickness(0, 0, 0, 14);
    }
}
