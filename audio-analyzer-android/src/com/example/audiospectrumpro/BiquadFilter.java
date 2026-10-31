package com.example.audiospectrumpro;

/** RBJ-cookbook biquad used as an optional pre-filter before analysis. */
public class BiquadFilter {
    public enum Type { ALLPASS, NOTCH50, HIGHPASS100, LOWPASS5000, BANDPASS1K, HIGHPASS500, CUSTOM }

    private float b0 = 1, b1, b2, a1, a2;
    private float x1, x2, y1, y2;
    private Type type = Type.ALLPASS;

    public synchronized Type getType() { return type; }

    public synchronized void setFilter(Type type, float sampleRate) {
        this.type = type;
        x1 = x2 = y1 = y2 = 0;
        double w0, alpha, c, a0;
        double nb0, nb1, nb2, na1, na2;
        switch (type) {
            case NOTCH50:
                w0 = 2 * Math.PI * 50 / sampleRate; alpha = Math.sin(w0) / (2 * 10); c = Math.cos(w0);
                nb0 = 1; nb1 = -2 * c; nb2 = 1; a0 = 1 + alpha; na1 = -2 * c; na2 = 1 - alpha;
                break;
            case HIGHPASS100:
            case HIGHPASS500:
                double fc = type == Type.HIGHPASS100 ? 100 : 500;
                w0 = 2 * Math.PI * fc / sampleRate; alpha = Math.sin(w0) / (2 * 0.707); c = Math.cos(w0);
                nb0 = (1 + c) / 2; nb1 = -(1 + c); nb2 = (1 + c) / 2; a0 = 1 + alpha; na1 = -2 * c; na2 = 1 - alpha;
                break;
            case LOWPASS5000:
                w0 = 2 * Math.PI * 5000 / sampleRate; alpha = Math.sin(w0) / (2 * 0.707); c = Math.cos(w0);
                nb0 = (1 - c) / 2; nb1 = 1 - c; nb2 = (1 - c) / 2; a0 = 1 + alpha; na1 = -2 * c; na2 = 1 - alpha;
                break;
            case BANDPASS1K:
                w0 = 2 * Math.PI * 1000 / sampleRate; alpha = Math.sin(w0) / (2 * 1.0); c = Math.cos(w0);
                nb0 = alpha; nb1 = 0; nb2 = -alpha; a0 = 1 + alpha; na1 = -2 * c; na2 = 1 - alpha;
                break;
            case ALLPASS:
            default:
                b0 = 1; b1 = b2 = a1 = a2 = 0;
                return;
        }
        b0 = (float) (nb0 / a0); b1 = (float) (nb1 / a0); b2 = (float) (nb2 / a0);
        a1 = (float) (na1 / a0); a2 = (float) (na2 / a0);
    }

    /** Arbitrary Butterworth-style high- or low-pass (used by the voice effects). */
    public synchronized void setCustom(boolean highPass, double fc, double q, float sampleRate) {
        type = Type.CUSTOM;
        x1 = x2 = y1 = y2 = 0;
        double w0 = 2 * Math.PI * fc / sampleRate, alpha = Math.sin(w0) / (2 * q), c = Math.cos(w0);
        double a0 = 1 + alpha;
        double nb0 = highPass ? (1 + c) / 2 : (1 - c) / 2;
        double nb1 = highPass ? -(1 + c) : 1 - c;
        b0 = (float) (nb0 / a0);
        b1 = (float) (nb1 / a0);
        b2 = (float) (nb0 / a0);
        a1 = (float) (-2 * c / a0);
        a2 = (float) ((1 - alpha) / a0);
    }

    /** RBJ peaking EQ: boosts or cuts gainDb around fc. */
    public synchronized void setPeaking(double fc, double q, double gainDb, float sampleRate) {
        type = Type.CUSTOM;
        double a = Math.pow(10, gainDb / 40), w0 = 2 * Math.PI * fc / sampleRate;
        double alpha = Math.sin(w0) / (2 * q), c = Math.cos(w0), a0 = 1 + alpha / a;
        b0 = (float) ((1 + alpha * a) / a0);
        b1 = (float) (-2 * c / a0);
        b2 = (float) ((1 - alpha * a) / a0);
        a1 = (float) (-2 * c / a0);
        a2 = (float) ((1 - alpha / a) / a0);
    }

    /** Magnitude response in dB at frequency f. */
    public synchronized double magnitudeDb(double f, float sampleRate) {
        if (type == Type.ALLPASS) return 0;
        double w = 2 * Math.PI * f / sampleRate;
        double c1 = Math.cos(w), s1 = Math.sin(w), c2 = Math.cos(2 * w), s2 = Math.sin(2 * w);
        double nr = b0 + b1 * c1 + b2 * c2, ni = -(b1 * s1 + b2 * s2);
        double dr = 1 + a1 * c1 + a2 * c2, di = -(a1 * s1 + a2 * s2);
        return 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di) + 1e-20);
    }

    public synchronized void process(float[] buf, int len) {
        if (type == Type.ALLPASS) return;
        for (int i = 0; i < len; i++) {
            float x = buf[i];
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            buf[i] = y;
        }
    }
}
