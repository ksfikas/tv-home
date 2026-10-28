package io.github.ksfikas.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Box-wide sleep timer (Control Center, phone remote): standby at a set time, whatever is on
 * screen. An Android alarm, so nothing runs in between. Needs the SleepAdmin permission.
 */
final class SleepTimer {
    private SleepTimer() { }

    private static PendingIntent pending(Context c) {
        return PendingIntent.getBroadcast(c, 7001, new Intent(c, Receiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean canSleep(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(new ComponentName(c, SleepAdmin.class));
    }

    /** Standby now (no-op without the permission). */
    static void sleepNow(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        if (canSleep(c)) dpm.lockNow();
    }

    /** Minutes from now, or 0 to cancel. */
    static void set(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pending(c));
        long at = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        Tvh.prefs(c).edit().putLong("sleep_at", at).apply();
        if (at > 0) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c));
    }

    /** Minutes left, or 0 when off. */
    static int left(Context c) {
        long at = Tvh.prefs(c).getLong("sleep_at", 0);
        long ms = at - System.currentTimeMillis();
        return ms > 0 ? (int) Math.max(1, (ms + 59_999) / 60_000) : 0;
    }

    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            Tvh.prefs(c).edit().remove("sleep_at").apply();
            sleepNow(c);
        }
    }
}
