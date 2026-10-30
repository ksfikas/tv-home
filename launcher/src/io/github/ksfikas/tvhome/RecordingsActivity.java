package io.github.ksfikas.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tvheadend recordings: recording now, scheduled, recorded, failed.
 * OK plays a recording (or offers to cancel a scheduled one); hold OK to delete.
 */
public class RecordingsActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    private Tvh tvh;
    private LinearLayout list;
    private TextView empty;
    private String focusUuid;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        tvh = Tvh.get(this);
        if (tvh == null) { finish(); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E0E10);
        root.setPadding(Ui.dp(this, 64), Ui.dp(this, 40), Ui.dp(this, 64), 0);
        TextView title = Ui.text(this, 28, "sans-serif-light", Color.WHITE);
        title.setText(R.string.rec_title);
        root.addView(title);
        TextView hint = Ui.text(this, 12, "sans-serif", 0x80FFFFFF);
        hint.setText(R.string.rec_hint);
        root.addView(hint, new LinearLayout.LayoutParams(-2, -2));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 40));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        empty = Ui.text(this, 16, "sans-serif", 0xB3FFFFFF);
        list.addView(empty);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        empty.setText(R.string.rec_loading);
        Tvh.IO.execute(() -> {
            List<Tvh.Recording> all = null;
            List<Tvh.SeriesRule> series = Collections.emptyList();
            String err = null;
            try {
                all = tvh.recordings();
                series = tvh.seriesRules();
            } catch (IOException e) { err = e.getMessage(); }
            List<Tvh.Recording> res = all;
            List<Tvh.SeriesRule> ser = series;
            String e2 = err;
            runOnUiThread(() -> { if (!isDestroyed()) show(res, ser, e2); });
        });
    }

    private void show(List<Tvh.Recording> all, List<Tvh.SeriesRule> series, String err) {
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof Tvh.Recording) focusUuid = ((Tvh.Recording) focused.getTag()).uuid;
        list.removeAllViews();
        list.addView(empty);
        if (all == null) { empty.setText(err); return; }
        List<Reminders.Reminder> reminders = Reminders.all(this);
        boolean nothing = all.isEmpty() && series.isEmpty() && reminders.isEmpty();
        List<Tvh.Recording> now = new ArrayList<>(), sched = new ArrayList<>(), done = new ArrayList<>(), failed = new ArrayList<>();
        for (Tvh.Recording r : all) {
            if (r.recording()) now.add(r);
            else if (r.scheduled()) sched.add(r);
            else if (r.failed() || !r.playable()) failed.add(r);
            else done.add(r);
        }
        Collections.sort(sched, (a, b) -> Long.compare(a.start, b.start));
        Collections.sort(done, (a, b) -> Long.compare(b.start, a.start));
        Collections.sort(failed, (a, b) -> Long.compare(b.start, a.start));
        empty.setText(nothing ? getString(R.string.rec_empty) : "");
        empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
        View first = null, refocus = null;
        // The recording you were last watching goes first.
        String last = Tvh.resume(this).getString("last", null);
        for (Tvh.Recording r : done) if (r.uuid.equals(last)) { done.remove(r); done.add(0, r); break; }
        for (Object[] section : new Object[][]{{getString(R.string.rec_section_now), now}, {getString(R.string.rec_section_scheduled), sched},
                {getString(R.string.rec_section_recorded), done}, {getString(R.string.rec_section_failed), failed}}) {
            @SuppressWarnings("unchecked") List<Tvh.Recording> rs = (List<Tvh.Recording>) section[1];
            if (rs.isEmpty()) continue;
            list.addView(header((String) section[0]));
            for (Tvh.Recording r : rs) {
                TextView row = Ui.row(this, label(r));
                row.setTag(r);
                row.setOnClickListener(v -> open(r));
                row.setOnLongClickListener(v -> { delete(r); return true; });
                list.addView(row, new LinearLayout.LayoutParams(-1, -2));
                if (first == null) first = row;
                if (r.uuid.equals(focusUuid)) refocus = row;
            }
        }
        if (!series.isEmpty()) {
            list.addView(header(getString(R.string.rec_section_series)));
            for (Tvh.SeriesRule s : series) {
                TextView row = Ui.row(this, twoLine(s.title, s.channel + "   " + getString(R.string.rec_every_episode)));
                row.setOnClickListener(v -> Ui.confirm(this, s.title,
                        getString(R.string.rec_series_stop_message), getString(R.string.rec_series_stop_button),
                        () -> run(() -> tvh.deleteSeries(s.uuid), getString(R.string.rec_series_stopped))));
                list.addView(row, new LinearLayout.LayoutParams(-1, -2));
                if (first == null) first = row;
            }
        }
        if (!reminders.isEmpty()) {
            list.addView(header(getString(R.string.rec_section_reminders)));
            for (Reminders.Reminder r : reminders) {
                TextView row = Ui.row(this, twoLine(r.title, r.channelName + "   " + Ui.day(r.start) + "  " + Ui.time(this, r.start)));
                row.setOnClickListener(v -> Ui.confirm(this, r.title, getString(R.string.rec_reminder_cancel_message), getString(R.string.rec_reminder_cancel_button), () -> {
                    Reminders.remove(this, r.id);
                    load();
                }));
                list.addView(row, new LinearLayout.LayoutParams(-1, -2));
                if (first == null) first = row;
            }
        }
        View f = refocus != null ? refocus : first;
        if (f != null) f.requestFocus();
    }

    private TextView header(String s) {
        TextView h = Ui.text(this, 11, "sans-serif-medium", 0x99FFFFFF);
        h.setText(s);
        h.setLetterSpacing(0.22f);
        h.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), 0, Ui.dp(this, 6));
        return h;
    }

    private static CharSequence twoLine(String title, String meta) {
        SpannableStringBuilder s = new SpannableStringBuilder(title).append("\n");
        int m = s.length();
        s.append(meta);
        s.setSpan(new RelativeSizeSpan(0.8f), m, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(0x99888888), m, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private CharSequence label(Tvh.Recording r) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        s.append(r.title.isEmpty() ? getString(R.string.rec_untitled) : r.title);
        if (r.subtitle != null) s.append("  ·  ").append(r.subtitle);
        int metaStart = s.length();
        s.append("\n").append(r.channel).append("   ").append(Ui.day(r.start)).append("  ")
                .append(Ui.time(this, r.start)).append(" – ").append(Ui.time(this, r.stop))
                .append("   ").append(Ui.duration((r.stop - r.start) * 1000));
        if (r.size > 0) s.append("   ").append(String.valueOf(r.size / (1024 * 1024))).append(" MB");
        if (r.recording()) s.append("   ● ").append(getString(R.string.rec_badge_rec));
        long pos = Tvh.resume(this).getLong(r.uuid, 0);
        if (pos > 0 && r.stop > r.start) s.append("   ▶ ").append(getString(R.string.rec_percent_watched, (int) Math.min(99, pos / 10 / (r.stop - r.start))));
        if (r.failed()) s.append("   ").append(r.status);
        s.setSpan(new RelativeSizeSpan(0.8f), metaStart, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(0x99888888), metaStart, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private void open(Tvh.Recording r) {
        if (r.playable()) {
            startActivity(new Intent(this, PlayerActivity.class)
                    .putExtra(PlayerActivity.EXTRA_RECORDING, r.uuid)
                    .putExtra(PlayerActivity.EXTRA_TITLE, r.title));
        } else if (r.scheduled()) {
            Ui.confirm(this, r.title, getString(R.string.rec_cancel_scheduled_message), getString(R.string.rec_cancel_scheduled_button),
                    () -> run(() -> tvh.stopOrCancel(r.uuid, false), getString(R.string.rec_cancelled)));
        } else {
            delete(r);
        }
    }

    private void delete(Tvh.Recording r) {
        if (r.scheduled()) { open(r); return; }
        Ui.confirm(this, r.title, getString(r.recording() ? R.string.rec_delete_running_message : R.string.rec_delete_message),
                getString(R.string.rec_delete_button), () -> {
                    Tvh.saveResume(this, r.uuid, r.title, 0, 0); // and its "continue watching" tile
                    run(() -> tvh.deleteRecording(r.uuid), getString(R.string.rec_deleted));
                });
    }

    private void run(GuideActivity.IoAction a, String done) {
        Tvh.IO.execute(() -> {
            String msg = done;
            try { a.run(); } catch (IOException e) { msg = e.getMessage(); }
            String m = msg;
            runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); load(); });
        });
    }
}
