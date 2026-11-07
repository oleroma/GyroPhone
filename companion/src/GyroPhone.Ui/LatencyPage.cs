using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using GyroPhone.Latency;
using GyroPhone.Protocol;

namespace GyroPhone.Ui;

/// <summary>
/// Latency test: how long the phone's gyro takes to move the mouse a game gets, measured from a
/// slow-motion video of the phone and the test window side by side. Four short steps, Start test
/// (the test window, and the phone's markers), a place to drop the video, and the results so far,
/// each with Open, Copy image, Save image and Save data.
/// </summary>
internal sealed class LatencyPage : Page
{
    private readonly ICompanion _app;
    private readonly Window _owner;
    private readonly ImageSource? _icon;
    private readonly StackPanel _root = new();
    private readonly Button _start;
    private readonly TextBlock _testSub, _status;
    private readonly Border _drop;
    private readonly Button _choose;
    private readonly StackPanel _list = new();
    private LatencyTestWindow? _window;
    private CompanionView? _view;
    /// <summary>
    /// The analysis outlives the window (closing and opening it builds a new page): its line under
    /// the drop area, whether it runs, and the pages showing it.
    /// </summary>
    private static string s_status = "";
    private static bool s_busy;
    private static event Action? StatusChanged;
    private IReadOnlyList<(string File, LatencyRun Run)> _runs = [];

    public override FrameworkElement View => _root;

    public LatencyPage(ICompanion app, Window owner, ImageSource? icon)
    {
        _app = app;
        _owner = owner;
        _icon = icon;
        _root.Children.Add(Parts.Text("Latency test", "H1"));
        var intro = Parts.Text("Measures the time from moving the phone to the game seeing the mouse move, from a slow-motion video you film with another phone.", "Sub");
        intro.Margin = new Thickness(0, 0, 0, 14);
        _root.Children.Add(intro);

        var steps = new UniformGrid4();
        steps.Add("1", "Press Start test. The test window opens with a green bar, and the phone shows four markers.");
        steps.Add("2", "Hold the phone in front of the test window, screen toward you. Twist your wrist left and right for about 10 seconds.");
        steps.Add("3", "Film it with another phone in slow motion (240 fps or more), the markers and the green bar in the picture.");
        steps.Add("4", "Copy the original video file to this PC and drop it below. Shared copies are often slowed to 30 fps.");
        _root.Children.Add(steps.View);

        var row = new Grid { Margin = new Thickness(0, 12, 0, 12) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(300) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var (test, tb) = Parts.Card("Test");
        test.Margin = new Thickness(0, 0, 12, 0);
        _start = Parts.Button("Start test", ToggleTest, "Accent", "\uE768");
        _start.HorizontalAlignment = HorizontalAlignment.Left;
        tb.Children.Add(_start);
        _testSub = Parts.Text("", "Sub");
        _testSub.Margin = new Thickness(0, 10, 0, 0);
        tb.Children.Add(_testSub);
        row.Children.Add(test);

        var dropBody = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
        var dropText = Parts.Text("Drop the slow-motion video here", "Sub");
        dropText.TextAlignment = TextAlignment.Center;
        dropBody.Children.Add(dropText);
        _choose = Parts.Button("Choose video…", ChooseVideo, glyph: "\uE8E5");
        _choose.HorizontalAlignment = HorizontalAlignment.Center;
        _choose.Margin = new Thickness(0, 10, 0, 0);
        dropBody.Children.Add(_choose);
        _status = Parts.Text(s_status, "Sub");
        _status.TextAlignment = TextAlignment.Center;
        _status.Margin = new Thickness(0, 10, 0, 0);
        dropBody.Children.Add(_status);
        _drop = new Border
        {
            BorderThickness = new Thickness(1.5),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(16),
            AllowDrop = true,
            Background = Brushes.Transparent,
            Child = dropBody,
        };
        _drop.SetResourceReference(Border.BorderBrushProperty, "LineBrush");
        _drop.Drop += (_, e) =>
        {
            if (e.Data.GetData(DataFormats.FileDrop) is string[] { Length: > 0 } files) Analyze(files[0]);
        };
        Grid.SetColumn(_drop, 1);
        row.Children.Add(_drop);
        _root.Children.Add(row);

        var (results, rb) = Parts.Card("Results");
        rb.Children.Add(_list);
        _root.Children.Add(results);
        LoadRuns();
        _choose.IsEnabled = !s_busy;
        Action changed = () =>
        {
            _status.Text = s_status;
            _choose.IsEnabled = !s_busy;
        };
        Action reload = LoadRuns;
        StatusChanged += changed;
        ResultsChanged += reload;
        owner.Closed += (_, _) =>
        {
            StatusChanged -= changed;
            ResultsChanged -= reload;
        };
    }

    private static void SetStatus(string text)
    {
        s_status = text;
        StatusChanged?.Invoke();
    }

    public override void Refresh(CompanionView v)
    {
        _view = v;
        var s = v.Status;
        _start.Content = _window == null ? "Start test" : "Stop test";
        _testSub.Text = !s.Connected ? "No phone connected: the phone won't show its markers."
            : $"Phone: {s.PhoneName} · {LinkName(v)}" + (_window != null ? "\nThe phone shows its markers." : "");
    }

    private void ToggleTest()
    {
        if (_window != null)
        {
            _window.Close();
            return;
        }
        _window = new LatencyTestWindow(_icon);
        _window.Closed += (_, _) =>
        {
            _window = null;
            _app.SetLatencyScreen(false);
            if (_view != null) Refresh(_view);
        };
        _app.SetLatencyScreen(true);
        _window.Show();
        if (_view != null) Refresh(_view);
    }

    private void ChooseVideo()
    {
        var d = new Microsoft.Win32.OpenFileDialog
        {
            Title = "Choose the slow-motion video",
            Filter = "Videos|*.mov;*.mp4;*.m4v;*.avi;*.mkv|All files|*.*",
        };
        if (d.ShowDialog(_owner) == true) Analyze(d.FileName);
    }

    private async void Analyze(string path)
    {
        if (s_busy) return;
        s_busy = true;
        SetStatus("Reading the video…");
        var progress = new Progress<double>(p => SetStatus($"Reading the video… {p * 100:0}%"));
        string name = Path.GetFileName(path);
        _app.Log($"latency test: reading {path}");
        try
        {
            SetStatus("Copying the video…");
            string copy = await Task.Run(() => LatencyResults.KeepLastVideo(path));
            _app.Log($"latency test: copied to {copy}");
            SetStatus("Reading the video…");
            var a = await Task.Run(() => VideoTracker.Analyze(copy, progress));
            var v = _view;
            var run = LatencyRun.From(a, path,
                v != null ? LinkName(v) : "—",
                v?.Status.PhoneName ?? "—",
                LatencyTestWindow.LastMonitorHz,
                v?.Version ?? "GyroPhone");
            LatencyResults.Save(run);
            string timed = a.TimedToS - a.TimedFromS < a.LengthS - 1
                ? $" Timed {Clock(a.TimedFromS)}–{Clock(a.TimedToS)} of {Clock(a.LengthS)}, where you twist."
                : "";
            _app.Log($"latency test: {name} {a.Fps:0} fps, {a.Frames} frames, timed {a.TimedFromS:0.0}-{a.TimedToS:0.0} s, phone {a.Motion}: "
                + $"{run.LatencyMs:0.0} ms ±{run.SpreadMs:0.0}, {run.Twists} twists, match {run.Match:0.00}, {run.Stretches} stretches used, {run.Skipped} left out, {run.Freezes} freezes");
            string left = run.LeftOut();
            SetStatus($"{run.LatencyMs:0.0} ms (±{run.SpreadMs:0.0}) from {run.Twists} twists.{timed}"
                + (left.Length > 0 ? $" {char.ToUpper(left[0])}{left[1..]}." : ""));
            if (_root.IsLoaded) Open(run);
            ResultsChanged?.Invoke();
        }
        catch (VideoProblem e)
        {
            _app.Log($"latency test: {name}: {e.Message}");
            SetStatus(e.Message);
        }
        catch (Exception e)
        {
            _app.Log($"latency test: {name} couldn't be read: {e}");
            SetStatus($"This video couldn't be read: {e.Message}");
        }
        finally
        {
            s_busy = false;
            StatusChanged?.Invoke();
        }
    }

    private static event Action? ResultsChanged;

    private static string Clock(double s) => TimeSpan.FromSeconds(Math.Max(0, s)).ToString(@"m\:ss");

    private void LoadRuns()
    {
        _runs = LatencyResults.Load();
        _list.Children.Clear();
        if (_runs.Count == 0)
        {
            _list.Children.Add(Parts.Text("No results yet.", "Sub"));
            return;
        }
        for (int i = 0; i < _runs.Count; i++)
        {
            if (i > 0) _list.Children.Add(Parts.Divider());
            _list.Children.Add(RunRow(_runs[i].File, _runs[i].Run));
        }
    }

    private Grid RunRow(string file, LatencyRun r)
    {
        var g = new Grid { Margin = new Thickness(0, 4, 0, 4) };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var text = Parts.Column(
            Parts.Text($"{r.LatencyMs:0.0} ms  ·  ±{r.SpreadMs:0.0} ms"),
            Parts.Text($"{r.When:d MMM yyyy, HH:mm} · {r.Link} · {r.App}", "Sub"));
        g.Children.Add(text);
        var buttons = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
        foreach (var b in new[]
        {
            Parts.Button("Open", () => Open(r), "Quiet"),
            Parts.Button("Copy image", () => Clipboard.SetImage(LatencyCard.Render(r)), "Quiet"),
            Parts.Button("Save image…", () => SaveImage(r), "Quiet"),
            Parts.Button("Save data…", () => SaveData(file, r), "Quiet"),
        })
        {
            b.Margin = new Thickness(6, 0, 0, 0);
            buttons.Children.Add(b);
        }
        Grid.SetColumn(buttons, 1);
        g.Children.Add(buttons);
        return g;
    }

    private void Open(LatencyRun r)
    {
        var img = new Image { Source = LatencyCard.Render(r), Stretch = Stretch.Uniform };
        var w = new Window
        {
            Title = $"Latency {r.LatencyMs:0.0} ms",
            Icon = _icon,
            Owner = _owner,
            Width = LatencyCard.Width / 1.25 + 16,
            Height = LatencyCard.Height / 1.25 + 40,
            Content = img,
            Background = Brushes.Black,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
        };
        w.SourceInitialized += (_, _) => Theme.TitleBar(w);
        w.Show();
    }

    private void SaveImage(LatencyRun r)
    {
        var d = new Microsoft.Win32.SaveFileDialog
        {
            Title = "Save the result picture",
            Filter = "PNG picture|*.png",
            FileName = $"gyrophone-latency-{r.When:yyyyMMdd-HHmm}.png",
        };
        if (d.ShowDialog(_owner) == true) LatencyCard.SavePng(LatencyCard.Render(r), d.FileName);
    }

    private void SaveData(string file, LatencyRun r)
    {
        var d = new Microsoft.Win32.SaveFileDialog
        {
            Title = "Save the result data",
            Filter = "JSON data|*.json",
            FileName = $"gyrophone-latency-{r.When:yyyyMMdd-HHmm}.json",
        };
        if (d.ShowDialog(_owner) == true) File.Copy(file, d.FileName, overwrite: true);
    }

    /// <summary>The link as the Home page names it.</summary>
    private static string LinkName(CompanionView v)
    {
        if (v.Status.PhoneLink is not { } l || !v.Status.Connected) return "—";
        if ((l.Flags & LinkInfoFlags.Usb) != 0) return (l.Flags & LinkInfoFlags.UsbDirect) != 0 ? "USB cable (direct)" : "USB cable (tethering)";
        return l.Band == 0 ? "Wi‑Fi" : $"Wi‑Fi {l.Band} GHz";
    }

    /// <summary>Four numbered step cards in a row.</summary>
    private sealed class UniformGrid4
    {
        public readonly System.Windows.Controls.Primitives.UniformGrid View = new() { Columns = 4 };

        public void Add(string n, string text)
        {
            var (card, body) = Parts.Card();
            card.Margin = new Thickness(0, 0, View.Children.Count < 3 ? 10 : 0, 0);
            var num = Parts.Text(n, "Caps");
            num.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
            body.Children.Add(num);
            body.Children.Add(Parts.Text(text));
            View.Children.Add(card);
        }
    }
}
