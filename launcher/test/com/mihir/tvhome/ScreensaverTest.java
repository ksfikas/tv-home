package com.mihir.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The pure parts of the screensaver: which programmes the "On TV" slide shows, and the ticker line. */
public class ScreensaverTest {

    private static final long NOW = 1_000_000;

    private static Tvh.Channel channel(String uuid, String name, boolean radio) {
        Tvh.Channel c = new Tvh.Channel();
        c.uuid = uuid;
        c.name = name;
        c.radio = radio;
        return c;
    }

    private static Tvh.Event event(String channelUuid, String title, long start, long stop) {
        Tvh.Event e = new Tvh.Event();
        e.channelUuid = channelUuid;
        e.title = title;
        e.start = start;
        e.stop = stop;
        return e;
    }

    @Test
    public void favouritesComeFirstThenTheRestInOrder() {
        List<Tvh.Channel> channels = Arrays.asList(channel("a", "ERT1", false), channel("b", "Mega", false), channel("c", "Skai", false));
        Map<String, List<Tvh.Event>> window = new HashMap<>();
        for (String u : new String[]{"a", "b", "c"}) window.put(u, Collections.singletonList(event(u, "Show " + u, NOW - 600, NOW + 600)));

        List<Screensaver.Row> rows = Screensaver.pick(channels, Collections.singletonList("c"), window, NOW, 2);
        assertEquals(2, rows.size());
        assertEquals("Skai", rows.get(0).channel);
        assertEquals("ERT1", rows.get(1).channel);
        assertEquals(NOW + 600, rows.get(0).stop);
    }

    @Test
    public void skipsRadioChannelsWithoutGuideAndWithNothingOnNow() {
        List<Tvh.Channel> channels = Arrays.asList(channel("r", "Radio", true), channel("n", "NoGuide", false),
                channel("later", "Later", false), channel("ok", "OK", false));
        Map<String, List<Tvh.Event>> window = new HashMap<>();
        window.put("r", Collections.singletonList(event("r", "Music", NOW - 60, NOW + 60)));
        window.put("later", Collections.singletonList(event("later", "Tonight", NOW + 60, NOW + 600)));
        window.put("ok", Collections.singletonList(event("ok", "News", NOW - 60, NOW + 60)));

        List<Screensaver.Row> rows = Screensaver.pick(channels, new ArrayList<>(), window, NOW, 6);
        assertEquals(1, rows.size());
        assertEquals("OK", rows.get(0).channel);
    }

    @Test
    public void nextProgrammeIsTheOneAfterTheCurrent() {
        List<Tvh.Channel> channels = Collections.singletonList(channel("a", "ERT1", false));
        Map<String, List<Tvh.Event>> window = new HashMap<>();
        window.put("a", Arrays.asList(event("a", "Film", NOW - 60, NOW + 60), event("a", "News", NOW + 60, NOW + 900)));

        Screensaver.Row row = Screensaver.pick(channels, new ArrayList<>(), window, NOW, 6).get(0);
        assertEquals("Film", row.title);
        assertEquals("News", row.next);

        window.put("a", Collections.singletonList(event("a", "Film", NOW - 60, NOW + 60)));
        assertNull(Screensaver.pick(channels, new ArrayList<>(), window, NOW, 6).get(0).next);
    }

    @Test
    public void tickerJoinsHeadlinesOnOneLine() {
        List<Shelf.Item> news = new ArrayList<>();
        for (String t : new String[]{"First  story", "", "Second\nstory"}) {
            Shelf.Item it = new Shelf.Item();
            it.title = t;
            news.add(it);
        }
        String line = Screensaver.tickerText(news);
        assertTrue(line.startsWith("First story"));
        assertTrue(line.endsWith("Second story"));
        assertEquals(1, line.split("•").length - 1);
        assertEquals("", Screensaver.tickerText(new ArrayList<>()));
    }
}
