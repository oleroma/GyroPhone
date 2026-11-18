using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Exceptions;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.DualShock4;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace GyroPhone.Windows;

/// <summary>
/// Virtual pad through the ViGEmBus driver: Xbox 360 (XInput) or DualShock 4 (DirectInput HID).
/// Reports are built in full and sent with one SubmitReport (AutoSubmitReport off). Submit runs under the
/// engine lock, Plug and Unplug on its pad thread; rumble feedback arrives on the driver's thread and is
/// only forwarded. A pad removed underneath (driver reset, disabled) makes Submit throw and
/// <see cref="Plugged"/> None; the engine has the pad thread plug a new one, never Submit.
/// </summary>
public sealed class ViGEmPadSink : IPadSink, IDisposable
{
    public const string DriverDownloadUrl = "https://github.com/nefarius/ViGEmBus/releases";

    private readonly ViGEmClient _client;
    private readonly Action<string>? _log;
    private IXbox360Controller? _x360;
    private IDualShock4Controller? _ds4;
    private readonly byte[] _ds4Report = new byte[Ds4Report.Size];
    /// <summary>The bus is older than 1.17 and lacks DS4_REPORT_EX: the DualShock 4 gets the simple report, no motion.</summary>
    private bool _ds4Simple;
    private Ds4Frame _ds4SimpleLast;
    private bool _ds4SimpleSent;

    private ViGEmPadSink(ViGEmClient client, Action<string>? log)
    {
        _client = client;
        _log = log;
    }

    /// <summary>
    /// Connects to the bus driver. On any failure (driver missing, version mismatch, access denied)
    /// returns a <see cref="NullPadSink"/> and a user-facing reason; keyboard and mouse keep working.
    /// </summary>
    public static IPadSink Create(Action<string>? log, out string? error)
    {
        try
        {
            var sink = new ViGEmPadSink(new ViGEmClient(), log);
            error = null;
            return sink;
        }
        catch (VigemBusNotFoundException)
        {
            error = "ViGEmBus driver is not installed";
        }
        catch (VigemBusVersionMismatchException)
        {
            error = "ViGEmBus driver version is not supported; reinstall it";
        }
        catch (VigemBusAccessFailedException)
        {
            error = "ViGEmBus driver could not be opened";
        }
        catch (Exception e)
        {
            error = $"ViGEmBus unavailable ({e.GetType().Name})";
        }
        log?.Invoke(error);
        return NullPadSink.Instance;
    }

    public bool DriverOk => true;

    public OutputMode Plugged { get; private set; } = OutputMode.None;

    public event Action<byte, byte>? Rumble;

    public bool Plug(OutputMode mode)
    {
        if (mode == Plugged) return mode != OutputMode.None;
        Unplug(); // also frees a pad lost underneath
        IXbox360Controller? x = null;
        IDualShock4Controller? d = null;
        try
        {
            switch (mode)
            {
                case OutputMode.Xbox360:
                    x = _client.CreateXbox360Controller();
                    x.AutoSubmitReport = false;
                    x.FeedbackReceived += OnX360Feedback;
                    x.Connect();
                    _x360 = x;
                    break;
                case OutputMode.DualShock4:
                    d = _client.CreateDualShock4Controller();
                    d.AutoSubmitReport = false;
                    // Deprecated upstream in favour of AwaitRawOutputReport; rumble is informational in v1.
#pragma warning disable CS0618
                    d.FeedbackReceived += OnDs4Feedback;
#pragma warning restore CS0618
                    d.Connect();
                    _ds4 = d;
                    _ds4SimpleSent = false;
                    break;
                default:
                    return false;
            }
            Plugged = mode;
            return true;
        }
        catch (Exception e)
        {
            _log?.Invoke($"plug {mode} failed: {e.GetType().Name}");
            _x360 = null;
            _ds4 = null;
            Plugged = OutputMode.None;
            Free(x, d);
            return false;
        }
    }

    /// <summary>
    /// The XInput slot the plugged Xbox 360 pad took (the driver reports it once Windows assigned one),
    /// <see cref="IPadSink.SlotUnknown"/> until then, -1 when none is plugged.
    /// </summary>
    public int XInputSlot
    {
        get
        {
            var x = Plugged == OutputMode.Xbox360 ? _x360 : null;
            if (x == null) return -1;
            try
            {
                return x.UserIndex;
            }
            catch (Exception)
            {
                return IPadSink.SlotUnknown;
            }
        }
    }

    public void Unplug()
    {
        var x = _x360;
        var d = _ds4;
        _x360 = null;
        _ds4 = null;
        Plugged = OutputMode.None;
        if (x != null)
        {
            try
            {
                x.Disconnect();
            }
            catch (Exception e)
            {
                _log?.Invoke($"unplug failed: {e.GetType().Name}");
            }
        }
        if (d != null)
        {
            try
            {
                d.Disconnect();
            }
            catch (Exception e)
            {
                _log?.Invoke($"unplug failed: {e.GetType().Name}");
            }
        }
        Free(x, d);
    }

    /// <summary>Releases the controllers' native targets and feedback registrations, disconnected or never connected.</summary>
    private void Free(IXbox360Controller? x, IDualShock4Controller? d)
    {
        try
        {
            if (x != null)
            {
                x.FeedbackReceived -= OnX360Feedback;
                (x as IDisposable)?.Dispose();
            }
            if (d != null)
            {
#pragma warning disable CS0618
                d.FeedbackReceived -= OnDs4Feedback;
#pragma warning restore CS0618
                d.Dispose();
            }
        }
        catch (Exception e)
        {
            _log?.Invoke($"pad free failed: {e.GetType().Name}");
        }
    }

    public void Submit(in X360Frame f)
    {
        var x = _x360;
        if (x == null) return;
        try
        {
            x.SetButtonsFull(f.Buttons);
            x.SetAxisValue(Xbox360Axis.LeftThumbX, f.Lx);
            x.SetAxisValue(Xbox360Axis.LeftThumbY, f.Ly);
            x.SetAxisValue(Xbox360Axis.RightThumbX, f.Rx);
            x.SetAxisValue(Xbox360Axis.RightThumbY, f.Ry);
            x.SetSliderValue(Xbox360Slider.LeftTrigger, f.Lt);
            x.SetSliderValue(Xbox360Slider.RightTrigger, f.Rt);
            x.SubmitReport();
        }
        catch (Exception e) when (e is VigemTargetNotPluggedInException or VigemInvalidTargetException)
        {
            Lost();
            throw;
        }
    }

    public void Submit(in Ds4Frame f, in Ds4Motion m)
    {
        var d = _ds4;
        if (d == null) return;
        try
        {
            if (!_ds4Simple)
            {
                try
                {
                    // The full report (DS4_REPORT_EX) carries the gyro and accelerometer the simple one lacks.
                    Ds4Report.Build(_ds4Report, f, m);
                    d.SubmitRawReport(_ds4Report);
                    return;
                }
                catch (VigemNotSupportedException)
                {
                    // ViGEmBus before 1.17 has no DS4_REPORT_EX: buttons, sticks and triggers still go, without motion.
                    _ds4Simple = true;
                    _log?.Invoke("ViGEmBus is older than 1.17: the DualShock 4 gets no gyro; update ViGEmBus for it");
                }
            }
            SubmitSimple(d, f);
        }
        catch (Exception e) when (e is VigemTargetNotPluggedInException or VigemInvalidTargetException)
        {
            Lost();
            throw;
        }
    }

    /// <summary>The simple DS4 report; only when the frame changed, since each motion sample would repeat it.</summary>
    private void SubmitSimple(IDualShock4Controller d, in Ds4Frame f)
    {
        if (_ds4SimpleSent && f == _ds4SimpleLast) return;
        int hat = f.Hat <= PadMapper.Ds4HatNone ? f.Hat : PadMapper.Ds4HatNone;
        d.SetButtonsFull((ushort)((f.Buttons & 0xFFF0) | hat));
        d.SetSpecialButtonsFull((byte)(f.Special & 0x03));
        d.SetAxisValue(DualShock4Axis.LeftThumbX, f.Lx);
        d.SetAxisValue(DualShock4Axis.LeftThumbY, f.Ly);
        d.SetAxisValue(DualShock4Axis.RightThumbX, f.Rx);
        d.SetAxisValue(DualShock4Axis.RightThumbY, f.Ry);
        d.SetSliderValue(DualShock4Slider.LeftTrigger, f.Lt);
        d.SetSliderValue(DualShock4Slider.RightTrigger, f.Rt);
        d.SubmitReport();
        _ds4SimpleLast = f;
        _ds4SimpleSent = true;
    }

    /// <summary>
    /// The pad was removed underneath (disabled in Device Manager, driver reset): the bus no longer knows the target
    /// (not plugged in, or an invalid target when the bus refuses its reports). Plugging a new one blocks in
    /// the driver, so it is left to the engine's pad thread: Plugged goes None, and its next Plug frees the old one.
    /// </summary>
    private void Lost()
    {
        if (Plugged == OutputMode.None) return;
        _log?.Invoke($"{Plugged} pad was unplugged underneath");
        Plugged = OutputMode.None;
    }

    private void OnX360Feedback(object sender, Xbox360FeedbackReceivedEventArgs e) => Rumble?.Invoke(e.LargeMotor, e.SmallMotor);

    private void OnDs4Feedback(object sender, DualShock4FeedbackReceivedEventArgs e) => Rumble?.Invoke(e.LargeMotor, e.SmallMotor);

    public void Dispose()
    {
        Unplug();
        _client.Dispose();
    }
}
