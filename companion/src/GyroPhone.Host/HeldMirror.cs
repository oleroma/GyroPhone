using System.Runtime.CompilerServices;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// What the overlay windows read from the input side many times a second, in memory both sides map
/// (<see cref="SharedRegion"/>): whether a phone is connected, the newest STATE's buttons, keys and triggers (a laser
/// pointer's Fire and Aim), and the PC controller while the phone's "Use PC controller for gyro ratchet" is on (Fire,
/// Aim and the handles). The engine writes it under its lock; readers copy it without any lock (a sequence lock in
/// the shared memory), so a reader paused mid-copy can never keep the input thread waiting. Nothing is made.
/// </summary>
public sealed unsafe class HeldMirror
{
    // seq (i32), connected, pad gyro, pc connected (u8), buttons (u16), hat, mouse (u8), lt, rt (u16), keys (32),
    // pc held (u32), pc axes (6 floats).
    private const int Seq = 0, ConnectedOff = 4, PadGyroOff = 5, PcOnOff = 6, ButtonsOff = 8, HatOff = 10, MouseOff = 11,
        LtOff = 12, RtOff = 14, KeysOff = 16, PcHeldOff = 48, PcAxesOff = 52;

    /// <summary>The bytes it takes in a <see cref="SharedRegion"/>.</summary>
    public const int BytesNeeded = 128;

    private readonly byte* _p;

    public HeldMirror(byte* p) => _p = p;

    private ref int SeqRef => ref Unsafe.AsRef<int>(_p + Seq);

    private void BeginWrite()
    {
        Volatile.Write(ref SeqRef, SeqRef + 1); // odd: a write is under way
        Interlocked.MemoryBarrier();
    }

    private void EndWrite() => Volatile.Write(ref SeqRef, SeqRef + 1);

    private int ReadBegin()
    {
        int s = Volatile.Read(ref SeqRef);
        if ((s & 1) == 0) return s;
        var spin = new SpinWait();
        while (((s = Volatile.Read(ref SeqRef)) & 1) != 0) spin.SpinOnce();
        return s;
    }

    private bool ReadRetry(int begin)
    {
        Interlocked.MemoryBarrier();
        return Volatile.Read(ref SeqRef) != begin;
    }

    // ---- written by the engine, under its lock ----

    public void SetConnected(bool on)
    {
        BeginWrite();
        _p[ConnectedOff] = (byte)(on ? 1 : 0);
        EndWrite();
    }

    public void SetPadGyro(bool on)
    {
        BeginWrite();
        _p[PadGyroOff] = (byte)(on ? 1 : 0);
        EndWrite();
    }

    /// <summary>The newest STATE's buttons, hat, mouse buttons, triggers and keys (what <see cref="LaserFire.Held"/> reads).</summary>
    public void PublishHeldOutputs(StateFrame s)
    {
        BeginWrite();
        *(ushort*)(_p + ButtonsOff) = s.Buttons;
        _p[HatOff] = s.Hat;
        _p[MouseOff] = s.MouseButtons;
        *(ushort*)(_p + LtOff) = s.Lt;
        *(ushort*)(_p + RtOff) = s.Rt;
        s.Keys.AsSpan(0, StateFrame.KeyBitmapSize).CopyTo(new Span<byte>(_p + KeysOff, StateFrame.KeyBitmapSize));
        EndWrite();
    }

    /// <summary>The PC controller now: found, its buttons as place bits, its sticks and triggers (lx, ly, rx, ry, l2, r2).</summary>
    public void PublishPcPad(bool connected, uint held, ReadOnlySpan<float> axes)
    {
        BeginWrite();
        _p[PcOnOff] = (byte)(connected ? 1 : 0);
        *(uint*)(_p + PcHeldOff) = held;
        var dst = new Span<float>(_p + PcAxesOff, 6);
        for (int i = 0; i < 6; i++) dst[i] = i < axes.Length ? axes[i] : 0f;
        EndWrite();
    }

    // ---- read from any thread, either process ----

    public bool Connected => Volatile.Read(ref _p[ConnectedOff]) != 0;

    public bool PadGyroOn => Volatile.Read(ref _p[PadGyroOff]) != 0;

    /// <summary>
    /// The PC controller while the phone's "Use PC controller for gyro ratchet" is on: false (and nothing held) while
    /// that switch is off or no controller is found.
    /// </summary>
    public bool PcPad(out uint held, Span<float> axes)
    {
        while (true)
        {
            int s = ReadBegin();
            bool on = _p[PadGyroOff] != 0 && _p[PcOnOff] != 0;
            held = *(uint*)(_p + PcHeldOff);
            new ReadOnlySpan<float>(_p + PcAxesOff, 6).CopyTo(axes);
            if (ReadRetry(s)) continue;
            if (!on)
            {
                held = 0;
                axes.Clear();
            }
            return on;
        }
    }

    /// <summary>
    /// A laser pointer overlay's output <paramref name="code"/> (<see cref="LaserFire"/>) is held: sent by the phone
    /// now, or pressed on the PC's controller with "Use PC controller for gyro ratchet" on. <paramref name="scratch"/>
    /// is the caller's own frame to copy into (nothing is made).
    /// </summary>
    public bool Held(int code, StateFrame scratch)
    {
        if (code == 0) return false;
        Span<float> axes = stackalloc float[6];
        bool pc;
        uint pcHeld;
        while (true)
        {
            int s = ReadBegin();
            scratch.Buttons = *(ushort*)(_p + ButtonsOff);
            scratch.Hat = _p[HatOff];
            scratch.MouseButtons = _p[MouseOff];
            scratch.Lt = *(ushort*)(_p + LtOff);
            scratch.Rt = *(ushort*)(_p + RtOff);
            new ReadOnlySpan<byte>(_p + KeysOff, StateFrame.KeyBitmapSize).CopyTo(scratch.Keys);
            pc = _p[PadGyroOff] != 0 && _p[PcOnOff] != 0;
            pcHeld = *(uint*)(_p + PcHeldOff);
            new ReadOnlySpan<float>(_p + PcAxesOff, 6).CopyTo(axes);
            if (!ReadRetry(s)) break;
        }
        return LaserFire.Held(code, scratch, pc ? pcHeld : 0, pc ? axes[4] : 0f, pc ? axes[5] : 0f);
    }
}
