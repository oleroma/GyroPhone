using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Host;

/// <summary>One raw Diagnostics event from the input path: what was injected or given to the virtual pad, copied as is.</summary>
[StructLayout(LayoutKind.Sequential)]
public struct DebugRaw
{
    public enum Type : byte { Inject, X360, Ds4 }

    public long Us;
    public Type Kind;
    public InjectEvent Inject;
    public X360Frame X360;
    public Ds4Frame Ds4;
}

/// <summary>
/// Diagnostics' raw events on their way from the input path to the thread that makes them into lines: a fixed ring of
/// <see cref="DebugRaw"/> slots. With the split it sits in memory both processes map (<see cref="SharedRegion"/>): the
/// input process only copies events in, and the companion makes the text, so the input process makes no text for
/// Diagnostics. One writer at a time (the caller's lock), one reader. When full the newest is dropped and counted
/// (<see cref="Dropped"/>).
/// </summary>
public sealed unsafe class DebugQueue : IDisposable
{
    /// <summary>Raw events the input path may queue between two drains.</summary>
    public const int Capacity = 16_384;

    private const int HeadOff = 0, TailOff = 64, DroppedOff = 128, SlotsOff = 192;
    private static readonly int SlotBytes = (Unsafe.SizeOf<DebugRaw>() + 7) & ~7;

    /// <summary>The bytes it takes in a <see cref="SharedRegion"/>.</summary>
    public static int BytesNeeded => SlotsOff + Capacity * SlotBytes;

    private readonly byte* _p;
    private void* _own;

    public DebugQueue(byte* p) => _p = p;

    /// <summary>A queue of its own, in this process only.</summary>
    public DebugQueue()
    {
        _own = NativeMemory.AllocZeroed((nuint)BytesNeeded);
        _p = (byte*)_own;
    }

    private ref int Head => ref Unsafe.AsRef<int>(_p + HeadOff);
    private ref int Tail => ref Unsafe.AsRef<int>(_p + TailOff);

    /// <summary>Raw events dropped because the queue was full (the reader fell behind, or there is none).</summary>
    public long Dropped => Volatile.Read(ref Unsafe.AsRef<long>(_p + DroppedOff));

    private DebugRaw* SlotAt(int i) => (DebugRaw*)(_p + SlotsOff + (i & (Capacity - 1)) * SlotBytes);

    // Where an event goes that the full queue drops.
    private DebugRaw _spare;

    /// <summary>The next free slot to fill, or a spare one when full (dropped, counted); call <see cref="Commit"/> after a fresh one.</summary>
    public ref DebugRaw Next(out bool fresh)
    {
        int head = Head;
        fresh = head - Volatile.Read(ref Tail) < Capacity;
        if (!fresh)
        {
            Interlocked.Increment(ref Unsafe.AsRef<long>(_p + DroppedOff));
            return ref _spare;
        }
        return ref *SlotAt(head);
    }

    /// <summary>Hands the slot <see cref="Next"/> gave (when fresh) to the reader.</summary>
    public void Commit() => Volatile.Write(ref Head, Head + 1);

    /// <summary>The oldest event waiting, if any.</summary>
    public bool TryTake(out DebugRaw raw)
    {
        int tail = Tail;
        if (tail == Volatile.Read(ref Head))
        {
            raw = default;
            return false;
        }
        raw = *SlotAt(tail);
        Volatile.Write(ref Tail, tail + 1);
        return true;
    }

    /// <summary>Drops everything waiting (reader side).</summary>
    public void SkipAll() => Volatile.Write(ref Tail, Volatile.Read(ref Head));

    public void Dispose()
    {
        if (_own == null) return;
        NativeMemory.Free(_own);
        _own = null;
    }
}
