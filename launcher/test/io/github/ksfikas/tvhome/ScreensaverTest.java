package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    // ---------------------------------------------------------------- night mode

    @Test
    public void nightRunsFromTheChosenHourAcrossMidnightUntilSix() {
        // 23:00 to 06:00
        assertTrue(Screensaver.isNight(23, 23, 6));
        assertTrue(Screensaver.isNight(0, 23, 6));
        assertTrue(Screensaver.isNight(5, 23, 6));
        assertFalse(Screensaver.isNight(6, 23, 6));
        assertFalse(Screensaver.isNight(12, 23, 6));
        assertFalse(Screensaver.isNight(22, 23, 6));
        // starting at midnight itself
        assertTrue(Screensaver.isNight(0, 0, 6));
        assertTrue(Screensaver.isNight(5, 0, 6));
        assertFalse(Screensaver.isNight(23, 0, 6));
        // an evening start
        assertTrue(Screensaver.isNight(21, 21, 6));
        assertFalse(Screensaver.isNight(20, 21, 6));
    }

    @Test
    public void nightOffNeverDims() {
        for (int h = 0; h < 24; h++) assertFalse(Screensaver.isNight(h, -1, 6));
    }

    // ---------------------------------------------------------------- coming up

    private static Tvh.Recording rec(String title, String status, long start) {
        Tvh.Recording r = new Tvh.Recording();
        r.title = title; r.channel = "ERT 1"; r.schedStatus = status; r.start = start;
        return r;
    }

    private static Reminders.Reminder remind(String title, long start) {
        Reminders.Reminder r = new Reminders.Reminder();
        r.title = title; r.channelName = "MEGA"; r.start = start;
        return r;
    }

    @Test
    public void comingUpMergesFutureRecordingsAndRemindersSoonestFirst() {
        List<Tvh.Recording> recs = new ArrayList<>();
        recs.add(rec("Late film", "scheduled", NOW + 7200));
        recs.add(rec("Running now", "recording", NOW - 600));    // not coming up
        recs.add(rec("Finished", "completed", NOW - 86400));     // not coming up
        recs.add(rec("Soon show", "scheduled", NOW + 600));
        List<Reminders.Reminder> reminders = new ArrayList<>();
        reminders.add(remind("News", NOW + 3600));
        reminders.add(remind("Already started", NOW - 5));        // not coming up

        List<Screensaver.Coming> out = Screensaver.pickComing(recs, reminders, NOW, 5);
        assertEquals(3, out.size());
        assertEquals("Soon show", out.get(0).title);
        assertTrue(out.get(0).recording);
        assertEquals("News", out.get(1).title);
        assertFalse("a reminder is not a recording", out.get(1).recording);
        assertEquals("Late film", out.get(2).title);
    }

    @Test
    public void aReminderAndARecordingForTheSameProgrammeShowOnce() {
        List<Tvh.Recording> recs = new ArrayList<>();
        recs.add(rec("Film", "scheduled", NOW + 600));
        List<Reminders.Reminder> reminders = new ArrayList<>();
        reminders.add(remind("Film", NOW + 600));
        List<Screensaver.Coming> out = Screensaver.pickComing(recs, reminders, NOW, 5);
        assertEquals(1, out.size());
        assertTrue("the recording wins", out.get(0).recording);
    }

    @Test
    public void comingUpIsCappedAndToleratesMissingData() {
        List<Reminders.Reminder> many = new ArrayList<>();
        for (int i = 1; i <= 9; i++) many.add(remind("Show " + i, NOW + i * 60L));
        assertEquals(5, Screensaver.pickComing(null, many, NOW, 5).size());
        assertTrue(Screensaver.pickComing(null, null, NOW, 5).isEmpty());
    }
}
