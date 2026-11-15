using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// What the input side hands the overlay side: the phone's OVERLAY, OVERLAY_SAMPLES and OVERLAY_LAYOUT bytes as
/// they came (decrypted), and marks for when the overlay data starts again (a new session, overlay off). A fixed
/// ring of preallocated slots: putting one in is a byte copy and two writes, nothing is made, nothing waits.
/// <para>
/// One writer at a time: the engine only writes under its lock (gp-rx, or a window's call into the engine). One
/// reader: <see cref="OverlayWorker"/>. When the ring is full the newest is dropped; a dropped mark is kept as
/// <see cref="ClearLost"/>, so the overlay side starts again rather than carry on from a reset it never saw.
/// </para>
/// </summary>
internal sealed class OverlayInbox
{
    public const int Capacity = 256;
    public static readonly int MaxBytes = Math.Max(Wire.OverlayPlain, Math.Max(Wire.OverlaySamplesPlain, Wire.OverlayLayoutPlain));

    internal enum Kind : byte
    {
        Overlay,
        Samples,
        Layout,
        /// <summary>A new session: the next OVERLAY totals are the base.</summary>
        TotalsReset,
        /// <summary>The same phone in a new session: its next layout is taken again and its press counts start again.</summary>
        Renew,
        /// <summary>Overlay data starts again (off, on, phone gone): poses, rest and totals go; <see cref="Slot.Flag"/> also drops the layout.</summary>
        Clear,
        /// <summary>Level overlay <see cref="Slot.Id"/> (its Level button).</summary>
        Level,
    }

    internal sealed class Slot
    {
        public Kind Kind;
        public int Length;
        /// <summary>Host time it came (us).</summary>
        public long RxUs;
        /// <summary><see cref="Environment.TickCount64"/> when it came (a pointer's paint timing).</summary>
        public long RxMs;
        /// <summary>OVERLAY: the PC controller's gate applied (the phone's "Use PC controller for gyro ratchet" on).</summary>
        public bool Gated;
        public byte GateAxes, GatePresses;
        public bool GateHeld;
        /// <summary><see cref="Kind.Clear"/>: the layout goes too.</summary>
        public bool Flag;
        public string? Id;
        public readonly byte[] Data = new byte[MaxBytes];
    }

    private readonly Slot[] _slots = new Slot[Capacity];
    private int _head; // next to write (writer only)
    private int _tail; // next to read (reader only)
    private int _clearLost;

    public OverlayInbox()
    {
        for (int i = 0; i < Capacity; i++) _slots[i] = new Slot();
    }

    /// <summary>Overlay packets and marks dropped because the ring was full (the reader fell behind).</summary>
    public long Dropped;

    /// <summary>A packet (<see cref="Kind.Overlay"/>, <see cref="Kind.Samples"/>, <see cref="Kind.Layout"/>); false when full.</summary>
    public bool Put(Kind kind, ReadOnlySpan<byte> plain, long rxUs, long rxMs)
    {
        var s = Next();
        if (s == null) return false;
        plain.CopyTo(s.Data);
        s.Kind = kind;
        s.Length = plain.Length;
        s.RxUs = rxUs;
        s.RxMs = rxMs;
        s.Gated = false;
        s.Id = null;
        Commit();
        return true;
    }

    /// <summary>An OVERLAY with the PC controller's gate: the axes left on, a ratchet held, its ratchet presses.</summary>
    public bool PutGated(ReadOnlySpan<byte> plain, long rxUs, long rxMs, byte axes, bool held, byte presses)
    {
        var s = Next();
        if (s == null) return false;
        plain.CopyTo(s.Data);
        s.Kind = Kind.Overlay;
        s.Length = plain.Length;
        s.RxUs = rxUs;
        s.RxMs = rxMs;
        s.Gated = true;
        s.GateAxes = axes;
        s.GateHeld = held;
        s.GatePresses = presses;
        s.Id = null;
        Commit();
        return true;
    }

    /// <summary>A mark (<see cref="Kind.TotalsReset"/>, <see cref="Kind.Renew"/>, <see cref="Kind.Clear"/>, <see cref="Kind.Level"/>).</summary>
    public void Mark(Kind kind, long nowUs, bool flag = false, string? id = null)
    {
        var s = Next();
        if (s == null)
        {
            if (kind != Kind.Level) Volatile.Write(ref _clearLost, 1);
            return;
        }
        s.Kind = kind;
        s.Length = 0;
        s.RxUs = nowUs;
        s.Flag = flag;
        s.Id = id;
        Commit();
    }

    private Slot? Next()
    {
        int head = _head;
        if (head - Volatile.Read(ref _tail) >= Capacity)
        {
            Dropped++;
            return null;
        }
        return _slots[head & (Capacity - 1)];
    }

    /// <summary>Hands the slot <see cref="Next"/> gave to the reader.</summary>
    private void Commit() => Volatile.Write(ref _head, _head + 1);

    /// <summary>True (once) when a mark was dropped: the reader starts everything again.</summary>
    public bool ClearLost() => Interlocked.Exchange(ref _clearLost, 0) != 0;

    /// <summary>The oldest slot not yet read, or null; <see cref="Release"/> it when done.</summary>
    public Slot? Peek()
    {
        int tail = _tail;
        return tail == Volatile.Read(ref _head) ? null : _slots[tail & (Capacity - 1)];
    }

    public void Release() => Volatile.Write(ref _tail, _tail + 1);
}
