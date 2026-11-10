using System.Net.Sockets;

namespace GyroPhone.Host.Abstractions;

/// <summary>
/// gp-rx's wait while a lost or late stretch of mouse motion catches up (<see cref="UdpHostServer"/>): it must
/// wake at once for a datagram and close to on time without one, so the catch-up moves on every millisecond.
/// </summary>
public interface ICatchUpWait
{
    /// <summary>Waits until <paramref name="socket"/> has a datagram (true) or about <paramref name="us"/> µs passed (false). Leaves the socket blocking.</summary>
    bool Wait(Socket socket, int us);
}

/// <summary>
/// socket.Poll: wakes at once for a datagram, but on Windows its timeout ends only on the system timer's
/// tick (about 15.6 ms, as the companion asks for no finer timer). Linux and tests.
/// </summary>
public sealed class PollCatchUpWait : ICatchUpWait
{
    public static readonly PollCatchUpWait Instance = new();

    public bool Wait(Socket socket, int us) => socket.Poll(us, SelectMode.SelectRead);
}
