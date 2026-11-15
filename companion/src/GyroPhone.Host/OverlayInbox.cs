using System.Runtime.CompilerServices;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// What the input side hands the overlay side: the phone's OVERLAY, OVERLAY_SAMPLES and OVERLAY_LAYOUT bytes as
/// they came (decrypted), and marks for when the overlay data starts again (a new session, the phone gone). A fixed
/// ring of slots in memory both sides map (<see cref="SharedRegion"/>: the input process writes, the companion
/// reads): putting one in is a byte copy and a few writes, nothing is made, nothing waits.
/// <para>
/// One writer at a time: the engine only writes under its lock. One reader: <see cref="OverlayWorker"/>. When the
/// ring is full the newest is dropped; a dropped mark is kept as <see cref="ClearLost"/>, so the overlay side starts
/// again rather than carry on from a reset it never saw. A reader that comes back (a restarted companion) skips what
/// waits (<see cref="SkipAll"/>).
/// </para>
/// </summary>
public sealed unsafe class OverlayInbox
{
    public const int Capacity = 256;
    public static readonly int MaxBytes = Math.Max(Wire.OverlayPlain, Math.Max(Wire.OverlaySamplesPlain, Wire.OverlayLayoutPlain));

    public enum Kind : byte
    {
        Overlay,
        Samples,
        Layout,
        /// <summary>A new session: the next OVERLAY totals are the base.</summary>
        TotalsReset,
        /// <summary>The same phone in a new session: its next layout is taken again and its press counts start again.</summary>
        Renew,
        /// <summary>Overlay data starts again (phone gone, overlays off): poses, rest and totals go; <see cref="Slot.Flag"/> also drops the layout.</summary>
        Clear,
    }

    /// <summary>A slot as the reader sees it: copied header fields, and the bytes in place (valid until <see cref="Release"/>).</summary>
    public readonly ref struct Slot
    {
        public readonly Kind Kind;
        /// <summary>Host time it came (us).</summary>
        public readonly long RxUs;
        /// <summary><see cref="Environment.TickCount64"/> when it came (a pointer's paint timing).</summary>
        public readonly long RxMs;
        /// <summary>OVERLAY: the PC controller's gate applied (the phone's "Use PC controller for gyro ratchet" on).</summary>
        public readonly bool Gated;
        public readonly byte GateAxes, GatePresses;
        public readonly bool GateHeld;
        /// <summary><see cref="Kind.Clear"/>: the layout goes too.</summary>
        public readonly bool Flag;
        public readonly ReadOnlySpan<byte> Data;

        internal Slot(Kind kind, long rxUs, long rxMs, byte flags, byte axes, byte presses, ReadOnlySpan<byte> data)
        {
            Kind = kind;
            RxUs = rxUs;
            RxMs = rxMs;
            Gated = (flags & FlagGated) != 0;
            GateHeld = (flags & FlagHeld) != 0;
            Flag = (flags & FlagMark) != 0;
            GateAxes = axes;
            GatePresses = presses;
            Data = data;
        }
    }

    // Header: head (writer) and tail (reader) on their own cache lines, then the lost mark and the drop count.
    private const int HeadOff = 0, TailOff = 64, ClearLostOff = 128, DroppedOff = 136;
    private const int SlotsOff = 192;
    // A slot: kind, flags, axes, presses, length (u16), then rx us and rx ms, then the bytes.
    private const int SKind = 0, SFlags = 1, SAxes = 2, SPresses = 3, SLength = 4, SRxUs = 8, SRxMs = 16, SData = 32;
    private const byte FlagGated = 1, FlagHeld = 2, FlagMark = 4;
    private static readonly int SlotBytes = (SData + MaxBytes + 63) & ~63;

    /// <summary>The bytes the ring takes in a <see cref="SharedRegion"/>.</summary>
    public static int BytesNeeded => SlotsOff + Capacity * SlotBytes;

    private readonly byte* _p;
    private readonly SharedRegion? _owned;

    /// <summary>A ring over <paramref name="p"/> (<see cref="BytesNeeded"/> bytes, zeroed when new).</summary>
    public OverlayInbox(byte* p) => _p = p;

    /// <summary>A ring of its own, in this process only (tests).</summary>
    public OverlayInbox()
    {
        _owned = SharedRegion.InProcess();
        _p = _owned.Overlay;
    }

    private ref int Head => ref Unsafe.AsRef<int>(_p + HeadOff);
    private ref int Tail => ref Unsafe.AsRef<int>(_p + TailOff);
    private ref int ClearLostFlag => ref Unsafe.AsRef<int>(_p + ClearLostOff);

    /// <summary>Overlay packets and marks dropped because the ring was full (the reader fell behind or is gone).</summary>
    public long Dropped => Volatile.Read(ref Unsafe.AsRef<long>(_p + DroppedOff));

    private byte* SlotAt(int i) => _p + SlotsOff + (i & (Capacity - 1)) * SlotBytes;

    /// <summary>A packet (<see cref="Kind.Overlay"/>, <see cref="Kind.Samples"/>, <see cref="Kind.Layout"/>); false when full.</summary>
    public bool Put(Kind kind, ReadOnlySpan<byte> plain, long rxUs, long rxMs) => Write(kind, plain, rxUs, rxMs, 0, 0, 0);

    /// <summary>An OVERLAY with the PC controller's gate: the axes left on, a ratchet held, its ratchet presses.</summary>
    public bool PutGated(ReadOnlySpan<byte> plain, long rxUs, long rxMs, byte axes, bool held, byte presses) =>
        Write(Kind.Overlay, plain, rxUs, rxMs, (byte)(FlagGated | (held ? FlagHeld : 0)), axes, presses);

    /// <summary>A mark (<see cref="Kind.TotalsReset"/>, <see cref="Kind.Renew"/>, <see cref="Kind.Clear"/>).</summary>
    public void Mark(Kind kind, long nowUs, bool flag = false)
    {
        if (!Write(kind, default, nowUs, 0, flag ? FlagMark : (byte)0, 0, 0)) Volatile.Write(ref ClearLostFlag, 1);
    }

    private bool Write(Kind kind, ReadOnlySpan<byte> plain, long rxUs, long rxMs, byte flags, byte axes, byte presses)
    {
        int head = Head;
        if (head - Volatile.Read(ref Tail) >= Capacity || plain.Length > MaxBytes)
        {
            Interlocked.Increment(ref Unsafe.AsRef<long>(_p + DroppedOff));
            return false;
        }
        byte* s = SlotAt(head);
        plain.CopyTo(new Span<byte>(s + SData, MaxBytes));
        s[SKind] = (byte)kind;
        s[SFlags] = flags;
        s[SAxes] = axes;
        s[SPresses] = presses;
        *(ushort*)(s + SLength) = (ushort)plain.Length;
        *(long*)(s + SRxUs) = rxUs;
        *(long*)(s + SRxMs) = rxMs;
        // Hands the slot to the reader.
        Volatile.Write(ref Head, head + 1);
        return true;
    }

    /// <summary>True (once) when a mark was dropped: the reader starts everything again.</summary>
    public bool ClearLost() => Interlocked.Exchange(ref ClearLostFlag, 0) != 0;

    /// <summary>The oldest slot not yet read; false when none waits. <see cref="Release"/> it when done.</summary>
    public bool TryPeek(out Slot slot)
    {
        int tail = Tail;
        if (tail == Volatile.Read(ref Head))
        {
            slot = default;
            return false;
        }
        byte* s = SlotAt(tail);
        int length = Math.Min(*(ushort*)(s + SLength), MaxBytes);
        slot = new Slot((Kind)s[SKind], *(long*)(s + SRxUs), *(long*)(s + SRxMs), s[SFlags], s[SAxes], s[SPresses],
            new ReadOnlySpan<byte>(s + SData, length));
        return true;
    }

    public void Release() => Volatile.Write(ref Tail, Tail + 1);

    /// <summary>Drops everything waiting (a reader that starts again on a ring the writer kept filling).</summary>
    public void SkipAll() => Volatile.Write(ref Tail, Volatile.Read(ref Head));
}
