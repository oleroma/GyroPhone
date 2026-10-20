namespace GyroPhone.Host.Abstractions;

/// <summary>
/// Asks the user to allow a pairing. Show is called under the engine lock on the rx thread and must
/// return immediately; the answer comes back later through HostEngine.AnswerPair(attemptId, allow).
/// </summary>
public interface IPairPrompt
{
    void Show(uint attemptId, string phoneName, string sasDisplay);

    /// <summary>The attempt ended (answered, timed out, failed or cancelled): close any open prompt.</summary>
    void Close(uint attemptId);
}
