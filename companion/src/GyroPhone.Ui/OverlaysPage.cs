using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using GyroPhone.Host.Settings;

namespace GyroPhone.Ui;

/// <summary>
/// Overlays: the switches for all of them (Edit, Hide all, Level all) at the top; the overlay windows
/// as a plain list on the left (each with its Show switch, then Add and the shortcuts); the picked
/// one's settings on the right in tabs as on the phone: Window, Look, Draw, Paint, Colours and Turning (Draw, Paint and
/// Colours only for the pointer). Nothing scrolls but the page.
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
    /// <summary>How many of <see cref="_addKinds"/>' buttons are the built-in kinds (the ★ presets follow).</summary>
    private int _kindButtons;
    private string _presetKey = "\0";
    private readonly List<ShortcutBox> _shortcuts = [];
    private readonly CheckBox _restOn, _restSilent;
    private readonly StackPanel _restOptions = new();
    private readonly SliderRow _restFlat, _restStill, _restQuiet, _restTo, _restOver;
    private readonly TextBlock _restLive;

    // Right
    private readonly StackPanel _settings = new();
    private readonly TextBlock _empty;
    /// <summary>The picked overlay's label, and the name it has without one greyed behind it.</summary>
    private readonly TextBox _label;
    private readonly TextBlock _labelHint;
    private readonly Seg<int> _tabs;
    private int _tab;
    /// <summary>Tabs as on the phone: Window (PC only), Look, Paint, Snake, Colours and Turning; Paint, Snake and Colours only for the pointer.</summary>
    private const int WindowTab = 0, LookTab = 1, PaintTab = 2, SnakeTab = 3, ColoursTab = 4, TurnTab = 5;
    private readonly StackPanel _windowTab, _lookTab, _paintTab, _snakeTab, _coloursTab, _turnTab;
    /// <summary>The Colours card: in the Look tab, or the pointer's own Colours tab.</summary>
    private readonly Border _coloursCard;

    // Window
    private readonly CheckBox _shown;
    private readonly Seg<OverlayBackground> _background;
    private readonly SliderRow _opacity;
    private readonly Seg<OverlayQuality> _quality;
    private readonly Seg<int> _fps;

    // Look
    private readonly Border _phoneLook, _shapeLook, _diskLook, _laserLook, _ringLook, _graphLook;
    private readonly Seg<int> _graphRange, _graphSeconds;
    private readonly CheckBox _laserAxon, _laserSphereTurns, _laserGrid, _laserAxes, _laserGimbal, _laserDraw, _laserClearOnRatchet, _laserSnakeClearOnRatchet;
    private readonly CheckBox _laserPerspective, _laserSnake, _laserOutline;
    private readonly StackPanel _laserSnakeOptions;
    private readonly SliderRow _laserSnakeLength, _laserSnakeBrush, _laserSnakeTaper, _laserSnakeFade, _laserSnakeGlow, _laserSoftness, _laserGlow;
    private readonly Seg<bool> _laserColourBy;
    private readonly Seg<int> _laserPaintDetail;
    private readonly StackPanel _laserDrawLook, _paintShots, _paintFire, _paintAim, _shotOptions, _paintAndSnake;
    private readonly Seg<int> _shotBeat, _shootsLike;
    private readonly SliderRow _shotRpm;
    private readonly UIElement _shootsLikeTitle;
    private readonly StackPanel _spreadOptions;
    private readonly SphereLookRows _paintLook, _snakeLook;
    private readonly StackPanel _snakeZip, _zipOptions, _zipTimed, _snakeShots, _snakeLookPanel, _laserFacesPanel;
    private readonly CheckBox _zipOn, _zipStops, _zipRatchet;
    private readonly TextBlock _zipSpeedHint;
    private readonly Seg<bool> _zipBy, _snakeColourBy;
    private readonly SliderRow _zipSeconds, _zipSpeed, _snakeFireBrush, _snakeAimBrush;
    private readonly Seg<int> _laserShape;
    private readonly StackPanel _chOptions;
    private readonly Seg<int> _chShape;
    private readonly SliderRow _chSize, _chGap, _chThick;
    private readonly CheckBox _chDot, _chOutline;
    private readonly Seg<int> _laserFaces;
    private readonly TextBlock _laserRifleHint;
    private readonly SliderRow _laserBrush, _laserLineSmoothing;
    private readonly CheckBox _laserSmoothColours;
    private readonly TextBlock _laserMovesHint;
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
    private readonly StackPanel _trackballAxes;
    private readonly Seg<int>[] _tbFrom = new Seg<int>[3];
    private readonly CheckBox[] _tbInvert = new CheckBox[3];
    private readonly Seg<bool> _tbOwnAxes;
    private readonly CheckBox[] _tbStop = new CheckBox[3];
    private readonly ButtonPick _firePick, _aimPick;
    private readonly SliderRow _fireBrush, _fireGlow, _aimSize, _aimBrush, _aimGlow;
    private readonly StackPanel _fireOptions, _aimOptions, _kickOptions;
    private readonly Seg<int> _kickMode;
    private readonly Seg<bool> _kickLines;
    private readonly SliderRow _kickScale, _kickRpm;
    private readonly UIElement _kickTitle, _kickHint, _rifleKickHint, _recoilLikeTitle;
    private readonly StackPanel _gunOptions;
    private readonly Seg<int> _gunPick, _recoilLike;
    private readonly CheckBox _gunLowered, _gunScope;
    private readonly SliderRow _gunHipPull, _gunHipRecover;
    private readonly CheckBox _muzzleFlash, _laserSpread, _laserBloom, _laserShotDraw;
    private readonly SliderRow _gunSpread;
    private readonly Seg<int> _hitLook;
    private readonly CheckBox _laserFireOnly;
    private readonly CheckBox[] _tbEase = new CheckBox[3];
    private readonly StackPanel[] _tbEaseOptions = new StackPanel[3];
    private readonly Seg<bool>[] _tbEaseWhen = new Seg<bool>[3];
    private readonly Seg<bool>[] _tbEaseBy = new Seg<bool>[3];
    private readonly SliderRow[] _tbEaseDelay = new SliderRow[3];
    private readonly SliderRow[] _tbEaseSeconds = new SliderRow[3];
    private readonly SliderRow[] _tbEaseSpeed = new SliderRow[3];
    private readonly TextBlock[] _tbFromTitle = new TextBlock[3];
    private readonly Button _tbAsCamera;
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
        _kindButtons = _addKinds.Children.Count;
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

        // "Fade when phone rests", for every overlay.
        var (restCard, restBody) = Parts.Card("Fade when phone rests");
        restCard.Margin = new Thickness(0, 0, 0, 12);
        _restOn = Parts.Toggle("Fade overlays", () => _app.ChangeRestFade(r => r.On = !r.On),
            "While the phone lies still at its Level with nothing pressed. Any touch or movement brings them back at once.");
        restBody.Children.Add(_restOn);
        restBody.Children.Add(_restOptions);
        _restFlat = new SliderRow("Flat within", 1, 45, 1, v => $"{v:0}°", v => _app.ChangeRestFade(r => r.FlatWithinDeg = (float)v), stacked: true);
        _restOptions.Children.Add(_restFlat.View);
        _restStill = new SliderRow("Still below", 0.1, 5, 0.1, v => $"{v:0.0}°/s", v => _app.ChangeRestFade(r => r.StillBelowDps = (float)v), stacked: true);
        _restOptions.Children.Add(_restStill.View);
        _restLive = Parts.Text("", "Hint");
        _restLive.Margin = new Thickness(0, 0, 0, 4);
        _restOptions.Children.Add(_restLive);
        _restQuiet = new SliderRow("Quiet for", 0.5, 30, 0.5, v => $"{v:0.0} s", v => _app.ChangeRestFade(r => r.QuietMs = (int)Math.Round(v * 1000)), stacked: true);
        _restOptions.Children.Add(_restQuiet.View);
        _restTo = new SliderRow("Fade to", 5, 100, 5, v => $"{v:0}%", v => _app.ChangeRestFade(r => r.FadeTo = (float)v / 100f), stacked: true);
        _restOptions.Children.Add(_restTo.View);
        _restOver = new SliderRow("Fade over", 0, 3, 0.1, v => $"{v:0.0} s", v => _app.ChangeRestFade(r => r.FadeMs = (int)Math.Round(v * 1000)), stacked: true);
        _restOptions.Children.Add(_restOver.View);
        _restSilent = Parts.Toggle("Also when the phone stops sending", () => _app.ChangeRestFade(r => r.WhenSilent = !r.WhenSilent),
            "Screen off, app closed or link stopped, for the quiet time.");
        _restSilent.Margin = new Thickness(0, 6, 0, 0);
        _restOptions.Children.Add(_restSilent);
        left.Children.Add(restCard);

        // Right: the picked overlay's settings.
        var right = new StackPanel();
        Grid.SetRow(right, 1);
        Grid.SetColumn(right, 2);
        _root.Children.Add(right);
        _empty = Parts.Text("No overlays yet. Press \"Add overlay\" to put the phone, a 3D shape, an attitude indicator, a bubble disk, a pointer or the game's FPS over your game.", "Sub");
        right.Children.Add(_empty);
        right.Children.Add(_settings);
        // Label: names it in the lists and the tray, never drawn in the overlay.
        _label = new TextBox { FontSize = 18, MaxLength = 40, Padding = new Thickness(4, 2, 4, 2), ToolTip = "Its name in the lists and the tray menu. The overlay itself never shows it." };
        _labelHint = new TextBlock { FontSize = 18, Margin = new Thickness(8, 3, 0, 0), IsHitTestVisible = false };
        _labelHint.SetResourceReference(TextBlock.ForegroundProperty, "FaintBrush");
        _label.TextChanged += (_, _) =>
        {
            Parts.Show(_labelHint, _label.Text.Length == 0);
            if (_refreshing) return;
            var text = _label.Text;
            Change(o => o.Label = text);
            _window.RefreshNow();
        };
        var labelBox = new Grid { Margin = new Thickness(0, 0, 0, 10), MaxWidth = 360, HorizontalAlignment = HorizontalAlignment.Left, MinWidth = 220 };
        labelBox.Children.Add(_label);
        labelBox.Children.Add(_labelHint);
        _settings.Children.Add(labelBox);
        var tabBar = new Border { BorderThickness = new Thickness(0, 0, 0, 1), Margin = new Thickness(0, 0, 0, 14) };
        tabBar.SetResourceReference(Border.BorderBrushProperty, "LineBrush");
        _tabs = new Seg<int>(t =>
        {
            _tab = t;
            ShowTab();
        }, true, ("Window", WindowTab), ("Look", LookTab), ("Paint", PaintTab), ("Snake", SnakeTab), ("Colours", ColoursTab), ("Turning", TurnTab));
        // Tabs: the segmented buttons in the tab style.
        foreach (var rb in ((Panel)_tabs.View).Children.OfType<RadioButton>()) rb.Style = Parts.Style("Tab");
        tabBar.Child = _tabs.View;
        _settings.Children.Add(tabBar);
        _windowTab = new StackPanel();
        _lookTab = new StackPanel();
        _paintTab = new StackPanel();
        _snakeTab = new StackPanel();
        _coloursTab = new StackPanel();
        _turnTab = new StackPanel();
        foreach (var t in new[] { _windowTab, _lookTab, _paintTab, _snakeTab, _coloursTab, _turnTab }) _settings.Children.Add(t);

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
        _fps = new Seg<int>(f => Change(o => o.MaxFps = f), false, ("Every screen refresh", 0), ("About 60 fps", 60), ("About 30 fps", 30));
        _fps.View.Margin = new Thickness(0, 6, 0, 2);
        db.Children.Add(_fps.View);
        db.Children.Add(Parts.Text("Every screen refresh is smoothest but costs more. About 60 and 30 draw every 2nd, 3rd… refresh, whichever is nearest (72 fps on a 144 Hz screen, 55 on 165 Hz), so the motion stays even. Draw times are in companion.log with Overlay timing log on, and in the Debug window (Performance page, Debug).", "Hint"));
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

        var (ring, rgb) = Parts.Card("Ratchet ring");
        rgb.Children.Add(Parts.Text("Yaw, pitch and roll arcs, lit while that axis moves the mouse and dim while a ratchet (or the gyro mode) pauses it.", "Hint"));
        _ringLook = ring;
        _lookTab.Children.Add(ring);

        var (graph, gb) = Parts.Card("Speed graph");
        _graphRange = new Seg<int>(v => Change(o => o.GraphRange = v), true, ("±180°/s", 180), ("±360°/s", 360), ("±720°/s", 720), ("±1440°/s", 1440));
        gb.Children.Add(_graphRange.View);
        _graphSeconds = new Seg<int>(v => Change(o => o.GraphSeconds = v), true, ("2 s", 2), ("3 s", 3), ("5 s", 5), ("8 s", 8));
        gb.Children.Add(_graphSeconds.View);
        gb.Children.Add(Parts.Text("Yaw, pitch and roll speed; shaded while a ratchet is held.", "Hint"));
        _graphLook = graph;
        _lookTab.Children.Add(graph);

        var (laser, lb) = Parts.Card("Pointer");
        lb.Children.Add(Parts.Text("A see-through sphere seen from above and to the side. The laser at its centre aims along the phone's roll axis (out of its top edge, into the screen at rest) onto the inside; the tick at the dot shows which way the phone's screen faces. The beam dims while a ratchet is held.", "Hint"));
        _laserShape = new Seg<int>(k => Change(o => { o.LaserRifle = k == 1; o.LaserCrosshair = k == 2; }), false, ("Sphere", 0), ("Gun", 1), ("Crosshair", 2));
        _laserShape.View.Margin = new Thickness(0, 6, 0, 2);
        lb.Children.Add(_laserShape.View);
        _laserRifleHint = Parts.Text("A gun aims instead: no sphere or beam, and Draw mode still paints where it points. Best with Axonometric view (face-on you look along the barrel). Each gun has its own model, recoil, fire rate and muzzle flash.", "Hint");
        lb.Children.Add(_laserRifleHint);
        _gunOptions = new StackPanel { Margin = new Thickness(20, 0, 0, 4) };
        _gunPick = new Seg<int>(k => Change(o => o.LaserGun = k), true, [.. GyroPhone.Host.Guns.All.Select((g, i) => (g.Name, i))]);
        _gunPick.View.Margin = new Thickness(0, 6, 0, 2);
        _gunOptions.Children.Add(_gunPick.View);
        _gunLowered = Parts.Toggle("Lowered when idle", () => Change(o => o.GunLowered = !o.GunLowered), "It rests pitched down and comes up straight while you aim or fire.");
        _gunOptions.Children.Add(_gunLowered);
        _gunHipPull = new SliderRow("Hip fire pull", 0, 1, 0.05, v => $"{v * 100:0}%", v => Change(o => o.GunHipPull = (float)v));
        _gunOptions.Children.Add(_gunHipPull.View);
        _gunHipRecover = new SliderRow("Hip fire recovery", 0, 2000, 10, v => $"{v:0} ms", v => Change(o => o.GunHipRecoverMs = (int)v));
        _gunOptions.Children.Add(_gunHipRecover.View);
        _gunScope = Parts.Toggle("Scope while aiming", () => Change(o => o.GunScope = !o.GunScope), "Aiming, a round scope grows onto it.");
        _gunOptions.Children.Add(_gunScope);
        lb.Children.Add(_gunOptions);
        _chOptions = new StackPanel { Margin = new Thickness(20, 0, 0, 4) };
        _chOptions.Children.Add(Parts.Text("A crosshair instead: no sphere or beam, just a crosshair lying on the sphere where the laser lands, facing its centre, so it squashes toward the edge. It turns about the beam as the Trackball's Roll row maps it (Lock keeps it upright, Invert turns it the other way). Draw mode still paints where it points.", "Hint"));
        _chShape = new Seg<int>(k => Change(o => o.CrosshairShape = k), true,
            [.. GyroPhone.Host.LaserTrace.Crosshairs.Select((n, i) => (n, i))]);
        _chShape.View.Margin = new Thickness(0, 6, 0, 2);
        _chOptions.Children.Add(_chShape.View);
        _chSize = new SliderRow("Size", 2, 30, 1, v => $"{v:0}°", v => Change(o => o.CrosshairSize = (int)v));
        _chGap = new SliderRow("Gap", 0, 10, 1, v => $"{v:0}°", v => Change(o => o.CrosshairGap = (int)v));
        _chThick = new SliderRow("Thickness", 0.5, 6, 0.5, v => $"{v:0.0}", v => Change(o => o.CrosshairThickness = (float)v));
        _chOptions.Children.Add(_chSize.View);
        _chOptions.Children.Add(_chGap.View);
        _chOptions.Children.Add(_chThick.View);
        _chDot = Parts.Toggle("Centre dot", () => Change(o => o.CrosshairDot = !o.CrosshairDot));
        _chOutline = Parts.Toggle("Outline", () => Change(o => o.CrosshairOutline = !o.CrosshairOutline), "A dark edge so it shows on any background.");
        _chOptions.Children.Add(_chDot);
        _chOptions.Children.Add(_chOutline);
        lb.Children.Add(_chOptions);
        // Turning tab (pointer as a trackball): which way of it the pointer faces.
        _laserFacesPanel = new StackPanel { Margin = new Thickness(20, 4, 0, 4) };
        _laserFacesPanel.Children.Add(Parts.Text("Pointer faces (as a trackball)"));
        _laserFaces = new Seg<int>(f => Change(o => o.LaserFaces = f), false,
            ("−Z (into the screen)", 0), ("+Z", 1), ("+X", 2), ("−X", 3), ("+Y", 4), ("−Y", 5));
        _laserFaces.View.Margin = new Thickness(0, 6, 0, 2);
        _laserFacesPanel.Children.Add(_laserFaces.View);

        _laserAxon = Parts.Toggle("Axonometric view", () => Change(o => o.LaserAxonometric = !o.LaserAxonometric), "Seen from 30° round and 30° above, so a beam into the far side shows as a line. Off, face-on: a beam straight in is a dot.");
        lb.Children.Add(_laserAxon);
        _laserPerspective = Parts.Toggle("Perspective", () => Change(o => o.Perspective = !o.Perspective), "Nearer parts show bigger, as a camera sees them. Off: flat, every part the same size wherever it is.");
        lb.Children.Add(_laserPerspective);
        _laserSphereTurns = Parts.Toggle("Fixed pointer mode", () => Change(o => o.LaserSphereTurns = !o.LaserSphereTurns), "Off: the beam turns inside a still sphere. On: the beam stays pointing ahead and the sphere, with its grid, axes and drawing, turns under it.");
        lb.Children.Add(_laserSphereTurns);
        _laserGrid = Parts.Toggle("Grid every 15°", () => Change(o => o.LaserGrid = !o.LaserGrid));
        lb.Children.Add(_laserGrid);
        _laserAxes = Parts.Toggle("Axes (pitch X red, yaw Y green, roll Z blue)", () => Change(o => o.LaserAxes = !o.LaserAxes), "They turn with the pointer, R along its beam.");
        lb.Children.Add(_laserAxes);

        // Fire display and Aim display: the buttons, sent from the phone or pressed on the PC's controller with
        // "Use PC controller for gyro ratchet" on. Here how the gun and crosshair take them; the paint's in the Paint tab.
        _firePick = new ButtonPick("Fire display", "While this button is sent from the phone (or pressed on the PC's controller with \"Use PC controller for gyro ratchet\" on) the gun and crosshair kick and flash with each shot, and Draw mode paints in the Fire colours. Click to pick another, or None.", k => Change(o => o.LaserFire = k));
        lb.Children.Add(_firePick.View);
        _fireOptions = new StackPanel { Margin = new Thickness(20, 0, 0, 0) };
        _kickTitle = Parts.Text("Kick");
        _kickHint = Parts.Text("Each shot the crosshair jumps bigger and bounces back with the recoil of the gun picked here. Once: one kick per press. Auto rifle: keeps kicking while the button is held. Each gun keeps its own kick size and fire rate.", "Hint");
        _rifleKickHint = Parts.Text("Each shot the gun kicks back toward you, its muzzle jumping up, and springs back with its own recoil. Once: one kick per press. Auto rifle: keeps kicking while the button is held. Each gun keeps its own kick size and fire rate.", "Hint");
        _fireOptions.Children.Add(_kickTitle);
        _fireOptions.Children.Add(_kickHint);
        _fireOptions.Children.Add(_rifleKickHint);
        _recoilLikeTitle = Parts.Text("Recoil like", "Sub");
        _fireOptions.Children.Add(_recoilLikeTitle);
        _recoilLike = new Seg<int>(k => Change(o => o.LaserGun = k), true, [.. GyroPhone.Host.Guns.All.Select((g, i) => (g.Name, i))]);
        _fireOptions.Children.Add(_recoilLike.View);
        _kickMode = new Seg<int>(k => Change(o => o.CrosshairKick = k), false,
            [.. GyroPhone.Host.CrosshairKick.Modes.Select((n, i) => (n, i))]);
        _kickMode.View.Margin = new Thickness(0, 6, 0, 2);
        _fireOptions.Children.Add(_kickMode.View);
        _kickOptions = new StackPanel();
        _kickScale = new SliderRow("Kick size (this gun)", 0.05, 2, 0.05, v => $"{v * 100:0}%", v => Change(o => (o.GunKick ??= [])[o.Gun().Key] = (float)v));
        _kickRpm = new SliderRow("Fire rate (this gun)", GyroPhone.Host.CrosshairKick.RpmMin, 1200, 5, v => $"{v:0} a minute", v => Change(o => (o.GunRpm ??= [])[o.Gun().Key] = (int)v));
        _kickLines = new Seg<bool>(b => Change(o => o.CrosshairKickLinesOnly = b), false, ("Lines only", true), ("Evenly, thickness too", false));
        _kickLines.View.Margin = new Thickness(0, 6, 0, 2);
        _kickOptions.Children.Add(_kickScale.View);
        _kickOptions.Children.Add(_kickRpm.View);
        _kickOptions.Children.Add(_kickLines.View);
        _fireOptions.Children.Add(_kickOptions);
        _muzzleFlash = Parts.Toggle("Muzzle flash", () => Change(o => o.MuzzleFlash = !o.MuzzleFlash), "Each shot a flash in time with the kick: white, cooling to the Flash colour (yellow), then Flash end (orange). The crosshair glows and takes those colours, then its own again; the gun's muzzle blasts flame as wide as its muzzle and as long as its calibre (pistol shortest, shotgun and sniper longest).");
        _fireOptions.Children.Add(_muzzleFlash);
        _shootsLikeTitle = Parts.Text("Shoots like", "Sub");
        _fireOptions.Children.Add(_shootsLikeTitle);
        _shootsLike = new Seg<int>(k => Change(o => o.LaserGun = k), true, [.. GyroPhone.Host.Guns.All.Select((g, i) => (g.Name, i))]);
        _fireOptions.Children.Add(_shootsLike.View);
        _laserSpread = Parts.Toggle("Simulate spread", () => Change(o => o.LaserSpread = !o.LaserSpread), "Each shot lands a little off the aim point, a random way, by up to the gun's spread; the crosshair or gun kicks toward it and springs back. Each gun keeps its own spread.");
        _fireOptions.Children.Add(_laserSpread);
        _spreadOptions = new StackPanel { Margin = new Thickness(20, 0, 0, 0) };
        _gunSpread = new SliderRow("Spread (this gun)", 0, 10, 0.1, v => $"{v:0.0}°", v => Change(o => (o.GunSpread ??= [])[o.Gun().Key] = (float)v));
        _spreadOptions.Children.Add(_gunSpread.View);
        _laserBloom = Parts.Toggle("Bloom", () => Change(o => o.LaserBloom = !o.LaserBloom), $"Shots fired in a row spread wider, up to twice over {GyroPhone.Host.CrosshairKick.BloomShots} shots; it settles half a second after you let go.");
        _spreadOptions.Children.Add(_laserBloom);
        _fireOptions.Children.Add(_spreadOptions);
        lb.Children.Add(_fireOptions);
        _aimPick = new ButtonPick("Aim display", "While this button is held the crosshair shrinks and turns laser red (its Crosshair aiming colour), the gun is shouldered with a scope and a laser sight, and Draw mode paints in the Aim colours. Firing wins over aiming. Click to pick another, or None.", k => Change(o => o.LaserAim = k));
        lb.Children.Add(_aimPick.View);
        _aimOptions = new StackPanel { Margin = new Thickness(20, 0, 0, 0) };
        _aimSize = new SliderRow("Crosshair while aiming", 0.2, 1, 0.05, v => $"{v * 100:0}%", v => Change(o => o.LaserAimSize = (float)v));
        _aimOptions.Children.Add(_aimSize.View);
        lb.Children.Add(_aimOptions);
        _laserGimbal = Parts.Toggle("Gimbal rings", () => Change(o => o.LaserGimbal = !o.LaserGimbal), "Yaw (green, outside), pitch (red) and roll (blue) rings turning with the pointer's axes, each round its own: the path the beam and crosshair take turning about it. A None axis's ring is faint and dashed, and an axis lights up while the gyro turns it.");
        lb.Children.Add(_laserGimbal);
        _laserLook = laser;
        _lookTab.Children.Add(laser);

        // Paint tab (pointer): Draw mode, its shots and aiming, how its paint looks, and what it shares with the Snake.
        var (paintCard, pab) = Parts.Card("Paint");
        _paintTab.Children.Add(paintCard);
        _laserDraw = Parts.Toggle("Draw mode", () => Change(o => o.LaserDraw = !o.LaserDraw), "The beam paints the inside of the sphere as you aim; the paint stays until you clear it.");
        pab.Children.Add(_laserDraw);
        _laserDrawOptions = new StackPanel { Margin = new Thickness(20, 0, 0, 0) };
        _laserBrush = new SliderRow("Brush", 1, 20, 1, v => $"{v:0}°", v => Change(o => o.LaserBrush = (int)v));
        _laserDrawOptions.Children.Add(_laserBrush.View);
        _laserClearOnRatchet = Parts.Toggle("Clear on ratchet", () => Change(o => o.LaserClearOnRatchet = !o.LaserClearOnRatchet), "A ratchet press clears the paint (the Snake has its own).");
        _laserDrawOptions.Children.Add(_laserClearOnRatchet);
        _laserDrawOptions.Children.Add(Parts.Text("The paint uncovers the sphere's own colours where it lands (the Moving colours, laid on the sphere as a normal map: right pink, left teal, up light green, down purple), or with Colour by the way it moved, the colour of each sweep's direction. Shots take the Fire colours and aiming the Aim colours, each with its own brush and glow (Shots and aiming below). Nothing is painted while a ratchet is held. Turning Draw mode off clears the drawing. A Clear displays mapping on the phone (Other targets) clears every pointer at once.", "Hint"));
        pab.Children.Add(_laserDrawOptions);

        // Paint from shots and aiming (the Fire and Aim display buttons are in the Look tab).
        _paintShots = new StackPanel();
        _paintShots.Children.Add(Parts.Text("Shots and aiming", "Sub"));
        _paintFire = new StackPanel();
        _fireBrush = new SliderRow("Fire brush", 1, 20, 1, v => $"{v:0}°", v => Change(o => { o.KeepSnakeLook(); o.LaserFireBrush = (int)v; }));
        _paintFire.Children.Add(_fireBrush.View);
        _fireGlow = new SliderRow("Fire glow", 0, 3, 0.05, v => $"{v * 100:0}%", v => Change(o => o.LaserFireGlow = (float)v));
        _paintFire.Children.Add(_fireGlow.View);
        _laserShotDraw = Parts.Toggle("Draw each shot", () => Change(o => o.LaserShotDraw = !o.LaserShotDraw), "Each shot marks a hit where it lands, in the Fire colours, as wide as the Fire brush (a shotgun's pellets half that). Firing then paints no Fire line. Hits stay until you clear the drawing.");
        _paintFire.Children.Add(_laserShotDraw);
        _shotOptions = new StackPanel { Margin = new Thickness(20, 0, 0, 0) };
        // The Sphere look has no Kick: its shots' beat here.
        _shotBeat = new Seg<int>(k => Change(o => o.CrosshairKick = k), false,
            [.. GyroPhone.Host.CrosshairKick.Modes.Select((n, i) => (n, i)).Skip(1)]);
        _shotBeat.View.Margin = new Thickness(0, 6, 0, 2);
        _shotOptions.Children.Add(_shotBeat.View);
        _shotRpm = new SliderRow("Fire rate (this gun)", GyroPhone.Host.CrosshairKick.RpmMin, 1200, 5, v => $"{v:0} a minute", v => Change(o => (o.GunRpm ??= [])[o.Gun().Key] = (int)v));
        _shotOptions.Children.Add(_shotRpm.View);
        _shotOptions.Children.Add(Parts.Text("Hit look", "Sub"));
        _hitLook = new Seg<int>(k => Change(o => o.LaserHitLook = k), false, [.. GyroPhone.Host.LaserTrace.HitLooks.Select((n, i) => (n, i))]);
        _shotOptions.Children.Add(_hitLook.View);
        _paintFire.Children.Add(_shotOptions);
        _paintShots.Children.Add(_paintFire);
        _paintAim = new StackPanel();
        _aimBrush = new SliderRow("Aim brush", 1, 20, 1, v => $"{v:0}°", v => Change(o => { o.KeepSnakeLook(); o.LaserAimBrush = (int)v; }));
        _paintAim.Children.Add(_aimBrush.View);
        _aimGlow = new SliderRow("Aim glow", 0, 3, 0.05, v => $"{v * 100:0}%", v => Change(o => o.LaserAimGlow = (float)v));
        _paintAim.Children.Add(_aimGlow.View);
        _paintShots.Children.Add(_paintAim);
        pab.Children.Add(_paintShots);

        // How Draw mode's paint looks (the Snake has its own in the Snake tab): changing one keeps the Snake as it was.
        _laserDrawLook = new StackPanel();
        _laserDrawLook.Children.Add(Parts.Text("Colour by", "Sub"));
        _laserColourBy = new Seg<bool>(w => Change(o => { o.KeepSnakeLook(); o.LaserColourByWay = w; }), true, ("Where it is", false), ("The way it moved", true));
        _laserDrawLook.Children.Add(_laserColourBy.View);
        _laserDrawLook.Children.Add(Parts.Text("Paint detail", "Sub"));
        _laserPaintDetail = new Seg<int>(k => Change(o => o.LaserPaintDetail = k), true, ("Low", 0), ("Normal", 1), ("High", 2));
        _laserDrawLook.Children.Add(_laserPaintDetail.View);
        _laserDrawLook.Children.Add(Parts.Text("How finely Draw mode's paint is kept: Low is lightest on the PC, High sharpest. Changing it clears the paint.", "Hint"));
        _laserSoftness = new SliderRow("Softness", 0, 1, 0.05, v => $"{v * 100:0}%", v => Change(o => o.LaserSoftness = (float)v));
        _laserDrawLook.Children.Add(_laserSoftness.View);
        _laserGlow = new SliderRow("Glow", 0, 1, 0.05, v => $"{v * 100:0}%", v => Change(o => o.LaserGlow = (float)v));
        _laserDrawLook.Children.Add(_laserGlow.View);
        _laserOutline = Parts.Toggle("Outline", () => Change(o => o.LaserOutline = !o.LaserOutline), "A thin dark edge round the paint (its colour is Paint outline), so it shows on light backgrounds.");
        _laserDrawLook.Children.Add(_laserOutline);
        _paintLook = new SphereLookRows(_laserDrawLook,
            v => Change(o => { o.KeepSnakeLook(); o.LaserShade = v; }), v => Change(o => { o.KeepSnakeLook(); o.LaserInside = v; }),
            v => Change(o => { o.KeepSnakeLook(); o.LaserLight = v; }), v => Change(o => { o.KeepSnakeLook(); o.LaserLightFrom = v; }),
            v => Change(o => { o.KeepSnakeLook(); o.LaserShine = v; }));
        _laserDrawLook.Children.Add(Parts.Text("Softness: how soft the paint's edge is (0 sharp); it shapes the paint as you lay it down, so changing it changes only new strokes. Where lines cross, the paint builds up and their colours blend. The Snake has its own look in the Snake tab.", "Hint"));
        pab.Children.Add(_laserDrawLook);

        // One line feeds both the paint and the Snake: these steady and gate it for both.
        _paintAndSnake = new StackPanel();
        _paintAndSnake.Children.Add(Parts.Text("Paint and Snake", "Sub"));
        _laserLineSmoothing = new SliderRow("Line smoothing", 0, 1, 0.05, v => v < 0.025 ? "off" : $"{v * 100:0}%", v => Change(o => o.LaserLineSmoothing = (float)v));
        _paintAndSnake.Children.Add(_laserLineSmoothing.View);
        _laserFireOnly = Parts.Toggle("Paint only while firing or aiming", () => Change(o => o.LaserFireOnly = !o.LaserFireOnly), "The beam alone leaves no trail; only shots and aiming draw.");
        _paintAndSnake.Children.Add(_laserFireOnly);
        _paintAndSnake.Children.Add(Parts.Text("Both follow the same line, so these set the paint and the Snake together. Line smoothing steadies the line against hand shake, strongly when you move slowly and hardly when you move fast, so it may trail a slow aim a little; the beam, dot and crosshair stay as they are.", "Hint"));
        pab.Children.Add(_paintAndSnake);
        var clear = Parts.Button("Clear", () => { if (Picked is { } id) _app.ClearOverlay(id); }, glyph: "");
        clear.Margin = new Thickness(0, 8, 0, 0);
        clear.HorizontalAlignment = HorizontalAlignment.Left;
        pab.Children.Add(clear);

        // Snake tab (pointer): the Snake, its zip back, shots and aiming, and its own look.
        var (snakeCard, snb) = Parts.Card("Snake");
        _snakeTab.Children.Add(snakeCard);
        _laserSnake = Parts.Toggle("Snake", () => Change(o => o.LaserSnake = !o.LaserSnake), "A line following the beam along the newest stretch of its path, thinning and fading toward its tail as set. With or without Draw mode.");
        snb.Children.Add(_laserSnake);
        // Length 10° to 720°, finer toward the short ones: the slider's square, in 5° steps.
        _laserSnakeOptions = new StackPanel { Margin = new Thickness(20, 0, 0, 0) };
        _laserSnakeLength = new SliderRow("Length", 0, 1, 0.005, v => $"{SnakeDeg(v)}°", v => Change(o => o.LaserSnakeDeg = SnakeDeg(v)));
        _laserSnakeOptions.Children.Add(_laserSnakeLength.View);
        _laserSnakeBrush = new SliderRow("Snake brush", 1, 20, 1, v => $"{v:0}°", v => Change(o => o.LaserSnakeBrush = (int)v));
        _laserSnakeOptions.Children.Add(_laserSnakeBrush.View);
        _laserSnakeTaper = new SliderRow("Taper", 0, 1, 0.05, v => $"{v * 100:0}%", v => Change(o => o.LaserSnakeTaper = (float)v));
        _laserSnakeOptions.Children.Add(_laserSnakeTaper.View);
        _laserSnakeFade = new SliderRow("Tail fade", 0, 1, 0.05, v => $"{v * 100:0}%", v => Change(o => o.LaserSnakeFade = (float)v));
        _laserSnakeOptions.Children.Add(_laserSnakeFade.View);
        _laserSnakeGlow = new SliderRow("Snake glow", 0, 1, 0.05, v => $"{v * 100:0}%", v => Change(o => o.LaserSnakeGlow = (float)v));
        _laserSnakeOptions.Children.Add(_laserSnakeGlow.View);
        _laserSnakeClearOnRatchet = Parts.Toggle("Clear on ratchet", () => Change(o => o.LaserSnakeClearOnRatchet = !o.LaserSnakeClearOnRatchet), "A ratchet press clears the Snake.");
        _laserSnakeOptions.Children.Add(_laserSnakeClearOnRatchet);
        _laserSnakeOptions.Children.Add(Parts.Text("Snake brush: how wide its head is. Taper: how much it thins toward its tail (0 keeps it the same width). Tail fade: how see-through it gets toward its tail (0 none). Snake glow: a soft glow round it (0 none).", "Hint"));
        snb.Children.Add(_laserSnakeOptions);

        // Zip back: the tail runs back into the head; the Snake grows again as the beam moves on.
        _snakeZip = new StackPanel();
        _zipOn = Parts.Toggle("Zip back", () => Change(o => o.LaserSnakeZip = !o.LaserSnakeZip), "The tail runs back into the head, then the Snake grows again as the beam moves on.");
        _snakeZip.Children.Add(_zipOn);
        _zipOptions = new StackPanel { Margin = new Thickness(20, 0, 0, 0) };
        _zipBy = new Seg<bool>(b => Change(o => o.LaserSnakeZipBySpeed = b), false, ("By time", false), ("By speed", true));
        _zipBy.View.Margin = new Thickness(0, 6, 0, 4);
        _zipOptions.Children.Add(_zipBy.View);
        _zipSpeed = new SliderRow("Zip speed", 30, 2000, 10, v => $"{v:0} °/s", v => Change(o => o.LaserSnakeZipSpeed = (int)v));
        _zipSpeedHint = Parts.Text("The tail always runs toward the head at this many degrees a second, while the beam moves and while it holds still: moving faster than that, the Snake grows (up to its Length); slower, it shrinks.", "Hint");
        _zipOptions.Children.Add(_zipSpeed.View);
        _zipOptions.Children.Add(_zipSpeedHint);
        _zipTimed = new StackPanel();
        _zipStops = Parts.Toggle("When the beam stops", () => Change(o => o.LaserSnakeZipStops = !o.LaserSnakeZipStops), "The moment the beam holds still, the tail runs back into the head.");
        _zipTimed.Children.Add(_zipStops);
        _zipRatchet = Parts.Toggle("On ratchet", () => Change(o => o.LaserSnakeZipRatchet = !o.LaserSnakeZipRatchet), "A ratchet press runs the tail back into the head.");
        _zipTimed.Children.Add(_zipRatchet);
        _zipSeconds = new SliderRow("Zip back in", 0.05, 2, 0.05, v => $"{v:0.00} s", v => Change(o => o.LaserSnakeZipSeconds = (float)v));
        _zipTimed.Children.Add(_zipSeconds.View);
        _zipTimed.Children.Add(Parts.Text("The whole Snake goes in that long, however long it is. Clear on ratchet, when on, wins over On ratchet.", "Hint"));
        _zipOptions.Children.Add(_zipTimed);
        _snakeZip.Children.Add(_zipOptions);
        snb.Children.Add(_snakeZip);

        _snakeShots = new StackPanel();
        _snakeShots.Children.Add(Parts.Text("Shots and aiming", "Sub"));
        _snakeFireBrush = new SliderRow("Fire brush", 1, 20, 1, v => $"{v:0}°", v => Change(o => o.LaserSnakeFireBrush = (int)v));
        _snakeShots.Children.Add(_snakeFireBrush.View);
        _snakeAimBrush = new SliderRow("Aim brush", 1, 20, 1, v => $"{v:0}°", v => Change(o => o.LaserSnakeAimBrush = (int)v));
        _snakeShots.Children.Add(_snakeAimBrush.View);
        _snakeShots.Children.Add(Parts.Text("How wide the Snake is while firing (in the Fire colours) and aiming (in the Aim colours).", "Hint"));
        snb.Children.Add(_snakeShots);

        _snakeLookPanel = new StackPanel();
        _snakeLookPanel.Children.Add(Parts.Text("Colour by", "Sub"));
        _snakeColourBy = new Seg<bool>(w => Change(o => o.LaserSnakeColourByWay = w), true, ("Where it is", false), ("The way it moved", true));
        _snakeLookPanel.Children.Add(_snakeColourBy.View);
        _snakeLook = new SphereLookRows(_snakeLookPanel,
            v => Change(o => o.LaserSnakeShade = v), v => Change(o => o.LaserSnakeInside = v),
            v => Change(o => o.LaserSnakeLight = v), v => Change(o => o.LaserSnakeLightFrom = v),
            v => Change(o => o.LaserSnakeShine = v));
        _snakeLookPanel.Children.Add(Parts.Text("The Snake's own look: Draw mode's paint has its own in the Paint tab.", "Hint"));
        snb.Children.Add(_snakeLookPanel);

        _laserSmoothColours = Parts.Toggle("Smooth way colours", () => Change(o => o.LaserSmoothColours = !o.LaserSmoothColours), "Small shakes don't flicker the Moving colours.");
        _laserMovesHint = Parts.Text("The Moving colours colour the crosshair, and with Colour by the way it moved the paint, by the way the beam moves (right, left, up, down, toward you, away, as the sphere's axes are when it isn't turned). A slant blends its ways' colours, as a normal map does. The crosshair keeps the last way's colour when you stop. With Colour by where it is, the paint takes them by where on the sphere it lands: its right side Moving right's colour, and so on.", "Hint");

        // Colours: in Look, or for the pointer, with much to set, its own tab (as on the phone).
        var (colors, cb) = Parts.Card("Colours");
        cb.Children.Add(_colors);
        cb.Children.Add(_laserSmoothColours);
        cb.Children.Add(_laserMovesHint);
        var defaults = Parts.Button("Default colours", () => Change(o => o.Colors.Clear()), glyph: "");
        defaults.Margin = new Thickness(0, 8, 0, 0);
        cb.Children.Add(defaults);
        _coloursCard = colors;
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
        // A trackball's axes: for each, the gyro turn that rolls it about that axis, and which way.
        // TrackballAxes bytes: 0 about up Y (yaw), 1 about right X (pitch), 2 along the beam Z (roll).
        _trackballAxes = new StackPanel { Margin = new Thickness(20, 4, 0, 4) };
        _trackballAxes.Children.Add(Parts.Text("Turns about"));
        _tbOwnAxes = new Seg<bool>(own => Change(o =>
        {
            o.TrackballOwnAxes = own;
            // About its own axes the loops of yaw and pitch leave a roll; easing roll back levels it.
            if (own && !o.EaseOf(2).On) { o.EaseOf(2).On = true; o.EaseOf(2).OnRatchet = false; }
        }), false, ("The sphere's axes", false), ("Its own axes", true));
        _tbOwnAxes.View.Margin = new Thickness(0, 4, 0, 6);
        _trackballAxes.Children.Add(_tbOwnAxes.View);
        foreach (var (i, name) in new[] { (0, "Yaw"), (1, "Pitch"), (2, "Roll") })
        {
            int shift = 8 * i;
            void Put(OverlaySettings o, int c) => o.TrackballAxes = (o.TrackballAxes & ~(0xFF << shift)) | (c << shift);
            _tbFromTitle[i] = Parts.Text($"{name} axis turns with");
            _trackballAxes.Children.Add(_tbFromTitle[i]);
            // None locks the axis: it never turns. Picking any chip clears an old Lock flag.
            _tbFrom[i] = new Seg<int>(a => Change(o => Put(o, a | ((o.TrackballAxes >> shift) & ~0x1F & ~OverlaySettings.TrackballLock & 0xFF))), false,
                ("Yaw", 3), ("Pitch", 2), ("Roll", 1), ("None", 0));
            _tbFrom[i].View.Margin = new Thickness(0, 4, 0, 2);
            _trackballAxes.Children.Add(_tbFrom[i].View);
            _tbInvert[i] = Parts.Toggle($"Invert {name} axis", () => Change(o => Put(o, ((o.TrackballAxes >> shift) & 0xFF) ^ 0x80)));
            _trackballAxes.Children.Add(_tbInvert[i]);
            _tbStop[i] = Parts.Toggle($"Stop {name} at ±90°", () => Change(o => Put(o, ((o.TrackballAxes >> shift) & 0xFF) ^ OverlaySettings.TrackballStop)),
                i == 1 ? "Off: it goes over the top, left and right staying as seen." : null);
            _trackballAxes.Children.Add(_tbStop[i]);
            // Easing this axis back to straight, with its own options.
            int ai = i;
            _tbEase[i] = Parts.Toggle($"Ease {name} axis back", () => Change(o => o.EaseOf(ai).On = !o.EaseOf(ai).On));
            _trackballAxes.Children.Add(_tbEase[i]);
            var opts = new StackPanel { Margin = new Thickness(20, 0, 0, 4) };
            _tbEaseWhen[i] = new Seg<bool>(r => Change(o => o.EaseOf(ai).OnRatchet = r), false, ("On ratchet", true), ("When the phone stops", false));
            opts.Children.Add(_tbEaseWhen[i].View);
            _tbEaseDelay[i] = new SliderRow("Delay", 0, 3000, 50, v => $"{v:0} ms", v => Change(o => o.EaseOf(ai).DelayMs = (int)v));
            opts.Children.Add(_tbEaseDelay[i].View);
            _tbEaseBy[i] = new Seg<bool>(b => Change(o => o.EaseOf(ai).BySpeed = b), false, ("By time", false), ("By speed", true));
            _tbEaseBy[i].View.Margin = new Thickness(0, 8, 0, 4);
            opts.Children.Add(_tbEaseBy[i].View);
            _tbEaseSeconds[i] = new SliderRow("Back in", 0.1, 5, 0.1, v => $"{v:0.0} s", v => Change(o => o.EaseOf(ai).Seconds = (float)v));
            _tbEaseSpeed[i] = new SliderRow("Speed", 10, 720, 10, v => $"{v:0} °/s", v => Change(o => o.EaseOf(ai).Speed = (int)v));
            opts.Children.Add(_tbEaseSeconds[i].View);
            opts.Children.Add(_tbEaseSpeed[i].View);
            _tbEaseOptions[i] = opts;
            _trackballAxes.Children.Add(opts);
        }
        _trackballAxes.Children.Add(Parts.Text("The sphere's axes: it turns as a game camera, left and right about the sphere's vertical, up and down about its own right, then about where it faces. Its own axes: yaw about its own up, pitch about its own right, so the crosshair moves at right angles as it sees it. None never turns, whatever the others do, as Camera never rolls; it shows grey and dashed with a padlock. Stop at ±90° keeps an axis from turning past sideways (Pitch: straight up or down).", "Hint"));
        _tbAsCamera = Parts.Button("As the camera", () => Change(o => { o.TrackballAxes = OverlaySettings.TrackballAsCamera; o.TrackballOwnAxes = false; }));
        _trackballAxes.Children.Add(_tbAsCamera);
        tbb.Children.Add(_trackballAxes);
        tbb.Children.Add(_laserFacesPanel);
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
        var savePreset = Parts.Button("Save as preset", SavePreset, glyph: "\uE734");
        savePreset.ToolTip = "Adds it to Add overlay with a ★: every setting and its size, named by its label. Right-click a preset there to rename or delete it.";
        savePreset.Margin = new Thickness(0, 4, 0, 0);
        _settings.Children.Add(savePreset);
        _settings.Children.Add(remove);
        _tabs.Set(0);
        ShowTab();
    }

    private const string AxonHint = "Seen from 30° round and 30° above with the world's axes (yaw toward you red, pitch right green, roll up blue, as the gimbal's rings), as the pointer is, instead of face-on.";

    /// <summary>Meshes.MaxSubdivision in the tray app; the icosphere's finest level.</summary>
    private const int MaxSubdivision = 3;

    private void ShowTab()
    {
        Parts.Show(_windowTab, _tab == WindowTab);
        Parts.Show(_lookTab, _tab == LookTab);
        Parts.Show(_paintTab, _tab == PaintTab);
        Parts.Show(_snakeTab, _tab == SnakeTab);
        Parts.Show(_coloursTab, _tab == ColoursTab);
        Parts.Show(_turnTab, _tab == TurnTab);
    }

    /// <summary>Paint, Snake and Colours tabs only for the pointer; other overlays keep their colours in Look.
    /// A tab that goes away falls back to Look.</summary>
    private void ShowTabs(bool laser, bool still)
    {
        var home = laser ? _coloursTab : _lookTab;
        if (_coloursCard.Parent != home)
        {
            ((Panel)_coloursCard.Parent).Children.Remove(_coloursCard);
            home.Children.Add(_coloursCard);
        }
        _tabs.Show(PaintTab, laser);
        _tabs.Show(SnakeTab, laser);
        _tabs.Show(ColoursTab, laser);
        // The FPS overlay, Ratchet ring and Speed graph don't turn with the phone.
        _tabs.Show(TurnTab, !still);
        bool gone = _tab switch { PaintTab or SnakeTab or ColoursTab => !laser, TurnTab => still, _ => false };
        if (gone)
        {
            _tab = LookTab;
            _tabs.Set(_tab);
        }
        ShowTab();
    }

    private void SavePreset()
    {
        if (Picked is not { } id || _app.OverlayOf(id) is not { } o) return;
        _app.SaveOverlayPreset(id, _app.OverlayName(o));
        Parts.Show(_addKinds, true);
    }

    /// <summary>The ★ presets after the kinds under Add overlay, rebuilt when they change.</summary>
    private void RebuildPresets()
    {
        var presets = _app.OverlayPresets;
        var key = string.Join("|", presets.Select(p => p.Id + ":" + p.Label));
        if (key == _presetKey) return;
        _presetKey = key;
        while (_addKinds.Children.Count > _kindButtons) _addKinds.Children.RemoveAt(_addKinds.Children.Count - 1);
        foreach (var p in presets)
        {
            var pid = p.Id;
            var name = p.Label.Length > 0 ? p.Label : OverlaySettings.KindName(p.Kind);
            var b = Parts.Button("★ " + name, () =>
            {
                if (_app.AddOverlayFromPreset(pid) is { } nid) _app.SelectOverlay(nid);
                Parts.Show(_addKinds, false);
                _window.RefreshNow();
            });
            b.Margin = new Thickness(0, 0, 6, 6);
            b.ToolTip = $"Your saved {OverlaySettings.KindName(p.Kind)} overlay. Right-click to rename or delete it.";
            var menu = new ContextMenu();
            var rename = new MenuItem { Header = "Rename…" };
            rename.Click += (_, _) =>
            {
                if (AskName("Rename preset", name) is { } n) _app.RenameOverlayPreset(pid, n);
            };
            var delete = new MenuItem { Header = "Delete" };
            delete.Click += (_, _) =>
            {
                var r = MessageBox.Show(_window, $"Delete the preset \"{name}\"? Overlays added from it stay.", _window.Title,
                    MessageBoxButton.OKCancel, MessageBoxImage.Question);
                if (r == MessageBoxResult.OK) _app.RemoveOverlayPreset(pid);
            };
            menu.Items.Add(rename);
            menu.Items.Add(delete);
            b.ContextMenu = menu;
            _addKinds.Children.Add(b);
        }
    }

    /// <summary>A small window asking for a name; null when cancelled or left empty.</summary>
    private string? AskName(string title, string initial)
    {
        var box = new TextBox { Text = initial, MaxLength = 40, Margin = new Thickness(0, 0, 0, 12), FontSize = 14 };
        var w = new Window
        {
            Title = title,
            Owner = _window,
            SizeToContent = SizeToContent.WidthAndHeight,
            ResizeMode = ResizeMode.NoResize,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            ShowInTaskbar = false,
        };
        w.SetResourceReference(Control.BackgroundProperty, "BgBrush");
        var ok = Parts.Button("OK", () => w.DialogResult = true);
        ok.IsDefault = true;
        var cancel = Parts.Button("Cancel", () => w.DialogResult = false);
        cancel.IsCancel = true;
        cancel.Margin = new Thickness(8, 0, 0, 0);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        buttons.Children.Add(ok);
        buttons.Children.Add(cancel);
        var body = new StackPanel { Margin = new Thickness(16), MinWidth = 280 };
        body.Children.Add(box);
        body.Children.Add(buttons);
        w.Content = body;
        w.SourceInitialized += (_, _) => Theme.TitleBar(w);
        w.Loaded += (_, _) => { box.Focus(); box.SelectAll(); };
        if (w.ShowDialog() != true) return null;
        var text = box.Text.Trim();
        return text.Length > 0 ? text : null;
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
            RebuildPresets();
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
            var rf = _app.RestFade;
            Parts.SetChecked(_restOn, rf.On);
            Parts.Show(_restOptions, rf.On);
            _restFlat.Set(rf.FlatWithinDeg);
            _restStill.Set(Math.Round(rf.StillBelowDps, 1));
            _restQuiet.Set(rf.QuietMs / 1000.0);
            _restTo.Set(Math.Round(rf.FadeTo * 100f));
            _restOver.Set(rf.FadeMs / 1000.0);
            Parts.SetChecked(_restSilent, rf.WhenSilent);
            _restLive.Text = _app.StillnessDps is { } dps
                ? $"Phone now: {dps:0.00}°/s" + (dps < rf.StillBelowDps ? ", still" : ", moving")
                : "Phone now: no data (show an overlay with the phone connected)";
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

    private const int SnakeMinDeg = 10, SnakeMaxDeg = 720;

    /// <summary>Snake's Length slider's place (0..1, its square) in degrees, to 5°, from 10°.</summary>
    private static int SnakeDeg(double p) => Math.Max(SnakeMinDeg, (int)Math.Round(p * p * SnakeMaxDeg / 5) * 5);

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
        // Not while typing in it: the caret would jump.
        if (!_label.IsKeyboardFocused && _label.Text != o.Label) _label.Text = o.Label;
        _labelHint.Text = o.Label.Length == 0 ? _app.OverlayName(o) : "";
        Parts.Show(_labelHint, _label.Text.Length == 0);
        Parts.SetChecked(_shown, o.Shown);
        _background.Set(o.Background);
        _opacity.Set(MathF.Round(o.Opacity * 100f));
        _quality.Set(o.Quality);
        _fps.Set(o.MaxFps is 60 or 30 ? o.MaxFps : 0);

        Parts.Show(_phoneLook, o.Kind == OverlayKind.Phone);
        Parts.Show(_shapeLook, o.Kind == OverlayKind.Shape);
        Parts.Show(_diskLook, o.Kind == OverlayKind.Disk);
        Parts.Show(_ringLook, o.Kind == OverlayKind.Ring);
        Parts.Show(_graphLook, o.Kind == OverlayKind.Graph);
        _graphRange.Set(o.GraphRange);
        _graphSeconds.Set(o.GraphSeconds);
        bool laser = o.Kind == OverlayKind.Laser;
        Parts.Show(_laserLook, laser);
        Parts.SetChecked(_phoneAxon, o.Axonometric);
        Parts.SetChecked(_shapeAxon, o.Axonometric);
        _laserShape.Set(o.LaserRifle ? 1 : o.LaserCrosshair ? 2 : 0);
        bool ch = o.LaserCrosshair && !o.LaserRifle;
        Parts.Show(_chOptions, ch);
        _chShape.Set(o.CrosshairShape);
        _chSize.Set(o.CrosshairSize);
        _chGap.Set(o.CrosshairGap);
        _chThick.Set(o.CrosshairThickness);
        Parts.SetChecked(_chDot, o.CrosshairDot);
        Parts.SetChecked(_chOutline, o.CrosshairOutline);
        Parts.Show(_chGap.View, o.CrosshairShape is 1 or 3 or 5);
        Parts.Show(_chThick.View, o.CrosshairShape != 4);
        Parts.Show(_chDot, o.CrosshairShape != 4);
        _laserFaces.Set(o.LaserFaces);
        Parts.Show(_laserFacesPanel, laser && o.IsTrackball);
        Parts.Show(_laserRifleHint, o.LaserRifle);
        Parts.Show(_gunOptions, o.LaserRifle);
        _gunPick.Set(o.LaserGun);
        _recoilLike.Set(o.LaserGun);
        _shootsLike.Set(o.LaserGun);
        Parts.SetChecked(_gunLowered, o.GunLowered);
        _gunHipPull.Set(o.GunHipPull);
        _gunHipRecover.Set(o.GunHipRecoverMs);
        Parts.SetChecked(_gunScope, o.GunScope);
        Parts.Show(_gunScope, o.Gun().Scope != null);
        Parts.Show(_laserGrid, !o.LaserRifle);
        Parts.SetChecked(_laserAxon, o.LaserAxonometric);
        Parts.SetChecked(_laserSphereTurns, o.LaserSphereTurns);
        Parts.SetChecked(_laserGrid, o.LaserGrid);
        Parts.SetChecked(_laserAxes, o.LaserAxes);
        Parts.SetChecked(_laserGimbal, o.LaserGimbal);
        Parts.Show(_laserGimbal, !o.LaserRifle && !o.LaserCrosshair);
        Parts.SetChecked(_laserPerspective, o.Perspective);
        bool fires = o.LaserFire != GyroPhone.Host.LaserFire.None, aims = o.LaserAim != GyroPhone.Host.LaserFire.None;
        bool kicks = ch || o.LaserRifle;

        // Look: the Fire display's kick, flash and spread; the Aim display's crosshair size.
        _firePick.Set(o.LaserFire);
        _aimPick.Set(o.LaserAim);
        Parts.Show(_fireOptions, fires && (o.LaserDraw || kicks));
        Parts.Show(_kickTitle, kicks);
        Parts.Show(_kickHint, ch);
        Parts.Show(_rifleKickHint, o.LaserRifle);
        Parts.Show(_recoilLikeTitle, ch);
        Parts.Show(_recoilLike.View, ch);
        Parts.Show(_kickMode.View, kicks);
        _kickMode.Set(o.CrosshairKick);
        Parts.Show(_kickOptions, kicks && o.CrosshairKick != GyroPhone.Host.CrosshairKick.Off);
        _kickScale.Set(o.KickOf(o.Gun()));
        _kickRpm.Set(o.RpmOf(o.Gun()));
        Parts.Show(_kickRpm.View, o.CrosshairKick == GyroPhone.Host.CrosshairKick.Auto);
        Parts.Show(_kickLines.View, ch);
        _kickLines.Set(o.CrosshairKickLinesOnly);
        Parts.Show(_muzzleFlash, kicks);
        Parts.SetChecked(_muzzleFlash, o.MuzzleFlash);
        Parts.Show(_shootsLikeTitle, !kicks && o.LaserDraw);
        Parts.Show(_shootsLike.View, !kicks && o.LaserDraw);
        Parts.SetChecked(_laserSpread, o.LaserSpread);
        Parts.Show(_spreadOptions, o.LaserSpread);
        _gunSpread.Set(o.SpreadOf(o.Gun()));
        Parts.SetChecked(_laserBloom, o.LaserBloom);
        Parts.Show(_aimOptions, aims && ch);
        _aimSize.Set(o.LaserAimSize);

        // Paint: Draw mode, its shots and aiming, its look, and what it shares with the Snake.
        Parts.SetChecked(_laserDraw, o.LaserDraw);
        Parts.Show(_laserDrawOptions, o.LaserDraw);
        _laserBrush.Set(o.LaserBrush);
        Parts.SetChecked(_laserClearOnRatchet, o.LaserClearOnRatchet);
        Parts.Show(_paintShots, o.LaserDraw && (fires || aims));
        Parts.Show(_paintFire, fires);
        _fireBrush.Set(o.LaserFireBrush);
        _fireGlow.Set(o.LaserFireGlow);
        Parts.SetChecked(_laserShotDraw, o.LaserShotDraw);
        Parts.Show(_shotOptions, o.LaserShotDraw);
        // The Sphere look has no Kick: its shots come Once a press or by Auto rifle, set here.
        Parts.Show(_shotBeat.View, !kicks);
        _shotBeat.Set(Math.Max(o.CrosshairKick, GyroPhone.Host.CrosshairKick.Once));
        Parts.Show(_shotRpm.View, !kicks && o.CrosshairKick == GyroPhone.Host.CrosshairKick.Auto);
        _shotRpm.Set(o.RpmOf(o.Gun()));
        _hitLook.Set(o.LaserHitLook);
        Parts.Show(_paintAim, aims);
        _aimBrush.Set(o.LaserAimBrush);
        _aimGlow.Set(o.LaserAimGlow);
        Parts.Show(_laserDrawLook, o.LaserDraw);
        _laserColourBy.Set(o.LaserColourByWay);
        _laserPaintDetail.Set(Math.Clamp(o.LaserPaintDetail, 0, 2));
        _laserSoftness.Set(o.LaserSoftness);
        _laserGlow.Set(o.LaserGlow);
        Parts.SetChecked(_laserOutline, o.LaserOutline);
        _paintLook.Set(o.LaserShade, o.LaserInside, o.LaserLight, o.LaserLightFrom, o.LaserShine);
        bool paints = o.LaserDraw || o.LaserSnake;
        Parts.Show(_paintAndSnake, paints);
        _laserLineSmoothing.Set(o.LaserLineSmoothing);
        Parts.Show(_laserFireOnly, fires || aims);
        Parts.SetChecked(_laserFireOnly, o.LaserFireOnly);

        // Snake: its options, zip back, shots and aiming, and its own look.
        Parts.SetChecked(_laserSnake, o.LaserSnake);
        Parts.Show(_laserSnakeOptions, o.LaserSnake);
        _laserSnakeLength.Set(Math.Sqrt(Math.Clamp(o.LaserSnakeDeg, SnakeMinDeg, SnakeMaxDeg) / (double)SnakeMaxDeg));
        _laserSnakeBrush.Set(o.LaserSnakeBrush);
        _laserSnakeTaper.Set(o.LaserSnakeTaper);
        _laserSnakeFade.Set(o.LaserSnakeFade);
        _laserSnakeGlow.Set(o.LaserSnakeGlow);
        Parts.SetChecked(_laserSnakeClearOnRatchet, o.LaserSnakeClearOnRatchet);
        Parts.Show(_snakeZip, o.LaserSnake);
        Parts.SetChecked(_zipOn, o.LaserSnakeZip);
        Parts.Show(_zipOptions, o.LaserSnakeZip);
        _zipBy.Set(o.LaserSnakeZipBySpeed);
        Parts.Show(_zipSpeed.View, o.LaserSnakeZipBySpeed);
        Parts.Show(_zipSpeedHint, o.LaserSnakeZipBySpeed);
        Parts.Show(_zipTimed, !o.LaserSnakeZipBySpeed);
        Parts.SetChecked(_zipStops, o.LaserSnakeZipStops);
        Parts.SetChecked(_zipRatchet, o.LaserSnakeZipRatchet);
        _zipSeconds.Set(o.LaserSnakeZipSeconds);
        _zipSpeed.Set(o.LaserSnakeZipSpeed);
        Parts.Show(_snakeShots, o.LaserSnake && (fires || aims));
        Parts.Show(_snakeFireBrush.View, fires);
        Parts.Show(_snakeAimBrush.View, aims);
        _snakeFireBrush.Set(o.SnakeFireBrush);
        _snakeAimBrush.Set(o.SnakeAimBrush);
        Parts.Show(_snakeLookPanel, o.LaserSnake);
        _snakeColourBy.Set(o.SnakeColourByWay);
        _snakeLook.Set(o.SnakeShade, o.SnakeInside, o.SnakeLight, o.SnakeLightFrom, o.SnakeShine);

        bool moves = paints || (o.LaserCrosshair && !o.LaserRifle);
        Parts.Show(_laserSmoothColours, laser && moves);
        Parts.Show(_laserMovesHint, laser && moves);
        Parts.SetChecked(_laserSmoothColours, o.LaserSmoothColours);
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
        ShowTabs(laser, !o.Turns);

        _turnsWith.Set(o.TurnsWith);
        _turnsHint.Text = o.TurnsWith switch
        {
            OverlayTurnsWith.Camera => "Where the game's camera points: it turns left and right and up and down as the camera does, never rolling, with the gyro and every other camera move (touchpad, buttons, controllers, keys). A ratchet stops it, as it stops the camera.",
            OverlayTurnsWith.Trackball => "Like a ball rolled by your moves: each of its axes turns by the gyro turn picked below (as Camera, by default), and every other camera move rolls it too.",
            _ => "How you hold the phone.",
        };
        Parts.SetChecked(_rws, o.TrackballRws);
        Parts.Show(_rws, o.FollowsCamera);
        _spin.Set(o.TrackballSpin);
        Parts.Show(_spin.View, o.FollowsCamera && !o.TrackballRws);
        Parts.Show(_trackballAxes, o.IsTrackball);
        for (int i = 0; i < 3; i++)
        {
            int code = (o.TrackballAxes >> (8 * i)) & 0xFF;
            bool locked = (OverlaySettings.TrackballLocks(o.TrackballAxes) & (1 << i)) != 0;
            _tbFrom[i].Set(locked ? 0 : code & 0x1F);
            Parts.SetChecked(_tbInvert[i], (code & 0x80) != 0);
            Parts.Show(_tbInvert[i], !locked);
            Parts.SetChecked(_tbStop[i], (code & OverlaySettings.TrackballStop) != 0);
            Parts.Show(_tbStop[i], !locked && !o.TrackballOwnAxes);
            var e = o.EaseOf(i);
            Parts.SetChecked(_tbEase[i], e.On);
            Parts.Show(_tbEase[i], !locked);
            Parts.Show(_tbEaseOptions[i], !locked && e.On);
            _tbEaseWhen[i].Set(e.OnRatchet);
            _tbEaseDelay[i].Set(e.DelayMs);
            _tbEaseBy[i].Set(e.BySpeed);
            _tbEaseSeconds[i].Set(e.Seconds);
            _tbEaseSpeed[i].Set(e.Speed);
            Parts.Show(_tbEaseSeconds[i].View, !e.BySpeed);
            Parts.Show(_tbEaseSpeed[i].View, e.BySpeed);
        }
        _tbOwnAxes.Set(o.TrackballOwnAxes);
        Parts.Show(_tbAsCamera, o.TrackballAxes != OverlaySettings.TrackballAsCamera || o.TrackballOwnAxes);
        // A trackball eases back per axis (in its axis rows), not here. Following the camera: no gravity or compass.
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
            var c = Color.FromRgb((byte)(argb >> 16), (byte)(argb >> 8), (byte)argb);
            // One brush per swatch, changed only when its colour does: a new brush redraws it every refresh.
            if (swatch.Background is SolidColorBrush { IsFrozen: false } b)
            {
                if (b.Color != c) b.Color = c;
            }
            else swatch.Background = new SolidColorBrush(c);
        }
    }

    private void PickColor(string key)
    {
        if (Picked is not { } id || _app.OverlayOf(id) is not { } o) return;
        if (_app.PickColor(o.Color(key), _window.Handle) is { } argb) _app.ChangeOverlay(id, x => x.Colors[key] = argb);
    }
}

/// <summary>
/// How paint on the sphere is shaded and lit, as rows in <c>into</c>: facing shade, inside, and a light fixed in the
/// scene (strength, where it shines from, shine). The paint and the Snake each have their own, as on the phone.
/// </summary>
internal sealed class SphereLookRows
{
    private readonly SliderRow _shade, _inside, _light, _shine;
    private readonly Seg<int> _from;

    public SphereLookRows(Panel into, Action<float> shade, Action<float> inside, Action<float> light, Action<int> from, Action<float> shine)
    {
        _shade = new SliderRow("Facing shade", 0, 1, 0.05, v => $"{v * 100:0}%", v => shade((float)v));
        into.Children.Add(_shade.View);
        _inside = new SliderRow("Inside", 0.2, 1, 0.05, v => $"{v * 100:0}%", v => inside((float)v));
        into.Children.Add(_inside.View);
        into.Children.Add(Parts.Text("Facing shade darkens it where the sphere's surface turns away from you, so you see its roundness (0 off). Inside: how bright the far half is, seen from inside the sphere, next to the near half seen from outside.", "Hint"));
        into.Children.Add(Parts.Text("Light", "Sub"));
        _light = new SliderRow("Light strength", 0, 1, 0.05, v => $"{v * 100:0}%", v => light((float)v));
        into.Children.Add(_light.View);
        _from = new Seg<int>(from, true, ("Upper left", 0), ("Above", 1), ("Upper right", 2), ("Front", 3));
        into.Children.Add(_from.View);
        _shine = new SliderRow("Shine", 0, 1, 0.05, v => $"{v * 100:0}%", v => shine((float)v));
        into.Children.Add(_shine.View);
        into.Children.Add(Parts.Text("A light fixed in the scene, shining from where you pick: it stays put while the sphere or the pointer turns. Light strength darkens what faces away from it (0 off); Shine adds a glossy highlight where it reflects toward you (0 none).", "Hint"));
    }

    public void Set(float shade, float inside, float light, int from, float shine)
    {
        _shade.Set(shade);
        _inside.Set(inside);
        _light.Set(light);
        _from.Set(Math.Clamp(from, 0, 3));
        _shine.Set(shine);
    }
}
