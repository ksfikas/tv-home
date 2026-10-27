package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no background work. The only network use is the
 * Live TV row, which talks to the user's own Tvheadend server (see Tvh) while home is on screen.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, clock, ampm, date, title, wifiPill, appsLabel;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;

    // Live TV row (Tvheadend).
    private LinearLayout liveSection, channelRow, radioSection, radioRow;
    private RowScroller channelScroll, radioScroll;
    private final List<View> channelCards = new ArrayList<>(); // Guide, Recordings, then TV channels
    private final List<View> radioCards = new ArrayList<>();
    private final Map<String, TextView> channelNow = new HashMap<>();
    private final Map<String, ProgressBar> channelProgress = new HashMap<>();
    private List<String> shownChannels = Collections.emptyList();
    private long channelsLoadedAt, nowLoadedAt;
    private boolean liveLoading, isResumed;
    private String liveStateKey;
    private final Runnable retryLive = () -> refreshLive(false);
    static final long LIVE_RETRY_MS = 15 * 1000;
    static final long CHANNELS_TTL_MS = 15 * 60 * 1000, NOW_TTL_MS = 60 * 1000;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); tickLive(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();
        showLiveState(null);
        // Just booted and Live TV is set to resume: go straight back to the last channel.
        if (b == null && SystemClock.elapsedRealtime() < 4 * 60 * 1000 && Tvh.get(this) != null
                && Tvh.prefs(this).getBoolean("autoplay", false)
                && Tvh.prefs(this).getString("last_channel", null) != null) {
            launch(new Intent(this, PlayerActivity.class));
        }

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        isResumed = true;
        refreshLive(false);
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        handler.removeCallbacks(retryLive);
        isResumed = false;
        unregisterReceiver(tick);
        if (inputsPopup != null) inputsPopup.dismiss();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (inputsPopup != null) inputsPopup.dismiss();
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }

        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        // Live TV: one horizontal row of Tvheadend channels between the dock and the app grid.
        liveSection = new LinearLayout(this);
        liveSection.setOrientation(LinearLayout.VERTICAL);
        liveSection.setClipChildren(false);
        liveSection.setClipToPadding(false);
        TextView liveLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        liveLabel.setText("LIVE TV");
        liveLabel.setLetterSpacing(0.22f);
        liveSection.addView(liveLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));
        channelScroll = rowScroller();
        channelRow = (LinearLayout) channelScroll.getChildAt(0);
        liveSection.addView(channelScroll, new LinearLayout.LayoutParams(-1, -2));
        content.addView(liveSection, llp(-1, -2, 0, 0, 0, dp(30)));

        // Radio: same shape, its own row so stations don't crowd the TV channels.
        radioSection = new LinearLayout(this);
        radioSection.setOrientation(LinearLayout.VERTICAL);
        radioSection.setClipChildren(false);
        radioSection.setClipToPadding(false);
        TextView radioLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        radioLabel.setText("RADIO");
        radioLabel.setLetterSpacing(0.22f);
        radioSection.addView(radioLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));
        radioScroll = rowScroller();
        radioRow = (LinearLayout) radioScroll.getChildAt(0);
        radioSection.addView(radioScroll, new LinearLayout.LayoutParams(-1, -2));
        radioSection.setVisibility(View.GONE);
        content.addView(radioSection, llp(-1, -2, 0, 0, 0, dp(30)));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("ALL APPS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(10, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        TextView inputs = pill("Inputs", "⇥");
        inputs.setOnClickListener(v -> showInputs(v));
        wifiPill = pill("Wi-Fi", "◉");
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        TextView settings = pill("Settings", "⚙");
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        for (TextView p : new TextView[]{inputs, wifiPill, settings}) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, String glyph) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(14), 0, dp(14), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Set<String> userHidden = Tvh.prefs(this).getStringSet("hidden_user", Collections.emptySet());
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || userHidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockApps) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == dock.size() - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        // Hold OK on an app in the grid to hide its tile (the app itself is untouched).
        if (!inDock) card.setOnLongClickListener(v -> {
            Ui.menu(this, app.label, new String[]{"Hide from All Apps"}, i -> {
                Set<String> h = new HashSet<>(Tvh.prefs(this).getStringSet("hidden_user", new HashSet<>()));
                h.add(app.pkg);
                Tvh.prefs(this).edit().putStringSet("hidden_user", h).apply();
                lastFocusedId = View.NO_ID;
                buildApps();
            });
            return true;
        });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        // Live rows between the dock and the apps: TV (with Guide/Recordings), then radio.
        List<List<View>> live = new ArrayList<>();
        if (!channelCards.isEmpty()) live.add(channelCards);
        if (!radioCards.isEmpty()) live.add(radioCards);
        View firstLive = live.isEmpty() ? null : live.get(0).get(0);
        View lastLive = live.isEmpty() ? null : live.get(live.size() - 1).get(0);
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(firstLive != null ? firstLive.getId()
                    : g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        // Each live row is one line: Up to the row above (or the dock), Down to the next (or the apps).
        for (int r = 0; r < live.size(); r++) {
            List<View> row = live.get(r);
            int above = r == 0 ? (d > 0 ? dockCards.get(0).getId() : topId) : live.get(r - 1).get(0).getId();
            for (int i = 0; i < row.size(); i++) {
                View c = row.get(i);
                c.setNextFocusLeftId(i == 0 ? c.getId() : row.get(i - 1).getId());
                c.setNextFocusRightId(i == row.size() - 1 ? c.getId() : row.get(i + 1).getId());
                c.setNextFocusUpId(above);
                c.setNextFocusDownId(r + 1 < live.size() ? live.get(r + 1).get(0).getId()
                        : g > 0 ? gridCards.get(0).getId() : c.getId());
            }
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (lastLive != null ? lastLive.getId() : d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        // With the Live TV row in place, that slot belongs to the channels and every app row centres.
        int target = grid.indexOfChild(row) == 0 && channelCards.isEmpty()
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private void showInputs(View anchor) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("Live TV", () -> openPackage(liveTvPkg)));
        rows.add(inputRow("Live TV setup…", this::showLiveSetup));
        if (!Tvh.prefs(this).getStringSet("hidden_user", Collections.emptySet()).isEmpty()) {
            rows.add(inputRow("Show hidden apps", () -> {
                Tvh.prefs(this).edit().remove("hidden_user").apply();
                buildApps();
            }));
        }
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", () -> openPackage(inputPickerPkg)));

        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- live tv

    /** Reload channels (every 15 min) or just what's on now (every minute) while home is showing. */
    private void refreshLive(boolean force) {
        Tvh tvh = Tvh.get(this);
        if (tvh == null) { showLiveState(null); return; }
        if (liveLoading) return;
        long now = SystemClock.elapsedRealtime();
        // shownChannels, not channelCards: a placeholder card ("Loading…", an error) is not a channel list.
        boolean noChannels = shownChannels.isEmpty();
        boolean needChannels = force || noChannels || now - channelsLoadedAt > CHANNELS_TTL_MS;
        if (!needChannels && now - nowLoadedAt < NOW_TTL_MS) return;
        handler.removeCallbacks(retryLive);
        liveLoading = true;
        if (noChannels) showLiveState("Loading channels…");
        Tvh.IO.execute(() -> {
            String error = null;
            List<Tvh.Channel> list = null;
            try {
                if (needChannels) list = tvh.loadChannels();
                else { list = new ArrayList<>(Tvh.channels); tvh.fillNow(list); }
            } catch (java.io.IOException e) {
                error = e.getMessage();
            }
            List<Tvh.Channel> result = list;
            String err = error;
            runOnUiThread(() -> {
                liveLoading = false;
                if (isDestroyed()) return;
                if (result == null) {
                    // Nothing to show yet (e.g. Wi-Fi still connecting after boot): keep trying while home is up.
                    if (shownChannels.isEmpty()) {
                        showLiveState(err);
                        if (isResumed) handler.postDelayed(retryLive, LIVE_RETRY_MS);
                    }
                    return;
                }
                long t = SystemClock.elapsedRealtime();
                nowLoadedAt = t;
                if (needChannels) channelsLoadedAt = t;
                if (result.isEmpty()) { showLiveState("No channels on the Tvheadend server"); return; }
                showChannels(result);
            });
        });
    }

    /** Called every minute: move the progress bars, and fetch the next programmes once one ends. */
    private void tickLive() {
        long nowSec = System.currentTimeMillis() / 1000;
        boolean ended = false;
        for (Tvh.Channel ch : Tvh.channels) {
            ProgressBar p = channelProgress.get(ch.uuid);
            if (p != null && ch.now != null) p.setProgress((int) (ch.now.progress(nowSec) * 1000));
            if (ch.now != null && ch.now.stop <= nowSec) ended = true;
        }
        if (ended || SystemClock.elapsedRealtime() - nowLoadedAt > 5 * NOW_TTL_MS) refreshLive(false);
    }

    /**
     * TV row: Guide, Recordings, then favourites (in the user's order) or every TV channel.
     * Radio row: every radio station. Rebuilt only when the line-up changes; otherwise the
     * "now" text and progress bars are updated in place.
     */
    private void showChannels(List<Tvh.Channel> list) {
        List<String> favs = Tvh.favourites(this);
        List<Tvh.Channel> tv = new ArrayList<>(), radio = new ArrayList<>();
        if (!favs.isEmpty()) for (String u : favs) {
            for (Tvh.Channel ch : list) if (ch.uuid.equals(u) && !ch.radio) tv.add(ch);
        }
        for (Tvh.Channel ch : list) {
            if (ch.radio) radio.add(ch);
            else if (favs.isEmpty()) tv.add(ch);
        }
        List<String> ids = new ArrayList<>();
        for (Tvh.Channel ch : tv) ids.add(ch.uuid);
        ids.add("|");
        for (Tvh.Channel ch : radio) ids.add(ch.uuid);
        if (!ids.equals(shownChannels)) {
            View focused = getCurrentFocus();
            int focusedTv = channelCards.indexOf(focused), focusedRadio = radioCards.indexOf(focused);
            channelRow.removeAllViews();
            radioRow.removeAllViews();
            channelCards.clear();
            radioCards.clear();
            channelNow.clear();
            channelProgress.clear();
            addTile(channelRow, actionTile("Guide", "☰", v -> launch(new Intent(this, GuideActivity.class)), channelCards), true, false);
            addTile(channelRow, actionTile("Recordings", "●", v -> launch(new Intent(this, RecordingsActivity.class)), channelCards), false, tv.isEmpty());
            for (int i = 0; i < tv.size(); i++) addTile(channelRow, channelTile(tv.get(i), channelCards), false, i == tv.size() - 1);
            for (int i = 0; i < radio.size(); i++) addTile(radioRow, channelTile(radio.get(i), radioCards), i == 0, i == radio.size() - 1);
            radioSection.setVisibility(radio.isEmpty() ? View.GONE : View.VISIBLE);
            shownChannels = ids;
            wireFocus();
            if (focusedTv >= 0 && !channelCards.isEmpty()) channelCards.get(Math.min(focusedTv, channelCards.size() - 1)).requestFocus();
            if (focusedRadio >= 0 && !radioCards.isEmpty()) radioCards.get(Math.min(focusedRadio, radioCards.size() - 1)).requestFocus();
        }
        long nowSec = System.currentTimeMillis() / 1000;
        for (Tvh.Channel ch : list) {
            TextView t = channelNow.get(ch.uuid);
            ProgressBar p = channelProgress.get(ch.uuid);
            if (t == null || p == null) continue;
            t.setText(ch.now == null ? " " : ch.now.title);
            p.setVisibility(ch.now == null ? View.INVISIBLE : View.VISIBLE);
            if (ch.now != null) p.setProgress((int) (ch.now.progress(nowSec) * 1000));
        }
    }

    /** One placeholder card for "not set up", "loading" or an error; selecting it opens setup or retries. */
    private void showLiveState(String message) {
        boolean configured = Tvh.get(this) != null;
        String key = configured + "|" + message;
        if (key.equals(liveStateKey) && shownChannels.isEmpty() && !channelCards.isEmpty()) return;
        liveStateKey = key;
        channelRow.removeAllViews();
        radioRow.removeAllViews();
        channelCards.clear();
        radioCards.clear();
        radioSection.setVisibility(View.GONE);
        channelNow.clear();
        channelProgress.clear();
        shownChannels = Collections.emptyList();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0xFF1C1C1E);
        bg.setStroke(dp(2), 0x00FFFFFF);
        card.setBackground(bg);
        TextView t = text(13, "sans-serif-medium", Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setText(!configured ? "▶  Set up Live TV" : message != null ? message : "Loading channels…");
        card.addView(t, new FrameLayout.LayoutParams(-1, -1));
        card.setOnClickListener(v -> { if (configured) refreshLive(true); else showLiveSetup(); });
        card.setOnLongClickListener(v -> { showLiveSetup(); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            bg.setStroke(dp(2), has ? 0xE6FFFFFF : 0x00FFFFFF);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(200).start();
            if (has) onChannelFocus(v);
        });
        box.addView(card, new LinearLayout.LayoutParams(-1, -2));
        if (configured && message != null && !message.startsWith("Loading")) {
            TextView hint = text(11, "sans-serif", 0x99FFFFFF);
            hint.setText("OK to retry · hold OK for setup");
            hint.setGravity(Gravity.CENTER);
            box.addView(hint, llp(-1, -2, 0, dp(10), 0, 0));
        }
        channelRow.addView(box, new LinearLayout.LayoutParams(Math.round(tileWidth() * 1.6f), -2));
        channelCards.add(card);
        wireFocus();
    }

    private void addTile(LinearLayout row, View tile, boolean first, boolean last) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(tileWidth(), -2);
        p.setMargins(first ? 0 : dp(10), 0, last ? 0 : dp(10), 0);
        row.addView(tile, p);
    }

    /** Guide / Recordings: same card shape as a channel, with a glyph and a label. */
    private View actionTile(String label, String glyph, View.OnClickListener onClick, List<View> into) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0x33FFFFFF);
        bg.setStroke(dp(2), 0x00FFFFFF);
        card.setBackground(bg);
        TextView g = text(30, "sans-serif-light", Color.WHITE);
        g.setText(glyph);
        g.setGravity(Gravity.CENTER);
        card.addView(g, new FrameLayout.LayoutParams(-1, -1));
        box.addView(card, new LinearLayout.LayoutParams(-1, -2));
        TextView l = text(11, "sans-serif-medium", 0xE6FFFFFF);
        l.setText(label);
        l.setGravity(Gravity.CENTER);
        box.addView(l, llp(-1, -2, 0, dp(10), 0, 0));
        card.setOnClickListener(onClick);
        card.setOnFocusChangeListener((v, has) -> {
            bg.setStroke(dp(2), has ? 0xE6FFFFFF : 0x00FFFFFF);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            if (has) onChannelFocus(v);
        });
        into.add(card);
        return box;
    }

    /** Hold OK on a channel: favourites, order, setup. */
    private void channelMenu(Tvh.Channel ch) {
        List<String> favs = Tvh.favourites(this);
        boolean fav = favs.contains(ch.uuid);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!ch.radio) {
            labels.add(fav ? "Remove from favourites" : "Add to favourites");
            actions.add(() -> {
                if (fav) favs.remove(ch.uuid); else favs.add(ch.uuid);
                Tvh.setFavourites(this, favs);
                showChannels(Tvh.channels);
            });
            if (fav && favs.size() > 1) {
                int i = favs.indexOf(ch.uuid);
                if (i > 0) { labels.add("Move left"); actions.add(() -> moveFavourite(favs, i, -1)); }
                if (i < favs.size() - 1) { labels.add("Move right"); actions.add(() -> moveFavourite(favs, i, +1)); }
            }
            if (!favs.isEmpty()) {
                labels.add("Show all channels (clear favourites)");
                actions.add(() -> { Tvh.setFavourites(this, new ArrayList<>()); showChannels(Tvh.channels); });
            }
        }
        labels.add("Live TV setup…");
        actions.add(this::showLiveSetup);
        Ui.menu(this, ch.name, labels.toArray(new String[0]), i -> actions.get(i).run());
    }

    private void moveFavourite(List<String> favs, int i, int d) {
        String u = favs.remove(i);
        favs.add(i + d, u);
        Tvh.setFavourites(this, favs);
        int keep = channelCards.indexOf(getCurrentFocus());
        showChannels(Tvh.channels);
        if (keep >= 0 && keep + d < channelCards.size()) channelCards.get(keep + d).requestFocus();
    }

    private View channelTile(Tvh.Channel ch, List<View> into) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C1E);
        card.setBackground(bg);

        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(18), dp(12), dp(18), dp(12));
        card.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        // No logo: the channel name stands in for it.
        TextView fallback = text(14, "sans-serif-medium", Color.WHITE);
        fallback.setText(ch.name);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(dp(10), 0, dp(10), 0);
        card.addView(fallback, new FrameLayout.LayoutParams(-1, -1));

        TextView number = text(10, "sans-serif-medium", 0xB3FFFFFF);
        number.setText(ch.numberLabel());
        card.addView(number, lp(-2, -2, Gravity.TOP | Gravity.START, dp(8), dp(6), 0, 0));

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));
        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView now = text(11, "sans-serif-medium", 0xE6FFFFFF);
        now.setSingleLine(true);
        now.setEllipsize(android.text.TextUtils.TruncateAt.END);
        now.setText(ch.now == null ? " " : ch.now.title);
        box.addView(now, llp(-1, -2, dp(2), dp(10), dp(2), 0));

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xE6FFFFFF));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x33FFFFFF));
        progress.setVisibility(View.INVISIBLE);
        box.addView(progress, llp(-1, dp(3), dp(2), dp(6), dp(2), 0));

        channelNow.put(ch.uuid, now);
        channelProgress.put(ch.uuid, progress);

        card.setOnClickListener(v -> launch(new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.EXTRA_CHANNEL, ch.uuid)));
        card.setOnLongClickListener(v -> { channelMenu(ch); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? dp(14) : 0)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            if (has) onChannelFocus(v);
        });

        Tvh tvh = Tvh.get(this);
        if (tvh != null && ch.icon != null) Tvh.IO.execute(() -> {
            android.graphics.Bitmap bm = tvh.logo(this, ch, tileWidth());
            if (bm != null) runOnUiThread(() -> { logo.setImageBitmap(bm); fallback.setVisibility(View.GONE); });
        });
        into.add(card);
        return box;
    }

    private void onChannelFocus(View card) {
        lastFocusedId = card.getId();
        fadeGlow(0.35f);
        title.animate().alpha(0f).setDuration(200).start();
        boolean inRadio = radioCards.contains(card);
        // TV row: the dock band slides to the top edge (the original's first-app-row spot).
        // Radio row: sits a third of the way down, with the TV row still peeking above.
        View band = (View) dockRow.getParent();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int target = inRadio ? radioSection.getTop() - (int) (screenH * 0.38f)
                : hero.getTop() + band.getTop() + dockRow.getTop() - dp(22);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
        (inRadio ? radioScroll : channelScroll).reveal(card);
    }

    /** Same width as an app tile in the grid, so the channel row lines up with it. */
    private int tileWidth() {
        int w = getResources().getDisplayMetrics().widthPixels - 2 * dp(68);
        return (w - (cols - 1) * dp(20)) / cols;
    }

    /** Server address, login, and the restart option. Credentials stay in this app's private storage. */
    private void showLiveSetup() {
        android.content.SharedPreferences prefs = Tvh.prefs(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        EditText url = field(form, "Tvheadend address", prefs.getString("url", "http://192.168.1.10:9981"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = field(form, "Username", prefs.getString("user", ""), InputType.TYPE_CLASS_TEXT);
        EditText pass = field(form, "Password", prefs.getString("pass", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox autoplay = new CheckBox(this);
        autoplay.setText("Start the last channel after the box restarts");
        autoplay.setChecked(prefs.getBoolean("autoplay", false));
        form.addView(autoplay, llp(-1, -2, 0, dp(8), 0, 0));
        TextView result = text(13, "sans-serif", 0xFFFF8A80);
        form.addView(result, llp(-1, -2, 0, dp(6), 0, 0));

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Live TV")
                .setView(form)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Remove", (d, w) -> {
                    prefs.edit().remove("url").remove("user").remove("pass").apply();
                    Tvh.channels = Collections.emptyList();
                    showLiveState(null);
                })
                .create();
        dialog.setOnShowListener(d -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
                String u = url.getText().toString().trim();
                if (!u.startsWith("http")) u = "http://" + u;
                String address = u;
                result.setTextColor(0xB3FFFFFF);
                result.setText("Checking…");
                save.setEnabled(false);
                prefs.edit().putString("url", address)
                        .putString("user", user.getText().toString().trim())
                        .putString("pass", pass.getText().toString())
                        .putBoolean("autoplay", autoplay.isChecked())
                        .apply();
                Tvh tvh = Tvh.get(this);
                Tvh.IO.execute(() -> {
                    String err = tvh.test();
                    runOnUiThread(() -> {
                        save.setEnabled(true);
                        if (err != null) { result.setTextColor(0xFFFF8A80); result.setText(err); return; }
                        dialog.dismiss();
                        refreshLive(true);
                    });
                });
            });
        });
        dialog.show();
    }

    private EditText field(LinearLayout form, String label, String value, int type) {
        TextView l = text(12, "sans-serif-medium", 0xB3FFFFFF);
        l.setText(label);
        form.addView(l, llp(-1, -2, 0, dp(10), 0, 0));
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setText(value);
        form.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
    }

    private void updateWifi() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                if (wm.getConnectionInfo().getNetworkId() == -1) name = "Offline";
            }
        } catch (SecurityException ignored) { }
        wifiPill.setText("◉  " + name);
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        g.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.14f), Color.TRANSPARENT});
        glow.setBackground(g);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = "wallpapers/" + wallpapers[wallIndex];
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeStream(in, null, o);
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Menu key on the remote opens the Inputs dropdown.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !pills.isEmpty()) {
            showInputs(pills.get(0));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    private RowScroller rowScroller() {
        RowScroller s = new RowScroller(this);
        s.setHorizontalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setPadding(dp(68), dp(14), dp(68), dp(14));
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        s.addView(row, new FrameLayout.LayoutParams(-2, -2));
        return s;
    }

    /**
     * Horizontal row that only scrolls when told to. The stock HorizontalScrollView brings a focused
     * card just to the very edge, ignoring the side padding and the card's focus zoom, so the first
     * and last cards ended up partly off screen. {@link #reveal} keeps the whole card inside the margins.
     */
    static final class RowScroller extends HorizontalScrollView {
        RowScroller(Context c) { super(c); }

        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }

        void reveal(View card) {
            View box = (View) card.getParent();
            int margin = getPaddingLeft();
            int left = getPaddingLeft() + box.getLeft(), right = getPaddingLeft() + box.getRight();
            int x = getScrollX();
            if (left - margin < x) x = left - margin;
            else if (right + margin > x + getWidth()) x = right + margin - getWidth();
            int max = Math.max(0, getChildAt(0).getWidth() + getPaddingLeft() + getPaddingRight() - getWidth());
            smoothScrollTo(Math.max(0, Math.min(x, max)), 0);
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
