namespace GyroPhone.Tray;

/// <summary>"Status…": a read-only text view of the link, refreshed at 2 Hz.</summary>
internal sealed class StatusForm : Form
{
    private readonly Func<string> _text;
    private readonly TextBox _box;
    private readonly Font _mono;
    private readonly System.Windows.Forms.Timer _timer;

    public StatusForm(Func<string> text)
    {
        _text = text;
        Text = "Status - " + Program.AppName;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(600, 380);
        MinimumSize = new Size(360, 240);
        ShowInTaskbar = true;

        _mono = new Font(FontFamily.GenericMonospace, 10f);
        _box = new TextBox
        {
            Multiline = true,
            ReadOnly = true,
            WordWrap = true,
            ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill,
            BorderStyle = BorderStyle.None,
            BackColor = SystemColors.Window,
            Font = _mono,
            TabStop = false,
        };
        Controls.Add(_box);

        _timer = new System.Windows.Forms.Timer { Interval = 500 };
        _timer.Tick += (_, _) => UpdateText();
        Load += (_, _) =>
        {
            UpdateText();
            _timer.Start();
        };
    }

    private void UpdateText()
    {
        var t = _text();
        if (t != _box.Text) _box.Text = t; // unchanged text keeps the selection and scroll position
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        _timer.Stop();
        base.OnFormClosed(e);
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
