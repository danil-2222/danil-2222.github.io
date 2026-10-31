package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;

import java.util.Random;

/** Bass grows mountains, the middle grows trees, highs bring birds and stars, loudness is the wind. */
final class LandscapeLesson implements LessonView {
    private final LessonKit k;
    private float bass, mid, high, wind;
    private final float[] starX = new float[40], starY = new float[40];
    private final long start = System.currentTimeMillis();

    LandscapeLesson(float dp) {
        k = new LessonKit(dp);
        Random r = new Random(5);
        for (int i = 0; i < starX.length; i++) { starX[i] = r.nextFloat(); starY[i] = r.nextFloat() * 0.45f; }
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        double b = LessonKit.bandPower(f, 30, 250), m = LessonKit.bandPower(f, 250, 2500), hi = LessonKit.bandPower(f, 2500, 16000);
        double total = b + m + hi + 1e-12;
        float level = Math.max(0, Math.min(1, (f.rmsDb - f.calibrationDb + 60) / 45));
        bass += ((float) Math.sqrt(b / total) * level - bass) * 0.15f;
        mid += ((float) Math.sqrt(m / total) * level - mid) * 0.15f;
        high += ((float) Math.sqrt(hi / total) * level - high) * 0.15f;
        wind += (level - wind) * 0.2f;
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float t = (System.currentTimeMillis() - start) / 1000f;
        float ground = h * 0.78f;
        k.fill.setShader(new LinearGradient(0, 0, 0, ground, 0xFF0B1E3F, 0xFF3B5B8C, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, ground, k.fill);
        k.fill.setShader(null);
        // Stars and birds: high sounds.
        int stars = (int) (high * 40);
        for (int i = 0; i < stars; i++) {
            k.fill.setColor(0xFFFFF59D);
            c.drawCircle(starX[i] * w, starY[i] * h, 2 * dp, k.fill);
        }
        int birds = (int) (high * 8);
        k.stroke.setColor(0xFF0B1220);
        k.stroke.setStrokeWidth(2 * dp);
        for (int i = 0; i < birds; i++) {
            float bx = (float) ((i * 0.13 + t * 0.05 * (1 + i % 3)) % 1.0) * w, by = h * (0.12f + 0.05f * (i % 4));
            float flap = (float) Math.sin(t * 8 + i) * 4 * dp;
            c.drawLine(bx - 8 * dp, by - flap, bx, by, k.stroke);
            c.drawLine(bx, by, bx + 8 * dp, by - flap, k.stroke);
        }
        // Mountains: bass.
        k.path.reset();
        k.path.moveTo(0, ground);
        for (int x = 0; x <= w; x += 6) {
            double u = (double) x / w;
            float hgt = (float) ((0.5 + 0.5 * Math.sin(u * 7 + 1) * Math.sin(u * 3)) * bass * h * 0.55);
            k.path.lineTo(x, ground - hgt);
        }
        k.path.lineTo(w, ground);
        k.path.close();
        k.fill.setColor(0xFF5D4037);
        c.drawPath(k.path, k.fill);
        // Ground and grass swaying in the wind (loudness).
        k.fill.setColor(0xFF2E7D32);
        c.drawRect(0, ground, w, h, k.fill);
        // Trees: the middle of the sound.
        int trees = (int) (mid * 12);
        for (int i = 0; i < trees; i++) {
            float tx = w * (0.06f + 0.88f * ((i * 0.37f) % 1));
            float th = (40 + 50 * mid) * dp * (0.7f + 0.3f * ((i * 0.53f) % 1));
            float sway = (float) Math.sin(t * 3 + i) * wind * 10 * dp;
            k.line(c, tx, ground, tx + sway * 0.3f, ground - th * 0.4f, 0xFF4E342E, 4);
            k.fill.setColor(0xFF43A047);
            c.drawCircle(tx + sway, ground - th * 0.6f, th * 0.3f, k.fill);
        }
        for (int x = 0; x < w; x += 8) {
            float sway = (float) Math.sin(t * 5 + x * 0.05) * wind * 8 * dp;
            k.line(c, x, ground + 4 * dp, x + sway, ground - 8 * dp * (0.5f + wind), 0xFF66BB6A, 1.5f);
        }
        k.label(c, "Пейзаж звуку", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "гори — бас · дерева — середина · зорі й птахи — високі · вітер — гучність", 8 * dp, 40 * dp, 10.5f,
                LessonKit.SOFT, Paint.Align.LEFT, false);
        String msg = wind < 0.08f ? "Тихо — спокійна ніч" : bass > mid && bass > high ? "Багато гір — низький, оксамитовий звук"
                : high > mid ? "Багато зірок — дзвінкий, високий звук" : "Багато дерев — звук, схожий на голос";
        k.label(c, msg, w / 2f, h - 26 * dp, 15, LessonKit.WHITE, Paint.Align.CENTER, true);
    }
}
