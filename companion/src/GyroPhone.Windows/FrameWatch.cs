using System.ComponentModel;
using System.Diagnostics;
using GyroPhone.Host.Perf;

namespace GyroPhone.Windows;

/// <summary>
/// The game's frames, read right here with <see cref="EtwPresents"/>: only works while the companion runs as
/// administrator. Otherwise <see cref="FpsHelperSource"/> gets them from the FPS helper.
/// </summary>
public sealed class FrameWatch : IFrameSource
{
    private static readonly double UsPerTick = 1_000_000.0 / Stopwatch.Frequency;
    private readonly EtwPresents _etw;

    public FrameWatch(Action<string> log) => _etw = new EtwPresents(log);

    public FrameWatchState Start(Action<int, long> present, out string? error)
    {
        error = null;
        int rc = _etw.Start((pid, qpc) => present(pid, (long)(qpc * UsPerTick)), out var step);
        if (rc == 0) return FrameWatchState.On;
        if (rc == EtwPresents.ErrorAccessDenied) return FrameWatchState.NeedsPermission;
        error = $"{step}: {new Win32Exception(rc).Message}";
        return FrameWatchState.Failed;
    }

    public void Stop() => _etw.Stop();

    public void Dispose() => _etw.Dispose();
}
