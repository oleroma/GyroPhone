namespace GyroPhone.Protocol.Mapping;

/// <summary>
/// The wired DualSense's input report 0x01 (64 bytes with its id), as the GyroPhone pad driver
/// (driver/src/dualsense.h) passes it to Windows. Its buttons are the DualShock 4's, so it takes the
/// same <see cref="Ds4Frame"/>: sticks, triggers, the D-pad hat in the low nibble with Square, Cross,
/// Circle, Triangle above it, then L1 R1 L2 R2 Create Options L3 R3, then PS and touchpad click.
/// Gyro and accelerometer go in the DualShock 4's units (1/16 deg/s, 1/8192 g), which the driver's
/// calibration report makes the DualSense's too, and the sample's time in 1/3 µs (the DualSense's
/// sensor timestamp), so a game adds up each turn at the time it was measured. The driver fills in
/// the report counter.
/// </summary>
public static class DualSenseReport
{
    public const int Size = 64;
    public const byte Id = 0x01;

    public static void Build(Span<byte> r, in Ds4Frame f, in Ds4Motion m)
    {
        r[..Size].Clear();
        r[0] = Id;
        r[1] = f.Lx;
        r[2] = f.Ly;
        r[3] = f.Rx;
        r[4] = f.Ry;
        r[5] = f.Lt;
        r[6] = f.Rt;
        // r[7]: the report counter, set by the driver.
        int hat = f.Hat <= PadMapper.Ds4HatNone ? f.Hat : PadMapper.Ds4HatNone;
        r[8] = (byte)((f.Buttons & 0xF0) | hat);
        r[9] = (byte)(f.Buttons >> 8);
        r[10] = (byte)(f.Special & 0x03);
        Le.PutI16(r, 16, m.Gx);
        Le.PutI16(r, 18, m.Gy);
        Le.PutI16(r, 20, m.Gz);
        Le.PutI16(r, 22, m.Ax);
        Le.PutI16(r, 24, m.Ay);
        Le.PutI16(r, 26, m.Az);
        Le.PutU32(r, 28, m.Clock);
        // Both touch points: not touching (bit 7).
        r[33] = 0x80;
        r[37] = 0x80;
        // On cable, charging, battery level 10 of 10.
        r[53] = 0x1A;
    }
}
