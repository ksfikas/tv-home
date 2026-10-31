package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** The glow colour picked from a picture's pixels. */
public class AccentTest {
    private static final int FALLBACK = 0xFF3A3A3A;

    private static int[] fill(int argb, int n) {
        int[] px = new int[n];
        Arrays.fill(px, argb);
        return px;
    }

    private static int[] mix(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static int red(int c) { return (c >> 16) & 255; }
    private static int green(int c) { return (c >> 8) & 255; }
    private static int blue(int c) { return c & 255; }

    @Test
    public void aRedPictureGivesRed() {
        int c = Accent.dominant(fill(0xFFC02020, 576), FALLBACK);
        assertTrue(red(c) > 180 && green(c) < 120 && blue(c) < 120);
        assertEquals("opaque", 0xFF, c >>> 24);
    }

    @Test
    public void aBlueSkyBeatsAGreyForeground() {
        int c = Accent.dominant(mix(fill(0xFF3060C0, 200), fill(0xFF777777, 376)), FALLBACK);
        assertTrue(blue(c) > red(c) && blue(c) > green(c));
    }

    @Test
    public void theMoreVividColourWinsOverAMutedOne() {
        int c = Accent.dominant(mix(fill(0xFFB06060, 300), fill(0xFF10E010, 120)), FALLBACK);
        assertTrue("vivid green over a larger muted red", green(c) > red(c));
    }

    @Test
    public void greysBlacksAndTransparentGiveTheFallback() {
        assertEquals(FALLBACK, Accent.dominant(fill(0xFF808080, 576), FALLBACK));
        assertEquals(FALLBACK, Accent.dominant(fill(0xFF050505, 576), FALLBACK));
        assertEquals(FALLBACK, Accent.dominant(fill(0x00FF0000, 576), FALLBACK));
        assertEquals(FALLBACK, Accent.dominant(new int[0], FALLBACK));
    }

    @Test
    public void aDarkColourIsLiftedSoItShowsAsAGlow() {
        int c = Accent.dominant(fill(0xFF300808, 576), FALLBACK);   // dark red
        assertTrue("brightened", red(c) >= 150);
    }
}
