package com.example.audiospectrumpro;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

/**
 * Plays sonar pings through the loudspeaker while recording the microphone, and feeds every
 * period of the recording to a {@link SonarProcessor}. Needs exclusive use of the microphone,
 * so the main {@link AudioEngine} must be stopped while it runs.
 */
final class SonarEngine {
    interface Listener {
        void onSonar(SonarProcessor.Frame frame);
        void onSonarError(String message);
    }

    private static final int PERIOD_MS = 100;
    private static final float CHIRP_MS = 10;

    private final Listener listener;
    private volatile boolean running;
    private volatile boolean motion;
    private volatile float level = 0.8f;
    private volatile boolean levelChanged;
    private volatile boolean resetBackground;
    private Thread recordThread, playThread;
    private SonarProcessor processor;

    SonarEngine(Listener listener) { this.listener = listener; }

    boolean isRunning() { return running; }

    void setMotion(boolean m) {
        motion = m;
        resetBackground = true;
    }

    void setLevel(float l) {
        level = l;
        levelChanged = true;
    }

    synchronized void start(float f0, float f1, float rangeM, int micSource) {
        if (running) return;
        AudioRecord rec = openRecord(micSource);
        if (rec == null) {
            listener.onSonarError("Не вдалося відкрити мікрофон для ехолота");
            return;
        }
        int rate = rec.getSampleRate();
        float top = Math.min(f1, rate / 2f - 600);
        float bottom = Math.min(f0, top - 2000);
        int period = rate * PERIOD_MS / 1000;
        processor = new SonarProcessor(rate, bottom, top, CHIRP_MS, period, rangeM);
        running = true;
        final AudioRecord record = rec;
        playThread = new Thread(new Runnable() {
            @Override public void run() { play(); }
        }, "SonarPlay");
        recordThread = new Thread(new Runnable() {
            @Override public void run() { capture(record); }
        }, "SonarRecord");
        playThread.setPriority(Thread.MAX_PRIORITY);
        recordThread.setPriority(Thread.MAX_PRIORITY);
        playThread.start();
        recordThread.start();
    }

    synchronized void stop() {
        running = false;
        try {
            if (playThread != null) playThread.join(600);
            if (recordThread != null) recordThread.join(600);
        } catch (InterruptedException ignored) {}
        playThread = null;
        recordThread = null;
    }

    private AudioRecord openRecord(int preferred) {
        // Sources without echo cancellation or AGC: the echo is exactly what we want to hear.
        int[] sources = {preferred, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            for (int rate : new int[]{48000, 44100}) {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                try {
                    AudioRecord r = new AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate / 2));
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                        r.startRecording();
                        if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) return r;
                    }
                    r.release();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private void play() {
        SonarProcessor p = processor;
        int rate = p.rate;
        AudioTrack track;
        try {
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, p.period * 2 * 2), AudioTrack.MODE_STREAM);
            track.play();
        } catch (Exception e) {
            listener.onSonarError("Не вдалося відкрити динамік: " + e.getMessage());
            running = false;
            return;
        }
        short[] pcm = toPcm(p.pingBuffer(level));
        try {
            while (running) {
                if (levelChanged) {
                    levelChanged = false;
                    pcm = toPcm(p.pingBuffer(level));
                }
                if (track.write(pcm, 0, pcm.length) < 0) break; // blocks: one ping per period
            }
        } finally {
            try { track.stop(); } catch (Exception ignored) {}
            track.release();
        }
    }

    private static short[] toPcm(float[] f) {
        short[] s = new short[f.length];
        for (int i = 0; i < f.length; i++) s[i] = (short) Math.round(Math.max(-1f, Math.min(1f, f[i])) * 32767f);
        return s;
    }

    private void capture(AudioRecord record) {
        SonarProcessor p = processor;
        int period = p.period;
        float[] window = new float[2 * period];
        short[] chunk = new short[period / 4];
        int sinceLast = 0;
        try {
            while (running) {
                int r = record.read(chunk, 0, chunk.length);
                if (r <= 0) {
                    listener.onSonarError("Мікрофон перестав віддавати звук");
                    break;
                }
                System.arraycopy(window, r, window, 0, window.length - r);
                for (int i = 0; i < r; i++) window[window.length - r + i] = chunk[i] / 32768f;
                sinceLast += r;
                if (sinceLast >= period) {
                    sinceLast -= period;
                    if (resetBackground) {
                        resetBackground = false;
                        p.resetBackground();
                    }
                    listener.onSonar(p.process(window, motion));
                }
            }
        } finally {
            try { record.stop(); } catch (Exception ignored) {}
            record.release();
            running = false;
        }
    }
}
