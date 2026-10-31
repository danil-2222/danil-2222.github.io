package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Rhythm trainer: the speaker plays metronome clicks (pure tones), the microphone hears both the
 * clicks and your claps (broadband), so timing is compared on the same clock with no latency error.
 */
final class RhythmLesson implements LessonView {
    static final float CLICK_HZ = 1600, ACCENT_HZ = 2400;

    int bpm = 90;
    private final LessonKit k;
    private final LessonKit.SampleTap tap = new LessonKit.SampleTap();
    private float[] carry = new float[0];
    private int rate = 48000;
    private long clock;                // samples heard so far
    private long lastClick = -1;
    private long lastOnset = -100000;
    private final float[] recentDb = new float[8];
    private int recentIdx;
    private static final int KEEP = 24;
    private final float[] offsets = new float[KEEP];
    private int offsetCount;
    private final long[] clickTimes = new long[64], clapTimes = new long[64];
    private final float[] clapOffsets = new float[64];
    private int clicks, claps;

    RhythmLesson(float dp) {
        k = new LessonKit(dp);
        java.util.Arrays.fill(recentDb, -120);
    }

    /** One bar (4 beats) of clicks, the first accented; loop it for a metronome. */
    static float[] metronome(int bpm, int rate) {
        int beat = Math.round(60f / bpm * rate);
        float[] out = new float[beat * 4];
        for (int b = 0; b < 4; b++) {
            int len = rate / 60;
            double f = b == 0 ? ACCENT_HZ : CLICK_HZ;
            for (int i = 0; i < len; i++) {
                double env = Math.exp(-i / (rate * 0.004)) * Math.min(1, i / 8.0);
                out[b * beat + i] += (float) (0.9 * env * Math.sin(2 * Math.PI * f * i / rate));
            }
        }
        return out;
    }

    void reset() {
        offsetCount = 0;
        clicks = 0;
        claps = 0;
        lastClick = -1;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        rate = f.sampleRate;
        float[] fresh = tap.take(f);
        if (fresh.length == 0) return;
        int n = 128;
        float[] buf = new float[carry.length + fresh.length];
        System.arraycopy(carry, 0, buf, 0, carry.length);
        System.arraycopy(fresh, 0, buf, carry.length, fresh.length);
        int off = 0;
        for (; off + n <= buf.length; off += n) {
            double e = 0;
            for (int i = 0; i < n; i++) e += buf[off + i] * buf[off + i];
            float db = (float) (10 * Math.log10(e / n + 1e-14));
            float ref = -120;
            for (float v : recentDb) ref = Math.max(ref, v);
            recentDb[recentIdx] = db;
            recentIdx = (recentIdx + 1) % recentDb.length;
            long t = clock + off;
            if (db > ref + 10 && db > -50 && t - lastOnset > rate * 0.02) {
                lastOnset = t;
                double g = LessonKit.goertzel(buf, off, n, CLICK_HZ, rate) + LessonKit.goertzel(buf, off, n, ACCENT_HZ, rate);
                double tonal = g / (e * n / 2 + 1e-20);
                if (tonal > 0.4) onClick(t); else onClap(t);
            }
        }
        clock += off;
        carry = java.util.Arrays.copyOfRange(buf, off, buf.length);
    }

    private void onClick(long t) {
        lastClick = t;
        clickTimes[clicks % clickTimes.length] = t;
        clicks++;
    }

    private void onClap(long t) {
        float offMs = Float.NaN;
        if (lastClick >= 0) {
            double period = 60.0 / bpm * rate;
            double d = (t - lastClick) % period;
            if (d > period / 2) d -= period;   // closer to the next beat: early
            offMs = (float) (d * 1000 / rate);
            offsets[offsetCount % KEEP] = offMs;
            offsetCount++;
        }
        clapTimes[claps % clapTimes.length] = t;
        clapOffsets[claps % clapOffsets.length] = offMs;
        claps++;
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, String.format(Locale.US, "Тренажер ритму · %d уд/хв", bpm), 8 * dp, 20 * dp, 15, LessonKit.WHITE,
                Paint.Align.LEFT, true);
        k.label(c, "Плескайте разом із клацаннями метронома", 8 * dp, 36 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);

        int n = Math.min(offsetCount, KEEP);
        if (n > 0) {
            double mean = 0, var = 0;
            for (int i = 0; i < n; i++) mean += offsets[i];
            mean /= n;
            for (int i = 0; i < n; i++) var += (offsets[i] - mean) * (offsets[i] - mean);
            double sd = Math.sqrt(var / n);
            String tendency = Math.abs(mean) < 15 ? "точно в долю" : mean < 0 ? "поспішаєте" : "запізнюєтесь";
            k.label(c, String.format(Locale.US, "%s: %+.0f мс", tendency, mean), w / 2f, h * 0.26f, 22,
                    Math.abs(mean) < 15 ? LessonKit.GREEN : LessonKit.YELLOW, Paint.Align.CENTER, true);
            String grade = sd < 15 ? "як метроном!" : sd < 30 ? "дуже рівно" : sd < 60 ? "непогано" : "нерівно — слухайте клацання";
            k.label(c, String.format(Locale.US, "розкид ±%.0f мс — %s · оплесків: %d", sd, grade, n), w / 2f, h * 0.26f + 22 * dp,
                    11, LessonKit.SOFT, Paint.Align.CENTER, false);
        } else {
            k.label(c, clicks > 0 ? "Чую метроном — плескайте!" : "Натисніть «Старт метронома»", w / 2f, h * 0.26f, 16,
                    LessonKit.WHITE, Paint.Align.CENTER, true);
        }

        // Timeline of the last 4 seconds: clicks as lines, claps as dots placed by their timing.
        float left = 8 * dp, right = w - 8 * dp, top = h * 0.45f, bottom = h * 0.70f;
        k.rect(c, left, top, right, bottom, 0xFF0B1220);
        double window = 4.0 * rate;
        for (int i = Math.max(0, clicks - 64); i < clicks; i++) {
            long t = clickTimes[i % clickTimes.length];
            float x = right - (float) ((clock - t) / window) * (right - left);
            if (x < left) continue;
            k.line(c, x, top, x, bottom, LessonKit.GREEN, 2);
        }
        for (int i = Math.max(0, claps - 64); i < claps; i++) {
            long t = clapTimes[i % clapTimes.length];
            float x = right - (float) ((clock - t) / window) * (right - left);
            if (x < left) continue;
            float o = clapOffsets[i % clapOffsets.length];
            k.fill.setColor(Float.isNaN(o) ? LessonKit.LABEL : Math.abs(o) < 20 ? LessonKit.GREEN : Math.abs(o) < 50 ? LessonKit.YELLOW : LessonKit.PINK);
            c.drawCircle(x, (top + bottom) / 2, 7 * dp, k.fill);
            if (!Float.isNaN(o)) k.label(c, String.format(Locale.US, "%+.0f", o), x, top - 4 * dp, 9, LessonKit.SOFT, Paint.Align.CENTER, false);
        }
        k.label(c, "зелені лінії — метроном, кружечки — ваші оплески (мс до долі)", left, bottom + 14 * dp, 9.5f,
                LessonKit.LABEL, Paint.Align.LEFT, false);

        // Offset scatter of recent claps.
        float cy = h * 0.86f, half = w * 0.42f;
        k.line(c, w / 2f - half, cy, w / 2f + half, cy, LessonKit.GRID, 2);
        k.line(c, w / 2f, cy - 10 * dp, w / 2f, cy + 10 * dp, LessonKit.GREEN, 2);
        k.label(c, "−100 мс раніше", w / 2f - half, cy + 22 * dp, 9, LessonKit.LABEL, Paint.Align.LEFT, false);
        k.label(c, "пізніше +100 мс", w / 2f + half, cy + 22 * dp, 9, LessonKit.LABEL, Paint.Align.RIGHT, false);
        for (int i = 0; i < n; i++) {
            float x = w / 2f + Math.max(-100, Math.min(100, offsets[i])) / 100f * half;
            k.fill.setColor(0xAAFFE600);
            c.drawCircle(x, cy, 5 * dp, k.fill);
        }
    }
}
