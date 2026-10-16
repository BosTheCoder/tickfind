# Tickfind: views

**Date:** 2026-10-16 · **Status:** approved 2026-10-16, shipped in v1.1.0

## Why

Opening Tickfind shows an empty box and, under it, every task by priority. Most of the time he opens it wanting one of a few lists he already knows: what's on today, what he just added, what he just touched. He asked for those as views that show the moment the box opens, that he can set up himself and put in his own order.

## What it is

A **view** is a name plus a search, written in the filter syntax Tickfind already has (`due:today`, `#tag`, `~list`, `!high`, `sort:created` …). Views show as a row of chips just under the search box.

- **Opening the box** (shortcut, bubble, tile, assistant gesture) starts empty, on the **first** view. So the order he sets decides what he sees first.
- **Typing searches everything**, as now. The chips hide while there's text; clearing the box brings back the view he was on. (Typing inside the view would make "find the Glance task" fail whenever Today is selected, and finding is the box's main job.)
- **Switching views:** click or tap a chip. Windows also: Tab / Shift+Tab while the box is empty, Ctrl+1 to Ctrl+9 to jump.
- The default filter and "lists to leave out" in settings still apply to every view.

## Starting views

Shipped as the defaults on first run, his to change:

| Name | Search |
|---|---|
| Today | `due:overdue due:today -~gcal \| #today` (his TickTick filter ✨Today, below) |
| Recently edited | `sort:modified` |
| Recently added | `sort:created` |

His ✨Today filter (read from TickTick's web API, 2026-10-16): due today or overdue and not in the 📅Calenders folder (Daily Schedule - GCAL, Events - GCAL), **or** tagged #today; sorted by priority. TickTick's MCP can't list filters, so the rules are copied, not synced.

A view with an empty search is "everything by the default sort", which is what the box shows today. He can add it back as an "All" view.

## Setting them up

One list editor, the same on both platforms: each row has a **name**, a **search**, **↑ ↓** to move it, and **✕** to delete it, with **Add view** at the bottom. Changes save as he goes.

- **Windows:** gear menu → *Views…* opens the editor in the Tickfind window (like the task editor; Esc goes back). The views also sit in `settings.json` as a `Views` list for hand edits.
- **Android:** a *Views* section on the settings screen.

## Search changes

- `due:` values combine with OR, so `due:overdue due:today` means either. Before, the second silently replaced the first.
- `|` on its own between words means **or**: `due:today -~gcal | #today` matches either side. The default filter applies to each side. A side left empty (`glance |` while typing) is ignored. This is what lets ✨Today, and his other OR-shaped filters (Downtime, Triage), be copied over.

Every other filter is unchanged. Both covered by tests on both platforms (`Search.cs`, `Search.kt`), kept in step.

## Not now

- Counts on the chips ("Today 4").
- "Save this search as a view" straight from the box.
- Reading his custom filters from TickTick: its MCP endpoint has no way to list them, so a view has to be written in Tickfind's syntax.

## Calls I made (check these)

1. Opening the box now always starts empty on the first view. Before, it reopened with the last search selected.
2. Typing searches all tasks, not just the selected view.
3. Added `|` (or) to the search so ✨Today copies over exactly. (He chose "custom filter" but gave no rules; read them from TickTick.)

## Release

v1.1.0 on both platforms, through the usual tag → Actions → `just android` + upload flow. Verified on the Windows demo snapshot and the Android emulator at phone width before release.
