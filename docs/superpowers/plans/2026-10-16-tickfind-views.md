# Tickfind views: implementation plan

> Executed natively in one session (he reviews specs, not plans). Steps use `- [ ]`.

**Goal:** Named saved searches shown as chips when the box opens, editable and reorderable on both platforms.
**Architecture:** A view is `{Name, Query}` in settings. The search gains `|` (or) and OR'd `due:`; the default filter moves into a `filter` argument applied to every side. The UIs render chips while the box is empty and add a list editor.
**Spec:** `docs/superpowers/specs/2026-10-16-tickfind-views-design.md`

## Global constraints

- Windows: WPF .NET 8, no NuGet packages. Android: Kotlin, plain Views, no new dependencies.
- `Search.cs` and `Search.kt` keep the same rules and the same tests.
- Default views, in order: `Today` = `due:overdue due:today -~gcal | #today`, `Recently edited` = `sort:modified`, `Recently added` = `sort:created`.
- Version 1.1.0 (Android versionCode 3).

## Review focus

- No views at all (he deletes them all): the box behaves as before, everything in the default sort, no chip row.
- A view whose search matches nothing: says so, rather than an empty box that looks broken.
- Typing `|` mid-search (`glance |`): still shows glance matches, not everything.
- Settings saved by v1.0.x have no `Views`: the defaults appear.
- The selected view index after deleting views: clamped, never out of range.

---

### Task 1: Search: `|` and OR'd `due:` (both platforms)

**Files:** `windows/Search.cs`, `tests/SearchTests.cs`, `android/.../Search.kt`, `android/.../SearchTest.kt`

**Produces:** `Search.Run(tasks, text, defaultSort, now, string filter = "")` (C#) and `Search.run(tasks, text, defaultSort, today, zone, filter = "")` (Kotlin). `Query.Due` becomes a set. `Parse(text)` skips `|` tokens (its terms are used for highlighting).

- [ ] Tests, both platforms:
  - `Due_values_combine_with_or`: overdue + today + tomorrow tasks; `due:overdue due:today` → the overdue and today ones.
  - `Bar_means_or_and_the_filter_applies_to_each_side`: tasks "Overdue chore" (due −1, list Inbox), "Daily standup" (due 0, list "Daily Schedule - GCAL"), "Tagged today" (no due, tag `today`), "Calendar tagged" (no due, tag `today`, list "Events - GCAL"), "Someday" (no due). `Run("due:overdue due:today -~gcal | #today", filter: "-~events")` → {"Overdue chore", "Tagged today"}; and `Run("someday |")` → {"Someday"}.
- [ ] Run them, see them fail.
- [ ] Implement: refactor `Parse`'s loop body into `Add(Query, raw)`; `Alternatives(text, filter)` splits tokens on `|`, drops empty sides (keeps one if all empty), adds filter tokens first; a task passes if any side passes, scored by its best side; sort = first side's `sort:` or the default.
- [ ] Callers pass the default filter as `filter` instead of prepending it.
- [ ] `just test` and the Kotlin unit tests pass. Delete the `|` split and see the new test fail.
- [ ] Commit.

### Task 2: Windows views

**Files:** `windows/Settings.cs`, `windows/MainWindow.xaml`, `windows/MainWindow.xaml.cs`, `windows/Demo.cs`

- [ ] `Settings.Views: List<View>` (class `View { Name, Query }`) defaulting to the three views.
- [ ] `ViewBar` (WrapPanel of chips) under the box; shown only when the box is empty, signed in, not tips/server, and there are views. Chip click selects it. Tab/Shift+Tab cycle while empty; Ctrl+1–9 clears the box and jumps. Footer keys mention Tab.
- [ ] Empty box + views → results of the selected view's query; no "Search all of TickTick" row; no matches → "Nothing in <name>" row.
- [ ] `Summon` on the search page clears the box and selects view 0.
- [ ] `ViewsPage`: rows of name + search fields, ↑ ↓ ✕, Add view; saves on every change; Esc/back returns. Gear menu → "Views…".
- [ ] Tips: add `a | b` and `due:` combining.
- [ ] Demo: one task tagged `today`, a mix of created ages, so the chips show something.
- [ ] Publish, snapshot `--demo` (empty box with chips, and the Views page) and look at them.
- [ ] Commit.

### Task 3: Android views

**Files:** `Store.kt`, `SearchActivity.kt`, `MainActivity.kt`, `Demo.kt`

- [ ] `Store.views: List<View>` as a JSON string pref, defaults when absent; `data class View(val name: String, val query: String)`.
- [ ] Chip row (HorizontalScrollView) under the box, same rules as Windows; tap selects. `summon()` clears the box and picks view 0 (but the `query` extra still works for the demo).
- [ ] Settings: "Views" section with name/search fields, ↑ ↓ ✕, Add view.
- [ ] `just android`; check on the emulator with demo data at phone width (search box with chips, settings section).
- [ ] Commit.

### Task 4: Docs and release

- [ ] README: views section, `|` and `due:` in the syntax table, `Views` in the settings table. docs/android.md: Views row.
- [ ] Bump to 1.1.0, tag, push; `just android` + `gh release upload v1.1.0 android/dist/Tickfind.apk`; `just deploy` to the PC.
- [ ] Task folder index.md and the TickTick task log.
