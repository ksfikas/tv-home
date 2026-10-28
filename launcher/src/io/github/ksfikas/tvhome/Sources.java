package io.github.ksfikas.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Where Live TV channels come from: the Tvheadend server, a network tuner (HDHomeRun and
 * compatibles: found on the network or by address, streams played by our own player), and tuner
 * apps on the box itself (Android TV inputs, e.g. a USB stick's driver app, played via TvView).
 * Settings live in the "tvh" prefs: url/user/pass (Tvheadend), "hdhr" ("", "auto" or an address),
 * "tif" (true to include the box's TV inputs).
 */
final class Sources {
    private Sources() { }

    static String hdhr(Context c) { return Tvh.prefs(c).getString("hdhr", "").trim(); }

    static boolean tvInputs(Context c) { return Tvh.prefs(c).getBoolean("tif", false); }

    /** Any source set up at all. */
    static boolean configured(Context c) {
        return Tvh.get(c) != null || !hdhr(c).isEmpty() || tvInputs(c);
    }

    /**
     * Every channel from every source, Tvheadend's first; sets Tvh.channels. A source that fails is
     * skipped as long as another one answers; if none does, the first error is thrown.
     */
    static List<Tvh.Channel> load(Context c) throws IOException {
        List<Tvh.Channel> tvh = Collections.emptyList(), extra = new ArrayList<>();
        IOException error = null;
        Tvh server = Tvh.get(c);
        if (server != null) {
            try { tvh = server.loadChannels(); } catch (IOException e) { error = e; }
        }
        String h = hdhr(c);
        if (!h.isEmpty()) {
            try {
                for (String base : "auto".equalsIgnoreCase(h) ? discover(1500) : Collections.singletonList(baseUrl(h))) {
                    extra.addAll(hdhomerun(base));
                }
            } catch (IOException e) { if (error == null) error = e; }
        }
        if (tvInputs(c)) extra.addAll(tvInputChannels(c));
        if (tvh.isEmpty() && extra.isEmpty() && error != null) throw error;
        List<Tvh.Channel> all = merge(tvh, extra);
        Tvh.channels = all;
        return all;
    }

    /**
     * Tvheadend's channels, then the others that Tvheadend doesn't already have (same name,
     * ignoring case, spaces and an "HD" suffix): Tvheadend's copy has the guide and recordings.
     */
    static List<Tvh.Channel> merge(List<Tvh.Channel> primary, List<Tvh.Channel> extra) {
        List<Tvh.Channel> out = new ArrayList<>(primary);
        Set<String> have = new HashSet<>();
        for (Tvh.Channel ch : primary) have.add(key(ch.name));
        List<Tvh.Channel> add = new ArrayList<>();
        for (Tvh.Channel ch : extra) if (have.add(key(ch.name))) add.add(ch);
        Collections.sort(add, (a, b) -> {
            if ((a.number > 0) != (b.number > 0)) return a.number > 0 ? -1 : 1;
            if (a.number != b.number) return Double.compare(a.number, b.number);
            return a.name.compareToIgnoreCase(b.name);
        });
        out.addAll(add);
        return out;
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("\\s+hd$", "").replaceAll("[^\\p{L}\\p{N}]", "");
    }

    // ---------------------------------------------------------------- network tuner (HDHomeRun)

    static String baseUrl(String address) {
        String a = address.trim().replaceAll("/+$", "");
        return a.startsWith("http") ? a : "http://" + a;
    }

    /** Ask the local network who's a tuner (HDHomeRun discovery, UDP 65001); their base URLs. */
    static List<String> discover(int timeoutMs) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(300);
            byte[] req = discoverRequest();
            s.send(new DatagramPacket(req, req.length, InetAddress.getByName("255.255.255.255"), 65001));
            long until = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < until) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { continue; }
                byte[] data = java.util.Arrays.copyOf(p.getData(), p.getLength());
                String base = parseDiscoverReply(data);
                if (base == null && isDiscoverReply(data)) base = "http://" + p.getAddress().getHostAddress();
                if (base != null) found.add(base);
            }
        }
        if (found.isEmpty()) throw new IOException("No network tuner found");
        return new ArrayList<>(found);
    }

    /** Discover request: any tuner, any device id, CRC32 at the end (little-endian). */
    static byte[] discoverRequest() {
        ByteBuffer b = ByteBuffer.allocate(4 + 12 + 4);
        b.putShort((short) 0x0002).putShort((short) 12);
        b.put((byte) 0x01).put((byte) 4).putInt(0x00000001); // device type: tuner
        b.put((byte) 0x02).put((byte) 4).putInt(0xFFFFFFFF); // device id: any
        byte[] body = java.util.Arrays.copyOf(b.array(), 16);
        CRC32 crc = new CRC32();
        crc.update(body);
        b.order(ByteOrder.LITTLE_ENDIAN).putInt((int) crc.getValue());
        return b.array();
    }

    private static boolean isDiscoverReply(byte[] d) {
        return d.length >= 8 && d[0] == 0 && d[1] == 3 && crcOk(d);
    }

    private static boolean crcOk(byte[] d) {
        CRC32 crc = new CRC32();
        crc.update(d, 0, d.length - 4);
        int want = ByteBuffer.wrap(d, d.length - 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return (int) crc.getValue() == want;
    }

    /** The base URL tag (0x2A) of a discover reply; null if it isn't a valid reply or has none. */
    static String parseDiscoverReply(byte[] d) {
        if (!isDiscoverReply(d)) return null;
        int len = ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        int i = 4, end = Math.min(d.length - 4, 4 + len);
        while (i + 2 <= end) {
            int tag = d[i++] & 0xFF, l = d[i++] & 0xFF;
            if ((l & 0x80) != 0) l = (l & 0x7F) | ((d[i++] & 0xFF) << 7);
            if (i + l > end) break;
            if (tag == 0x2A) return new String(d, i, l, StandardCharsets.UTF_8).replaceAll("/+$", "");
            i += l;
        }
        return null;
    }

    /** A tuner's channels: discover.json gives the device id and the lineup link. */
    static List<Tvh.Channel> hdhomerun(String base) throws IOException {
        try {
            JSONObject dev = new JSONObject(http(base + "/discover.json"));
            String lineup = dev.optString("LineupURL", base + "/lineup.json");
            return parseLineup(http(lineup), dev.optString("DeviceID", base));
        } catch (JSONException e) {
            throw new IOException("Unexpected reply from the network tuner");
        }
    }

    /** lineup.json → channels; DRM-protected ones are left out (they can't play). */
    static List<Tvh.Channel> parseLineup(String json, String deviceId) throws JSONException {
        JSONArray rows = new JSONArray(json);
        List<Tvh.Channel> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.getJSONObject(i);
            if (o.optInt("DRM", 0) == 1 || o.optString("URL", "").isEmpty()) continue;
            Tvh.Channel ch = new Tvh.Channel();
            String num = o.optString("GuideNumber", "");
            ch.uuid = "hdhr:" + deviceId + ":" + num;
            ch.name = o.optString("GuideName", num);
            try { ch.number = Double.parseDouble(num); } catch (NumberFormatException ignored) { }
            ch.url = o.getString("URL");
            // DVB lineups list codecs; a channel with sound but no picture is a radio station.
            ch.radio = !o.has("VideoCodec") && o.has("AudioCodec");
            out.add(ch);
        }
        return out;
    }

    private static String http(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(4000);
        con.setReadTimeout(8000);
        try {
            if (con.getResponseCode() != 200) throw new IOException("Network tuner replied HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (java.net.ConnectException | SocketTimeoutException e) {
            throw new IOException("Can't reach the network tuner at " + url);
        } finally {
            con.disconnect();
        }
    }

    // ---------------------------------------------------------------- tuner apps on this box

    /**
     * Channels other apps (a USB tuner's driver app, say) have published to Android's TV
     * provider. Without system rights only browsable channels are visible, which are the ones
     * the user would see anyway.
     */
    static List<Tvh.Channel> tvInputChannels(Context c) {
        List<Tvh.Channel> out = new ArrayList<>();
        String[] cols = {TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID,
                TvContract.Channels.COLUMN_DISPLAY_NAME, TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                TvContract.Channels.COLUMN_SERVICE_TYPE};
        try (Cursor cur = c.getContentResolver().query(TvContract.Channels.CONTENT_URI, cols,
                TvContract.Channels.COLUMN_BROWSABLE + "=1", null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                Tvh.Channel ch = new Tvh.Channel();
                ch.tifId = cur.getLong(0);
                ch.inputId = cur.getString(1);
                ch.uuid = "tif:" + ch.tifId;
                ch.name = cur.getString(2) == null ? "Channel " + cur.getString(3) : cur.getString(2);
                try { ch.number = Double.parseDouble(cur.getString(3)); } catch (Exception ignored) { }
                ch.radio = TvContract.Channels.SERVICE_TYPE_AUDIO.equals(cur.getString(4));
                ch.icon = TvContract.buildChannelLogoUri(ch.tifId).toString();
                out.add(ch);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            // No TV provider on this device, or no access: no channels from here.
        }
        fillTvInputNow(c, out);
        return out;
    }

    /** What's on now, if the tuner app publishes a guide. */
    private static void fillTvInputNow(Context c, List<Tvh.Channel> chs) {
        long now = System.currentTimeMillis();
        for (Tvh.Channel ch : chs) {
            try (Cursor cur = c.getContentResolver().query(TvContract.buildProgramsUriForChannel(ch.tifId, now, now + 1),
                    new String[]{TvContract.Programs.COLUMN_TITLE, TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
                            TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    Tvh.Event e = new Tvh.Event();
                    e.channelUuid = ch.uuid;
                    e.title = cur.getString(0) == null ? "" : cur.getString(0);
                    e.start = cur.getLong(1) / 1000;
                    e.stop = cur.getLong(2) / 1000;
                    ch.now = e;
                }
            } catch (RuntimeException ignored) { }
        }
    }
}
