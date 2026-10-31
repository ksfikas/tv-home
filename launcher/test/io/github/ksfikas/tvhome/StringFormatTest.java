package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Format mistakes in a string resource only show up on the TV, as a crash or as a stray "%%" on screen.
 * These tests format every string and plural with sample arguments, in both languages.
 */
public class StringFormatTest {

    private static final TestTxt[] LANGS = {TestTxt.only("res/values"), TestTxt.only("res/values-el")};
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+)\\$([sd])");
    private static final Pattern ANY_PERCENT = Pattern.compile("%%|%(\\d+\\$)?[sdf]");

    /** One sample value per argument position: strings for %s, numbers for %d. */
    private static Object[] samples(String text) {
        int highest = 0;
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) highest = Math.max(highest, Integer.parseInt(m.group(1)));
        Object[] args = new Object[highest];
        for (int i = 0; i < highest; i++) args[i] = "x";
        m = PLACEHOLDER.matcher(text);
        while (m.find()) if (m.group(2).equals("d")) args[Integer.parseInt(m.group(1)) - 1] = 7;
        return args;
    }

    @Test
    public void everyStringFormatsWithItsOwnPlaceholders() {
        List<String> bad = new ArrayList<>();
        for (TestTxt t : LANGS) {
            for (Map.Entry<String, String> e : t.strings.entrySet()) {
                try { String.format(e.getValue(), samples(e.getValue())); }
                catch (IllegalFormatException ex) { bad.add(e.getKey() + " (" + ex.getClass().getSimpleName() + ")"); }
            }
            for (Map.Entry<String, Map<String, String>> p : t.plurals.entrySet()) {
                for (Map.Entry<String, String> item : p.getValue().entrySet()) {
                    try { String.format(item.getValue(), samples(item.getValue())); }
                    catch (IllegalFormatException ex) { bad.add(p.getKey() + "#" + item.getKey() + " (" + ex.getClass().getSimpleName() + ")"); }
                }
            }
        }
        assertTrue("Strings that fail to format: " + bad, bad.isEmpty());
    }

    @Test
    public void aStringWithoutPlaceholdersDoesNotEscapeAPercentSign() {
        // getString(id) without arguments does not run the format, so "%%" would reach the screen as two signs.
        // Strings that are only ever fetched with arguments (they have a placeholder) may use "%%".
        List<String> bad = new ArrayList<>();
        for (TestTxt t : LANGS) {
            for (Map.Entry<String, String> e : t.strings.entrySet()) {
                String v = e.getValue();
                if (v.contains("%%") && !PLACEHOLDER.matcher(v).find()) bad.add(e.getKey());
            }
        }
        assertTrue("Strings with an escaped percent but nothing to format: " + bad, bad.isEmpty());
    }

    @Test
    public void noStrayPercentSignsRemainAfterFormatting() {
        List<String> bad = new ArrayList<>();
        for (TestTxt t : LANGS) {
            for (Map.Entry<String, String> e : t.strings.entrySet()) {
                String v = e.getValue();
                if (!PLACEHOLDER.matcher(v).find()) continue; // not formatted
                String out = String.format(v, samples(v));
                if (out.contains("%")) {
                    // a literal percent from "%%" is fine; anything else means a malformed placeholder survived
                    String stripped = ANY_PERCENT.matcher(v).replaceAll("");
                    if (stripped.contains("%")) bad.add(e.getKey());
                }
            }
        }
        assertTrue("Malformed placeholders: " + bad, bad.isEmpty());
    }

    @Test
    public void placeholdersUseNumberedFormsSoWordOrderCanChange() {
        // "%s" and "%d" without a position can't be reordered in a translation; the project uses %1$s style.
        List<String> bad = new ArrayList<>();
        Pattern unnumbered = Pattern.compile("%[sd]");
        for (TestTxt t : LANGS) {
            for (Map.Entry<String, String> e : t.strings.entrySet()) if (unnumbered.matcher(e.getValue()).find()) bad.add(e.getKey());
            for (Map.Entry<String, Map<String, String>> p : t.plurals.entrySet())
                for (String v : p.getValue().values()) if (unnumbered.matcher(v).find()) bad.add(p.getKey());
        }
        assertTrue("Unnumbered placeholders: " + bad, bad.isEmpty());
    }

    @Test
    public void noStringIsEmptyOrJustWhitespace() {
        List<String> bad = new ArrayList<>();
        for (TestTxt t : LANGS) {
            for (Map.Entry<String, String> e : t.strings.entrySet()) if (e.getValue().trim().isEmpty()) bad.add(e.getKey());
        }
        assertTrue("Empty strings: " + bad, bad.isEmpty());
        assertFalse(LANGS[0].strings.isEmpty());
    }
}
