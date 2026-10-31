package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Voice plane: the higher you sing, the higher it flies; fly through the gaps between the mountains. */
final class PlaneGame implements LessonView {
    static final float PLANE_X = 0.25f, SPEED = 0.16f, GAP = 0.36f, SPAN = 7;   // SPAN: semitones above/below the centre

    int centerMidi = 52;
    private final LessonKit k;
    private final Random rnd = new Random();
    private final List<float[]> walls = new ArrayList<>();   // {x, gapCentre, passed}
    private float y = 0.5f, target = Float.NaN, clock, nextWall;
    private boolean running, crashed;
    private int score, best;

    PlaneGame(float dp) { k = new LessonKit(dp); }

    void start() {
        walls.clear();
        y = 0.5f;
        score = 0;
        clock = 0;
        nextWall = 1.5f;
        crashed = false;
        running = true;
    }

    int score() { return score; }
    boolean crashed() { return crashed; }
    float planeY() { return y; }

    /** Gap centre of the next wall ahead of the plane, or 0.5. */
    float nextGap() {
        for (float[] w : walls) if (w[0] > PLANE_X - 0.02f) return w[1];
        return 0.5f;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float dt = (float) AudioEngine.HOP / f.sampleRate;
        float hz = f.rmsDb - f.calibrationDb > -50 ? LessonRenderer.yin(f.time, f.sampleRate) : 0;
        if (hz > 0) {
            float midi = (float) (69 + 12 * Math.log(hz / 440.0) / Math.log(2));
            target = Math.max(0.03f, Math.min(0.97f, 0.5f + (midi - centerMidi) / (2 * SPAN)));
        } else {
            target = Float.NaN;
        }
        if (!running || crashed) return;
        clock += dt;
        y = Float.isNaN(target) ? Math.max(0, y - 0.25f * dt) : y + (target - y) * Math.min(1, dt * 6);
        if (clock >= nextWall) {
            walls.add(new float[]{1.1f, 0.25f + rnd.nextFloat() * 0.5f, 0});
            nextWall = clock + 1.9f;
        }
        for (int i = walls.size() - 1; i >= 0; i--) {
            float[] w = walls.get(i);
            w[0] -= SPEED * dt;
            if (w[0] < -0.1f) { walls.remove(i); continue; }
            if (Math.abs(w[0] - PLANE_X) < 0.035f && Math.abs(y - w[1]) > GAP / 2) crashed = true;
            if (w[2] == 0 && w[0] < PLANE_X - 0.035f) { w[2] = 1; score++; best = Math.max(best, score); }
        }
        if (y <= 0) crashed = true;
        if (crashed) running = false;
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float top = 50 * dp, bottom = h - 40 * dp, hh = bottom - top;
        k.rect(c, 0, top, w, bottom, 0xFF4FC3F7);
        for (float[] wl : walls) {
            float x = wl[0] * w, gTop = bottom - (wl[1] + GAP / 2) * hh, gBot = bottom - (wl[1] - GAP / 2) * hh;
            k.rect(c, x - 0.035f * w, top, x + 0.035f * w, gTop, 0xFF6D4C41);
            k.rect(c, x - 0.035f * w, gBot, x + 0.035f * w, bottom, 0xFF6D4C41);
        }
        float px = PLANE_X * w, py = bottom - y * hh;
        if (!Float.isNaN(target)) {
            k.fill.setColor(0x88FFFFFF);
            c.drawCircle(px + 30 * dp, bottom - target * hh, 4 * dp, k.fill);
        }
        k.label(c, crashed ? "💥" : "✈️", px, py + 10 * dp, 30, LessonKit.WHITE, Paint.Align.CENTER, false);
        k.label(c, "Голосовий літачок", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Співайте вище — летить вгору, нижче — вниз, тиша — падає", 8 * dp, 40 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
        String msg = !running && !crashed ? "Натисніть «Старт» і тягніть «у-у-у»" : crashed ? "Аварія! Пролетіли: " + score
                : "Пролетіли: " + score;
        k.label(c, msg + "   (рекорд " + best + ")", w / 2f, h - 14 * dp, 15, crashed ? LessonKit.PINK : LessonKit.YELLOW,
                Paint.Align.CENTER, true);
    }
}
