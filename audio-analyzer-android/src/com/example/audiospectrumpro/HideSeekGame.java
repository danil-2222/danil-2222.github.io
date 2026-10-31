package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Sound hide-and-seek for any number of seekers. The hidden phone first sends "GO" by sound (so every
 * seeker's stopwatch starts at the same moment) and then beeps. Each seeker's phone hears only that beep,
 * shows hot/cold and whether it is getting warmer, and stops the clock by itself when it is right next
 * to the hidden phone.
 */
final class HideSeekGame implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); void stop(); }

    enum State { IDLE, HIDER_GO, HIDER_BEEP, SEEK_WAIT, SEEKING, FOUND }

    static final int RATE = 48000;
    static final float FOUND_HEAT = 0.85f, FOUND_HOLD_S = 0.8f, GIVE_UP_S = 300;

    boolean hider;
    boolean quiet;                // beep at 15 kHz (hard to hear) instead of 3 kHz
    private final LessonKit k;
    private final Sound sound;
    private final ModemLesson decoder;
    private State state = State.IDLE;
    private float clock, startAt, nextAt, foundHold, foundS = -1;
    private float floorDb = -100, heat, peakHold = -140;
    private int holdFrames;
    private final float[] trail = new float[120];   // heat over the last ~2.5 s
    private int trailHead;

    HideSeekGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        decoder = new ModemLesson(dp);
    }

    float beepHz() { return quiet ? 15000 : 3000; }

    static float[] beeps(float hz) {
        float[] pcm = new float[(int) (RATE * 0.6f)];
        LessonKit.addTone(pcm, 0, (int) (RATE * 0.15f), hz, 0.9f, RATE, RATE / 200);
        return pcm;
    }

    private void resetMeter() {
        foundS = -1;
        foundHold = 0;
        heat = 0;
        peakHold = -140;
        holdFrames = 0;
        java.util.Arrays.fill(trail, 0);
    }

    void start() {
        resetMeter();
        if (hider) {
            float[] go = ModemLesson.encode("GO", RATE);
            sound.play(go, false);
            nextAt = clock + go.length / (float) RATE;
            state = State.HIDER_GO;
        } else {
            decoder.reset();
            decoder.clearReceived();
            state = State.SEEK_WAIT;
        }
    }

    /** Start the stopwatch without waiting for the signal. */
    void startNow() {
        if (hider) return;
        resetMeter();
        startAt = clock;
        state = State.SEEKING;
    }

    String state() { return state.name(); }
    float heat() { return heat; }
    float foundSeconds() { return foundS; }

    /** +1 getting warmer, -1 colder, 0 about the same (compares the last second with the one before). */
    int trend() {
        int n = trail.length;
        float a = 0, b = 0;
        for (int i = 0; i < n / 2; i++) { a += trail[(trailHead + i) % n]; b += trail[(trailHead + n / 2 + i) % n]; }
        float d = (b - a) / (n / 2f);
        return d > 0.04f ? 1 : d < -0.04f ? -1 : 0;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float dt = (float) AudioEngine.HOP / f.sampleRate;
        clock += dt;
        if (state == State.HIDER_GO && clock >= nextAt) { sound.play(beeps(beepHz()), true); state = State.HIDER_BEEP; }
        if (hider || state == State.IDLE) return;
        if (state == State.SEEK_WAIT) {
            decoder.onFrame(f);
            if ("GO".equals(decoder.receivedText())) { startAt = clock; state = State.SEEKING; }
        }
        float hz = beepHz();
        double p = LessonKit.bandPower(f, hz - 150, hz + 150);
        float db = (float) (10 * Math.log10(p + 1e-20));
        if (db > peakHold || --holdFrames <= 0) {
            peakHold = db;
            holdFrames = (int) (0.7f * f.sampleRate / AudioEngine.HOP);
        }
        double around = LessonKit.bandPower(f, hz * 0.6f, hz * 0.85f) + LessonKit.bandPower(f, hz * 1.15f, hz * 1.4f);
        floorDb = Math.min(floorDb + 0.05f, (float) (10 * Math.log10(around / 8 + 1e-20)));
        float target = Math.max(0, Math.min(1, (peakHold - Math.max(floorDb + 6, -72)) / 55));
        heat += (target - heat) * 0.15f;
        trail[trailHead] = heat;
        trailHead = (trailHead + 1) % trail.length;
        if (state == State.SEEKING) {
            foundHold = heat >= FOUND_HEAT ? foundHold + dt : 0;
            if (foundHold >= FOUND_HOLD_S) { foundS = clock - startAt - FOUND_HOLD_S; state = State.FOUND; }
        }
    }

    @Override public boolean animating() { return state == State.SEEKING || state == State.SEEK_WAIT; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Звукові хованки", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        float cx = w / 2f;
        if (hider) {
            k.label(c, "Цей телефон ховається 🙈", cx, h * 0.35f, 20, LessonKit.CYAN, Paint.Align.CENTER, true);
            String s = state == State.HIDER_GO ? "📡 Даю сигнал старту шукачам…" : state == State.HIDER_BEEP
                    ? "Пищу кожні 0,6 с (" + (quiet ? "15 кГц — ледь чутно" : "3 кГц") + ")" : "Сховайте телефон і натисніть «▶ Почати»";
            k.label(c, s, cx, h * 0.35f + 30 * dp, 13, LessonKit.SOFT, Paint.Align.CENTER, false);
            return;
        }
        String top = state == State.SEEK_WAIT ? "👂 Чекаю сигнал старту від того, хто ховається…" : state == State.FOUND
                ? String.format(Locale.US, "🎉 ЗНАЙШЛИ за %.1f с!", foundS)
                : state == State.SEEKING ? String.format(Locale.US, "⏱ %.1f с", clock - startAt) : "Натисніть «▶ Почати»";
        k.label(c, top, cx, 52 * dp, state == State.FOUND ? 20 : 16, state == State.FOUND ? LessonKit.GREEN : LessonKit.YELLOW,
                Paint.Align.CENTER, true);
        float t0 = h * 0.17f, b0 = h * 0.7f, tw = 26 * dp;
        k.fill.setColor(0xFF263238);
        c.drawRoundRect(new RectF(cx - tw, t0, cx + tw, b0), tw, tw, k.fill);
        int col = ThreadsLesson.blend(0xFF29B6F6, 0xFFFF1744, heat);
        k.fill.setColor(col);
        c.drawRoundRect(new RectF(cx - tw * 0.6f, b0 - (b0 - t0 - 10 * dp) * Math.max(0.05f, heat), cx + tw * 0.6f, b0 - 5 * dp),
                tw * 0.6f, tw * 0.6f, k.fill);
        c.drawCircle(cx, b0 + tw * 0.4f, tw * 1.1f, k.fill);
        int tr = trend();
        if (state == State.SEEKING) {
            k.label(c, tr > 0 ? "▲ тепліше" : tr < 0 ? "▼ холодніше" : "", cx + tw * 2.2f, h * 0.45f, 16,
                    tr > 0 ? LessonKit.PINK : LessonKit.BLUE, Paint.Align.LEFT, true);
        }
        String word = heat < 0.1f ? "Крижано 🥶" : heat < 0.3f ? "Холодно" : heat < 0.5f ? "Тепліше" : heat < 0.7f ? "Тепло"
                : heat < FOUND_HEAT ? "Гаряче!" : "ОПІК! 🔥";
        k.label(c, word, cx, h - 30 * dp, 22, col, Paint.Align.CENTER, true);
    }
}
