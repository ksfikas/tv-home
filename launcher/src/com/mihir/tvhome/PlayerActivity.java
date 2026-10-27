package com.mihir.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen live TV. Up/Down or Channel+/- zap, Left jumps back to the previous channel,
 * OK opens the channel list, Right/Info shows the now/next banner, number keys tune directly.
 * The player is released whenever the screen leaves the foreground so it holds no memory at home.
 */
public class PlayerActivity extends Activity {

    static final String EXTRA_CHANNEL = "channel";

    private Tvh tvh;
    private List<Tvh.Channel> list = new ArrayList<>();
    private int index = -1, previousIndex = -1;

    private ExoPlayer player;
    private AspectFrame videoFrame;
    private SurfaceView surface;
    private TextView status;
    private LinearLayout banner;
    private ImageView bannerLogo;
    private TextView bannerNumber, bannerName, bannerNow, bannerTimes, bannerNext, digits;
    private ProgressBar bannerProgress;
    private FrameLayout listPanel;
    private LinearLayout listRows;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideBanner = () -> banner.animate().alpha(0f).setDuration(250).start();
    private final Runnable tuneNow = this::startPlayback;
    private final Runnable commitDigits = this::commitDigits;
    private final Runnable retry = this::startPlayback;
    private int retries;
    private long tuneSeq;
    private final StringBuilder typed = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        tvh = Tvh.get(this);
        if (tvh == null) { finish(); return; }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        String uuid = i.getStringExtra(EXTRA_CHANNEL);
        if (uuid == null) uuid = Tvh.prefs(this).getString("last_channel", null);
        String target = uuid;
        list = new ArrayList<>(Tvh.channels);
        if (!list.isEmpty()) { tuneTo(find(target), false); return; }
        // Started cold (e.g. at boot): fetch the channel list first.
        status("Loading channels…");
        Tvh.IO.execute(() -> {
            try {
                List<Tvh.Channel> loaded = tvh.loadChannels();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    list = new ArrayList<>(loaded);
                    if (list.isEmpty()) { status("No channels on the Tvheadend server"); return; }
                    tuneTo(find(target), false);
                });
            } catch (java.io.IOException e) {
                runOnUiThread(() -> status(e.getMessage()));
            }
        });
    }

    private int find(String uuid) {
        if (uuid != null) for (int i = 0; i < list.size(); i++) if (uuid.equals(list.get(i).uuid)) return i;
        return list.isEmpty() ? -1 : 0;
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        if (index >= 0 && player == null) startPlayback();
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
    }

    // ---------------------------------------------------------------- playback

    /** Select a channel; the stream starts after a short pause so fast zapping doesn't open every channel. */
    private void tuneTo(int i, boolean debounce) {
        if (i < 0 || i >= list.size()) return;
        if (index >= 0 && i != index) previousIndex = index;
        index = i;
        retries = 0;
        Tvh.prefs(this).edit().putString("last_channel", list.get(i).uuid).apply();
        showBanner();
        handler.removeCallbacks(tuneNow);
        handler.removeCallbacks(retry);
        if (debounce) handler.postDelayed(tuneNow, 450);
        else startPlayback();
    }

    private void startPlayback() {
        if (index < 0) return;
        Tvh.Channel ch = list.get(index);
        long seq = ++tuneSeq;
        status("");
        if (player != null) player.stop();
        Tvh.IO.execute(() -> {
            String url;
            try {
                url = tvh.streamUrl(ch.uuid);
            } catch (java.io.IOException e) {
                runOnUiThread(() -> { if (seq == tuneSeq) failed(e.getMessage()); });
                return;
            }
            runOnUiThread(() -> {
                // Stale (zapped again, or the screen was left) results are dropped.
                if (seq != tuneSeq || isDestroyed() || !visible) return;
                play(url);
            });
        });
    }

    private boolean visible;

    private void play(String url) {
        if (player == null) player = buildPlayer();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("tvhome")
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000);
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        player.setMediaSource(new ProgressiveMediaSource.Factory(http, extractors)
                .createMediaSource(MediaItem.fromUri(url)));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private ExoPlayer buildPlayer() {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        DefaultTrackSelector tracks = new DefaultTrackSelector(this);
        // Tunneled playback lets the Amlogic video pipeline handle A/V sync and deinterlacing itself.
        boolean tunnel = Tvh.prefs(this).getBoolean("tunneling", false);
        tracks.setParameters(tracks.buildUponParameters().setTunnelingEnabled(tunnel));
        // Live TV: start fast, keep a modest buffer (memory is tight on 2 GB boxes).
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 12000, 700, 1500)
                .build();
        ExoPlayer p = new ExoPlayer.Builder(this, renderers)
                .setTrackSelector(tracks)
                .setLoadControl(load)
                .build();
        p.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        p.setVideoSurfaceView(surface);
        p.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) { retries = 0; status(""); }
                else if (state == Player.STATE_BUFFERING) status("…");
                else if (state == Player.STATE_ENDED) failed("Stream ended");
            }

            @Override public void onPlayerError(PlaybackException e) {
                failed(describe(e));
            }

            @Override public void onVideoSizeChanged(VideoSize v) {
                if (v.width > 0 && v.height > 0) videoFrame.setAspect(v.width * v.pixelWidthHeightRatio / v.height);
            }
        });
        return p;
    }

    /** Retry a few times (tickets expire, tuners get busy), then say what went wrong. */
    private void failed(String why) {
        if (retries < 3) {
            retries++;
            status("Reconnecting…");
            handler.removeCallbacks(retry);
            handler.postDelayed(retry, 1500L * retries);
        } else {
            status(why == null ? "Can't play this channel" : why);
        }
    }

    private static String describe(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "Tvheadend refused the stream (no free tuner, or no access to this channel)";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "Can't reach Tvheadend";
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return "This box can't decode this channel's format";
            default:
                return "Playback error: " + e.getErrorCodeName();
        }
    }

    private void releasePlayer() {
        tuneSeq++;
        if (player != null) { player.release(); player = null; }
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (listPanel.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT) { closeList(); return true; }
            return super.onKeyDown(code, e);
        }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            typeDigit((char) ('0' + code - KeyEvent.KEYCODE_0));
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                zap(+1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                zap(-1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (previousIndex >= 0 && previousIndex < list.size()) tuneTo(previousIndex, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_INFO:
                showBanner(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (typed.length() > 0) commitDigits();
                else openList();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (banner.getAlpha() > 0.5f) { handler.removeCallbacks(hideBanner); hideBanner.run(); return true; }
                finish(); return true;
        }
        return super.onKeyDown(code, e);
    }

    private void zap(int dir) {
        if (list.isEmpty()) return;
        int n = list.size();
        tuneTo(((index + dir) % n + n) % n, true);
    }

    private void typeDigit(char c) {
        if (typed.length() >= 4) typed.setLength(0);
        typed.append(c);
        digits.setText(typed);
        digits.setVisibility(View.VISIBLE);
        handler.removeCallbacks(commitDigits);
        handler.postDelayed(commitDigits, 1600);
    }

    private void commitDigits() {
        handler.removeCallbacks(commitDigits);
        digits.setVisibility(View.GONE);
        if (typed.length() == 0) return;
        double want = Double.parseDouble(typed.toString());
        typed.setLength(0);
        for (int i = 0; i < list.size(); i++) if (list.get(i).number == want) { tuneTo(i, false); return; }
        status("No channel " + (long) want);
        handler.postDelayed(() -> status(""), 2000);
    }

    // ---------------------------------------------------------------- banner

    private void showBanner() {
        if (index < 0) return;
        Tvh.Channel ch = list.get(index);
        bannerNumber.setText(ch.numberLabel());
        bannerName.setText(ch.name);
        bannerLogo.setImageBitmap(null);
        setNow(ch.now, null);
        banner.animate().cancel();
        banner.setAlpha(1f);
        handler.removeCallbacks(hideBanner);
        handler.postDelayed(hideBanner, 5000);
        Tvh.IO.execute(() -> {
            android.graphics.Bitmap logo = tvh.logo(this, ch, dp(120));
            List<Tvh.Event> events = null;
            try { events = tvh.upcoming(ch.uuid, 3); } catch (java.io.IOException ignored) { }
            List<Tvh.Event> ev = events;
            runOnUiThread(() -> {
                if (isDestroyed() || index < 0 || list.get(index) != ch) return;
                bannerLogo.setImageBitmap(logo);
                if (ev != null && !ev.isEmpty()) {
                    ch.now = ev.get(0);
                    setNow(ev.get(0), ev.size() > 1 ? ev.get(1) : null);
                }
            });
        });
    }

    private void setNow(Tvh.Event now, Tvh.Event next) {
        SimpleDateFormat hm = new SimpleDateFormat(
                android.text.format.DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm a", Locale.getDefault());
        if (now == null) {
            bannerNow.setText("No programme information");
            bannerTimes.setText("");
            bannerProgress.setVisibility(View.INVISIBLE);
        } else {
            bannerNow.setText(now.subtitle == null ? now.title : now.title + "  ·  " + now.subtitle);
            bannerTimes.setText(hm.format(new Date(now.start * 1000)) + " – " + hm.format(new Date(now.stop * 1000)));
            bannerProgress.setVisibility(View.VISIBLE);
            bannerProgress.setProgress((int) (now.progress(System.currentTimeMillis() / 1000) * 1000));
        }
        bannerNext.setText(next == null ? "" : "Next  " + hm.format(new Date(next.start * 1000)) + "  " + next.title);
    }

    // ---------------------------------------------------------------- channel list

    private void openList() {
        if (list.isEmpty()) return;
        listRows.removeAllViews();
        View current = null;
        for (int i = 0; i < list.size(); i++) {
            Tvh.Channel ch = list.get(i);
            TextView row = text(15, "sans-serif", Color.WHITE);
            String now = ch.now == null ? "" : "   " + ch.now.title;
            row.setText(ch.numberLabel() + "   " + ch.name + now);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setFocusable(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(Color.TRANSPARENT);
            row.setBackground(bg);
            row.setOnFocusChangeListener((v, has) -> {
                bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
                ((TextView) v).setTextColor(has ? 0xFF111111 : Color.WHITE);
            });
            int target = i;
            row.setOnClickListener(v -> { closeList(); if (target != index) tuneTo(target, false); });
            listRows.addView(row, new LinearLayout.LayoutParams(-1, dp(40)));
            if (i == index) current = row;
        }
        listPanel.setVisibility(View.VISIBLE);
        View focus = current != null ? current : listRows.getChildAt(0);
        focus.post(focus::requestFocus);
    }

    private void closeList() {
        listPanel.setVisibility(View.GONE);
        listRows.removeAllViews();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoFrame = new AspectFrame(this);
        surface = new SurfaceView(this);
        videoFrame.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        root.addView(videoFrame, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        status = text(18, "sans-serif-light", Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setShadowLayer(dp(8), 0, 0, 0xFF000000);
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        digits = text(42, "sans-serif-light", Color.WHITE);
        digits.setShadowLayer(dp(8), 0, 0, 0xFF000000);
        digits.setVisibility(View.GONE);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        dl.setMargins(0, dp(32), dp(48), 0);
        root.addView(digits, dl);

        // Now/next banner along the bottom.
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(24), dp(18), dp(28), dp(18));
        GradientDrawable bbg = new GradientDrawable();
        bbg.setCornerRadius(dp(18));
        bbg.setColor(0xE61C1C1E);
        bbg.setStroke(dp(1), 0x1FFFFFFF);
        banner.setBackground(bbg);
        banner.setAlpha(0f);

        bannerLogo = new ImageView(this);
        bannerLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        banner.addView(bannerLogo, new LinearLayout.LayoutParams(dp(110), dp(62)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, -2, 1f);
        colLp.setMargins(dp(20), 0, 0, 0);
        banner.addView(textCol, colLp);

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(Gravity.BOTTOM);
        bannerNumber = text(22, "sans-serif-medium", 0xB3FFFFFF);
        nameRow.addView(bannerNumber, new LinearLayout.LayoutParams(-2, -2));
        bannerName = text(22, "sans-serif", Color.WHITE);
        bannerName.setSingleLine(true);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f);
        nl.setMargins(dp(12), 0, dp(12), 0);
        nameRow.addView(bannerName, nl);
        bannerTimes = text(14, "sans-serif-medium", 0xB3FFFFFF);
        nameRow.addView(bannerTimes, new LinearLayout.LayoutParams(-2, -2));
        textCol.addView(nameRow);

        bannerNow = text(16, "sans-serif", Color.WHITE);
        bannerNow.setSingleLine(true);
        bannerNow.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nowl = new LinearLayout.LayoutParams(-1, -2);
        nowl.setMargins(0, dp(6), 0, 0);
        textCol.addView(bannerNow, nowl);

        bannerProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bannerProgress.setMax(1000);
        bannerProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        bannerProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x40FFFFFF));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, dp(4));
        pl.setMargins(0, dp(8), 0, dp(6));
        textCol.addView(bannerProgress, pl);

        bannerNext = text(13, "sans-serif", 0x99FFFFFF);
        bannerNext.setSingleLine(true);
        bannerNext.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(bannerNext);

        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bl.setMargins(dp(48), 0, dp(48), dp(36));
        root.addView(banner, bl);

        // Channel list, slides over the left side.
        listPanel = new FrameLayout(this);
        listPanel.setBackgroundColor(0xF0141416);
        listPanel.setVisibility(View.GONE);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        listRows = new LinearLayout(this);
        listRows.setOrientation(LinearLayout.VERTICAL);
        listRows.setPadding(dp(12), dp(24), dp(12), dp(24));
        sv.addView(listRows, new FrameLayout.LayoutParams(-1, -2));
        listPanel.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        root.addView(listPanel, new FrameLayout.LayoutParams(dp(380), -1, Gravity.START));
        return root;
    }

    private void status(String s) {
        status.setText(s);
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

    /** Letterboxes the video to its real shape (4:3 SD channels, 16:9 HD). */
    static final class AspectFrame extends FrameLayout {
        private float aspect = 16f / 9f;

        AspectFrame(Context c) { super(c); }

        void setAspect(float a) {
            if (Math.abs(a - aspect) > 0.01f) { aspect = a; requestLayout(); }
        }

        @Override protected void onMeasure(int w, int h) {
            int W = MeasureSpec.getSize(w), H = MeasureSpec.getSize(h);
            int vw = W, vh = Math.round(W / aspect);
            if (vh > H) { vh = H; vw = Math.round(H * aspect); }
            super.onMeasure(MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY));
        }
    }
}
