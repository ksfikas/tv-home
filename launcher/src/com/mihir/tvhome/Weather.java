package com.mihir.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Current weather for the clock, from Open-Meteo (free, no key, no account). The city is set once
 * (Inputs → Weather…) and turned into coordinates; only those coordinates are sent afterwards.
 */
final class Weather {
    private Weather() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    static String city(Context c) { return prefs(c).getString("city", ""); }

    /** Look the city up; returns null when fine, else a message. Empty name turns weather off. */
    static String setCity(Context c, String name) {
        name = name.trim();
        if (name.isEmpty()) { prefs(c).edit().clear().apply(); return null; }
        try {
            JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                    + URLEncoder.encode(name, "UTF-8")));
            JSONArray r = o.optJSONArray("results");
            if (r == null || r.length() == 0) return "Can't find “" + name + "”";
            JSONObject p = r.getJSONObject(0);
            prefs(c).edit().clear().putString("city", p.optString("name", name))
                    .putFloat("lat", (float) p.getDouble("latitude"))
                    .putFloat("lon", (float) p.getDouble("longitude")).apply();
            return null;
        } catch (Exception e) {
            return "Can't reach the weather service";
        }
    }

    /** "21°  Partly cloudy", refreshed at most every 30 min; null when off or not fetched yet. */
    static String current(Context c, boolean fetch) {
        SharedPreferences p = prefs(c);
        if (!p.contains("lat")) return null;
        long age = System.currentTimeMillis() - p.getLong("at", 0);
        if (fetch && age > 30 * 60_000) {
            try {
                JSONObject cur = new JSONObject(get(String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day",
                        p.getFloat("lat", 0), p.getFloat("lon", 0)))).getJSONObject("current");
                String s = Math.round(cur.getDouble("temperature_2m")) + "°  " + describe(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
                p.edit().putString("text", s).putLong("at", System.currentTimeMillis()).apply();
                age = 0;
            } catch (Exception ignored) { }
        }
        return age < 3 * 3600_000 ? p.getString("text", null) : null; // don't show stale weather
    }

    /** WMO weather codes, in words. */
    private static String describe(int code, boolean day) {
        if (code == 0) return day ? "Sunny" : "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code <= 48) return "Fog";
        if (code <= 57) return "Drizzle";
        if (code <= 67) return "Rain";
        if (code <= 77) return "Snow";
        if (code <= 82) return "Showers";
        if (code <= 86) return "Snow showers";
        return "Thunderstorms";
    }

    private static String get(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(6000);
        con.setReadTimeout(10000);
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            con.disconnect();
        }
    }
}
