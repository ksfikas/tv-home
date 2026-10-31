package io.github.ksfikas.tvhome;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.util.Rational;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
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
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen live TV, radio and recordings.
 *
 * Live: Up/Down or Channel ± zap, Left = previous channel, Right/Info = now/next banner,
 * OK = channel list, hold OK or Menu = options (pause, audio, subtitles, record, guide),
 * Play/Pause pauses live TV, number keys tune directly.
 * Recordings: Left/Right seek 30 s, Up/Down 5 min, OK pauses, hold OK for audio/subtitles.
 * The player is released whenever the screen leaves the foreground so it holds no memory at home.
 */
public class PlayerActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    static final String EXTRA_CHANNEL = "channel", EXTRA_RECORDING = "recording", EXTRA_TITLE = "title";
    /** Channel on screen right now, or null; reminders for it are skipped. */
    static volatile String playing;
    private static java.lang.ref.WeakReference<PlayerActivity> shown = new java.lang.ref.WeakReference<>(null);

    /** From the phone remote: zap, pause, back to live, banner, sleep timer, leave. */
    static void remote(String what, String arg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            PlayerActivity p = shown.get();
            if (p == null || p.isDestroyed() || !p.visible || what == null) return;
            switch (what) {
                case "up": p.zap(+1); break;
                case "down": p.zap(-1); break;
                case "prev": if (p.previousIndex >= 0 && p.previousIndex < p.list.size()) p.tuneTo(p.previousIndex, false); break;
                case "pause": p.togglePause(); break;
                case "live": p.backToLive(); break;
                case "info": p.showBanner(); break;
                case "sleep": try { p.setSleep(Long.parseLong(arg) * 60_000); } catch (NumberFormatException ignored) { } break;
                case "stop": p.finish(); break;
            }
        });
    }

    private Tvh tvh;
    private List<Tvh.Channel> list = new ArrayList<>();
    private int index = -1, previousIndex = -1;
    private String recordingUuid, recordingTitle;
    private Tvh.Event current, next; // live: what's on now/next on this channel, with DVR state

    private ExoPlayer player;
    private android.media.tv.TvView tvView; // only created for channels from tuner apps on the box
    private AspectFrame videoFrame;
    private SurfaceView surface;
    private CueView cues;
    private TextView status, digits, pauseBadge;
    private LinearLayout banner;
    private ImageView bannerLogo;
    private TextView bannerNumber, bannerName, bannerNow, bannerTimes, bannerNext;
    private ProgressBar bannerProgress;
    private LinearLayout radioPanel;
    private final Spectrum.Tap spectrumTap = new Spectrum.Tap();
    private ImageView radioLogo;
    private TextView radioName, radioNow, radioNext;
    private FrameLayout listPanel;
    private LinearLayout listRows;
    private final java.util.Map<String, TextView> listLabels = new java.util.HashMap<>();
    private java.util.Map<String, Tvh.Event[]> nowNext;
    private long nowNextAt;
    private TextView infoPanel, sleepBadge;
    private boolean infoShown, swallowKeyUp;
    private long sleepAt; // elapsedRealtime when the sleep timer goes off, 0 = off
    private boolean saverOn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable infoTick = this::refreshInfo;
    private final Runnable sleepTick = this::sleepTick;
    private final Runnable startSaver = this::startSaver;
    private final Runnable drift = this::drift;
    private final Runnable hideBanner = () -> banner.animate().alpha(0f).setDuration(250).start();
    private final Runnable tuneNow = this::startPlayback;
    private final Runnable commitDigits = this::commitDigits;
    private final Runnable retry = this::startPlayback;
    /** A live stream stuck in "buffering" (a tuner that was asleep, a lost ticket) never fails by itself: try again. */
    private boolean firstFrame;
    private int pictureRetries;
    private final Runnable noPicture = this::noPicture;
    private final Runnable stall = () -> { if (!isRecording()) failed(null); };
    private final Runnable tick = this::tick;
    private int retries;
    private long tuneSeq;
    private boolean visible, longPress;
    private long pausedAt, behindMs; // live pause: when paused, and how far behind live we are
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
        if (!Sources.configured(this)) { finish(); return; } // tvh may be null: tuner-only setups
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private boolean isRecording() { return recordingUuid != null; }

    private boolean isRadio() { return !isRecording() && index >= 0 && list.get(index).radio; }

    private void handleIntent(Intent i) {
        recordingUuid = i.getStringExtra(EXTRA_RECORDING);
        recordingTitle = i.getStringExtra(EXTRA_TITLE);
        if (isRecording()) {
            index = -1;
            radioPanel.setVisibility(View.GONE);
            videoFrame.setVisibility(View.VISIBLE);
            startPlayback();
            showBanner();
            return;
        }
        String uuid = i.getStringExtra(EXTRA_CHANNEL);
        if (uuid == null) uuid = Tvh.prefs(this).getString("last_channel", null);
        String target = uuid;
        if (!Tvh.channels.isEmpty()) { useChannels(target); return; }
        // Started cold (e.g. at boot): fetch the channel list first.
        status(getString(R.string.player_loading_channels));
        Tvh.IO.execute(() -> {
            try {
                Sources.load(this);
                runOnUiThread(() -> { if (!isDestroyed()) useChannels(target); });
            } catch (IOException e) {
                runOnUiThread(() -> status(e.getMessage()));
            }
        });
    }

    /** Zap within the same kind (TV or radio) as the channel being opened. */
    private void useChannels(String target) {
        Tvh.Channel t = Tvh.find(target);
        list = Tvh.ofKind(t != null && t.radio);
        if (list.isEmpty()) { status(getString(R.string.player_no_channels)); return; }
        int i = 0;
        for (int j = 0; j < list.size(); j++) if (list.get(j).uuid.equals(target)) i = j;
        index = -1;
        tuneTo(i, false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        shown = new java.lang.ref.WeakReference<>(this);
        if ((index >= 0 || isRecording()) && player == null) startPlayback();
        if (sleepAt > 0) sleepTick();
        if (infoShown) refreshInfo();
        wake();
    }

    // ---------------------------------------------------------------- picture in picture

    /** True while we open one of our own screens (guide, recordings): that is not the user leaving. */
    private boolean openingOwnScreen;

    /** Live TV or a recording from our own player, with a picture; not radio or a tuner app's TvView. */
    private boolean canPip() {
        return android.os.Build.VERSION.SDK_INT >= 26
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                && player != null && !isTvInput() && !isRadio() && (isRecording() || index >= 0);
    }

    /** Shrink the picture into a corner window; the launcher (or any app) shows behind it. */
    private void enterPip() {
        if (!canPip()) return;
        closeList();
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build());
        } catch (IllegalStateException | IllegalArgumentException ignored) { } // not allowed right now: stay full-screen
    }

    /** Home (or Recents) pressed: keep watching in the corner instead of stopping. */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (!openingOwnScreen) enterPip();
    }

    @Override
    protected void onResume() {
        super.onResume();
        openingOwnScreen = false;
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        // The overlays are drawn for a full screen: hide them in the small window, bring them back after.
        float a = inPip ? 0f : 1f;
        for (View v : new View[]{status, digits, pauseBadge, infoPanel, sleepBadge}) if (v != null) v.setAlpha(a);
        if (inPip) { handler.removeCallbacks(hideBanner); banner.setAlpha(0f); }
        else showBanner();
    }

    private void openOwnScreen(Intent i) {
        openingOwnScreen = true;
        startActivity(i);
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        playing = null;
        HomeHooks.event(this, HomeHooks.STOP);
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (tvView != null) { tvView.reset(); tvView.setVisibility(View.GONE); }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // While "going to sleep" shows, any key keeps watching and does nothing else.
        if (sleepBadge.getVisibility() == View.VISIBLE && e.getAction() == KeyEvent.ACTION_DOWN) {
            setSleep(0);
            Toast.makeText(this, R.string.player_sleep_off_toast, Toast.LENGTH_SHORT).show();
            swallowKeyUp = true;
            return true;
        }
        if (swallowKeyUp && e.getAction() == KeyEvent.ACTION_UP) { swallowKeyUp = false; return true; }
        if (e.getAction() == KeyEvent.ACTION_DOWN && wake()) return true; // first key just wakes the radio screen
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- playback

    /** Select a channel; the stream starts after a short pause so fast zapping doesn't open every channel. */

    private void tuneTo(int i, boolean debounce) {
        if (i < 0 || i >= list.size()) return;
        if (index >= 0 && i != index) previousIndex = index;
        index = i;
        retries = 0;
        pictureRetries = 0;
        current = next = null;
        Tvh.prefs(this).edit().putString("last_channel", list.get(i).uuid).apply();
        boolean radio = list.get(i).radio;
        radioPanel.setVisibility(radio ? View.VISIBLE : View.GONE);
        videoFrame.setVisibility(radio ? View.GONE : View.VISIBLE);
        wake();
        showBanner();
        handler.removeCallbacks(tuneNow);
        handler.removeCallbacks(retry);
        handler.removeCallbacks(stall);
        handler.removeCallbacks(noPicture);
        if (debounce) handler.postDelayed(tuneNow, 250); // short pause so fast channel-surfing does not tune every channel
        else startPlayback();
    }

    private void startPlayback() {
        if (index < 0 && !isRecording()) return;
        long seq = ++tuneSeq;
        firstFrame = false;
        pausedAt = 0;
        behindMs = 0;
        updatePauseBadge();
        status("");
        if (player != null) player.stop();
        stopTvInput();
        if (isRecording()) { play(tvh.recordingUrl(recordingUuid)); return; }
        Tvh.Channel ch = list.get(index);
        playing = ch.uuid;
        if (ch.inputId != null) { playTvInput(ch); return; }
        if (ch.url != null) { play(ch.url); return; } // network tuner: a plain HTTP stream, no ticket needed
        Tvh.IO.execute(() -> {
            String url;
            try {
                url = tvh.streamUrl(ch.uuid);
            } catch (IOException e) {
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

    private void play(String url) {
        if (player == null) player = buildPlayer();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("tvhome")
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000);
        File spill = new File(getCacheDir(), "pause.ts");
        DataSource.Factory source = isRecording()
                ? () -> new AuthDataSource(tvh, http.createDataSource())
                : () -> new LiveBuffer(http.createDataSource(), spill);
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        player.setMediaSource(new ProgressiveMediaSource.Factory(source, extractors)
                .createMediaSource(MediaItem.fromUri(url)));
        // A recording stopped part-way carries on from there; "Start from the beginning" is in the menu.
        long resumeAt = isRecording() ? Tvh.resume(this).getLong(recordingUuid, 0) : 0;
        if (resumeAt > 0) {
            player.seekTo(resumeAt);
            Toast.makeText(this, getString(R.string.player_continuing, Ui.duration(resumeAt)), Toast.LENGTH_SHORT).show();
        }
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private ExoPlayer buildPlayer() {
        // Decoded audio also flows past the radio spectrum tap (a no-op unless the radio screen is up).
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
            @Override
            protected androidx.media3.exoplayer.audio.AudioSink buildAudioSink(Context c, boolean floatOut, boolean trackParams) {
                return new androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(c)
                        .setEnableFloatOutput(floatOut)
                        .setEnableAudioTrackPlaybackParams(trackParams)
                        .setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[]{
                                new androidx.media3.exoplayer.audio.TeeAudioProcessor(spectrumTap)})
                        .build();
            }
        }.setEnableDecoderFallback(true);
        DefaultTrackSelector tracks = new DefaultTrackSelector(this);
        android.content.SharedPreferences prefs = Tvh.prefs(this);
        // Tunneled playback lets the Amlogic video pipeline handle A/V sync and deinterlacing itself.
        // Remembered audio/subtitle languages carry over from channel to channel.
        String audioLang = prefs.getString("audio_lang", null), textLang = prefs.getString("text_lang", null);
        tracks.setParameters(tracks.buildUponParameters()
                .setTunnelingEnabled(prefs.getBoolean("tunneling", false))
                .setPreferredAudioLanguage(audioLang)
                .setPreferredTextLanguage(textLang)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, textLang == null));
        // Live TV: start fast, keep a modest buffer (memory is tight on 2 GB boxes).
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 12000, 250, 1000) // 250 ms is enough to start: a live stream only arrives in real time
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
                handler.removeCallbacks(stall);
                if (state == Player.STATE_READY) { retries = 0; status(""); if (!firstFrame) handler.postDelayed(noPicture, 4000); }
                else if (state == Player.STATE_BUFFERING) { status("…"); handler.postDelayed(stall, 8000); }
                else if (state == Player.STATE_ENDED) {
                    if (isRecording()) { Tvh.saveResume(PlayerActivity.this, recordingUuid, recordingTitle, 0, 0); finish(); }
                    else failed(getString(R.string.player_stream_ended)); // also how an over-long live pause gets back to live
                }
            }

            @Override public void onPlayerError(PlaybackException e) { failed(describe(e)); }

            @Override public void onIsPlayingChanged(boolean isPlayingNow) { HomeHooks.event(PlayerActivity.this, isPlayingNow ? HomeHooks.PLAY : HomeHooks.PAUSE); }

            @Override public void onRenderedFirstFrame() { firstFrame = true; handler.removeCallbacks(noPicture); }

            @Override public void onVideoSizeChanged(VideoSize v) {
                if (v.width > 0 && v.height > 0) videoFrame.setAspect(v.width * v.pixelWidthHeightRatio / v.height);
            }

            @Override public void onCues(CueGroup group) { cues.setCues(group.cues); }
        });
        return p;
    }

    /**
     * A channel from a tuner app on the box: Android's TvView asks that app to tune and draw the
     * picture; our ExoPlayer isn't involved (so no pause, track menu or stream info for these).
     */
    private void playTvInput(Tvh.Channel ch) {
        releasePlayer();
        infoShown = false;
        refreshInfo();
        videoFrame.setVisibility(View.GONE);
        radioPanel.setVisibility(View.GONE);
        if (tvView == null) {
            tvView = new android.media.tv.TvView(this);
            tvView.setCallback(new android.media.tv.TvView.TvInputCallback() {
                @Override public void onVideoAvailable(String inputId) { status(""); }
                @Override public void onVideoUnavailable(String inputId, int reason) {
                    status(reason == android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL ? getString(R.string.player_weak_signal)
                            : reason == android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY ? ""
                            : reason == android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING
                              || reason == android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING ? "…"
                            : getString(R.string.player_no_picture));
                }
                @Override public void onConnectionFailed(String inputId) { status(getString(R.string.player_tuner_not_responding)); }
                @Override public void onDisconnected(String inputId) { status(getString(R.string.player_tuner_stopped)); }
            });
            ((FrameLayout) videoFrame.getParent()).addView(tvView, 1, new FrameLayout.LayoutParams(-1, -1));
        }
        tvView.setVisibility(View.VISIBLE);
        tvView.tune(ch.inputId, android.media.tv.TvContract.buildChannelUri(ch.tifId));
    }

    private void stopTvInput() {
        if (tvView != null && tvView.getVisibility() == View.VISIBLE) {
            tvView.reset();
            tvView.setVisibility(View.GONE);
            boolean radio = !isRecording() && index >= 0 && list.get(index).radio;
            radioPanel.setVisibility(radio ? View.VISIBLE : View.GONE);
            videoFrame.setVisibility(radio ? View.GONE : View.VISIBLE);
        }
    }

    private boolean isTvInput() { return !isRecording() && index >= 0 && list.get(index).inputId != null; }

    /**
     * Sound without a picture: the stream plays (the server sees it) but the video decoder never drew a
     * frame, which a fresh start of the same channel fixes (what zapping does by accident). Twice at most.
     */
    private void noPicture() {
        if (firstFrame || player == null || isTvInput() || player.getVideoFormat() == null) return; // audio-only service: nothing to show
        if (pictureRetries++ < 2) { status(getString(R.string.player_reconnecting)); startPlayback(); }
    }

    /** Retry a few times (tickets expire, tuners get busy), then say what went wrong. */
    private void failed(String why) {
        if (retries < 3) {
            retries++;
            status(getString(R.string.player_reconnecting));
            handler.removeCallbacks(retry);
            handler.postDelayed(retry, 1500L * retries);
        } else {
            status(why == null ? getString(R.string.player_cant_play) : why);
        }
    }

    private String describe(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return getString(R.string.player_err_refused);
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return getString(R.string.player_err_unreachable);
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return getString(R.string.player_err_format);
            default:
                return getString(R.string.player_err_playback, e.getErrorCodeName());
        }
    }

    private void releasePlayer() {
        tuneSeq++;
        if (player != null) {
            if (isRecording()) Tvh.saveResume(this, recordingUuid, recordingTitle, player.getCurrentPosition(), player.getDuration());
            player.release();
            player = null;
        }
        cues.setCues(Collections.emptyList());
    }

    // ---------------------------------------------------------------- pause (live) / transport (recordings)

    private void togglePause() {
        if (player == null) return;
        if (player.getPlayWhenReady()) {
            player.setPlayWhenReady(false);
            pausedAt = System.currentTimeMillis();
        } else {
            if (pausedAt > 0) behindMs += System.currentTimeMillis() - pausedAt;
            pausedAt = 0;
            player.setPlayWhenReady(true);
            badgeUntil = System.currentTimeMillis() + 5000;
        }
        updatePauseBadge();
        if (isRecording()) showBanner();
    }

    private long badgeUntil; // "behind live" shows briefly after resuming, and with the banner

    private void backToLive() {
        if (!isRecording()) startPlayback();
    }

    /** "❚❚ Paused 1:23" while paused, "1:23 behind live" after resuming; ticks every second. */
    private void updatePauseBadge() {
        handler.removeCallbacks(tick);
        if (isRecording()) { pauseBadge.setVisibility(View.GONE); return; }
        long now = System.currentTimeMillis();
        long behind = behindMs + (pausedAt > 0 ? now - pausedAt : 0);
        boolean show = pausedAt > 0 || behind >= 2000 && (now < badgeUntil || banner.getAlpha() > 0.5f);
        if (!show) { pauseBadge.setVisibility(View.GONE); return; }
        pauseBadge.setText(pausedAt > 0 ? "❚❚  " + getString(R.string.player_paused, Ui.duration(behind)) : getString(R.string.player_behind_live, Ui.duration(behind)));
        pauseBadge.setVisibility(View.VISIBLE);
        handler.postDelayed(tick, 1000);
    }

    private void tick() {
        updatePauseBadge();
        if (isRecording() && banner.getAlpha() > 0) updateRecordingBanner();
    }

    private void seek(long deltaMs) {
        if (player == null || !isRecording()) return;
        long dur = player.getDuration();
        long to = Math.max(0, player.getCurrentPosition() + deltaMs);
        if (dur != C.TIME_UNSET) to = Math.min(to, Math.max(0, dur - 1000));
        player.seekTo(to);
        showBanner();
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (listPanel.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT) { closeList(); return true; }
            return super.onKeyDown(code, e);
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (e.getRepeatCount() == 0) { e.startTracking(); longPress = false; }
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_MENU: options(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY:
                togglePause(); return true;
            case KeyEvent.KEYCODE_GUIDE: openGuide(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (banner.getAlpha() > 0.5f) { handler.removeCallbacks(hideBanner); hideBanner.run(); return true; }
                if (infoShown) { infoShown = false; refreshInfo(); return true; }
                finish(); return true;
        }
        if (isRecording()) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_REWIND: seek(-30_000); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seek(30_000); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: seek(-5 * 60_000); return true;
                case KeyEvent.KEYCODE_DPAD_UP: seek(5 * 60_000); return true;
                case KeyEvent.KEYCODE_INFO: showBanner(); return true;
            }
            return super.onKeyDown(code, e);
        }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            typeDigit((char) ('0' + code - KeyEvent.KEYCODE_0));
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: zap(+1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: zap(-1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (previousIndex >= 0 && previousIndex < list.size()) tuneTo(previousIndex, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_INFO: showBanner(); return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            longPress = true;
            options();
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)
                && listPanel.getVisibility() != View.VISIBLE) {
            if (!longPress && !e.isCanceled()) {
                if (isRecording()) togglePause();
                else if (typed.length() > 0) commitDigits();
                else openList();
            }
            longPress = false;
            return true;
        }
        return super.onKeyUp(code, e);
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
        status(getString(R.string.player_no_channel, (long) want));
        handler.postDelayed(() -> status(""), 2000);
    }

    // ---------------------------------------------------------------- options menu

    private void options() {
        if (!isRecording() && (index < 0 || index >= list.size())) return; // still loading channels: nothing to offer yet
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        // A tuner app's channel plays in TvView, outside our player: no pause, tracks or stream info.
        boolean ownPlayer = !isTvInput();
        boolean fromTvh = !isRecording() && tvh != null && list.get(index).fromTvh();
        if (ownPlayer && (!isRadio() || isRecording())) {
            labels.add(player != null && !player.getPlayWhenReady() ? getString(R.string.player_resume) : getString(R.string.player_pause));
            actions.add(this::togglePause);
        }
        if (!isRecording() && (pausedAt > 0 || behindMs > 0)) { labels.add(getString(R.string.player_back_to_live)); actions.add(this::backToLive); }
        if (isRecording() && player != null && player.getCurrentPosition() > 10_000) {
            labels.add(getString(R.string.player_start_beginning));
            actions.add(() -> { player.seekTo(0); showBanner(); });
        }
        if (ownPlayer) {
            labels.add(getString(R.string.player_menu_audio, selectedTrack(C.TRACK_TYPE_AUDIO, getString(R.string.player_default))));
            actions.add(() -> pickTrack(C.TRACK_TYPE_AUDIO));
        }
        if (ownPlayer && !isRadio()) {
            labels.add(getString(R.string.player_menu_subtitles, selectedTrack(C.TRACK_TYPE_TEXT, getString(R.string.player_off))));
            actions.add(() -> pickTrack(C.TRACK_TYPE_TEXT));
        }
        if (fromTvh) {
            Tvh.Event ev = current;
            if (ev != null && ev.recordingSet()) {
                labels.add(getString(R.string.player_stop_recording, ev.title));
                actions.add(() -> dvr(() -> tvh.stopOrCancel(ev.dvrUuid, true), getString(R.string.player_recording_stopped)));
            } else if (ev != null) {
                labels.add(getString(R.string.player_record_title, ev.title));
                actions.add(() -> dvr(() -> tvh.record(ev.id), getString(R.string.player_recording)));
            }
            if (ev != null) {
                labels.add(getString(R.string.player_record_every));
                actions.add(() -> recordSeries(this, tvh, ev, this::showBanner));
            }
        }
        if (canPip()) {
            labels.add(getString(R.string.player_pip));
            actions.add(this::enterPip);
        }
        if (!isRecording() && tvh != null) {
            labels.add(getString(R.string.player_tv_guide));
            actions.add(this::openGuide);
            labels.add(getString(R.string.player_recordings));
            actions.add(() -> openOwnScreen(new Intent(this, RecordingsActivity.class)));
        }
        if (sleepAt > 0) {
            int left = (int) Math.max(1, (sleepAt - android.os.SystemClock.elapsedRealtime()) / 60_000);
            labels.add(getResources().getQuantityString(R.plurals.player_sleep_left, left, left));
        } else labels.add(getString(R.string.player_sleep_off));
        actions.add(this::pickSleep);
        if (ownPlayer) {
            labels.add(infoShown ? getString(R.string.player_hide_stream_info) : getString(R.string.player_stream_info));
            actions.add(() -> { infoShown = !infoShown; refreshInfo(); });
        }
        Ui.menu(this, isRecording() ? recordingTitle : list.get(index).name, labels.toArray(new String[0]),
                i -> actions.get(i).run());
    }

    private void dvr(GuideActivity.IoAction a, String done) {
        Tvh.IO.execute(() -> {
            String msg = done;
            try { a.run(); } catch (IOException e) { msg = e.getMessage(); }
            String m = msg;
            runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); showBanner(); });
        });
    }

    private void openGuide() {
        openOwnScreen(new Intent(this, GuideActivity.class));
    }

    /** Shared by the player, guide and search: confirm, then set up a series recording. */
    static void recordSeries(Activity a, Tvh tvh, Tvh.Event ev, Runnable after) {
        Tvh.Channel ch = Tvh.find(ev.channelUuid);
        Ui.confirm(a, a.getString(R.string.player_record_every),
                ch == null ? a.getString(R.string.player_series_confirm_this, ev.title)
                        : a.getString(R.string.player_series_confirm, ev.title, ch.name),
                a.getString(R.string.player_record_series),() -> Tvh.IO.execute(() -> {
                    String msg = a.getString(R.string.player_series_set);
                    try { tvh.recordSeries(ev.id); } catch (IOException e) { msg = e.getMessage(); }
                    String m = msg;
                    a.runOnUiThread(() -> {
                        Toast.makeText(a, m, Toast.LENGTH_SHORT).show();
                        if (after != null) after.run();
                    });
                }));
    }

    // ---------------------------------------------------------------- sleep timer

    private void pickSleep() {
        List<String> labels = new ArrayList<>();
        List<Long> mins = new ArrayList<>();
        labels.add(getString(R.string.player_off)); mins.add(0L);
        for (long m : new long[]{15, 30, 60, 90, 120}) { labels.add(getResources().getQuantityString(R.plurals.player_minutes, (int) m, (int) m)); mins.add(m); }
        long nowSec = System.currentTimeMillis() / 1000;
        if (!isRecording() && current != null && current.stop > nowSec + 60) {
            labels.add(getString(R.string.player_end_programme, Ui.time(this, current.stop)));
            mins.add(-(current.stop - nowSec)); // negative: seconds, not minutes
        } else if (isRecording() && player != null && player.getDuration() != C.TIME_UNSET) {
            labels.add(getString(R.string.player_end_recording));
            mins.add(-Math.max(60, (player.getDuration() - player.getCurrentPosition()) / 1000));
        }
        Ui.menu(this, getString(R.string.player_sleep_timer), labels.toArray(new String[0]), i -> {
            long v = mins.get(i);
            setSleep(v >= 0 ? v * 60_000 : -v * 1000);
            if (v != 0) Toast.makeText(this, getString(R.string.player_going_to_sleep, Ui.duration(v >= 0 ? v * 60_000 : -v * 1000)), Toast.LENGTH_SHORT).show();
        });
    }

    private void setSleep(long inMs) {
        handler.removeCallbacks(sleepTick);
        sleepBadge.setVisibility(View.GONE);
        sleepAt = inMs > 0 ? android.os.SystemClock.elapsedRealtime() + inMs : 0;
        if (sleepAt > 0) sleepTick();
    }

    /** Wakes once a minute, then every second during the last minute's warning. */
    private void sleepTick() {
        handler.removeCallbacks(sleepTick);
        if (sleepAt == 0) return;
        long left = sleepAt - android.os.SystemClock.elapsedRealtime();
        if (left <= 0) { goToSleep(); return; }
        if (left <= 60_000) {
            sleepBadge.setText(getString(R.string.player_sleep_badge, (int) (left / 1000 + 1)));
            sleepBadge.setVisibility(View.VISIBLE);
            handler.postDelayed(sleepTick, Math.min(1000, left));
        } else {
            handler.postDelayed(sleepTick, Math.min(60_000, left - 60_000));
        }
    }

    /**
     * Stop playing and put the box to standby. That needs the launcher to be a device admin
     * (set once over ADB, see the README); without it the player just closes and the box's own
     * screen-off timer takes over.
     */
    private void goToSleep() {
        sleepAt = 0;
        sleepBadge.setVisibility(View.GONE);
        releasePlayer();
        android.app.admin.DevicePolicyManager dpm = getSystemService(android.app.admin.DevicePolicyManager.class);
        android.content.ComponentName admin = new android.content.ComponentName(this, SleepAdmin.class);
        finish();
        if (dpm != null && dpm.isAdminActive(admin)) dpm.lockNow();
    }

    // ---------------------------------------------------------------- stream info

    /** Codec, resolution and bitrate, plus the tuner's signal; refreshes every 2 s while shown. */
    private void refreshInfo() {
        handler.removeCallbacks(infoTick);
        if (!infoShown) { infoPanel.setVisibility(View.GONE); return; }
        StringBuilder s = new StringBuilder();
        Format v = player == null ? null : player.getVideoFormat(), a = player == null ? null : player.getAudioFormat();
        if (v != null) {
            s.append(infoLabel(R.string.player_info_video)).append(codec(v.sampleMimeType));
            if (v.width > 0) s.append("  ·  ").append(v.width).append('×').append(v.height);
            if (v.frameRate > 0) s.append("  ·  ").append(Math.round(v.frameRate)).append(" fps");
            s.append('\n');
        }
        if (a != null) {
            s.append(infoLabel(R.string.player_info_audio)).append(codec(a.sampleMimeType));
            if (a.channelCount > 0) s.append("  ·  ").append(a.channelCount == 6 ? "5.1" : a.channelCount == 2 ? getString(R.string.player_stereo) : a.channelCount == 1 ? getString(R.string.player_mono) : getString(R.string.player_channels, a.channelCount));
            if (a.sampleRate > 0) s.append("  ·  ").append(a.sampleRate / 1000).append(" kHz");
            if (a.language != null && !"und".equals(a.language)) s.append("  ·  ").append(new Locale(a.language).getDisplayLanguage());
            s.append('\n');
        }
        if (s.length() == 0) s.append(getString(R.string.player_waiting_stream)).append('\n');
        String base = s.toString();
        infoPanel.setText(base.trim());
        infoPanel.setVisibility(View.VISIBLE);
        if (!isRecording() && index >= 0 && tvh != null && list.get(index).fromTvh()) { // tuner figures come from Tvheadend
            String name = list.get(index).name;
            Tvh.IO.execute(() -> {
                Tvh.Status st;
                try { st = tvh.status(name); } catch (IOException e) { st = null; }
                Tvh.Status f = st;
                runOnUiThread(() -> {
                    if (!infoShown || isDestroyed()) return;
                    StringBuilder t = new StringBuilder(base);
                    if (f == null) t.append(infoLabel(R.string.player_info_signal)).append(getString(R.string.player_admins_only));
                    else {
                        if (f.bitrate > 0) t.append(infoLabel(R.string.player_info_stream)).append(String.format(Locale.ROOT, "%.1f Mbit/s", f.bitrate / 1e6)).append('\n');
                        if (f.tuner != null) t.append(infoLabel(R.string.player_info_tuner)).append(f.tuner).append('\n');
                        if (f.signal != null || f.snr != null) {
                            t.append(infoLabel(R.string.player_info_signal)).append(f.signal == null ? "–" : f.signal);
                            if (f.snr != null) t.append("  ·  SNR ").append(f.snr);
                            t.append('\n');
                        }
                        if (f.errors != null) t.append(infoLabel(R.string.player_info_errors)).append(f.errors);
                    }
                    infoPanel.setText(t.toString().trim());
                });
            });
        }
        handler.postDelayed(infoTick, 2000);
    }

    /** A stream-info label padded so the values line up in the monospace panel. */
    private String infoLabel(int id) {
        StringBuilder b = new StringBuilder(getString(id));
        while (b.length() < 9) b.append(' ');
        return b.toString();
    }

    private static String codec(String mime) {
        if (mime == null) return "?";
        switch (mime) {
            case "video/avc": return "H.264";
            case "video/hevc": return "HEVC";
            case "video/mpeg2": return "MPEG-2";
            case "audio/mpeg": case "audio/mpeg-L2": return "MP2";
            case "audio/mp4a-latm": return "AAC";
            case "audio/ac3": return "Dolby Digital";
            case "audio/eac3": return "Dolby Digital+";
            default: return mime.substring(mime.indexOf('/') + 1).toUpperCase(Locale.ROOT);
        }
    }

    // ---------------------------------------------------------------- radio screensaver

    /** Any key: restore the radio screen; true if it was dimmed (that key only wakes it). */
    private boolean wake() {
        handler.removeCallbacks(startSaver);
        handler.removeCallbacks(drift);
        boolean was = saverOn;
        if (saverOn) {
            saverOn = false;
            for (int i = 0; i < radioPanel.getChildCount(); i++) {
                radioPanel.getChildAt(i).animate().alpha(1f).translationX(0).translationY(0).setDuration(400).start();
            }
        }
        if (isRadio() && visible) handler.postDelayed(startSaver, 3 * 60_000);
        return was;
    }

    /** After three idle minutes on radio: dim everything but the spectrum and let it drift slowly. */
    private void startSaver() {
        if (!isRadio() || radioPanel.getVisibility() != View.VISIBLE || listPanel.getVisibility() == View.VISIBLE) return;
        saverOn = true;
        handler.removeCallbacks(hideBanner);
        hideBanner.run();
        for (int i = 0; i < radioPanel.getChildCount(); i++) {
            View c = radioPanel.getChildAt(i);
            c.animate().alpha(c instanceof Spectrum.Bars ? 0.7f : c == radioLogo ? 0f : 0.45f).setDuration(2500).start();
        }
        drift();
    }

    private void drift() {
        if (!saverOn) return;
        java.util.Random r = new java.util.Random();
        float dx = (r.nextFloat() * 2 - 1) * Ui.dp(this, 140), dy = (r.nextFloat() * 2 - 1) * Ui.dp(this, 90);
        for (int i = 0; i < radioPanel.getChildCount(); i++) {
            radioPanel.getChildAt(i).animate().translationX(dx).translationY(dy).setDuration(12_000)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
        }
        handler.postDelayed(drift, 60_000);
    }

    private String trackLabel(Format f, int n) {
        String s = f.label;
        if (s == null && f.language != null && !"und".equals(f.language)) {
            s = new Locale(f.language).getDisplayLanguage();
        }
        if (s == null || s.isEmpty()) s = getString(R.string.player_track, n);
        String mime = f.sampleMimeType == null ? "" : f.sampleMimeType;
        if (mime.contains("ac3") || mime.contains("eac3")) s += " (Dolby)";
        else if (mime.contains("dvbsubs")) s += " (DVB)";
        return s;
    }

    private String selectedTrack(int type, String none) {
        if (player == null) return none;
        if (type == C.TRACK_TYPE_TEXT && player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return none;
        int n = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                n++;
                if (g.isTrackSelected(i)) return trackLabel(g.getTrackFormat(i), n);
            }
        }
        return none;
    }

    /** Pick an audio or subtitle track; the language is remembered for the next channels. */
    private void pickTrack(int type) {
        if (player == null) return;
        List<String> labels = new ArrayList<>();
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        if (type == C.TRACK_TYPE_TEXT) { labels.add(getString(R.string.player_off)); groups.add(null); idx.add(-1); }
        int n = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                n++;
                if (!g.isTrackSupported(i)) continue;
                labels.add(trackLabel(g.getTrackFormat(i), n) + (g.isTrackSelected(i) ? "  ✓" : ""));
                groups.add(g);
                idx.add(i);
            }
        }
        if (labels.size() <= (type == C.TRACK_TYPE_TEXT ? 1 : 0)) {
            Toast.makeText(this, type == C.TRACK_TYPE_TEXT ? getString(R.string.player_no_subtitles) : getString(R.string.player_one_audio), Toast.LENGTH_SHORT).show();
            return;
        }
        Ui.menu(this, type == C.TRACK_TYPE_TEXT ? getString(R.string.player_subtitles) : getString(R.string.player_audio), labels.toArray(new String[0]), which -> {
            TrackSelectionParameters.Builder p = player.getTrackSelectionParameters().buildUpon();
            Tracks.Group g = groups.get(which);
            String key = type == C.TRACK_TYPE_TEXT ? "text_lang" : "audio_lang";
            if (g == null) {
                p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
                Tvh.prefs(this).edit().remove(key).apply();
            } else {
                p.setTrackTypeDisabled(type, false)
                        .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), idx.get(which)));
                String lang = g.getTrackFormat(idx.get(which)).language;
                if (lang != null) {
                    Tvh.prefs(this).edit().putString(key, lang).apply();
                    if (type == C.TRACK_TYPE_TEXT) p.setPreferredTextLanguage(lang); else p.setPreferredAudioLanguage(lang);
                }
            }
            player.setTrackSelectionParameters(p.build());
        });
    }

    // ---------------------------------------------------------------- banner

    private void showBanner() {
        banner.animate().cancel();
        banner.setAlpha(1f);
        handler.removeCallbacks(hideBanner);
        handler.postDelayed(hideBanner, 5000);
        badgeUntil = System.currentTimeMillis() + 5000;
        updatePauseBadge();
        if (isRecording()) { updateRecordingBanner(); return; }
        if (index < 0) return;
        Tvh.Channel ch = list.get(index);
        bannerNumber.setText(ch.numberLabel());
        bannerName.setText(ch.name);
        radioName.setText(ch.name);
        Bitmap cached = Tvh.cachedLogo(ch.icon, Ui.dp(this, 240));
        bannerLogo.setImageBitmap(cached);
        setRadioLogo(cached);
        setNow(current != null ? current : ch.now, next);
        Tvh.IO.execute(() -> {
            Bitmap logo = Tvh.logo(this, tvh, ch, Ui.dp(this, 240));
            List<Tvh.Event> events = null;
            if (tvh != null && ch.fromTvh()) try { events = tvh.upcoming(ch.uuid, 3); } catch (IOException ignored) { }
            List<Tvh.Event> ev = events;
            runOnUiThread(() -> {
                if (isDestroyed() || index < 0 || list.get(index) != ch) return;
                bannerLogo.setImageBitmap(logo);
                setRadioLogo(logo);
                if (ev != null && !ev.isEmpty()) {
                    current = ev.get(0);
                    next = ev.size() > 1 ? ev.get(1) : null;
                    ch.now = current;
                    setNow(current, next);
                }
            });
        });
    }

    /** No logo: drop the slot so the station name and programme sit centred. */
    private void setRadioLogo(Bitmap b) {
        radioLogo.setImageBitmap(b);
        radioLogo.setVisibility(b == null ? View.GONE : View.VISIBLE);
    }

    private void setNow(Tvh.Event now, Tvh.Event nxt) {
        String rec = now != null && now.recordingSet() ? "●  " : "";
        if (now == null) {
            bannerNow.setText(R.string.player_no_programme_info);
            bannerTimes.setText("");
            bannerProgress.setVisibility(View.INVISIBLE);
            radioNow.setText("");
        } else {
            String title = now.subtitle == null ? now.title : now.title + "  ·  " + now.subtitle;
            bannerNow.setText(rec + title);
            radioNow.setText(title);
            bannerTimes.setText(Ui.time(this, now.start) + " – " + Ui.time(this, now.stop));
            bannerProgress.setVisibility(View.VISIBLE);
            bannerProgress.setProgress((int) (now.progress(System.currentTimeMillis() / 1000) * 1000));
        }
        String n = nxt == null ? "" : getString(R.string.player_next, Ui.time(this, nxt.start), nxt.title);
        bannerNext.setText(n);
        radioNext.setText(n);
    }

    private void updateRecordingBanner() {
        bannerNumber.setText(player != null && !player.getPlayWhenReady() ? "❚❚" : "▶");
        bannerName.setText(recordingTitle == null ? getString(R.string.player_recording) : recordingTitle);
        bannerLogo.setImageBitmap(null);
        long pos = player == null ? 0 : player.getCurrentPosition(), dur = player == null ? C.TIME_UNSET : player.getDuration();
        bannerTimes.setText(Ui.duration(pos) + (dur != C.TIME_UNSET ? " / " + Ui.duration(dur) : ""));
        bannerProgress.setVisibility(View.VISIBLE);
        bannerProgress.setProgress(dur > 0 ? (int) (pos * 1000 / dur) : 0);
        bannerNow.setText(R.string.player_hint_recording);
        bannerNext.setText("");
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
    }

    // ---------------------------------------------------------------- channel list

    /** Mini guide: every channel with its logo, what's on now (time left) and next; the picture keeps playing. */
    private void openList() {
        if (list.isEmpty()) return;
        listRows.removeAllViews();
        listLabels.clear();
        View currentRow = null;
        int logoW = Ui.dp(this, 56), logoH = Ui.dp(this, 32);
        for (int i = 0; i < list.size(); i++) {
            Tvh.Channel ch = list.get(i);
            TextView row = Ui.row(this, listLabel(ch));
            row.setMaxLines(3);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setCompoundDrawablePadding(Ui.dp(this, 14));
            setRowLogo(row, Tvh.cachedLogo(ch.icon, GuideActivity.LOGO_W), logoW, logoH);
            int target = i;
            row.setOnClickListener(v -> { closeList(); if (target != index) tuneTo(target, false); });
            listRows.addView(row, new LinearLayout.LayoutParams(-1, -2));
            listLabels.put(ch.uuid, row);
            if (i == index) currentRow = row;
        }
        listPanel.setVisibility(View.VISIBLE);
        View focus = currentRow != null ? currentRow : listRows.getChildAt(0);
        focus.post(() -> { focus.requestFocus(); centerFocusedRow(); });
        List<Tvh.Channel> chs = new ArrayList<>(list);
        boolean stale = nowNext == null || android.os.SystemClock.elapsedRealtime() - nowNextAt > 60_000;
        Tvh.IO.execute(() -> {
            java.util.Map<String, Tvh.Event[]> nn = null;
            if (stale && tvh != null) try { nn = tvh.nowNext(); } catch (IOException ignored) { }
            java.util.Map<String, Tvh.Event[]> got = nn;
            runOnUiThread(() -> {
                if (got != null) { nowNext = got; nowNextAt = android.os.SystemClock.elapsedRealtime(); }
                if (listPanel.getVisibility() != View.VISIBLE) return;
                for (Tvh.Channel ch : chs) { TextView r = listLabels.get(ch.uuid); if (r != null) r.setText(listLabel(ch)); }
                centerFocusedRow(); // rows just grew from one line to three
            });
            // Logos: usually on disk already; small, shared with the guide's cache.
            for (Tvh.Channel ch : chs) {
                if (ch.icon == null || Tvh.cachedLogo(ch.icon, GuideActivity.LOGO_W) != null) continue;
                Bitmap b = Tvh.logo(this, tvh, ch, GuideActivity.LOGO_W);
                if (b != null) runOnUiThread(() -> { TextView r = listLabels.get(ch.uuid); if (r != null) setRowLogo(r, b, logoW, logoH); });
            }
        });
    }

    /**
     * Scroll the focused row (the playing channel, when the list opens) to the middle of the list. Rows
     * start one line tall and grow to three once the guide text arrives, which would push the focused
     * row off the bottom, so this runs again after that, once the new layout is in place.
     */
    private void centerFocusedRow() {
        if (!(listRows.getParent() instanceof ScrollView)) return;
        final ScrollView sv = (ScrollView) listRows.getParent();
        sv.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                sv.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                View f = listRows.findFocus();
                if (f != null) sv.scrollTo(0, Math.max(0, f.getTop() - (sv.getHeight() - f.getHeight()) / 2));
            }
        });
        listRows.requestLayout(); // make sure a layout pass follows, so the listener fires
    }

    private void setRowLogo(TextView row, Bitmap b, int w, int h) {
        android.graphics.drawable.Drawable d;
        if (b == null) d = new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
        else {
            d = new android.graphics.drawable.BitmapDrawable(getResources(), b);
            // Fit inside w×h, keeping the logo's shape.
            float s = Math.min(w / (float) b.getWidth(), h / (float) b.getHeight());
            int dw = Math.round(b.getWidth() * s), dh = Math.round(b.getHeight() * s);
            d = new android.graphics.drawable.InsetDrawable(d, (w - dw) / 2, (h - dh) / 2, w - dw - (w - dw) / 2, h - dh - (h - dh) / 2);
        }
        d.setBounds(0, 0, w, h);
        row.setCompoundDrawables(d, null, null, null);
    }

    private CharSequence listLabel(Tvh.Channel ch) {
        Tvh.Event[] nn = nowNext == null ? null : nowNext.get(ch.uuid);
        Tvh.Event now = nn != null && nn[0] != null ? nn[0] : ch.now, nxt = nn == null ? null : nn[1];
        long nowSec = System.currentTimeMillis() / 1000;
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder();
        String num = ch.numberLabel();
        if (!num.isEmpty()) {
            s.append(num).append("   ");
            s.setSpan(new android.text.style.ForegroundColorSpan(0x99888888), 0, s.length(), 0);
        }
        s.append(ch.name);
        if (now != null && now.isOn(nowSec)) {
            int a = s.length();
            if (now.recordingSet()) {
                s.append("\n● ");
                s.setSpan(new android.text.style.ForegroundColorSpan(0xFFE53935), a + 1, s.length(), 0);
            } else s.append("\n");
            // One line for "now", so the next programme always shows.
            s.append(now.title.length() > 48 ? now.title.substring(0, 46).trim() + "…" : now.title).append("  ·  ");
            int minLeft = (int) Math.max(1, (now.stop - nowSec) / 60);
            s.append(getResources().getQuantityString(R.plurals.watched_left_min, minLeft, minLeft));
            s.setSpan(new android.text.style.RelativeSizeSpan(0.85f), a, s.length(), 0);
        }
        if (nxt != null) {
            int a = s.length();
            s.append("\n").append(Ui.time(this, nxt.start)).append("  ").append(nxt.title);
            s.setSpan(new android.text.style.RelativeSizeSpan(0.75f), a, s.length(), 0);
            s.setSpan(new android.text.style.ForegroundColorSpan(0x99888888), a, s.length(), 0);
        }
        return s;
    }

    private void closeList() {
        listPanel.setVisibility(View.GONE);
        listRows.removeAllViews();
        listLabels.clear();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        videoFrame = new AspectFrame(this);
        surface = new SurfaceView(this);
        videoFrame.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        cues = new CueView(this);
        videoFrame.addView(cues, new FrameLayout.LayoutParams(-1, -1));
        root.addView(videoFrame, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // Radio: a calm "now playing" card instead of a black screen.
        radioPanel = new LinearLayout(this);
        radioPanel.setOrientation(LinearLayout.VERTICAL);
        radioPanel.setGravity(Gravity.CENTER);
        radioPanel.setVisibility(View.GONE);
        radioPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1B1B22, 0xFF0B0B0E}));
        radioLogo = new ImageView(this);
        radioLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        radioPanel.addView(radioLogo, new LinearLayout.LayoutParams(Ui.dp(this, 320), Ui.dp(this, 180)));
        radioPanel.addView(new Spectrum.Bars(this, spectrumTap),
                lp(Ui.dp(this, 680), Ui.dp(this, 150), 0, Ui.dp(this, 12), 0, Ui.dp(this, 22)));
        radioName = Ui.text(this, 30, "sans-serif-light", Color.WHITE);
        radioName.setGravity(Gravity.CENTER);
        radioPanel.addView(radioName);
        radioNow = Ui.text(this, 18, "sans-serif", 0xE6FFFFFF);
        radioNow.setGravity(Gravity.CENTER);
        radioPanel.addView(radioNow, lp(-2, -2, 0, Ui.dp(this, 14), 0, 0));
        radioNext = Ui.text(this, 14, "sans-serif", 0x80FFFFFF);
        radioNext.setGravity(Gravity.CENTER);
        radioPanel.addView(radioNext, lp(-2, -2, 0, Ui.dp(this, 8), 0, 0));
        root.addView(radioPanel, new FrameLayout.LayoutParams(-1, -1));

        status = Ui.text(this, 18, "sans-serif-light", Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setShadowLayer(Ui.dp(this, 8), 0, 0, 0xFF000000);
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        digits = Ui.text(this, 42, "sans-serif-light", Color.WHITE);
        digits.setShadowLayer(Ui.dp(this, 8), 0, 0, 0xFF000000);
        digits.setVisibility(View.GONE);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        dl.setMargins(0, Ui.dp(this, 32), Ui.dp(this, 48), 0);
        root.addView(digits, dl);

        pauseBadge = Ui.text(this, 15, "sans-serif-medium", Color.WHITE);
        pauseBadge.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        GradientDrawable pb = new GradientDrawable();
        pb.setCornerRadius(Ui.dp(this, 16));
        pb.setColor(0xCC1C1C1E);
        pauseBadge.setBackground(pb);
        pauseBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        pl.setMargins(Ui.dp(this, 48), Ui.dp(this, 32), 0, 0);
        root.addView(pauseBadge, pl);

        // Now/next banner along the bottom.
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(Ui.dp(this, 24), Ui.dp(this, 18), Ui.dp(this, 28), Ui.dp(this, 18));
        GradientDrawable bbg = new GradientDrawable();
        bbg.setCornerRadius(Ui.dp(this, 18));
        bbg.setColor(0xE61C1C1E);
        bbg.setStroke(Ui.dp(this, 1), 0x1FFFFFFF);
        banner.setBackground(bbg);
        banner.setAlpha(0f);

        bannerLogo = new ImageView(this);
        bannerLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        banner.addView(bannerLogo, new LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 62)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        banner.addView(textCol, lp(0, -2, Ui.dp(this, 20), 0, 0, 0, 1f));

        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setGravity(Gravity.BOTTOM);
        bannerNumber = Ui.text(this, 22, "sans-serif-medium", 0xB3FFFFFF);
        nameRow.addView(bannerNumber, new LinearLayout.LayoutParams(-2, -2));
        bannerName = Ui.text(this, 22, "sans-serif", Color.WHITE);
        bannerName.setSingleLine(true);
        nameRow.addView(bannerName, lp(0, -2, Ui.dp(this, 12), 0, Ui.dp(this, 12), 0, 1f));
        bannerTimes = Ui.text(this, 14, "sans-serif-medium", 0xB3FFFFFF);
        nameRow.addView(bannerTimes, new LinearLayout.LayoutParams(-2, -2));
        textCol.addView(nameRow);

        bannerNow = Ui.text(this, 16, "sans-serif", Color.WHITE);
        bannerNow.setSingleLine(true);
        bannerNow.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(bannerNow, lp(-1, -2, 0, Ui.dp(this, 6), 0, 0));

        bannerProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bannerProgress.setMax(1000);
        bannerProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        bannerProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x40FFFFFF));
        textCol.addView(bannerProgress, lp(-1, Ui.dp(this, 4), 0, Ui.dp(this, 8), 0, Ui.dp(this, 6)));

        bannerNext = Ui.text(this, 13, "sans-serif", 0x99FFFFFF);
        bannerNext.setSingleLine(true);
        bannerNext.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(bannerNext);

        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bl.setMargins(Ui.dp(this, 48), 0, Ui.dp(this, 48), Ui.dp(this, 36));
        root.addView(banner, bl);

        // Channel list, slides over the left side.
        listPanel = new FrameLayout(this);
        listPanel.setBackgroundColor(0xF0141416);
        listPanel.setVisibility(View.GONE);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        listRows = new LinearLayout(this);
        listRows.setOrientation(LinearLayout.VERTICAL);
        listRows.setPadding(Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24));
        sv.addView(listRows, new FrameLayout.LayoutParams(-1, -2));
        listPanel.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        root.addView(listPanel, new FrameLayout.LayoutParams(Ui.dp(this, 520), -1, Gravity.START));

        // Stream info, top right; monospace so the labels line up.
        infoPanel = Ui.text(this, 13, "sans-serif-medium", 0xE6FFFFFF);
        infoPanel.setLineSpacing(Ui.dp(this, 4), 1f);
        infoPanel.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
        infoPanel.setTypeface(Typeface.MONOSPACE);
        GradientDrawable ib = new GradientDrawable();
        ib.setCornerRadius(Ui.dp(this, 14));
        ib.setColor(0xD91C1C1E);
        infoPanel.setBackground(ib);
        infoPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        il.setMargins(0, Ui.dp(this, 32), Ui.dp(this, 48), 0);
        root.addView(infoPanel, il);

        sleepBadge = Ui.text(this, 16, "sans-serif-medium", Color.WHITE);
        sleepBadge.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        GradientDrawable sb = new GradientDrawable();
        sb.setCornerRadius(Ui.dp(this, 22));
        sb.setColor(0xE61C1C1E);
        sleepBadge.setBackground(sb);
        sleepBadge.setVisibility(View.GONE);
        root.addView(sleepBadge, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return root;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(l, t, r, b);
        return p;
    }

    private void status(String s) {
        status.setText(s);
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

    /**
     * Draws subtitle cues over the video: DVB bitmap subtitles at the position the broadcaster
     * set, text subtitles centred near the bottom. Replaces Media3's UI module to stay small.
     */
    static final class CueView extends View {
        private List<Cue> cues = Collections.emptyList();
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        CueView(Context c) {
            super(c);
            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void setCues(List<Cue> c) {
            cues = c == null ? Collections.emptyList() : c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            float bottom = H * 0.92f;
            for (Cue cue : cues) {
                if (cue.bitmap != null) {
                    float x = cue.position != Cue.DIMEN_UNSET ? cue.position * W : 0;
                    float w = cue.size != Cue.DIMEN_UNSET ? cue.size * W : cue.bitmap.getWidth();
                    float h = cue.bitmapHeight != Cue.DIMEN_UNSET ? cue.bitmapHeight * H : w * cue.bitmap.getHeight() / cue.bitmap.getWidth();
                    float y = cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION ? cue.line * H : bottom - h;
                    if (cue.positionAnchor == Cue.ANCHOR_TYPE_MIDDLE) x -= w / 2; else if (cue.positionAnchor == Cue.ANCHOR_TYPE_END) x -= w;
                    if (cue.lineAnchor == Cue.ANCHOR_TYPE_MIDDLE) y -= h / 2; else if (cue.lineAnchor == Cue.ANCHOR_TYPE_END) y -= h;
                    r.set(x, y, x + w, y + h);
                    c.drawBitmap(cue.bitmap, null, r, null);
                } else if (cue.text != null && cue.text.length() > 0) {
                    text.setTextSize(H * 0.045f);
                    StaticLayout l = StaticLayout.Builder.obtain(cue.text, 0, cue.text.length(), text, (int) (W * 0.8f))
                            .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
                    float y = bottom - l.getHeight();
                    c.save();
                    c.translate(W * 0.1f, y);
                    // Outline first, then the fill, for legibility on any picture.
                    text.setStyle(Paint.Style.STROKE);
                    text.setStrokeWidth(H * 0.006f);
                    text.setColor(Color.BLACK);
                    l.draw(c);
                    text.setStyle(Paint.Style.FILL);
                    text.setColor(Color.WHITE);
                    l.draw(c);
                    c.restore();
                    bottom = y - H * 0.01f;
                }
            }
        }
    }
}
