# Regenerating the docs images

Windows (works with the PC locked, no glass: a dark gradient stands in for the acrylic):

```bash
just publish   # then, from PowerShell or via powershell.exe:
Tickfind.exe --demo --query glance --snapshot C:\path\windows-search.png         # also --edit for the first match's editor
```

`--snapshot` renders the window to a PNG at 2x and quits. Android: see emulator.md, then crop the phone shots.
