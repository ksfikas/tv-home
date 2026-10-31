package io.github.ksfikas.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** What goes into a settings backup, and what a restore writes back. */
public class BackupTest {

    @Test
    public void passwordsAndCachesStayOut() throws Exception {
        Map<String, Object> all = new HashMap<>();
        all.put("favourites", "a,b");
        all.put("dock_user", "x,y");
        all.put("ss_secs", 30);
        all.put("ss_night_from", 22);
        all.put("wall_mine_only", true);
        all.put("user", "kostas");
        all.put("pass", "secret");
        all.put("tif", "ticket");
        all.put("tidied_at", 5L);
        all.put("last_channel", "uuid");
        JSONObject o = Backup.filter(all);
        assertEquals("a,b", o.getString("favourites"));
        assertEquals(30, o.getInt("ss_secs"));
        assertTrue(o.getBoolean("wall_mine_only"));
        for (String banned : new String[]{"user", "pass", "tif", "tidied_at", "last_channel"}) assertFalse(banned, o.has(banned));
    }

    @Test
    public void onlyKnownKeysAreRestoredAndTypesAreKept() throws Exception {
        Map<String, Object> existing = new HashMap<>();
        existing.put("ss_secs", 16);
        Map<String, Object> written = new HashMap<>();
        SharedPreferences.Editor ed = recorder(written);
        JSONObject saved = new JSONObject("{\"favourites\":\"a,b\",\"ss_secs\":30,\"live_hidden\":true,\"pass\":\"evil\",\"junk\":1}");
        int n = Backup.apply(saved, existing, ed);
        assertEquals(3, n);
        assertEquals("a,b", written.get("favourites"));
        assertEquals(30, written.get("ss_secs"));
        assertEquals(true, written.get("live_hidden"));
        assertFalse(written.containsKey("pass"));
        assertFalse(written.containsKey("junk"));
    }

    /** A minimal Editor that records puts. */
    private static SharedPreferences.Editor recorder(Map<String, Object> into) {
        return (SharedPreferences.Editor) java.lang.reflect.Proxy.newProxyInstance(
                BackupTest.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, m, args) -> {
                    if (m.getName().startsWith("put")) into.put((String) args[0], args[1]);
                    return proxy;
                });
    }
}
