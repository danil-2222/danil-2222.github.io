package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.Locale;

/** Draggable 10-band equaliser over the live spectrum of the (equalised) generator output. */
final class EqualizerLesson implements LessonView {
    static final float MAX_DB = 12;
    static final String[] PRESETS = {"Рівно", "Більше басу", "Телефон", "Яскраво", "Без середини"};
    static final float[][] PRESET_GAINS = {
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {9, 8, 6, 3, 0, 0, 0, 0, 0, 0},
            {-12, -12, -12, -4, 2, 4, 3, -4, -12, -12},
            {0, 0, 0, 0, 0, 1, 3, 6, 8, 9},
            {2, 3, 2, -2, -8, -10, -8, -2, 2, 3},
    };

    private final LessonKit k;
    private final BiquadFilter[] resp = new BiquadFilter[SignalGenerator.EQ_HZ.length];
    private AudioEngine.Frame frame;
    private int dragging = -1;

    EqualizerLesson(float dp) {
        k = new LessonKit(dp);
        for (int i = 0; i < resp.length; i++) resp[i] = new BiquadFilter();
    }

    static void setGains(float[] g) {
        System.arraycopy(g, 0, SignalGenerator.eqGains, 0, g.length);
        SignalGenerator.eqVersion++;
    }

    private float left, right, top, bottom;

    private float xOf(float hz) {
        return left + (float) (Math.log(hz / 20) / Math.log(20000 / 20.0)) * (right - left);
    }

    private float yOfGain(float db) { return (top + bottom) / 2 - db / MAX_DB * (bottom - top) / 2 * 0.9f; }

    private float yOfLevel(float db) { return top + (0 - db) / 100f * (bottom - top); }

    @Override public void onFrame(AudioEngine.Frame f) { frame = f; }

    @Override public boolean animating() { return false; }

    @Override
    public boolean onTouch(MotionEvent e, int w, int h) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            dragging = -1;
            float best = 36 * k.dp;
            for (int b = 0; b < SignalGenerator.EQ_HZ.length; b++) {
                float d = Math.abs(e.getX() - xOf(SignalGenerator.EQ_HZ[b]));
                if (d < best && e.getY() > top - 20 * k.dp && e.getY() < bottom + 20 * k.dp) { best = d; dragging = b; }
            }
        }
        if (dragging < 0) return false;
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            float mid = (top + bottom) / 2;
            float g = -(e.getY() - mid) / ((bottom - top) / 2 * 0.9f) * MAX_DB;
            SignalGenerator.eqGains[dragging] = Math.round(Math.max(-MAX_DB, Math.min(MAX_DB, g)));
            SignalGenerator.eqVersion++;
        } else {
            dragging = -1;
        }
        return true;
    }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        left = 10 * dp;
        right = w - 10 * dp;
        top = 54 * dp;
        bottom = h - 34 * dp;
        k.label(c, "Еквалайзер своїми руками", 8 * dp, 18 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Тягніть кружечки вгору (гучніше) і вниз (тихіше)", 8 * dp, 34 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);
        k.rect(c, left, top, right, bottom, 0xFF0B1220);

        // Live spectrum of what is playing (after the equaliser).
        AudioEngine.Frame f = frame;
        if (f != null) {
            float[] cols = new float[(int) (right - left)];
            SpectrumMath.columns(f.spectrumDb, f.binHz, true, 20, 20000, cols);
            k.path.reset();
            k.path.moveTo(left, bottom);
            for (int x = 0; x < cols.length; x++) k.path.lineTo(left + x, Math.min(bottom, yOfLevel(cols[x] - f.calibrationDb + 10)));
            k.path.lineTo(right, bottom);
            k.path.close();
            k.fill.setColor(0x5564B5F6);
            c.drawPath(k.path, k.fill);
        }
        // Grid and 0 dB line.
        for (float db = -12; db <= 12; db += 6) {
            float y = yOfGain(db);
            k.line(c, left, y, right, y, db == 0 ? 0xFF475569 : LessonKit.GRID, 1);
            k.label(c, (db > 0 ? "+" : "") + Math.round(db), right - 2 * dp, y - 2 * dp, 9, LessonKit.LABEL, Paint.Align.RIGHT, false);
        }
        // Response curve of the ten filters.
        for (int b = 0; b < resp.length; b++) resp[b].setPeaking(SignalGenerator.EQ_HZ[b], 1.4, SignalGenerator.eqGains[b], 48000);
        k.path.reset();
        for (int x = 0; x <= (int) (right - left); x += 2) {
            double hz = 20 * Math.pow(1000, x / (right - left));
            double g = 0;
            for (BiquadFilter r : resp) g += r.magnitudeDb(hz, 48000);
            float y = yOfGain((float) Math.max(-MAX_DB * 1.3, Math.min(MAX_DB * 1.3, g)));
            if (x == 0) k.path.moveTo(left + x, y); else k.path.lineTo(left + x, y);
        }
        k.stroke.setColor(LessonKit.YELLOW);
        k.stroke.setStrokeWidth(2.5f * dp);
        c.drawPath(k.path, k.stroke);
        String[] names = {"31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"};
        for (int b = 0; b < SignalGenerator.EQ_HZ.length; b++) {
            float x = xOf(SignalGenerator.EQ_HZ[b]), y = yOfGain(SignalGenerator.eqGains[b]);
            k.fill.setColor(b == dragging ? LessonKit.WHITE : LessonKit.CYAN);
            c.drawCircle(x, y, 8 * dp, k.fill);
            k.label(c, names[b], x, bottom + 13 * dp, 9.5f, LessonKit.LABEL, Paint.Align.CENTER, false);
        }
        k.label(c, "бас", xOf(60), bottom + 27 * dp, 9.5f, LessonKit.SOFT, Paint.Align.CENTER, true);
        k.label(c, "середина (голос)", xOf(1000), bottom + 27 * dp, 9.5f, LessonKit.SOFT, Paint.Align.CENTER, true);
        k.label(c, "високі", xOf(10000), bottom + 27 * dp, 9.5f, LessonKit.SOFT, Paint.Align.CENTER, true);
    }

    /** A short loop that has bass, chords and hi-hats, so every band has something to change. */
    static float[] music(int rate) {
        int beat = rate / 2;                         // 120 bpm
        float[] out = new float[beat * 16];
        float[][] chords = {{48, 60, 64, 67}, {45, 57, 60, 64}, {41, 57, 60, 65}, {43, 55, 59, 62}}; // C Am F G
        java.util.Random rnd = new java.util.Random(4);
        for (int bar = 0; bar < 4; bar++) {
            int start = bar * beat * 4;
            float[] ch = chords[bar];
            LessonKit.addNote(out, start, beat * 4, LessonKit.midiToHz(ch[0]), 0.9f, rate);
            for (int b = 0; b < 4; b++) {
                for (int n = 1; n < ch.length; n++) LessonKit.addNote(out, start + b * beat, beat, LessonKit.midiToHz(ch[n]), 0.28f, rate);
            }
            for (int e = 0; e < 8; e++) {           // hi-hat: short high noise on every eighth
                int at = start + e * beat / 2;
                float prev = 0;
                for (int i = 0; i < rate / 25 && at + i < out.length; i++) {
                    float nz = rnd.nextFloat() * 2 - 1;
                    out[at + i] += (nz - prev) * 0.25f * (float) Math.exp(-i / (rate * 0.012));
                    prev = nz;
                }
            }
        }
        LessonKit.normalize(out, 0.5f);
        return out;
    }
}
