# hisense-tv-keys

A tiny Android TV accessibility service (`local.tvkeys`, "TV Keys") that remaps the app buttons on a
Hisense remote without breaking volume hold.

Built and tested on a **Hisense HiSmartTV A4** (Android TV 9, MStar/MediaTek m7322, firmware
PTMR.190127.037). Other Hisense models may use different keycodes.

| Remote button | Opens | Also from deep standby |
|---|---|---|
| YouTube | SmartTube (`org.smarttube.stable`) | yes |
| Netflix | Stremio (`com.stremio.one`) | yes |
| Prime Video | Jellyfin (`org.jellyfin.androidtv`) | no: the standby chip doesn't wake on it |

## Why it exists

Normal button remappers (Key Mapper, Button Mapper, Projectivy's remapping) are accessibility
services that filter key events. On this TV, while *any* such service filters keys, Android stops
auto-repeating a held volume key, so holding volume up or down moves it a single step. Volume keys
also never reach accessibility services here, so a remapper can't repeat them itself.

TV Keys works around both:

- **App buttons** — it swallows the Hisense vendor keycodes (293 YouTube, 265 Netflix, 295 Prime
  Video) and launches the chosen app instead.
- **Volume hold** — the firmware logs every key press and release from WindowManager
  (`interceptKeyTi keyCode=24/25 down=true|false`). TV Keys follows that log and adds the missing
  volume steps while a key is held, at the TV's native rate (first repeat ~500 ms, then ~8.5
  steps/s), with a 10 s safety stop.
- **Waking from deep standby** — the standby chip wakes the TV as a plain power-on, so Android
  never sees the app key. The kernel logs the raw IR code of the wake button
  (`PmEvent:HeaderCode:0xbf,HWcode:0x..`: `0xd` power, `0xaa` YouTube, `0x2d` Netflix), and TV Keys
  opens the right app from that.
- **Guard** — if the firmware's default app (e.g. official YouTube) still comes to the front within
  5 s of a remapped press, TV Keys brings the chosen app back.

## Download

Every push to `main` is built and signed by GitHub Actions (`.github/workflows/build.yml`). A new
`versionName` in `AndroidManifest.xml` publishes a release with `tvkeys.apk` attached; other builds
are available as the workflow's `tvkeys-apk` artifact.

## Build

Needs a JDK (17 tested) and the Android SDK with `platforms;android-28` and
`build-tools;34.0.0`.

**Linux:** `bash build.sh` (uses `ANDROID_SDK_ROOT`, plus `KEYSTORE` / `KEYSTORE_PASSWORD` to
sign with an existing key).

**Windows:** `build.ps1`, with the JDK and SDK laid out as:

```
<root>\jdk17\
<root>\sdk\platforms\android-28\
<root>\sdk\build-tools\34.0.0\
<root>\tvkeys\        <- this repo
```

Then run `build.ps1` (PowerShell). It builds without Gradle (aapt2 → javac → d8 → zipalign →
apksigner) and creates a local signing key `tvkeys.keystore` on first run. Keep that file to install
later builds as updates; it is git-ignored.

## Install

```
adb install -r tvkeys.apk
adb shell pm grant local.tvkeys android.permission.READ_LOGS
```

Then turn on **TV Keys** in Settings → Accessibility. If it was already running when you granted
`READ_LOGS`, restart it (`adb shell am force-stop local.tvkeys` and re-enable it), since the log
group is only applied when the process starts.

If you use Projectivy Launcher, turn off its **Enable button remapping** option, otherwise
Projectivy filters keys too and volume hold breaks again.

## Changing the buttons

Edit `APP_KEYS` (and `WAKE_CODES` for standby wake) at the top of
`src/local/tvkeys/KeyService.java`. To find a button's keycode, run `adb logcat | findstr interceptKeyTq`
and press it.

## Limitations

- Relies on Hisense's debug key logging; a firmware update that removes it would break volume hold
  and standby wake (the app buttons keep working).
- The Prime Video button can't wake the TV from deep standby.
- `READ_LOGS` lets the app read the system log. TV Keys only parses key and wake lines and has no
  internet permission.

## License

MIT
