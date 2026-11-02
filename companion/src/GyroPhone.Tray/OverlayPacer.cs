using System.Runtime.InteropServices;

namespace GyroPhone.Tray;

/// <summary>
/// Paces the overlay windows to the screen: a background thread waits for each refresh of the
/// desktop compositor (DwmFlush) and asks the UI thread to run <see cref="_tick"/> once, never
/// queueing a second before the first ran. It sleeps while no overlay window is open.
/// </summary>
internal sealed class OverlayPacer : IDisposable
{
    private readonly Control _ui;
    private readonly Action _tick;
    private readonly Action<string> _log;
    private readonly ManualResetEventSlim _running = new(false);
    private readonly Thread _thread;
    private int _pending;
    private volatile bool _stop;

    public OverlayPacer(Control ui, Action tick, Action<string> log)
    {
        _ui = ui;
        _tick = tick;
        _log = log;
        _thread = new Thread(Loop) { IsBackground = true, Name = "overlay pacer" };
        _thread.Start();
    }

    /// <summary>On while any overlay window is open.</summary>
    public void SetRunning(bool on)
    {
        if (on) _running.Set();
        else _running.Reset();
    }

    private void Loop()
    {
        bool dwmFailed = false;
        while (!_stop)
        {
            _running.Wait();
            if (_stop) return;
            // One wait per screen refresh; without the compositor, about 60 Hz.
            if (dwmFailed || DwmFlush() != 0)
            {
                if (!dwmFailed) _log("overlay: no DwmFlush, pacing at 60 Hz");
                dwmFailed = true;
                Thread.Sleep(16);
            }
            if (Interlocked.CompareExchange(ref _pending, 1, 0) != 0) continue;
            try
            {
                _ui.BeginInvoke(() =>
                {
                    Volatile.Write(ref _pending, 0);
                    _tick();
                });
            }
            catch (Exception e) when (e is InvalidOperationException or ObjectDisposedException)
            {
                return; // exiting: the marshalling window is gone
            }
        }
    }

    public void Dispose()
    {
        _stop = true;
        _running.Set();
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmFlush();
}
