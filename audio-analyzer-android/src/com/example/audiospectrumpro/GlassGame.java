package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.Locale;
import java.util.Random;

/** Break the glass: find its resonant note with your voice (any octave) and hold it steady for 2 s. */
final class GlassGame implements LessonView {
    interface Sound { void play(float[] pcm); }

    static final float HOLD_MS = 2000;
    private final LessonKit k;
    private final Sound sound;
    private final Random rnd = new Random();
    private int targetMidi = 57;
    private float energy;          // 0..1: how close to breaking
    private float cents = Float.NaN;
    private boolean broken;
    private int glasses;
    private final long start = System.currentTimeMillis();

    GlassGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        newGlass();
    }

    int targetMidi() { return targetMidi; }
    boolean broken() { return broken; }

    void newGlass() {
        targetMidi = 52 + rnd.nextInt(13);
        energy = 0;
        broken = false;
        ping();
    }

    /** Taps the glass so you can hear its note. */
    void ping() {
        int rate = 48000;
        float[] pcm = new float[rate * 2];
        double f = LessonKit.midiToHz(targetMidi);
        for (int i = 0; i < pcm.length; i++) {
            double t = (double) i / rate;
            pcm[i] = (float) (0.6 * Math.exp(-t * 2.5) * (Math.sin(2 * Math.PI * f * t) + 0.3 * Math.sin(2 * Math.PI * f * 2.76 * t)));
        }
        sound.play(pcm);
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (broken) return;
        float dt = 1000f * AudioEngine.HOP / f.sampleRate;
        float hz = f.rmsDb - f.calibrationDb > -50 ? LessonRenderer.yin(f.time, f.sampleRate) : 0;
        if (hz <= 0) {
            cents = Float.NaN;
            energy = Math.max(0, energy - dt / 3000);
            return;
        }
        double c = 1200 * Math.log(hz / LessonKit.midiToHz(targetMidi)) / Math.log(2);
        cents = (float) (((c % 1200) + 1800) % 1200 - 600);       // any octave counts
        if (Math.abs(cents) < 30) energy += dt / HOLD_MS;
        else energy = Math.max(0, energy - dt / 1500);
        if (energy >= 1) {
            broken = true;
            glasses++;
        }
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float t = (System.currentTimeMillis() - start) / 1000f;
        k.label(c, "Розбий склянку голосом", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Склянка «співає» ноту " + LessonRenderer.noteUa(targetMidi) + ". Знайдіть її й тримайте!", 8 * dp, 40 * dp, 12,
                LessonKit.SOFT, Paint.Align.LEFT, false);
        float cx = w / 2f, top = h * 0.2f, bottom = h * 0.62f, half = w * 0.16f;
        float shake = broken ? 0 : (float) Math.sin(t * 60) * energy * 6 * dp;
        if (!broken) {
            k.stroke.setColor(0xCCB3E5FC);
            k.stroke.setStrokeWidth(3 * dp);
            k.path.reset();
            k.path.moveTo(cx - half + shake, top);
            k.path.quadTo(cx - half * 0.9f + shake, bottom - 40 * dp, cx + shake, bottom - 30 * dp);
            k.path.quadTo(cx + half * 0.9f + shake, bottom - 40 * dp, cx + half + shake, top);
            c.drawPath(k.path, k.stroke);
            c.drawLine(cx + shake, bottom - 30 * dp, cx, bottom, k.stroke);
            c.drawLine(cx - half * 0.6f, bottom, cx + half * 0.6f, bottom, k.stroke);
            // Cracks appear as the energy grows.
            int cracks = (int) (energy * 6);
            for (int i = 0; i < cracks; i++) {
                float x0 = cx - half * 0.6f + i * half * 0.25f + shake, y0 = top + 10 * dp + i * 8 * dp;
                k.line(c, x0, y0, x0 + 12 * dp, y0 + 20 * dp, 0xFFFFFFFF, 1.5f);
                k.line(c, x0 + 12 * dp, y0 + 20 * dp, x0 + 4 * dp, y0 + 34 * dp, 0xFFFFFFFF, 1.5f);
            }
        } else {
            Random r = new Random(7);
            for (int i = 0; i < 18; i++) {
                float x = cx + (r.nextFloat() - 0.5f) * w * 0.7f, y = bottom - r.nextFloat() * 30 * dp;
                k.line(c, x, y, x + (r.nextFloat() - 0.5f) * 20 * dp, y - r.nextFloat() * 14 * dp, 0xCCB3E5FC, 2.5f);
            }
            k.label(c, "ДЗЕНЬ! 💥", cx, (top + bottom) / 2, 40, LessonKit.YELLOW, Paint.Align.CENTER, true);
        }
        // Tuning meter.
        float my = h * 0.74f, mw = w * 0.38f;
        k.line(c, cx - mw, my, cx + mw, my, LessonKit.GRID, 6);
        k.rect(c, cx - mw * 30 / 100f, my - 5 * dp, cx + mw * 30 / 100f, my + 5 * dp, 0x5539FF14);
        if (!Float.isNaN(cents)) {
            k.fill.setColor(Math.abs(cents) < 30 ? LessonKit.GREEN : LessonKit.PINK);
            c.drawCircle(cx + Math.max(-1, Math.min(1, cents / 100)) * mw, my, 9 * dp, k.fill);
        }
        k.label(c, "нижче", cx - mw, my + 20 * dp, 10, LessonKit.LABEL, Paint.Align.LEFT, false);
        k.label(c, "вище", cx + mw, my + 20 * dp, 10, LessonKit.LABEL, Paint.Align.RIGHT, false);
        k.rect(c, cx - mw, my + 30 * dp, cx - mw + 2 * mw * Math.min(1, energy), my + 38 * dp, LessonKit.YELLOW);
        String msg = broken ? "Розбито! Це резонанс — ваш голос розгойдав склянку" : Float.isNaN(cents) ? "Співайте…"
                : Math.abs(cents) < 30 ? "Так! Тримайте рівно…" : cents < 0 ? "Трохи вище" : "Трохи нижче";
        k.label(c, msg, cx, h - 30 * dp, 15, broken ? LessonKit.GREEN : LessonKit.CYAN, Paint.Align.CENTER, true);
        k.label(c, "Розбито склянок: " + glasses, cx, h - 10 * dp, 10.5f, LessonKit.LABEL, Paint.Align.CENTER, false);
    }
}
