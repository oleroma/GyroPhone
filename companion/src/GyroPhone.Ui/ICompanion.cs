using GyroPhone.Host;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Ui;

/// <summary>Something wrong that stops the phone or the gamepad from working, with the button that fixes it.</summary>
public sealed record Problem(string Text, string? FixLabel = null, Action? Fix = null);

/// <summary>What the USB cable needs from the user, besides turning it on.</summary>
public enum UsbHelp
{
    None,
    /// <summary>adb holds the phone: offer to stop adb and use the cable.</summary>
    AdbBusy,
    /// <summary>The accessory interface has no WinUSB driver: offer the how-to.</summary>
    NeedsDriver,
}

/// <summary>Everything the windows show besides the overlay settings; the tray app builds it on each refresh.</summary>
public sealed record CompanionView(
    HostSnapshot Status,
    string Version,
    string StatusLine,
    string? UpdatePending,
    bool AutoStart,
    bool Watcher,
    TrayClickAction TrayClick,
    bool UsbDirect,
    string UsbDirectText,
    UsbHelp UsbHelp,
    bool? FirewallOk,
    bool? UsbRuleOk,
    bool Elevated,
    string ServerText,
    string? PadError,
    string HostKey,
    string KeysFolder,
    string LogPath,
    IReadOnlyList<Problem> Problems,
    bool MeasureButtons = false,
    ButtonLatency? Buttons = null,
    bool InputLog = true,
    int CatchUpMs = 16,
    bool PressKeyNotice = true,
    bool PressKeyListen = true);

/// <summary>
/// The tray app as the windows see it. Every setter saves the setting and refreshes the open windows,
/// so controls never change themselves: they ask, and the next refresh shows what is saved.
/// All calls are on the UI thread.
/// </summary>
public interface ICompanion
{
    CompanionView View();

    /// <summary>The full status text (Home's "Details").</summary>
    string DetailsText();

    // Latency
    void ToggleMeasureButtons();
    /// <summary>The latency test window is open: the phone shows its tracking markers.</summary>
    void SetLatencyScreen(bool on);
    /// <summary>A line in the companion's log.</summary>
    void Log(string message);

    // Gamepad and input
    void SetOutputMode(OutputMode mode);
    void TogglePaused();
    void OpenDriverDownload();
    void RetryDriver();

    // Phones
    void PairNewPhone();
    void Forget(PairedPhoneInfo phone);
    void OpenKeysFolder();

    // Connection
    void OpenNetworkSettings();
    void AllowThroughFirewall();
    void AllowUsb();
    void ToggleUsbDirect();
    void TakeUsbFromAdb();
    void ShowUsbDriverHelp();
    void RestartAsAdmin();

    // App
    void ToggleAutoStart();
    void ToggleWatcher();
    void SetTrayClick(TrayClickAction action);
    void OpenLogFolder();
    void ShowDebugWindow();
    void ToggleInputLog();
    /// <summary>"Use this PC's keys for Press a key".</summary>
    void TogglePressKeyListen();
    /// <summary>"Notify when the phone listens for a key".</summary>
    void TogglePressKeyNotice();
    /// <summary>"Catch-up time", ms (0 = off).</summary>
    void SetCatchUpMs(int ms);

    // Performance
    /// <summary>The Performance page's frame watcher, link seconds and test records.</summary>
    GyroPhone.Host.Perf.PerfMonitor Perf { get; }
    /// <summary>
    /// Adds the user to Windows' "Performance Log Users" (one admin prompt), so the frame watcher works without
    /// admin from the next sign-in. True when added.
    /// </summary>
    bool AllowFrameWatch();
    /// <summary>The way the phone's packets go now ("Phone hotspot · PC Wi-Fi") and which Record Setup that is, or null without a phone.</summary>
    (string Text, string? Setup)? LinkPath();
    void OpenPerfFolder();
    /// <summary>Opens a file in its default program.</summary>
    void OpenFile(string path);

    // Overlays
    IReadOnlyList<OverlaySettings> Overlays { get; }
    OverlaySettings? OverlayOf(string id);
    string OverlayName(OverlaySettings o);
    /// <summary>Overlays saved as presets, named by their <see cref="OverlaySettings.Label"/>.</summary>
    IReadOnlyList<OverlaySettings> OverlayPresets { get; }
    void SaveOverlayPreset(string id, string name);
    /// <summary>Adds a new overlay window from a preset; returns its id (null: the preset is gone).</summary>
    string? AddOverlayFromPreset(string presetId);
    void RenameOverlayPreset(string presetId, string name);
    void RemoveOverlayPreset(string presetId);
    string? SelectedOverlay { get; }
    void SelectOverlay(string? id);
    void SetOverlayShown(string id, bool shown);
    bool OverlaysHidden { get; }
    void SetOverlaysHidden(bool hidden);
    bool OverlayEdit { get; }
    void SetOverlayEdit(bool on);
    void ChangeOverlay(string id, Action<OverlaySettings> change);
    string AddOverlay(OverlayKind kind);
    void RemoveOverlay(string id);
    void LevelOverlay(string id);
    /// <summary>Clears laser pointer overlay <paramref name="id"/>'s paint and trail.</summary>
    void ClearOverlay(string id);
    void LevelAll();
    /// <summary>"Fade when phone rests", for every overlay.</summary>
    OverlayRestFade RestFade { get; }
    void ChangeRestFade(Action<OverlayRestFade> change);
    /// <summary>The phone's turning speed now (deg/s) from its overlay data, or null without any lately.</summary>
    float? StillnessDps { get; }

    // Overlay shortcuts
    OverlayShortcuts Shortcuts { get; }
    bool ShortcutTaken(OverlayAction a);
    void SetShortcut(OverlayAction a, string keys);
    /// <summary>A shortcut field is taking keys: the shortcuts let go meanwhile, so the press reaches it.</summary>
    void PauseShortcuts(bool paused);
    /// <summary>"Ctrl+Alt+R" for a key press (Win is read from the keyboard), or null when it can't be a shortcut.</summary>
    string? FormatShortcut(int virtualKey, bool ctrl, bool alt, bool shift);

    /// <summary>Windows' colour picker, owned by <paramref name="owner"/>; the picked colour (alpha kept), or null.</summary>
    int? PickColor(int argb, IntPtr owner);
}
