# Debloating the Hisense HiSmartTV A4

What was turned off on this TV (Android TV 9, firmware PTMR.190127.037), why, and how to undo it.
Based on the [cleanyourtv](https://github.com/Gathin23/cleanyourtv) list for this exact model
(`examples/hisense-hismarttv-a4/debloat.txt`), with the changes noted below.

Everything is **disabled, not uninstalled**: `pm disable-user` keeps the app on the system
partition, so every step can be reversed with `pm enable`, and a factory reset restores it all.

## How to apply

```
adb connect <tv-ip>:5555
adb shell pm disable-user --user 0 <package>
```

- Do one group at a time and reboot after each (`adb reboot`), checking that the TV starts
  normally, HDMI/AV/tuner inputs are still there, and Home works.
- **Wait ~15 seconds after a change before switching the TV off at the wall.** Android saves package
  state a few seconds later; a hard power cut right after a change lost it once on this TV.
- Check what is disabled: `adb shell pm list packages -d`. On this TV that also lists
  `com.google.android.apps.inputmethod.zhuyin` (a Chinese keyboard), which comes disabled from the
  factory and isn't part of this list.

## What was disabled

### 1. Telemetry

| Package | What it is |
|---|---|
| `com.hisense.app.logreport` | Hisense log reporting service |
| `com.google.android.feedback` | Google feedback |
| `com.google.android.tv.bugreportsender` | Google bug report sender |

### 2. Ads, recommendations, store demo, silent installs

| Package | What it is |
|---|---|
| `com.google.android.leanbacklauncher.recommendations` | Old notification-based recommendations (unused by this firmware's launcher) |
| `com.google.android.leanbacklauncher` | Old launcher, not a Home candidate on this firmware |
| `com.hisense.storemode` | Retail store mode |
| `fusion.android.tv.demo` | Retail display demo |
| `com.mstar.android.tv.disclaimercustomization` | Store disclaimer screen |
| `android.autoinstalls.config.hisense.xiaoyushan` | Silently installs preloaded apps |

**Kept, unlike cleanyourtv's list:** `com.google.android.tvrecommendations` ("Android TV Core
Services"). It handles apps' requests to add their rows to the home screen and feeds Play Next;
disabling it risks Jellyfin's *Next Up* row.

### 3. Preloaded apps

| Package | What it is |
|---|---|
| `com.netflix.ninja` | Netflix |
| `com.amazon.amazonvideo.livingroom` | Prime Video |
| `com.seraphic.browser` | Hisense browser |
| `com.google.android.music` | Play Music (discontinued) |
| `com.google.android.videos` | Play Movies |
| `com.google.android.play.games` | Play Games |
| `com.google.android.apps.nbu.smartconnect.tv` | "Smart Connect" / Data Saver: meters and caps data through a local VPN, which would compete with a VPN-based ad blocker such as Blokada |

### 4. Firmware updates

| Package | What it is |
|---|---|
| `com.hisense.tv.ota` | OTA update client |
| `com.hisense.remoteupgrade` | Remote upgrade service |

Re-enable both before installing a firmware update. Note that an update could also remove the key
logging [TV Keys](../README.md) depends on.

### 5. Netflix observer (needed for TV Keys)

| Package | What it is |
|---|---|
| `com.mstar.netflixobserver` | Handles the Netflix button and "wake straight into an app" from standby |

Disabled so the firmware stops opening official YouTube when the TV is woken with the YouTube
button; TV Keys takes over both jobs. It is a persistent system app, so it keeps running until the
next reboot after disabling, and the firmware still starts its process at boot, but its wake
handling no longer fires. Without TV Keys, leave it enabled.

## Deliberately kept

| Package | Why |
|---|---|
| `com.google.android.tvlauncher` | Stock launcher. Owns the Input-button picker (`InputsPanelActivity`, the only handler of `com.android.tv.action.VIEW_INPUTS`); disabling it breaks HDMI switching from the remote. See [launcher.md](launcher.md). |
| `com.google.android.tvrecommendations` | Home-screen rows and Play Next (see group 2) |
| `com.mediatek.*` | Live TV app, HDMI, hotkey dispatcher, factory functions |
| `com.hisense.tv.hitvinput` | The HDMI, AV and tuner inputs themselves |
| `com.hisense.kpad` | Remote's menu key |
| `com.hisense.tv.customerlauncher`, `com.hisense.uifac`, `com.hisense.audio_update_service` | Firmware services cleanyourtv also keeps |
| `com.hisense.tv.smartsense` | Left alone because of a recovery warning on another Hisense list |
| `com.google.android.apps.mediashell`, `com.google.android.katniss`, `com.google.android.speech.pumpkin` | Chromecast and Assistant |
| `com.mediatek.androidbox` | Miracast receiver ("Screen Sharing") |
| `com.google.android.youtube.tv` | Official YouTube, kept for casting from phones |

## Undo

One package:

```
adb shell pm enable <package>
```

Everything from this page:

```
for p in com.hisense.app.logreport com.google.android.feedback com.google.android.tv.bugreportsender \
  com.google.android.leanbacklauncher.recommendations com.google.android.leanbacklauncher \
  com.hisense.storemode fusion.android.tv.demo com.mstar.android.tv.disclaimercustomization \
  android.autoinstalls.config.hisense.xiaoyushan \
  com.netflix.ninja com.amazon.amazonvideo.livingroom com.seraphic.browser com.google.android.music \
  com.google.android.videos com.google.android.play.games com.google.android.apps.nbu.smartconnect.tv \
  com.hisense.tv.ota com.hisense.remoteupgrade com.mstar.netflixobserver; do
  adb shell pm enable "$p"
done
adb reboot
```

(PowerShell: `foreach ($p in "pkg1","pkg2") { adb shell pm enable $p }`.)

A factory reset also re-enables everything, but wipes the TV's apps and settings.
