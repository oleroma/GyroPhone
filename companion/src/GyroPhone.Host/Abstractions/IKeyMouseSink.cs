using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Host.Abstractions;

/// <summary>Keyboard and mouse injection (SendInput on Windows). One call per Flush, only with events.</summary>
public interface IKeyMouseSink
{
    void Inject(ReadOnlySpan<InjectEvent> events);
}
