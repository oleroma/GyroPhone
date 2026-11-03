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
    /// <summary>Phone to host: the phone's static key, for the host whose QR code it scanned.</summary>
    public const byte KeyEnroll = 0x09;
    public const byte KeyEnrollAck = 0x0A;
    public const byte Hello = 0x10;
    public const byte Welcome = 0x11;
    public const byte State = 0x20;
    public const byte Ping = 0x21;
    public const byte LinkInfo = 0x22;
    /// <summary>STATE with 1/120-notch wheel totals; sent only to a host whose WELCOME has HiresWheel.</summary>
    public const byte StateHires = 0x23;
    /// <summary>Sent only while HOST_STATUS has <see cref="HostStatusFlags.Overlay"/>: pose, touches, presses.</summary>
    public const byte Overlay = 0x24;
    /// <summary>Sent only while HOST_STATUS has <see cref="HostStatusFlags.Overlay"/>: the layout, in pieces.</summary>
    public const byte OverlayLayout = 0x25;
    /// <summary>STATE_HIRES plus the gyro's shares; sent only to a host whose WELCOME has <see cref="WelcomeFlags.GyroGate"/>.</summary>
    public const byte StateGyro = 0x26;
    /// <summary>The phone's controller buttons that ratchet or toggle the gyro, for a controller plugged into the host.</summary>
    public const byte PadGyro = 0x27;
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
        KeyEnroll => Wire.KeyEnrollSize,
        KeyEnrollAck => Wire.KeyEnrollAckSize,
        _ => PlainSize(type) is var p && p >= 0 ? Wire.Header + p + Wire.GcmTag : -1,
    };

    /// <summary>Plaintext size of a sealed type, or -1.</summary>
    public static int PlainSize(byte type) => type switch
    {
        State => Wire.StatePlain,
        StateHires => Wire.StateHiresPlain,
        StateGyro => Wire.StateGyroPlain,
        PadGyro => Wire.PadGyroPlain,
        Ping => Wire.PingPlain,
        LinkInfo => Wire.LinkInfoPlain,
        Bye => Wire.ByePlain,
        Pong => Wire.PongPlain,
        HostStatus => Wire.HostStatusPlain,
        Rumble => Wire.RumblePlain,
        Foreground => Wire.ForegroundPlain,
        Overlay => Wire.OverlayPlain,
        OverlayLayout => Wire.OverlayLayoutPlain,
        HostBye => Wire.ByePlain,
        _ => -1,
    };
}
