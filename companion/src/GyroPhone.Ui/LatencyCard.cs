using System.Windows;
using GyroPhone.Latency;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;

namespace GyroPhone.Ui;

/// <summary>
/// The shareable result picture: the latency big, what it was measured with, and a second of both
/// motions on top of each other. Drawn in fixed dark colours (a shared picture doesn't follow the
/// viewer's theme), 1000 × 520.
/// </summary>
internal static class LatencyCard
{
    public const int Width = 1000, Height = 520;
    private static readonly Brush Bg = Frozen(0x1B, 0x1F, 0x2A), Text = Frozen(0xE6, 0xE9, 0xF0), Dim = Frozen(0x8D, 0x96, 0xA8),
        Accent = Frozen(0x83, 0xCF, 0xFF), Line = Frozen(0x2F, 0x37, 0x48), PhoneInk = Frozen(0xFF, 0x7A, 0xD9), BarInk = Frozen(0x2C, 0xFF, 0x8A);

    private static SolidColorBrush Frozen(byte r, byte g, byte b)
    {
        var x = new SolidColorBrush(Color.FromRgb(r, g, b));
        x.Freeze();
        return x;
    }

    public static FrameworkElement Build(LatencyRun r)
    {
        var root = new Grid { Width = Width, Height = Height, Background = Bg };
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(360) });
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var left = new StackPanel { Margin = new Thickness(36, 30, 10, 20) };
        left.Children.Add(Label("GyroPhone latency test", 15, Accent, FontWeights.SemiBold, 0, 22));
        left.Children.Add(Label("PHONE MOVE → GAME SEES MOUSE", 12, Dim, FontWeights.Normal, 0, 6));
        var big = new TextBlock { Foreground = Text, FontSize = 72, FontWeight = FontWeights.Bold };
        big.Inlines.Add(new System.Windows.Documents.Run($"{r.LatencyMs:0.0}"));
        big.Inlines.Add(new System.Windows.Documents.Run(" ms") { FontSize = 28, Foreground = Dim, FontWeight = FontWeights.Normal });
        left.Children.Add(big);
        if (r.Tag is { } tag) left.Children.Add(Label($"Timed by {tag}", 12, Accent, FontWeights.SemiBold, 0, 4));
        var lines = new List<string>
        {
            r.Unreliable ? $"{r.Twists} twists · unreliable: too few swings timed" : $"{r.Twists} twists · margin ±{r.SpreadMs:0.0} ms",
        };
        if (r.FreezesText() is { Length: > 0 } fz) lines.Add(char.ToUpper(fz[0]) + fz[1..]);
        if (r.LeftOut() is { Length: > 0 } lo) lines.Add(char.ToUpper(lo[0]) + lo[1..]);
        for (int i = 0; i < lines.Count; i++)
            left.Children.Add(Label(lines[i], 14, i == 0 && r.Unreliable ? Accent : Dim, FontWeights.Normal, 0, i == lines.Count - 1 ? 22 : 4));
        var kv = new Grid();
        kv.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(110) });
        kv.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        (string, string)[] rows =
        [
            ("Link", r.Link), ("Phone", r.Phone), ("Monitor", r.MonitorHz > 0 ? $"{r.MonitorHz} Hz" : "—"),
            ("Camera", $"{r.Fps:0} fps video"), ("App", r.App), ("Date", r.When.ToString("yyyy-MM-dd HH:mm")),
        ];
        for (int i = 0; i < rows.Length; i++)
        {
            kv.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            var k = Label(rows[i].Item1, 14, Dim, FontWeights.Normal, 0, 6);
            var v = Label(rows[i].Item2, 14, Text, FontWeights.Normal, 0, 6);
            Grid.SetRow(k, i);
            Grid.SetRow(v, i);
            Grid.SetColumn(v, 1);
            kv.Children.Add(k);
            kv.Children.Add(v);
        }
        left.Children.Add(kv);
        root.Children.Add(left);

        var right = new StackPanel { Margin = new Thickness(10, 30, 36, 20) };
        right.Children.Add(Label("TURNING OVER ONE SECOND", 12, Dim, FontWeights.Normal, 0, 10));
        right.Children.Add(Chart(r, 560, 340));
        var legend = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 10, 0, 0) };
        legend.Children.Add(Key(PhoneInk, "Real phone (from the video)"));
        legend.Children.Add(Key(BarInk, "What the game got"));
        right.Children.Add(legend);
        Grid.SetColumn(right, 1);
        root.Children.Add(right);
        return root;
    }

    private static TextBlock Label(string text, double size, Brush brush, FontWeight weight, double left, double bottom) =>
        new() { Text = text, FontSize = size, Foreground = brush, FontWeight = weight, Margin = new Thickness(left, 0, 0, bottom), TextWrapping = TextWrapping.Wrap };

    private static StackPanel Key(Brush ink, string text)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 22, 0) };
        p.Children.Add(new Rectangle { Width = 16, Height = 3, Fill = ink, Margin = new Thickness(0, 0, 8, 0), VerticalAlignment = VerticalAlignment.Center });
        p.Children.Add(Label(text, 13, Dim, FontWeights.Normal, 0, 0));
        return p;
    }

    private static Canvas Chart(LatencyRun r, double w, double h)
    {
        var c = new Canvas { Width = w, Height = h, ClipToBounds = true };
        c.Children.Add(new Line { X1 = 0, Y1 = h / 2, X2 = w, Y2 = h / 2, Stroke = Line, StrokeThickness = 1 });
        double max = r.PhoneCurve.Concat(r.BarCurve).Select(Math.Abs).DefaultIfEmpty(1).Max();
        if (max <= 0) max = 1;
        c.Children.Add(Curve(r.PhoneCurve, PhoneInk, w, h, max));
        c.Children.Add(Curve(r.BarCurve, BarInk, w, h, max));
        return c;
    }

    private static Polyline Curve(double[] v, Brush ink, double w, double h, double max)
    {
        var p = new Polyline { Stroke = ink, StrokeThickness = 2.4, StrokeLineJoin = PenLineJoin.Round };
        for (int i = 0; i < v.Length; i++)
            p.Points.Add(new Point(i * w / Math.Max(1, v.Length - 1), h / 2 - v[i] / max * (h / 2 - 8)));
        return p;
    }

    /// <summary>The card as a picture, at its own size.</summary>
    public static BitmapSource Render(LatencyRun r)
    {
        var card = Build(r);
        card.Measure(new Size(Width, Height));
        card.Arrange(new Rect(0, 0, Width, Height));
        card.UpdateLayout();
        var bmp = new RenderTargetBitmap(Width, Height, 96, 96, PixelFormats.Pbgra32);
        bmp.Render(card);
        bmp.Freeze();
        return bmp;
    }
}
