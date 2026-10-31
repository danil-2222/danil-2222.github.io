package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * "Simon": the app plays a growing sequence of four coloured notes (до, мі, соль, ля); repeat it by
 * singing or whistling (any octave). Each round adds one note.
 */
final class SimonGame implements LessonView {
    interface Sound { void play(float[] pcm); }

    static final int[] PITCH_CLASS = {0, 4, 7, 9};             // до, мі, соль, ля
    static final String[] NAMES = {"до", "мі", "соль", "ля"};
    static final int[] COLORS = {0xFFE53935, 0xFF43A047, 0xFF1E88E5, 0xFFFDD835};
    static final float NOTE_S = 0.6f, GAP_S = 0.15f;

    enum State { IDLE, PLAYING, LISTENING, WON_ROUND, LOST }

    private final LessonKit k;
    private final Sound sound;
    private final Random rnd = new Random();
    private final List<Integer> seq = new ArrayList<>();
    private State state = State.IDLE;
    private int pos, best;
    private float clockMs, stateUntil, heldMs = 0;
    private int heldPad = -1, litPad = -1;
    private boolean released = true;

    SimonGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
    }

    List<Integer> sequence() { return seq; }
    int level() { return seq.size(); }
    String state() { return state.name(); }

    void start() {
        seq.clear();
        nextRound();
    }

    private void nextRound() {
        seq.add(rnd.nextInt(4));
        playSequence();
    }

    private void playSequence() {
        int rate = 48000, note = (int) (NOTE_S * rate), gap = (int) (GAP_S * rate);
        float[] pcm = new float[seq.size() * (note + gap) + rate / 4];
        for (int i = 0; i < seq.size(); i++) {
            LessonKit.addNote(pcm, i * (note + gap), note, LessonKit.midiToHz(60 + PITCH_CLASS[seq.get(i)]), 0.8f, rate);
        }
        sound.play(pcm);
        state = State.PLAYING;
        stateUntil = clockMs + pcm.length * 1000f / rate + 400;
        pos = 0;
        heldPad = -1;
        heldMs = 0;
        released = true;
    }

    /** Which pad is lit while the sequence plays. */
    private int playingPad() {
        float into = clockMs - (stateUntil - 400 - (seq.size() * (NOTE_S + GAP_S) + 0.25f) * 1000);
        int idx = (int) (into / ((NOTE_S + GAP_S) * 1000));
        float within = into - idx * (NOTE_S + GAP_S) * 1000;
        return idx >= 0 && idx < seq.size() && within < NOTE_S * 1000 ? seq.get(idx) : -1;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float dt = 1000f * AudioEngine.HOP / f.sampleRate;
        clockMs += dt;
        if (state == State.PLAYING) {
            litPad = playingPad();
            if (clockMs >= stateUntil) { state = State.LISTENING; litPad = -1; }
            return;
        }
        if (state == State.WON_ROUND) {
            if (clockMs >= stateUntil) nextRound();
            return;
        }
        if (state != State.LISTENING) return;
        float hz = f.rmsDb - f.calibrationDb > -50 ? LessonRenderer.yin(f.time, f.sampleRate) : 0;
        int pad = -1;
        if (hz > 0) {
            float midi = (float) (69 + 12 * Math.log(hz / 440.0) / Math.log(2));
            int pc = ((Math.round(midi) % 12) + 12) % 12;
            if (Math.abs(midi - Math.round(midi)) < 0.4f) for (int i = 0; i < 4; i++) if (PITCH_CLASS[i] == pc) pad = i;
        }
        if (pad < 0) {
            heldMs = Math.max(0, heldMs - dt);
            if (heldMs == 0) { released = true; heldPad = -1; }
            litPad = -1;
            return;
        }
        if (pad != heldPad) { heldPad = pad; heldMs = 0; released = true; }   // moving to another note
        heldMs = Math.min(400, heldMs + dt);
        litPad = pad;
        // A note counts after 250 ms; the next one needs a new note or a short break.
        if (heldMs >= 250 && released) {
            released = false;
            if (pad == seq.get(pos)) {
                pos++;
                if (pos == seq.size()) {
                    best = Math.max(best, seq.size());
                    state = State.WON_ROUND;
                    stateUntil = clockMs + 900;
                }
            } else {
                state = State.LOST;
            }
        }
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Повтори мелодію", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Слухайте ноти, потім заспівайте або просвистіть їх по черзі", 8 * dp, 40 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
        float size = Math.min(w, h * 0.7f) * 0.36f, cx = w / 2f, cy = h * 0.46f, g = 6 * dp;
        for (int i = 0; i < 4; i++) {
            float x0 = i % 2 == 0 ? cx - size - g : cx + g, y0 = i < 2 ? cy - size - g : cy + g;
            boolean lit = i == litPad;
            k.fill.setColor(lit ? COLORS[i] : (COLORS[i] & 0x00FFFFFF) | 0x44000000);
            c.drawRoundRect(new RectF(x0, y0, x0 + size, y0 + size), 18 * dp, 18 * dp, k.fill);
            k.label(c, NAMES[i], x0 + size / 2, y0 + size / 2 + 8 * dp, 22, lit ? 0xFF000000 : LessonKit.WHITE, Paint.Align.CENTER, true);
        }
        String msg;
        switch (state) {
            case IDLE: msg = "Натисніть «Почати»"; break;
            case PLAYING: msg = "Слухайте… (" + seq.size() + " нот)"; break;
            case LISTENING: msg = "Ваша черга: нота " + (pos + 1) + " з " + seq.size(); break;
            case WON_ROUND: msg = "Правильно! Додаю ноту…"; break;
            default: msg = "Помилка! Ви запам'ятали " + (seq.size() - 1) + " нот"; break;
        }
        k.label(c, msg, cx, h - 32 * dp, 16, state == State.LOST ? LessonKit.PINK : LessonKit.YELLOW, Paint.Align.CENTER, true);
        k.label(c, "Рекорд: " + best + " нот · будь-яка октава підходить", cx, h - 10 * dp, 10.5f, LessonKit.LABEL, Paint.Align.CENTER, false);
    }
}
