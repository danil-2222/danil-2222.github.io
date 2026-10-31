package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/** Don't wake the dragon: tap to steal coins while staying quiet; any noise wakes it. */
final class DragonGame implements LessonView {
    private final LessonKit k;
    private float floorDb = -60, noise;     // 0..1 how close to waking
    private boolean awake;
    private int coins, best;
    private float calmMs;
    private final long start = System.currentTimeMillis();

    DragonGame(float dp) { k = new LessonKit(dp); }

    void reset() { awake = false; coins = 0; noise = 0; calmMs = 0; }
    int coins() { return coins; }
    boolean awake() { return awake; }

    /** Taking a coin is allowed only while the dragon sleeps. */
    void steal() {
        if (awake || calmMs < 1500) return;
        coins++;
        best = Math.max(best, coins);
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float dt = 1000f * AudioEngine.HOP / f.sampleRate;
        float db = f.rmsDb - f.calibrationDb;
        if (awake) return;
        calmMs += dt;
        // Learn the room's quiet level during the first second and while it stays calm.
        if (calmMs <= dt * 1.5f) floorDb = db;
        else if (calmMs < 1000) floorDb = floorDb * 0.8f + db * 0.2f;
        else if (db < floorDb + 6) floorDb = floorDb * 0.995f + db * 0.005f;
        floorDb = Math.max(-80, floorDb);
        float over = db - (floorDb + 6);
        noise += (Math.max(0, Math.min(1, over / 12)) - noise) * 0.4f;
        if (calmMs > 1500 && over > 12) awake = true;
    }

    @Override public boolean animating() { return true; }

    @Override
    public boolean onTouch(MotionEvent e, int w, int h) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) steal();
        return true;
    }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float t = (System.currentTimeMillis() - start) / 1000f;
        k.label(c, "Не розбуди дракона", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Торкайтеся екрана, щоб вкрасти монету. Тихо!", 8 * dp, 40 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
        float cx = w / 2f, cy = h * 0.45f, s = Math.min(w, h) * 0.25f;
        // Treasure.
        for (int i = 0; i < 12; i++) {
            k.fill.setColor(0xFFFFC107);
            c.drawOval(new RectF(cx - s + i * s * 0.17f, cy + s * 0.75f, cx - s + i * s * 0.17f + s * 0.25f, cy + s * 0.9f), k.fill);
        }
        float breathe = (float) Math.sin(t * (awake ? 6 : 1.2f)) * 3 * dp;
        k.fill.setColor(awake ? 0xFFC62828 : 0xFF2E7D32);
        c.drawOval(new RectF(cx - s, cy - s * 0.4f - breathe, cx + s * 0.6f, cy + s * 0.8f), k.fill);   // body
        c.drawCircle(cx + s * 0.75f, cy - s * 0.1f, s * 0.42f, k.fill);                                  // head
        k.path.reset();                                                                                   // spikes
        for (int i = 0; i < 5; i++) {
            float x = cx - s * 0.8f + i * s * 0.3f;
            k.path.moveTo(x, cy - s * 0.35f - breathe);
            k.path.lineTo(x + s * 0.12f, cy - s * 0.65f - breathe);
            k.path.lineTo(x + s * 0.24f, cy - s * 0.35f - breathe);
        }
        k.fill.setColor(0xFF1B5E20);
        c.drawPath(k.path, k.fill);
        float ex = cx + s * 0.85f, ey = cy - s * 0.2f;
        if (awake) {
            k.fill.setColor(0xFFFFEB3B);
            c.drawCircle(ex, ey, s * 0.1f, k.fill);
            k.fill.setColor(0xFF000000);
            c.drawCircle(ex, ey, s * 0.04f, k.fill);
            k.label(c, "🔥", cx + s * 1.3f, cy + s * 0.05f, 34, LessonKit.WHITE, Paint.Align.CENTER, false);
        } else {
            k.line(c, ex - s * 0.1f, ey, ex + s * 0.1f, ey, 0xFF000000, 3);
            k.label(c, noise > 0.4f ? "?!" : "z z z", cx + s * 1.1f, cy - s * 0.6f, noise > 0.4f ? 26 : 18,
                    noise > 0.4f ? LessonKit.YELLOW : LessonKit.SOFT, Paint.Align.CENTER, true);
        }
        // Noise meter.
        float my = h * 0.80f, mw = w * 0.4f;
        k.rect(c, cx - mw, my, cx + mw, my + 10 * dp, LessonKit.GRID);
        k.rect(c, cx - mw, my, cx - mw + 2 * mw * noise, my + 10 * dp, noise > 0.6f ? LessonKit.PINK : LessonKit.GREEN);
        k.label(c, "шум", cx - mw, my - 4 * dp, 10, LessonKit.LABEL, Paint.Align.LEFT, false);
        String msg = awake ? "Дракон прокинувся! Монет: " + coins : calmMs < 1500 ? "Прислухаюсь до тиші…" : "Монет: " + coins + "   (рекорд " + best + ")";
        k.label(c, msg, cx, h - 22 * dp, 17, awake ? LessonKit.PINK : LessonKit.YELLOW, Paint.Align.CENTER, true);
    }
}
