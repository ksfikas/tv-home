package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The server status text and the channel manager's list, from payloads shaped like Tvheadend 4.3's. */
public class ServerStatusTest {

    private static final Txt EN = TestTxt.english(), EL = TestTxt.greek();
    private static final java.util.function.LongFunction<String> SIZE = n -> (n >> 30) + " GB";

    private static final String IDLE = "{\"entries\":[{\"uuid\":\"9d34\",\"input\":\"Sony CXD2880 #0 : DVB-T #0\",\"subs\":0,\"weight\":0,"
            + "\"signal\":0,\"signal_scale\":0,\"ber\":0,\"snr\":0,\"snr_scale\":0,\"unc\":0,\"bps\":0,\"cc\":0}],\"totalCount\":1}";
    private static final String BUSY = "{\"entries\":[{\"uuid\":\"9d34\",\"input\":\"Sony CXD2880 #0 : DVB-T #0\",\"subs\":2,\"weight\":150,"
            + "\"signal\":52428,\"signal_scale\":1,\"ber\":0,\"snr\":28600,\"snr_scale\":2,\"unc\":3,\"bps\":6500000,\"cc\":0}],\"totalCount\":1}";
    private static final String NO_SUBS = "{\"entries\":[],\"totalCount\":0}";
    private static final String SUBS = "{\"entries\":[{\"id\":7,\"channel\":\"ERT 1 HD\",\"username\":\"kostas\","
            + "\"hostname\":\"192.168.178.130\",\"client\":\"tvhome\",\"state\":\"Running\",\"in\":812345},"
            + "{\"id\":8,\"service\":\"Sony/DVB-T/ERT2\",\"hostname\":\"192.168.178.20\"}],\"totalCount\":2}";
    private static final long GB = 1L << 30;

    private static ServerStatus parse(String inputs, String subs, List<Tvh.Recording> recs, long[] disk) throws Exception {
        return ServerStatus.parse(new JSONObject(inputs), new JSONObject(subs), recs, disk, "4.3-2471");
    }

    @Test
    public void anIdleServerSaysSo() throws Exception {
        String text = parse(IDLE, NO_SUBS, new ArrayList<>(), new long[]{26 * GB, 31 * GB}).render(EN, SIZE);
        assertTrue(text, text.contains("Tvheadend 4.3-2471"));
        assertTrue(text, text.contains("Tuner: Sony CXD2880 · DVB-T   idle"));
        assertFalse("no signal line while idle", text.contains("Signal"));
        assertTrue(text, text.contains("Nobody is watching"));
        assertTrue(text, text.contains("Nothing is recording"));
        assertTrue(text, text.contains("Recordings disk: 26 GB free of 31 GB"));
    }

    @Test
    public void aBusyTunerShowsSignalWatchersAndRecordings() throws Exception {
        Tvh.Recording r = new Tvh.Recording();
        r.title = "Film"; r.channel = "ERT 3 HD"; r.schedStatus = "recording";
        Tvh.Recording done = new Tvh.Recording();
        done.title = "Old"; done.channel = "MEGA"; done.schedStatus = "completed";
        List<Tvh.Recording> recs = new ArrayList<>();
        recs.add(r);
        recs.add(done);
        String text = parse(BUSY, SUBS, recs, null).render(EN, SIZE);
        assertTrue(text, text.contains("in use (2 streams)"));
        assertTrue(text, text.contains("Signal 80 %  ·  SNR 28.6 dB  ·  Uncorrected 3   ·   6.5 Mbit/s"));
        assertTrue(text, text.contains("ERT 1 HD  ·  kostas@192.168.178.130"));
        assertTrue("falls back to the service name and just the host", text.contains("Sony/DVB-T/ERT2  ·  192.168.178.20"));
        assertTrue(text, text.contains("Film  ·  ERT 3 HD"));
        assertFalse("a finished recording is not 'recording now'", text.contains("Old"));
        assertFalse("no disk line when the server doesn't report one", text.contains("Recordings disk"));
    }

    @Test
    public void greekHasTheSameInformation() throws Exception {
        String text = parse(BUSY, SUBS, new ArrayList<>(), new long[]{GB, 31 * GB}).render(EL, SIZE);
        assertTrue(text, text.contains("Δέκτης: Sony CXD2880 · DVB-T   σε χρήση (2 ροές)"));
        assertTrue(text, text.contains("Σήμα 80 %  ·  SNR 28.6 dB  ·  Μη διορθωμένα 3"));
        assertTrue(text, text.contains("Παρακολουθούν τώρα"));
        assertTrue(text, text.contains("Δίσκος εγγραφών: 1 GB ελεύθερα από 31 GB"));
    }

    @Test
    public void missingPagesDoNotCrash() throws Exception {
        // A non-admin user or an old server: the parts that are there still show.
        ServerStatus s = ServerStatus.parse(null, null, null, null, null);
        String text = s.render(EN, SIZE);
        assertTrue(text, text.contains("Nobody is watching"));
        assertTrue(s.tuners.isEmpty());
    }

    @Test
    public void channelRowsAreSortedByNumberWithUnnumberedLastAndHiddenOnesKept() throws Exception {
        String grid = "{\"entries\":["
                + "{\"uuid\":\"c\",\"enabled\":true,\"name\":\"Zeta\",\"number\":0},"
                + "{\"uuid\":\"b\",\"enabled\":false,\"name\":\"B channel\",\"number\":12},"
                + "{\"uuid\":\"a\",\"enabled\":true,\"name\":\"A channel\",\"number\":3},"
                + "{\"uuid\":\"d\",\"enabled\":true,\"name\":\"alpha\",\"number\":0},"
                + "{\"name\":\"no uuid so it is skipped\",\"number\":1}],\"total\":5}";
        List<Tvh.ChannelRow> rows = Tvh.parseChannelRows(new JSONObject(grid));
        assertEquals(4, rows.size());
        assertEquals("a", rows.get(0).uuid);
        assertEquals("b", rows.get(1).uuid);
        assertFalse("disabled channels stay in the list so they can be turned back on", rows.get(1).enabled);
        assertEquals("d", rows.get(2).uuid); // unnumbered, by name: alpha before Zeta
        assertEquals("c", rows.get(3).uuid);
    }

    @Test
    public void anEmptyOrBrokenGridGivesAnEmptyList() throws Exception {
        assertTrue(Tvh.parseChannelRows(new JSONObject("{}")).isEmpty());
        assertTrue(Tvh.parseChannelRows(null).isEmpty());
    }
}
