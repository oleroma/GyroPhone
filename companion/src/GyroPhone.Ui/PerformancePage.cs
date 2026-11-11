using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using GyroPhone.Host.Perf;
using GyroPhone.Protocol;
using Frame = GyroPhone.Host.Perf.Frame;

namespace GyroPhone.Ui;

/// <summary>
/// Performance (docs/spec/pc-companion.md, "Performance"): the game in front with its frames per second and
/// every jump (a frame much longer than the ones before), next to the phone link's trouble; "Record test"
/// saves a few minutes of it under a name, and Results compares the records side by side.
/// </summary>
internal sealed class PerformancePage : Page
{
    private readonly ICompanion _app;
    private readonly StackPanel _root = new();
    private readonly Border _permission;
    private readonly TextBlock _permissionText;
    private readonly Button _allow, _admin;
    private readonly TextBlock _gameBig, _gameSub, _loadSub;
    private readonly FrameGraph _graph = new() { Margin = new Thickness(0, 10, 0, 4) };
    private readonly TextBox _name;
    private readonly Seg<string> _picks;
    private readonly Seg<int> _length;
    private int _seconds = 180;
    private readonly Button _record;
    private readonly TextBlock _recordSub;
    private readonly StackPanel _results = new();
    private string _resultsKey = "\0";
    private bool _nameEdited, _settingName, _granted, _wasRecording;

    public override FrameworkElement View => _root;

    public PerformancePage(ICompanion app)
    {
        _app = app;
        _root.Children.Add(Parts.Text("Performance", "H1"));
        var intro = Parts.Text("Watches the game in front: its frames per second and every jump (a frame at least twice as long as the ones before it), " +
            "next to the phone link's lost and late packets. Record a test for each way of connecting, then compare them below.", "Sub");
        intro.Margin = new Thickness(0, 0, 0, 14);
        _root.Children.Add(intro);

        // Permission banner
        var pg = new Grid();
        pg.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        pg.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _permissionText = Parts.Text("");
        _permissionText.SetResourceReference(TextBlock.ForegroundProperty, "WarnTextBrush");
        _permissionText.VerticalAlignment = VerticalAlignment.Center;
        _permissionText.FontSize = 13.5;
        pg.Children.Add(_permissionText);
        _allow = Parts.Button("Allow", Allow, "WarnButton");
        _admin = Parts.Button("Restart as administrator", app.RestartAsAdmin, "WarnButton");
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        _allow.Margin = _admin.Margin = new Thickness(12, 0, 0, 0);
        buttons.Children.Add(_allow);
        buttons.Children.Add(_admin);
        Grid.SetColumn(buttons, 1);
        pg.Children.Add(buttons);
        _permission = new Border { Style = Parts.Style("Banner"), Child = pg, MinHeight = 44, Margin = new Thickness(0, 0, 0, 14) };
        _root.Children.Add(_permission);

        // Game
        var (game, gb) = Parts.Card("Game");
        _gameBig = Parts.Text("", "Big");
        _gameSub = Parts.Text("", "Sub");
        gb.Children.Add(_gameBig);
        gb.Children.Add(_gameSub);
        gb.Children.Add(_graph);
        var legend = Parts.Text("Last 10 seconds of frame times (higher is slower). Red dots: jumps. Orange marks under the graph: " +
            "the phone link lost or held up packets, or let go of held keys, in that second.", "Sub");
        legend.FontSize = 11.5;
        gb.Children.Add(legend);
        _loadSub = Parts.Text("", "Sub");
        _loadSub.Margin = new Thickness(0, 8, 0, 0);
        gb.Children.Add(_loadSub);
        _root.Children.Add(game);

        // Record test
        var (rec, rb) = Parts.Card("Record test");
        _name = new TextBox { MaxLength = 40, Padding = new Thickness(4, 2, 4, 2), MinWidth = 260, HorizontalAlignment = HorizontalAlignment.Left,
            ToolTip = "What you're testing: pick a setup above or type your own, such as \"Overlays off\". Starts as the link the phone uses now." };
        _name.TextChanged += (_, _) =>
        {
            if (!_settingName) _nameEdited = true;
        };
        var picks = new Seg<string>(n =>
        {
            _name.Text = n;
            _nameEdited = true;
        }, true, ("Router", "Router"), ("Phone hotspot", "Phone hotspot"), ("PC hotspot", "PC hotspot"), ("Wired (USB)", "Wired (USB)"));
        _picks = picks;
        rb.Children.Add(Parts.Labelled("Setup", picks.View, 90));
        rb.Children.Add(Parts.Labelled("Name", _name, 90));
        _length = new Seg<int>(s =>
        {
            _seconds = s;
            _length!.Set(s);
        }, false, ("3 min", 180), ("5 min", 300), ("10 min", 600), ("Until I stop", 0));
        _length.Set(_seconds);
        rb.Children.Add(Parts.Labelled("Length", _length.View, 90));
        _record = Parts.Button("Record", Record, "Accent", "");
        _record.Margin = new Thickness(0, 10, 0, 0);
        _record.HorizontalAlignment = HorizontalAlignment.Left;
        rb.Children.Add(_record);
        _recordSub = Parts.Text("Start it, then play. Only the time the game is in front counts.", "Sub");
        _recordSub.Margin = new Thickness(0, 6, 0, 0);
        rb.Children.Add(_recordSub);
        _root.Children.Add(rec);

        // Results
        var (res, sb) = Parts.Card("Results");
        sb.Children.Add(_results);
        var open = Parts.Button("Open folder", app.OpenPerfFolder, glyph: "");
        open.Margin = new Thickness(0, 10, 0, 0);
        open.HorizontalAlignment = HorizontalAlignment.Left;
        sb.Children.Add(open);
        _root.Children.Add(res);

        app.Perf.Watch(true);
    }

    private void Allow()
    {
        _granted = _app.AllowFrameWatch();
        _app.Perf.Retry();
        _app.Log($"performance page: allow {(_granted ? "done" : "not done")}");
    }

    private void Record()
    {
        var p = _app.Perf;
        if (p.Recording != null) p.StopRecording();
        else
        {
            p.StartRecording(_name.Text, LinkName(_app.View()), _seconds);
            _nameEdited = false;
        }
    }

    public override void Refresh(CompanionView v)
    {
        var p = _app.Perf;
        if (p.State == FrameWatchState.Off) p.Watch(true);

        // Permission
        bool needs = p.State is FrameWatchState.NeedsPermission or FrameWatchState.Failed;
        Parts.Show(_permission, needs);
        if (needs)
        {
            _permissionText.Text = p.State == FrameWatchState.Failed
                ? $"Windows wouldn't let GyroPhone watch frames: {p.Error}."
                : _granted
                    ? "Allowed. It works from your next Windows sign-in (sign out and back in), or now if you restart GyroPhone as administrator."
                    : "Watching the game's frames needs a Windows permission. \"Allow\" asks once for admin rights, then it works without them from your next sign-in.";
            Parts.Show(_allow, p.State == FrameWatchState.NeedsPermission && !_granted);
            Parts.Show(_admin, !v.Elevated);
        }

        // Game
        var f = p.View();
        var game = p.GameName;
        if (p.State != FrameWatchState.On)
        {
            _gameBig.Text = "—";
            _gameSub.Text = "Not watching.";
        }
        else if (f.Pid == 0 || game == null)
        {
            _gameBig.Text = "No game";
            _gameSub.Text = "Start a game: anything that draws with Direct3D shows up here.";
        }
        else
        {
            _gameBig.Text = $"{game} · {f.Fps:0} fps";
            var parts = new List<string>();
            if (!f.Focused) parts.Add("in the background");
            parts.Add($"1% low {f.Low1Fps:0} fps");
            parts.Add($"{f.Jumps} {(f.Jumps == 1 ? "jump" : "jumps")} in the last minute");
            parts.Add($"worst frame {f.WorstMs:0} ms");
            _gameSub.Text = string.Join(" · ", parts);
        }
        long now = f.Recent.Length > 0 ? Math.Max(f.Recent[^1].AtUs, NowUs()) : NowUs();
        _graph.Set(f.Recent, p.Link.Between(now - FrameStats.GraphUs - 1_000_000, now + 1_000_000), now);
        _loadSub.Text = p.Load is { } l
            ? $"PC: CPU {l.CpuPct:0}% · driver time {l.DriverPct:0.0}% · busiest processor's driver time {l.BusiestCoreDriverPct:0}%"
            : "";

        // Record
        var r = p.Recording;
        if (!_nameEdited && r == null)
        {
            var link = LinkName(v);
            if (_name.Text != link)
            {
                _settingName = true;
                _name.Text = link;
                _settingName = false;
            }
        }
        _name.IsEnabled = _length.View.IsEnabled = _picks.View.IsEnabled = r == null;
        _picks.Set(_name.Text);
        _record.IsEnabled = p.State == FrameWatchState.On || r != null;
        SetRecordText(r == null ? "Record" : "Stop");
        if (r != null)
            _recordSub.Text = $"Recording '{r.Name}': {Clock(r.Elapsed)}{(r.Seconds > 0 ? $" of {Clock(r.Seconds)}" : "")}, game in front {Clock(r.GameSeconds)}.";
        else if (_wasRecording)
            _recordSub.Text = "Done. The new record is at the top of Results.";
        _wasRecording = r != null;

        // Results
        var runs = p.Runs;
        var key = string.Join("|", runs.Select(x => x.Start.Ticks));
        if (key != _resultsKey)
        {
            _resultsKey = key;
            BuildResults(runs);
        }
    }

    private void SetRecordText(string text)
    {
        if (_record.Content is StackPanel sp && sp.Children.Count > 1 && sp.Children[1] is TextBlock t)
        {
            t.Text = text;
            if (sp.Children[0] is TextBlock g) g.Text = text == "Stop" ? "" : "";
        }
    }

    private static long NowUs() => (long)(System.Diagnostics.Stopwatch.GetTimestamp() * (1_000_000.0 / System.Diagnostics.Stopwatch.Frequency));

    private static string Clock(double s) => $"{(int)s / 60}:{(int)s % 60:00}";

    /// <summary>The link the phone uses now, as Home names it.</summary>
    internal static string LinkName(CompanionView v)
    {
        var s = v.Status;
        if (s.PhoneLink is { } l)
        {
            if ((l.Flags & LinkInfoFlags.Usb) != 0)
                return (l.Flags & LinkInfoFlags.UsbDirect) != 0 ? "USB cable (direct)" : "USB cable (tethering)";
            return l.Band == 0 ? "Wi-Fi" : $"Wi-Fi {l.Band} GHz";
        }
        return s.Connected ? "Connected" : "No phone";
    }

    private void BuildResults(IReadOnlyList<PerfRun> runs)
    {
        _results.Children.Clear();
        if (runs.Count == 0)
        {
            _results.Children.Add(Parts.Text("No tests yet.", "Sub"));
            return;
        }
        string[] heads = ["Name", "When", "Game", "Avg fps", "1% low", "Jumps/min", "Worst frame", "Lost", "Late", "Let go", "CPU", "Driver"];
        string[] tips =
        [
            "", "", "Minutes the game was in front and counted.", "Average frames per second.", "The slowest 1% of frames, as frames per second.",
            "Frames at least twice as long as the ones before them, per minute.", "The longest frame.", "Phone packets lost.",
            "Phone packets held up on the way (more than 15 ms late).", "Times held keys and buttons were let go because the phone went silent.",
            "All processors' busy time.", "Time the processors spent in drivers (network, Wi-Fi, USB), and the busiest single processor's.",
        ];
        var g = new Grid();
        for (int c = 0; c < heads.Length; c++)
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        for (int c = 0; c < heads.Length; c++)
        {
            var h = Parts.Text(heads[c], "Sub");
            h.FontWeight = FontWeights.SemiBold;
            if (tips[c].Length > 0) h.ToolTip = tips[c];
            Cell(g, h, 0, c);
        }
        // The best value in each column is in the accent colour.
        double bestFps = runs.Max(r => r.AvgFps), bestLow = runs.Max(r => r.Low1Fps), bestJumps = runs.Min(r => r.JumpsPerMin);
        int bestLost = runs.Min(r => r.Lost), bestLate = runs.Min(r => r.Late);
        bool many = runs.Count > 1;
        for (int i = 0; i < runs.Count; i++)
        {
            var r = runs[i];
            g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            int row = i + 1;
            var name = Parts.Text(r.Name);
            name.ToolTip = $"{r.Link}" + (r.File != null ? $"\nClick to open the second-by-second file." : "");
            if (r.File is { } file)
            {
                name.Cursor = System.Windows.Input.Cursors.Hand;
                name.TextDecorations = TextDecorations.Underline;
                name.MouseLeftButtonUp += (_, _) => _app.OpenFile(file);
            }
            Cell(g, name, row, 0);
            Cell(g, Parts.Text(r.Start.ToString("d MMM HH:mm"), "Sub"), row, 1);
            Cell(g, Parts.Text($"{r.Game} {Clock(r.GameSeconds)}", "Sub"), row, 2);
            Cell(g, Num($"{r.AvgFps:0}", many && r.AvgFps >= bestFps), row, 3);
            Cell(g, Num($"{r.Low1Fps:0}", many && r.Low1Fps >= bestLow), row, 4);
            Cell(g, Num($"{r.JumpsPerMin:0.0}", many && r.JumpsPerMin <= bestJumps), row, 5);
            Cell(g, Num($"{r.WorstMs:0} ms", false), row, 6);
            Cell(g, Num($"{r.Lost}", many && r.Lost <= bestLost), row, 7);
            Cell(g, Num(r.Late > 0 ? $"{r.Late} (≤{r.MaxLateMs:0} ms)" : "0", many && r.Late <= bestLate), row, 8);
            Cell(g, Num($"{r.Releases}", false), row, 9);
            Cell(g, Num($"{r.CpuPct:0}%", false), row, 10);
            Cell(g, Num($"{r.DriverPct:0.0}% · {r.BusiestCoreDriverPct:0}%", false), row, 11);
        }
        _results.Children.Add(new ScrollViewer
        {
            Content = g,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
        });
    }

    private static TextBlock Num(string text, bool best)
    {
        var t = Parts.Text(text);
        t.TextWrapping = TextWrapping.NoWrap;
        if (best)
        {
            t.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
            t.FontWeight = FontWeights.SemiBold;
        }
        return t;
    }

    private static void Cell(Grid g, FrameworkElement e, int row, int col)
    {
        e.Margin = new Thickness(0, 3, 18, 3);
        if (e is TextBlock t) t.TextWrapping = TextWrapping.NoWrap;
        Grid.SetRow(e, row);
        Grid.SetColumn(e, col);
        g.Children.Add(e);
    }
}

/// <summary>The last 10 seconds of frame times as a line, jumps as red dots, link trouble as orange marks under it.</summary>
internal sealed class FrameGraph : FrameworkElement
{
    private Frame[] _frames = [];
    private List<LinkSecond> _link = [];
    private long _nowUs;

    public void Set(Frame[] frames, List<LinkSecond> link, long nowUs)
    {
        _frames = frames;
        _link = link;
        _nowUs = nowUs;
        InvalidateVisual();
    }

    protected override Size MeasureOverride(Size availableSize) => new(0, 110);

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight, plot = h - 10;
        if (w <= 0) return;
        var line = (Brush)FindResource("LineBrush");
        var faint = (Brush)FindResource("FaintBrush");
        dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, h));
        dc.DrawLine(new Pen(line, 1), new Point(0, plot - 0.5), new Point(w, plot - 0.5));
        long from = _nowUs - FrameStats.GraphUs;
        double X(long us) => (us - from) * w / FrameStats.GraphUs;

        // Scale: at least 33 ms, room for the longest frame up to 200 ms.
        double max = 33;
        foreach (var f in _frames) max = Math.Max(max, f.Ms);
        max = Math.Min(200, max * 1.15);
        double Y(double ms) => plot - 2 - (plot - 6) * Math.Clamp(ms / max, 0, 1);

        // Guides at 60 and 30 fps.
        var guide = new Pen(line, 1) { DashStyle = DashStyles.Dash };
        foreach (double ms in new[] { 16.7, 33.3 })
            if (ms < max)
            {
                dc.DrawLine(guide, new Point(0, Y(ms)), new Point(w, Y(ms)));
                var label = new FormattedText($"{1000 / ms:0} fps", System.Globalization.CultureInfo.InvariantCulture, FlowDirection.LeftToRight,
                    new Typeface("Segoe UI"), 10, faint, VisualTreeHelper.GetDpi(this).PixelsPerDip);
                dc.DrawText(label, new Point(2, Y(ms) - 13));
            }

        if (_frames.Length > 1)
        {
            var geo = new StreamGeometry();
            using (var g = geo.Open())
            {
                g.BeginFigure(new Point(X(_frames[0].AtUs), Y(_frames[0].Ms)), false, false);
                for (int i = 1; i < _frames.Length; i++) g.LineTo(new Point(X(_frames[i].AtUs), Y(_frames[i].Ms)), true, true);
            }
            geo.Freeze();
            dc.DrawGeometry(null, new Pen((Brush)FindResource("AccentBrush"), 1.4) { LineJoin = PenLineJoin.Round }, geo);
            var danger = (Brush)FindResource("DangerBrush");
            foreach (var f in _frames)
                if (f.Jump) dc.DrawEllipse(danger, null, new Point(X(f.AtUs), Y(f.Ms)), 3.5, 3.5);
        }

        var warn = (Brush)FindResource("WarnBrush");
        foreach (var s in _link)
            if (s.Trouble)
            {
                double x1 = Math.Max(0, X(s.AtUs - 1_000_000)), x2 = Math.Min(w, X(s.AtUs));
                if (x2 > x1) dc.DrawRectangle(warn, null, new Rect(x1, h - 6, Math.Max(2, x2 - x1 - 1), 5));
            }
    }
}
