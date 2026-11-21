using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using GyroPhone.Host;
using GyroPhone.Host.Split;
using GyroPhone.Protocol;
using Xunit;

namespace GyroPhone.Tests;

/// <summary>The phone's Diagnostics file over TCP: the shared vectors (logfile-v1.json) and a whole fetch from a fake phone.</summary>
public class LogFileTests
{
    private static readonly JsonElement V = JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "vectors", "logfile-v1.json"))).RootElement;

    private static byte[] Key => LogFileWire.LogKey(V.Bytes("kPH"), (uint)V.Long("sessionId"));

    [Fact]
    public void KeyRequestAndFramesMatchTheVectors()
    {
        Assert.Equal(V.Str("logKey"), Hex.S(Key));
        var req = LogFileWire.EncodeRequest(Key, LogFileWire.KindNewest, (ushort)V.Long("maxAgeS"), (uint)V.Long("sessionId"), V.Bytes("reqId"));
        Assert.Equal(V.Str("request"), Hex.S(req));
        Assert.Equal(LogFileWire.Ok, LogFileWire.HeadStatus(V.Bytes("head")));
        var reqId = V.Bytes("reqId");
        Assert.Equal(V.Str("frame1"), Hex.S(LogFileWire.SealFrame(Key, reqId, 1, V.Bytes("data"))));
        Assert.Equal(V.Str("frame2"), Hex.S(LogFileWire.SealFrame(Key, reqId, 2, [])));
        var f0 = V.Bytes("frame0");
        var meta = LogFileWire.DecodeMeta(LogFileWire.OpenFrame(Key, reqId, 0, f0.AsSpan(4))!)!.Value;
        Assert.Equal(V.Str("metaName"), meta.Name);
        Assert.Equal(5u, meta.RawSize);
        Assert.Null(LogFileWire.OpenFrame(Key, reqId, 1, f0.AsSpan(4)));
    }

    [Fact]
    public void TheInputProcessKeyIsTheSharedOne()
    {
        var s = new HostSession((uint)V.Long("sessionId"), new byte[16], "p", new byte[16], new byte[16], V.Bytes("kPH"), new byte[16],
            new byte[Wire.WelcomeSize], new IPEndPoint(IPAddress.Loopback, 1).Serialize(), 0);
        Assert.Equal(V.Str("logKey"), Hex.S(s.LogKey));
    }

    [Fact]
    public async Task AFetchSavesThePhonesFileAfterANotYet()
    {
        var key = Key;
        const uint sid = 7;
        var text = string.Concat(Enumerable.Repeat("1795265391835\tphone\tmove\tmouse move -12,-31\n", 3000));
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var phone = Task.Run(() =>
        {
            for (int round = 0; round < 2; round++)
            {
                using var c = listener.AcceptTcpClient();
                using var s = c.GetStream();
                var req = new byte[LogFileWire.Request];
                s.ReadExactly(req);
                Assert.Equal(sid, BitConverter.ToUInt32(req, 8));
                var reqId = req[12..20];
                if (round == 0)
                {
                    s.Write(Head(LogFileWire.NotYet));
                    continue;
                }
                var zipped = new MemoryStream();
                using (var gz = new GZipStream(zipped, CompressionLevel.Fastest, leaveOpen: true)) gz.Write(Encoding.UTF8.GetBytes(text));
                var z = zipped.ToArray();
                s.Write(Head(LogFileWire.Ok));
                var name = Encoding.UTF8.GetBytes("phone-20261121-211736.txt");
                var meta = new byte[9 + name.Length];
                BitConverter.TryWriteBytes(meta.AsSpan(0), (uint)text.Length);
                BitConverter.TryWriteBytes(meta.AsSpan(4), (uint)z.Length);
                meta[8] = (byte)name.Length;
                name.CopyTo(meta, 9);
                uint i = 0;
                s.Write(LogFileWire.SealFrame(key, reqId, i++, meta));
                for (int off = 0; off < z.Length; off += LogFileWire.FrameMax)
                    s.Write(LogFileWire.SealFrame(key, reqId, i++, z.AsSpan(off, Math.Min(LogFileWire.FrameMax, z.Length - off))));
                s.Write(LogFileWire.SealFrame(key, reqId, i, []));
            }
        });
        var folder = Path.Combine(Path.GetTempPath(), "gp-logfile-" + Guid.NewGuid().ToString("N"));
        var lines = new List<string>();
        try
        {
            var path = await Task.Run(() => PhoneLogFetch.Fetch(new LogFileKey(sid, key, "127.0.0.1"), folder, lines.Add, port, waitMs: 5_000));
            await phone.WaitAsync(TimeSpan.FromSeconds(5));
            Assert.NotNull(path);
            Assert.Equal("phone-20261121-211736.txt", Path.GetFileName(path));
            Assert.Equal(text, File.ReadAllText(path!));
        }
        finally
        {
            listener.Stop();
            if (Directory.Exists(folder)) Directory.Delete(folder, true);
        }
    }

    [Fact]
    public void TheDirectCableIsSaidAndSkipped()
    {
        var lines = new List<string>();
        Assert.Null(PhoneLogFetch.Fetch(new LogFileKey(1, new byte[16], "127.0.0.1"), Path.GetTempPath(), lines.Add));
        Assert.Contains("direct USB cable", lines.Single());
    }

    private static byte[] Head(byte status) => [(byte)'G', (byte)'P', (byte)'L', (byte)'F', 1, status, 0, 0];
}
