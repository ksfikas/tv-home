package io.github.ksfikas.tvhome;

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

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    private Tvh tvh;
    private EditText query;
    private LinearLayout results;
    private TextView message;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable search = this::search;
    private int seq;
    private android.widget.ImageView mic;
    private boolean focusFirstResult; // after voice input: jump straight to the results

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
        // Mic first: OK and say the title (Google's voice input, through the remote's microphone).
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mic = Ui.iconView(this, R.drawable.sym_mic, Color.WHITE, 12);
        mic.setFocusable(true);
        mic.setClickable(true);
        GradientDrawable mb = new GradientDrawable();
        mb.setShape(GradientDrawable.OVAL);
        mb.setColor(0xFF1C1C1E);
        mic.setBackground(mb);
        mic.setOnFocusChangeListener((v, has) -> {
            mb.setColor(has ? 0xF2FFFFFF : 0xFF1C1C1E);
            mic.setImageTintList(android.content.res.ColorStateList.valueOf(has ? 0xFF111111 : Color.WHITE));
        });
        mic.setOnClickListener(v -> listen());
        int ms = Ui.dp(this, 48);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ms, ms);
        mlp.setMargins(0, 0, Ui.dp(this, 12), 0);
        bar.addView(mic, mlp);
        bar.addView(query, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(-1, -2);
        ql.setMargins(0, Ui.dp(this, 16), 0, Ui.dp(this, 8));
        root.addView(bar, ql);
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
        mic.requestFocus();
    }

    private static final int VOICE = 1;

    private void listen() {
        android.content.Intent i = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a programme title");
        try {
            // Google TV's own voice input (the remote's microphone), so Android doesn't ask which app.
            startActivityForResult(new android.content.Intent(i).setPackage("com.google.android.katniss"), VOICE);
        } catch (android.content.ActivityNotFoundException notGoogleTv) {
            try { startActivityForResult(i, VOICE); } catch (android.content.ActivityNotFoundException e) { voiceMissing(); }
        }
    }

    private void voiceMissing() {
        message.setText("Voice input isn't available on this box; type instead");
        query.requestFocus();
    }

    @Override
    protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE || result != RESULT_OK || data == null) return;
        java.util.ArrayList<String> said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (said == null || said.isEmpty()) return;
        query.setText(said.get(0));
        query.setSelection(query.length());
        handler.removeCallbacks(search);
        search();
        focusFirstResult = true;
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
        if (focusFirstResult && results.getChildCount() > 1) { focusFirstResult = false; results.getChildAt(1).requestFocus(); }
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
