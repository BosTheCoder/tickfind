# Trying the Android app on the emulator

Same AVD as Glance (`glance35`, Android 35, Google APIs, needs KVM; see glance's docs/claude-notes/emulator.md).

```bash
/usr/bin/sg kvm -c "$HOME/Android/Sdk/emulator/emulator -avd glance35 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect" &
A=~/Android/Sdk/platform-tools/adb
$A install -r android/dist/Tickfind.apk
$A shell input keyevent 82; $A shell input swipe 540 2000 540 600 200     # past the lock screen
$A shell am start -n io.github.bosthecoder.tickfind/.SearchActivity --ez demo true --es query glance   # demo data, no TickTick
$A exec-out screencap -p > /tmp/shot.png
```

- `--ez edit true` opens the first match in the editor. Extras only apply on a fresh start: `am force-stop` first (singleTask).
- Bubble: `appops set io.github.bosthecoder.tickfind SYSTEM_ALERT_WINDOW allow`, `pm grant … POST_NOTIFICATIONS`, then the switch in settings.
- Assistant: `cmd role add-role-holder android.app.role.ASSISTANT io.github.bosthecoder.tickfind`, then `input keyevent 219` opens the search box (checked 2026-10-16).
- Sign-in can't finish on the emulator (its Chrome isn't signed in to TickTick). A fake redirect checks the routing: `am start -a android.intent.action.VIEW -d "'io.github.bosthecoder.tickfind:/oauth?code=x&state=y'"` shows "state mismatch".
