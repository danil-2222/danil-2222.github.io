package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * "Заспівай ноту": the phone plays a note (round 1), two notes (round 2) or three (round 3); every player in
 * turn sings them back in any octave. Accuracy is measured in cents (1/100 of a semitone); points add up.
 */
final class SingNoteGame implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); }

    enum State { READY, REFERENCE, ARMED, RECORDING, SCORED, TABLE }

    static final int ROUNDS = 3;
    static final float NOTE_S = 0.8f;

    private final LessonKit k;
    private final Sound sound;
    private final LessonKit.GaplessTap tap = new LessonKit.GaplessTap();
    private final List<String> players = new ArrayList<>();
    private float[][] points;              // [player][round]
    private State state = State.READY;
    private int round, player;
    private List<Float> target = new ArrayList<>();
    private List<Float> sung = new ArrayList<>();
    private float[] centsErr = new float[0];
    private float lastScore;
    private List<Float> lastTarget = new ArrayList<>();
    private float clock, armAt;
    private java.util.Random rnd = new java.util.Random();
    // Recorder.
    private float[] rec = new float[0];
    private int recLen, silentMs, rate = 48000;
    private float floorDb = -60;

    SingNoteGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        setPlayers("Гравець 1, Гравець 2");
    }

    void setPlayers(String csv) {
        players.clear();
        for (String s : csv.split("[,;\n]")) if (!s.trim().isEmpty()) players.add(s.trim());
        if (players.isEmpty()) players.add("Гравець 1");
        while (players.size() > 8) players.remove(players.size() - 1);
        newGame(System.nanoTime());
    }

    String playersCsv() {
        StringBuilder b = new StringBuilder();
        for (String p : players) b.append(b.length() > 0 ? ", " : "").append(p);
        return b.toString();
    }

    void newGame(long seed) {
        rnd = new java.util.Random(seed);
        points = new float[players.size()][ROUNDS];
        for (float[] r : points) Arrays.fill(r, Float.NaN);
        round = 0;
        player = 0;
        makeTarget();
        state = State.READY;
    }

    private void makeTarget() {
        int[] scale = {57, 59, 60, 62, 64, 65, 67, 69};
        target = new ArrayList<>();
        int idx = 2 + rnd.nextInt(4);
        for (int i = 0; i <= round; i++) {
            target.add((float) scale[idx]);
            int step;
            do { step = rnd.nextInt(7) - 3; } while (step == 0 || idx + step < 0 || idx + step >= scale.length);
            idx += step;
        }
    }

    List<Float> target() { return target; }

    /** The current player's turn: play the reference, then listen. */
    void turn() {
        if (state == State.TABLE) newGame(System.nanoTime());
        float[] ref = MelodyTools.render(target, NOTE_S);
        sound.play(ref, false);
        state = State.REFERENCE;
        armAt = clock + ref.length / 48000f + 0.2f;
    }

    String state() { return state.name(); }
    float lastScore() { return lastScore; }
    float[] centsErr() { return centsErr; }
    float points(int p, int r) { return points[p][r]; }
    int player() { return player; }
    int round() { return round; }

    float total(int p) {
        float s = 0;
        for (float v : points[p]) if (!Float.isNaN(v)) s += v;
        return s;
    }

    /** Cents between a sung and a target note, ignoring octaves (-600..600). */
    static float centsOff(float sungMidi, float targetMidi) {
        float d = (sungMidi - targetMidi) % 12;
        if (d > 6) d -= 12;
        if (d < -6) d += 12;
        return d * 100;
    }

    /** 100 points for a perfect note, 0 for half a tone or worse. Missing or extra notes cost too. */
    static float score(List<Float> sung, List<Float> target, float[] errOut) {
        float sum = 0;
        for (int i = 0; i < target.size(); i++) {
            if (i >= sung.size()) { if (errOut != null) errOut[i] = Float.NaN; continue; }
            float c = centsOff(sung.get(i), target.get(i));
            if (errOut != null) errOut[i] = c;
            sum += Math.max(0, 100 - 2 * Math.abs(c));
        }
        float extra = Math.max(0, sung.size() - target.size()) * 25;
        return Math.max(0, sum / target.size() - extra);
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        clock += (float) AudioEngine.HOP / f.sampleRate;
        rate = f.sampleRate;
        float[] fresh = tap.take(f);
        if (state == State.REFERENCE && clock >= armAt) { state = State.ARMED; recLen = 0; silentMs = 0; }
        if (state != State.ARMED && state != State.RECORDING) return;
        if (rec.length != rate * 10) rec = new float[rate * 10];
        int block = rate / 50;
        for (int off = 0; off + block <= fresh.length; off += block) {
            float db = LessonKit.blockDb(fresh, off, block);
            if (state == State.ARMED) {
                floorDb = db < floorDb ? db : floorDb * 0.99f + db * 0.01f;
                if (db > floorDb + 12 && db > -50) { state = State.RECORDING; recLen = 0; silentMs = 0; }
            }
            if (state == State.RECORDING) {
                int n = Math.min(block, rec.length - recLen);
                System.arraycopy(fresh, off, rec, recLen, n);
                recLen += n;
                silentMs = db < floorDb + 6 ? silentMs + 20 : 0;
                if (silentMs >= 900 || recLen >= rec.length) { done(); return; }
            }
        }
    }

    private void done() {
        sung = MelodyTools.transcribe(Arrays.copyOf(rec, recLen), rate);
        centsErr = new float[target.size()];
        lastScore = score(sung, target, centsErr);
        lastTarget = target;
        points[player][round] = lastScore;
        player++;
        state = State.SCORED;
        if (player >= players.size()) {
            player = 0;
            round++;
            if (round >= ROUNDS) { state = State.TABLE; round = ROUNDS - 1; return; }
            makeTarget();
        }
    }

    // ---------------------------------------------------------------- drawing

    @Override public boolean animating() { return state == State.REFERENCE || state == State.ARMED || state == State.RECORDING; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Заспівай ноту", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, String.format(Locale.US, "Раунд %d з %d: %s", round + 1, ROUNDS, round == 0 ? "одна нота" : round == 1 ? "дві ноти" : "три ноти"),
                8 * dp, 40 * dp, 11.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        float cx = w / 2f;
        if (state != State.TABLE) {
            String who = players.get(player);
            String msg;
            int col = LessonKit.CYAN;
            switch (state) {
                case READY: msg = who + ": натисніть «▶ Мій хід»"; col = LessonKit.YELLOW; break;
                case REFERENCE: msg = "👂 Слухайте…"; break;
                case ARMED: msg = "🎤 " + who + ", співайте! (будь-яка октава)"; break;
                case RECORDING: msg = "🔴 Слухаю…"; col = LessonKit.PINK; break;
                default: msg = "Наступний: " + who + " — «▶ Мій хід»"; col = LessonKit.YELLOW; break;
            }
            k.label(c, msg, cx, 72 * dp, 16, col, Paint.Align.CENTER, true);
        }
        if (state == State.SCORED || state == State.TABLE) drawScore(c, w, 96 * dp);
        drawTable(c, w, h);
    }

    /** Last attempt: a needle per note showing how many cents off it was. */
    private void drawScore(Canvas c, int w, float top) {
        float dp = k.dp;
        RectF box = new RectF(12 * dp, top, w - 12 * dp, top + 120 * dp);
        k.fill.setColor(0xFF14213A);
        c.drawRoundRect(box, 10 * dp, 10 * dp, k.fill);
        k.label(c, String.format(Locale.US, "%.0f очок", lastScore), box.left + 12 * dp, box.top + 24 * dp, 18,
                lastScore >= 80 ? LessonKit.GREEN : lastScore >= 50 ? LessonKit.YELLOW : LessonKit.PINK, Paint.Align.LEFT, true);
        int n = centsErr.length;
        float cw = (box.width() - 24 * dp) / Math.max(1, n);
        for (int i = 0; i < n; i++) {
            float x0 = box.left + 12 * dp + i * cw, mid = x0 + cw / 2, gy = box.top + 70 * dp;
            k.line(c, x0 + 8 * dp, gy, x0 + cw - 8 * dp, gy, LessonKit.GRID, 3);
            k.line(c, mid, gy - 10 * dp, mid, gy + 10 * dp, LessonKit.GREEN, 2);
            float e = centsErr[i];
            if (Float.isNaN(e)) {
                k.label(c, "не почув", mid, gy + 28 * dp, 11, LessonKit.PINK, Paint.Align.CENTER, false);
                continue;
            }
            float x = mid + Math.max(-1, Math.min(1, e / 100f)) * (cw / 2 - 10 * dp);
            k.fill.setColor(Math.abs(e) <= 15 ? LessonKit.GREEN : Math.abs(e) <= 40 ? LessonKit.YELLOW : LessonKit.PINK);
            c.drawCircle(x, gy, 7 * dp, k.fill);
            k.label(c, MelodyTools.NAMES[((Math.round(lastTarget.get(i)) % 12) + 12) % 12]
                    + String.format(Locale.US, "  %+.0f ц", e), mid, gy + 28 * dp, 11, LessonKit.SOFT, Paint.Align.CENTER, false);
        }
        k.label(c, "нижче ←   → вище", box.right - 12 * dp, box.top + 24 * dp, 10, LessonKit.LABEL, Paint.Align.RIGHT, false);
    }

    private void drawTable(Canvas c, int w, int h) {
        float dp = k.dp;
        Integer[] idx = new Integer[players.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return Float.compare(total(b), total(a)); }
        });
        float y = Math.max(240 * dp, h - (players.size() + 1) * 26 * dp - 10 * dp);
        k.label(c, state == State.TABLE ? "🏆 Підсумок" : "Таблиця", 16 * dp, y, 14, LessonKit.WHITE, Paint.Align.LEFT, true);
        String[] medal = {"🥇", "🥈", "🥉"};
        for (int i = 0; i < idx.length; i++) {
            int p = idx[i];
            y += 26 * dp;
            String place = state == State.TABLE && i < 3 ? medal[i] : (i + 1) + ".";
            k.label(c, place + " " + players.get(p), 16 * dp, y, 14, i == 0 ? LessonKit.YELLOW : LessonKit.SOFT, Paint.Align.LEFT, i == 0);
            StringBuilder rs = new StringBuilder();
            for (int r = 0; r < ROUNDS; r++) rs.append(Float.isNaN(points[p][r]) ? " · " : String.format(Locale.US, " %.0f ", points[p][r]));
            k.label(c, rs + String.format(Locale.US, " = %.0f", total(p)), w - 16 * dp, y, 13, LessonKit.WHITE, Paint.Align.RIGHT, false);
        }
    }
}
