package com.mihir.tvhome;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small view helpers shared by the Live TV screens (guide, recordings, player). */
final class Ui {
    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, float sp, String family, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    /** A focusable list row that turns white when focused, like the rest of the launcher. */
    static TextView row(Context c, CharSequence label) {
        TextView r = text(c, 15, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        r.setFocusable(true);
        r.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 8));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            ((TextView) v).setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    /** A simple pick-one menu; framework dialog, D-pad friendly, no extra views to keep around. */
    static void menu(Context c, String title, String[] items, java.util.function.IntConsumer onPick) {
        new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(items, (d, which) -> onPick.accept(which))
                .show();
    }

    static void confirm(Context c, String title, String message, String yes, Runnable onYes) {
        new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(yes, (d, w) -> onYes.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    static String time(Context c, long epochSec) {
        return new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(c) ? "H:mm" : "h:mm a",
                Locale.getDefault()).format(new Date(epochSec * 1000));
    }

    static String day(long epochSec) {
        return new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(new Date(epochSec * 1000));
    }

    /** 1:02:03 or 2:03. */
    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
