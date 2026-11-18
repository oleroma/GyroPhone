using System.IO.MemoryMappedFiles;
using System.Runtime.InteropServices;

namespace GyroPhone.Host;

/// <summary>
/// The memory the input process and the companion both map (Windows: a named file mapping in the session): the
/// input side writes, the companion reads, and neither ever waits on the other or makes anything to hand data over.
/// Holds <see cref="HeldMirror"/> (held outputs and the PC controller, for the overlays), <see cref="OverlayInbox"/>
/// (the phone's overlay packets), <see cref="DebugQueue"/> (Diagnostics' raw events) and <see cref="MoveRing"/>
/// (the mouse moves the Delivery test times). Made by the input process,
/// opened by the companion with the name the input process tells it.
/// </summary>
public sealed unsafe class SharedRegion : IDisposable
{
    private const int Magic = 0x31535047; // "GPS1"
    /// <summary>Bumped whenever the layout changes: a companion and an input process of different builds never share one.</summary>
    public const int LayoutVersion = 2;

    private const int HeaderBytes = 64;
    private static readonly int HeldOff = HeaderBytes;
    private static readonly int OverlayOff = Align(HeldOff + HeldMirror.BytesNeeded);
    private static readonly int DebugOff = Align(OverlayOff + OverlayInbox.BytesNeeded);
    private static readonly int MovesOff = Align(DebugOff + DebugQueue.BytesNeeded);
    public static readonly int TotalBytes = Align(MovesOff + MoveRing.BytesNeeded);

    private static int Align(int n) => (n + 4095) & ~4095;

    private byte* _base;
    private readonly MemoryMappedFile? _map;
    private readonly MemoryMappedViewAccessor? _view;
    private readonly bool _native;

    private SharedRegion(byte* p, MemoryMappedFile? map, MemoryMappedViewAccessor? view, bool native, bool fresh)
    {
        _base = p;
        _map = map;
        _view = view;
        _native = native;
        if (fresh)
        {
            *(int*)(p + 4) = LayoutVersion;
            *(int*)(p + 8) = Environment.ProcessId;
            System.Threading.Volatile.Write(ref *(int*)p, Magic);
        }
        Held = new HeldMirror(p + HeldOff);
        Overlays = new OverlayInbox(p + OverlayOff);
        Debug = new DebugQueue(p + DebugOff);
        Moves = new MoveRing(p + MovesOff);
    }

    public HeldMirror Held { get; }
    public OverlayInbox Overlays { get; }
    public DebugQueue Debug { get; }
    /// <summary>The mouse moves while the Delivery test runs (written by the input process, read by the companion).</summary>
    public MoveRing Moves { get; }

    /// <summary>The process that made it.</summary>
    public int OwnerPid => *(int*)(_base + 8);

    /// <summary>The start of the overlay ring (for a standalone <see cref="OverlayInbox"/>).</summary>
    internal byte* Overlay => _base + OverlayOff;

    /// <summary>A region in this process only (tests, and one-process use).</summary>
    public static SharedRegion InProcess()
    {
        var p = (byte*)NativeMemory.AllocZeroed((nuint)TotalBytes);
        return new SharedRegion(p, null, null, native: true, fresh: true);
    }

    /// <summary>Makes the named region (Windows); the name must be new.</summary>
    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    public static SharedRegion Create(string name)
    {
        var map = MemoryMappedFile.CreateNew(name, TotalBytes, MemoryMappedFileAccess.ReadWrite);
        return Map(map, fresh: true);
    }

    /// <summary>
    /// The session's region, made by whichever process comes first and kept while either has it open: a companion
    /// keeps it across an input process restart, an input process across a companion restart. Throws when one of
    /// another layout is open (an older build still runs).
    /// </summary>
    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    public static SharedRegion CreateOrOpen(string name)
    {
        var map = MemoryMappedFile.CreateOrOpen(name, TotalBytes, MemoryMappedFileAccess.ReadWrite);
        MemoryMappedViewAccessor? view = null;
        try
        {
            view = map.CreateViewAccessor(0, TotalBytes, MemoryMappedFileAccess.ReadWrite);
            byte* p = null;
            view.SafeMemoryMappedViewHandle.AcquirePointer(ref p);
            p += view.PointerOffset;
            // A new mapping is zeros (a valid empty region): both sides writing the header at once write the same bytes.
            int magic = System.Threading.Volatile.Read(ref *(int*)p);
            if (magic != 0 && (magic != Magic || *(int*)(p + 4) != LayoutVersion))
            {
                view.SafeMemoryMappedViewHandle.ReleasePointer();
                throw new InvalidDataException($"shared memory {name} is of another layout");
            }
            return new SharedRegion(p, map, view, native: false, fresh: magic == 0);
        }
        catch
        {
            view?.Dispose();
            map.Dispose();
            throw;
        }
    }

    /// <summary>Opens the region the input process made; throws when it isn't there or is of another layout.</summary>
    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    public static SharedRegion Open(string name)
    {
        var map = MemoryMappedFile.OpenExisting(name, MemoryMappedFileRights.ReadWrite);
        var r = Map(map, fresh: false);
        if (*(int*)r._base != Magic || *(int*)(r._base + 4) != LayoutVersion)
        {
            r.Dispose();
            throw new InvalidDataException($"shared memory {name} is of another layout");
        }
        return r;
    }

    private static SharedRegion Map(MemoryMappedFile map, bool fresh)
    {
        MemoryMappedViewAccessor? view = null;
        try
        {
            view = map.CreateViewAccessor(0, TotalBytes, MemoryMappedFileAccess.ReadWrite);
            byte* p = null;
            view.SafeMemoryMappedViewHandle.AcquirePointer(ref p);
            return new SharedRegion(p + view.PointerOffset, map, view, native: false, fresh);
        }
        catch
        {
            view?.Dispose();
            map.Dispose();
            throw;
        }
    }

    public void Dispose()
    {
        if (_base == null) return;
        if (_native) NativeMemory.Free(_base);
        else
        {
            _view!.SafeMemoryMappedViewHandle.ReleasePointer();
            _view.Dispose();
            _map!.Dispose();
        }
        _base = null;
    }
}
