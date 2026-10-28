package com.mihir.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.format.Formatter;

import java.io.File;
import java.io.IOException;

/**
 * "Box status" (memory, storage, uptime, Tvheadend's recordings disk) and a once-a-day tidy of
 * the launcher's own cache. Android doesn't let an app clear other apps' caches without root;
 * this keeps the launcher's own footprint small and shows when the box itself is running low.
 */
final class BoxHealth {
    private BoxHealth() { }

    private static final long DAY = 24 * 3600_000L, LOGO_MAX_AGE = 30 * DAY;

    /** At most once a day, on returning home: drop old logos and any leftover pause buffer. */
    static void dailyTidy(Context c) {
        SharedPreferences p = Tvh.prefs(c);
        long now = System.currentTimeMillis();
        if (now - p.getLong("tidied_at", 0) < DAY) return;
        p.edit().putLong("tidied_at", now).apply();
        Tvh.IO.execute(() -> tidy(c));
    }

    /** Returns the bytes freed. */
    private static long tidy(Context c) {
        long freed = 0;
        File[] logos = new File(c.getCacheDir(), "logos").listFiles();
        long now = System.currentTimeMillis();
        if (logos != null) for (File f : logos) {
            if (now - f.lastModified() > LOGO_MAX_AGE) { long n = f.length(); if (f.delete()) freed += n; }
        }
        // Normally deleted when playback stops; a crash could leave it behind.
        File pause = new File(c.getCacheDir(), "pause.ts");
        if (PlayerActivity.playing == null && pause.exists()) { long n = pause.length(); if (pause.delete()) freed += n; }
        return freed;
    }

    /** "Recordings disk 93% full" when under 10% (or 3 GB) is left; null when fine or unknown. */
    static String diskWarning(long[] disk) {
        if (disk == null || disk[1] <= 0) return null;
        long free = disk[0], total = disk[1];
        if (free * 10 > total && free > 3L << 30) return null;
        return "Recordings disk " + Math.round(100 - free * 100.0 / total) + "% full";
    }

    private static long dirSize(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += dirSize(k);
        return n;
    }

    static void show(Activity a) {
        AlertDialog d = new AlertDialog.Builder(a, R.style.Glass)
                .setTitle("Box status")
                .setMessage("Checking…")
                .setPositiveButton("Tidy launcher cache", null)
                .setNegativeButton("Close", null)
                .create();
        Ui.frost(d).show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> Tvh.IO.execute(() -> {
            long freed = tidyAll(a);
            a.runOnUiThread(() -> refresh(a, d, "\n\nFreed " + Formatter.formatShortFileSize(a, freed) + "."));
        }));
        refresh(a, d, "");
    }

    /** "Tidy" button: also drops every cached logo (they're fetched again when needed). */
    private static long tidyAll(Context c) {
        long freed = tidy(c);
        File[] logos = new File(c.getCacheDir(), "logos").listFiles();
        if (logos != null) for (File f : logos) { long n = f.length(); if (f.delete()) freed += n; }
        return freed;
    }

    private static void refresh(Activity a, AlertDialog d, String note) {
        if (!d.isShowing()) return;
        d.setMessage(report(a, null) + note);
        Tvh tvh = Tvh.get(a);
        if (tvh == null) return;
        Tvh.IO.execute(() -> {
            long[] disk = null;
            try { disk = tvh.diskSpace(); } catch (IOException ignored) { }
            long[] got = disk;
            a.runOnUiThread(() -> { if (d.isShowing() && got != null) d.setMessage(report(a, got) + note); });
        });
    }

    private static String report(Context c, long[] tvhDisk) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        StatFs data = new StatFs(c.getFilesDir().getAbsolutePath());
        long up = SystemClock.elapsedRealtime() / 60_000;
        StringBuilder s = new StringBuilder()
                .append("Memory free:   ").append(Formatter.formatShortFileSize(c, mi.availMem))
                .append(" of ").append(Formatter.formatShortFileSize(c, mi.totalMem))
                .append(mi.lowMemory ? "   (low: Android is closing apps)" : "").append('\n')
                .append("Storage free:   ").append(Formatter.formatShortFileSize(c, data.getAvailableBytes()))
                .append(" of ").append(Formatter.formatShortFileSize(c, data.getTotalBytes())).append('\n')
                .append("Running for:   ").append(up >= 1440 ? up / 1440 + " d " : "").append(up / 60 % 24).append(" h ").append(up % 60).append(" min\n")
                .append("Launcher cache:   ").append(Formatter.formatShortFileSize(c, dirSize(c.getCacheDir())));
        if (tvhDisk != null) {
            s.append("\nTvheadend recordings disk:   ").append(Formatter.formatShortFileSize(c, tvhDisk[0]))
                    .append(" free of ").append(Formatter.formatShortFileSize(c, tvhDisk[1]));
            if (diskWarning(tvhDisk) != null) s.append("   ⚠");
        }
        if (mi.availMem < 300L << 20) s.append("\n\nMemory is tight. A restart (Settings → System → Restart) clears it.");
        return s.toString();
    }
}
