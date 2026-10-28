package io.github.ksfikas.tvhome;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * What was last watched in each app, for the line under the app's name in the dock.
 * Filled by MediaWatcher from the "now playing" details apps announce (title, show, position,
 * artwork); apps that announce nothing fall back to when they were last opened (usage access).
 * Kept on the box only: one small prefs entry and at most one artwork file per app.
 */
final class Watched {
    private Watched() { }

    static final class Entry {
        String title, subtitle;
        long pos, dur, at; // ms, ms, epoch ms when recorded
        boolean paused;
    }

    private static final long KEEP = 14L * 24 * 3600_000, USAGE_WINDOW = 7L * 24 * 3600_000;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("watched", Context.MODE_PRIVATE);
    }

    static File artFile(Context c, String pkg) {
        return new File(new File(c.getCacheDir(), "art"), pkg + ".jpg");
    }

    /** Record an app's session: called on every metadata/state change, so it stays cheap. */
    static void save(Context c, MediaController mc) {
        String pkg = mc.getPackageName();
        if (pkg == null || pkg.equals(c.getPackageName()) || pkg.equals("com.android.bluetooth")) return;
        PlaybackState st = mc.getPlaybackState();
        MediaMetadata md = mc.getMetadata();
        if (st == null) return;
        int s = st.getState();
        boolean playing = s == PlaybackState.STATE_PLAYING;
        if (!playing && s != PlaybackState.STATE_PAUSED && s != PlaybackState.STATE_STOPPED) return;
        long pos = st.getPosition();
        if (playing && st.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        Entry old = get(c, pkg);
        // A stop that resets to 0 (Netflix does this) says nothing new: keep what we had.
        if (!playing && pos <= 0 && old != null) return;

        Entry e = new Entry();
        e.pos = Math.max(0, pos);
        e.paused = !playing;
        e.at = System.currentTimeMillis();
        if (md != null) {
            e.title = text(md.getDescription() == null ? null : md.getDescription().getTitle(),
                    md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), md.getString(MediaMetadata.METADATA_KEY_TITLE));
            e.subtitle = text(md.getDescription() == null ? null : md.getDescription().getSubtitle(),
                    md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE), md.getString(MediaMetadata.METADATA_KEY_ARTIST));
            e.dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        // Some apps drop the title while pausing; same video (same length) keeps it.
        if (e.title == null && old != null && old.title != null && old.dur > 0 && old.dur == e.dur) {
            e.title = old.title;
            e.subtitle = old.subtitle;
        }
        put(c, pkg, e);
        if (mc.getSessionActivity() != null) resume.put(pkg, mc.getSessionActivity());
        if (md != null) saveArt(c, pkg, md, e.title, old == null ? null : old.title);
    }

    private static String text(CharSequence... options) {
        for (CharSequence o : options) if (o != null && o.toString().trim().length() > 0) return o.toString().trim();
        return null;
    }

    /** The app's artwork, at most 1280 px wide; a new title without artwork drops the old picture. */
    private static void saveArt(Context c, String pkg, MediaMetadata md, String title, String oldTitle) {
        File f = artFile(c, pkg);
        boolean sameTitle = title != null && title.equals(oldTitle);
        if (sameTitle && f.exists()) return;
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b == null) {
            String uri = md.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (uri == null) uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (uri == null) uri = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            if (uri != null) { String u = uri; Tvh.IO.execute(() -> fetchArt(c, f, u)); return; }
            if (!sameTitle) f.delete();
            return;
        }
        writeArt(f, b);
    }

    private static void fetchArt(Context c, File f, String uri) {
        try (java.io.InputStream in = uri.startsWith("http")
                ? new java.net.URL(uri).openStream()
                : c.getContentResolver().openInputStream(android.net.Uri.parse(uri))) {
            Bitmap b = in == null ? null : BitmapFactory.decodeStream(in);
            if (b != null) writeArt(f, b);
        } catch (Exception ignored) { }
    }

    private static void writeArt(File f, Bitmap b) {
        if (b.getWidth() < 64) return; // an app icon, not artwork
        if (b.getWidth() > 1280) b = Bitmap.createScaledBitmap(b, 1280, Math.max(1, b.getHeight() * 1280 / b.getWidth()), true);
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (java.io.IOException ignored) { }
    }

    static Entry get(Context c, String pkg) {
        String s = prefs(c).getString(pkg, null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            Entry e = new Entry();
            e.title = o.has("t") ? o.getString("t") : null;
            e.subtitle = o.has("s") ? o.getString("s") : null;
            e.pos = o.optLong("p");
            e.dur = o.optLong("d");
            e.at = o.optLong("at");
            e.paused = o.optBoolean("paused");
            return System.currentTimeMillis() - e.at > KEEP ? null : e;
        } catch (JSONException ex) {
            return null;
        }
    }

    /** Every app's latest entry (the Top Shelf's "All continue watching"). */
    static Map<String, Entry> all(Context c) {
        Map<String, Entry> out = new HashMap<>();
        for (String pkg : prefs(c).getAll().keySet()) {
            Entry e = get(c, pkg);
            if (e != null) out.put(pkg, e);
        }
        return out;
    }

    /** "Remove" on the shelf: forget an app's entry and its artwork. */
    static void remove(Context c, String pkg) {
        prefs(c).edit().remove(pkg).apply();
        artFile(c, pkg).delete();
    }

    /**
     * Where each app's player is, while that app is still running: its media session's own
     * "open me" link, kept in memory only (MediaWatcher fills it). Resume uses it.
     */
    static final Map<String, android.app.PendingIntent> resume = new java.util.concurrent.ConcurrentHashMap<>();

    private static void put(Context c, String pkg, Entry e) {
        JSONObject o = new JSONObject();
        try {
            if (e.title != null) o.put("t", e.title);
            if (e.subtitle != null) o.put("s", e.subtitle);
            o.put("p", e.pos).put("d", e.dur).put("at", e.at).put("paused", e.paused);
        } catch (JSONException ignored) { }
        prefs(c).edit().putString(pkg, o.toString()).apply();
    }

    // ---------------------------------------------------------------- what the dock shows

    static final class Line {
        CharSequence text;
        float progress = -1; // 0..1, or -1 for no bar
    }

    /** "▶ Title · Show · 12 min left", "Paused at 7:09" or "Last opened 20 min ago"; null for nothing. */
    static Line line(Context c, String pkg, Map<String, Long> lastUsed) {
        Entry e = get(c, pkg);
        long now = System.currentTimeMillis();
        Line l = new Line();
        if (e != null && (e.title != null || e.pos > 0)) {
            StringBuilder s = new StringBuilder();
            if (e.title != null) {
                s.append("▶  ").append(e.title);
                if (e.subtitle != null && !e.subtitle.equals(e.title)) s.append("  ·  ").append(e.subtitle);
                if (e.dur > 0 && e.pos > 0 && e.pos < e.dur) s.append("  ·  ").append(left(e.dur - e.pos));
            } else {
                s.append(e.paused ? "Paused at " : "Stopped at ").append(Ui.duration(e.pos));
            }
            if (now - e.at > 6 * 3600_000L) s.append("  ·  ").append(ago(now - e.at));
            l.text = s;
            if (e.dur > 0 && e.pos > 0) l.progress = Math.min(1f, e.pos / (float) e.dur);
            return l;
        }
        Long used = lastUsed.get(pkg);
        if (used != null && now - used < USAGE_WINDOW) {
            l.text = "Last opened " + ago(now - used);
            return l;
        }
        return null;
    }

    static String left(long ms) {
        long m = Math.max(1, ms / 60_000);
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " min left" : m + " min left";
    }

    static String ago(long ms) {
        long m = ms / 60_000;
        if (m < 2) return "just now";
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + (h == 1 ? " hour ago" : " hours ago");
        long d = h / 24;
        return d == 1 ? "yesterday" : d + " days ago";
    }

    /** When each app was last in front (needs usage access, granted over ADB); empty without it. */
    static Map<String, Long> lastUsed(Context c) {
        UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
        if (um == null) return Collections.emptyMap();
        long now = System.currentTimeMillis();
        Map<String, Long> out = new HashMap<>();
        try {
            for (Map.Entry<String, UsageStats> e : um.queryAndAggregateUsageStats(now - USAGE_WINDOW, now).entrySet()) {
                long t = e.getValue().getLastTimeVisible();
                if (t <= 0) t = e.getValue().getLastTimeUsed();
                if (t > 0) out.put(e.getKey(), t);
            }
        } catch (SecurityException ignored) { }
        return out;
    }
}
