package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Зіпсований телефон" with a melody. The phone plays a short tune to the first player, who sings it back;
 * the next player hears only that recording, sings it, and so on. The phone writes down every version as
 * notes and in the end shows how the melody changed along the chain.
 */
final class ChainGame implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); }

    enum State { READY, ARMED, RECORDING, RESULT }

    static final int NOTES = 5;
    static final float NOTE_S = 0.55f, END_SILENCE_MS = 1000, MAX_S = 12;

    private final LessonKit k;
    private final Sound sound;
    private final LessonKit.GaplessTap tap = new LessonKit.GaplessTap();
    private State state = State.READY;
    final List<List<Float>> versions = new ArrayList<>();
    private final List<float[]> recordings = new ArrayList<>();
    private float[] rec = new float[0];
    private int recLen, silentMs, rate = 48000;
    private float floorDb = -60, levelDb = -100;
    private String note = "";

    ChainGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        newMelody(System.nanoTime());
    }

    void newMelody(long seed) {
        java.util.Random r = new java.util.Random(seed);
        int[] scale = {60, 62, 64, 67, 69, 72};
        List<Float> m = new ArrayList<>();
        int idx = r.nextInt(3);
        for (int i = 0; i < NOTES; i++) {
            m.add((float) scale[idx]);
            int step;
            do { step = r.nextInt(5) - 2; } while (step == 0 || idx + step < 0 || idx + step >= scale.length);
            idx += step;
        }
        versions.clear();
        recordings.clear();
        versions.add(m);
        recordings.add(MelodyTools.render(m, NOTE_S));
        state = State.READY;
        note = "Гравець 1: натисніть «👂 Послухати» (тихо, біля вуха)";
    }

    /** Play the newest version — the one the current player must repeat. */
    void listen() {
        if (state == State.ARMED || state == State.RECORDING) return;
        sound.play(recordings.get(recordings.size() - 1), false);
    }

    void record() {
        state = State.ARMED;
        recLen = 0;
        silentMs = 0;
        note = "Співайте! (на «ла-ла-ла»)";
    }

    void finish() {
        state = State.RESULT;
        float[] a = recordings.get(0), b = recordings.get(recordings.size() - 1);
        float[] both = new float[a.length + 48000 / 2 + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length + 24000, b.length);
        sound.play(both, false);
    }

    String state() { return state.name(); }
    int players() { return versions.size() - 1; }
    float similarity(int v) { return MelodyTools.similarity(versions.get(0), versions.get(v)); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        rate = f.sampleRate;
        float[] fresh = tap.take(f);
        if (rec.length != rate * (int) MAX_S) rec = new float[rate * (int) MAX_S];
        int block = rate / 50;
        for (int off = 0; off + block <= fresh.length; off += block) {
            levelDb = LessonKit.blockDb(fresh, off, block);
            if (state == State.ARMED) {
                floorDb = levelDb < floorDb ? levelDb : floorDb * 0.99f + levelDb * 0.01f;
                if (levelDb > floorDb + 12 && levelDb > -50) { state = State.RECORDING; recLen = 0; silentMs = 0; }
            }
            if (state == State.RECORDING) {
                int n = Math.min(block, rec.length - recLen);
                System.arraycopy(fresh, off, rec, recLen, n);
                recLen += n;
                silentMs = levelDb < floorDb + 6 ? silentMs + 20 : 0;
                if (silentMs >= END_SILENCE_MS || recLen >= rec.length) done();
            }
        }
    }

    private void done() {
        float[] take = java.util.Arrays.copyOf(rec, Math.max(0, recLen - (int) (rate * END_SILENCE_MS / 1000) + rate / 10));
        List<Float> notes = MelodyTools.transcribe(take, rate);
        state = State.READY;
        if (notes.size() < 2) { note = "Не розчув мелодію — натисніть «🎤 Заспівати» ще раз"; return; }
        versions.add(notes);
        recordings.add(MelodyTools.to48k(take, rate));
        note = String.format(Locale.US, "Записано %d нот. Гравець %d: «👂 Послухати» і «🎤 Заспівати»", notes.size(), versions.size());
    }

    @Override public boolean animating() { return state == State.ARMED || state == State.RECORDING; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Зіпсований телефон", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Мелодія передається від гравця до гравця", 8 * dp, 40 * dp, 11.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        boolean result = state == State.RESULT;
        int rows = versions.size();
        float top = 56 * dp, rowH = Math.min(64 * dp, (h - 120 * dp) / Math.max(1, rows));
        for (int v = 0; v < rows; v++) {
            // Players do not see the earlier versions until the end (that would spoil the game).
            boolean hidden = !result;
            float y = top + v * rowH;
            k.fill.setColor(v == 0 ? 0xFF1E3A5F : 0xFF14213A);
            c.drawRoundRect(new RectF(8 * dp, y + 2 * dp, w - 8 * dp, y + rowH - 2 * dp), 8 * dp, 8 * dp, k.fill);
            k.label(c, v == 0 ? "Оригінал" : "Гравець " + v, 16 * dp, y + 18 * dp, 12, LessonKit.WHITE, Paint.Align.LEFT, true);
            if (v > 0 && !hidden) {
                float sim = similarity(v);
                k.label(c, String.format(Locale.US, "%.0f%%", sim * 100), w - 16 * dp, y + 18 * dp, 13,
                        sim >= 0.8f ? LessonKit.GREEN : sim >= 0.5f ? LessonKit.YELLOW : LessonKit.PINK, Paint.Align.RIGHT, true);
            }
            if (hidden) {
                k.label(c, v == 0 ? "🤫 таємниця до кінця" : "🤫 записано", w - 16 * dp, y + 18 * dp, 12, LessonKit.LABEL, Paint.Align.RIGHT, false);
                continue;
            }
            drawMelody(c, versions.get(v), 100 * dp, y + 6 * dp, w - 60 * dp, y + rowH - 8 * dp, v == 0 ? LessonKit.CYAN : LessonKit.YELLOW);
        }
        String msg = state == State.ARMED ? "🎤 Чекаю на спів…" : state == State.RECORDING ? "🔴 Записую… (пауза 1 с — кінець)" :
                result ? "Грає оригінал, а потім — що вийшло в кінці!" : note;
        k.label(c, msg, w / 2f, h - 20 * dp, 13, state == State.RECORDING ? LessonKit.PINK : LessonKit.CYAN, Paint.Align.CENTER, true);
    }

    /** Notes as dots on a relative pitch scale (centred on the melody's own average). */
    private void drawMelody(Canvas c, List<Float> m, float l, float t, float r, float b, int col) {
        if (m.isEmpty()) return;
        float mean = 0;
        for (float v : m) mean += v;
        mean /= m.size();
        float dx = (r - l) / Math.max(1, m.size()), prevX = 0, prevY = 0;
        for (int i = 0; i < m.size(); i++) {
            float x = l + dx * (i + 0.5f);
            float y = (t + b) / 2 - (m.get(i) - mean) / 12f * (b - t);
            y = Math.max(t + 4, Math.min(b - 4, y));
            if (i > 0) k.line(c, prevX, prevY, x, y, col & 0x88FFFFFF, 2);
            k.fill.setColor(col);
            c.drawCircle(x, y, 5 * k.dp, k.fill);
            k.label(c, MelodyTools.NAMES[((Math.round(m.get(i)) % 12) + 12) % 12], x, y - 7 * k.dp, 9, LessonKit.LABEL, Paint.Align.CENTER, false);
            prevX = x;
            prevY = y;
        }
    }
}
