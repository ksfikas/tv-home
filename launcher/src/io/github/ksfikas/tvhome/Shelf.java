package io.github.ksfikas.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Top Shelf above the dock (tvOS style), in one of four modes:
 *  app  — what you last watched in the focused app (Watched), the default;
 *  all  — everything you were watching, as a carousel with Resume;
 *  recs — popular films (Apple's iTunes chart for Greece) and news headlines (an RSS feed);
 *  off  — just the wallpaper.
 * Recommendations are fetched at most hourly while home is showing, cached on the box.
 */
final class Shelf {
    private Shelf() { }

    static final String APP = "app", ALL = "all", RECS = "recs", OFF = "off";

    static final class Item {
        String kind;            // "watch", "film", "news"
        String title, sub, summary, link, pkg, artUrl;
        File artFile;           // continue watching: the app's artwork on the box
        boolean tall;           // a poster (portrait) rather than a backdrop
        float progress = -1;
        long at;                // continue watching: when it was last played
    }

    static final String[][] FEEDS = {
            {"ERT News", "https://www.ertnews.gr/feed/"},
            {"Kathimerini", "https://www.kathimerini.gr/infeeds/rss/nx-rss-feed.xml"},
            {"in.gr", "https://www.in.gr/feed/"},
    };

    private static SharedPreferences prefs(Context c) { return Tvh.prefs(c); }

    static String mode(Context c) { return prefs(c).getString("shelf_mode", APP); }
    static void setMode(Context c, String m) { prefs(c).edit().putString("shelf_mode", m).apply(); }
    static boolean films(Context c) { return prefs(c).getBoolean("shelf_films", true); }
    static boolean news(Context c) { return prefs(c).getBoolean("shelf_news", true); }
    static int feed(Context c) { return Math.min(FEEDS.length - 1, prefs(c).getInt("shelf_feed", 0)); }

    static String modeName(Txt t, String m) {
        switch (m) {
            case ALL: return t.s(R.string.shelf_mode_all);
            case RECS: return t.s(R.string.shelf_mode_recs);
            case OFF: return t.s(R.string.shelf_mode_off);
            default: return t.s(R.string.shelf_mode_app);
        }
    }

    // ---------------------------------------------------------------- continue watching

    /** Everything Watched knows about, newest first, for apps still installed. */
    static List<Item> continueWatching(Context c) {
        List<Item> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        Txt t = Txt.of(c);
        for (Map.Entry<String, Watched.Entry> e : Watched.all(c).entrySet()) {
            Watched.Entry w = e.getValue();
            if (w.title == null && w.pos <= 0) continue;
            String label;
            try { label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(e.getKey(), 0))); }
            catch (PackageManager.NameNotFoundException gone) { continue; }
            Item it = new Item();
            it.kind = "watch";
            it.pkg = e.getKey();
            it.title = w.title != null ? w.title : label;
            StringBuilder s = new StringBuilder(label);
            if (w.subtitle != null && w.title != null) s.append("  ·  ").append(w.subtitle);
            if (w.dur > 0 && w.pos > 0 && w.pos < w.dur) s.append("  ·  ").append(Watched.left(t, w.dur - w.pos));
            else if (w.title == null) s.append("  ·  ").append(c.getString(w.paused ? R.string.shelf_paused_at : R.string.shelf_stopped_at, Ui.duration(w.pos)));
            s.append("  ·  ").append(Watched.ago(t, System.currentTimeMillis() - w.at));
            it.sub = s.toString();
            if (w.dur > 0 && w.pos > 0) it.progress = Math.min(1f, w.pos / (float) w.dur);
            File art = Watched.artFile(c, e.getKey());
            if (art.exists()) it.artFile = art;
            it.at = w.at;
            out.add(it);
        }
        out.sort((a, b) -> Long.compare(b.at, a.at));
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    // ---------------------------------------------------------------- recommendations & news

    /** Cached recommendations; refreshed in the background when older than an hour (call off the UI thread). */
    static List<Item> recommendations(Context c, boolean fetch) {
        File cache = new File(c.getCacheDir(), "shelf.json");
        // The cached captions ("5 min ago", "Popular film") are in the language they were fetched in, so
        // the language is part of the key. Bump the version to refetch after a format change.
        String key = "v5|" + films(c) + "|" + news(c) + "|" + feed(c) + "|" + c.getResources().getConfiguration().getLocales().get(0).getLanguage();
        boolean fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < 3600_000L
                && key.equals(prefs(c).getString("shelf_key", ""));
        // Both sources down (offline): don't retry on every return to home, wait a few minutes.
        if (fetch && !fresh && System.currentTimeMillis() - lastTotalFailure > 5 * 60_000L) {
            List<Item> got = new ArrayList<>();
            Txt t = Txt.of(c);
            List<Item> films = films(c) ? films(t) : new ArrayList<>(), news = news(c) ? news(t, feed(c)) : new ArrayList<>();
            // Grouped, not interleaved: all the films, then the headlines.
            got.addAll(films);
            got.addAll(news);
            // A source that failed this time (empty) is retried next time rather than cached as empty.
            boolean complete = (!films(c) || !films.isEmpty()) && (!news(c) || !news.isEmpty());
            if (!got.isEmpty()) {
                save(cache, got);
                if (!complete) cache.setLastModified(System.currentTimeMillis() - 3300_000L); // retry in ~5 min
                prefs(c).edit().putString("shelf_key", key).apply();
            } else if (films(c) || news(c)) {
                lastTotalFailure = System.currentTimeMillis();
            }
        }
        return load(cache);
    }

    private static volatile long lastTotalFailure;

    /** The top films on Apple's Greek iTunes chart, with large posters. */
    static List<Item> films(Txt t) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray entries = new JSONObject(http("https://itunes.apple.com/gr/rss/topmovies/limit=10/json"))
                    .getJSONObject("feed").getJSONArray("entry");
            for (int i = 0; i < entries.length(); i++) try {
                JSONObject e = entries.getJSONObject(i);
                Item it = new Item();
                it.kind = "film";
                it.title = e.getJSONObject("im:name").getString("label");
                String genre = e.optJSONObject("category") == null ? "" : e.getJSONObject("category").getJSONObject("attributes").optString("label");
                String year = e.optJSONObject("im:releaseDate") == null ? "" : e.getJSONObject("im:releaseDate").optString("label", "").replaceAll("^(\\d{4}).*", "$1");
                it.sub = t.s(R.string.shelf_popular_film) + (genre.isEmpty() ? "" : "  ·  " + genre) + (year.isEmpty() ? "" : "  ·  " + year);
                it.summary = e.optJSONObject("summary") == null ? null : e.getJSONObject("summary").optString("label");
                JSONArray imgs = e.getJSONArray("im:image");
                it.artUrl = poster(imgs.getJSONObject(imgs.length() - 1).getString("label"));
                it.tall = true;
                // Apple TV app link: tv.apple.com resolves the iTunes id to the film's page.
                String id = e.getJSONObject("id").getJSONObject("attributes").optString("im:id", "");
                if (!id.isEmpty()) it.link = "https://tv.apple.com/gr/movie/id" + id;
                out.add(it);
            } catch (Exception oneBadEntry) { }  // skip just that film
        } catch (Exception ignored) { } // offline or the chart changed: no films this time
        return out;
    }

    /** iTunes artwork links end in the size (".../115x170bb.png"); ask for a big one. */
    static String poster(String url) {
        return url.replaceAll("/\\d+x\\d+bb\\.(png|jpg)$", "/600x900bb.jpg");
    }

    /** The newest headlines from an RSS feed, with the story's photo when the feed has one. */
    static List<Item> news(Txt t, int feed) {
        List<Item> out = new ArrayList<>();
        try {
            out = parseRss(t, http(FEEDS[feed][1]), FEEDS[feed][0], 8);
        } catch (Exception ignored) { }
        return out;
    }

    static List<Item> parseRss(Txt t, String xml, String source, int max) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "UTF-8");
        return parseRss(t, p, source, max, Shelf::plain);
    }

    /** The parsing itself; the parser and the HTML cleaner are passed in so it can be tested off the device. */
    static List<Item> parseRss(Txt t, XmlPullParser p, String source, int max,
                               java.util.function.UnaryOperator<String> clean) throws Exception {
        List<Item> out = new ArrayList<>();
        Item it = null;
        String tag = null;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && out.size() < max; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                tag = p.getName();
                if ("item".equals(tag)) { it = new Item(); it.kind = "news"; }
                else if (it != null && ("enclosure".equals(tag) || "media:content".equals(tag) || "media:thumbnail".equals(tag))) {
                    String url = p.getAttributeValue(null, "url"), type = p.getAttributeValue(null, "type");
                    if (url != null && it.artUrl == null && (type == null || type.startsWith("image"))) it.artUrl = url;
                }
            } else if (ev == XmlPullParser.TEXT && it != null && tag != null) {
                String text = p.getText().trim();
                if (text.isEmpty()) continue;
                switch (tag) {
                    case "title": it.title = it.title == null ? text : it.title + text; break;
                    case "link": it.link = text; break;
                    case "description": it.summary = (it.summary == null ? "" : it.summary) + text; break;
                    case "pubDate": it.sub = source + "  ·  " + ago(t, text); break;
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if ("item".equals(p.getName()) && it != null) {
                    if (it.title != null) {
                        if (it.sub == null) it.sub = source;
                        if (it.summary != null) {
                            // Many feeds (ERT) put the story's photo in the text as <img>: use it, and
                            // drop it from the text (Android would show an "OBJ" placeholder there).
                            java.util.regex.Matcher img = java.util.regex.Pattern.compile("<img[^>]+src=\"([^\"]+)\"").matcher(it.summary);
                            if (it.artUrl == null && img.find()) it.artUrl = img.group(1);
                            it.summary = clean.apply(it.summary);
                        }
                        it.title = clean.apply(it.title);
                        out.add(it);
                    }
                    it = null;
                }
                tag = null;
            }
        }
        return out;
    }

    /** HTML to plain text, without images' placeholder characters or doubled spaces. */
    static String plain(String html) {
        String s = html.replaceAll("(?is)<img[^>]*>", "");
        s = android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_COMPACT).toString();
        return s.replace("￼", "").replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String ago(Txt t, String rfc822) {
        try {
            Date d = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).parse(rfc822);
            return Watched.ago(t, System.currentTimeMillis() - d.getTime());
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- cache

    private static void save(File f, List<Item> items) {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) {
                a.put(new JSONObject().put("kind", it.kind).put("title", it.title).put("sub", it.sub)
                        .put("summary", it.summary).put("link", it.link).put("art", it.artUrl).put("tall", it.tall));
            }
            // Write beside it and rename, so a reader (or a second fetch) never sees half a file.
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
        } catch (Exception ignored) { }
    }

    private static List<Item> load(File f) {
        List<Item> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (InputStream in = new java.io.FileInputStream(f)) {
            JSONArray a = new JSONArray(new String(read(in), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.kind = o.optString("kind");
                it.title = o.optString("title");
                it.sub = o.optString("sub", "");
                it.summary = o.isNull("summary") ? null : o.optString("summary");
                it.link = o.isNull("link") ? null : o.optString("link");
                it.artUrl = o.isNull("art") ? null : o.optString("art");
                it.tall = o.optBoolean("tall");
                out.add(it);
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---------------------------------------------------------------- network

    static String http(String url) throws java.io.IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(6000);
        con.setReadTimeout(10000);
        con.setRequestProperty("User-Agent", "Advanced-TV-Home");
        con.setInstanceFollowRedirects(true);
        try (InputStream in = con.getInputStream()) {
            return new String(read(in), StandardCharsets.UTF_8);
        } finally {
            con.disconnect();
        }
    }

    /** An image for the shelf, cached on disk by URL. */
    static android.graphics.Bitmap image(Context c, String url, int maxW) {
        if (url == null) return null;
        File f = new File(new File(c.getCacheDir(), "shelf"), Integer.toHexString(url.hashCode()) + ".img");
        try {
            byte[] data;
            if (f.exists()) data = read(new java.io.FileInputStream(f));
            else {
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(6000);
                con.setReadTimeout(15000);
                try (InputStream in = con.getInputStream()) { data = read(in); } finally { con.disconnect(); }
                f.getParentFile().mkdirs();
                File tmp = new File(f.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) { f.delete(); return null; } // not an image (an error page, a cut-off download): fetch again next time
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= maxW) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] read(InputStream in) throws java.io.IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = is.read(b)) > 0; ) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
}
