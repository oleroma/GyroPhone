using System.Net;
using System.Net.Sockets;
using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

public enum ServerState
{
    Stopped,
    Running,
    PortInUse,
    Failed,
}

/// <summary>
/// The one UDP socket on port 47800. Thread gp-rx blocks in ReceiveFrom, drains the backlog into
/// HostEngine.OnDatagram, then calls Flush once, so a burst of STATEs causes a single injection
/// (the engine injects early only when a newer STATE changes keys or buttons).
/// </summary>
public sealed class UdpHostServer : IDatagramSender, IDisposable
{
    private const int SioUdpConnReset = unchecked((int)0x9800000C);

    private readonly HostEngine _engine;
    private readonly IClock _clock;
    private readonly IPEndPoint _bind;
    private readonly Action? _onRxThreadStart;
    private readonly Action<string> _log;
    private Socket? _socket;
    private Thread? _thread;
    private volatile bool _stopping;
    private long _rxExceptions;

    public UdpHostServer(HostEngine engine, IClock clock, IPEndPoint? bind = null, Action? onRxThreadStart = null,
        Action<string>? log = null)
    {
        _engine = engine;
        _clock = clock;
        _bind = bind ?? new IPEndPoint(IPAddress.Any, Wire.Port);
        _onRxThreadStart = onRxThreadStart;
        _log = log ?? (_ => { });
    }

    public ServerState State { get; private set; } = ServerState.Stopped;

    /// <summary>The bound port (useful when binding port 0 in tests).</summary>
    public int LocalPort => (_socket?.LocalEndPoint as IPEndPoint)?.Port ?? 0;

    public long RxExceptions => Interlocked.Read(ref _rxExceptions);

    /// <summary>Binds and starts gp-rx. False (State = PortInUse or Failed) when the bind fails.</summary>
    public bool Start()
    {
        var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        try
        {
            s.ReceiveBufferSize = 65536;
            if (OperatingSystem.IsWindows())
            {
                s.ExclusiveAddressUse = true;
                // Without this, an ICMP port-unreachable makes the next ReceiveFrom throw WSAECONNRESET.
                s.IOControl(SioUdpConnReset, new byte[4], null);
            }
            s.Bind(_bind);
        }
        catch (SocketException e)
        {
            s.Dispose();
            State = e.SocketErrorCode is SocketError.AddressAlreadyInUse or SocketError.AccessDenied ? ServerState.PortInUse : ServerState.Failed;
            _log($"bind {_bind} failed: {e.SocketErrorCode}");
            return false;
        }
        _socket = s;
        _engine.AttachSender(this);
        _stopping = false;
        _thread = new Thread(RxLoop) { Name = "gp-rx", IsBackground = true, Priority = ThreadPriority.Highest };
        _thread.Start();
        State = ServerState.Running;
        return true;
    }

    private void RxLoop()
    {
        try
        {
            _onRxThreadStart?.Invoke();
        }
        catch (Exception e)
        {
            _log($"rx thread start hook failed: {e.GetType().Name}");
        }

        var socket = _socket!;
        var buf = new byte[2048];
        var from = new SocketAddress(AddressFamily.InterNetwork);
        while (!_stopping)
        {
            try
            {
                int n = socket.ReceiveFrom(buf, SocketFlags.None, from);
                _engine.OnDatagram(buf.AsSpan(0, n), from, _clock.NowUs);
                while (socket.Available > 0)
                {
                    n = socket.ReceiveFrom(buf, SocketFlags.None, from);
                    _engine.OnDatagram(buf.AsSpan(0, n), from, _clock.NowUs);
                }
                _engine.Flush(_clock.NowUs);
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (SocketException e) when (_stopping || e.SocketErrorCode is SocketError.Interrupted or SocketError.OperationAborted)
            {
                break;
            }
            catch (Exception e)
            {
                // One bad datagram never kills the loop.
                Interlocked.Increment(ref _rxExceptions);
                _log($"rx error: {e.GetType().Name}");
            }
        }
    }

    public void Send(ReadOnlySpan<byte> datagram, SocketAddress to)
    {
        var s = _socket;
        if (s == null) return;
        try
        {
            s.SendTo(datagram, SocketFlags.None, to);
        }
        catch (Exception e) when (e is SocketException or ObjectDisposedException)
        {
            // UDP send failures are transient (no route, buffer full); the protocol repairs itself.
        }
    }

    public void Stop()
    {
        _stopping = true;
        var s = _socket;
        _socket = null;
        s?.Dispose();
        if (_thread != null && Thread.CurrentThread != _thread) _thread.Join(2000);
        _thread = null;
        State = ServerState.Stopped;
    }

    public void Dispose() => Stop();
}
