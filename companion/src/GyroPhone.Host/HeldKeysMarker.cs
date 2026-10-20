using System.Text.Json;
using GyroPhone.Host.Abstractions;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;

namespace GyroPhone.Host;

/// <summary>
/// Crash safety for injected input: while anything is held, %LOCALAPPDATA%\GyroPhone\held.json lists
/// it. Written or deleted only from the watchdog thread when the engine's held-set version changes;
/// on the next start the listed keys and buttons are released and the file is deleted.
/// </summary>
public sealed class HeldKeysMarker
{
    private int _writtenVersion = -1;

    public HeldKeysMarker(string path) => Path = path;

    public string Path { get; }

    public static string DefaultPath() => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GyroPhone", "held.json");

    /// <summary>Writes the held set (or deletes the file when nothing is held) if the version changed.</summary>
    public void Update(int version, ReadOnlySpan<byte> keys, byte mouseButtons)
    {
        if (version == _writtenVersion) return;
        try
        {
            if (!InputDiff.AnyHeld(keys, mouseButtons))
            {
                if (File.Exists(Path)) File.Delete(Path);
            }
            else
            {
                var usages = new List<int>();
                for (int u = 0; u < 256; u++)
                    if ((keys[u >> 3] & (1 << (u & 7))) != 0) usages.Add(u);
                var dir = System.IO.Path.GetDirectoryName(Path);
                if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
                var json = JsonSerializer.Serialize(new HeldFile { Keys = usages, MouseButtons = mouseButtons }, HeldJson.Default.HeldFile);
                File.WriteAllText(Path, json);
            }
            _writtenVersion = version;
        }
        catch (IOException)
        {
            // Retried on the next tick because _writtenVersion is unchanged.
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    /// <summary>Releases whatever a previous run left held, then deletes the marker. Returns the event count.</summary>
    public static int Recover(string path, IKeyMouseSink sink)
    {
        if (!File.Exists(path)) return 0;
        int n = 0;
        try
        {
            var f = JsonSerializer.Deserialize(File.ReadAllText(path), HeldJson.Default.HeldFile);
            if (f != null)
            {
                var keys = new byte[StateFrame.KeyBitmapSize];
                foreach (var u in f.Keys ?? [])
                    if (u is >= 0 and < 256) keys[u >> 3] |= (byte)(1 << (u & 7));
                byte mb = (byte)(f.MouseButtons & 0x1F);
                var events = new InjectEvent[256 + 8];
                n = InputDiff.ReleaseAll(keys, ref mb, events);
                if (n > 0) sink.Inject(events.AsSpan(0, n));
            }
        }
        catch (JsonException)
        {
        }
        try { File.Delete(path); } catch (IOException) { }
        return n;
    }
}

internal sealed class HeldFile
{
    public List<int>? Keys { get; set; }
    public int MouseButtons { get; set; }
}

[System.Text.Json.Serialization.JsonSerializable(typeof(HeldFile))]
internal sealed partial class HeldJson : System.Text.Json.Serialization.JsonSerializerContext;
