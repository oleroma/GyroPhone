namespace GyroPhone.Protocol;

/// <summary>
/// Datagrams over the direct USB cable (Android Open Accessory), which is a byte stream: each one
/// goes as 0x47, length (u16 LE), bytes. A frame whose size is a multiple of 64 gets one 0x00
/// after it, so no USB transfer ends on a full packet (the other side would wait for more). The
/// reader skips anything that is not a frame start, which also finds its feet again mid-stream.
/// Same format as the phone's UsbFrames.
/// </summary>
public static class UsbFraming
{
    public const byte Magic = 0x47;
    public const int Header = 3;

    /// <summary>Room Encode needs for a datagram of len bytes.</summary>
    public static int MaxSize(int len) => Header + len + 1;

    /// <summary>Writes the frame for datagram to dst and returns its size.</summary>
    public static int Encode(ReadOnlySpan<byte> datagram, Span<byte> dst)
    {
        if (datagram.Length is < 1 or > Wire.MaxDatagram) throw new ArgumentOutOfRangeException(nameof(datagram));
        dst[0] = Magic;
        dst[1] = (byte)datagram.Length;
        dst[2] = (byte)(datagram.Length >> 8);
        datagram.CopyTo(dst[Header..]);
        int n = Header + datagram.Length;
        if (n % 64 == 0) dst[n++] = 0;
        return n;
    }
}

/// <summary>Splits the USB byte stream back into datagrams. Not thread safe: one reader.</summary>
public sealed class UsbDeframer
{
    private readonly byte[] _buf = new byte[UsbFraming.Header + Wire.MaxDatagram];
    private int _have, _need;

    public delegate void FrameHandler(ReadOnlySpan<byte> datagram);

    /// <summary>Feeds data; onFrame gets each datagram (only valid during the call).</summary>
    public void Feed(ReadOnlySpan<byte> data, FrameHandler onFrame)
    {
        foreach (byte b in data)
        {
            if (_have == 0)
            {
                if (b == UsbFraming.Magic) _buf[_have++] = b;
                continue;
            }
            _buf[_have++] = b;
            if (_have == UsbFraming.Header)
            {
                _need = _buf[1] | (_buf[2] << 8);
                if (_need is < 1 or > Wire.MaxDatagram)
                {
                    _have = 0; // not a frame: look for the next start
                    continue;
                }
            }
            if (_have > UsbFraming.Header && _have == UsbFraming.Header + _need)
            {
                onFrame(_buf.AsSpan(UsbFraming.Header, _need));
                _have = 0;
            }
        }
    }
}
