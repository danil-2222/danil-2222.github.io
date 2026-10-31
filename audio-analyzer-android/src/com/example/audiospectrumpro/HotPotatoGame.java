package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Hot potato: the phone ticks faster and faster and will "explode" at a secret moment. You may pass it
 * on only after doing the sound task on the screen (whistle, squeak, hum, hiss, blow). Whoever holds the
 * phone at the bang is out.
 */
final class HotPotatoGame implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); }

    enum State { READY, TICKING, BOOM }

    static final int RATE = 48000;

    private final LessonKit k;
    private final Sound sound;
    private final java.util.Random rnd = new java.util.Random();
    private final SoundTasks.Hold hold = new SoundTasks.Hold();
    private State state = State.READY;
    private float clock, startAt, fuseS, readyAt;
    int task = -1;
    private boolean done;
    private int passes;
    private float shake;

    HotPotatoGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
    }

    /** Ticks that speed up towards the end, then the bang. */
    static float[] fuse(float seconds) {
        int n = (int) ((seconds + 1.5f) * RATE);
        float[] out = new float[n];
        float t = 0;
        while (t < seconds) {
            float left = seconds - t, gap = Math.max(0.12f, Math.min(1.0f, left / 8f));
            LessonKit.addTone(out, (int) (t * RATE), RATE / 70, 3200, 0.35f, RATE, RATE / 1000);
            t += gap;
        }
        java.util.Random r = new java.util.Random(1);
        int at = (int) (seconds * RATE);
        for (int i = 0; i < RATE && at + i < n; i++) {
            double tt = (double) i / RATE;
            out[at + i] += (float) ((r.nextFloat() * 2 - 1) * Math.exp(-tt * 5) * 0.9
                    + 0.8 * Math.exp(-tt * 6) * Math.sin(2 * Math.PI * 55 * tt));
        }
        return out;
    }

    void start() { start(20 + rnd.nextFloat() * 25, rnd.nextInt(5)); }

    void start(float seconds, int firstTask) {
        fuseS = seconds;
        startAt = clock;
        passes = 0;
        task = firstTask;
        done = false;
        hold.reset();
        sound.play(fuse(seconds), false);
        state = State.TICKING;
    }

    /** For tests: just pick a task without the fuse. */
    void setTask(int t) { task = t; done = false; hold.reset(); readyAt = clock; }

    String state() { return state.name(); }
    boolean done() { return done; }
    int passes() { return passes; }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float dt = (float) AudioEngine.HOP / f.sampleRate;
        clock += dt;
        shake = state == State.TICKING ? Math.min(1, (clock - startAt) / fuseS) : 0;
        if (state == State.TICKING && clock - startAt >= fuseS) { state = State.BOOM; return; }
        if (task < 0 || done || clock < readyAt) return;
        boolean[] kind = SoundTasks.classify(f);
        if (hold.feed(kind[task], dt)) {
            done = true;
            passes++;
            // The next holder gets a new task a moment later.
            if (state == State.TICKING) {
                final int prev = task;
                int next;
                do { next = rnd.nextInt(5); } while (next == prev);
                task = next;
                done = false;
                hold.reset();
                readyAt = clock + 1.2f;
            }
        }
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Гаряча картопля", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Виконай завдання — тоді передавай!", 8 * dp, 40 * dp, 11.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        float cx = w / 2f, cy = h * 0.36f;
        if (state == State.BOOM) {
            k.fill.setColor(0xFFFF6D00);
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6;
                c.drawCircle(cx + (float) Math.cos(a) * 60 * dp, cy + (float) Math.sin(a) * 60 * dp, 26 * dp, k.fill);
            }
            k.fill.setColor(0xFFFFEB3B);
            c.drawCircle(cx, cy, 60 * dp, k.fill);
            k.label(c, "БАБАХ! 💥", cx, cy + 10 * dp, 30, 0xFFB71C1C, Paint.Align.CENTER, true);
            k.label(c, "У кого телефон — вибуває! Передач: " + passes, cx, h * 0.72f, 16, LessonKit.WHITE, Paint.Align.CENTER, true);
            k.label(c, "«▶ Старт» — наступний раунд", cx, h - 24 * dp, 13, LessonKit.CYAN, Paint.Align.CENTER, false);
            return;
        }
        // The potato, shaking more as the fuse burns down.
        float jig = state == State.TICKING ? (float) Math.sin(clock * (10 + 50 * shake)) * 10 * dp * shake : 0;
        k.fill.setColor(0xFFC69C6D);
        c.drawOval(new RectF(cx - 80 * dp + jig, cy - 55 * dp, cx + 80 * dp + jig, cy + 55 * dp), k.fill);
        k.fill.setColor(0xFF8D6E63);
        for (int i = 0; i < 5; i++) c.drawCircle(cx - 50 * dp + i * 25 * dp + jig, cy + (i % 2 == 0 ? -15 : 18) * dp, 4 * dp, k.fill);
        if (state == State.TICKING) {
            k.fill.setColor(0x55FF3D00);
            c.drawOval(new RectF(cx - 90 * dp + jig, cy - 65 * dp, cx + 90 * dp + jig, cy + 65 * dp), k.fill);
        }
        if (task >= 0) {
            boolean waiting = clock < readyAt;
            k.label(c, waiting ? "✓ Передавай далі!" : SoundTasks.TASKS[task], cx, h * 0.66f, 19,
                    waiting ? LessonKit.GREEN : LessonKit.YELLOW, Paint.Align.CENTER, true);
            float bw = w * 0.6f, by = h * 0.66f + 18 * dp;
            k.rect(c, cx - bw / 2, by, cx + bw / 2, by + 10 * dp, 0xFF1E293B);
            k.rect(c, cx - bw / 2, by, cx - bw / 2 + bw * (done ? 1 : hold.progress()), by + 10 * dp, LessonKit.GREEN);
        }
        k.label(c, state == State.READY ? "Натисніть «▶ Старт» і передавайте телефон по колу" : "Передач: " + passes,
                cx, h - 24 * dp, 13, LessonKit.CYAN, Paint.Align.CENTER, false);
    }
}
