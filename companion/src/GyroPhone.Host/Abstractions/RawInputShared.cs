namespace GyroPhone.Host.Abstractions;

/// <summary>
/// What raw input readers in projects without the Windows code (the latency test window) need from it, set by the tray
/// app at start: whether a raw input device is the input driver's own mouse or keyboard (GyroPhone's output, like
/// SendInput's input with no device), and a notice that the reader gave the process's raw mouse input up (so
/// Diagnostics' delivery probe takes it again).
/// </summary>
public static class RawInputShared
{
    public static Func<IntPtr, bool> IsOwnDevice { get; set; } = _ => false;
    public static Action RawMouseReleased { get; set; } = () => { };
}
