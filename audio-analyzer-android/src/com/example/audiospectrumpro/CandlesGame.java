package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/** Blow out the candles: blowing into the microphone is loud, noisy and has no pitch. */
final class CandlesGame implements LessonView {
    static final int CANDLES = 8;
    private final LessonKit k;
    private int lit = CANDLES;
    private float blowMs, totalMs, blow;
    private boolean won;
    private final long start = System.currentTimeMillis();

    CandlesGame(float dp) { k = new LessonKit(dp); }

    void reset() { lit = CANDLES; blowMs = 0; totalMs = 0; won = false; }

    int lit() { return lit; }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float dt = 1000f * AudioEngine.HOP / f.sampleRate;
        float level = f.rmsDb - f.calibrationDb;
        // A blow: loud, broadband (no clear pitch) and with lots of low "rumble" from the air hitting the mic.
        boolean noisy = LessonRenderer.yin(f.time, f.sampleRate) <= 0;
        double low = LessonKit.bandPower(f, 20, 500), all = LessonKit.bandPower(f, 20, 16000) + 1e-20;
        boolean isBlow = level > -42 && noisy && low / all > 0.2;
        blow += ((isBlow ? Math.min(1, (level + 42) / 25) : 0) - blow) * 0.3f;
        if (won || lit == 0) return;
        totalMs += dt;
        if (isBlow) {
            blowMs += dt * (0.6f + blow);
            if (blowMs > 280) { blowMs = 0; lit--; }
            if (lit == 0) won = true;
        } else {
            blowMs = Math.max(0, blowMs - dt * 0.5f);
        }
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float t = (System.currentTimeMillis() - start) / 1000f;
        k.label(c, "Задуй свічки", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Дмухайте прямо в мікрофон (внизу телефона)", 8 * dp, 40 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
        float cakeTop = h * 0.55f, cakeL = w * 0.12f, cakeR = w * 0.88f;
        k.fill.setColor(0xFF8D6E63);
        c.drawRoundRect(new RectF(cakeL, cakeTop, cakeR, h * 0.8f), 14 * dp, 14 * dp, k.fill);
        k.fill.setColor(0xFFF8BBD0);
        c.drawRoundRect(new RectF(cakeL - 4 * dp, cakeTop - 6 * dp, cakeR + 4 * dp, cakeTop + 16 * dp), 10 * dp, 10 * dp, k.fill);
        float gap = (cakeR - cakeL) / CANDLES;
        for (int i = 0; i < CANDLES; i++) {
            float x = cakeL + gap * (i + 0.5f), top = cakeTop - 50 * dp;
            k.rect(c, x - 5 * dp, top, x + 5 * dp, cakeTop - 4 * dp, i % 2 == 0 ? 0xFF90CAF9 : 0xFFFFF59D);
            boolean on = i >= CANDLES - lit;
            if (on) {
                float lean = blow * 10 * dp, flick = (float) Math.sin(t * 20 + i) * (1 + 3 * blow) * dp;
                k.fill.setColor(0xFFFFA000);
                c.drawOval(new RectF(x - 6 * dp + lean, top - 22 * dp + flick, x + 6 * dp + lean, top), k.fill);
                k.fill.setColor(0xFFFFF176);
                c.drawOval(new RectF(x - 3 * dp + lean, top - 14 * dp + flick, x + 3 * dp + lean, top - 2 * dp), k.fill);
            } else {
                k.line(c, x, top, x + (float) Math.sin(t + i) * 6 * dp, top - 20 * dp, 0x66B0BEC5, 2);
            }
        }
        String msg = won ? String.format(Locale.US, "З днем народження! 🎉 Усі свічки за %.1f с", totalMs / 1000)
                : blow > 0.2f ? "Дмухайте, дмухайте!" : "Залишилось свічок: " + lit;
        k.label(c, msg, w / 2f, h - 40 * dp, 17, won ? LessonKit.GREEN : LessonKit.YELLOW, Paint.Align.CENTER, true);
        k.label(c, "Свист чи голос не допоможуть — потрібен саме видих", w / 2f, h - 16 * dp, 11, LessonKit.LABEL, Paint.Align.CENTER, false);
    }
}
