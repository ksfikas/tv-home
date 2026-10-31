package io.github.ksfikas.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/**
 * What the Tvheadend server is doing right now: the tuner (signal, who is using it), who is watching,
 * what is recording and the recordings disk. Control Center → Tvheadend → Server status shows it and
 * refreshes every few seconds while open. Parsing and wording are separate from the screen so they can
 * be unit tested.
 */
final class ServerStatus {

    static final class Tuner {
        String name, signal, snr;
        int subs;
        long uncorrected, bitsPerSecond;
    }

    static final class Watcher {
        String channel, who, client;
    }

    final List<Tuner> tuners = new ArrayList<>();
    final List<Watcher> watchers = new ArrayList<>();
    final List<String> recording = new ArrayList<>();   // "Title  ·  Channel"
    long[] disk;                                        // {free, total} bytes, or null when not reported
    String version = "";

    /** From the server's status pages; any part may be missing (a non-admin user, an old server). */
    static ServerStatus parse(JSONObject inputs, JSONObject subs, List<Tvh.Recording> recs, long[] disk, String version) {
        ServerStatus s = new ServerStatus();
        s.disk = disk;
        s.version = version == null ? "" : version;
        JSONArray in = inputs == null ? null : inputs.optJSONArray("entries");
        if (in != null) for (int i = 0; i < in.length(); i++) {
            JSONObject o = in.optJSONObject(i);
            if (o == null) continue;
            Tuner t = new Tuner();
            t.name = o.optString("input").replaceAll("\\s*#\\d+", "").replace(" : ", " · ");
            t.subs = o.optInt("subs");
            t.signal = Tvh.level(o.optInt("signal"), o.optInt("signal_scale"), "dBm");
            t.snr = Tvh.level(o.optInt("snr"), o.optInt("snr_scale"), "dB");
            t.uncorrected = o.optLong("unc");
            t.bitsPerSecond = o.optLong("bps");
            s.tuners.add(t);
        }
        JSONArray sub = subs == null ? null : subs.optJSONArray("entries");
        if (sub != null) for (int i = 0; i < sub.length(); i++) {
            JSONObject o = sub.optJSONObject(i);
            if (o == null) continue;
            Watcher w = new Watcher();
            w.channel = o.optString("channel");
            if (w.channel.isEmpty()) w.channel = o.optString("service");
            String user = o.optString("username"), host = o.optString("hostname");
            w.who = user.isEmpty() ? host : host.isEmpty() ? user : user + "@" + host;
            w.client = o.optString("client");
            s.watchers.add(w);
        }
        if (recs != null) for (Tvh.Recording r : recs) {
            if (r.recording()) s.recording.add(r.title + (r.channel == null || r.channel.isEmpty() ? "" : "  ·  " + r.channel));
        }
        return s;
    }

    /** The same information as data, for the phone remote page (which words it in the phone's language). */
    JSONObject toJson() throws org.json.JSONException {
        JSONObject o = new JSONObject().put("version", version);
        JSONArray ts = new JSONArray();
        for (Tuner t : tuners) {
            ts.put(new JSONObject().put("name", t.name).put("subs", t.subs)
                    .put("signal", t.signal == null ? JSONObject.NULL : t.signal)
                    .put("snr", t.snr == null ? JSONObject.NULL : t.snr)
                    .put("unc", t.uncorrected).put("bps", t.bitsPerSecond));
        }
        JSONArray ws = new JSONArray();
        for (Watcher w : watchers) ws.put(new JSONObject().put("channel", w.channel).put("who", w.who));
        JSONArray rs = new JSONArray();
        for (String r : recording) rs.put(r);
        return o.put("tuners", ts).put("watchers", ws).put("recording", rs)
                .put("disk", disk == null || disk[1] <= 0 ? JSONObject.NULL : new JSONArray().put(disk[0]).put(disk[1]));
    }

    /** The text of the screen. {@code size} turns a byte count into "26 GB" (the TV's own formatter). */
    String render(Txt t, LongFunction<String> size) {
        StringBuilder out = new StringBuilder();
        if (!version.isEmpty()) out.append(t.s(R.string.status_server, version)).append("\n\n");
        for (Tuner tn : tuners) {
            out.append(t.s(R.string.status_tuner, tn.name)).append("   ")
                    .append(tn.subs == 0 ? t.s(R.string.status_idle) : t.n(R.plurals.status_busy, tn.subs, tn.subs)).append('\n');
            if (tn.subs > 0) {
                out.append(t.s(R.string.status_signal, tn.signal == null ? "–" : tn.signal, tn.snr == null ? "–" : tn.snr, (int) tn.uncorrected));
                if (tn.bitsPerSecond > 0) out.append("   ·   ").append(t.s(R.string.status_rate, String.format(java.util.Locale.ROOT, "%.1f", tn.bitsPerSecond / 1e6)));
                out.append('\n');
            }
        }
        out.append('\n').append(t.s(R.string.status_watching)).append('\n');
        if (watchers.isEmpty()) out.append("  ").append(t.s(R.string.status_nobody)).append('\n');
        for (Watcher w : watchers) {
            out.append("  ").append(w.channel.isEmpty() ? "?" : w.channel);
            if (!w.who.isEmpty()) out.append("  ·  ").append(w.who);
            out.append('\n');
        }
        out.append('\n').append(t.s(R.string.status_recording)).append('\n');
        if (recording.isEmpty()) out.append("  ").append(t.s(R.string.status_not_recording)).append('\n');
        for (String r : recording) out.append("  ").append(r).append('\n');
        if (disk != null && disk[1] > 0) {
            out.append('\n').append(t.s(R.string.status_disk, size.apply(disk[0]), size.apply(disk[1])));
        }
        return out.toString().trim();
    }

    // ---------------------------------------------------------------- the screen

    private static final long REFRESH_MS = 3000;

    /** A dialog that shows the status and keeps it fresh while it is open. */
    static void show(Activity a) {
        Tvh tvh = Tvh.get(a);
        if (tvh == null) return;
        Handler handler = new Handler(Looper.getMainLooper());
        AlertDialog d = new AlertDialog.Builder(a, R.style.Glass)
                .setTitle(a.getString(R.string.status_title))
                .setMessage(a.getString(R.string.box_checking))
                .setPositiveButton(a.getString(R.string.box_close), null)
                .create();
        Ui.frost(d).show();
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!d.isShowing() || a.isDestroyed()) return;
            Tvh.IO.execute(() -> {
                String text;
                try {
                    text = tvh.serverStatus().render(Txt.of(a), n -> Formatter.formatShortFileSize(a, n));
                } catch (Exception e) {
                    text = e.getMessage() == null ? a.getString(R.string.tvh_bad_reply) : e.getMessage();
                }
                String shown = text;
                a.runOnUiThread(() -> {
                    if (!d.isShowing() || a.isDestroyed()) return;
                    d.setMessage(shown);
                    handler.postDelayed(tick[0], REFRESH_MS);
                });
            });
        };
        d.setOnDismissListener(x -> handler.removeCallbacksAndMessages(null));
        tick[0].run();
    }
}
