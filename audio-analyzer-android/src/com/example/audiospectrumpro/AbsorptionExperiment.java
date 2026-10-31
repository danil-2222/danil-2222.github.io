package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * How much do rooms swallow sound? Clap in a room: the phone measures the reverberation time RT60 (how
 * long until the echo fades by 60 dB) by Schroeder integration. With the room size, Sabine's formula gives
 * the total absorption A = 0.161·V / RT60 and the average absorption coefficient α = A / S of its surfaces.
 * Compare a bathroom (tiles), a bedroom (bed, curtains) and a hall.
 */
final class AbsorptionExperiment implements LessonView {
    static final int CHUNK = 256;

    static final class Room {
        String name;
        float l, w, h;
        final List<Float> rt = new ArrayList<>();

        float volume() { return l * w * h; }
        float surface() { return 2 * (l * w + l * h + w * h); }

        float rt60() {
            if (rt.isEmpty()) return Float.NaN;
            float s = 0;
            for (float v : rt) s += v;
            return s / rt.size();
        }

        float absorptionArea() { return 0.161f * volume() / rt60(); }
        float alpha() { return Math.min(1, absorptionArea() / surface()); }
    }

    float length = 4, width = 3, height = 2.6f;
    private final LessonKit k;
    private final LessonKit.GaplessTap tap = new LessonKit.GaplessTap();
    final List<Room> rooms = new ArrayList<>();
    private float[] env = new float[0];
    private int head, rate;
    private float floorDb = -90;
    private int captureLeft = -1, capturedLen;
    private float[] captured;
    private float[] carry = new float[0];
    private String status = "Назвіть кімнату, введіть розміри і плесніть у долоні";

    AbsorptionExperiment(float dp) { k = new LessonKit(dp); }

    void newRoom(String name) {
        Room r = new Room();
        r.name = name.isEmpty() ? "Кімната " + (rooms.size() + 1) : name;
        r.l = length;
        r.w = width;
        r.h = height;
        if (rooms.size() >= 4) rooms.remove(0);
        rooms.add(r);
        status = "«" + r.name + "»: плесніть голосно 3 рази (з паузами)";
    }

    Room current() { return rooms.isEmpty() ? null : rooms.get(rooms.size() - 1); }

    /** Update the size of the current room when the sliders move. */
    void resize() {
        Room r = current();
        if (r != null) { r.l = length; r.w = width; r.h = height; }
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (rate != f.sampleRate) {
            rate = f.sampleRate;
            env = new float[rate / CHUNK];
            Arrays.fill(env, -120);
        }
        float[] fresh = tap.take(f);
        float[] buf = new float[carry.length + fresh.length];
        System.arraycopy(carry, 0, buf, 0, carry.length);
        System.arraycopy(fresh, 0, buf, carry.length, fresh.length);
        int off = 0;
        for (; off + CHUNK <= buf.length; off += CHUNK) push(LessonKit.blockDb(buf, off, CHUNK));
        carry = Arrays.copyOfRange(buf, off, buf.length);
    }

    private void push(float db) {
        if (captureLeft < 0) {
            float[] recent = env.clone();
            Arrays.sort(recent);
            floorDb = recent[recent.length / 5];
        }
        env[head] = db;
        head = (head + 1) % env.length;
        if (captureLeft < 0 && db > floorDb + 25 && db > -45 && current() != null) {
            captureLeft = (int) (2.5f * rate / CHUNK);
            captured = new float[captureLeft + 4];
            capturedLen = 0;
        }
        if (captureLeft >= 0) {
            if (capturedLen < captured.length) captured[capturedLen++] = db;
            if (--captureLeft < 0) analyse();
        }
    }

    /** Schroeder backward integration; T20 (or T10) extrapolated to 60 dB. */
    static float rt60(float[] cap, int n, float floorDb, float dt) {
        int peak = 0;
        for (int i = 1; i < Math.min(n, 12); i++) if (cap[i] > cap[peak]) peak = i;
        double noise = Math.pow(10, floorDb / 10);
        // Integrate only down to where the decay meets the background, or the noise makes the tail too long.
        int end = n;
        for (int i = peak; i < n; i++) if (cap[i] < floorDb + 3) { end = i; break; }
        double[] edc = new double[n];
        double acc = 0;
        for (int i = end - 1; i >= peak; i--) { acc += Math.max(0, Math.pow(10, cap[i] / 10) - noise); edc[i] = acc; }
        if (edc[peak] <= 0 || cap[n - 1] > cap[peak] - 15) return Float.NaN;
        int i5 = -1, i15 = -1, i25 = -1;
        for (int i = peak; i < end; i++) {
            double d = 10 * Math.log10(edc[i] / edc[peak] + 1e-12);
            if (i5 < 0 && d <= -5) i5 = i;
            if (i15 < 0 && d <= -15) i15 = i;
            if (i25 < 0 && d <= -25) { i25 = i; break; }
        }
        int stop = i25 > 0 ? i25 : i15;
        float drop = i25 > 0 ? 20 : 10;
        if (i5 < 0 || stop <= i5) return Float.NaN;
        return (stop - i5) * dt * 60 / drop;
    }

    private void analyse() {
        float t = rt60(captured, capturedLen, floorDb, (float) CHUNK / rate);
        Room r = current();
        if (Float.isNaN(t) || r == null) { status = "✗ Не вийшло — плесніть різкіше і голосніше"; return; }
        r.rt.add(t);
        Journal.add("Кімнати", r.name + ": час луни RT60", t, "с");
        status = String.format(Locale.US, "✓ Оплеск %d: луна затихає за %.2f с", r.rt.size(), t);
    }

    @Override public boolean animating() { return captureLeft >= 0; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Як кімнати «ковтають» звук", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, status, 8 * dp, 40 * dp, 11.5f, status.startsWith("✗") ? LessonKit.PINK : LessonKit.CYAN, Paint.Align.LEFT, false);
        float y = 64 * dp, rowH = 66 * dp;
        float maxRt = 2;
        for (Room r : rooms) if (!Float.isNaN(r.rt60())) maxRt = Math.max(maxRt, r.rt60() * 1.1f);
        for (int i = 0; i < rooms.size(); i++) {
            Room r = rooms.get(i);
            RectF box = new RectF(8 * dp, y, w - 8 * dp, y + rowH - 6 * dp);
            k.fill.setColor(r == current() ? 0xFF1E3A5F : 0xFF14213A);
            c.drawRoundRect(box, 10 * dp, 10 * dp, k.fill);
            k.label(c, r.name, box.left + 10 * dp, box.top + 18 * dp, 13, LessonKit.WHITE, Paint.Align.LEFT, true);
            k.label(c, String.format(Locale.US, "%.1f×%.1f×%.1f м = %.0f м³", r.l, r.w, r.h, r.volume()), box.right - 10 * dp, box.top + 18 * dp,
                    10, LessonKit.LABEL, Paint.Align.RIGHT, false);
            float rt = r.rt60();
            float bl = box.left + 10 * dp, br = box.right - 10 * dp, by = box.top + 28 * dp;
            k.rect(c, bl, by, br, by + 10 * dp, 0xFF0B1220);
            if (!Float.isNaN(rt)) {
                k.rect(c, bl, by, bl + (br - bl) * rt / maxRt, by + 10 * dp, rt < 0.4f ? LessonKit.GREEN : rt < 0.9f ? LessonKit.YELLOW : LessonKit.PINK);
                k.label(c, String.format(Locale.US, "RT60 %.2f с (%d опл.) · поглинання %.1f м² · α = %.2f", rt, r.rt.size(), r.absorptionArea(), r.alpha()),
                        bl, by + 26 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);
            } else {
                k.label(c, "плесніть у долоні…", bl, by + 26 * dp, 11, LessonKit.LABEL, Paint.Align.LEFT, false);
            }
            y += rowH;
        }
        k.label(c, "α = 0 — усе відбивається (кахель), α = 1 — усе поглинається (відчинене вікно)", w / 2f, h - 14 * dp, 10,
                LessonKit.LABEL, Paint.Align.CENTER, false);
    }
}
