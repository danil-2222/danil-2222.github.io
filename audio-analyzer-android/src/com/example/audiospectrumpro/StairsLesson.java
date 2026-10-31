package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/** Pitch as a ball on a staircase of notes: sing higher and it climbs; off-key it hangs between steps. */
final class StairsLesson implements LessonView {
    private static final String[] NAMES = {"до", "ре", "мі", "фа", "соль", "ля", "сі", "до"};
    private static final int[] STEPS = {0, 2, 4, 5, 7, 9, 11, 12};
    private final LessonKit k;
    private float pos = Float.NaN;           // 0..7 along the steps, fractional between notes
    private int baseMidi = 48;               // C3, shifted to the octave the voice is in
    private int onStep = -1;
    private final long start = System.currentTimeMillis();

    StairsLesson(float dp) { k = new LessonKit(dp); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float hz = f.rmsDb - f.calibrationDb > -55 ? LessonRenderer.yin(f.time, f.sampleRate) : 0;
        if (hz <= 0) { onStep = -1; return; }
        float midi = (float) (69 + 12 * Math.log(hz / 440.0) / Math.log(2));
        // Keep the staircase in the singer's octave.
        while (midi < baseMidi - 1) baseMidi -= 12;
        while (midi > baseMidi + 13) baseMidi += 12;
        float rel = midi - baseMidi;
        // Position on the staircase: interpolate between the scale steps.
        float p = 0;
        for (int i = 0; i < STEPS.length - 1; i++) {
            if (rel >= STEPS[i] && rel <= STEPS[i + 1]) { p = i + (rel - STEPS[i]) / (STEPS[i + 1] - STEPS[i]); break; }
            if (rel > STEPS[STEPS.length - 1]) p = STEPS.length - 1;
        }
        pos = Float.isNaN(pos) ? p : pos * 0.6f + p * 0.4f;
        int nearest = Math.round(pos);
        onStep = Math.abs(pos - nearest) < 0.15f ? nearest : -1;
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        int n = STEPS.length;
        float left = 16 * dp, bottom = h - 40 * dp, stepW = (w - 32 * dp) / n, stepH = (h - 150 * dp) / n;
        for (int i = 0; i < n; i++) {
            float x0 = left + i * stepW, top = bottom - (i + 1) * stepH;
            k.rect(c, x0, top, x0 + stepW - 2 * dp, bottom, i == onStep ? 0xFF2E7D32 : 0xFF1E293B);
            k.label(c, NAMES[i], x0 + stepW / 2, top + 18 * dp, 13, i == onStep ? LessonKit.WHITE : LessonKit.SOFT, Paint.Align.CENTER, true);
        }
        if (!Float.isNaN(pos)) {
            float t = (System.currentTimeMillis() - start) / 1000f;
            float bounce = onStep >= 0 ? (float) Math.abs(Math.sin(t * 6)) * 14 * dp : 0;
            float x = left + (pos + 0.5f) * stepW, y = bottom - (pos + 1) * stepH - 14 * dp - bounce;
            k.fill.setColor(onStep >= 0 ? LessonKit.YELLOW : LessonKit.PINK);
            c.drawCircle(x, y, 13 * dp, k.fill);
        }
        k.label(c, "М'ячик на сходах", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Співайте «до-ре-мі…» — м'ячик стрибає сходинками", 8 * dp, 40 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
        String msg = Float.isNaN(pos) ? "Заспівайте будь-яку ноту" : onStep >= 0 ? "Влучили в ноту «" + NAMES[onStep] + "»!"
                : "Між сходинками — трохи не в ноту";
        k.label(c, msg, w / 2f, h - 12 * dp, 16, onStep >= 0 ? LessonKit.GREEN : LessonKit.YELLOW, Paint.Align.CENTER, true);
    }
}
