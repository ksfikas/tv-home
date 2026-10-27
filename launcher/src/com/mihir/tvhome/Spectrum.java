package com.mihir.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.media3.common.C;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Live spectrum for radio. The player hands every decoded PCM buffer to {@link Tap} (a
 * TeeAudioProcessor sink, so audio passes through untouched); while enabled it mixes to mono,
 * runs a 1024-point FFT and folds it into log-spaced bands. {@link Bars} draws them at display
 * rate only while it's on screen. Disabled (TV, recordings) it returns immediately.
 */
final class Spectrum {
    static final int BANDS = 64, N = 1024;

    private Spectrum() { }

    static final class Tap implements TeeAudioProcessor.AudioBufferSink {
        volatile boolean enabled;
        final float[] levels = new float[BANDS]; // 0..1, written here, read by the view

        private int channels = 2, encoding = C.ENCODING_INVALID, sampleRate = 48000, pos;
        private final float[] re = new float[N], im = new float[N], win = new float[N], mono = new float[N];
        private final double[] db = new double[BANDS];
        private double peakDb = 0; // slow automatic gain: the loudest recent band sets the top
        private static final double RANGE_DB = 42;

        Tap() {
            for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1))); // Hann
        }

        @Override
        public void flush(int sampleRateHz, int channelCount, int enc) {
            sampleRate = sampleRateHz;
            channels = Math.max(1, channelCount);
            encoding = enc;
            pos = 0;
        }

        @Override
        public void handleBuffer(ByteBuffer buffer) {
            if (!enabled || encoding != C.ENCODING_PCM_16BIT) return;
            ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int frames = b.remaining() / (2 * channels);
            for (int f = 0; f < frames; f++) {
                float sum = 0;
                for (int c = 0; c < channels; c++) sum += b.getShort();
                mono[pos++] = sum / (channels * 32768f);
                if (pos == N) { analyse(); pos = 0; }
            }
        }

        private void analyse() {
            for (int i = 0; i < N; i++) { re[i] = mono[i] * win[i]; im[i] = 0; }
            fft(re, im);
            // Log-spaced bands from ~110 Hz (below that a 1024-point FFT has too few bins to move
            // independently) to ~14 kHz. A slight tilt lifts the naturally quieter treble.
            double lo = 110, hi = Math.min(14000, sampleRate / 2.0), binHz = (double) sampleRate / N;
            double frameMax = -200;
            for (int band = 0; band < BANDS; band++) {
                double f0 = lo * Math.pow(hi / lo, (double) band / BANDS), f1 = lo * Math.pow(hi / lo, (double) (band + 1) / BANDS);
                int b0 = Math.max(1, (int) (f0 / binHz)), b1 = Math.max(b0 + 1, (int) (f1 / binHz));
                float peak = 0;
                for (int k = b0; k < b1 && k < N / 2; k++) peak = Math.max(peak, re[k] * re[k] + im[k] * im[k]);
                db[band] = 10 * Math.log10(peak + 1e-12) + 9.0 * band / BANDS;
                frameMax = Math.max(frameMax, db[band]);
            }
            // Jump up to new peaks, settle down slowly (~3 dB/s), never below a sensible floor.
            peakDb = Math.max(frameMax, Math.max(peakDb - 0.07, -30));
            for (int band = 0; band < BANDS; band++) {
                levels[band] = (float) Math.max(0, Math.min(1, (db[band] - (peakDb - RANGE_DB)) / RANGE_DB));
            }
        }

        /** In-place iterative radix-2 FFT. */
        private static void fft(float[] re, float[] im) {
            int n = re.length;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
            }
            for (int len = 2; len <= n; len <<= 1) {
                double ang = -2 * Math.PI / len;
                float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
                for (int i = 0; i < n; i += len) {
                    float cr = 1, ci = 0;
                    for (int k = 0; k < len / 2; k++) {
                        int a = i + k, b = a + len / 2;
                        float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                        re[b] = re[a] - xr; im[b] = im[a] - xi;
                        re[a] += xr; im[a] += xi;
                        float nr = cr * wr - ci * wi;
                        ci = cr * wi + ci * wr;
                        cr = nr;
                    }
                }
            }
        }
    }

    /**
     * Thin rounded bars rising from a hairline, with a faint mirrored reflection below and the
     * whole shape fading out toward both sides. Eased motion: soft rise, long fall.
     */
    static final class Bars extends View {
        private final Tap tap;
        private final float[] shown = new float[BANDS];
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), mirror = new Paint(Paint.ANTI_ALIAS_FLAG),
                line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Bars(Context c, Tap tap) {
            super(c);
            this.tap = tap;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float base = h * 0.72f;
            bar.setShader(new LinearGradient(0, base, 0, 0, 0x73FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP));
            mirror.setShader(new LinearGradient(0, base, 0, h, 0x29FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            line.setShader(new LinearGradient(0, 0, w, 0, new int[]{0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            float base = H * 0.72f, top = base, below = H - base;
            float slot = W / (float) BANDS, w = slot * 0.42f, rad = w / 2;
            c.drawRect(0, base, W, base + 1, line);
            for (int i = 0; i < BANDS; i++) {
                float target = tap.levels[i];
                shown[i] += (target - shown[i]) * (target > shown[i] ? 0.35f : 0.08f);
                // Taper: full height in the middle, easing to nothing at the edges.
                float taper = (float) Math.sin(Math.PI * (i + 0.5) / BANDS);
                float h = Math.max(w, shown[i] * taper * top);
                float x = i * slot + (slot - w) / 2;
                int alpha = (int) (255 * (0.35f + 0.65f * taper));
                bar.setAlpha(alpha);
                r.set(x, base - h - 2, x + w, base - 2);
                c.drawRoundRect(r, rad, rad, bar);
                mirror.setAlpha(alpha);
                r.set(x, base + 2, x + w, base + 2 + Math.min(below - 2, h * 0.45f));
                c.drawRoundRect(r, rad, rad, mirror);
            }
            if (isShown()) postInvalidateOnAnimation();
        }

        @Override
        protected void onVisibilityChanged(View v, int visibility) {
            super.onVisibilityChanged(v, visibility);
            tap.enabled = isShown();
            if (isShown()) invalidate();
        }
    }
}
