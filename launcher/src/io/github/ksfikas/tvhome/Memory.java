package io.github.ksfikas.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Memory pressure on a 2 GB box: apps left in the background keep hundreds of megabytes and push the
 * rest into swap, which shows as light stuttering. This closes the background processes of the other
 * apps on the home screen (Android allows that for a normal permission; an app that is playing keeps
 * running, and anything closed simply starts again next time).
 */
final class Memory {
    private Memory() { }

    /** Below this much available memory, live TV may close background apps first (when switched on). */
    static final long LOW = 400L << 20;

    private static final String AUTO = "free_mem_auto";

    /** Never closed: the home app itself and Google's voice search (the remote's mic needs it quickly). */
    private static final String[] KEEP = {"com.google.android.katniss"};

    static boolean auto(Context c) { return Tvh.prefs(c).getBoolean(AUTO, false); }

    static void setAuto(Context c, boolean on) { Tvh.prefs(c).edit().putBoolean(AUTO, on).apply(); }

    static long available(Context c) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        c.getSystemService(ActivityManager.class).getMemoryInfo(mi);
        return mi.availMem;
    }

    /** The packages worth closing: every launchable app but this one and the few that stay, once each. */
    static List<String> candidates(List<String> launchable, String self) {
        Set<String> out = new LinkedHashSet<>();
        outer:
        for (String p : launchable) {
            if (p == null || p.equals(self)) continue;
            for (String k : KEEP) if (p.equals(k)) continue outer;
            out.add(p);
        }
        return new ArrayList<>(out);
    }

    private static List<String> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> all = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                all.add(ri.activityInfo.packageName);
            }
        }
        return all;
    }

    /** Closes the other apps' background processes; returns roughly how many bytes that freed. Blocks about a second: call off the main thread. */
    static long freeBackground(Context c) {
        long before = available(c);
        ActivityManager am = c.getSystemService(ActivityManager.class);
        for (String p : candidates(launchable(c), c.getPackageName())) am.killBackgroundProcesses(p);
        try { Thread.sleep(900); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Math.max(0, available(c) - before);
    }

    /** At the start of live TV: if switched on and memory is low, close background apps first (off the main thread). */
    static void freeIfLow(Context c) {
        if (auto(c) && available(c) < LOW) Tvh.IO.execute(() -> freeBackground(c));
    }
}
