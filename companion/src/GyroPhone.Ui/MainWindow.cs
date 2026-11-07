using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;

namespace GyroPhone.Ui;

/// <summary>The main window's pages, in sidebar order.</summary>
public enum MainPage
{
    Home,
    Phones,
    Gamepad,
    Overlays,
    Connection,
    Latency,
    App,
}

/// <summary>
/// The main window: a sidebar of pages on the left, the picked page on the right. Each page scrolls
/// as a whole (nothing scrolls inside it). Refreshed twice a second and whenever the tray app says
/// something changed.
/// </summary>
public sealed class MainWindow : Window
{
    private static MainPage _lastPage = MainPage.Home;

    private readonly ICompanion _app;
    private readonly Dictionary<MainPage, (RadioButton Nav, ScrollViewer Scroll, Page Page)> _pages = [];
    private readonly TextBlock _version;
    private readonly DispatcherTimer _timer;
    private bool _refreshing;

    public MainWindow(ICompanion app, string title, ImageSource? icon)
    {
        _app = app;
        Title = title;
        Icon = icon;
        Width = 1040;
        Height = 700;
        MinWidth = 820;
        MinHeight = 520;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        FontFamily = (FontFamily)FindResource("UiFont");
        FontSize = 14;
        SetResourceReference(BackgroundProperty, "BgBrush");
        SetResourceReference(ForegroundProperty, "TextBrush");
        UseLayoutRounding = true;
        TextOptions.SetTextFormattingMode(this, TextFormattingMode.Display);

        var root = new Grid();
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(216) });
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        // Sidebar
        var side = new DockPanel { LastChildFill = false };
        var sideBox = new Border { Padding = new Thickness(10, 12, 10, 12), Child = side };
        sideBox.SetResourceReference(Border.BackgroundProperty, "SideBrush");
        root.Children.Add(sideBox);

        var brand = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(10, 2, 0, 18) };
        if (icon != null) brand.Children.Add(new Image { Source = icon, Width = 22, Height = 22, Margin = new Thickness(0, 0, 10, 0) });
        brand.Children.Add(new TextBlock { Text = "GyroPhone", FontSize = 16, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center });
        DockPanel.SetDock(brand, Dock.Top);
        side.Children.Add(brand);

        _version = Parts.Text("", "Sub");
        _version.FontSize = 11.5;
        _version.Margin = new Thickness(12, 0, 6, 0);
        DockPanel.SetDock(_version, Dock.Bottom);
        side.Children.Add(_version);

        var content = new Grid();
        Grid.SetColumn(content, 1);
        root.Children.Add(content);

        Add(side, content, MainPage.Home, "Home", "", new HomePage(app, Go));
        Add(side, content, MainPage.Phones, "Phones", "", new PhonesPage(app));
        Add(side, content, MainPage.Gamepad, "Gamepad", "", new GamepadPage(app));
        Add(side, content, MainPage.Overlays, "Overlays", "", new OverlaysPage(app, this));
        Add(side, content, MainPage.Connection, "Connection", "", new ConnectionPage(app));
        Add(side, content, MainPage.App, "App", "", new AppPage(app));

        Content = root;
        Go(_lastPage);

        _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        _timer.Tick += (_, _) => RefreshNow();
        SourceInitialized += (_, _) => Theme.TitleBar(this);
        Loaded += (_, _) =>
        {
            RefreshNow();
            _timer.Start();
        };
        Closed += (_, _) => _timer.Stop();
    }

    private void Add(DockPanel side, Grid content, MainPage id, string name, string glyph, Page page)
    {
        var nav = new RadioButton { Content = name, Tag = glyph, Style = Parts.Style("Nav"), GroupName = "nav" };
        nav.Click += (_, _) => Go(id);
        DockPanel.SetDock(nav, Dock.Top);
        side.Children.Add(nav);
        var scroll = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = new Border { Padding = new Thickness(32, 26, 32, 20), Child = page.View },
            Visibility = Visibility.Collapsed,
        };
        content.Children.Add(scroll);
        _pages[id] = (nav, scroll, page);
    }

    /// <summary>Shows page <paramref name="id"/>.</summary>
    public void Go(MainPage id)
    {
        _lastPage = id;
        foreach (var (key, (nav, scroll, _)) in _pages)
        {
            Parts.SetChecked(nav, key == id);
            Parts.Show(scroll, key == id);
        }
        if (IsLoaded) RefreshNow();
    }

    /// <summary>Shows the settings and stats as they are now. Called by the tray app on every change, and by the timer.</summary>
    public void RefreshNow()
    {
        if (_refreshing || !IsLoaded) return;
        _refreshing = true;
        try
        {
            var v = _app.View();
            _version.Text = v.UpdatePending is { } p ? $"{v.Version}\nRestarting into {p}…" : v.Version;
            // Home's latency graph keeps sampling while another page is shown.
            foreach (var (key, (_, scroll, page)) in _pages)
                if (key == _lastPage || page is HomePage) page.Refresh(v);
        }
        finally
        {
            _refreshing = false;
        }
    }

    /// <summary>This window's handle, to own dialogs from the tray app.</summary>
    internal IntPtr Handle => new WindowInteropHelper(this).Handle;
}

/// <summary>One page of the main window.</summary>
internal abstract class Page
{
    public abstract FrameworkElement View { get; }

    public abstract void Refresh(CompanionView v);
}
