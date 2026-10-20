namespace GyroPhone.Protocol;

/// <summary>Packet type byte (header offset 3) and the exact datagram size of each type.</summary>
public static class PacketType
{
    public const byte Discover = 0x01;
    public const byte Announce = 0x02;
    public const byte PairStart = 0x03;
    public const byte PairHost = 0x04;
    public const byte PairReveal = 0x05;
    public const byte PairAck = 0x06;
    public const byte PairConfirm = 0x07;
    public const byte PairDone = 0x08;
    public const byte Hello = 0x10;
    public const byte Welcome = 0x11;
    public const byte State = 0x20;
    public const byte Ping = 0x21;
    public const byte LinkInfo = 0x22;
    /// <summary>STATE with 1/120-notch wheel totals; sent only to a host whose WELCOME has HiresWheel.</summary>
    public const byte StateHires = 0x23;
    public const byte Bye = 0x2F;
    public const byte Pong = 0x30;
    public const byte HostStatus = 0x31;
    public const byte Rumble = 0x32;
    public const byte Foreground = 0x33;
    public const byte HostBye = 0x3F;
    public const byte Error = 0x7F;

    /// <summary>Sealed (AES-GCM) packets are types 0x20..0x3F.</summary>
    public static bool IsSealed(byte type) => type is >= 0x20 and <= 0x3F;

    /// <summary>Sealed packets travelling phone to host (key kPH, dir 1).</summary>
    public static bool IsPhoneToHostSealed(byte type) => type is >= 0x20 and <= 0x2F;

    /// <summary>Exact datagram size for a known type, or -1 for an unknown type.</summary>
    public static int Size(byte type) => type switch
    {
        Discover => Wire.DiscoverSize,
        Announce => Wire.AnnounceSize,
        PairStart => Wire.PairStartSize,
        PairHost => Wire.PairHostSize,
        PairReveal => Wire.PairRevealSize,
        PairAck => Wire.PairAckSize,
        PairConfirm => Wire.PairConfirmSize,
        PairDone => Wire.PairDoneSize,
        Hello => Wire.HelloSize,
        Welcome => Wire.WelcomeSize,
        Error => Wire.ErrorSize,
        _ => PlainSize(type) is var p && p >= 0 ? Wire.Header + p + Wire.GcmTag : -1,
    };

    /// <summary>Plaintext size of a sealed type, or -1.</summary>
    public static int PlainSize(byte type) => type switch
    {
        State => Wire.StatePlain,
        StateHires => Wire.StateHiresPlain,
        Ping => Wire.PingPlain,
        LinkInfo => Wire.LinkInfoPlain,
        Bye => Wire.ByePlain,
        Pong => Wire.PongPlain,
        HostStatus => Wire.HostStatusPlain,
        Rumble => Wire.RumblePlain,
        Foreground => Wire.ForegroundPlain,
        HostBye => Wire.ByePlain,
        _ => -1,
    };
}
