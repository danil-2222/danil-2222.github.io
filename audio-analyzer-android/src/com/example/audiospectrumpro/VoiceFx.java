package com.example.audiospectrumpro;

/** Offline voice effects for the learning tab, each chosen to show one idea about sound. */
final class VoiceFx {
    static final String[] NAMES = {"Оригінал", "Гелій", "Велетень", "Робот", "Телефон", "Луна", "Задом наперед"};
    static final String[] EXPLAIN = {
            "Ваш запис без змін — порівнюйте з ним інші ефекти.",
            "Звук прискорено в 1,5 раза: усі частоти (і основний тон, і форманти) зросли в 1,5 раза — на спектрограмі все піднялося вгору.",
            "Звук сповільнено: частоти впали в 0,7 раза, голос став низьким і «великим».",
            "Голос помножено на тон 50 Гц (кільцева модуляція): кожна частота розщепилася на дві — f ± 50 Гц. Звідси металевий призвук.",
            "Залишено лише 300–3400 Гц — так звучав звичайний телефон. На спектрограмі зникли низ і верх, а мову все одно чути!",
            "До звуку додано його копію через 0,25 с, тихішу: так звучить луна від далекої стіни.",
            "Звук прокручено задом наперед: спектр кожного моменту той самий, але порядок змінився — і мову вже не впізнати."};

    /** Keeps the part of the clip that contains sound (plus a little margin); null if it is silent. */
    static short[] trim(short[] pcm, int rate) {
        int block = rate / 50;
        int first = -1, last = -1;
        for (int b = 0; b + block <= pcm.length; b += block) {
            double e = 0;
            for (int i = b; i < b + block; i++) e += (double) pcm[i] * pcm[i];
            double db = 10 * Math.log10(e / block / (32768.0 * 32768.0) + 1e-14);
            if (db > -45) {
                if (first < 0) first = b;
                last = b + block;
            }
        }
        if (first < 0) return null;
        int margin = rate / 10;
        first = Math.max(0, first - margin);
        last = Math.min(pcm.length, last + margin);
        short[] out = new short[last - first];
        System.arraycopy(pcm, first, out, 0, out.length);
        return out;
    }

    static short[] apply(int effect, short[] src, int rate) {
        float[] x = new float[src.length];
        for (int i = 0; i < x.length; i++) x[i] = src[i] / 32768f;
        float[] y;
        switch (effect) {
            case 1: y = resample(x, 1.5); break;
            case 2: y = resample(x, 0.7); break;
            case 3: {
                y = new float[x.length];
                for (int i = 0; i < x.length; i++) y[i] = (float) (x[i] * Math.sin(2 * Math.PI * 50 * i / rate)) * 1.6f;
                break;
            }
            case 4: {
                y = x.clone();
                BiquadFilter hp = biquad(true, 300, rate), hp2 = biquad(true, 300, rate);
                BiquadFilter lp = biquad(false, 3400, rate), lp2 = biquad(false, 3400, rate);
                hp.process(y, y.length);
                hp2.process(y, y.length);
                lp.process(y, y.length);
                lp2.process(y, y.length);
                break;
            }
            case 5: {
                int d = rate / 4;
                y = new float[x.length + rate];
                for (int i = 0; i < y.length; i++) {
                    float v = i < x.length ? x[i] : 0;
                    if (i >= d) v += 0.5f * y[i - d];
                    y[i] = v;
                }
                break;
            }
            case 6: {
                y = new float[x.length];
                for (int i = 0; i < x.length; i++) y[i] = x[x.length - 1 - i];
                break;
            }
            default: y = x;
        }
        float peak = 1e-6f;
        for (float v : y) peak = Math.max(peak, Math.abs(v));
        float g = Math.min(4f, 0.9f / peak);
        short[] out = new short[y.length];
        for (int i = 0; i < y.length; i++) out[i] = (short) Math.round(Math.max(-1f, Math.min(1f, y[i] * g)) * 32767f);
        return out;
    }

    /** Plays the clip faster (factor > 1) or slower: pitch and duration change together. */
    private static float[] resample(float[] x, double factor) {
        int n = (int) (x.length / factor);
        float[] y = new float[n];
        for (int i = 0; i < n; i++) {
            double p = i * factor;
            int a = (int) p;
            double fr = p - a;
            float v0 = x[Math.min(a, x.length - 1)], v1 = x[Math.min(a + 1, x.length - 1)];
            y[i] = (float) (v0 + (v1 - v0) * fr);
        }
        return y;
    }

    /** Second-order Butterworth high- or low-pass (RBJ cookbook) via a custom BiquadFilter. */
    private static BiquadFilter biquad(boolean high, double fc, int rate) {
        BiquadFilter f = new BiquadFilter();
        f.setCustom(high, fc, 0.7071, rate);
        return f;
    }

    private VoiceFx() {}
}
