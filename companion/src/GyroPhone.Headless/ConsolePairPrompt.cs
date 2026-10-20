using GyroPhone.Host;
using GyroPhone.Host.Abstractions;

namespace GyroPhone.Headless;

/// <summary>
/// Pair prompt on the console. Show only records the attempt and prints the question; the answer is
/// typed on the main thread's input loop and goes back through HostEngine.AnswerPair.
/// </summary>
internal sealed class ConsolePairPrompt : IPairPrompt
{
    private readonly object _lock = new();
    private uint _attemptId;

    public HostEngine? Engine { get; set; }

    public void Show(uint attemptId, string phoneName, string sasDisplay)
    {
        lock (_lock) _attemptId = attemptId;
        Console.WriteLine();
        Console.WriteLine($"Pair {phoneName}? Code {sasDisplay}");
        Console.WriteLine("Check the phone shows the same code, then type y (allow) or n (deny) and Enter.");
    }

    public void Close(uint attemptId)
    {
        lock (_lock)
        {
            if (_attemptId != attemptId) return;
            _attemptId = 0;
        }
        Console.WriteLine("Pairing prompt closed.");
    }

    /// <summary>Answers the open prompt. False when no prompt is open.</summary>
    public bool TryAnswer(bool allow)
    {
        uint id;
        lock (_lock)
        {
            id = _attemptId;
            _attemptId = 0;
        }
        if (id == 0 || Engine == null) return false;
        Engine.AnswerPair(id, allow);
        return true;
    }
}
