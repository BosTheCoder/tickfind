# Development

Two small apps that share the same design and the same search rules: `windows/` (WPF, .NET 8, no NuGet packages) and `android/` (Kotlin, plain Views, androidx.core only). The design and the reasoning behind it are in [superpowers/specs/2026-10-16-tickfind-design.md](superpowers/specs/2026-10-16-tickfind-design.md).

## Build and run

**Windows** (.NET 8 SDK):

```powershell
dotnet run --project windows -- --demo     # made-up tasks, no TickTick needed
dotnet test tests                          # the search logic
dotnet publish windows -c Release -o out   # out\Tickfind.exe, framework-dependent single file
```

**Linux / WSL.** WPF can't run there, but it builds (`EnableWindowsTargeting`):

```bash
just test       # search tests
just publish    # then run out/Tickfind.exe from Windows
just deploy     # WSL: publish, copy to %USERPROFILE%\Apps\Tickfind (or $TICKFIND_DIR), relaunch
just demo       # WSL: a --demo copy from %TEMP%, alongside the real one
just android    # unit tests + signed release APK in android/dist/Tickfind.apk
```

`--demo` uses its own single-instance lock. `--snapshot out.png` (with `--query x` and `--edit`) renders the window to a PNG and quits, for docs and checks; it works with the screen locked.

## Layout

| File | What's in it |
| --- | --- |
| `windows/Search.cs` | The search: parsing filters, matching, scoring, the typo fallback, sorting, snippets. Pure, tested |
| `windows/TickTick.cs` | Sign-in (dynamic client registration + PKCE + loopback redirect), the MCP calls, sync, parsing |
| `windows/MainWindow.xaml(.cs)` | The box: matches, keys, the quick editor, the ⚙ menu |
| `windows/Settings.cs` | `settings.json` |
| `windows/Native.cs` | Global hotkey, acrylic backdrop, the work area under the mouse |
| `windows/Updater.cs` | Self-update from the latest GitHub release (same as Glance) |
| `windows/Demo.cs` | Made-up tasks for `--demo` |
| `tests/` | xUnit tests for `Search.cs`. Plain `net8.0`, so they run on Linux too |
| `android/…/Search.kt` | The same search rules as `Search.cs`, with the same tests in `SearchTest.kt` |
| `android/…/TickTick.kt` | Sign-in (redirect through the `io.github.bosthecoder.tickfind:` scheme), MCP calls, sync |
| `android/…/SearchActivity.kt` | The floating search box and the quick editor |
| `android/…/MainActivity.kt` | Settings, where sign-in returns, updates |
| `android/…/BubbleService.kt` | The optional floating bubble |
| `android/…/SearchTile.kt` | The Quick Settings tile and the boot receiver |
| `android/…/Repo.kt`, `Store.kt` | The index in memory and on disk, settings, the token |
| `tools/oauth-check.py` | Checks TickTick's sign-in without either app |
| `assets/icon.py` | Draws the icon (Windows `.ico`, Android launcher foreground) |

## How it works

- **TickTick.** Both apps call TickTick's hosted MCP server (`https://mcp.ticktick.com/`) with JSON-RPC `tools/call`: `list_projects`, `get_project_with_undone_tasks`, `get_task_by_id`, `update_task`, `complete_task`, `search_task`. A transient `unknown_exception` or 5xx gets one retry.
- **Sign-in.** `ticktick.com/.well-known/oauth-authorization-server` advertises dynamic registration and PKCE with public clients. Each sign-in registers a client for its exact redirect (`http://127.0.0.1:<port>/callback` on Windows, `io.github.bosthecoder.tickfind:/oauth` on Android), sends the browser to the consent page with `resource=https://mcp.ticktick.com/`, and exchanges the code. There's no refresh grant; tokens come with `expires_in` ≈ 180 days (checked 2026-10-16).
- **Index.** No changed-since feed exists, so a sync reads every open list, six in parallel (~50 calls for ~50 lists). A list that fails is skipped and named in the status line. The cache is `tasks.json` next to the exe / in app storage, so the box is useful the moment it opens.
- **Editing.** `update_task` with only the changed fields keeps everything else (dates, reminders, tags). Checklist `items` are sent as the whole list, with each item as TickTick returned it. TickTick doesn't reject a stale `etag`, so Save re-reads the task first and asks if it changed meanwhile.
- **Windows: Open in TickTick.** TickTick for Windows registers `ticktick://` and routes `v1/show`, `v1/search` and `v1/add_task` (read from its URI router); nothing opens a single task, so Tickfind uses `ticktick://v1/search?keyword=<title>`.
- **Android: Open in TickTick.** `https://ticktick.com/assetlinks.json` grants `com.ticktick.task` the `/webapp` links, so the task's web link with `setPackage("com.ticktick.task")` goes to the app, falling back to the browser.
- **Android assistant.** An `ACTION_ASSIST` activity is enough for Android to offer the app as the digital assistant; no voice service needed.

## Releasing

1. Bump `<Version>` in `windows/Tickfind.csproj`, and `versionName` **and** `versionCode` in `android/app/build.gradle.kts`.
2. Tag and push: `git tag v1.2.0 && git push --tags`. The [build workflow](../.github/workflows/build.yml) tests, builds `Tickfind.exe` and `Tickfind-standalone.exe`, and creates the release. Every push also runs the Android tests and a debug build.
3. Attach the APK, signed locally with a key that never leaves the machine (`~/.config/tickfind/`): `just android && gh release upload v1.2.0 android/dist/Tickfind.apk`.

The apps update from the latest release by asset name (`Tickfind.exe`, `Tickfind-standalone.exe`, `Tickfind.apk`), checking the SHA-256 digest GitHub reports, so keep those names.
