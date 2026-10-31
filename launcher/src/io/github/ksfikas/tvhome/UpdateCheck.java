package io.github.ksfikas.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * "A newer version is out": asks GitHub for the latest release at most once a day (while the daily cache
 * tidy runs), keeps the answer, and Box status shows it with the install command. One small request, no
 * account, nothing sent but the usual request headers; it can be switched off in Box status.
 */
final class UpdateCheck {
    private UpdateCheck() { }

    static final String API = "https://api.github.com/repos/ksfikas/advanced-tv-home/releases/latest";
    static final String PAGE = "github.com/ksfikas/advanced-tv-home/releases";
    private static final long DAY = 24 * 3600_000L, RETRY = 6 * 3600_000L;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("update", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) { return prefs(c).getBoolean("check", true); }
    static void setEnabled(Context c, boolean on) { prefs(c).edit().putBoolean("check", on).apply(); }

    /** This app's own version name, like "3.0.1-tvh". */
    static String installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** The newer release's tag (like "v3.0.2") if one is known, else null. Reads the saved answer; no network. */
    static String newerTag(Context c) {
        if (!enabled(c)) return null;
        String tag = prefs(c).getString("tag", null);
        return tag != null && isNewer(tag, installed(c)) ? tag : null;
    }

    /** Ask GitHub if the last answer is a day old (a failed try waits six hours). Call off the main thread. */
    static void refreshIfDue(Context c) {
        if (!enabled(c)) return;
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (now - p.getLong("checked", 0) < DAY || now - p.getLong("failed", 0) < RETRY) return;
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(10000);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            con.setRequestProperty("User-Agent", "Advanced-TV-Home");
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                String tag = parseTag(new String(out.toByteArray(), StandardCharsets.UTF_8));
                if (tag == null) throw new java.io.IOException("no tag");
                p.edit().putString("tag", tag).putLong("checked", now).apply();
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            p.edit().putLong("failed", now).apply(); // offline, rate-limited, or GitHub changed: quietly try again later
        }
    }

    /** "tag_name" from the release JSON, or null. */
    static String parseTag(String json) {
        try {
            String tag = new JSONObject(json).optString("tag_name", "");
            return tag.isEmpty() ? null : tag;
        } catch (Exception e) {
            return null;
        }
    }

    /** The numbers of a version name: "v3.0.1" and "3.0.1-tvh" are {3,0,1}; "2.2-tvh" is {2,2,0}. */
    static int[] numbers(String version) {
        int[] out = new int[3];
        if (version == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").matcher(version);
        if (m.find()) for (int i = 0; i < 3; i++) out[i] = m.group(i + 1) == null ? 0 : Integer.parseInt(m.group(i + 1));
        return out;
    }

    static boolean isNewer(String latest, String installed) {
        int[] a = numbers(latest), b = numbers(installed);
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }
}
