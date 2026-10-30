package com.example.audiospectrumpro;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Frequency-axis mapping shared by the 2D and 3D views. */
final class SpectrumMath {
    static final float LOG_F_MIN = 20f;

    /** Frequency at normalised position t (0..1) of the axis spanning [lo, hi]. */
    static float freqAt(float t, boolean log, float lo, float hi) {
        return log ? (float) (lo * Math.pow(hi / lo, t)) : lo + t * (hi - lo);
    }

    /** Normalised axis position of frequency f on the axis spanning [lo, hi]. */
    static float posOf(float f, boolean log, float lo, float hi) {
        if (log) return (float) (Math.log(Math.max(f, 1e-3f) / lo) / Math.log(hi / lo));
        return (f - lo) / (hi - lo);
    }

    /** Interpolated dB value at fractional bin b. */
    static float valueAtBin(float[] db, float b) {
        if (b <= 0) return db[0];
        int i = (int) b;
        if (i >= db.length - 1) return db[db.length - 1];
        float f = b - i;
        return db[i] + (db[i + 1] - db[i]) * f;
    }

    /**
     * Resamples a spectrum into out.length display columns: the maximum of the bins a column
     * covers, or an interpolated value where a column is narrower than one bin.
     */
    static void columns(float[] db, float binHz, boolean log, float lo, float hi, float[] out) {
        int cols = out.length;
        int last = db.length - 1;
        float b1 = freqAt(0, log, lo, hi) / binHz;
        for (int c = 0; c < cols; c++) {
            float b0 = b1;
            b1 = freqAt((float) (c + 1) / cols, log, lo, hi) / binHz;
            int i0 = (int) Math.ceil(b0), i1 = Math.min(last, (int) Math.floor(b1));
            if (i1 >= i0) {
                float m = db[i0];
                for (int i = i0 + 1; i <= i1; i++) if (db[i] > m) m = db[i];
                out[c] = m;
            } else {
                out[c] = valueAtBin(db, (b0 + b1) * 0.5f);
            }
        }
    }

    /** Labelled grid frequencies for [lo, hi], chosen so roughly maxLabels fit. */
    static List<Float> majorTicks(boolean log, float lo, float hi, int maxLabels) {
        List<Float> out = new ArrayList<>();
        if (log) {
            int[][] sets = {{1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 5}, {1, 3}, {1}};
            for (int[] set : sets) {
                out.clear();
                for (double dec = Math.pow(10, Math.floor(Math.log10(lo))); dec <= hi; dec *= 10) {
                    for (int m : set) {
                        float f = (float) (dec * m);
                        if (f >= lo * 0.999f && f <= hi * 1.001f) out.add(f);
                    }
                }
                if (out.size() <= maxLabels) break;
            }
        } else {
            double raw = (hi - lo) / Math.max(1, maxLabels);
            double mag = Math.pow(10, Math.floor(Math.log10(raw)));
            double step = raw / mag < 2 ? 2 * mag : raw / mag < 5 ? 5 * mag : 10 * mag;
            for (double f = Math.ceil(lo / step) * step; f <= hi; f += step) out.add((float) f);
        }
        return out;
    }

    /** Unlabelled minor grid lines (log axis only). */
    static List<Float> minorTicks(float lo, float hi) {
        List<Float> out = new ArrayList<>();
        for (double dec = Math.pow(10, Math.floor(Math.log10(lo))); dec <= hi; dec *= 10) {
            for (int m = 1; m <= 9; m++) {
                float f = (float) (dec * m);
                if (f >= lo && f <= hi) out.add(f);
            }
        }
        return out;
    }

    static String tickLabel(float f) {
        if (f >= 1000) {
            float k = f / 1000f;
            return (k == Math.floor(k) ? String.valueOf((int) k) : String.format(Locale.US, "%.1f", k)) + "k";
        }
        return f == Math.floor(f) ? String.valueOf((int) f) : String.format(Locale.US, "%.1f", f);
    }

    static String formatHz(float f) {
        if (f >= 10000) return String.format(Locale.US, "%.2f kHz", f / 1000);
        if (f >= 1000) return String.format(Locale.US, "%.3f kHz", f / 1000);
        if (f >= 100) return String.format(Locale.US, "%.1f Hz", f);
        return String.format(Locale.US, "%.2f Hz", f);
    }

    private static final String[] NOTES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    /** Note name with octave for f, and cents deviation written to centsOut[0]. */
    static String noteName(float f, float a4, float[] centsOut) {
        double midi = 69 + 12 * Math.log(f / a4) / Math.log(2);
        long n = Math.round(midi);
        if (centsOut != null) centsOut[0] = (float) ((midi - n) * 100);
        int idx = (int) (((n % 12) + 12) % 12);
        return NOTES[idx] + ((n < 0 ? (n - 11) / 12 : n / 12) - 1);
    }

    private SpectrumMath() {}
}
