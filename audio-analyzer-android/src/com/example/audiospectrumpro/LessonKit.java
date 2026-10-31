package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

/** Shared drawing helpers and small DSP utilities for the lessons. */
final class LessonKit {
    static final int CYAN = 0xFF00F0FF, GREEN = 0xFF39FF14, YELLOW = 0xFFFFE600, PINK = 0xFFFF007F;
    static final int ORANGE = 0xFFFF8800, BLUE = 0xFF64B5F6, GRID = 0xFF1E293B, LABEL = 0xFF7F93A8;
    static final int SOFT = 0xFFCBD5E1, WHITE = 0xFFFFFFFF, CARD = 0xE60A101E;

    final float dp;
    final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();

    LessonKit(float dp) {
        this.dp = dp;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    void label(Canvas c, String s, float x, float y, float sizeDp, int color, Paint.Align align, boolean bold) {
        text.setTextSize(sizeDp * dp);
        text.setColor(color);
        text.setTextAlign(align);
        text.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(s, x, y, text);
    }

    void line(Canvas c, float x0, float y0, float x1, float y1, int color, float widthDp) {
        stroke.setColor(color);
        stroke.setStrokeWidth(widthDp * dp);
        c.drawLine(x0, y0, x1, y1, stroke);
    }

    void rect(Canvas c, float l, float t, float r, float b, int color) {
        fill.setColor(color);
        c.drawRect(l, t, r, b, fill);
    }

    /** Hands out the samples that arrived since the previous frame (live input or a playing tape). */
    static final class SampleTap {
        private long last = -1;

        float[] take(AudioEngine.Frame f) {
            if (f.tape != null && !f.tapePlaying) { last = f.index; return new float[0]; }
            long missed = last < 0 ? 1 : f.index - last;
            last = f.index;
            if (missed <= 0) return new float[0];
            int n = (int) Math.min(f.time.length, missed * AudioEngine.HOP);
            float[] out = new float[n];
            System.arraycopy(f.time, f.time.length - n, out, 0, n);
            return out;
        }

        void reset() { last = -1; }
    }

    /** Goertzel power of one frequency over x[off .. off + n). A full-scale sine at f gives ~ (n/2)^2. */
    static double goertzel(float[] x, int off, int n, double freq, int rate) {
        double w = 2 * Math.PI * freq / rate, coeff = 2 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < n; i++) {
            double s = x[off + i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }

    /** Tone with raised-cosine fade in/out, mixed into dst at offset. */
    static void addTone(float[] dst, int off, int len, double freq, float amp, int rate, int fade) {
        for (int i = 0; i < len && off + i < dst.length; i++) {
            float env = 1f;
            if (i < fade) env = (float) (0.5 - 0.5 * Math.cos(Math.PI * i / fade));
            else if (i > len - fade) env = (float) (0.5 - 0.5 * Math.cos(Math.PI * (len - i) / fade));
            dst[off + i] += (float) (amp * env * Math.sin(2 * Math.PI * freq * i / rate));
        }
    }

    /** A plucked note with a few harmonics and an exponential decay (for the ear trainer). */
    static void addNote(float[] dst, int off, int len, double freq, float amp, int rate) {
        float[] h = {1f, 0.5f, 0.3f, 0.18f, 0.1f};
        for (int i = 0; i < len && off + i < dst.length; i++) {
            double t = (double) i / rate;
            float env = (float) (Math.exp(-t * 2.2) * Math.min(1, i / (rate * 0.006)));
            if (i > len - rate / 50) env *= (float) (len - i) / (rate / 50f);
            double v = 0;
            for (int k = 0; k < h.length; k++) v += h[k] * Math.sin(2 * Math.PI * freq * (k + 1) * t);
            dst[off + i] += (float) (amp * env * v / 2.1);
        }
    }

    static void normalize(float[] x, float peak) {
        float m = 1e-9f;
        for (float v : x) m = Math.max(m, Math.abs(v));
        float g = peak / m;
        for (int i = 0; i < x.length; i++) x[i] *= g;
    }

    /** Total power (re full scale, uncalibrated) of a frame's spectrum between lo and hi Hz. */
    static double bandPower(AudioEngine.Frame f, float lo, float hi) {
        int i0 = Math.max(1, (int) (lo / f.binHz)), i1 = Math.min(f.spectrumDb.length - 1, (int) (hi / f.binHz));
        double p = 0;
        for (int i = i0; i <= i1; i++) p += Math.pow(10, (f.spectrumDb[i] - f.calibrationDb) / 10.0);
        return p / Math.max(1f, f.enbw);
    }

    static float midiToHz(float midi) { return (float) (440 * Math.pow(2, (midi - 69) / 12.0)); }

    /**
     * Like SampleTap, but never loses time: if frames were skipped for longer than one analysis window,
     * the missing stretch comes back as silence, so counting samples is an exact clock.
     */
    static final class GaplessTap {
        private long last = -1;

        float[] take(AudioEngine.Frame f) {
            if (f.tape != null) { last = f.index; return new float[0]; }
            long missed = last < 0 ? 1 : f.index - last;
            last = f.index;
            if (missed <= 0) return new float[0];
            long want = missed * AudioEngine.HOP;
            int have = (int) Math.min(f.time.length, want);
            int gap = (int) Math.min(want - have, f.sampleRate * 2L);
            float[] out = new float[gap + have];
            System.arraycopy(f.time, f.time.length - have, out, gap, have);
            return out;
        }
    }

    /** Feeds a stream in exact 10 ms blocks; the callback gets the block and its start time in ms. */
    static final class Blocks {
        interface Sink { void block(float[] x, int off, int n, int rate, float timeMs); }

        private final GaplessTap tap = new GaplessTap();
        private float[] carry = new float[0];
        private long samples;
        private int rate = 48000;

        float nowMs() { return samples * 1000f / rate; }

        void feed(AudioEngine.Frame f, Sink sink) {
            rate = f.sampleRate;
            float[] fresh = tap.take(f);
            if (fresh.length == 0) return;
            int n = rate / 100;
            float[] buf = new float[carry.length + fresh.length];
            System.arraycopy(carry, 0, buf, 0, carry.length);
            System.arraycopy(fresh, 0, buf, carry.length, fresh.length);
            int off = 0;
            for (; off + n <= buf.length; off += n) {
                sink.block(buf, off, n, rate, samples * 1000f / rate);
                samples += n;
            }
            carry = java.util.Arrays.copyOfRange(buf, off, buf.length);
        }
    }

    /** Level in dB of block energy (mean square). */
    static float blockDb(float[] x, int off, int n) {
        double e = 0;
        for (int i = 0; i < n; i++) e += x[off + i] * x[off + i];
        return (float) (10 * Math.log10(e / n + 1e-14));
    }

    /** Level in dB (re a full-scale sine) of one frequency in a block. */
    static float toneDb(float[] x, int off, int n, double hz, int rate) {
        double p = goertzel(x, off, n, hz, rate);
        return (float) (10 * Math.log10(p / ((double) n * n / 4) + 1e-14));
    }

    /**
     * "Finger on the microphone" detector: the phone plays its own quiet carrier tone and listens to it;
     * a sudden drop means the microphone is covered. Feed it every 10 ms block.
     */
    static final class CoverDetector {
        float carrierHz;
        float baseDb = -200, levelDb = -200;
        boolean covered, heard;

        CoverDetector(float hz) { carrierHz = hz; }

        void reset() { baseDb = -200; covered = false; heard = false; }

        /** @return +1 when the microphone has just been covered, -1 when released, 0 otherwise. */
        int block(float[] x, int off, int n, int rate) {
            levelDb = toneDb(x, off, n, carrierHz, rate);
            if (!heard) {
                if (levelDb > -55) { heard = true; baseDb = levelDb; }
                return 0;
            }
            if (!covered) baseDb = levelDb > baseDb ? levelDb : baseDb * 0.995f + levelDb * 0.005f;
            if (!covered && levelDb < baseDb - 9) { covered = true; return 1; }
            if (covered && levelDb > baseDb - 4) { covered = false; return -1; }
            return 0;
        }
    }

    /** Continuous carrier tone (a whole number of periods, so it loops without clicks). */
    static float[] carrierLoop(float hz, float amp, int rate) {
        int periods = Math.round(hz);                      // one second
        int n = rate;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = (float) (amp * Math.sin(2 * Math.PI * periods * i / rate));
        return out;
    }

    /** Mixes src into dst at offset. */
    static void mixIn(float[] dst, float[] src, int off, float gain) {
        for (int i = 0; i < src.length && off + i < dst.length; i++) if (off + i >= 0) dst[off + i] += src[i] * gain;
    }

    /** A cheerful random pentatonic tune (melody + bass), duration in seconds. */
    static float[] tune(double seconds, long seed, int rate) {
        java.util.Random r = new java.util.Random(seed);
        int total = (int) (seconds * rate);
        float[] out = new float[total];
        int[] scale = {60, 62, 64, 67, 69, 72, 74, 76};
        int step = rate / 4, i = 0, note = 3;
        for (int at = 0; at < total; at += step, i++) {
            note = Math.max(0, Math.min(scale.length - 1, note + r.nextInt(5) - 2));
            addNote(out, at, Math.min(step * 2, total - at), midiToHz(scale[note]), 0.5f, rate);
            if (i % 4 == 0) addNote(out, at, Math.min(step * 4, total - at), midiToHz(scale[(i / 4) % 3 == 2 ? 3 : 0] - 24), 0.4f, rate);
        }
        int fade = rate / 50;
        for (int k = 0; k < fade && k < total; k++) out[total - 1 - k] *= (float) k / fade;
        normalize(out, 0.6f);
        return out;
    }
}
