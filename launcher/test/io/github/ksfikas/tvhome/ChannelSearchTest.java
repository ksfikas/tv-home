package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Channel search on the Search screen: by name (accent-blind) or by the start of the number. */
public class ChannelSearchTest {

    private static Tvh.Channel ch(double number, String name, boolean radio) {
        Tvh.Channel c = new Tvh.Channel();
        c.uuid = "u" + number + name;
        c.number = number;
        c.name = name;
        c.radio = radio;
        return c;
    }

    private static final List<Tvh.Channel> ALL = new ArrayList<>();
    static {
        ALL.add(ch(1, "ERT 1 HD", false));
        ALL.add(ch(2, "ERT2 SPORT", false));
        ALL.add(ch(3, "ERT 3 HD", false));
        ALL.add(ch(12, "ΣΚΑΪ", false));
        ALL.add(ch(16, "MEGA HD", false));
        ALL.add(ch(20, "ERT News", false));
        ALL.add(ch(700, "ErtNews Radio", true));
        ALL.add(ch(701, "Deftero 103,7", true));
        ALL.add(ch(0, "No number", false));
    }

    private static List<String> names(List<Tvh.Channel> found) {
        List<String> out = new ArrayList<>();
        for (Tvh.Channel c : found) out.add(c.name);
        return out;
    }

    @Test
    public void foldIgnoresCaseAndAccents() {
        assertEquals("σκαι", Tvh.fold("ΣΚΑΪ"));
        assertEquals("ειδησεις", Tvh.fold("Ειδήσεις"));
        assertEquals("mega hd", Tvh.fold("  MEGA HD "));
        assertEquals("", Tvh.fold(null));
    }

    @Test
    public void nameMatchesInsideAndStartingWithTheTextComesFirst() {
        List<String> found = names(Tvh.matchChannels(ALL, "ert", 10));
        // starts with "ert": ERT 1 HD, ERT2 SPORT, ERT 3 HD, ERT News, ErtNews Radio
        assertEquals(5, found.size());
        assertEquals("ERT 1 HD", found.get(0));
        // "news" is inside both, and starts neither
        assertEquals(2, Tvh.matchChannels(ALL, "news", 10).size());
        assertEquals(1, Tvh.matchChannels(ALL, "mega", 10).size());
    }

    @Test
    public void greekNamesMatchWithoutAccents() {
        assertEquals("ΣΚΑΪ", names(Tvh.matchChannels(ALL, "σκαι", 10)).get(0));
        assertEquals("ΣΚΑΪ", names(Tvh.matchChannels(ALL, "σκάι", 10)).get(0));
    }

    @Test
    public void digitsMatchTheStartOfTheNumber() {
        assertEquals(names(Tvh.matchChannels(ALL, "1", 10)), java.util.Arrays.asList("ERT 1 HD", "ΣΚΑΪ", "MEGA HD")); // 1, 12, 16
        assertEquals(java.util.Arrays.asList("ErtNews Radio", "Deftero 103,7"), names(Tvh.matchChannels(ALL, "70", 10)));
        assertEquals(java.util.Arrays.asList("ErtNews Radio"), names(Tvh.matchChannels(ALL, "700", 10)));
        assertTrue("a channel without a number is not found by digits", Tvh.matchChannels(ALL, "0", 10).isEmpty());
    }

    @Test
    public void emptyOrUnknownQueryFindsNothingAndTheListIsCapped() {
        assertTrue(Tvh.matchChannels(ALL, "", 10).isEmpty());
        assertTrue(Tvh.matchChannels(ALL, "   ", 10).isEmpty());
        assertTrue(Tvh.matchChannels(ALL, "zzz", 10).isEmpty());
        assertEquals(2, Tvh.matchChannels(ALL, "ert", 2).size());
    }
}
