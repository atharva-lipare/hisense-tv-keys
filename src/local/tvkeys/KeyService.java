package local.tvkeys;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Swallows Hisense app keys and opens a chosen app instead: YouTube (vendor keycode 293) opens
 * SmartTube, Netflix (265) opens Stremio, Prime Video (295) opens Jellyfin.
 *
 * Filtering keys has a side effect on this TV: while any accessibility service filters keys,
 * Android stops auto-repeating a held volume key, and volume keys never reach the service, so it
 * can't repeat them itself. The firmware does log every key press and release from WindowManager,
 * though, so this service follows that log (needs READ_LOGS, granted once over adb) and adds the
 * missing volume steps while a volume key is held.
 */
public class KeyService extends AccessibilityService {
    private static final String TAG = "TvKeys";
    // Hisense vendor keycodes, as logged by WindowManager, the app each one should open, and the
    // app the firmware opens for it by default.
    private static final SparseArray<String[]> APP_KEYS = new SparseArray<>();
    static {
        APP_KEYS.put(293, new String[] {"org.smarttube.stable", "com.google.android.youtube.tv"});
        APP_KEYS.put(295, new String[] {"org.jellyfin.androidtv", "com.amazon.amazonvideo.livingroom"});
        APP_KEYS.put(265, new String[] {"com.stremio.one", "com.netflix.ninja"}); // KEYCODE_STEM_1
    }
    // From deep standby the standby chip wakes the TV as a power-on, so Android never sees the app
    // key. The kernel logs the raw IR code of the button that woke it ("PmEvent: ... HWcode:0x..");
    // these are the app keys among them (power is 0xd). Prime Video can't wake the TV at all.
    private static final SparseIntArray WAKE_CODES = new SparseIntArray();
    static {
        WAKE_CODES.put(0xaa, 293); // YouTube button
        WAKE_CODES.put(0x2d, 265); // Netflix button
    }
    // When the TV is woken by an app key, the release can slip past the key filter and the
    // firmware opens its default app on top of ours. For this long after a remapped press, the
    // default app coming to the front sends the user back to the chosen one.
    private static final long GUARD_MS = 5_000;

    // Measured on this TV with no key filter running: the first repeat lands ~500 ms after the
    // press, and a held key then averages ~8.5 volume steps a second. Going faster backs up the
    // audio service, so steps keep landing after the key is released.
    private static final long REPEAT_DELAY_MS = 500;
    private static final long REPEAT_INTERVAL_MS = 120;
    private static final int STEPS_PER_REPEAT = 1;
    // Stop if the release line never shows up, so a missed log line can't run the volume to max.
    private static final long MAX_HOLD_MS = 10_000;
    // The firmware logs each press twice, ~150 ms apart, so on a quick tap the second press line
    // can arrive after the release. A press this soon after the same key's release is that echo.
    private static final long ECHO_WINDOW_MS = 300;

    private static final Pattern VOLUME_KEY = Pattern.compile(
            "interceptKeyTi keyCode=(24|25) down=(true|false) repeatCount=0");
    private static final Pattern WAKE_EVENT = Pattern.compile("PmEvent:HeaderCode:0x[0-9a-f]+,HWcode:0x([0-9a-f]+)");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager audio;
    private int heldVolumeKey = 0;
    private long holdStart = 0;
    private int lastReleasedKey = 0;
    private long lastReleaseTime = 0;

    private String guardTarget;
    private String guardDefault;
    private long guardUntil = 0;

    private volatile boolean running = false;
    private Thread logThread;
    private volatile Process logcat;

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (heldVolumeKey == 0) return;
            if (SystemClock.uptimeMillis() - holdStart > MAX_HOLD_MS) {
                Log.w(TAG, "no release after " + MAX_HOLD_MS + " ms, stopping repeat");
                stopRepeat();
                return;
            }
            int direction = heldVolumeKey == KeyEvent.KEYCODE_VOLUME_UP
                    ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
            for (int i = 0; i < STEPS_PER_REPEAT; i++) {
                audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE,
                        AudioManager.FLAG_SHOW_UI);
            }
            handler.postDelayed(this, REPEAT_INTERVAL_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_LOGS not granted, volume repeat is off: "
                    + "adb shell pm grant local.tvkeys android.permission.READ_LOGS");
        }
        startLogWatcher();
        Log.i(TAG, "service connected");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String[] apps = APP_KEYS.get(event.getKeyCode());
        if (apps == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            openForKey(apps);
        }
        return true;
    }

    private void openForKey(String[] apps) {
        guardTarget = apps[0];
        guardDefault = apps[1];
        guardUntil = SystemClock.uptimeMillis() + GUARD_MS;
        launch(apps[0]);
    }

    private void onWake(int hwCode) {
        int key = WAKE_CODES.get(hwCode, 0);
        if (key == 0) return;
        Log.i(TAG, "woken by app key 0x" + Integer.toHexString(hwCode));
        openForKey(APP_KEYS.get(key));
    }

    private void startLogWatcher() {
        if (running) return;
        running = true;
        logThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        // -T 1 starts at the newest line, so old presses are never replayed.
                        logcat = new ProcessBuilder("logcat", "-b", "main,system", "-v", "raw",
                                "-T", "1", "-s", "WindowManager:D", "MTK_KL:D")
                                .redirectErrorStream(true).start();
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(logcat.getInputStream()));
                        String line;
                        while (running && (line = in.readLine()) != null) {
                            if (line.contains("PmEvent:")) {
                                Matcher w = WAKE_EVENT.matcher(line);
                                if (w.find()) {
                                    final int hwCode = Integer.parseInt(w.group(1), 16);
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            onWake(hwCode);
                                        }
                                    });
                                }
                                continue;
                            }
                            Matcher m = VOLUME_KEY.matcher(line);
                            if (!m.find()) continue;
                            final int code = Integer.parseInt(m.group(1));
                            final boolean down = "true".equals(m.group(2));
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    onVolumeKey(code, down);
                                }
                            });
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "log watcher stopped: " + e);
                    }
                    if (logcat != null) logcat.destroy();
                    // logcat exited or failed to start; retry without spinning.
                    if (running) SystemClock.sleep(2000);
                }
            }
        }, "tvkeys-log");
        logThread.setDaemon(true);
        logThread.start();
    }

    private void onVolumeKey(int code, boolean down) {
        long now = SystemClock.uptimeMillis();
        if (down) {
            // Only the first of the two press lines starts a hold, even when the echo lands
            // after a quick tap's release.
            if (heldVolumeKey == code) return;
            if (code == lastReleasedKey && now - lastReleaseTime < ECHO_WINDOW_MS) return;
            stopRepeat();
            heldVolumeKey = code;
            holdStart = now;
            handler.postDelayed(repeat, REPEAT_DELAY_MS);
        } else {
            lastReleasedKey = code;
            lastReleaseTime = now;
            if (code == heldVolumeKey) stopRepeat();
        }
    }

    private void stopRepeat() {
        handler.removeCallbacks(repeat);
        heldVolumeKey = 0;
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Log.w(TAG, pkg + " is not installed");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (guardDefault == null || SystemClock.uptimeMillis() > guardUntil) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null && guardDefault.contentEquals(pkg)) {
            Log.i(TAG, guardDefault + " opened after a remapped key, returning to " + guardTarget);
            launch(guardTarget);
        }
    }

    @Override
    public void onInterrupt() {
        stopRepeat();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (logcat != null) logcat.destroy();
        stopRepeat();
        super.onDestroy();
    }
}
