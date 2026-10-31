package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * A string vibrating with the harmonics of the sound you make: the full motion on top, and each
 * mode (1x, 2x, 3x...) separately with its nodes underneath. Motion is slowed down.
 */
final class StringLesson implements LessonView {
    private static final int K = 8;
    private final LessonKit k;
    private final float[] amp = new float[K + 1];
    private float f0;
    private final long start = System.currentTimeMillis();

    StringLesson(float dp) { k = new LessonKit(dp); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        SoundExplainer.Result r = SoundExplainer.explain(f, 440);
        boolean tone = r.f0 > 0 && (r.kind == SoundExplainer.Kind.HARMONIC || r.kind == SoundExplainer.Kind.PURE_TONE);
        float max = 0;
        float[] lin = new float[K + 1];
        for (int i = 1; i <= K; i++) {
            float db = tone ? r.harmonicDb[i - 1] : Float.NaN;
            lin[i] = Float.isNaN(db) ? 0 : (float) Math.pow(10, (db - f.calibrationDb) / 20);
            max = Math.max(max, lin[i]);
        }
        for (int i = 1; i <= K; i++) {
            float target = max > 0 ? lin[i] / max : 0;
            amp[i] = amp[i] * 0.7f + target * 0.3f;
        }
        if (tone) f0 = r.f0;
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        double t = (System.currentTimeMillis() - start) / 1000.0;
        double w1 = 2 * Math.PI * 0.6; // fundamental: 0.6 swings per second on screen
        float sum = 0;
        for (int i = 1; i <= K; i++) sum += amp[i];
        k.label(c, "Струна, що співає", 8 * dp, 20 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, sum > 0.05f ? String.format(Locale.US, "Основний тон %s · рух сповільнено", SpectrumMath.formatHz(f0))
                : "Заспівайте або зіграйте ноту", 8 * dp, 36 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);

        float left = 24 * dp, right = w - 24 * dp, len = right - left;
        // The whole string.
        float cy = 90 * dp, a = 34 * dp;
        drawString(c, left, right, cy, a, 0, t, w1, LessonKit.YELLOW, 3, sum);
        k.fill.setColor(LessonKit.SOFT);
        c.drawCircle(left, cy, 4 * dp, k.fill);
        c.drawCircle(right, cy, 4 * dp, k.fill);
        k.label(c, "уся струна = сума всіх мод", w / 2f, cy + a + 16 * dp, 10, LessonKit.LABEL, Paint.Align.CENTER, false);

        // Each mode on its own row.
        float rowTop = cy + a + 32 * dp, rowH = (h - rowTop - 10 * dp) / 6;
        int[] colors = {LessonKit.CYAN, LessonKit.GREEN, LessonKit.ORANGE, LessonKit.PINK, 0xFFB388FF, 0xFF64FFDA};
        for (int m = 1; m <= 6; m++) {
            float y = rowTop + (m - 0.5f) * rowH;
            float ra = Math.min(rowH * 0.38f, 16 * dp);
            int col = amp[m] > 0.08f ? colors[m - 1] : 0xFF334155;
            drawString(c, left + 56 * dp, right, y, ra, m, t, w1, col, 2, 1);
            for (int n = 0; n <= m; n++) {
                float x = left + 56 * dp + (right - left - 56 * dp) * n / m;
                k.fill.setColor(LessonKit.WHITE);
                c.drawCircle(x, y, 2.5f * dp, k.fill);
            }
            k.label(c, m == 1 ? "1× f0" : m + "×", left, y - 2 * dp, 11, col, Paint.Align.LEFT, true);
            k.label(c, String.format(Locale.US, "%.0f%%", amp[m] * 100), left, y + 11 * dp, 9, LessonKit.LABEL, Paint.Align.LEFT, false);
        }
    }

    /** mode 0 draws the sum of all modes weighted by amp[], otherwise a single mode at full size. */
    private void drawString(Canvas c, float x0, float x1, float cy, float a, int mode, double t, double w1,
                            int color, float widthDp, float sum) {
        k.path.reset();
        int n = 120;
        for (int i = 0; i <= n; i++) {
            double u = (double) i / n;
            double y = 0;
            if (mode == 0) {
                for (int m = 1; m <= K; m++) y += amp[m] * Math.sin(m * Math.PI * u) * Math.cos(m * w1 * t + m);
                y /= Math.max(0.5f, sum * 0.55f);
            } else {
                y = amp[mode] * Math.sin(mode * Math.PI * u) * Math.cos(mode * w1 * t + mode);
            }
            float px = x0 + (float) u * (x1 - x0), py = cy - (float) y * a;
            if (i == 0) k.path.moveTo(px, py); else k.path.lineTo(px, py);
        }
        k.stroke.setColor(color);
        k.stroke.setStrokeWidth(widthDp * k.dp);
        c.drawPath(k.path, k.stroke);
    }
}
