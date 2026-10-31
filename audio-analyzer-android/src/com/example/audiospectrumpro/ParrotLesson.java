package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * Talking parrot: waits for you to speak, records the phrase until you stop, then repeats it in a
 * funny voice. The microphone is ignored while the parrot is talking so it does not repeat itself.
 */
final class ParrotLesson implements LessonView {
    interface Speaker { float[] speak(short[] phrase, int rate); }

    enum State { LISTENING, RECORDING, TALKING }

    private final LessonKit k;
    private final Speaker speaker;
    private final LessonKit.SampleTap tap = new LessonKit.SampleTap();
    private State state = State.LISTENING;
    private float floorDb = -60;
    private short[] rec = new short[48000 * 6];
    private int recLen, silentMs, preLen;
    private final short[] pre = new short[48000 / 3];       // ~0.3 s kept before the voice starts
    private int preHead;
    private long talkUntil;
    private float[] talking;
    private long talkStart;
    private int phrases;
    private final long start = System.currentTimeMillis();

    ParrotLesson(float dp, Speaker speaker) {
        k = new LessonKit(dp);
        this.speaker = speaker;
    }

    int phrases() { return phrases; }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float[] fresh = tap.take(f);
        if (state == State.TALKING) {
            if (System.currentTimeMillis() < talkUntil) return;
            state = State.LISTENING;
            preLen = 0;
        }
        int block = f.sampleRate / 50;
        for (int off = 0; off + block <= fresh.length; off += block) {
            double e = 0;
            for (int i = 0; i < block; i++) e += fresh[off + i] * fresh[off + i];
            float db = (float) (10 * Math.log10(e / block + 1e-14));
            if (state == State.LISTENING) {
                floorDb = db < floorDb ? db : floorDb * 0.99f + db * 0.01f;
                for (int i = 0; i < block; i++) {
                    pre[preHead] = toShort(fresh[off + i]);
                    preHead = (preHead + 1) % pre.length;
                    if (preLen < pre.length) preLen++;
                }
                if (db > floorDb + 12 && db > -50) {
                    state = State.RECORDING;
                    recLen = 0;
                    silentMs = 0;
                    for (int i = 0; i < preLen; i++) rec[recLen++] = pre[(preHead - preLen + i + pre.length) % pre.length];
                }
            } else {
                for (int i = 0; i < block && recLen < rec.length; i++) rec[recLen++] = toShort(fresh[off + i]);
                silentMs = db < floorDb + 6 ? silentMs + 20 : 0;
                if (silentMs >= 600 || recLen >= rec.length) {
                    short[] phrase = java.util.Arrays.copyOf(rec, Math.max(0, recLen - f.sampleRate * 4 / 10));
                    talking = speaker.speak(phrase, f.sampleRate);
                    if (talking != null) {
                        phrases++;
                        state = State.TALKING;
                        talkStart = System.currentTimeMillis();
                        talkUntil = talkStart + talking.length * 1000L / 48000 + 400;
                    } else {
                        state = State.LISTENING;
                    }
                    return;
                }
            }
        }
    }

    private static short toShort(float v) { return (short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f); }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float t = (System.currentTimeMillis() - start) / 1000f;
        float cx = w / 2f, cy = h * 0.45f, s = Math.min(w, h) * 0.22f;
        float tilt = state == State.RECORDING ? (float) Math.sin(t * 3) * 6 : state == State.LISTENING ? -8 : 0;
        // How wide the beak opens: follows the parrot's own voice while it talks.
        float beak = 0;
        if (state == State.TALKING && talking != null) {
            int idx = (int) ((System.currentTimeMillis() - talkStart) * 48L);
            if (idx >= 0 && idx < talking.length) {
                float m = 0;
                for (int i = idx; i < Math.min(talking.length, idx + 800); i++) m = Math.max(m, Math.abs(talking[i]));
                beak = Math.min(1, m * 3);
            }
        }
        c.save();
        c.rotate(tilt, cx, cy);
        k.fill.setColor(0xFF2E7D32);
        c.drawOval(new RectF(cx - s * 0.8f, cy - s * 0.2f, cx + s * 0.8f, cy + s * 1.7f), k.fill);        // body
        k.fill.setColor(0xFF43A047);
        c.drawCircle(cx, cy - s * 0.3f, s * 0.75f, k.fill);                                               // head
        k.fill.setColor(0xFFFFEB3B);
        c.drawOval(new RectF(cx - s * 0.5f, cy - s * 0.05f, cx + s * 0.5f, cy + s * 0.6f), k.fill);        // cheeks
        k.fill.setColor(0xFFFFFFFF);
        c.drawCircle(cx + s * 0.3f, cy - s * 0.5f, s * 0.2f, k.fill);
        k.fill.setColor(0xFF000000);
        c.drawCircle(cx + s * 0.35f, cy - s * 0.5f, s * 0.09f, k.fill);
        k.fill.setColor(0xFFFF8F00);                                                                       // beak
        k.path.reset();
        k.path.moveTo(cx + s * 0.55f, cy - s * 0.35f);
        k.path.lineTo(cx + s * 1.15f, cy - s * 0.15f - beak * s * 0.15f);
        k.path.lineTo(cx + s * 0.6f, cy - s * 0.05f);
        k.path.close();
        c.drawPath(k.path, k.fill);
        k.path.reset();
        k.path.moveTo(cx + s * 0.6f, cy - s * 0.05f);
        k.path.lineTo(cx + s * 1.0f, cy + beak * s * 0.35f);
        k.path.lineTo(cx + s * 0.55f, cy + s * 0.08f);
        k.path.close();
        c.drawPath(k.path, k.fill);
        k.fill.setColor(0xFFD32F2F);                                                                       // crest
        for (int i = 0; i < 3; i++) c.drawCircle(cx - s * 0.2f + i * s * 0.15f, cy - s * 1.05f + Math.abs(i - 1) * s * 0.1f, s * 0.13f, k.fill);
        c.restore();
        k.line(c, cx - s * 1.4f, cy + s * 1.75f, cx + s * 1.4f, cy + s * 1.75f, 0xFF8D6E63, 6);         // perch

        k.label(c, "Папуга Кеша", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        String msg = state == State.LISTENING ? "Скажіть щось — Кеша слухає…"
                : state == State.RECORDING ? "Запам'ятовую…" : "Повторюю!";
        k.label(c, msg, cx, h - 44 * dp, 18, state == State.TALKING ? LessonKit.YELLOW : LessonKit.CYAN, Paint.Align.CENTER, true);
        k.label(c, "Повторено фраз: " + phrases, cx, h - 20 * dp, 11, LessonKit.LABEL, Paint.Align.CENTER, false);
    }
}
