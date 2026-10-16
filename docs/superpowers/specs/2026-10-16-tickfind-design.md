# Tickfind: design

**Date:** 2026-10-16 · **Status:** building (the request came as a `/goal`, so the calls below are mine, not agreed)

## Why

When a thought turns out to be about something already in TickTick, getting to it on Android takes six steps (home screen, TickTick, bottom row, ellipsis, Search, then TickTick's weak search). TickTick's search can't sort by priority and ranks badly: on 2026-10-16 its server search for "glance" put a journaling link and a company-filing plan above the actual Glance task.

Tickfind is a Glance-style companion: one shortcut, a floating search box, matches as you type, and a small editor for the add-a-line or just-read cases. "Open in TickTick" stays one tap away.

## Goals

- **One action to a search box** on both platforms. Windows: a global shortcut (default `Ctrl+Alt+F`, next to his `Ctrl+Alt+T` quick add). Android: a floating bubble, a Quick Settings tile, the launcher icon, and the phone's assistant gesture.
- **Instant, good search** over every open task and note, in every list: title, content, checklist items, tags and list name; accent- and case-blind; every word must match; typo-tolerant when nothing matches exactly.
- **Sort by priority by default**, switchable (best match, due, modified, created), plus inline filters (`#tag`, `~list`, `!high`, `due:today`, `is:note`, `-word`, `sort:…`) and a default filter in settings.
- **Compact by default, expandable**: a few matches, or a bigger view with more.
- **Quick edit**: tap a match to read it and change title, notes, checklist items or priority, complete it, or open it in TickTick.
- **Installable and self-updating** from GitHub releases, like Glance: `Tickfind.exe`, `Tickfind-standalone.exe`, `Tickfind.apk`.

## Non-goals (for now)

- Semantic search. The index and ranking are kept in one pure function per platform so embeddings can slot in later (his TickTick task "Project - Semantic search over ticktick").
- Creating tasks (TickTick's own quick add does that), dates, reminders, moving lists.
- Indexing completed tasks. A "Search all of TickTick" row falls back to TickTick's own server search, which covers them.

## Anti-goals

- Losing text. An edit must never overwrite a change made in TickTick in the meantime without asking.
- Needing a developer account, client secret or any setup beyond signing in.

## Decisions

- **Name and repo:** Tickfind, `BosTheCoder/tickfind`, public, MIT. Same shape as Glance: `windows/` (WPF, .NET 8, no NuGet packages, single-file exe) and `android/` (Kotlin, plain Views, minSdk 26, target 35).
- **TickTick access:** TickTick's hosted MCP endpoint (`https://mcp.ticktick.com/`, JSON-RPC `tools/call`), the same one his triage job and Claude Code use. Its OAuth server supports dynamic client registration with PKCE and public clients (checked 2026-10-16: it accepted both a `http://127.0.0.1:<port>/callback` and an `io.github.bosthecoder.tickfind:/oauth` redirect). Each sign-in registers a client and runs the authorization-code flow. There's no refresh grant, so when the token expires the app asks to sign in again.
- **Index:** list projects, then fetch each open list's undone tasks (`get_project_with_undone_tasks`) in parallel, cache to disk, and search locally. Re-sync when opened if the cache is older than 5 minutes, and in the background. Archived lists are off by default.
- **Editing:** `update_task` with only the changed fields. Checked 2026-10-16: partial updates keep dates, reminders and tags, and checklist `items` are replaced as a whole list (ids kept). TickTick ignores a stale `etag`, so before saving the app re-reads the task and, if its etag changed since the editor opened, asks before overwriting.
- **Open in TickTick:** Windows TickTick has no open-this-task link (read from its decompiled URI router, 2026-10-16), so "Open in TickTick" runs `ticktick://v1/search?keyword=<title>`, which opens the desktop app's search on that title; the web link (`https://ticktick.com/webapp/#p/<list>/tasks/<id>`) is the fallback and a setting. Android: see `docs/android.md`.
- **Sort:** priority (high first), then match quality, then most recently modified. With an empty query the list is the default sort over everything the filters allow.
- **Settings:** Windows keeps `settings.json` next to the exe (gear menu for the common ones); Android has a settings screen.

## Left to the builder

Layout details, colours, the bubble's look, exact scores in the ranking.

## Build order

1. Repo, spec, CI workflow, justfile.
2. Search logic with tests on both platforms (the one piece of real logic).
3. Windows app: popup, hotkey, sign-in, sync, editor, updater, demo mode.
4. Android app: search activity, sign-in, sync, editor, bubble, tile, assistant entry, updater, demo mode.
5. Docs and screenshots, release v1.0.0, deploy to his PC, check the APK on the emulator.
