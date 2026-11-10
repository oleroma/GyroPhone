using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Windows;

/// <summary>Makes the companion's pad sink: ViGEmBus for Xbox 360 and DualShock 4, plus the DualSense when the GyroPhone pad driver is installed.</summary>
public static class PadSinks
{
    /// <summary>The sink; <paramref name="vigemError"/> says why ViGEmBus can't be used (null when it can).</summary>
    public static IPadSink Create(Action<string>? log, out string? vigemError)
    {
        var vigem = ViGEmPadSink.Create(log, out vigemError);
        var dualSense = DualSensePadSink.Create(log);
        return dualSense == null ? vigem : new CombinedPadSink(vigem, dualSense);
    }
}

/// <summary>ViGEmBus's pads and the GyroPhone pad driver's DualSense behind one sink; at most one of them plugged.</summary>
public sealed class CombinedPadSink(IPadSink vigem, DualSensePadSink dualSense) : IPadSink, IDisposable
{
    public bool DriverOk => vigem.DriverOk;

    public bool DualSenseOk => true;

    public bool CanPlug(OutputMode mode) => mode == OutputMode.DualSense || vigem.CanPlug(mode);

    public OutputMode Plugged => dualSense.Plugged ? OutputMode.DualSense : vigem.Plugged;

    public event Action<byte, byte>? Rumble
    {
        add => vigem.Rumble += value;
        remove => vigem.Rumble -= value;
    }

    public int XInputSlot => vigem.XInputSlot;

    public bool Plug(OutputMode mode)
    {
        if (mode == Plugged) return mode != OutputMode.None;
        Unplug();
        return mode == OutputMode.DualSense ? dualSense.Plug() : vigem.Plug(mode);
    }

    public void Unplug()
    {
        dualSense.Unplug();
        vigem.Unplug();
    }

    public void Submit(in X360Frame frame) => vigem.Submit(frame);

    public void Submit(in Ds4Frame frame, in Ds4Motion motion)
    {
        if (dualSense.Plugged) dualSense.Submit(frame, motion);
        else vigem.Submit(frame, motion);
    }

    public void Dispose()
    {
        dualSense.Dispose();
        (vigem as IDisposable)?.Dispose();
    }
}
