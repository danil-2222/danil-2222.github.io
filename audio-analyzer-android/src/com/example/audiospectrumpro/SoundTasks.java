package com.example.audiospectrumpro;

/**
 * Recognises a few kinds of sound in one analysis frame — whistle, high voice, low hum «у», hiss «ш»
 * and a blow — and checks that the asked one lasts long enough (short clicks never count).
 */
final class SoundTasks {
    static final int WHISTLE = 0, HIGH_VOICE = 1, HUM = 2, HISS = 3, BLOW = 4;
    static final String[] TASKS = {"Свисни 🎵", "Заспівай тоненько, як мишка 🐭", "Загуди «у-у-у», як ведмідь 🐻",
            "Зашипи «ш-ш-ш», як змія 🐍", "Подми, як вітер 💨"};
    static final float NEED_S = 0.45f;

    /** Which kinds of sound this frame is (several can be true). */
    static boolean[] classify(AudioEngine.Frame f) {
        boolean[] r = new boolean[5];
        float level = f.rmsDb - f.calibrationDb;
        if (level < -48 || !steady(f.time)) return r;
        double low = LessonKit.bandPower(f, 70, 900), high = LessonKit.bandPower(f, 2500, 12000);
        double all = LessonKit.bandPower(f, 70, 12000) + 1e-20;
        // Whistle: almost all energy in one narrow peak between 600 Hz and 4 kHz.
        int i0 = (int) (600 / f.binHz), i1 = Math.min(f.spectrumDb.length - 3, (int) (4000 / f.binHz)), best = i0;
        for (int i = i0; i <= i1; i++) if (f.spectrumDb[i] > f.spectrumDb[best]) best = i;
        double peak = 0;
        for (int i = best - 2; i <= best + 2; i++) peak += Math.pow(10, (f.spectrumDb[i] - f.calibrationDb) / 10.0);
        peak /= Math.max(1f, f.enbw);
        float pitch = LessonRenderer.yin(f.time, f.sampleRate);
        r[WHISTLE] = peak / all > 0.6;
        r[HIGH_VOICE] = !r[WHISTLE] && pitch >= 330 && pitch <= 1200;
        double lowDb = 10 * Math.log10(low / all), highDb = 10 * Math.log10(high / all);
        r[HUM] = pitch > 0 && pitch < 330 && high / (low + 1e-20) < 0.1;
        r[HISS] = pitch <= 0 && highDb > -3 && lowDb < -10;
        r[BLOW] = pitch <= 0 && low / all > 0.3;
        return r;
    }

    /**
     * True if the newest samples sound continuous: four short pieces all audible and within 10 dB of each
     * other. Clicks, ticks and claps are not.
     */
    static boolean steady(float[] t) {
        int n = AudioEngine.HOP / 4, base = t.length - AudioEngine.HOP;
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int p = 0; p < 4; p++) {
            float db = LessonKit.blockDb(t, base + p * n, n);
            lo = Math.min(lo, db);
            hi = Math.max(hi, db);
        }
        return lo > -52 && hi - lo < 10;
    }

    /** Tracks how long the asked sound has lasted (short gaps are forgiven). */
    static final class Hold {
        private float held, gap;

        void reset() { held = 0; gap = 0; }

        /** @return true once the sound has lasted NEED_S. */
        boolean feed(boolean ok, float dt) {
            if (ok) { held += dt; gap = 0; } else { gap += dt; if (gap > 0.12f) held = 0; }
            return held >= NEED_S;
        }

        float progress() { return Math.min(1, held / NEED_S); }
    }
}
