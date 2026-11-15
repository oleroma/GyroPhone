using GyroPhone.Host.Settings;
using GyroPhone.Protocol;

namespace GyroPhone.Host.Split;

/// <summary>
/// What the companion (the control panel) asks of the input process, over the <see cref="PipeChannel"/>. The input
/// process answers each call on its pipe thread, never on the input path. Every call returns at once: anything slow
/// (a driver retry, a firewall prompt) runs on another thread and shows in the next <see cref="InputStatus"/>.
/// </summary>
public interface IInputControl
{
    /// <summary>The companion attached: the shared memory's name and the layout it has, the build, and the status now.</summary>
    InputHello Attach(int companionPid);

    /// <summary>The engine's state now (the pair window wants it fresh; everything else reads the pushed status).</summary>
    HostSnapshot Snapshot();

    /// <summary>Tray Exit (or an update): the input process releases everything, says goodbye to the phone, saves and exits.</summary>
    void Exit();

    // Gamepad and input
    void SetOutputMode(OutputMode mode);
    void SetPaused(bool paused);
    /// <summary>Session lock or sleep: release-all, inputs held back until unlocked.</summary>
    void SetLocked(bool locked);
    /// <summary>Looks for the gamepad driver again; null when found, else why not.</summary>
    string? RetryDriver();
    void SetCatchUpMs(int ms);
    /// <summary>"Use SendInput" even when the input driver is installed.</summary>
    void SetUseSendInput(bool on);

    // Pairing
    void OpenPairingWindow();
    void AnswerPair(uint attemptId, bool allow);
    /// <summary>A one-time QR pairing token (the phone that scans it is saved as a key file), or null without a PC key.</summary>
    byte[]? OpenQrPairing();
    void CloseQrPairing();
    /// <summary>This PC's public key (null without one), for the QR code.</summary>
    byte[]? HostPublicKey();
    /// <summary>Forgets a code-paired phone, or deletes a key-paired phone's key file.</summary>
    void Forget(PairedPhoneInfo phone);

    // Connection
    void SetUsbDirect(bool on);
    void TakeUsbFromAdb();
    /// <summary>The active phone's address, for naming the way the packets go (null without a phone).</summary>
    string? PhoneAddress();

    // App and Diagnostics
    void SetStartWithWindows(bool on);
    void SetDiagnostics(bool on);
    void SetDiagnosticsMinutes(int minutes);
    /// <summary>Diagnostics' Save: the phone saves its side; the companion is told to save the PC's (event "debug-save").</summary>
    void SaveDiagnostics();
    void SetPressKeyListen(bool on);
    void SetPressKeyNotice(bool on);

    // Measure buttons and Performance
    void SetMeasureButtons(bool on);
    /// <summary>The Performance page shows the link (the phone's stats are wanted each second).</summary>
    void SetLinkWatch(bool on);
    /// <summary>
    /// The Delivery test window is open (<paramref name="on"/>) and in front (<paramref name="counting"/>): the engine's
    /// mouse moves also go to the shared <see cref="MoveRing"/>, and the delivery probe adds up the test's moves.
    /// </summary>
    void SetDeliveryTest(bool on, bool counting);
    /// <summary>The delivery probe's numbers for the running Delivery test, or null without one.</summary>
    DeliveryNumbers? DeliveryTestNumbers();

    // Overlays
    /// <summary>What the companion's overlays ask of the phone (<see cref="HostEngine.SetOverlay"/>).</summary>
    void SetOverlay(bool on, bool compass, bool samples);
}

/// <summary>The names both processes use in the user's Windows session.</summary>
public static class SplitNames
{
    /// <summary>The shared memory (<see cref="SharedRegion.CreateOrOpen"/>).</summary>
    public const string Region = @"Local\GyroPhoneShared";
    /// <summary>Set by the input process when it put something in the overlay ring.</summary>
    public const string OverlayWake = @"Local\GyroPhoneOverlayWake";
    /// <summary>The input process's single-instance lock.</summary>
    public const string InputMutex = @"Local\GyroPhoneInput";

    /// <summary>The pipe a companion connects to: one per user and Windows session.</summary>
    public static string Pipe(int sessionId) => $"GyroPhoneInput.{Environment.UserName}.{sessionId}";
}

/// <summary>What the input process tells a companion that attaches.</summary>
public sealed record InputHello(string Version, int Pid, string SharedName, int LayoutVersion, string OverlayWakeName,
    InputStatus Status);

/// <summary>
/// The input process's state for the tray and the windows, pushed as event "status" when it changes (and once a
/// second while a phone is connected).
/// </summary>
public sealed record InputStatus(
    HostSnapshot Snapshot,
    InputSettingsView Settings,
    // The UDP server: Running, PortInUse, Failed or Stopped.
    string Server,
    bool BindRetrying,
    bool BindWaitLong,
    string? KeyError,
    bool KeyRetrying,
    string? PadError,
    bool InputDriverReady,
    bool InputDriverInstalled,
    bool UsbEnabled,
    string UsbState,
    string UsbDetail,
    string? AuthorizedScan,
    long SendInputShortWrites,
    long RxExceptions,
    ButtonLatency? Buttons,
    bool Elevated,
    string KeysFolder,
    string? LogError,
    int DiagnosticsSaved);

/// <summary>The input process's own settings (settings.json) as the windows show them.</summary>
public sealed record InputSettingsView(
    string HostName,
    OutputMode OutputMode,
    bool StartWithWindows,
    bool UsbDirect,
    bool MeasureButtons,
    bool Diagnostics,
    int DiagnosticsMinutes,
    bool UseSendInput,
    bool PressKeyListen,
    bool PressKeyNotice,
    int CatchUpMs)
{
    public static InputSettingsView Of(HostSettings s) => new(s.HostName, s.OutputMode, s.StartWithWindows, s.UsbDirect,
        s.MeasureButtons, s.Diagnostics, s.DiagnosticsMinutes, s.UseSendInput, s.PressKeyListen, s.PressKeyNotice, s.CatchUpMs);
}

/// <summary>A Diagnostics line from the input process (event "debug").</summary>
public sealed record DebugLine(long Us, DebugTrace.Kind Kind, string Text);

/// <summary>Pairing as the pair window sees it (the input process, through the companion).</summary>
public interface IPairing
{
    HostSnapshot Snapshot();
    byte[]? HostPublicKey { get; }
    void OpenPairingWindow();
    void AnswerPair(uint attemptId, bool allow);
    byte[]? OpenQrPairing();
    void CloseQrPairing();
}
