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
                    .append(describe(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1))
                    .append("   ·   feels like ").append(Math.round(cur.getDouble("apparent_temperature"))).append('°')
                    .append("   ·   humidity ").append(cur.getInt("relative_humidity_2m")).append('%')
                    .append("   ·   wind ").append(Math.round(cur.getDouble("wind_speed_10m"))).append(" km/h\n\n");

            s.append("NEXT HOURS\n");
            for (int i = 1; i < h.getJSONArray("time").length(); i++) {
                s.append(h.getJSONArray("time").getString(i).substring(11, 16)).append("   ")
                        .append(Math.round(h.getJSONArray("temperature_2m").getDouble(i))).append("°  ")
                        .append(describe(h.getJSONArray("weather_code").getInt(i), true));
                int rain = h.getJSONArray("precipitation_probability").optInt(i, 0);
                if (rain >= 20) s.append("  ·  rain ").append(rain).append('%');
                s.append('\n');
            }

            s.append("\nNEXT DAYS\n");
            for (int i = 0; i < d.getJSONArray("time").length(); i++) {
                s.append(dayName(i, d.getJSONArray("time").getString(i))).append("   ").append(Math.round(d.getJSONArray("temperature_2m_max").getDouble(i))).append("° / ")
                        .append(Math.round(d.getJSONArray("temperature_2m_min").getDouble(i))).append("°  ")
                        .append(describe(d.getJSONArray("weather_code").getInt(i), true))
                        .append("  ·  rain ").append(d.getJSONArray("precipitation_probability_max").optInt(i, 0)).append("%\n");
            }

            s.append("\nSunrise ").append(d.getJSONArray("sunrise").getString(0).substring(11, 16))
                    .append("   ·   sunset ").append(d.getJSONArray("sunset").getString(0).substring(11, 16))
                    .append("   ·   UV ").append(uv(d.getJSONArray("uv_index_max").optDouble(0, 0)));
            try {
                JSONObject aq = new JSONObject(get(String.format(Locale.ROOT,
                        "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.3f&longitude=%.3f&current=european_aqi", lat, lon)))
                        .getJSONObject("current");
                int aqi = aq.getInt("european_aqi");
                s.append("\nAir quality ").append(airQuality(aqi)).append(" (").append(aqi).append(')');
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
        long age = System.currentTimeMillis() - p.getLong("glance_at", 0);
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
                out.put(describe(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1));
                out.put("Feels like " + Math.round(cur.getDouble("apparent_temperature")) + "°   ·   humidity "
                        + cur.getInt("relative_humidity_2m") + "%   ·   wind " + Math.round(cur.getDouble("wind_speed_10m")) + " km/h");
                for (int i = 0; i < d.getJSONArray("time").length(); i++) {
                    out.put(dayName(i, d.getJSONArray("time").getString(i)) + "\t"
                            + Math.round(d.getJSONArray("temperature_2m_max").getDouble(i)) + "° / "
                            + Math.round(d.getJSONArray("temperature_2m_min").getDouble(i)) + "°\t"
                            + describe(d.getJSONArray("weather_code").getInt(i), true));
                }
                p.edit().putString("glance", out.toString()).putLong("glance_at", System.currentTimeMillis()).apply();
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
    static String dayName(int i, String iso) {
        if (i == 0) return "Today";
        if (i == 1) return "Tomorrow";
        try {
            return new java.text.SimpleDateFormat("EEEE", Locale.getDefault())
                    .format(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso));
        } catch (java.text.ParseException e) {
            return iso;
        }
    }

    /** UV index in words (WHO bands). */
    static String uv(double v) {
        long r = Math.round(v);
        return r + (r <= 2 ? " low" : r <= 5 ? " moderate" : r <= 7 ? " high" : r <= 10 ? " very high" : " extreme");
    }

    /** European AQI bands. */
    static String airQuality(int aqi) {
        return aqi <= 20 ? "good" : aqi <= 40 ? "fair" : aqi <= 60 ? "moderate" : aqi <= 80 ? "poor" : aqi <= 100 ? "very poor" : "extremely poor";
    }

    /** WMO weather codes, in words. */
    static String describe(int code, boolean day) {
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
