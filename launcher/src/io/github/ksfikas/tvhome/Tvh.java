package io.github.ksfikas.tvhome;

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
        boolean radio;
        Event now;
        // Channels from other sources (see Sources): a network tuner's direct stream URL, or an
        // Android TV input (a tuner app on the box) played through TvView.
        String url, inputId;
        long tifId;

        /** From Tvheadend: has a guide, recordings, stream status. */
        boolean fromTvh() { return url == null && inputId == null; }

        String numberLabel() {
            if (number <= 0) return "";
            return number == Math.floor(number) ? String.valueOf((long) number) : String.valueOf(number);
        }
    }

    static final class Event {
        long id;
        String channelUuid, title, subtitle, description;
        String dvrUuid, dvrState; // set when a recording is scheduled/running for this programme
        long start, stop; // epoch seconds

        float progress(long nowSec) {
            if (stop <= start) return 0f;
            return Math.max(0f, Math.min(1f, (nowSec - start) / (float) (stop - start)));
        }

        boolean isOn(long nowSec) { return start <= nowSec && nowSec < stop; }

        boolean recordingSet() {
            return dvrUuid != null && dvrState != null
                    && (dvrState.startsWith("scheduled") || dvrState.startsWith("recording"));
        }
    }

    static final class Recording {
        String uuid, title, subtitle, channel, schedStatus, status;
        long start, stop, size;

        boolean scheduled() { return schedStatus.startsWith("scheduled"); }
        boolean recording() { return schedStatus.startsWith("recording"); }
        boolean playable() { return size > 0 && (recording() || schedStatus.startsWith("completed")); }
        boolean failed() { return schedStatus.contains("Error") || schedStatus.contains("Failed") || "missed".equals(schedStatus); }
    }

    // Where each recording was left off, so it can carry on ("Continue watching").
    static final String RESUME = "resume";

    static SharedPreferences resume(Context c) {
        return c.getApplicationContext().getSharedPreferences(RESUME, Context.MODE_PRIVATE);
    }

    /** Remember (or forget, near the start or end) where a recording was stopped. */
    static void saveResume(Context c, String uuid, String title, long posMs, long durMs) {
        SharedPreferences.Editor e = resume(c).edit();
        boolean keep = posMs > 60_000 && durMs > 0 && posMs < durMs * 0.95;
        if (keep) e.putLong(uuid, posMs).putString("last", uuid).putString("last_title", title)
                .putInt("last_pct", (int) (posMs * 100 / durMs));
        else {
            e.remove(uuid);
            if (uuid.equals(resume(c).getString("last", null))) e.remove("last").remove("last_title").remove("last_pct");
        }
        e.apply();
    }

    /** Background work for every Tvheadend call; small pool so logos load in parallel. */
    static final ExecutorService IO = Executors.newFixedThreadPool(3);

    /** Last channel list loaded (TV and radio), shared by the home screen, guide and player. */
    static volatile List<Channel> channels = Collections.emptyList();

    static List<Channel> ofKind(boolean radio) {
        List<Channel> out = new ArrayList<>();
        for (Channel ch : channels) if (ch.radio == radio) out.add(ch);
        return out;
    }

    static Channel find(String uuid) {
        if (uuid != null) for (Channel ch : channels) if (uuid.equals(ch.uuid)) return ch;
        return null;
    }

    /** Lower case without accents, so "σκαι" finds "ΣΚΑΪ" and "mega" finds "MEGA HD". */
    static String fold(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Channels for a typed query: digits match the start of a channel number ("7" finds 7, 70, 700...),
     * anything else matches inside the name. Names that start with the text come first. At most max.
     */
    static List<Channel> matchChannels(List<Channel> all, String query, int max) {
        List<Channel> starts = new ArrayList<>(), inside = new ArrayList<>();
        String q = fold(query);
        if (q.isEmpty()) return starts;
        boolean digits = q.matches("\\d+");
        for (Channel ch : all) {
            if (digits) {
                if (ch.number > 0 && ch.numberLabel().startsWith(q)) starts.add(ch);
                continue;
            }
            String name = fold(ch.name);
            if (name.startsWith(q)) starts.add(ch);
            else if (name.contains(q)) inside.add(ch);
        }
        starts.addAll(inside);
        return starts.size() > max ? new ArrayList<>(starts.subList(0, max)) : starts;
    }

    // Favourites: channel uuids in the user's order, kept on the box.
    static List<String> favourites(Context c) {
        String s = prefs(c).getString("favourites", "");
        List<String> out = new ArrayList<>();
        for (String u : s.split(",")) if (!u.isEmpty()) out.add(u);
        return out;
    }

    static void setFavourites(Context c, List<String> uuids) {
        prefs(c).edit().putString("favourites", android.text.TextUtils.join(",", uuids)).apply();
    }

    private static final LruCache<String, Bitmap> logos = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private final String base, user, pass;
    /** Text for messages shown to the user; refreshed on every {@link #get(Context)}. */
    private volatile Txt txt;
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
        instance.txt = Txt.of(c);
        return instance;
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- API

    /** Enabled channels sorted by number, with what's on now filled in. */
    List<Channel> loadChannels() throws IOException {
        // Tvheadend tags radio services itself ("Radio channels"); that tag is how radio is told apart.
        java.util.Set<String> radioTags = new java.util.HashSet<>();
        JSONArray tags = json("/api/channeltag/grid?limit=500").optJSONArray("entries");
        if (tags != null) for (int i = 0; i < tags.length(); i++) {
            JSONObject t = tags.optJSONObject(i);
            if (t != null && t.optString("name").toLowerCase(Locale.ROOT).contains("radio")) radioTags.add(t.optString("uuid"));
        }
        JSONArray rows = json("/api/channel/grid?limit=5000").optJSONArray("entries");
        List<Channel> out = new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null || !o.optBoolean("enabled", true)) continue;
            Channel ch = new Channel();
            ch.uuid = o.optString("uuid");
            ch.name = o.optString("name");
            ch.number = o.optDouble("number", 0);
            ch.icon = usableIcon(o.optString("icon_public_url", ""));
            JSONArray ct = o.optJSONArray("tags");
            if (ct != null) for (int j = 0; j < ct.length(); j++) if (radioTags.contains(ct.optString(j))) ch.radio = true;
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

    /**
     * A channel's logo link, or null. Tvheadend serves logos as a full URL, "imagecache/…" or
     * "picon/…"; a bare "/1_0_…png" is a picon name with no picon path set, which can't load.
     */
    static String usableIcon(String publicUrl) {
        if (publicUrl == null || publicUrl.isEmpty()) return null;
        if (publicUrl.matches("/?\\d+_\\d+_[0-9A-Fa-f_]+\\.png")) return null;
        return publicUrl;
    }

    /** Refresh the "now" programme of every channel in one request. */
    void fillNow(List<Channel> list) throws IOException {
        JSONArray rows = json("/api/epg/events/grid?mode=now&limit=5000").optJSONArray("entries");
        Map<String, Event> now = new HashMap<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o != null) now.put(o.optString("channelUuid"), event(o));
        }
        for (Channel ch : list) if (ch.fromTvh()) ch.now = now.get(ch.uuid);
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

    /** Every programme overlapping [from, to) (epoch seconds), grouped by channel uuid, sorted by start. */
    Map<String, List<Event>> window(long from, long to) throws IOException {
        String filter = "[{\"field\":\"stop\",\"type\":\"numeric\",\"value\":" + from + ",\"comparison\":\"gt\"},"
                + "{\"field\":\"start\",\"type\":\"numeric\",\"value\":" + to + ",\"comparison\":\"lt\"}]";
        JSONArray rows = json("/api/epg/events/grid?limit=20000&filter=" + enc(filter)).optJSONArray("entries");
        Map<String, List<Event>> out = new HashMap<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            Event e = event(o);
            List<Event> l = out.get(e.channelUuid);
            if (l == null) out.put(e.channelUuid, l = new ArrayList<>());
            l.add(e);
        }
        for (List<Event> l : out.values()) Collections.sort(l, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    /** What's on now and next on every channel: channel uuid → {now, next} (either may be null). */
    Map<String, Event[]> nowNext() throws IOException {
        long nowSec = System.currentTimeMillis() / 1000;
        Map<String, Event[]> out = new HashMap<>();
        for (Map.Entry<String, List<Event>> e : window(nowSec, nowSec + 4 * 3600).entrySet()) {
            Event[] nn = new Event[2];
            for (Event x : e.getValue()) {
                if (x.isOn(nowSec)) nn[0] = x;
                else if (x.start >= nowSec && nn[1] == null) nn[1] = x;
            }
            out.put(e.getKey(), nn);
        }
        return out;
    }

    /** Programmes still to air (or airing) whose title contains the text, soonest first. */
    List<Event> search(String text) throws IOException {
        JSONArray rows = json("/api/epg/events/grid?limit=300&title=" + enc(text)).optJSONArray("entries");
        List<Event> out = new ArrayList<>();
        long nowSec = System.currentTimeMillis() / 1000;
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            Event e = event(rows.optJSONObject(i));
            if (e.stop > nowSec) out.add(e);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    // ---------------------------------------------------------------- series

    static final class SeriesRule {
        String uuid, title, channel;
    }

    /** "Record every episode": Tvheadend matches this title on this channel from now on. */
    void recordSeries(long eventId) throws IOException {
        get("/api/dvr/autorec/create_by_series?event_id=" + eventId + "&config_uuid=");
    }

    List<SeriesRule> seriesRules() throws IOException {
        JSONArray rows = json("/api/dvr/autorec/grid?limit=500").optJSONArray("entries");
        List<SeriesRule> out = new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            SeriesRule r = new SeriesRule();
            r.uuid = o.optString("uuid");
            r.title = o.optString("name", "");
            if (r.title.isEmpty()) r.title = o.optString("title", "").replace("\\", "");
            Channel ch = find(o.optString("channel", ""));
            r.channel = ch == null ? txt.s(R.string.tvh_any_channel) : ch.name;
            out.add(r);
        }
        return out;
    }

    /** Stop a series; its scheduled episodes go with it, finished recordings stay. */
    void deleteSeries(String uuid) throws IOException {
        get("/api/idnode/delete?uuid=" + enc(uuid));
    }

    // ---------------------------------------------------------------- status

    /** Tuner and stream figures for the stream info overlay; any part may be missing. */
    static final class Status {
        String tuner, signal, snr, errors;
        long bitrate; // bits per second into Tvheadend for this channel, 0 if unknown
    }

    Status status(String channelName) throws IOException {
        Status s = new Status();
        JSONArray subs = json("/api/status/subscriptions").optJSONArray("entries");
        if (subs != null) for (int i = 0; i < subs.length(); i++) {
            JSONObject o = subs.optJSONObject(i);
            if (o != null && channelName.equals(o.optString("channel"))) s.bitrate = Math.max(s.bitrate, o.optLong("in") * 8);
        }
        JSONArray inputs = json("/api/status/inputs").optJSONArray("entries");
        if (inputs != null) for (int i = 0; i < inputs.length(); i++) {
            JSONObject o = inputs.optJSONObject(i);
            if (o == null || o.optInt("subs") == 0) continue;
            s.tuner = o.optString("input").replaceAll("\\s*#\\d+", "").replace(" : ", " · ");
            s.signal = level(o.optInt("signal"), o.optInt("signal_scale"), "dBm");
            s.snr = level(o.optInt("snr"), o.optInt("snr_scale"), "dB");
            s.errors = "BER " + o.optInt("ber") + " · UNC " + o.optInt("unc") + " · CC " + o.optInt("cc");
            if (s.bitrate == 0) s.bitrate = o.optLong("bps");
            break;
        }
        return s;
    }

    /** Tvheadend scale: 1 = relative 0–65535, 2 = thousandths of a decibel. */
    static String level(int v, int scale, String unit) {
        if (scale == 1) return Math.round(v * 100.0 / 65535) + " %";
        if (scale == 2) return String.format(Locale.ROOT, "%.1f %s", v / 1000.0, unit);
        return null;
    }

    /** Recording storage {free, total} in bytes, from Tvheadend's status feed; null if not reported. */
    // ---------------------------------------------------------------- server status and management (Control Center → Tvheadend)

    /** Everything the status screen shows in one go. The inputs/subscriptions pages need an admin user. */
    ServerStatus serverStatus() throws IOException {
        JSONObject inputs = json("/api/status/inputs"), subs = json("/api/status/subscriptions");
        List<Recording> recs = new ArrayList<>();
        try { recs = recordings(); } catch (IOException ignored) { } // the rest is still worth showing
        long[] disk = null;
        try { disk = diskSpace(); } catch (IOException ignored) { }
        String version = "";
        try { version = json("/api/serverinfo").optString("sw_version"); } catch (IOException ignored) { }
        return ServerStatus.parse(inputs, subs, recs, disk, version);
    }

    /** One row of the channel manager: every channel Tvheadend has, enabled or not. */
    static final class ChannelRow {
        String uuid, name;
        int number;
        boolean enabled;
    }

    List<ChannelRow> allChannelRows() throws IOException {
        return parseChannelRows(json("/api/channel/grid?limit=5000"));
    }

    /** From /api/channel/grid: numbered channels first (by number), then the rest by name. */
    static List<ChannelRow> parseChannelRows(JSONObject grid) {
        List<ChannelRow> out = new ArrayList<>();
        JSONArray rows = grid == null ? null : grid.optJSONArray("entries");
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null || o.optString("uuid").isEmpty()) continue;
            ChannelRow r = new ChannelRow();
            r.uuid = o.optString("uuid");
            r.name = o.optString("name");
            r.number = o.optInt("number");
            r.enabled = o.optBoolean("enabled", true);
            out.add(r);
        }
        java.util.Collections.sort(out, (a, b) -> {
            if ((a.number > 0) != (b.number > 0)) return a.number > 0 ? -1 : 1;
            if (a.number != b.number) return Integer.compare(a.number, b.number);
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    void setChannelEnabled(String uuid, boolean on) throws IOException {
        saveNode(uuid, "enabled", on);
    }

    void setChannelNumber(String uuid, int number) throws IOException {
        saveNode(uuid, "number", number);
    }

    private void saveNode(String uuid, String field, Object value) throws IOException {
        try {
            get("/api/idnode/save?node=" + enc(new JSONObject().put("uuid", uuid).put(field, value).toString()));
        } catch (org.json.JSONException e) {
            throw new IOException(txt.s(R.string.tvh_bad_reply));
        }
    }

    /** Ask the tuner to collect the over-the-air programme guide now instead of at the nightly time. */
    void grabGuideNow() throws IOException {
        get("/api/epggrab/ota/trigger?trigger=1");
    }

    /** Start a scan of every DVB network for new channels; returns how many networks were asked. */
    int rescanChannels() throws IOException {
        JSONArray nets = json("/api/mpegts/network/grid").optJSONArray("entries");
        int n = 0;
        if (nets != null) for (int i = 0; i < nets.length(); i++) {
            JSONObject o = nets.optJSONObject(i);
            if (o == null || o.optString("uuid").isEmpty()) continue;
            get("/api/mpegts/network/scan?uuid=" + enc(o.optString("uuid")));
            n++;
        }
        return n;
    }

    long[] diskSpace() throws IOException {
        JSONArray msgs = json("/comet/poll").optJSONArray("messages");
        if (msgs != null) for (int i = 0; i < msgs.length(); i++) {
            JSONObject o = msgs.optJSONObject(i);
            if (o != null && o.has("totaldiskspace") && o.optLong("totaldiskspace") > 0) {
                return new long[]{o.optLong("freediskspace"), o.optLong("totaldiskspace")};
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- recordings

    /** All DVR entries: recording now, scheduled, finished and failed. */
    List<Recording> recordings() throws IOException {
        JSONArray rows = json("/api/dvr/entry/grid?limit=2000").optJSONArray("entries");
        List<Recording> out = new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            Recording r = new Recording();
            r.uuid = o.optString("uuid");
            r.title = o.optString("disp_title", "");
            String sub = o.optString("disp_subtitle", "").trim();
            r.subtitle = sub.isEmpty() ? null : sub;
            r.channel = o.optString("channelname", "");
            r.schedStatus = o.optString("sched_status", "");
            r.status = o.optString("status", "");
            r.start = o.optLong("start");
            r.stop = o.optLong("stop");
            r.size = o.optLong("filesize");
            out.add(r);
        }
        return out;
    }

    /** Record a programme from the guide; Tvheadend's default recording profile applies. */
    void record(long eventId) throws IOException {
        get("/api/dvr/entry/create_by_event?event_id=" + eventId + "&config_uuid=");
    }

    /** Scheduled: cancel it. Recording now: stop it (keeps what's recorded). */
    void stopOrCancel(String dvrUuid, boolean running) throws IOException {
        get("/api/dvr/entry/" + (running ? "stop" : "cancel") + "?uuid=" + enc(dvrUuid));
    }

    /** Delete a recording and its file from the server. */
    void deleteRecording(String uuid) throws IOException {
        get("/api/dvr/entry/remove?uuid=" + enc(uuid));
    }

    String recordingUrl(String uuid) { return base + "/dvrfile/" + uuid; }

    /**
     * Authorization header for a request the player makes itself (recordings, which need seeking).
     * Primes a Digest nonce first if we haven't talked to the server yet.
     */
    String authHeader(String url) throws IOException {
        synchronized (this) {
            if (scheme == null) get("/api/serverinfo");
            return authorization(url.startsWith(base) ? url.substring(base.length()) : url);
        }
    }

    /** The nonce expired (player got a 401): fetch a fresh one. */
    synchronized void refreshAuth() throws IOException {
        nonce = null;
        scheme = null;
        get("/api/serverinfo");
    }

    /**
     * A playable URL for the channel. Tvheadend's per-channel playlist hands back a stream link
     * carrying a short-lived ticket, so the player itself never needs the password.
     * Profile "pass" sends the broadcast untouched so the box decodes it in hardware.
     */
    String streamUrl(String channelUuid) throws IOException {
        String url = firstStream(new String(get("/playlist/ticket/channel/" + enc(channelUuid) + "?profile=pass"), StandardCharsets.UTF_8));
        if (url == null) throw new IOException(txt.s(R.string.tvh_no_stream));
        return url;
    }

    /** The first stream link in an M3U playlist, forced to the pass-through profile; null if none. */
    static String firstStream(String m3u) {
        for (String line : m3u.split("\r?\n")) {
            line = line.trim();
            if (line.startsWith("http")) {
                if (!line.contains("profile=")) line += (line.contains("?") ? "&" : "?") + "profile=pass";
                return line;
            }
        }
        return null;
    }

    /** Logo at this width if it's already in memory; never blocks (for drawing). */
    static Bitmap cachedLogo(String icon, int maxW) {
        return icon == null ? null : logos.get(icon + "@" + maxW);
    }

    /**
     * Channel logo scaled to maxW wide; memory cache (per size, so the guide's small logos don't
     * push out the home row's), then disk cache, then network.
     */
    Bitmap logo(Context c, Channel ch, int maxW) {
        return logo(c, this, ch, maxW);
    }

    /** Same, for any source: a web link, a TV input's logo (content://), or a path on Tvheadend (needs tvh). */
    static Bitmap logo(Context c, Tvh tvh, Channel ch, int maxW) {
        if (ch.icon == null) return null;
        String key = ch.icon + "@" + maxW;
        Bitmap bm = logos.get(key);
        if (bm != null) return bm;
        File f = new File(new File(c.getCacheDir(), "logos"), Integer.toHexString(ch.icon.hashCode()) + ".img");
        byte[] data = null;
        try {
            if (f.exists()) data = readAll(new java.io.FileInputStream(f));
            else {
                if (ch.icon.startsWith("content://")) {
                    InputStream in = c.getContentResolver().openInputStream(android.net.Uri.parse(ch.icon));
                    if (in == null) return null;
                    data = readAll(in);
                } else if (ch.icon.startsWith("http")) data = download(ch.icon);
                else if (tvh != null) data = tvh.get("/" + ch.icon.replaceFirst("^/", ""));
                else return null;
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
        if (bm == null) return null;
        // inSampleSize only halves; finish with an exact scale so no logo is kept larger than drawn.
        if (bm.getWidth() > maxW) {
            Bitmap s = Bitmap.createScaledBitmap(bm, maxW, Math.max(1, bm.getHeight() * maxW / bm.getWidth()), true);
            if (s != bm) bm.recycle();
            bm = s;
        }
        logos.put(key, bm);
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
            throw new IOException(txt.s(R.string.tvh_bad_reply));
        }
    }

    /** GET with auth; answers a fresh challenge (new or stale nonce) up to twice before giving up. */
    synchronized byte[] get(String path) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection con = (HttpURLConnection) new URL(base + path).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(15000);
            con.setInstanceFollowRedirects(false);
            String auth = authorization(path);
            if (auth != null) con.setRequestProperty("Authorization", auth);
            try {
                int code = con.getResponseCode();
                if (code == 401) {
                    // 401: no nonce yet, or ours expired. Take the fresh one and retry; twice, since right after
                    // a server restart the first fresh nonce can be refused too.
                    parseChallenge(con.getHeaderField("WWW-Authenticate"));
                    if (user.isEmpty()) throw new IOException(txt.s(R.string.tvh_needs_login));
                    if (attempt < 2) continue;
                    throw new IOException(txt.s(R.string.tvh_rejected_login));
                }
                if (code == 403) throw new IOException(txt.s(R.string.tvh_forbidden));
                if (code != 200) throw new IOException(txt.s(R.string.tvh_http_error, code));
                return readAll(con.getInputStream());
            } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
                throw new IOException(txt.s(R.string.tvh_unreachable, base));
            } finally {
                con.disconnect();
            }
        }
        throw new IOException(txt.s(R.string.tvh_rejected_login));
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
                    .append("\", response=\"").append(digestResponse(alg, user, realm, pass, "GET", uri, nonce, ncs, cnonce)).append('"');
        } else {
            h.append(", response=\"").append(digestResponse(alg, user, realm, pass, "GET", uri, nonce, null, null)).append('"');
        }
        if (opaque != null) h.append(", opaque=\"").append(opaque).append('"');
        return h.toString();
    }

    /** RFC 2617 / 7616 response hash; nc and cnonce are null when the server asked for no qop. */
    static String digestResponse(String alg, String user, String realm, String pass, String method, String uri,
                                 String nonce, String nc, String cnonce) {
        String hashAlg = alg.toUpperCase(Locale.ROOT).startsWith("SHA-256") ? "SHA-256" : "MD5";
        String ha1 = hex(hashAlg, user + ":" + realm + ":" + pass);
        String ha2 = hex(hashAlg, method + ":" + uri);
        return nc == null ? hex(hashAlg, ha1 + ":" + nonce + ":" + ha2)
                : hex(hashAlg, ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
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

    static Event event(JSONObject o) {
        Event e = new Event();
        e.id = o.optLong("eventId");
        e.channelUuid = o.optString("channelUuid");
        e.title = o.optString("title", "");
        String sub = o.optString("subtitle", "").trim();
        e.subtitle = sub.isEmpty() ? null : sub;
        String desc = o.optString("description", "");
        e.description = desc.isEmpty() ? null : desc;
        String dvr = o.optString("dvrUuid", "");
        e.dvrUuid = dvr.isEmpty() ? null : dvr;
        String state = o.optString("dvrState", "");
        e.dvrState = state.isEmpty() ? null : state;
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
