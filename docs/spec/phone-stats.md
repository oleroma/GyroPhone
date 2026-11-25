# Stats

The pad's **Stats** capsule (top row, right after Defaults) opens a page about how you move the phone in play, for the **workspace in use**. Code: `core/engine/AimStats.kt` (recording and the maths), `app/runtime/AimStats.kt` (its thread and files), `app/ui/StatsScreen.kt` (the page).

## On the page

- **Collect** switch (app-wide, off by default). While on, stats add up for whichever workspace is in use. Off, nothing is recorded. Only time with the gyro driving something counts (a held ratchet, Gyro off or a covered pad don't).
- **Fire** and **Aim**: which input fires and which aims, per workspace. Default: **Left click** and **Right click**, meaning whatever input makes GP send that click. Any input pressed while collecting can be picked instead (a controller key, a trigger, a screen button). A new choice applies from then on. Reset leaves these.
- **Turning speed**: typical (median) and 1-in-10 (90th percentile) speed in °/s, and the share of time, for **Aiming** (Aim held), **Firing** (Fire held, which wins over Aim) and **Moving** (the rest). Speed is the yaw and pitch turn about the screen (the workspace's yaw axis), roll left out.
- **Turning speed** also has a graph: the share of time at each speed (1 to 2000 °/s, log scale), one line each for Aiming, Moving and Firing.
- **How it works**: a short note on the press curves below.
- **Your hand alone**: the hand's own shake level, aiming and not aiming, on the four channels (see Press curves), from moments with no press edge within 0.5 s (one every 0.7 s while not firing).
- **Fire while aiming**, **Hip fire** and **Aim** cards (Fire presses split by whether Aim was held at the press): once an input has 10 presses, its split *At the press* and at its *Worst* moment, per channel: total = hand + press (kick, varies); a *Kick* line saying how much of what the press adds is the same every press (a fixed kick, which a filter could take out, or random); and four graphs. *Details* opens the older short-window measures: *Off your line before* (80 ms before), *Kick after* (most thrown off in the 150 ms after), *On letting go*, *Sharpest jerk*, *Turning when pressed*, each also ↔ sideways and ↕ up/down.
- **What shakes your aim most**: every physical input with at least 10 presses, ordered by what its press adds to the cursor sideways at its worst moment (sideways misses). Each row: what it adds ↔ and ↕ in pixels, when, and how fixed its kick is. Tap a row for its full card. Clicks (outputs) are left out of this list.

## Press curves

Every press (down edge) of every input, and every hand-alone moment, adds a curve from 1 s before to 1 s after, in 101 steps of 20 ms, on four channels: **↔ gyro sideways** (yaw) and **↕ gyro up/down** (pitch) in degrees, and **↔ cursor x** and **↕ cursor y** in pixels, the cursor as GP sent it (every mouse move in the sent snapshots, after sens and curves, from any source). Each channel's value is how far the path (turn or cursor added up) was off its smooth path, the path averaged over 150 ms either side, so your own aiming doesn't count as shake. Per step the curve keeps the sum and the sum of squares over presses.

From those, per step: the **average** (with sign) is what the press does the same way every time, its *signature* or **kick**: the hand's own shake comes at random times and cancels out in it. The **spread** around the average is what differs between presses; beyond the hand-alone level (for the press's mix of aiming and not aiming) it is the press's **jitter**. Squares add up: total² ≈ hand² + kick² + jitter². The chance left in an average of n presses (spread²/n) is taken out of the kick. The *Worst* moment is the step where the press adds the most to the cursor (x and y together). There are no fixed windows: the graphs show where a press starts to differ from the hand alone and where it settles.

Graph: grey band ± the hand-alone level, line the average kick, shaded band ± the spread around it; the middle line is the press, dashed lines ±0.5 s.

A curve needs the gyro on and no gap over 50 ms in its 2.4 s span. Stats saved before curves have empty curves.
- **Reset stats** (at the bottom, red outline) asks "Reset stats for *workspace*?" before clearing anything.

## The line

Shake is measured against the turn you were already making: the average turn from 250 to 80 ms before the press is the line, and only what the phone did beyond that line counts. A steady track counts as no shake. (This is the Details measure; the press curves above use the smooth path instead.) A press whose window (250 ms before to 150 ms after) has the gyro off, or isn't fully in the kept samples, isn't counted.

## Inputs

- Controller and phone keys: every edge ([MappingEngine.setKey]).
- Triggers (LTRIGGER, RTRIGGER, GAS, BRAKE): pressed at half the pull, released under 0.4.
- Screen buttons: the first finger down and the last up ([MappingEngine] `press` / `release`), by the button's id.
- Left and Right click: the engine's sent mouse buttons, in each snapshot.
- The cursor: every snapshot's mouse move (dx, dy) when not zero.

## Input latency

Collecting adds no work to the input path beyond copying numbers:
- The gyro thread writes 4 numbers per sample into a ready-made 8192-sample ring (one writer, no lock, no allocation).
- Press edges take the time and a short lock to write 3 numbers into a 1024-entry ring.
- The sender's snapshot compares the mouse buttons with the last ones and, when the cursor moved, takes a short lock to write 3 numbers into an 8192-entry ring.
All the maths runs on its own background-priority thread ("GP stats") four times a second, 250 ms behind the newest sample; a press's curve is worked out once the 1.2 s after it is in. With Collect off the engine's tap and the gyro thread's recorder are null (one null check each, the arguments are not even worked out) and the Stats thread is not started until the page is opened; opening it without collecting only loads the stats once for the page, with no ticking.

## Storage

Each workspace's stats: `files/stats/<workspace id>.json` (histograms and press curves, not raw samples), saved every 15 s while changing, when switching workspace and when Collect goes off. The Collect switch and each workspace's Fire and Aim inputs are shared preferences (`gp_stats_collect`, `gp_stats_fire_<id>`, `gp_stats_aim_<id>`). Stats are not part of exports.

## Reading them off the phone

Every Diagnostics save on the phone ends with a `--- stats` section: per workspace its Fire and Aim, the names of its inputs and the stats JSON (histograms); the workspace in use is saved first. The save reaches the PC over GP's own link (Documents\GyroPhone\Debug), so agents read stats with `tools/gp-log.sh pc save`, no adb.
