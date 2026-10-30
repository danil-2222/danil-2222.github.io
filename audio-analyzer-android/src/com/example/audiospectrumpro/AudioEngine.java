package com.example.audiospectrumpro;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

/**
 * Captures audio (microphone or internal test generator), runs a windowed FFT on a
 * sliding buffer and publishes spectrum frames plus derived measurements.
 */
public class AudioEngine {
    public static final int SAMPLE_RATE = 44100;
    public static final float MIN_DB = -120f;
    private static final int HOP = 1024;

    public enum SourceType { MIC, GENERATOR }
    public enum GenWaveform { SINE, SQUARE, SAWTOOTH, TRIANGLE, WHITE_NOISE, PINK_NOISE, LOG_SWEEP }

    /** Immutable snapshot handed to the UI. */
    public static final class Frame {
        public int fftSize;
        public int sampleRate;
        public float binHz;
        public float[] spectrumDb;
        public float[] peakHoldDb;
        public float[] time;
        public float peakFreqHz;
        public float peakDb;
        public float thd;
        public float snr;
        public float rmsDb;
        public int bpm;
    }

    public interface Listener {
        void onFrame(Frame frame);
        void onError(String message);
    }

    private final Listener listener;
    private final BiquadFilter filter = new BiquadFilter();
    private final Random random = new Random();

    private volatile SourceType sourceType = SourceType.MIC;
    private volatile GenWaveform genWaveform = GenWaveform.SINE;
    private volatile float genFreq = 1000f;
    private volatile float genVol = 0.5f;
    private volatile float gainLinear = 1f;
    private volatile float smoothing = 0.6f;
    private volatile int requestedFftSize = 4096;
    private volatile boolean frozen;
    private volatile boolean running;
    private volatile boolean resetPeaks;

    private Thread thread;
    private AudioRecord record;
    private WavWriter wav;

    public AudioEngine(Listener listener) {
        this.listener = listener;
    }

    public void setSourceType(SourceType t) { sourceType = t; }
    public void setGenWaveform(GenWaveform w) { genWaveform = w; }
    public void setGenFreq(float f) { genFreq = f; }
    public void setGenVol(float v) { genVol = v; }
    public void setInputGainDb(float db) { gainLinear = (float) Math.pow(10, db / 20.0); }
    public void setSmoothing(float s) { smoothing = Math.max(0f, Math.min(0.95f, s)); }
    public void setFftSize(int size) { requestedFftSize = size; }
    public void setFilter(BiquadFilter.Type type) { filter.setFilter(type, SAMPLE_RATE); }
    public void setFrozen(boolean f) { frozen = f; }
    public boolean isFrozen() { return frozen; }
    public boolean isRunning() { return running; }
    public void resetPeakHold() { resetPeaks = true; }

    /** @param micAllowed whether RECORD_AUDIO is granted; without it only the generator works. */
    public synchronized void start(boolean micAllowed) {
        if (running) return;
        if (micAllowed) {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            try {
                AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, HOP * 2 * 8));
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    r.startRecording();
                    record = r;
                } else {
                    r.release();
                    listener.onError("Не вдалося відкрити мікрофон");
                }
            } catch (Exception e) {
                listener.onError("Мікрофон недоступний: " + e.getMessage());
            }
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "AudioEngine");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) {}
            thread = null;
        }
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) {}
            record.release();
            record = null;
        }
    }

    public synchronized void startRecording(File file) throws IOException {
        wav = new WavWriter(file, SAMPLE_RATE);
    }

    /** @return length in seconds of the finished recording, or -1 if nothing was recording. */
    public synchronized int stopRecording() {
        WavWriter w = wav;
        wav = null;
        if (w == null) return -1;
        try { w.close(); } catch (IOException ignored) {}
        return w.seconds();
    }

    public boolean isRecording() { return wav != null; }

    public int recordingSeconds() {
        WavWriter w = wav;
        return w == null ? 0 : w.seconds();
    }

    private void loop() {
        int size = 0;
        FFT fft = null;
        float[] ring = null, window = null, re = null, im = null, mag = null, smooth = null, peak = null;
        float windowSum = 1;
        short[] pcm = new short[HOP];
        float[] block = new float[HOP];

        double phase = 0, sweepT = 0;
        float pinkB0 = 0, pinkB1 = 0, pinkB2 = 0, pinkB3 = 0, pinkB4 = 0, pinkB5 = 0, pinkB6 = 0;

        float[] bassHistory = new float[43];
        int bassIdx = 0;
        long lastBeat = 0;
        long[] intervals = new long[8];
        int intervalCount = 0;
        int bpm = 0;
        boolean micWarned = false;

        while (running) {
            int want = requestedFftSize;
            if (want != size) {
                size = want;
                fft = new FFT(size);
                ring = new float[size];
                window = new float[size];
                windowSum = 0;
                for (int i = 0; i < size; i++) {
                    window[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * i / (size - 1))));
                    windowSum += window[i];
                }
                re = new float[size];
                im = new float[size];
                mag = new float[size / 2];
                smooth = new float[size / 2];
                peak = new float[size / 2];
                Arrays.fill(smooth, MIN_DB);
                Arrays.fill(peak, MIN_DB);
            }
            int hop = Math.min(HOP, size);

            // 1. Acquire one hop of samples.
            if (sourceType == SourceType.MIC) {
                if (record == null) {
                    if (!micWarned) {
                        listener.onError("Немає доступу до мікрофона — увімкніть генератор або дайте дозвіл");
                        micWarned = true;
                    }
                    Arrays.fill(block, 0, hop, 0f);
                    sleepMs(hop * 1000L / SAMPLE_RATE);
                } else {
                    int got = 0;
                    while (got < hop && running) {
                        int r = record.read(pcm, got, hop - got);
                        if (r <= 0) break;
                        got += r;
                    }
                    for (int i = 0; i < hop; i++) block[i] = i < got ? pcm[i] / 32768f : 0f;
                }
            } else {
                GenWaveform wf = genWaveform;
                float vol = genVol;
                for (int i = 0; i < hop; i++) {
                    double f = genFreq;
                    if (wf == GenWaveform.LOG_SWEEP) {
                        f = 20 * Math.pow(1000, sweepT / 8.0); // 20 Hz -> 20 kHz over 8 s
                        sweepT += 1.0 / SAMPLE_RATE;
                        if (sweepT >= 8.0) sweepT = 0;
                    }
                    double p = phase / (2 * Math.PI);
                    float s;
                    switch (wf) {
                        case SQUARE: s = p < 0.5 ? 1f : -1f; break;
                        case SAWTOOTH: s = (float) (2 * p - 1); break;
                        case TRIANGLE: s = (float) (p < 0.5 ? 4 * p - 1 : 3 - 4 * p); break;
                        case WHITE_NOISE: s = random.nextFloat() * 2 - 1; break;
                        case PINK_NOISE: {
                            float w = random.nextFloat() * 2 - 1;
                            pinkB0 = 0.99886f * pinkB0 + w * 0.0555179f;
                            pinkB1 = 0.99332f * pinkB1 + w * 0.0750759f;
                            pinkB2 = 0.96900f * pinkB2 + w * 0.1538520f;
                            pinkB3 = 0.86650f * pinkB3 + w * 0.3104856f;
                            pinkB4 = 0.55000f * pinkB4 + w * 0.5329522f;
                            pinkB5 = -0.7616f * pinkB5 - w * 0.0168980f;
                            s = (pinkB0 + pinkB1 + pinkB2 + pinkB3 + pinkB4 + pinkB5 + pinkB6 + w * 0.5362f) * 0.11f;
                            pinkB6 = w * 0.115926f;
                            break;
                        }
                        case SINE:
                        case LOG_SWEEP:
                        default: s = (float) Math.sin(phase); break;
                    }
                    block[i] = s * vol;
                    phase += 2 * Math.PI * f / SAMPLE_RATE;
                    if (phase >= 2 * Math.PI) phase -= 2 * Math.PI;
                }
                sleepMs(hop * 1000L / SAMPLE_RATE);
            }

            float g = gainLinear;
            for (int i = 0; i < hop; i++) block[i] *= g;
            filter.process(block, hop);

            WavWriter w = wav;
            if (w != null) {
                try { w.write(block, hop); } catch (IOException e) {
                    listener.onError("Помилка запису WAV: " + e.getMessage());
                    stopRecording();
                }
            }

            System.arraycopy(ring, hop, ring, 0, size - hop);
            System.arraycopy(block, 0, ring, size - hop, hop);

            if (frozen) continue;

            // 2. Windowed FFT.
            for (int i = 0; i < size; i++) { re[i] = ring[i] * window[i]; im[i] = 0f; }
            fft.forward(re, im);

            int bins = size / 2;
            float binHz = (float) SAMPLE_RATE / size;
            float norm = 2f / windowSum;
            float a = smoothing;
            float decay = 0.4f * hop / 1024f;
            if (resetPeaks) { Arrays.fill(peak, MIN_DB); resetPeaks = false; }

            int firstBin = Math.max(1, (int) (20 / binHz));
            int maxBin = firstBin;
            for (int i = 0; i < bins; i++) {
                float m = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]) * norm;
                mag[i] = m;
                float db = m > 1e-6f ? (float) (20 * Math.log10(m)) : MIN_DB;
                if (db < MIN_DB) db = MIN_DB;
                smooth[i] = smooth[i] * a + db * (1 - a);
                peak[i] = smooth[i] >= peak[i] ? smooth[i] : Math.max(MIN_DB, peak[i] - decay);
                if (i >= firstBin && smooth[i] > smooth[maxBin]) maxBin = i;
            }

            // 3. Peak frequency with parabolic interpolation.
            float peakFreq = maxBin * binHz;
            if (maxBin > 0 && maxBin < bins - 1) {
                float al = smooth[maxBin - 1], be = smooth[maxBin], ga = smooth[maxBin + 1];
                float den = al - 2 * be + ga;
                if (Math.abs(den) > 1e-6f) {
                    float d = 0.5f * (al - ga) / den;
                    peakFreq = (maxBin + Math.max(-0.5f, Math.min(0.5f, d))) * binHz;
                }
            }

            // 4. THD (harmonics 2..5) and SNR (fundamental vs. everything except harmonics).
            float thd = 0, snr = 0;
            int f0Bin = Math.round(peakFreq / binHz);
            if (smooth[maxBin] > -90 && peakFreq > 20) {
                float v1 = localMax(mag, f0Bin, 2);
                float harmPow = 0;
                for (int h = 2; h <= 5; h++) {
                    int hb = Math.round(h * peakFreq / binHz);
                    if (hb >= bins - 2) break;
                    float v = localMax(mag, hb, 2);
                    harmPow += v * v;
                }
                if (v1 > 1e-7f) thd = (float) (Math.sqrt(harmPow) / v1 * 100);

                double sig = 0, noise = 0;
                for (int i = firstBin; i < bins; i++) {
                    double p = (double) mag[i] * mag[i];
                    if (Math.abs(i - f0Bin) <= 3) { sig += p; continue; }
                    boolean isHarm = false;
                    for (int h = 2; h <= 5 && !isHarm; h++) {
                        if (Math.abs(i - Math.round(h * peakFreq / binHz)) <= 3) isHarm = true;
                    }
                    if (!isHarm) noise += p;
                }
                if (sig > 0 && noise > 0) snr = (float) (10 * Math.log10(sig / noise));
            }

            // 5. RMS level of the analysis buffer.
            double sum = 0;
            for (int i = 0; i < size; i++) sum += ring[i] * ring[i];
            double rms = Math.sqrt(sum / size);
            float rmsDb = rms > 1e-6 ? (float) (20 * Math.log10(rms)) : MIN_DB;

            // 6. Beat detection on 30-150 Hz energy.
            int lo = Math.max(1, (int) (30 / binHz)), hi = Math.max(lo + 1, (int) (150 / binHz));
            float bass = 0;
            for (int i = lo; i < hi && i < bins; i++) bass += mag[i] * mag[i];
            float avg = 0;
            for (float v : bassHistory) avg += v;
            avg /= bassHistory.length;
            bassHistory[bassIdx] = bass;
            bassIdx = (bassIdx + 1) % bassHistory.length;
            long now = System.currentTimeMillis();
            if (bass > avg * 2.0f && bass > 1e-6f && now - lastBeat > 280) {
                long dt = now - lastBeat;
                lastBeat = now;
                if (dt >= 300 && dt <= 1500) {
                    intervals[intervalCount % intervals.length] = dt;
                    intervalCount++;
                    int n = Math.min(intervalCount, intervals.length);
                    long[] sorted = Arrays.copyOf(intervals, n);
                    Arrays.sort(sorted);
                    bpm = Math.round(60000f / sorted[n / 2]);
                }
            }
            if (now - lastBeat > 3000) { bpm = 0; intervalCount = 0; }

            Frame f = new Frame();
            f.fftSize = size;
            f.sampleRate = SAMPLE_RATE;
            f.binHz = binHz;
            f.spectrumDb = smooth.clone();
            f.peakHoldDb = peak.clone();
            f.time = ring.clone();
            f.peakFreqHz = peakFreq;
            f.peakDb = smooth[maxBin];
            f.thd = thd;
            f.snr = snr;
            f.rmsDb = rmsDb;
            f.bpm = bpm;
            listener.onFrame(f);
        }
    }

    private static float localMax(float[] m, int center, int radius) {
        float best = 0;
        for (int i = Math.max(0, center - radius); i <= Math.min(m.length - 1, center + radius); i++) {
            if (m[i] > best) best = m[i];
        }
        return best;
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
