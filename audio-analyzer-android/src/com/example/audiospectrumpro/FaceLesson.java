package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/** A cartoon face reacting to sound: pitch raises the eyebrows, loudness opens the mouth, a clean tone smiles. */
final class FaceLesson implements LessonView {
    private final LessonKit k;
    private float brows = 0.5f, mouth = 0, smile = 0, squint = 0;
    private boolean asleep = true;
    private float lastLevel = -100;
    private String mood = "";
    private final long start = System.currentTimeMillis();

    FaceLesson(float dp) { k = new LessonKit(dp); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float level = f.rmsDb - f.calibrationDb;
        asleep = level < -62;
        float hz = LessonRenderer.yin(f.time, f.sampleRate);
        SoundExplainer.Result r = SoundExplainer.explain(f, 440);
        boolean clean = r.kind == SoundExplainer.Kind.PURE_TONE || r.kind == SoundExplainer.Kind.HARMONIC;
        float tBrows = hz > 0 ? (float) Math.max(0, Math.min(1, Math.log(hz / 80) / Math.log(1500 / 80.0))) : 0.4f;
        float tMouth = Math.max(0, Math.min(1, (level + 55) / 45));
        float tSmile = asleep ? 0 : clean ? 1 : -1;
        // A sudden jump in loudness makes the face wince.
        if (level - lastLevel > 18) squint = 1;
        squint *= 0.9f;
        lastLevel = level;
        brows += (tBrows - brows) * 0.25f;
        mouth += (tMouth - mouth) * 0.35f;
        smile += (tSmile - smile) * 0.2f;
        if (asleep) mood = "Сплю… тиша";
        else if (squint > 0.5f) mood = "Ой! Різкий звук";
        else if (smile > 0.4f) mood = brows > 0.6f ? "Чистий високий звук — подобається!" : "Рівний спокійний тон — приємно";
        else mood = mouth > 0.6f ? "Гучний шум — кривлюся" : "Шум — не дуже приємно";
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float cx = w / 2f, cy = h * 0.46f, r = Math.min(w, h) * 0.33f;
        k.fill.setColor(0xFFFFD54F);
        c.drawCircle(cx, cy, r, k.fill);
        k.stroke.setColor(0xFF5D4037);
        k.stroke.setStrokeWidth(4 * dp);
        float eyeY = cy - r * 0.25f, eyeDx = r * 0.38f;
        float t = (System.currentTimeMillis() - start) / 1000f;
        boolean blink = (t % 4) < 0.12f;
        for (int s = -1; s <= 1; s += 2) {
            float ex = cx + s * eyeDx;
            if (asleep || blink || squint > 0.5f) {
                c.drawLine(ex - r * 0.12f, eyeY, ex + r * 0.12f, eyeY, k.stroke);
            } else {
                k.fill.setColor(0xFF3E2723);
                c.drawCircle(ex, eyeY, r * (0.08f + 0.03f * brows), k.fill);
            }
            // Eyebrows: high pitch raises them, low pitch lowers them.
            float by = eyeY - r * (0.16f + 0.22f * brows);
            // Low sounds and noise pull the inner ends of the brows down (a frown).
            float frown = Math.max(0, Math.max(0.45f - brows, -smile * 0.5f)) * r * 0.25f;
            float inner = ex - s * r * 0.15f, outer = ex + s * r * 0.15f;
            c.drawLine(outer, by, inner, by + frown, k.stroke);
        }
        // Mouth: loudness opens it; a clean tone smiles, noise makes it wavy.
        float my = cy + r * 0.38f, mw = r * 0.42f;
        if (mouth > 0.15f && !asleep) {
            k.fill.setColor(0xFF6D1B1B);
            c.drawOval(new RectF(cx - mw * (0.6f + 0.4f * mouth), my - r * 0.3f * mouth, cx + mw * (0.6f + 0.4f * mouth), my + r * 0.3f * mouth), k.fill);
        }
        k.path.reset();
        int steps = 24;
        for (int i = 0; i <= steps; i++) {
            float u = (float) i / steps * 2 - 1;
            float y = my + smile * r * 0.18f * (1 - u * u);
            if (smile < 0) y += (float) Math.sin(u * 9 + t * 12) * r * 0.03f * -smile;
            if (i == 0) k.path.moveTo(cx + u * mw, y); else k.path.lineTo(cx + u * mw, y);
        }
        c.drawPath(k.path, k.stroke);
        if (asleep) k.label(c, "z z z", cx + r * 0.9f, cy - r * 0.8f, 20, LessonKit.SOFT, Paint.Align.CENTER, true);

        k.label(c, "Обличчя звуку", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, mood, cx, h - 46 * dp, 17, LessonKit.CYAN, Paint.Align.CENTER, true);
        k.label(c, "брови — висота · рот — гучність · усмішка — чистота звуку", cx, h - 18 * dp, 11, LessonKit.LABEL, Paint.Align.CENTER, false);
    }
}
