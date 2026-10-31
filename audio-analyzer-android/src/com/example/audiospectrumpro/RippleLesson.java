package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/** Sound as rings on water seen from above: low sounds make wide rings, high sounds dense ones. */
final class RippleLesson implements LessonView {
    private final LessonKit k;
    private final List<float[]> rings = new ArrayList<>();   // {radius, strength, hue}
    private float hz = 0, level;
    private float sinceEmit;
    private float dp;

    RippleLesson(float dp) {
        k = new LessonKit(dp);
        this.dp = dp;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        level = Math.max(0, Math.min(1, (f.rmsDb - f.calibrationDb + 60) / 45));
        float p = LessonRenderer.yin(f.time, f.sampleRate);
        if (p <= 0 && f.peakDb - f.calibrationDb > -70) p = f.peakFreqHz;
        if (p > 0) hz = hz <= 0 ? p : hz * 0.7f + p * 0.3f;
        // Rings move with the sound clock (one frame = one hop), not with screen redraws.
        float dt = (float) AudioEngine.HOP / f.sampleRate;
        float speed = 90 * dp;
        float spacing = hz > 0 ? Math.max(8 * dp, Math.min(200 * dp, 343f / hz * 60 * dp)) : 120 * dp;
        synchronized (rings) {
            for (float[] r : rings) r[0] += dt * speed;
            sinceEmit += dt * speed;
            if (level > 0.05f && sinceEmit >= spacing) {
                sinceEmit = 0;
                rings.add(new float[]{0, level, hz});
            }
        }
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        // Spacing between rings stands for the wavelength: 343 / f, drawn 1 m = 60 dp.
        float cx = w / 2f, cy = h * 0.47f, max = Math.max(w, h) * 0.7f;
        c.drawColor(0xFF0D3B66);
        synchronized (rings) {
        for (int i = rings.size() - 1; i >= 0; i--) {
            float[] r = rings.get(i);
            if (r[0] > max) { rings.remove(i); continue; }
            float fade = 1 - r[0] / max;
            k.stroke.setColor(((int) (255 * fade * Math.min(1, r[1] * 1.5f)) << 24) | 0xBBDEFB);
            k.stroke.setStrokeWidth((1 + 5 * r[1]) * dp);
            c.drawCircle(cx, cy, r[0], k.stroke);
        }
        }
        k.fill.setColor(0xFF90A4AE);
        c.drawCircle(cx, cy, 7 * dp, k.fill);
        k.label(c, "Камінь у воді", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Звук розходиться кругами, як хвилі від каменя", 8 * dp, 40 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
        String msg = level < 0.05f ? "Тихо — вода спокійна. Скажіть щось!"
                : hz < 200 ? "Низький звук — рідкі широкі хвилі"
                : hz < 1000 ? "Середній звук — хвилі середні"
                : "Високий звук — часті дрібні хвилі";
        k.label(c, msg, w / 2f, h - 34 * dp, 15, LessonKit.WHITE, Paint.Align.CENTER, true);
        if (hz > 0 && level > 0.05f) {
            float lam = 343f / hz;
            k.label(c, lam >= 1 ? String.format(java.util.Locale.US, "відстань між хвилями в повітрі: %.1f м", lam)
                            : String.format(java.util.Locale.US, "відстань між хвилями в повітрі: %.0f см", lam * 100),
                    w / 2f, h - 14 * dp, 11, LessonKit.SOFT, Paint.Align.CENTER, false);
        }
    }
}
