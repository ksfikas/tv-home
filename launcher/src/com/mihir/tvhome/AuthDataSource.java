package com.mihir.tvhome;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * HTTP source for recordings: adds a fresh Tvheadend Authorization header to every request
 * (seeking reopens the file with a Range), and retries once with a new nonce after a 401.
 */
final class AuthDataSource implements DataSource {

    private final Tvh tvh;
    private final DefaultHttpDataSource http;

    AuthDataSource(Tvh tvh, DefaultHttpDataSource http) {
        this.tvh = tvh;
        this.http = http;
    }

    @Override public void addTransferListener(TransferListener l) { http.addTransferListener(l); }

    @Override
    public long open(DataSpec spec) throws IOException {
        String url = spec.uri.toString();
        authorize(url);
        try {
            return http.open(spec);
        } catch (HttpDataSource.InvalidResponseCodeException e) {
            if (e.responseCode != 401) throw e;
            tvh.refreshAuth();
            authorize(url);
            return http.open(spec);
        }
    }

    private void authorize(String url) throws IOException {
        String header = tvh.authHeader(url);
        if (header != null) http.setRequestProperty("Authorization", header);
        else http.clearRequestProperty("Authorization"); // server without a login
    }

    @Override public int read(byte[] buf, int off, int len) throws IOException { return http.read(buf, off, len); }

    @Override public Uri getUri() { return http.getUri(); }

    @Override public Map<String, List<String>> getResponseHeaders() { return http.getResponseHeaders(); }

    @Override public void close() throws IOException { http.close(); }
}
