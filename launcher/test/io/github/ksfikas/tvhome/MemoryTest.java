package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Which apps get closed when freeing memory. */
public class MemoryTest {

    @Test
    public void theHomeAppAndVoiceSearchAreKeptAndDuplicatesCollapse() {
        assertEquals(Arrays.asList("com.netflix.ninja", "com.apple.atve.androidtv.appletv"),
                Memory.candidates(Arrays.asList("com.netflix.ninja", "io.github.ksfikas.tvhome", "com.google.android.katniss",
                        "com.netflix.ninja", "com.apple.atve.androidtv.appletv", null), "io.github.ksfikas.tvhome"));
    }

    @Test
    public void nothingLaunchableMeansNothingToClose() {
        assertEquals(Collections.emptyList(), Memory.candidates(Collections.emptyList(), "x"));
    }
}
