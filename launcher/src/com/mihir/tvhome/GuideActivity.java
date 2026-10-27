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
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen programme guide: channels down the side, time across, drawn by one custom view.
 * Loads six hours at a time and fetches more as you move right.
 * OK on a programme that's on now watches it; OK on a later one records it (or cancels).
 * Long-press OK on one that's on now to record it.
 */
public class GuideActivity extends Activity {

    private static final long CHUNK = 6 * 3600, STEP = 30 * 60, SPAN = 2 * 3600;
    /** Logo width in the channel column, in pixels (small, so all channels' logos fit in the cache). */
    static final int LOGO_W = 160;

    private Tvh tvh;
    private GuideView view;
    private final Map<String, List<Tvh.Event>> events = new HashMap<>();
    private long loadedFrom, loadedTo;
    private boolean loading, longPress;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        tvh = Tvh.get(this);
        if (tvh == null) { finish(); return; }
        view = new GuideView(this);
        setContentView(view);
        long now = System.currentTimeMillis() / 1000;
        view.viewStart = now - now % STEP;
        loadedFrom = loadedTo = view.viewStart;
        List<Tvh.Channel> list = Tvh.ofKind(false);
        if (list.isEmpty()) {
            Tvh.IO.execute(() -> {
                try { tvh.loadChannels(); } catch (IOException ignored) { }
                runOnUiThread(this::setChannels);
            });
        } else setChannels();
    }

    private void setChannels() {
        view.channels = Tvh.ofKind(false);
        String last = Tvh.prefs(this).getString("last_channel", null);
        for (int i = 0; i < view.channels.size(); i++) if (view.channels.get(i).uuid.equals(last)) view.selRow = i;
        view.cursor = System.currentTimeMillis() / 1000;
        loadMore();
        // Logos are usually on disk already (the home row fetched them); load into memory for drawing.
        List<Tvh.Channel> chs = view.channels;
        Tvh.IO.execute(() -> {
            for (Tvh.Channel ch : chs) tvh.logo(this, ch, LOGO_W);
            runOnUiThread(() -> view.invalidate());
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back (e.g. after recording something elsewhere): refresh the visible window's marks.
        if (loadedTo > loadedFrom) reload();
    }

    /** Fetch the next six hours after what we have. */
    private void loadMore() {
        if (loading) return;
        loading = true;
        long from = loadedTo, to = loadedTo + CHUNK;
        Tvh.IO.execute(() -> {
            Map<String, List<Tvh.Event>> got = null;
            try { got = tvh.window(from, to); } catch (IOException ignored) { }
            Map<String, List<Tvh.Event>> res = got;
            runOnUiThread(() -> {
                loading = false;
                if (res == null || isDestroyed()) {
                    if (res == null) Toast.makeText(this, "Can't reach Tvheadend", Toast.LENGTH_SHORT).show();
                    return;
                }
                merge(res);
                loadedTo = to;
                view.selectAtCursor();
                view.invalidate();
            });
        });
    }

    /** Re-fetch everything loaded so far (after recording or cancelling). */
    private void reload() {
        long from = loadedFrom, to = loadedTo;
        Tvh.IO.execute(() -> {
            Map<String, List<Tvh.Event>> got = null;
            try { got = tvh.window(from, to); } catch (IOException ignored) { }
            Map<String, List<Tvh.Event>> res = got;
            runOnUiThread(() -> {
                if (res == null || isDestroyed()) return;
                long selId = view.sel == null ? -1 : view.sel.id;
                events.clear();
                merge(res);
                view.reselect(selId);
                view.invalidate();
            });
        });
    }

    private void merge(Map<String, List<Tvh.Event>> got) {
        for (Map.Entry<String, List<Tvh.Event>> e : got.entrySet()) {
            List<Tvh.Event> have = events.get(e.getKey());
            if (have == null) { events.put(e.getKey(), new ArrayList<>(e.getValue())); continue; }
            Set<Long> ids = new HashSet<>();
            for (Tvh.Event x : have) ids.add(x.id);
            for (Tvh.Event x : e.getValue()) if (!ids.contains(x.id)) have.add(x);
            Collections.sort(have, (a, c) -> Long.compare(a.start, c.start));
        }
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: view.moveRow(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: view.moveRow(+1); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP: view.moveRow(-view.visibleRows()); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN: view.moveRow(view.visibleRows()); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: view.moveEvent(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                view.moveEvent(+1);
                if (view.viewStart + SPAN + STEP >= loadedTo) loadMore();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) { e.startTracking(); longPress = false; }
                return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            longPress = true;
            if (view.sel != null) recordMenu(view.sel);
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && !longPress && !e.isCanceled()) {
            Tvh.Event ev = view.sel;
            Tvh.Channel ch = view.selChannel();
            long now = System.currentTimeMillis() / 1000;
            if (ev != null && ev.isOn(now) || ev == null && ch != null) watch(ch);
            else if (ev != null && ev.start > now) recordMenu(ev);
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void watch(Tvh.Channel ch) {
        if (ch == null) return;
        startActivity(new Intent(this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_CHANNEL, ch.uuid)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private void recordMenu(Tvh.Event ev) {
        long now = System.currentTimeMillis() / 1000;
        if (ev.stop <= now) return;
        if (ev.recordingSet()) {
            boolean running = ev.dvrState.startsWith("recording");
            Ui.confirm(this, ev.title, running ? "Stop this recording? What's recorded so far is kept."
                    : "Cancel this scheduled recording?", running ? "Stop" : "Cancel recording",
                    () -> dvr(() -> tvh.stopOrCancel(ev.dvrUuid, running), running ? "Recording stopped" : "Recording cancelled"));
        } else {
            Ui.confirm(this, ev.title, Ui.day(ev.start) + "  " + Ui.time(this, ev.start) + " – " + Ui.time(this, ev.stop),
                    "Record", () -> dvr(() -> tvh.record(ev.id), "Will record"));
        }
    }

    interface IoAction { void run() throws IOException; }

    private void dvr(IoAction action, String done) {
        Tvh.IO.execute(() -> {
            String msg = done;
            try { action.run(); } catch (IOException e) { msg = e.getMessage(); }
            String m = msg;
            runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); reload(); });
        });
    }

    // ---------------------------------------------------------------- view

    final class GuideView extends View {
        List<Tvh.Channel> channels = Collections.emptyList();
        long viewStart, cursor;
        int selRow, firstRow;
        Tvh.Event sel;

        private final int headerH = Ui.dp(getContext(), 46), rowH = Ui.dp(getContext(), 54),
                nameW = Ui.dp(getContext(), 240), infoH = Ui.dp(getContext(), 120),
                pad = Ui.dp(getContext(), 40), gap = Ui.dp(getContext(), 3), radius = Ui.dp(getContext(), 8);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        GuideView(Context c) {
            super(c);
            setBackgroundColor(0xFF0E0E10);
            setFocusable(true);
        }

        int visibleRows() { return Math.max(1, (getHeight() - 2 * pad - headerH - infoH) / rowH); }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            // Now that the height is known, open with the selected channel in the middle.
            firstRow = Math.max(0, Math.min(selRow - visibleRows() / 2, channels.size() - visibleRows()));
        }

        Tvh.Channel selChannel() { return channels.isEmpty() ? null : channels.get(selRow); }

        private List<Tvh.Event> rowEvents(int row) {
            List<Tvh.Event> l = events.get(channels.get(row).uuid);
            return l == null ? Collections.emptyList() : l;
        }

        /** Pick the programme on the selected row that's airing at the cursor time. */
        void selectAtCursor() {
            if (channels.isEmpty()) { sel = null; return; }
            Tvh.Event best = null;
            for (Tvh.Event e : rowEvents(selRow)) {
                if (e.stop <= Math.max(cursor, viewStart)) continue;
                best = e;
                break;
            }
            sel = best;
            keepVisible();
            invalidate();
        }

        void reselect(long id) {
            for (Tvh.Event e : rowEvents(selRow)) if (e.id == id) { sel = e; return; }
            selectAtCursor();
        }

        void moveRow(int d) {
            if (channels.isEmpty()) return;
            selRow = Math.max(0, Math.min(channels.size() - 1, selRow + d));
            selectAtCursor();
        }

        void moveEvent(int d) {
            if (channels.isEmpty()) return;
            List<Tvh.Event> l = rowEvents(selRow);
            int i = sel == null ? -1 : l.indexOf(sel);
            long now = System.currentTimeMillis() / 1000;
            if (i < 0) {
                // Empty stretch: just slide the window.
                viewStart = Math.max(now - now % STEP, viewStart + d * STEP);
                cursor = viewStart;
                selectAtCursor();
                return;
            }
            int j = i + d;
            if (j < 0 || j >= l.size() || l.get(j).stop <= now) {
                if (d > 0) { viewStart += STEP; cursor = Math.max(cursor, viewStart); }
                invalidate();
                return;
            }
            sel = l.get(j);
            cursor = Math.max(sel.start, now);
            keepVisible();
            invalidate();
        }

        private void keepVisible() {
            long now = System.currentTimeMillis() / 1000, floor = now - now % STEP;
            if (sel != null) {
                while (sel.start >= viewStart + SPAN - STEP / 2) viewStart += STEP;
                while (sel.stop <= viewStart + STEP / 2 && viewStart > floor) viewStart -= STEP;
            }
            viewStart = Math.max(floor, viewStart);
            int vis = visibleRows();
            if (selRow < firstRow) firstRow = selRow;
            if (selRow >= firstRow + vis) firstRow = selRow - vis + 1;
        }

        private float xOf(long t, float gridL, float gridW) {
            return gridL + (t - viewStart) * gridW / SPAN;
        }

        @Override
        protected void onDraw(Canvas c) {
            Context ctx = getContext();
            int W = getWidth(), H = getHeight();
            float gridL = pad + nameW, gridR = W - pad, gridW = gridR - gridL;
            long now = System.currentTimeMillis() / 1000;

            // Time header.
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setTextSize(Ui.dp(ctx, 13));
            text.setColor(0xB3FFFFFF);
            c.drawText(Ui.day(viewStart), pad, pad + headerH * 0.55f, text);
            for (long t = viewStart; t < viewStart + SPAN; t += STEP) {
                c.drawText(Ui.time(ctx, t), xOf(t, gridL, gridW) + Ui.dp(ctx, 6), pad + headerH * 0.55f, text);
            }

            int vis = visibleRows();
            float top = pad + headerH;
            for (int row = firstRow; row < Math.min(channels.size(), firstRow + vis); row++) {
                float y = top + (row - firstRow) * rowH;
                Tvh.Channel ch = channels.get(row);
                boolean selRowNow = row == selRow;

                // Channel column: number, logo if already cached, else the name.
                fill.setColor(selRowNow ? 0xFF2A2A2E : 0xFF1A1A1D);
                r.set(pad, y + gap, gridL - gap * 2, y + rowH - gap);
                c.drawRoundRect(r, radius, radius, fill);
                text.setTextSize(Ui.dp(ctx, 12));
                text.setColor(0x99FFFFFF);
                c.drawText(ch.numberLabel(), pad + Ui.dp(ctx, 10), y + rowH / 2f + Ui.dp(ctx, 4), text);
                Bitmap logo = Tvh.cachedLogo(ch.icon, LOGO_W);
                float nameX = pad + Ui.dp(ctx, 48);
                if (logo != null) {
                    float lh = rowH - gap * 2 - Ui.dp(ctx, 12), lw = Math.min(Ui.dp(ctx, 80), lh * logo.getWidth() / logo.getHeight());
                    r.set(nameX, y + (rowH - lh) / 2, nameX + lw, y + (rowH + lh) / 2);
                    c.drawBitmap(logo, null, r, null);
                    nameX += Ui.dp(ctx, 90);
                }
                text.setTextSize(Ui.dp(ctx, 14));
                text.setColor(Color.WHITE);
                CharSequence name = TextUtils.ellipsize(ch.name, text, gridL - gap * 2 - nameX - Ui.dp(ctx, 8), TextUtils.TruncateAt.END);
                c.drawText(name, 0, name.length(), nameX, y + rowH / 2f + Ui.dp(ctx, 5), text);

                // Programmes.
                for (Tvh.Event e : rowEvents(row)) {
                    if (e.stop <= viewStart || e.start >= viewStart + SPAN) continue;
                    float x0 = Math.max(gridL, xOf(e.start, gridL, gridW)), x1 = Math.min(gridR, xOf(e.stop, gridL, gridW));
                    boolean isSel = e == sel, on = e.isOn(now);
                    fill.setColor(isSel ? 0xF2FFFFFF : on ? 0xFF2C2C31 : 0xFF1E1E22);
                    r.set(x0 + gap, y + gap, x1 - gap, y + rowH - gap);
                    c.drawRoundRect(r, radius, radius, fill);
                    float tx = x0 + Ui.dp(ctx, 10);
                    if (e.recordingSet()) {
                        fill.setColor(0xFFE53935);
                        c.drawCircle(tx + Ui.dp(ctx, 4), y + rowH / 2f, Ui.dp(ctx, 4), fill);
                        tx += Ui.dp(ctx, 14);
                    }
                    text.setColor(isSel ? 0xFF111111 : on ? Color.WHITE : 0xCCFFFFFF);
                    text.setTextSize(Ui.dp(ctx, 13));
                    CharSequence t = TextUtils.ellipsize(e.title, text, x1 - tx - Ui.dp(ctx, 8), TextUtils.TruncateAt.END);
                    c.drawText(t, 0, t.length(), tx, y + rowH / 2f + Ui.dp(ctx, 5), text);
                }
            }

            // "Now" line.
            if (now > viewStart && now < viewStart + SPAN) {
                fill.setColor(0xFFE53935);
                float x = xOf(now, gridL, gridW);
                c.drawRect(x - 1, pad + headerH - Ui.dp(ctx, 6), x + 1, top + vis * rowH, fill);
            }

            // Details of the selected programme.
            float iy = H - pad - infoH + Ui.dp(ctx, 10);
            Tvh.Channel ch = selChannel();
            if (sel != null && ch != null) {
                text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                text.setTextSize(Ui.dp(ctx, 20));
                text.setColor(Color.WHITE);
                CharSequence title = TextUtils.ellipsize(sel.title + (sel.subtitle != null ? "  ·  " + sel.subtitle : ""),
                        text, W - 2 * pad, TextUtils.TruncateAt.END);
                c.drawText(title, 0, title.length(), pad, iy + Ui.dp(ctx, 20), text);
                text.setTextSize(Ui.dp(ctx, 13));
                text.setColor(0xB3FFFFFF);
                String meta = ch.name + "   " + Ui.day(sel.start) + "  " + Ui.time(ctx, sel.start) + " – " + Ui.time(ctx, sel.stop)
                        + (sel.recordingSet() ? "   ● Recording set" : "")
                        + (sel.isOn(now) ? "   OK to watch · hold OK to " + (sel.recordingSet() ? "stop recording" : "record")
                           : sel.start > now ? (sel.recordingSet() ? "   OK to cancel the recording" : "   OK to record") : "");
                c.drawText(meta, pad, iy + Ui.dp(ctx, 44), text);
                if (sel.description != null) {
                    text.setColor(0x99FFFFFF);
                    StaticLayout desc = StaticLayout.Builder.obtain(sel.description, 0, sel.description.length(), text, W - 2 * pad)
                            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
                    c.save();
                    c.translate(pad, iy + Ui.dp(ctx, 54));
                    desc.draw(c);
                    c.restore();
                }
            } else if (channels.isEmpty() || loading) {
                text.setTextSize(Ui.dp(ctx, 16));
                text.setColor(0xB3FFFFFF);
                c.drawText(loading ? "Loading guide…" : "No guide data", pad, iy + Ui.dp(ctx, 20), text);
            }
        }
    }
}
