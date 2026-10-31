package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The inverse-square law: twice as far, the sound is 6 dB quieter (a quarter of the power). One phone
 * plays a steady band of noise (1-4 kHz, so room resonances average out), the other records the level at
 * several distances and fits dB against log2(distance).
 */
final class DistanceLawExperiment implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); void stop(); }

    static final float AVG_S = 2f, LO = 1000, HI = 4000;

    boolean source;
    float distance = 0.5f;
    private final LessonKit k;
    private final Sound sound;
    private final List<float[]> points = new ArrayList<>();     // {distance, dB}
    private double acc;
    private int n;
    private float left = -1, nowDb = -200;

    DistanceLawExperiment(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
    }

    /** One second of noise made of whole-hertz sines between 1 and 4 kHz: loops without a click. */
    static float[] bandNoise() {
        java.util.Random r = new java.util.Random(7);
        float[] out = new float[48000];
        for (int s = 0; s < 300; s++) {
            int f = 1000 + r.nextInt(3000);
            double ph = r.nextDouble() * 2 * Math.PI, w = 2 * Math.PI * f / 48000;
            for (int i = 0; i < out.length; i++) out[i] += (float) Math.sin(w * i + ph);
        }
        LessonKit.normalize(out, 0.8f);
        return out;
    }

    void start() { if (source) sound.play(bandNoise(), true); else sound.stop(); }

    void record() { acc = 0; n = 0; left = AVG_S; }

    void clear() { points.clear(); }

    int count() { return points.size(); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (source) return;
        double p = LessonKit.bandPower(f, LO, HI);
        nowDb = (float) (10 * Math.log10(p + 1e-20));
        if (left > 0) {
            acc += p;
            n++;
            left -= (float) AudioEngine.HOP / f.sampleRate;
            if (left <= 0) {
                points.add(new float[]{distance, (float) (10 * Math.log10(acc / n + 1e-20))});
                Journal.add("Закон −6 дБ", String.format(Locale.US, "рівень на %.2f м", distance), points.get(points.size() - 1)[1], "дБ");
                if (fit() != null) Journal.add("Закон −6 дБ", "спад на подвоєння відстані", fit()[0], "дБ");
            }
        }
    }

    /** {slope dB per doubling, intercept}, or null with fewer than two distances. */
    float[] fit() {
        int m = points.size();
        if (m < 2) return null;
        double mx = 0, my = 0;
        for (float[] p : points) { mx += Math.log(p[0]) / Math.log(2); my += p[1]; }
        mx /= m;
        my /= m;
        double sxx = 0, sxy = 0;
        for (float[] p : points) {
            double x = Math.log(p[0]) / Math.log(2) - mx;
            sxx += x * x;
            sxy += x * (p[1] - my);
        }
        if (sxx < 0.1) return null;
        return new float[]{(float) (sxy / sxx), (float) (my - sxy / sxx * mx)};
    }

    float slope() { float[] f = fit(); return f == null ? Float.NaN : f[0]; }

    @Override public boolean animating() { return left > 0; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Закон −6 дБ", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        if (source) {
            k.label(c, "Цей телефон — джерело шуму 🔊", w / 2f, h * 0.35f, 18, LessonKit.CYAN, Paint.Align.CENTER, true);
            k.label(c, "Покладіть його і не рухайте; гучність не змінюйте", w / 2f, h * 0.35f + 26 * dp, 12, LessonKit.SOFT,
                    Paint.Align.CENTER, false);
            return;
        }
        k.label(c, String.format(Locale.US, "Зараз: %.1f дБ · відстань %.2f м%s", nowDb, distance, left > 0 ? " · записую…" : ""), 8 * dp, 40 * dp,
                11.5f, left > 0 ? LessonKit.YELLOW : LessonKit.SOFT, Paint.Align.LEFT, false);
        // Plot: dB against distance on a doubling scale (0.25 .. 8 m).
        RectF g = new RectF(44 * dp, 60 * dp, w - 14 * dp, h * 0.6f);
        k.rect(c, g.left, g.top, g.right, g.bottom, 0xFF0B1220);
        float top = -200;
        for (float[] p : points) top = Math.max(top, p[1]);
        if (top < -150) top = -20;
        top = (float) Math.ceil(top / 6) * 6 + 6;
        float dbSpan = 36;
        float[] marks = {0.25f, 0.5f, 1, 2, 4, 8};
        for (float m : marks) {
            float x = g.left + g.width() * (float) ((Math.log(m / 0.25) / Math.log(2)) / 5);
            k.line(c, x, g.top, x, g.bottom, LessonKit.GRID, 1);
            k.label(c, (m < 1 ? String.format(Locale.US, "%.2f", m) : String.valueOf((int) m)) + "м", x, g.bottom + 14 * dp, 9.5f,
                    LessonKit.LABEL, Paint.Align.CENTER, false);
        }
        for (int d = 0; d <= dbSpan; d += 6) {
            float y = g.top + g.height() * d / dbSpan;
            k.line(c, g.left, y, g.right, y, LessonKit.GRID, 1);
            k.label(c, String.format(Locale.US, "%.0f", top - d), g.left - 4 * dp, y + 4 * dp, 9.5f, LessonKit.LABEL, Paint.Align.RIGHT, false);
        }
        float[] fit = fit();
        if (fit != null) {
            float x0 = 0.25f, x1 = 8;
            float y0 = fit[1] + fit[0] * (float) (Math.log(x0) / Math.log(2)), y1 = fit[1] + fit[0] * (float) (Math.log(x1) / Math.log(2));
            k.line(c, g.left, g.top + (top - y0) / dbSpan * g.height(), g.right, g.top + (top - y1) / dbSpan * g.height(), LessonKit.YELLOW, 1.5f);
        }
        for (float[] p : points) {
            float x = g.left + g.width() * (float) ((Math.log(p[0] / 0.25) / Math.log(2)) / 5);
            float y = g.top + (top - p[1]) / dbSpan * g.height();
            k.fill.setColor(LessonKit.CYAN);
            c.drawCircle(x, Math.max(g.top, Math.min(g.bottom, y)), 5 * dp, k.fill);
        }
        float y = g.bottom + 40 * dp;
        if (fit == null) {
            k.label(c, "Запишіть рівень на 0,5 м, 1 м, 2 м, 4 м…", w / 2f, y, 13, LessonKit.CYAN, Paint.Align.CENTER, true);
            return;
        }
        k.label(c, String.format(Locale.US, "Удвічі далі — на %.1f дБ тихіше", -fit[0]), w / 2f, y, 18,
                Math.abs(fit[0] + 6) < 1 ? LessonKit.GREEN : LessonKit.YELLOW, Paint.Align.CENTER, true);
        String why = Math.abs(fit[0] + 6) < 1 ? "Як у теорії: вдвічі більша сфера — вчетверо менше енергії"
                : fit[0] > -5 ? "Менше 6 дБ: луна кімнати «доливає» гучності вдалині"
                : "Більше 6 дБ: щось затуляє звук або телефон повернули";
        k.label(c, why, w / 2f, y + 22 * dp, 10.5f, LessonKit.SOFT, Paint.Align.CENTER, false);
    }
}
