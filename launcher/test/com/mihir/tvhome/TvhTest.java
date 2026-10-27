package com.mihir.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** The Tvheadend client's pure parts: login hashes, playlist and EPG parsing, signal figures. */
public class TvhTest {

    @Test
    public void digestMd5MatchesRfc2617Example() {
        assertEquals("6629fae49393a05397450978507c4ef1", Tvh.digestResponse("MD5", "Mufasa", "testrealm@host.com",
                "Circle Of Life", "GET", "/dir/index.html", "dcd98b7102dd2f0e8b11d0f600bfb0c093", "00000001", "0a4f113b"));
    }

    @Test
    public void digestSha256MatchesRfc7616Example() {
        assertEquals("753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1",
                Tvh.digestResponse("SHA-256", "Mufasa", "http-auth@example.org", "Circle of Life", "GET", "/dir/index.html",
                        "7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v", "00000001", "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ"));
    }

    @Test
    public void digestWithoutQopHashesNonceAndUriOnly() {
        // HA1 = md5("u:r:p"), HA2 = md5("GET:/x"), response = md5(HA1:n:HA2); stable across runs.
        String a = Tvh.digestResponse("MD5", "u", "r", "p", "GET", "/x", "n", null, null);
        assertEquals(32, a.length());
        assertEquals(a, Tvh.digestResponse("MD5", "u", "r", "p", "GET", "/x", "n", null, null));
        assertFalse(a.equals(Tvh.digestResponse("MD5", "u", "r", "p", "GET", "/y", "n", null, null)));
    }

    @Test
    public void firstStreamTakesTheTicketLinkAndForcesPassProfile() {
        String m3u = "#EXTM3U\n#EXTINF:-1,ERT 1 HD\nhttp://tvh:9981/stream/channelid/42?ticket=ABC\r\n";
        assertEquals("http://tvh:9981/stream/channelid/42?ticket=ABC&profile=pass", Tvh.firstStream(m3u));
        assertEquals("http://tvh/s?profile=matroska", Tvh.firstStream("#EXTM3U\nhttp://tvh/s?profile=matroska\n"));
        assertEquals("http://tvh/s?profile=pass", Tvh.firstStream("http://tvh/s"));
        assertNull(Tvh.firstStream("#EXTM3U\n#EXTINF:-1,nothing\n"));
    }

    @Test
    public void eventParsesEpgRowWithRecordingState() throws Exception {
        Tvh.Event e = Tvh.event(new JSONObject("{\"eventId\":47511,\"channelUuid\":\"abc\",\"title\":\"News\","
                + "\"subtitle\":\"  \",\"description\":\"\",\"dvrUuid\":\"d1\",\"dvrState\":\"scheduled\","
                + "\"start\":1000,\"stop\":2000}"));
        assertEquals(47511, e.id);
        assertEquals("abc", e.channelUuid);
        assertEquals("News", e.title);
        assertNull(e.subtitle);       // blank subtitles are dropped
        assertNull(e.description);
        assertTrue(e.recordingSet());
        assertEquals(0.5f, e.progress(1500), 1e-6);
        assertEquals(0f, e.progress(500), 0);
        assertEquals(1f, e.progress(5000), 0);
        assertTrue(e.isOn(1000));
        assertFalse(e.isOn(2000));
    }

    @Test
    public void recordingSetOnlyWhileScheduledOrRecording() throws Exception {
        assertTrue(Tvh.event(new JSONObject("{\"dvrUuid\":\"d\",\"dvrState\":\"recording\"}")).recordingSet());
        assertFalse(Tvh.event(new JSONObject("{\"dvrUuid\":\"d\",\"dvrState\":\"completed\"}")).recordingSet());
        assertFalse(Tvh.event(new JSONObject("{}")).recordingSet());
    }

    @Test
    public void recordingStates() {
        Tvh.Recording r = new Tvh.Recording();
        r.schedStatus = "completed";
        r.size = 10;
        assertTrue(r.playable());
        r.size = 0;
        assertFalse(r.playable());
        r.schedStatus = "scheduled";
        assertTrue(r.scheduled());
        r.schedStatus = "completedError";
        assertTrue(r.failed());
    }

    @Test
    public void signalLevelsFollowTvheadendScales() {
        assertEquals("100 %", Tvh.level(65535, 1, "dBm"));
        assertEquals("50 %", Tvh.level(32768, 1, "dBm"));
        assertEquals("-47.7 dBm", Tvh.level(-47700, 2, "dBm"));
        assertEquals("28.6 dB", Tvh.level(28600, 2, "dB"));
        assertNull(Tvh.level(0, 0, "dB"));
    }

    @Test
    public void iconsWithoutAPiconPathAreSkipped() {
        assertNull(Tvh.usableIcon(""));
        assertNull(Tvh.usableIcon("/1_0_2_15_2_212C_EEEE0000_0_0_0.png"));
        assertEquals("imagecache/12", Tvh.usableIcon("imagecache/12"));
        assertEquals("picon/1_0_1_4_1_212C_EEEE0000_0_0_0.png", Tvh.usableIcon("picon/1_0_1_4_1_212C_EEEE0000_0_0_0.png"));
        assertEquals("https://example.org/ert1.png", Tvh.usableIcon("https://example.org/ert1.png"));
    }

    @Test
    public void channelNumberLabels() {
        Tvh.Channel c = new Tvh.Channel();
        c.number = 5;
        assertEquals("5", c.numberLabel());
        c.number = 5.1;
        assertEquals("5.1", c.numberLabel());
        c.number = 0;
        assertEquals("", c.numberLabel());
    }
}
