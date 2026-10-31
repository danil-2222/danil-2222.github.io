package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Voice shooting gallery: the height of your voice moves the sight up and down; targets fly across at
 * different heights. Hold the sight on a target for 0.4 s to hit it. Trains singing a pitch you see.
 */
final class VoiceShooterGame implements LessonView {
    enum State { READY, PLAYING, OVER }

    static final int TARGETS = 12;
    static final float WINDOW_S = 4f, HOLD_S = 0.4f, LEAD_S = 2f;
    /** Voice ranges (MIDI): low voices and high/children's voices. */
    static final float[][] RANGES = {{45, 67}, {55, 79}};

    int range = 1;
    private final LessonKit k;
    private State state = State.READY;
    private float clock, startAt, hold;
    private float[] targets = new float[TARGETS];     // MIDI height of each target
    private final boolean[] hit = new boolean[TARGETS];
    private float sight = Float.NaN, sightSmooth = Float.NaN;
    private int score;

    VoiceShooterGame(float dp) { k = new LessonKit(dp); }

    static float[] targets(long seed, float lo, float hi) {
        java.util.Random r = new java.util.Random(seed);
        float[] t = new float[TARGETS];
        for (int i = 0; i < TARGETS; i++) t[i] = lo + 2 + r.nextInt((int) (hi - lo - 4));
        return t;
    }

    void start() { start(System.nanoTime()); }

    void start(long seed) {
        targets = targets(seed, RANGES[range][0], RANGES[range][1]);
        java.util.Arrays.fill(hit, false);
        score = 0;
        hold = 0;
        startAt = clock;
        state = State.PLAYING;
    }

    String state() { return state.name(); }
    int score() { return score; }
    boolean[] hits() { return hit; }

    /** Index of the target on screen now, or -1 during the lead-in. */
    int current() {
        float t = clock - startAt - LEAD_S;
        return t < 0 ? -1 : (int) (t / WINDOW_S);
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float dt = (float) AudioEngine.HOP / f.sampleRate;
        clock += dt;
        float hz = f.rmsDb - f.calibrationDb > -50 ? LessonRenderer.yin(f.time, f.sampleRate) : 0;
        sight = hz > 60 ? MelodyTools.hzToMidi(hz) : Float.NaN;
        // The sight follows octaves sensibly: fold the voice into the playing range.
        if (!Float.isNaN(sight)) {
            float lo = RANGES[range][0], hi = RANGES[range][1];
            while (sight < lo - 3) sight += 12;
            while (sight > hi + 3) sight -= 12;
            sightSmooth = Float.isNaN(sightSmooth) ? sight : sightSmooth + (sight - sightSmooth) * 0.5f;
        } else {
            sightSmooth = Float.NaN;
        }
        if (state != State.PLAYING) return;
        int cur = current();
        if (cur >= TARGETS) { state = State.OVER; return; }
        if (cur < 0 || hit[cur]) { hold = 0; return; }
        if (!Float.isNaN(sight) && Math.abs(sight - targets[cur]) <= 0.7f) {
            hold += dt;
            if (hold >= HOLD_S) { hit[cur] = true; score++; hold = 0; }
        } else {
            hold = Math.max(0, hold - dt);
        }
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Голосовий тир 🎯", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, String.format(Locale.US, "Влучань: %d з %d", score, TARGETS), w - 8 * dp, 22 * dp, 14, LessonKit.YELLOW, Paint.Align.RIGHT, true);
        float lo = RANGES[range][0], hi = RANGES[range][1];
        RectF g = new RectF(40 * dp, 40 * dp, w - 10 * dp, h - 40 * dp);
        k.rect(c, g.left, g.top, g.right, g.bottom, 0xFF0B1220);
        for (int m = (int) lo; m <= hi; m++) {
            if (m % 12 != 0 && m % 12 != 4 && m % 12 != 7) continue;
            float y = g.bottom - (m - lo) / (hi - lo) * g.height();
            k.line(c, g.left, y, g.right, y, LessonKit.GRID, 1);
            k.label(c, MelodyTools.noteName(m), g.left - 4 * dp, y + 4 * dp, 9, LessonKit.LABEL, Paint.Align.RIGHT, false);
        }
        float sightX = g.left + g.width() * 0.25f;
        if (state == State.PLAYING) {
            int cur = current();
            if (cur >= 0 && cur < TARGETS) {
                float t = (clock - startAt - LEAD_S) - cur * WINDOW_S;
                // The target slides from the right edge to the left during its window.
                float x = g.right - (g.right - g.left) * t / WINDOW_S;
                float y = g.bottom - (targets[cur] - lo) / (hi - lo) * g.height();
                if (hit[cur]) {
                    k.label(c, "💥", x, y + 10 * dp, 30, LessonKit.WHITE, Paint.Align.CENTER, false);
                } else {
                    for (int ring = 3; ring >= 1; ring--) {
                        k.fill.setColor(ring % 2 == 1 ? 0xFFE53935 : 0xFFFFFFFF);
                        c.drawCircle(x, y, ring * 8 * dp, k.fill);
                    }
                    if (hold > 0) {
                        k.stroke.setColor(LessonKit.GREEN);
                        k.stroke.setStrokeWidth(4 * dp);
                        c.drawArc(new RectF(x - 30 * dp, y - 30 * dp, x + 30 * dp, y + 30 * dp), -90, 360 * hold / HOLD_S, false, k.stroke);
                    }
                }
                sightX = x;
            } else if (cur < 0) {
                k.label(c, "Приготуйтесь… " + (int) Math.ceil(LEAD_S - (clock - startAt)), g.centerX(), g.centerY(), 22, LessonKit.WHITE,
                        Paint.Align.CENTER, true);
            }
        }
        if (!Float.isNaN(sightSmooth)) {
            float y = g.bottom - Math.max(0, Math.min(1, (sightSmooth - lo) / (hi - lo))) * g.height();
            k.stroke.setColor(LessonKit.CYAN);
            k.stroke.setStrokeWidth(2 * dp);
            c.drawCircle(sightX, y, 16 * dp, k.stroke);
            k.line(c, sightX - 24 * dp, y, sightX + 24 * dp, y, LessonKit.CYAN, 2);
            k.line(c, sightX, y - 24 * dp, sightX, y + 24 * dp, LessonKit.CYAN, 2);
        }
        String msg = state == State.READY ? "Натисніть «▶ Старт» і тягніть «а-а-а» — приціл піде за голосом"
                : state == State.OVER ? (score >= 10 ? "🏆 Снайпер! " : score >= 6 ? "Добре! " : "Ще потренуйтесь! ") + score + " з " + TARGETS
                : "Вище голос — приціл вище. Тримайте ноту на мішені";
        k.label(c, msg, w / 2f, h - 14 * dp, 12, state == State.OVER ? LessonKit.GREEN : LessonKit.SOFT, Paint.Align.CENTER, true);
    }
}
