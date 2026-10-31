package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * "Замри": the phone plays dance music; when it stops everybody must freeze in silence. The phone
 * first measures the quiet of the room, then catches any sound louder than that during a freeze.
 */
final class FreezeGame implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); }

    enum State { READY, CALIBRATE, DANCE, FREEZE, RESULT }

    static final int RATE = 48000, ROUNDS = 6;
    static final float CALIB_S = 2.5f, GUARD_S = 0.3f, LOUD_DB = 12;

    private final LessonKit k;
    private final Sound sound;
    private final LessonKit.Blocks blocks = new LessonKit.Blocks();
    private State state = State.READY;
    private float[] music, freeze;      // seconds per round
    private float startMs;
    private double calibSum;
    private int calibN;
    private float roomDb = -200, levelDb = -200, peakDb = -200;
    private final boolean[] caught = new boolean[ROUNDS];
    private final float[] catchDb = new float[ROUNDS];
    private int round = -1;
    private float quietMs;
    private boolean armed;
    private float caughtFlashMs = -1e9f;
    private float[] history = new float[300];
    private int histHead;

    FreezeGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
    }

    static float[][] schedule(long seed) {
        java.util.Random r = new java.util.Random(seed);
        float[] m = new float[ROUNDS], f = new float[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) { m[i] = 5 + r.nextFloat() * 5; f[i] = 4 + r.nextFloat() * 3; }
        return new float[][]{m, f};
    }

    static float[] soundtrack(long seed) {
        float[][] s = schedule(seed);
        int total = (int) (CALIB_S * RATE);
        for (int i = 0; i < ROUNDS; i++) total += (int) (s[0][i] * RATE) + (int) (s[1][i] * RATE);
        float[] out = new float[total];
        int at = (int) (CALIB_S * RATE);
        for (int i = 0; i < ROUNDS; i++) {
            float[] t = LessonKit.tune(s[0][i], seed * 7 + i, RATE);
            System.arraycopy(t, 0, out, at, t.length);
            at += t.length + (int) (s[1][i] * RATE);
        }
        return out;
    }

    void start() { start(System.nanoTime()); }

    void start(long seed) {
        float[][] s = schedule(seed);
        music = s[0];
        freeze = s[1];
        java.util.Arrays.fill(caught, false);
        java.util.Arrays.fill(catchDb, 0);
        calibSum = 0;
        calibN = 0;
        round = -1;
        startMs = blocks.nowMs();
        sound.play(soundtrack(seed), false);
        state = State.CALIBRATE;
    }

    String state() { return state.name(); }
    boolean[] caught() { return caught; }
    float roomDb() { return roomDb; }
    int passed() {
        int n = 0;
        for (boolean c : caught) if (!c) n++;
        return n;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        blocks.feed(f, new LessonKit.Blocks.Sink() {
            @Override public void block(float[] x, int off, int n, int rate, float t) { onBlock(x, off, n, t); }
        });
    }

    private void onBlock(float[] x, int off, int n, float t) {
        levelDb = LessonKit.blockDb(x, off, n);
        history[histHead] = levelDb;
        histHead = (histHead + 1) % history.length;
        if (state == State.READY || state == State.RESULT) return;
        float s = (t - startMs) / 1000f;
        // Where are we in the schedule?
        float at = CALIB_S;
        int r = -1;
        boolean dancing = false, guarded = true;
        float sinceFreeze = 0;
        for (int i = 0; i < ROUNDS; i++) {
            if (s < at) break;
            if (s < at + music[i]) { r = i; dancing = true; break; }
            at += music[i];
            if (s < at + freeze[i]) { r = i; sinceFreeze = s - at; guarded = sinceFreeze < GUARD_S || at + freeze[i] - s < 0.3f; break; }
            at += freeze[i];
            r = i + 1;
        }
        if (s < CALIB_S) {
            if (s > 0.5f) { calibSum += Math.pow(10, levelDb / 10); calibN++; }
            state = State.CALIBRATE;
            return;
        }
        if (calibN > 0) { roomDb = (float) (10 * Math.log10(calibSum / calibN + 1e-14)); calibN = 0; }
        if (r >= ROUNDS) { state = State.RESULT; return; }
        round = r;
        state = dancing ? State.DANCE : State.FREEZE;
        float limit = Math.max(roomDb, -75) + LOUD_DB;
        // Start judging only once we actually hear the music has faded (speaker delay, room echo).
        if (state != State.FREEZE || guarded) { quietMs = 0; armed = false; }
        else if (!armed) {
            quietMs = levelDb < limit - 3 ? quietMs + 10 : 0;
            if (quietMs >= 100) armed = true;
        }
        if (state == State.FREEZE && armed && levelDb > limit) {
            if (!caught[r]) caughtFlashMs = t;
            caught[r] = true;
            catchDb[r] = Math.max(catchDb[r], levelDb - roomDb);
        }
    }

    // ---------------------------------------------------------------- drawing

    @Override public boolean animating() { return state != State.READY && state != State.RESULT; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Замри!", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Музика — танцюйте; тиша — замріть і ні звуку!", 8 * dp, 40 * dp, 11.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        float cx = w / 2f;
        String big;
        int col;
        switch (state) {
            case READY: big = "Натисніть «▶ Почати»"; col = LessonKit.YELLOW; break;
            case CALIBRATE: big = "🤫 Тиша… слухаю кімнату"; col = LessonKit.SOFT; break;
            case DANCE: big = "💃 ТАНЦЮЙТЕ!"; col = LessonKit.GREEN; break;
            case FREEZE: big = "🧊 ЗАМРИ!"; col = LessonKit.CYAN; break;
            default: big = passed() == ROUNDS ? "🏆 Ідеальна тиша!" : "Кінець гри"; col = LessonKit.WHITE; break;
        }
        RectF card = new RectF(16 * dp, 56 * dp, w - 16 * dp, 150 * dp);
        boolean flash = blocks.nowMs() - caughtFlashMs < 1500;
        k.fill.setColor(flash ? 0xFFB71C1C : state == State.FREEZE ? 0xFF0D3B66 : state == State.DANCE ? 0xFF1B5E20 : 0xFF1E293B);
        c.drawRoundRect(card, 16 * dp, 16 * dp, k.fill);
        k.label(c, flash ? "🔊 Хтось зашумів!" : big, cx, card.centerY() + 9 * dp, 24, flash ? LessonKit.WHITE : col, Paint.Align.CENTER, true);

        // Noise meter: room level, the "caught" threshold and the last few seconds.
        float gy = 176 * dp, gh = 110 * dp, gl = 16 * dp, gr = w - 16 * dp;
        k.rect(c, gl, gy, gr, gy + gh, 0xFF0B1220);
        float lo = roomDb > -150 ? roomDb - 15 : -90, hi = lo + 60;
        if (roomDb > -150) {
            float ty = gy + gh - (Math.max(roomDb, -75) + LOUD_DB - lo) / (hi - lo) * gh;
            k.line(c, gl, ty, gr, ty, LessonKit.PINK, 1.5f);
            k.label(c, "межа тиші", gr - 4 * dp, ty - 4 * dp, 10, LessonKit.PINK, Paint.Align.RIGHT, false);
        }
        int n = history.length;
        for (int i = 1; i < n; i++) {
            float v0 = history[(histHead + i - 1) % n], v1 = history[(histHead + i) % n];
            float x0 = gl + (gr - gl) * (i - 1) / n, x1 = gl + (gr - gl) * i / n;
            float y0 = gy + gh - Math.max(0, Math.min(1, (v0 - lo) / (hi - lo))) * gh;
            float y1 = gy + gh - Math.max(0, Math.min(1, (v1 - lo) / (hi - lo))) * gh;
            k.line(c, x0, y0, x1, y1, LessonKit.CYAN, 1.5f);
        }
        k.label(c, "шумомір (останні 3 с)", gl, gy + gh + 14 * dp, 10, LessonKit.LABEL, Paint.Align.LEFT, false);

        // Rounds.
        float y = gy + gh + 34 * dp, cell = (w - 32 * dp) / ROUNDS;
        for (int i = 0; i < ROUNDS; i++) {
            float x = 16 * dp + cell * i;
            boolean done = state == State.RESULT || i < round;
            int fill = caught[i] ? 0xFF7F1D1D : done ? 0xFF14532D : i == round ? 0xFF1E3A5F : 0xFF14213A;
            k.fill.setColor(fill);
            c.drawRoundRect(new RectF(x + 3 * dp, y, x + cell - 3 * dp, y + 46 * dp), 8 * dp, 8 * dp, k.fill);
            k.label(c, String.valueOf(i + 1), x + cell / 2, y + 16 * dp, 10, LessonKit.LABEL, Paint.Align.CENTER, false);
            k.label(c, caught[i] ? "🔊" : done ? "✓" : "", x + cell / 2, y + 38 * dp, 15, LessonKit.WHITE, Paint.Align.CENTER, true);
        }
        if (state == State.RESULT) {
            k.label(c, String.format(Locale.US, "Тихих раундів: %d з %d", passed(), ROUNDS), cx, y + 80 * dp, 17,
                    passed() == ROUNDS ? LessonKit.GREEN : LessonKit.YELLOW, Paint.Align.CENTER, true);
        }
    }
}
