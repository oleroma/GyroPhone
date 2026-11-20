using System.Globalization;
using System.Text.Json;

namespace GyroPhone.Host.Perf;

/// <summary>One fact about the setup ("pc adapter" = "Realtek PCIe 2.5GbE"), in the order it is written.</summary>
public readonly record struct SetupItem(string Key, string Value);

/// <summary>
/// The setup a run was made on (docs/spec/pc-companion.md, "Diagnostics", "Setup"): both sides' adapters, drivers,
/// Wi-Fi, power, the phone's battery and charger, versions, the input program and the "known suspect" programs
/// running. Read at Diagnostics on, every few seconds while it runs (off the input threads), and at Save; a change
/// becomes a "setup changed" line, and each record keeps the setup it was made on.
/// </summary>
public static class SetupSnapshot
{
    /// <summary>Change all the time: written in the setup line, never a "setup changed" line.</summary>
    public static readonly HashSet<string> Volatile = ["phone battery", "phone temp", "pc wifi signal", "pc wifi rate"];

    /// <summary>
    /// What the link's floor (the fastest packets) depends on: two runs whose values here match should have about the
    /// same floor (<see cref="FloorBook"/>).
    /// </summary>
    public static readonly string[] LinkKeys =
        ["path", "pc adapter", "pc driver", "pc link", "pc wifi", "phone usb adapter", "phone wifi", "input program"];

    /// <summary>Known USB network adapters (vendor:product, hex), named; others show as their ids.</summary>
    private static readonly Dictionary<int, string> UsbNames = new()
    {
        [0x0bda_8153] = "Realtek RTL8153 (1 Gb)",
        [0x0bda_8156] = "Realtek RTL8156 (2.5 Gb)",
        [0x0bda_8152] = "Realtek RTL8152 (100 Mb)",
        [0x0bda_8157] = "Realtek RTL8157 (5 Gb)",
        [0x0b95_1790] = "ASIX AX88179 (1 Gb)",
        [0x0b95_178a] = "ASIX AX88179A (1 Gb)",
        [0x0b95_772b] = "ASIX AX88772B (100 Mb)",
        [0x0424_7800] = "Microchip LAN7800 (1 Gb)",
        [0x05ac_1402] = "Apple USB Ethernet (100 Mb)",
    };

    /// <summary>The phone's side, from its newest PHONE_STATS (sent only while Diagnostics is on).</summary>
    public static List<SetupItem> Phone(PhoneSecond p)
    {
        var ci = CultureInfo.InvariantCulture;
        var list = new List<SetupItem>(8);
        if (p.UsbVendor != 0)
        {
            int id = p.UsbVendor << 16 | p.UsbProduct;
            string ids = string.Create(ci, $"{p.UsbVendor:x4}:{p.UsbProduct:x4}");
            list.Add(new("phone usb adapter", UsbNames.TryGetValue(id, out var n) ? $"{n} {ids}" : ids));
        }
        else list.Add(new("phone usb adapter", "none"));
        list.Add(new("phone wifi", p.WifiStandard is >= 4 and <= 8 ? $"Wi-Fi {p.WifiStandard}" : "-"));
        list.Add(new("phone charging", (p.SetupFlags & 1) != 0 ? "yes" : "no"));
        list.Add(new("phone battery saver", (p.SetupFlags & 2) != 0 ? "on" : "off"));
        if ((p.SetupFlags & 8) != 0) list.Add(new("phone doze", "yes"));
        list.Add(new("phone thermal", p.Thermal switch
        {
            0 => "none", 1 => "light", 2 => "moderate", 3 => "severe", 4 => "critical", 5 => "emergency", 6 => "shutdown", _ => "-",
        }));
        if (p.Battery is >= 0 and <= 100) list.Add(new("phone battery", $"{p.Battery}%"));
        if (p.TempDeciC != short.MinValue) list.Add(new("phone temp", string.Create(ci, $"{p.TempDeciC / 10.0:0.0} °C")));
        return list;
    }

    /// <summary>"key value · key value".</summary>
    public static string Line(IReadOnlyList<SetupItem> items) => string.Join(" · ", items.Select(i => $"{i.Key} {i.Value}"));

    /// <summary>What changed from <paramref name="before"/> to <paramref name="now"/> ("pc wifi 5 GHz ch 48 → 5 GHz ch 100"), volatile facts left out.</summary>
    public static List<string> Changes(IReadOnlyList<SetupItem> before, IReadOnlyList<SetupItem> now)
    {
        var a = Dict(before);
        var b = Dict(now);
        var list = new List<string>();
        foreach (var (k, v) in b)
        {
            if (Volatile.Contains(k)) continue;
            if (!a.TryGetValue(k, out var old)) list.Add($"{k} {v} (new)");
            else if (old != v) list.Add($"{k} {old} → {v}");
        }
        foreach (var (k, v) in a)
            if (!Volatile.Contains(k) && !b.ContainsKey(k)) list.Add($"{k} {v} (gone)");
        return list;
    }

    /// <summary>The facts the floor depends on, as one key (<see cref="LinkKeys"/>).</summary>
    public static string LinkKey(IReadOnlyList<SetupItem> items)
    {
        var d = Dict(items);
        return string.Join(" | ", LinkKeys.Select(k => d.TryGetValue(k, out var v) ? v : "-"));
    }

    private static Dictionary<string, string> Dict(IReadOnlyList<SetupItem> items)
    {
        var d = new Dictionary<string, string>();
        foreach (var i in items) d[i.Key] = i.Value;
        return d;
    }
}

/// <summary>
/// The last floor (half the best round trip) seen on each setup (<see cref="SetupSnapshot.LinkKey"/>), in
/// floors.json beside the records: a run whose floor differs clearly from the last one on the same setup gets a
/// warning, so a silent change (a different adapter, a driver update, a DFS channel) shows at once.
/// </summary>
public sealed class FloorBook
{
    /// <summary>A floor this much different (ms) and by this share counts as a shift.</summary>
    public const double ShiftMs = 0.15, ShiftShare = 0.25;

    public sealed record Entry(double FloorMs, DateTime At, string Run);

    private readonly string _path;
    private readonly Dictionary<string, Entry> _floors;

    public FloorBook(string folder)
    {
        _path = System.IO.Path.Combine(folder, "floors.json");
        _floors = Load(_path);
    }

    /// <summary>The warning for a run with <paramref name="floorMs"/> on <paramref name="key"/>, or null; then remembers it.</summary>
    public string? CheckAndKeep(string key, double floorMs, DateTime at, string run)
    {
        if (floorMs <= 0 || key.Length == 0) return null;
        string? warning = null;
        if (_floors.TryGetValue(key, out var last) && Shifted(last.FloorMs, floorMs))
            warning = string.Create(CultureInfo.InvariantCulture,
                $"the link's floor is {floorMs:0.00} ms, but {last.FloorMs:0.00} ms on the same setup last time ('{last.Run}', {last.At:d MMM HH:mm}): something on the way changed");
        _floors[key] = new Entry(floorMs, at, run);
        Save();
        return warning;
    }

    public static bool Shifted(double before, double now) =>
        Math.Abs(now - before) >= ShiftMs && Math.Abs(now - before) >= ShiftShare * Math.Min(before, now);

    private static Dictionary<string, Entry> Load(string path)
    {
        try
        {
            if (File.Exists(path) && JsonSerializer.Deserialize<Dictionary<string, Entry>>(File.ReadAllText(path)) is { } d) return d;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException)
        {
        }
        return [];
    }

    private void Save()
    {
        try
        {
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(_path)!);
            string tmp = _path + ".new";
            File.WriteAllText(tmp, JsonSerializer.Serialize(_floors));
            File.Move(tmp, _path, overwrite: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
        }
    }
}
