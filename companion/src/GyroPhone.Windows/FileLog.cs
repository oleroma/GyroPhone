using System.Collections.Concurrent;

namespace GyroPhone.Windows;

/// <summary>
/// Append-only log in %LOCALAPPDATA%\GyroPhone\logs. Write only enqueues, so it is safe to call
/// under the engine lock; a background thread does the file I/O. Callers log counters and lifecycle
/// events only, never keys.
/// </summary>
public sealed class FileLog : IDisposable
{
    private const long MaxBytes = 1_000_000;

    private readonly BlockingCollection<string> _queue = new(boundedCapacity: 1024);
    private readonly Thread _thread;
    private readonly Action<string>? _echo;

    public FileLog(string? directory = null, Action<string>? echo = null)
    {
        Directory = directory ?? DefaultDirectory();
        Path = System.IO.Path.Combine(Directory, "companion.log");
        _echo = echo;
        _thread = new Thread(Run) { Name = "gp-log", IsBackground = true, Priority = ThreadPriority.BelowNormal };
        _thread.Start();
    }

    public string Directory { get; }

    public string Path { get; }

    public static string DefaultDirectory() => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GyroPhone", "logs");

    public void Write(string message)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {message}";
        try
        {
            _queue.TryAdd(line); // drops when the disk stalls rather than blocking a hot thread
        }
        catch (InvalidOperationException)
        {
            // disposed: late messages during exit are dropped
        }
    }

    private void Run()
    {
        try
        {
            System.IO.Directory.CreateDirectory(Directory);
            if (File.Exists(Path) && new FileInfo(Path).Length > MaxBytes)
                File.Move(Path, Path + ".old", overwrite: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
        }
        foreach (var line in _queue.GetConsumingEnumerable())
        {
            // Echo from this thread too: a console in QuickEdit selection blocks writers.
            _echo?.Invoke(line);
            try
            {
                File.AppendAllText(Path, line + Environment.NewLine);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
            }
        }
    }

    public void Dispose()
    {
        _queue.CompleteAdding();
        _thread.Join(1000);
    }
}
