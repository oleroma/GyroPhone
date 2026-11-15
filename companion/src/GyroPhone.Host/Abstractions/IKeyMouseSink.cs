using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Host.Abstractions;

/// <summary>
/// Keyboard and mouse output: SendInput, or the optional GyroPhone input driver (a virtual USB mouse and keyboard)
/// on Windows. One call per Flush, only with events.
/// </summary>
public interface IKeyMouseSink
{
    /// <summary>
    /// False when the events didn't all arrive: Windows took fewer than given (an elevated window or the lock screen
    /// is in front), or the input driver's device went away (the next call goes through SendInput).
    /// </summary>
    bool Inject(ReadOnlySpan<InjectEvent> events);

    /// <summary>Where the last Inject went, for the logs: "SendInput" or "input driver".</summary>
    string Route => "SendInput";
}
