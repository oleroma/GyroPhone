namespace GyroPhone.Host.Perf;

/// <summary>
/// Diagnostics' A/B test (docs/spec/pc-companion.md, "Diagnostics", "A/B test"): switches one thing back and forth
/// every few minutes in the same session, and marks each phase in the log ("ab: phase 3 B: ..."), so A and B are
/// measured on the same day, link and game. tools/diag/gpdiag.py ab splits a save by these marks and compares A with B.
/// Stopping it (or Diagnostics going off) puts the thing back as it was at the start. Runs on the UI thread's tick.
/// </summary>
public sealed class AbTest
{
    public enum Thing { InputProgram, Overlays }

    public const int MinMinutes = 1, MaxMinutes = 10, DefaultMinutes = 3;

    private readonly Func<Thing, bool> _get;
    private readonly Action<Thing, bool> _set;
    private readonly Action<string> _mark;
    private bool _start;
    private long _phaseStartMs;

    /// <param name="get">The thing's state now (input program: true = Rust; overlays: true = shown).</param>
    /// <param name="set">Switches it.</param>
    /// <param name="mark">Writes a line into Diagnostics' log.</param>
    public AbTest(Func<Thing, bool> get, Action<Thing, bool> set, Action<string> mark)
    {
        _get = get;
        _set = set;
        _mark = mark;
    }

    public Thing? Running { get; private set; }
    public int Minutes { get; private set; } = DefaultMinutes;
    /// <summary>1, 2, 3... Odd phases are A (as it was at the start), even ones B.</summary>
    public int Phase { get; private set; }
    public bool InB => Phase % 2 == 0;

    public static string Name(Thing t) => t == Thing.InputProgram ? "input program" : "overlays";

    public static string StateName(Thing t, bool on) => t == Thing.InputProgram ? (on ? "Rust" : "C#") : (on ? "shown" : "hidden");

    /// <summary>What A and B are: "input program Rust" and "C#".</summary>
    public string? Label(bool b) => Running is { } t ? StateName(t, b ? !_start : _start) : null;

    /// <summary>Seconds left in this phase.</summary>
    public int SecondsLeft(long nowMs) => Running == null ? 0 : (int)Math.Max(0, (Minutes * 60_000L - (nowMs - _phaseStartMs) + 999) / 1000);

    public void Start(Thing t, int minutes, long nowMs)
    {
        if (Running != null) Stop(nowMs, "a new A/B test");
        Minutes = Math.Clamp(minutes, MinMinutes, MaxMinutes);
        _start = _get(t);
        Running = t;
        Phase = 1;
        _phaseStartMs = nowMs;
        _mark($"ab: start: {Name(t)} A {StateName(t, _start)}, B {StateName(t, !_start)}, switching every {Minutes} min");
        _mark($"ab: phase 1 A: {Name(t)} {StateName(t, _start)}");
    }

    /// <summary>Each UI tick: switches when the phase is over.</summary>
    public void Tick(long nowMs)
    {
        if (Running is not { } t || nowMs - _phaseStartMs < Minutes * 60_000L) return;
        Phase++;
        _phaseStartMs = nowMs;
        bool want = InB ? !_start : _start;
        _set(t, want);
        _mark($"ab: phase {Phase} {(InB ? "B" : "A")}: {Name(t)} {StateName(t, want)}");
    }

    /// <summary>Ends it and puts the thing back as it was at the start.</summary>
    public void Stop(long nowMs, string why)
    {
        if (Running is not { } t) return;
        if (_get(t) != _start) _set(t, _start);
        _mark($"ab: stopped ({why}) after {Phase} phases; {Name(t)} back to {StateName(t, _start)}");
        Running = null;
        Phase = 0;
    }
}
