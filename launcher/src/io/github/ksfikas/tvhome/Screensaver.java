package io.github.ksfikas.tvhome;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The screensaver (an Android "dream"): your photos drifting slowly under the clock, with a news
 * ticker along the bottom and, in turn, the weather, what is on TV now, popular films and the day's
 * headlines. It uses what the launcher already has (weather, Tvheadend guide, the Top Shelf's news
 * feed, the wallpapers), so there is nothing extra to set up beyond choosing it:
 * adb shell settings put secure screensaver_components io.github.ksfikas.tvhome/.Screensaver
 * Each part can be switched off in Control Center → Screensaver.
 *
 * Sizes are in dp for the box's 960 x 540 dp screen: below the clock and above the ticker there is
 * only about 330 dp, so every slide has to fit in that.
 */
public class Screensaver extends DreamService {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }
    private static final long FADE_MS = 1_600, REFRESH_MS = 45 * 60_000L;

    // Options (Control Center → Screensaver). All on by default; the slide time is in seconds.
    static final String WEATHER = "ss_weather", TV = "ss_tv", FILMS = "ss_films", NEWS = "ss_news", TICKER = "ss_ticker";
    static final int[] SECONDS = {8, 16, 30};

    static boolean option(Context c, String key) { return Tvh.prefs(c).getBoolean(key, true); }
    static void toggle(Context c, String key) { Tvh.prefs(c).edit().putBoolean(key, !option(c, key)).apply(); }
    static int seconds(Context c) { return Tvh.prefs(c).getInt("ss_secs", 16); }
    static void nextSeconds(Context c) {
        int cur = seconds(c), next = SECONDS[0];
        for (int i = 0; i < SECONDS.length; i++) if (SECONDS[i] == cur) next = SECONDS[(i + 1) % SECONDS.length];
        Tvh.prefs(c).edit().putInt("ss_secs", next).apply();
    }

    /** One programme on now, for the "On TV" slide. */
    static final class Row {
        String channel, title, next;
        long stop; // epoch seconds
    }

    private static final class Slide {
        final View view; // null: just the photo
        Slide(View view) { this.view = view; }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout bgHolder, content, tickerBar;
    private ImageView bgA, bgB, bgFront;
    private View textScrim, current;
    private TextView weatherChip, ticker;
    private List<Slide> slides = new ArrayList<>();
    private List<String> photos = new ArrayList<>();
    private int slideIndex = -1, photoIndex;
    private boolean running, firstShown;
    private final Runnable tick = this::next, refresh = this::load;

    // The ticker scrolls by hand (once per display frame) rather than with TextView's marquee, which
    // doesn't reliably start when the text is set before the view has been laid out.
    private long tickerT0;
    private float tickerFrom, tickerTo;
    private final Runnable scroll = new Runnable() {
        @Override public void run() {
            if (!running || tickerBar.getVisibility() != View.VISIBLE) return;
            float travel = tickerFrom - tickerTo, speed = Ui.dp(Screensaver.this, 70) / 1000f; // px per ms
            ticker.setTranslationX(tickerFrom - ((SystemClock.uptimeMillis() - tickerT0) * speed) % travel);
            ticker.postOnAnimation(this);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // any key or touch wakes it
        setFullscreen(true);
        setScreenBright(true);
        setContentView(buildRoot());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        running = true;
        load();
    }

    @Override
    public void onDreamingStopped() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        ticker.removeCallbacks(scroll);
        for (ImageView v : new ImageView[]{bgA, bgB}) {
            v.animate().cancel();
            Object a = v.getTag();
            if (a instanceof Animator) ((Animator) a).cancel();
            v.setImageDrawable(null);
        }
        super.onDreamingStopped();
    }

    // ---------------------------------------------------------------- the fixed layer: photo, clock, weather, ticker

    private View buildRoot() {
        int side = Ui.dp(this, 80);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        bgHolder = new FrameLayout(this);
        bgA = photoView();
        bgB = photoView();
        bgFront = bgA;
        bgHolder.addView(bgA, new FrameLayout.LayoutParams(-1, -1));
        bgHolder.addView(bgB, new FrameLayout.LayoutParams(-1, -1));
        root.addView(bgHolder, new FrameLayout.LayoutParams(-1, -1));

        // Dark at the top and bottom edge, where the clock and the ticker sit.
        View edges = new View(this);
        edges.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x00000000, 0x00000000, 0xCC000000}));
        root.addView(edges, new FrameLayout.LayoutParams(-1, -1));

        // Behind the text slides, so the words stay readable over any photo.
        textScrim = new View(this);
        textScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xCC000000, 0x99000000, 0x00000000}));
        textScrim.setAlpha(0f);
        root.addView(textScrim, new FrameLayout.LayoutParams(-1, -1));

        // The slides get the space between the clock and the ticker (~330 dp).
        content = new FrameLayout(this);
        content.setPadding(side, Ui.dp(this, 140), side, Ui.dp(this, 76));
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextSize(64);
        time.setTypeface(android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL));
        time.setTextColor(Color.WHITE);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE  d MMMM");
        date.setFormat24Hour("EEEE  d MMMM");
        date.setAllCaps(true);
        date.setTextSize(14);
        date.setLetterSpacing(0.15f);
        date.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        date.setTextColor(0xCCFFFFFF);
        clockBox.addView(time);
        clockBox.addView(date);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cp.setMargins(side, Ui.dp(this, 36), 0, 0);
        root.addView(clockBox, cp);

        weatherChip = Ui.text(this, 22, "sans-serif-light", Color.WHITE);
        weatherChip.setVisibility(View.GONE);
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        wp.setMargins(0, Ui.dp(this, 56), side, 0);
        root.addView(weatherChip, wp);

        // A strip along the bottom; the headline text slides through it (see scroll).
        tickerBar = new FrameLayout(this);
        tickerBar.setBackgroundColor(0x66000000);
        tickerBar.setVisibility(View.GONE);
        ticker = Ui.text(this, 20, "sans-serif", 0xE6FFFFFF);
        ticker.setSingleLine(true);
        ticker.setGravity(Gravity.CENTER_VERTICAL);
        tickerBar.addView(ticker, new FrameLayout.LayoutParams(-2, -1));
        root.addView(tickerBar, new FrameLayout.LayoutParams(-1, Ui.dp(this, 60), Gravity.BOTTOM));
        return root;
    }

    private ImageView photoView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        return v;
    }

    private void startTicker(String line) {
        ticker.removeCallbacks(scroll);
        if (line.isEmpty()) { tickerBar.setVisibility(View.GONE); return; }
        ticker.setText(line);
        float w = ticker.getPaint().measureText(line);
        ticker.getLayoutParams().width = (int) Math.ceil(w) + 4;
        ticker.requestLayout();
        tickerFrom = getResources().getDisplayMetrics().widthPixels;
        tickerTo = -w - 4;
        tickerT0 = SystemClock.uptimeMillis();
        ticker.setTranslationX(tickerFrom);
        tickerBar.setVisibility(View.VISIBLE);
        ticker.postOnAnimation(scroll);
    }

    // ---------------------------------------------------------------- data

    /** Gather everything off the UI thread, then rebuild the slides; repeats every 45 minutes. */
    private void load() {
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, REFRESH_MS);
        final Context c = getApplicationContext();
        final boolean wantWeather = option(c, WEATHER), wantTv = option(c, TV), wantFilms = option(c, FILMS),
                wantNews = option(c, NEWS), wantTicker = option(c, TICKER);
        Tvh.IO.execute(() -> {
            String[] weather = wantWeather ? Weather.glance(c) : null;
            List<Row> tv = wantTv ? onTv(c) : new ArrayList<>();
            List<Shelf.Item> films = wantFilms ? Shelf.films(Txt.of(c)) : new ArrayList<>();
            List<Shelf.Item> news = wantNews || wantTicker ? Shelf.news(Txt.of(c), Shelf.feed(c)) : new ArrayList<>();
            List<String> list = photoList(c);
            Map<String, Bitmap> pics = new HashMap<>();
            int shown = 0;
            for (Shelf.Item it : films) {
                if (it.artUrl == null || shown >= 5) continue;
                Bitmap b = Shelf.image(c, it.artUrl, 400);
                if (b != null) { pics.put(it.artUrl, b); shown++; }
            }
            shown = 0;
            if (wantNews) for (Shelf.Item it : news) {
                if (it.artUrl == null || shown >= 4) continue;
                Bitmap b = Shelf.image(c, it.artUrl, 800);
                if (b != null) { pics.put(it.artUrl, b); shown++; }
            }
            handler.post(() -> { if (running) build(weather, tv, films, wantNews ? news : new ArrayList<>(), wantTicker ? tickerText(news) : "", list, pics); });
        });
    }

    private void build(String[] weather, List<Row> tv, List<Shelf.Item> films, List<Shelf.Item> news, String line,
                       List<String> list, Map<String, Bitmap> pics) {
        photos = list;
        if (weather != null) {
            weatherChip.setText(weather[0] + "   " + weather[1] + "   ·   " + Weather.city(this));
            weatherChip.setVisibility(View.VISIBLE);
        } else {
            weatherChip.setVisibility(View.GONE);
        }
        startTicker(line);

        // A photo on its own between every information slide, so it still feels like a picture frame.
        List<Slide> out = new ArrayList<>();
        out.add(new Slide(null));
        if (weather != null) { out.add(weatherSlide(weather)); out.add(new Slide(null)); }
        if (!tv.isEmpty()) { out.add(tvSlide(tv)); out.add(new Slide(null)); }
        if (!films.isEmpty()) { out.add(filmsSlide(films, pics)); out.add(new Slide(null)); }
        for (int i = 0; i < news.size() && i < 4; i++) {
            out.add(newsSlide(news.get(i), pics.get(news.get(i).artUrl)));
            if (i == 1) out.add(new Slide(null));
        }
        out.add(new Slide(null));
        slides = out;
        if (slideIndex < 0) next(); // first load starts the show; later ones just swap the list
    }

    // ---------------------------------------------------------------- the show

    private void next() {
        handler.removeCallbacks(tick);
        if (!running || slides.isEmpty()) return;
        handler.postDelayed(tick, seconds(this) * 1000L);
        slideIndex = (slideIndex + 1) % slides.size();
        final Slide s = slides.get(slideIndex);
        final String photo = nextPhoto();
        Tvh.IO.execute(() -> {
            Bitmap bm = photo == null ? null : decode(photo);
            handler.post(() -> show(s, bm));
        });
    }

    private void show(Slide s, Bitmap bm) {
        if (!running) return;
        if (bm != null) {
            final ImageView in = bgFront == bgA ? bgB : bgA, out = bgFront;
            bgFront = in;
            in.setImageBitmap(bm);
            in.setAlpha(0f);
            in.setScaleX(1f);
            in.setScaleY(1f);
            in.setPivotX(in.getWidth() * (slideIndex % 2 == 0 ? 0.35f : 0.65f)); // drift towards alternate sides
            in.setPivotY(in.getHeight() * (slideIndex % 3 == 0 ? 0.4f : 0.6f));
            bgHolder.bringChildToFront(in);
            in.animate().alpha(1f).setDuration(firstShown ? FADE_MS : 800).withEndAction(() -> {
                Object a = out.getTag();
                if (a instanceof Animator) ((Animator) a).cancel();
                out.setImageDrawable(null); // release the previous photo
            }).start();
            ObjectAnimator zoom = ObjectAnimator.ofPropertyValuesHolder(in,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.1f));
            zoom.setDuration(seconds(this) * 1000L + 2 * FADE_MS);
            zoom.setInterpolator(new LinearInterpolator());
            in.setTag(zoom);
            zoom.start();
            firstShown = true;
        }

        final View old = current;
        current = s.view;
        if (old != null && old != s.view) {
            old.animate().cancel();
            old.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(() -> content.removeView(old)).start();
        }
        if (s.view != null) {
            s.view.animate().cancel();
            if (s.view.getParent() != null) ((ViewGroup) s.view.getParent()).removeView(s.view);
            content.addView(s.view, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
            s.view.setAlpha(0f);
            s.view.setTranslationY(Ui.dp(this, 16));
            s.view.animate().alpha(1f).translationY(0f).setStartDelay(500).setDuration(FADE_MS).start();
        }
        textScrim.animate().alpha(s.view == null ? 0f : 1f).setDuration(FADE_MS).start();
    }

    // ---------------------------------------------------------------- slides

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, 13, "sans-serif-medium", 0xB3FFFFFF);
        t.setAllCaps(true);
        t.setLetterSpacing(0.2f);
        t.setText(s);
        t.setPadding(0, 0, 0, Ui.dp(this, 12));
        return t;
    }

    private TextView text(String s, float sp, String family, int color) {
        TextView t = Ui.text(this, sp, family, color);
        t.setText(s);
        return t;
    }

    private Slide weatherSlide(String[] w) {
        LinearLayout v = column();
        v.addView(label("Weather  ·  " + Weather.city(this)));
        v.addView(text(w[0], 72, "sans-serif-thin", Color.WHITE));
        v.addView(text(w[1], 26, "sans-serif-light", Color.WHITE));
        TextView extra = text(w[2], 14, "sans-serif", 0xCCFFFFFF);
        extra.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 14));
        v.addView(extra);
        for (int i = 3; i < w.length && i < 6; i++) { // today and the two days after
            String[] p = w[i].split("\t");
            if (p.length < 3) continue;
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
            row.addView(cell(p[0], 19, "sans-serif-medium", Color.WHITE, 120));
            row.addView(cell(p[1], 19, "sans-serif-light", Color.WHITE, 120));
            row.addView(cell(p[2], 19, "sans-serif-light", 0xCCFFFFFF, 220));
            v.addView(row);
        }
        return new Slide(v);
    }

    private TextView cell(String s, float sp, String family, int color, int widthDp) {
        TextView t = text(s, sp, family, color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, widthDp), -2));
        return t;
    }

    private Slide tvSlide(List<Row> rows) {
        LinearLayout v = column();
        v.addView(label("On TV now"));
        for (int i = 0; i < rows.size() && i < 5; i++) {
            Row r = rows.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            row.addView(cell(r.channel, 16, "sans-serif-medium", 0xCCFFFFFF, 150));
            LinearLayout titles = column();
            titles.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 450), -2));
            TextView title = text(r.title, 21, "sans-serif-light", Color.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            titles.addView(title);
            if (r.next != null) {
                TextView next = text("Next: " + r.next, 13, "sans-serif", 0x99FFFFFF);
                next.setSingleLine(true);
                next.setEllipsize(TextUtils.TruncateAt.END);
                titles.addView(next);
            }
            row.addView(titles);
            row.addView(text("until " + Ui.time(this, r.stop), 15, "sans-serif", 0xB3FFFFFF));
            v.addView(row);
        }
        return new Slide(v);
    }

    private Slide filmsSlide(List<Shelf.Item> films, Map<String, Bitmap> pics) {
        LinearLayout v = column();
        v.addView(label("Popular films this week"));
        LinearLayout posters = new LinearLayout(this);
        int shown = 0;
        for (Shelf.Item f : films) {
            Bitmap b = pics.get(f.artUrl);
            if (b == null || shown >= 5) continue;
            shown++;
            LinearLayout card = column();
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(this, 118), -2);
            cp.rightMargin = Ui.dp(this, 20);
            card.setLayoutParams(cp);
            ImageView img = rounded(new ImageView(this), 8);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageBitmap(b);
            card.addView(img, new LinearLayout.LayoutParams(Ui.dp(this, 118), Ui.dp(this, 177)));
            TextView name = text(f.title, 13, "sans-serif-medium", Color.WHITE);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, Ui.dp(this, 8), 0, 0);
            card.addView(name);
            posters.addView(card);
        }
        v.addView(posters);
        return new Slide(v);
    }

    private Slide newsSlide(Shelf.Item n, Bitmap photo) {
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = column();
        words.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        words.addView(label(n.sub == null ? "News" : n.sub));
        TextView head = text(n.title, 28, "sans-serif-light", Color.WHITE);
        head.setMaxLines(4);
        head.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(head);
        if (n.summary != null && !n.summary.isEmpty()) {
            TextView sum = text(n.summary, 15, "sans-serif", 0xCCFFFFFF);
            sum.setMaxLines(3);
            sum.setEllipsize(TextUtils.TruncateAt.END);
            sum.setPadding(0, Ui.dp(this, 12), 0, 0);
            words.addView(sum);
        }
        v.addView(words);
        if (photo != null) {
            ImageView img = rounded(new ImageView(this), 10);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageBitmap(photo);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 280), Ui.dp(this, 175));
            lp.leftMargin = Ui.dp(this, 40);
            v.addView(img, lp);
        }
        return new Slide(v);
    }

    private <V extends View> V rounded(V v, float radiusDp) {
        final int r = Ui.dp(this, radiusDp);
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r); }
        });
        return v;
    }

    // ---------------------------------------------------------------- photos

    /** Your photos (only them, if you chose that on the phone), then the built-in ones, shuffled. */
    private List<String> photoList(Context c) {
        List<String> out = new ArrayList<>();
        for (File f : MainActivity.userWallpapers(c)) out.add(f.getAbsolutePath());
        if (out.isEmpty() || !Tvh.prefs(c).getBoolean("wall_mine_only", false)) {
            try {
                String[] names = c.getAssets().list("wallpapers");
                if (names != null) for (String n : names) out.add("wallpapers/" + n);
            } catch (IOException ignored) { }
        }
        Collections.shuffle(out);
        return out;
    }

    private String nextPhoto() {
        if (photos.isEmpty()) return null;
        return photos.get(photoIndex++ % photos.size());
    }

    /** Decoded down to roughly screen width, so a big photo doesn't cost a big bitmap. */
    private Bitmap decode(String name) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = open(name)) { BitmapFactory.decodeStream(in, null, o); }
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 1920) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            try (InputStream in = open(name)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (IOException e) {
            return null;
        }
    }

    private InputStream open(String name) throws IOException {
        return name.startsWith("/") ? new java.io.FileInputStream(name) : getAssets().open(name);
    }

    // ---------------------------------------------------------------- pure helpers (unit tested)

    /** Headlines for the ticker, in one line. */
    static String tickerText(List<Shelf.Item> news) {
        StringBuilder s = new StringBuilder();
        for (Shelf.Item n : news) {
            if (n.title == null || n.title.isEmpty()) continue;
            if (s.length() > 0) s.append("      •      ");
            s.append(n.title.replaceAll("\\s+", " "));
        }
        return s.toString();
    }

    private static List<Row> onTv(Context c) {
        Tvh t = Tvh.get(c);
        if (t == null) return new ArrayList<>();
        try {
            List<Tvh.Channel> channels = Tvh.channels.isEmpty() ? t.loadChannels() : Tvh.channels;
            long now = System.currentTimeMillis() / 1000;
            return pick(channels, Tvh.favourites(c), t.window(now, now + 3 * 3600), now, 5);
        } catch (Exception e) {
            return new ArrayList<>(); // server asleep or unreachable: no TV slide this time
        }
    }

    /**
     * What is on right now: your favourite channels first (in your order), then the others by
     * channel number, up to max. Radio and non-Tvheadend channels are skipped, as are channels with
     * no guide data.
     */
    static List<Row> pick(List<Tvh.Channel> channels, List<String> favourites,
                          Map<String, List<Tvh.Event>> window, long nowSec, int max) {
        List<Tvh.Channel> order = new ArrayList<>();
        for (String uuid : favourites) for (Tvh.Channel ch : channels) if (uuid.equals(ch.uuid)) order.add(ch);
        for (Tvh.Channel ch : channels) if (!order.contains(ch)) order.add(ch);
        List<Row> out = new ArrayList<>();
        for (Tvh.Channel ch : order) {
            if (out.size() >= max) break;
            if (ch.radio || !ch.fromTvh()) continue;
            List<Tvh.Event> events = window.get(ch.uuid);
            if (events == null) continue;
            Tvh.Event on = null, after = null;
            for (Tvh.Event e : events) {
                if (e.isOn(nowSec)) on = e;
                else if (on != null && after == null && e.start >= on.stop) after = e;
            }
            if (on == null || on.title == null || on.title.isEmpty()) continue;
            Row r = new Row();
            r.channel = ch.name;
            r.title = on.title;
            r.stop = on.stop;
            r.next = after == null ? null : after.title;
            out.add(r);
        }
        return out;
    }
}
