package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** What spoken phrases mean, in English and Greek. */
public class VoiceCommandTest {

    private static void check(String said, int type, String arg) {
        VoiceCommand c = VoiceCommand.parse(said);
        assertEquals(said + " -> type", type, c.type);
        assertEquals(said + " -> arg", arg, c.arg);
    }

    @Test
    public void watchingAChannel() {
        check("watch ERT 1", VoiceCommand.WATCH, "ERT 1".toLowerCase());
        check("Play channel 5", VoiceCommand.WATCH, "5");
        check("please switch to MEGA", VoiceCommand.WATCH, "mega");
        check("Δες ΕΡΤ ένα", VoiceCommand.WATCH, "ερτ 1");
        check("Βάλε το Mega", VoiceCommand.WATCH, "mega");
        check("Βάλε τον ΣΚΑΪ", VoiceCommand.WATCH, "σκαι");
    }

    @Test
    public void askingWhatIsOn() {
        check("what's on ERT 2", VoiceCommand.WHAT_ON, "ert 2");
        check("What is on MEGA?", VoiceCommand.WHAT_ON, "mega");
        check("whats on at Open", VoiceCommand.WHAT_ON, "open");
        check("Τι παίζει στο ΣΚΑΪ;", VoiceCommand.WHAT_ON, "σκαι");
        check("τι παίζει στην ΕΡΤ τρία", VoiceCommand.WHAT_ON, "ερτ 3");
    }

    @Test
    public void recording() {
        check("record the news", VoiceCommand.RECORD, "news");
        check("Record Gandhi", VoiceCommand.RECORD, "gandhi");
        check("Καταγραψε τα νέα", VoiceCommand.RECORD, "νεα");
        check("κάνε εγγραφή το ποδόσφαιρο", VoiceCommand.RECORD, "ποδοσφαιρο");
    }

    @Test
    public void anythingElseIsAPlainSearch() {
        check("Gandhi", VoiceCommand.SEARCH, "Gandhi");
        check("the news tonight", VoiceCommand.SEARCH, "the news tonight");
        check("Οικογένεια Χωραφά", VoiceCommand.SEARCH, "Οικογένεια Χωραφά");
        check("", VoiceCommand.SEARCH, "");
    }

    @Test
    public void spokenNumbersBecomeDigits() {
        assertEquals("ert 1", VoiceCommand.numbers("ert one"));
        assertEquals("ερτ 2", VoiceCommand.numbers("ερτ δύο"));
        assertEquals("channel 10", VoiceCommand.numbers("channel ten"));
        assertEquals("mega", VoiceCommand.numbers("mega"));
    }
}
