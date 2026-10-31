package io.github.ksfikas.tvhome;

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
            // language: the place name comes back in the app's language ("Αθήνα" or "Athens")
            JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language="
                    + c.getResources().getConfiguration().getLocales().get(0).getLanguage() + "&name="
                    + URLEncoder.encode(name, "UTF-8")));
            JSONArray r = o.optJSONArray("results");
            if (r == null || r.length() == 0) return c.getString(R.string.weather_city_not_found, name);
            JSONObject p = r.getJSONObject(0);
            prefs(c).edit().clear().putString("city", p.optString("name", name))
                    .putFloat("lat", (float) p.getDouble("latitude"))
                    .putFloat("lon", (float) p.getDouble("longitude")).apply();
            return null;
        } catch (Exception e) {
            return c.getString(R.string.weather_unreachable);
        }
    }

    /** "21°  Partly cloudy", refreshed at most every 30 min; null when off or not fetched yet. */
    static String current(Context c, boolean fetch) {
        SharedPreferences p = prefs(c);
        if (!p.contains("lat")) return null;
        // The cached text is words in one language: a language change makes it stale.
        String lang = c.getResources().getConfiguration().getLocales().get(0).getLanguage();
        boolean sameLang = lang.equals(p.getString("text_lang", ""));
        long age = sameLang ? System.currentTimeMillis() - p.getLong("at", 0) : Long.MAX_VALUE;
        if (fetch && age > 30 * 60_000) {
            try {
                JSONObject cur = new JSONObject(get(String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day",
                        p.getFloat("lat", 0), p.getFloat("lon", 0)))).getJSONObject("current");
                String s = Math.round(cur.getDouble("temperature_2m")) + "°  " + describe(Txt.of(c), cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1);
                p.edit().putString("text", s).putString("text_lang", lang).putLong("at", System.currentTimeMillis()).apply();
                age = 0;
            } catch (Exception ignored) { }
        }
        return age < 3 * 3600_000 ? p.getString("text", null) : null; // don't show stale weather
    }

    /**
     * The detail card (Control Center → Weather): now, the next hours, three days, sun, UV, rain and
     * air quality. Fetched only when opened; null when weather is off or unreachable.
     */
    static String details(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("lat")) return null;
        float lat = p.getFloat("lat", 0), lon = p.getFloat("lon", 0);
        try {
            JSONObject f = new JSONObject(get(String.format(Locale.ROOT,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&timezone=auto&forecast_days=3&forecast_hours=7"
                            + "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m"
                            + "&hourly=temperature_2m,weather_code,precipitation_probability"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max,uv_index_max",
                    lat, lon)));
            JSONObject cur = f.getJSONObject("current"), h = f.getJSONObject("hourly"), d = f.getJSONObject("daily");
            StringBuilder s = new StringBuilder();
            s.append(Math.round(cur.getDouble("temperature_2m"))).append("°  ")
                    .append(describe(Txt.of(c), cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1))
                    .append("   ·   ").append(c.getString(R.string.weather_feels_like, Math.round(cur.getDouble("apparent_temperature"))))
                    .append("   ·   ").append(c.getString(R.string.weather_humidity, cur.getInt("relative_humidity_2m")))
                    .append("   ·   ").append(c.getString(R.string.weather_wind, Math.round(cur.getDouble("wind_speed_10m")))).append("\n\n");

            s.append(c.getString(R.string.weather_next_hours)).append('\n');
            for (int i = 1; i < h.getJSONArray("time").length(); i++) {
                s.append(h.getJSONArray("time").getString(i).substring(11, 16)).append("   ")
                        .append(Math.round(h.getJSONArray("temperature_2m").getDouble(i))).append("°  ")
                        .append(describe(Txt.of(c), h.getJSONArray("weather_code").getInt(i), true));
                int rain = h.getJSONArray("precipitation_probability").optInt(i, 0);
                if (rain >= 20) s.append("  ·  ").append(c.getString(R.string.weather_rain_pct, rain));
                s.append('\n');
            }

            s.append('\n').append(c.getString(R.string.weather_next_days)).append('\n');
            for (int i = 0; i < d.getJSONArray("time").length(); i++) {
                s.append(dayName(Txt.of(c), i, d.getJSONArray("time").getString(i))).append("   ").append(Math.round(d.getJSONArray("temperature_2m_max").getDouble(i))).append("° / ")
                        .append(Math.round(d.getJSONArray("temperature_2m_min").getDouble(i))).append("°  ")
                        .append(describe(Txt.of(c), d.getJSONArray("weather_code").getInt(i), true))
                        .append("  ·  ").append(c.getString(R.string.weather_rain_pct, d.getJSONArray("precipitation_probability_max").optInt(i, 0))).append('\n');
            }

            s.append('\n').append(c.getString(R.string.weather_sunrise, d.getJSONArray("sunrise").getString(0).substring(11, 16)))
                    .append("   ·   ").append(c.getString(R.string.weather_sunset, d.getJSONArray("sunset").getString(0).substring(11, 16)))
                    .append("   ·   ").append(c.getString(R.string.weather_uv, uv(Txt.of(c), d.getJSONArray("uv_index_max").optDouble(0, 0))));
            try {
                JSONObject aq = new JSONObject(get(String.format(Locale.ROOT,
                        "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.3f&longitude=%.3f&current=european_aqi", lat, lon)))
                        .getJSONObject("current");
                int aqi = aq.getInt("european_aqi");
                s.append('\n').append(c.getString(R.string.weather_air_quality, airQuality(Txt.of(c), aqi), aqi));
            } catch (Exception ignored) { } // air quality is a nice-to-have
            return s.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The weather slide of the screensaver: {big temperature, conditions, feels-like line, then one
     * "day TAB high / low TAB conditions" entry per day}. Cached 30 minutes; null when weather is off
     * or has never loaded.
     */
    static String[] glance(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("lat")) return null;
        String lang = c.getResources().getConfiguration().getLocales().get(0).getLanguage();
        boolean sameLang = lang.equals(p.getString("glance_lang", ""));
        long age = sameLang ? System.currentTimeMillis() - p.getLong("glance_at", 0) : Long.MAX_VALUE;
        if (!p.contains("glance") || age > 30 * 60_000) {
            try {
                JSONObject f = new JSONObject(get(String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&timezone=auto&forecast_days=3"
                                + "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m"
                                + "&daily=weather_code,temperature_2m_max,temperature_2m_min",
                        p.getFloat("lat", 0), p.getFloat("lon", 0))));
                JSONObject cur = f.getJSONObject("current"), d = f.getJSONObject("daily");
                JSONArray out = new JSONArray();
                out.put(Math.round(cur.getDouble("temperature_2m")) + "°");
                out.put(describe(Txt.of(c), cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1));
                out.put(c.getString(R.string.weather_feels_like_cap, Math.round(cur.getDouble("apparent_temperature")))
                        + "   ·   " + c.getString(R.string.weather_humidity, cur.getInt("relative_humidity_2m"))
                        + "   ·   " + c.getString(R.string.weather_wind, Math.round(cur.getDouble("wind_speed_10m"))));
                for (int i = 0; i < d.getJSONArray("time").length(); i++) {
                    out.put(dayName(Txt.of(c), i, d.getJSONArray("time").getString(i)) + "\t"
                            + Math.round(d.getJSONArray("temperature_2m_max").getDouble(i)) + "° / "
                            + Math.round(d.getJSONArray("temperature_2m_min").getDouble(i)) + "°\t"
                            + describe(Txt.of(c), d.getJSONArray("weather_code").getInt(i), true));
                }
                p.edit().putString("glance", out.toString()).putString("glance_lang", lang).putLong("glance_at", System.currentTimeMillis()).apply();
                age = 0;
            } catch (Exception ignored) { }
        }
        if (age > 3 * 3600_000 || !p.contains("glance")) return null; // don't show stale weather
        try {
            JSONArray a = new JSONArray(p.getString("glance", "[]"));
            String[] out = new String[a.length()];
            for (int i = 0; i < out.length; i++) out[i] = a.getString(i);
            return out.length >= 3 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** "Today", "Tomorrow", then the weekday; iso is the day as yyyy-MM-dd. */
    static String dayName(Txt t, int i, String iso) {
        if (i == 0) return t.s(R.string.weather_today);
        if (i == 1) return t.s(R.string.weather_tomorrow);
        try {
            return new java.text.SimpleDateFormat("EEEE", Locale.getDefault())
                    .format(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso));
        } catch (java.text.ParseException e) {
            return iso;
        }
    }

    /** UV index in words (WHO bands). */
    static String uv(Txt t, double v) {
        int r = (int) Math.round(v);
        return t.s(r <= 2 ? R.string.weather_uv_low : r <= 5 ? R.string.weather_uv_moderate : r <= 7 ? R.string.weather_uv_high
                : r <= 10 ? R.string.weather_uv_very_high : R.string.weather_uv_extreme, r);
    }

    /** European AQI bands. */
    static String airQuality(Txt t, int aqi) {
        return t.s(aqi <= 20 ? R.string.weather_aq_good : aqi <= 40 ? R.string.weather_aq_fair : aqi <= 60 ? R.string.weather_aq_moderate
                : aqi <= 80 ? R.string.weather_aq_poor : aqi <= 100 ? R.string.weather_aq_very_poor : R.string.weather_aq_extremely_poor);
    }

    /** WMO weather codes, in words. */
    static String describe(Txt t, int code, boolean day) {
        if (code == 0) return t.s(day ? R.string.weather_sunny : R.string.weather_clear);
        if (code <= 2) return t.s(R.string.weather_partly_cloudy);
        if (code == 3) return t.s(R.string.weather_cloudy);
        if (code <= 48) return t.s(R.string.weather_fog);
        if (code <= 57) return t.s(R.string.weather_drizzle);
        if (code <= 67) return t.s(R.string.weather_rain);
        if (code <= 77) return t.s(R.string.weather_snow);
        if (code <= 82) return t.s(R.string.weather_showers);
        if (code <= 86) return t.s(R.string.weather_snow_showers);
        return t.s(R.string.weather_thunderstorms);
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
