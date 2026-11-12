using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Shapes;
using GyroPhone.Protocol;

namespace GyroPhone.Ui;

/// <summary>
/// Home: what's wrong (a warning bar per problem, with its fix), then three cards (phone, the link's path,
/// gamepad with output and pause), and the full status text folded away under "Details". Link numbers,
/// latency and tests are on Performance, measured only while it is open.
/// </summary>
internal sealed class HomePage : Page
{
    private readonly ICompanion _app;
    private readonly StackPanel _root = new();
    private readonly StackPanel _problems = new();
    private string _problemsKey = "\0";
    private readonly Ellipse _phoneDot;
    private readonly TextBlock _phoneName, _phoneSub, _linkBig, _linkSub, _padSub, _pcSub;
    private readonly Button _pair;
    private readonly Seg<OutputMode> _output;
    private readonly CheckBox _pause;
    private readonly ToggleButton _detailsFold;
    private readonly Border _detailsCard;
    private readonly TextBox _details;

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

        // Gamepad
        var (pad, gb) = Parts.Card("Gamepad");
        _output = new Seg<OutputMode>(app.SetOutputMode, false,
            ("Xbox 360", OutputMode.Xbox360), ("DualShock 4", OutputMode.DualShock4), ("DualSense", OutputMode.DualSense),
            ("Off", OutputMode.None));
        gb.Children.Add(_output.View);
        _padSub = Parts.Text("", "Sub");
        _padSub.Margin = new Thickness(0, 0, 0, 6);
        gb.Children.Add(_padSub);
        _pause = Parts.Toggle("Pause input", app.TogglePaused, "Stops everything from the phone.");
        gb.Children.Add(_pause);
        _pcSub = Parts.Text("", "Sub");
        _pcSub.Margin = new Thickness(0, 6, 0, 0);
        gb.Children.Add(_pcSub);
        Place(grid, pad, 1, 0);

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

        // Link: only which way it goes; its numbers are on Performance.
        if (s.Connected)
        {
            _linkBig.Text = _app.LinkPath()?.Text ?? "Connected";
            _linkSub.Text = "Latency, lost and late packets, and link tests: Performance.";
        }
        else
        {
            _linkBig.Text = "—";
            _linkSub.Text = v.UsbDirect ? $"USB cable: {v.UsbDirectText}" : "Waiting for the phone on Wi‑Fi.";
        }

        // Gamepad
        _output.Set(s.OutputMode);
        _output.Show(OutputMode.DualSense, s.DualSenseOk || s.OutputMode == OutputMode.DualSense);
        Parts.SetChecked(_pause, s.Paused);
        _padSub.Text = s.OutputMode == OutputMode.None
            ? "No gamepad: the phone sends keyboard and mouse only."
            : s.OutputMode == OutputMode.DualSense
                ? !s.DualSenseOk ? "No gamepad: the GyroPhone pad driver isn't installed (driver\\install.cmd), or GyroPhone needs a restart after installing it."
                : s.PadPlugged ? "Virtual DualSense plugged in · GyroPhone pad driver ok." : "Virtual DualSense plugs in when the phone connects · GyroPhone pad driver ok."
            : !s.DriverOk
                ? $"No gamepad: {v.PadError ?? "the ViGEmBus driver is missing"}."
                : s.PadPlugged ? "Virtual pad plugged in · ViGEmBus ok." : "Virtual pad plugs in when the phone connects · ViGEmBus ok.";
        // "Use PC controller for gyro ratchet" on the phone.
        Parts.Show(_pcSub, s.PcController != null);
        _pcSub.Text = s.PcController is { Length: > 0 } name
            ? $"Gyro ratchet from the PC controller: {name}."
            : "Gyro ratchet from the PC controller: no controller found. Plug one in.";

        // Not while the user selects or copies it: setting the text clears the selection.
        if (_detailsFold.IsChecked == true && !_details.IsKeyboardFocusWithin && _details.SelectionLength == 0)
        {
            var t = _app.DetailsText();
            if (t != _details.Text) _details.Text = t;
        }
    }

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
