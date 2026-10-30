package com.example.audiospectrumpro;

/**
 * Active sonar signal processing (no Android dependencies). The loudspeaker repeats a tapered
 * linear chirp every {@code period} samples; the microphone recording is matched-filtered
 * against the chirp. The strongest arrival is the direct speaker-to-microphone path and serves
 * as time zero, so unknown playback/recording latency does not matter.
 */
public final class SonarProcessor {
    public static final float SPEED_OF_SOUND = 343f;

    /** Result of one ping. Levels are dB relative to the direct path. */
    public static final class Frame {
        public float[] profileDb;
        public float[] motionDb;
        public float metersPerSample;
        public float rangeM;
        /** Direct-path peak above the correlation noise floor; low values mean the band does not work. */
        public float directSnrDb;
        /** Distance of the nearest echo (or of the strongest movement in motion mode), NaN if none. */
        public float targetM = Float.NaN;
        public float targetDb;
        public boolean motionMode;
        public long index;
    }

    public final int rate;
    public final int period;
    public final float[] chirp;
    private final int n;
    private final FFT fft;
    private final float[] hRe, hIm;
    private final float[] re, im;
    private final int profileLen;
    private final int blindSamples;
    private float[] bgRe, bgIm;
    /** Envelope of the direct pulse's own compressed shape (dB re peak) at each lag after it. */
    private final float[] selfDb;
    private long index;

    public SonarProcessor(int rate, float f0, float f1, float chirpMs, int period, float rangeM) {
        this.rate = rate;
        this.period = period;
        this.chirp = makeChirp(rate, f0, f1, Math.round(chirpMs * rate / 1000f));
        int need = 2 * period + chirp.length;
        int size = 1;
        while (size < need) size <<= 1;
        n = size;
        fft = new FFT(n);
        hRe = new float[n];
        hIm = new float[n];
        // Matched filter reference with a Blackman-Harris taper: a wider peak, but range sidelobes
        // low enough that weak echoes close to the phone are not hidden by the direct pulse.
        float[] taper = AudioEngine.buildWindow(AudioEngine.WindowType.BLACKMAN_HARRIS, chirp.length);
        for (int i = 0; i < chirp.length; i++) hRe[i] = chirp[i] * taper[i];
        fft.forward(hRe, hIm);
        re = new float[n];
        im = new float[n];
        // Run the chirp itself through the matched filter to learn its range sidelobes.
        float[] alone = new float[2 * period];
        System.arraycopy(chirp, 0, alone, period / 4, chirp.length);
        correlate(alone);
        int pk = 0;
        for (int i = 0; i < period; i++) if (mag2(i) > mag2(pk)) pk = i;
        int tmpLen = Math.min(period - chirp.length, Math.round(2 * rangeM / SPEED_OF_SOUND * rate) + 1);
        selfDb = new float[tmpLen];
        for (int i = 0; i < tmpLen; i++) {
            selfDb[i] = (float) (10 * Math.log10(mag2(pk + i) / mag2(pk) + 1e-14));
        }
        profileLen = Math.min(period - chirp.length, Math.round(2 * rangeM / SPEED_OF_SOUND * rate) + 1);
        // Near zone hidden by the direct pulse itself: main lobe of the compressed chirp plus margin.
        blindSamples = Math.round(rate / Math.abs(f1 - f0) * 2.5f) + Math.round(2 * 0.08f / SPEED_OF_SOUND * rate);
    }

    /** Linear chirp with raised-cosine edges so the speaker does not click. */
    public static float[] makeChirp(int rate, float f0, float f1, int len) {
        float[] c = new float[len];
        int taper = Math.max(1, len / 8);
        double k = (f1 - f0) / ((double) len / rate);
        for (int i = 0; i < len; i++) {
            double t = (double) i / rate;
            double w = 1;
            if (i < taper) w = 0.5 - 0.5 * Math.cos(Math.PI * i / taper);
            else if (i >= len - taper) w = 0.5 - 0.5 * Math.cos(Math.PI * (len - 1 - i) / taper);
            c[i] = (float) (w * Math.sin(2 * Math.PI * (f0 * t + 0.5 * k * t * t)));
        }
        return c;
    }

    /** One period of loudspeaker output: chirp at the start, silence after it. */
    public float[] pingBuffer(float level) {
        float[] out = new float[period];
        for (int i = 0; i < chirp.length; i++) out[i] = chirp[i] * level;
        return out;
    }

    public void resetBackground() { bgRe = null; bgIm = null; }

    /**
     * @param rec   the latest 2 * period recorded samples (oldest first)
     * @param motion highlight changes against a slowly adapting background instead of raw echoes
     */
    private float mag2(int i) { return re[i] * re[i] + im[i] * im[i]; }

    /** Complex (analytic) matched-filter output of the last 2 * period samples into re/im. */
    private void correlate(float[] rec) {
        int len = Math.min(rec.length, 2 * period);
        for (int i = 0; i < n; i++) {
            re[i] = i < len ? rec[rec.length - len + i] : 0f;
            im[i] = 0f;
        }
        fft.forward(re, im);
        // Cross-correlation spectrum R = X * conj(H), keeping positive frequencies only so the
        // inverse transform is the analytic signal: its magnitude is the echo envelope.
        for (int i = 0; i < n; i++) {
            float xr = re[i], xi = im[i];
            float pr = xr * hRe[i] + xi * hIm[i];
            float pi = xi * hRe[i] - xr * hIm[i];
            float g = i == 0 || i == n / 2 ? 1f : i < n / 2 ? 2f : 0f;
            // Conjugate here and after the forward FFT to get an inverse FFT.
            re[i] = pr * g;
            im[i] = -pi * g;
        }
        fft.forward(re, im);
        float inv = 1f / n;
        for (int i = 0; i < n; i++) {
            re[i] *= inv;
            im[i] = -im[i] * inv;
        }
    }

    public Frame process(float[] rec, boolean motion) {
        correlate(rec);

        // Direct path: strongest arrival among the lags of the first ping in the window.
        int direct = 0;
        float best = 0;
        for (int i = 0; i < period; i++) {
            float m = re[i] * re[i] + im[i] * im[i];
            if (m > best) { best = m; direct = i; }
        }
        float directMag = (float) Math.sqrt(best) + 1e-12f;

        Frame f = new Frame();
        f.index = index++;
        f.motionMode = motion;
        f.metersPerSample = SPEED_OF_SOUND / rate / 2f;
        f.rangeM = (profileLen - 1) * f.metersPerSample;
        f.profileDb = new float[profileLen];
        f.motionDb = new float[profileLen];
        if (bgRe == null || bgRe.length != profileLen) {
            bgRe = new float[profileLen];
            bgIm = new float[profileLen];
            for (int i = 0; i < profileLen; i++) {
                int k = Math.min(n - 1, direct + i);
                bgRe[i] = re[k] / directMag;
                bgIm[i] = im[k] / directMag;
            }
        }
        for (int i = 0; i < profileLen; i++) {
            int k = Math.min(n - 1, direct + i);
            float pr = re[k] / directMag, pim = im[k] / directMag;
            f.profileDb[i] = (float) (10 * Math.log10(pr * pr + pim * pim + 1e-14));
            float dr = pr - bgRe[i], di = pim - bgIm[i];
            f.motionDb[i] = (float) (10 * Math.log10(dr * dr + di * di + 1e-14));
            bgRe[i] = bgRe[i] * 0.92f + pr * 0.08f;
            bgIm[i] = bgIm[i] * 0.92f + pim * 0.08f;
        }

        // Correlation noise floor from the far part of the period (after the longest echoes).
        double noise = 0;
        int cnt = 0;
        // Correlation noise floor between the longest echoes and the next ping's pre-ringing.
        for (int i = (int) (period * 0.35f); i < (int) (period * 0.8f); i++) {
            int k = (direct + i) % period;
            noise += re[k] * re[k] + im[k] * im[k];
            cnt++;
        }
        float floorRel = (float) (10 * Math.log10(noise / Math.max(1, cnt) / best + 1e-14));
        f.directSnrDb = -floorRel;

        // Target: first clear local maximum past the blind zone (static), or strongest change (motion).
        float[] p = motion ? f.motionDb : f.profileDb;
        float threshold = motion ? Math.max(-60f, floorRel + 15) : Math.max(-60f, floorRel + 12);
        int start = Math.min(profileLen - 2, blindSamples);
        int target = -1;
        if (motion) {
            for (int i = start; i < profileLen - 1; i++) {
                if (p[i] > threshold && (target < 0 || p[i] > p[target])) target = i;
            }
        } else {
            for (int i = start + 1; i < profileLen - 1; i++) {
                // A real echo must stand well above the direct pulse's own sidelobes at that lag.
                if (p[i] > threshold && p[i] > selfDb[i] + 6 && p[i] >= p[i - 1] && p[i] >= p[i + 1]) {
                    // Take the top of this echo (its envelope may have a few ripples).
                    int top = i;
                    int win = Math.round(rate / 4000f);
                    for (int j = i; j < Math.min(profileLen, i + win); j++) if (p[j] > p[top]) top = j;
                    target = top;
                    break;
                }
            }
        }
        if (target > 0) {
            f.targetM = target * f.metersPerSample;
            f.targetDb = p[target];
        }
        return f;
    }

    public int blindSamples() { return blindSamples; }
}
