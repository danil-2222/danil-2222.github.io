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
}
