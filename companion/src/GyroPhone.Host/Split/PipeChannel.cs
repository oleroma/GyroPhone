using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace GyroPhone.Host.Split;

/// <summary>
/// The command and event line between the companion and the input process: one duplex stream (a named pipe), each
/// message a length, a kind and JSON. Either side can <see cref="Call"/> (and wait for the reply) and
/// <see cref="Post"/> events; one reader thread per side takes what comes. Calls are answered on that reader thread
/// in order, so a call never overtakes an earlier one. Nothing here is on the input path: the input process uses it
/// only for what the user does and what the windows show.
/// </summary>
public sealed class PipeChannel : IDisposable
{
    private enum Kind : byte { Call = 1, Reply = 2, Event = 3 }

    /// <summary>The longest message either side accepts (a snapshot with many paired phones is a few KB).</summary>
    public const int MaxMessage = 4 * 1024 * 1024;

    public static readonly JsonSerializerOptions Json = new()
    {
        IncludeFields = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        NumberHandling = JsonNumberHandling.AllowNamedFloatingPointLiterals,
    };

    private readonly Stream _stream;
    private readonly object _writeLock = new();
    private readonly ConcurrentDictionary<int, Pending> _pending = new();
    private readonly Func<string, JsonElement, object?>? _onCall;
    private readonly Action<string, JsonElement>? _onEvent;
    private readonly Action<string>? _log;
    private readonly Thread _reader;
    private int _nextId;
    private volatile bool _closed;

    private sealed class Pending
    {
        public readonly ManualResetEventSlim Done = new();
        public bool Ok;
        public string? Error;
        public byte[]? Result;
    }

    /// <param name="onCall">Answers a call from the other side (its method name and JSON array of arguments); may throw.</param>
    /// <param name="onEvent">Takes an event from the other side.</param>
    public PipeChannel(Stream stream, Func<string, JsonElement, object?>? onCall, Action<string, JsonElement>? onEvent,
        Action<string>? log = null, string name = "gp-pipe")
    {
        _stream = stream;
        _onCall = onCall;
        _onEvent = onEvent;
        _log = log;
        _reader = new Thread(ReadLoop) { Name = name, IsBackground = true };
        _reader.Start();
    }

    /// <summary>The other side went away (the pipe broke or closed).</summary>
    public event Action? Closed;

    public bool IsClosed => _closed;

    /// <summary>Calls <paramref name="method"/> on the other side and waits for its answer; throws <see cref="PipeClosedException"/> when the line is gone.</summary>
    public T? Call<T>(string method, params object?[] args)
    {
        var bytes = CallRaw(method, args, Timeout.InfiniteTimeSpan);
        return bytes == null ? default : JsonSerializer.Deserialize<T>(bytes, Json);
    }

    /// <summary>A call whose answer is ignored.</summary>
    public void Call(string method, params object?[] args) => CallRaw(method, args, Timeout.InfiniteTimeSpan);

    /// <summary>As <see cref="Call{T}"/>, giving up after <paramref name="timeout"/> (throws <see cref="TimeoutException"/>).</summary>
    public byte[]? CallRaw(string method, object?[] args, TimeSpan timeout)
    {
        if (_closed) throw new PipeClosedException();
        int id = Interlocked.Increment(ref _nextId);
        var p = new Pending();
        _pending[id] = p;
        try
        {
            var payload = JsonSerializer.SerializeToUtf8Bytes(new CallMessage(id, method, args), Json);
            Write(Kind.Call, payload);
            if (!p.Done.Wait(timeout)) throw new TimeoutException($"{method}: no answer");
            if (!p.Ok) throw p.Error == null ? new PipeClosedException() : new InvalidOperationException($"{method}: {p.Error}");
            return p.Result;
        }
        finally
        {
            _pending.TryRemove(id, out _);
        }
    }

    /// <summary>Sends an event; false when the line is gone (dropped, never thrown).</summary>
    public bool Post(string name, object? payload = null)
    {
        if (_closed) return false;
        try
        {
            Write(Kind.Event, JsonSerializer.SerializeToUtf8Bytes(new EventMessage(name, payload), Json));
            return true;
        }
        catch (Exception e) when (e is IOException or ObjectDisposedException or PipeClosedException)
        {
            Close();
            return false;
        }
    }

    private sealed record CallMessage(int Id, string Method, object?[] Args);
    private sealed record ReplyMessage(int Id, bool Ok, string? Error, JsonElement? Result);
    private sealed record EventMessage(string Name, object? Payload);

    private void Write(Kind kind, byte[] payload)
    {
        if (payload.Length > MaxMessage) throw new InvalidOperationException("message too long");
        Span<byte> head = stackalloc byte[5];
        BinaryPrimitives.WriteInt32LittleEndian(head, payload.Length);
        head[4] = (byte)kind;
        lock (_writeLock)
        {
            if (_closed) throw new PipeClosedException();
            try
            {
                _stream.Write(head);
                _stream.Write(payload);
                _stream.Flush();
            }
            catch (Exception e) when (e is IOException or ObjectDisposedException)
            {
                Close();
                throw new PipeClosedException();
            }
        }
    }

    private void ReadLoop()
    {
        var head = new byte[5];
        try
        {
            while (!_closed)
            {
                if (!ReadExactly(head)) break;
                int length = BinaryPrimitives.ReadInt32LittleEndian(head);
                if (length < 0 || length > MaxMessage) throw new InvalidDataException("bad message length");
                var body = new byte[length];
                if (!ReadExactly(body)) break;
                Handle((Kind)head[4], body);
            }
        }
        catch (Exception e) when (e is IOException or ObjectDisposedException or InvalidDataException or JsonException)
        {
            if (!_closed) _log?.Invoke($"pipe: {e.GetType().Name}: {e.Message}");
        }
        Close();
    }

    private bool ReadExactly(byte[] buffer)
    {
        int got = 0;
        while (got < buffer.Length)
        {
            int n = _stream.Read(buffer, got, buffer.Length - got);
            if (n <= 0) return false;
            got += n;
        }
        return true;
    }

    private void Handle(Kind kind, byte[] body)
    {
        using var doc = JsonDocument.Parse(body);
        var root = doc.RootElement;
        switch (kind)
        {
            case Kind.Call:
            {
                int id = root.GetProperty("id").GetInt32();
                string method = root.GetProperty("method").GetString() ?? "";
                var args = root.GetProperty("args").Clone();
                ReplyMessage reply;
                try
                {
                    object? result = _onCall == null ? null : _onCall(method, args);
                    reply = new ReplyMessage(id, true, null, result == null ? null : JsonSerializer.SerializeToElement(result, result.GetType(), Json));
                }
                catch (Exception e)
                {
                    var inner = e is TargetInvocationException { InnerException: { } ie } ? ie : e;
                    _log?.Invoke($"pipe: {method} failed: {inner.GetType().Name}: {inner.Message}");
                    reply = new ReplyMessage(id, false, $"{inner.GetType().Name}: {inner.Message}", null);
                }
                try
                {
                    Write(Kind.Reply, JsonSerializer.SerializeToUtf8Bytes(reply, Json));
                }
                catch (PipeClosedException)
                {
                }
                break;
            }
            case Kind.Reply:
            {
                int id = root.GetProperty("id").GetInt32();
                if (!_pending.TryGetValue(id, out var p)) break;
                p.Ok = root.GetProperty("ok").GetBoolean();
                p.Error = root.TryGetProperty("error", out var err) && err.ValueKind == JsonValueKind.String ? err.GetString() : null;
                p.Result = root.TryGetProperty("result", out var res) && res.ValueKind != JsonValueKind.Null
                    ? JsonSerializer.SerializeToUtf8Bytes(res, Json) : null;
                p.Done.Set();
                break;
            }
            case Kind.Event:
            {
                string name = root.GetProperty("name").GetString() ?? "";
                var payload = root.TryGetProperty("payload", out var pl) ? pl.Clone() : default;
                try
                {
                    _onEvent?.Invoke(name, payload);
                }
                catch (Exception e) when (e is not OutOfMemoryException)
                {
                    _log?.Invoke($"pipe: event {name} failed: {e.GetType().Name}: {e.Message}");
                }
                break;
            }
        }
    }

    /// <summary>Closes the line: waiting calls fail, <see cref="Closed"/> is raised once.</summary>
    public void Close()
    {
        lock (_writeLock)
        {
            if (_closed) return;
            _closed = true;
        }
        try { _stream.Dispose(); } catch (Exception) { }
        foreach (var p in _pending.Values)
        {
            p.Ok = false;
            p.Error = null;
            p.Done.Set();
        }
        try { Closed?.Invoke(); } catch (Exception) { }
    }

    public void Dispose() => Close();

    // =====================================================================================
    // calls by interface: the companion calls IInputControl, the input process answers with its own
    // =====================================================================================

    /// <summary>
    /// Answers calls by invoking the method of the same name on <paramref name="target"/> (an <typeparamref name="T"/>),
    /// its arguments read as that method's parameter types.
    /// </summary>
    public static Func<string, JsonElement, object?> Dispatch<T>(T target) where T : class
    {
        var methods = new Dictionary<string, MethodInfo>(StringComparer.Ordinal);
        foreach (var m in typeof(T).GetMethods()) methods[m.Name] = m;
        foreach (var i in typeof(T).GetInterfaces())
            foreach (var m in i.GetMethods()) methods.TryAdd(m.Name, m);
        return (name, args) =>
        {
            if (!methods.TryGetValue(name, out var m)) throw new MissingMethodException(typeof(T).Name, name);
            var ps = m.GetParameters();
            var values = new object?[ps.Length];
            int i = 0;
            if (args.ValueKind == JsonValueKind.Array)
                foreach (var a in args.EnumerateArray())
                {
                    if (i >= ps.Length) break;
                    values[i] = a.Deserialize(ps[i].ParameterType, Json);
                    i++;
                }
            for (; i < ps.Length; i++) values[i] = ps[i].HasDefaultValue ? ps[i].DefaultValue : null;
            return m.Invoke(target, values);
        };
    }

    /// <summary>An <typeparamref name="T"/> whose calls go over this line (the other side answers with <see cref="Dispatch"/>).</summary>
    public T Proxy<T>() where T : class
    {
        var proxy = DispatchProxy.Create<T, ChannelProxy>();
        ((ChannelProxy)(object)proxy).Source = () => this;
        return proxy;
    }

    /// <summary>
    /// An <typeparamref name="T"/> whose calls go over whatever line <paramref name="source"/> gives now, and never
    /// throw for a missing or broken line, or one that doesn't answer within <paramref name="timeout"/>: they tell
    /// <paramref name="failed"/> (the method and why) and return the type's default. For a window, which must keep
    /// working while the other process restarts.
    /// </summary>
    public static T Proxy<T>(Func<PipeChannel?> source, TimeSpan timeout, Action<string, Exception> failed) where T : class
    {
        var proxy = DispatchProxy.Create<T, ChannelProxy>();
        var p = (ChannelProxy)(object)proxy;
        p.Source = source;
        p.Timeout = timeout;
        p.Failed = failed;
        return proxy;
    }

    /// <summary>Forwards interface calls over a <see cref="PipeChannel"/>.</summary>
    public class ChannelProxy : DispatchProxy
    {
        internal Func<PipeChannel?>? Source;
        internal TimeSpan Timeout = System.Threading.Timeout.InfiniteTimeSpan;
        internal Action<string, Exception>? Failed;

        protected override object? Invoke(MethodInfo? method, object?[]? args)
        {
            if (method == null) return null;
            object? Default() => method.ReturnType != typeof(void) && method.ReturnType.IsValueType ? Activator.CreateInstance(method.ReturnType) : null;
            byte[]? bytes;
            try
            {
                var channel = Source?.Invoke() ?? throw new PipeClosedException();
                bytes = channel.CallRaw(method.Name, args ?? [], Timeout);
            }
            catch (Exception e) when (Failed != null && e is PipeClosedException or TimeoutException or InvalidOperationException or JsonException)
            {
                Failed(method.Name, e);
                return Default();
            }
            if (method.ReturnType == typeof(void)) return null;
            if (bytes == null) return Default();
            return JsonSerializer.Deserialize(bytes, method.ReturnType, Json);
        }
    }
}

/// <summary>The other side of a <see cref="PipeChannel"/> is gone.</summary>
public sealed class PipeClosedException : IOException
{
    public PipeClosedException() : base("the other process is gone")
    {
    }
}
