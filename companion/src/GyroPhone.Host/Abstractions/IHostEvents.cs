namespace GyroPhone.Host.Abstractions;

/// <summary>
/// Notifications for the UI. Called under the engine lock from the rx or watchdog thread, so
/// implementations only marshal to their own thread and must never call back into the engine.
/// </summary>
public interface IHostEvents
{
    /// <summary>Session, pairing window, paired list, output or flags changed; re-read HostEngine.Snapshot().</summary>
    void StateChanged();

    /// <summary>Counters and lifecycle messages. Never keys.</summary>
    void Log(string message);
}

/// <summary>No-op events.</summary>
public sealed class NullHostEvents : IHostEvents
{
    public static readonly NullHostEvents Instance = new();

    public void StateChanged() { }

    public void Log(string message) { }
}
