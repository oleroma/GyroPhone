using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using GyroPhone.Host.Perf;
using GyroPhone.Protocol;
using Frame = GyroPhone.Host.Perf.Frame;

namespace GyroPhone.Ui;

/// <summary>
/// Performance (docs/spec/pc-companion.md, "Performance"): everything measured, and only while this page is on
/// screen. The phone link live (the way its packets go, packets, latency with a graph, trouble marks, Measure
/// buttons), link tests ("Record test", compared in Results, link first), and the video Latency test. The
/// game's frames show only while an FPS overlay is on: it is the only thing that watches them.
/// </summary>
internal sealed class PerformancePage : Page
{
    private readonly ICompanion _app;
    private readonly StackPanel _root = new();
    private readonly LatencyPage _latency;
    private readonly Border _permission;
    private readonly TextBlock _permissionText;
    private readonly Button _setUp;
    private readonly TextBlock _linkBig, _linkSub, _latBig, _latSub, _gameSub;
    private readonly Sparkline _latGraph = new() { Margin = new Thickness(0, 8, 0, 0) };
    private readonly FrameGraph _graph = new() { Margin = new Thickness(0, 10, 0, 4) };
    private readonly CheckBox _measureButtons;
    private readonly CheckBox _inputLog, _overlayLog;
    private readonly TextBlock _logPath;
    private readonly TextBlock _buttonsSub;
    private readonly TextBox _name;
    private readonly Seg<string> _picks;
    private readonly Seg<int> _length;
    private int _seconds = 180;
    private readonly Button _record;
    private readonly TextBlock _pathLine, _busyLine, _recordSub;
    private readonly StackPanel _results = new();
    private string _resultsKey = "\0";
    private string? _setup;
    private bool _nameEdited, _settingName, _setUpFailed, _wasRecording, _wasConnected, _shown;
    private long _sampledAt;

    public override FrameworkElement View => _root;

    public PerformancePage(ICompanion app, Window owner, ImageSource? icon)
    {
        _app = app;
        _root.Children.Add(Parts.Text("Performance", "H1"));
        var intro = Parts.Text("The phone link live, link tests to compare ways of connecting, the video latency test, and the debug logs and tools. " +
            "Measured only while this page is open (and while a test records).", "Sub");
        intro.Margin = new Thickness(0, 0, 0, 14);
        _root.Children.Add(intro);

        // Permission banner (the FPS overlay's frame watch)
        var pg = new Grid();
        pg.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        pg.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _permissionText = Parts.Text("");
        _permissionText.SetResourceReference(TextBlock.ForegroundProperty, "WarnTextBrush");
        _permissionText.VerticalAlignment = VerticalAlignment.Center;
        _permissionText.FontSize = 13.5;
        pg.Children.Add(_permissionText);
        _setUp = Parts.Button("Set up FPS helper", SetUp, "WarnButton");
        _setUp.Margin = new Thickness(12, 0, 0, 0);
        _setUp.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(_setUp, 1);
        pg.Children.Add(_setUp);
        _permission = new Border { Style = Parts.Style("Banner"), Child = pg, MinHeight = 44, Margin = new Thickness(0, 0, 0, 14) };
        _root.Children.Add(_permission);

        // Link
        var (link, lb) = Parts.Card("Link");
        _linkBig = Parts.Text("", "Big");
        _linkSub = Parts.Text("", "Sub");
        lb.Children.Add(_linkBig);
        lb.Children.Add(_linkSub);
        var latRow = new WrapPanel { Margin = new Thickness(0, 12, 0, 0) };
        _latBig = Parts.Text("", "Big");
        _latBig.Margin = new Thickness(0, 0, 10, 0);
        _latSub = Parts.Text("", "Sub");
        _latSub.VerticalAlignment = VerticalAlignment.Bottom;
        _latSub.Margin = new Thickness(0, 0, 0, 3);
        latRow.Children.Add(_latBig);
        latRow.Children.Add(_latSub);
        lb.Children.Add(latRow);
        lb.Children.Add(_latGraph);
        var latLegend = Parts.Text("Latency while this page is open: middle (p50) bright, slowest 1% (p99) faint.", "Sub");
        latLegend.FontSize = 11.5;
        lb.Children.Add(latLegend);
        lb.Children.Add(_graph);
        var legend = Parts.Text("Last 10 seconds. Orange marks: the link lost or held up packets, the phone paused, or held keys were let go, in that second. " +
            "With the FPS overlay on, also the game's frame times (higher is slower), jumps as red dots.", "Sub");
        legend.FontSize = 11.5;
        lb.Children.Add(legend);
        _gameSub = Parts.Text("", "Sub");
        _gameSub.Margin = new Thickness(0, 8, 0, 0);
        lb.Children.Add(_gameSub);
        _measureButtons = Parts.Toggle("Measure buttons", app.ToggleMeasureButtons,
            "Times each press from the phone seeing your touch or controller button to the game getting it.");
        _measureButtons.Margin = new Thickness(0, 10, 0, 0);
        lb.Children.Add(_measureButtons);
        _buttonsSub = Parts.Text("", "Sub");
        lb.Children.Add(_buttonsSub);
        _root.Children.Add(link);

        // Record test
        var (rec, rb) = Parts.Card("Record test");
        _name = new TextBox { MaxLength = 40, Padding = new Thickness(4, 2, 4, 2), MinWidth = 260, HorizontalAlignment = HorizontalAlignment.Left,
            ToolTip = "What you're testing. Starts as the setup the phone is on now; type your own, such as \"Overlays off\"." };
        _name.TextChanged += (_, _) =>
        {
            if (!_settingName) _nameEdited = true;
        };
        _picks = new Seg<string>(n =>
        {
            _name.Text = n;
            _nameEdited = true;
        }, true, [.. LinkPaths.Setups.Select(x => (x, x))]);
        rb.Children.Add(Parts.Labelled("Setup", _picks.View, 90));
        rb.Children.Add(Parts.Labelled("Name", _name, 90));
        _length = new Seg<int>(s =>
        {
            _seconds = s;
            _length!.Set(s);
        }, false, ("3 min", 180), ("5 min", 300), ("10 min", 600), ("Until I stop", 0));
        _length.Set(_seconds);
        rb.Children.Add(Parts.Labelled("Length", _length.View, 90));
        _pathLine = Parts.Text("", "Sub");
        _pathLine.Margin = new Thickness(0, 6, 0, 0);
        rb.Children.Add(_pathLine);
        _busyLine = Parts.Text("", "Sub");
        _busyLine.SetResourceReference(TextBlock.ForegroundProperty, "WarnTextBrush");
        _busyLine.Margin = new Thickness(0, 6, 0, 0);
        rb.Children.Add(_busyLine);
        _record = Parts.Button("Record", Record, "Accent", "");
        _record.Margin = new Thickness(0, 10, 0, 0);
        _record.HorizontalAlignment = HorizontalAlignment.Left;
        rb.Children.Add(_record);
        _recordSub = Parts.Text("Record closes this window so it costs nothing while you play; the phone buzzes when the test ends. " +
            "Opening the window again ends it early. The record keeps the link's every second and the way its packets went; " +
            "the game's frames too while the FPS overlay is on.", "Sub");
        _recordSub.Margin = new Thickness(0, 6, 0, 0);
        rb.Children.Add(_recordSub);
        _root.Children.Add(rec);

        // Results
        var (res, sb) = Parts.Card("Results");
        sb.Children.Add(_results);
        var openRow = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
        openRow.Children.Add(Parts.Button("Open results", app.OpenPerfResults));
        var open = Parts.Button("Open folder", app.OpenPerfFolder, glyph: "");
        open.Margin = new Thickness(8, 0, 0, 0);
        openRow.Children.Add(open);
        sb.Children.Add(openRow);
        _root.Children.Add(res);

        // Latency test (video)
        _latency = new LatencyPage(app, owner, icon);
        _root.Children.Add(_latency.View);

        // Debug: every log and debug tool, each switchable
        var (debug, db) = Parts.Card("Debug");
        _inputLog = Parts.Toggle("Record input log", app.ToggleInputLog,
            "Notes lost, late and stalled input in input.log in the log folder, so a stutter can be traced after you play. Costs almost nothing.");
        db.Children.Add(_inputLog);
        db.Children.Add(Parts.Divider());
        _overlayLog = Parts.Toggle("Overlay timing log", app.ToggleOverlayTimingLog,
            "Writes each open overlay's draw times to companion.log once a second while they draw, so a slow overlay can be found. Costs almost nothing.");
        db.Children.Add(_overlayLog);
        db.Children.Add(Parts.Divider());
        _logPath = Parts.Text("", "Sub");
        db.Children.Add(_logPath);
        db.Children.Add(Parts.Row(
            Parts.Button("Open log folder", app.OpenLogFolder, glyph: ""),
            Parts.Button("Debug window…", app.ShowDebugWindow, glyph: "")));
        _root.Children.Add(debug);
    }

    public override void Shown(bool on)
    {
        _shown = on;
        _app.Perf.Check(on);
        _latency.Shown(on);
    }

    private void SetUp()
    {
        bool done = _app.SetUpFpsHelper();
        _setUpFailed = !done;
        if (done) _app.Perf.Retry();
        _app.Log($"performance page: FPS helper set up {(done ? "done" : "not done")}");
    }

    private void Record()
    {
        var p = _app.Perf;
        if (p.Recording != null) p.StopRecording();
        else
        {
            p.StartRecording(_name.Text, _app.LinkPath()?.Text ?? LinkName(_app.View()), _seconds);
            _nameEdited = false;
            // A fair test runs without this window drawing: it closes, and the record goes on in the tray.
            if (p.Recording != null) Window.GetWindow(_root)?.Close();
        }
    }

    public override void Refresh(CompanionView v)
    {
        Parts.SetChecked(_inputLog, v.InputLog);
        Parts.SetChecked(_overlayLog, v.OverlayTimingLog);
        _logPath.Text = $"Log: {v.LogPath}";
        var p = _app.Perf;
        var s = v.Status;
        var path = s.Connected ? _app.LinkPath() : null;

        // Permission: only matters while an FPS overlay asks for frames.
        bool needs = p.State is FrameWatchState.NeedsPermission or FrameWatchState.Failed;
        Parts.Show(_permission, needs);
        if (needs)
        {
            _permissionText.Text = p.State == FrameWatchState.Failed
                ? $"The FPS overlay can't watch frames: {p.Error}."
                : _setUpFailed
                    ? "The FPS helper wasn't set up (the admin prompt was declined, or a step failed: see the log). Try again?"
                    : p.Error != null
                        ? $"The FPS overlay needs its helper updated: {p.Error}. \"Set up FPS helper\" asks once for admin rights."
                        : "The FPS overlay needs a small helper that watches the game's frames. \"Set up FPS helper\" asks once for admin rights; " +
                          "after that Windows starts it at sign-in and GyroPhone never asks again.";
            Parts.Show(_setUp, p.State == FrameWatchState.NeedsPermission);
        }

        // Link
        if (s.PhoneLink is { } l && s.Connected)
        {
            _linkBig.Text = path?.Text ?? LinkName(v);
            bool wire = l.Path is LinkPath.UsbTether or LinkPath.UsbDirect or LinkPath.Ethernet or LinkPath.Hotspot ||
                (l.Flags & (LinkInfoFlags.Usb | LinkInfoFlags.Ethernet)) != 0;
            var parts = new List<string> { $"{s.RxPerSec:N0}/s received", $"loss {s.LossPermille / 10.0:0.0}%" };
            if (!wire) parts.Add($"{l.RssiDbm} dBm · {l.LinkMbps:N0} Mbps");
            parts.Add($"phone sends {l.SendRateHz:N0} Hz");
            parts.Add($"round trip {l.RttMinUs / 1000.0:0.0} ms");
            if (!wire) parts.Add((l.Flags & LinkInfoFlags.LowLatencyLockHeld) != 0 ? "low-latency Wi‑Fi on" : "low-latency Wi‑Fi off");
            _linkSub.Text = string.Join(" · ", parts);
        }
        else if (s.Connected)
        {
            _linkBig.Text = "Connected";
            _linkSub.Text = $"{s.RxPerSec:N0}/s received · loss {s.LossPermille / 10.0:0.0}%";
        }
        else
        {
            _linkBig.Text = "No phone";
            _linkSub.Text = v.UsbDirect ? $"USB cable: {v.UsbDirectText}" : "Waiting for the phone.";
        }
        bool known = s.Connected && s.LatP50Us != HostStatus.Unknown;
        if (known)
        {
            _latBig.Text = $"{s.LatP50Us / 1000.0:0.0} ms";
            _latSub.Text = $"latency p50 · p99 {Ms(s.LatP99Us)} · jitter {s.JitterUs / 1000.0:0.00} ms";
            long t = Environment.TickCount64;
            if (_shown && t - _sampledAt >= 450)
            {
                _sampledAt = t;
                _latGraph.Add(s.LatP50Us / 1000.0, s.LatP99Us == HostStatus.Unknown ? s.LatP50Us / 1000.0 : s.LatP99Us / 1000.0);
            }
        }
        else
        {
            _latBig.Text = "—";
            _latSub.Text = s.Connected ? "latency: measuring…" : "latency: no phone";
        }
        if (s.Connected && !_wasConnected) _latGraph.Clear();
        _wasConnected = s.Connected;
        Parts.SetChecked(_measureButtons, v.MeasureButtons);
        Parts.Show(_buttonsSub, v.MeasureButtons);
        if (v.MeasureButtons)
            _buttonsSub.Text = !s.Connected ? "Buttons: no phone"
                : v.Buttons is { } b ? $"Buttons: {b.TotalUs / 1000.0:0.0} ms, phone part {b.PhoneUs / 1000.0:0.0} ms (middle of {b.Count} · last {b.LastUs / 1000.0:0.0} ms)"
                : "Buttons: press something on the phone…";

        // The game: only through the FPS overlay.
        var f = p.View();
        if (!p.Watching) _gameSub.Text = "Game frames: off. Add the FPS overlay (Overlays → Add overlay → FPS) to watch them; it's the only thing that does.";
        else if (f.Pid == 0 || p.GameName == null) _gameSub.Text = "Game frames (FPS overlay on): no game showing frames.";
        else _gameSub.Text = $"Game frames (FPS overlay on): {p.GameName} · {f.Fps:0} fps · 1% low {f.Low1Fps:0} · {f.Jumps} jumps in the last minute · worst {f.WorstMs:0} ms" +
            (f.Focused ? "" : " · in the background");
        long now = f.Recent.Length > 0 ? Math.Max(f.Recent[^1].AtUs, NowUs()) : NowUs();
        _graph.Set(p.Watching ? f.Recent : Array.Empty<Frame>(), p.Link.Between(now - FrameStats.GraphUs - 1_000_000, now + 1_000_000), now);

        // Record
        var r = p.Recording;
        _setup = path?.Setup;
        if (!_nameEdited && r == null)
        {
            var want = _setup ?? (path?.Text ?? LinkName(v));
            if (_name.Text != want)
            {
                _settingName = true;
                _name.Text = want;
                _settingName = false;
            }
        }
        _name.IsEnabled = _length.View.IsEnabled = _picks.View.IsEnabled = r == null;
        _picks.Set(_name.Text);
        bool picked = LinkPaths.Setups.Contains(_name.Text);
        _pathLine.Text = path == null ? "The phone isn't connected: a record now has no link to measure."
            : picked && _setup != null && _setup != _name.Text ? $"⚠ The phone's packets go by {path.Value.Text}, not {_name.Text}. The record keeps the real path either way."
            : $"Detected: {path.Value.Text}.";
        _pathLine.SetResourceReference(TextBlock.ForegroundProperty, picked && _setup != null && _setup != _name.Text ? "WarnTextBrush" : "DimBrush");
        // Other programs busy (a build, a scan, an update) spoil a test: say so before recording.
        var busy = r == null ? p.Busy : null;
        Parts.Show(_busyLine, busy != null);
        if (busy != null)
            _busyLine.Text = $"⚠ The PC is busy: {busy}. A build, scan or update running now can cause hitches the test would blame on the link; wait for it to end. Records mark busy seconds either way.";
        SetRecordText(r == null ? "Record" : "Stop");
        if (r != null)
            _recordSub.Text = $"Recording '{r.Name}': {Clock(r.Elapsed)}{(r.Seconds > 0 ? $" of {Clock(r.Seconds)}" : "")}.";
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

        _latency.Refresh(v);
    }

    private static string Ms(uint us) => us == HostStatus.Unknown ? "–" : $"{us / 1000.0:0.0} ms";

    private void SetRecordText(string text)
    {
        if (_record.Content is StackPanel sp && sp.Children.Count > 1 && sp.Children[1] is TextBlock t)
        {
            t.Text = text;
            if (sp.Children[0] is TextBlock g) g.Text = text == "Stop" ? "" : "";
        }
    }

    private static long NowUs() => (long)(System.Diagnostics.Stopwatch.GetTimestamp() * (1_000_000.0 / System.Diagnostics.Stopwatch.Frequency));

    private static string Clock(double s) => $"{(int)s / 60}:{(int)s % 60:00}";

    /// <summary>The link the phone uses now, from LINK_INFO alone (without the PC's side).</summary>
    internal static string LinkName(CompanionView v)
    {
        var s = v.Status;
        if (s.PhoneLink is { } l) return LinkPaths.Phone(l, null);
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
        string[] heads = ["Name", "When", "Path", "Lost", "Late", "Pauses", "Let go", "Bursts", "Latency", "Jitter", "Avg fps", "1% low", "Jumps/min", "Worst frame", "CPU", "Driver"];
        string[] tips =
        [
            "Click a name to open the results page in your browser: every test, each with its bursts of link trouble and second-by-second table.", "",
            "The way the phone's packets went, detected (→ when it changed during the test).",
            "Phone packets lost.", "Phone packets held up on the way (more than 15 ms late), and the worst delay.",
            "Times the phone sent nothing for a while during input.", "Times held keys and buttons were let go because the phone went silent.",
            "Stretches of link trouble (seconds in a row with lost or late packets, pauses or let-go keys).",
            "Phone-to-PC latency: typical (middle) · slowest 1%.", "How much the latency varied.",
            "Average frames per second (only with the FPS overlay on).", "The slowest 1% of frames, as frames per second.",
            "Frames at least twice as long as the ones before them, per minute.", "The longest frame.",
            "All processors' busy time. ⚠ busy: seconds other programs (a build, a scan, an update) used 20% of the PC or more.", "Time the processors spent in drivers (network, Wi-Fi, USB), and the busiest single processor's.",
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
        var withFrames = runs.Where(r => r.Frames && r.GameSeconds > 0).ToList();
        double bestFps = withFrames.Count > 0 ? withFrames.Max(r => r.AvgFps) : 0, bestLow = withFrames.Count > 0 ? withFrames.Max(r => r.Low1Fps) : 0;
        double bestJumps = withFrames.Count > 0 ? withFrames.Min(r => r.JumpsPerMin) : 0;
        int bestLost = runs.Min(r => r.Lost), bestLate = runs.Min(r => r.Late), bestGaps = runs.Min(r => r.Gaps), bestBursts = runs.Min(r => r.Bursts);
        var lat = runs.Where(r => r.LatP50Ms >= 0).ToList();
        double bestLat = lat.Count > 0 ? lat.Min(r => r.LatP99Ms >= 0 ? r.LatP99Ms : r.LatP50Ms) : 0;
        bool many = runs.Count > 1;
        for (int i = 0; i < runs.Count; i++)
        {
            var r = runs[i];
            g.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            int row = i + 1;
            var name = Parts.Text(r.Name);
            name.ToolTip = (r.Path.Length > 0 ? r.Path : r.Link) + "\nClick to open the results page.";
            name.Cursor = System.Windows.Input.Cursors.Hand;
            name.TextDecorations = TextDecorations.Underline;
            name.MouseLeftButtonUp += (_, _) => _app.OpenPerfResults();
            bool fr = r.Frames && r.GameSeconds > 0;
            Cell(g, name, row, 0);
            Cell(g, Parts.Text(r.Start.ToString("d MMM HH:mm"), "Sub"), row, 1);
            Cell(g, Parts.Text(r.Path.Length > 0 ? r.Path : r.Link + " (typed)", "Sub"), row, 2);
            Cell(g, Num($"{r.Lost}", many && r.Lost <= bestLost), row, 3);
            Cell(g, Num(r.Late > 0 ? $"{r.Late} (≤{r.MaxLateMs:0} ms)" : "0", many && r.Late <= bestLate), row, 4);
            Cell(g, Num($"{r.Gaps}", many && r.Gaps <= bestGaps), row, 5);
            Cell(g, Num($"{r.Releases}", false), row, 6);
            Cell(g, Num($"{r.Bursts}", many && r.Bursts <= bestBursts), row, 7);
            Cell(g, Num(r.LatP50Ms >= 0 ? $"{r.LatP50Ms:0.0} · {r.LatP99Ms:0.0} ms" : "–",
                many && r.LatP50Ms >= 0 && (r.LatP99Ms >= 0 ? r.LatP99Ms : r.LatP50Ms) <= bestLat), row, 8);
            Cell(g, Num(r.LatP50Ms >= 0 ? $"{r.JitterMs:0.0} ms" : "–", false), row, 9);
            Cell(g, Num(fr ? $"{r.AvgFps:0}" : "–", many && fr && r.AvgFps >= bestFps), row, 10);
            Cell(g, Num(fr ? $"{r.Low1Fps:0}" : "–", many && fr && r.Low1Fps >= bestLow), row, 11);
            Cell(g, Num(fr ? $"{r.JumpsPerMin:0.0}" : "–", many && fr && r.JumpsPerMin <= bestJumps), row, 12);
            Cell(g, Num(fr ? $"{r.WorstMs:0} ms" : "–", false), row, 13);
            var cpu = Num(r.BusySeconds > 0 ? $"{r.CpuPct:0}% ⚠ busy {r.BusySeconds} s" : $"{r.CpuPct:0}%", false);
            if (r.BusySeconds > 0)
            {
                // Other programs (a build, a scan) kept the PC busy: this test isn't a fair one.
                cpu.SetResourceReference(TextBlock.ForegroundProperty, "WarnTextBrush");
                cpu.ToolTip = $"Other programs kept the PC busy for {r.BusySeconds} s, mostly {r.BusyTop}. Their hitches may not be the link's.";
            }
            Cell(g, cpu, row, 14);
            Cell(g, Num($"{r.DriverPct:0.0}% · {r.BusiestCoreDriverPct:0}%", false), row, 15);
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
