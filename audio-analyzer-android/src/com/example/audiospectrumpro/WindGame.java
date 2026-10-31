package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * "Вітрило": a blowing tournament on one phone passed around. Four contests — strongest blow, longest
 * blow, longest strong blow and steady control — plus a champion run of all four. A blow is recognised
 * as loud noise without pitch and with a strong low rumble; singing or shouting does not count.
 */
final class WindGame implements LessonView {
    enum Mode { STRONG, LONG, STRONG_LONG, CONTROL, CHAMPION }
    enum State { READY, COUNTDOWN, ACTIVE, DONE, TABLE }

    static final String[] MODE_NAMES = {"💨 Хто сильніше", "⏱ Хто довше", "🔥 Сильно і довго", "🎯 Контроль", "🏆 Чемпіон (усе підряд)"};
    static final float STRONG_BAR = 0.55f, MAX_S = 20f, CONTROL_S = 12f, STRONG_S = 4f;
    static final float GAP_S = 0.3f, COUNT_S = 3f, WAIT_S = 6f;

    private final LessonKit k;
    private Mode mode = Mode.STRONG;
    private State state = State.READY;
    private final List<String> players = new ArrayList<>(Arrays.asList("Гравець 1", "Гравець 2"));
    /** scores[player][contest 0..3]; NaN = not played yet. */
    private float[][] scores = new float[2][4];
    private int player;
    private int contest;            // the contest being played now (0..3)

    private float clock, phaseStart, strength, smooth, peak;
    private float runStart = -1, lastOn = -1, inZone, total;
    private boolean voiceWarn;
    private float tonalUntil;
    private int blowRun;
    private float voiceUntil;
    private final float[] trail = new float[200];
    private int trailHead;
    private float angle, boatX;
    private String lastResult = "";

    WindGame(float dp) {
        k = new LessonKit(dp);
        clearScores();
    }

    // ---------------------------------------------------------------- setup

    void setPlayers(String csv) {
        players.clear();
        for (String s : csv.split("[,;\n]")) if (!s.trim().isEmpty()) players.add(s.trim());
        if (players.isEmpty()) players.add("Гравець 1");
        while (players.size() > 8) players.remove(players.size() - 1);
        clearScores();
    }

    void setMode(int m) {
        mode = Mode.values()[m];
        clearScores();
    }

    String playersCsv() {
        StringBuilder b = new StringBuilder();
        for (String p : players) b.append(b.length() > 0 ? ", " : "").append(p);
        return b.toString();
    }

    private void clearScores() {
        scores = new float[players.size()][4];
        for (float[] r : scores) Arrays.fill(r, Float.NaN);
        player = 0;
        contest = mode == Mode.CHAMPION ? 0 : mode.ordinal();
        state = State.READY;
        lastResult = "";
    }

    /** Start (or continue with) the next attempt. */
    void start() {
        if (state == State.TABLE) clearScores();
        voiceWarn = false;
        state = State.COUNTDOWN;
        phaseStart = clock;
        peak = 0;
        runStart = lastOn = -1;
        inZone = total = 0;
        boatX = 0;
        Arrays.fill(trail, 0);
    }

    String state() { return state.name(); }
    float score(int p, int c) { return scores[p][c]; }
    int player() { return player; }
    float strength() { return smooth; }
    boolean voiceWarned() { return voiceWarn; }

    /** Corridor centre for the control contest at time t seconds. */
    static float target(float t) {
        return 0.5f + 0.22f * (float) Math.sin(2 * Math.PI * t / 6.0) + 0.06f * (float) Math.sin(2 * Math.PI * t / 2.3);
    }

    static final float ZONE = 0.09f;

    /** Blow strength 0..1 from level in dBFS: -50 dB = nothing, -10 dB = full. */
    static float strengthOf(float dbfs) { return Math.max(0, Math.min(1, (dbfs + 50) / 40f)); }

    // ---------------------------------------------------------------- listening

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float dt = (float) AudioEngine.HOP / f.sampleRate;
        clock += dt;
        float level = f.rmsDb - f.calibrationDb;
        boolean loud = level > -48;
        boolean tonal = loud && LessonRenderer.yin(f.time, f.sampleRate) > 0;
        double low = LessonKit.bandPower(f, 20, 500), all = LessonKit.bandPower(f, 20, 16000) + 1e-20;
        boolean raw = loud && !tonal && low / all > 0.2;
        if (tonal) tonalUntil = clock + 0.25f;
        blowRun = raw ? blowRun + 1 : 0;
        // A real blow is noise for several frames in a row, with no voice around it.
        boolean blow = raw && blowRun >= 3 && clock > tonalUntil;
        if (tonal && state == State.ACTIVE) { voiceWarn = true; voiceUntil = clock + 1.5f; }
        strength = blow ? strengthOf(level) : 0;
        smooth += (strength - smooth) * 0.45f;
        trail[trailHead] = smooth;
        trailHead = (trailHead + 1) % trail.length;
        angle += smooth * 900 * dt;
        tick(dt);
    }

    private void tick(float dt) {
        float t = clock - phaseStart;
        if (state == State.COUNTDOWN && t >= COUNT_S) { state = State.ACTIVE; phaseStart = clock; return; }
        if (state != State.ACTIVE) return;
        Mode m = Mode.values()[contest];
        switch (m) {
            case STRONG:
                peak = Math.max(peak, smooth);
                if (t >= STRONG_S) finish(Math.round(peak * 100));
                break;
            case LONG:
            case STRONG_LONG: {
                boolean on = m == Mode.LONG ? smooth > 0.08f : smooth >= STRONG_BAR;
                if (on) {
                    if (runStart < 0) runStart = clock;
                    lastOn = clock;
                }
                if (m == Mode.STRONG_LONG && runStart >= 0) boatX = Math.min(1, (clock - runStart) / MAX_S);
                if (runStart < 0 && t > WAIT_S) finish(0);
                else if (runStart >= 0 && clock - lastOn > GAP_S) finish(lastOn - runStart);
                else if (runStart >= 0 && clock - runStart >= MAX_S) finish(MAX_S);
                break;
            }
            case CONTROL: {
                total += dt;
                if (Math.abs(smooth - target(t)) <= ZONE) inZone += dt;
                if (t >= CONTROL_S) finish(Math.round(100 * inZone / Math.max(1e-3f, total)));
                break;
            }
            default:
                break;
        }
    }

    private void finish(float value) {
        scores[player][contest] = value;
        lastResult = players.get(player) + ": " + format(contest, value);
        state = State.DONE;
        if (mode == Mode.CHAMPION && contest < 3) {
            contest++;
        } else {
            contest = mode == Mode.CHAMPION ? 0 : mode.ordinal();
            player++;
            if (player >= players.size()) { player = 0; state = State.TABLE; }
        }
    }

    static String format(int contest, float v) {
        if (Float.isNaN(v)) return "—";
        switch (contest) {
            case 0: return String.format(Locale.US, "сила %.0f", v);
            case 1: case 2: return String.format(Locale.US, "%.1f с", v);
            default: return String.format(Locale.US, "%.0f%% у зоні", v);
        }
    }

    /** Points for the champion table: every contest scaled to 0..100. */
    static float points(int contest, float v) {
        if (Float.isNaN(v)) return 0;
        switch (contest) {
            case 1: return Math.min(100, v * 100 / MAX_S);
            case 2: return Math.min(100, v * 100 / 10f);
            default: return v;
        }
    }

    /** Value used for ranking in the current mode. */
    float rankValue(int p) {
        if (mode == Mode.CHAMPION) {
            float s = 0;
            for (int c = 0; c < 4; c++) s += points(c, scores[p][c]);
            return s;
        }
        float v = scores[p][mode.ordinal()];
        return Float.isNaN(v) ? -1 : v;
    }

    /** Players ordered best first. */
    Integer[] ranking() {
        Integer[] idx = new Integer[players.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return Float.compare(rankValue(b), rankValue(a)); }
        });
        return idx;
    }

    // ---------------------------------------------------------------- drawing

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Вітрило — " + MODE_NAMES[mode.ordinal()], 8 * dp, 22 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Дмухайте в мікрофон (внизу телефона) з відстані ~10 см", 8 * dp, 40 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);
        if (state == State.TABLE) { drawTable(c, w, h, 60 * dp); return; }

        String who = players.get(player);
        int ct = state == State.DONE && mode != Mode.CHAMPION ? -1 : contest;
        String sub = mode == Mode.CHAMPION && ct >= 0 ? "  ·  " + MODE_NAMES[ct].substring(MODE_NAMES[ct].indexOf(' ') + 1) : "";
        k.label(c, "Хід: " + who + sub, w / 2f, 66 * dp, 16, LessonKit.CYAN, Paint.Align.CENTER, true);

        float sceneTop = 80 * dp, sceneBottom = h - 120 * dp;
        drawScene(c, w, sceneTop, sceneBottom);

        // Strength meter.
        float mx = 16 * dp, mw = w - 32 * dp, my = sceneBottom + 14 * dp;
        k.rect(c, mx, my, mx + mw, my + 14 * dp, 0xFF1E293B);
        k.rect(c, mx, my, mx + mw * smooth, my + 14 * dp, smooth >= STRONG_BAR ? LessonKit.ORANGE : LessonKit.CYAN);
        if (Mode.values()[contest] == Mode.STRONG_LONG) k.line(c, mx + mw * STRONG_BAR, my - 4 * dp, mx + mw * STRONG_BAR, my + 18 * dp, LessonKit.WHITE, 2);
        k.label(c, String.format(Locale.US, "сила вітру %.0f", smooth * 100), mx, my + 30 * dp, 11, LessonKit.LABEL, Paint.Align.LEFT, false);

        String msg;
        int col = LessonKit.YELLOW;
        float t = clock - phaseStart;
        switch (state) {
            case READY: msg = "Натисніть «▶ Старт», коли " + who + " готовий(а)"; break;
            case COUNTDOWN: msg = "Приготуйтесь… " + (int) Math.ceil(COUNT_S - t); col = LessonKit.WHITE; break;
            case DONE: msg = "✓ " + lastResult + " · «▶ Старт» — далі"; col = LessonKit.GREEN; break;
            default: msg = activeHint(t); col = LessonKit.CYAN; break;
        }
        if (voiceWarn && clock < voiceUntil && state == State.ACTIVE) { msg = "Це не вітер! 😄 Дмухайте, а не співайте"; col = LessonKit.PINK; }
        k.label(c, msg, w / 2f, h - 54 * dp, 14, col, Paint.Align.CENTER, true);
        if (hasScores()) k.label(c, "Таблиця з'явиться, коли зіграють усі (" + players.size() + ")", w / 2f, h - 30 * dp, 10.5f,
                LessonKit.LABEL, Paint.Align.CENTER, false);
    }

    private String activeHint(float t) {
        switch (Mode.values()[contest]) {
            case STRONG: return String.format(Locale.US, "Дмухніть щосили! %.1f с   рекорд %.0f", STRONG_S - t, peak * 100);
            case LONG: return runStart < 0 ? "Почніть дмухати…" : String.format(Locale.US, "Дмухайте! %.1f с", clock - runStart);
            case STRONG_LONG: return runStart < 0 ? "Дмухайте сильніше за білу риску…"
                    : String.format(Locale.US, "Тримайте силу! %.1f с", clock - runStart);
            default: return String.format(Locale.US, "Тримайте м'ячик у смузі · %.0f с · %.0f%%", CONTROL_S - t, total > 0 ? 100 * inZone / total : 0);
        }
    }

    private boolean hasScores() {
        for (float[] r : scores) for (float v : r) if (!Float.isNaN(v)) return true;
        return false;
    }

    private void drawScene(Canvas c, int w, float top, float bottom) {
        float dp = k.dp;
        float cx = w / 2f, cy = (top + bottom) / 2f, hgt = bottom - top;
        Mode m = state == State.ACTIVE || state == State.COUNTDOWN ? Mode.values()[contest] : Mode.values()[contest];
        switch (m) {
            case STRONG: {
                float r = hgt * (0.12f + 0.3f * Math.max(smooth, state == State.ACTIVE ? peak * 0.9f : smooth));
                k.fill.setColor(0xFFFF4081);
                c.drawOval(new RectF(cx - r, cy - r * 1.15f, cx + r, cy + r * 1.15f), k.fill);
                k.fill.setColor(0x55FFFFFF);
                c.drawOval(new RectF(cx - r * 0.55f, cy - r * 0.8f, cx - r * 0.2f, cy - r * 0.3f), k.fill);
                k.line(c, cx, cy + r * 1.15f, cx, bottom, LessonKit.SOFT, 1.5f);
                break;
            }
            case LONG: {
                float r = hgt * 0.36f;
                k.line(c, cx, cy, cx, bottom, 0xFF8D6E63, 6);
                for (int i = 0; i < 4; i++) {
                    double a = Math.toRadians(angle + i * 90);
                    Path p = new Path();
                    p.moveTo(cx, cy);
                    p.lineTo(cx + (float) (r * Math.cos(a)), cy + (float) (r * Math.sin(a)));
                    p.lineTo(cx + (float) (r * 0.6 * Math.cos(a + 0.5)), cy + (float) (r * 0.6 * Math.sin(a + 0.5)));
                    p.close();
                    k.fill.setColor(i % 2 == 0 ? LessonKit.CYAN : LessonKit.YELLOW);
                    c.drawPath(p, k.fill);
                }
                k.fill.setColor(LessonKit.WHITE);
                c.drawCircle(cx, cy, 5 * dp, k.fill);
                break;
            }
            case STRONG_LONG: {
                float sea = cy + hgt * 0.2f;
                k.rect(c, 0, sea, w, bottom, 0xFF0D47A1);
                float bx = 30 * dp + (w - 90 * dp) * boatX;
                Path hull = new Path();
                hull.moveTo(bx - 26 * dp, sea - 4 * dp);
                hull.lineTo(bx + 26 * dp, sea - 4 * dp);
                hull.lineTo(bx + 16 * dp, sea + 10 * dp);
                hull.lineTo(bx - 16 * dp, sea + 10 * dp);
                hull.close();
                k.fill.setColor(0xFF8D6E63);
                c.drawPath(hull, k.fill);
                k.line(c, bx, sea - 4 * dp, bx, sea - 70 * dp, LessonKit.SOFT, 2);
                Path sail = new Path();
                float bulge = 8 * dp + 26 * dp * smooth;
                sail.moveTo(bx + 2 * dp, sea - 68 * dp);
                sail.quadTo(bx + bulge * 1.4f, sea - 40 * dp, bx + 2 * dp, sea - 10 * dp);
                sail.close();
                k.fill.setColor(smooth >= STRONG_BAR ? LessonKit.ORANGE : LessonKit.WHITE);
                c.drawPath(sail, k.fill);
                k.line(c, w - 50 * dp, sea - 40 * dp, w - 50 * dp, sea, LessonKit.GREEN, 3);
                k.label(c, "20 с", w - 50 * dp, sea - 46 * dp, 10, LessonKit.GREEN, Paint.Align.CENTER, false);
                break;
            }
            default: {
                // Control: corridor scrolling from right to left, the ball is the current strength.
                float left = 16 * dp, right = w - 16 * dp, span = 6f;
                float t = state == State.ACTIVE ? clock - phaseStart : 0;
                Path up = new Path(), dn = new Path();
                for (int i = 0; i <= 60; i++) {
                    float tt = t - span * 0.3f + span * i / 60f;
                    float x = left + (right - left) * i / 60f;
                    float yU = bottom - (target(tt) + ZONE) * hgt, yD = bottom - (target(tt) - ZONE) * hgt;
                    if (i == 0) { up.moveTo(x, yU); dn.moveTo(x, yD); } else { up.lineTo(x, yU); dn.lineTo(x, yD); }
                }
                k.stroke.setColor(LessonKit.GREEN);
                k.stroke.setStrokeWidth(2 * dp);
                c.drawPath(up, k.stroke);
                c.drawPath(dn, k.stroke);
                float bx = left + (right - left) * 0.3f, by = bottom - smooth * hgt;
                boolean in = Math.abs(smooth - target(t)) <= ZONE;
                k.fill.setColor(in ? LessonKit.GREEN : LessonKit.PINK);
                c.drawCircle(bx, by, 9 * dp, k.fill);
                break;
            }
        }
    }

    private void drawTable(Canvas c, int w, int h, float top) {
        float dp = k.dp;
        k.label(c, "Результати", w / 2f, top + 10 * dp, 20, LessonKit.WHITE, Paint.Align.CENTER, true);
        Integer[] order = ranking();
        String[] medal = {"🥇", "🥈", "🥉"};
        float y = top + 44 * dp;
        for (int i = 0; i < order.length; i++) {
            int p = order[i];
            String value;
            if (mode == Mode.CHAMPION) {
                value = String.format(Locale.US, "%.0f очок", rankValue(p));
            } else {
                value = format(mode.ordinal(), scores[p][mode.ordinal()]);
            }
            int col = i == 0 ? LessonKit.YELLOW : i < 3 ? LessonKit.WHITE : LessonKit.SOFT;
            k.label(c, (i < 3 ? medal[i] : (i + 1) + ".") + "  " + players.get(p), 20 * dp, y, 17, col, Paint.Align.LEFT, i == 0);
            k.label(c, value, w - 20 * dp, y, 16, col, Paint.Align.RIGHT, true);
            if (mode == Mode.CHAMPION) {
                StringBuilder b = new StringBuilder();
                for (int ci = 0; ci < 4; ci++) b.append(ci > 0 ? " · " : "").append(format(ci, scores[p][ci]));
                k.label(c, b.toString(), 46 * dp, y + 17 * dp, 10.5f, LessonKit.LABEL, Paint.Align.LEFT, false);
                y += 16 * dp;
            }
            y += 34 * dp;
        }
        k.label(c, "«▶ Старт» — новий турнір", w / 2f, h - 24 * dp, 13, LessonKit.CYAN, Paint.Align.CENTER, false);
    }
}
