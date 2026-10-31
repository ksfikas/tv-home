package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.kxml2.io.KXmlParser;
import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.List;

/** Reading a news feed: titles, links, photos from every place feeds put them, and the limits. */
public class ShelfFeedTest {

    private static List<Shelf.Item> parse(String xml, int max) throws Exception {
        XmlPullParser p = new KXmlParser();
        p.setInput(new StringReader(xml));
        return Shelf.parseRss(TestTxt.english(), p, "Source", max, s -> s.trim());
    }

    private static String feed(String items) {
        return "<?xml version=\"1.0\"?><rss xmlns:media=\"http://search.yahoo.com/mrss/\"><channel><title>Feed</title>" + items + "</channel></rss>";
    }

    @Test
    public void titleLinkAndSourceAreRead() throws Exception {
        List<Shelf.Item> r = parse(feed("<item><title>Headline</title><link>https://x/1</link></item>"), 8);
        assertEquals(1, r.size());
        assertEquals("Headline", r.get(0).title);
        assertEquals("https://x/1", r.get(0).link);
        assertEquals("news", r.get(0).kind);
        assertEquals("Source", r.get(0).sub);   // no date: just the source
    }

    @Test
    public void theChannelTitleIsNotAStory() throws Exception {
        assertTrue(parse(feed(""), 8).isEmpty());
    }

    @Test
    public void photoComesFromEnclosureMediaOrTheTextInThatOrder() throws Exception {
        List<Shelf.Item> r = parse(feed(
                "<item><title>A</title><enclosure url=\"https://x/a.jpg\" type=\"image/jpeg\"/></item>"
              + "<item><title>B</title><media:content url=\"https://x/b.jpg\"/></item>"
              + "<item><title>C</title><media:thumbnail url=\"https://x/c.jpg\"/></item>"
              + "<item><title>D</title><description>&lt;img src=\"https://x/d.jpg\"&gt; text</description></item>"
              + "<item><title>E</title><enclosure url=\"https://x/e.mp3\" type=\"audio/mpeg\"/></item>"), 8);
        assertEquals("https://x/a.jpg", r.get(0).artUrl);
        assertEquals("https://x/b.jpg", r.get(1).artUrl);
        assertEquals("https://x/c.jpg", r.get(2).artUrl);
        assertEquals("https://x/d.jpg", r.get(3).artUrl);
        assertNull("an audio enclosure is not a photo", r.get(4).artUrl);
    }

    @Test
    public void anEnclosurePhotoWinsOverOneInTheText() throws Exception {
        List<Shelf.Item> r = parse(feed("<item><title>A</title><enclosure url=\"https://x/a.jpg\" type=\"image/jpeg\"/>"
                + "<description>&lt;img src=\"https://x/z.jpg\"&gt;</description></item>"), 8);
        assertEquals("https://x/a.jpg", r.get(0).artUrl);
    }

    @Test
    public void itemsWithoutATitleAreSkippedAndTheMaximumIsRespected() throws Exception {
        List<Shelf.Item> r = parse(feed("<item><link>https://x/0</link></item>"
                + "<item><title>1</title></item><item><title>2</title></item><item><title>3</title></item>"), 2);
        assertEquals(2, r.size());
        assertEquals("1", r.get(0).title);
        assertEquals("2", r.get(1).title);
    }

    @Test
    public void cdataAndSplitTextAreJoined() throws Exception {
        List<Shelf.Item> r = parse(feed("<item><title><![CDATA[Fish & chips]]></title>"
                + "<description>one &amp; <![CDATA[two]]></description></item>"), 8);
        assertEquals("Fish & chips", r.get(0).title);
        assertTrue(r.get(0).summary, r.get(0).summary.matches("one &[ ]?two"));   // parsers differ on joining the pieces
    }

    @Test
    public void theDateBecomesHowLongAgo() throws Exception {
        List<Shelf.Item> r = parse(feed("<item><title>Old</title><pubDate>Mon, 01 Jan 2024 10:00:00 +0000</pubDate></item>"), 8);
        assertTrue(r.get(0).sub, r.get(0).sub.startsWith("Source  ·  "));
        assertTrue("a long time ago, in days", r.get(0).sub.contains("days"));
    }

    @Test(expected = Exception.class)
    public void brokenXmlIsAnError() throws Exception {
        parse("<rss><channel><item><title>x</item>", 8);
    }

    @Test
    public void plainTextCleaningIsAppliedToTitleAndSummary() throws Exception {
        XmlPullParser p = new KXmlParser();
        p.setInput(new StringReader(feed("<item><title>t</title><description>d</description></item>")));
        List<Shelf.Item> r = Shelf.parseRss(TestTxt.english(), p, "S", 8, s -> s.toUpperCase());
        assertEquals("T", r.get(0).title);
        assertEquals("D", r.get(0).summary);
    }
}
