using System.Runtime;
using GyroPhone.Host;
using GyroPhone.Host.Abstractions;
using GyroPhone.Host.Settings;
using GyroPhone.Protocol;
using GyroPhone.Protocol.Mapping;
using GyroPhone.Windows;

namespace GyroPhone.Headless;

/// <summary>
/// GyroPhoneHeadless [--pair] [--no-vigem] [--latency-csv path]. Runs the same engine as the tray
/// without UI: pairing is answered by typing y/n, and --latency-csv logs one row per injected STATE.
/// </summary>
internal static class Program
{
    private const string Usage =
        "GyroPhoneHeadless [--pair] [--no-vigem] [--latency-csv <path>]\n"
        + "  --pair          open the pairing window at start\n"
        + "  --no-vigem      no virtual pad, keyboard and mouse only\n"
        + "  --latency-csv   write seq,phoneUs,hostRecvUs,latencyUs,injectUs per injected STATE\n"
        + "Commands while running: y / n answer a pairing prompt, p pair new phone, s status, q quit.";

    private static int Main(string[] args)
    {
        bool pair = false, noVigem = false;
        string? csvPath = null;
        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--pair":
                    pair = true;
                    break;
                case "--no-vigem":
                    noVigem = true;
                    break;
                case "--latency-csv" when i + 1 < args.Length:
                    csvPath = args[++i];
                    break;
                case "-h" or "--help" or "/?":
                    Console.WriteLine(Usage);
                    return 0;
                default:
                    Console.Error.WriteLine($"unknown argument '{args[i]}'");
                    Console.Error.WriteLine(Usage);
                    return 2;
            }
        }

        GCSettings.LatencyMode = GCLatencyMode.SustainedLowLatency;
        using var log = new FileLog(echo: Console.WriteLine);
        var clock = StopwatchClock.Instance;
        var keyMouse = new SendInputSink(log.Write);

        var store = new SettingsStore(SettingsStore.DefaultPath());
        var settings = store.Load(Environment.MachineName, log.Write);

        IPadSink pad = NullPadSink.Instance;
        if (!noVigem)
        {
            pad = ViGEmPadSink.Create(log.Write, out var padError);
            if (padError != null)
                log.Write($"{padError}: gamepad output off, keyboard and mouse still work. Driver: {ViGEmPadSink.DriverDownloadUrl}");
        }

        var prompt = new ConsolePairPrompt();
        var events = new ConsoleEvents(log);
        var engine = new HostEngine(settings, pad, keyMouse, prompt, new DpapiKeyProtector(), clock, events);
        prompt.Engine = engine;

        LatencyCsv? csv = null;
        if (csvPath != null)
        {
            csv = new LatencyCsv(csvPath);
            engine.LatencySampleHook = csv.Add;
            log.Write($"latency log: {Path.GetFullPath(csvPath)}");
        }

        var server = new UdpHostServer(engine, clock,
            onRxThreadStart: () => Mmcss.TryEnter("Games", Mmcss.AvrtPriorityHigh, log.Write), log: log.Write);
        if (!server.Start())
        {
            log.Write(server.State == ServerState.PortInUse
                ? $"Port {Wire.Port} in use: is the GyroPhone tray app already running?"
                : $"could not open UDP port {Wire.Port}");
            csv?.Dispose();
            return 1;
        }
        // Owning the port is the single-instance guard: only now is the marker ours to recover.
        var markerPath = HeldKeysMarker.DefaultPath();
        int recovered = HeldKeysMarker.Recover(markerPath, keyMouse);
        if (recovered > 0) log.Write($"released {recovered} inputs left held by a previous run");
        var marker = new HeldKeysMarker(markerPath);
        var watchdog = new Watchdog(engine, clock, marker, store,
            ElevationProbe.IsForegroundElevated, NetworkCategory.IsAnyConnectedPublic, log.Write);

        engine.Start(clock.NowUs);
        if (pair) engine.OpenPairingWindow();
        watchdog.Start();
        log.Write($"listening on UDP {Wire.Port} as '{settings.HostName}', output {settings.OutputMode}, driver {(pad.DriverOk ? "ok" : "missing")}");
        string exe = Environment.ProcessPath ?? "GyroPhoneHeadless.exe";
        if (!FirewallHelper.RuleExists())
            log.Write($"no '{FirewallHelper.RuleName}' firewall rule; if the phone cannot find this PC run as admin: netsh {FirewallHelper.DeleteRulesArguments(exe)} & netsh {FirewallHelper.AddRuleArguments(exe)}");
        PrintStatus(engine);

        int shutdown = 0;
        var exited = new ManualResetEventSlim(false);
        void Shutdown()
        {
            if (Interlocked.Exchange(ref shutdown, 1) == 1) return;
            watchdog.Dispose();
            engine.Shutdown();
            server.Stop();
            watchdog.SaveSettings();
            var held = new byte[StateFrame.KeyBitmapSize];
            int version = engine.HeldSnapshot(held, out byte mb);
            marker.Update(version, held, mb);
            csv?.Dispose();
            if (pad is IDisposable d) d.Dispose();
            log.Write("stopped");
            exited.Set();
        }
        AppDomain.CurrentDomain.ProcessExit += (_, _) => Shutdown();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            Shutdown();
        };

        var input = new Thread(() => InputLoop(engine, prompt, Shutdown)) { Name = "gp-console", IsBackground = true };
        input.Start();
        exited.Wait();
        return 0;
    }

    private static void InputLoop(HostEngine engine, ConsolePairPrompt prompt, Action shutdown)
    {
        while (true)
        {
            string? line;
            try
            {
                line = Console.ReadLine();
            }
            catch (IOException)
            {
                return;
            }
            if (line == null) return; // stdin closed: keep running until Ctrl+C
            switch (line.Trim().ToLowerInvariant())
            {
                case "y":
                    if (!prompt.TryAnswer(true)) Console.WriteLine("no pairing prompt is open");
                    break;
                case "n":
                    if (!prompt.TryAnswer(false)) Console.WriteLine("no pairing prompt is open");
                    break;
                case "p":
                    engine.OpenPairingWindow();
                    Console.WriteLine($"pairing window open for {Wire.PairingWindowS} s: on the phone choose this PC in the Wi-Fi tab");
                    break;
                case "s":
                    PrintStatus(engine);
                    break;
                case "q":
                    shutdown();
                    return;
                case "":
                    break;
                default:
                    Console.WriteLine("commands: y, n, p, s, q");
                    break;
            }
        }
    }

    private static void PrintStatus(HostEngine engine)
    {
        var s = engine.Snapshot();
        string lat = s.LatP50Us == HostStatus.Unknown ? "n/a" : $"p50 {s.LatP50Us / 1000.0:0.0} ms, p99 {s.LatP99Us / 1000.0:0.0} ms";
        Console.WriteLine(s.Connected
            ? $"connected to {s.PhoneName}: {s.RxPerSec}/s, loss {s.LossPermille / 10.0:0.0}%, {lat}, jitter {s.JitterUs / 1000.0:0.0} ms"
            : "waiting for a phone");
        Console.WriteLine($"output {s.OutputMode} (driver {(s.DriverOk ? "ok" : "missing")}, pad {(s.PadPlugged ? "plugged" : "off")}), "
            + $"paired phones {s.PairedPhones.Count}, pairing window {(s.PairingWindowS > 0 ? $"{s.PairingWindowS} s" : "closed")}");
        if (s.ElevatedForeground) Console.WriteLine("the window in front runs as administrator: input to it is blocked unless this app runs as administrator too");
        if (s.NetworkPublic) Console.WriteLine("this network is Public: set it to Private in Windows settings");
        var c = s.Counters;
        Console.WriteLine($"datagrams {c.Datagrams}, states {c.States}, injections {c.Injections}, dropped {c.Dropped}, bad auth {c.BadAuth}");
    }

    /// <summary>Logs engine messages; state changes are printed on demand with "s".</summary>
    private sealed class ConsoleEvents(FileLog log) : IHostEvents
    {
        public void StateChanged() { }

        public void Log(string message) => log.Write(message);
    }
}
