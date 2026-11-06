using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Host.Abstractions;

/// <summary>
/// Virtual game pad: ViGEm on Windows for Xbox 360 and DualShock 4, the optional GyroPhone pad driver
/// for the DualSense. Called under the engine lock; must not block.
/// </summary>
public interface IPadSink
{
    /// <summary>The ViGEmBus driver is present and usable (Xbox 360 and DualShock 4).</summary>
    bool DriverOk { get; }

    /// <summary>The GyroPhone pad driver is installed (DualSense).</summary>
    bool DualSenseOk => false;

    /// <summary>A pad of this type can be plugged: its driver works.</summary>
    bool CanPlug(OutputMode mode) => mode == OutputMode.DualSense ? DualSenseOk
        : DriverOk && mode is OutputMode.Xbox360 or OutputMode.DualShock4;

    /// <summary>Currently plugged pad type, or <see cref="OutputMode.None"/>.</summary>
    OutputMode Plugged { get; }

    /// <summary>Plugs a pad of the given type (unplugging any other). False when it could not.</summary>
    bool Plug(OutputMode mode);

    void Unplug();

    void Submit(in X360Frame frame);

    /// <summary>The DualShock 4's (or DualSense's) whole report: buttons, sticks and triggers with its gyro and accelerometer.</summary>
    void Submit(in Ds4Frame frame, in Ds4Motion motion);

    /// <summary>Raised from the driver's thread when the game changes rumble (large, small motor).</summary>
    event Action<byte, byte>? Rumble;

    /// <summary>
    /// The XInput slot (0..3) our plugged Xbox 360 pad took, -1 when none is plugged, <see cref="SlotUnknown"/>
    /// while it is plugged but Windows hasn't given it a slot yet. May block briefly: not under the engine lock.
    /// </summary>
    int XInputSlot => -1;

    const int SlotUnknown = -2;
}

/// <summary>Pad sink used when the ViGEmBus driver is missing (or --no-vigem): nothing is plugged, nothing is sent.</summary>
public sealed class NullPadSink : IPadSink
{
    public static readonly NullPadSink Instance = new();

    public bool DriverOk => false;

    public OutputMode Plugged => OutputMode.None;

    public bool Plug(OutputMode mode) => false;

    public void Unplug() { }

    public void Submit(in X360Frame frame) { }

    public void Submit(in Ds4Frame frame, in Ds4Motion motion) { }

    public event Action<byte, byte>? Rumble
    {
        add { }
        remove { }
    }
}
