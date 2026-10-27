package com.mihir.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

    static final String EXTRA_CHANNEL = "channel", EXTRA_RECORDING = "recording", EXTRA_TITLE = "title";

    private Tvh tvh;
    private List<Tvh.Channel> list = new ArrayList<>();
    private int index = -1, previousIndex = -1;
    private String recordingUuid, recordingTitle;
    private Tvh.Event current, next; // live: what's on now/next on this channel, with DVR state

    private ExoPlayer player;
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideBanner = () -> banner.animate().alpha(0f).setDuration(250).start();
    private final Runnable tuneNow = this::startPlayback;
    private final Runnable commitDigits = this::commitDigits;
    private final Runnable retry = this::startPlayback;
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
        if (tvh == null) { finish(); return; }
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
        status("Loading channels…");
        Tvh.IO.execute(() -> {
            try {
                tvh.loadChannels();
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
        if (list.isEmpty()) { status("No channels on the Tvheadend server"); return; }
        int i = 0;
        for (int j = 0; j < list.size(); j++) if (list.get(j).uuid.equals(target)) i = j;
        index = -1;
        tuneTo(i, false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        if ((index >= 0 || isRecording()) && player == null) startPlayback();
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
        current = next = null;
        Tvh.prefs(this).edit().putString("last_channel", list.get(i).uuid).apply();
        boolean radio = list.get(i).radio;
        radioPanel.setVisibility(radio ? View.VISIBLE : View.GONE);
        videoFrame.setVisibility(radio ? View.GONE : View.VISIBLE);
        showBanner();
        handler.removeCallbacks(tuneNow);
        handler.removeCallbacks(retry);
        if (debounce) handler.postDelayed(tuneNow, 450);
        else startPlayback();
    }

    private void startPlayback() {
        if (index < 0 && !isRecording()) return;
        long seq = ++tuneSeq;
        pausedAt = 0;
        behindMs = 0;
        updatePauseBadge();
        status("");
        if (player != null) player.stop();
        if (isRecording()) { play(tvh.recordingUrl(recordingUuid)); return; }
        Tvh.Channel ch = list.get(index);
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
                else if (state == Player.STATE_ENDED) {
                    if (isRecording()) finish();
                    else failed("Stream ended"); // also how an over-long live pause gets back to live
                }
            }

            @Override public void onPlayerError(PlaybackException e) { failed(describe(e)); }

            @Override public void onVideoSizeChanged(VideoSize v) {
                if (v.width > 0 && v.height > 0) videoFrame.setAspect(v.width * v.pixelWidthHeightRatio / v.height);
            }

            @Override public void onCues(CueGroup group) { cues.setCues(group.cues); }
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
            status(why == null ? "Can't play this" : why);
        }
    }

    private static String describe(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return "Tvheadend refused the stream (no free tuner, or no access)";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "Can't reach Tvheadend";
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
                return "This box can't decode this format";
            default:
                return "Playback error: " + e.getErrorCodeName();
        }
    }

    private void releasePlayer() {
        tuneSeq++;
        if (player != null) { player.release(); player = null; }
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
        pauseBadge.setText(pausedAt > 0 ? "❚❚  Paused  " + Ui.duration(behind) : Ui.duration(behind) + " behind live");
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
        status("No channel " + (long) want);
        handler.postDelayed(() -> status(""), 2000);
    }

    // ---------------------------------------------------------------- options menu

    private void options() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!isRadio() || isRecording()) {
            labels.add(player != null && !player.getPlayWhenReady() ? "Resume" : "Pause");
            actions.add(this::togglePause);
        }
        if (!isRecording() && (pausedAt > 0 || behindMs > 0)) { labels.add("Back to live"); actions.add(this::backToLive); }
        labels.add("Audio: " + selectedTrack(C.TRACK_TYPE_AUDIO, "Default"));
        actions.add(() -> pickTrack(C.TRACK_TYPE_AUDIO));
        if (!isRadio()) {
            labels.add("Subtitles: " + selectedTrack(C.TRACK_TYPE_TEXT, "Off"));
            actions.add(() -> pickTrack(C.TRACK_TYPE_TEXT));
        }
        if (!isRecording()) {
            Tvh.Event ev = current;
            if (ev != null && ev.recordingSet()) {
                labels.add("Stop recording “" + ev.title + "”");
                actions.add(() -> dvr(() -> tvh.stopOrCancel(ev.dvrUuid, true), "Recording stopped"));
            } else if (ev != null) {
                labels.add("Record “" + ev.title + "”");
                actions.add(() -> dvr(() -> tvh.record(ev.id), "Recording"));
            }
            labels.add("TV guide");
            actions.add(this::openGuide);
            labels.add("Recordings");
            actions.add(() -> startActivity(new Intent(this, RecordingsActivity.class)));
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
        startActivity(new Intent(this, GuideActivity.class));
    }

    private static String trackLabel(Format f, int n) {
        String s = f.label;
        if (s == null && f.language != null && !"und".equals(f.language)) {
            s = new Locale(f.language).getDisplayLanguage();
        }
        if (s == null || s.isEmpty()) s = "Track " + n;
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
        if (type == C.TRACK_TYPE_TEXT) { labels.add("Off"); groups.add(null); idx.add(-1); }
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
            Toast.makeText(this, type == C.TRACK_TYPE_TEXT ? "No subtitles on this channel" : "Only one audio track", Toast.LENGTH_SHORT).show();
            return;
        }
        Ui.menu(this, type == C.TRACK_TYPE_TEXT ? "Subtitles" : "Audio", labels.toArray(new String[0]), which -> {
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
            Bitmap logo = tvh.logo(this, ch, Ui.dp(this, 240));
            List<Tvh.Event> events = null;
            try { events = tvh.upcoming(ch.uuid, 3); } catch (IOException ignored) { }
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
            bannerNow.setText("No programme information");
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
        String n = nxt == null ? "" : "Next  " + Ui.time(this, nxt.start) + "  " + nxt.title;
        bannerNext.setText(n);
        radioNext.setText(n);
    }

    private void updateRecordingBanner() {
        bannerNumber.setText(player != null && !player.getPlayWhenReady() ? "❚❚" : "▶");
        bannerName.setText(recordingTitle == null ? "Recording" : recordingTitle);
        bannerLogo.setImageBitmap(null);
        long pos = player == null ? 0 : player.getCurrentPosition(), dur = player == null ? C.TIME_UNSET : player.getDuration();
        bannerTimes.setText(Ui.duration(pos) + (dur != C.TIME_UNSET ? " / " + Ui.duration(dur) : ""));
        bannerProgress.setVisibility(View.VISIBLE);
        bannerProgress.setProgress(dur > 0 ? (int) (pos * 1000 / dur) : 0);
        bannerNow.setText("◀ ▶ 30 s    ▲ ▼ 5 min    OK pause");
        bannerNext.setText("");
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
    }

    // ---------------------------------------------------------------- channel list

    private void openList() {
        if (list.isEmpty()) return;
        listRows.removeAllViews();
        View currentRow = null;
        for (int i = 0; i < list.size(); i++) {
            Tvh.Channel ch = list.get(i);
            String now = ch.now == null ? "" : "   " + ch.now.title;
            TextView row = Ui.row(this, ch.numberLabel() + "   " + ch.name + now);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            int target = i;
            row.setOnClickListener(v -> { closeList(); if (target != index) tuneTo(target, false); });
            listRows.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));
            if (i == index) currentRow = row;
        }
        listPanel.setVisibility(View.VISIBLE);
        View focus = currentRow != null ? currentRow : listRows.getChildAt(0);
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
        root.addView(listPanel, new FrameLayout.LayoutParams(Ui.dp(this, 380), -1, Gravity.START));
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
