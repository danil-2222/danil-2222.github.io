package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * The cochlea as a living spectrum analyser: each point of the basilar membrane responds to one
 * frequency (Greenwood map), high frequencies near the entrance, low ones deep in the spiral.
 */
final class CochleaLesson implements LessonView {
    private static final int SEGMENTS = 220;
    private final LessonKit k;
    private final float[] level = new float[SEGMENTS];
    private float peakHz;
    private float peakLevel;

    CochleaLesson(float dp) {
        k = new LessonKit(dp);
        java.util.Arrays.fill(level, -120);
    }

    /** Greenwood function: x = 0 at the apex (deep inside), 1 at the base (entrance). */
    static float greenwoodHz(float x) {
        return (float) (165.4 * (Math.pow(10, 2.1 * x) - 0.88));
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        for (int s = 0; s < SEGMENTS; s++) {
            float x = 1f - (float) s / (SEGMENTS - 1);         // segment 0 = base
            float lo = greenwoodHz(Math.max(0, x - 0.5f / SEGMENTS)), hi = greenwoodHz(Math.min(1, x + 0.5f / SEGMENTS));
            int i0 = Math.max(1, (int) (lo / f.binHz)), i1 = Math.min(f.spectrumDb.length - 1, Math.max(i0, (int) (hi / f.binHz)));
            float m = -140;
            for (int i = i0; i <= i1; i++) m = Math.max(m, f.spectrumDb[i] - f.calibrationDb);
            // Hair cells react fast and relax slowly.
            level[s] = m > level[s] ? m : level[s] - 1.2f;
        }
        peakHz = f.peakFreqHz;
        peakLevel = f.peakDb - f.calibrationDb;
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    /** Quiet places keep the membrane's grey colour; louder ones glow. */
    private int color(float db) {
        float t = Math.max(0, Math.min(1, (db + 100) / 75f));
        int hot = Palettes.lut(Palettes.Palette.FIRE)[(int) (t * 255)];
        float a = Math.min(1, t * 1.6f);
        int r = (int) (0x1E + (((hot >> 16) & 0xFF) - 0x1E) * a), g = (int) (0x29 + (((hot >> 8) & 0xFF) - 0x29) * a),
                bl = (int) (0x3B + ((hot & 0xFF) - 0x3B) * a);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Равлик вуха", 8 * dp, 20 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Кожне місце мембрани відгукується на свою частоту", 8 * dp, 36 * dp, 11, LessonKit.SOFT,
                Paint.Align.LEFT, false);

        // Spiral: 2.6 turns, entrance (base) outside, apex in the centre.
        float cx = w * 0.5f, cy = 62 * dp + (h * 0.62f - 62 * dp) / 2;
        float r0 = Math.min(w * 0.38f, (h * 0.62f - 80 * dp) / 2);
        float prevX = 0, prevY = 0;
        k.stroke.setStrokeWidth(13 * dp);
        k.stroke.setStrokeCap(Paint.Cap.BUTT);
        for (int s = 0; s < SEGMENTS; s++) {
            float t = (float) s / (SEGMENTS - 1);
            double th = -Math.PI / 2 + t * 2.6 * 2 * Math.PI;
            float r = r0 * (1 - 0.8f * t);
            float x = cx + (float) Math.cos(th) * r, y = cy + (float) Math.sin(th) * r;
            if (s > 0) {
                k.stroke.setColor(color(level[s]));
                c.drawLine(prevX, prevY, x, y, k.stroke);
            }
            prevX = x;
            prevY = y;
        }
        k.stroke.setStrokeCap(Paint.Cap.ROUND);
        float[] marks = {20000, 4000, 1000, 250, 50};
        for (float hz : marks) {
            float x = (float) (Math.log10(hz / 165.4 + 0.88) / 2.1);
            float t = 1 - x;
            double th = -Math.PI / 2 + t * 2.6 * 2 * Math.PI;
            float r = r0 * (1 - 0.8f * t) + 16 * dp;
            k.label(c, hz >= 1000 ? Math.round(hz / 1000) + "k" : String.valueOf(Math.round(hz)),
                    cx + (float) Math.cos(th) * r, cy + (float) Math.sin(th) * r + 4 * dp, 10, LessonKit.CYAN, Paint.Align.CENTER, true);
        }
        k.label(c, "вхід", cx, cy - r0 - 10 * dp, 9, LessonKit.LABEL, Paint.Align.CENTER, false);

        // The same membrane unrolled into a straight strip.
        float left = 8 * dp, right = w - 8 * dp, top = h * 0.68f, bottom = top + 22 * dp;
        float sw = (right - left) / SEGMENTS;
        for (int s = 0; s < SEGMENTS; s++) k.rect(c, left + s * sw, top, left + (s + 1) * sw + 0.6f, bottom, color(level[s]));
        k.label(c, "вхід · 20 кГц", left, bottom + 12 * dp, 9.5f, LessonKit.LABEL, Paint.Align.LEFT, false);
        k.label(c, "глибина · 20 Гц", right, bottom + 12 * dp, 9.5f, LessonKit.LABEL, Paint.Align.RIGHT, false);
        k.label(c, "розгорнута мембрана (≈ 3,5 см у справжньому вусі)", (left + right) / 2, top - 5 * dp, 9.5f,
                LessonKit.SOFT, Paint.Align.CENTER, false);
        if (peakLevel > -80 && peakHz > 20) {
            float x = (float) (Math.log10(peakHz / 165.4 + 0.88) / 2.1);
            float px = left + (1 - x) * (right - left);
            k.line(c, px, top - 2 * dp, px, bottom + 2 * dp, LessonKit.WHITE, 2);
            k.label(c, String.format(Locale.US, "найсильніше: %s — %.0f%% глибини", SpectrumMath.formatHz(peakHz), (1 - x) * 100),
                    w / 2f, bottom + 30 * dp, 12, LessonKit.WHITE, Paint.Align.CENTER, true);
        }
        k.label(c, "≈ 15 000 волоскових клітин перетворюють коливання на нервові сигнали", w / 2f, h - 8 * dp, 9.5f,
                LessonKit.LABEL, Paint.Align.CENTER, false);
    }
}
