package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.MotionEvent;

/** The whole sound as one colour: bass red, middle green, highs blue; with a name and a "temperature". */
final class ColorLesson implements LessonView {
    private final LessonKit k;
    private float r, g, b, bright;
    private final int[] history = new int[200];
    private int head;

    ColorLesson(float dp) { k = new LessonKit(dp); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        double lo = LessonKit.bandPower(f, 40, 300), mi = LessonKit.bandPower(f, 300, 2500), hi = LessonKit.bandPower(f, 2500, 14000);
        double m = Math.max(lo, Math.max(mi, hi)) + 1e-20;
        float level = Math.max(0, Math.min(1, (f.rmsDb - f.calibrationDb + 62) / 40));
        r += ((float) Math.pow(lo / m, 0.5) - r) * 0.2f;
        g += ((float) Math.pow(mi / m, 0.5) - g) * 0.2f;
        b += ((float) Math.pow(hi / m, 0.5) - b) * 0.2f;
        bright += (level - bright) * 0.2f;
        history[head] = color();
        head = (head + 1) % history.length;
    }

    private int color() {
        return Color.rgb((int) (r * 255 * bright), (int) (g * 255 * bright), (int) (b * 255 * bright));
    }

    static String name(float[] hsv) {
        if (hsv[2] < 0.15f) return "темрява (тиша)";
        if (hsv[1] < 0.25f) return "білий — усі частоти разом";
        float h = hsv[0];
        if (h < 15 || h >= 330) return "червоний";
        if (h < 45) return "помаранчевий";
        if (h < 70) return "жовтий";
        if (h < 160) return "зелений";
        if (h < 200) return "бірюзовий";
        if (h < 250) return "синій";
        return "фіолетовий";
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        int col = color();
        float[] hsv = new float[3];
        Color.colorToHSV(col, hsv);
        float cx = w / 2f, cy = h * 0.42f, rad = Math.min(w, h) * 0.3f;
        k.fill.setShader(new RadialGradient(cx, cy, rad * 1.4f, new int[]{col, col & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rad * 1.4f, k.fill);
        k.fill.setShader(null);
        k.fill.setColor(col);
        c.drawCircle(cx, cy, rad * 0.8f, k.fill);
        k.label(c, "Колір звуку", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "низькі — червоні · середина — зелена · високі — сині", 8 * dp, 40 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);
        String nm = name(hsv);
        boolean warm = hsv[0] < 90 || hsv[0] >= 330;
        k.label(c, nm, cx, h * 0.80f, 22, LessonKit.WHITE, Paint.Align.CENTER, true);
        if (hsv[2] >= 0.15f && hsv[1] >= 0.25f) {
            k.label(c, warm ? "теплий звук: більше низьких" : "холодний звук: більше високих", cx, h * 0.80f + 22 * dp, 13,
                    warm ? 0xFFFFAB91 : 0xFF90CAF9, Paint.Align.CENTER, false);
        }
        float sw = (float) w / history.length;
        for (int i = 0; i < history.length; i++) {
            k.rect(c, i * sw, h - 14 * dp, (i + 1) * sw + 1, h, history[(head + i) % history.length]);
        }
    }
}
