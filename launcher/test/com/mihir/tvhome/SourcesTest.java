package com.mihir.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/** Network tuner (HDHomeRun) protocol and lineup parsing, and merging sources into one list. */
public class SourcesTest {

    @Test
    public void discoverRequestMatchesTheHdhomerunWireFormat() {
        byte[] p = Sources.discoverRequest();
        assertEquals(20, p.length);
        // type 2 (discover request), 12 payload bytes, device type = tuner, device id = any
        assertEquals("0002000c01040000000102 04ffffffff".replace(" ", ""), hex(Arrays.copyOf(p, 16)));
        CRC32 crc = new CRC32();
        crc.update(p, 0, 16);
        assertEquals((int) crc.getValue(), ByteBuffer.wrap(p, 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    @Test
    public void discoverReplyGivesTheBaseUrl() {
        byte[] reply = reply("http://192.168.178.50");
        assertEquals("http://192.168.178.50", Sources.parseDiscoverReply(reply));
    }

    @Test
    public void corruptOrForeignPacketsAreIgnored() {
        byte[] reply = reply("http://10.0.0.2");
        reply[6] ^= 1; // payload changed, CRC no longer matches
        assertNull(Sources.parseDiscoverReply(reply));
        assertNull(Sources.parseDiscoverReply(Sources.discoverRequest())); // a request, not a reply
        assertNull(Sources.parseDiscoverReply(new byte[3]));
    }

    @Test
    public void lineupBecomesChannels() throws Exception {
        String json = "[{\"GuideNumber\":\"5\",\"GuideName\":\"MEGA HD\",\"VideoCodec\":\"H264\",\"AudioCodec\":\"AAC\","
                + "\"HD\":1,\"URL\":\"http://192.168.178.50:5004/auto/v5\"},"
                + "{\"GuideNumber\":\"7.1\",\"GuideName\":\"Locked\",\"DRM\":1,\"URL\":\"http://x/auto/v7.1\"},"
                + "{\"GuideNumber\":\"700\",\"GuideName\":\"ErtNews Radio\",\"AudioCodec\":\"MPEG\",\"URL\":\"http://x/auto/v700\"}]";
        List<Tvh.Channel> chs = Sources.parseLineup(json, "1050ABCD");
        assertEquals(2, chs.size()); // the DRM channel is dropped
        Tvh.Channel mega = chs.get(0);
        assertEquals("hdhr:1050ABCD:5", mega.uuid);
        assertEquals("MEGA HD", mega.name);
        assertEquals(5.0, mega.number, 0);
        assertEquals("http://192.168.178.50:5004/auto/v5", mega.url);
        assertFalse(mega.radio);
        assertFalse(mega.fromTvh());
        assertTrue(chs.get(1).radio);
    }

    @Test
    public void tvheadendChannelsWinWhenNamesMatch() {
        List<Tvh.Channel> merged = Sources.merge(Arrays.asList(ch("ERT 1 HD", 1, null)),
                Arrays.asList(ch("ERT1", 21, "http://t/1"), ch("Local News", 30, "http://t/2"), ch("Alpha", 0, "http://t/3")));
        assertEquals(3, merged.size());
        assertTrue(merged.get(0).fromTvh());                 // Tvheadend's ERT 1, not the tuner's copy
        assertEquals("Local News", merged.get(1).name);      // then the new ones, numbered first
        assertEquals("Alpha", merged.get(2).name);
    }

    @Test
    public void addressesAndNameKeys() {
        assertEquals("http://192.168.1.5", Sources.baseUrl("192.168.1.5/"));
        assertEquals("http://pc:8080", Sources.baseUrl(" http://pc:8080 "));
        assertEquals(Sources.key("ERT 1 HD"), Sources.key("ert1"));
        assertFalse(Sources.key("ERT 2").equals(Sources.key("ERT 1")));
    }

    private static Tvh.Channel ch(String name, double number, String url) {
        Tvh.Channel c = new Tvh.Channel();
        c.uuid = name;
        c.name = name;
        c.number = number;
        c.url = url;
        return c;
    }

    /** A discover reply as a tuner sends it: device type, device id, base URL, CRC. */
    private static byte[] reply(String base) {
        byte[] url = base.getBytes(StandardCharsets.UTF_8);
        int len = 6 + 6 + 2 + url.length;
        ByteBuffer b = ByteBuffer.allocate(4 + len + 4);
        b.putShort((short) 3).putShort((short) len);
        b.put((byte) 0x01).put((byte) 4).putInt(1);
        b.put((byte) 0x02).put((byte) 4).putInt(0x1050ABCD);
        b.put((byte) 0x2A).put((byte) url.length).put(url);
        CRC32 crc = new CRC32();
        crc.update(b.array(), 0, 4 + len);
        b.order(ByteOrder.LITTLE_ENDIAN).putInt((int) crc.getValue());
        return b.array();
    }

    private static String hex(byte[] d) {
        StringBuilder s = new StringBuilder();
        for (byte x : d) s.append(String.format("%02x", x));
        return s.toString();
    }
}
