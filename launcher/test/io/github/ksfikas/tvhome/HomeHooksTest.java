package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The smart-home hook addresses and when a call is worth making. */
public class HomeHooksTest {

    @Test
    public void onlyWebAddressesWithAHostAreAccepted() {
        assertTrue(HomeHooks.valid("http://192.168.1.5:8123/api/webhook/abc"));
        assertTrue(HomeHooks.valid(" https://example.com/hook "));
        assertFalse(HomeHooks.valid(""));
        assertFalse(HomeHooks.valid(null));
        assertFalse(HomeHooks.valid("ftp://example.com/x"));
        assertFalse(HomeHooks.valid("file:///etc/passwd"));
        assertFalse(HomeHooks.valid("javascript:alert(1)"));
        assertFalse(HomeHooks.valid("http://"));
    }

    @Test
    public void aRepeatOfTheSameEventIsNotSent() {
        assertTrue(HomeHooks.worthSending(HomeHooks.STOP, HomeHooks.PLAY));
        assertFalse(HomeHooks.worthSending(HomeHooks.PLAY, HomeHooks.PLAY));
        assertTrue(HomeHooks.worthSending(HomeHooks.PLAY, HomeHooks.PAUSE));
        assertTrue(HomeHooks.worthSending(HomeHooks.PAUSE, HomeHooks.STOP));
    }

    @Test
    public void eachEventHasItsOwnPreferenceKey() {
        assertEquals("hook_play", HomeHooks.key(HomeHooks.PLAY));
        assertEquals("hook_stop", HomeHooks.key(HomeHooks.STOP));
    }
}
