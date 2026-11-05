# Build and release

How GyroPhone is built, tested, installed and published. The agent workflow around it (branches, scripts, results) is in the shared rules at the top of `AGENTS.md`; this page is what is specific to this repo.

## Parts

The repo has two **parts** (`.agents/hooks/parts`), built only when a branch changed them since it left `main`:

| Part | Sources | Build file |
|---|---|---|
| `apk` | `app/`, `core/`, the Gradle files | `app/build/outputs/apk/debug/gyrophone-<version>.apk` (debug build, arm64-v8a) |
| `companion` | `companion/`, plus the protocol test vectors in `core/` | `companion/out/GyroPhoneCompanion.exe` (self-contained single-file .NET 8, win-x64) |

Docs, `media/` and `tools/` belong to no part. Any other change (hooks, workflows) builds both, and so does a branch that changed no part. The companion builds only on Windows; an unchanged companion exe is reused with its older version.

## Versions

`<version>` is **`0.1.<build number>`**; Android's `versionCode` is the number. `.agents/build-number` claims the next number by pushing a `build/<n>` tag (never by hand), starting at **400** (`.agents/hooks/first-build-number`). The companion exe is stamped `<version>-<commit>`. Local Gradle builds without a number are `0.1.0-dev`.

App: `applicationId com.oleroma.gyrophone`, minSdk 35, target/compile SDK 36, signed with `keystore/debug.keystore` (always the same key, so updates install in place). Native code: `app/src/main/cpp/gyrobuffer.c` (gralloc buffer reader).

## Hooks

- **quick-test**: before the build. `apk`: the Kotlin `core` unit tests (including the shared protocol and gyro-math vectors). `companion`: the companion's tests (on Linux only the cross-platform projects).
- **build**: `./gradlew :app:assembleDebug`; `dotnet publish companion/src/GyroPhone.Tray -c Release -r win-x64 --self-contained -p:PublishSingleFile=true` (branch builds skip ReadyToRun). Prints `build-file: <path> <version>`.
- **full-check**: after the build. Android lint for the app; the companion has none.
- **install**: on the PC after the APK is on the phone: installs the companion and swaps the key files ([Install and update](pc-install-update.md)).

## Where builds run

- **PC** (the normal way, through Remote Control): in `D:\dev\gyrophone`, `build/src` is a reused worktree checked out at the branch; `bash build/src/.agents/agent-build pc` runs the hooks, keeps the files in `build/builds` (last 4 builds plus each part's newest), installs the APK with `adb install -r` when the phone is on adb, runs the install hook, and posts the result on the PR. When the phone wasn't reachable it publishes the APK as a pre-release on `build/<n>` so Obtainium on the phone can install it.
- **Fallback** (`.github/workflows/fallback.yml`, run by hand on a branch only when the PC can't be reached): the same hooks, the APK on Ubuntu and the companion on Windows runners, uploads each build file, marks the commit checked when every job passed, and publishes the APK as a pre-release for Obtainium.
- **Cache** (`.github/workflows/cache.yml`): after each push to `main`, compiles only the changed parts with the same caches, so a branch's first fallback build is warm. No tests, no status.

## Publishing

Only when Olex asks: `bash .agents/agent-publish [<n>]` on the PC publishes build `<n>` (default `main`'s newest) as a GitHub **pre-release** on its `build/<n>` tag, with every part's current file (a part the build didn't remake comes from the build it was last made in). Its full checks must have passed. Obtainium on the phone reads these pre-releases.

## Key files

`.agents/hooks/parts`, `quick-test`, `build`, `full-check`, `install`, `first-build-number`, `.agents/build-number`, `.agents/agent-publish`, `.github/workflows/fallback.yml`, `cache.yml`, `app/build.gradle.kts`, `companion/Directory.Build.props`, `companion/src/GyroPhone.Tray/GyroPhone.Tray.csproj`.
