package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * Hot and cold: one phone hides and beeps at 3 kHz; the other listens for that beep only and turns
 * its loudness into a temperature.
 */
final class HotColdGame implements LessonView {
    static final float BEEP_HZ = 3000;
    boolean beacon;
    private final LessonKit k;
    private float floorDb = -100, heat, peakHold = -140;
    private int holdFrames;

    HotColdGame(float dp) { k = new LessonKit(dp); }

    float heat() { return heat; }

    /** Repeating short beep for the hidden phone. */
    static float[] beep(int rate) {
        float[] pcm = new float[(int) (rate * 0.5f)];
        LessonKit.addTone(pcm, 0, (int) (rate * 0.12f), BEEP_HZ, 0.9f, rate, rate / 200);
        return pcm;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (beacon) return;
        double p = LessonKit.bandPower(f, BEEP_HZ - 120, BEEP_HZ + 120);
        float db = (float) (10 * Math.log10(p + 1e-20));
        // Beeps come and go: hold the strongest level for half a second.
        if (db > peakHold || --holdFrames <= 0) {
            peakHold = db;
            holdFrames = (int) (0.5f * f.sampleRate / AudioEngine.HOP);
        }
        double around = LessonKit.bandPower(f, 1500, 2700) + LessonKit.bandPower(f, 3300, 4500);
        floorDb = Math.min(floorDb + 0.05f, (float) (10 * Math.log10(around / 8 + 1e-20)));
        // Absolute scale: about -70 dB (far away) .. -15 dB (right next to it), and only when the beep
        // stands clearly above the neighbouring frequencies (so talking does not count).
        float target = Math.max(0, Math.min(1, (peakHold - Math.max(floorDb + 6, -70)) / 55));
        heat += (target - heat) * 0.2f;
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Тепло — холодно", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        if (beacon) {
            k.label(c, "Цей телефон — маячок 📡", w / 2f, h * 0.4f, 20, LessonKit.CYAN, Paint.Align.CENTER, true);
            k.label(c, "Сховайте його — він тихо пищить. Шукайте іншим телефоном", w / 2f, h * 0.4f + 26 * dp, 12, LessonKit.SOFT,
                    Paint.Align.CENTER, false);
            return;
        }
        k.label(c, "Ходіть із телефоном — він чує лише писк маячка", 8 * dp, 40 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
        float cx = w / 2f, top = h * 0.18f, bottom = h * 0.72f, tw = 26 * dp;
        k.fill.setColor(0xFF263238);
        c.drawRoundRect(new RectF(cx - tw, top, cx + tw, bottom), tw, tw, k.fill);
        int col = ThreadsLesson.blend(0xFF29B6F6, 0xFFFF1744, heat);
        k.fill.setColor(col);
        c.drawRoundRect(new RectF(cx - tw * 0.6f, bottom - (bottom - top - 10 * dp) * Math.max(0.05f, heat), cx + tw * 0.6f, bottom - 5 * dp),
                tw * 0.6f, tw * 0.6f, k.fill);
        c.drawCircle(cx, bottom + tw * 0.4f, tw * 1.1f, k.fill);
        String word = heat < 0.1f ? "Крижано 🥶" : heat < 0.3f ? "Холодно" : heat < 0.5f ? "Тепліше" : heat < 0.7f ? "Тепло"
                : heat < 0.9f ? "Гаряче!" : "ОПІК! 🔥 Знайшли!";
        k.label(c, word, cx, h - 30 * dp, 22, col, Paint.Align.CENTER, true);
    }
}
