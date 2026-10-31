package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Reading JustWatch's answer: which apps stream a film, included ones first. */
public class OffersTest {

    private static String node(String title, int year, String offers) {
        return "{\"content\":{\"title\":\"" + title + "\",\"originalReleaseYear\":" + year + "},\"offers\":[" + offers + "]}";
    }

    private static String offer(String type, String tech) {
        return "{\"monetizationType\":\"" + type + "\",\"package\":{\"technicalName\":\"" + tech + "\"},\"standardWebURL\":\"https://x/" + tech + "\"}";
    }

    private static String answer(String... nodes) {
        StringBuilder e = new StringBuilder();
        for (String n : nodes) e.append(e.length() == 0 ? "" : ",").append("{\"node\":").append(n).append("}");
        return "{\"data\":{\"popularTitles\":{\"edges\":[" + e + "]}}}";
    }

    @Test
    public void includedServicesComeBeforeRentAndBuyAndEachAppOnce() throws Exception {
        List<Shelf.Offer> r = Shelf.parseOffers(answer(node("Coraline", 2009,
                offer("RENT", "itunes") + "," + offer("BUY", "itunes") + "," + offer("FLATRATE", "netflix"))), "Coraline", 2009);
        assertEquals(2, r.size());
        assertEquals("Netflix", r.get(0).app);
        assertTrue(r.get(0).included);
        assertEquals("com.netflix.ninja", r.get(0).pkg);
        assertEquals("Apple TV", r.get(1).app);
        assertFalse(r.get(1).included);
    }

    @Test
    public void theMatchingTitleAndYearIsUsedNotJustTheFirstResult() throws Exception {
        List<Shelf.Offer> r = Shelf.parseOffers(answer(
                node("Coraline", 1999, offer("FLATRATE", "netflix")),
                node("coraline", 2009, offer("FLATRATE", "disneyplus"))), "Coraline", 2009);
        assertEquals(1, r.size());
        assertEquals("Disney+", r.get(0).app);
    }

    @Test
    public void unknownServicesAndNoMatchGiveNothing() throws Exception {
        assertTrue(Shelf.parseOffers(answer(node("Film", 2020, offer("FLATRATE", "somelocalservice"))), "Film", 2020).isEmpty());
        assertTrue(Shelf.parseOffers(answer(node("Other", 2020, offer("FLATRATE", "netflix"))), "Film", 2020).isEmpty());
        assertTrue(Shelf.parseOffers(answer(), "Film", 2020).isEmpty());
    }

    @Test
    public void primeVideoHasSeveralJustWatchNames() throws Exception {
        List<Shelf.Offer> r = Shelf.parseOffers(answer(node("Film", 2020, offer("FLATRATE", "amazonprimevideo"))), "Film", 2020);
        assertEquals("Prime Video", r.get(0).app);
    }
}
