using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Host.Abstractions;

/// <summary>Keyboard and mouse injection (SendInput on Windows). One call per Flush, only with events.</summary>
public interface IKeyMouseSink
{
    /// <summary>False when the system took fewer events than given (an elevated window or the lock screen is in front).</summary>
    bool Inject(ReadOnlySpan<InjectEvent> events);
}
