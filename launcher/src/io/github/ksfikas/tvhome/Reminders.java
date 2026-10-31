package io.github.ksfikas.tvhome;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Remind me": a popup a minute before a programme starts, with Watch / Dismiss.
 * Kept on the box (one prefs entry per programme) and re-armed after a restart.
 * The popup can appear over other apps only if the launcher may draw over them; that's granted
 * once over ADB:  adb shell appops set io.github.ksfikas.tvhome SYSTEM_ALERT_WINDOW allow
 */
final class Reminders {
    private Reminders() { }

    static final class Reminder {
        long id, start;
        String channelUuid, channelName, title;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("reminders", Context.MODE_PRIVATE);
    }

    static boolean isSet(Context c, long eventId) {
        return prefs(c).contains(String.valueOf(eventId));
    }

    static void add(Context c, Tvh.Event e) {
        Tvh.Channel ch = Tvh.find(e.channelUuid);
        JSONObject o = new JSONObject();
        try {
            o.put("id", e.id).put("start", e.start).put("ch", e.channelUuid)
                    .put("name", ch == null ? "" : ch.name).put("title", e.title);
        } catch (JSONException ignored) { }
        prefs(c).edit().putString(String.valueOf(e.id), o.toString()).apply();
        arm(c, e.id, e.start);
    }

    static void remove(Context c, long eventId) {
        prefs(c).edit().remove(String.valueOf(eventId)).apply();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pending(c, eventId));
    }

    static List<Reminder> all(Context c) {
        List<Reminder> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            Reminder r = parse(String.valueOf(e.getValue()));
            if (r != null) out.add(r);
        }
        out.sort((a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    private static Reminder parse(String s) {
        try {
            JSONObject o = new JSONObject(s);
            Reminder r = new Reminder();
            r.id = o.getLong("id");
            r.start = o.getLong("start");
            r.channelUuid = o.optString("ch");
            r.channelName = o.optString("name");
            r.title = o.optString("title");
            return r;
        } catch (JSONException e) {
            return null;
        }
    }

    private static PendingIntent pending(Context c, long eventId) {
        Intent i = new Intent(c, Receiver.class).putExtra("id", eventId);
        return PendingIntent.getBroadcast(c, (int) eventId, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void arm(Context c, long eventId, long startSec) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long at = Math.max(System.currentTimeMillis() + 1000, startSec * 1000 - 60_000);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, eventId));
    }

    /** Alarm went off (or the box restarted / the app was updated: re-arm everything still ahead, drop the rest). */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            long nowSec = System.currentTimeMillis() / 1000;
            if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
                for (Reminder r : all(c)) {
                    if (r.start > nowSec - 300) arm(c, r.id, r.start); else remove(c, r.id);
                }
                return;
            }
            long id = i.getLongExtra("id", -1);
            String stored = prefs(c).getString(String.valueOf(id), "");
            Reminder r = parse(stored);
            remove(c, id);
            if (r == null || r.start < nowSec - 600) return;
            // Already watching that channel: nothing to remind.
            if (r.channelUuid.equals(PlayerActivity.playing)) return;
            c.startActivity(new Intent(c, Popup.class).putExtra("r", stored)
                    // NO_USER_ACTION: a popup coming up is not the user leaving, so the player must not shrink for it.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NO_USER_ACTION));
        }
    }

    /** See-through activity holding just the reminder dialog; goes away by itself after a minute. */
    public static class Popup extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            Reminder r = parse(getIntent().getStringExtra("r"));
            if (r == null) { finish(); return; }
            long nowSec = System.currentTimeMillis() / 1000;
            boolean onCh = !r.channelName.isEmpty();
            String msg = r.start <= nowSec
                    ? (onCh ? getString(R.string.remind_starting_now_on, r.channelName) : getString(R.string.remind_starting_now))
                    : (onCh ? getString(R.string.remind_starts_at_on, Ui.time(this, r.start), r.channelName)
                            : getString(R.string.remind_starts_at, Ui.time(this, r.start)));
            AlertDialog d = new AlertDialog.Builder(this, R.style.Glass)
                    .setTitle(r.title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.remind_watch, (x, w) -> startActivity(new Intent(this, PlayerActivity.class)
                            .putExtra(PlayerActivity.EXTRA_CHANNEL, r.channelUuid)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                    .setNegativeButton(R.string.remind_dismiss, null)
                    .create();
            d.setOnDismissListener(x -> finish());
            d.show();
            handler.postDelayed(() -> { if (!isFinishing()) d.dismiss(); }, 60_000);
        }

        @Override
        protected void onDestroy() {
            handler.removeCallbacksAndMessages(null);
            super.onDestroy();
        }
    }
}
