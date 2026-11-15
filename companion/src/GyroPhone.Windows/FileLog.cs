using System.Collections.Concurrent;

namespace GyroPhone.Windows;

/// <summary>
/// Append-only log in %LOCALAPPDATA%\GyroPhone\logs. Write only puts the line in a lock-free queue
/// (and wakes the writer when the queue was empty), so it never waits on the writer thread, which does
/// the file I/O: safe to call from gp-rx, even under the engine lock. Callers log counters and lifecycle
/// events only, never keys.
/// </summary>
public sealed class FileLog : IDisposable
{
    private const int MaxQueued = 1024;
    private readonly long _maxBytes;

    private readonly ConcurrentQueue<string> _queue = new();
    private int _queued;
    private readonly AutoResetEvent _wake = new(false);
    private volatile bool _closing;
    private readonly Thread _thread;
    private readonly Action<string>? _echo;
    private readonly Mutex _fileLock;

    public FileLog(string? directory = null, Action<string>? echo = null, string fileName = "companion.log", long maxBytes = 1_000_000)
    {
        Directory = directory ?? DefaultDirectory();
        Path = System.IO.Path.Combine(Directory, fileName);
        _maxBytes = maxBytes;
        _echo = echo;
        _fileLock = new Mutex(false, LockName(Path));
        // Normal, not below: a writer starved by a busy PC would only let lines pile up (Write never waits on it).
        _thread = new Thread(Run) { Name = "gp-log", IsBackground = true, Priority = ThreadPriority.Normal };
        _thread.Start();
    }

    public string Directory { get; }

    public string Path { get; }

    /// <summary>Why the last write to <see cref="Path"/> failed (exception type and message), or null after a good one.</summary>
    public string? LastError => _lastError;

    private volatile string? _lastError;

    public static string DefaultDirectory() => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "GyroPhone", "logs");

    public void Write(string message)
    {
        if (_closing) return; // late messages during exit are dropped
        // Dropped when the disk stalls, rather than holding up a hot thread.
        if (Interlocked.Increment(ref _queued) > MaxQueued)
        {
            Interlocked.Decrement(ref _queued);
            return;
        }
        _queue.Enqueue($"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {message}");
        _wake.Set();
    }

    private void Run()
    {
        try
        {
            System.IO.Directory.CreateDirectory(Directory);
            if (File.Exists(Path) && new FileInfo(Path).Length > _maxBytes) Rotate();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _lastError = $"{e.GetType().Name}: {e.Message}";
        }
        while (true)
        {
            bool closing = _closing;
            while (_queue.TryDequeue(out var line))
            {
                Interlocked.Decrement(ref _queued);
                WriteLine(line);
            }
            if (closing) return;
            _wake.WaitOne();
        }
    }

    private void WriteLine(string line)
    {
        // Echo from this thread too: a console in QuickEdit selection blocks writers.
        _echo?.Invoke(line);
        try
        {
            if (Append(line + Environment.NewLine) > _maxBytes) Rotate();
            _lastError = null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            _lastError = $"{e.GetType().Name}: {e.Message}";
        }
    }

    /// <summary>
    /// Appends one line and returns the file's length. The companion, the input process and the watcher write the
    /// same file, so it is opened only for the line and shared, including for another one's rotation. Opening for
    /// append only seeks to the end, so two processes writing at once would write over each other's line: a mutex
    /// named after the file (this log thread only waits on it, never a caller) lets one write at a time.
    /// </summary>
    private long Append(string text)
    {
        bool owned = false;
        try
        {
            try
            {
                owned = _fileLock.WaitOne(500);
            }
            catch (AbandonedMutexException)
            {
                owned = true; // a writer died holding it: the file is still fine to append to
            }
            using var f = new FileStream(Path, FileMode.Append, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete);
            var bytes = System.Text.Encoding.UTF8.GetBytes(text);
            f.Write(bytes);
            return f.Length;
        }
        finally
        {
            if (owned) _fileLock.ReleaseMutex();
        }
    }

    /// <summary>One name per log file for every process of this user's session.</summary>
    private static string LockName(string path) =>
        @"Local\GyroPhoneLog-" + Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes(path.ToUpperInvariant())), 0, 8);

    private void Rotate()
    {
        try
        {
            File.Move(Path, Path + ".old", overwrite: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // open in another program: tried again after the next line
        }
    }

    public void Dispose()
    {
        _closing = true;
        _wake.Set();
        if (_thread.Join(1000)) _fileLock.Dispose();
    }
}
