using System.Diagnostics;

namespace GyroPhone.Host.Abstractions;

/// <summary>Monotonic microsecond clock.</summary>
public interface IClock
{
    long NowUs { get; }
}

/// <summary><see cref="Stopwatch"/>-based clock.</summary>
public sealed class StopwatchClock : IClock
{
    public static readonly StopwatchClock Instance = new();
    private static readonly double UsPerTick = 1_000_000.0 / Stopwatch.Frequency;

    public long NowUs => (long)(Stopwatch.GetTimestamp() * UsPerTick);
}
