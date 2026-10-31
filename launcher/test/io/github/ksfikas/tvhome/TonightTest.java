package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Choosing tonight's programmes from the guide. */
public class TonightTest {
    private static final long NOW = 1_800_000_000L;   // some moment; programmes are placed relative to it

    private static Tvh.Event ev(String ch, String title, long startOffsetMin, long lengthMin) {
        Tvh.Event e = new Tvh.Event();
        e.channelUuid = ch;
        e.title = title;
        e.start = NOW + startOffsetMin * 60;
        e.stop = e.start + lengthMin * 60;
        return e;
    }

    private static Map<String, List<Tvh.Event>> guide(Tvh.Event... events) {
        Map<String, List<Tvh.Event>> m = new HashMap<>();
        for (Tvh.Event e : events) m.computeIfAbsent(e.channelUuid, k -> new java.util.ArrayList<>()).add(e);
        return m;
    }

    private static Map<String, String> names() {
        Map<String, String> n = new HashMap<>();
        n.put("a", "ERT 1");
        n.put("b", "MEGA");
        return n;
    }

    @Test
    public void aSeriesYouRecordComesFirstWithThatReason() {
        List<Tonight.Pick> r = Tonight.pick(guide(ev("a", "Περίμετρος [K8]", 30, 60), ev("b", "Random Show", 30, 60)),
                names(), Collections.emptySet(), Arrays.asList("Περίμετρος"), NOW, NOW + 6 * 3600, 5);
        assertEquals(1, r.size());
        assertEquals(Tonight.RECORD, r.get(0).reason);
        assertEquals("Περίμετρος", r.get(0).reasonArg);
    }

    @Test
    public void sharedWordsAndFavouritesCount() {
        List<Tonight.Pick> r = Tonight.pick(guide(ev("a", "Gandhi: the story", 60, 90), ev("b", "Cooking today", 60, 30)),
                names(), new HashSet<>(Arrays.asList("b")), Arrays.asList("Gandhi"), NOW, NOW + 6 * 3600, 5);
        assertEquals(1, r.size());                       // the favourite-only programme scores 2: not enough alone
        assertEquals(Tonight.SIMILAR, r.get(0).reason);
        assertEquals("ERT 1", r.get(0).channel);
    }

    @Test
    public void aFavouriteChannelPlusLikeTitleIsEnough() {
        List<Tonight.Pick> r = Tonight.pick(guide(ev("b", "Gandhi in India", 60, 90)),
                names(), new HashSet<>(Arrays.asList("b")), Arrays.asList("Gandhi"), NOW, NOW + 6 * 3600, 5);
        assertEquals(1, r.size());
        assertTrue(r.get(0).score >= 5);
    }

    @Test
    public void nothingToGoOnGivesNothing() {
        assertTrue(Tonight.pick(guide(ev("a", "Anything", 30, 60)), names(), Collections.emptySet(),
                Collections.emptyList(), NOW, NOW + 6 * 3600, 5).isEmpty());
    }

    @Test
    public void alreadyRecordingOrAlmostOverIsLeftOut() {
        Tvh.Event rec = ev("a", "Show A", 30, 60);
        rec.dvrUuid = "d";
        rec.dvrState = "scheduled";
        Tvh.Event late = ev("a", "Show A", -50, 60);                 // 83% through
        Tvh.Event past = ev("a", "Show A", -90, 30);
        Tvh.Event far = ev("a", "Show A", 8 * 60, 30);                // after the window
        assertTrue(Tonight.pick(guide(rec, late, past, far), names(), Collections.emptySet(),
                Arrays.asList("Show A"), NOW, NOW + 6 * 3600, 5).isEmpty());
    }

    @Test
    public void theSameProgrammeOnTwoChannelsAppearsOnceAndResultsAreInTimeOrder() {
        List<Tonight.Pick> r = Tonight.pick(guide(ev("a", "Show A", 120, 60), ev("b", "Show A", 120, 60), ev("a", "Special Guests", 30, 60)),
                names(), Collections.emptySet(), Arrays.asList("Show A", "Special Report"), NOW, NOW + 6 * 3600, 5);
        assertEquals(2, r.size());
        assertEquals("Special Guests", r.get(0).event.title);
        assertEquals("Show A", r.get(1).event.title);
    }

    @Test
    public void keysIgnoreTagsAccentsAndCase() {
        assertEquals(Tonight.key("Περίμετρος[K8]"), Tonight.key("περιμετρος"));
        assertEquals("open news", Tonight.key("[K] Open News"));
    }

    @Test
    public void reasonsReadInBothLanguages() {
        Tonight.Pick p = new Tonight.Pick();
        p.reason = Tonight.RECORD;
        p.reasonArg = "X";
        assertTrue(Tonight.why(TestTxt.english(), p).contains("X"));
        assertTrue(Tonight.why(TestTxt.greek(), p).contains("X"));
    }
}
