package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

/** Language-dependent helpers: weekday names, the language labels and the poster link rewrite. */
public class LocaleHelpersTest {

    private static final Txt EN = TestTxt.english(), EL = TestTxt.greek();
    private Locale saved;

    @Before
    public void rememberLocale() { saved = Locale.getDefault(); }

    @After
    public void restoreLocale() { Locale.setDefault(saved); }

    @Test
    public void todayAndTomorrowAreWordsThenWeekdaysFollowTheLocale() {
        Locale.setDefault(Locale.ENGLISH);
        assertEquals("Today", Weather.dayName(EN, 0, "2026-10-31"));
        assertEquals("Tomorrow", Weather.dayName(EN, 1, "2026-11-01"));
        assertEquals("Monday", Weather.dayName(EN, 2, "2026-11-02"));   // 2 Nov 2026 is a Monday
        Locale.setDefault(new Locale("el"));
        assertEquals("Σήμερα", Weather.dayName(EL, 0, "2026-10-31"));
        assertEquals("Αύριο", Weather.dayName(EL, 1, "2026-11-01"));
        assertEquals("Δευτέρα", Weather.dayName(EL, 2, "2026-11-02"));
    }

    @Test
    public void aBadDateFallsBackToTheTextInsteadOfCrashing() {
        assertEquals("not-a-date", Weather.dayName(EN, 3, "not-a-date"));
    }

    @Test
    public void languageLabelsAreShownInTheirOwnLanguage() {
        // The same label whatever the UI language, so a user who picked the wrong one can still find theirs.
        assertEquals("English", Lang.label(EN, Lang.EN));
        assertEquals("English", Lang.label(EL, Lang.EN));
        assertEquals("Ελληνικά", Lang.label(EN, Lang.EL));
        assertEquals("Ελληνικά", Lang.label(EL, Lang.EL));
        assertEquals("Automatic (TV language)", Lang.label(EN, Lang.AUTO));
        assertEquals("Αυτόματα (γλώσσα τηλεόρασης)", Lang.label(EL, Lang.AUTO));
    }

    @Test
    public void theTileUsesTheShortAutomaticLabel() {
        assertEquals("Automatic", Lang.shortLabel(EN, Lang.AUTO));
        assertEquals("Αυτόματα", Lang.shortLabel(EL, Lang.AUTO));
        assertEquals("English", Lang.shortLabel(EN, Lang.EN));
        assertEquals("Ελληνικά", Lang.shortLabel(EL, Lang.EL));
    }

    @Test
    public void everyLanguageChoiceHasALabel() {
        for (String choice : Lang.CHOICES) {
            assertEquals(false, Lang.label(EN, choice).isEmpty());
            assertEquals(false, Lang.label(EL, choice).isEmpty());
        }
    }

    @Test
    public void posterLinksAskForTheBigImage() {
        assertEquals("https://x.example/a/600x900bb.jpg", Shelf.poster("https://x.example/a/115x170bb.png"));
        assertEquals("https://x.example/a/600x900bb.jpg", Shelf.poster("https://x.example/a/200x300bb.jpg"));
        assertEquals("https://x.example/a/cover.png", Shelf.poster("https://x.example/a/cover.png")); // not a sized link: unchanged
    }

    @Test
    public void shelfModesHaveANameInBothLanguages() {
        for (String m : new String[]{Shelf.APP, Shelf.ALL, Shelf.RECS, Shelf.OFF}) {
            assertEquals(false, Shelf.modeName(EN, m).isEmpty());
            assertEquals(false, Shelf.modeName(EL, m).isEmpty());
        }
        assertEquals("Picks & news", Shelf.modeName(EN, Shelf.RECS));
        assertEquals("Προτάσεις & νέα", Shelf.modeName(EL, Shelf.RECS));
    }
}
