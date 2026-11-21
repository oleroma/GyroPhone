using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using GyroPhone.Host.Split;
using GyroPhone.Protocol;

namespace GyroPhone.Host;

/// <summary>
/// Fetches the phone's Diagnostics save over TCP after a Save (<see cref="LogFileWire"/>), so the phone's side lands
/// next to the PC's without adb. Runs on a pool thread, never near the input path; the phone paces what it sends.
/// </summary>
public static class PhoneLogFetch
{
    /// <summary>How long it keeps asking while the phone is still writing its save.</summary>
    public const int WaitMs = 20_000;
    private const int RetryMs = 1_000;
    private const int IoTimeoutMs = 5_000;
    /// <summary>A save older than this on the phone isn't the one just made.</summary>
    private const ushort MaxAgeS = 60;
    /// <summary>A phone save is a few MB at most; anything far bigger is refused.</summary>
    private const long MaxRaw = 64L << 20;

    /// <summary>
    /// Fetches the newest save from the phone at <paramref name="access"/> into <paramref name="folder"/> and returns its
    /// path, or null (said in <paramref name="log"/>). <paramref name="port"/> is for the tests.
    /// </summary>
    public static string? Fetch(LogFileKey access, string folder, Action<string> log, int port = LogFileWire.Port, int waitMs = WaitMs)
    {
        if (!IPAddress.TryParse(access.Address, out var ip) || IPAddress.IsLoopback(ip) && port == LogFileWire.Port)
        {
            log("phone recording: not fetched (the phone is on the direct USB cable, which carries no TCP; adb pull it)");
            return null;
        }
        long until = Environment.TickCount64 + waitMs;
        string why = "";
        while (true)
        {
            try
            {
                var (status, path) = TryOnce(access, ip, port, folder);
                if (path != null) return path;
                why = status switch
                {
                    LogFileWire.NotYet => "the phone had no new save",
                    LogFileWire.Refused => "the phone refused the request",
                    LogFileWire.Off => "Diagnostics is off on the phone, or a new session started",
                    _ => "the phone answered oddly",
                };
                if (status is LogFileWire.Refused or LogFileWire.Off) break;
            }
            catch (Exception e) when (e is SocketException or IOException or InvalidDataException or TimeoutException)
            {
                // An older phone app has no file server: the connection is refused.
                why = e is SocketException se ? $"no answer from the phone ({se.SocketErrorCode}; an older phone app has no file server)" : e.Message;
            }
            if (Environment.TickCount64 + RetryMs > until) break;
            Thread.Sleep(RetryMs);
        }
        log($"phone recording: not fetched ({why})");
        return null;
    }

    private static (int Status, string? Path) TryOnce(LogFileKey access, IPAddress ip, int port, string folder)
    {
        using var tcp = new TcpClient(ip.AddressFamily) { NoDelay = true, ReceiveTimeout = IoTimeoutMs, SendTimeout = IoTimeoutMs };
        if (!tcp.ConnectAsync(ip, port).Wait(IoTimeoutMs)) throw new TimeoutException("the phone didn't answer in time");
        using var s = tcp.GetStream();
        var reqId = RandomNumberGenerator.GetBytes(8);
        s.Write(LogFileWire.EncodeRequest(access.Key, LogFileWire.KindNewest, MaxAgeS, access.SessionId, reqId));
        var head = new byte[LogFileWire.Head];
        s.ReadExactly(head);
        int status = LogFileWire.HeadStatus(head);
        if (status != LogFileWire.Ok) return (status, null);
        uint index = 0;
        var meta = LogFileWire.DecodeMeta(ReadFrame(s, access.Key, reqId, index++)) ?? throw new InvalidDataException("a bad first frame");
        if (meta.RawSize > MaxRaw || meta.ZipSize > MaxRaw) throw new InvalidDataException("a file too big");
        using var zipped = new MemoryStream((int)meta.ZipSize);
        while (true)
        {
            var piece = ReadFrame(s, access.Key, reqId, index++);
            if (piece.Length == 0) break;
            zipped.Write(piece);
            if (zipped.Length > meta.ZipSize) throw new InvalidDataException("more data than announced");
        }
        if (zipped.Length != meta.ZipSize) throw new InvalidDataException("the file was cut short");
        zipped.Position = 0;
        string name = Path.GetFileName(meta.Name);
        if (!name.StartsWith("phone-", StringComparison.Ordinal) || !name.EndsWith(".txt", StringComparison.Ordinal) || name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
            name = $"phone-{DateTime.Now:yyyyMMdd-HHmmss}.txt";
        Directory.CreateDirectory(folder);
        string path = Path.Combine(folder, name);
        using (var gz = new GZipStream(zipped, CompressionMode.Decompress))
        using (var f = File.Create(path))
            gz.CopyTo(f);
        // The newest 20 phone recordings stay, as on the phone.
        foreach (var old in Directory.GetFiles(folder, "phone-*.txt").OrderByDescending(x => x, StringComparer.Ordinal).Skip(20)) File.Delete(old);
        return (status, path);
    }

    private static byte[] ReadFrame(NetworkStream s, byte[] key, byte[] reqId, uint index)
    {
        var lenBytes = new byte[4];
        s.ReadExactly(lenBytes);
        uint len = BitConverter.ToUInt32(lenBytes);
        if (len < Wire.GcmTag || len > LogFileWire.FrameMax + Wire.GcmTag) throw new InvalidDataException("a bad frame length");
        var sealedBytes = new byte[len];
        s.ReadExactly(sealedBytes);
        return LogFileWire.OpenFrame(key, reqId, index, sealedBytes) ?? throw new InvalidDataException("a frame that failed its check");
    }
}
