package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Rhythm duel. Each phone plays the song plus its own quiet carrier tone; covering the microphone
 * with a finger on the beat makes the carrier drop, which counts as a hit. With two phones the start
 * and the final scores travel between them as sound, using the acoustic modem.
 */
final class DuelGame implements LessonView {
    interface Sound {
        void play(float[] pcm, boolean loop);
        void stop();
    }

    static final int RATE = 48000;
    static final float BEAT_MS = 600;            // 100 beats per minute
    static final int LEAD_BEATS = 4;
    /** Beats (from the end of the count-in) that must be hit. */
    static final float[] HITS = {0, 1, 2, 3, 4, 5, 6, 6.5f, 7, 8, 10, 11, 12, 12.5f, 13, 14, 15,
            16, 17, 18, 18.5f, 19, 20, 21, 22, 22.5f, 23, 24, 25, 26, 27, 28, 28.5f, 29, 29.5f, 30, 31};
    static final int SONG_BEATS = 32;
    static final float PERFECT_MS = 60, GOOD_MS = 150;

    enum Role { SOLO, PLAYER1, PLAYER2 }
    enum State { IDLE, SENDING_GO, WAIT_GO, PLAYING, EXCHANGE, RESULT }

    Role role = Role.SOLO;
    boolean audible;

    private final LessonKit k;
    private final Sound sound;
    private final ModemLesson decoder;
    private final LessonKit.SampleTap tap = new LessonKit.SampleTap();
    private float[] carry = new float[0];
    private State state = State.IDLE;
    private float clockMs, songStartMs, stateUntil;
    private float latencyMs = Float.NaN;
    private float baseDb = -200, levelDb = -200;
    private boolean covered;
    private final boolean[] judged = new boolean[HITS.length];
    private int score, combo, bestCombo, perfect, good, miss, extra;
    private String feedback = "";
    private float feedbackUntil;
    private int opponent = -1;
    private String sentScore = "";
    private boolean scoreSent;

    DuelGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        decoder = new ModemLesson(dp);
    }

    float carrierHz() {
        boolean second = role == Role.PLAYER2;
        return audible ? (second ? 1100 : 800) : (second ? 18200 : 17000);
    }

    String state() { return state.name(); }
    int score() { return score; }
    int perfect() { return perfect; }
    int opponent() { return opponent; }
    float songStartMs() { return songStartMs; }
    float clockMs() { return clockMs; }

    static float hitMs(int i) { return (LEAD_BEATS + HITS[i]) * BEAT_MS; }

    /** The song: a drum and bass loop with a count-in, plus the player's carrier tone underneath. */
    static float[] song(float carrierHz) {
        int beat = (int) (BEAT_MS * RATE / 1000);
        int total = (LEAD_BEATS + SONG_BEATS) * beat + RATE / 2;
        float[] out = new float[total];
        java.util.Random r = new java.util.Random(2);
        int[] bass = {36, 36, 43, 41};
        for (int b = 0; b < LEAD_BEATS + SONG_BEATS; b++) {
            int at = b * beat;
            if (b < LEAD_BEATS) {                                     // count-in clicks
                LessonKit.addTone(out, at, RATE / 25, b == LEAD_BEATS - 1 ? 1800 : 1200, 0.5f, RATE, RATE / 400);
                continue;
            }
            int s = b - LEAD_BEATS;
            for (int i = 0; i < RATE / 6 && at + i < total; i++) {   // kick
                double t = (double) i / RATE;
                out[at + i] += (float) (0.6 * Math.exp(-t * 18) * Math.sin(2 * Math.PI * (55 + 90 * Math.exp(-t * 30)) * t));
            }
            if (s % 2 == 1) {                                         // snare
                for (int i = 0; i < RATE / 8 && at + i < total; i++) {
                    out[at + i] += (float) ((r.nextFloat() * 2 - 1) * 0.25 * Math.exp(-i / (RATE * 0.04)));
                }
            }
            LessonKit.addNote(out, at, beat, LessonKit.midiToHz(bass[(s / 4) % 4]), 0.35f, RATE);
        }
        LessonKit.normalize(out, 0.55f);
        // Carrier under everything, so the microphone always hears its own phone.
        for (int i = 0; i < total; i++) out[i] += (float) (0.12 * Math.sin(2 * Math.PI * carrierHz * i / RATE));
        return out;
    }

    // ---------------------------------------------------------------- control

    void start() {
        resetScore();
        decoder.reset();
        decoder.clearReceived();
        switch (role) {
            case SOLO: startSong(); break;
            case PLAYER1: {
                float[] go = ModemLesson.encode("GO", RATE);
                sound.play(go, false);
                state = State.SENDING_GO;
                // Start right when the other phone will have heard the end of the message.
                stateUntil = clockMs + go.length * 1000f / RATE - 120;
                break;
            }
            default: state = State.WAIT_GO; break;
        }
    }

    private void resetScore() {
        java.util.Arrays.fill(judged, false);
        score = combo = bestCombo = perfect = good = miss = extra = 0;
        opponent = -1;
        scoreSent = false;
        feedback = "";
    }

    private void startSong() {
        sound.play(song(carrierHz()), false);
        state = State.PLAYING;
        songStartMs = clockMs;
        latencyMs = Float.NaN;
        baseDb = -200;
        covered = false;
    }

    // ---------------------------------------------------------------- listening

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (state == State.WAIT_GO || state == State.EXCHANGE) decoder.onFrame(f);
        float[] fresh = tap.take(f);
        int n = f.sampleRate / 100;                                  // 10 ms blocks
        float[] buf = new float[carry.length + fresh.length];
        System.arraycopy(carry, 0, buf, 0, carry.length);
        System.arraycopy(fresh, 0, buf, carry.length, fresh.length);
        int off = 0;
        for (; off + n <= buf.length; off += n) {
            clockMs += 10;
            if (state == State.PLAYING) onCarrierBlock(buf, off, n, f.sampleRate);
        }
        carry = java.util.Arrays.copyOfRange(buf, off, buf.length);
        tick();
    }

    private void tick() {
        if (state == State.SENDING_GO && clockMs >= stateUntil) startSong();
        if (state == State.WAIT_GO && "GO".equals(decoder.receivedText())) startSong();
        if (state == State.PLAYING) {
            float t = clockMs - songStartMs - (Float.isNaN(latencyMs) ? 0 : latencyMs);
            for (int i = 0; i < HITS.length; i++) {
                if (!judged[i] && t > hitMs(i) + GOOD_MS) { judged[i] = true; miss++; combo = 0; say("Мимо", t); }
            }
            if (t > (LEAD_BEATS + SONG_BEATS) * BEAT_MS + 300) finishSong();
        }
        if (state == State.EXCHANGE) {
            String msg = decoder.receivedText();
            String want = role == Role.PLAYER1 ? "P2:" : "P1:";
            if (msg != null && msg.startsWith(want)) {
                try { opponent = Integer.parseInt(msg.substring(3).trim()); } catch (NumberFormatException ignored) {}
            }
            // Player 1 answers first, player 2 three seconds later, so the messages never overlap.
            float sendAt = role == Role.PLAYER1 ? 500 : 3800;
            if (!scoreSent && clockMs >= stateUntil + sendAt) {
                scoreSent = true;
                sentScore = (role == Role.PLAYER1 ? "P1:" : "P2:") + score;
                sound.play(ModemLesson.encode(sentScore, RATE), false);
            }
            if (opponent >= 0 && scoreSent && clockMs >= stateUntil + sendAt + 3000) state = State.RESULT;
            if (clockMs > stateUntil + 15000) state = State.RESULT;            // give up waiting
        }
    }

    private void finishSong() {
        sound.stop();
        if (role == Role.SOLO) {
            state = State.RESULT;
        } else {
            state = State.EXCHANGE;
            stateUntil = clockMs;
            decoder.reset();
            decoder.clearReceived();
        }
    }

    /** Own carrier level every 10 ms: a sudden drop means the microphone was covered. */
    private void onCarrierBlock(float[] x, int off, int n, int rate) {
        double p = LessonKit.goertzel(x, off, n, carrierHz(), rate);
        levelDb = (float) (10 * Math.log10(p / ((double) n * n / 4) + 1e-14));
        float t = clockMs - songStartMs;
        if (Float.isNaN(latencyMs)) {
            // The first time we hear our own carrier tells the speaker+microphone delay.
            if (levelDb > -55) { latencyMs = t; baseDb = levelDb; }
            return;
        }
        if (!covered) baseDb = levelDb > baseDb ? levelDb : baseDb * 0.995f + levelDb * 0.005f;
        if (!covered && levelDb < baseDb - 9) {
            covered = true;
            onHit(t - latencyMs);
        } else if (covered && levelDb > baseDb - 4) {
            covered = false;
        }
    }

    private void onHit(float t) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < HITS.length; i++) {
            if (judged[i]) continue;
            float d = Math.abs(t - hitMs(i));
            if (d < bestD) { bestD = d; best = i; }
        }
        if (best < 0 || bestD > GOOD_MS) { extra++; combo = 0; say("Не в такт", t); return; }
        judged[best] = true;
        combo++;
        bestCombo = Math.max(bestCombo, combo);
        int mult = combo >= 5 ? 2 : 1;
        if (bestD <= PERFECT_MS) { perfect++; score += 100 * mult; say("Ідеально!", t); }
        else { good++; score += 50 * mult; say("Добре", t); }
    }

    private void say(String s, float t) {
        feedback = s;
        feedbackUntil = clockMs + 500;
    }

    // ---------------------------------------------------------------- drawing

    @Override public boolean animating() { return state == State.PLAYING; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Ритм-дуель", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        String who = role == Role.SOLO ? "гра наодинці" : role == Role.PLAYER1 ? "ви — гравець 1" : "ви — гравець 2";
        k.label(c, who + " · закривайте мікрофон пальцем у такт", 8 * dp, 40 * dp, 11.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        switch (state) {
            case IDLE:
                k.label(c, role == Role.PLAYER2 ? "Натисніть «Почати» і чекайте на друга" : "Натисніть «Почати»", w / 2f, h * 0.45f, 16,
                        LessonKit.YELLOW, Paint.Align.CENTER, true);
                return;
            case SENDING_GO:
                k.label(c, "📡 Даю сигнал старту другу…", w / 2f, h * 0.45f, 17, LessonKit.CYAN, Paint.Align.CENTER, true);
                return;
            case WAIT_GO:
                k.label(c, "👂 Чекаю сигнал старту від гравця 1…", w / 2f, h * 0.45f, 16, LessonKit.CYAN, Paint.Align.CENTER, true);
                return;
            case EXCHANGE:
                k.label(c, "Ваш рахунок: " + score, w / 2f, h * 0.38f, 22, LessonKit.WHITE, Paint.Align.CENTER, true);
                k.label(c, opponent >= 0 ? "Рахунок друга отримано ✓" : "📡 Обмін рахунками звуком…", w / 2f, h * 0.38f + 30 * dp, 14,
                        LessonKit.CYAN, Paint.Align.CENTER, false);
                return;
            case RESULT:
                drawResult(c, w, h);
                return;
            default:
                break;
        }
        // Playing: notes travel from the right to the hit line.
        float t = clockMs - songStartMs - (Float.isNaN(latencyMs) ? 0 : latencyMs);
        float lineX = w * 0.22f, laneY = h * 0.42f, pxPerMs = w * 0.6f / 2000f;
        k.rect(c, 0, laneY - 40 * dp, w, laneY + 40 * dp, 0xFF0B1220);
        k.line(c, lineX, laneY - 46 * dp, lineX, laneY + 46 * dp, LessonKit.WHITE, 3);
        for (int i = 0; i < HITS.length; i++) {
            float x = lineX + (hitMs(i) - t) * pxPerMs;
            if (x < -20 * dp || x > w + 20 * dp) continue;
            k.fill.setColor(judged[i] ? 0x33FFFFFF : HITS[i] % 1 == 0 ? LessonKit.CYAN : LessonKit.YELLOW);
            c.drawCircle(x, laneY, 16 * dp, k.fill);
        }
        if (t < LEAD_BEATS * BEAT_MS) {
            int count = LEAD_BEATS - (int) (t / BEAT_MS);
            k.label(c, String.valueOf(Math.max(1, count)), w / 2f, h * 0.25f, 40, LessonKit.WHITE, Paint.Align.CENTER, true);
        }
        k.label(c, covered ? "✋" : "👆", lineX, laneY + 90 * dp, 40, LessonKit.WHITE, Paint.Align.CENTER, false);
        if (clockMs < feedbackUntil) {
            int col = feedback.startsWith("Ідеально") ? LessonKit.GREEN : feedback.startsWith("Добре") ? LessonKit.YELLOW : LessonKit.PINK;
            k.label(c, feedback, w * 0.6f, laneY + 90 * dp, 20, col, Paint.Align.CENTER, true);
        }
        k.label(c, String.format(Locale.US, "Очки %d   серія %d%s", score, combo, combo >= 5 ? " ×2" : ""), w / 2f, h - 24 * dp, 17,
                LessonKit.WHITE, Paint.Align.CENTER, true);
    }

    private void drawResult(Canvas c, int w, int h) {
        float dp = k.dp;
        float cx = w / 2f;
        if (role != Role.SOLO && opponent >= 0) {
            boolean win = score > opponent, draw = score == opponent;
            k.label(c, draw ? "Нічия! 🤝" : win ? "Перемога! 🏆" : "Друг переміг 😅", cx, h * 0.25f, 26,
                    win ? LessonKit.GREEN : draw ? LessonKit.YELLOW : LessonKit.PINK, Paint.Align.CENTER, true);
            k.label(c, "Ви " + score + "  :  " + opponent + " друг", cx, h * 0.25f + 34 * dp, 20, LessonKit.WHITE, Paint.Align.CENTER, true);
        } else {
            k.label(c, "Рахунок: " + score, cx, h * 0.25f, 26, LessonKit.WHITE, Paint.Align.CENTER, true);
            if (role != Role.SOLO) k.label(c, "Рахунок друга не почули — підійдіть ближче", cx, h * 0.25f + 30 * dp, 12,
                    LessonKit.PINK, Paint.Align.CENTER, false);
        }
        RectF box = new RectF(24 * dp, h * 0.45f, w - 24 * dp, h * 0.45f + 110 * dp);
        k.fill.setColor(0xFF14213A);
        c.drawRoundRect(box, 12 * dp, 12 * dp, k.fill);
        float y = box.top + 26 * dp;
        k.label(c, "Ідеально: " + perfect, box.left + 16 * dp, y, 14, LessonKit.GREEN, Paint.Align.LEFT, true);
        k.label(c, "Добре: " + good, box.left + 16 * dp, y + 24 * dp, 14, LessonKit.YELLOW, Paint.Align.LEFT, true);
        k.label(c, "Мимо: " + (miss + extra), box.left + 16 * dp, y + 48 * dp, 14, LessonKit.PINK, Paint.Align.LEFT, true);
        k.label(c, "Найдовша серія: " + bestCombo, box.right - 16 * dp, y, 14, LessonKit.CYAN, Paint.Align.RIGHT, true);
        if (!Float.isNaN(latencyMs)) {
            k.label(c, String.format(Locale.US, "затримка телефона %.0f мс (врахована)", latencyMs), box.right - 16 * dp, y + 48 * dp, 10.5f,
                    LessonKit.LABEL, Paint.Align.RIGHT, false);
        }
    }
}
