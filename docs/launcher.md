# Launcher: Projectivy

The stock Android TV launcher on this TV shows a full-width *Sponsored* banner and a sponsored row
that can't be turned off. The replacement used here is
[Projectivy Launcher](https://play.google.com/store/apps/details?id=com.spocky.projengmenu)
(`com.spocky.projengmenu`, tested with v4.72, from the Play Store), with the stock launcher left
**enabled**.

## Why Projectivy

Measured on this TV (Mali-470 GPU, OpenGL ES 2.0 only; 1.8 GB RAM, flagged low-RAM) by scrolling
with D-pad presses and reading frame times:

| Launcher | Built with | Typical frame while scrolling | Notes |
|---|---|---|---|
| Stock (`com.google.android.tvlauncher`) | Native views | 6–8 ms | Smooth, but sponsored content |
| **Projectivy** | Native views | **8–11 ms** | ~4% late frames; ~62 MB RAM; idle draws nothing |
| ARC (`com.omeda.arc`) | Flutter | ~67 ms (~15 fps) | Too heavy for this GPU |

Avoid Flutter-based launchers (ARC, FLauncher) on this model: they redraw the whole 1080p surface
every frame, which the Mali-470 can't sustain.

Projectivy also:
- shows the **Watch Next** row (Jellyfin's *Next Up* appears there);
- can show other apps' channels as rows. Stremio puts *Continue watching* in its own channel
  (`continue_watching_channel`), not in Watch Next, so add that channel in Projectivy to see it;
- has built-in shortcuts for switching inputs (HDMI 1/2/3, AV).

## Why the stock launcher stays enabled

The picker that appears when you press **Input** on the remote is part of the stock launcher
(`com.google.android.tvlauncher/.inputs.InputsPanelActivity`, the only app that handles
`com.android.tv.action.VIEW_INPUTS`). Disabling the stock launcher breaks input switching from the
remote. Projectivy takes over Home through its accessibility service instead.

Also: with the stock launcher enabled, Android ignores other Home apps (it has a higher system
priority), so `cmd package set-home-activity` and launcher managers such as Wolf Launcher Manager
can't make another launcher the default. Projectivy's accessibility override is what works.

## Setup

1. Install Projectivy Launcher from the Play Store and open it.
2. Turn on its accessibility service when it asks (or Settings → Accessibility → Projectivy).
   This is what sends Home to Projectivy.
3. In Projectivy: Settings → System → **Accessibility service**:
   - **Enable button remapping: off.** With it on, Projectivy filters every key press, and on this
     TV that stops Android auto-repeating held keys, so holding volume moves it one step only.
     Remap buttons with [TV Keys](../README.md) instead.
   - **Volume buttons fix: off** (not needed once remapping is off; it was unreliable anyway).
   - Use alternate config: off. Show reminder: on.
4. Add the rows you want (Watch Next, Stremio's channel, etc.) in Projectivy's settings.

With button remapping off, Projectivy no longer catches the Home *key*; it replaces the stock
launcher as soon as it opens. You may see the stock home screen for a fraction of a second after
pressing Home.

If Projectivy's accessibility service gets turned off, Home falls back to the stock launcher (with
its sponsored banner). Over ADB, append services to the enabled list rather than replacing it:
Projectivy re-registers itself in its own format and can drop entries written alongside a
replacement (see the README's install steps).

## Undo

Back to the stock launcher, keeping Projectivy installed: turn off Projectivy's accessibility
service (Settings → Accessibility → Projectivy → off). Home goes to the stock launcher again.

Remove it completely:

```
adb uninstall com.spocky.projengmenu
```

Nothing else was changed for the launcher: the stock launcher was never disabled, so there is
nothing to re-enable.
