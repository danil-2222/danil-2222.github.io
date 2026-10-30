package com.example.audiospectrumpro;

/** Frequency-axis mapping shared by the 2D and 3D views. */
final class SpectrumMath {
    static final float LOG_F_MIN = 20f;

    /** Frequency at normalised position t (0..1) along the axis. */
    static float freqAt(float t, boolean log, float fMax) {
        return log ? (float) (LOG_F_MIN * Math.pow(fMax / LOG_F_MIN, t)) : t * fMax;
    }

    /** Normalised axis position (0..1) of frequency f. */
    static float posOf(float f, boolean log, float fMax) {
        if (log) return (float) (Math.log(Math.max(f, 1e-3f) / LOG_F_MIN) / Math.log(fMax / LOG_F_MIN));
        return f / fMax;
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
    static void columns(float[] db, float binHz, boolean log, float fMax, float[] out) {
        int cols = out.length;
        int last = db.length - 1;
        for (int c = 0; c < cols; c++) {
            float b0 = freqAt((float) c / cols, log, fMax) / binHz;
            float b1 = freqAt((float) (c + 1) / cols, log, fMax) / binHz;
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

    static String formatHz(float f) {
        if (f >= 10000) return String.format(java.util.Locale.US, "%.1f kHz", f / 1000);
        if (f >= 1000) return String.format(java.util.Locale.US, "%.2f kHz", f / 1000);
        return String.format(java.util.Locale.US, "%.1f Hz", f);
    }

    private static final String[] NOTES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    /** Note name with octave for f, and cents deviation written to centsOut[0]. */
    static String noteName(float f, float[] centsOut) {
        double midi = 69 + 12 * Math.log(f / 440.0) / Math.log(2);
        long n = Math.round(midi);
        if (centsOut != null) centsOut[0] = (float) ((midi - n) * 100);
        int idx = (int) (((n % 12) + 12) % 12);
        return NOTES[idx] + (n / 12 - 1);
    }

    private SpectrumMath() {}
}
