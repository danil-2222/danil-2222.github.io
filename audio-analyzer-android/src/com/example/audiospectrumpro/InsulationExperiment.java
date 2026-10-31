package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sound insulation: one phone plays noise with equal energy in every octave; the other records octave
 * levels once without a barrier and then behind doors, pillows, windows… Each barrier gets a bar chart of
 * how many dB it removes per octave. Low notes pass almost everything — that is why you hear the
 * neighbours' bass but not their voices.
 */
final class InsulationExperiment implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); void stop(); }

    static final float[] BANDS = {125, 250, 500, 1000, 2000, 4000, 8000};
    static final float AVG_S = 3f;
    static final int[] COLORS = {LessonKit.CYAN, LessonKit.YELLOW, LessonKit.PINK, LessonKit.GREEN};

    boolean source;
    private final LessonKit k;
    private final Sound sound;
    private float[] reference;
    private final List<String> names = new ArrayList<>();
    private final List<float[]> barriers = new ArrayList<>();
    private double[] acc = new double[BANDS.length];
    private int n;
    private float left = -1;
    private String pendingName;

    InsulationExperiment(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
    }

    /** One second of "pink" noise: whole-hertz sines spread evenly on a log scale (loops seamlessly). */
    static float[] pinkNoise() {
        java.util.Random r = new java.util.Random(3);
        float[] out = new float[48000];
        int count = 420;
        for (int s = 0; s < count; s++) {
            int f = (int) Math.round(88 * Math.pow(2, 7.0 * s / count));
            double ph = r.nextDouble() * 2 * Math.PI, w = 2 * Math.PI * f / 48000;
            for (int i = 0; i < out.length; i++) out[i] += (float) Math.sin(w * i + ph);
        }
        LessonKit.normalize(out, 0.8f);
        return out;
    }

    void start() { if (source) sound.play(pinkNoise(), true); else sound.stop(); }

    /** name == null: the reference without a barrier. */
    void record(String name) {
        pendingName = name;
        acc = new double[BANDS.length];
        n = 0;
        left = AVG_S;
    }

    void clear() { reference = null; names.clear(); barriers.clear(); }

    /** Attenuation (dB) of barrier i in every band, or null. */
    float[] attenuation(int i) {
        if (reference == null || i >= barriers.size()) return null;
        float[] a = new float[BANDS.length];
        for (int b = 0; b < a.length; b++) a[b] = reference[b] - barriers.get(i)[b];
        return a;
    }

    int barrierCount() { return barriers.size(); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (source || left <= 0) return;
        for (int b = 0; b < BANDS.length; b++) acc[b] += LessonKit.bandPower(f, BANDS[b] / 1.4142f, BANDS[b] * 1.4142f);
        n++;
        left -= (float) AudioEngine.HOP / f.sampleRate;
        if (left > 0) return;
        float[] db = new float[BANDS.length];
        for (int b = 0; b < db.length; b++) db[b] = (float) (10 * Math.log10(acc[b] / n + 1e-20));
        if (pendingName == null) {
            reference = db;
        } else {
            if (barriers.size() >= COLORS.length) { barriers.remove(0); names.remove(0); }
            barriers.add(db);
            names.add(pendingName.isEmpty() ? "Перешкода " + (barriers.size()) : pendingName);
            float[] a = attenuation(barriers.size() - 1);
            if (a != null) {
                float avg = 0;
                for (float v : a) avg += v;
                Journal.add("Звукоізоляція", names.get(names.size() - 1) + ": у середньому", avg / a.length, "дБ");
            }
        }
    }

    @Override public boolean animating() { return left > 0; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Звукоізоляція", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        if (source) {
            k.label(c, "Цей телефон — джерело шуму 🔊", w / 2f, h * 0.35f, 18, LessonKit.CYAN, Paint.Align.CENTER, true);
            k.label(c, "Шум однаковий в усіх октавах — від басу до дзвону", w / 2f, h * 0.35f + 26 * dp, 12, LessonKit.SOFT,
                    Paint.Align.CENTER, false);
            return;
        }
        String st = left > 0 ? "🎤 Записую…" : reference == null ? "1) Запишіть рівень БЕЗ перешкоди" : "2) Поставте перешкоду і запишіть ще";
        k.label(c, st, 8 * dp, 40 * dp, 12, left > 0 ? LessonKit.YELLOW : LessonKit.SOFT, Paint.Align.LEFT, false);
        RectF g = new RectF(40 * dp, 60 * dp, w - 12 * dp, h * 0.62f);
        k.rect(c, g.left, g.top, g.right, g.bottom, 0xFF0B1220);
        float maxDb = 40;
        for (int d = 0; d <= maxDb; d += 10) {
            float y = g.top + g.height() * d / maxDb;
            k.line(c, g.left, y, g.right, y, LessonKit.GRID, 1);
            k.label(c, "−" + d, g.left - 4 * dp, y + 4 * dp, 9.5f, LessonKit.LABEL, Paint.Align.RIGHT, false);
        }
        float group = g.width() / BANDS.length;
        int nb = Math.max(1, barriers.size());
        for (int b = 0; b < BANDS.length; b++) {
            float gx = g.left + group * b;
            String lab = BANDS[b] >= 1000 ? (int) (BANDS[b] / 1000) + "к" : String.valueOf((int) BANDS[b]);
            k.label(c, lab, gx + group / 2, g.bottom + 14 * dp, 10, LessonKit.LABEL, Paint.Align.CENTER, false);
            for (int i = 0; i < barriers.size(); i++) {
                float[] a = attenuation(i);
                if (a == null) continue;
                float bw = (group - 8 * dp) / nb, x = gx + 4 * dp + bw * i;
                float hgt = Math.max(0, Math.min(maxDb, a[b])) / maxDb * g.height();
                k.rect(c, x, g.top, x + bw - 1, g.top + hgt, COLORS[i]);
            }
        }
        k.label(c, "Гц", g.right, g.bottom + 28 * dp, 9.5f, LessonKit.LABEL, Paint.Align.RIGHT, false);
        float y = g.bottom + 46 * dp;
        for (int i = 0; i < barriers.size(); i++) {
            float[] a = attenuation(i);
            if (a == null) continue;
            float avg = 0;
            for (float v : a) avg += v;
            avg /= a.length;
            k.fill.setColor(COLORS[i]);
            c.drawRect(12 * dp, y - 10 * dp, 24 * dp, y + 2 * dp, k.fill);
            k.label(c, String.format(Locale.US, "%s: у середньому −%.0f дБ (бас −%.0f, дзвін −%.0f)", names.get(i), avg, a[0], a[a.length - 1]),
                    30 * dp, y, 12, LessonKit.WHITE, Paint.Align.LEFT, false);
            y += 20 * dp;
        }
        if (reference != null && barriers.isEmpty()) k.label(c, "✓ Рівень без перешкоди записано", 12 * dp, y, 12, LessonKit.GREEN, Paint.Align.LEFT, true);
    }
}
