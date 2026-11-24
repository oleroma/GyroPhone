# Stats

The pad's **Stats** capsule (top row, right after Defaults) opens a page about how you move the phone in play, for the **workspace in use**. Code: `core/engine/AimStats.kt` (recording and the maths), `app/runtime/AimStats.kt` (its thread and files), `app/ui/StatsScreen.kt` (the page).

## On the page

- **Collect** switch (app-wide, off by default). While on, stats add up for whichever workspace is in use. Off, nothing is recorded. Only time with the gyro driving something counts (a held ratchet, Gyro off or a covered pad don't).
- **Fire** and **Aim**: which input fires and which aims, per workspace. Default: **Left click** and **Right click**, meaning whatever input makes GP send that click. Any input pressed while collecting can be picked instead (a controller key, a trigger, a screen button). A new choice applies from then on. Reset leaves these.
- **Turning speed**: typical (median) and 1-in-10 (90th percentile) speed in °/s, and the share of time, for **Aiming** (Aim held), **Firing** (Fire held, which wins over Aim) and **Moving** (the rest). Speed is the yaw and pitch turn about the screen (the workspace's yaw axis), roll left out.
- **Fire** and **Aim** cards, for that input's presses: *Off your line before* (80 ms before the press counts), *Kick after* (the most it was thrown off in the 150 ms after), *On letting go* (the same after release), *Sharpest jerk* (°/s off the line, 80 ms before to 150 ms after), *Turning when pressed*.
- **What shakes your aim most**: every physical input with at least 5 presses, ordered by typical *before* + *after*. Clicks (outputs) are left out of this list.
- **Reset stats** (at the bottom, red outline) asks "Reset stats for *workspace*?" before clearing anything.

## The line

Shake is measured against the turn you were already making: the average turn from 250 to 80 ms before the press is the line, and only what the phone did beyond that line counts. A steady track counts as no shake. A press whose window (250 ms before to 150 ms after) has the gyro off, or isn't fully in the kept samples, isn't counted.

## Inputs

- Controller and phone keys: every edge ([MappingEngine.setKey]).
- Triggers (LTRIGGER, RTRIGGER, GAS, BRAKE): pressed at half the pull, released under 0.4.
- Screen buttons: the first finger down and the last up ([MappingEngine] `press` / `release`), by the button's id.
- Left and Right click: the engine's sent mouse buttons, in each snapshot.

## Input latency

Collecting adds no work to the input path beyond copying numbers:
- The gyro thread writes 4 numbers per sample into a ready-made 8192-sample ring (one writer, no lock, no allocation).
- Press edges take the time and a short lock to write 3 numbers into a 1024-entry ring.
- The sender's snapshot compares the mouse buttons with the last ones.
All the maths runs on its own background-priority thread ("GP stats") four times a second, 250 ms behind the newest sample, so a press has its after-window in. With Collect off the engine's tap is null and nothing runs.

## Storage

Each workspace's stats: `files/stats/<workspace id>.json` (histograms, not raw samples), saved every 15 s while changing, when switching workspace and when Collect goes off. The Collect switch and each workspace's Fire and Aim inputs are shared preferences (`gp_stats_collect`, `gp_stats_fire_<id>`, `gp_stats_aim_<id>`). Stats are not part of exports.

## Reading them off the phone

Every Diagnostics save on the phone ends with a `--- stats` section: per workspace its Fire and Aim, the names of its inputs and the stats JSON (histograms); the workspace in use is saved first. The save reaches the PC over GP's own link (Documents\GyroPhone\Debug), so agents read stats with `tools/gp-log.sh pc save`, no adb.
