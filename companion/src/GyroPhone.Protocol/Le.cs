using System.Buffers.Binary;

namespace GyroPhone.Protocol;

/// <summary>Allocation-free little-endian accessors at an offset.</summary>
public static class Le
{
    public static void PutU16(Span<byte> b, int off, ushort v) => BinaryPrimitives.WriteUInt16LittleEndian(b[off..], v);
    public static void PutI16(Span<byte> b, int off, short v) => BinaryPrimitives.WriteInt16LittleEndian(b[off..], v);
    public static void PutU32(Span<byte> b, int off, uint v) => BinaryPrimitives.WriteUInt32LittleEndian(b[off..], v);
    public static void PutI32(Span<byte> b, int off, int v) => BinaryPrimitives.WriteInt32LittleEndian(b[off..], v);
    public static void PutU64(Span<byte> b, int off, ulong v) => BinaryPrimitives.WriteUInt64LittleEndian(b[off..], v);
    public static void PutI64(Span<byte> b, int off, long v) => BinaryPrimitives.WriteInt64LittleEndian(b[off..], v);

    public static ushort U16(ReadOnlySpan<byte> b, int off) => BinaryPrimitives.ReadUInt16LittleEndian(b[off..]);
    public static short I16(ReadOnlySpan<byte> b, int off) => BinaryPrimitives.ReadInt16LittleEndian(b[off..]);
    public static uint U32(ReadOnlySpan<byte> b, int off) => BinaryPrimitives.ReadUInt32LittleEndian(b[off..]);
    public static int I32(ReadOnlySpan<byte> b, int off) => BinaryPrimitives.ReadInt32LittleEndian(b[off..]);
    public static ulong U64(ReadOnlySpan<byte> b, int off) => BinaryPrimitives.ReadUInt64LittleEndian(b[off..]);
    public static long I64(ReadOnlySpan<byte> b, int off) => BinaryPrimitives.ReadInt64LittleEndian(b[off..]);
}
