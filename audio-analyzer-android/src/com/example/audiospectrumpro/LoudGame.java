package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Who is loudest: each player gets 3 seconds; the loudest 50 ms of the turn counts. */
final class LoudGame implements LessonView {
    static final float TURN_MS = 3000;
    private final LessonKit k;
    private final List<Float> results = new ArrayList<>();
    private final LessonKit.SampleTap tap = new LessonKit.SampleTap();
    private boolean measuring;
    private float turnMs, peak = -120, now = -120;
    private float[] carry = new float[0];

    LoudGame(float dp) { k = new LessonKit(dp); }

    void newGame() { results.clear(); measuring = false; }
    void startTurn() { measuring = true; turnMs = 0; peak = -120; }
    List<Float> results() { return results; }
    boolean measuring() { return measuring; }

    int winner() {
        int best = -1;
        for (int i = 0; i < results.size(); i++) if (best < 0 || results.get(i) > results.get(best)) best = i;
        return best;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float[] fresh = tap.take(f);
        int n = Math.max(64, f.sampleRate / 20);                  // 50 ms windows, longer than one frame
        float[] buf = new float[carry.length + fresh.length];
        System.arraycopy(carry, 0, buf, 0, carry.length);
        System.arraycopy(fresh, 0, buf, carry.length, fresh.length);
        int off = 0;
        for (; off + n <= buf.length; off += n) {
            double e = 0;
            for (int i = 0; i < n; i++) e += buf[off + i] * buf[off + i];
            now = (float) (10 * Math.log10(e / n + 1e-14));
            if (measuring) {
                peak = Math.max(peak, now);
                turnMs += 50;
                if (turnMs >= TURN_MS) {
                    measuring = false;
                    results.add(peak);
                }
            }
        }
        carry = java.util.Arrays.copyOfRange(buf, off, buf.length);
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    /** A friendly 0..100 "power" from dBFS. */
    static int power(float db) { return Math.max(0, Math.min(100, Math.round((db + 60) * 100 / 60))); }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Хто гучніший?", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "По черзі: кожен має 3 секунди, щоб крикнути найгучніше", 8 * dp, 40 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
        float bx = w / 2f, by = h * 0.36f;
        if (measuring) {
            k.label(c, String.format(Locale.US, "Гравець %d — КРИЧІТЬ! %.1f с", results.size() + 1, (TURN_MS - turnMs) / 1000), bx, by, 18,
                    LessonKit.YELLOW, Paint.Align.CENTER, true);
            k.label(c, String.valueOf(power(peak)), bx, by + 50 * dp, 44, LessonKit.CYAN, Paint.Align.CENTER, true);
        } else {
            k.label(c, results.isEmpty() ? "Натисніть «Хід гравця 1»" : "Наступний гравець — натисніть кнопку", bx, by, 15,
                    LessonKit.SOFT, Paint.Align.CENTER, true);
        }
        int win = winner();
        float y = h * 0.58f;
        for (int i = 0; i < results.size(); i++) {
            int p = power(results.get(i));
            float rowY = y + i * 26 * dp;
            k.label(c, (i == win ? "🏆 " : "") + "Гравець " + (i + 1), 16 * dp, rowY, 14, i == win ? LessonKit.YELLOW : LessonKit.WHITE,
                    Paint.Align.LEFT, i == win);
            k.rect(c, w * 0.42f, rowY - 12 * dp, w * 0.42f + (w * 0.45f) * p / 100f, rowY, i == win ? LessonKit.YELLOW : LessonKit.CYAN);
            k.label(c, String.valueOf(p), w - 12 * dp, rowY, 13, LessonKit.SOFT, Paint.Align.RIGHT, false);
        }
    }
}
