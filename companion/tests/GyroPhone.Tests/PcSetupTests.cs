using GyroPhone.Host.Perf;
using GyroPhone.Protocol;
using Xunit;

namespace GyroPhone.Tests;

public class PcSetupTests
{
    private static readonly PcAdapter Wifi = new("Wi-Fi", false, true, "{00000000-0000-0000-0000-000000000001}");

    [Fact]
    public void CableOrHotspotOrNoAdapterGetsNoWifiBits()
    {
        var radio = new PcWifiRadio(2437, -80);
        Assert.Equal(0, PcSetup.Bits(new PcAdapter("Ethernet", false), radio, false, false));
        Assert.Equal(0, PcSetup.Bits(new PcAdapter("Local Area Connection* 10", true, true), radio, false, false));
        Assert.Equal(0, PcSetup.Bits(null, radio, false, false));
    }

    [Fact]
    public void WifiClientGetsBandAndSignal()
    {
        Assert.Equal(HostStatusFlags2.PcWifi, PcSetup.Bits(Wifi, new PcWifiRadio(5180, -55), false, false));
        Assert.Equal(HostStatusFlags2.PcWifi, PcSetup.Bits(Wifi, default, false, false));
        Assert.Equal(HostStatusFlags2.PcWifi | HostStatusFlags2.PcWifi24 | HostStatusFlags2.PcWifiWeak,
            PcSetup.Bits(Wifi, new PcWifiRadio(2412, -75), false, false));
        Assert.Equal(HostStatusFlags2.PcWifi, PcSetup.Bits(Wifi, new PcWifiRadio(5955, -70), false, false));
    }

    [Fact]
    public void PowerBitsAlways()
    {
        Assert.Equal(HostStatusFlags2.PcOnBattery | HostStatusFlags2.PcPowerSaving, PcSetup.Bits(null, default, true, true));
    }

    [Fact]
    public void SetupBitsLeaveUsbDirectFree()
    {
        Assert.Equal(0, HostStatusFlags2.SetupMask & HostStatusFlags2.UsbDirect);
    }
}
