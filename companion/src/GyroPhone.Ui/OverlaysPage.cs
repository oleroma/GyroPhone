using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using GyroPhone.Host.Settings;

namespace GyroPhone.Ui;

/// <summary>
/// Overlays: the switches for all of them (Edit, Hide all, Level all) at the top; the overlay windows
/// as a plain list on the left (each with its Show switch, then Add and the shortcuts); the picked
/// one's settings on the right in three tabs: Window, Look and Turning. Nothing scrolls but the page.
/// </summary>
internal sealed class OverlaysPage : Page
{
    private readonly ICompanion _app;
    private readonly MainWindow _window;
    private readonly Grid _root = new();
    private bool _refreshing;

    // Top
    private readonly CheckBox _edit, _hideAll;
    private readonly Button _levelAll;

    // Left
    private readonly StackPanel _list = new();
    private string _listKey = "\0";
    private readonly List<(string Id, RadioButton Row, CheckBox Shown, TextBlock Sub)> _rows = [];
    private readonly WrapPanel _addKinds;
    private readonly List<ShortcutBox> _shortcuts = [];

    // Right
    private readonly StackPanel _settings = new();
    private readonly TextBlock _empty;
    private readonly Seg<int> _tabs;
    private int _tab;
    private readonly StackPanel _windowTab, _lookTab, _turnTab;

    // Window
    private readonly CheckBox _shown;
    private readonly Seg<OverlayBackground> _background;
    private readonly SliderRow _opacity;
    private readonly Seg<OverlayQuality> _quality;
    private readonly Seg<int> _fps;

    // Look
    private readonly Border _phoneLook, _shapeLook, _diskLook, _laserLook;
    private readonly CheckBox _laserAxon, _laserGrid, _laserAxes, _laserDraw, _laserClearOnRatchet;
    private readonly Seg<int> _laserTrail, _laserFade;
    private readonly SliderRow _laserBrush;
    private readonly StackPanel _laserDrawOptions;
    private readonly CheckBox _dots, _trails, _phoneAxon, _shapeAxon;
    private readonly Seg<OverlayHandles> _handles;
    private readonly Seg<OverlayHandleSource> _handleSource;
    private readonly Seg<OverlayShape> _shape;
    private readonly SliderRow _subdivision, _diskRange;
    private readonly CheckBox _smooth, _hiddenEdges, _fillFaces, _equator, _screenMark, _topMark, _crosshair;
    private readonly StackPanel _colors = new();
    private string _colorsKey = "";
    private readonly List<(string Key, Border Swatch)> _swatches = [];

    // Turning
    private readonly Border _poseCard;
    private readonly Seg<OverlayTurnsWith> _turnsWith;
    private readonly TextBlock _turnsHint;
    private readonly UIElement _gravityDivider;
    private readonly CheckBox _rws;
    private readonly SliderRow _spin;
    private readonly CheckBox _gravity, _compass, _ease, _headingOnly;
    private readonly StackPanel _easeOptions;
    private readonly Seg<bool> _easeWhen, _easeBy;
    private readonly SliderRow _delay, _seconds, _speed, _scale;
    /// <summary>Under Turn amount; both hidden while following the camera, where Spin sets how far it turns.</summary>
    private readonly UIElement _scaleDivider;

    public override FrameworkElement View => _root;

    private string? Picked => _app.SelectedOverlay;

    public OverlaysPage(ICompanion app, MainWindow window)
    {
        _app = app;
        _window = window;
        _root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new RowDefinition());
        _root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(250) });
        _root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(20) });
        _root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        // Top: the title and the switches for every overlay.
        var top = new DockPanel { Margin = new Thickness(0, 0, 0, 16) };
        var title = Parts.Text("Overlays", "H1");
        title.Margin = new Thickness(0, 0, 24, 0);
        top.Children.Add(title);
        var global = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
        _edit = Parts.Toggle("Edit", () => _app.SetOverlayEdit(!_app.OverlayEdit));
        _edit.ToolTip = "Drag the overlay windows to move and resize them. Click where they overlap to pick the next one.";
        _edit.Margin = new Thickness(0, 0, 24, 0);
        _hideAll = Parts.Toggle("Hide all", () => _app.SetOverlaysHidden(!_app.OverlaysHidden));
        _hideAll.ToolTip = "Closes every overlay window; each keeps its own Show for when this is off.";
        _hideAll.Margin = new Thickness(0, 0, 24, 0);
        _levelAll = Parts.Button("Level all", app.LevelAll, glyph: "");
        _levelAll.ToolTip = "Held the way it is now, every overlay shows the phone face-on.";
        global.Children.Add(_edit);
        global.Children.Add(_hideAll);
        global.Children.Add(_levelAll);
        DockPanel.SetDock(global, Dock.Right);
        top.Children.Insert(0, global);
        Grid.SetColumnSpan(top, 3);
        _root.Children.Add(top);

        // Left: the windows, Add, and the shortcuts.
        var left = new StackPanel();
        Grid.SetRow(left, 1);
        _root.Children.Add(left);
        left.Children.Add(_list);
        var add = Parts.Button("Add overlay", () => Parts.Show(_addKinds!, _addKinds!.Visibility != Visibility.Visible), glyph: "");
        add.HorizontalAlignment = HorizontalAlignment.Stretch;
        left.Children.Add(add);
        _addKinds = new WrapPanel { Margin = new Thickness(0, 8, 0, 0), Visibility = Visibility.Collapsed };
        foreach (var k in Enum.GetValues<OverlayKind>())
        {
            var kind = k;
            var b = Parts.Button(OverlaySettings.KindName(kind), () =>
            {
                _app.SelectOverlay(_app.AddOverlay(kind));
                Parts.Show(_addKinds, false);
                _window.RefreshNow();
            });
            b.Margin = new Thickness(0, 0, 6, 6);
            _addKinds.Children.Add(b);
        }
        left.Children.Add(_addKinds);

        var (keys, kb) = Parts.Card("Shortcuts");
        keys.Margin = new Thickness(0, 16, 0, 12);
        kb.Children.Add(Parts.Text("Work everywhere, also in games. Click a field and press the keys: Ctrl, Alt, Shift or Win with a key, or F1 to F24 alone.", "Hint"));
        foreach (var a in OverlayShortcuts.All)
        {
            var box = new ShortcutBox(app, a);
            _shortcuts.Add(box);
            kb.Children.Add(box.View);
        }
        left.Children.Add(keys);

        // Right: the picked overlay's settings.
        var right = new StackPanel();
        Grid.SetRow(right, 1);
        Grid.SetColumn(right, 2);
        _root.Children.Add(right);
        _empty = Parts.Text("No overlays yet. Press \"Add overlay\" to put the phone, a 3D shape, an attitude indicator, a bubble disk or a laser pointer over your game.", "Sub");
        right.Children.Add(_empty);
        right.Children.Add(_settings);
        var tabBar = new Border { BorderThickness = new Thickness(0, 0, 0, 1), Margin = new Thickness(0, 0, 0, 14) };
        tabBar.SetResourceReference(Border.BorderBrushProperty, "LineBrush");
        _tabs = new Seg<int>(t =>
        {
            _tab = t;
            ShowTab();
        }, true, ("Window", 0), ("Look", 1), ("Turning", 2));
        // Tabs: the segmented buttons in the tab style.
        foreach (var rb in ((Panel)_tabs.View).Children.OfType<RadioButton>()) rb.Style = Parts.Style("Tab");
        tabBar.Child = _tabs.View;
        _settings.Children.Add(tabBar);
        _windowTab = new StackPanel();
        _lookTab = new StackPanel();
        _turnTab = new StackPanel();
        _settings.Children.Add(_windowTab);
        _settings.Children.Add(_lookTab);
        _settings.Children.Add(_turnTab);

        // Window tab
        var (show, sb) = Parts.Card();
        _shown = Parts.Toggle("Show this overlay", () => { if (Picked is { } id) _app.SetOverlayShown(id, !(_app.OverlayOf(id)?.Shown ?? false)); });
        sb.Children.Add(_shown);
        _windowTab.Children.Add(show);
        var (bg, bb) = Parts.Card("Background");
        _background = new Seg<OverlayBackground>(b => Change(o => o.Background = b), false,
            ("Transparent", OverlayBackground.Transparent), ("Green", OverlayBackground.Green), ("Dark", OverlayBackground.Dark));
        bb.Children.Add(_background.View);
        bb.Children.Add(Parts.Text("Green is for an OBS chroma key.", "Hint"));
        _opacity = new SliderRow("Opacity", 10, 100, 5, v => $"{v:0}%", v => Change(o => o.Opacity = (float)v / 100f));
        bb.Children.Add(_opacity.View);
        _windowTab.Children.Add(bg);
        var (draw, db) = Parts.Card("Drawing");
        db.Children.Add(Parts.Text("Quality"));
        _quality = new Seg<OverlayQuality>(q => Change(o => o.Quality = q), false,
            ("High", OverlayQuality.High), ("Balanced", OverlayQuality.Balanced), ("Fast", OverlayQuality.Fast));
        _quality.View.Margin = new Thickness(0, 6, 0, 2);
        db.Children.Add(_quality.View);
        db.Children.Add(Parts.Text("High: smooth edges and text. Balanced: smooth edges, plain text. Fast: no smoothing, the quickest for big windows.", "Hint"));
        db.Children.Add(Parts.Text("Frame rate"));
        _fps = new Seg<int>(f => Change(o => o.MaxFps = f), false, ("Every screen refresh", 0), ("60 fps", 60), ("30 fps", 30));
        _fps.View.Margin = new Thickness(0, 6, 0, 2);
        db.Children.Add(_fps.View);
        db.Children.Add(Parts.Text("Every screen refresh is smoothest but costs more. Draw times are in the Debug window (App page).", "Hint"));
        _windowTab.Children.Add(draw);

        // Look tab
        var (phone, pb) = Parts.Card("Phone");
        _dots = Parts.Toggle("Finger dots", () => Change(o => o.FingerDots = !o.FingerDots));
        _trails = Parts.Toggle("Finger trails", () => Change(o => o.FingerTrails = !o.FingerTrails));
        pb.Children.Add(_dots);
        pb.Children.Add(_trails);
        _phoneAxon = Parts.Toggle("Axonometric view", () => Change(o => o.Axonometric = !o.Axonometric), AxonHint);
        pb.Children.Add(_phoneAxon);
        pb.Children.Add(Parts.Divider());
        pb.Children.Add(Parts.Text("Controller handles"));
        _handles = new Seg<OverlayHandles>(h => Change(o => o.Handles = h), false,
            ("While a controller is attached", OverlayHandles.Auto), ("Always", OverlayHandles.Always), ("Off", OverlayHandles.Off));
        _handles.View.Margin = new Thickness(0, 6, 0, 2);
        pb.Children.Add(_handles.View);
        pb.Children.Add(Parts.Text("Controller"));
        _handleSource = new Seg<OverlayHandleSource>(h => Change(o => o.HandleSource = h), false,
            ("Phone", OverlayHandleSource.Phone), ("PC", OverlayHandleSource.Pc));
        _handleSource.View.Margin = new Thickness(0, 6, 0, 2);
        pb.Children.Add(_handleSource.View);
        pb.Children.Add(Parts.Text("PC: the controller plugged into this PC, while \"Use PC controller for gyro ratchet\" is on in the phone's Connection settings.", "Hint"));
        _phoneLook = phone;
        _lookTab.Children.Add(phone);

        var (shape, shb) = Parts.Card("Shape");
        _shape = new Seg<OverlayShape>(s => Change(o => o.Shape = s), true,
            Enum.GetValues<OverlayShape>().Select(s => (OverlaySettings.ShapeName(s), s)).ToArray());
        shb.Children.Add(_shape.View);
        _subdivision = new SliderRow("Subdivision", 0, MaxSubdivision, 1, v => $"{v:0}", v => Change(o => o.Subdivision = (int)v));
        shb.Children.Add(_subdivision.View);
        _smooth = Parts.Toggle("Smooth (edges bent onto a ball)", () => Change(o => o.Smooth = !o.Smooth));
        _hiddenEdges = Parts.Toggle("Edges round the back, faint", () => Change(o => o.HiddenEdges = !o.HiddenEdges));
        _fillFaces = Parts.Toggle("Filled faces", () => Change(o => o.FillFaces = !o.FillFaces));
        _equator = Parts.Toggle("Equator", () => Change(o => o.Equator = !o.Equator));
        _screenMark = Parts.Toggle("Mark where the screen faces", () => Change(o => o.ScreenMark = !o.ScreenMark));
        _topMark = Parts.Toggle("Mark at the top", () => Change(o => o.TopMark = !o.TopMark));
        foreach (var c in new[] { _smooth, _hiddenEdges, _fillFaces, _equator, _screenMark, _topMark }) shb.Children.Add(c);
        _shapeAxon = Parts.Toggle("Axonometric view", () => Change(o => o.Axonometric = !o.Axonometric), AxonHint);
        shb.Children.Add(_shapeAxon);
        _shapeLook = shape;
        _lookTab.Children.Add(shape);

        var (disk, dkb) = Parts.Card("Bubble disk");
        _diskRange = new SliderRow("Dot on the rim at", 5, 90, 5, v => $"{v:0}°", v => Change(o => o.DiskRange = (int)v));
        dkb.Children.Add(_diskRange.View);
        _crosshair = Parts.Toggle("Crosshair", () => Change(o => o.Crosshair = !o.Crosshair));
        dkb.Children.Add(_crosshair);
        _diskLook = disk;
        _lookTab.Children.Add(disk);

        var (laser, lb) = Parts.Card("Laser pointer");
        lb.Children.Add(Parts.Text("A see-through sphere seen from above and to the side. The laser at its centre shines out of the phone's back onto the inside; the tick at the dot shows which way the phone's top points. The beam dims while a ratchet is held.", "Hint"));
        _laserAxon = Parts.Toggle("Axonometric view", () => Change(o => o.LaserAxonometric = !o.LaserAxonometric), "Seen from 30° round and 30° above, so a beam into the far side shows as a line. Off, face-on: a beam straight in is a dot.");
        _laserGrid = Parts.Toggle("Grid every 15°", () => Change(o => o.LaserGrid = !o.LaserGrid));
        lb.Children.Add(_laserAxon);
        _laserAxes = Parts.Toggle("Axes (right, up, toward you)", () => Change(o => o.LaserAxes = !o.LaserAxes));
        lb.Children.Add(_laserGrid);
        lb.Children.Add(_laserAxes);
        lb.Children.Add(Parts.Text("Trail"));
        _laserTrail = new Seg<int>(t => Change(o => o.LaserTrailMs = t), false,
            ("None", 0), ("0.25 s", 250), ("0.5 s", 500), ("1 s", 1000), ("2 s", 2000), ("3 s", 3000));
        _laserTrail.View.Margin = new Thickness(0, 6, 0, 6);
        lb.Children.Add(_laserTrail.View);
        lb.Children.Add(Parts.Divider());
        _laserDraw = Parts.Toggle("Draw mode", () => Change(o => o.LaserDraw = !o.LaserDraw), "The beam paints the inside of the sphere as you aim.");
        lb.Children.Add(_laserDraw);
        _laserDrawOptions = new StackPanel { Margin = new Thickness(20, 0, 0, 0) };
        _laserBrush = new SliderRow("Brush", 1, 20, 1, v => $"{v:0}°", v => Change(o => o.LaserBrush = (int)v));
        _laserDrawOptions.Children.Add(_laserBrush.View);
        _laserDrawOptions.Children.Add(Parts.Text("Fades after"));
        _laserFade = new Seg<int>(f => Change(o => o.LaserFadeS = f), false,
            ("Never", 0), ("5 s", 5), ("10 s", 10), ("30 s", 30), ("60 s", 60));
        _laserFade.View.Margin = new Thickness(0, 6, 0, 2);
        _laserDrawOptions.Children.Add(_laserFade.View);
        _laserClearOnRatchet = Parts.Toggle("Clear on ratchet", () => Change(o => o.LaserClearOnRatchet = !o.LaserClearOnRatchet), "A ratchet press clears the drawing.");
        _laserDrawOptions.Children.Add(_laserClearOnRatchet);
        _laserDrawOptions.Children.Add(Parts.Text("Strokes glow, older ones darker. Nothing is painted while a ratchet is held. Turning Draw mode off clears the drawing. A Clear displays mapping on the phone (Other targets) clears every laser pointer at once.", "Hint"));
        lb.Children.Add(_laserDrawOptions);
        var clear = Parts.Button("Clear", () => { if (Picked is { } id) _app.ClearOverlay(id); }, glyph: "");
        clear.Margin = new Thickness(0, 8, 0, 0);
        clear.HorizontalAlignment = HorizontalAlignment.Left;
        lb.Children.Add(clear);
        _laserLook = laser;
        _lookTab.Children.Add(laser);

        var (colors, cb) = Parts.Card("Colours");
        cb.Children.Add(_colors);
        var defaults = Parts.Button("Default colours", () => Change(o => o.Colors.Clear()), glyph: "");
        defaults.Margin = new Thickness(0, 8, 0, 0);
        cb.Children.Add(defaults);
        _lookTab.Children.Add(colors);

        // Turning tab
        var (tb, tbb) = Parts.Card("Turns with");
        _turnsWith = new Seg<OverlayTurnsWith>(t => Change(o => o.TurnsWith = t), false,
            ("Phone", OverlayTurnsWith.Phone), ("Camera", OverlayTurnsWith.Camera), ("Trackball", OverlayTurnsWith.Trackball));
        _turnsHint = Parts.Text("", "Hint");
        _rws = Parts.Toggle("As far as the game's camera", () => Change(o => o.TrackballRws = !o.TrackballRws));
        _rws.Margin = new Thickness(20, 5, 0, 5);
        _spin = new SliderRow("Spin", 0.1, 10, 0.1, v => $"× {v:0.0}", v => Change(o => o.TrackballSpin = (float)v));
        _spin.View.Margin = new Thickness(20, 4, 0, 4);
        tbb.Children.Add(_turnsWith.View);
        tbb.Children.Add(_turnsHint);
        tbb.Children.Add(_rws);
        tbb.Children.Add(_spin.View);
        _turnTab.Children.Add(tb);

        var (pose, pob) = Parts.Card("Pose");
        _gravity = Parts.Toggle("Gravity", () => Change(o => o.Gravity = !o.Gravity), "Tilt held to the world, so it never drifts.");
        _compass = Parts.Toggle("Compass", () => Change(o => o.Compass = !o.Compass), "Heading held to where the phone faced.");
        _compass.Margin = new Thickness(20, 5, 0, 5);
        _ease = Parts.Toggle("Ease back to straight", () => Change(o => o.Ease = !o.Ease));
        pob.Children.Add(_gravity);
        pob.Children.Add(_compass);
        _gravityDivider = Parts.Divider();
        pob.Children.Add(_gravityDivider);
        pob.Children.Add(_ease);
        _easeOptions = new StackPanel { Margin = new Thickness(20, 0, 0, 0) };
        _easeWhen = new Seg<bool>(r => Change(o => o.EaseOnRatchet = r), false, ("On ratchet", true), ("When the phone stops", false));
        _easeOptions.Children.Add(_easeWhen.View);
        _headingOnly = Parts.Toggle("Heading only", () => Change(o => o.EaseHeadingOnly = !o.EaseHeadingOnly));
        _easeOptions.Children.Add(_headingOnly);
        _delay = new SliderRow("Delay", 0, 3000, 50, v => $"{v:0} ms", v => Change(o => o.EaseDelayMs = (int)v));
        _easeOptions.Children.Add(_delay.View);
        _easeBy = new Seg<bool>(b => Change(o => o.EaseBySpeed = b), false, ("By time", false), ("By speed", true));
        _easeBy.View.Margin = new Thickness(0, 8, 0, 4);
        _easeOptions.Children.Add(_easeBy.View);
        _seconds = new SliderRow("Back in", 0.1, 5, 0.1, v => $"{v:0.0} s", v => Change(o => o.EaseSeconds = (float)v));
        _speed = new SliderRow("Speed", 10, 720, 10, v => $"{v:0} °/s", v => Change(o => o.EaseSpeed = (int)v));
        _easeOptions.Children.Add(_seconds.View);
        _easeOptions.Children.Add(_speed.View);
        pob.Children.Add(_easeOptions);
        _poseCard = pose;
        _turnTab.Children.Add(pose);

        var (amount, ab) = Parts.Card("Amount");
        _scale = new SliderRow("Turn amount", 0.5, 3, 0.1, v => $"× {v:0.0}", v => Change(o => o.MotionScale = (float)v));
        ab.Children.Add(_scale.View);
        _scaleDivider = Parts.Divider();
        ab.Children.Add(_scaleDivider);
        ab.Children.Add(Parts.Text("Hold the phone the way you play and press Level: held that way, it shows face-on, its screen flat toward you.", "Hint"));
        ab.Children.Add(Parts.Button("Level", () => { if (Picked is { } id) _app.LevelOverlay(id); }, glyph: ""));
        _turnTab.Children.Add(amount);

        var remove = Parts.Button("Remove this overlay", Remove, glyph: "");
        remove.Margin = new Thickness(0, 4, 0, 0);
        remove.SetResourceReference(Control.ForegroundProperty, "DangerBrush");
        _settings.Children.Add(remove);
        _tabs.Set(0);
        ShowTab();
    }

    private const string AxonHint = "Seen from 30° round and 30° above with the world's axes (right red, up green, toward you blue), as the laser pointer is, instead of face-on.";

    /// <summary>Meshes.MaxSubdivision in the tray app; the icosphere's finest level.</summary>
    private const int MaxSubdivision = 3;

    private void ShowTab()
    {
        Parts.Show(_windowTab, _tab == 0);
        Parts.Show(_lookTab, _tab == 1);
        Parts.Show(_turnTab, _tab == 2);
    }

    private void Remove()
    {
        if (Picked is not { } id || _app.OverlayOf(id) is not { } o) return;
        var r = MessageBox.Show(_window, $"Remove the {_app.OverlayName(o)} overlay and its settings?", _window.Title,
            MessageBoxButton.OKCancel, MessageBoxImage.Question);
        if (r != MessageBoxResult.OK) return;
        _app.SelectOverlay(null);
        _app.RemoveOverlay(id);
    }

    private void Change(Action<OverlaySettings> change)
    {
        if (_refreshing || Picked is not { } id) return;
        _app.ChangeOverlay(id, change);
    }

    public override void Refresh(CompanionView v)
    {
        if (_refreshing) return;
        _refreshing = true;
        try
        {
            var all = _app.Overlays;
            if (Picked == null || all.All(o => o.Id != Picked)) _app.SelectOverlay(all.Count > 0 ? all[0].Id : null);
            RebuildList(all);
            foreach (var (id, row, shown, sub) in _rows)
            {
                Parts.SetChecked(row, id == Picked);
                if (_app.OverlayOf(id) is { } o)
                {
                    Parts.SetChecked(shown, o.Shown);
                    sub.Text = (o.Shown ? (_app.OverlaysHidden ? "Hidden by Hide all" : "Shown") : "Not shown") + " · " + BackgroundName(o.Background);
                }
            }
            Parts.SetChecked(_edit, _app.OverlayEdit);
            Parts.SetChecked(_hideAll, _app.OverlaysHidden);
            _edit.ToolTip = "Drag the overlay windows to move and resize them. Click where they overlap to pick the next one." + Keys(OverlayAction.Edit);
            _hideAll.ToolTip = "Closes every overlay window; each keeps its own Show for when this is off." + Keys(OverlayAction.HideAll);
            _levelAll.ToolTip = "Held the way it is now, every overlay shows the phone face-on." + Keys(OverlayAction.LevelAll);
            foreach (var s in _shortcuts) s.Refresh();
            var picked = Picked == null ? null : _app.OverlayOf(Picked);
            Parts.Show(_settings, picked != null);
            Parts.Show(_empty, picked == null);
            if (picked != null) ShowSettings(picked);
        }
        finally
        {
            _refreshing = false;
        }
    }

    private string Keys(OverlayAction a) => _app.Shortcuts.Get(a) is { Length: > 0 } k ? $" ({k})" : "";

    private static string BackgroundName(OverlayBackground b) => b switch
    {
        OverlayBackground.Green => "Green",
        OverlayBackground.Dark => "Dark",
        _ => "Transparent",
    };

    private void RebuildList(IReadOnlyList<OverlaySettings> all)
    {
        var key = string.Join("|", all.Select(o => $"{o.Id}{o.Kind}{_app.OverlayName(o)}"));
        if (key == _listKey) return;
        _listKey = key;
        _list.Children.Clear();
        _rows.Clear();
        foreach (var o in all)
        {
            var id = o.Id;
            var g = new Grid();
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var name = Parts.Text(_app.OverlayName(o));
            var sub = Parts.Text("", "Sub");
            sub.Margin = new Thickness(0, 1, 0, 0);
            sub.FontSize = 11.5;
            g.Children.Add(Parts.Column(name, sub));
            var shown = new CheckBox { Margin = new Thickness(8, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center, ToolTip = "Show" };
            shown.Click += (_, e) =>
            {
                _app.SetOverlayShown(id, !(_app.OverlayOf(id)?.Shown ?? false));
                e.Handled = true; // the row would select this overlay too
            };
            Grid.SetColumn(shown, 1);
            g.Children.Add(shown);
            var row = new RadioButton { Style = Parts.Style("ListRow"), GroupName = "overlays", Content = g };
            row.Click += (_, _) =>
            {
                _app.SelectOverlay(id);
                _window.RefreshNow();
            };
            _list.Children.Add(row);
            _rows.Add((id, row, shown, sub));
        }
    }

    private void ShowSettings(OverlaySettings o)
    {
        Parts.SetChecked(_shown, o.Shown);
        _background.Set(o.Background);
        _opacity.Set(MathF.Round(o.Opacity * 100f));
        _quality.Set(o.Quality);
        _fps.Set(o.MaxFps is 60 or 30 ? o.MaxFps : 0);

        Parts.Show(_phoneLook, o.Kind == OverlayKind.Phone);
        Parts.Show(_shapeLook, o.Kind == OverlayKind.Shape);
        Parts.Show(_diskLook, o.Kind == OverlayKind.Disk);
        Parts.Show(_laserLook, o.Kind == OverlayKind.Laser);
        Parts.SetChecked(_phoneAxon, o.Axonometric);
        Parts.SetChecked(_shapeAxon, o.Axonometric);
        Parts.SetChecked(_laserAxon, o.LaserAxonometric);
        Parts.SetChecked(_laserGrid, o.LaserGrid);
        Parts.SetChecked(_laserAxes, o.LaserAxes);
        _laserTrail.Set(o.LaserTrailMs);
        Parts.SetChecked(_laserDraw, o.LaserDraw);
        Parts.Show(_laserDrawOptions, o.LaserDraw);
        _laserBrush.Set(o.LaserBrush);
        _laserFade.Set(o.LaserFadeS);
        Parts.SetChecked(_laserClearOnRatchet, o.LaserClearOnRatchet);
        Parts.SetChecked(_dots, o.FingerDots);
        Parts.SetChecked(_trails, o.FingerTrails);
        _handles.Set(o.Handles);
        _handleSource.Set(o.HandleSource);
        _shape.Set(o.Shape);
        Parts.Show(_subdivision.View, o.Shape == OverlayShape.Icosphere);
        _subdivision.Set(o.Subdivision);
        Parts.SetChecked(_smooth, o.Smooth);
        Parts.Show(_smooth, o.Shape is not (OverlayShape.Plane or OverlayShape.Tetrahedron or OverlayShape.Camera));
        Parts.SetChecked(_hiddenEdges, o.HiddenEdges);
        Parts.SetChecked(_fillFaces, o.FillFaces);
        Parts.Show(_fillFaces, o.Shape != OverlayShape.Gimbal);
        Parts.SetChecked(_equator, o.Equator);
        Parts.Show(_equator, o.Shape == OverlayShape.Globe);
        Parts.SetChecked(_screenMark, o.ScreenMark);
        Parts.SetChecked(_topMark, o.TopMark);
        _diskRange.Set(o.DiskRange);
        Parts.SetChecked(_crosshair, o.Crosshair);
        ShowColors(o);

        _turnsWith.Set(o.TurnsWith);
        _turnsHint.Text = o.TurnsWith switch
        {
            OverlayTurnsWith.Camera => "Where the game's camera points: it turns left and right and up and down as the camera does, never rolling, with the gyro and every other camera move (touchpad, buttons, controllers, keys). A ratchet stops it, as it stops the camera.",
            OverlayTurnsWith.Trackball => "Like a ball under your hand: still against the world while the gyro aims, so it rolls the other way; every other camera move rolls it too.",
            _ => "How you hold the phone.",
        };
        Parts.SetChecked(_rws, o.TrackballRws);
        Parts.Show(_rws, o.FollowsCamera);
        _spin.Set(o.TrackballSpin);
        Parts.Show(_spin.View, o.FollowsCamera && !o.TrackballRws);
        // A trackball never eases back: every roll is a camera move. Following the camera: no gravity or compass.
        Parts.Show(_poseCard, !o.IsTrackball);
        Parts.Show(_gravity, !o.FollowsCamera);
        Parts.Show(_gravityDivider, !o.FollowsCamera);
        Parts.SetChecked(_gravity, o.Gravity);
        Parts.SetChecked(_compass, o.Compass);
        Parts.Show(_compass, o.Gravity && !o.FollowsCamera);
        // With gravity only the heading eases back.
        Parts.SetChecked(_headingOnly, o.EaseHeadingOnly);
        Parts.Show(_headingOnly, !o.Gravity || o.FollowsCamera);
        Parts.SetChecked(_ease, o.Ease);
        _ease.IsEnabled = !o.NeedsCompass;
        Parts.Show(_easeOptions, o.Ease && !o.NeedsCompass);
        _easeWhen.Set(o.EaseOnRatchet);
        _delay.Set(o.EaseDelayMs);
        _easeBy.Set(o.EaseBySpeed);
        _seconds.Set(o.EaseSeconds);
        _speed.Set(o.EaseSpeed);
        Parts.Show(_seconds.View, !o.EaseBySpeed);
        Parts.Show(_speed.View, o.EaseBySpeed);
        _scale.Set(o.MotionScale);
        Parts.Show(_scale.View, !o.FollowsCamera);
        Parts.Show(_scaleDivider, !o.FollowsCamera);
    }

    /// <summary>One swatch per colour the picked window's kind (and shape) draws with; a click opens Windows' colour picker.</summary>
    private void ShowColors(OverlaySettings o)
    {
        var list = OverlaySettings.ColorsOf(o.Kind, o.Kind == OverlayKind.Shape ? o.Shape : null);
        var key = string.Join("|", list.Select(c => c.Key)) + o.Kind;
        if (key != _colorsKey)
        {
            _colorsKey = key;
            _colors.Children.Clear();
            _swatches.Clear();
            var wrap = new WrapPanel();
            foreach (var c in list)
            {
                var swatch = new Border
                {
                    Width = 34,
                    Height = 22,
                    CornerRadius = new CornerRadius(5),
                    BorderThickness = new Thickness(1),
                    Margin = new Thickness(0, 0, 10, 0),
                };
                swatch.SetResourceReference(Border.BorderBrushProperty, "LineBrush");
                var colorKey = c.Key;
                var row = new Button { Margin = new Thickness(0, 0, 8, 8), Padding = new Thickness(8, 5, 12, 5), MinWidth = 200, HorizontalContentAlignment = HorizontalAlignment.Left };
                var content = new StackPanel { Orientation = Orientation.Horizontal };
                content.Children.Add(swatch);
                content.Children.Add(new TextBlock { Text = c.Label, VerticalAlignment = VerticalAlignment.Center });
                row.Content = content;
                row.Click += (_, _) => PickColor(colorKey);
                wrap.Children.Add(row);
                _swatches.Add((c.Key, swatch));
            }
            _colors.Children.Add(wrap);
        }
        foreach (var (k, swatch) in _swatches)
        {
            int argb = o.Color(k);
            swatch.Background = new SolidColorBrush(Color.FromRgb((byte)(argb >> 16), (byte)(argb >> 8), (byte)argb));
        }
    }

    private void PickColor(string key)
    {
        if (Picked is not { } id || _app.OverlayOf(id) is not { } o) return;
        if (_app.PickColor(o.Color(key), _window.Handle) is { } argb) _app.ChangeOverlay(id, x => x.Colors[key] = argb);
    }
}
