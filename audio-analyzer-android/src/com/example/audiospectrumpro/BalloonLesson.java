package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/** Loudness as a balloon: a whisper barely inflates it, a shout pops it. */
final class BalloonLesson implements LessonView {
    private final LessonKit k;
    private float size = 0.1f, popped;
    private String word = "";

    BalloonLesson(float dp) { k = new LessonKit(dp); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float db = f.rmsDb - f.calibrationDb;
        // The radius follows the sound pressure: +6 dB doubles it.
        float target = (float) Math.max(0.08, Math.min(1.2, Math.pow(10, (db + 18) / 20.0 * 0.75) * 0.5));
        if (popped > 0) { popped -= 0.03f; size = 0.08f; return; }
        size += (target - size) * (target > size ? 0.3f : 0.08f);
        if (size > 1.05f) { popped = 1; size = 0.08f; }
        word = db < -55 ? "тиша" : db < -40 ? "шепіт" : db < -25 ? "розмова" : db < -12 ? "голосно" : "крик!";
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float cx = w / 2f, base = h * 0.82f;
        float maxR = Math.min(w * 0.42f, h * 0.33f);
        float r = Math.max(10 * dp, size * maxR);
        float cy = base - 60 * dp - r;
        k.line(c, cx, cy + r * 1.15f, cx, base, 0xFFB0BEC5, 1.5f);
        if (popped > 0) {
            k.label(c, "БАХ!", cx, cy, 44, LessonKit.YELLOW, Paint.Align.CENTER, true);
            k.label(c, "Занадто гучно — кулька луснула", cx, cy + 34 * dp, 13, LessonKit.SOFT, Paint.Align.CENTER, false);
        } else {
            k.fill.setColor(0xFFE53935);
            c.drawOval(new RectF(cx - r * 0.9f, cy - r, cx + r * 0.9f, cy + r * 1.1f), k.fill);
            k.fill.setColor(0x55FFFFFF);
            c.drawOval(new RectF(cx - r * 0.5f, cy - r * 0.75f, cx - r * 0.15f, cy - r * 0.25f), k.fill);
        }
        k.label(c, "Повітряна кулька", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Що гучніше — то більша кулька. Не переборщіть!", 8 * dp, 40 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
        k.label(c, word, cx, h - 30 * dp, 22, LessonKit.CYAN, Paint.Align.CENTER, true);
        k.label(c, "кожні +10 дБ — це вдесятеро більше енергії звуку", cx, h - 8 * dp, 10.5f, LessonKit.LABEL,
                Paint.Align.CENTER, false);
    }
}
