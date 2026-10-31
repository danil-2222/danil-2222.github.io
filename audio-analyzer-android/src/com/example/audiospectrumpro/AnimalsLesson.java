package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/** Pitch shown as the animal that makes such a sound, loudness as an everyday noise. No numbers needed. */
final class AnimalsLesson implements LessonView {
    static final String[] ANIMALS = {"🐋", "🐘", "🐻", "🧔", "👩", "🐱", "🐦", "🦟"};
    static final String[] ANIMAL_NAMES = {"кит", "слон", "ведмідь", "чоловік", "жінка", "кошеня", "пташка", "комар"};
    static final float[] ANIMAL_HZ = {30, 60, 110, 130, 230, 600, 2800, 7000};
    static final String[] NOISES = {"🤫", "🗣️", "🚿", "🏍️", "✈️"};
    static final String[] NOISE_NAMES = {"шепіт", "розмова", "душ", "мотоцикл", "літак"};
    static final float[] NOISE_DB = {30, 60, 75, 95, 120};

    private final LessonKit k;
    private float pitchPos = Float.NaN, loudPos = Float.NaN;
    private int animal = -1, noise = -1;

    AnimalsLesson(float dp) { k = new LessonKit(dp); }

    private static float pitchT(float hz) { return (float) (Math.log(hz / 25) / Math.log(9000 / 25.0)); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float level = f.rmsDb - f.calibrationDb;
        if (level < -65) { animal = -1; noise = -1; pitchPos = Float.NaN; loudPos = Float.NaN; return; }
        float hz = LessonRenderer.yin(f.time, f.sampleRate);
        if (hz <= 0) {
            // No clear pitch (noise, hiss): use where most of the energy is.
            double sum = 0, wsum = 0;
            for (int i = 1; i < f.spectrumDb.length; i++) {
                double p = Math.pow(10, (f.spectrumDb[i] - f.calibrationDb) / 10.0);
                sum += p;
                wsum += p * i * f.binHz;
            }
            hz = (float) (wsum / Math.max(1e-20, sum));
        }
        float tgt = pitchT(Math.max(25, hz));
        pitchPos = Float.isNaN(pitchPos) ? tgt : pitchPos * 0.7f + tgt * 0.3f;
        int best = 0;
        for (int i = 1; i < ANIMALS.length; i++) {
            if (Math.abs(pitchT(ANIMAL_HZ[i]) - pitchPos) < Math.abs(pitchT(ANIMAL_HZ[best]) - pitchPos)) best = i;
        }
        animal = best;
        float spl = f.calibrationDb != 0 ? f.rmsDb : level + 100;     // rough guess when not calibrated
        float lt = Math.max(0, Math.min(1, (spl - 20) / 110));
        loudPos = Float.isNaN(loudPos) ? lt : loudPos * 0.7f + lt * 0.3f;
        int nb = 0;
        for (int i = 1; i < NOISES.length; i++) if (Math.abs(NOISE_DB[i] - spl) < Math.abs(NOISE_DB[nb] - spl)) nb = i;
        noise = nb;
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float left = 26 * dp, right = w - 26 * dp;
        k.label(c, "На кого схожий звук?", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Що більше тіло — то нижчий голос", 8 * dp, 40 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);

        float y1 = h * 0.30f;
        k.line(c, left, y1, right, y1, LessonKit.GRID, 3);
        for (int i = 0; i < ANIMALS.length; i++) {
            float x = left + pitchT(ANIMAL_HZ[i]) * (right - left);
            boolean on = i == animal;
            k.label(c, ANIMALS[i], x, y1 - 10 * dp, on ? 40 : 24, LessonKit.WHITE, Paint.Align.CENTER, false);
            if (on) k.label(c, ANIMAL_NAMES[i], x, y1 + 20 * dp, 12, LessonKit.YELLOW, Paint.Align.CENTER, true);
        }
        k.label(c, "низько", left, y1 + 40 * dp, 10, LessonKit.LABEL, Paint.Align.LEFT, false);
        k.label(c, "високо", right, y1 + 40 * dp, 10, LessonKit.LABEL, Paint.Align.RIGHT, false);
        if (!Float.isNaN(pitchPos)) {
            float x = left + pitchPos * (right - left);
            k.fill.setColor(LessonKit.PINK);
            c.drawCircle(x, y1, 8 * dp, k.fill);
        }

        float y2 = h * 0.62f;
        k.line(c, left, y2, right, y2, LessonKit.GRID, 3);
        for (int i = 0; i < NOISES.length; i++) {
            float x = left + (NOISE_DB[i] - 20) / 110 * (right - left);
            boolean on = i == noise;
            k.label(c, NOISES[i], x, y2 - 10 * dp, on ? 38 : 24, LessonKit.WHITE, Paint.Align.CENTER, false);
            if (on) k.label(c, NOISE_NAMES[i], x, y2 + 20 * dp, 12, LessonKit.YELLOW, Paint.Align.CENTER, true);
        }
        k.label(c, "тихо", left, y2 + 40 * dp, 10, LessonKit.LABEL, Paint.Align.LEFT, false);
        k.label(c, "гучно", right, y2 + 40 * dp, 10, LessonKit.LABEL, Paint.Align.RIGHT, false);
        if (!Float.isNaN(loudPos)) {
            k.fill.setColor(LessonKit.PINK);
            c.drawCircle(left + loudPos * (right - left), y2, 8 * dp, k.fill);
        }
        String msg = animal < 0 ? "Скажіть щось або свисніть" :
                "Звучить як " + ANIMAL_NAMES[animal] + ", гучно як " + NOISE_NAMES[noise];
        k.label(c, msg, w / 2f, h - 20 * dp, 16, animal < 0 ? LessonKit.SOFT : LessonKit.CYAN, Paint.Align.CENTER, true);
    }
}
