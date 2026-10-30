package io.github.ksfikas.tvhome;

import android.content.Context;
import android.content.res.Resources;

/**
 * Looks up UI text. On the TV it is backed by the app's string resources (in the language chosen in
 * Control Center); the unit tests supply one that reads res/values and res/values-el directly,
 * because the plain JVM has no Android resources. Helpers that build sentences take one of these
 * instead of a Context, so they stay testable.
 */
interface Txt {
    /** A string resource, formatted with args (use %1$s / %2$d placeholders in the resource). */
    String s(int id, Object... args);

    /** A plurals resource for the given quantity, formatted with args (the quantity is not added for you). */
    String n(int id, int quantity, Object... args);

    static Txt of(Context c) {
        final Resources r = c.getResources();
        return new Txt() {
            @Override public String s(int id, Object... args) { return r.getString(id, args); }
            @Override public String n(int id, int quantity, Object... args) { return r.getQuantityString(id, quantity, args); }
        };
    }
}
