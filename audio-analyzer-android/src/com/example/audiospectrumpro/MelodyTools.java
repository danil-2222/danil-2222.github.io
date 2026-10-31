package com.example.audiospectrumpro;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Turning singing into notes and comparing melodies (independent of the key they are sung in). */
final class MelodyTools {
    private MelodyTools() {}

    static float hzToMidi(float hz) { return 69 + 12 * (float) (Math.log(hz / 440.0) / Math.log(2)); }

    /**
     * Notes (MIDI numbers, fractional) of a sung recording: pitch every 10 ms, steady stretches of at
     * least 120 ms become notes. A small slide or vibrato stays one note; a jump or a pause starts a new one.
     */
    static List<Float> transcribe(float[] x, int rate) {
        int win = Integer.highestOneBit(Math.max(1024, rate / 24)), hop = rate / 100;
        List<Float> pitch = new ArrayList<>();
        double peak = 1e-12;
        for (float v : x) peak = Math.max(peak, v * v);
        float gate = (float) (10 * Math.log10(peak)) - 30;
        float[] buf = new float[win];
        for (int off = 0; off + win <= x.length; off += hop) {
            System.arraycopy(x, off, buf, 0, win);
            float db = LessonKit.blockDb(buf, 0, win);
            float hz = db > gate && db > -55 ? LessonRenderer.yin(buf, rate) : 0;
            pitch.add(hz > 60 && hz < 1500 ? hzToMidi(hz) : Float.NaN);
        }
        List<Float> notes = new ArrayList<>();
        List<Float> seg = new ArrayList<>(), pend = new ArrayList<>();
        int gap = 0;
        for (float m : pitch) {
            if (Float.isNaN(m)) {
                if (++gap >= 3) { close(seg, notes); pend.clear(); }
                continue;
            }
            gap = 0;
            if (seg.isEmpty() || Math.abs(m - median(seg)) <= 0.7f) {
                seg.add(m);
                pend.clear();
            } else {
                pend.add(m);
                if (pend.size() >= 3) {           // a real jump, not a wobble
                    close(seg, notes);
                    seg.addAll(pend);
                    pend.clear();
                }
            }
        }
        close(seg, notes);
        return notes;
    }

    private static void close(List<Float> seg, List<Float> notes) {
        if (seg.size() >= 12) notes.add(median(seg));
        seg.clear();
    }

    static float median(List<Float> v) {
        float[] a = new float[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        return a[a.length / 2];
    }

    /**
     * How alike two melodies are, 0..1, comparing the jumps between notes (so a melody sung lower or
     * higher is still the same melody). A jump off by a semitone costs half a mistake.
     */
    static float similarity(List<Float> a, List<Float> b) {
        if (a.size() < 2 || b.size() < 2) return a.size() == b.size() && a.size() > 0 ? 1 : 0;
        float[] ia = intervals(a), ib = intervals(b);
        int n = ia.length, m = ib.length;
        float[][] d = new float[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                float sub = Math.min(1f, Math.max(0, Math.abs(ia[i - 1] - ib[j - 1]) - 0.4f) / 2f);
                d[i][j] = Math.min(d[i - 1][j - 1] + sub, Math.min(d[i - 1][j], d[i][j - 1]) + 1);
            }
        }
        return Math.max(0, 1 - d[n][m] / Math.max(n, m));
    }

    static float[] intervals(List<Float> notes) {
        float[] r = new float[notes.size() - 1];
        for (int i = 1; i < notes.size(); i++) r[i - 1] = notes.get(i) - notes.get(i - 1);
        return r;
    }

    /** Linear resampling to 48 kHz for playback. */
    static float[] to48k(float[] x, int rate) {
        if (rate == 48000) return x.clone();
        int n = (int) ((long) x.length * 48000 / rate);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double p = (double) i * rate / 48000;
            int j = (int) p;
            float f = (float) (p - j);
            out[i] = j + 1 < x.length ? x[j] * (1 - f) + x[j + 1] * f : x[Math.min(j, x.length - 1)];
        }
        return out;
    }

    /** A melody as sound: plucked notes of the given length. */
    static float[] render(List<Float> midi, float noteS) {
        int rate = 48000, len = (int) (noteS * rate);
        float[] out = new float[len * midi.size() + rate / 2];
        for (int i = 0; i < midi.size(); i++) LessonKit.addNote(out, i * len, len, LessonKit.midiToHz(midi.get(i)), 0.6f, rate);
        return out;
    }

    static final String[] NAMES = {"до", "до♯", "ре", "ре♯", "мі", "фа", "фа♯", "соль", "соль♯", "ля", "ля♯", "сі"};

    static String noteName(float midi) {
        int m = Math.round(midi);
        return NAMES[((m % 12) + 12) % 12] + (m / 12 - 1);
    }
}
