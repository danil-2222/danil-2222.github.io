package com.example.audiospectrumpro;

/** RBJ-cookbook biquad used as an optional pre-filter before analysis. */
public class BiquadFilter {
    public enum Type { ALLPASS, NOTCH50, HIGHPASS100, LOWPASS5000, BANDPASS1K, HIGHPASS500 }

    private float b0 = 1, b1, b2, a1, a2;
    private float x1, x2, y1, y2;
    private Type type = Type.ALLPASS;

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
