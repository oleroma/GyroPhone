using System.Collections.Concurrent;
using GyroPhone.Host;

namespace GyroPhone.Headless;

/// <summary>
/// --latency-csv writer. The engine hook only enqueues (it runs on the rx thread under the engine
/// lock); a background thread writes "seq,phoneUs,hostRecvUs,latencyUs,injectUs" rows.
/// </summary>
internal sealed class LatencyCsv : IDisposable
{
    private readonly ConcurrentQueue<LatencySample> _queue = new();
    private readonly StreamWriter _writer;
    private readonly Thread _thread;
    private readonly ManualResetEventSlim _stop = new(false);
    private long _rows;

    public LatencyCsv(string path)
    {
        _writer = new StreamWriter(path, append: false);
        _writer.WriteLine("seq,phoneUs,hostRecvUs,latencyUs,injectUs");
        _thread = new Thread(Run) { Name = "gp-csv", IsBackground = true, Priority = ThreadPriority.BelowNormal };
        _thread.Start();
    }

    public long Rows => Interlocked.Read(ref _rows);

    public void Add(LatencySample s) => _queue.Enqueue(s);

    private void Run()
    {
        while (!_stop.Wait(200)) Drain();
        Drain();
    }

    private void Drain()
    {
        while (_queue.TryDequeue(out var s))
        {
            _writer.Write(s.Seq);
            _writer.Write(',');
            _writer.Write(s.PhoneUs);
            _writer.Write(',');
            _writer.Write(s.HostRecvUs);
            _writer.Write(',');
            _writer.Write(s.LatencyUs);
            _writer.Write(',');
            _writer.WriteLine(s.InjectUs);
            Interlocked.Increment(ref _rows);
        }
        _writer.Flush();
    }

    public void Dispose()
    {
        _stop.Set();
        _thread.Join(2000);
        _writer.Dispose();
    }
}
