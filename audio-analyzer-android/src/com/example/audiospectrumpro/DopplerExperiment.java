package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Arrays;
import java.util.Locale;

/**
 * Doppler effect: one phone plays a steady 4 kHz tone and moves (swung on a cord, carried past); the other
 * phone tracks the pitch precisely. Coming closer the tone is higher, going away lower; the spread gives the
 * speed: v = c·(fmax − fmin)/(fmax + fmin).
 */
final class DopplerExperiment implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); void stop(); }

    static final float F0 = 4000, C = 343;
    static final int KEEP = 600;                 // ~13 s of frames

    boolean source;
    private final LessonKit k;
    private final Sound sound;
    private final float[] freq = new float[KEEP];
    private int head, count;
    private float last = Float.NaN;

    DopplerExperiment(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
    }

    /** Exactly 4000 periods in one second, so the loop is seamless. */
    static float[] tone() { return LessonKit.carrierLoop(F0, 0.8f, 48000); }

    void start() {
        reset();
        if (source) sound.play(tone(), true); else sound.stop();
    }

    void reset() { head = 0; count = 0; last = Float.NaN; Arrays.fill(freq, Float.NaN); }

    /** Peak frequency near 4 kHz with sub-bin precision, NaN if the tone is not heard. */
    static float track(AudioEngine.Frame f) {
        int lo = (int) ((F0 - 400) / f.binHz), hi = Math.min(f.spectrumDb.length - 2, (int) ((F0 + 400) / f.binHz));
        int best = lo;
        for (int i = lo; i <= hi; i++) if (f.spectrumDb[i] > f.spectrumDb[best]) best = i;
        float peak = f.spectrumDb[best] - f.calibrationDb;
        // Must stand out: 20 dB above the bins 1 kHz away.
        int side = (int) (1000 / f.binHz);
        float around = Math.max(f.spectrumDb[Math.max(1, best - side)], f.spectrumDb[Math.min(f.spectrumDb.length - 1, best + side)]) - f.calibrationDb;
        if (peak < -75 || peak - around < 20) return Float.NaN;
        double a = f.spectrumDb[best - 1], b = f.spectrumDb[best], c = f.spectrumDb[best + 1];
        double den = a - 2 * b + c, d = den != 0 ? 0.5 * (a - c) / den : 0;
        return (float) ((best + Math.max(-0.5, Math.min(0.5, d))) * f.binHz);
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (source) return;
        last = track(f);
        freq[head] = last;
        head = (head + 1) % KEEP;
        count = Math.min(KEEP, count + 1);
    }

    /** {fmin, fmax} from the 2nd and 98th percentiles of what was heard, or null. */
    float[] range() {
        float[] v = new float[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            float x = freq[(head - count + i + KEEP) % KEEP];
            if (!Float.isNaN(x)) v[n++] = x;
        }
        if (n < 20) return null;
        Arrays.sort(v, 0, n);
        return new float[]{v[(int) (n * 0.02f)], v[(int) (n * 0.98f)]};
    }

    /** Speed of the moving phone (m/s), NaN if not enough data. */
    float speed() {
        float[] r = range();
        return r == null ? Float.NaN : C * (r[1] - r[0]) / (r[1] + r[0]);
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Ефект Доплера", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        if (source) {
            k.label(c, "Цей телефон — сирена 🚑", w / 2f, h * 0.35f, 20, LessonKit.CYAN, Paint.Align.CENTER, true);
            k.label(c, "Рівний тон 4000 Гц. Розкручуйте телефон на шнурку", w / 2f, h * 0.35f + 28 * dp, 12, LessonKit.SOFT,
                    Paint.Align.CENTER, false);
            k.label(c, "або пройдіть/пробіжіть повз слухача", w / 2f, h * 0.35f + 46 * dp, 12, LessonKit.SOFT, Paint.Align.CENTER, false);
            return;
        }
        k.label(c, "Цей телефон слухає і стежить за висотою тону", 8 * dp, 40 * dp, 11.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        RectF g = new RectF(44 * dp, 60 * dp, w - 12 * dp, h * 0.55f);
        k.rect(c, g.left, g.top, g.right, g.bottom, 0xFF0B1220);
        float dev = 20;                                   // scale follows the biggest shift heard
        for (int i = 0; i < count; i++) {
            float v = freq[(head - count + i + KEEP) % KEEP];
            if (!Float.isNaN(v)) dev = Math.max(dev, Math.abs(v - F0));
        }
        int step = dev > 150 ? 100 : dev > 70 ? 40 : 20;
        float span = (float) Math.ceil(dev * 1.15f / step) * step;
        for (int d = -(int) span; d <= span; d += step) {
            float y = g.centerY() - d / span * g.height() / 2;
            k.line(c, g.left, y, g.right, y, d == 0 ? 0xFF334155 : LessonKit.GRID, 1);
            k.label(c, (d > 0 ? "+" : "") + d, g.left - 4 * dp, y + 4 * dp, 9.5f, LessonKit.LABEL, Paint.Align.RIGHT, false);
        }
        k.label(c, "Гц від 4000", g.left, g.top - 4 * dp, 9.5f, LessonKit.LABEL, Paint.Align.LEFT, false);
        float px = Float.NaN, py = 0;
        for (int i = 0; i < count; i++) {
            float v = freq[(head - count + i + KEEP) % KEEP];
            float x = g.left + g.width() * i / KEEP;
            if (Float.isNaN(v)) { px = Float.NaN; continue; }
            float y = g.centerY() - Math.max(-span, Math.min(span, v - F0)) / span * g.height() / 2;
            if (!Float.isNaN(px)) k.line(c, px, py, x, y, v > F0 ? LessonKit.PINK : LessonKit.BLUE, 2);
            px = x;
            py = y;
        }
        float y = g.bottom + 26 * dp;
        k.label(c, Float.isNaN(last) ? "Тон не чути…" : String.format(Locale.US, "Зараз: %.1f Гц (%+.1f)", last, last - F0), 12 * dp, y, 14,
                Float.isNaN(last) ? LessonKit.LABEL : last > F0 ? LessonKit.PINK : LessonKit.BLUE, Paint.Align.LEFT, true);
        float[] r = range();
        if (r != null) {
            float v = speed();
            k.label(c, String.format(Locale.US, "Найвища %.1f Гц · найнижча %.1f Гц", r[1], r[0]), 12 * dp, y + 24 * dp, 12.5f,
                    LessonKit.SOFT, Paint.Align.LEFT, false);
            RectF box = new RectF(12 * dp, y + 40 * dp, w - 12 * dp, y + 104 * dp);
            k.fill.setColor(0xFF14213A);
            c.drawRoundRect(box, 10 * dp, 10 * dp, k.fill);
            k.label(c, String.format(Locale.US, "Швидкість телефона: %.1f м/с", v), box.left + 12 * dp, box.top + 26 * dp, 16, LessonKit.GREEN,
                    Paint.Align.LEFT, true);
            k.label(c, String.format(Locale.US, "= %.0f км/год  (v = c·Δf / (f₁+f₂))", v * 3.6f), box.left + 12 * dp, box.top + 48 * dp, 12,
                    LessonKit.SOFT, Paint.Align.LEFT, false);
        }
        k.label(c, "рожеве — наближається (вище), синє — віддаляється (нижче)", w / 2f, h - 14 * dp, 10.5f, LessonKit.LABEL,
                Paint.Align.CENTER, false);
    }
}
