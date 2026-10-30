package com.example.audiospectrumpro;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * Captures audio (microphone or internal generator) and publishes analysed frames.
 * The last {@link #HISTORY_SECONDS} seconds of input are kept so they can be reopened as a
 * {@link Tape} ("time machine"); audio files are analysed through the same tape mode.
 */
public class AudioEngine {
    public static final float MIN_DB = -140f;
    public static final int HOP = 1024;
    public static final int HISTORY_SECONDS = 120;
    /** MediaRecorder.AudioSource.UNPROCESSED (API 24); the build targets the API 23 stubs. */
    public static final int SOURCE_UNPROCESSED = 9;

    public enum SourceType { MIC, GENERATOR }
    public enum WindowType { HANN, HAMMING, BLACKMAN_HARRIS, FLAT_TOP, RECTANGULAR }

    /** Mono 16-bit audio held in memory: recent history or a decoded file. */
    public static final class Tape {
        public final short[] pcm;
        public final int rate;
        public final String name;
        /** True for the live-input history, false for a file. */
        public final boolean history;

        public Tape(short[] pcm, int rate, String name, boolean history) {
            this.pcm = pcm;
            this.rate = rate;
            this.name = name;
            this.history = history;
        }

        public float seconds() { return (float) pcm.length / rate; }
    }

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
        /** Set when the frame comes from a tape; position is the end of the analysis window. */
        public Tape tape;
        public int tapePosition;
        public boolean tapePlaying;
    }

    public interface Listener {
        void onFrame(Frame frame);
        void onError(String message);
    }

    private final Listener listener;
    private final BiquadFilter filter = new BiquadFilter();
    private final SignalGenerator generator = new SignalGenerator();
    private final Analyzer analyzer = new Analyzer();

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

    // Live-input history ring buffer (raw samples, before gain and filter).
    private short[] history;
    private int historyPos;
    private long historyCount;
    private int historyRate;

    // Tape mode.
    private volatile Tape tape;
    private volatile int playhead;
    private volatile boolean playing;
    private volatile boolean refresh;
    private volatile float speed = 1f;
    private volatile boolean audible = true;
    private AudioTrack track;
    private float trackSpeed;

    private Thread thread;
    private AudioRecord record;
    private WavWriter wav;

    public AudioEngine(Listener listener) {
        this.listener = listener;
    }

    public void setSourceType(SourceType t) { sourceType = t; }
    public void setInputGainDb(float db) { gainLinear = (float) Math.pow(10, db / 20.0); refresh = true; }
    public void setCalibrationDb(float db) { calibrationDb = db; refresh = true; }
    public void setSmoothing(float s) { smoothing = Math.max(0f, Math.min(0.97f, s)); }
    public void setFftSize(int size) { requestedFftSize = size; refresh = true; }
    public void setWindow(WindowType w) { requestedWindow = w; refresh = true; }

    public void setFilter(BiquadFilter.Type type) {
        filter.setFilter(type, actualSampleRate > 0 ? actualSampleRate : sampleRate);
        refresh = true;
    }

    public BiquadFilter.Type getFilterType() { return filter.getType(); }
    public float getGainLinear() { return gainLinear; }
    public int getFftSize() { return requestedFftSize; }
    public WindowType getWindow() { return requestedWindow; }
    public float getCalibrationDb() { return calibrationDb; }
    public void setFrozen(boolean f) { frozen = f; }
    public boolean isFrozen() { return frozen; }
    public boolean isRunning() { return running; }
    public void resetPeakHold() { resetPeaks = true; refresh = true; }
    public int getSampleRate() { return actualSampleRate > 0 ? actualSampleRate : sampleRate; }

    /** Takes effect on the next start(). */
    public void setMicSource(int source) { micSource = source; }

    /** Takes effect on the next start(). */
    public void setSampleRate(int rate) { sampleRate = rate; }

    // ---------------------------------------------------------------- tape ("time machine")

    /** Seconds of live input currently held in the history buffer. */
    public synchronized float historySeconds() {
        return history == null ? 0 : (float) Math.min(historyCount, history.length) / historyRate;
    }

    /** Copies the history buffer (oldest first) into a tape, or returns null if it is empty. */
    public synchronized Tape captureHistory() {
        if (history == null || historyCount == 0) return null;
        int n = (int) Math.min(historyCount, history.length);
        short[] pcm = new short[n];
        int start = (historyPos - n + history.length) % history.length;
        int first = Math.min(n, history.length - start);
        System.arraycopy(history, start, pcm, 0, first);
        System.arraycopy(history, 0, pcm, first, n - first);
        return new Tape(pcm, historyRate, "Останні " + Math.round((float) n / historyRate) + " с", true);
    }

    public void openTape(Tape t, int position) {
        playing = false;
        playhead = Math.max(0, Math.min(t.pcm.length, position));
        tape = t;
        refresh = true;
    }

    public void closeTape() {
        playing = false;
        tape = null;
    }

    public Tape getTape() { return tape; }
    public int getPlayhead() { return playhead; }
    public boolean isPlaying() { return playing; }

    public void seek(int position) {
        Tape t = tape;
        if (t == null) return;
        playhead = Math.max(0, Math.min(t.pcm.length, position));
        refresh = true;
    }

    public void setPlaying(boolean p) {
        Tape t = tape;
        if (t == null) return;
        if (p && playhead >= t.pcm.length) playhead = 0;
        playing = p;
        refresh = true;
    }

    public void setSpeed(float s) { speed = s; }
    public float getSpeed() { return speed; }
    public void setTapeAudible(boolean a) { audible = a; }

    // ---------------------------------------------------------------- lifecycle

    /** @param micAllowed whether RECORD_AUDIO is granted; without it only generator and tapes work. */
    public synchronized void start(boolean micAllowed) {
        if (running) return;
        actualSampleRate = sampleRate;
        if (micAllowed) record = openRecord();
        filter.setFilter(filter.getType(), actualSampleRate);
        if (history == null || historyRate != actualSampleRate) {
            historyRate = actualSampleRate;
            history = new short[historyRate * HISTORY_SECONDS];
            historyPos = 0;
            historyCount = 0;
        }
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
            try { thread.join(800); } catch (InterruptedException ignored) {}
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

    // ---------------------------------------------------------------- processing

    private void loop() {
        final int rate = actualSampleRate;
        float[] ring = new float[0];
        short[] pcm = new short[HOP];
        float[] block = new float[HOP];
        boolean micWarned = false;

        try {
            while (running) {
                analyzer.configure(requestedFftSize, requestedWindow);
                int size = analyzer.size();
                if (ring.length != size) ring = new float[size];
                if (resetPeaks) { analyzer.resetPeaks(); resetPeaks = false; }

                Tape t = tape;
                if (t != null) {
                    tapeStep(t, ring);
                    continue;
                }
                releaseTrack();
                int hop = Math.min(HOP, size);

                // 1. Acquire one hop of raw samples.
                int clipped = 0;
                if (sourceType == SourceType.MIC) {
                    if (record == null) {
                        if (!micWarned) {
                            listener.onError("Немає доступу до мікрофона — оберіть внутрішній генератор або дайте дозвіл");
                            micWarned = true;
                        }
                        Arrays.fill(pcm, 0, hop, (short) 0);
                        sleepMs(hop * 1000L / rate);
                    } else {
                        int got = 0;
                        while (got < hop && running) {
                            int r = record.read(pcm, got, hop - got);
                            if (r <= 0) break;
                            got += r;
                        }
                        for (int i = got; i < hop; i++) pcm[i] = 0;
                    }
                    for (int i = 0; i < hop; i++) {
                        short s = pcm[i];
                        if (s >= 32767 || s <= -32768) clipped++;
                        block[i] = s / 32768f;
                    }
                } else {
                    generator.fill(block, hop, rate);
                    for (int i = 0; i < hop; i++) {
                        pcm[i] = (short) Math.round(Math.max(-1f, Math.min(1f, block[i])) * 32767f);
                    }
                    sleepMs(hop * 1000L / rate);
                }
                appendHistory(pcm, hop);

                // 2. Gain, filter, optional recording.
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

                listener.onFrame(analyzer.analyze(ring, rate, smoothing, calibrationDb, clipped, hop, true));
            }
        } finally {
            releaseTrack();
        }
    }

    private synchronized void appendHistory(short[] pcm, int n) {
        if (history == null) return;
        int len = history.length;
        int first = Math.min(n, len - historyPos);
        System.arraycopy(pcm, 0, history, historyPos, first);
        System.arraycopy(pcm, first, history, 0, n - first);
        historyPos = (historyPos + n) % len;
        historyCount += n;
    }

    /** One step of tape mode: play the next hop, or re-analyse the current position after a seek. */
    private void tapeStep(Tape t, float[] ring) {
        int size = ring.length;
        if (playing) {
            int start = playhead;
            if (start >= t.pcm.length) {
                playing = false;
                refresh = true;
                return;
            }
            int n = Math.min(Math.min(HOP, size), t.pcm.length - start);
            float sp = speed;
            if (audible && writeTrack(t, start, n, sp)) {
                // AudioTrack.write blocks, which paces playback.
            } else {
                sleepMs(Math.max(1, (long) (n * 1000f / (t.rate * sp))));
            }
            if (tape != t || !playing) return; // seek or close while writing
            if (playhead == start) playhead = start + n;
            refresh = false;
            listener.onFrame(analyzeTape(t, playhead, smoothing, n, true));
        } else {
            pauseTrack();
            if (refresh) {
                refresh = false;
                // A paused tape shows exactly one moment: no smoothing or peak hold from elsewhere.
                analyzer.resetSmoothing();
                analyzer.resetPeaks();
                listener.onFrame(analyzeTape(t, playhead, 0f, 0, false));
            } else {
                sleepMs(15);
            }
        }
    }

    private Frame analyzeTape(Tape t, int pos, float smooth, int hop, boolean isPlaying) {
        int size = analyzer.size();
        float[] buf = new float[size];
        float g = gainLinear;
        int clipped = 0;
        for (int i = 0; i < size; i++) {
            int idx = pos - size + i;
            if (idx < 0 || idx >= t.pcm.length) continue;
            short s = t.pcm[idx];
            if (s >= 32767 || s <= -32768) clipped++;
            buf[i] = s / 32768f * g;
        }
        BiquadFilter f = new BiquadFilter();
        f.setFilter(filter.getType(), t.rate);
        f.process(buf, size);
        Frame frame = analyzer.analyze(buf, t.rate, smooth, calibrationDb, clipped, hop > 0 ? hop : size, false);
        frame.clipPercent = 100f * clipped / size;
        frame.tape = t;
        frame.tapePosition = pos;
        frame.tapePlaying = isPlaying;
        return frame;
    }

    private boolean writeTrack(Tape t, int start, int n, float sp) {
        try {
            if (track == null || track.getSampleRate() != t.rate) {
                releaseTrack();
                int min = AudioTrack.getMinBufferSize(t.rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack(AudioManager.STREAM_MUSIC, t.rate, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, HOP * 4), AudioTrack.MODE_STREAM);
                trackSpeed = 0;
            }
            if (sp != trackSpeed) {
                track.setPlaybackRate(Math.round(t.rate * sp));
                trackSpeed = sp;
            }
            if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
            return track.write(t.pcm, start, n) > 0;
        } catch (Exception e) {
            releaseTrack();
            return false;
        }
    }

    private void pauseTrack() {
        if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            try {
                track.pause();
                track.flush();
            } catch (Exception ignored) {}
        }
    }

    private void releaseTrack() {
        if (track == null) return;
        try { track.stop(); } catch (Exception ignored) {}
        track.release();
        track = null;
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
