package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Vowel map: the first two formants (resonances of the mouth and throat) found with linear
 * prediction, plotted on the classic F2 x F1 chart with Ukrainian vowel regions.
 */
final class VowelLesson implements LessonView {
    static final String[] VOWELS = {"і", "и", "е", "а", "о", "у"};
    /** Typical adult male F1, F2 in Hz; other voices are scaled up. */
    static final float[][] TARGETS = {{300, 2250}, {390, 1850}, {500, 1800}, {720, 1250}, {500, 900}, {330, 760}};
    static final String[] VOICES = {"Чоловічий голос", "Жіночий голос", "Дитячий голос"};
    static final float[] VOICE_SCALE = {1f, 1.17f, 1.32f};

    int voice;
    private final LessonKit k;
    private static final int TRAIL = 40;
    private final float[] trailF1 = new float[TRAIL], trailF2 = new float[TRAIL];
    private int trailHead, trailCount;
    private float f1, f2;
    private String vowel = "";
    private float[] envelopeDb = new float[0];
    private int envRate = 12000;
    private int quiet = 100;

    VowelLesson(float dp) { k = new LessonKit(dp); }

    // ---------------------------------------------------------------- analysis

    /** LPC envelope (dB) at n points from 0 to rate/2, from the newest samples; null if unvoiced. */
    /** LPC coefficients of the last analysed block (a[0] = 1), used to find formants from roots. */
    static double[] lastLpc;

    static float[] lpcEnvelope(float[] x, int rate, int points, int[] outRate) {
        int dec = Math.max(1, Math.round(rate / 11000f));
        int r = rate / dec;
        int n = Math.min(x.length / dec, Math.round(0.04f * r));
        float[] y = new float[n];
        int start = x.length - n * dec;
        // Proper anti-alias low-pass before keeping every dec-th sample: otherwise folded-down high
        // frequencies take LPC poles away from the low formants of "о" and "у".
        int pre = Math.min(start, rate / 100);
        float[] seg = java.util.Arrays.copyOfRange(x, start - pre, x.length);
        if (dec > 1) {
            for (int pass = 0; pass < 3; pass++) {
                BiquadFilter lp = new BiquadFilter();
                lp.setCustom(false, r * 0.42, 0.7071, rate);
                lp.process(seg, seg.length);
            }
        }
        for (int i = 0; i < n; i++) y[i] = seg[pre + i * dec];
        double energy = 0;
        for (int i = n - 1; i > 0; i--) y[i] -= 0.95f * y[i - 1];
        for (int i = 0; i < n; i++) {
            y[i] *= (float) (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (n - 1)));
            energy += y[i] * y[i];
        }
        if (energy < 1e-7) return null;
        int order = Math.min(12, r / 1000 + 1);
        double[] ac = new double[order + 1];
        for (int lag = 0; lag <= order; lag++) {
            double s = 0;
            for (int i = lag; i < n; i++) s += y[i] * y[i - lag];
            ac[lag] = s;
        }
        ac[0] *= 1.0001;
        // Levinson-Durbin.
        double[] a = new double[order + 1];
        a[0] = 1;
        double err = ac[0];
        for (int i = 1; i <= order; i++) {
            double acc = ac[i];
            for (int j = 1; j < i; j++) acc += a[j] * ac[i - j];
            double kk = -acc / err;
            double[] prev = a.clone();
            for (int j = 1; j < i; j++) a[j] = prev[j] + kk * prev[i - j];
            a[i] = kk;
            err *= 1 - kk * kk;
            if (err <= 0) return null;
        }
        lastLpc = a;
        float[] env = new float[points];
        for (int p = 0; p < points; p++) {
            double w = Math.PI * p / (points - 1);
            double re = 0, im = 0;
            for (int j = 0; j <= order; j++) {
                re += a[j] * Math.cos(j * w);
                im -= a[j] * Math.sin(j * w);
            }
            env[p] = (float) (-10 * Math.log10(re * re + im * im + 1e-20));
        }
        if (outRate != null) outRate[0] = r;
        return env;
    }

    /**
     * Formants from the roots of the LPC polynomial: each resonance is a complex root pair whose
     * angle gives the frequency and whose distance from the unit circle gives the bandwidth.
     * Unlike envelope peaks this still separates F1 and F2 when they are close ("о", "у").
     */
    static float[] formantsFromRoots(double[] a, int rate) {
        if (a == null) return null;
        int n = a.length - 1;
        // Durand-Kerner on z^n + a1 z^(n-1) + ... + an.
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n + 0.4;
            re[i] = 0.9 * Math.cos(ang);
            im[i] = 0.9 * Math.sin(ang);
        }
        for (int it = 0; it < 500; it++) {
            double change = 0;
            for (int i = 0; i < n; i++) {
                // p(z_i)
                double pr = 1, pi = 0;
                for (int k = 1; k <= n; k++) {
                    double nr = pr * re[i] - pi * im[i] + a[k], ni = pr * im[i] + pi * re[i];
                    pr = nr;
                    pi = ni;
                }
                // prod (z_i - z_j)
                double qr = 1, qi = 0;
                for (int j = 0; j < n; j++) {
                    if (j == i) continue;
                    double dr = re[i] - re[j], di = im[i] - im[j];
                    double nr = qr * dr - qi * di, ni = qr * di + qi * dr;
                    qr = nr;
                    qi = ni;
                }
                double den = qr * qr + qi * qi;
                if (den < 1e-30) continue;
                double sr = (pr * qr + pi * qi) / den, si = (pi * qr - pr * qi) / den;
                re[i] -= sr;
                im[i] -= si;
                change += Math.abs(sr) + Math.abs(si);
            }
            if (change < 1e-10) break;
        }
        java.util.List<float[]> res = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (im[i] <= 0) continue;
            double mag = Math.hypot(re[i], im[i]);
            double hz = Math.atan2(im[i], re[i]) * rate / (2 * Math.PI);
            double bw = -Math.log(mag) * rate / Math.PI;
            if (hz > 150 && hz < 4500 && bw > 0 && bw < 450) res.add(new float[]{(float) hz, (float) bw});
        }
        java.util.Collections.sort(res, new java.util.Comparator<float[]>() {
            @Override public int compare(float[] x, float[] y) { return Float.compare(x[0], y[0]); }
        });
        float f1v = -1, f2v = -1;
        for (float[] r : res) {
            if (f1v < 0) {
                if (r[0] >= 200 && r[0] <= 1100) f1v = r[0];
            } else if (r[0] > f1v + 150 && r[0] >= 550 && r[0] <= 3000) {
                f2v = r[0];
                break;
            }
        }
        return f1v > 0 && f2v > 0 ? new float[]{f1v, f2v} : null;
    }

    /** First two envelope peaks inside plausible formant ranges: {F1, F2} or null. */
    static float[] formants(float[] env, int rate) {
        float hzPer = rate / 2f / (env.length - 1);
        float[] peaks = new float[4];
        int count = 0;
        int last = Math.min(env.length - 1, (int) (4500 / hzPer));
        for (int i = 1; i < last && count < 4; i++) {
            if (env[i] > env[i - 1] && env[i] >= env[i + 1]) {
                float d = 0.5f * (env[i - 1] - env[i + 1]) / (env[i - 1] - 2 * env[i] + env[i + 1]);
                float hz = (i + d) * hzPer;
                if (hz > 180) peaks[count++] = hz;
            }
        }
        if (count < 2) return null;
        float a = peaks[0], b = peaks[1];
        // Back vowels often merge F1 and F2 into close peaks; keep them ordered and in range.
        if (a > 1100 || b < 550 || b > 3200) return null;
        return new float[]{a, b};
    }

    private int nearestVowel(float f1v, float f2v) {
        float s = VOICE_SCALE[voice];
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int v = 0; v < TARGETS.length; v++) {
            double d1 = Math.log(f1v / (TARGETS[v][0] * s)), d2 = Math.log(f2v / (TARGETS[v][1] * s));
            double d = d1 * d1 * 1.6 + d2 * d2;
            if (d < bestD) { bestD = d; best = v; }
        }
        return bestD < 0.12 ? best : -1;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float level = f.rmsDb - f.calibrationDb;
        float pitch = LessonRenderer.yin(f.time, f.sampleRate);
        if (level < -50 || pitch <= 0) {
            if (++quiet > 10) vowel = "";
            return;
        }
        int[] r = new int[1];
        float[] env = lpcEnvelope(f.time, f.sampleRate, 256, r);
        if (env == null) return;
        envelopeDb = env;
        envRate = r[0];
        float[] fm = formantsFromRoots(lastLpc, r[0]);
        if (fm == null) fm = formants(env, r[0]);
        if (fm == null) return;
        quiet = 0;
        f1 = f1 == 0 ? fm[0] : f1 * 0.6f + fm[0] * 0.4f;
        f2 = f2 == 0 ? fm[1] : f2 * 0.6f + fm[1] * 0.4f;
        trailF1[trailHead] = f1;
        trailF2[trailHead] = f2;
        trailHead = (trailHead + 1) % TRAIL;
        if (trailCount < TRAIL) trailCount++;
        int v = nearestVowel(f1, f2);
        vowel = v < 0 ? "?" : VOWELS[v];
    }

    String currentVowel() { return vowel; }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    // ---------------------------------------------------------------- drawing

    private float left, right, top, bottom;

    private float xOfF2(float f2v) {
        // F2 runs right to left (front vowels on the left), log scale 600..3000 Hz.
        return left + (float) (Math.log(3000 / f2v) / Math.log(3000 / 600.0)) * (right - left);
    }

    private float yOfF1(float f1v) {
        return top + (float) (Math.log(f1v / 220) / Math.log(1100 / 220.0)) * (bottom - top);
    }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        left = 30 * dp;
        right = w - 10 * dp;
        top = 52 * dp;
        bottom = h * 0.70f;
        k.label(c, "Карта голосних", 8 * dp, 18 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Протягуйте голосні: «і-и-е-а-о-у» · " + VOICES[voice].toLowerCase(Locale.ROOT), 8 * dp, 34 * dp, 11,
                LessonKit.SOFT, Paint.Align.LEFT, false);
        k.rect(c, left, top, right, bottom, 0xFF0B1220);
        k.label(c, "язик спереду ←  F2  → язик ззаду", (left + right) / 2, top - 4 * dp, 9.5f, LessonKit.LABEL, Paint.Align.CENTER, false);
        c.save();
        c.rotate(-90, 12 * dp, (top + bottom) / 2);
        k.label(c, "рот відкритий ↓ F1", 12 * dp, (top + bottom) / 2 + 3 * dp, 9.5f, LessonKit.LABEL, Paint.Align.CENTER, false);
        c.restore();

        float s = VOICE_SCALE[voice];
        int[] colors = {LessonKit.CYAN, 0xFF64FFDA, LessonKit.GREEN, LessonKit.YELLOW, LessonKit.ORANGE, LessonKit.PINK};
        for (int v = 0; v < TARGETS.length; v++) {
            float x = xOfF2(TARGETS[v][1] * s), y = yOfF1(TARGETS[v][0] * s);
            boolean active = VOWELS[v].equals(vowel);
            k.fill.setColor((colors[v] & 0x00FFFFFF) | (active ? 0x88000000 : 0x33000000));
            c.drawOval(new RectF(x - 34 * dp, y - 20 * dp, x + 34 * dp, y + 20 * dp), k.fill);
            k.label(c, VOWELS[v], x, y + 8 * dp, active ? 26 : 20, colors[v], Paint.Align.CENTER, true);
        }
        // Trail and current point.
        for (int i = 0; i < trailCount; i++) {
            int idx = (trailHead - trailCount + i + TRAIL) % TRAIL;
            float a = (float) (i + 1) / trailCount;
            k.fill.setColor(((int) (a * 200) << 24) | 0xFFFFFF);
            c.drawCircle(xOfF2(trailF2[idx]), yOfF1(trailF1[idx]), (2 + 3 * a) * dp, k.fill);
        }
        if (!vowel.isEmpty()) {
            k.label(c, vowel.equals("?") ? "між голосними" : "«" + vowel + "»", right - 6 * dp, bottom - 10 * dp, 22,
                    LessonKit.WHITE, Paint.Align.RIGHT, true);
        }

        // LPC envelope with the two formants marked.
        float eTop = bottom + 22 * dp, eBottom = h - 30 * dp;
        k.label(c, "Огинаюча спектра голосу (резонанси рота)", 8 * dp, eTop - 6 * dp, 10, LessonKit.LABEL, Paint.Align.LEFT, false);
        k.rect(c, 8 * dp, eTop, w - 8 * dp, eBottom, 0xFF0B1220);
        float[] env = envelopeDb;
        if (env.length > 0) {
            float max = -1e9f, min = 1e9f;
            int lim = (int) Math.min(env.length - 1, 4000f / (envRate / 2f) * (env.length - 1));
            for (int i = 0; i <= lim; i++) { max = Math.max(max, env[i]); min = Math.min(min, env[i]); }
            k.path.reset();
            for (int i = 0; i <= lim; i++) {
                float x = 8 * dp + (float) i / lim * (w - 16 * dp);
                float y = eBottom - (env[i] - min) / Math.max(1, max - min) * (eBottom - eTop) * 0.9f;
                if (i == 0) k.path.moveTo(x, y); else k.path.lineTo(x, y);
            }
            k.stroke.setColor(LessonKit.YELLOW);
            k.stroke.setStrokeWidth(2 * dp);
            c.drawPath(k.path, k.stroke);
            if (!vowel.isEmpty()) {
                for (float fm : new float[]{f1, f2}) {
                    float x = 8 * dp + fm / 4000f * (w - 16 * dp);
                    k.line(c, x, eTop, x, eBottom, LessonKit.PINK, 1.5f);
                }
                k.label(c, String.format(Locale.US, "F1 %.0f Гц · F2 %.0f Гц", f1, f2), w - 10 * dp, eTop + 14 * dp, 11,
                        LessonKit.PINK, Paint.Align.RIGHT, true);
            }
        }
        for (int hz = 0; hz <= 4000; hz += 1000) {
            k.label(c, hz == 0 ? "0" : hz / 1000 + "k", 8 * dp + hz / 4000f * (w - 16 * dp), h - 18 * dp, 9, LessonKit.LABEL,
                    Paint.Align.CENTER, false);
        }
        k.label(c, "Висота голосу не важлива — голосну визначають форманти F1 і F2", 8 * dp, h - 4 * dp, 9.5f,
                LessonKit.LABEL, Paint.Align.LEFT, false);
    }
}
