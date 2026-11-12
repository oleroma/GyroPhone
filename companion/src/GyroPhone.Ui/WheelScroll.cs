using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;

namespace GyroPhone.Ui;

/// <summary>
/// Scrolling with the wheel and the touchpad the way a web browser does, in every window (docs/spec/pc-companion.md,
/// "Scrolling"). WPF's own ScrollViewer moves a fixed 3 lines for every wheel message whatever its size, so a
/// touchpad, which sends many small ones, raced. Here each message moves the page in proportion to its size:
/// 100 px for a whole wheel notch with Windows' usual 3 lines (as Chrome does), a touchpad's small steps at once
/// so the page follows the fingers, a notch eased in over a few frames. The innermost scrollable area under the
/// pointer that can still move that way takes it, so a table that only scrolls sideways passes the page's
/// scrolling on. Shift + wheel and the touchpad's sideways swipe (WM_MOUSEHWHEEL) scroll sideways.
/// Item-based lists (CanContentScroll) keep WPF's own scrolling.
/// </summary>
internal static class WheelScroll
{
    private const int WmMouseHWheel = 0x020E;
    /// <summary>One notch is 120; Chrome scrolls 100 px for it at Windows' default 3 lines.</summary>
    private const double PxPerLine = 100.0 / 3;
    /// <summary>How quickly a notch's scroll settles: the time constant of the ease, in seconds.</summary>
    private const double EaseSeconds = 0.045;

    private sealed class Motion
    {
        /// <summary>Where it is going, and where it was last put (ScrollViewer's own offsets lag until its next layout).</summary>
        public double TargetX, TargetY, X, Y;
        public bool Moving;
    }

    private static readonly ConditionalWeakTable<ScrollViewer, Motion> Motions = new();
    private static readonly List<ScrollViewer> Moving = [];
    private static readonly ConditionalWeakTable<Window, object> Hooked = new();
    private static TimeSpan _lastFrame;
    private static bool _registered, _rendering;

    public static void Register()
    {
        if (_registered) return;
        _registered = true;
        EventManager.RegisterClassHandler(typeof(Window), UIElement.PreviewMouseWheelEvent, new MouseWheelEventHandler(OnWheel));
        EventManager.RegisterClassHandler(typeof(Window), FrameworkElement.LoadedEvent, new RoutedEventHandler(OnLoaded));
    }

    /// <summary>Pixels for a wheel delta (120 = one notch), by the user's lines-per-notch setting.</summary>
    private static double Pixels(int delta, ScrollViewer sv, bool horizontal)
    {
        int lines = SystemParameters.WheelScrollLines;
        // "One screen at a time" in Windows' settings.
        if (lines < 0) return delta / 120.0 * (horizontal ? sv.ViewportWidth : sv.ViewportHeight) * 0.875;
        return delta / 120.0 * Math.Max(1, lines) * PxPerLine;
    }

    private static void OnWheel(object sender, MouseWheelEventArgs e)
    {
        if (e.Handled) return;
        bool horizontal = (Keyboard.Modifiers & ModifierKeys.Shift) != 0;
        // Wheel down (negative) scrolls toward the end: down, or right with Shift.
        if (Scroll(e.OriginalSource as DependencyObject, horizontal, -e.Delta, notch: e.Delta % 120 == 0)) e.Handled = true;
    }

    private static void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is not Window w || Hooked.TryGetValue(w, out _)) return;
        if (PresentationSource.FromVisual(w) is not HwndSource src) return;
        Hooked.Add(w, new object());
        src.AddHook((IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled) =>
        {
            if (msg != WmMouseHWheel) return IntPtr.Zero;
            int delta = (short)((wParam.ToInt64() >> 16) & 0xFFFF);
            // Positive is to the right.
            if (Scroll(Mouse.DirectlyOver as DependencyObject, true, delta, notch: delta % 120 == 0))
            {
                handled = true;
                return new IntPtr(1);
            }
            return IntPtr.Zero;
        });
    }

    /// <summary>
    /// Scrolls the innermost area under <paramref name="from"/> that can move that way by <paramref name="delta"/>
    /// (wheel units, positive toward the end). False when none can, or when it is an item-based list (left to WPF).
    /// </summary>
    private static bool Scroll(DependencyObject? from, bool horizontal, int delta, bool notch)
    {
        if (delta == 0) return false;
        for (var d = from; d != null; d = Parent(d))
        {
            if (d is not ScrollViewer sv) continue;
            if (!CanMove(sv, horizontal, delta)) continue;
            if (sv.CanContentScroll) return false;
            var m = Motions.GetValue(sv, _ => new Motion());
            if (!m.Moving)
            {
                m.TargetX = m.X = sv.HorizontalOffset;
                m.TargetY = m.Y = sv.VerticalOffset;
            }
            double px = Pixels(delta, sv, horizontal);
            if (horizontal) m.TargetX = Math.Clamp(m.TargetX + px, 0, sv.ScrollableWidth);
            else m.TargetY = Math.Clamp(m.TargetY + px, 0, sv.ScrollableHeight);
            if (!notch)
            {
                // A touchpad's small step: at once, keeping any notch still easing in.
                if (horizontal) sv.ScrollToHorizontalOffset(m.X = Math.Clamp(m.X + px, 0, sv.ScrollableWidth));
                else sv.ScrollToVerticalOffset(m.Y = Math.Clamp(m.Y + px, 0, sv.ScrollableHeight));
            }
            if (!m.Moving)
            {
                m.Moving = true;
                Moving.Add(sv);
            }
            Start();
            return true;
        }
        return false;
    }

    /// <summary>True when <paramref name="sv"/> scrolls that way and isn't already at its end that way.</summary>
    private static bool CanMove(ScrollViewer sv, bool horizontal, int delta)
    {
        Motions.TryGetValue(sv, out var m);
        bool moving = m is { Moving: true };
        if (horizontal)
        {
            if (sv.HorizontalScrollBarVisibility == ScrollBarVisibility.Disabled || sv.ScrollableWidth <= 0) return false;
            double at = moving ? m!.TargetX : sv.HorizontalOffset;
            return delta > 0 ? at < sv.ScrollableWidth - 0.5 : at > 0.5;
        }
        if (sv.VerticalScrollBarVisibility == ScrollBarVisibility.Disabled || sv.ScrollableHeight <= 0) return false;
        double y = moving ? m!.TargetY : sv.VerticalOffset;
        return delta > 0 ? y < sv.ScrollableHeight - 0.5 : y > 0.5;
    }

    private static DependencyObject? Parent(DependencyObject d) =>
        d is Visual or System.Windows.Media.Media3D.Visual3D ? VisualTreeHelper.GetParent(d) ?? LogicalTreeHelper.GetParent(d) : LogicalTreeHelper.GetParent(d);

    private static void Start()
    {
        if (_rendering) return;
        _rendering = true;
        _lastFrame = TimeSpan.Zero;
        CompositionTarget.Rendering += OnFrame;
    }

    private static void OnFrame(object? sender, EventArgs e)
    {
        var now = e is RenderingEventArgs r ? r.RenderingTime : TimeSpan.Zero;
        double dt = _lastFrame == TimeSpan.Zero ? 1 / 60.0 : Math.Clamp((now - _lastFrame).TotalSeconds, 0.001, 0.1);
        if (now == _lastFrame && _lastFrame != TimeSpan.Zero) return; // the same frame again
        _lastFrame = now;
        double k = 1 - Math.Exp(-dt / EaseSeconds);
        for (int i = Moving.Count - 1; i >= 0; i--)
        {
            var sv = Moving[i];
            if (!Motions.TryGetValue(sv, out var m)) { Moving.RemoveAt(i); continue; }
            // The content may have changed size since.
            m.TargetX = Math.Clamp(m.TargetX, 0, sv.ScrollableWidth);
            m.TargetY = Math.Clamp(m.TargetY, 0, sv.ScrollableHeight);
            double dx = m.TargetX - m.X, dy = m.TargetY - m.Y;
            bool doneX = Math.Abs(dx) < 0.5, doneY = Math.Abs(dy) < 0.5;
            if (!sv.IsVisible) doneX = doneY = true;
            m.X = doneX ? m.TargetX : m.X + dx * k;
            m.Y = doneY ? m.TargetY : m.Y + dy * k;
            sv.ScrollToHorizontalOffset(m.X);
            sv.ScrollToVerticalOffset(m.Y);
            if (doneX && doneY)
            {
                m.Moving = false;
                Moving.RemoveAt(i);
            }
        }
        if (Moving.Count == 0)
        {
            CompositionTarget.Rendering -= OnFrame;
            _rendering = false;
        }
    }
}
