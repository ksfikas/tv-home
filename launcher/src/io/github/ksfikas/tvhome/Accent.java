package io.github.ksfikas.tvhome;

import android.graphics.Bitmap;

/**
 * The colour a picture "feels" like, for the glow behind the home screen: the most vivid hue in it,
 * brightened so it reads on the dark scrim. Greys and near-blacks don't count, so a black-and-white
 * poster gives the fallback instead of mud. No library: a 24 x 24 thumbnail and a hue histogram.
 */
final class Accent {
    private Accent() { }

    private static final int BINS = 12;

    /** Accent of a bitmap, or {@code fallback} when it has no real colour (or is null). */
    static int of(Bitmap b, int fallback) {
        if (b == null || b.getWidth() == 0 || b.getHeight() == 0) return fallback;
        Bitmap small = Bitmap.createScaledBitmap(b, 24, 24, true);
        int[] px = new int[24 * 24];
        small.getPixels(px, 0, 24, 0, 0, 24, 24);
        if (small != b) small.recycle();
        return dominant(px, fallback);
    }

    /** Accent of ARGB pixels. */
    static int dominant(int[] px, int fallback) {
        double[] weight = new double[BINS], r = new double[BINS], g = new double[BINS], bl = new double[BINS];
        for (int p : px) {
            if ((p >>> 24) < 128) continue;
            int pr = (p >> 16) & 255, pg = (p >> 8) & 255, pb = p & 255;
            int max = Math.max(pr, Math.max(pg, pb)), min = Math.min(pr, Math.min(pg, pb));
            if (max == 0) continue;
            double v = max / 255.0, s = (max - min) / (double) max;
            if (v < 0.18 || s < 0.22) continue;          // too dark or too grey to carry a colour
            double w = s * s * v;                         // vivid and bright counts most
            int bin = (int) (hue(pr, pg, pb, max, min) / 360.0 * BINS) % BINS;
            weight[bin] += w; r[bin] += pr * w; g[bin] += pg * w; bl[bin] += pb * w;
        }
        int best = -1;
        double total = 0;
        for (int i = 0; i < BINS; i++) { total += weight[i]; if (best < 0 || weight[i] > weight[best]) best = i; }
        if (best < 0 || weight[best] < 0.6 || total <= 0) return fallback;   // nothing vivid in a 576-pixel picture
        return lift((int) (r[best] / weight[best]), (int) (g[best] / weight[best]), (int) (bl[best] / weight[best]));
    }

    /** Saturation at least 0.55, brightness at least 0.6: a glow needs to be seen, not muddy. */
    private static int lift(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double v = Math.max(max / 255.0, 0.6), s = max == 0 ? 0 : Math.max((max - min) / (double) max, 0.55);
        double h = hue(r, g, b, max, min);
        // HSV to RGB
        double c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
        double[] rgb = h < 60 ? new double[]{c, x, 0} : h < 120 ? new double[]{x, c, 0} : h < 180 ? new double[]{0, c, x}
                : h < 240 ? new double[]{0, x, c} : h < 300 ? new double[]{x, 0, c} : new double[]{c, 0, x};
        return 0xFF000000 | (int) Math.round((rgb[0] + m) * 255) << 16 | (int) Math.round((rgb[1] + m) * 255) << 8 | (int) Math.round((rgb[2] + m) * 255);
    }

    private static double hue(int r, int g, int b, int max, int min) {
        if (max == min) return 0;
        double d = max - min, h;
        if (max == r) h = ((g - b) / d) % 6;
        else if (max == g) h = (b - r) / d + 2;
        else h = (r - g) / d + 4;
        h *= 60;
        return h < 0 ? h + 360 : h;
    }
}
