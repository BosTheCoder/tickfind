# Tickfind for Android

A search box that floats over whatever app you're in. Type, and your TickTick tasks appear, highest priority first. Tap one to read or edit it; long-press it to open it in TickTick.

<p align="center"><img src="images/android.png" width="760" alt="Search, everything by priority, a checklist in the editor"></p>

## Install

1. Download **`Tickfind.apk`** from the [latest release](https://github.com/BosTheCoder/tickfind/releases/latest) on your phone.
2. Open it. Android asks you to allow installs from your browser or file manager the first time. Allow it, then install.
3. Open **Tickfind** and tap ⚙ (or open **Tickfind settings**), then **Sign in to TickTick**. Your browser opens TickTick's consent page: tap **Allow**, and you're sent back to Tickfind, which reads your tasks (a few seconds).
4. Pick one or more ways to open it fast (below).

Android 8 or later.

## Opening it fast

| Way | Set it up |
| --- | --- |
| **Assistant gesture** (recommended) | Settings → **Use as the assistant app** → *Default digital assistant app* → Tickfind. Then long-press the power button, or swipe up from a bottom corner, depending on your phone. Checked on Android 15 (2026-10-16): the assist action opens the search box |
| **Floating bubble** | Settings → **Floating bubble over other apps**. Android asks for *Display over other apps* (switch Tickfind on, come back) and notifications (for the quiet "bubble is on" notification Android requires; tapping that also opens the search). Tap the bubble to search; drag it up, down or to the other edge; long-press it for settings. It comes back after a restart |
| **Quick Settings tile** | Swipe down twice, tap the pencil, drag **Search TickTick** into your tiles |
| **Side key / back tap** | Samsung: Settings → Advanced features → Side button → Double press → Open app → Tickfind. Pixel: Settings → System → Gestures → Quick Tap → Open app → Tickfind |
| **App icon** | Opens straight to the search box |

## Using it

| Do this | To |
| --- | --- |
| Type | Search. Every word has to match somewhere: title, notes, checklist, tags or list |
| Tap a match | Open the quick editor: title, notes, priority, checklist items, Complete, Save |
| Long-press a match, or **Open in TickTick** in the editor | Open it in TickTick |
| **Copy link** in the editor | Copy the task's TickTick link |
| The keyboard's go key | Open the top match |
| ⤢ | Show more matches, with the box filling the screen (tap again for fewer) |
| **Search all of TickTick for "…"** (last row) | TickTick's own search, completed tasks included |
| Tap outside the box, or Back | Close (from the editor, Back returns to the matches and asks first if you have unsaved changes) |

The search syntax (`#tag`, `~list`, `!high`, `due:today`, `is:note`, `-word`, `sort:…`) is the same as on Windows: see the [README](../README.md#search).

## Settings

| Setting | Options |
| --- | --- |
| **Sort by** | Priority (default), Best match, Due, Changed, Added |
| **Matches shown** | 4, 6 (default), 8, 10 in the compact box |
| **Default filter** | Added to every search, e.g. `-~gcal` |
| **Include archived lists** | Off by default |
| **Lists to leave out** | Tick any list to hide it from results |
| **Open in TickTick goes to** | The TickTick app (default) or the browser |
| **Check for updates** | See below |

## Open in TickTick

Tickfind sends TickTick the task's web link (`https://ticktick.com/webapp/#p/<list>/tasks/<task>`), which the TickTick app registers for, so it opens in the app. If TickTick isn't installed it opens in the browser. Not yet checked on a real phone: whether the app jumps to that exact task or just opens. If it only opens the app, switch the setting to the browser, which always shows the exact task.

## Updates

Settings → **Check for updates** compares your version with the latest GitHub release, downloads `Tickfind.apk`, checks its SHA-256 against GitHub's, and hands it to Android's installer, which asks you to confirm. The first time, Android asks you to allow installs from Tickfind.

## Privacy

The sign-in token and the task index live in Tickfind's private storage on the phone. **Sign out** deletes both. Tickfind only talks to TickTick and GitHub.
