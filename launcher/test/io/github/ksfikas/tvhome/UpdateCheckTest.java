package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The "newer version available" decision: reading GitHub's answer and comparing version names. */
public class UpdateCheckTest {

    @Test
    public void versionNumbersComeOutOfTagsAndVersionNames() {
        assertArrayEquals(new int[]{3, 0, 1}, UpdateCheck.numbers("v3.0.1"));
        assertArrayEquals(new int[]{3, 0, 1}, UpdateCheck.numbers("3.0.1-tvh"));
        assertArrayEquals(new int[]{2, 2, 0}, UpdateCheck.numbers("2.2-tvh"));   // two parts: patch is 0
        assertArrayEquals(new int[]{3, 0, 0}, UpdateCheck.numbers("3"));
        assertArrayEquals(new int[]{0, 0, 0}, UpdateCheck.numbers("nightly"));
        assertArrayEquals(new int[]{0, 0, 0}, UpdateCheck.numbers(null));
    }

    @Test
    public void newerMeansHigherInTheFirstNumberThatDiffers() {
        assertTrue(UpdateCheck.isNewer("v3.0.2", "3.0.1-tvh"));
        assertTrue(UpdateCheck.isNewer("v3.1.0", "3.0.9-tvh"));
        assertTrue(UpdateCheck.isNewer("v4.0.0", "3.9.9-tvh"));
        assertTrue("2.10 is newer than 2.9, not older", UpdateCheck.isNewer("v2.10.0", "2.9.0-tvh"));
        assertFalse("the same version is not an update", UpdateCheck.isNewer("v3.0.1", "3.0.1-tvh"));
        assertFalse("an older release is not an update", UpdateCheck.isNewer("v2.2.0", "3.0.1-tvh"));
    }

    @Test
    public void theTagIsReadFromTheReleaseJson() {
        assertEquals("v3.0.2", UpdateCheck.parseTag("{\"id\":1,\"tag_name\":\"v3.0.2\",\"name\":\"v3.0.2\",\"draft\":false}"));
        assertNull(UpdateCheck.parseTag("{\"message\":\"Not Found\"}"));
        assertNull(UpdateCheck.parseTag("{\"tag_name\":\"\"}"));
        assertNull(UpdateCheck.parseTag("<html>rate limited</html>"));
        assertNull(UpdateCheck.parseTag(null));
    }
}
