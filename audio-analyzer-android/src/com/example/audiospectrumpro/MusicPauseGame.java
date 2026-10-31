package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Музична пауза": the host phone plays music that suddenly stops; every player covers the microphone
 * of their own phone as fast as possible. Each phone measures its own reaction from the moment it heard
 * the pause (so distance to the host does not matter) and at the end the results travel by sound.
 */
final class MusicPauseGame implements LessonView {
    interface Sound {
        void play(float[] pcm, boolean loop);
        void stop();
    }

    static final int RATE = 48000, ROUNDS = 5;
    static final float PAUSE_S = 3.2f, MISS_MS = 2000, FALSE_START_MS = 400;
    static final String[] ROLES = {"Ведучий (грає музику)", "Гравець 1", "Гравець 2", "Гравець 3", "Гравець 4", "Гравець 5",
            "Тренування на одному телефоні"};

    enum State { IDLE, HOSTING, PLAYING, EXCHANGE, TABLE }

    int role = 1;

    private final LessonKit k;
    private final Sound sound;
    private final LessonKit.Blocks blocks = new LessonKit.Blocks();
    private final PartyLink link;
    private final LessonKit.CoverDetector cover = new LessonKit.CoverDetector(17000);
    private final BiquadFilter lp1 = new BiquadFilter(), lp2 = new BiquadFilter();
    private int lpRate;
    private State state = State.IDLE;

    // Music tracking (players).
    private float floorDb = 0, peakDb = -200, quietSince = -1;
    private int loudRun;
    private boolean musicOn, waitingReaction, falseThisRound;
    private float stopMs, lastFeedbackMs;
    private final List<Float> reactions = new ArrayList<>();
    private int falseStarts;
    private String feedback = "";

    // Host / exchange timing.
    private float startMs, endMs = -1, hostEndMs;
    private boolean sent;

    MusicPauseGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        link = new PartyLink(dp);
    }

    boolean isHost() { return role == 0; }
    boolean isTraining() { return role == ROLES.length - 1; }
    int playerNo() { return isTraining() ? 1 : role; }
    static float carrierFor(int n) { return 16800 + 300 * (n - 1); }

    /** Music lengths of the rounds, seconds. */
    static float[] schedule(long seed) {
        java.util.Random r = new java.util.Random(seed);
        float[] s = new float[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) s[i] = 3.5f + r.nextFloat() * 5f;
        return s;
    }

    /** Host soundtrack: music, pause, music, pause … then the "END" signal. */
    static float[] soundtrack(long seed) {
        float[] sched = schedule(seed);
        int total = 0;
        for (float s : sched) total += (int) (s * RATE) + (int) (PAUSE_S * RATE);
        float[] end = ModemLesson.encode("END", RATE);
        float[] out = new float[total + end.length];
        int at = 0;
        for (int i = 0; i < ROUNDS; i++) {
            float[] t = LessonKit.tune(sched[i], seed * 31 + i, RATE);
            System.arraycopy(t, 0, out, at, t.length);
            at += t.length + (int) (PAUSE_S * RATE);
        }
        System.arraycopy(end, 0, out, at, end.length);
        return out;
    }

    void start() {
        reactions.clear();
        falseStarts = 0;
        feedback = "";
        link.reset();
        musicOn = waitingReaction = falseThisRound = false;
        peakDb = -200;
        floorDb = 0;
        loudRun = 0;
        quietSince = -1;
        endMs = -1;
        sent = false;
        startMs = blocks.nowMs();
        cover.carrierHz = carrierFor(playerNo());
        cover.reset();
        if (isHost()) {
            float[] track = soundtrack(System.nanoTime());
            hostEndMs = startMs + track.length * 1000f / RATE;
            sound.play(track, false);
            state = State.HOSTING;
        } else if (isTraining()) {
            float[] track = soundtrack(System.nanoTime());
            float[] carrier = LessonKit.carrierLoop(cover.carrierHz, 0.12f, RATE);
            for (int i = 0; i < track.length; i++) track[i] = track[i] * 0.8f + carrier[i % carrier.length];
            sound.play(track, false);
            state = State.PLAYING;
        } else {
            sound.play(LessonKit.carrierLoop(cover.carrierHz, 0.12f, RATE), true);
            state = State.PLAYING;
        }
    }

    String state() { return state.name(); }
    List<Float> reactions() { return reactions; }
    int falseStarts() { return falseStarts; }
    Map<Integer, String> results() { return link.results; }

    /** Final score: average reaction (misses count as 2 s) plus a penalty for every false start. */
    float score() {
        if (reactions.isEmpty()) return MISS_MS + FALSE_START_MS * falseStarts;
        float s = 0;
        for (float r : reactions) s += r;
        return s / reactions.size() + FALSE_START_MS * falseStarts;
    }

    // ---------------------------------------------------------------- listening

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (state == State.IDLE || state == State.TABLE) return;
        if (lpRate != f.sampleRate) {
            lpRate = f.sampleRate;
            lp1.setCustom(false, 6000, 0.707, lpRate);
            lp2.setCustom(false, 6000, 0.707, lpRate);
        }
        link.onFrame(f);
        blocks.feed(f, new LessonKit.Blocks.Sink() {
            @Override public void block(float[] x, int off, int n, int rate, float t) { onBlock(x, off, n, rate, t); }
        });
        String msg = link.poll();
        float now = blocks.nowMs();
        if ("END".equals(msg) && (state == State.PLAYING || state == State.HOSTING)) beginExchange(now);
        if (state == State.HOSTING && now > hostEndMs + 1500) beginExchange(hostEndMs);
        if (state == State.PLAYING && isTraining() && reactions.size() >= ROUNDS && !waitingReaction) beginExchange(now);
        if (state == State.EXCHANGE) {
            int me = playerNo();
            if (!isHost() && !sent && now >= endMs + PartyLink.slotMs(me)) {
                sent = true;
                link.results.put(me, resultString());
                sound.play(ModemLesson.encode(PartyLink.message(me, resultString()), RATE), false);
            }
            if (now >= endMs + PartyLink.allDoneMs()) state = State.TABLE;
        }
    }

    private void beginExchange(float t) {
        finishPendingRound();
        if (isTraining()) { state = State.TABLE; link.results.put(1, resultString()); return; }
        endMs = t;
        state = State.EXCHANGE;
        if (!isHost()) sound.stop();
    }

    String resultString() { return Math.round(score()) + ":" + falseStarts; }

    private void finishPendingRound() {
        if (waitingReaction) { reactions.add(MISS_MS); waitingReaction = false; }
    }

    private void onBlock(float[] x, int off, int n, int rate, float t) {
        if (state != State.PLAYING) return;
        int c = cover.block(x, off, n, rate);
        float[] y = java.util.Arrays.copyOfRange(x, off, off + n);
        lp1.process(y, n);
        lp2.process(y, n);
        float db = LessonKit.blockDb(y, 0, n);
        floorDb = db < floorDb ? db : floorDb + 0.002f * (db - floorDb);

        if (!musicOn) {
            loudRun = db > floorDb + 15 ? loudRun + 1 : 0;
            if (loudRun >= 10 && reactions.size() < ROUNDS) {
                musicOn = true;
                finishPendingRound();
                falseThisRound = false;
                peakDb = db;
                quietSince = -1;
            }
        } else {
            peakDb = Math.max(db, peakDb - 0.03f);
            if (db < peakDb - 12 && !cover.covered) {
                if (quietSince < 0) quietSince = t;
                if (t - quietSince >= 150) {                // a real pause, not a gap between notes
                    musicOn = false;
                    stopMs = quietSince;
                    waitingReaction = true;
                    loudRun = 0;
                    feedback = "⏸ ПАУЗА!";
                }
            } else {
                quietSince = -1;
            }
        }

        if (c == 1) {
            if (musicOn && !falseThisRound) {
                falseThisRound = true;
                falseStarts++;
                say("Фальстарт! Музика ще грає", t);
            } else if (waitingReaction) {
                float r = t - stopMs;
                waitingReaction = false;
                reactions.add(r);
                say(String.format(Locale.US, "%.0f мс%s", r, r < 300 ? " ⚡" : ""), t);
            }
        }
        if (waitingReaction && t - stopMs > MISS_MS) {
            waitingReaction = false;
            reactions.add(MISS_MS);
            say("Проґавили паузу", t);
        }
    }

    private void say(String s, float t) {
        feedback = s;
        lastFeedbackMs = t;
    }

    // ---------------------------------------------------------------- drawing

    @Override public boolean animating() { return state != State.IDLE && state != State.TABLE; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Музична пауза", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, ROLES[role], 8 * dp, 40 * dp, 11.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        float cx = w / 2f;
        switch (state) {
            case IDLE:
                k.label(c, isHost() ? "Натисніть «Почати», коли всі готові" : "Натисніть «Почати» і слухайте музику",
                        cx, h * 0.4f, 15, LessonKit.YELLOW, Paint.Align.CENTER, true);
                return;
            case HOSTING: {
                float el = (blocks.nowMs() - startMs) / 1000f;
                k.label(c, "🎵 Грає музика…", cx, h * 0.32f, 22, LessonKit.CYAN, Paint.Align.CENTER, true);
                k.label(c, String.format(Locale.US, "%.0f с — тримайте телефон-ведучий у центрі", el), cx, h * 0.32f + 28 * dp, 12,
                        LessonKit.SOFT, Paint.Align.CENTER, false);
                return;
            }
            case EXCHANGE:
                k.label(c, "📡 Телефони обмінюються результатами…", cx, h * 0.3f, 15, LessonKit.CYAN, Paint.Align.CENTER, true);
                k.label(c, "Отримано: " + link.results.size(), cx, h * 0.3f + 26 * dp, 13, LessonKit.SOFT, Paint.Align.CENTER, false);
                if (!isHost()) drawRounds(c, w, h * 0.45f);
                return;
            case TABLE:
                drawTable(c, w, h);
                return;
            default:
                break;
        }
        boolean pause = !musicOn && waitingReaction;
        RectF r = new RectF(cx - 130 * dp, h * 0.16f, cx + 130 * dp, h * 0.16f + 120 * dp);
        k.fill.setColor(pause ? 0xFFB71C1C : musicOn ? 0xFF1B5E20 : 0xFF1E293B);
        c.drawRoundRect(r, 18 * dp, 18 * dp, k.fill);
        k.label(c, pause ? "✋ ЗАКРИВАЙ!" : musicOn ? "🎵 грає" : "👂 чекаю музику", cx, r.centerY() + 8 * dp, 22, LessonKit.WHITE,
                Paint.Align.CENTER, true);
        if (blocks.nowMs() - lastFeedbackMs < 1500 && !feedback.isEmpty()) {
            k.label(c, feedback, cx, r.bottom + 34 * dp, 22, feedback.startsWith("Фаль") || feedback.startsWith("Проґ") ? LessonKit.PINK
                    : LessonKit.YELLOW, Paint.Align.CENTER, true);
        }
        drawRounds(c, w, r.bottom + 60 * dp);
        k.label(c, "Коли музика замовкне — закрийте пальцем мікрофон (внизу телефона)", cx, h - 16 * dp, 11, LessonKit.LABEL,
                Paint.Align.CENTER, false);
    }

    private void drawRounds(Canvas c, int w, float y) {
        float dp = k.dp;
        float cell = (w - 32 * dp) / ROUNDS;
        for (int i = 0; i < ROUNDS; i++) {
            float x = 16 * dp + cell * i;
            k.fill.setColor(0xFF14213A);
            c.drawRoundRect(new RectF(x + 3 * dp, y, x + cell - 3 * dp, y + 44 * dp), 8 * dp, 8 * dp, k.fill);
            String v = i < reactions.size() ? (reactions.get(i) >= MISS_MS ? "—" : String.format(Locale.US, "%.0f", reactions.get(i))) : "";
            k.label(c, "Р" + (i + 1), x + cell / 2, y + 15 * dp, 10, LessonKit.LABEL, Paint.Align.CENTER, false);
            k.label(c, v, x + cell / 2, y + 36 * dp, 14, LessonKit.WHITE, Paint.Align.CENTER, true);
        }
        if (falseStarts > 0) k.label(c, "Фальстартів: " + falseStarts + " (+" + (int) FALSE_START_MS + " мс кожен)",
                w / 2f, y + 64 * dp, 11, LessonKit.PINK, Paint.Align.CENTER, false);
    }

    /** Players ordered by result, best first: {player, score ms, false starts}. */
    List<int[]> ranking() {
        List<int[]> rows = new ArrayList<>();
        for (Map.Entry<Integer, String> e : link.results.entrySet()) {
            String[] p = e.getValue().split(":");
            try {
                rows.add(new int[]{e.getKey(), Integer.parseInt(p[0]), p.length > 1 ? Integer.parseInt(p[1]) : 0});
            } catch (NumberFormatException ignored) { }
        }
        java.util.Collections.sort(rows, new java.util.Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return Integer.compare(a[1], b[1]); }
        });
        return rows;
    }

    private void drawTable(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Найшвидша реакція", w / 2f, 72 * dp, 20, LessonKit.WHITE, Paint.Align.CENTER, true);
        List<int[]> rows = ranking();
        String[] medal = {"🥇", "🥈", "🥉"};
        float y = 110 * dp;
        if (rows.isEmpty()) k.label(c, "Результатів не почули — підійдіть ближче", w / 2f, y, 13, LessonKit.PINK, Paint.Align.CENTER, false);
        for (int i = 0; i < rows.size(); i++) {
            int[] r = rows.get(i);
            boolean me = !isHost() && r[0] == playerNo();
            String name = isTraining() ? "Ви" : "Гравець " + r[0] + (me ? " (ви)" : "");
            int col = i == 0 ? LessonKit.YELLOW : me ? LessonKit.CYAN : LessonKit.WHITE;
            k.label(c, (i < 3 ? medal[i] : (i + 1) + ".") + "  " + name, 20 * dp, y, 17, col, Paint.Align.LEFT, i == 0 || me);
            k.label(c, r[1] + " мс" + (r[2] > 0 ? "  (фальстарт ×" + r[2] + ")" : ""), w - 20 * dp, y, 15, col, Paint.Align.RIGHT, true);
            y += 32 * dp;
        }
        if (!isHost()) drawRounds(c, w, Math.max(y + 10 * dp, h * 0.55f));
    }
}
