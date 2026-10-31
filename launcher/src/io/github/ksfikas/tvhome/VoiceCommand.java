package io.github.ksfikas.tvhome;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a spoken phrase means: "watch ERT 1", "what's on MEGA", "record the news" (and the Greek
 * equivalents). Anything else is a plain search. Pure text handling, so it is easy to test; the
 * Search screen does the actual watching, looking up and recording.
 */
final class VoiceCommand {
    private VoiceCommand() { }

    static final int SEARCH = 0, WATCH = 1, WHAT_ON = 2, RECORD = 3;

    int type = SEARCH;
    String arg = "";

    private static final String POLITE = "^(please |παρακαλω |ok |hey )+";

    // Patterns run on folded text (lower case, no accents), so "Τι παίζει" and "τι παιζει" are the same.
    private static final Pattern WHAT_ON_RE = Pattern.compile(
            "^(?:what(?:'s| is| s)? (?:on|playing|showing)(?: on| at| in)?|whats on(?: on| at)?|τι παιζει(?: στο| στον| στην| στα| σε)?|τι εχει(?: στο| στον| στην| σε)?|τι δειχνει(?: το| ο| η)?) (.+)$");
    private static final Pattern RECORD_RE = Pattern.compile(
            "^(?:record|tape|save|set a recording (?:of|for)|καταγραψε|ηχογραψε|γραψε|κανε εγγραφη(?: για| του| της| το)?) (?:the |a |an |το |τα |τη |την |τον |τις |τους )?(.+)$");
    private static final Pattern WATCH_RE = Pattern.compile(
            "^(?:watch|play|put on|tune (?:in )?to|switch to|go to|open|show me|δες|βαλε|ανοιξε|παρε|παμε(?: στο| στον| στην)?|θελω να δω) (?:the |channel |το |τον |την |κανάλι |καναλι )?(.+)$");

    /** Interprets a phrase. {@code type} is SEARCH (with the phrase as {@code arg}) when it is no command. */
    static VoiceCommand parse(String said) {
        VoiceCommand c = new VoiceCommand();
        String s = Tvh.fold(said).replaceAll("[.!?,;]+", " ").replaceAll("\\s+", " ").trim();
        c.arg = said == null ? "" : said.trim();
        s = s.replaceFirst(POLITE, "");
        Matcher m = WHAT_ON_RE.matcher(s);
        if (m.matches()) return c.set(WHAT_ON, m.group(1));
        m = RECORD_RE.matcher(s);
        if (m.matches()) return c.set(RECORD, m.group(1));
        m = WATCH_RE.matcher(Tvh.fold(s));
        if (m.matches()) return c.set(WATCH, m.group(1));
        return c;
    }

    private VoiceCommand set(int type, String arg) {
        this.type = type;
        this.arg = numbers(arg.trim());
        return this;
    }

    /** "ert one" -> "ert 1": spoken small numbers become digits, so they match channel numbers and names. */
    static String numbers(String s) {
        String[][] words = {{"one", "1"}, {"two", "2"}, {"three", "3"}, {"four", "4"}, {"five", "5"}, {"six", "6"}, {"seven", "7"},
                {"eight", "8"}, {"nine", "9"}, {"ten", "10"},
                {"ενα", "1"}, {"εναν", "1"}, {"μια", "1"}, {"δυο", "2"}, {"δυο", "2"}, {"τρια", "3"}, {"τρεις", "3"}, {"τεσσερα", "4"},
                {"πεντε", "5"}, {"εξι", "6"}, {"επτα", "7"}, {"εφτα", "7"}, {"οκτω", "8"}, {"οχτω", "8"}, {"εννια", "9"}, {"δεκα", "10"}};
        StringBuilder out = new StringBuilder();
        for (String tok : s.split(" ")) {
            String f = Tvh.fold(tok);
            String rep = tok;
            for (String[] w : words) if (w[0].equals(f)) { rep = w[1]; break; }
            if (out.length() > 0) out.append(' ');
            out.append(rep);
        }
        return out.toString();
    }
}
