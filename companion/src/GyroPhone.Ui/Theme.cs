using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using Microsoft.Win32;

namespace GyroPhone.Ui;

/// <summary>
/// The colours, dark or light as Windows' app mode says, as brushes in the WPF application's resources
/// (the styles use them through DynamicResource, so a switch repaints every open window). The accent
/// is the icon's light blue.
/// </summary>
public static class Theme
{
    private static bool _started;

    public static bool Dark { get; private set; } = true;

    /// <summary>Makes the WPF application (the tray app's message loop runs it), loads the styles and colours.</summary>
    public static void Start()
    {
        if (_started) return;
        var app = Application.Current ?? new Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        var styles = new ResourceDictionary
        {
            Source = new Uri("pack://application:,,,/GyroPhone.Ui;component/Styles.xaml", UriKind.Absolute),
        };
        app.Resources.MergedDictionaries.Add(styles);
        try
        {
            Apply(ReadDark());
        }
        catch
        {
            app.Resources.MergedDictionaries.Remove(styles); // so a retry doesn't load them twice
            throw;
        }
        _started = true; // only once loaded, so a failed load is tried again by the next window
        SystemEvents.UserPreferenceChanged += (_, e) =>
        {
            if (e.Category != UserPreferenceCategory.General) return;
            bool dark = ReadDark();
            if (dark != Dark) app.Dispatcher.BeginInvoke(() => Apply(dark));
        };
    }

    /// <summary>Windows' "Choose your app mode": dark unless it says light.</summary>
    private static bool ReadDark()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("AppsUseLightTheme") is not int light || light == 0;
        }
        catch (Exception e) when (e is System.Security.SecurityException or UnauthorizedAccessException or IOException)
        {
            return true;
        }
    }

    private static readonly (string Key, uint Dark, uint Light)[] Colors =
    [
        ("Bg", 0xFF14171F, 0xFFF3F5F9),
        ("Side", 0xFF1B1F2A, 0xFFE7EBF2),
        ("Card", 0xFF212633, 0xFFFFFFFF),
        ("CardHover", 0xFF283041, 0xFFEEF2F8),
        ("CardPress", 0xFF2F384B, 0xFFE2E8F1),
        ("Line", 0xFF2F3748, 0xFFD5DCE6),
        ("Text", 0xFFE6E9F0, 0xFF1B1F2A),
        ("Dim", 0xFF8D96A8, 0xFF5D6678),
        ("Faint", 0xFF5C6577, 0xFFA3ABB9),
        ("Accent", 0xFF83CFFF, 0xFF1F7EC2),
        ("AccentHover", 0xFFA3DCFF, 0xFF2B8FD6),
        ("OnAccent", 0xFF0D1520, 0xFFFFFFFF),
        ("Ok", 0xFF6FD49A, 0xFF1E9A57),
        ("Danger", 0xFFFF8A80, 0xFFC62828),
        ("Warn", 0xFFFFC46B, 0xFFB87300),
        ("WarnBg", 0xFF3A2F1A, 0xFFFFF4DF),
        ("WarnLine", 0xFF5C4720, 0xFFF0D49A),
        ("WarnText", 0xFFFFE2AD, 0xFF5A3B00),
        ("Mono", 0xFFB8C0CF, 0xFF384152),
    ];

    private static void Apply(bool dark)
    {
        Dark = dark;
        var res = Application.Current.Resources;
        foreach (var (key, d, l) in Colors)
        {
            var c = ToColor(dark ? d : l);
            res[key + "Color"] = c;
            var b = new SolidColorBrush(c);
            b.Freeze();
            res[key + "Brush"] = b;
        }
        foreach (Window w in Application.Current.Windows) TitleBar(w);
    }

    public static Color ToColor(uint argb) =>
        Color.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb);

    /// <summary>The title bar in the window's colours: dark mode, and on Windows 11 the sidebar's colour.</summary>
    public static void TitleBar(Window w)
    {
        var hwnd = new WindowInteropHelper(w).Handle;
        if (hwnd == IntPtr.Zero) return;
        try
        {
            int on = Dark ? 1 : 0;
            DwmSetWindowAttribute(hwnd, DwmUseImmersiveDarkMode, ref on, sizeof(int));
            uint side = Dark ? 0xFF1B1F2A : 0xFFE7EBF2;
            int colorRef = (int)(((side & 0xFF) << 16) | (side & 0xFF00) | ((side >> 16) & 0xFF)); // COLORREF is 0x00BBGGRR
            DwmSetWindowAttribute(hwnd, DwmCaptionColor, ref colorRef, sizeof(int));
            uint text = Dark ? 0xFFE6E9F0 : 0xFF1B1F2A;
            int textRef = (int)(((text & 0xFF) << 16) | (text & 0xFF00) | ((text >> 16) & 0xFF));
            DwmSetWindowAttribute(hwnd, DwmTextColor, ref textRef, sizeof(int));
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            // Not on this Windows: the default title bar stays.
        }
    }

    private const int DwmUseImmersiveDarkMode = 20, DwmCaptionColor = 35, DwmTextColor = 36;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}
