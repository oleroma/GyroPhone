using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The main window's "Phone overlay" group: everything in the tray's Phone overlay menu plus the
/// pose settings (as the phone's gyro phone view has them, but the PC's own) and the looks.
/// Controls ask TrayApp to change a setting; <see cref="RefreshNow"/> shows what is saved.
/// </summary>
internal sealed class OverlaySection
{
    private readonly TrayApp _app;
    private readonly CheckBox _shown, _clickThrough, _dots;
    private readonly RadioButton _bgClear, _bgGreen, _bgDark;
    private readonly NumericUpDown _scale;
    private readonly CheckBox _gravity, _compass, _reset, _headingOnly, _ease;
    private readonly RadioButton _whenStill, _whenRatchet, _byTime, _bySpeed;
    private readonly NumericUpDown _delay, _seconds, _speed, _level, _opacity;
    private readonly Control _easeOptions, _secondsRow, _speedRow;
    private readonly Button _body, _screen, _lit;
    private bool _refreshing;

    public OverlaySection(TrayApp app, FlowLayoutPanel g)
    {
        _app = app;
        _shown = MainForm.Check("Show phone overlay", g, () => _app.SetOverlayShown(!_app.OverlaySettings.Shown));
        _clickThrough = MainForm.Check("Click-through (off: drag to move or resize)", g,
            () => Change(o => o.ClickThrough = !o.ClickThrough));
        var bg = MainForm.Row();
        bg.Controls.Add(Caption("Background"));
        _bgClear = MainForm.Radio("Transparent", bg, () => Change(o => o.Background = OverlayBackground.Transparent));
        _bgGreen = MainForm.Radio("Green", bg, () => Change(o => o.Background = OverlayBackground.Green));
        _bgDark = MainForm.Radio("Dark", bg, () => Change(o => o.Background = OverlayBackground.Dark));
        g.Controls.Add(bg);
        _dots = MainForm.Check("Finger dots", g, () => Change(o => o.FingerDots = !o.FingerDots));
        _scale = Number(g, "Turn amount ×", 0.5m, 3m, 0.1m, 1, v => Change(o => o.MotionScale = (float)v));

        g.Controls.Add(Heading("Orientation"));
        _gravity = MainForm.Check("Gravity: tilt held to the world (never drifts)", g, () => Change(o => o.Gravity = !o.Gravity));
        _compass = MainForm.Check("Compass: heading held to where the phone faced", g, () => Change(o => o.Compass = !o.Compass));
        _reset = MainForm.Check("Reset on ratchet", g, () => Change(o => o.ResetOnRatchet = !o.ResetOnRatchet));
        _headingOnly = MainForm.Check("Heading only", g, () => Change(o => o.ResetHeadingOnly = !o.ResetHeadingOnly));
        _headingOnly.Margin = Indent(_headingOnly.Margin);
        _ease = MainForm.Check("Ease back to straight", g, () => Change(o => o.Ease = !o.Ease));

        var ease = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            Margin = new Padding(18, 0, 0, 0),
        };
        var when = MainForm.Row();
        _whenStill = MainForm.Radio("When the phone stops", when, () => Change(o => o.EaseAfterRatchet = false));
        _whenRatchet = MainForm.Radio("When the ratchet is let go", when, () => Change(o => o.EaseAfterRatchet = true));
        ease.Controls.Add(when);
        _delay = Number(ease, "Delay, ms", 0, 3000, 50, 0, v => Change(o => o.EaseDelayMs = (int)v));
        var by = MainForm.Row();
        _byTime = MainForm.Radio("By time", by, () => Change(o => o.EaseBySpeed = false));
        _bySpeed = MainForm.Radio("By speed", by, () => Change(o => o.EaseBySpeed = true));
        ease.Controls.Add(by);
        _seconds = Number(ease, "Back in, s", 0.1m, 5m, 0.1m, 1, v => Change(o => o.EaseSeconds = (float)v));
        _secondsRow = _seconds.Parent!;
        _speed = Number(ease, "Speed, °/s", 10, 720, 10, 0, v => Change(o => o.EaseSpeed = (int)v));
        _speedRow = _speed.Parent!;
        g.Controls.Add(ease);
        _easeOptions = ease;

        _level = Number(g, "Level at, °", 0, 180, 5, 0, v => Change(o => o.LevelDeg = (int)v));
        g.Controls.Add(MainForm.NewLabel("0 upright facing you, 90 flat screen up, 180 facing away."));
        var center = MainForm.Row();
        MainForm.NewButton("Recenter (Ctrl+Alt+R)", () => _app.RecenterOverlay(), center);
        MainForm.NewButton("Level again", () => Change(o => o.Center = null), center);
        g.Controls.Add(center);

        g.Controls.Add(Heading("Looks"));
        var colors = MainForm.Row();
        _body = Swatch("Body", colors, o => o.BodyColor, (o, c) => o.BodyColor = c);
        _screen = Swatch("Screen", colors, o => o.ScreenColor, (o, c) => o.ScreenColor = c);
        _lit = Swatch("Pressed", colors, o => o.LitColor, (o, c) => o.LitColor = c);
        g.Controls.Add(colors);
        _opacity = Number(g, "Opacity, %", 10, 100, 5, 0, v => Change(o => o.Opacity = (float)v / 100f));
        var defaults = MainForm.Row();
        MainForm.NewButton("Default colours", () => Change(o =>
        {
            var d = new OverlaySettings();
            o.BodyColor = d.BodyColor;
            o.ScreenColor = d.ScreenColor;
            o.LitColor = d.LitColor;
            o.Opacity = d.Opacity;
        }), defaults);
        g.Controls.Add(defaults);
    }

    public void RefreshNow(OverlaySettings o)
    {
        _refreshing = true;
        try
        {
            _shown.Checked = o.Shown;
            _clickThrough.Checked = o.ClickThrough;
            _bgClear.Checked = o.Background == OverlayBackground.Transparent;
            _bgGreen.Checked = o.Background == OverlayBackground.Green;
            _bgDark.Checked = o.Background == OverlayBackground.Dark;
            _dots.Checked = o.FingerDots;
            Set(_scale, (decimal)o.MotionScale);
            _gravity.Checked = o.Gravity;
            _compass.Checked = o.Compass;
            _compass.Enabled = o.Gravity;
            _reset.Checked = o.ResetOnRatchet;
            // With gravity a reset is always the heading only.
            _headingOnly.Checked = o.ResetHeadingOnly;
            _headingOnly.Visible = o.ResetOnRatchet && !o.Gravity;
            _ease.Checked = o.Ease;
            _ease.Enabled = !o.NeedsCompass;
            _easeOptions.Visible = o.Ease && !o.NeedsCompass;
            _whenStill.Checked = !o.EaseAfterRatchet;
            _whenRatchet.Checked = o.EaseAfterRatchet;
            Set(_delay, o.EaseDelayMs);
            _byTime.Checked = !o.EaseBySpeed;
            _bySpeed.Checked = o.EaseBySpeed;
            Set(_seconds, (decimal)o.EaseSeconds);
            Set(_speed, o.EaseSpeed);
            _secondsRow.Visible = !o.EaseBySpeed;
            _speedRow.Visible = o.EaseBySpeed;
            Set(_level, o.LevelDeg);
            _body.BackColor = Color.FromArgb(255, Color.FromArgb(o.BodyColor));
            _screen.BackColor = Color.FromArgb(255, Color.FromArgb(o.ScreenColor));
            _lit.BackColor = Color.FromArgb(255, Color.FromArgb(o.LitColor));
            Set(_opacity, (decimal)MathF.Round(o.Opacity * 100f));
        }
        finally
        {
            _refreshing = false;
        }
    }

    private void Change(Action<OverlaySettings> change)
    {
        if (!_refreshing) _app.ChangeOverlay(change);
    }

    private static void Set(NumericUpDown n, decimal v)
    {
        v = Math.Clamp(v, n.Minimum, n.Maximum);
        if (n.Value != v) n.Value = v;
    }

    private static Padding Indent(Padding p) => new(p.Left + 18, p.Top, p.Right, p.Bottom);

    private static Label Caption(string text) => new()
    {
        Text = text,
        AutoSize = true,
        Margin = new Padding(3, 6, 6, 3),
    };

    private static Label Heading(string text)
    {
        var l = Caption(text);
        l.Font = new Font(l.Font, FontStyle.Bold);
        l.Margin = new Padding(3, 10, 3, 3);
        return l;
    }

    private NumericUpDown Number(Control parent, string label, decimal min, decimal max, decimal step, int decimals, Action<decimal> changed)
    {
        var row = MainForm.Row();
        var l = Caption(label);
        l.MinimumSize = new Size(110, 0);
        row.Controls.Add(l);
        var n = new NumericUpDown
        {
            Minimum = min,
            Maximum = max,
            Increment = step,
            DecimalPlaces = decimals,
            Width = 80,
            Value = min,
        };
        n.ValueChanged += (_, _) =>
        {
            if (!_refreshing) changed(n.Value);
        };
        row.Controls.Add(n);
        parent.Controls.Add(row);
        return n;
    }

    private Button Swatch(string text, Control parent, Func<OverlaySettings, int> get, Action<OverlaySettings, int> set)
    {
        var row = MainForm.Row();
        row.Controls.Add(Caption(text));
        var b = new Button { Width = 36, Height = 22, FlatStyle = FlatStyle.Flat, Text = "" };
        b.Click += (_, _) =>
        {
            using var dlg = new ColorDialog { Color = Color.FromArgb(get(_app.OverlaySettings)), FullOpen = true, AnyColor = true };
            if (dlg.ShowDialog(b.FindForm()) != DialogResult.OK) return;
            int argb = dlg.Color.ToArgb() | unchecked((int)0xFF000000);
            Change(o => set(o, argb));
        };
        row.Controls.Add(b);
        parent.Controls.Add(row);
        return b;
    }
}
