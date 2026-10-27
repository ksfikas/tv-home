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
