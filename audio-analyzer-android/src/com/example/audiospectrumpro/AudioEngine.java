package com.example.audiospectrumpro;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * Captures audio (microphone or internal test generator), runs a windowed FFT on a
 * sliding buffer and publishes spectrum frames plus derived measurements.
 */
public class AudioEngine {
    public static final float MIN_DB = -140f;
    private static final int HOP = 1024;
    /** MediaRecorder.AudioSource.UNPROCESSED (API 24); the build targets the API 23 stubs. */
    public static final int SOURCE_UNPROCESSED = 9;

    public enum SourceType { MIC, GENERATOR }
    public enum WindowType { HANN, HAMMING, BLACKMAN_HARRIS, FLAT_TOP, RECTANGULAR }

    /** Immutable snapshot handed to the UI. Levels include the calibration offset. */
    public static final class Frame {
        public int fftSize;
        public int sampleRate;
        public float binHz;
        /** Equivalent noise bandwidth of the window, in bins. */
        public float enbw;
        /** Offset already added to every level below; subtract it to get dBFS. */
        public float calibrationDb;
        public float[] spectrumDb;
        public float[] peakHoldDb;
        public float[] time;
        public float peakFreqHz;
        public float peakDb;
        public float thd;
        public float thdN;
        public float snr;
        public float rmsDb;
        public float clipPercent;
        public int bpm;
        /** True when the spectrum is dominated by one tone, so THD/SNR are meaningful. */
        public boolean tonal;
        /** Sequence number; gaps mean the UI skipped frames. */
        public long index;
    }

    public interface Listener {
        void onFrame(Frame frame);
        void onError(String message);
    }

    private final Listener listener;
    private final BiquadFilter filter = new BiquadFilter();
    private final SignalGenerator generator = new SignalGenerator();

    private volatile SourceType sourceType = SourceType.MIC;
    private volatile int micSource = SOURCE_UNPROCESSED;
    private volatile int sampleRate = 48000;
    private volatile float gainLinear = 1f;
    private volatile float calibrationDb = 0f;
    private volatile float smoothing = 0.6f;
    private volatile int requestedFftSize = 4096;
    private volatile WindowType requestedWindow = WindowType.HANN;
    private volatile boolean frozen;
    private volatile boolean running;
    private volatile boolean resetPeaks;
    private volatile int actualSampleRate;

    private Thread thread;
    private AudioRecord record;
    private WavWriter wav;

    public AudioEngine(Listener listener) {
        this.listener = listener;
    }

    public void setSourceType(SourceType t) { sourceType = t; }
    public void setInputGainDb(float db) { gainLinear = (float) Math.pow(10, db / 20.0); }
    public void setCalibrationDb(float db) { calibrationDb = db; }
    public void setSmoothing(float s) { smoothing = Math.max(0f, Math.min(0.97f, s)); }
    public void setFftSize(int size) { requestedFftSize = size; }
    public void setWindow(WindowType w) { requestedWindow = w; }
    public void setFilter(BiquadFilter.Type type) { filter.setFilter(type, actualSampleRate > 0 ? actualSampleRate : sampleRate); }
    public void setFrozen(boolean f) { frozen = f; }
    public boolean isFrozen() { return frozen; }
    public boolean isRunning() { return running; }
    public void resetPeakHold() { resetPeaks = true; }
    public int getSampleRate() { return actualSampleRate > 0 ? actualSampleRate : sampleRate; }

    /** Takes effect on the next start(). */
    public void setMicSource(int source) { micSource = source; }

    /** Takes effect on the next start(). */
    public void setSampleRate(int rate) { sampleRate = rate; }

    /** @param micAllowed whether RECORD_AUDIO is granted; without it only the generator works. */
    public synchronized void start(boolean micAllowed) {
        if (running) return;
        actualSampleRate = sampleRate;
        if (micAllowed) record = openRecord();
        filter.setFilter(filter.getType(), actualSampleRate);
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "AudioEngine");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    /** Tries the preferred source and rate first, then falls back to safer combinations. */
    private AudioRecord openRecord() {
        int[] sources = {micSource, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        int[] rates = {sampleRate, 48000, 44100};
        String lastError = null;
        for (int src : sources) {
            for (int rate : rates) {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                try {
                    AudioRecord r = new AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, HOP * 2 * 8));
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                        r.startRecording();
                        if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                            actualSampleRate = rate;
                            if (src != micSource) listener.onError("Обраний тип входу недоступний — використано інший");
                            return r;
                        }
                    }
                    r.release();
                } catch (Exception e) {
                    lastError = e.getMessage();
                }
            }
        }
        listener.onError("Не вдалося відкрити мікрофон" + (lastError != null ? ": " + lastError : ""));
        return null;
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
        wav = new WavWriter(file, getSampleRate());
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

    static float[] buildWindow(WindowType type, int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            double x = 2 * Math.PI * i / (n - 1);
            double v;
            switch (type) {
                case HAMMING: v = 0.54 - 0.46 * Math.cos(x); break;
                case BLACKMAN_HARRIS:
                    v = 0.35875 - 0.48829 * Math.cos(x) + 0.14128 * Math.cos(2 * x) - 0.01168 * Math.cos(3 * x);
                    break;
                case FLAT_TOP:
                    v = 0.21557895 - 0.41663158 * Math.cos(x) + 0.277263158 * Math.cos(2 * x)
                            - 0.083578947 * Math.cos(3 * x) + 0.006947368 * Math.cos(4 * x);
                    break;
                case RECTANGULAR: v = 1; break;
                case HANN:
                default: v = 0.5 * (1 - Math.cos(x)); break;
            }
            w[i] = (float) v;
        }
        return w;
    }

    private void loop() {
        final int rate = actualSampleRate;
        int size = 0;
        WindowType windowType = null;
        FFT fft = null;
        float[] ring = null, window = null, re = null, im = null, mag = null, smooth = null, peak = null;
        float windowSum = 1, enbw = 1.5f;
        short[] pcm = new short[HOP];
        float[] block = new float[HOP];

        float[] bassHistory = new float[43];
        Arrays.fill(bassHistory, MIN_DB);
        int bassIdx = 0;
        long lastBeat = 0;
        long[] intervals = new long[8];
        int intervalCount = 0;
        int bpm = 0;
        boolean micWarned = false;
        long frameIndex = 0;

        while (running) {
            int want = requestedFftSize;
            if (want != size) {
                size = want;
                fft = new FFT(size);
                ring = new float[size];
                re = new float[size];
                im = new float[size];
                mag = new float[size / 2];
                smooth = new float[size / 2];
                peak = new float[size / 2];
                Arrays.fill(smooth, MIN_DB);
                Arrays.fill(peak, MIN_DB);
                windowType = null;
            }
            if (windowType != requestedWindow) {
                windowType = requestedWindow;
                window = buildWindow(windowType, size);
                windowSum = 0;
                double sq = 0;
                for (float v : window) { windowSum += v; sq += v * v; }
                enbw = (float) (size * sq / ((double) windowSum * windowSum));
            }
            int hop = Math.min(HOP, size);

            // 1. Acquire one hop of samples.
            int clipped = 0;
            if (sourceType == SourceType.MIC) {
                if (record == null) {
                    if (!micWarned) {
                        listener.onError("Немає доступу до мікрофона — оберіть внутрішній генератор або дайте дозвіл");
                        micWarned = true;
                    }
                    Arrays.fill(block, 0, hop, 0f);
                    sleepMs(hop * 1000L / rate);
                } else {
                    int got = 0;
                    while (got < hop && running) {
                        int r = record.read(pcm, got, hop - got);
                        if (r <= 0) break;
                        got += r;
                    }
                    for (int i = 0; i < hop; i++) {
                        short s = i < got ? pcm[i] : 0;
                        if (s >= 32767 || s <= -32768) clipped++;
                        block[i] = s / 32768f;
                    }
                }
            } else {
                generator.fill(block, hop, rate);
                sleepMs(hop * 1000L / rate);
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
            float binHz = (float) rate / size;
            float norm = 2f / windowSum;
            float a = smoothing;
            float decay = 0.4f * hop / 1024f;
            if (resetPeaks) { Arrays.fill(peak, MIN_DB); resetPeaks = false; }

            int firstBin = Math.max(1, (int) Math.ceil(20 / binHz));
            int maxBin = firstBin;
            for (int i = 0; i < bins; i++) {
                float m = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]) * norm;
                mag[i] = m;
                float db = m > 1e-7f ? (float) (20 * Math.log10(m)) : MIN_DB;
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

            // 4. THD (harmonics 2..9), THD+N and SNR (fundamental vs. everything except harmonics).
            float thd = 0, thdN = 0, snr = 0;
            int f0Bin = Math.round(peakFreq / binHz);
            int lobe = windowType == WindowType.RECTANGULAR ? 2 : windowType == WindowType.HANN || windowType == WindowType.HAMMING ? 3 : 5;
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

            // 5. RMS level of the analysis buffer.
            double sum = 0;
            for (int i = 0; i < size; i++) sum += ring[i] * ring[i];
            double rms = Math.sqrt(sum / size);
            float rmsDb = rms > 1e-7 ? (float) (20 * Math.log10(rms)) : MIN_DB;

            // 6. Beat detection: onsets in 30-150 Hz energy, BPM reported only when intervals agree.
            int lo = Math.max(1, (int) (30 / binHz)), hi = Math.max(lo + 1, (int) (150 / binHz));
            double bass = 0;
            for (int i = lo; i < hi && i < bins; i++) bass += (double) mag[i] * mag[i];
            float bassDb = bass > 1e-14 ? (float) (10 * Math.log10(bass)) : MIN_DB;
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

            float cal = calibrationDb;
            Frame f = new Frame();
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
            f.time = ring.clone();
            f.peakFreqHz = peakFreq;
            f.peakDb = smooth[maxBin] + cal;
            f.thd = thd;
            f.thdN = thdN;
            f.snr = snr;
            f.rmsDb = rmsDb + cal;
            f.clipPercent = 100f * clipped / hop;
            f.bpm = bpm;
            f.tonal = snr > 15;
            f.index = frameIndex++;
            listener.onFrame(f);
        }
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
