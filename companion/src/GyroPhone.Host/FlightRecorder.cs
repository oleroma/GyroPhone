using System.Buffers.Binary;

namespace GyroPhone.Host;

/// <summary>
/// Diagnostics' per-packet record (docs/spec/pc-companion.md, "Diagnostics", "Flight record"): one fixed-size entry
/// per accepted STATE, in a ring made when Diagnostics comes on (sized for the minutes it keeps), so every question
/// about bunching, gaps or a hold-up's exact shape can be answered after the fact rather than from per-second
/// summaries. Save writes it as a binary file (<see cref="Write"/>; tools/diag/gpdiag.py flight reads it).
/// <para>
/// The input path only stores numbers into preallocated arrays (the engine calls it under its lock, one writer); it never
/// allocates, locks or makes text. <see cref="Write"/> copies without the engine lock: entries the writer overwrote meanwhile
/// are left out. The Rust input program writes the same format (flight.rs).
/// </para>
/// </summary>
public sealed class FlightRecorder
{
    public const int RecordBytes = 32;
    public const int Version = 1;
    /// <summary>Flags of an entry.</summary>
    public const uint Moving = 1, Active = 2, LostBefore = 4, PadOnly = 8, Refused = 16, HandedOff = 32;
    /// <summary>Entries per minute kept: up to a thousand packets a second, with room.</summary>
    public const int PerMinute = 66_000;

    private readonly long[] _recv;
    private readonly uint[] _phone;
    private readonly int[] _extra, _handOff, _lockWait, _driver;
    private readonly uint[] _flags;
    private long _head;

    public FlightRecorder(int minutes)
    {
        int n = Math.Clamp(minutes, 1, 30) * PerMinute;
        Capacity = n;
        _recv = new long[n];
        _phone = new uint[n];
        _extra = new int[n];
        _handOff = new int[n];
        _lockWait = new int[n];
        _driver = new int[n];
        _flags = new uint[n];
    }

    public int Capacity { get; }
    public int Minutes => Capacity / PerMinute;

    /// <summary>Entries so far (all time; the ring keeps the newest <see cref="Capacity"/>).</summary>
    public long Count => Volatile.Read(ref _head);

    /// <summary>One accepted STATE: its phone time, arrival on the engine's clock, how much later than the fastest packets it came.</summary>
    public void State(uint phoneUs, long recvUs, long extraUs, uint flags)
    {
        long h = _head;
        int i = (int)(h % Capacity);
        _recv[i] = recvUs;
        _phone[i] = phoneUs;
        _extra[i] = (int)Math.Clamp(extraUs, int.MinValue, int.MaxValue);
        _handOff[i] = -1;
        _lockWait[i] = 0;
        _driver[i] = 0;
        _flags[i] = flags;
        Volatile.Write(ref _head, h + 1);
    }

    /// <summary>The newest STATE was handed to Windows: how long after its arrival, the engine lock wait and the time in the drivers (µs).</summary>
    public void HandOff(long tookUs, long lockWaitUs, long driverUs, uint flags)
    {
        long h = _head;
        if (h == 0) return;
        int i = (int)((h - 1) % Capacity);
        if (_handOff[i] >= 0) return; // a second hand-off of the same packet (a catch-up step): the first counts
        _handOff[i] = (int)Math.Clamp(tookUs, 0, int.MaxValue);
        _lockWait[i] = (int)Math.Clamp(lockWaitUs, 0, int.MaxValue);
        _driver[i] = (int)Math.Clamp(driverUs, 0, int.MaxValue);
        _flags[i] |= flags | HandedOff;
    }

    /// <summary>
    /// Writes the entries that arrived after <paramref name="fromUs"/>, oldest first, with a header: "GPFLIGHT", version,
    /// entry size, count, the engine clock and the wall clock (Unix ms) at <paramref name="nowUs"/>. Any thread; returns the count.
    /// </summary>
    public int Write(Stream s, long fromUs, long nowUs, long unixMs)
    {
        long head = Volatile.Read(ref _head);
        long tail = Math.Max(0, head - Capacity);
        // Leave out the newest (it may be half written) and anything the writer may reach while this copies.
        long end = head - 1;
        int n = 0;
        var all = new byte[Math.Max(0, end - tail) * RecordBytes];
        for (long k = tail; k < end; k++)
        {
            int i = (int)(k % Capacity);
            if (_recv[i] <= fromUs) continue;
            Fill(all.AsSpan(n * RecordBytes, RecordBytes), i);
            n++;
        }
        // Entries the writer reached while this copied (only once the ring is full): leave out that many of the oldest,
        // and one more for the entry it may be writing.
        long reached = Volatile.Read(ref _head) + 1 - Capacity - tail;
        int skip = (int)Math.Clamp(reached, 0, n);
        var header = new byte[40];
        "GPFLIGHT"u8.CopyTo(header);
        BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(8), Version);
        BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(12), RecordBytes);
        BinaryPrimitives.WriteInt64LittleEndian(header.AsSpan(16), n - skip);
        BinaryPrimitives.WriteInt64LittleEndian(header.AsSpan(24), nowUs);
        BinaryPrimitives.WriteInt64LittleEndian(header.AsSpan(32), unixMs);
        s.Write(header);
        s.Write(all, skip * RecordBytes, (n - skip) * RecordBytes);
        return n - skip;
    }

    private void Fill(Span<byte> s, int i)
    {
        BinaryPrimitives.WriteInt64LittleEndian(s, _recv[i]);
        BinaryPrimitives.WriteUInt32LittleEndian(s[8..], _phone[i]);
        BinaryPrimitives.WriteInt32LittleEndian(s[12..], _extra[i]);
        BinaryPrimitives.WriteInt32LittleEndian(s[16..], _handOff[i]);
        BinaryPrimitives.WriteInt32LittleEndian(s[20..], _lockWait[i]);
        BinaryPrimitives.WriteInt32LittleEndian(s[24..], _driver[i]);
        BinaryPrimitives.WriteUInt32LittleEndian(s[28..], _flags[i]);
    }

    /// <summary>The file a save writes: flight-&lt;local time&gt;.bin in <paramref name="folder"/> (the logs folder); keeps the newest 5.</summary>
    public string Save(string folder, long fromUs, long nowUs)
    {
        Directory.CreateDirectory(folder);
        string path = Path.Combine(folder, $"flight-{DateTime.Now:yyyyMMdd-HHmmss}.bin");
        using (var f = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read, 1 << 16))
            Write(f, fromUs, nowUs, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        foreach (var old in Directory.GetFiles(folder, "flight-*.bin").OrderByDescending(x => x).Skip(5)) File.Delete(old);
        return path;
    }
}
