namespace GyroPhone.Host.Split;

/// <summary>
/// The input process's events for the companion, sent off the input path: <see cref="Post"/> only queues (a line made
/// on gp-rx, say), and its own thread (gp-events) writes them to the pipe. While no companion is attached, or when the
/// queue is long (the companion stopped reading), new events are dropped and counted.
/// </summary>
public sealed class EventOutbox : IDisposable
{
    public const int MaxQueued = 4096;

    private readonly System.Collections.Concurrent.ConcurrentQueue<(string Name, object? Payload)> _queue = new();
    private readonly AutoResetEvent _wake = new(false);
    private readonly Thread _thread;
    private volatile PipeChannel? _channel;
    private volatile bool _stop;
    private int _count;

    public EventOutbox()
    {
        _thread = new Thread(Run) { Name = "gp-events", IsBackground = true, Priority = ThreadPriority.BelowNormal };
        _thread.Start();
    }

    /// <summary>Events dropped (no companion, or it fell behind).</summary>
    public long Dropped;

    /// <summary>The companion's line now (null: none attached, events are dropped).</summary>
    public PipeChannel? Channel
    {
        get => _channel;
        set
        {
            _channel = value;
            _wake.Set();
        }
    }

    public void Post(string name, object? payload = null)
    {
        if (_channel == null || Interlocked.Increment(ref _count) > MaxQueued)
        {
            if (_channel != null) Interlocked.Decrement(ref _count);
            Interlocked.Increment(ref Dropped);
            return;
        }
        _queue.Enqueue((name, payload));
        _wake.Set();
    }

    private void Run()
    {
        while (!_stop)
        {
            _wake.WaitOne(1000);
            while (_queue.TryDequeue(out var e))
            {
                Interlocked.Decrement(ref _count);
                var c = _channel;
                if (c == null || !c.Post(e.Name, e.Payload)) Interlocked.Increment(ref Dropped);
            }
        }
    }

    /// <summary>Sends what waits now (on the caller's thread), for the last events before exit.</summary>
    public void Flush()
    {
        while (_queue.TryDequeue(out var e))
        {
            Interlocked.Decrement(ref _count);
            _channel?.Post(e.Name, e.Payload);
        }
    }

    public void Dispose()
    {
        _stop = true;
        _wake.Set();
        _thread.Join(1000);
    }
}
