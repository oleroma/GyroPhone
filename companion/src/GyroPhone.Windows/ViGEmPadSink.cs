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
/// only forwarded.
/// </summary>
public sealed class ViGEmPadSink : IPadSink, IDisposable
{
    public const string DriverDownloadUrl = "https://github.com/nefarius/ViGEmBus/releases";

    private readonly ViGEmClient _client;
    private readonly Action<string>? _log;
    private IXbox360Controller? _x360;
    private IDualShock4Controller? _ds4;
    private readonly byte[] _ds4Report = new byte[Ds4Report.Size];
    private const long ReplugGapMs = 1000;
    private long _replugAtMs = -ReplugGapMs;

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
        Unplug();
        try
        {
            switch (mode)
            {
                case OutputMode.Xbox360:
                    var x = _client.CreateXbox360Controller();
                    x.AutoSubmitReport = false;
                    x.FeedbackReceived += OnX360Feedback;
                    x.Connect();
                    _x360 = x;
                    break;
                case OutputMode.DualShock4:
                    var d = _client.CreateDualShock4Controller();
                    d.AutoSubmitReport = false;
                    // Deprecated upstream in favour of AwaitRawOutputReport; rumble is informational in v1.
#pragma warning disable CS0618
                    d.FeedbackReceived += OnDs4Feedback;
#pragma warning restore CS0618
                    d.Connect();
                    _ds4 = d;
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
            var x = _x360;
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
        try
        {
            if (x != null)
            {
                x.FeedbackReceived -= OnX360Feedback;
                x.Disconnect();
            }
            if (d != null)
            {
#pragma warning disable CS0618
                d.FeedbackReceived -= OnDs4Feedback;
#pragma warning restore CS0618
                d.Disconnect();
                d.Dispose();
            }
        }
        catch (Exception e)
        {
            _log?.Invoke($"unplug failed: {e.GetType().Name}");
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
        catch (VigemTargetNotPluggedInException)
        {
            // Unplugged underneath us (driver reset): plug a new one, and fail so the engine sends the frame again.
            Replug();
            throw;
        }
    }

    public void Submit(in Ds4Frame f, in Ds4Motion m)
    {
        var d = _ds4;
        if (d == null) return;
        try
        {
            // The full report (DS4_REPORT_EX) carries the gyro and accelerometer the simple one lacks.
            Ds4Report.Build(_ds4Report, f, m);
            d.SubmitRawReport(_ds4Report);
        }
        catch (VigemTargetNotPluggedInException)
        {
            Replug();
            throw;
        }
    }

    /// <summary>
    /// The pad was removed underneath (disabled in Device Manager, driver reset): Plugged still says it is
    /// in, so nothing else would make a new one. At most once a second; a failed Plug leaves it unplugged.
    /// </summary>
    private void Replug()
    {
        long now = Environment.TickCount64;
        if (now - _replugAtMs < ReplugGapMs) return;
        _replugAtMs = now;
        var mode = Plugged;
        _log?.Invoke($"{mode} pad was unplugged underneath; plugging it again");
        Unplug();
        Plug(mode);
    }

    private void OnX360Feedback(object sender, Xbox360FeedbackReceivedEventArgs e) => Rumble?.Invoke(e.LargeMotor, e.SmallMotor);

    private void OnDs4Feedback(object sender, DualShock4FeedbackReceivedEventArgs e) => Rumble?.Invoke(e.LargeMotor, e.SmallMotor);

    public void Dispose()
    {
        Unplug();
        _client.Dispose();
    }
}
