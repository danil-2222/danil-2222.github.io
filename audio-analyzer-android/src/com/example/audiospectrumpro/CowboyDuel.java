package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cowboy reaction duel on two phones. Player 1's phone counts "раз… два…" and at a random moment plays the
 * "ПІФ!" signal; each player covers their own phone's microphone as fast as possible. Each phone measures
 * the reaction from the moment IT heard the signal, then both exchange all five reactions by sound and
 * show who won every round.
 */
final class CowboyDuel implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); void stop(); }

    enum State { IDLE, PLAYING, EXCHANGE, RESULT }

    static final int RATE = 48000, ROUNDS = 5;
    static final float SIGNAL_HZ = 2000, MISS_MS = 1500;

    int me = 1;
    private final LessonKit k;
    private final Sound sound;
    private final LessonKit.Blocks blocks = new LessonKit.Blocks();
    private final LessonKit.CoverDetector cover = new LessonKit.CoverDetector(17000);
    private final PartyLink link;
    private State state = State.IDLE;
    private final List<Float> mine = new ArrayList<>();
    private List<Float> theirs = new ArrayList<>();
    private float signalMs = -1, floorDb = -60, endMs, flashUntil;
    private boolean armed, falseStart, sent;
    private int falseStarts, loudRun;

    CowboyDuel(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        link = new PartyLink(dp);
    }

    static float carrier(int n) { return n == 1 ? 17000 : 18200; }

    /** Waits (seconds) before each signal. */
    static float[] waits(long seed) {
        java.util.Random r = new java.util.Random(seed);
        float[] w = new float[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) w[i] = 2.5f + r.nextFloat() * 3f;
        return w;
    }

    /** Player 1's soundtrack: count-in clicks, random waits, the signals, then "END". */
    static float[] track(long seed) {
        float[] w = waits(seed);
        int total = 0;
        for (float x : w) total += (int) ((x + 2.5f) * RATE);
        float[] end = ModemLesson.encode("END", RATE);
        float[] out = new float[total + end.length];
        int at = 0;
        for (int i = 0; i < ROUNDS; i++) {
            LessonKit.addTone(out, at, RATE / 25, 600, 0.3f, RATE, RATE / 400);           // "get ready" tick
            at += (int) (w[i] * RATE);
            LessonKit.addTone(out, at, (int) (0.3 * RATE), SIGNAL_HZ, 0.8f, RATE, RATE / 500);  // ПІФ!
            at += (int) (2.5f * RATE);
        }
        System.arraycopy(end, 0, out, at, end.length);
        return out;
    }

    void start() { start(System.nanoTime()); }

    void start(long seed) {
        mine.clear();
        theirs = new ArrayList<>();
        falseStarts = 0;
        link.reset();
        signalMs = -1;
        armed = true;
        falseStart = false;
        sent = false;
        floorDb = -60;
        cover.carrierHz = carrier(me);
        cover.reset();
        float[] carrierLoop = LessonKit.carrierLoop(cover.carrierHz, 0.12f, RATE);
        if (me == 1) {
            float[] t = track(seed);
            for (int i = 0; i < t.length; i++) t[i] = t[i] * 0.8f + carrierLoop[i % carrierLoop.length];
            sound.play(t, false);
        } else {
            sound.play(carrierLoop, true);
        }
        state = State.PLAYING;
    }

    String state() { return state.name(); }
    List<Float> mine() { return mine; }
    List<Float> theirs() { return theirs; }
    int falseStarts() { return falseStarts; }

    /** Rounds won by me, by the other player. */
    int[] wins() {
        int a = 0, b = 0;
        for (int i = 0; i < Math.min(mine.size(), theirs.size()); i++) {
            if (mine.get(i) < theirs.get(i)) a++;
            else if (theirs.get(i) < mine.get(i)) b++;
        }
        return new int[]{a, b};
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (state == State.IDLE || state == State.RESULT) return;
        link.onFrame(f);
        blocks.feed(f, new LessonKit.Blocks.Sink() {
            @Override public void block(float[] x, int off, int n, int rate, float t) { onBlock(x, off, n, rate, t); }
        });
        String m = link.poll();
        float now = blocks.nowMs();
        if ("END".equals(m) && state == State.PLAYING) {
            if (!armed) mine.add(MISS_MS);
            state = State.EXCHANGE;
            endMs = now;
            if (me != 1) sound.stop();
        }
        if (state == State.EXCHANGE) {
            String other = link.results.get(me == 1 ? 2 : 1);
            if (other != null && theirs.isEmpty()) {
                for (String p : other.split(",")) {
                    try { theirs.add(Float.parseFloat(p)); } catch (NumberFormatException ignored) { }
                }
            }
            if (!sent && now >= endMs + PartyLink.slotMs(me)) {
                sent = true;
                StringBuilder b = new StringBuilder();
                for (float r : mine) b.append(b.length() > 0 ? "," : "").append(Math.round(r));
                sound.play(ModemLesson.encode(PartyLink.message(me, b.toString()), RATE), false);
            }
            if (now >= endMs + PartyLink.slotMs(2) + 2800) state = State.RESULT;
        }
    }

    private void onBlock(float[] x, int off, int n, int rate, float t) {
        if (state != State.PLAYING) return;
        int c = cover.block(x, off, n, rate);
        float db = LessonKit.toneDb(x, off, n, SIGNAL_HZ, rate);
        floorDb = db < floorDb ? db : floorDb + 0.01f * (db - floorDb);
        loudRun = db > floorDb + 20 && db > -60 ? loudRun + 1 : 0;
        if (armed && loudRun == 3 && mine.size() < ROUNDS) {                         // the signal (30 ms of it, not a click)
            signalMs = t - 20;
            armed = false;
            falseStart = false;
            flashUntil = t + 600;
        }
        if (c == 1) {
            if (armed) {
                if (!falseStart) { falseStart = true; falseStarts++; }
            } else if (!armed) {
                mine.add(t - signalMs);
                armed = true;
            }
        }
        if (!armed && t - signalMs > MISS_MS) { mine.add(MISS_MS); armed = true; }
    }

    // ---------------------------------------------------------------- drawing

    @Override public boolean animating() { return state == State.PLAYING || state == State.EXCHANGE; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Ковбойська дуель 🤠", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "ви — гравець " + me + (me == 1 ? " (ваш телефон подає сигнал)" : ""), 8 * dp, 40 * dp, 11.5f,
                LessonKit.SOFT, Paint.Align.LEFT, false);
        float cx = w / 2f;
        if (state == State.IDLE) {
            k.label(c, me == 1 ? "Натисніть «▶ Почати», коли друг готовий" : "Натисніть «▶ Почати» ДО друга", cx, h * 0.4f, 15,
                    LessonKit.YELLOW, Paint.Align.CENTER, true);
            return;
        }
        boolean fire = blocks.nowMs() < flashUntil;
        RectF card = new RectF(16 * dp, 60 * dp, w - 16 * dp, 160 * dp);
        k.fill.setColor(fire ? 0xFFB71C1C : 0xFF3E2723);
        c.drawRoundRect(card, 16 * dp, 16 * dp, k.fill);
        String big = state == State.EXCHANGE ? "📡 Звіряємо результати…" : state == State.RESULT ? resultText()
                : fire ? "ПІФ! 🔫 Закривай!" : "Руку на кобуру… чекай сигналу";
        k.label(c, big, cx, card.centerY() + 8 * dp, 20, LessonKit.WHITE, Paint.Align.CENTER, true);
        float y = 200 * dp, cell = (w - 32 * dp) / ROUNDS;
        k.label(c, "ви", 16 * dp, y - 8 * dp, 11, LessonKit.CYAN, Paint.Align.LEFT, true);
        k.label(c, "суперник", 16 * dp, y + 58 * dp, 11, LessonKit.PINK, Paint.Align.LEFT, true);
        for (int i = 0; i < ROUNDS; i++) {
            float x = 16 * dp + cell * i;
            Float a = i < mine.size() ? mine.get(i) : null, b = i < theirs.size() ? theirs.get(i) : null;
            boolean won = a != null && b != null && a < b, lost = a != null && b != null && b < a;
            k.fill.setColor(won ? 0xFF14532D : lost ? 0xFF7F1D1D : 0xFF14213A);
            c.drawRoundRect(new RectF(x + 3 * dp, y, x + cell - 3 * dp, y + 40 * dp), 8 * dp, 8 * dp, k.fill);
            k.label(c, a == null ? "" : a >= MISS_MS ? "—" : String.format(Locale.US, "%.0f", a), x + cell / 2, y + 26 * dp, 14,
                    LessonKit.WHITE, Paint.Align.CENTER, true);
            k.label(c, b == null ? "" : b >= MISS_MS ? "—" : String.format(Locale.US, "%.0f", b), x + cell / 2, y + 82 * dp, 13,
                    LessonKit.SOFT, Paint.Align.CENTER, false);
        }
        k.label(c, "мілісекунди від сигналу до пальця на мікрофоні", cx, y + 110 * dp, 10.5f, LessonKit.LABEL, Paint.Align.CENTER, false);
        if (falseStarts > 0) k.label(c, "Фальстартів: " + falseStarts, cx, y + 130 * dp, 12, LessonKit.PINK, Paint.Align.CENTER, false);
    }

    private String resultText() {
        if (theirs.isEmpty()) return "Результат друга не почули";
        int[] wn = wins();
        return wn[0] > wn[1] ? "Перемога! " + wn[0] + ":" + wn[1] + " 🏆" : wn[0] < wn[1] ? "Друг швидший " + wn[0] + ":" + wn[1]
                : "Нічия " + wn[0] + ":" + wn[1];
    }
}
