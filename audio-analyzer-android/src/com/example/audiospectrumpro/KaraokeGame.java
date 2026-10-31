package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Karaoke score: the melody scrolls as bars on a pitch scale, your voice is drawn as a line over them.
 * Every note is scored by how close (in cents, any octave) the voice stays to it. The phone does not play
 * the melody while you sing (it would hear itself) — use "Послухати" before.
 */
final class KaraokeGame implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); }

    enum State { READY, LISTEN, COUNT, SING, RESULT }

    static final String[] SONGS = {"З днем народження", "Жили у бабусі", "Зірочка (Twinkle)", "Гама"};
    static final float[][] MIDI = {
            {55, 55, 57, 55, 60, 59, 55, 55, 57, 55, 62, 60, 55, 55, 67, 64, 60, 59, 57, 65, 65, 64, 60, 62, 60},
            {65, 64, 62, 60, 67, 67, 65, 64, 62, 60, 67, 67, 65, 69, 69, 65, 64, 67, 67, 64, 62, 64, 65, 62, 60, 60},
            {60, 60, 67, 67, 69, 69, 67, 65, 65, 64, 64, 62, 62, 60},
            {60, 62, 64, 65, 67, 69, 71, 72, 71, 69, 67, 65, 64, 62, 60}};
    static final float[][] BEATS = {
            {0.75f, 0.25f, 1, 1, 1, 2, 0.75f, 0.25f, 1, 1, 1, 2, 0.75f, 0.25f, 1, 1, 1, 1, 2, 0.75f, 0.25f, 1, 1, 1, 2},
            {1, 1, 1, 1, 2, 2, 1, 1, 1, 1, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2},
            {1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 2},
            {1, 1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1, 2}};
    static final float BEAT_S = 0.6f, COUNT_BEATS = 4;

    int song;
    private final LessonKit k;
    private final Sound sound;
    private State state = State.READY;
    private float clock, songAt, until;
    private float[] start, end;               // note times (s from the first note)
    private double[] sum;
    private java.util.List<java.util.List<Float>> cents;
    private int[] frames, voiced;
    private final float[] trace = new float[700];
    private final float[] traceT = new float[700];
    private int traceN;

    KaraokeGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        layout();
    }

    void setSong(int i) {
        song = i;
        layout();
        state = State.READY;
    }

    private void layout() {
        int n = MIDI[song].length;
        start = new float[n];
        end = new float[n];
        float t = 0;
        for (int i = 0; i < n; i++) { start[i] = t; t += BEATS[song][i] * BEAT_S; end[i] = t; }
        sum = new double[n];
        frames = new int[n];
        voiced = new int[n];
        cents = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) cents.add(new java.util.ArrayList<Float>());
        traceN = 0;
    }

    static float[] melody(int song) {
        int rate = 48000;
        float total = 0;
        for (float b : BEATS[song]) total += b * BEAT_S;
        float[] out = new float[(int) ((total + 0.5f) * rate)];
        float t = 0;
        for (int i = 0; i < MIDI[song].length; i++) {
            LessonKit.addNote(out, (int) (t * rate), (int) (BEATS[song][i] * BEAT_S * rate), LessonKit.midiToHz(MIDI[song][i]), 0.6f, rate);
            t += BEATS[song][i] * BEAT_S;
        }
        return out;
    }

    /** Play the tune to learn it (not scored). */
    void listen() {
        layout();
        float[] m = melody(song);
        sound.play(m, false);
        state = State.LISTEN;
        songAt = clock + 0.15f;
        until = songAt + m.length / 48000f;
    }

    /** Count-in, then sing. */
    void sing() {
        layout();
        int rate = 48000;
        float[] clicks = new float[(int) (COUNT_BEATS * BEAT_S * rate) + rate / 10];
        for (int b = 0; b < COUNT_BEATS; b++) LessonKit.addTone(clicks, (int) (b * BEAT_S * rate), rate / 30, b == COUNT_BEATS - 1 ? 1800 : 1200, 0.4f, rate, rate / 400);
        sound.play(clicks, false);
        songAt = clock + COUNT_BEATS * BEAT_S;
        until = songAt + end[end.length - 1] + 0.3f;
        state = State.COUNT;
    }

    String state() { return state.name(); }

    /** Score of note i (0..100), NaN if not sung yet. */
    /**
     * Accuracy over the moments the voice sounded, reduced only if less than 60 % of the note was sung
     * (breaths and note changes are fine).
     */
    float noteScore(int i) {
        if (frames[i] == 0) return Float.NaN;
        if (voiced[i] == 0) return 0;
        float cover = Math.min(1, voiced[i] / (0.6f * frames[i]));
        // The median pitch of the note: vibrato and the glide into it do not cost points.
        float c = MelodyTools.median(cents.get(i));
        return Math.max(0, 100 - 2 * Math.abs(c)) * cover;
    }

    float total() {
        double s = 0;
        for (int i = 0; i < sum.length; i++) { float v = noteScore(i); s += Float.isNaN(v) ? 0 : v; }
        return (float) (s / sum.length);
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        clock += (float) AudioEngine.HOP / f.sampleRate;
        if (state == State.LISTEN && clock > until) state = State.READY;
        if (state == State.COUNT && clock >= songAt) state = State.SING;
        if (state != State.SING) return;
        // The pitch detector looks at the newest ~40 ms of the window, so the sound is ~20 ms old.
        float t = clock - songAt - 0.02f;
        if (clock > until) { state = State.RESULT; return; }
        float hz = f.rmsDb - f.calibrationDb > -50 ? LessonRenderer.yin(f.time, f.sampleRate) : 0;
        float m = hz > 60 ? MelodyTools.hzToMidi(hz) : Float.NaN;
        boolean inNote = false;
        for (int i = 0; i < start.length; i++) {
            // Score the body of each note (singers and phones both lag a little).
            if (t < start[i] + 0.06f || t > end[i] + 0.06f) continue;
            inNote = true;
            frames[i]++;
            if (!Float.isNaN(m)) {
                voiced[i]++;
                float c = SingNoteGame.centsOff(m, MIDI[song][i]);
                cents.get(i).add(c);
                sum[i] += Math.max(0, 100 - 2 * Math.abs(c));
                // Draw the voice in the octave of the melody.
                m = MIDI[song][i] + c / 100f;
            }
        }
        if (!inNote) m = Float.NaN;                      // between notes the octave is unknown: do not draw
        if (traceN < trace.length) { trace[traceN] = m; traceT[traceN] = t; traceN++; }
    }

    @Override public boolean animating() { return state != State.READY && state != State.RESULT; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Караоке — «" + SONGS[song] + "»", 8 * dp, 22 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        float lo = 200, hi = 0;
        for (float m : MIDI[song]) { lo = Math.min(lo, m); hi = Math.max(hi, m); }
        lo -= 3;
        hi += 3;
        RectF g = new RectF(36 * dp, 36 * dp, w - 8 * dp, h * 0.7f);
        k.rect(c, g.left, g.top, g.right, g.bottom, 0xFF0B1220);
        for (int m = (int) lo; m <= hi; m++) {
            if (m % 12 != 0 && m % 12 != 7) continue;
            float y = g.bottom - (m - lo) / (hi - lo) * g.height();
            k.line(c, g.left, y, g.right, y, LessonKit.GRID, 1);
            k.label(c, MelodyTools.noteName(m), g.left - 3 * dp, y + 4 * dp, 9, LessonKit.LABEL, Paint.Align.RIGHT, false);
        }
        boolean result = state == State.RESULT;
        // While singing the view scrolls (now at 25 % of the width, 6 s visible); the result shows everything.
        float total = end[end.length - 1];
        float now = state == State.SING || state == State.LISTEN ? clock - songAt : state == State.COUNT ? clock - songAt : 0;
        float view0 = result ? 0 : now - 1.5f, viewS = result ? total + 0.3f : 6f;
        float noteH = g.height() / (hi - lo);
        for (int i = 0; i < start.length; i++) {
            float x0 = g.left + (start[i] - view0) / viewS * g.width(), x1 = g.left + (end[i] - view0) / viewS * g.width() - 2 * dp;
            if (x1 < g.left || x0 > g.right) continue;
            float y = g.bottom - (MIDI[song][i] - lo) / (hi - lo) * g.height();
            float sc = noteScore(i);
            int col = Float.isNaN(sc) ? 0xFF334155 : sc >= 80 ? 0xFF2E7D32 : sc >= 50 ? 0xFF9E9D24 : 0xFF8E2442;
            k.fill.setColor(col);
            c.drawRoundRect(new RectF(Math.max(g.left, x0), y - noteH * 0.45f, Math.min(g.right, x1), y + noteH * 0.45f), 4 * dp, 4 * dp, k.fill);
        }
        float px = Float.NaN, py = 0;
        for (int i = 0; i < traceN; i++) {
            float x = g.left + (traceT[i] - view0) / viewS * g.width();
            if (Float.isNaN(trace[i]) || x < g.left || x > g.right) { px = Float.NaN; continue; }
            float y = g.bottom - Math.max(0, Math.min(1, (trace[i] - lo) / (hi - lo))) * g.height();
            if (!Float.isNaN(px)) k.line(c, px, py, x, y, LessonKit.CYAN, 2.5f);
            px = x;
            py = y;
        }
        if (!result) {
            float nx = g.left + (now - view0) / viewS * g.width();
            k.line(c, nx, g.top, nx, g.bottom, LessonKit.WHITE, 1.5f);
        }
        String msg;
        switch (state) {
            case READY: msg = "«👂 Послухати» — вивчити мелодію, «🎤 Співати» — на оцінку"; break;
            case LISTEN: msg = "👂 Слухайте мелодію…"; break;
            case COUNT: msg = "Приготуйтесь… " + (int) Math.ceil(songAt - clock); break;
            case SING: msg = "🎤 Співайте на «ла-ла»! Голос — блакитна лінія"; break;
            default: {
                float s = total();
                int stars = s >= 85 ? 3 : s >= 65 ? 2 : s >= 40 ? 1 : 0;
                msg = String.format(Locale.US, "%s  %.0f зі 100", "★★★".substring(0, stars) + "☆☆☆".substring(stars), s);
            }
        }
        k.label(c, msg, w / 2f, g.bottom + 30 * dp, state == State.RESULT ? 24 : 13, state == State.RESULT ? LessonKit.YELLOW : LessonKit.SOFT,
                Paint.Align.CENTER, true);
        if (result) k.label(c, "зелені ноти — влучили, жовті — майже, червоні — мимо", w / 2f, g.bottom + 54 * dp, 11, LessonKit.LABEL,
                Paint.Align.CENTER, false);
    }
}
