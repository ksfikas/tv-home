package io.github.ksfikas.tvhome;

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

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

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
    private int wallIndex = -2; // -2: not shown yet (resume from the saved one)
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, clock, ampm, date, weather, title, wifiPill, appsLabel, liveLabel;
    private String diskWarning; // recordings disk nearly full, shown next to LIVE TV
    private LinearLayout titleBox;
    private TextView watchedLine;
    private ProgressBar watchedBar;
    private ImageView artLayer;
    private String artPkg;  // whose artwork artLayer holds
    private App dockFocus;  // the focused dock app, to refresh its line on returning home
    private Map<String, Long> lastUsed = Collections.emptyMap();
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;

    // Live TV row (Tvheadend).
    private LinearLayout liveSection, channelRow, radioSection, radioRow;
    private RowScroller channelScroll, radioScroll;
    private final List<View> channelCards = new ArrayList<>(); // TV channels (or the placeholder card)
    private final List<View> actionCards = new ArrayList<>();  // Guide, Search, Recordings, Continue: the small 2×2 block before them
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
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); tickLive(); updateWeather(); }
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
        current = new java.lang.ref.WeakReference<>(this);
        RemoteServer.start(this); // only if the phone remote was turned on
        showLiveState(null);
        // Just booted and Live TV is set to resume: go straight back to the last channel.
        if (b == null && SystemClock.elapsedRealtime() < 4 * 60 * 1000 && Sources.configured(this)
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
        updateWeather();
        BoxHealth.dailyTidy(this);
        // Back from an app: its "last watched" line may have changed.
        refreshShelf(); // also refreshes the focused dock app's line
        Tvh.IO.execute(() -> {
            Map<String, Long> used = Watched.lastUsed(this);
            runOnUiThread(() -> { lastUsed = used; if (dockFocus != null && !isDestroyed()) showWatched(dockFocus); });
        });
        isResumed = true;
        if (!shownChannels.isEmpty()) showChannels(Tvh.channels); // e.g. a new "continue watching" tile
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
        handler.removeCallbacks(shelfTick);
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
        // Artwork of what was last watched in the focused dock app, faded in over the wallpaper.
        artLayer = new ImageView(this);
        artLayer.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artLayer.setAlpha(0f);
        root.addView(artLayer, new FrameLayout.LayoutParams(-1, -1));
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

        // App name, and under it what was last watched there (see Watched); they fade as one.
        titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setClipChildren(false); // focused shelf buttons grow a little
        titleBox.setClipToPadding(false);
        bottom.addView(titleBox, llp(-1, -2, dp(8), 0, 0, dp(14)));
        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        titleBox.addView(title, new LinearLayout.LayoutParams(-2, -2));
        watchedLine = text(15, "sans-serif", 0xE6FFFFFF);
        watchedLine.setShadowLayer(dp(10), 0, 0, 0x99000000);
        watchedLine.setSingleLine(true);
        watchedLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        watchedLine.setVisibility(View.GONE);
        titleBox.addView(watchedLine, llp(-1, -2, dp(2), dp(2), dp(120), 0));
        watchedBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        watchedBar.setMax(1000);
        watchedBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xE6FFFFFF));
        watchedBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x40FFFFFF));
        watchedBar.setVisibility(View.GONE);
        titleBox.addView(watchedBar, llp(dp(260), dp(3), dp(2), dp(8), 0, 0));
        // Top Shelf carousel buttons (Resume / Find where to watch / Read on phone) and a film poster.
        shelfBar = new LinearLayout(this);
        shelfBar.setGravity(Gravity.CENTER_VERTICAL);
        shelfBar.setVisibility(View.GONE);
        titleBox.addView(shelfBar, llp(-2, -2, 0, dp(14), 0, 0));
        title.setSingleLine(false);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.66f));
        poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setClipToOutline(true);
        poster.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); }
        });
        poster.setElevation(dp(12));
        poster.setAlpha(0f);
        hero.addView(poster, lp(dp(170), dp(255), Gravity.END | Gravity.TOP, 0, dp(110), dp(72), 0));

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
        liveLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        liveLabel.setText(getString(R.string.main_live_tv_caps));
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
        radioLabel.setText(getString(R.string.main_radio_caps));
        radioLabel.setLetterSpacing(0.22f);
        radioSection.addView(radioLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));
        radioScroll = rowScroller();
        radioRow = (LinearLayout) radioScroll.getChildAt(0);
        radioSection.addView(radioScroll, new LinearLayout.LayoutParams(-1, -2));
        radioSection.setVisibility(View.GONE);
        content.addView(radioSection, llp(-1, -2, 0, 0, 0, dp(30)));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText(getString(R.string.main_all_apps_caps));
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
        weather = text(12, "sans-serif-light", 0xCCFFFFFF);
        weather.setSingleLine(true);
        weather.setVisibility(View.GONE);
        left.addView(weather, llp(-2, -2, 0, dp(6), 0, 0));
        for (TextView t : new TextView[]{greeting, clock, ampm, date, weather}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        TextView inputs = pill(getString(R.string.main_pill_inputs), R.drawable.sym_input);
        inputs.setOnClickListener(v -> showInputs(v));
        wifiPill = pill("Wi-Fi", R.drawable.sym_wifi);
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        TextView settings = pill(getString(R.string.main_pill_settings), R.drawable.sym_settings);
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        TextView control = pill(getString(R.string.main_pill_control), R.drawable.sym_tune);
        control.setOnClickListener(v -> showControlCenter());
        for (TextView p : new TextView[]{control, inputs, wifiPill, settings}) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, int icon) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(label);
        Ui.iconBefore(p, icon, 15);
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
            Ui.setIconColor((TextView) v, has ? 0xFF111111 : 0xE6FFFFFF);
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
        for (String p : dockList()) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
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

    private static final int DOCK_MAX = 6;

    /** Dock apps in order: the user's choice if they've edited it, else config.xml's list. */
    private List<String> dockList() {
        String s = Tvh.prefs(this).getString("dock_user", null);
        if (s == null) return new ArrayList<>(Arrays.asList(dockApps));
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** Save the dock, rebuild, and keep focus on the app that was edited. */
    private void setDock(List<String> pkgs, App keep) {
        Tvh.prefs(this).edit().putString("dock_user", android.text.TextUtils.join(",", pkgs)).apply();
        lastFocusedId = View.NO_ID;
        buildApps();
        List<View> all = new ArrayList<>(dockCards);
        all.addAll(gridCards);
        for (View c : all) if (c.getTag() instanceof App && ((App) c.getTag()).pkg.equals(keep.pkg)) { c.requestFocus(); break; }
    }

    /** Hold OK on any app: open, dock it or move it, hide it, app info, uninstall. */
    private void appMenu(App app, boolean inDock) {
        List<String> dock = dockList();
        // Only apps that are actually installed count towards the dock's size.
        dock.removeIf(p -> !isInstalled(p));
        int i = dock.indexOf(app.pkg);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.main_open));
        actions.add(() -> launch(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        if (inDock) {
            if (i > 0) { labels.add(getString(R.string.main_move_left)); actions.add(() -> { dock.remove(i); dock.add(i - 1, app.pkg); setDock(dock, app); }); }
            if (i >= 0 && i < dock.size() - 1) { labels.add(getString(R.string.main_move_right)); actions.add(() -> { dock.remove(i); dock.add(i + 1, app.pkg); setDock(dock, app); }); }
            if (dock.size() > 1) { labels.add(getString(R.string.main_remove_from_dock)); actions.add(() -> { dock.remove(app.pkg); setDock(dock, app); }); }
        } else {
            labels.add(getString(dock.size() < DOCK_MAX ? R.string.main_add_to_dock : R.string.main_add_to_dock_replace));
            actions.add(() -> {
                if (dock.size() >= DOCK_MAX) dock.remove(dock.size() - 1);
                dock.add(app.pkg);
                setDock(dock, app);
            });
            labels.add(getString(R.string.main_hide_from_all_apps));
            actions.add(() -> {
                Set<String> h = new HashSet<>(Tvh.prefs(this).getStringSet("hidden_user", new HashSet<>()));
                h.add(app.pkg);
                Tvh.prefs(this).edit().putStringSet("hidden_user", h).apply();
                lastFocusedId = View.NO_ID;
                buildApps();
            });
        }
        labels.add(getString(R.string.main_app_info));
        actions.add(() -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + app.pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        labels.add(getString(R.string.main_uninstall));
        actions.add(() -> launch(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + app.pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        Ui.menu(this, app.label, labels.toArray(new String[0]), k -> actions.get(k).run());
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
        card.setOnLongClickListener(v -> { appMenu(app, inDock); return true; });
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
                showWatched(app);
                titleBox.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                dockFocus = null;
                showArt(null);
                fadeGlow(0.35f);
                titleBox.animate().alpha(0f).setDuration(200).start();
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
                // Left from the first TV channel reaches the small Guide/Search/… block.
                int leftOfFirst = row == channelCards && !actionCards.isEmpty() ? actionCards.get(Math.min(1, actionCards.size() - 1)).getId() : c.getId();
                c.setNextFocusLeftId(i == 0 ? leftOfFirst : row.get(i - 1).getId());
                c.setNextFocusRightId(i == row.size() - 1 ? c.getId() : row.get(i + 1).getId());
                c.setNextFocusUpId(above);
                c.setNextFocusDownId(r + 1 < live.size() ? live.get(r + 1).get(0).getId()
                        : g > 0 ? gridCards.get(0).getId() : c.getId());
            }
        }
        // The 2×2 block: arrows move inside it; Right from its right column goes to the channels,
        // Up from its top row to the dock, Down from its bottom row to the next row.
        int a = actionCards.size();
        View firstChannel = channelCards.isEmpty() ? null : channelCards.get(0);
        View below = !radioCards.isEmpty() ? radioCards.get(0) : g > 0 ? gridCards.get(0) : null;
        for (int i = 0; i < a; i++) {
            View c = actionCards.get(i);
            boolean leftCol = i % 2 == 0, topRow = i < 2;
            c.setNextFocusLeftId(leftCol ? c.getId() : actionCards.get(i - 1).getId());
            c.setNextFocusRightId(!leftCol || i + 1 >= a
                    ? (firstChannel != null ? firstChannel.getId() : c.getId()) : actionCards.get(i + 1).getId());
            c.setNextFocusUpId(topRow ? (d > 0 ? dockCards.get(0).getId() : topId) : actionCards.get(i - 2).getId());
            c.setNextFocusDownId(i + 2 < a ? actionCards.get(i + 2).getId()
                    : !topRow || a <= 2 ? (below != null ? below.getId() : c.getId())
                    : actionCards.get(a - 1).getId());
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
        // With a Top Shelf card showing, Up from the dock goes to its buttons first.
        if (shelfBar != null && shelfBar.getVisibility() == View.VISIBLE && shelfBar.getChildCount() > 1) {
            for (View c : dockCards) c.setNextFocusUpId(shelfBar.getChildAt(0).getId());
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

        PopupWindow pw = new PopupWindow(list, dp(240), -2, true);
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
        if (isInstalled(liveTvPkg)) rows.add(inputRow(getString(R.string.main_live_tv), () -> openPackage(liveTvPkg)));
        rows.add(inputRow(getString(R.string.main_live_tv_setup), this::showLiveSetup));
        if (Tvh.get(this) != null) rows.add(inputRow(getString(R.string.server_menu_item), this::pickServer));
        rows.add(inputRow(getString(R.string.main_weather_menu), this::showWeatherSetup));
        rows.add(inputRow(getString(R.string.main_box_status_menu),() -> BoxHealth.show(this)));
        rows.add(inputRow(getString(R.string.main_phone_remote_menu), this::showRemoteSetup));
        if (!Tvh.prefs(this).getStringSet("hidden_user", Collections.emptySet()).isEmpty()) {
            rows.add(inputRow(getString(R.string.main_show_hidden_apps),() -> {
                Tvh.prefs(this).edit().remove("hidden_user").apply();
                buildApps();
            }));
        }
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow(getString(R.string.main_all_sources),() -> openPackage(inputPickerPkg)));

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
        Tvh tvh = Tvh.get(this); // null when only tuners are set up
        if (!Sources.configured(this)) { showLiveState(null); return; }
        if (liveLoading) return;
        long now = SystemClock.elapsedRealtime();
        // shownChannels, not channelCards: a placeholder card ("Loading…", an error) is not a channel list.
        boolean noChannels = shownChannels.isEmpty();
        boolean needChannels = force || noChannels || now - channelsLoadedAt > CHANNELS_TTL_MS;
        if (!needChannels && now - nowLoadedAt < NOW_TTL_MS) return;
        handler.removeCallbacks(retryLive);
        liveLoading = true;
        if (noChannels) showLiveState(getString(R.string.main_loading_channels));
        Tvh.IO.execute(() -> {
            String error = null;
            List<Tvh.Channel> list = null;
            try {
                if (needChannels) {
                    list = Sources.load(this);
                    // With the channel list (every 15 min): is the recordings disk getting full?
                    long[] disk = null;
                    if (tvh != null) try { disk = tvh.diskSpace(); } catch (java.io.IOException ignored) { }
                    diskWarning = BoxHealth.diskWarning(Txt.of(this), disk);
                }
                else { list = new ArrayList<>(Tvh.channels); if (tvh != null) tvh.fillNow(list); }
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
                if (result.isEmpty()) { showLiveState(getString(R.string.main_no_channels)); return; }
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
        android.content.SharedPreferences resume = Tvh.resume(this);
        String cont = resume.getString("last", null);
        liveLabel.setText(diskWarning == null ? getString(R.string.main_live_tv_caps) : getString(R.string.main_live_tv_caps) + "    ·    " + diskWarning);
        liveLabel.setTextColor(diskWarning == null ? 0x99FFFFFF : 0xE6FFB74D);
        List<String> ids = new ArrayList<>();
        ids.add("c:" + cont + resume.getInt("last_pct", 0));
        for (Tvh.Channel ch : tv) ids.add(ch.uuid);
        ids.add("|");
        for (Tvh.Channel ch : radio) ids.add(ch.uuid);
        if (!ids.equals(shownChannels)) {
            View focused = getCurrentFocus();
            int focusedTv = channelCards.indexOf(focused), focusedRadio = radioCards.indexOf(focused);
            channelRow.removeAllViews();
            radioRow.removeAllViews();
            channelCards.clear();
            actionCards.clear();
            radioCards.clear();
            channelNow.clear();
            channelProgress.clear();
            List<String[]> labels = new ArrayList<>();
            List<View.OnClickListener> clicks = new ArrayList<>();
            if (Tvh.get(this) != null) { // guide, search and recordings come from Tvheadend
                labels.add(new String[]{"☰", getString(R.string.main_guide)});
                clicks.add(v -> launch(new Intent(this, GuideActivity.class)));
                labels.add(new String[]{"⌕", getString(R.string.main_search)});
                clicks.add(v -> launch(new Intent(this, SearchActivity.class)));
                labels.add(new String[]{"●", getString(R.string.main_recordings)});
                clicks.add(v -> launch(new Intent(this, RecordingsActivity.class)));
            }
            if (cont != null) {
                // Continue watching: the recording last stopped part-way.
                String t = resume.getString("last_title", getString(R.string.main_recording));
                labels.add(new String[]{"▶", getString(R.string.main_continue, t, resume.getInt("last_pct", 0))});
                clicks.add(v -> launch(new Intent(this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_RECORDING, cont)
                        .putExtra(PlayerActivity.EXTRA_TITLE, t)));
            }
            if (!labels.isEmpty()) {
                View block = actionBlock(labels, clicks);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Math.round(tileWidth() * 9f / 16f), -2);
                bp.setMargins(0, 0, dp(10), 0);
                channelRow.addView(block, bp);
            }
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
            t.setText(nowText(ch));
            p.setVisibility(ch.now == null ? View.INVISIBLE : View.VISIBLE);
            if (ch.now != null) p.setProgress((int) (ch.now.progress(nowSec) * 1000));
        }
    }

    /** One placeholder card for "not set up", "loading" or an error; selecting it opens setup or retries. */
    private void showLiveState(String message) {
        boolean configured = Sources.configured(this);
        // No sources and the user said they don't want Live TV: no row at all.
        boolean hidden = !configured && Tvh.prefs(this).getBoolean("live_hidden", false);
        liveSection.setVisibility(hidden ? View.GONE : View.VISIBLE);
        String key = configured + "|" + message + "|" + hidden;
        if (key.equals(liveStateKey) && shownChannels.isEmpty() && (hidden || !channelCards.isEmpty())) return;
        liveStateKey = key;
        channelRow.removeAllViews();
        radioRow.removeAllViews();
        channelCards.clear();
        actionCards.clear();
        radioCards.clear();
        radioSection.setVisibility(View.GONE);
        channelNow.clear();
        channelProgress.clear();
        shownChannels = Collections.emptyList();
        if (hidden) { wireFocus(); return; }

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
        t.setText(!configured ? "▶  " + getString(R.string.main_set_up_live_tv) : message != null ? message : getString(R.string.main_loading_channels));
        card.addView(t, new FrameLayout.LayoutParams(-1, -1));
        card.setOnClickListener(v -> { if (configured) refreshLive(true); else showLiveSetup(); });
        card.setOnLongClickListener(v -> {
            if (configured) { showLiveSetup(); return true; }
            Ui.menu(this, getString(R.string.main_live_tv), new String[]{getString(R.string.main_set_up_live_tv) + "…", getString(R.string.main_hide_live_tv)}, i -> {
                if (i == 0) showLiveSetup();
                else { Tvh.prefs(this).edit().putBoolean("live_hidden", true).apply(); showLiveState(null); }
            });
            return true;
        });
        card.setOnFocusChangeListener((v, has) -> {
            bg.setStroke(dp(2), has ? 0xE6FFFFFF : 0x00FFFFFF);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(200).start();
            if (has) onChannelFocus(v);
        });
        box.addView(card, new LinearLayout.LayoutParams(-1, -2));
        if (configured && message != null && !message.equals(getString(R.string.main_loading_channels))) {
            TextView hint = text(11, "sans-serif", 0x99FFFFFF);
            hint.setText(getString(R.string.main_retry_hint));
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

    /**
     * Guide, Search, Recordings (and Continue) as small glyph buttons in a 2×2 block, as tall as a
     * channel card, so the channels lead the row. The focused button's name shows underneath.
     */
    private View actionBlock(List<String[]> items, List<View.OnClickListener> clicks) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        int side = Math.round(tileWidth() * 9f / 16f), gap = dp(6), cell = (side - gap) / 2;
        TextView caption = text(11, "sans-serif-medium", 0xE6FFFFFF);
        caption.setText(" ");
        caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout line = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 2 == 0) {
                line = new LinearLayout(this);
                line.setClipChildren(false);
                box.addView(line, llp(-1, -2, 0, i == 0 ? 0 : gap, 0, 0));
            }
            String[] it = items.get(i);
            int icon = "☰".equals(it[0]) ? R.drawable.sym_view_timeline : "⌕".equals(it[0]) ? R.drawable.sym_search
                    : "●".equals(it[0]) ? R.drawable.sym_fiber_manual_record : R.drawable.sym_play_arrow;
            ImageView b = Ui.iconView(this, icon, 0xCCFFFFFF, 11);
            b.setId(View.generateViewId());
            b.setFocusable(true);
            b.setClickable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(0x1FFFFFFF);
            b.setBackground(bg);
            b.setOnClickListener(clicks.get(i));
            b.setOnFocusChangeListener((v, has) -> {
                bg.setColor(has ? 0xF2FFFFFF : 0x1FFFFFFF);
                b.setImageTintList(android.content.res.ColorStateList.valueOf(has ? 0xFF111111 : 0xCCFFFFFF));
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(160).start();
                caption.setText(has ? it[1] : " ");
                if (has) onChannelFocus(v);
            });
            line.addView(b, llp(cell, cell, i % 2 == 0 ? 0 : gap, 0, 0, 0));
            actionCards.add(b);
        }
        box.addView(caption, llp(-1, -2, dp(2), dp(10), 0, 0));
        return box;
    }

    /** Hold OK on a channel: favourites, order, setup. */
    private void channelMenu(Tvh.Channel ch) {
        List<String> favs = Tvh.favourites(this);
        boolean fav = favs.contains(ch.uuid);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!ch.radio) {
            labels.add(getString(fav ? R.string.main_fav_remove : R.string.main_fav_add));
            actions.add(() -> {
                if (fav) favs.remove(ch.uuid); else favs.add(ch.uuid);
                Tvh.setFavourites(this, favs);
                showChannels(Tvh.channels);
            });
            if (fav && favs.size() > 1) {
                int i = favs.indexOf(ch.uuid);
                if (i > 0) { labels.add(getString(R.string.main_move_left)); actions.add(() -> moveFavourite(favs, i, -1)); }
                if (i < favs.size() - 1) { labels.add(getString(R.string.main_move_right)); actions.add(() -> moveFavourite(favs, i, +1)); }
            }
            if (!favs.isEmpty()) {
                labels.add(getString(R.string.main_fav_clear));
                actions.add(() -> { Tvh.setFavourites(this, new ArrayList<>()); showChannels(Tvh.channels); });
            }
        }
        labels.add(getString(R.string.main_live_tv_setup));
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
        now.setText(nowText(ch));
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
        if (ch.icon != null) Tvh.IO.execute(() -> {
            android.graphics.Bitmap bm = Tvh.logo(this, tvh, ch, tileWidth());
            if (bm != null) runOnUiThread(() -> { logo.setImageBitmap(bm); fallback.setVisibility(View.GONE); });
        });
        into.add(card);
        return box;
    }

    /**
     * The focused dock app. Top Shelf "focused app" mode shows what was last watched there under
     * its name (a progress bar, its artwork behind); "off" just the name; the carousel modes keep
     * showing their current card.
     */
    private void showWatched(App app) {
        dockFocus = app;
        String mode = Shelf.mode(this);
        if (!shelfItems.isEmpty()) { showShelfItem(); return; }
        Watched.Line l = Shelf.OFF.equals(mode) ? null : Watched.line(this, app.pkg, lastUsed);
        title.setText(app.label);
        watchedLine.setText(l == null ? "" : l.text);
        watchedLine.setVisibility(l == null ? View.GONE : View.VISIBLE);
        watchedBar.setVisibility(l != null && l.progress >= 0 ? View.VISIBLE : View.GONE);
        if (l != null && l.progress >= 0) watchedBar.setProgress(Math.round(l.progress * 1000));
        java.io.File art = l == null ? null : Watched.artFile(this, app.pkg);
        showArt(art != null && art.exists() ? app.pkg : null);
        shelfBar.setVisibility(View.GONE);
    }

    /** Fade the app's artwork in over the wallpaper (dimmed by the scrim), or back out. */
    private void showArt(String pkg) {
        if (pkg == null) { showBackdrop(null, null); return; }
        java.io.File f = Watched.artFile(this, pkg);
        showBackdrop(pkg, () -> BitmapFactory.decodeFile(f.getAbsolutePath()));
    }

    /** A backdrop behind the hero (artwork, a news photo), loaded off the UI thread; null fades it out. */
    private void showBackdrop(String key, java.util.concurrent.Callable<Bitmap> load) {
        if (key == null) {
            artPkg = null;
            artLayer.animate().alpha(0f).setDuration(400).start();
            poster.animate().alpha(0f).setDuration(300).start();
            return;
        }
        if (key.equals(artPkg)) { artLayer.animate().alpha(1f).setDuration(600).start(); return; }
        artPkg = key;
        Tvh.IO.execute(() -> {
            Bitmap b = null;
            try { b = load.call(); } catch (Exception ignored) { }
            Bitmap got = b;
            runOnUiThread(() -> {
                if (!key.equals(artPkg)) return; // moved on meanwhile
                if (got == null) { showBackdrop(null, null); return; }
                artLayer.setImageBitmap(got);
                artLayer.animate().alpha(1f).setDuration(600).start();
            });
        });
    }

    // ---------------------------------------------------------------- top shelf

    private List<Shelf.Item> shelfItems = Collections.emptyList();
    private int shelfIndex;
    private LinearLayout shelfBar;
    private ImageView poster;
    private final Runnable shelfTick = this::rotateShelf;

    /** Load the shelf for the current mode (continue watching from the box; recommendations online, hourly). */
    private void refreshShelf() {
        handler.removeCallbacks(shelfTick);
        String mode = Shelf.mode(this);
        if (Shelf.ALL.equals(mode)) setShelf(Shelf.continueWatching(this));
        else if (Shelf.RECS.equals(mode)) {
            Tvh.IO.execute(() -> {
                List<Shelf.Item> cached = Shelf.recommendations(this, false);
                runOnUiThread(() -> { if (Shelf.RECS.equals(Shelf.mode(this))) setShelf(cached); });
                List<Shelf.Item> fresh = Shelf.recommendations(this, true);
                if (!signature(fresh).equals(signature(cached))) {
                    runOnUiThread(() -> { if (Shelf.RECS.equals(Shelf.mode(this))) setShelf(fresh); });
                }
            });
        } else setShelf(Collections.emptyList());
    }

    /** Everything a card shows, so a refreshed list replaces the old one whenever anything changed. */
    private static String signature(List<Shelf.Item> items) {
        StringBuilder s = new StringBuilder();
        for (Shelf.Item it : items) s.append(it.title).append('|').append(it.artUrl).append('|').append(it.sub).append('\n');
        return s.toString();
    }

    private void setShelf(List<Shelf.Item> items) {
        shelfItems = items;
        if (shelfIndex >= items.size()) shelfIndex = 0;
        if (items.isEmpty()) {
            shelfBar.setVisibility(View.GONE);
            for (View d : dockCards) d.setNextFocusUpId(pills.get(0).getId());
            if (dockFocus != null) showWatched(dockFocus);
            else poster.setAlpha(0f);
            return;
        }
        showShelfItem();
        handler.removeCallbacks(shelfTick);
        handler.postDelayed(shelfTick, 9000);
    }

    /** Next card every 9 s, unless you're on the shelf's buttons or away from the dock. */
    private void rotateShelf() {
        if (shelfItems.size() > 1 && !shelfBar.hasFocus() && dockFocus != null) {
            shelfIndex = (shelfIndex + 1) % shelfItems.size();
            showShelfItem();
        }
        handler.postDelayed(shelfTick, 9000);
    }

    private void showShelfItem() {
        Shelf.Item it = shelfItems.get(shelfIndex);
        title.setText(it.title);
        watchedLine.setText(it.sub);
        watchedLine.setVisibility(View.VISIBLE);
        watchedBar.setVisibility(it.progress >= 0 ? View.VISIBLE : View.GONE);
        if (it.progress >= 0) watchedBar.setProgress(Math.round(it.progress * 1000));

        // Picture: an app's artwork or a news photo behind; a film's poster on the right.
        if (it.tall) {
            showBackdrop(null, null);
            String url = it.artUrl;
            Tvh.IO.execute(() -> {
                Bitmap b = Shelf.image(this, url, dp(240));
                runOnUiThread(() -> {
                    if (shelfItems.isEmpty() || shelfItems.get(shelfIndex) != it) return;
                    poster.setImageBitmap(b);
                    poster.animate().alpha(b == null ? 0f : 1f).setDuration(500).start();
                });
            });
        } else {
            poster.animate().alpha(0f).setDuration(300).start();
            if (it.artFile != null) showArt(it.pkg);
            else if (it.artUrl != null) showBackdrop(it.artUrl, () -> Shelf.image(this, it.artUrl, 1280));
            else showBackdrop(null, null);
        }

        // Buttons for this card; Left/Right at the ends step through the cards.
        View focused = shelfBar.findFocus();
        int focusedAt = focused == null ? -1 : shelfBar.indexOfChild(focused);
        shelfBar.removeAllViews();
        List<String[]> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        switch (it.kind) {
            case "watch":
                buttons.add(new String[]{getString(R.string.main_shelf_resume), "play"}); actions.add(() -> resumeApp(it.pkg));
                buttons.add(new String[]{getString(R.string.main_shelf_remove), "close"}); actions.add(() -> { Watched.remove(this, it.pkg); refreshShelf(); });
                break;
            case "film":
                if (it.link != null && isInstalled("com.apple.atve.androidtv.appletv")) {
                    buttons.add(new String[]{"Apple TV", "play"}); actions.add(() -> openInAppleTv(it.link));
                }
                buttons.add(new String[]{getString(R.string.main_shelf_where), "search"});
                actions.add(() -> shelfDetails(it, "https://www.justwatch.com/gr/search?q=" + android.net.Uri.encode(it.title)));
                break;
            default:
                buttons.add(new String[]{getString(R.string.main_shelf_phone), "phone"}); actions.add(() -> shelfDetails(it, it.link));
        }
        for (int i = 0; i < buttons.size(); i++) {
            TextView b = text(14, "sans-serif-medium", Color.WHITE);
            b.setText(buttons.get(i)[0]);
            String k = buttons.get(i)[1];
            Ui.iconBefore(b, "play".equals(k) ? R.drawable.sym_play_arrow : "close".equals(k) ? R.drawable.sym_close
                    : "search".equals(k) ? R.drawable.sym_search : "info".equals(k) ? R.drawable.sym_info : R.drawable.sym_smartphone, 17);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(18), 0, dp(18), 0);
            b.setId(View.generateViewId());
            ccFocus(b, false);
            Runnable a = actions.get(i);
            b.setOnClickListener(v -> a.run());
            boolean first = i == 0, last = i == buttons.size() - 1;
            b.setOnKeyListener((v, code, e) -> {
                if (e.getAction() != android.view.KeyEvent.ACTION_DOWN || shelfItems.size() < 2) return false;
                int step = code == android.view.KeyEvent.KEYCODE_DPAD_LEFT && first ? -1 : code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && last ? 1 : 0;
                if (step == 0) return false;
                shelfIndex = (shelfIndex + step + shelfItems.size()) % shelfItems.size();
                showShelfItem();
                View t = shelfBar.getChildAt(step < 0 ? 0 : Math.max(0, shelfBar.getChildCount() - 2));
                if (t != null) t.requestFocus();
                return true;
            });
            shelfBar.addView(b, llp(-2, dp(40), i == 0 ? 0 : dp(10), 0, 0, 0));
        }
        TextView dots = text(12, "sans-serif", 0x99FFFFFF);
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < shelfItems.size(); i++) d.append(i == shelfIndex ? "●" : "○").append(' ');
        dots.setText(shelfItems.size() > 1 ? d.toString().trim() : "");
        dots.setGravity(Gravity.CENTER_VERTICAL);
        shelfBar.addView(dots, llp(-2, dp(40), dp(16), 0, 0, 0));
        shelfBar.setVisibility(View.VISIBLE);
        noClip(shelfBar);

        // Up from the dock lands on the shelf's first button; Up again reaches the pills.
        View firstButton = shelfBar.getChildAt(0);
        for (View dc : dockCards) dc.setNextFocusUpId(firstButton.getId());
        for (int i = 0; i < shelfBar.getChildCount() - 1; i++) {
            View b = shelfBar.getChildAt(i);
            b.setNextFocusUpId(pills.get(0).getId());
            b.setNextFocusDownId(dockCards.isEmpty() ? b.getId() : dockCards.get(0).getId());
        }
        if (focusedAt >= 0 && focusedAt < shelfBar.getChildCount() - 1) shelfBar.getChildAt(focusedAt).requestFocus();
    }

    /** Back into the app: straight to its player if it's still running, else just open it. */
    private void resumeApp(String pkg) {
        android.app.PendingIntent pi = Watched.resume.get(pkg);
        if (pi != null) {
            try { pi.send(); return; } catch (android.app.PendingIntent.CanceledException gone) { Watched.resume.remove(pkg); }
        }
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /**
     * The film's page in the Apple TV app. Its link (tv.apple.com/…/id123) redirects to the real
     * page, which the app opens itself; resolved off the UI thread, then handed over.
     */
    private void openInAppleTv(String link) {
        Tvh.IO.execute(() -> {
            String url = link;
            try {
                java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(link).openConnection();
                con.setInstanceFollowRedirects(false);
                con.setConnectTimeout(5000);
                String to = con.getHeaderField("Location");
                if (to != null) url = to.startsWith("http") ? to : "https://tv.apple.com" + to;
                con.disconnect();
            } catch (Exception ignored) { }
            String u = url;
            runOnUiThread(() -> launch(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))
                    .setPackage("com.apple.atve.androidtv.appletv")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)));
        });
    }

    /** A card's story; with a link, a QR code to read it on the phone. */
    private void shelfDetails(Shelf.Item it, String link) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        box.setGravity(Gravity.CENTER_VERTICAL);
        if (link != null) {
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(qrCode(link, dp(180)));
            box.addView(qr, new LinearLayout.LayoutParams(dp(180), dp(180)));
        }
        TextView t = text(15, "sans-serif", 0xE6FFFFFF);
        t.setLineSpacing(dp(3), 1f);
        String about = it.summary == null || it.summary.isEmpty() ? "" : it.summary;
        if (about.length() > 600) about = about.substring(0, 597) + "…";
        t.setText(it.sub + (about.isEmpty() ? "" : "\n\n" + about) + (link != null ? "\n\n" + getString(R.string.main_scan_phone) : ""));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMargins(link != null ? dp(24) : 0, 0, 0, 0);
        box.addView(t, tl);
        Ui.frost(new AlertDialog.Builder(this, R.style.Glass).setTitle(it.title).setView(box)
                .setPositiveButton(getString(R.string.main_close), null).create()).show();
    }

    /** Control Center → Top shelf: the four modes, and what Recommendations includes. */
    private void pickShelf() {
        String mode = Shelf.mode(this);
        String[] modes = {Shelf.APP, Shelf.ALL, Shelf.RECS, Shelf.OFF};
        List<String> labels = new ArrayList<>();
        for (String m : modes) labels.add((m.equals(mode) ? "✓  " : "      ") + Shelf.modeName(Txt.of(this), m));
        labels.add(getString(R.string.main_shelf_films, getString(Shelf.films(this) ? R.string.main_state_on : R.string.main_state_off)));
        labels.add(getString(R.string.main_shelf_news, getString(Shelf.news(this) ? R.string.main_state_on : R.string.main_state_off)));
        labels.add(getString(R.string.main_news_from_item, Shelf.FEEDS[Shelf.feed(this)][0]));
        Ui.menu(this, getString(R.string.main_top_shelf),labels.toArray(new String[0]), i -> {
            if (i < modes.length) { Shelf.setMode(this, modes[i]); shelfIndex = 0; refreshShelf(); return; }
            if (i == modes.length) Tvh.prefs(this).edit().putBoolean("shelf_films", !Shelf.films(this)).apply();
            else if (i == modes.length + 1) Tvh.prefs(this).edit().putBoolean("shelf_news", !Shelf.news(this)).apply();
            else {
                String[] names = new String[Shelf.FEEDS.length];
                for (int k = 0; k < names.length; k++) names[k] = Shelf.FEEDS[k][0];
                Ui.menu(this, getString(R.string.main_news_from), names, k -> { Tvh.prefs(this).edit().putInt("shelf_feed", k).apply(); refreshShelf(); });
                return;
            }
            refreshShelf();
            pickShelf();
        });
    }

    /** Control Center → Language: automatic (the TV's language), English or Greek. Applying it restarts the app. */
    private void pickLanguage() {
        Txt t = Txt.of(this);
        String current = Lang.choice(this);
        String[] labels = new String[Lang.CHOICES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = (Lang.CHOICES[i].equals(current) ? "✓  " : "      ") + Lang.label(t, Lang.CHOICES[i]);
        Ui.menu(this, t.s(R.string.language_title), labels, i -> Lang.apply(this, Lang.CHOICES[i]));
    }

    /** Control Center → Screensaver: start it now, which slides to show, and how long each stays. */
    private void pickScreensaver() {
        String[] keys = {Screensaver.WEATHER, Screensaver.TV, Screensaver.FILMS, Screensaver.NEWS, Screensaver.TICKER};
        String[] names = {getString(R.string.main_weather), getString(R.string.main_on_tv_now), getString(R.string.main_popular_films), getString(R.string.main_news_slides), getString(R.string.main_news_ticker)};
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.main_start_now));
        for (int i = 0; i < keys.length; i++) labels.add(getString(R.string.main_name_state, names[i], getString(Screensaver.option(this, keys[i]) ? R.string.main_state_on : R.string.main_state_off)));
        labels.add(getResources().getQuantityString(R.plurals.main_each_slide, Screensaver.seconds(this), Screensaver.seconds(this)));
        Ui.menu(this, getString(R.string.main_screensaver),labels.toArray(new String[0]), i -> {
            if (i == 0) { startScreensaver(); return; }
            if (i <= keys.length) Screensaver.toggle(this, keys[i - 1]);
            else Screensaver.nextSeconds(this);
            pickScreensaver();
        });
    }

    /**
     * The TV's own "sleep" action starts the chosen screensaver (and only puts the box to sleep when
     * none is chosen, so check ours is the one first).
     */
    private void startScreensaver() {
        String chosen = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        if (chosen == null || !chosen.contains(getPackageName())) {
            android.widget.Toast.makeText(this, getString(R.string.main_ss_choose_first), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (!launch(new Intent("com.google.android.pano.action.SLEEP"))) {
            android.widget.Toast.makeText(this, getString(R.string.main_ss_cant_start), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** What's on now under a channel card; a red dot when it's being recorded. */
    private static CharSequence nowText(Tvh.Channel ch) {
        if (ch.now == null) return " ";
        if (!ch.now.recordingSet() || !ch.now.dvrState.startsWith("recording")) return ch.now.title;
        android.text.SpannableString s = new android.text.SpannableString("●  " + ch.now.title);
        s.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF5252), 0, 1, 0);
        return s;
    }

    private void onChannelFocus(View card) {
        lastFocusedId = card.getId();
        fadeGlow(0.35f);
        titleBox.animate().alpha(0f).setDuration(200).start();
        dockFocus = null;
        showArt(null);
        boolean inRadio = radioCards.contains(card);
        // TV row: the dock band slides to the top edge (the original's first-app-row spot).
        // Radio row: sits a third of the way down, with the TV row still peeking above.
        View band = (View) dockRow.getParent();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int target = inRadio ? radioSection.getTop() - (int) (screenH * 0.38f)
                : hero.getTop() + band.getTop() + dockRow.getTop() - dp(22);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
        if (actionCards.contains(card)) channelScroll.smoothScrollTo(0, 0); // the small buttons start the row
        else (inRadio ? radioScroll : channelScroll).reveal(card);
    }

    /** Same width as an app tile in the grid, so the channel row lines up with it. */
    private int tileWidth() {
        int w = getResources().getDisplayMetrics().widthPixels - 2 * dp(68);
        return (w - (cols - 1) * dp(20)) / cols;
    }

    /**
     * Sources (Tvheadend, a network tuner, tuner apps on the box) and the restart option.
     * Credentials stay in this app's private storage. Any one source is enough.
     */
    private interface IoAction { void run() throws java.io.IOException; }

    /** Inputs → Tvheadend server…: what the server is doing, the channel manager, guide refresh and rescan. */
    private void pickServer() {
        Tvh t = Tvh.get(this);
        if (t == null) return;
        String[] items = {getString(R.string.server_status), getString(R.string.server_channels),
                getString(R.string.server_grab_guide), getString(R.string.server_rescan)};
        Ui.menu(this, getString(R.string.server_title), items, i -> {
            switch (i) {
                case 0: ServerStatus.show(this); break;
                case 1: launch(new Intent(this, ChannelsActivity.class)); break;
                case 2:
                    Ui.confirm(this, items[2], getString(R.string.server_grab_confirm), getString(R.string.server_go),
                            () -> serverAction(t::grabGuideNow, getString(R.string.server_grab_started)));
                    break;
                default:
                    Ui.confirm(this, items[3], getString(R.string.server_rescan_confirm), getString(R.string.server_go),
                            () -> serverAction(t::rescanChannels, getString(R.string.server_rescan_started)));
            }
        });
    }

    /** Run a server request off the main thread and say what happened. */
    private void serverAction(IoAction action, String done) {
        Tvh.IO.execute(() -> {
            String msg;
            try { action.run(); msg = done; }
            catch (java.io.IOException e) { msg = e.getMessage() == null ? getString(R.string.tvh_bad_reply) : e.getMessage(); }
            String shown = msg;
            runOnUiThread(() -> android.widget.Toast.makeText(this, shown, android.widget.Toast.LENGTH_LONG).show());
        });
    }

    private void showLiveSetup() {
        android.content.SharedPreferences prefs = Tvh.prefs(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        EditText url = field(form, getString(R.string.main_f_tvh_address), prefs.getString("url", "http://192.168.1.10:9981"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText user = field(form, getString(R.string.main_f_username), prefs.getString("user", ""), InputType.TYPE_CLASS_TEXT);
        EditText pass = field(form, getString(R.string.main_f_password), prefs.getString("pass", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText hdhr = field(form, getString(R.string.main_f_hdhr),
                prefs.getString("hdhr", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        CheckBox tif = new CheckBox(this);
        tif.setText(getString(R.string.main_cb_tif));
        tif.setChecked(prefs.getBoolean("tif", false));
        form.addView(tif, llp(-1, -2, 0, dp(8), 0, 0));
        CheckBox autoplay = new CheckBox(this);
        autoplay.setText(getString(R.string.main_cb_autoplay));
        autoplay.setChecked(prefs.getBoolean("autoplay", false));
        form.addView(autoplay, llp(-1, -2, 0, dp(4), 0, 0));
        TextView result = text(13, "sans-serif", 0xFFFF8A80);
        form.addView(result, llp(-1, -2, 0, dp(6), 0, 0));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(form);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Glass)
                .setTitle(getString(R.string.main_live_tv))
                .setView(sv)
                .setPositiveButton(getString(R.string.main_save), null)
                .setNegativeButton(getString(R.string.main_cancel), null)
                .setNeutralButton(getString(R.string.main_remove_all), (d, w) -> {
                    prefs.edit().remove("url").remove("user").remove("pass").remove("hdhr").remove("tif").apply();
                    Tvh.channels = Collections.emptyList();
                    showLiveState(null);
                })
                .create();
        dialog.setOnShowListener(d -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
                String u = url.getText().toString().trim();
                if (!u.isEmpty() && !u.startsWith("http")) u = "http://" + u;
                String address = u;
                result.setTextColor(0xB3FFFFFF);
                result.setText(getString(R.string.main_checking));
                save.setEnabled(false);
                android.content.SharedPreferences.Editor e = prefs.edit()
                        .remove("live_hidden") // saving setup brings a hidden Live TV row back
                        .putString("hdhr", hdhr.getText().toString().trim())
                        .putBoolean("tif", tif.isChecked())
                        .putBoolean("autoplay", autoplay.isChecked());
                if (address.isEmpty()) e.remove("url").remove("user").remove("pass");
                else e.putString("url", address)
                        .putString("user", user.getText().toString().trim())
                        .putString("pass", pass.getText().toString());
                e.apply();
                Tvh.IO.execute(() -> {
                    // Load every source once: that's the real test, and it fills the rows straight away.
                    String err = null;
                    int count = 0;
                    try {
                        Tvh tvh = Tvh.get(this);
                        if (tvh != null) err = tvh.test();
                        if (err == null && Sources.configured(this)) count = Sources.load(this).size();
                    } catch (java.io.IOException ex) { err = ex.getMessage(); }
                    String er = err;
                    boolean none = er == null && count == 0 && Sources.configured(this);
                    runOnUiThread(() -> {
                        save.setEnabled(true);
                        if (er != null || none) {
                            result.setTextColor(0xFFFF8A80);
                            result.setText(er != null ? er : getString(R.string.main_no_channels_found));
                            return;
                        }
                        dialog.dismiss();
                        refreshLive(true);
                    });
                });
            });
        });
        Ui.frost(dialog).show();
    }

    /**
     * Control Center: the quick things in one panel, tvOS style. Tiles show their current state
     * (network, sleep timer, weather) and do one thing each.
     */
    private void showControlCenter() {
        // tvOS style: no panel, just small frosted tiles and capsules floating over the blurred
        // screen at the top right; a white square for Sleep, round buttons underneath.
        android.app.Dialog dialog = new android.app.Dialog(this, R.style.Glass);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        // Room around the tiles, so a focused one can grow without the window edge cutting it.
        panel.setPadding(dp(14), dp(10), dp(14), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView time = text(13, "sans-serif-medium", Color.WHITE);
        time.setText(clock.getText() + (ampm.getText().length() > 0 ? " " + ampm.getText() : ""));
        head.addView(time, new LinearLayout.LayoutParams(0, -2, 1f));
        String weatherNow = Weather.current(this, false);
        TextView w = text(12, "sans-serif", 0xB3FFFFFF);
        w.setText(weatherNow == null ? "" : weatherNow);
        head.addView(w);
        panel.addView(head, llp(-1, -2, dp(6), 0, dp(6), dp(10)));

        int sleep = SleepTimer.left(this);
        String net = String.valueOf(wifiPill.getText());
        int gap = dp(8), pill = dp(46);

        // Sleep square, with Wi-Fi and Bluetooth capsules stacked beside it.
        LinearLayout r1 = new LinearLayout(this);
        int square = pill * 2 + gap;
        View sleepTile = ccTile("◷", getString(R.string.main_sleep), sleep > 0 ? getResources().getQuantityString(R.plurals.main_sleep_in_min, sleep, sleep) : getString(R.string.main_off), true, dialog, this::pickSleepTimer);
        r1.addView(sleepTile, new LinearLayout.LayoutParams(square, square));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ccTile("◉", "Wi-Fi", net, false, dialog, () -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS))), new LinearLayout.LayoutParams(-1, pill));
        col.addView(ccTile("ᛒ", "Bluetooth", getString(R.string.main_bluetooth_state), false, dialog, this::openBluetooth), llp(-1, pill, 0, gap, 0, 0));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, square, 1f);
        cl.setMargins(gap, 0, 0, 0);
        r1.addView(col, cl);
        panel.addView(r1);

        // Pairs of capsules.
        Object[][] pairs = {
                {"⇥", getString(R.string.main_pill_inputs), getString(R.string.main_live_tv), (Runnable) () -> showInputs(pills.get(1))},
                {"⚙", getString(R.string.main_pill_settings), "Android TV", (Runnable) () -> launch(new Intent(Settings.ACTION_SETTINGS))},
                {"▤", getString(R.string.main_top_shelf), Shelf.modeName(Txt.of(this), Shelf.mode(this)), (Runnable) this::pickShelf},
                {"☰", getString(R.string.main_tv_guide), getString(Tvh.get(this) == null ? R.string.main_needs_tvh : R.string.main_whats_on), (Runnable) () -> { if (Tvh.get(this) != null) launch(new Intent(this, GuideActivity.class)); }},
                {"☁", getString(R.string.main_weather), getString(weatherNow == null ? R.string.main_set_city : R.string.main_next_days), (Runnable) this::showWeatherDetails},
                {"▯", getString(R.string.main_phone_remote), getString(RemoteServer.enabled(this) ? R.string.main_remote_on_qr : R.string.main_off), (Runnable) this::showRemoteSetup},
                {"▣", getString(R.string.main_screensaver), getString(R.string.main_photos_info), (Runnable) this::pickScreensaver},
                {"🌐", getString(R.string.language_title), Lang.shortLabel(Txt.of(this), Lang.choice(this)), (Runnable) this::pickLanguage},
        };
        for (int i = 0; i < pairs.length; i += 2) {
            LinearLayout r = new LinearLayout(this);
            for (int j = 0; j < 2; j++) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, pill, 1f);
                p.setMargins(j == 0 ? 0 : gap / 2, 0, j == 0 ? gap / 2 : 0, 0);
                if (i + j >= pairs.length) { r.addView(new View(this), p); continue; } // odd count: keep the last tile half-width
                Object[] t = pairs[i + j];
                r.addView(ccTile((String) t[0], (String) t[1], (String) t[2], false, dialog, (Runnable) t[3]), p);
            }
            panel.addView(r, llp(-1, -2, 0, gap, 0, 0));
        }

        // Small round buttons. Volume keeps the overlay open, so you can press it a few times.
        LinearLayout r4 = new LinearLayout(this);
        r4.setGravity(Gravity.CENTER_VERTICAL);
        Object[][] round = {
                {"🎙", getString(R.string.main_voice_search), (Runnable) () -> launch(new Intent(this, SearchActivity.class))},
                {"−", getString(R.string.main_volume_down), (Runnable) () -> volume(android.media.AudioManager.ADJUST_LOWER)},
                {"+", getString(R.string.main_volume_up), (Runnable) () -> volume(android.media.AudioManager.ADJUST_RAISE)},
                {"ⓘ", getString(R.string.main_box_status), (Runnable) () -> BoxHealth.show(this)},
                {"⏻", getString(R.string.main_turn_off), (Runnable) () -> {
                    if (SleepTimer.canSleep(this)) SleepTimer.sleepNow(this);
                    else android.widget.Toast.makeText(this, getString(R.string.main_turn_off_needs_perm), android.widget.Toast.LENGTH_LONG).show();
                }},
        };
        int rs = dp(40);
        for (int i = 0; i < round.length; i++) {
            Object[] t = round[i];
            boolean stay = i == 1 || i == 2;
            ImageView b = Ui.iconView(this, sym((String) t[0]), Color.WHITE, 10);
            b.setContentDescription((String) t[1]);
            ccFocus(b, true);
            Runnable action = (Runnable) t[2];
            b.setOnClickListener(v -> { if (!stay) dialog.dismiss(); action.run(); });
            r4.addView(b, llp(rs, rs, 0, 0, i == round.length - 1 ? 0 : dp(10), 0));
        }
        panel.addView(r4, llp(-1, -2, dp(2), dp(12), 0, 0));

        noClip(panel); // focused tiles grow a little: let them spill over their row
        dialog.setContentView(panel);
        android.view.Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            win.setGravity(Gravity.TOP | Gravity.END);
            android.view.WindowManager.LayoutParams lp = win.getAttributes();
            lp.width = dp(368);
            lp.x = dp(34);
            lp.y = dp(46);
            win.setAttributes(lp);
        }
        Ui.frost(dialog).show();
        sleepTile.requestFocus();
    }

    private static void noClip(View v) {
        if (!(v instanceof ViewGroup)) return;
        ((ViewGroup) v).setClipChildren(false);
        ((ViewGroup) v).setClipToPadding(false);
        for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) noClip(((ViewGroup) v).getChildAt(i));
    }

    /** A Control Center tile: small glyph, name and state; the square one is white like tvOS's StandBy. */
    private View ccTile(String glyph, String name, String state, boolean big, android.app.Dialog dialog, Runnable action) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(big ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        t.setGravity(big ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        t.setPadding(big ? dp(8) : dp(14), dp(4), dp(12), dp(4));
        ImageView g = Ui.iconView(this, sym(glyph), Color.WHITE, 0);
        t.addView(g, big ? new LinearLayout.LayoutParams(dp(30), dp(30)) : new LinearLayout.LayoutParams(dp(19), dp(19)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setGravity(big ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        TextView n = text(12, "sans-serif-medium", Color.WHITE);
        n.setText(name);
        n.setSingleLine(true);
        words.addView(n);
        if (!state.isEmpty()) {
            TextView s = text(10, "sans-serif", 0x99FFFFFF);
            s.setText(state);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(s);
        }
        LinearLayout.LayoutParams wl = big ? new LinearLayout.LayoutParams(-2, -2) : new LinearLayout.LayoutParams(0, -2, 1f);
        wl.setMargins(big ? 0 : dp(8), big ? dp(6) : 0, 0, 0);
        t.addView(words, wl);
        ccFocus(t, false);
        t.setOnClickListener(v -> { dialog.dismiss(); action.run(); });
        return t;
    }

    /** Frosted capsule (or circle) with a fine light edge; white with dark text when focused. */
    private void ccFocus(View v, boolean round) {
        GradientDrawable bg = new GradientDrawable();
        if (round) bg.setShape(GradientDrawable.OVAL); else bg.setCornerRadius(dp(26)); // capsule ends
        bg.setColor(0x26FFFFFF);
        bg.setStroke(dp(1), 0x33FFFFFF);
        v.setBackground(bg);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((x, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x26FFFFFF);
            bg.setStroke(dp(1), has ? 0xF2FFFFFF : 0x33FFFFFF);
            tint(x, has);
            x.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start();
        });
    }

    /** Text and icons inside a focused tile turn dark (secondary text a softer dark); back to their own colour after. */
    private static void tint(View v, boolean focused) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (t.getTag() == null) t.setTag(t.getCurrentTextColor());
            int idle = (Integer) t.getTag();
            Ui.setIconColor(t, focused ? ((idle >>> 24) < 0xF0 ? 0x99000000 : 0xFF111111) : idle);
        } else if (v instanceof ImageView) {
            ((ImageView) v).setImageTintList(android.content.res.ColorStateList.valueOf(focused ? 0xFF111111 : Color.WHITE));
        } else if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) tint(((ViewGroup) v).getChildAt(i), focused);
        }
    }

    /** Control Center's icons, by the short key each tile is declared with. */
    private static int sym(String key) {
        switch (key) {
            case "◷": return R.drawable.sym_bedtime;
            case "◉": return R.drawable.sym_wifi;
            case "ᛒ": return R.drawable.sym_bluetooth;
            case "⇥": return R.drawable.sym_input;
            case "⚙": return R.drawable.sym_settings;
            case "▤": return R.drawable.sym_view_carousel;
            case "☰": return R.drawable.sym_view_timeline;
            case "☁": return R.drawable.sym_cloud;
            case "▯": return R.drawable.sym_smartphone;
            case "▣": return R.drawable.sym_screensaver;
            case "🌐": return R.drawable.sym_language;
            case "🎙": return R.drawable.sym_mic;
            case "−": return R.drawable.sym_volume_down;
            case "+": return R.drawable.sym_volume_up;
            case "ⓘ": return R.drawable.sym_info;
            case "⏻": return R.drawable.sym_power_settings_new;
            default: return R.drawable.sym_tune;
        }
    }

    private void volume(int direction) {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        if (am != null) am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, android.media.AudioManager.FLAG_SHOW_UI);
    }

    private void pickSleepTimer() {
        if (!SleepTimer.canSleep(this)) {
            android.widget.Toast.makeText(this, getString(R.string.main_sleep_needs_perm), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        int[] mins = {0, 15, 30, 60, 90, 120};
        String[] labels = {getString(R.string.main_off), getString(R.string.main_sleep_15), getString(R.string.main_sleep_30), getString(R.string.main_sleep_60), getString(R.string.main_sleep_90), getString(R.string.main_sleep_120)};
        Ui.menu(this, getString(R.string.main_sleep_timer), labels, i -> {
            SleepTimer.set(this, mins[i]);
            android.widget.Toast.makeText(this, mins[i] == 0 ? getString(R.string.main_sleep_off_toast) : getString(R.string.main_sleep_standby_in, labels[i]), android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    private void showWeatherDetails() {
        if (Weather.city(this).isEmpty()) { showWeatherSetup(); return; }
        AlertDialog d = new AlertDialog.Builder(this, R.style.Glass)
                .setTitle(Weather.city(this))
                .setMessage(getString(R.string.main_loading))
                .setPositiveButton(getString(R.string.main_close), null)
                .setNeutralButton(getString(R.string.main_change_city), (x, w) -> showWeatherSetup())
                .create();
        Ui.frost(d).show();
        Tvh.IO.execute(() -> {
            String s = Weather.details(this);
            runOnUiThread(() -> { if (d.isShowing()) d.setMessage(s == null ? getString(R.string.main_weather_unreachable) : s); });
        });
    }

    /** Phone remote: on/off, and a QR code (address + PIN) or the address and PIN to pair a phone. */
    private void showRemoteSetup() {
        boolean on = RemoteServer.enabled(this);
        String addr = RemoteServer.address();
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.Glass)
                .setTitle(getString(R.string.main_phone_remote));
        if (on && addr != null) {
            String pin = RemoteServer.pin(this);
            LinearLayout box = new LinearLayout(this);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding(dp(24), dp(8), dp(24), 0);
            ImageView qr = new ImageView(this);
            // The QR carries the PIN too, so scanning it pairs the phone straight away.
            qr.setImageBitmap(qrCode(addr + "/#pin=" + pin, dp(200)));
            box.addView(qr, new LinearLayout.LayoutParams(dp(200), dp(200)));
            TextView t = text(15, "sans-serif", 0xE6FFFFFF);
            t.setLineSpacing(dp(4), 1f);
            t.setText(getString(R.string.main_remote_scan, addr, pin));
            box.addView(t, llp(0, -2, dp(24), 0, 0, 0));
            ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1f;
            b.setView(box);
        } else {
            b.setMessage(getString(on ? R.string.main_remote_no_network : R.string.main_remote_intro));
        }
        b.setPositiveButton(getString(on ? R.string.main_turn_off : R.string.main_turn_on), (d, w) -> {
            RemoteServer.setEnabled(this, !on);
            if (!on) showRemoteSetup();
        }).setNegativeButton(getString(R.string.main_close), null);
        Ui.frost(b.create()).show();
    }

    /** Black-on-white QR code, size × size pixels, with the standard quiet zone. */
    private static Bitmap qrCode(String text, int size) {
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565);
        } catch (com.google.zxing.WriterException e) {
            return null;
        }
    }

    /** City for the weather under the clock; empty turns it off. */
    private void showWeatherSetup() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        EditText city = field(form, getString(R.string.main_f_city), Weather.city(this), InputType.TYPE_CLASS_TEXT);
        TextView result = text(13, "sans-serif", 0xFFFF8A80);
        form.addView(result, llp(-1, -2, 0, dp(6), 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Glass)
                .setTitle(getString(R.string.main_weather))
                .setView(form)
                .setPositiveButton(getString(R.string.main_save), null)
                .setNegativeButton(getString(R.string.main_cancel), null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = city.getText().toString();
            result.setTextColor(0xB3FFFFFF);
            result.setText(getString(R.string.main_looking_up));
            Tvh.IO.execute(() -> {
                String err = Weather.setCity(this, name);
                runOnUiThread(() -> {
                    if (err != null) { result.setTextColor(0xFFFF8A80); result.setText(err); return; }
                    dialog.dismiss();
                    updateWeather();
                });
            });
        }));
        Ui.frost(dialog).show();
    }

    private void updateWeather() {
        String cached = Weather.current(this, false);
        weather.setText(cached == null ? "" : cached + "  ·  " + Weather.city(this));
        weather.setVisibility(cached == null ? View.GONE : View.VISIBLE);
        if (Weather.city(this).isEmpty()) return;
        Tvh.IO.execute(() -> {
            String s = Weather.current(this, true);
            if (s != null && !s.equals(cached)) runOnUiThread(this::updateWeather);
        });
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
        greeting.setText(getString(h < 5 ? R.string.main_greeting_night : h < 12 ? R.string.main_greeting_morning : h < 17 ? R.string.main_greeting_afternoon : R.string.main_greeting_evening));
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : getString(h < 12 ? R.string.main_am : R.string.main_pm));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
    }

    private void updateWifi() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
                if (wm.getConnectionInfo().getNetworkId() == -1) name = getString(R.string.main_offline);
            }
        } catch (SecurityException ignored) { }
        wifiPill.setText(name);
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
    /** Photos sent from the phone remote, oldest first. */
    static java.io.File[] userWallpapers(Context c) {
        java.io.File[] f = new java.io.File(c.getFilesDir(), "wallpapers").listFiles();
        if (f == null) return new java.io.File[0];
        Arrays.sort(f, (a, b) -> a.getName().compareTo(b.getName()));
        return f;
    }

    /** Your photos (only them, if you chose that on the phone), then the built-in ones. */
    private List<String> wallpaperList() {
        List<String> out = new ArrayList<>();
        for (java.io.File f : userWallpapers(this)) out.add(f.getAbsolutePath());
        if (out.isEmpty() || !Tvh.prefs(this).getBoolean("wall_mine_only", false)) {
            for (String w : wallpapers) out.add("wallpapers/" + w);
        }
        return out;
    }

    /** A photo just arrived from the phone: show it now (the phone remote calls this). */
    static void wallpaperAdded(String path) {
        MainActivity m = current == null ? null : current.get();
        if (m == null) return;
        m.runOnUiThread(() -> {
            if (m.isDestroyed()) return;
            m.wallIndex = Math.max(-1, m.wallpaperList().indexOf(path) - 1); // nextWallpaper steps onto it
            m.nextWallpaper();
        });
    }

    private static java.lang.ref.WeakReference<MainActivity> current;

    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        List<String> list = wallpaperList();
        if (list.isEmpty()) return;
        if (wallIndex == -2) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % list.size();
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = list.get(wallIndex);
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = name.startsWith("/") ? new java.io.FileInputStream(name) : getAssets().open(name)) {
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
        titleBox.setAlpha(0f);
        titleBox.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
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

    /**
     * Bluetooth settings. Phones and some TVs answer the standard intent; Android TV's own Settings
     * (the Xiaomi box) doesn't, and offers the "add accessory" pairing screen instead. Last resort:
     * the main Settings app, so the tile never does nothing.
     */
    private void openBluetooth() {
        if (launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) return;
        // The accessory screen refuses callers without BLUETOOTH_CONNECT (a runtime permission since Android 12).
        if (android.os.Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
            return;
        }
        if (launch(new Intent("com.google.android.intent.action.CONNECT_INPUT"))) return;
        launch(new Intent(Settings.ACTION_SETTINGS));
    }

    private static final int REQUEST_BLUETOOTH = 41;

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQUEST_BLUETOOTH) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openBluetooth();
        else launch(new Intent(Settings.ACTION_SETTINGS)); // refused: general Settings, and don't ask again
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
