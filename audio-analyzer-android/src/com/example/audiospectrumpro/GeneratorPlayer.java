package com.example.audiospectrumpro;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Plays the test signal through the loudspeaker so it can be measured with the microphone. */
final class GeneratorPlayer {
    private static final int RATE = 48000;
    private static final int CHUNK = 1024;

    private volatile boolean running;
    private Thread thread;

    boolean isPlaying() { return running; }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() { play(); }
        }, "GeneratorPlayer");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) {}
            thread = null;
        }
    }

    private void play() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track;
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 4), AudioTrack.MODE_STREAM);
            track.play();
        } catch (Exception e) {
            running = false;
            return;
        }
        SignalGenerator gen = new SignalGenerator();
        float[] buf = new float[CHUNK];
        short[] pcm = new short[CHUNK];
        while (running) {
            gen.fill(buf, CHUNK, RATE);
            for (int i = 0; i < CHUNK; i++) pcm[i] = (short) Math.round(Math.max(-1f, Math.min(1f, buf[i])) * 32767f);
            track.write(pcm, 0, CHUNK); // blocks, which paces the loop
        }
        try { track.stop(); } catch (Exception ignored) {}
        track.release();
    }
}
