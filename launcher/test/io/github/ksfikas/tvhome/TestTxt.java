package io.github.ksfikas.tvhome;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * UI text for unit tests. The plain JVM has no Android resources, so this reads the real
 * res/values (English) and res/values-el (Greek) files, which also means the tests check the actual
 * translations. Greek falls back to English for anything missing, like Android does.
 */
final class TestTxt implements Txt {
    final Map<String, String> strings = new TreeMap<>();
    final Map<String, Map<String, String>> plurals = new TreeMap<>();
    private final Map<Integer, String> stringNames = new HashMap<>();
    private final Map<Integer, String> pluralNames = new HashMap<>();
    private final Locale locale;

    static TestTxt english() { return new TestTxt(Locale.ENGLISH, "res/values"); }
    static TestTxt greek() { return new TestTxt(new Locale("el"), "res/values", "res/values-el"); }

    /** Only the files of one language, with no fallback (used to compare the two languages). */
    static TestTxt only(String dir) { return new TestTxt(Locale.ROOT, dir); }

    private TestTxt(Locale locale, String... dirs) {
        this.locale = locale;
        try {
            for (Field f : R.string.class.getFields()) stringNames.put(f.getInt(null), f.getName());
            for (Field f : R.plurals.class.getFields()) pluralNames.put(f.getInt(null), f.getName());
            for (String dir : dirs) load(new File(dir));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void load(File dir) throws Exception {
        File[] files = dir.listFiles((d, name) -> name.startsWith("strings") && name.endsWith(".xml"));
        if (files == null) return;
        for (File file : files) {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
            NodeList ss = doc.getElementsByTagName("string");
            for (int i = 0; i < ss.getLength(); i++) {
                Element e = (Element) ss.item(i);
                strings.put(e.getAttribute("name"), unescape(e.getTextContent()));
            }
            NodeList ps = doc.getElementsByTagName("plurals");
            for (int i = 0; i < ps.getLength(); i++) {
                Element e = (Element) ps.item(i);
                Map<String, String> items = new TreeMap<>();
                NodeList its = e.getElementsByTagName("item");
                for (int j = 0; j < its.getLength(); j++) {
                    Element item = (Element) its.item(j);
                    items.put(item.getAttribute("quantity"), unescape(item.getTextContent()));
                }
                plurals.put(e.getAttribute("name"), items);
            }
        }
    }

    /** Android resource escapes: a backslash before a quote, n, another backslash, @ or ?, and backslash-u with four hex digits. */
    static String unescape(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) { out.append(ch); continue; }
            char next = s.charAt(++i);
            if (next == 'n') out.append('\n');
            else if (next == 'u' && i + 4 < s.length()) { out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
            else out.append(next);
        }
        return out.toString();
    }

    @Override
    public String s(int id, Object... args) {
        String name = stringNames.get(id);
        String text = name == null ? null : strings.get(name);
        if (text == null) throw new IllegalStateException("No string for resource " + (name == null ? id : name));
        return String.format(locale, text, args);
    }

    @Override
    public String n(int id, int quantity, Object... args) {
        String name = pluralNames.get(id);
        Map<String, String> items = name == null ? null : plurals.get(name);
        if (items == null) throw new IllegalStateException("No plurals for resource " + (name == null ? id : name));
        String text = quantity == 1 && items.containsKey("one") ? items.get("one") : items.get("other"); // English and Greek: one / other
        return String.format(locale, text, args);
    }
}
