using System.Buffers.Binary;
using System.ComponentModel;
using System.Diagnostics;
using System.IO.Pipes;
using System.Security.Principal;
using GyroPhone.Host.Perf;

namespace GyroPhone.Windows;

/// <summary>
/// The game's frames without admin: from the FPS helper (<see cref="FrameHelperWire"/>), which a Windows task runs
/// as administrator. Needs the helper set up once (<see cref="FpsHelperSetup"/>). Running as administrator itself,
/// the companion reads them directly (<see cref="FrameWatch"/>).
/// </summary>
public sealed class FpsHelperSource : IFrameSource
{
    public const string OutdatedText = "the FPS helper is from an older GyroPhone";
    private static readonly double UsPerTick = 1_000_000.0 / Stopwatch.Frequency;
    private static readonly TimeSpan TaskRunGap = TimeSpan.FromSeconds(15);

    private readonly Action<string> _log;
    private readonly FrameWatch _direct;
    private bool _usingDirect;
    private CancellationTokenSource? _stop;
    private Task? _run;

    public FpsHelperSource(Action<string> log)
    {
        _log = log;
        _direct = new FrameWatch(log);
    }

    public FrameWatchState Start(Action<int, long> present, out string? error)
    {
        error = null;
        Stop();
        if (ElevationProbe.IsSelfElevated)
        {
            _usingDirect = true;
            return _direct.Start(present, out error);
        }
        switch (FpsHelperSetup.Installed())
        {
            case null:
                return FrameWatchState.NeedsPermission;
            case int v when v != FrameHelperWire.Version:
                error = OutdatedText;
                return FrameWatchState.NeedsPermission;
        }
        var stop = new CancellationTokenSource();
        _stop = stop;
        _run = Task.Run(() => Run(present, stop.Token));
        return FrameWatchState.On;
    }

    public void Stop()
    {
        if (_usingDirect)
        {
            _direct.Stop();
            _usingDirect = false;
        }
        if (_stop is { } s)
        {
            s.Cancel();
            try { _run?.Wait(2000); }
            catch (AggregateException) { }
            s.Dispose();
            _stop = null;
            _run = null;
        }
    }

    public void Dispose()
    {
        Stop();
        _direct.Dispose();
    }

    /// <summary>Connects to the helper (starting its task when it isn't running) and passes its frames on, until stopped.</summary>
    private async Task Run(Action<int, long> present, CancellationToken stop)
    {
        string sid;
        using (var id = WindowsIdentity.GetCurrent()) sid = id.User?.Value ?? "";
        string name = FrameHelperWire.PipeName(sid);
        var lastTaskRun = DateTime.MinValue;
        string? lastTrouble = null;
        var buf = new byte[FrameHelperWire.RecordSize * 256];
        while (!stop.IsCancellationRequested)
        {
            string? trouble = null;
            try
            {
                using var pipe = new NamedPipeClientStream(".", name, PipeDirection.In, PipeOptions.Asynchronous);
                try
                {
                    await pipe.ConnectAsync(500, stop).ConfigureAwait(false);
                }
                catch (TimeoutException)
                {
                    trouble = "the FPS helper isn't running";
                    if (DateTime.UtcNow - lastTaskRun >= TaskRunGap)
                    {
                        lastTaskRun = DateTime.UtcNow;
                        RunTask();
                    }
                }
                if (pipe.IsConnected)
                {
                    var hello = new byte[FrameHelperWire.HelloSize];
                    await pipe.ReadExactlyAsync(hello, stop).ConfigureAwait(false);
                    uint magic = BinaryPrimitives.ReadUInt32LittleEndian(hello);
                    int version = BinaryPrimitives.ReadInt32LittleEndian(hello.AsSpan(4));
                    int rc = BinaryPrimitives.ReadInt32LittleEndian(hello.AsSpan(8));
                    if (magic != FrameHelperWire.Magic || version != FrameHelperWire.Version)
                    {
                        _log($"frame watch: the FPS helper answers version {version}, not {FrameHelperWire.Version}: set it up again");
                        return;
                    }
                    if (rc != 0)
                    {
                        _log($"frame watch: the FPS helper can't watch frames: {new Win32Exception(rc).Message}");
                        return;
                    }
                    _log("frame watch: on, through the FPS helper");
                    lastTrouble = null;
                    await Pass(pipe, buf, present, stop).ConfigureAwait(false);
                    trouble = "the FPS helper closed the pipe";
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or EndOfStreamException)
            {
                trouble = $"FPS helper pipe: {e.GetType().Name}: {e.Message}";
            }
            if (trouble != null && trouble != lastTrouble) _log($"frame watch: {trouble}");
            lastTrouble = trouble;
            try { await Task.Delay(1000, stop).ConfigureAwait(false); }
            catch (OperationCanceledException) { return; }
        }
    }

    private static async Task Pass(NamedPipeClientStream pipe, byte[] buf, Action<int, long> present, CancellationToken stop)
    {
        const int size = FrameHelperWire.RecordSize;
        int have = 0;
        while (true)
        {
            int n = await pipe.ReadAsync(buf.AsMemory(have), stop).ConfigureAwait(false);
            if (n == 0) return;
            have += n;
            int whole = have / size * size;
            for (int i = 0; i < whole; i += size)
            {
                int pid = BinaryPrimitives.ReadInt32LittleEndian(buf.AsSpan(i));
                if (pid == 0) continue; // heartbeat
                long qpc = BinaryPrimitives.ReadInt64LittleEndian(buf.AsSpan(i + 4));
                present(pid, (long)(qpc * UsPerTick));
            }
            Buffer.BlockCopy(buf, whole, buf, 0, have - whole);
            have -= whole;
        }
    }

    /// <summary>Starts the helper's task (it starts by itself at sign-in; this covers it being closed since).</summary>
    private void RunTask()
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo("schtasks.exe", $"/run /tn \"{FrameHelperWire.TaskName}\"")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            });
            if (p == null) return;
            string err = p.StandardError.ReadToEnd();
            p.WaitForExit(5000);
            _log($"frame watch: started the FPS helper's task, exit {p.ExitCode}{(err.Trim().Length > 0 ? $" ({err.Trim()})" : "")}");
        }
        catch (Exception e) when (e is Win32Exception or InvalidOperationException)
        {
            _log($"frame watch: can't start the FPS helper's task: {e.Message}");
        }
    }
}
