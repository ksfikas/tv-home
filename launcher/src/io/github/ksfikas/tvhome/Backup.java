package io.github.ksfikas.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * Settings backup for the phone remote: favourites, dock, sources, language, weather city and
 * screensaver choices as one small JSON file. Passwords are never included (re-enter them after a
 * restore), nor anything that is only a cache or a position.
 */
final class Backup {
    private Backup() { }

    static final int VERSION = 1;

    /** Keys of the "tvh" prefs that belong in a backup (plus every "ss_" screensaver key). */
    private static final String[] KEYS = {"favourites", "dock_user", "live_hidden", "shelf_mode", "shelf_feed",
            "shelf_films", "shelf_news", "wall_mine_only", "url", "hdhr", "setup_done", "setup_offered"};

    static boolean allowed(String key) {
        if (key.startsWith("ss_")) return true;
        for (String k : KEYS) if (k.equals(key)) return true;
        return false;
    }

    /** The backup-worthy part of a preferences map (booleans, numbers and strings only). */
    static JSONObject filter(Map<String, ?> all) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            Object v = e.getValue();
            if (!allowed(e.getKey()) || !(v instanceof Boolean || v instanceof Number || v instanceof String)) continue;
            o.put(e.getKey(), v);
        }
        return o;
    }

    /** Writes the allowed entries of {@code saved} into {@code ed}, keeping each setting's stored type. Returns how many. */
    static int apply(JSONObject saved, Map<String, ?> existing, SharedPreferences.Editor ed) {
        int n = 0;
        java.util.Iterator<String> it = saved.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = saved.opt(k);
            if (!allowed(k)) continue;
            Object had = existing.get(k);
            if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
            else if (v instanceof String) ed.putString(k, (String) v);
            else if (v instanceof Number) {
                if (had instanceof Long) ed.putLong(k, ((Number) v).longValue());
                else ed.putInt(k, ((Number) v).intValue());
            } else continue;
            n++;
        }
        return n;
    }

    static JSONObject export(Context c) throws JSONException {
        JSONObject o = new JSONObject().put("app", "advanced-tv-home").put("version", VERSION);
        o.put("tvh", filter(Tvh.prefs(c).getAll()));
        o.put("language", Lang.choice(c));
        o.put("city", Weather.city(c));
        return o;
    }

    /** Applies a backup; returns how many settings were restored, or -1 when the file isn't one of ours. */
    static int restore(Context c, JSONObject o) {
        if (o == null || !"advanced-tv-home".equals(o.optString("app")) || o.optInt("version") > VERSION) return -1;
        SharedPreferences p = Tvh.prefs(c);
        SharedPreferences.Editor ed = p.edit();
        int n = apply(o.optJSONObject("tvh") == null ? new JSONObject() : o.optJSONObject("tvh"), p.getAll(), ed);
        ed.apply();
        String lang = o.optString("language", "");
        if (!lang.isEmpty() && !lang.equals(Lang.choice(c))) {
            c.getSharedPreferences("lang", Context.MODE_PRIVATE).edit().putString("lang", lang).apply();
            n++;
        }
        String city = o.optString("city", "");
        if (!city.isEmpty() && !city.equals(Weather.city(c))) { Weather.setCity(c, city); n++; }
        return n;
    }
}
