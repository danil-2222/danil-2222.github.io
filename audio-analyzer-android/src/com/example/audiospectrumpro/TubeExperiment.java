package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Speed of sound from a tube or bottle neck of known length. A tube closed at one end sounds at
 * f = c / (4·(L + 0.3·d)); open at both ends f = c / (2·(L + 0.6·d)) (0.3·d per open end is the "end
 * correction"). Blow across the top or tap it, the phone finds f — and c = 4·f·L' (or 2·f·L').
 */
final class TubeExperiment implements LessonView {
    static final float LISTEN_S = 3f;

    float lengthM = 0.3f, diameterM = 0.025f;
    boolean open;
    private final LessonKit k;
    private double[] acc;
    private int frames, used;
    private float listenLeft, binHz = 1;
    private float freq = Float.NaN;
    private boolean listening;
    private float[] shown;
    private String status = "Натисніть «🎤 Слухати» і подуйте над трубкою";

    TubeExperiment(float dp) { k = new LessonKit(dp); }

    float effectiveLength() { return lengthM + (open ? 0.6f : 0.3f) * diameterM; }

    float expectedHz(float c) { return c / ((open ? 2 : 4) * effectiveLength()); }

    void listen() {
        acc = null;
        frames = used = 0;
        listenLeft = LISTEN_S;
        listening = true;
        freq = Float.NaN;
        status = "🎤 Слухаю… дуйте або стукайте!";
    }

    float frequency() { return freq; }

    float speed() { return Float.isNaN(freq) ? Float.NaN : freq * (open ? 2 : 4) * effectiveLength(); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (!listening) return;
        listenLeft -= (float) AudioEngine.HOP / f.sampleRate;
        binHz = f.binHz;
        if (acc == null || acc.length != f.spectrumDb.length) acc = new double[f.spectrumDb.length];
        frames++;
        // Average the power spectrum over the loud moments only.
        if (f.rmsDb - f.calibrationDb > -55) {
            used++;
            for (int i = 0; i < acc.length; i++) acc[i] += Math.pow(10, (f.spectrumDb[i] - f.calibrationDb) / 10);
        }
        if (listenLeft <= 0) { listening = false; analyse(); }
    }

    private void analyse() {
        if (used < 5) { status = "✗ Було затихо — дуйте сильніше або ближче до телефона"; return; }
        shown = new float[acc.length];
        for (int i = 0; i < acc.length; i++) shown[i] = (float) (10 * Math.log10(acc[i] / used + 1e-20));
        // Look for the lowest resonance near the expected pitch (between c = 250 and 450 m/s), so that
        // overtones or a hum from elsewhere are not taken for it.
        int lo = Math.max(2, (int) (expectedHz(250) / binHz)), hi = Math.min(acc.length - 2, (int) (expectedHz(450) / binHz) + 1);
        int best = lo;
        for (int i = lo; i <= hi; i++) if (shown[i] > shown[best]) best = i;
        float floor = median(shown, (int) (50 / binHz), (int) (5000 / binHz));
        if (shown[best] < floor + 10) { status = "✗ Чіткого тону немає — спробуйте ще (дуйте рівно, під кутом)"; return; }
        double a = shown[best - 1], b = shown[best], c = shown[best + 1], den = a - 2 * b + c;
        double d = den != 0 ? 0.5 * (a - c) / den : 0;
        freq = (float) ((best + Math.max(-0.5, Math.min(0.5, d))) * binHz);
        status = String.format(Locale.US, "✓ Тон трубки: %.1f Гц", freq);
        Journal.add("Трубка", String.format(Locale.US, "тон трубки %.1f см", lengthM * 100), freq, "Гц");
        Journal.add("Трубка", "швидкість звуку", speed(), "м/с");
    }

    private static float median(float[] v, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(v.length, Math.max(from + 1, to));
        float[] s = java.util.Arrays.copyOfRange(v, from, to);
        java.util.Arrays.sort(s);
        return s[s.length / 2];
    }

    @Override public boolean animating() { return listening; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Швидкість звуку з трубки", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, String.format(Locale.US, "%s трубка: L = %.1f см, d = %.1f см", open ? "відкрита з обох боків" : "закрита знизу",
                lengthM * 100, diameterM * 100), 8 * dp, 40 * dp, 11.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        // Tube picture with the standing wave (a quarter or a half wavelength fits inside).
        float tx = 20 * dp, ty = 60 * dp, tw = 46 * dp, th = h * 0.42f;
        k.stroke.setColor(LessonKit.SOFT);
        k.stroke.setStrokeWidth(3 * dp);
        c.drawLine(tx, ty, tx, ty + th, k.stroke);
        c.drawLine(tx + tw, ty, tx + tw, ty + th, k.stroke);
        if (!open) c.drawLine(tx, ty + th, tx + tw, ty + th, k.stroke);
        k.stroke.setColor(LessonKit.CYAN);
        k.stroke.setStrokeWidth(2 * dp);
        android.graphics.Path p = new android.graphics.Path();
        for (int i = 0; i <= 40; i++) {
            float y = ty + th * i / 40f;
            double amp = open ? Math.cos(Math.PI * i / 40.0) : Math.cos(Math.PI / 2 * i / 40.0);
            float x = tx + tw / 2 + (float) amp * tw * 0.4f;
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        c.drawPath(p, k.stroke);
        k.label(c, open ? "½ хвилі" : "¼ хвилі", tx + tw / 2, ty + th + 18 * dp, 11, LessonKit.CYAN, Paint.Align.CENTER, true);

        // Averaged spectrum with the found resonance.
        RectF g = new RectF(tx + tw + 20 * dp, ty, w - 12 * dp, ty + th);
        k.rect(c, g.left, g.top, g.right, g.bottom, 0xFF0B1220);
        float fmax = Math.max(1500, expectedHz(343) * 4);
        if (shown != null) {
            float px = g.left, py = g.bottom;
            for (int i = 1; i < shown.length && i * binHz < fmax; i++) {
                float x = g.left + g.width() * i * binHz / fmax;
                float y = g.bottom - Math.max(0, Math.min(1, (shown[i] + 100) / 80)) * g.height();
                k.line(c, px, py, x, y, LessonKit.SOFT, 1);
                px = x;
                py = y;
            }
            if (!Float.isNaN(freq)) {
                float x = g.left + g.width() * freq / fmax;
                k.line(c, x, g.top, x, g.bottom, LessonKit.YELLOW, 1.5f);
                k.label(c, String.format(Locale.US, "%.0f Гц", freq), x + 3 * dp, g.top + 14 * dp, 11, LessonKit.YELLOW, Paint.Align.LEFT, true);
            }
        }
        k.label(c, "0 … " + (int) fmax + " Гц", g.right - 4 * dp, g.bottom - 4 * dp, 9.5f, LessonKit.LABEL, Paint.Align.RIGHT, false);
        float y = ty + th + 44 * dp;
        k.label(c, status, 12 * dp, y, 13, status.startsWith("✗") ? LessonKit.PINK : LessonKit.CYAN, Paint.Align.LEFT, true);
        float cs = speed();
        if (!Float.isNaN(cs)) {
            RectF box = new RectF(12 * dp, y + 14 * dp, w - 12 * dp, y + 90 * dp);
            k.fill.setColor(0xFF14213A);
            c.drawRoundRect(box, 10 * dp, 10 * dp, k.fill);
            k.label(c, String.format(Locale.US, "c = %d·f·L' = %d × %.1f × %.3f = %.0f м/с", open ? 2 : 4, open ? 2 : 4, freq, effectiveLength(), cs),
                    box.left + 12 * dp, box.top + 24 * dp, 12.5f, LessonKit.WHITE, Paint.Align.LEFT, true);
            k.label(c, String.format(Locale.US, "Температура повітря ≈ %.0f °C", TemperatureExperiment.temperature(cs)), box.left + 12 * dp,
                    box.top + 48 * dp, 14, LessonKit.GREEN, Paint.Align.LEFT, true);
            k.label(c, "1 мм у довжині ≈ 1 м/с, тож міряйте лінійкою точно", box.left + 12 * dp, box.top + 66 * dp, 10, LessonKit.LABEL,
                    Paint.Align.LEFT, false);
        }
    }
}
