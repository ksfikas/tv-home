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

    // ---------------------------------------------------------------- icons

    /** A Material Symbols icon (res/drawable/sym_*), sized in dp; tinted by whoever shows it. */
    static android.graphics.drawable.Drawable icon(Context c, int res, float sizeDp) {
        android.graphics.drawable.Drawable d = c.getDrawable(res).mutate();
        int s = dp(c, sizeDp);
        d.setBounds(0, 0, s, s);
        return d;
    }

    /** An icon before a TextView's text, in the text's colour (keep it in step with setIconColor). */
    static void iconBefore(TextView t, int res, float sizeDp) {
        t.setCompoundDrawables(icon(t.getContext(), res, sizeDp), null, null, null);
        t.setCompoundDrawablePadding(dp(t.getContext(), 8));
        setIconColor(t, t.getCurrentTextColor());
    }

    /** Text and its icon in one colour (focus changes). */
    static void setIconColor(TextView t, int color) {
        t.setTextColor(color);
        t.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
    }

    /** An icon-only button image, tinted, with even padding around it. */
    static android.widget.ImageView iconView(Context c, int res, int color, float padDp) {
        android.widget.ImageView v = new android.widget.ImageView(c);
        v.setImageResource(res);
        v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int p = dp(c, padDp);
        v.setPadding(p, p, p, p);
        v.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        return v;
    }

    /** A simple pick-one menu; framework dialog, D-pad friendly, no extra views to keep around. */
    static void menu(Context c, String title, String[] items, java.util.function.IntConsumer onPick) {
        frost(new AlertDialog.Builder(c, R.style.Glass)
                .setTitle(title)
                .setItems(items, (d, which) -> onPick.accept(which))
                .show());
    }

    static void confirm(Context c, String title, String message, String yes, Runnable onYes) {
        frost(new AlertDialog.Builder(c, R.style.Glass)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(yes, (d, w) -> onYes.run())
                .setNegativeButton("Cancel", null)
                .show());
    }

    // ---------------------------------------------------------------- frosted glass

    /**
     * tvOS-style frosting behind a dialog: the screen it covers is snapshotted at 1/10 size, blurred
     * once and faded in full-screen, darkened; it goes when the last dialog closes. This box has no
     * system window blur, and a one-off snapshot costs next to nothing. Skipped over live video
     * (a snapshot can't capture it, so the picture would go black).
     */
    static <D extends android.app.Dialog> D frost(D d) {
        android.app.Activity a = activity(d.getContext());
        if (a == null || a instanceof PlayerActivity || a.isFinishing() || d.getWindow() == null) return d;
        android.view.ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.getWidth() == 0) return d;
        Frost f = null;
        for (int i = 0; i < content.getChildCount(); i++) if (content.getChildAt(i) instanceof Frost) f = (Frost) content.getChildAt(i);
        if (f == null) {
            f = new Frost(a);
            content.addView(f, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        Frost frost = f;
        if (frost.users++ == 0) {
            frost.setVisibility(android.view.View.GONE);
            frost.setImageBitmap(blurredSnapshot(content, 14));
            frost.setVisibility(android.view.View.VISIBLE);
            frost.setAlpha(0f);
            frost.animate().cancel();
            frost.animate().alpha(1f).setDuration(220).start();
        }
        d.getWindow().setDimAmount(0.05f); // the frost already darkens
        d.getWindow().getDecorView().addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View v) { }
            @Override public void onViewDetachedFromWindow(android.view.View v) {
                // A follow-up dialog (menu → sub-menu) usually opens right away: let it keep the frost.
                frost.postDelayed(() -> {
                    if (--frost.users > 0) return;
                    frost.users = 0;
                    frost.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                        if (frost.users == 0) { frost.setVisibility(android.view.View.GONE); frost.setImageBitmap(null); }
                    }).start();
                }, 120);
            }
        });
        return d;
    }

    private static final class Frost extends android.widget.ImageView {
        int users;
        Frost(Context c) {
            super(c);
            setScaleType(ScaleType.FIT_XY);
            setColorFilter(0x80000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
            setClickable(false);
            setFocusable(false);
        }
    }

    private static android.app.Activity activity(Context c) {
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** The view drawn at 1/scale size and box-blurred (three passes look like a Gaussian). */
    static android.graphics.Bitmap blurredSnapshot(android.view.View v, int scale) {
        int w = Math.max(1, v.getWidth() / scale), h = Math.max(1, v.getHeight() / scale);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        c.scale(1f / scale, 1f / scale);
        v.draw(c);
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) boxBlur(px, w, h, 3);
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    /** One horizontal + one vertical box-blur pass of the given radius, in place. */
    static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 2; pass++) {
            boolean horiz = pass == 0;
            int[] src = horiz ? px : tmp, dst = horiz ? tmp : px;
            int lines = horiz ? h : w, len = horiz ? w : h;
            for (int l = 0; l < lines; l++) {
                for (int i = 0; i < len; i++) {
                    int a = 0, rr = 0, g = 0, bb = 0, n = 0;
                    for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                        int p = src[horiz ? l * w + k : k * w + l];
                        a += p >>> 24; rr += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; bb += p & 0xFF; n++;
                    }
                    dst[horiz ? l * w + i : i * w + l] = (a / n) << 24 | (rr / n) << 16 | (g / n) << 8 | (bb / n);
                }
            }
        }
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
