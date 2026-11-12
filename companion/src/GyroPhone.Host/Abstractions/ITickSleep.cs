namespace GyroPhone.Host.Abstractions;

/// <summary>
/// Sleeps the calling thread for a set time, as exactly as the system allows. The watchdog thread makes
/// one for itself and uses it for every tick. On Windows it is a high-resolution waitable timer, so a
/// 50 ms tick is not stretched to the next 15.6 ms system timer step.
/// </summary>
public interface ITickSleep : IDisposable
{
    void Sleep(long us);
}

/// <summary>Thread.Sleep, rounded up to whole milliseconds: the fallback where no exact timer exists.</summary>
public sealed class PlainTickSleep : ITickSleep
{
    public void Sleep(long us) => Thread.Sleep((int)Math.Clamp((us + 999) / 1000, 1, int.MaxValue));

    public void Dispose()
    {
    }
}
