package com.example.audiospectrumpro;

import java.util.Arrays;
import java.util.Locale;

/**
 * Describes a spectrum in plain words for the learning mode: estimates the fundamental,
 * finds its harmonics and classifies the sound (silence, pure tone, harmonic tone, noise...).
 */
final class SoundExplainer {
    static final int MAX_HARMONICS = 16;

    enum Kind { SILENCE, NOISE, PURE_TONE, HARMONIC, COMPLEX }

    static final class Result {
        Kind kind = Kind.SILENCE;
        /** Fundamental frequency in Hz, 0 if none. */
        float f0;
        /** Level (dB, calibrated like the frame) of harmonic k at index k-1; NaN when absent. */
        final float[] harmonicDb = new float[MAX_HARMONICS];
        final float[] harmonicHz = new float[MAX_HARMONICS];
        int harmonicCount;
        boolean oddOnly;
        float centroidHz;
        float flatness;
        float noiseFloorDb;
        String title = "";
        String[] lines = new String[0];
    }

    static Result explain(AudioEngine.Frame f, float a4) {
        Result r = new Result();
        Arrays.fill(r.harmonicDb, Float.NaN);
        float[] db = f.spectrumDb;
        float cal = f.calibrationDb;
        int bins = db.length;
        float binHz = f.binHz;
        int lo = Math.max(1, (int) Math.ceil(40 / binHz));
        int hi = Math.min(bins - 2, (int) (12000 / binHz));
        if (hi <= lo + 8) return r;

        // Spectral statistics over 40 Hz - 12 kHz.
        double sumP = 0, sumFP = 0, sumLog = 0;
        float[] sorted = Arrays.copyOfRange(db, lo, hi);
        Arrays.sort(sorted);
        r.noiseFloorDb = sorted[sorted.length / 2];
        for (int i = lo; i < hi; i++) {
            double p = Math.pow(10, (db[i] - cal) / 10.0) + 1e-20;
            sumP += p;
            sumFP += p * i * binHz;
            sumLog += Math.log(p);
        }
        int n = hi - lo;
        r.centroidHz = (float) (sumFP / sumP);
        r.flatness = (float) (Math.exp(sumLog / n) / (sumP / n));

        float levelDbfs = f.rmsDb - cal;
        if (levelDbfs < -65) {
            r.kind = Kind.SILENCE;
            r.title = "Тиша";
            r.lines = new String[]{
                    String.format(Locale.US, "Рівень %.0f dBFS — звуку майже немає.", levelDbfs),
                    "Свисніть, заспівайте або увімкніть музику."};
            return r;
        }

        // Fundamental: harmonic sum over candidate pitches 50-1500 Hz (pitch above floor at each harmonic).
        // At least 4 bins up, so the window's main lobe is resolved from DC.
        int cLo = Math.max(Math.max(lo, 4), (int) (50 / binHz)), cHi = Math.min(hi, (int) (1500 / binHz));
        float bestScore = 0;
        int best = -1;
        for (int i = cLo; i <= cHi; i++) {
            if (db[i] < db[i - 1] || db[i] < db[i + 1]) continue; // only local maxima as fundamentals
            float score = 0;
            for (int h = 1; h <= 6; h++) {
                int b = Math.round(i * h);
                if (b >= bins - 1) break;
                int pk = db[b - 1] > db[b] ? b - 1 : db[b + 1] > db[b] ? b + 1 : b;
                float v = prominence(db, pk);
                if (v > 8) score += Math.min(v, 40) / h;
            }
            if (score > bestScore * 1.05f) { bestScore = score; best = i; }
        }

        float f0Prominence = best < 0 ? 0 : prominence(db, best);
        if (best < 0 || f0Prominence < 15) {
            r.kind = Kind.NOISE;
            describeNoise(r);
            return r;
        }

        // Refine f0 with parabolic interpolation.
        float al = db[best - 1], be = db[best], ga = db[best + 1];
        float den = al - 2 * be + ga;
        float d = Math.abs(den) > 1e-6f ? Math.max(-0.5f, Math.min(0.5f, 0.5f * (al - ga) / den)) : 0;
        r.f0 = (best + d) * binHz;

        // Harmonics: local maxima near k*f0 that stand out from the floor and from the strongest one.
        float strongest = -999;
        for (int k = 1; k <= MAX_HARMONICS; k++) {
            float target = k * r.f0;
            if (target > Math.min(16000, f.sampleRate / 2f - 100)) break;
            int c = Math.round(target / binHz);
            int w = Math.max(2, Math.round(target * 0.03f / binHz));
            int bi = -1;
            for (int i = Math.max(1, c - w); i <= Math.min(bins - 2, c + w); i++) {
                if (bi < 0 || db[i] > db[bi]) bi = i;
            }
            if (bi < 0) continue;
            if (prominence(db, bi) > 12) {
                r.harmonicDb[k - 1] = db[bi];
                r.harmonicHz[k - 1] = bi * binHz;
                strongest = Math.max(strongest, db[bi]);
            }
        }
        int odd = 0, even = 0;
        for (int k = 1; k <= MAX_HARMONICS; k++) {
            float v = r.harmonicDb[k - 1];
            if (Float.isNaN(v)) continue;
            if (v < strongest - 60) { r.harmonicDb[k - 1] = Float.NaN; continue; }
            r.harmonicCount++;
            if (k > 1) { if (k % 2 == 0) even++; else odd++; }
        }
        r.oddOnly = odd >= 2 && even == 0;

        // How much of the tonal energy the harmonic series explains.
        double harmP = 0, peaksP = 0;
        for (int k = 0; k < MAX_HARMONICS; k++) {
            if (!Float.isNaN(r.harmonicDb[k])) harmP += Math.pow(10, (r.harmonicDb[k] - cal) / 10.0);
        }
        for (int i = lo; i < hi; i++) {
            if (db[i] - r.noiseFloorDb > 20 && db[i] >= db[i - 1] && db[i] >= db[i + 1]) {
                peaksP += Math.pow(10, (db[i] - cal) / 10.0);
            }
        }
        boolean explained = peaksP <= 0 || harmP / peaksP > 0.6;

        float[] cents = new float[1];
        String note = SpectrumMath.noteName(r.f0, a4, cents);
        if ((r.flatness > 0.35 && r.harmonicCount <= 1) || (r.harmonicCount <= 1 && f0Prominence < 20)) {
            r.kind = Kind.NOISE;
            describeNoise(r);
        } else if (r.harmonicCount <= 1) {
            r.kind = Kind.PURE_TONE;
            r.title = String.format(Locale.US, "Чистий тон %s (%s)", SpectrumMath.formatHz(r.f0), note);
            r.lines = new String[]{
                    "Одна частота без гармонік — майже синусоїда.",
                    "Так звучить свист, камертон або гудок.",
                    "Вище частота → вища нота; в 2 рази вище → октава."};
        } else if (!explained) {
            r.kind = Kind.COMPLEX;
            r.title = "Складний звук: кілька тонів";
            r.lines = new String[]{
                    "Піки не стоять на кратних частотах однієї ноти.",
                    "Це акорд, кілька джерел або «дзвінкий» звук (дзвін, метал).",
                    String.format(Locale.US, "Найгучніша частота: %s.", SpectrumMath.formatHz(f.peakFreqHz))};
        } else {
            r.kind = Kind.HARMONIC;
            r.title = String.format(Locale.US, "Музичний тон %s (%s %+.0f¢)", SpectrumMath.formatHz(r.f0), note, cents[0]);
            String timbre = r.oddOnly
                    ? "Лише непарні гармоніки (1×, 3×, 5×…) — «порожнистий» тембр, як у кларнета чи меандру."
                    : r.harmonicCount >= 8
                    ? "Багато гармонік — яскравий, «дзвінкий» тембр (пилка, скрипка, голос)."
                    : "Кілька гармонік — м'який тембр (флейта, спокійний голос).";
            r.lines = new String[]{
                    String.format(Locale.US, "%d гармонік на рівних відстанях: f0, 2×f0, 3×f0… Висоту чуємо за f0.", r.harmonicCount),
                    timbre,
                    String.format(Locale.US, "«Яскравість» (центр спектра): %s.", SpectrumMath.formatHz(r.centroidHz))};
        }
        return r;
    }

    /** Height of bin i above the mean of its +-1/6 octave neighbourhood (main lobe excluded). */
    static float prominence(float[] db, int i) {
        int a = Math.max(1, (int) (i / 1.12f)), b = Math.min(db.length - 1, (int) Math.ceil(i * 1.12f));
        if (b - a < 16) {
            a = Math.max(1, i - 12);
            b = Math.min(db.length - 1, i + 12);
        }
        double sum = 0;
        int n = 0;
        for (int j = a; j <= b; j++) {
            if (Math.abs(j - i) <= 3) continue;
            sum += db[j];
            n++;
        }
        return n == 0 ? 0 : db[i] - (float) (sum / n);
    }

    private static void describeNoise(Result r) {
        boolean bright = r.centroidHz > 3000;
        r.title = bright ? "Шум (яскравий, «ш-ш-ш»)" : "Шум (глухий, «гу-у»)";
        r.lines = new String[]{
                "Енергія розмазана по багатьох частотах — немає однієї висоти.",
                bright ? "Багато високих частот: як «ш», шипіння, білий шум."
                       : "Переважають низькі частоти: як вітер, водоспад, рожевий шум.",
                String.format(Locale.US, "Центр спектра: %s, «шумність» %.0f%%.", SpectrumMath.formatHz(r.centroidHz), r.flatness * 100)};
    }

    private SoundExplainer() {}
}
