package io.github.ksfikas.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Optional smart-home hooks: when playback starts, pauses or stops, the box calls a web address you
 * choose (a Home Assistant webhook, Node-RED, IFTTT, an ESPHome device...), so a scene can dim the
 * lights. Off until an address is set on the phone remote page; nothing runs in the background.
 */
final class HomeHooks {
    private HomeHooks() { }

    static final String PLAY = "play", PAUSE = "pause", STOP = "stop";
    static final String[] EVENTS = {PLAY, PAUSE, STOP};

    private static Handler main;   // created on first use, so the class loads in plain JVM tests too

    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }
    private static Runnable pending;
    private static String lastSent = STOP;

    static String key(String event) { return "hook_" + event; }

    static String url(Context c, String event) { return Tvh.prefs(c).getString(key(event), "").trim(); }

    static void setUrl(Context c, String event, String url) {
        Tvh.prefs(c).edit().putString(key(event), url == null ? "" : url.trim()).apply();
    }

    /** Only plain web addresses: http or https, with a host. */
    static boolean valid(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;
        try {
            return new URL(u).getHost().length() > 0;
        } catch (java.net.MalformedURLException e) {
            return false;
        }
    }

    /** Whether going from the last event to this one is worth a call: play after play is not. */
    static boolean worthSending(String last, String next) { return !next.equals(last); }

    /**
     * Playback changed. Briefly delayed, and a newer event replaces an unsent one, so changing
     * channels (stop then play within a second) sends nothing and a pause is not announced twice.
     */
    static void event(Context c, String event) {
        final Context app = c.getApplicationContext();
        if (url(app, event).isEmpty() && !anySet(app)) return;
        main().post(() -> {
            if (pending != null) main().removeCallbacks(pending);
            pending = () -> {
                pending = null;
                if (!worthSending(lastSent, event)) return;
                lastSent = event;
                fire(app, event);
            };
            main().postDelayed(pending, PLAY.equals(event) ? 400 : 1500);
        });
    }

    private static boolean anySet(Context c) {
        for (String e : EVENTS) if (!url(c, e).isEmpty()) return true;
        return false;
    }

    /** Calls the address set for an event, off the main thread (nothing when none is set). */
    static void fire(Context c, String event) {
        String u = url(c, event);
        if (!valid(u)) return;
        Tvh.IO.execute(() -> { call(u); });
    }

    /** One POST; null when it worked, else a short error (the phone page's test button shows it). */
    static String call(String u) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
            con.setConnectTimeout(3000);
            con.setReadTimeout(4000);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(0);
            int code = con.getResponseCode();
            con.disconnect();
            return code < 400 ? null : "HTTP " + code;
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }
}
