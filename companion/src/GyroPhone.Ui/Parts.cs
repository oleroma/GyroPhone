using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;

namespace GyroPhone.Ui;

/// <summary>
/// The building blocks every page is made of: cards, switches, segmented choices, slider rows and
/// buttons, in the styles from Styles.xaml. Controls never change a setting by themselves: a click
/// asks the companion, and the page's refresh shows what was saved.
/// </summary>
internal static class Parts
{
    public static Style Style(string key) => (Style)Application.Current.FindResource(key);

    public static TextBlock Text(string text, string? style = null, double? size = null)
    {
        var t = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap };
        if (style != null) t.Style = Style(style);
        else t.SetResourceReference(TextBlock.ForegroundProperty, "TextBrush");
        if (size is { } s) t.FontSize = s;
        return t;
    }

    public static TextBlock Glyph(string glyph, string brush = "TextBrush", double size = 16)
    {
        var t = new TextBlock { Text = glyph, Style = Style("Icon"), FontSize = size };
        t.SetResourceReference(TextBlock.ForegroundProperty, brush);
        return t;
    }

    /// <summary>A card with an optional small-caps title; returns the card and the panel its rows go into.</summary>
    public static (Border Card, StackPanel Body) Card(string? title = null)
    {
        var body = new StackPanel();
        if (title != null) body.Children.Add(Text(title, "Caps"));
        var card = new Border { Style = Style("Card"), Child = body };
        return (card, body);
    }

    public static StackPanel Column(params UIElement[] children)
    {
        var p = new StackPanel();
        foreach (var c in children) p.Children.Add(c);
        return p;
    }

    public static WrapPanel Row(params UIElement[] children)
    {
        var p = new WrapPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 6, 0, 2) };
        foreach (var c in children)
        {
            if (c is FrameworkElement f && f.Margin == default) f.Margin = new Thickness(0, 0, 8, 6);
            p.Children.Add(c);
        }
        return p;
    }

    public static Button Button(string text, Action click, string? style = null, string? glyph = null)
    {
        var b = new Button();
        if (style != null) b.Style = Style(style);
        if (glyph != null)
        {
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            var g = new TextBlock { Text = glyph, Style = Style("Icon"), FontSize = 13, Margin = new Thickness(0, 0, 8, 0) };
            g.SetBinding(TextBlock.ForegroundProperty, new System.Windows.Data.Binding("Foreground") { Source = b });
            sp.Children.Add(g);
            sp.Children.Add(new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center });
            b.Content = sp;
        }
        else b.Content = text;
        b.Click += (_, _) => click();
        return b;
    }

    /// <summary>A switch row: <paramref name="text"/> on the left (with an optional hint under it), the switch on the right.</summary>
    public static CheckBox Toggle(string text, Action click, string? hint = null)
    {
        var label = new StackPanel();
        label.Children.Add(new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap });
        TextBlock? h = null;
        if (hint != null)
        {
            h = Text(hint, "Sub");
            h.Margin = new Thickness(0, 2, 0, 0);
            label.Children.Add(h);
        }
        var c = new CheckBox { Content = label };
        c.Click += (_, _) => click();
        return c;
    }

    /// <summary>The hint line under a switch made by <see cref="Toggle"/>, to change its text.</summary>
    public static TextBlock? HintOf(CheckBox c) =>
        c.Content is StackPanel p && p.Children.Count > 1 ? p.Children[1] as TextBlock : null;

    public static void SetChecked(ToggleButton b, bool on)
    {
        if (b.IsChecked != on) b.IsChecked = on;
    }

    public static void Show(UIElement e, bool visible) => e.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>A labelled row: name on the left, <paramref name="right"/> after it.</summary>
    public static Grid Labelled(string label, UIElement right, double labelWidth = 150)
    {
        var g = new Grid { Margin = new Thickness(0, 4, 0, 4) };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(labelWidth) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var t = Text(label);
        t.VerticalAlignment = VerticalAlignment.Center;
        g.Children.Add(t);
        Grid.SetColumn(right, 1);
        g.Children.Add(right);
        return g;
    }

    /// <summary>A thin line between rows of a card.</summary>
    public static Border Divider()
    {
        var b = new Border { Height = 1, Margin = new Thickness(0, 8, 0, 8) };
        b.SetResourceReference(Border.BackgroundProperty, "LineBrush");
        return b;
    }
}

/// <summary>Segmented choice (or chips): one of <typeparamref name="T"/>, picked by a click.</summary>
internal sealed class Seg<T> where T : notnull
{
    private readonly List<(T Value, RadioButton Button)> _buttons = [];

    public FrameworkElement View { get; }

    public Seg(Action<T> pick, bool chips, params (string Label, T Value)[] options)
    {
        string group = Guid.NewGuid().ToString("N");
        Panel panel = chips ? new WrapPanel() : new StackPanel { Orientation = Orientation.Horizontal };
        foreach (var (label, value) in options)
        {
            var b = new RadioButton { Content = label, GroupName = group, Style = Parts.Style(chips ? "Chip" : "Seg") };
            var v = value;
            b.Click += (_, _) => pick(v);
            panel.Children.Add(b);
            _buttons.Add((value, b));
        }
        View = chips ? panel : new Border { Style = Parts.Style("SegBox"), Child = panel };
    }

    public void Set(T value)
    {
        foreach (var (v, b) in _buttons) Parts.SetChecked(b, EqualityComparer<T>.Default.Equals(v, value));
    }

    public void Enable(T value, bool on)
    {
        foreach (var (v, b) in _buttons) if (EqualityComparer<T>.Default.Equals(v, value)) b.IsEnabled = on;
    }
}

/// <summary>A slider row: name, slider, the value as text. Only moves made by the user call back.</summary>
internal sealed class SliderRow
{
    private readonly Slider _slider;
    private readonly TextBlock _value;
    private readonly Func<double, string> _format;
    private bool _setting;

    public Grid View { get; }

    public SliderRow(string label, double min, double max, double step, Func<double, string> format, Action<double> changed)
    {
        _format = format;
        _slider = new Slider
        {
            Minimum = min,
            Maximum = max,
            SmallChange = step,
            LargeChange = step * 5,
            TickFrequency = step,
            IsSnapToTickEnabled = true,
            Value = min,
        };
        _value = Parts.Text(format(min), "Sub");
        _value.Margin = new Thickness(12, 0, 0, 0);
        _value.VerticalAlignment = VerticalAlignment.Center;
        _slider.ValueChanged += (_, e) =>
        {
            _value.Text = _format(e.NewValue);
            if (!_setting) changed(Math.Round(e.NewValue / step) * step);
        };
        var g = new Grid { Margin = new Thickness(0, 4, 0, 4) };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(150) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star), MaxWidth = 260 });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(70) });
        var name = Parts.Text(label);
        name.VerticalAlignment = VerticalAlignment.Center;
        g.Children.Add(name);
        Grid.SetColumn(_slider, 1);
        g.Children.Add(_slider);
        Grid.SetColumn(_value, 2);
        g.Children.Add(_value);
        View = g;
    }

    /// <summary>Shows the saved value, unless the user is dragging the slider right now.</summary>
    public void Set(double v)
    {
        if (_slider.IsMouseCaptureWithin) return;
        v = Math.Clamp(v, _slider.Minimum, _slider.Maximum);
        if (Math.Abs(_slider.Value - v) < 1e-9) return;
        _setting = true;
        try
        {
            _slider.Value = v;
            _value.Text = _format(v);
        }
        finally
        {
            _setting = false;
        }
    }
}

/// <summary>A small live graph of recent latency: p50 in the accent, p99 faint behind it.</summary>
internal sealed class Sparkline : FrameworkElement
{
    private const int Points = 120;
    private readonly double[] _p50 = new double[Points], _p99 = new double[Points];
    private int _count, _head;

    public void Add(double p50Ms, double p99Ms)
    {
        _p50[_head] = p50Ms;
        _p99[_head] = p99Ms;
        _head = (_head + 1) % Points;
        if (_count < Points) _count++;
        InvalidateVisual();
    }

    public void Clear()
    {
        _count = 0;
        _head = 0;
        InvalidateVisual();
    }

    protected override Size MeasureOverride(Size availableSize) => new(0, 48);

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight;
        var line = (Brush)FindResource("LineBrush");
        dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, h));
        dc.DrawLine(new Pen(line, 1), new Point(0, h - 0.5), new Point(w, h - 0.5));
        if (_count < 2 || w <= 0) return;
        double max = 1;
        for (int i = 0; i < _count; i++) max = Math.Max(max, _p99[i]);
        max *= 1.15;
        Draw(dc, _p99, (Brush)FindResource("FaintBrush"), 1, w, h, max);
        Draw(dc, _p50, (Brush)FindResource("AccentBrush"), 1.6, w, h, max);
    }

    private void Draw(DrawingContext dc, double[] data, Brush brush, double thickness, double w, double h, double max)
    {
        var geo = new StreamGeometry();
        using (var g = geo.Open())
        {
            int start = (_head - _count + Points) % Points;
            double step = w / (Points - 1);
            double x0 = w - (_count - 1) * step;
            for (int i = 0; i < _count; i++)
            {
                double v = data[(start + i) % Points];
                var p = new Point(x0 + i * step, h - 2 - (h - 4) * Math.Clamp(v / max, 0, 1));
                if (i == 0) g.BeginFigure(p, false, false);
                else g.LineTo(p, true, true);
            }
        }
        geo.Freeze();
        dc.DrawGeometry(null, new Pen(brush, thickness) { LineJoin = PenLineJoin.Round }, geo);
    }
}

/// <summary>A field that takes a shortcut: click it and press the keys (Esc cancels, Delete clears).</summary>
internal sealed class ShortcutBox
{
    private readonly ICompanion _app;
    private readonly GyroPhone.Host.Settings.OverlayAction _action;
    private readonly TextBox _box;
    private readonly TextBlock _note;

    public FrameworkElement View { get; }

    public ShortcutBox(ICompanion app, GyroPhone.Host.Settings.OverlayAction action)
    {
        _app = app;
        _action = action;
        _box = new TextBox { IsReadOnly = true, Cursor = Cursors.Hand, IsReadOnlyCaretVisible = false, MinWidth = 120 };
        _box.GotKeyboardFocus += (_, _) =>
        {
            _app.PauseShortcuts(true);
            _box.Text = "Press the keys…";
        };
        _box.LostKeyboardFocus += (_, _) =>
        {
            _app.PauseShortcuts(false);
            _box.Text = KeysText(_app.Shortcuts.Get(_action));
        };
        _box.PreviewKeyDown += (_, e) =>
        {
            e.Handled = true;
            var key = e.Key == Key.System ? e.SystemKey : e.Key;
            var mods = Keyboard.Modifiers;
            if (mods == ModifierKeys.None && key == Key.Escape)
            {
                Leave();
                return;
            }
            if (mods == ModifierKeys.None && key is Key.Delete or Key.Back)
            {
                _app.SetShortcut(_action, "");
                Leave();
                return;
            }
            int vk = KeyInterop.VirtualKeyFromKey(key);
            if (_app.FormatShortcut(vk, (mods & ModifierKeys.Control) != 0, (mods & ModifierKeys.Alt) != 0, (mods & ModifierKeys.Shift) != 0) is { } keys)
            {
                _app.SetShortcut(_action, keys);
                Leave();
            }
        };
        var clear = Parts.Button("", () => _app.SetShortcut(_action, ""), "Quiet");
        clear.FontFamily = (FontFamily)Application.Current.FindResource("IconFont");
        clear.FontSize = 11;
        clear.ToolTip = "No shortcut";
        clear.Margin = new Thickness(4, 0, 0, 0);
        _note = Parts.Text("Taken by another app", "Sub");
        _note.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
        _note.Visibility = Visibility.Collapsed;

        var name = Parts.Text(GyroPhone.Host.Settings.OverlayShortcuts.ActionName(action), "Sub");
        name.Margin = new Thickness(0, 0, 0, 3);
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(_box);
        Grid.SetColumn(clear, 1);
        grid.Children.Add(clear);
        View = Parts.Column(name, grid, _note);
        View.Margin = new Thickness(0, 0, 0, 10);
    }

    public void Refresh()
    {
        if (!_box.IsKeyboardFocused) _box.Text = KeysText(_app.Shortcuts.Get(_action));
        Parts.Show(_note, _app.ShortcutTaken(_action));
    }

    private void Leave()
    {
        var scope = FocusManager.GetFocusScope(_box);
        FocusManager.SetFocusedElement(scope, null);
        Keyboard.ClearFocus();
    }

    private static string KeysText(string keys) => keys.Length > 0 ? keys : "None";
}
