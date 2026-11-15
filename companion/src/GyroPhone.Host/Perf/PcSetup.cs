using GyroPhone.Protocol;

namespace GyroPhone.Host.Perf;

/// <summary>What the PC's Wi-Fi radio on the phone's adapter reports: centre frequency (MHz) and signal (dBm), each null when unknown.</summary>
public readonly record struct PcWifiRadio(int? FreqMhz, int? RssiDbm);

/// <summary>
/// The PC's side of the phone's Setup tips (docs/spec/phone-connection.md, "Setup tips"): HOST_STATUS flags2's setup bits
/// from the adapter that reaches the phone, its Wi-Fi radio and the PC's power. Only what could be better is set.
/// </summary>
public static class PcSetup
{
    /// <summary>A signal below this (dBm) is weak, as on the phone.</summary>
    public const int WeakDbm = -70;

    public static byte Bits(PcAdapter? adapter, PcWifiRadio radio, bool onBattery, bool powerSaving)
    {
        byte b = 0;
        if (adapter is { Wireless: true, Hotspot: false })
        {
            b |= HostStatusFlags2.PcWifi;
            if (radio.FreqMhz is > 0 and < 3000) b |= HostStatusFlags2.PcWifi24;
            if (radio.RssiDbm is < WeakDbm and > -127) b |= HostStatusFlags2.PcWifiWeak;
        }
        if (onBattery) b |= HostStatusFlags2.PcOnBattery;
        if (powerSaving) b |= HostStatusFlags2.PcPowerSaving;
        return b;
    }
}
