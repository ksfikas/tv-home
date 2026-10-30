package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Small helpers behind the banner, Box status and the weather line, in both languages. */
public class HelpersTest {

    private static final long GB = 1L << 30;
    private static final Txt EN = TestTxt.english(), EL = TestTxt.greek();

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
        assertNull(BoxHealth.diskWarning(EN, null));
        assertNull(BoxHealth.diskWarning(EN, new long[]{0, 0}));
        assertNull(BoxHealth.diskWarning(EN, new long[]{26 * GB, 31 * GB}));        // this server today
        assertEquals("Recordings disk 97% full", BoxHealth.diskWarning(EN, new long[]{1 * GB, 31 * GB}));
        assertEquals("Recordings disk 80% full", BoxHealth.diskWarning(EN, new long[]{2 * GB, 10 * GB})); // under 3 GB left
        assertNull(BoxHealth.diskWarning(EN, new long[]{50 * GB, 400 * GB}));       // 12.5% and plenty of GB
        assertEquals("Ο δίσκος εγγραφών είναι γεμάτος κατά 97%", BoxHealth.diskWarning(EL, new long[]{1 * GB, 31 * GB}));
    }

    @Test
    public void lastWatchedWording() {
        assertEquals("just now", Watched.ago(EN, 30_000));
        assertEquals("20 min ago", Watched.ago(EN, 20 * 60_000));
        assertEquals("1 hour ago", Watched.ago(EN, 61 * 60_000));
        assertEquals("5 hours ago", Watched.ago(EN, 5 * 3600_000L));
        assertEquals("yesterday", Watched.ago(EN, 30 * 3600_000L));
        assertEquals("3 days ago", Watched.ago(EN, 3 * 24 * 3600_000L));
        assertEquals("1 min left", Watched.left(EN, 10_000));
        assertEquals("12 min left", Watched.left(EN, 12 * 60_000));
        assertEquals("1 h 5 min left", Watched.left(EN, 65 * 60_000));
    }

    @Test
    public void lastWatchedWordingInGreekUsesTheRightPlural() {
        assertEquals("μόλις τώρα", Watched.ago(EL, 30_000));
        assertEquals("πριν από 20 λεπτά", Watched.ago(EL, 20 * 60_000));
        assertEquals("πριν από 1 ώρα", Watched.ago(EL, 61 * 60_000));
        assertEquals("πριν από 5 ώρες", Watched.ago(EL, 5 * 3600_000L));
        assertEquals("χθες", Watched.ago(EL, 30 * 3600_000L));
        assertEquals("πριν από 3 ημέρες", Watched.ago(EL, 3 * 24 * 3600_000L));
        assertEquals("απομένει 1 λεπτό", Watched.left(EL, 10_000));
        assertEquals("απομένουν 12 λεπτά", Watched.left(EL, 12 * 60_000));
        assertEquals("απομένουν 1 ώ. 5 λ.", Watched.left(EL, 65 * 60_000));
    }

    @Test
    public void uvAndAirQualityBands() {
        assertEquals("1 low", Weather.uv(EN, 1.2));
        assertEquals("5 moderate", Weather.uv(EN, 4.6));
        assertEquals("7 high", Weather.uv(EN, 7));
        assertEquals("9 very high", Weather.uv(EN, 9.4));
        assertEquals("11 extreme", Weather.uv(EN, 11));
        assertEquals("good", Weather.airQuality(EN, 15));
        assertEquals("fair", Weather.airQuality(EN, 40));
        assertEquals("moderate", Weather.airQuality(EN, 55));
        assertEquals("poor", Weather.airQuality(EN, 75));
        assertEquals("very poor", Weather.airQuality(EN, 95));
        assertEquals("extremely poor", Weather.airQuality(EN, 140));
        assertEquals("1 χαμηλός", Weather.uv(EL, 1.2));
        assertEquals("καλή", Weather.airQuality(EL, 15));
        assertEquals("εξαιρετικά κακή", Weather.airQuality(EL, 140));
    }

    @Test
    public void weatherCodesInWords() {
        assertEquals("Sunny", Weather.describe(EN, 0, true));
        assertEquals("Clear", Weather.describe(EN, 0, false));
        assertEquals("Partly cloudy", Weather.describe(EN, 2, true));
        assertEquals("Cloudy", Weather.describe(EN, 3, true));
        assertEquals("Fog", Weather.describe(EN, 45, true));
        assertEquals("Rain", Weather.describe(EN, 63, true));
        assertEquals("Snow", Weather.describe(EN, 73, true));
        assertEquals("Showers", Weather.describe(EN, 81, true));
        assertEquals("Thunderstorms", Weather.describe(EN, 95, true));
        assertEquals("Ηλιοφάνεια", Weather.describe(EL, 0, true));
        assertEquals("Αίθριος", Weather.describe(EL, 0, false));
        assertEquals("Καταιγίδες", Weather.describe(EL, 95, true));
    }
}
