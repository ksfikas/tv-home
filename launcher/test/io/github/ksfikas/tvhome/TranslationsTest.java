package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The English and Greek string files must describe the same strings, with the same placeholders. */
public class TranslationsTest {

    private static final TestTxt EN = TestTxt.only("res/values"), EL = TestTxt.only("res/values-el");
    private static final Pattern FORMAT = Pattern.compile("%(\\d+\\$)?[sdf]");

    private static List<String> formats(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = FORMAT.matcher(s);
        while (m.find()) out.add(m.group());
        out.sort(String::compareTo);
        return out;
    }

    @Test
    public void everyEnglishStringHasAGreekOne() {
        TreeSet<String> missing = new TreeSet<>(EN.strings.keySet());
        missing.removeAll(EL.strings.keySet());
        assertTrue("Not translated to Greek: " + missing, missing.isEmpty());
        TreeSet<String> extra = new TreeSet<>(EL.strings.keySet());
        extra.removeAll(EN.strings.keySet());
        assertTrue("Greek strings with no English original: " + extra, extra.isEmpty());
    }

    @Test
    public void everyEnglishPluralHasAGreekOne() {
        TreeSet<String> missing = new TreeSet<>(EN.plurals.keySet());
        missing.removeAll(EL.plurals.keySet());
        assertTrue("Plurals not translated to Greek: " + missing, missing.isEmpty());
        for (Map.Entry<String, Map<String, String>> e : EL.plurals.entrySet()) {
            assertTrue("Greek plural " + e.getKey() + " needs 'one' and 'other'",
                    e.getValue().containsKey("one") && e.getValue().containsKey("other"));
        }
    }

    @Test
    public void greekKeepsTheSamePlaceholders() {
        for (Map.Entry<String, String> e : EN.strings.entrySet()) {
            String greek = EL.strings.get(e.getKey());
            if (greek == null) continue; // reported by the test above
            assertEquals("Placeholders differ in " + e.getKey(), formats(e.getValue()), formats(greek));
        }
        for (Map.Entry<String, Map<String, String>> e : EN.plurals.entrySet()) {
            Map<String, String> greek = EL.plurals.get(e.getKey());
            if (greek == null) continue;
            for (String quantity : greek.keySet()) {
                // Any English quantity will do as the reference: the arguments are the same in each.
                String english = e.getValue().containsKey(quantity) ? e.getValue().get(quantity) : e.getValue().get("other");
                assertEquals("Placeholders differ in plural " + e.getKey() + " (" + quantity + ")", formats(english), formats(greek.get(quantity)));
            }
        }
    }
}
