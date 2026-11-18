using System.Diagnostics;
using System.IO.Pipes;
using System.Text.Json;
using GyroPhone.Host;
using GyroPhone.Host.Split;
using GyroPhone.Windows;

namespace GyroPhone.Tray;

/// <summary>
/// The companion's line to the input process (<see cref="InputHost"/>): starts it when none runs, connects, and keeps
/// the newest <see cref="InputStatus"/> it pushes. When the input process goes away without being asked to (a crash),
/// it is started again (and the new one lets go of anything the old one held, from the held-keys marker). Calls through
/// <see cref="Input"/> never throw: while there is no line they do nothing and return defaults, so the windows keep
/// working through a restart.
/// </summary>
internal sealed class InputClient : IDisposable
{
    /// <summary>The input process's young-object budget (64 MB): it allocates almost nothing, so collections stay rare.</summary>
    private const string Gen0Size = "0x4000000";
    private const int ConnectWaitMs = 15_000;
    private const int CallTimeoutMs = 5_000;

    private readonly string _exePath;
    private readonly Action<string> _log;
    private readonly string _startArgs;
    private volatile PipeChannel? _channel;
    private volatile InputStatus? _status;
    private volatile bool _stopping;
    /// <summary>Set by <see cref="ExitInput"/> (with _stopping, under _gate): the input process is to exit too.</summary>
    private bool _exitAsked;
    private readonly object _gate = new();
    private readonly ManualResetEventSlim _stopped = new();
    private Thread? _thread;
    private readonly Queue<long> _restarts = new();

    /// <param name="startArgs">Passed to the first input process this companion starts (--woken, --updated: wait longer for locked files).</param>
    public InputClient(string exePath, Action<string> log, string startArgs)
    {
        _exePath = exePath;
        _log = log;
        _startArgs = startArgs;
        Input = PipeChannel.Proxy<IInputControl>(() => _channel, TimeSpan.FromMilliseconds(CallTimeoutMs),
            (method, e) =>
            {
                // Before the first attach (the overlays at start) there is nothing to tell yet: it learns on attach.
                if (e is PipeClosedException && Hello == null) return;
                _log($"input: {method} not done: {e.GetType().Name}: {e.Message}");
            });
    }

    /// <summary>The input process's commands; never throw (see the class).</summary>
    public IInputControl Input { get; }

    /// <summary>The newest status, or null before the first attach.</summary>
    public InputStatus? Status => _status;

    /// <summary>The input process attached now.</summary>
    public InputHello? Hello { get; private set; }

    public bool Attached => _channel != null;

    /// <summary>An event from the input process (on the pipe thread); "status" also updates <see cref="Status"/> first.</summary>
    public event Action<string, JsonElement>? Event;

    /// <summary>Attached to an input process (the first, or one started again), on the connecting thread.</summary>
    public event Action? Connected;

    /// <summary>The input process went away without being asked to (on the pipe thread).</summary>
    public event Action? Lost;

    /// <summary>Connects (starting the input process first when none runs) on its own thread, and again after a crash.</summary>
    public void Start()
    {
        _thread = new Thread(Run) { Name = "gp-input-link", IsBackground = true, Priority = ThreadPriority.BelowNormal };
        _thread.Start();
    }

    private void Run()
    {
        bool first = true;
        while (!_stopping)
        {
            var channel = Connect(first);
            first = false;
            if (channel == null)
            {
                if (Wait(RestartDelayMs())) return;
                continue;
            }
            using var gone = new ManualResetEventSlim();
            channel.Closed += gone.Set;
            if (channel.IsClosed) gone.Set();
            bool exit, stop;
            lock (_gate)
            {
                exit = _exitAsked;
                stop = _stopping;
                if (!stop) _channel = channel;
            }
            if (stop)
            {
                // Exit came while this was connecting (ExitInput found no channel): this one is told here.
                if (exit) TellExit(channel, Hello?.Pid ?? 0);
                else channel.Close();
                return;
            }
            Connected?.Invoke();
            gone.Wait();
            _channel = null;
            if (_stopping) return;
            _log("input: the input process went away; starting it again");
            Lost?.Invoke();
            if (Wait(RestartDelayMs())) return;
        }
    }

    /// <summary>Waits <paramref name="ms"/> or until stopped; true when stopped.</summary>
    private bool Wait(int ms) => _stopped.Wait(ms);

    /// <summary>Quick at first; slower when it keeps crashing (5 restarts within a minute: every 10 s).</summary>
    private int RestartDelayMs()
    {
        long now = Environment.TickCount64;
        while (_restarts.Count > 0 && now - _restarts.Peek() > 60_000) _restarts.Dequeue();
        _restarts.Enqueue(now);
        return _restarts.Count > 5 ? 10_000 : 500;
    }

    /// <summary>Starts the input process if none runs, connects and attaches; null when that failed (tried again later).</summary>
    private PipeChannel? Connect(bool first)
    {
        for (int round = 0; round < 2 && !_stopping; round++)
        {
            if (!InputHost.IsRunning() && !Launch(first ? _startArgs : "")) return null;
            var pipe = new NamedPipeClientStream(".", InputHost.PipeName, PipeDirection.InOut,
                PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
            try
            {
                pipe.Connect(ConnectWaitMs);
            }
            catch (Exception e) when (e is TimeoutException or IOException or UnauthorizedAccessException)
            {
                pipe.Dispose();
                _log($"input: no answer on the pipe: {e.GetType().Name}: {e.Message}");
                return null;
            }
            var channel = new PipeChannel(pipe, null, OnEvent, _log, "gp-input-pipe");
            InputHello? hello;
            try
            {
                var bytes = channel.CallRaw(nameof(IInputControl.Attach), [Environment.ProcessId], TimeSpan.FromMilliseconds(ConnectWaitMs));
                hello = bytes == null ? null : JsonSerializer.Deserialize<InputHello>(bytes, PipeChannel.Json);
            }
            catch (Exception e) when (e is PipeClosedException or TimeoutException or InvalidOperationException or JsonException)
            {
                channel.Close();
                _log($"input: attach failed: {e.GetType().Name}: {e.Message}");
                return null;
            }
            if (hello == null)
            {
                channel.Close();
                return null;
            }
            // Another build (an update came in while it ran), another memory layout, or not elevated while this companion
            // is: that one exits, and this companion's own starts.
            string? why = hello.Version != InputHost.Version ? $"it is {hello.Version}"
                : hello.LayoutVersion != SharedRegion.LayoutVersion ? "its shared memory differs"
                : ElevationProbe.IsSelfElevated && !hello.Status.Elevated ? "it isn't elevated" : null;
            if (why == null)
            {
                Hello = hello;
                _status = hello.Status;
                _log($"input: attached to the input process (pid {hello.Pid})");
                return channel;
            }
            _log($"input: replacing the running input process: {why}");
            try
            {
                channel.CallRaw(nameof(IInputControl.Exit), [], TimeSpan.FromMilliseconds(CallTimeoutMs));
            }
            catch (Exception e) when (e is PipeClosedException or TimeoutException or InvalidOperationException)
            {
            }
            channel.Close();
            WaitForExit(hello.Pid, 10_000);
        }
        return null;
    }

    private void OnEvent(string name, JsonElement payload)
    {
        if (name == "status" && payload.ValueKind == JsonValueKind.Object)
            _status = payload.Deserialize<InputStatus>(PipeChannel.Json);
        Event?.Invoke(name, payload);
    }

    /// <summary>Starts GyroPhoneCompanion.exe --input (elevated when this companion is: it inherits the token).</summary>
    private bool Launch(string args)
    {
        try
        {
            var psi = new ProcessStartInfo(_exePath, $"{InputHost.Arg} {args}".Trim())
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                WorkingDirectory = Path.GetDirectoryName(_exePath) ?? "",
            };
            psi.Environment["DOTNET_GCgen0size"] = Gen0Size;
            using var p = Process.Start(psi);
            _log($"input: started the input process (pid {p?.Id})");
            return p != null;
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or IOException or InvalidOperationException)
        {
            _log($"input: starting the input process failed: {e.GetType().Name}: {e.Message}");
            return false;
        }
    }

    private static void WaitForExit(int pid, int ms)
    {
        try
        {
            using var p = Process.GetProcessById(pid);
            p.WaitForExit(ms);
        }
        catch (Exception e) when (e is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            // already gone
        }
    }

    /// <summary>Tray Exit or an update: the input process stops too, and this waits (up to 5 s; longer while it is still attaching) until it has, so the exe is free.</summary>
    public void ExitInput()
    {
        PipeChannel? c;
        lock (_gate)
        {
            _exitAsked = true;
            _stopping = true;
            c = _channel;
        }
        _stopped.Set();
        if (c != null)
        {
            TellExit(c, Hello?.Pid ?? 0);
            return;
        }
        // Still connecting: the link thread tells the input process once it attaches, and waits for it to exit. Wait for
        // that whole step (attach, then Exit and the process's end), or this process ends with the link thread (a background
        // one) mid-attach and the input process, seeing the companion gone without Exit, starts it again.
        var t = _thread;
        if (t != null && t != Thread.CurrentThread) t.Join(ConnectWaitMs + 2 * CallTimeoutMs);
    }

    /// <summary>Tells the input process on <paramref name="c"/> to exit, waits (up to 5 s) until it has, and lets go of the line.</summary>
    private void TellExit(PipeChannel c, int pid)
    {
        try
        {
            c.CallRaw(nameof(IInputControl.Exit), [], TimeSpan.FromMilliseconds(CallTimeoutMs));
        }
        catch (Exception e) when (e is PipeClosedException or TimeoutException or InvalidOperationException)
        {
            _log($"input: exit not answered: {e.GetType().Name}");
        }
        if (pid > 0) WaitForExit(pid, 5_000);
        c.Close();
    }

    /// <summary>Lets go of the line without stopping the input process (it starts this companion again only after a crash).</summary>
    public void Dispose()
    {
        PipeChannel? c;
        lock (_gate)
        {
            _stopping = true;
            c = _channel;
        }
        _stopped.Set();
        c?.Close();
    }
}
