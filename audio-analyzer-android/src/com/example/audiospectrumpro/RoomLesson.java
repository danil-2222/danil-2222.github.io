package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Room modes: the speaker plays a low tone; walking around shows loud and quiet spots (standing
 * waves). The axial modes f = n · c / (2 · size) are computed from the room dimensions.
 */
final class RoomLesson implements LessonView {
    float lengthM = 4.5f, widthM = 3.5f, heightM = 2.6f;
    volatile float toneHz = 80;

    private final LessonKit k;
    private static final int HISTORY = 900;      // about 20 s of frames
    private final float[] levels = new float[HISTORY];
    private int head, count;
    private float current = Float.NaN, maxSeen = -200, minSeen = 200;

    RoomLesson(float dp) { k = new LessonKit(dp); }

    /** Axial modes up to 300 Hz: {frequency, dimension index (0 L, 1 W, 2 H), order}. */
    List<float[]> modes() {
        List<float[]> out = new ArrayList<>();
        float[] dims = {lengthM, widthM, heightM};
        for (int d = 0; d < 3; d++) {
            for (int n = 1; n <= 8; n++) {
                float f = n * 343f / (2 * dims[d]);
                if (f > 300) break;
                out.add(new float[]{f, d, n});
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<float[]>() {
            @Override public int compare(float[] a, float[] b) { return Float.compare(a[0], b[0]); }
        });
        return out;
    }

    void resetHistory() {
        count = 0;
        maxSeen = -200;
        minSeen = 200;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float db = SpectrumMath.valueAtBin(f.spectrumDb, toneHz / f.binHz) - f.calibrationDb;
        current = db;
        levels[head] = db;
        head = (head + 1) % HISTORY;
        if (count < HISTORY) count++;
        if (db > -90) {
            maxSeen = Math.max(maxSeen, db);
            minSeen = Math.min(minSeen, db);
        }
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    private float xOf(float f, float left, float right) {
        return left + (float) (Math.log(f / 20) / Math.log(300 / 20.0)) * (right - left);
    }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Стоячі хвилі в кімнаті", 8 * dp, 20 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, String.format(Locale.US, "Кімната %.1f × %.1f × %.1f м · тон %.0f Гц", lengthM, widthM, heightM, toneHz),
                8 * dp, 36 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);

        // Mode map on a log frequency axis.
        float left = 12 * dp, right = w - 12 * dp, axisY = 86 * dp;
        k.line(c, left, axisY, right, axisY, LessonKit.GRID, 2);
        for (int f : new int[]{20, 30, 50, 70, 100, 150, 200, 300}) {
            float x = xOf(f, left, right);
            k.line(c, x, axisY - 3 * dp, x, axisY + 3 * dp, LessonKit.LABEL, 1);
            k.label(c, String.valueOf(f), x, axisY + 14 * dp, 9, LessonKit.LABEL, Paint.Align.CENTER, false);
        }
        int[] colors = {LessonKit.CYAN, LessonKit.GREEN, LessonKit.ORANGE};
        String[] names = {"довжина", "ширина", "висота"};
        for (float[] m : modes()) {
            float x = xOf(m[0], left, right);
            int d = (int) m[1];
            k.line(c, x, axisY - 22 * dp + d * 6 * dp, x, axisY, colors[d], 2);
        }
        for (int d = 0; d < 3; d++) {
            k.rect(c, left + d * 90 * dp, axisY + 22 * dp, left + d * 90 * dp + 10 * dp, axisY + 28 * dp, colors[d]);
            k.label(c, names[d], left + d * 90 * dp + 14 * dp, axisY + 29 * dp, 10, LessonKit.SOFT, Paint.Align.LEFT, false);
        }
        float tx = xOf(toneHz, left, right);
        k.line(c, tx, axisY - 30 * dp, tx, axisY + 4 * dp, LessonKit.WHITE, 2);
        k.label(c, "▼ тон", tx, axisY - 32 * dp, 9, LessonKit.WHITE, Paint.Align.CENTER, true);

        // Level now and over time.
        float top = axisY + 52 * dp;
        k.label(c, "Гучність тону тут:", 8 * dp, top, 11, LessonKit.LABEL, Paint.Align.LEFT, false);
        k.label(c, Float.isNaN(current) ? "—" : String.format(Locale.US, "%.0f дБ", current), 8 * dp, top + 30 * dp, 26,
                LessonKit.YELLOW, Paint.Align.LEFT, true);
        if (maxSeen > minSeen) {
            k.label(c, String.format(Locale.US, "різниця між найгучнішим і найтихішим місцем: %.0f дБ", maxSeen - minSeen),
                    8 * dp, top + 48 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);
        }
        float gTop = top + 60 * dp, gBottom = h - 20 * dp;
        k.rect(c, 8 * dp, gTop, w - 8 * dp, gBottom, 0xFF0B1220);
        if (count > 1) {
            float lo = Math.min(-80, minSeen - 3), hi = Math.max(lo + 20, maxSeen + 3);
            k.path.reset();
            for (int i = 0; i < count; i++) {
                float v = levels[(head - count + i + HISTORY) % HISTORY];
                float x = 8 * dp + (float) i / (HISTORY - 1) * (w - 16 * dp);
                float y = gBottom - (Math.max(lo, Math.min(hi, v)) - lo) / (hi - lo) * (gBottom - gTop);
                if (i == 0) k.path.moveTo(x, y); else k.path.lineTo(x, y);
            }
            k.stroke.setColor(LessonKit.YELLOW);
            k.stroke.setStrokeWidth(2 * dp);
            c.drawPath(k.path, k.stroke);
        }
        k.label(c, "← 20 с: ходіть кімнатою — піки й провали це пучності й вузли хвилі", 8 * dp, h - 6 * dp, 9.5f,
                LessonKit.LABEL, Paint.Align.LEFT, false);
    }
}
