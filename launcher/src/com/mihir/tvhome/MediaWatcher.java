package com.mihir.tvhome;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hears what other apps are playing (their media sessions) and records it in Watched.
 * Android only shares media sessions with an app that has notification access, so this is a
 * notification listener; it ignores notifications themselves. Granted once over ADB:
 *   adb shell cmd notification allow_listener com.mihir.tvhome/com.mihir.tvhome.MediaWatcher
 * Android wakes it on session changes; it does nothing in between.
 */
public class MediaWatcher extends NotificationListenerService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token, MediaController.Callback> tracked = new HashMap<>();
    private MediaSessionManager sessions;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::track;

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        ComponentName me = new ComponentName(this, MediaWatcher.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, handler);
            track(sessions.getActiveSessions(me));
        } catch (SecurityException ignored) { } // access was taken away
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        tracked.clear();
    }

    private void track(List<MediaController> controllers) {
        if (controllers == null) return;
        for (MediaController mc : controllers) {
            MediaSession.Token token = mc.getSessionToken();
            if (tracked.containsKey(token)) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState state) { Watched.save(MediaWatcher.this, mc); }
                @Override public void onMetadataChanged(MediaMetadata metadata) { Watched.save(MediaWatcher.this, mc); }
                @Override public void onSessionDestroyed() {
                    mc.unregisterCallback(this);
                    tracked.remove(token);
                }
            };
            mc.registerCallback(cb, handler);
            tracked.put(token, cb);
            Watched.save(this, mc);
        }
    }
}
