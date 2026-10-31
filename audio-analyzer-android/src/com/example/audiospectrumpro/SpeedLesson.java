package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Speed of sound: clap facing a wall at a known distance; the delay between the clap and its
 * echo gives c = 2 · distance / delay.
 */
final class SpeedLesson implements LessonView {
    float distanceM = 10;

    private static final int CHUNK = 64;
    private final LessonKit k;
    private final LessonKit.SampleTap tap = new LessonKit.SampleTap();
    private int rate = 48000;
    private float floorDb = -70;
    private float[] capture;
    private int captured = -1;
    private float[] shown;
    private int clapIdx, echoIdx = -1;
    private float speed = Float.NaN;
    private float[] carry = new float[0];

    SpeedLesson(float dp) { k = new LessonKit(dp); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        rate = f.sampleRate;
        float[] fresh = tap.take(f);
        if (fresh.length == 0) return;
        float[] buf = new float[carry.length + fresh.length];
        System.arraycopy(carry, 0, buf, 0, carry.length);
        System.arraycopy(fresh, 0, buf, carry.length, fresh.length);
        int off = 0;
        for (; off + CHUNK <= buf.length; off += CHUNK) {
            double e = 0;
            for (int i = 0; i < CHUNK; i++) e += buf[off + i] * buf[off + i];
            onChunk((float) (10 * Math.log10(e / CHUNK + 1e-14)));
        }
        carry = java.util.Arrays.copyOfRange(buf, off, buf.length);
    }

    private void onChunk(float db) {
        if (captured >= 0) {
            capture[captured++] = db;
            if (captured == capture.length) {
                analyse();
                captured = -1;
            }
            return;
        }
        floorDb = db < floorDb ? db : floorDb * 0.995f + db * 0.005f;
        if (db > floorDb + 30 && db > -35) {
            capture = new float[(int) (0.5f * rate / CHUNK)];
            captured = 0;
            capture[captured++] = db;
        }
    }

    private void analyse() {
        shown = capture;
        clapIdx = 0;
        for (int i = 1; i < Math.min(8, shown.length); i++) if (shown[i] > shown[clapIdx]) clapIdx = i;
        float peak = shown[clapIdx];
        // Wait until the clap itself has died away, then take the strongest rise as the echo.
        int i = clapIdx;
        while (i < shown.length - 1 && shown[i] > peak - 15) i++;
        float runMin = shown[i];
        echoIdx = -1;
        float best = 0;
        for (int j = i; j < shown.length - 1; j++) {
            runMin = Math.min(runMin, shown[j]);
            float rise = shown[j] - runMin;
            if (rise >= 6 && shown[j] >= shown[j - 1] && shown[j] >= shown[j + 1] && rise > best) {
                best = rise;
                echoIdx = j;
            }
        }
        if (echoIdx > 0) {
            float delay = (echoIdx - clapIdx) * CHUNK / (float) rate;
            speed = 2 * distanceM / delay;
        } else {
            speed = Float.NaN;
        }
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Швидкість звуку", 8 * dp, 20 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        float expected = 2 * distanceM / 343f * 1000;
        k.label(c, String.format(Locale.US, "Стіна за %.0f м → луна має прийти через ≈ %.0f мс", distanceM, expected),
                8 * dp, 36 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);
        if (shown == null) {
            k.label(c, captured >= 0 ? "Слухаю луну…" : "Станьте обличчям до стіни і ляскніть один раз",
                    w / 2f, h / 2f, 15, captured >= 0 ? LessonKit.YELLOW : LessonKit.WHITE, Paint.Align.CENTER, true);
            k.label(c, "Найкраще надворі: стіна будинку за 10–30 м, довкола тихо", w / 2f, h / 2f + 20 * dp, 11,
                    LessonKit.SOFT, Paint.Align.CENTER, false);
            return;
        }
        float left = 34 * dp, right = w - 8 * dp, top = 90 * dp, bottom = h - 26 * dp;
        float total = shown.length * CHUNK / (float) rate * 1000;
        float lo = -90, hi = 0;
        for (int db = 0; db >= -90; db -= 30) {
            float y = top + (hi - db) / (hi - lo) * (bottom - top);
            k.line(c, left, y, right, y, LessonKit.GRID, 1);
            k.label(c, String.valueOf(db), left - 4 * dp, y + 3 * dp, 9, LessonKit.LABEL, Paint.Align.RIGHT, false);
        }
        for (int ms = 0; ms <= total; ms += 100) {
            k.label(c, ms + " мс", left + ms / total * (right - left), h - 10 * dp, 9, LessonKit.LABEL, Paint.Align.CENTER, false);
        }
        float ex = left + expected / total * (right - left);
        if (expected < total) {
            k.line(c, ex, top, ex, bottom, 0x66FFFFFF, 1);
            k.label(c, "очікувано", ex, top - 4 * dp, 9, LessonKit.LABEL, Paint.Align.CENTER, false);
        }
        k.path.reset();
        for (int i = 0; i < shown.length; i++) {
            float x = left + (float) i / (shown.length - 1) * (right - left);
            float y = top + (hi - Math.max(lo, Math.min(hi, shown[i]))) / (hi - lo) * (bottom - top);
            if (i == 0) k.path.moveTo(x, y); else k.path.lineTo(x, y);
        }
        k.stroke.setColor(LessonKit.BLUE);
        k.stroke.setStrokeWidth(1.5f * dp);
        c.drawPath(k.path, k.stroke);
        float cx = left + (float) clapIdx / (shown.length - 1) * (right - left);
        k.line(c, cx, top, cx, bottom, LessonKit.GREEN, 2);
        k.label(c, "ляск", cx + 3 * dp, bottom - 4 * dp, 10, LessonKit.GREEN, Paint.Align.LEFT, true);
        if (echoIdx > 0) {
            float x = left + (float) echoIdx / (shown.length - 1) * (right - left);
            k.line(c, x, top, x, bottom, LessonKit.PINK, 2);
            k.label(c, "луна", x + 3 * dp, bottom - 4 * dp, 10, LessonKit.PINK, Paint.Align.LEFT, true);
            float delay = (echoIdx - clapIdx) * CHUNK / (float) rate * 1000;
            k.label(c, String.format(Locale.US, "Швидкість звуку ≈ %.0f м/с", speed), 8 * dp, 62 * dp, 18,
                    Math.abs(speed - 343) < 40 ? LessonKit.GREEN : LessonKit.YELLOW, Paint.Align.LEFT, true);
            k.label(c, String.format(Locale.US, "затримка %.0f мс · 2 × %.0f м ÷ %.3f с · довідково 343 м/с", delay,
                    distanceM, delay / 1000), 8 * dp, 78 * dp, 10, LessonKit.SOFT, Paint.Align.LEFT, false);
        } else {
            k.label(c, "Луну не знайдено — спробуйте далі від стіни або голосніше", 8 * dp, 66 * dp, 12, LessonKit.YELLOW,
                    Paint.Align.LEFT, true);
        }
    }
}
