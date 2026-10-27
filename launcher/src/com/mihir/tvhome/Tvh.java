package com.mihir.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Tvheadend client over its HTTP API: channel list, now/next EPG, channel logos and
 * ticketed stream URLs. Handles Digest (Tvheadend's default) and Basic auth. Plain java.net, no deps.
 */
final class Tvh {

    static final String PREFS = "tvh";

    static final class Channel {
        String uuid, name, icon;
        double number;
        Event now;

        String numberLabel() {
            if (number <= 0) return "";
            return number == Math.floor(number) ? String.valueOf((long) number) : String.valueOf(number);
        }
    }

    static final class Event {
        String title, subtitle;
        long start, stop; // epoch seconds

        float progress(long nowSec) {
            if (stop <= start) return 0f;
            return Math.max(0f, Math.min(1f, (nowSec - start) / (float) (stop - start)));
        }
    }

    /** Background work for every Tvheadend call; small pool so logos load in parallel. */
    static final ExecutorService IO = Executors.newFixedThreadPool(3);

    /** Last channel list loaded, shared by the home screen and the player for zapping. */
    static volatile List<Channel> channels = Collections.emptyList();

    private static final LruCache<String, Bitmap> logos = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private final String base, user, pass;
    private String scheme, realm, nonce, opaque, qop, algorithm;
    private int nc;

    private Tvh(String base, String user, String pass) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.user = user;
        this.pass = pass;
    }

    private static Tvh instance;

    /** The configured server, or null when Live TV hasn't been set up. */
    static synchronized Tvh get(Context c) {
        SharedPreferences p = prefs(c);
        String url = p.getString("url", ""), user = p.getString("user", ""), pass = p.getString("pass", "");
        if (url.isEmpty()) return null;
        if (instance == null || !instance.base.equals(url.replaceAll("/$", ""))
                || !instance.user.equals(user) || !instance.pass.equals(pass)) {
            instance = new Tvh(url, user, pass);
        }
        return instance;
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- API

    /** Enabled channels sorted by number, with what's on now filled in. */
    List<Channel> loadChannels() throws IOException {
        JSONArray rows = json("/api/channel/grid?limit=5000").optJSONArray("entries");
        List<Channel> out = new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null || !o.optBoolean("enabled", true)) continue;
            Channel ch = new Channel();
            ch.uuid = o.optString("uuid");
            ch.name = o.optString("name");
            ch.number = o.optDouble("number", 0);
            String icon = o.optString("icon_public_url", "");
            ch.icon = icon.isEmpty() ? null : icon;
            out.add(ch);
        }
        Collections.sort(out, (a, b) -> {
            // Numbered channels first, by number; then the rest by name.
            if ((a.number > 0) != (b.number > 0)) return a.number > 0 ? -1 : 1;
            if (a.number != b.number) return Double.compare(a.number, b.number);
            return a.name.compareToIgnoreCase(b.name);
        });
        fillNow(out);
        channels = out;
        return out;
    }

    /** Refresh the "now" programme of every channel in one request. */
    void fillNow(List<Channel> list) throws IOException {
        JSONArray rows = json("/api/epg/events/grid?mode=now&limit=5000").optJSONArray("entries");
        Map<String, Event> now = new HashMap<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o != null) now.put(o.optString("channelUuid"), event(o));
        }
        for (Channel ch : list) ch.now = now.get(ch.uuid);
    }

    /** The current and next few programmes on one channel. */
    List<Event> upcoming(String channelUuid, int count) throws IOException {
        JSONArray rows = json("/api/epg/events/grid?limit=" + count + "&channel=" + enc(channelUuid))
                .optJSONArray("entries");
        List<Event> out = new ArrayList<>();
        long nowSec = System.currentTimeMillis() / 1000;
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            Event e = event(rows.optJSONObject(i));
            if (e.stop > nowSec) out.add(e);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    /**
     * A playable URL for the channel. Tvheadend's per-channel playlist hands back a stream link
     * carrying a short-lived ticket, so the player itself never needs the password.
     * Profile "pass" sends the broadcast untouched so the box decodes it in hardware.
     */
    String streamUrl(String channelUuid) throws IOException {
        String m3u = new String(get("/playlist/ticket/channel/" + enc(channelUuid) + "?profile=pass"), StandardCharsets.UTF_8);
        for (String line : m3u.split("\r?\n")) {
            line = line.trim();
            if (line.startsWith("http")) {
                if (!line.contains("profile=")) line += (line.contains("?") ? "&" : "?") + "profile=pass";
                return line;
            }
        }
        throw new IOException("Tvheadend returned no stream for this channel");
    }

    /** Channel logo, scaled to at most maxW wide; memory cache, then disk cache, then network. */
    Bitmap logo(Context c, Channel ch, int maxW) {
        if (ch.icon == null) return null;
        Bitmap bm = logos.get(ch.icon);
        if (bm != null) return bm;
        File f = new File(new File(c.getCacheDir(), "logos"), Integer.toHexString(ch.icon.hashCode()) + ".img");
        byte[] data = null;
        try {
            if (f.exists()) data = readAll(new java.io.FileInputStream(f));
            else {
                data = ch.icon.startsWith("http") ? download(ch.icon) : get("/" + ch.icon.replaceFirst("^/", ""));
                f.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
            }
        } catch (IOException e) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= maxW) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (bm != null) logos.put(ch.icon, bm);
        return bm;
    }

    /** Quick credentials/server check for the setup screen; returns null when fine, else a message. */
    String test() {
        try {
            json("/api/channel/grid?limit=1");
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    // ---------------------------------------------------------------- HTTP

    private JSONObject json(String path) throws IOException {
        try {
            return new JSONObject(new String(get(path), StandardCharsets.UTF_8));
        } catch (org.json.JSONException e) {
            throw new IOException("Unexpected reply from Tvheadend");
        }
    }

    /** GET with auth; answers a fresh challenge once (new or stale nonce) before giving up. */
    synchronized byte[] get(String path) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection con = (HttpURLConnection) new URL(base + path).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(15000);
            con.setInstanceFollowRedirects(false);
            String auth = authorization(path);
            if (auth != null) con.setRequestProperty("Authorization", auth);
            try {
                int code = con.getResponseCode();
                if (code == 401) {
                    // First 401: no nonce yet, or ours expired. Take the fresh one and retry once.
                    parseChallenge(con.getHeaderField("WWW-Authenticate"));
                    if (user.isEmpty()) throw new IOException("Tvheadend needs a username and password");
                    if (attempt == 0) continue;
                    throw new IOException("Tvheadend rejected the username or password");
                }
                if (code == 403) throw new IOException("This Tvheadend user isn't allowed to do that");
                if (code != 200) throw new IOException("Tvheadend replied HTTP " + code);
                return readAll(con.getInputStream());
            } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
                throw new IOException("Can't reach Tvheadend at " + base);
            } finally {
                con.disconnect();
            }
        }
        throw new IOException("Tvheadend rejected the username or password");
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(6000);
        con.setReadTimeout(15000);
        try {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            return readAll(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }

    private static final Pattern PARAM = Pattern.compile("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))");

    private void parseChallenge(String h) {
        if (h == null) return;
        String lower = h.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("basic")) { scheme = "basic"; nonce = null; return; }
        scheme = "digest";
        realm = nonce = opaque = qop = algorithm = null;
        Matcher m = PARAM.matcher(h.substring(h.indexOf(' ') + 1));
        while (m.find()) {
            String k = m.group(1).toLowerCase(Locale.ROOT), v = m.group(2) != null ? m.group(2) : m.group(3);
            switch (k) {
                case "realm": realm = v; break;
                case "nonce": nonce = v; break;
                case "opaque": opaque = v; break;
                case "qop": qop = v; break;
                case "algorithm": algorithm = v; break;
            }
        }
        nc = 0;
    }

    private String authorization(String uri) {
        if (user.isEmpty() || scheme == null) return null;
        if ("basic".equals(scheme)) {
            return "Basic " + Base64.encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }
        if (nonce == null) return null;
        String alg = algorithm == null ? "MD5" : algorithm;
        String hashAlg = alg.toUpperCase(Locale.ROOT).startsWith("SHA-256") ? "SHA-256" : "MD5";
        String ha1 = hex(hashAlg, user + ":" + realm + ":" + pass);
        String ha2 = hex(hashAlg, "GET:" + uri);
        StringBuilder h = new StringBuilder("Digest username=\"").append(user)
                .append("\", realm=\"").append(realm)
                .append("\", nonce=\"").append(nonce)
                .append("\", uri=\"").append(uri)
                .append("\", algorithm=").append(alg);
        if (qop != null && qop.contains("auth")) {
            String ncs = String.format(Locale.ROOT, "%08x", ++nc);
            byte[] r = new byte[8];
            new SecureRandom().nextBytes(r);
            String cnonce = hexBytes(r);
            h.append(", qop=auth, nc=").append(ncs).append(", cnonce=\"").append(cnonce)
                    .append("\", response=\"").append(hex(hashAlg, ha1 + ":" + nonce + ":" + ncs + ":" + cnonce + ":auth:" + ha2)).append('"');
        } else {
            h.append(", response=\"").append(hex(hashAlg, ha1 + ":" + nonce + ":" + ha2)).append('"');
        }
        if (opaque != null) h.append(", opaque=\"").append(opaque).append('"');
        return h.toString();
    }

    private static String hex(String alg, String s) {
        try {
            return hexBytes(MessageDigest.getInstance(alg).digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hexBytes(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static Event event(JSONObject o) {
        Event e = new Event();
        e.title = o.optString("title", "");
        String sub = o.optString("subtitle", "");
        e.subtitle = sub.isEmpty() ? null : sub;
        e.start = o.optLong("start");
        e.stop = o.optLong("stop");
        return e;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = is.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
