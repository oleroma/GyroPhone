using System.Buffers.Binary;
using System.ComponentModel;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Principal;
using GyroPhone.Windows;
using Microsoft.Win32.SafeHandles;

namespace GyroPhone.FrameHelper;

/// <summary>
/// The FPS helper (docs/spec/pc-companion.md, "Performance"). A Windows task starts it as administrator at
/// sign-in. It waits on a pipe only this user can open; while the companion is connected it reads every
/// program's frames with <see cref="EtwPresents"/> and passes them on (<see cref="FrameHelperWire"/>); on a second
/// pipe, while the companion's Diagnostics is on, the moments Windows received GyroPhone's packets
/// (<see cref="EtwNetArrivals"/>). It takes no commands, opens no process and touches no game: it only listens to Windows' own events.
/// </summary>
internal static class Program
{
    private static int Main()
    {
        string sid;
        using (var id = WindowsIdentity.GetCurrent()) sid = id.User?.Value ?? "";
        if (sid.Length == 0) return 2;
        // Diagnostics' network arrival watch on its own pipe and thread: the companion connects to it only while
        // Diagnostics is on.
        new Thread(() => Listen(FrameHelperWire.NetPipeName(sid), sid, ServeNet)) { IsBackground = true, Name = "gp-net-pipe" }.Start();
        return Listen(FrameHelperWire.PipeName(sid), sid, Serve);
    }

    /// <summary>Waits for the companion on the pipe and serves it, again and again; 1 when another helper holds the name.</summary>
    private static int Listen(string name, string sid, Action<NamedPipeServerStream> serve)
    {
        while (true)
        {
            var handle = CreatePipe(name, sid);
            // Another helper already runs (or something else holds the name): leave it.
            if (handle.IsInvalid) return 1;
            using var pipe = new NamedPipeServerStream(PipeDirection.Out, false, false, handle);
            try
            {
                pipe.WaitForConnection();
                serve(pipe);
            }
            catch (IOException)
            {
                // The companion went away: wait for the next one.
            }
        }
    }

    /// <summary>The network arrival watch while the companion is connected: one record per datagram to GyroPhone's port.</summary>
    private static void ServeNet(NamedPipeServerStream pipe)
    {
        var pending = new List<(long Qpc, int Size, int Port)>(1024);
        var gate = new object();
        using var etw = new EtwNetArrivals(_ => { }, FrameHelperWire.Port);
        int rc = etw.Start((qpc, size, port) =>
        {
            lock (gate) pending.Add((qpc, size, port));
        }, out _);
        WriteHello(pipe, rc);
        if (rc != 0) return;

        var batch = new List<(long Qpc, int Size, int Port)>(1024);
        const int size = FrameHelperWire.NetRecordSize;
        var buf = new byte[size * 256];
        long beat = Environment.TickCount64;
        while (true)
        {
            Thread.Sleep(20);
            lock (gate)
            {
                batch.AddRange(pending);
                pending.Clear();
            }
            long now = Environment.TickCount64;
            if (batch.Count == 0)
            {
                if (now - beat < 1000) continue;
                batch.Add((0, 0, 0)); // a heartbeat
            }
            beat = now;
            for (int i = 0; i < batch.Count; i += 256)
            {
                int n = Math.Min(256, batch.Count - i);
                for (int j = 0; j < n; j++)
                {
                    var span = buf.AsSpan(j * size, size);
                    var (qpc, bytes, port) = batch[i + j];
                    BinaryPrimitives.WriteInt64LittleEndian(span, qpc);
                    BinaryPrimitives.WriteInt32LittleEndian(span[8..], bytes);
                    BinaryPrimitives.WriteUInt16LittleEndian(span[12..], (ushort)port);
                    BinaryPrimitives.WriteUInt16LittleEndian(span[14..], 0);
                }
                pipe.Write(buf, 0, n * size);
            }
            batch.Clear();
        }
    }

    private static void WriteHello(NamedPipeServerStream pipe, int rc)
    {
        var hello = new byte[FrameHelperWire.HelloSize];
        BinaryPrimitives.WriteUInt32LittleEndian(hello, FrameHelperWire.Magic);
        BinaryPrimitives.WriteInt32LittleEndian(hello.AsSpan(4), FrameHelperWire.Version);
        BinaryPrimitives.WriteInt32LittleEndian(hello.AsSpan(8), rc);
        pipe.Write(hello);
        pipe.Flush();
    }

    private static void Serve(NamedPipeServerStream pipe)
    {
        var pending = new List<(int Pid, long Qpc)>(256);
        var gate = new object();
        using var etw = new EtwPresents(_ => { });
        int rc = etw.Start((pid, qpc) =>
        {
            lock (gate) pending.Add((pid, qpc));
        }, out _);

        WriteHello(pipe, rc);
        if (rc != 0) return;

        var batch = new List<(int Pid, long Qpc)>(256);
        var buf = new byte[FrameHelperWire.RecordSize * 64];
        long beat = Environment.TickCount64;
        while (true)
        {
            Thread.Sleep(10);
            lock (gate)
            {
                batch.AddRange(pending);
                pending.Clear();
            }
            long now = Environment.TickCount64;
            if (batch.Count == 0)
            {
                if (now - beat < 1000) continue;
                batch.Add((0, 0)); // a heartbeat: a closed companion shows as a failed write
            }
            beat = now;
            for (int i = 0; i < batch.Count; i += 64)
            {
                int n = Math.Min(64, batch.Count - i);
                for (int j = 0; j < n; j++)
                {
                    var span = buf.AsSpan(j * FrameHelperWire.RecordSize);
                    BinaryPrimitives.WriteInt32LittleEndian(span, batch[i + j].Pid);
                    BinaryPrimitives.WriteInt64LittleEndian(span[4..], batch[i + j].Qpc);
                }
                pipe.Write(buf, 0, n * FrameHelperWire.RecordSize);
            }
            batch.Clear();
        }
    }

    /// <summary>
    /// The pipe, open only to this user (not even to other administrators' programs) and to the companion,
    /// which runs at normal, not administrator, level (the medium integrity label). The companion reads
    /// this pipe, so the label stops reads too (NR): a low-integrity program of this user (a sandboxed
    /// browser tab) cannot open it and take the one slot.
    /// </summary>
    private static SafePipeHandle CreatePipe(string name, string sid)
    {
        string sddl = $"D:P(A;;GA;;;{sid})S:(ML;;NWNR;;;ME)";
        if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, 1, out var sd, IntPtr.Zero))
            throw new Win32Exception(Marshal.GetLastPInvokeError());
        try
        {
            var sa = new SecurityAttributes { Length = Marshal.SizeOf<SecurityAttributes>(), Descriptor = sd };
            // The first instance always: if anything else made a pipe of this name, this fails instead of joining it.
            return CreateNamedPipeW(@"\\.\pipe\" + name, PipeAccessOutbound | FileFlagFirstPipeInstance, PipeTypeByte | PipeRejectRemoteClients,
                1, 64 * 1024, 0, 0, ref sa);
        }
        finally
        {
            LocalFree(sd);
        }
    }

    private const uint PipeAccessOutbound = 0x2, FileFlagFirstPipeInstance = 0x00080000;
    private const uint PipeTypeByte = 0, PipeRejectRemoteClients = 0x8;

    [StructLayout(LayoutKind.Sequential)]
    private struct SecurityAttributes
    {
        public int Length;
        public IntPtr Descriptor;
        public int Inherit;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafePipeHandle CreateNamedPipeW(string name, uint openMode, uint pipeMode, uint maxInstances,
        uint outBuffer, uint inBuffer, uint timeout, ref SecurityAttributes attributes);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptorW(string sddl, uint revision,
        out IntPtr descriptor, IntPtr size);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr mem);
}
