package com.mihir.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Small helpers behind the banner, Box status and the weather line. */
public class HelpersTest {

    private static final long GB = 1L << 30;

    @Test
    public void durations() {
        assertEquals("0:00", Ui.duration(0));
        assertEquals("0:00", Ui.duration(-5000));
        assertEquals("1:01", Ui.duration(61_000));
        assertEquals("59:59", Ui.duration(3_599_000));
        assertEquals("1:02:03", Ui.duration(3_723_000));
    }

    @Test
    public void diskWarningOnlyWhenLow() {
        assertNull(BoxHealth.diskWarning(null));
        assertNull(BoxHealth.diskWarning(new long[]{0, 0}));
        assertNull(BoxHealth.diskWarning(new long[]{26 * GB, 31 * GB}));        // this server today
        assertEquals("Recordings disk 97% full", BoxHealth.diskWarning(new long[]{1 * GB, 31 * GB}));
        assertEquals("Recordings disk 80% full", BoxHealth.diskWarning(new long[]{2 * GB, 10 * GB})); // under 3 GB left
        assertNull(BoxHealth.diskWarning(new long[]{50 * GB, 400 * GB}));       // 12.5% and plenty of GB
    }

    @Test
    public void lastWatchedWording() {
        assertEquals("just now", Watched.ago(30_000));
        assertEquals("20 min ago", Watched.ago(20 * 60_000));
        assertEquals("1 hour ago", Watched.ago(61 * 60_000));
        assertEquals("5 hours ago", Watched.ago(5 * 3600_000L));
        assertEquals("yesterday", Watched.ago(30 * 3600_000L));
        assertEquals("3 days ago", Watched.ago(3 * 24 * 3600_000L));
        assertEquals("1 min left", Watched.left(10_000));
        assertEquals("12 min left", Watched.left(12 * 60_000));
        assertEquals("1 h 5 min left", Watched.left(65 * 60_000));
    }

    @Test
    public void uvAndAirQualityBands() {
        assertEquals("1 low", Weather.uv(1.2));
        assertEquals("5 moderate", Weather.uv(4.6));
        assertEquals("7 high", Weather.uv(7));
        assertEquals("9 very high", Weather.uv(9.4));
        assertEquals("11 extreme", Weather.uv(11));
        assertEquals("good", Weather.airQuality(15));
        assertEquals("fair", Weather.airQuality(40));
        assertEquals("moderate", Weather.airQuality(55));
        assertEquals("poor", Weather.airQuality(75));
        assertEquals("very poor", Weather.airQuality(95));
        assertEquals("extremely poor", Weather.airQuality(140));
    }

    @Test
    public void weatherCodesInWords() {
        assertEquals("Sunny", Weather.describe(0, true));
        assertEquals("Clear", Weather.describe(0, false));
        assertEquals("Partly cloudy", Weather.describe(2, true));
        assertEquals("Cloudy", Weather.describe(3, true));
        assertEquals("Fog", Weather.describe(45, true));
        assertEquals("Rain", Weather.describe(63, true));
        assertEquals("Snow", Weather.describe(73, true));
        assertEquals("Showers", Weather.describe(81, true));
        assertEquals("Thunderstorms", Weather.describe(95, true));
    }
}
