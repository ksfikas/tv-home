package io.github.ksfikas.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone remote: a tiny web page served by the box on the home network (port 8099). Off until
 * turned on in Inputs → Phone remote…; a phone pairs once with the 4-digit PIN shown on the TV.
 * Answers only private (LAN) addresses. It drives the launcher's own features (channels, guide,
 * recordings, the player, opening apps, volume); Android doesn't let it press keys in other apps.
 */
final class RemoteServer {
    private RemoteServer() { }

    static final int PORT = 8099;
    private static ServerSocket socket;
    private static final ExecutorService pool = Executors.newFixedThreadPool(6); // a few idle sockets must not block the remote
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("remote", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) { return prefs(c).getBoolean("on", false); }

    /** The pairing PIN, made on first use. */
    static String pin(Context c) {
        String p = prefs(c).getString("pin", null);
        if (p == null) {
            p = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
            prefs(c).edit().putString("pin", p).apply();
        }
        return p;
    }

    /** Turn on or off; turning off also forgets paired phones and picks a new PIN next time. */
    static void setEnabled(Context c, boolean on) {
        SharedPreferences.Editor e = prefs(c).edit().putBoolean("on", on);
        if (!on) e.remove("tokens").remove("pin");
        e.apply();
        if (on) start(c); else stop();
    }

    /** Start if turned on (called when the launcher starts); harmless to call again. */
    static synchronized void start(Context c) {
        if (socket != null || !enabled(c)) return;
        Context app = c.getApplicationContext();
        try {
            socket = new ServerSocket(PORT);
        } catch (IOException e) {
            return;
        }
        ServerSocket s = socket;
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket client = s.accept();
                    pool.execute(() -> serve(app, client));
                } catch (IOException ignored) { }
            }
        }, "RemoteServer");
        t.setDaemon(true);
        t.start();
    }

    /** The box's address on the home network, for the page link; null if offline. */
    static String address() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return "http://" + a.getHostAddress() + ":" + PORT;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    static synchronized void stop() {
        if (socket != null) { try { socket.close(); } catch (IOException ignored) { } socket = null; }
    }

    // ---------------------------------------------------------------- HTTP

    private static void serve(Context c, Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(8000);
            InetAddress from = s.getInetAddress();
            if (!(from.isSiteLocalAddress() || from.isLoopbackAddress() || from.isLinkLocalAddress())) return;
            InputStream in = s.getInputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int k = lines[i].indexOf(':');
                if (k > 0) headers.put(lines[i].substring(0, k).trim().toLowerCase(Locale.ROOT), lines[i].substring(k + 1).trim());
            }
            String path = req[1], query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
            Map<String, String> params = parse(query);
            int len = 0;
            try { len = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) { }
            boolean image = headers.getOrDefault("content-type", "").startsWith("image/");
            if (len > 0 && len < 65536 && !image) {
                byte[] body = new byte[len];
                int got = 0;
                while (got < len) { int n = in.read(body, got, len - got); if (n < 0) break; got += n; }
                params.putAll(parse(new String(body, 0, got, StandardCharsets.UTF_8)));
            }
            OutputStream out = s.getOutputStream();
            if (path.equals("/") || path.equals("/index.html")) {
                send(out, 200, "text/html; charset=utf-8", page(c));
                return;
            }
            if (path.equals("/api/pair")) {
                if (pairLocked()) {
                    send(out, 403, "application/json", err(c, "pair_locked", R.string.remote_pair_locked).toString());
                } else if (pin(c).equals(params.get("pin"))) {
                    pairMisses = 0;
                    String token = Long.toHexString(new SecureRandom().nextLong()) + Long.toHexString(new SecureRandom().nextLong());
                    Set<String> tokens = new HashSet<>(prefs(c).getStringSet("tokens", new HashSet<>()));
                    tokens.add(token);
                    prefs(c).edit().putStringSet("tokens", tokens).apply();
                    sendJson(out, new JSONObject().put("token", token));
                } else {
                    countMiss(c);
                    send(out, 403, "application/json", err(c, "wrong_pin", R.string.remote_wrong_pin).toString());
                }
                return;
            }
            String token = headers.get("x-token");
            if (token == null || !prefs(c).getStringSet("tokens", new HashSet<>()).contains(token)) {
                send(out, 401, "application/json", err(c, "pair_needed", R.string.remote_pair_needed).toString());
                return;
            }
            if (path.equals("/api/wallpaper") && image) {
                sendJson(out, saveWallpaper(c, in, len));
                return;
            }
            sendJson(out, api(c, path, params));
        } catch (Exception ignored) {
            // A dropped connection or a bad request: nothing to clean up.
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev3 = 0;
        for (int i = 0; i < 16384; i++) {
            int ch = in.read();
            if (ch < 0) return null;
            b.write(ch);
            prev3 = (prev3 << 8 | ch) & 0xFFFFFFFF;
            if (prev3 == 0x0D0A0D0A) return b.toString("UTF-8");
        }
        return null;
    }

    private static Map<String, String> parse(String s) {
        Map<String, String> m = new HashMap<>();
        for (String kv : s.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            try {
                m.put(URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), "UTF-8"),
                        e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden" : "Error";
        out.write(("HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static void sendJson(OutputStream out, Object json) throws IOException {
        send(out, 200, "application/json; charset=utf-8", json.toString());
    }

    private static String page(Context c) throws IOException {
        try (InputStream in = c.getAssets().open("remote.html")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    // ---------------------------------------------------------------- API

    private static Object api(Context c, String path, Map<String, String> p) throws Exception {
        Tvh tvh = Tvh.get(c);
        switch (path) {
            case "/api/state": {
                JSONObject o = new JSONObject();
                Tvh.Channel ch = Tvh.find(PlayerActivity.playing);
                o.put("playing", ch == null ? JSONObject.NULL : channel(ch));
                o.put("tvh", tvh != null);
                return o;
            }
            case "/api/channels": {
                if (Tvh.channels.isEmpty() && Sources.configured(c)) Sources.load(c);
                JSONArray a = new JSONArray();
                for (Tvh.Channel ch : Tvh.channels) a.put(channel(ch));
                return a;
            }
            case "/api/watch":
                start(c, new Intent(c, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_CHANNEL, p.get("ch")));
                return ok();
            case "/api/player":
                PlayerActivity.remote(p.get("do"), p.get("arg"));
                return ok();
            case "/api/home":
                start(c, new Intent(c, MainActivity.class));
                return ok();
            case "/api/sleep":
                if (!SleepTimer.canSleep(c)) return err(c, "sleep_perm", R.string.remote_sleep_perm);
                SleepTimer.set(c, Integer.parseInt(p.get("min")));
                return ok();
            case "/api/wallpapers": {
                // Info, or: do=clear (back to the built-in photos), do=mine / do=all (rotation).
                String d = p.get("do");
                if ("clear".equals(d)) {
                    for (java.io.File f : MainActivity.userWallpapers(c)) f.delete();
                    Tvh.prefs(c).edit().remove("wall_mine_only").apply();
                } else if ("mine".equals(d) || "all".equals(d)) {
                    Tvh.prefs(c).edit().putBoolean("wall_mine_only", "mine".equals(d)).apply();
                }
                return new JSONObject().put("count", MainActivity.userWallpapers(c).length)
                        .put("mineOnly", Tvh.prefs(c).getBoolean("wall_mine_only", false));
            }
            case "/api/power": {
                if ("off".equals(p.get("do"))) {
                    // Standby, like the sleep timer; HDMI-CEC usually turns the TV off with it.
                    if (!SleepTimer.canSleep(c)) return err(c, "off_perm", R.string.remote_off_perm);
                    ui.post(() -> SleepTimer.sleepNow(c));
                } else {
                    // Wake from standby (and, over HDMI-CEC, usually the TV): WakeActivity turns the screen on.
                    // A fresh task every time, so onCreate (and turnScreenOn) really runs again.
                    start(c, new Intent(c, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
                }
                return ok();
            }
            case "/api/volume": {
                AudioManager am = c.getSystemService(AudioManager.class);
                String d = p.get("do");
                int dir = "up".equals(d) ? AudioManager.ADJUST_RAISE : "down".equals(d) ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE;
                ui.post(() -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI));
                return ok();
            }
            case "/api/apps": {
                JSONArray a = new JSONArray();
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                List<ResolveInfo> apps = c.getPackageManager().queryIntentActivities(i, 0);
                for (ResolveInfo r : apps) {
                    if (r.activityInfo.packageName.equals(c.getPackageName())) continue;
                    a.put(new JSONObject().put("pkg", r.activityInfo.packageName)
                            .put("name", String.valueOf(r.loadLabel(c.getPackageManager()))));
                }
                return a;
            }
            case "/api/open": {
                Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(String.valueOf(p.get("pkg")));
                if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(String.valueOf(p.get("pkg")));
                if (i == null) return err(c, "open_fail", R.string.remote_open_fail);
                start(c, i);
                return ok();
            }
            case "/api/search": {
                if (tvh == null) return err(c, "needs_tvh_search", R.string.remote_needs_tvh_search);
                if (Tvh.channels.isEmpty()) Sources.load(c);
                JSONArray a = new JSONArray();
                long now = System.currentTimeMillis() / 1000;
                for (Tvh.Event e : tvh.search(String.valueOf(p.get("q")))) {
                    Tvh.Channel ch = Tvh.find(e.channelUuid);
                    if (ch == null) continue;
                    a.put(new JSONObject().put("id", e.id).put("title", e.title).put("subtitle", e.subtitle == null ? "" : e.subtitle)
                            .put("channel", ch.name).put("ch", ch.uuid).put("start", e.start).put("stop", e.stop)
                            .put("on", e.isOn(now)).put("rec", e.recordingSet()).put("remind", Reminders.isSet(c, e.id)));
                    if (a.length() >= 60) break;
                }
                return a;
            }
            case "/api/guide": {
                // What's on now and next on every channel: the Guide tab before you search.
                if (tvh == null) return err(c, "needs_tvh_guide", R.string.remote_needs_tvh_guide);
                if (Tvh.channels.isEmpty()) Sources.load(c);
                Map<String, Tvh.Event[]> nn = tvh.nowNext();
                JSONArray a = new JSONArray();
                for (Tvh.Channel ch : Tvh.channels) {
                    if (ch.radio) continue;
                    Tvh.Event[] e = nn.get(ch.uuid);
                    JSONObject o = channel(ch);
                    if (e != null && e[0] != null) o.put("nowEv", event(c, e[0]));
                    if (e != null && e[1] != null) o.put("nextEv", event(c, e[1]));
                    a.put(o);
                }
                return a;
            }
            case "/api/record":
                if (tvh == null) return err(c, "needs_tvh_record", R.string.remote_needs_tvh_record);
                if ("series".equals(p.get("kind"))) tvh.recordSeries(Long.parseLong(p.get("id")));
                else tvh.record(Long.parseLong(p.get("id")));
                return ok();
            case "/api/remind": {
                Tvh.Event e = new Tvh.Event();
                e.id = Long.parseLong(p.get("id"));
                e.channelUuid = p.get("ch");
                e.title = p.get("title");
                e.start = Long.parseLong(p.get("start"));
                if (Reminders.isSet(c, e.id)) Reminders.remove(c, e.id); else Reminders.add(c, e);
                return ok();
            }
            default:
                return err(c, "unknown", R.string.remote_unknown);
        }
    }

    private static final int MAX_PHOTO = 8 * 1024 * 1024, MAX_PHOTOS = 30;

    /** A photo from the phone (already shrunk there to TV size) becomes a wallpaper, shown at once. */
    private static JSONObject saveWallpaper(Context c, InputStream in, int len) throws IOException, JSONException {
        if (len <= 0 || len > MAX_PHOTO) return err(c, "photo_large", R.string.remote_photo_large);
        byte[] data = new byte[len];
        int got = 0;
        while (got < len) { int n = in.read(data, got, len - got); if (n < 0) break; got += n; }
        if (got != len) return err(c, "upload_interrupted", R.string.remote_upload_interrupted);
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(data, 0, len, o);
        if (o.outWidth <= 0) return err(c, "not_photo", R.string.remote_not_photo);
        java.io.File dir = new java.io.File(c.getFilesDir(), "wallpapers");
        dir.mkdirs();
        java.io.File[] have = MainActivity.userWallpapers(c);
        for (int i = 0; i <= have.length - MAX_PHOTOS; i++) have[i].delete(); // keep the newest 30
        java.io.File f = new java.io.File(dir, System.currentTimeMillis() + ".jpg");
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { fo.write(data); }
        MainActivity.wallpaperAdded(f.getAbsolutePath());
        return new JSONObject().put("ok", true).put("count", MainActivity.userWallpapers(c).length);
    }

    private static JSONObject event(Context c, Tvh.Event e) throws JSONException {
        return new JSONObject().put("id", e.id).put("title", e.title).put("start", e.start).put("stop", e.stop)
                .put("rec", e.recordingSet()).put("remind", Reminders.isSet(c, e.id));
    }

    private static JSONObject channel(Tvh.Channel ch) throws JSONException {
        JSONObject o = new JSONObject().put("uuid", ch.uuid).put("name", ch.name).put("number", ch.numberLabel())
                .put("radio", ch.radio);
        if (ch.now != null) o.put("now", ch.now.title).put("start", ch.now.start).put("stop", ch.now.stop);
        return o;
    }

    /** Activities from the server thread: allowed because the launcher may draw over other apps. */
    private static void start(Context c, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ui.post(() -> c.startActivity(i));
    }

    private static JSONObject ok() throws JSONException { return new JSONObject().put("ok", true); }

    // Four digits are only 10,000 guesses: after a few wrong ones, refuse pairing for a minute and change the PIN.
    private static final int MAX_MISSES = 5;
    private static final long LOCK_MS = 60_000L;
    private static int pairMisses;
    private static long lockedUntil;

    private static synchronized boolean pairLocked() {
        return SystemClock.elapsedRealtime() < lockedUntil;
    }

    private static synchronized void countMiss(Context c) {
        if (++pairMisses < MAX_MISSES) return;
        pairMisses = 0;
        lockedUntil = SystemClock.elapsedRealtime() + LOCK_MS;
        prefs(c).edit().remove("pin").apply(); // the next pin(c) makes a new one
    }

    /** An error for the page: text in the TV language plus a short code the page translates into the phone language. */
    private static JSONObject err(Context c, String code, int res) throws JSONException {
        return new JSONObject().put("error", c.getString(res)).put("code", code);
    }
}
