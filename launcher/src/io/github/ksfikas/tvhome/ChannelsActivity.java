package io.github.ksfikas.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Channel manager (Inputs → Tvheadend server → Channels): every channel Tvheadend has. OK shows or hides
 * one on the TV and everywhere else; hold OK changes its number. Changes are saved on the server at once.
 */
public class ChannelsActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    private static final int ALL = 0, SHOWN = 1, HIDDEN = 2;

    private Tvh tvh;
    private LinearLayout list;
    private TextView message;
    private List<Tvh.ChannelRow> rows = new ArrayList<>();
    private int filter = ALL;
    private String focusUuid;
    private boolean changed;

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
        title.setText(R.string.chan_title);
        root.addView(title);
        TextView hint = Ui.text(this, 12, "sans-serif", 0x80FFFFFF);
        hint.setText(R.string.chan_hint);
        root.addView(hint, new LinearLayout.LayoutParams(-2, -2));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 40));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        message = Ui.text(this, 16, "sans-serif", 0xB3FFFFFF);
        list.addView(message);
        setContentView(root);
        load();
    }

    @Override
    public void finish() {
        // The home screen and the guide read the shared channel list: refresh it if anything changed.
        if (changed && tvh != null) Tvh.IO.execute(() -> { try { tvh.loadChannels(); } catch (IOException ignored) { } });
        super.finish();
    }

    private void load() {
        message.setText(R.string.rec_loading);
        Tvh.IO.execute(() -> {
            List<Tvh.ChannelRow> got = null;
            String err = null;
            try { got = tvh.allChannelRows(); } catch (IOException e) { err = e.getMessage(); }
            List<Tvh.ChannelRow> res = got;
            String e2 = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (res == null) { message.setText(e2 == null ? getString(R.string.tvh_bad_reply) : e2); return; }
                rows = res;
                show();
            });
        });
    }

    private int count(int which) {
        int n = 0;
        for (Tvh.ChannelRow r : rows) if (which == ALL || (which == SHOWN) == r.enabled) n++;
        return n;
    }

    private void show() {
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof Tvh.ChannelRow) focusUuid = ((Tvh.ChannelRow) focused.getTag()).uuid;
        list.removeAllViews();
        list.addView(message);
        message.setText("");
        message.setVisibility(View.GONE);
        // First row: which channels to list. OK cycles all, shown, hidden.
        TextView filterRow = Ui.row(this, getString(filter == ALL ? R.string.chan_filter_all : filter == SHOWN ? R.string.chan_filter_shown
                : R.string.chan_filter_hidden, count(filter)));
        filterRow.setOnClickListener(v -> { filter = (filter + 1) % 3; focusUuid = null; show(); list.getChildAt(1).requestFocus(); });
        list.addView(filterRow, new LinearLayout.LayoutParams(-1, -2));
        View refocus = null;
        for (Tvh.ChannelRow r : rows) {
            if (filter != ALL && (filter == SHOWN) != r.enabled) continue;
            TextView row = Ui.row(this, label(r));
            row.setTag(r);
            row.setOnClickListener(v -> toggle(r, (TextView) v));
            row.setOnLongClickListener(v -> { askNumber(r, (TextView) v); return true; });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (r.uuid.equals(focusUuid)) refocus = row;
        }
        if (refocus != null) refocus.requestFocus(); else filterRow.requestFocus();
    }

    private CharSequence label(Tvh.ChannelRow r) {
        String name = (r.number > 0 ? r.number + "   " : "") + r.name;
        SpannableStringBuilder s = new SpannableStringBuilder(name).append("\n");
        int m = s.length();
        s.append(getString(r.enabled ? R.string.chan_shown : R.string.chan_hidden));
        s.setSpan(new RelativeSizeSpan(0.8f), m, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(r.enabled ? 0x99888888 : 0xFFE0A040), m, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private void toggle(Tvh.ChannelRow r, TextView row) {
        boolean to = !r.enabled;
        Tvh.IO.execute(() -> {
            String err = null;
            try { tvh.setChannelEnabled(r.uuid, to); } catch (IOException e) { err = e.getMessage(); }
            String e2 = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (e2 != null) { Toast.makeText(this, e2, Toast.LENGTH_LONG).show(); return; }
                r.enabled = to;
                changed = true;
                // In the shown/hidden views the row no longer belongs: redraw the list, otherwise just this row.
                if (filter == ALL) row.setText(label(r)); else show();
            });
        });
    }

    private void askNumber(Tvh.ChannelRow r, TextView row) {
        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setText(r.number > 0 ? String.valueOf(r.number) : "");
        edit.setSelection(edit.length());
        edit.setHint(R.string.chan_number_hint);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(0x66FFFFFF);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), 0);
        box.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        Ui.frost(new AlertDialog.Builder(this, R.style.Glass)
                .setTitle(getString(R.string.chan_number_title, r.name))
                .setView(box)
                .setPositiveButton(R.string.main_save, (d, w) -> saveNumber(r, edit.getText().toString().trim()))
                .setNegativeButton(R.string.ui_cancel, null)
                .create()).show();
    }

    private void saveNumber(Tvh.ChannelRow r, String text) {
        int n;
        try { n = Integer.parseInt(text); } catch (NumberFormatException e) { n = -1; }
        if (n < 1 || n > 9999) { Toast.makeText(this, R.string.chan_number_bad, Toast.LENGTH_LONG).show(); return; }
        int to = n;
        Tvh.IO.execute(() -> {
            String err = null;
            try { tvh.setChannelNumber(r.uuid, to); } catch (IOException e) { err = e.getMessage(); }
            String e2 = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (e2 != null) { Toast.makeText(this, e2, Toast.LENGTH_LONG).show(); return; }
                changed = true;
                focusUuid = r.uuid;
                load(); // re-sorted by the new number
            });
        });
    }
}
