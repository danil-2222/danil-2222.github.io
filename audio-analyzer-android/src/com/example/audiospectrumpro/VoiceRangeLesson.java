package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.Locale;

/** Voice range: notes you can hold are marked on a piano keyboard; the range suggests a voice type. */
final class VoiceRangeLesson implements LessonView {
    static final int LOW = 36, HIGH = 88;   // C2 .. E6 on the keyboard
    static final String[] TYPES = {"Бас", "Баритон", "Тенор", "Альт (контральто)", "Меццо-сопрано", "Сопрано"};
    /** Typical comfortable ranges (MIDI). */
    static final int[][] RANGES = {{40, 64}, {43, 67}, {48, 72}, {53, 77}, {57, 81}, {60, 84}};

    private final LessonKit k;
    private final float[] heldMs = new float[128];
    private int lowest = -1, highest = -1;
    private float currentMidi = Float.NaN;
    private int candidate = -1;
    private float candidateMs;

    VoiceRangeLesson(float dp) { k = new LessonKit(dp); }

    void reset() {
        java.util.Arrays.fill(heldMs, 0);
        lowest = highest = -1;
        candidate = -1;
    }

    int lowest() { return lowest; }
    int highest() { return highest; }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float hz = f.rmsDb - f.calibrationDb > -50 ? LessonRenderer.yin(f.time, f.sampleRate) : 0;
        if (hz <= 0 || hz < 50 || hz > 1400) {
            currentMidi = Float.NaN;
            candidate = -1;
            return;
        }
        float midi = (float) (69 + 12 * Math.log(hz / 440.0) / Math.log(2));
        currentMidi = midi;
        int note = Math.round(midi);
        float dt = 1000f * AudioEngine.HOP / f.sampleRate;
        if (note == candidate && Math.abs(midi - note) < 0.5f) {
            candidateMs += dt;
        } else {
            candidate = note;
            candidateMs = dt;
        }
        // A note counts once it has been held for 0.3 s: short glitches and octave jumps do not.
        if (candidateMs >= 300) {
            heldMs[note] = Math.max(heldMs[note], candidateMs);
            if (lowest < 0 || note < lowest) lowest = note;
            if (highest < 0 || note > highest) highest = note;
        }
    }

    /** Voice type whose typical range best overlaps the sung range, or -1. */
    int voiceType() {
        if (lowest < 0 || highest - lowest < 5) return -1;
        int best = -1;
        float bestScore = -1e9f;
        for (int t = 0; t < RANGES.length; t++) {
            int lo = Math.max(lowest, RANGES[t][0]), hi = Math.min(highest, RANGES[t][1]);
            float overlap = Math.max(0, hi - lo);
            float outside = Math.max(0, RANGES[t][0] - lowest) + Math.max(0, highest - RANGES[t][1]);
            float unused = Math.max(0, lowest - RANGES[t][0]) + Math.max(0, RANGES[t][1] - highest);
            float score = overlap - 0.8f * outside - 0.3f * unused;
            if (score > bestScore) { bestScore = score; best = t; }
        }
        return best;
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    private static boolean black(int midi) {
        int pc = ((midi % 12) + 12) % 12;
        return pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10;
    }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Ваш голосовий діапазон", 8 * dp, 18 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Заспівайте найнижчу ноту, потім плавно піднімайтесь до найвищої", 8 * dp, 34 * dp, 11, LessonKit.SOFT,
                Paint.Align.LEFT, false);

        // Current note.
        if (!Float.isNaN(currentMidi)) {
            int n = Math.round(currentMidi);
            float cents = (currentMidi - n) * 100;
            k.label(c, LessonRenderer.noteUa(n), w / 2f, h * 0.20f, 30, Math.abs(cents) < 25 ? LessonKit.GREEN : LessonKit.YELLOW,
                    Paint.Align.CENTER, true);
            k.label(c, String.format(Locale.US, "%.1f Гц  %+.0f¢", LessonKit.midiToHz(currentMidi), cents), w / 2f, h * 0.20f + 22 * dp,
                    12, LessonKit.SOFT, Paint.Align.CENTER, false);
        } else {
            k.label(c, "співайте…", w / 2f, h * 0.20f, 18, LessonKit.LABEL, Paint.Align.CENTER, false);
        }

        // Keyboard: white keys across the width, black keys on top.
        int whites = 0;
        for (int m = LOW; m <= HIGH; m++) if (!black(m)) whites++;
        float kl = 6 * dp, kr = w - 6 * dp, kt = h * 0.36f, kb = h * 0.58f;
        float ww = (kr - kl) / whites;
        float[] xs = new float[128];
        int wi = 0;
        for (int m = LOW; m <= HIGH; m++) {
            if (black(m)) continue;
            float x0 = kl + wi * ww;
            xs[m] = x0 + ww / 2;
            boolean sung = heldMs[m] > 0;
            boolean inRange = lowest >= 0 && m >= lowest && m <= highest;
            k.rect(c, x0 + 0.5f, kt, x0 + ww - 0.5f, kb, sung ? 0xFF39FF14 : inRange ? 0xFF9BE79B : 0xFFE2E8F0);
            if (m % 12 == 0) k.label(c, "C" + (m / 12 - 1), x0 + ww / 2, kb + 12 * dp, 9, LessonKit.LABEL, Paint.Align.CENTER, false);
            wi++;
        }
        wi = 0;
        for (int m = LOW; m <= HIGH; m++) {
            if (!black(m)) { wi++; continue; }
            float x = kl + wi * ww;
            xs[m] = x;
            boolean sung = heldMs[m] > 0;
            boolean inRange = lowest >= 0 && m >= lowest && m <= highest;
            k.rect(c, x - ww * 0.32f, kt, x + ww * 0.32f, kt + (kb - kt) * 0.6f, sung ? 0xFF1E8C0F : inRange ? 0xFF3E6B3E : 0xFF0F172A);
        }
        if (!Float.isNaN(currentMidi) && currentMidi >= LOW && currentMidi <= HIGH) {
            float x = xs[Math.round(currentMidi)];
            k.fill.setColor(LessonKit.PINK);
            c.drawCircle(x, kt - 8 * dp, 6 * dp, k.fill);
        }

        // Result.
        float y = kb + 36 * dp;
        if (lowest < 0) {
            k.label(c, "Ноти, які ви втримаєте 0,3 с, засвітяться зеленим", w / 2f, y, 12, LessonKit.SOFT, Paint.Align.CENTER, false);
        } else {
            int semis = highest - lowest;
            k.label(c, String.format(Locale.US, "Діапазон: %s — %s", LessonRenderer.noteUa(lowest), LessonRenderer.noteUa(highest)),
                    w / 2f, y, 16, LessonKit.WHITE, Paint.Align.CENTER, true);
            k.label(c, String.format(Locale.US, "%d півтонів (%d окт. %d півт.) · %.0f–%.0f Гц", semis, semis / 12, semis % 12,
                    LessonKit.midiToHz(lowest), LessonKit.midiToHz(highest)), w / 2f, y + 20 * dp, 11, LessonKit.SOFT, Paint.Align.CENTER, false);
            int t = voiceType();
            k.label(c, t < 0 ? "Співайте ширше, щоб визначити тип голосу" : "Схоже на: " + TYPES[t], w / 2f, y + 46 * dp, 18,
                    t < 0 ? LessonKit.LABEL : LessonKit.CYAN, Paint.Align.CENTER, true);
            if (t >= 0) {
                k.label(c, String.format(Locale.US, "(типовий діапазон: %s — %s)", LessonRenderer.noteUa(RANGES[t][0]),
                        LessonRenderer.noteUa(RANGES[t][1])), w / 2f, y + 64 * dp, 10.5f, LessonKit.LABEL, Paint.Align.CENTER, false);
            }
        }
        k.label(c, "Співаки тренуються роками, щоб розширити діапазон; зазвичай це 1,5–2 октави", w / 2f, h - 8 * dp, 9.5f,
                LessonKit.LABEL, Paint.Align.CENTER, false);
    }
}
