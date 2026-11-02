using System.Runtime.InteropServices;
using GyroPhone.Host.Settings;

namespace GyroPhone.Tray;

/// <summary>
/// The overlays' global shortcuts (<see cref="OverlayShortcuts"/>), held by a hidden message
/// window so they work with no overlay window open (Hide all brings them back). Text form:
/// "Ctrl+Alt+R": modifiers Ctrl, Alt, Shift, Win, then one key.
/// </summary>
internal sealed class Hotkeys : NativeWindow, IDisposable
{
    private readonly Action<OverlayAction> _pressed;
    private readonly Dictionary<int, OverlayAction> _held = [];
    private readonly HashSet<OverlayAction> _taken = [];
    private bool _paused;

    public Hotkeys(Action<OverlayAction> pressed)
    {
        _pressed = pressed;
        // A message-only window: never shown, still gets WM_HOTKEY.
        CreateHandle(new CreateParams { Parent = new IntPtr(-3) });
    }

    /// <summary>Actions whose keys another app already holds (at the last <see cref="Apply"/>).</summary>
    public bool IsTaken(OverlayAction a) => _taken.Contains(a);

    /// <summary>Holds the keys in <paramref name="s"/>, letting go of the old ones; returns the actions whose keys were taken.</summary>
    public IReadOnlyCollection<OverlayAction> Apply(OverlayShortcuts s)
    {
        ReleaseAll();
        _taken.Clear();
        if (_paused || Handle == IntPtr.Zero) return _taken;
        foreach (var a in OverlayShortcuts.All)
        {
            if (Parse(s.Get(a)) is not { } k) continue;
            int id = BaseId + (int)a;
            if (RegisterHotKey(Handle, id, k.Mods | ModNoRepeat, (uint)k.Key)) _held[id] = a;
            else _taken.Add(a);
        }
        return _taken;
    }

    /// <summary>While a shortcut field is taking keys: let go of them all, so the press reaches it.</summary>
    public void Pause(bool paused, OverlayShortcuts s)
    {
        _paused = paused;
        Apply(s);
    }

    private void ReleaseAll()
    {
        if (Handle != IntPtr.Zero)
            foreach (var id in _held.Keys) UnregisterHotKey(Handle, id);
        _held.Clear();
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WmHotkey && _held.TryGetValue(m.WParam.ToInt32(), out var a))
        {
            _pressed(a);
            return;
        }
        base.WndProc(ref m);
    }

    public void Dispose()
    {
        ReleaseAll();
        DestroyHandle();
    }

    /// <summary>"Ctrl+Alt+R" for a key press (null when it can't be a shortcut: a bare modifier, or no modifier on a key that isn't F1..F24).</summary>
    public static string? Format(Keys keyData)
    {
        var key = keyData & Keys.KeyCode;
        if (key is Keys.ControlKey or Keys.ShiftKey or Keys.Menu or Keys.LWin or Keys.RWin or Keys.None or
            Keys.LControlKey or Keys.RControlKey or Keys.LShiftKey or Keys.RShiftKey or Keys.LMenu or Keys.RMenu)
            return null;
        bool win = IsDown(Keys.LWin) || IsDown(Keys.RWin);
        var parts = new List<string>();
        if ((keyData & Keys.Control) != 0) parts.Add("Ctrl");
        if ((keyData & Keys.Alt) != 0) parts.Add("Alt");
        if ((keyData & Keys.Shift) != 0) parts.Add("Shift");
        if (win) parts.Add("Win");
        bool fKey = key is >= Keys.F1 and <= Keys.F24;
        if (parts.Count == 0 && !fKey) return null;
        parts.Add(KeyName(key));
        return string.Join("+", parts);
    }

    /// <summary>Modifiers (RegisterHotKey's MOD_ flags) and key of "Ctrl+Alt+R", or null.</summary>
    public static (uint Mods, Keys Key)? Parse(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        uint mods = 0;
        Keys? key = null;
        foreach (var raw in text.Split('+', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            switch (raw.ToLowerInvariant())
            {
                case "ctrl": case "control": mods |= ModControl; continue;
                case "alt": mods |= ModAlt; continue;
                case "shift": mods |= ModShift; continue;
                case "win": mods |= ModWin; continue;
            }
            if (key != null) return null;
            if (raw.Length == 1 && char.IsDigit(raw[0])) key = Keys.D0 + (raw[0] - '0');
            else if (Enum.TryParse<Keys>(raw, true, out var k) && (k & Keys.Modifiers) == 0) key = k;
            else return null;
        }
        return key is { } kk ? (mods, kk) : null;
    }

    private static string KeyName(Keys k) => k switch
    {
        >= Keys.D0 and <= Keys.D9 => ((char)('0' + (k - Keys.D0))).ToString(),
        _ => k.ToString(),
    };

    private static bool IsDown(Keys k) => (GetAsyncKeyState((int)k) & 0x8000) != 0;

    private const int WmHotkey = 0x0312;
    private const int BaseId = 0x4760;
    private const uint ModAlt = 0x1, ModControl = 0x2, ModShift = 0x4, ModWin = 0x8, ModNoRepeat = 0x4000;

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    [DllImport("user32.dll")]
    private static extern short GetAsyncKeyState(int vKey);
}
