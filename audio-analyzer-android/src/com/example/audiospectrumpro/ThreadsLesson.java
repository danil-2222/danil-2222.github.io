package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * A curtain of threads, each "tuned" to one pitch: only the threads that match the sound swing.
 * Sympathetic resonance — the same idea the ear uses — without the word "spectrum".
 */
final class ThreadsLesson implements LessonView {
    private static final int N = 24;
    private final LessonKit k;
    private final float[] hz = new float[N], amp = new float[N], phase = new float[N];
    private final long start = System.currentTimeMillis();
    private int loudest = -1;

    ThreadsLesson(float dp) {
        k = new LessonKit(dp);
        for (int i = 0; i < N; i++) {
            hz[i] = (float) (80 * Math.pow(5000 / 80.0, (double) i / (N - 1)));
            phase[i] = i * 1.7f;
        }
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        double[] p = new double[N];
        double max = 1e-12;
        for (int i = 0; i < N; i++) {
            p[i] = LessonKit.bandPower(f, hz[i] / 1.09f, hz[i] * 1.09f);
            max = Math.max(max, p[i]);
        }
        float level = f.rmsDb - f.calibrationDb;
        float gate = Math.max(0, Math.min(1, (level + 60) / 30));
        loudest = -1;
        float best = 0;
        for (int i = 0; i < N; i++) {
            // Only threads close to the strongest pitch get pushed hard, as in real resonance.
            float drive = (float) Math.pow(p[i] / max, 0.6) * gate;
            amp[i] += (drive - amp[i]) * (drive > amp[i] ? 0.35f : 0.06f);
            if (amp[i] > best) { best = amp[i]; loudest = i; }
        }
        if (best < 0.15f) loudest = -1;
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float t = (System.currentTimeMillis() - start) / 1000f;
        float top = 70 * dp, bottomMax = h - 70 * dp;
        float gap = (w - 30 * dp) / N;
        k.rect(c, 10 * dp, top - 8 * dp, w - 10 * dp, top, 0xFF8D6E63);
        for (int i = 0; i < N; i++) {
            float x0 = 15 * dp + gap * (i + 0.5f);
            // Long threads for low sounds, short ones for high sounds.
            float len = (bottomMax - top) * (1 - 0.75f * i / (N - 1));
            double ang = amp[i] * 0.5 * Math.sin(t * (2 + 6.0 * i / N) * 2 + phase[i]);
            float x1 = x0 + (float) Math.sin(ang) * len, y1 = top + (float) Math.cos(ang) * len;
            int col = i == loudest ? LessonKit.YELLOW : blend(0xFF475569, LessonKit.CYAN, Math.min(1, amp[i] * 1.5f));
            k.line(c, x0, top, x1, y1, col, 1.5f + amp[i] * 2);
            k.fill.setColor(col);
            c.drawCircle(x1, y1, (5 + amp[i] * 5) * dp, k.fill);
        }
        k.label(c, "Завіса з ниточок", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Кожна ниточка «слухає» свою висоту звуку", 8 * dp, 40 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
        String msg;
        if (loudest < 0) msg = "Тихо… свисніть, заспівайте або загудіть";
        else if (loudest < N / 4) msg = "Гойдаються довгі ниточки — це низький звук";
        else if (loudest < N * 2 / 3) msg = "Гойдаються середні — як голос людини";
        else msg = "Гойдаються короткі — це високий звук, як свист";
        k.label(c, msg, w / 2f, h - 40 * dp, 15, loudest < 0 ? LessonKit.SOFT : LessonKit.YELLOW, Paint.Align.CENTER, true);
        k.label(c, "довгі — низькі", 12 * dp, h - 14 * dp, 11, LessonKit.LABEL, Paint.Align.LEFT, false);
        k.label(c, "короткі — високі", w - 12 * dp, h - 14 * dp, 11, LessonKit.LABEL, Paint.Align.RIGHT, false);
    }

    static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) + ((((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t));
        int g = (int) (((a >> 8) & 0xFF) + ((((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t));
        int bl = (int) ((a & 0xFF) + (((b & 0xFF) - (a & 0xFF)) * t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
