package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.Locale;
import java.util.Random;

/** Ear trainer: which note is higher, name the interval, sing back a note. */
final class EarLesson implements LessonView {
    interface Sound { void play(float[] pcm); }

    enum Game { HIGHER, INTERVAL, SING }

    static final String[] INTERVAL_NAMES = {"Мала секунда", "Велика секунда", "Мала терція", "Велика терція",
            "Кварта", "Квінта", "Октава"};
    static final int[] INTERVAL_STEPS = {1, 2, 3, 4, 5, 7, 12};

    private final LessonKit k;
    private final Sound sound;
    private final Random rnd = new Random();
    private final int rate;
    Game game = Game.HIGHER;

    private float[] lastSound;
    private boolean waiting;
    private float centsDiff = 100;
    private boolean firstHigher;
    private int intervalIdx;
    private int targetMidi = 57;
    private float singHz, holdMs;
    private String status = "Натисніть «Нове завдання»";
    private int statusColor = LessonKit.SOFT;
    private int score, total, streak, best;
    private final boolean[] results = new boolean[20];
    private int resultCount;

    EarLesson(float dp, int rate, Sound sound) {
        k = new LessonKit(dp);
        this.rate = rate;
        this.sound = sound;
    }

    void setGame(Game g) {
        game = g;
        waiting = false;
        status = "Натисніть «Нове завдання»";
        statusColor = LessonKit.SOFT;
    }

    void newTask() {
        int len = (int) (rate * 0.9f), gap = (int) (rate * 0.25f);
        switch (game) {
            case HIGHER: {
                float base = LessonKit.midiToHz(55 + rnd.nextInt(17));
                firstHigher = rnd.nextBoolean();
                float other = (float) (base * Math.pow(2, centsDiff / 1200.0));
                float[] pcm = new float[len * 2 + gap];
                LessonKit.addNote(pcm, 0, len, firstHigher ? other : base, 0.8f, rate);
                LessonKit.addNote(pcm, len + gap, len, firstHigher ? base : other, 0.8f, rate);
                lastSound = pcm;
                status = "Яка нота вища — перша чи друга?";
                break;
            }
            case INTERVAL: {
                intervalIdx = rnd.nextInt(INTERVAL_STEPS.length);
                int low = 52 + rnd.nextInt(12);
                float[] pcm = new float[len * 3 + gap * 2];
                LessonKit.addNote(pcm, 0, len, LessonKit.midiToHz(low), 0.8f, rate);
                LessonKit.addNote(pcm, len + gap, len, LessonKit.midiToHz(low + INTERVAL_STEPS[intervalIdx]), 0.8f, rate);
                // Then both together, which is how intervals usually sound in music.
                LessonKit.addNote(pcm, 2 * (len + gap), len, LessonKit.midiToHz(low), 0.6f, rate);
                LessonKit.addNote(pcm, 2 * (len + gap), len, LessonKit.midiToHz(low + INTERVAL_STEPS[intervalIdx]), 0.6f, rate);
                lastSound = pcm;
                status = "Який це інтервал?";
                break;
            }
            default: {
                targetMidi = 48 + rnd.nextInt(17); // C3..E4, comfortable for most voices
                float[] pcm = new float[len * 2];
                LessonKit.addNote(pcm, 0, len * 2, LessonKit.midiToHz(targetMidi), 0.9f, rate);
                lastSound = pcm;
                holdMs = 0;
                status = "Заспівайте цю ноту і тримайте її";
                break;
            }
        }
        statusColor = LessonKit.WHITE;
        waiting = true;
        sound.play(lastSound);
    }

    void replay() {
        if (lastSound != null) sound.play(lastSound);
    }

    /** @return true if correct. choice: HIGHER 0 = first, 1 = second; INTERVAL index. */
    boolean answer(int choice) {
        if (!waiting || game == Game.SING) return false;
        boolean ok;
        String truth;
        if (game == Game.HIGHER) {
            ok = (choice == 0) == firstHigher;
            truth = firstHigher ? "вища була перша" : "вища була друга";
            centsDiff = ok ? Math.max(3, centsDiff * 0.75f) : Math.min(200, centsDiff * 1.5f);
        } else {
            ok = choice == intervalIdx;
            truth = "це " + INTERVAL_NAMES[intervalIdx].toLowerCase(Locale.ROOT);
        }
        record(ok);
        status = ok ? "Правильно! ✓" : "Ні — " + truth;
        statusColor = ok ? LessonKit.GREEN : LessonKit.PINK;
        waiting = false;
        return ok;
    }

    private void record(boolean ok) {
        total++;
        if (ok) {
            score++;
            streak++;
            best = Math.max(best, streak);
        } else {
            streak = 0;
        }
        results[resultCount % results.length] = ok;
        resultCount++;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (game != Game.SING) return;
        singHz = LessonRenderer.yin(f.time, f.sampleRate);
        if (!waiting || singHz <= 0) return;
        double cents = 1200 * Math.log(singHz / LessonKit.midiToHz(targetMidi)) / Math.log(2);
        // Allow singing in another octave: compare within one octave.
        double folded = ((cents % 1200) + 1800) % 1200 - 600;
        if (Math.abs(folded) < 30) {
            holdMs += 1000f * AudioEngine.HOP / f.sampleRate;
            if (holdMs > 700) {
                record(true);
                status = "Влучили! ✓ Тримали ноту рівно";
                statusColor = LessonKit.GREEN;
                waiting = false;
            }
        } else {
            holdMs = Math.max(0, holdMs - 30);
        }
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        String title = game == Game.HIGHER ? "Яка нота вища?" : game == Game.INTERVAL ? "Вгадай інтервал" : "Повтори ноту голосом";
        k.label(c, title, 8 * dp, 20 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, String.format(Locale.US, "Рахунок %d з %d · серія %d · рекорд %d", score, total, streak, best),
                8 * dp, 36 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);
        k.label(c, status, w / 2f, h * 0.30f, 18, statusColor, Paint.Align.CENTER, true);
        if (game == Game.HIGHER) {
            k.label(c, String.format(Locale.US, "Різниця зараз: %.0f центів (півтон = 100)", centsDiff), w / 2f, h * 0.30f + 24 * dp,
                    11, LessonKit.SOFT, Paint.Align.CENTER, false);
            k.label(c, "Правильна відповідь зменшує різницю — гра стає складнішою", w / 2f, h * 0.30f + 40 * dp, 10,
                    LessonKit.LABEL, Paint.Align.CENTER, false);
        } else if (game == Game.SING) {
            String target = LessonRenderer.noteUa(targetMidi);
            k.label(c, "Ціль: " + target, w / 2f, h * 0.30f + 26 * dp, 13, LessonKit.CYAN, Paint.Align.CENTER, true);
            float cy = h * 0.55f, half = w * 0.4f;
            k.line(c, w / 2f - half, cy, w / 2f + half, cy, LessonKit.GRID, 6);
            k.rect(c, w / 2f - half * 30 / 100f, cy - 4 * dp, w / 2f + half * 30 / 100f, cy + 4 * dp, 0x5539FF14);
            if (singHz > 0) {
                double cents = 1200 * Math.log(singHz / LessonKit.midiToHz(targetMidi)) / Math.log(2);
                double folded = ((cents % 1200) + 1800) % 1200 - 600;
                float x = w / 2f + (float) Math.max(-100, Math.min(100, folded)) / 100f * half;
                k.fill.setColor(Math.abs(folded) < 30 ? LessonKit.GREEN : LessonKit.PINK);
                c.drawCircle(x, cy, 9 * dp, k.fill);
                k.label(c, String.format(Locale.US, "%s %+.0f¢", folded < 0 ? "нижче" : "вище", folded), w / 2f, cy + 30 * dp,
                        12, LessonKit.SOFT, Paint.Align.CENTER, false);
            } else {
                k.label(c, "співайте…", w / 2f, cy + 30 * dp, 12, LessonKit.LABEL, Paint.Align.CENTER, false);
            }
            k.rect(c, w / 2f - half, cy + 44 * dp, w / 2f - half + 2 * half * Math.min(1, holdMs / 700f), cy + 50 * dp, LessonKit.GREEN);
        }
        // Last answers as coloured squares.
        int n = Math.min(resultCount, results.length);
        float sz = 14 * dp, y = h - 30 * dp;
        k.label(c, "Останні відповіді:", 8 * dp, y - 6 * dp, 10, LessonKit.LABEL, Paint.Align.LEFT, false);
        for (int i = 0; i < n; i++) {
            boolean ok = results[(resultCount - n + i) % results.length];
            k.rect(c, 8 * dp + i * (sz + 3 * dp), y, 8 * dp + i * (sz + 3 * dp) + sz, y + sz, ok ? LessonKit.GREEN : LessonKit.PINK);
        }
    }
}
