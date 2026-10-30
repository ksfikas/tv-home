package io.github.ksfikas.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

/**
 * The app's language: "auto" follows the TV's own language setting, or it can be forced to English or
 * Greek in Control Center → Language. Every Activity (and the screensaver) wraps its base context
 * with {@link #wrap}, and so does the Application, so strings, dates and numbers all follow the choice.
 * Changing it restarts the app, which is the only way to make every screen and cached string agree.
 */
final class Lang {
    private Lang() { }

    static final String AUTO = "auto", EN = "en", EL = "el";
    static final String[] CHOICES = {AUTO, EN, EL};

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("lang", Context.MODE_PRIVATE);
    }

    static String choice(Context c) { return prefs(c).getString("lang", AUTO); }

    /** The base context with the chosen language applied; unchanged for "auto". */
    static Context wrap(Context base) {
        String choice = choice(base);
        if (AUTO.equals(choice)) return base;
        Locale locale = new Locale(choice);
        Locale.setDefault(locale); // java.text formats (weekday names) follow it too
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        return base.createConfigurationContext(cfg);
    }

    /** Native name of a choice, shown in its own language so it can always be found. */
    static String label(Txt t, String choice) {
        switch (choice) {
            case EN: return "English";
            case EL: return "Ελληνικά";
            default: return t.s(R.string.language_auto);
        }
    }

    /** Save the choice and restart the app so every screen picks it up. */
    static void apply(Activity a, String choice) {
        if (choice.equals(choice(a))) return;
        prefs(a).edit().putString("lang", choice).commit();
        Intent home = new Intent(a, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(home);
        a.finishAffinity();
        // A fresh process, so nothing cached in the old language survives. As the home app, Android
        // starts it again by itself; the intent above makes it land on the home screen.
        new Handler(Looper.getMainLooper()).postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 250);
    }
}
