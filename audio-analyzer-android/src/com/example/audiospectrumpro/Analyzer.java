package com.example.audiospectrumpro;

import java.util.Arrays;

/** Windowed FFT analysis of a sample buffer plus derived measurements; keeps smoothing state. */
final class Analyzer {
    private int size;
    private AudioEngine.WindowType windowType;
    private FFT fft;
    private float[] window, re, im, mag, smooth, peak;
    private float windowSum = 1, enbw = 1.5f;

    private final float[] bassHistory = new float[43];
    private int bassIdx;
    private long lastBeat;
    private final long[] intervals = new long[8];
    private int intervalCount;
    private int bpm;
    private long frameIndex;

    Analyzer() {
        Arrays.fill(bassHistory, AudioEngine.MIN_DB);
    }

    int size() { return size; }

    /** (Re)allocates buffers when the FFT size or window changes. */
    void configure(int newSize, AudioEngine.WindowType type) {
        if (newSize != size) {
            size = newSize;
            fft = new FFT(size);
            re = new float[size];
            im = new float[size];
            mag = new float[size / 2];
            smooth = new float[size / 2];
            peak = new float[size / 2];
            Arrays.fill(smooth, AudioEngine.MIN_DB);
            Arrays.fill(peak, AudioEngine.MIN_DB);
            windowType = null;
        }
        if (type != windowType) {
            windowType = type;
            window = AudioEngine.buildWindow(type, size);
            windowSum = 0;
            double sq = 0;
            for (float v : window) { windowSum += v; sq += v * v; }
            enbw = (float) (size * sq / ((double) windowSum * windowSum));
        }
    }

    void resetPeaks() { Arrays.fill(peak, AudioEngine.MIN_DB); }

    void resetSmoothing() { Arrays.fill(smooth, AudioEngine.MIN_DB); }

    /**
     * Analyses the last size samples in buf.
     *
     * @param hop      samples added since the previous call (scales peak-hold decay)
     * @param beats    whether to run beat detection (only meaningful in real time)
     */
    AudioEngine.Frame analyze(float[] buf, int rate, float smoothing, float cal, int clipped, int hop, boolean beats) {
        final float min = AudioEngine.MIN_DB;
        for (int i = 0; i < size; i++) { re[i] = buf[i] * window[i]; im[i] = 0f; }
        fft.forward(re, im);

        int bins = size / 2;
        float binHz = (float) rate / size;
        float norm = 2f / windowSum;
        float decay = 0.4f * hop / 1024f;

        int firstBin = Math.max(1, (int) Math.ceil(20 / binHz));
        int maxBin = firstBin;
        for (int i = 0; i < bins; i++) {
            float m = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]) * norm;
            mag[i] = m;
            float db = m > 1e-7f ? (float) (20 * Math.log10(m)) : min;
            if (db < min) db = min;
            smooth[i] = smooth[i] * smoothing + db * (1 - smoothing);
            peak[i] = smooth[i] >= peak[i] ? smooth[i] : Math.max(min, peak[i] - decay);
            if (i >= firstBin && smooth[i] > smooth[maxBin]) maxBin = i;
        }

        // Peak frequency with parabolic interpolation.
        float peakFreq = maxBin * binHz;
        if (maxBin > 0 && maxBin < bins - 1) {
            float al = smooth[maxBin - 1], be = smooth[maxBin], ga = smooth[maxBin + 1];
            float den = al - 2 * be + ga;
            if (Math.abs(den) > 1e-6f) {
                float d = 0.5f * (al - ga) / den;
                peakFreq = (maxBin + Math.max(-0.5f, Math.min(0.5f, d))) * binHz;
            }
        }

        // THD (harmonics 2..9), THD+N and SNR (fundamental vs. everything except harmonics).
        float thd = 0, thdN = 0, snr = 0;
        int f0Bin = Math.round(peakFreq / binHz);
        int lobe = windowType == AudioEngine.WindowType.RECTANGULAR ? 2
                : windowType == AudioEngine.WindowType.HANN || windowType == AudioEngine.WindowType.HAMMING ? 3 : 5;
        if (smooth[maxBin] > -100 && peakFreq > 20) {
            double sig = 0, harm = 0, noise = 0;
            for (int i = firstBin; i < bins; i++) {
                double p = (double) mag[i] * mag[i];
                if (Math.abs(i - f0Bin) <= lobe) { sig += p; continue; }
                boolean isHarm = false;
                for (int h = 2; h <= 9 && !isHarm; h++) {
                    int hb = Math.round(h * peakFreq / binHz);
                    if (hb >= bins) break;
                    if (Math.abs(i - hb) <= lobe) isHarm = true;
                }
                if (isHarm) harm += p; else noise += p;
            }
            if (sig > 0) {
                thd = (float) (Math.sqrt(harm / sig) * 100);
                thdN = (float) (Math.sqrt((harm + noise) / sig) * 100);
                if (noise > 0) snr = (float) (10 * Math.log10(sig / noise));
            }
        }

        double sum = 0;
        for (int i = 0; i < size; i++) sum += buf[i] * buf[i];
        double rms = Math.sqrt(sum / size);
        float rmsDb = rms > 1e-7 ? (float) (20 * Math.log10(rms)) : min;

        if (beats) detectBeat(binHz, bins); else bpm = 0;

        AudioEngine.Frame f = new AudioEngine.Frame();
        f.fftSize = size;
        f.sampleRate = rate;
        f.binHz = binHz;
        f.enbw = enbw;
        f.calibrationDb = cal;
        f.spectrumDb = new float[bins];
        f.peakHoldDb = new float[bins];
        for (int i = 0; i < bins; i++) {
            f.spectrumDb[i] = smooth[i] + cal;
            f.peakHoldDb[i] = peak[i] + cal;
        }
        f.time = Arrays.copyOf(buf, size);
        f.peakFreqHz = peakFreq;
        f.peakDb = smooth[maxBin] + cal;
        f.thd = thd;
        f.thdN = thdN;
        f.snr = snr;
        f.rmsDb = rmsDb + cal;
        f.clipPercent = hop > 0 ? 100f * clipped / hop : 0;
        f.bpm = bpm;
        f.tonal = snr > 15;
        f.index = frameIndex++;
        return f;
    }

    /** Onsets in 30-150 Hz energy; BPM is reported only when recent intervals agree. */
    private void detectBeat(float binHz, int bins) {
        int lo = Math.max(1, (int) (30 / binHz)), hi = Math.max(lo + 1, (int) (150 / binHz));
        double bass = 0;
        for (int i = lo; i < hi && i < bins; i++) bass += (double) mag[i] * mag[i];
        float bassDb = bass > 1e-14 ? (float) (10 * Math.log10(bass)) : AudioEngine.MIN_DB;
        float avgDb = 0;
        for (float v : bassHistory) avgDb += v;
        avgDb /= bassHistory.length;
        bassHistory[bassIdx] = bassDb;
        bassIdx = (bassIdx + 1) % bassHistory.length;
        long now = System.currentTimeMillis();
        if (bassDb > avgDb + 6 && bassDb > -50 && now - lastBeat > 280) {
            long dt = now - lastBeat;
            lastBeat = now;
            if (dt >= 300 && dt <= 1500) {
                intervals[intervalCount % intervals.length] = dt;
                intervalCount++;
                int n = Math.min(intervalCount, intervals.length);
                if (n >= 4) {
                    long[] sorted = Arrays.copyOf(intervals, n);
                    Arrays.sort(sorted);
                    long median = sorted[n / 2];
                    long[] dev = new long[n];
                    for (int i = 0; i < n; i++) dev[i] = Math.abs(sorted[i] - median);
                    Arrays.sort(dev);
                    bpm = dev[n / 2] < median * 0.12 ? Math.round(60000f / median) : 0;
                }
            } else {
                intervalCount = 0;
            }
        }
        if (now - lastBeat > 3000) { bpm = 0; intervalCount = 0; }
    }
}
