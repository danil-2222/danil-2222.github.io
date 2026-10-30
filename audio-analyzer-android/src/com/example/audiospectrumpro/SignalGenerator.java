package com.example.audiospectrumpro;

import java.util.Random;

/**
 * Test-signal source. Settings are shared static state so the analyser's internal source and
 * the loudspeaker output always produce the same signal; each instance keeps its own phase.
 */
final class SignalGenerator {
    enum Waveform { SINE, SQUARE, SAWTOOTH, TRIANGLE, WHITE_NOISE, PINK_NOISE, LOG_SWEEP, DUAL_TONE, HARMONICS, INTERVAL }

    static final float SWEEP_SECONDS = 8f;

    static volatile Waveform waveform = Waveform.SINE;
    static volatile float frequency = 1000f;
    static volatile float level = 0.5f;
    /** Amplitudes of harmonics 1..12 for the HARMONICS waveform (the learning constructor). */
    static final float[] harmonics = {1f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    /** INTERVAL waveform: second tone at frequency * intervalRatio + intervalDetuneHz. */
    static volatile float intervalRatio = 1.5f;
    static volatile float intervalDetuneHz = 0f;

    private final Random random = new Random();
    private double phase, phase2, sweepT;
    private float b0, b1, b2, b3, b4, b5, b6;

    void fill(float[] out, int len, int sampleRate) {
        Waveform wf = waveform;
        float vol = level;
        double f = frequency;
        for (int i = 0; i < len; i++) {
            double fi = f;
            if (wf == Waveform.LOG_SWEEP) {
                fi = 20 * Math.pow(1000, sweepT / SWEEP_SECONDS); // 20 Hz -> 20 kHz
                sweepT += 1.0 / sampleRate;
                if (sweepT >= SWEEP_SECONDS) sweepT = 0;
            }
            double p = phase / (2 * Math.PI);
            float s;
            switch (wf) {
                case SQUARE: {
                    double dt = fi / sampleRate;
                    double v = p < 0.5 ? 1 : -1;
                    v += polyBlep(p, dt);
                    v -= polyBlep((p + 0.5) % 1.0, dt);
                    s = (float) v;
                    break;
                }
                case SAWTOOTH: s = (float) (2 * p - 1 - polyBlep(p, fi / sampleRate)); break;
                case TRIANGLE: s = (float) (p < 0.5 ? 4 * p - 1 : 3 - 4 * p); break;
                case WHITE_NOISE: s = random.nextFloat() * 2 - 1; break;
                case PINK_NOISE: {
                    // Paul Kellet's refined pink-noise filter.
                    float w = random.nextFloat() * 2 - 1;
                    b0 = 0.99886f * b0 + w * 0.0555179f;
                    b1 = 0.99332f * b1 + w * 0.0750759f;
                    b2 = 0.96900f * b2 + w * 0.1538520f;
                    b3 = 0.86650f * b3 + w * 0.3104856f;
                    b4 = 0.55000f * b4 + w * 0.5329522f;
                    b5 = -0.7616f * b5 - w * 0.0168980f;
                    s = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f) * 0.11f;
                    b6 = w * 0.115926f;
                    break;
                }
                case DUAL_TONE: {
                    // Intermodulation test: f plus 7 kHz at a 4:1 amplitude ratio (SMPTE-style).
                    s = (float) (0.8 * Math.sin(phase) + 0.2 * Math.sin(phase2));
                    phase2 += 2 * Math.PI * 7000 / sampleRate;
                    if (phase2 >= 2 * Math.PI) phase2 -= 2 * Math.PI;
                    break;
                }
                case HARMONICS: {
                    double sum = 0, norm = 0;
                    for (int k = 1; k <= harmonics.length; k++) {
                        float a = harmonics[k - 1];
                        if (a <= 0 || k * fi >= sampleRate / 2.0) continue;
                        sum += a * Math.sin(k * phase);
                        norm += a;
                    }
                    s = norm > 0 ? (float) (sum / norm) : 0f;
                    break;
                }
                case INTERVAL: {
                    s = (float) (0.5 * Math.sin(phase) + 0.5 * Math.sin(phase2));
                    phase2 += 2 * Math.PI * (fi * intervalRatio + intervalDetuneHz) / sampleRate;
                    if (phase2 >= 2 * Math.PI) phase2 -= 2 * Math.PI;
                    break;
                }
                case SINE:
                case LOG_SWEEP:
                default: s = (float) Math.sin(phase); break;
            }
            out[i] = s * vol;
            phase += 2 * Math.PI * fi / sampleRate;
            if (phase >= 2 * Math.PI) phase -= 2 * Math.PI;
        }
    }

    /** Band-limited step correction (PolyBLEP) that suppresses aliasing at discontinuities. */
    private static double polyBlep(double t, double dt) {
        if (t < dt) {
            t /= dt;
            return t + t - t * t - 1;
        }
        if (t > 1 - dt) {
            t = (t - 1) / dt;
            return t * t + t + t + 1;
        }
        return 0;
    }
}
