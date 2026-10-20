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

The rest of the setup on this TV is documented in `docs/`:
[debloat.md](docs/debloat.md) (what was disabled and how to undo it) and
[launcher.md](docs/launcher.md) (Projectivy, and why the stock launcher stays enabled).

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

## Requirements

- The target apps installed: SmartTube, Stremio, Jellyfin (or change them, see below).
- ADB over the network: Settings → Device Preferences → About → press *Build* 7 times, then
  Developer options → **USB debugging** on. On this model that also opens network ADB on port 5555.
  The first `adb connect <tv-ip>:5555` shows an *Allow USB debugging?* prompt on the TV; tick
  *Always allow*.

## Install

1. Download the latest signed APK from
   [Releases](https://github.com/atharva-lipare/hisense-tv-keys/releases) (or
   `gh release download --repo atharva-lipare/hisense-tv-keys --pattern tvkeys.apk`), then:

   ```
   adb connect <tv-ip>:5555
   adb install -r tvkeys.apk
   adb shell pm grant local.tvkeys android.permission.READ_LOGS
   ```

2. Turn on **TV Keys** in Settings → Accessibility. Over ADB, append it to whatever is already
   enabled rather than replacing the list (Projectivy re-registers itself in its own format and can
   drop entries written alongside a replacement):

   ```
   adb shell settings get secure enabled_accessibility_services
   adb shell settings put secure enabled_accessibility_services "<current value>:local.tvkeys/local.tvkeys.KeyService"
   ```

   Check after a few seconds that the setting still contains `local.tvkeys`.

3. If TV Keys was already running when `READ_LOGS` was granted, restart it so the process picks
   up the log group (`adb shell am force-stop local.tvkeys`; Android rebinds the service). A
   process that can read the log shows group `1007` in `/proc/<pid>/status`.

4. Make sure nothing else filters keys, or volume hold breaks again:
   - **Projectivy Launcher:** turn off *Enable button remapping* (its accessibility service can stay
     on for the launcher override).
   - **Key Mapper, Button Mapper and similar:** turn their accessibility service off or uninstall them.

5. Stop the firmware opening official YouTube when the TV is woken with the YouTube button:

   ```
   adb shell pm disable-user --user 0 com.mstar.netflixobserver
   adb reboot
   ```

   This MStar service normally handles the Netflix button and "wake straight into an app"; TV Keys
   takes over both. It is persistent, so the reboot is what actually stops it. Undo with
   `pm enable com.mstar.netflixobserver`.

To uninstall: `adb uninstall local.tvkeys` (and re-enable `com.mstar.netflixobserver` if you
disabled it).

## Releases and CI

Every push to `main` that touches code is built and signed by GitHub Actions
(`.github/workflows/build.yml`) and uploaded as the run's `tvkeys-apk` artifact. To publish a
release, bump **both** `android:versionCode` (Android only installs an update with a higher one)
and `android:versionName` in `AndroidManifest.xml`; the workflow creates release `v<versionName>`.

The workflow signs with repository secrets, and fails rather than sign with a throwaway key:

| Secret | Value |
|---|---|
| `KEYSTORE_B64` | the keystore file, base64-encoded |
| `KEYSTORE_PASSWORD` | its password (`tvkeys` if created by the build scripts) |

Set them with `gh secret set NAME --body "<value>"`. Piping the value in from PowerShell adds
characters that break `base64 -d`. Forks need their own keystore and secrets; their APKs won't
install over one signed with a different key without uninstalling first.

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

Both scripts build without Gradle (aapt2 → javac → d8 → zipalign → apksigner) and create a local
signing key `tvkeys.keystore` on first run if none is given. Keep it to install later builds as
updates; it is git-ignored and must never be committed.

## Changing the buttons

Edit `APP_KEYS` (and `WAKE_CODES` for standby wake) at the top of
`src/local/tvkeys/KeyService.java`.

- **Keycode of a button:** `adb logcat | grep interceptKeyTq` (Windows: `findstr`), then press it.
- **Standby wake code:** turn the TV off, wait ~20 s for deep standby, wake it with the button, then
  `adb logcat -d | grep PmEvent`. A button that doesn't wake the TV can't be used from standby.

## Testing

`input keyevent` bypasses accessibility services, so it can't test remapped keys. Hardware-level
events through the IR receiver can; for example a 2 s volume-down hold:

```
adb shell "sendevent /dev/input/event2 1 114 1; sendevent /dev/input/event2 0 0 0; sleep 2; sendevent /dev/input/event2 1 114 0; sendevent /dev/input/event2 0 0 0"
```

(`/dev/input/event2` is the IR receiver on this model; check with `adb shell getevent -pl`.) With
TV Keys active, `adb logcat | grep interceptKeyTi` shows only `repeatCount=0` while
`setStreamVolumeIndex` keeps stepping: the steps come from TV Keys, not Android.

## Limitations

- Relies on Hisense's debug key logging; a firmware update that removes it would break volume hold
  and standby wake (the app buttons keep working).
- The Prime Video button can't wake the TV from deep standby.
- `READ_LOGS` lets the app read the system log. TV Keys only parses key and wake lines and has no
  internet permission.

## License

MIT
