package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Envelope (ADSR): how loudness changes during one sound. Detects an onset, records 1.5 s and
 * splits it into attack, decay, sustain and release.
 */
final class EnvelopeLesson implements LessonView {
    private static final int CHUNK = 240;            // 5 ms at 48 kHz
    private final LessonKit k;
    private final LessonKit.SampleTap tap = new LessonKit.SampleTap();
    private float[] carry = new float[0];
    private int rate = 48000;
    private float floorDb = -70;
    private final float[] live = new float[600];      // last 3 s, linear amplitude
    private int liveHead;
    private float[] capture;
    private int captured = -1;
    private float[] shown;
    private int iPeak, iDecayEnd, iSustainEnd, iEnd;
    private String verdict = "";

    EnvelopeLesson(float dp) { k = new LessonKit(dp); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        rate = f.sampleRate;
        float[] fresh = tap.take(f);
        if (fresh.length == 0) return;
        int n = Math.max(64, rate / 200);
        float[] buf = new float[carry.length + fresh.length];
        System.arraycopy(carry, 0, buf, 0, carry.length);
        System.arraycopy(fresh, 0, buf, carry.length, fresh.length);
        int off = 0;
        for (; off + n <= buf.length; off += n) {
            double e = 0;
            for (int i = 0; i < n; i++) e += buf[off + i] * buf[off + i];
            onChunk((float) Math.sqrt(e / n));
        }
        carry = java.util.Arrays.copyOfRange(buf, off, buf.length);
    }

    private void onChunk(float amp) {
        live[liveHead] = amp;
        liveHead = (liveHead + 1) % live.length;
        float db = (float) (20 * Math.log10(amp + 1e-9));
        if (captured >= 0) {
            capture[captured++] = amp;
            if (captured == capture.length) {
                analyse();
                captured = -1;
            }
            return;
        }
        floorDb = db < floorDb ? db : floorDb * 0.99f + db * 0.01f;
        if (db > floorDb + 20 && db > -45) {
            capture = new float[300];             // 1.5 s
            captured = 0;
            capture[captured++] = amp;
        }
    }

    private void analyse() {
        shown = capture;
        int n = shown.length;
        iPeak = 0;
        for (int i = 0; i < n / 2; i++) if (shown[i] > shown[iPeak]) iPeak = i;
        float peak = shown[iPeak];
        float noise = (float) Math.pow(10, floorDb / 20);
        iEnd = n - 1;
        for (int i = iPeak; i < n; i++) if (shown[i] < Math.max(noise * 2, peak * 0.03f)) { iEnd = i; break; }
        // Sustain: typical level in the middle of the sound.
        int a = iPeak + (iEnd - iPeak) / 3, b = iPeak + 2 * (iEnd - iPeak) / 3;
        float[] mid = java.util.Arrays.copyOfRange(shown, a, Math.max(a + 1, b));
        java.util.Arrays.sort(mid);
        float sustain = mid[mid.length / 2];
        // A sound that keeps fading has no sustain: compare the level at one and two thirds of it.
        boolean fading = shown[b] < shown[a] * 0.6f;
        iDecayEnd = iPeak;
        if (fading) {
            iDecayEnd = iEnd;
            iSustainEnd = iEnd;
        } else {
            while (iDecayEnd < iEnd && shown[iDecayEnd] > sustain * 1.15f) iDecayEnd++;
            iSustainEnd = iDecayEnd;
            while (iSustainEnd < iEnd && shown[iSustainEnd] > sustain * 0.6f) iSustainEnd++;
        }
        float attackMs = iPeak * 5f, sustainRel = sustain / peak, lengthMs = iEnd * 5f;
        if (fading && attackMs < 40) {
            verdict = String.format(Locale.US, "Удар або щипок: атака %.0f мс, далі звук лише згасає — як піаніно, гітара, дзвіночок", attackMs);
        } else if (sustainRel < 0.25f && attackMs < 30) {
            verdict = String.format(Locale.US, "Удар або щипок: атака %.0f мс, без витримки — як піаніно, гітара, барабан, ляск", attackMs);
        } else if (attackMs > 60) {
            verdict = String.format(Locale.US, "М'який початок (атака %.0f мс) і витримка — як смичок, флейта, протяжний голос", attackMs);
        } else {
            verdict = String.format(Locale.US, "Різкий початок і витримка %.0f%% — як орган, синтезатор, вигук «а!»", sustainRel * 100);
        }
        if (lengthMs >= 1490) verdict += " (звук довший за 1,5 с)";
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Огинаюча звуку: ADSR", 8 * dp, 20 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Ляскніть, смикніть струну, скажіть «па» або протягніть «а-а-а»", 8 * dp, 36 * dp, 11, LessonKit.SOFT,
                Paint.Align.LEFT, false);
        float left = 10 * dp, right = w - 10 * dp, top = 70 * dp, bottom = h * 0.66f;
        k.rect(c, left, top, right, bottom, 0xFF0B1220);
        if (shown == null || captured >= 0) {
            float max = 1e-4f;
            for (float v : live) max = Math.max(max, v);
            k.path.reset();
            for (int i = 0; i < live.length; i++) {
                float v = live[(liveHead + i) % live.length] / max;
                float x = left + (float) i / (live.length - 1) * (right - left), y = bottom - v * (bottom - top) * 0.9f;
                if (i == 0) k.path.moveTo(x, y); else k.path.lineTo(x, y);
            }
            k.stroke.setColor(LessonKit.BLUE);
            k.stroke.setStrokeWidth(1.5f * dp);
            c.drawPath(k.path, k.stroke);
            k.label(c, captured >= 0 ? "Записую звук…" : "Чекаю на звук (графік — гучність за 3 с)", w / 2f, top + 20 * dp, 13,
                    captured >= 0 ? LessonKit.YELLOW : LessonKit.SOFT, Paint.Align.CENTER, true);
            return;
        }
        float peak = shown[iPeak];
        int n = shown.length;
        int[] bounds = {0, iPeak, iDecayEnd, iSustainEnd, iEnd};
        String[] names = {"A атака", "D спад", "S витримка", "R згасання"};
        int[] colors = {LessonKit.GREEN, LessonKit.YELLOW, LessonKit.CYAN, LessonKit.PINK};
        for (int p = 0; p < 4; p++) {
            float x0 = left + (float) bounds[p] / (n - 1) * (right - left), x1 = left + (float) bounds[p + 1] / (n - 1) * (right - left);
            if (x1 - x0 < 1) continue;
            k.rect(c, x0, top, x1, bottom, (colors[p] & 0x00FFFFFF) | 0x22000000);
            k.label(c, names[p], (x0 + x1) / 2, bottom + 14 * dp, 10, colors[p], Paint.Align.CENTER, true);
        }
        k.path.reset();
        for (int i = 0; i < n; i++) {
            float x = left + (float) i / (n - 1) * (right - left), y = bottom - shown[i] / peak * (bottom - top) * 0.9f;
            if (i == 0) k.path.moveTo(x, y); else k.path.lineTo(x, y);
        }
        k.stroke.setColor(LessonKit.WHITE);
        k.stroke.setStrokeWidth(2 * dp);
        c.drawPath(k.path, k.stroke);
        for (int s = 0; s <= 15; s += 5) {
            k.label(c, String.format(Locale.US, "%.1f с", s / 10f), left + s / 15f * (right - left), top - 4 * dp, 9, LessonKit.LABEL,
                    s == 0 ? Paint.Align.LEFT : s == 15 ? Paint.Align.RIGHT : Paint.Align.CENTER, false);
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        k.text.setTextSize(12 * dp);
        for (String word : verdict.split(" ")) {
            String cand = line.length() == 0 ? word : line + " " + word;
            if (k.text.measureText(cand) > w - 16 * dp && line.length() > 0) { lines.add(line.toString()); line = new StringBuilder(word); }
            else line = new StringBuilder(cand);
        }
        lines.add(line.toString());
        for (int i = 0; i < lines.size(); i++) {
            k.label(c, lines.get(i), 8 * dp, bottom + 40 * dp + i * 17 * dp, 12, LessonKit.WHITE, Paint.Align.LEFT, i == 0);
        }
        k.label(c, "Однакова нота, але різна огинаюча — і ми чуємо різні інструменти", 8 * dp, h - 8 * dp, 10,
                LessonKit.LABEL, Paint.Align.LEFT, false);
    }
}
