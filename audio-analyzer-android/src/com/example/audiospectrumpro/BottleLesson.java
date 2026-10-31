package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Musical bottle: a Helmholtz resonator. Predicts the note from the air volume and the neck,
 * measures the note you blow, and tells how much water to pour for each note of a scale.
 */
final class BottleLesson implements LessonView {
    static final String[] PRESETS = {"Пляшка 0,5 л (вода)", "Пляшка 1,5 л (пластик)", "Скляна 0,33 л", "Винна 0,75 л"};
    /** volume ml, neck diameter mm, neck length mm */
    static final float[][] PRESET_DIMS = {{500, 21, 25}, {1500, 21, 25}, {330, 19, 60}, {750, 19, 80}};

    float volumeMl = 500, neckDiaMm = 21, neckLenMm = 25, waterPct = 0;

    private final LessonKit k;
    private float measuredHz;
    private float heldHz;
    private int heldFrames;

    BottleLesson(float dp) { k = new LessonKit(dp); }

    private double area() { return Math.PI * Math.pow(neckDiaMm / 2000.0, 2); }

    /** Effective neck length: the air just outside both ends moves with the plug (end correction). */
    private double effLen() { return neckLenMm / 1000.0 + 0.75 * neckDiaMm / 1000.0; }

    float predictHz() {
        double v = volumeMl * (1 - waterPct / 100.0) / 1e6;
        return (float) (343 / (2 * Math.PI) * Math.sqrt(area() / (v * effLen())));
    }

    /** Water (ml) needed for frequency f, or NaN if the bottle cannot reach it. */
    private float waterFor(double f) {
        double vAir = area() * 343 * 343 / (4 * Math.PI * Math.PI * f * f * effLen()) * 1e6;
        double water = volumeMl - vAir;
        return water < 0 || water > volumeMl * 0.92 ? Float.NaN : (float) water;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        // A blown bottle gives one strong, slightly breathy peak; look for it in 60-1500 Hz.
        float[] db = f.spectrumDb;
        int lo = Math.max(2, (int) (60 / f.binHz)), hi = Math.min(db.length - 2, (int) (1500 / f.binHz));
        int best = -1;
        for (int i = lo; i <= hi; i++) {
            if (db[i] < db[i - 1] || db[i] < db[i + 1]) continue;
            if (best < 0 || db[i] > db[best]) best = i;
        }
        boolean ok = best > 0 && SoundExplainer.prominence(db, best) > 12 && db[best] - f.calibrationDb > -60;
        if (ok) {
            float al = db[best - 1], be = db[best], ga = db[best + 1];
            float den = al - 2 * be + ga;
            float d = Math.abs(den) > 1e-6f ? Math.max(-0.5f, Math.min(0.5f, 0.5f * (al - ga) / den)) : 0;
            measuredHz = (best + d) * f.binHz;
            heldHz = heldFrames > 0 && Math.abs(measuredHz / heldHz - 1) < 0.03f ? heldHz * 0.8f + measuredHz * 0.2f : measuredHz;
            heldFrames = 60; // keep showing the last note for about a second
        } else if (heldFrames > 0) {
            heldFrames--;
        }
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float pred = predictHz();
        float[] cents = new float[1];
        String predNote = SpectrumMath.noteName(pred, 440, cents);
        k.label(c, "Музична пляшка", 8 * dp, 20 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "f = (c / 2π) · √(S / (V · L))  — резонатор Гельмгольца", 8 * dp, 36 * dp, 10.5f, LessonKit.SOFT,
                Paint.Align.LEFT, false);

        // Bottle drawing with water level.
        float bx = 18 * dp, bw = Math.min(w * 0.28f, 110 * dp), by = 52 * dp, bh = h * 0.56f;
        float neckW = bw * 0.32f, neckH = bh * 0.22f;
        float bodyTop = by + neckH + bh * 0.08f;
        RectF body = new RectF(bx, bodyTop, bx + bw, by + bh);
        float water = waterPct / 100f;
        k.fill.setColor(0xFF1565C0);
        c.save();
        c.clipRect(bx, body.bottom - (body.height()) * water, bx + bw, body.bottom);
        c.drawRoundRect(body, 14 * dp, 14 * dp, k.fill);
        c.restore();
        k.stroke.setColor(LessonKit.SOFT);
        k.stroke.setStrokeWidth(2 * dp);
        c.drawRoundRect(body, 14 * dp, 14 * dp, k.stroke);
        float nx = bx + (bw - neckW) / 2;
        c.drawRect(nx, by, nx + neckW, bodyTop + 2 * dp, k.stroke);
        k.label(c, "повітря коливається", nx + neckW + 6 * dp, by + neckH * 0.6f, 9.5f, LessonKit.CYAN, Paint.Align.LEFT, false);
        k.label(c, "в шийці, як пружина", nx + neckW + 6 * dp, by + neckH * 0.6f + 12 * dp, 9.5f, LessonKit.CYAN, Paint.Align.LEFT, false);
        k.label(c, String.format(Locale.US, "повітря %.0f мл", volumeMl * (1 - water)), bx + bw / 2,
                bodyTop + 16 * dp, 10, LessonKit.WHITE, Paint.Align.CENTER, true);
        if (water > 0.05f) {
            k.label(c, String.format(Locale.US, "вода %.0f мл", volumeMl * water), bx + bw / 2, by + bh - 8 * dp, 10,
                    LessonKit.WHITE, Paint.Align.CENTER, true);
        }

        // Prediction vs measurement.
        float tx = bx + bw + 20 * dp, ty = bodyTop + 24 * dp;
        k.label(c, "Розрахунок:", tx, ty, 11, LessonKit.LABEL, Paint.Align.LEFT, false);
        k.label(c, String.format(Locale.US, "%.0f Гц · %s", pred, predNote), tx, ty + 22 * dp, 18, LessonKit.CYAN, Paint.Align.LEFT, true);
        k.label(c, "Ви дмухнули:", tx, ty + 50 * dp, 11, LessonKit.LABEL, Paint.Align.LEFT, false);
        if (heldFrames > 0) {
            String note = SpectrumMath.noteName(heldHz, 440, cents);
            float diff = (heldHz / pred - 1) * 100;
            k.label(c, String.format(Locale.US, "%.0f Гц · %s", heldHz, note), tx, ty + 72 * dp, 18, LessonKit.GREEN, Paint.Align.LEFT, true);
            k.label(c, String.format(Locale.US, "різниця з розрахунком %+.0f%%", diff), tx, ty + 90 * dp, 10.5f,
                    Math.abs(diff) < 10 ? LessonKit.GREEN : LessonKit.YELLOW, Paint.Align.LEFT, false);
        } else {
            k.label(c, "дмухніть через шийку", tx, ty + 72 * dp, 13, LessonKit.SOFT, Paint.Align.LEFT, false);
        }

        // Scale table: how much water for each note this bottle can play.
        float y0 = by + bh + 22 * dp;
        k.label(c, "Гама на пляшках — скільки налити води:", 8 * dp, y0, 11, LessonKit.WHITE, Paint.Align.LEFT, true);
        int[] steps = {0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24};
        float baseMidi = (float) (69 + 12 * Math.log(predictEmptyHz() / 440.0) / Math.log(2));
        int startC = (int) Math.ceil((baseMidi) / 12.0) * 12; // first C at or above the empty-bottle note
        int shown = 0;
        float colW = (w - 16 * dp) / 4;
        for (int s : steps) {
            if (shown >= 8) break;
            int midi = startC - 12 + s;
            float f = LessonKit.midiToHz(midi);
            float ml = waterFor(f);
            if (Float.isNaN(ml)) continue;
            String name = LessonRenderer.noteUa(midi).split(" ")[1];
            k.label(c, String.format(Locale.US, "%s: %.0f мл", name, ml), 8 * dp + (shown % 4) * colW,
                    y0 + 20 * dp + (shown / 4) * 16 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);
            shown++;
        }
        if (shown == 0) k.label(c, "у цієї пляшки замалий діапазон — змініть розміри", 8 * dp, y0 + 20 * dp, 11,
                LessonKit.LABEL, Paint.Align.LEFT, false);
    }

    private float predictEmptyHz() {
        float w = waterPct;
        waterPct = 0;
        float f = predictHz();
        waterPct = w;
        return f;
    }
}
