package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rhythm echo: the app claps a pattern, you clap it back; each level is a harder pattern. */
final class RhythmEchoGame implements LessonView {
    interface Sound { void play(float[] pcm); }

    /** Gaps between claps, in beats (one beat = 500 ms). */
    static final float[][] PATTERNS = {
            {1, 1, 1}, {1, 0.5f, 0.5f, 1}, {0.5f, 0.5f, 1, 1}, {1, 0.5f, 0.5f, 0.5f, 0.5f},
            {0.5f, 1, 0.5f, 1}, {0.75f, 0.25f, 1, 0.5f, 0.5f}, {0.5f, 0.5f, 0.25f, 0.25f, 0.5f, 1}};
    static final float BEAT_MS = 500, TOLERANCE_MS = 110;

    enum State { IDLE, PLAYING, LISTENING, RESULT }

    private final LessonKit k;
    private final Sound sound;
    private final LessonKit.SampleTap tap = new LessonKit.SampleTap();
    private State state = State.IDLE;
    int level;
    private float clockMs, playUntil, lastOnsetMs = -1000, listenStart;
    private final float[] recent = new float[8];
    private int recentIdx;
    private final List<Float> claps = new ArrayList<>();
    private float[] errors;
    private boolean passed;

    RhythmEchoGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        java.util.Arrays.fill(recent, -120);
    }

    String state() { return state.name(); }
    boolean passed() { return passed; }
    float[] pattern() { return PATTERNS[Math.min(level, PATTERNS.length - 1)]; }

    void play() {
        int rate = 48000;
        float[] pat = pattern();
        float total = 0;
        for (float g : pat) total += g;
        float[] pcm = new float[(int) ((total * BEAT_MS / 1000 + 0.5) * rate)];
        java.util.Random r = new java.util.Random(3);
        float t = 0.1f;
        for (int i = 0; i <= pat.length; i++) {
            int at = (int) (t * rate);
            for (int j = 0; j < rate / 30 && at + j < pcm.length; j++) {
                pcm[at + j] += (float) ((r.nextFloat() * 2 - 1) * 0.8 * Math.exp(-j / (rate * 0.006)));
            }
            if (i < pat.length) t += pat[i] * BEAT_MS / 1000;
        }
        sound.play(pcm);
        state = State.PLAYING;
        playUntil = clockMs + pcm.length * 1000f / rate + 350;
        claps.clear();
        errors = null;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float[] fresh = tap.take(f);
        int n = Math.max(64, f.sampleRate / 200);                 // 5 ms blocks
        for (int off = 0; off + n <= fresh.length; off += n) {
            clockMs += 1000f * n / f.sampleRate;
            double e = 0;
            for (int i = 0; i < n; i++) e += fresh[off + i] * fresh[off + i];
            float db = (float) (10 * Math.log10(e / n + 1e-14));
            float ref = -120;
            for (float v : recent) ref = Math.max(ref, v);
            recent[recentIdx] = db;
            recentIdx = (recentIdx + 1) % recent.length;
            if (state == State.PLAYING && clockMs >= playUntil) {
                state = State.LISTENING;
                listenStart = clockMs;
            }
            if (state != State.LISTENING) continue;
            if (db > ref + 10 && db > -45 && clockMs - lastOnsetMs > 90) {
                lastOnsetMs = clockMs;
                claps.add(clockMs);
                if (claps.size() == pattern().length + 1) evaluate();
            }
            float since = claps.isEmpty() ? clockMs - listenStart : clockMs - claps.get(claps.size() - 1);
            if (state == State.LISTENING && since > 2500) evaluate();
        }
    }

    private void evaluate() {
        float[] pat = pattern();
        errors = new float[pat.length];
        passed = claps.size() == pat.length + 1;
        for (int i = 0; i < pat.length; i++) {
            if (i + 1 < claps.size()) {
                errors[i] = (claps.get(i + 1) - claps.get(i)) - pat[i] * BEAT_MS;
                if (Math.abs(errors[i]) > TOLERANCE_MS) passed = false;
            } else {
                errors[i] = Float.NaN;
            }
        }
        state = State.RESULT;
        if (passed) level = Math.min(level + 1, PATTERNS.length - 1);
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Відлуння ритму · рівень " + (level + 1), 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Послухайте оплески й повторіть їх так само", 8 * dp, 40 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
        float[] pat = pattern();
        float total = 0;
        for (float g : pat) total += g;
        float left = 20 * dp, right = w - 20 * dp, y1 = h * 0.35f, y2 = h * 0.5f;
        k.label(c, "зразок", left, y1 - 16 * dp, 10, LessonKit.LABEL, Paint.Align.LEFT, false);
        float t = 0;
        for (int i = 0; i <= pat.length; i++) {
            k.fill.setColor(LessonKit.CYAN);
            c.drawCircle(left + t / total * (right - left), y1, 9 * dp, k.fill);
            if (i < pat.length) t += pat[i];
        }
        k.label(c, "ви", left, y2 - 16 * dp, 10, LessonKit.LABEL, Paint.Align.LEFT, false);
        if (!claps.isEmpty()) {
            float first = claps.get(0);
            for (float cl : claps) {
                float x = left + (cl - first) / (total * BEAT_MS) * (right - left);
                k.fill.setColor(LessonKit.YELLOW);
                c.drawCircle(Math.min(right + 10 * dp, x), y2, 9 * dp, k.fill);
            }
        }
        String msg;
        switch (state) {
            case IDLE: msg = "Натисніть «Послухати ритм»"; break;
            case PLAYING: msg = "Слухайте…"; break;
            case LISTENING: msg = "Ваша черга! Плескайте (" + claps.size() + "/" + (pat.length + 1) + ")"; break;
            default: msg = passed ? "Чудово! Наступний рівень" : "Майже… спробуйте ще"; break;
        }
        k.label(c, msg, w / 2f, h * 0.68f, 18, state == State.RESULT && !passed ? LessonKit.PINK : LessonKit.YELLOW, Paint.Align.CENTER, true);
        if (errors != null) {
            StringBuilder sb = new StringBuilder("похибки: ");
            for (float e : errors) sb.append(Float.isNaN(e) ? "— " : String.format(Locale.US, "%+.0f ", e));
            k.label(c, sb.append("мс").toString(), w / 2f, h * 0.68f + 22 * dp, 12, LessonKit.SOFT, Paint.Align.CENTER, false);
        }
    }
}
