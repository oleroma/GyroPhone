using System.Text;
using GyroPhone.Host;

namespace GyroPhone.Tray;

/// <summary>
/// The Debug window (tray menu "Debug window…"): the link as this PC sees it, what the game is
/// given now (virtual pad, held keys and mouse buttons), and the newest events of the debug log,
/// refreshed at 4 Hz. "Save last 30 s" writes a recording to the debug folder. The engine logs
/// only while this window is open or the phone's debug tools are on.
/// </summary>
internal sealed class DebugForm : Form
{
    private const int EventsShown = 400;

    private readonly HostEngine _engine;
    private readonly Func<string?> _save;
    private readonly Label _status;
    private readonly TextBox _events;
    private readonly CheckBox _pause;
    private readonly Dictionary<DebugTrace.Kind, CheckBox> _kinds = new();
    private readonly Label _saved;
    private readonly Font _mono;
    private readonly System.Windows.Forms.Timer _timer;
    private long _shownVersion = -1;
    /// <summary>Local clock ticks minus engine us × 10, taken once so a row's time never changes.</summary>
    private readonly long _clockOffsetTicks =
        DateTime.Now.Ticks - GyroPhone.Host.Abstractions.StopwatchClock.Instance.NowUs * 10;

    /// <param name="save">Saves a recording and returns its path, or null when it could not.</param>
    public DebugForm(HostEngine engine, Func<string?> save, Action openFolder)
    {
        _engine = engine;
        _save = save;
        Text = $"{Program.AppName} debug";
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(1000, 640);
        MinimumSize = new Size(600, 360);
        Padding = new Padding(8);
        _mono = new Font(FontFamily.GenericMonospace, 9.5f);

        var table = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 3 };
        table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        table.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        _status = new Label { AutoSize = true, Font = _mono, Margin = new Padding(3, 3, 3, 8) };
        table.Controls.Add(_status, 0, 0);

        var buttons = new FlowLayoutPanel { AutoSize = true, WrapContents = true, Dock = DockStyle.Fill };
        var saveButton = new Button { Text = "Save last 30 s", AutoSize = true };
        saveButton.Click += (_, _) => ShowSaved(_save());
        var folderButton = new Button { Text = "Open recordings folder", AutoSize = true };
        folderButton.Click += (_, _) => openFolder();
        _pause = new CheckBox { Text = "Pause the list", AutoSize = true, Margin = new Padding(12, 6, 3, 3) };
        _saved = new Label { AutoSize = true, Margin = new Padding(12, 8, 3, 3) };
        buttons.Controls.AddRange([saveButton, folderButton, _pause]);
        // What the list shows (recordings keep everything); movement is hidden until ticked.
        foreach (var k in Enum.GetValues<DebugTrace.Kind>())
        {
            var box = new CheckBox { Text = DebugTrace.Tag(k), AutoSize = true, Checked = k != DebugTrace.Kind.Move, Margin = new Padding(8, 6, 3, 3) };
            box.CheckedChanged += (_, _) => { _shownVersion = -1; UpdateView(); };
            _kinds[k] = box;
            buttons.Controls.Add(box);
        }
        buttons.Controls.Add(_saved);
        table.Controls.Add(buttons, 0, 1);

        _events = new TextBox
        {
            Multiline = true,
            ReadOnly = true,
            WordWrap = false,
            ScrollBars = ScrollBars.Both,
            Dock = DockStyle.Fill,
            Font = _mono,
            BackColor = SystemColors.Window,
        };
        table.Controls.Add(_events, 0, 2);
        Controls.Add(table);

        _timer = new System.Windows.Forms.Timer { Interval = 250 };
        _timer.Tick += (_, _) => UpdateView();
        Load += (_, _) =>
        {
            _engine.Debug.SetWindowOpen(true);
            UpdateView();
            _timer.Start();
        };
        FormClosed += (_, _) =>
        {
            _timer.Stop();
            _engine.Debug.SetWindowOpen(false);
        };
    }

    /// <summary>Shows where a recording went (also one the phone asked for).</summary>
    public void ShowSaved(string? path) =>
        _saved.Text = path == null ? "Could not save (see the log)" : $"Saved {Path.GetFileName(path)}";

    private void UpdateView()
    {
        if (IsDisposed) return;
        _status.Text = StatusText(_engine);
        var d = _engine.Debug;
        if (_pause.Checked || d.Version == _shownVersion) return;
        _shownVersion = d.Version;
        var sb = new StringBuilder();
        var list = d.Latest(EventsShown, k => _kinds[k].Checked);
        for (int i = list.Count - 1; i >= 0; i--)
        {
            var e = list[i];
            var at = new DateTime(_clockOffsetTicks + e.Us * 10);
            sb.Append($"{at:HH:mm:ss.fff}  {DebugTrace.Tag(e.Kind),-5}  {e.Text}\r\n");
        }
        _events.Text = sb.ToString();
    }

    /// <summary>The link, the pad and the held keys now; also the header of a PC recording.</summary>
    internal static string StatusText(HostEngine engine)
    {
        var s = engine.Snapshot();
        var sb = new StringBuilder();
        sb.Append(s.Connected ? $"phone: {s.PhoneName}{(s.Silent ? " (not answering)" : "")}" : "phone: not connected");
        sb.Append($" · output {s.OutputMode}{(s.PadPlugged ? "" : " (not plugged)")}{(s.DriverOk ? "" : " · no ViGEm driver")}");
        if (s.Paused) sb.Append(" · input paused");
        sb.Append("\r\nlink: ");
        sb.Append($"got {s.RxPerSec}/s · loss {s.LossPermille / 10.0:0.0}%");
        sb.Append(s.LatP50Us == GyroPhone.Protocol.HostStatus.Unknown ? " · latency unknown" :
            $" · latency {s.LatP50Us / 1000.0:0.0} / {s.LatP99Us / 1000.0:0.0} ms (p50/p99) · jitter {s.JitterUs / 1000.0:0.0} ms");
        if (s.PhoneLink is { } l)
            sb.Append($" · phone sends {l.SendRateHz}/s · round trip {l.RttMinUs / 1000.0:0.0} ms · {l.RssiDbm} dBm {l.LinkMbps} Mbps");
        sb.Append($"\r\npad: {engine.Debug.CurrentPad}");
        Span<byte> keys = stackalloc byte[32];
        engine.HeldSnapshot(keys, out byte mouse);
        var held = new List<string>();
        for (int u = 0; u < 256; u++)
            if ((keys[u >> 3] & (1 << (u & 7))) != 0) held.Add(DebugTrace.UsageName((byte)u));
        sb.Append($"\r\nkeys: {(held.Count == 0 ? "none" : string.Join("+", held))} · mouse buttons {mouse}");
        sb.Append($"\r\nlogging: {(engine.Debug.Enabled ? "on" : "off")} · counters: {s.Counters}");
        return sb.ToString();
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _timer.Dispose();
            _mono.Dispose();
        }
        base.Dispose(disposing);
    }
}
