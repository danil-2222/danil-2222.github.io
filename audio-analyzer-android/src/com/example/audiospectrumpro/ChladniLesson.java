package com.example.audiospectrumpro;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chladni figures on a square plate (classic approximation): sand gathers on the nodal lines of
 * cos(nπx)cos(mπy) − cos(mπx)cos(nπy). Higher frequencies excite modes with more lines.
 */
final class ChladniLesson implements LessonView {
    static final float HZ_PER_UNIT = 40;   // f(n, m) ≈ 40 Hz · (n² + m²)
    private static final int RES = 140;

    /** Use the microphone's dominant frequency (live) instead of the slider frequency. */
    boolean live = true;
    volatile float driveHz = 520;

    private final LessonKit k;
    private final List<int[]> modes = new ArrayList<>();
    private int modeIdx = -1, shownIdx = -1;
    private Bitmap plate;
    private float heardHz;
    private int quiet;

    ChladniLesson(float dp) {
        k = new LessonKit(dp);
        for (int n = 0; n <= 9; n++) for (int m = n + 1; m <= 10; m++) modes.add(new int[]{n, m});
        java.util.Collections.sort(modes, new java.util.Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return (a[0] * a[0] + a[1] * a[1]) - (b[0] * b[0] + b[1] * b[1]); }
        });
    }

    static float modeHz(int[] nm) { return HZ_PER_UNIT * (nm[0] * nm[0] + nm[1] * nm[1]); }

    private int nearestMode(float hz) {
        int best = 0;
        for (int i = 1; i < modes.size(); i++) {
            if (Math.abs(Math.log(modeHz(modes.get(i)) / hz)) < Math.abs(Math.log(modeHz(modes.get(best)) / hz))) best = i;
        }
        return best;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (!live) return;
        if (f.peakDb - f.calibrationDb > -55 && f.peakFreqHz > 100) {
            heardHz = f.peakFreqHz;
            quiet = 0;
        } else if (++quiet > 40) {
            heardHz = 0;
        }
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    private Bitmap render(int[] nm) {
        Bitmap b = Bitmap.createBitmap(RES, RES, Bitmap.Config.ARGB_8888);
        int[] px = new int[RES * RES];
        double n = nm[0], m = nm[1];
        java.util.Random r = new java.util.Random(nm[0] * 31 + nm[1]);
        for (int y = 0; y < RES; y++) {
            for (int x = 0; x < RES; x++) {
                double u = (x + 0.5) / RES, v = (y + 0.5) / RES;
                double z = Math.cos(n * Math.PI * u) * Math.cos(m * Math.PI * v) - Math.cos(m * Math.PI * u) * Math.cos(n * Math.PI * v);
                double a = Math.abs(z);
                // Sand settles where the plate does not move; a little randomness looks like grains.
                double sand = Math.max(0, 1 - a / 0.16) * (0.75 + 0.25 * r.nextDouble());
                int s = (int) (sand * 255);
                int plateR = 34, plateG = 40, plateB = 52;
                px[y * RES + x] = 0xFF000000 | (Math.min(255, plateR + s * 230 / 255) << 16)
                        | (Math.min(255, plateG + s * 200 / 255) << 8) | Math.min(255, plateB + s * 140 / 255);
            }
        }
        b.setPixels(px, 0, RES, 0, 0, RES, RES);
        return b;
    }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float hz = live ? heardHz : driveHz;
        if (hz > 0) modeIdx = nearestMode(hz);
        if (modeIdx < 0) modeIdx = nearestMode(520);
        if (modeIdx != shownIdx) {
            plate = render(modes.get(modeIdx));
            shownIdx = modeIdx;
        }
        int[] nm = modes.get(modeIdx);
        k.label(c, "Фігури Хладні", 8 * dp, 20 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        String src = live ? (hz > 0 ? "чую " + SpectrumMath.formatHz(hz) : "свистіть або співайте — пластина «слухає» мікрофон")
                : "генератор " + SpectrumMath.formatHz(hz);
        k.label(c, src, 8 * dp, 36 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);
        float size = Math.min(w - 24 * dp, h - 120 * dp);
        float left = (w - size) / 2, top = 48 * dp;
        android.graphics.Paint p = new android.graphics.Paint();
        p.setFilterBitmap(true);
        c.drawBitmap(plate, null, new RectF(left, top, left + size, top + size), p);
        k.stroke.setColor(0xFF94A3B8);
        k.stroke.setStrokeWidth(2 * dp);
        c.drawRect(left, top, left + size, top + size, k.stroke);
        k.label(c, String.format(Locale.US, "Мода (%d, %d) · резонанс ≈ %.0f Гц", nm[0], nm[1], modeHz(nm)), w / 2f,
                top + size + 18 * dp, 13, LessonKit.CYAN, Paint.Align.CENTER, true);
        k.label(c, "Пісок збирається там, де пластина НЕ рухається — на вузлових лініях", w / 2f, top + size + 36 * dp,
                10.5f, LessonKit.SOFT, Paint.Align.CENTER, false);
        k.label(c, "Вища частота → більше ліній і дрібніший візерунок", w / 2f, top + size + 52 * dp, 10.5f,
                LessonKit.LABEL, Paint.Align.CENTER, false);
    }
}
