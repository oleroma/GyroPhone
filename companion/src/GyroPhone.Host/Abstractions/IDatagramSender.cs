using System.Net;

namespace GyroPhone.Host.Abstractions;

/// <summary>Sends one UDP datagram from the host port. Implemented by UdpHostServer; faked in tests.</summary>
public interface IDatagramSender
{
    void Send(ReadOnlySpan<byte> datagram, SocketAddress to);
}
