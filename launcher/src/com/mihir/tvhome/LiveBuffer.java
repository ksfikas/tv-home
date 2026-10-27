package com.mihir.tvhome;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/**
 * Live stream source that keeps receiving while playback is paused, so live TV can be paused.
 *
 * A pump thread reads the HTTP stream into a small in-memory FIFO. While the player is consuming
 * (normal viewing) the FIFO stays tiny and nothing touches the disk. When the player stops reading
 * (paused), the FIFO fills and the overflow spills to a temp file, capped at {@link #MAX_SPILL}.
 * Data comes back out strictly in order: memory first, then the file. Once the file is drained it
 * is truncated, and memory mode resumes. Past the cap the pump stops, so the player drains what's
 * buffered, ends, and the caller reconnects to live.
 */
final class LiveBuffer implements DataSource {

    static final long MAX_SPILL = 700L * 1024 * 1024;   // ~30-45 min of a Greek DVB-T HD channel
    private static final int MEM_CAP = 4 * 1024 * 1024;
    private static final int CHUNK = 64 * 1024;

    private final DefaultHttpDataSource upstream;
    private final File spillFile;

    private final Object lock = new Object();
    private final ArrayDeque<byte[]> mem = new ArrayDeque<>();
    private int memBytes, headOffset;
    private RandomAccessFile spill;
    private long spillWrite, spillRead;
    private boolean eof, closed;
    private IOException pumpError;
    private Thread pump;
    private Uri uri;

    /** Bytes waiting on disk right now: how far behind live the viewer is, roughly. */
    static volatile long spilled;

    LiveBuffer(DefaultHttpDataSource upstream, File spillFile) {
        this.upstream = upstream;
        this.spillFile = spillFile;
    }

    @Override public void addTransferListener(TransferListener l) { upstream.addTransferListener(l); }

    /** Never let a pause eat the box's last free space: keep 400 MB spare. */
    private long cap = MAX_SPILL;

    @Override
    public long open(DataSpec spec) throws IOException {
        uri = spec.uri;
        File dir = spillFile.getParentFile();
        if (dir != null) cap = Math.max(0, Math.min(MAX_SPILL, dir.getUsableSpace() - 400L * 1024 * 1024));
        upstream.open(spec);
        pump = new Thread(this::pumpLoop, "LiveBuffer");
        pump.start();
        return C.LENGTH_UNSET;
    }

    private void pumpLoop() {
        byte[] buf = new byte[CHUNK];
        try {
            while (true) {
                synchronized (lock) { if (closed) return; }
                int n = upstream.read(buf, 0, buf.length);
                if (n == C.RESULT_END_OF_INPUT) break;
                if (n <= 0) continue;
                synchronized (lock) {
                    if (closed) return;
                    if (spillWrite == spillRead && memBytes + n <= MEM_CAP) {
                        mem.addLast(java.util.Arrays.copyOf(buf, n));
                        memBytes += n;
                    } else {
                        if (spillWrite - spillRead + n > cap) break; // paused too long: stop receiving
                        if (spill == null) spill = new RandomAccessFile(spillFile, "rw");
                        spill.seek(spillWrite);
                        spill.write(buf, 0, n);
                        spillWrite += n;
                        spilled = spillWrite - spillRead;
                    }
                    lock.notifyAll();
                }
            }
        } catch (IOException e) {
            synchronized (lock) { pumpError = e; }
        } finally {
            synchronized (lock) { eof = true; lock.notifyAll(); }
        }
    }

    @Override
    public int read(byte[] out, int off, int len) throws IOException {
        if (len == 0) return 0;
        synchronized (lock) {
            while (memBytes == 0 && spillWrite == spillRead && !eof && !closed) {
                try { lock.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException(); }
            }
            if (closed) return C.RESULT_END_OF_INPUT;
            if (memBytes > 0) {
                byte[] head = mem.peekFirst();
                int n = Math.min(len, head.length - headOffset);
                System.arraycopy(head, headOffset, out, off, n);
                headOffset += n;
                memBytes -= n;
                if (headOffset == head.length) { mem.removeFirst(); headOffset = 0; }
                return n;
            }
            if (spillWrite > spillRead) {
                spill.seek(spillRead);
                int n = spill.read(out, off, (int) Math.min(len, spillWrite - spillRead));
                if (n > 0) spillRead += n;
                if (spillRead == spillWrite) { spill.setLength(0); spillRead = spillWrite = 0; }
                spilled = spillWrite - spillRead;
                return Math.max(n, 0);
            }
            if (pumpError != null) throw pumpError;
            return C.RESULT_END_OF_INPUT;
        }
    }

    @Override public Uri getUri() { return uri; }

    @Override public Map<String, List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
            mem.clear();
            memBytes = 0;
            if (spill != null) { try { spill.close(); } catch (IOException ignored) { } spill = null; }
            spillFile.delete();
            spilled = 0;
        }
        // Closing the connection unblocks a pump stuck in read().
        upstream.close();
        if (pump != null) { pump.interrupt(); pump = null; }
    }
}
