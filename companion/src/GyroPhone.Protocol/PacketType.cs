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
    /// <summary>STATE_HIRES plus the phone's raw gyro samples (<see cref="PcGyroTail"/>); sent only to a host whose WELCOME has <see cref="WelcomeFlags.PcGyro"/>.</summary>
    public const byte StateGyro = 0x26;
    /// <summary>The phone's controller buttons that ratchet or toggle the gyro, for a controller plugged into the host.</summary>
    public const byte PadGyro = 0x27;
    /// <summary>With PAD_GYRO: one controller button's gyro settings while held (<see cref="PadGyroConfig.DecodeHeld"/>).</summary>
    public const byte PadGyroSet = 0x28;
    /// <summary>The phone's raw motion for the DualShock 4's gyro and accelerometer (<see cref="MotionFrame"/>); sent only to a host whose WELCOME has <see cref="WelcomeFlags.Motion"/> while its pad is a DualShock 4.</summary>
    public const byte Motion = 0x29;
    /// <summary>Phone to host while TEST_MODE has <see cref="TestModeFlags.PressTimes"/>: when the phone saw the input behind a press or release (<see cref="PressTime"/>).</summary>
    public const byte PressTime = 0x2A;
    /// <summary>Phone to host, about once a second while the phone's "Press a key" screen is open (and when it closes): <see cref="CaptureFlags"/>.</summary>
    public const byte Capture = 0x2B;
    /// <summary>Sent before each OVERLAY while HOST_STATUS flags2 has <see cref="HostStatusFlags2.OverlaySamples"/>: each gyro sample's turn in it.</summary>
    public const byte OverlaySamples = 0x2C;
    /// <summary>Phone to host once a second while TEST_MODE has <see cref="TestModeFlags.PhoneStats"/>: the phone's side of the link (<see cref="Protocol.PhoneStats"/>).</summary>
    public const byte PhoneStats = 0x2D;
    public const byte Bye = 0x2F;
    public const byte Pong = 0x30;
    public const byte HostStatus = 0x31;
    public const byte Rumble = 0x32;
    public const byte Foreground = 0x33;
    /// <summary>Host to phone, with every HOST_STATUS: what each character key types on the PC's keyboard layout (<see cref="KeyLabels"/>).</summary>
    public const byte KeyLabels = 0x34;
    /// <summary>Host to phone, with every HOST_STATUS and on change: the latency tests the phone takes part in (<see cref="TestModeFlags"/>).</summary>
    public const byte TestMode = 0x35;
    /// <summary>Host to phone, every <see cref="Wire.KeepAwakeMs"/> during a session over Wi-Fi: keeps the PC's Wi-Fi out of power save. The phone drops it.</summary>
    public const byte KeepAwake = 0x36;
    /// <summary>Host to phone while CAPTURE is on: what is held on the PC's keyboard, mouse and controller (<see cref="PcPress"/>).</summary>
    public const byte Captured = 0x37;
    public const byte HostBye = 0x3F;
    /// <summary>Unsealed, from a program on this PC (loopback only): start or stop a Link test (tools/link-test.sh).</summary>
    public const byte LinkTestCtl = 0x7E;
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
        LinkTestCtl => Wire.LinkTestCtlSize,
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
        PadGyroSet => Wire.PadGyroSetPlain,
        Motion => Wire.MotionPlain,
        Ping => Wire.PingPlain,
        LinkInfo => Wire.LinkInfoPlain,
        Bye => Wire.ByePlain,
        Pong => Wire.PongPlain,
        HostStatus => Wire.HostStatusPlain,
        Rumble => Wire.RumblePlain,
        Foreground => Wire.ForegroundPlain,
        KeyLabels => Wire.KeyLabelsPlain,
        Overlay => Wire.OverlayPlain,
        OverlayLayout => Wire.OverlayLayoutPlain,
        OverlaySamples => Wire.OverlaySamplesPlain,
        PressTime => Wire.PressTimePlain,
        PhoneStats => Wire.PhoneStatsPlain,
        TestMode => Wire.TestModePlain,
        Capture => Wire.CapturePlain,
        Captured => Wire.CapturedPlain,
        HostBye => Wire.ByePlain,
        _ => -1,
    };
}
