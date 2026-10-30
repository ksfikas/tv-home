package io.github.ksfikas.tvhome;

import android.app.Application;
import android.content.Context;

/** Applies the chosen language to the application context too (background code uses that one). */
public class App extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }
}
