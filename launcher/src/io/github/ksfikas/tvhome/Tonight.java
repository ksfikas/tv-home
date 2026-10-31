package io.github.ksfikas.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Tonight for you": programmes from the guide that fit what you already record and watch. No account
 * and no cloud: the signals are your recordings (a series you record, titles that share words with
 * them), your favourite channels, and a small bonus for prime time. Plain scoring, easy to test.
 */
final class Tonight {
    private Tonight() { }

    static final int RECORD = 1, SIMILAR = 2, FAVOURITE = 3, PRIME = 4;

    static final class Pick {
        Tvh.Event event;
        String channel;
        int score, reason;
        String reasonArg = "";
    }

    /** Words too common to say two titles are alike. */
    private static final Set<String> COMMON = new HashSet<>(java.util.Arrays.asList(
            "news", "the", "and", "with", "live", "show", "film", "movie", "tonight",
            "ειδησεις", "δελτιο", "ταινια", "εκπομπη", "σειρα", "ντοκιμαντερ", "παιδικο", "επεισοδιο"));

    /** A title without [K8]-style tags, accents or punctuation, for comparing. */
    static String key(String title) {
        return Tvh.fold(title == null ? "" : title).replaceAll("\\[[^\\]]*\\]", " ").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** The meaningful words (four letters or more) of a title. */
    static Set<String> words(String title) {
        Set<String> out = new HashSet<>();
        for (String w : key(title).split(" ")) if (w.length() >= 4 && !COMMON.contains(w)) out.add(w);
        return out;
    }

    /**
     * The best programmes starting between now and untilSec (or already running with most still to go),
     * in time order. Each carries why. Programmes you already record are left out (they appear under
     * Coming up). With nothing to go on (no recordings, no favourites) the result is empty.
     */
    static List<Pick> pick(Map<String, List<Tvh.Event>> window, Map<String, String> channelNames, Set<String> favourites,
                           List<String> recordedTitles, long nowSec, long untilSec, int max) {
        Map<String, String> recordedKeys = new HashMap<>();      // key -> the title as recorded
        Map<String, String> wordOwner = new HashMap<>();         // word -> a recorded title that has it
        for (String t : recordedTitles) {
            String k = key(t);
            if (k.isEmpty()) continue;
            recordedKeys.put(k, t);
            for (String w : words(t)) wordOwner.putIfAbsent(w, t);
        }
        List<Pick> all = new ArrayList<>();
        for (Map.Entry<String, List<Tvh.Event>> ch : window.entrySet()) {
            for (Tvh.Event e : ch.getValue()) {
                if (e.title == null || e.title.isEmpty() || e.recordingSet()) continue;
                boolean running = e.start <= nowSec && e.stop > nowSec;
                if (running ? e.progress(nowSec) > 0.4f : (e.start < nowSec || e.start >= untilSec)) continue;
                Pick p = new Pick();
                p.event = e;
                p.channel = channelNames.getOrDefault(ch.getKey(), "");
                String k = key(e.title);
                if (recordedKeys.containsKey(k)) { p.score = 6; p.reason = RECORD; p.reasonArg = recordedKeys.get(k); }
                else {
                    for (String w : words(e.title)) {
                        String owner = wordOwner.get(w);
                        if (owner != null) { p.score = 3; p.reason = SIMILAR; p.reasonArg = owner; break; }
                    }
                }
                if (favourites.contains(ch.getKey())) {
                    p.score += 2;
                    if (p.reason == 0) { p.reason = FAVOURITE; p.reasonArg = p.channel; }
                }
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTimeInMillis(e.start * 1000);
                int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
                if (p.score > 0 && hour >= 20 && hour < 23) { p.score += 1; if (p.reason == 0) p.reason = PRIME; }
                if (p.score >= 3) all.add(p);
            }
        }
        Collections.sort(all, (a, b) -> b.score != a.score ? Integer.compare(b.score, a.score) : Long.compare(a.event.start, b.event.start));
        List<Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> perChannel = new HashMap<>();
        for (Pick p : all) {
            if (out.size() >= max) break;
            if (!seen.add(key(p.event.title))) continue;                        // the same programme on two channels, or twice
            int n = perChannel.merge(p.event.channelUuid, 1, Integer::sum);
            if (n > 2) continue;                                                // not a whole evening of one channel
            out.add(p);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.event.start, b.event.start)); // shown in time order
        return out;
    }

    /** Why, in words ("You record X"). */
    static String why(Txt t, Pick p) {
        switch (p.reason) {
            case RECORD: return t.s(R.string.tonight_reason_record, p.reasonArg);
            case SIMILAR: return t.s(R.string.tonight_reason_similar, p.reasonArg);
            case FAVOURITE: return t.s(R.string.tonight_reason_favourite, p.reasonArg);
            default: return t.s(R.string.tonight_reason_prime);
        }
    }
}
