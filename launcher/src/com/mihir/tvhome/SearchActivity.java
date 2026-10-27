package com.mihir.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;

/**
 * Search the TV guide by title: every upcoming airing, soonest first.
 * OK on a result: watch, record, record every episode, remind me.
 */
public class SearchActivity extends Activity {

    private Tvh tvh;
    private EditText query;
    private LinearLayout results;
    private TextView message;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable search = this::search;
    private int seq;

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
        title.setText("Search the guide");
        root.addView(title);

        query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Programme title, e.g. news, football, a film…");
        query.setHintTextColor(0x66FFFFFF);
        query.setTextColor(Color.WHITE);
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        GradientDrawable qb = new GradientDrawable();
        qb.setCornerRadius(Ui.dp(this, 10));
        qb.setColor(0xFF1C1C1E);
        qb.setStroke(Ui.dp(this, 2), 0x00FFFFFF);
        query.setBackground(qb);
        query.setOnFocusChangeListener((v, has) -> qb.setStroke(Ui.dp(this, 2), has ? 0xE6FFFFFF : 0x00FFFFFF));
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(-1, -2);
        ql.setMargins(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        root.addView(query, ql);
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(search);
                handler.postDelayed(search, 700);
            }
        });
        query.setOnEditorActionListener((v, action, e) -> {
            handler.removeCallbacks(search);
            search();
            if (results.getChildCount() > 1) results.getChildAt(1).requestFocus();
            return true;
        });

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 40));
        sv.addView(results);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        message = Ui.text(this, 14, "sans-serif", 0x99FFFFFF);
        message.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), 0, 0);
        results.addView(message);
        setContentView(root);
        query.requestFocus();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        // Down from the box goes to the first result rather than the keyboard's guess.
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && getCurrentFocus() == query && results.getChildCount() > 1) {
            results.getChildAt(1).requestFocus();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void search() {
        String q = query.getText().toString().trim();
        int mine = ++seq;
        if (q.length() < 2) { show(null, null, mine); return; }
        message.setText("Searching…");
        Tvh.IO.execute(() -> {
            List<Tvh.Event> found = null;
            String err = null;
            try {
                if (Tvh.channels.isEmpty()) tvh.loadChannels();
                found = tvh.search(q);
                // Only channels the launcher shows (Tvheadend also lists disabled ones' programmes).
                found.removeIf(e -> Tvh.find(e.channelUuid) == null);
            } catch (IOException e) { err = e.getMessage(); }
            List<Tvh.Event> f = found;
            String er = err;
            runOnUiThread(() -> show(f, er, mine));
        });
    }

    private void show(List<Tvh.Event> found, String err, int mine) {
        if (mine != seq || isDestroyed()) return;
        View focused = getCurrentFocus();
        long keepId = focused != null && focused.getTag() instanceof Tvh.Event ? ((Tvh.Event) focused.getTag()).id : -1;
        results.removeAllViews();
        results.addView(message);
        if (err != null) { message.setText(err); return; }
        if (found == null) { message.setText(""); return; }
        message.setText(found.isEmpty() ? "Nothing coming up with that title" : found.size() + " upcoming");
        long nowSec = System.currentTimeMillis() / 1000;
        for (Tvh.Event ev : found) {
            TextView row = Ui.row(this, label(ev, nowSec));
            row.setTag(ev);
            row.setOnClickListener(v -> GuideActivity.programmeMenu(this, tvh, ev, this::search));
            results.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (ev.id == keepId) row.requestFocus();
        }
    }

    private CharSequence label(Tvh.Event ev, long nowSec) {
        SpannableStringBuilder s = new SpannableStringBuilder(ev.title);
        if (ev.subtitle != null) s.append("  ·  ").append(ev.subtitle);
        int meta = s.length();
        Tvh.Channel ch = Tvh.find(ev.channelUuid);
        s.append("\n").append(ch == null ? "" : ch.name + "   ")
                .append(ev.isOn(nowSec) ? "On now, until " + Ui.time(this, ev.stop)
                        : Ui.day(ev.start) + "  " + Ui.time(this, ev.start) + " – " + Ui.time(this, ev.stop));
        if (ev.recordingSet()) s.append("   ● Recording");
        else if (Reminders.isSet(this, ev.id)) s.append("   ○ Reminder");
        s.setSpan(new RelativeSizeSpan(0.8f), meta, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(0x99888888), meta, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }
}
