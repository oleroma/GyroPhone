using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The main window's "Overlays" group: the overlay windows (Add a kind, Remove, pick one), and the
/// picked one's settings in three tabs: Window (showing, background, opacity), Look
/// (what its kind draws and its colours) and Turning (its own pose settings, as the phone's gyro
/// displays have them but the PC's own). Controls ask TrayApp to change a setting; <see cref="RefreshNow"/>
/// shows what is saved.
/// </summary>
internal sealed class OverlaySection
{
    private const int TabWidth = MainForm.ColumnWidth - 20;
    private const int TabHeight = 430;

    private readonly TrayApp _app;
    private readonly ListBox _list;
    private readonly Button _remove;
    private readonly TabControl _tabs;
    private readonly List<string> _ids = [];
    private string _listKey = "";
    // The pick is the tray's, shared with Edit's click-to-pick on the windows.
    private string? Picked
    {
        get => _app.SelectedOverlay;
        set => _app.SelectOverlay(value);
    }
    private bool _refreshing;

    // Window
    private readonly CheckBox _shown, _edit, _hideAll;
    private readonly Button _levelAll;
    private readonly List<(OverlayAction Action, TextBox Box, Label Note)> _keys = [];
    private readonly RadioButton _bgClear, _bgGreen, _bgDark;
    private readonly NumericUpDown _opacity;
    private readonly RadioButton _qHigh, _qBalanced, _qFast, _fpsDisplay, _fps60, _fps30;

    // Look
    private readonly Control _phoneLook, _shapeLook, _diskLook;
    private readonly CheckBox _dots, _trails;
    private readonly RadioButton _handlesAuto, _handlesAlways, _handlesOff;
    private readonly ComboBox _shape;
    private readonly NumericUpDown _subdivision, _diskRange;
    private readonly Control _subdivisionRow;
    private readonly CheckBox _smooth, _hiddenEdges, _fillFaces, _equator, _screenMark, _topMark, _crosshair;
    private readonly FlowLayoutPanel _colors;
    private string _colorsKey = "";
    private readonly List<(string Key, Button Swatch)> _swatches = [];

    // Turning
    private readonly Control _trackballRows, _poseRows;
    private readonly CheckBox _trackball, _rws;
    private readonly NumericUpDown _spin;
    private readonly Control _spinRow;
    private readonly NumericUpDown _scale;
    private readonly CheckBox _gravity, _compass, _reset, _headingOnly, _resetSmoothly, _ease;
    private readonly NumericUpDown _resetSeconds;
    private readonly Control _resetSecondsRow;
    private readonly RadioButton _whenStill, _whenRatchet, _byTime, _bySpeed;
    private readonly NumericUpDown _delay, _seconds, _speed;
    private readonly Control _easeOptions, _secondsRow, _speedRow;

    public OverlaySection(TrayApp app, FlowLayoutPanel g)
    {
        _app = app;
        g.Controls.Add(MainForm.NewLabel("Windows over the game showing the phone, a 3D shape, an attitude indicator or a bubble disk. Each has its own settings."));
        _list = new ListBox { Width = TabWidth, Height = 82, IntegralHeight = false };
        _list.SelectedIndexChanged += (_, _) =>
        {
            if (_refreshing || _list.SelectedIndex < 0 || _list.SelectedIndex >= _ids.Count) return;
            Picked = _ids[_list.SelectedIndex];
            RefreshNow();
        };
        g.Controls.Add(_list);
        var buttons = MainForm.Row();
        var kinds = new ContextMenuStrip();
        var add = MainForm.NewButton("Add ▾", () => { }, buttons);
        foreach (var k in Enum.GetValues<OverlayKind>())
        {
            var kind = k;
            kinds.Items.Add(OverlaySettings.KindName(kind), null, (_, _) =>
            {
                Picked = _app.AddOverlay(kind);
                RefreshNow();
            });
        }
        add.Click += (_, _) => kinds.Show(add, new Point(0, add.Height));
        _remove = MainForm.NewButton("Remove", () =>
        {
            if (Picked is { } id && _app.OverlayOf(id) is { } o &&
                MessageBox.Show($"Remove the {_app.OverlayName(o)} overlay and its settings?", Program.AppName, MessageBoxButtons.OKCancel) == DialogResult.OK)
            {
                Picked = null;
                _app.RemoveOverlay(id);
            }
        }, buttons);
        _levelAll = MainForm.NewButton("Level all", () => _app.LevelAll(), buttons);
        g.Controls.Add(buttons);
        _hideAll = MainForm.Check("Hide all overlays", g, () => _app.SetOverlaysHidden(!_app.OverlaysHidden));
        _edit = MainForm.Check("Edit: drag to move and resize; click overlaps to pick the next", g, () => _app.SetOverlayEdit(!_app.OverlayEdit));
        g.Controls.Add(Heading("Shortcuts"));
        g.Controls.Add(MainForm.NewLabel("Work everywhere, also in games. Click a field and press the keys (Ctrl, Alt, Shift or Win with a key, or F1..F24 alone); Esc cancels, Delete clears."));
        foreach (var a in OverlayShortcuts.All) AddShortcutRow(g, a);

        _tabs = new TabControl { Width = TabWidth, Height = TabHeight, Margin = new Padding(0, 6, 0, 0) };
        g.Controls.Add(_tabs);

        // Window
        var w = Page("Window");
        _shown = MainForm.Check("Show", w, () => Change(_ => { }, show: true));
        w.Controls.Add(Heading("Background"));
        var bg = MainForm.Row();
        _bgClear = MainForm.Radio("Transparent", bg, () => Change(o => o.Background = OverlayBackground.Transparent));
        _bgGreen = MainForm.Radio("Green", bg, () => Change(o => o.Background = OverlayBackground.Green));
        _bgDark = MainForm.Radio("Dark", bg, () => Change(o => o.Background = OverlayBackground.Dark));
        w.Controls.Add(bg);
        w.Controls.Add(MainForm.NewLabel("Green is for an OBS chroma key."));
        _opacity = Number(w, "Opacity, %", 10, 100, 5, 0, v => Change(o => o.Opacity = (float)v / 100f));
        w.Controls.Add(Heading("Drawing quality"));
        var q = MainForm.Row();
        _qHigh = MainForm.Radio("High", q, () => Change(o => o.Quality = OverlayQuality.High));
        _qBalanced = MainForm.Radio("Balanced", q, () => Change(o => o.Quality = OverlayQuality.Balanced));
        _qFast = MainForm.Radio("Fast", q, () => Change(o => o.Quality = OverlayQuality.Fast));
        w.Controls.Add(q);
        w.Controls.Add(MainForm.NewLabel("High: smooth edges and text. Balanced: smooth edges, plain text. Fast: no smoothing, the quickest for big windows."));
        w.Controls.Add(Heading("Frame rate"));
        var fr = MainForm.Row();
        _fpsDisplay = MainForm.Radio("Every screen refresh (more work)", fr, () => Change(o => o.MaxFps = 0));
        _fps60 = MainForm.Radio("60 fps", fr, () => Change(o => o.MaxFps = 60));
        _fps30 = MainForm.Radio("30 fps", fr, () => Change(o => o.MaxFps = 30));
        w.Controls.Add(fr);
        w.Controls.Add(MainForm.NewLabel("Draw times per window are in the Debug window (tray menu)."));

        // Look
        var l = Page("Look");
        var phone = Panel(l);
        _dots = MainForm.Check("Finger dots", phone, () => Change(o => o.FingerDots = !o.FingerDots));
        _trails = MainForm.Check("Finger trails", phone, () => Change(o => o.FingerTrails = !o.FingerTrails));
        phone.Controls.Add(Heading("Controller handles"));
        var hr = MainForm.Row();
        _handlesAuto = MainForm.Radio("While a controller is attached", hr, () => Change(o => o.Handles = OverlayHandles.Auto));
        _handlesAlways = MainForm.Radio("Always", hr, () => Change(o => o.Handles = OverlayHandles.Always));
        _handlesOff = MainForm.Radio("Off", hr, () => Change(o => o.Handles = OverlayHandles.Off));
        phone.Controls.Add(hr);
        _phoneLook = phone;

        var shape = Panel(l);
        var shapeRow = MainForm.Row();
        shapeRow.Controls.Add(Caption("Shape", 110));
        _shape = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 140 };
        foreach (var sh in Enum.GetValues<OverlayShape>()) _shape.Items.Add(OverlaySettings.ShapeName(sh));
        _shape.SelectedIndexChanged += (_, _) =>
        {
            int i = _shape.SelectedIndex;
            if (i >= 0) Change(o => o.Shape = (OverlayShape)i);
        };
        shapeRow.Controls.Add(_shape);
        shape.Controls.Add(shapeRow);
        _subdivision = Number(shape, "Subdivision", 0, Meshes.MaxSubdivision, 1, 0, v => Change(o => o.Subdivision = (int)v));
        _subdivisionRow = _subdivision.Parent!;
        _smooth = MainForm.Check("Smooth (edges bent onto a ball)", shape, () => Change(o => o.Smooth = !o.Smooth));
        _hiddenEdges = MainForm.Check("Edges round the back, faint", shape, () => Change(o => o.HiddenEdges = !o.HiddenEdges));
        _fillFaces = MainForm.Check("Filled faces", shape, () => Change(o => o.FillFaces = !o.FillFaces));
        _equator = MainForm.Check("Equator", shape, () => Change(o => o.Equator = !o.Equator));
        _screenMark = MainForm.Check("Mark where the screen faces", shape, () => Change(o => o.ScreenMark = !o.ScreenMark));
        _topMark = MainForm.Check("Mark at the top", shape, () => Change(o => o.TopMark = !o.TopMark));
        _shapeLook = shape;

        var disk = Panel(l);
        _diskRange = Number(disk, "Dot on the rim at, °", 5, 90, 5, 0, v => Change(o => o.DiskRange = (int)v));
        _crosshair = MainForm.Check("Crosshair", disk, () => Change(o => o.Crosshair = !o.Crosshair));
        _diskLook = disk;

        l.Controls.Add(Heading("Colours"));
        _colors = Panel(l);
        var defaults = MainForm.Row();
        MainForm.NewButton("Default colours", () => Change(o => o.Colors.Clear()), defaults);
        l.Controls.Add(defaults);

        // Turning
        var t = Page("Turning");
        var tb = Panel(t);
        _trackball = MainForm.Check("Trackball: still while the gyro aims, turned by other mouse moves", tb,
            () => Change(o => o.Trackball = !o.Trackball));
        _rws = MainForm.Check("As far as the game's camera", tb, () => Change(o => o.TrackballRws = !o.TrackballRws));
        _rws.Margin = Indent(_rws.Margin);
        _spin = Number(tb, "Spin ×", 0.1m, 10m, 0.1m, 1, v => Change(o => o.TrackballSpin = (float)v));
        _spinRow = _spin.Parent!;
        _spinRow.Margin = Indent(_spinRow.Margin);
        _trackballRows = tb;

        var pose = Panel(t);
        _gravity = MainForm.Check("Gravity: tilt held to the world (never drifts)", pose, () => Change(o => o.Gravity = !o.Gravity));
        _compass = MainForm.Check("Compass: heading held to where the phone faced", pose, () => Change(o => o.Compass = !o.Compass));
        _compass.Margin = Indent(_compass.Margin);
        _reset = MainForm.Check("Reset on ratchet", pose, () => Change(o => o.ResetOnRatchet = !o.ResetOnRatchet));
        _headingOnly = MainForm.Check("Heading only", pose, () => Change(o => o.ResetHeadingOnly = !o.ResetHeadingOnly));
        _headingOnly.Margin = Indent(_headingOnly.Margin);
        _resetSmoothly = MainForm.Check("Smoothly", pose, () => Change(o => o.ResetSmoothly = !o.ResetSmoothly));
        _resetSmoothly.Margin = Indent(_resetSmoothly.Margin);
        _resetSeconds = Number(pose, "Back in, s", 0.1m, 3m, 0.1m, 1, v => Change(o => o.ResetSeconds = (float)v));
        _resetSecondsRow = _resetSeconds.Parent!;
        _resetSecondsRow.Margin = Indent(Indent(_resetSecondsRow.Margin));
        _ease = MainForm.Check("Ease back to straight", pose, () => Change(o => o.Ease = !o.Ease));
        var ease = Panel(pose);
        ease.Margin = new Padding(18, 0, 0, 0);
        var when = MainForm.Row();
        _whenStill = MainForm.Radio("Phone stops", when, () => Change(o => o.EaseAfterRatchet = false));
        _whenRatchet = MainForm.Radio("Ratchet let go", when, () => Change(o => o.EaseAfterRatchet = true));
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
        _easeOptions = ease;
        _poseRows = pose;

        _scale = Number(t, "Turn amount ×", 0.5m, 3m, 0.1m, 1, v => Change(o => o.MotionScale = (float)v));
        var center = MainForm.Row();
        MainForm.NewButton("Level", () => { if (Picked is { } id) _app.LevelOverlay(id); }, center);
        t.Controls.Add(center);
        t.Controls.Add(MainForm.NewLabel("Hold the phone the way you play and press Level: held that way, it shows face-on, its screen flat toward you."));
    }

    /// <summary>The settings column is <paramref name="width"/> wide (the splitter moved): the list and tabs fill it.</summary>
    public void Fit(int width)
    {
        int w = Math.Max(TabWidth, width);
        if (_list.Width != w) _list.Width = w;
        if (_tabs.Width != w) _tabs.Width = w;
    }

    public void RefreshNow()
    {
        if (_refreshing) return;
        _refreshing = true;
        try
        {
            var all = _app.Overlays;
            if (Picked == null || all.All(o => o.Id != Picked)) Picked = all.Count > 0 ? all[0].Id : null;
            var key = string.Join("|", all.Select(o => $"{o.Id}{o.Kind}{o.Shown}"));
            if (key != _listKey)
            {
                _listKey = key;
                _ids.Clear();
                _list.BeginUpdate();
                _list.Items.Clear();
                foreach (var o in all)
                {
                    _ids.Add(o.Id);
                    _list.Items.Add($"{_app.OverlayName(o)}{(o.Shown ? "" : "  (hidden)")}");
                }
                _list.EndUpdate();
            }
            int at = Picked == null ? -1 : _ids.IndexOf(Picked);
            if (_list.SelectedIndex != at) _list.SelectedIndex = at;
            var s = Picked == null ? null : _app.OverlayOf(Picked);
            _remove.Enabled = s != null;
            _edit.Checked = _app.OverlayEdit;
            _hideAll.Checked = _app.OverlaysHidden;
            _hideAll.Text = "Hide all overlays" + _app.KeysSuffix(OverlayAction.HideAll);
            _edit.Text = "Edit: drag to move and resize; click overlaps to pick the next" + _app.KeysSuffix(OverlayAction.Edit);
            _levelAll.Text = "Level all" + _app.KeysSuffix(OverlayAction.LevelAll);
            foreach (var (a, box, note) in _keys)
            {
                if (!box.Focused) box.Text = KeysText(_app.Shortcuts.Get(a));
                bool taken = _app.ShortcutTaken(a);
                note.Text = taken ? "Taken by another app" : "";
                note.Visible = taken;
            }
            _tabs.Visible = s != null;
            if (s != null) ShowSettings(s);
        }
        finally
        {
            _refreshing = false;
        }
    }

    private void ShowSettings(OverlaySettings o)
    {
        _shown.Checked = o.Shown;
        _bgClear.Checked = o.Background == OverlayBackground.Transparent;
        _bgGreen.Checked = o.Background == OverlayBackground.Green;
        _bgDark.Checked = o.Background == OverlayBackground.Dark;
        Set(_opacity, (decimal)MathF.Round(o.Opacity * 100f));
        _qHigh.Checked = o.Quality == OverlayQuality.High;
        _qBalanced.Checked = o.Quality == OverlayQuality.Balanced;
        _qFast.Checked = o.Quality == OverlayQuality.Fast;
        _fpsDisplay.Checked = o.MaxFps is not (60 or 30);
        _fps60.Checked = o.MaxFps == 60;
        _fps30.Checked = o.MaxFps == 30;

        _phoneLook.Visible = o.Kind == OverlayKind.Phone;
        _shapeLook.Visible = o.Kind == OverlayKind.Shape;
        _diskLook.Visible = o.Kind == OverlayKind.Disk;
        _dots.Checked = o.FingerDots;
        _trails.Checked = o.FingerTrails;
        _handlesAuto.Checked = o.Handles == OverlayHandles.Auto;
        _handlesAlways.Checked = o.Handles == OverlayHandles.Always;
        _handlesOff.Checked = o.Handles == OverlayHandles.Off;
        if (_shape.SelectedIndex != (int)o.Shape) _shape.SelectedIndex = (int)o.Shape;
        _subdivisionRow.Visible = o.Shape == OverlayShape.Icosphere;
        Set(_subdivision, o.Subdivision);
        _smooth.Checked = o.Smooth;
        _smooth.Visible = o.Shape is not (OverlayShape.Plane or OverlayShape.Tetrahedron);
        _hiddenEdges.Checked = o.HiddenEdges;
        _fillFaces.Checked = o.FillFaces;
        _fillFaces.Visible = o.Shape != OverlayShape.Gimbal;
        _equator.Checked = o.Equator;
        _equator.Visible = o.Shape == OverlayShape.Globe;
        _screenMark.Checked = o.ScreenMark;
        _topMark.Checked = o.TopMark;
        Set(_diskRange, o.DiskRange);
        _crosshair.Checked = o.Crosshair;
        ShowColors(o);

        _trackballRows.Visible = o.Kind == OverlayKind.Shape;
        _trackball.Checked = o.Trackball;
        _rws.Checked = o.TrackballRws;
        _rws.Visible = o.Trackball;
        Set(_spin, (decimal)o.TrackballSpin);
        _spinRow.Visible = o.Trackball && !o.TrackballRws;
        _poseRows.Visible = !o.IsTrackball;
        _gravity.Checked = o.Gravity;
        _compass.Checked = o.Compass;
        _compass.Visible = o.Gravity;
        _reset.Checked = o.ResetOnRatchet;
        // With gravity a reset is always the heading only.
        _headingOnly.Checked = o.ResetHeadingOnly;
        _headingOnly.Visible = o.ResetOnRatchet && !o.Gravity;
        // With the compass the heading is the compass's: the reset re-aims at once.
        _resetSmoothly.Checked = o.ResetSmoothly;
        bool canGlide = o.ResetOnRatchet && !(o.Gravity && o.Compass);
        _resetSmoothly.Visible = canGlide;
        Set(_resetSeconds, (decimal)o.ResetSeconds);
        _resetSecondsRow.Visible = canGlide && o.ResetSmoothly;
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
        Set(_scale, (decimal)o.MotionScale);
    }

    /// <summary>One shortcut: its name, a field that takes the keys pressed in it, a Clear button and a note when another app holds them.</summary>
    private void AddShortcutRow(Control parent, OverlayAction a)
    {
        var row = MainForm.Row();
        row.Controls.Add(Caption(OverlayShortcuts.ActionName(a), 170));
        var box = new TextBox { Width = 130, ReadOnly = true, ShortcutsEnabled = false, Cursor = Cursors.Hand, BackColor = SystemColors.Window };
        box.GotFocus += (_, _) =>
        {
            _app.PauseShortcuts(true);
            box.Text = "Press the keys…";
        };
        box.LostFocus += (_, _) =>
        {
            _app.PauseShortcuts(false);
            box.Text = KeysText(_app.Shortcuts.Get(a));
        };
        box.PreviewKeyDown += (_, e) => e.IsInputKey = true; // Tab, arrows and Enter are keys too
        box.KeyDown += (_, e) =>
        {
            e.Handled = e.SuppressKeyPress = true;
            var key = e.KeyCode;
            if (e.Modifiers == Keys.None && key == Keys.Escape)
            {
                Leave(box);
                return;
            }
            if (e.Modifiers == Keys.None && key is Keys.Delete or Keys.Back)
            {
                _app.SetShortcut(a, "");
                Leave(box);
                return;
            }
            if (Hotkeys.Format(e.KeyData) is { } keys)
            {
                _app.SetShortcut(a, keys);
                Leave(box);
            }
        };
        row.Controls.Add(box);
        var clear = new Button { Text = "Clear", AutoSize = true };
        clear.Click += (_, _) => _app.SetShortcut(a, "");
        row.Controls.Add(clear);
        var note = new Label { AutoSize = true, ForeColor = Color.Firebrick, Margin = new Padding(3, 6, 3, 3), Visible = false };
        row.Controls.Add(note);
        parent.Controls.Add(row);
        _keys.Add((a, box, note));
    }

    /// <summary>Moves focus off a shortcut field (to its form), which puts the shortcuts back.</summary>
    private static void Leave(TextBox box)
    {
        if (box.FindForm() is { } f) f.ActiveControl = null;
    }

    private static string KeysText(string keys) => keys.Length > 0 ? keys : "None";

    /// <summary>One swatch per colour the picked window's kind (and shape) draws with.</summary>
    private void ShowColors(OverlaySettings o)
    {
        var list = OverlaySettings.ColorsOf(o.Kind, o.Kind == OverlayKind.Shape ? o.Shape : null);
        var key = string.Join("|", list.Select(c => c.Key)) + o.Kind;
        if (key != _colorsKey)
        {
            _colorsKey = key;
            _colors.SuspendLayout();
            foreach (var c in _colors.Controls.Cast<Control>().ToList()) c.Dispose();
            _colors.Controls.Clear();
            _swatches.Clear();
            foreach (var c in list)
            {
                var row = MainForm.Row();
                row.Controls.Add(Caption(c.Label, 140));
                var b = new Button { Width = 36, Height = 22, FlatStyle = FlatStyle.Flat, Text = "" };
                var colorKey = c.Key;
                b.Click += (_, _) => PickColor(b, colorKey);
                row.Controls.Add(b);
                _colors.Controls.Add(row);
                _swatches.Add((c.Key, b));
            }
            _colors.ResumeLayout();
        }
        foreach (var (k, b) in _swatches) b.BackColor = Color.FromArgb(255, Color.FromArgb(o.Color(k)));
    }

    private void PickColor(Button b, string key)
    {
        if (Picked == null || _app.OverlayOf(Picked) is not { } o) return;
        var now = Color.FromArgb(o.Color(key));
        using var dlg = new ColorDialog { Color = Color.FromArgb(255, now), FullOpen = true, AnyColor = true };
        if (dlg.ShowDialog(b.FindForm()) != DialogResult.OK) return;
        // The picker has no alpha: keep the colour's own (some defaults are see-through).
        int argb = (now.A << 24) | (dlg.Color.ToArgb() & 0xFFFFFF);
        Change(x => x.Colors[key] = argb);
    }

    private void Change(Action<OverlaySettings> change, bool show = false)
    {
        if (_refreshing || Picked is not { } id) return;
        if (show) _app.SetOverlayShown(id, !(_app.OverlayOf(id)?.Shown ?? false));
        else _app.ChangeOverlay(id, change);
    }

    private FlowLayoutPanel Page(string title)
    {
        var page = new TabPage(title) { Padding = new Padding(4) };
        var flow = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
            Dock = DockStyle.Fill,
        };
        page.Controls.Add(flow);
        _tabs.TabPages.Add(page);
        return flow;
    }

    private static FlowLayoutPanel Panel(Control parent)
    {
        var p = new FlowLayoutPanel
        {
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            Margin = new Padding(0),
        };
        parent.Controls.Add(p);
        return p;
    }

    private static void Set(NumericUpDown n, decimal v)
    {
        // A field being typed in is left alone: reading its Value would commit the half-typed text
        // (while refreshing, so the change was dropped) and setting it would throw the typing away.
        if (n.ContainsFocus) return;
        v = Math.Clamp(v, n.Minimum, n.Maximum);
        if (n.Value != v) n.Value = v;
    }

    private static Padding Indent(Padding p) => new(p.Left + 18, p.Top, p.Right, p.Bottom);

    private static Label Caption(string text, int width = 0) => new()
    {
        Text = text,
        AutoSize = true,
        MinimumSize = new Size(width, 0),
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
        row.Controls.Add(Caption(label, 120));
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
}
