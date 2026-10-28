using System.Net;
using System.Net.Sockets;
using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// Carries the direct USB link into the UDP server: every datagram from the phone goes to the
/// server's port from a loopback socket of our own, and whatever the server sends back to that
/// socket goes down the cable. The engine sees one more phone address (127.0.0.1:port), so
/// pairing, sessions and encryption are exactly those of Wi-Fi. Thread gp-usb-rx reads the cable,
/// gp-usb-tx the loopback socket.
/// </summary>
public sealed class UsbBridge : IDisposable
{
    private readonly IUsbPipe _pipe;
    private readonly IPEndPoint _server;
    private readonly Action<string> _log;
    private readonly Socket _socket;
    private readonly Thread _rx, _tx;
    private volatile bool _stopping;
    private volatile bool _dead;
    private long _fromPhone, _toPhone, _writeFailures;

    public UsbBridge(IUsbPipe pipe, int serverPort, Action<string>? log = null)
    {
        _pipe = pipe;
        _server = new IPEndPoint(IPAddress.Loopback, serverPort);
        _log = log ?? (_ => { });
        _socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        _socket.Bind(new IPEndPoint(IPAddress.Loopback, 0));
        _rx = new Thread(RxLoop) { Name = "gp-usb-rx", IsBackground = true, Priority = ThreadPriority.Highest };
        _tx = new Thread(TxLoop) { Name = "gp-usb-tx", IsBackground = true, Priority = ThreadPriority.Highest };
    }

    /// <summary>False once the cable side failed (unplugged, phone left accessory mode).</summary>
    public bool Alive => !_dead && !_stopping;

    public long FromPhone => Interlocked.Read(ref _fromPhone);
    public long ToPhone => Interlocked.Read(ref _toPhone);
    public long WriteFailures => Interlocked.Read(ref _writeFailures);

    /// <summary>The loopback address the engine sees for this phone.</summary>
    public IPEndPoint LocalEndPoint => (IPEndPoint)_socket.LocalEndPoint!;

    public void Start()
    {
        _rx.Start();
        _tx.Start();
    }

    private void RxLoop()
    {
        var buf = new byte[16384];
        var deframer = new UsbDeframer();
        try
        {
            while (!_stopping)
            {
                int n = _pipe.Read(buf);
                deframer.Feed(buf.AsSpan(0, n), d =>
                {
                    _socket.SendTo(d, SocketFlags.None, _server);
                    Interlocked.Increment(ref _fromPhone);
                });
            }
        }
        catch (Exception e) when (e is IOException or SocketException or ObjectDisposedException)
        {
            if (!_stopping) _log($"usb: cable side closed ({e.Message})");
        }
        _dead = true;
    }

    private void TxLoop()
    {
        var buf = new byte[2048];
        var frame = new byte[UsbFraming.MaxSize(Wire.MaxDatagram)];
        EndPoint from = new IPEndPoint(IPAddress.Any, 0);
        while (!_stopping && !_dead)
        {
            int n;
            try
            {
                n = _socket.ReceiveFrom(buf, ref from);
            }
            catch (SocketException e) when (e.SocketErrorCode == SocketError.ConnectionReset)
            {
                continue; // an ICMP port-unreachable from an earlier send: not fatal
            }
            catch (Exception e) when (e is SocketException or ObjectDisposedException)
            {
                break;
            }
            if (!_server.Equals(from) || n is < 1 or > Wire.MaxDatagram) continue; // only the server talks to us
            int len = UsbFraming.Encode(buf.AsSpan(0, n), frame);
            if (_pipe.Write(frame.AsSpan(0, len))) Interlocked.Increment(ref _toPhone);
            else Interlocked.Increment(ref _writeFailures);
        }
    }

    public void Dispose()
    {
        if (_stopping) return;
        _stopping = true;
        _socket.Dispose();
        _pipe.Cancel();
        bool joined = true;
        if (_rx.IsAlive && Thread.CurrentThread != _rx) joined &= _rx.Join(1000);
        if (_tx.IsAlive && Thread.CurrentThread != _tx) joined &= _tx.Join(1000);
        // A thread stuck in a transfer keeps the handle: leaking it beats freeing it under the call.
        if (joined) _pipe.Dispose();
        else _log("usb: a transfer did not stop; the handle is left open");
    }
}
