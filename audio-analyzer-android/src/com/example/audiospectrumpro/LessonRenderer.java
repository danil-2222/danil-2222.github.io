package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * The "laboratory" visualisations of the learning tab: pitch trail, note circle, intervals,
 * sound in air, room echo and sampling. Owned and driven by {@link VisualizerView}.
 */
final class LessonRenderer {
    // ---------------------------------------------------------------- shared settings (set by the activity)

    /** Sampling lesson: signal frequency and the demonstration sample rate. */
    static volatile float sampleSignalHz = 1000f;
    static volatile float sampleRateHz = 8000f;

    /** Intervals lesson: name of the current interval and its just-intonation ratio p/q. */
    static volatile String intervalName = "Квінта";
    static volatile int intervalP = 3, intervalQ = 2;

    static final String[] UA_NOTES = {"до", "до♯", "ре", "ре♯", "мі", "фа", "фа♯", "соль", "соль♯", "ля", "ля♯", "сі"};
    static final String[] EN_NOTES = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};

    private static final int CYAN = 0xFF00F0FF, GREEN = 0xFF39FF14, YELLOW = 0xFFFFE600, PINK = 0xFFFF007F;
    private static final int GRID = 0xFF1E293B, LABEL = 0xFF7F93A8, SOFT = 0xFFCBD5E1;

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final long start = System.currentTimeMillis();

    LessonRenderer(float dp) {
        this.dp = dp;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    private float now() { return (System.currentTimeMillis() - start) / 1000f; }

    private void label(Canvas c, String s, float x, float y, float sizeDp, int color, Paint.Align align, boolean bold) {
        text.setTextSize(sizeDp * dp);
        text.setColor(color);
        text.setTextAlign(align);
        text.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(s, x, y, text);
    }

    static String noteUa(int midi) {
        int pc = ((midi % 12) + 12) % 12;
        int oct = (midi < 0 ? (midi - 11) / 12 : midi / 12) - 1;
        return EN_NOTES[pc] + oct + " " + UA_NOTES[pc];
    }

    // ================================================================= 1. pitch trail

    private static final int PITCH_POINTS = 900;
    private final float[] pitchMidi = new float[PITCH_POINTS];
    private final long[] pitchTime = new long[PITCH_POINTS];
    private int pitchHead, pitchCount;
    private float pitchCenter = 60;
    private float lastPitchHz;

    /** YIN pitch detector on the newest part of the buffer (decimated by 2). Returns Hz or 0. */
    static float yin(float[] x, int rate) {
        int dec = rate > 32000 ? 2 : 1;
        int r = rate / dec;
        int n = x.length / dec;
        float[] y = new float[n];
        int off = x.length - n * dec;
        double energy = 0;
        for (int i = 0; i < n; i++) {
            float v = 0;
            for (int k = 0; k < dec; k++) v += x[off + i * dec + k];
            y[i] = v / dec;
            energy += y[i] * y[i];
        }
        if (Math.sqrt(energy / n) < 0.002) return 0; // about -54 dBFS
        int tauMin = Math.max(2, r / 1200), tauMax = Math.min(r / 60, n / 2);
        int w = Math.min(n - tauMax, r / 40);
        if (w < 64 || tauMax <= tauMin + 2) return 0;
        int base = n - w - tauMax;
        float[] d = new float[tauMax + 1];
        for (int tau = 1; tau <= tauMax; tau++) {
            double s = 0;
            for (int j = 0; j < w; j++) {
                float diff = y[base + j] - y[base + j + tau];
                s += diff * diff;
            }
            d[tau] = (float) s;
        }
        // Cumulative mean normalised difference; first dip under the threshold wins.
        float run = 0;
        d[0] = 1;
        for (int tau = 1; tau <= tauMax; tau++) {
            run += d[tau];
            d[tau] = run > 0 ? d[tau] * tau / run : 1;
        }
        int best = -1;
        for (int tau = tauMin; tau < tauMax; tau++) {
            if (d[tau] < 0.15f) {
                while (tau + 1 < tauMax && d[tau + 1] < d[tau]) tau++;
                best = tau;
                break;
            }
        }
        if (best < 0) return 0;
        float a = d[best - 1], b = d[best], cc = d[best + 1];
        float den = a - 2 * b + cc;
        float t = Math.abs(den) > 1e-9f ? best + 0.5f * (a - cc) / den : best;
        return r / t;
    }

    void pitchFrame(AudioEngine.Frame f) {
        float hz = yin(f.time, f.sampleRate);
        float midi = hz > 0 ? (float) (69 + 12 * Math.log(hz / 440.0) / Math.log(2)) : Float.NaN;
        lastPitchHz = hz;
        pitchMidi[pitchHead] = midi;
        pitchTime[pitchHead] = System.currentTimeMillis();
        pitchHead = (pitchHead + 1) % PITCH_POINTS;
        if (pitchCount < PITCH_POINTS) pitchCount++;
        if (!Float.isNaN(midi)) pitchCenter += (midi - pitchCenter) * 0.05f;
    }

    void drawPitch(Canvas c, float w, float h, float a4) {
        float top = 34 * dp, bottom = h - 16 * dp, left = 54 * dp, right = w - 8 * dp;
        float span = 18; // semitones visible
        float lo = Math.round(pitchCenter) - span / 2;
        float a4Offset = (float) (12 * Math.log(a4 / 440.0) / Math.log(2));
        for (int m = (int) Math.floor(lo); m <= lo + span; m++) {
            float y = bottom - (m - lo) / span * (bottom - top);
            if (y < top - 1) continue;
            int pc = ((m % 12) + 12) % 12;
            boolean natural = EN_NOTES[pc].length() == 1;
            stroke.setColor(natural ? 0xFF22324A : 0xFF141E2E);
            stroke.setStrokeWidth((pc == 0 ? 2 : 1) * dp);
            c.drawLine(left, y, right, y, stroke);
            if (natural) label(c, noteUa(m), left - 4 * dp, y + 4 * dp, 10, pc == 0 ? CYAN : LABEL, Paint.Align.RIGHT, pc == 0);
        }
        // Trail over the last 8 seconds, newest at the right edge.
        long t0 = System.currentTimeMillis();
        float window = 8000f;
        float prevX = -1, prevY = 0;
        for (int i = 0; i < pitchCount; i++) {
            int idx = (pitchHead - pitchCount + i + PITCH_POINTS) % PITCH_POINTS;
            float age = t0 - pitchTime[idx];
            if (age > window) { prevX = -1; continue; }
            float m = pitchMidi[idx];
            if (Float.isNaN(m)) { prevX = -1; continue; }
            m -= a4Offset;
            float x = right - age / window * (right - left);
            float y = bottom - (m - lo) / span * (bottom - top);
            float cents = (m - Math.round(m)) * 100;
            int col = Math.abs(cents) < 10 ? GREEN : Math.abs(cents) < 25 ? YELLOW : PINK;
            if (prevX >= 0 && x - prevX < 40 * dp) {
                stroke.setColor(col);
                stroke.setStrokeWidth(3 * dp);
                c.drawLine(prevX, prevY, x, y, stroke);
            } else {
                fill.setColor(col);
                c.drawCircle(x, y, 2 * dp, fill);
            }
            prevX = x;
            prevY = y;
        }
        label(c, "← 8 с", left + 2 * dp, h - 4 * dp, 10, LABEL, Paint.Align.LEFT, false);
        label(c, "зараз", right, h - 4 * dp, 10, LABEL, Paint.Align.RIGHT, false);
        if (lastPitchHz > 0) {
            float[] cents = new float[1];
            String note = SpectrumMath.noteName(lastPitchHz, a4, cents);
            int midi = Math.round((float) (69 + 12 * Math.log(lastPitchHz / a4) / Math.log(2)));
            int col = Math.abs(cents[0]) < 10 ? GREEN : Math.abs(cents[0]) < 25 ? YELLOW : PINK;
            label(c, noteUa(midi).split(" ")[1] + " (" + note + ")", right, 24 * dp, 18, col, Paint.Align.RIGHT, true);
            label(c, String.format(Locale.US, "%.1f Hz  %+.0f¢", lastPitchHz, cents[0]), left, 24 * dp, 12, SOFT, Paint.Align.LEFT, false);
        } else {
            label(c, "Співайте або наспівуйте…", left, 24 * dp, 13, SOFT, Paint.Align.LEFT, false);
        }
    }

    // ================================================================= 2. note circle (chroma)

    private final float[] chroma = new float[12];
    private String chordName = "";
    private static final int[][] CHORDS = {
            {0, 4, 7}, {0, 3, 7}, {0, 3, 6}, {0, 4, 8}, {0, 5, 7}, {0, 4, 7, 10}, {0, 4, 7, 11}, {0, 3, 7, 10}};
    private static final String[] CHORD_UA = {
            "мажор", "мінор", "зменшений", "збільшений", "sus4", "7 (домінантсептакорд)", "maj7", "мінор 7"};
    private static final String[] CHORD_EN = {"", "m", "dim", "aug", "sus4", "7", "maj7", "m7"};

    void chromaFrame(AudioEngine.Frame f, float a4) {
        float[] db = f.spectrumDb;
        float[] now = new float[12];
        int lo = Math.max(2, (int) (60 / f.binHz)), hi = Math.min(db.length - 2, (int) (4200 / f.binHz));
        for (int i = lo; i <= hi; i++) {
            if (db[i] < db[i - 1] || db[i] < db[i + 1]) continue;
            if (SoundExplainer.prominence(db, i) < 8) continue;
            double p = Math.pow(10, (db[i] - f.calibrationDb) / 20.0);
            float hz = i * f.binHz;
            int pc = (int) ((Math.round(12 * Math.log(hz / a4) / Math.log(2)) + 9 + 1200) % 12);
            now[pc] += p;
        }
        float max = 0;
        for (float v : now) max = Math.max(max, v);
        for (int k = 0; k < 12; k++) {
            float v = max > 0 ? now[k] / max : 0;
            chroma[k] = chroma[k] * 0.75f + v * 0.25f;
        }
        chordName = detectChord();
    }

    private String detectChord() {
        float max = 0;
        int active = 0;
        for (float v : chroma) max = Math.max(max, v);
        if (max < 0.2f) return "";
        for (float v : chroma) if (v > 0.35f * max) active++;
        if (active == 1) {
            for (int k = 0; k < 12; k++) if (chroma[k] == max) return "Нота " + UA_NOTES[k] + " (" + EN_NOTES[k] + ")";
        }
        float bestScore = 0;
        int bestRoot = -1, bestType = -1;
        for (int type = 0; type < CHORDS.length; type++) {
            for (int root = 0; root < 12; root++) {
                float in = 0, out = 0;
                boolean[] mask = new boolean[12];
                for (int iv : CHORDS[type]) mask[(root + iv) % 12] = true;
                int nIn = 0;
                for (int k = 0; k < 12; k++) {
                    if (mask[k]) { in += chroma[k]; nIn++; } else out += chroma[k];
                }
                float score = in / nIn - out / (12 - nIn) - 0.02f * nIn; // prefer simpler chords
                for (int iv : CHORDS[type]) if (chroma[(root + iv) % 12] < 0.25f * max) score -= 0.3f;
                if (score > bestScore) { bestScore = score; bestRoot = root; bestType = type; }
            }
        }
        if (bestRoot < 0 || bestScore < 0.25f) return "";
        return UA_NOTES[bestRoot] + " " + CHORD_UA[bestType] + "  (" + EN_NOTES[bestRoot] + CHORD_EN[bestType] + ")";
    }

    void drawChroma(Canvas c, float w, float h) {
        float cx = w / 2, cy = h * 0.5f + 6 * dp;
        float r = Math.min(w, h) * 0.36f;
        float max = 1e-6f;
        for (float v : chroma) max = Math.max(max, v);
        RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
        float[] px = new float[12], py = new float[12];
        for (int k = 0; k < 12; k++) {
            float v = chroma[k] / max;
            float startAng = -90 - 15 + k * 30;
            int hue = Color.HSVToColor(new float[]{k * 30f, 0.85f, 0.35f + 0.65f * v});
            fill.setColor(hue);
            fill.setAlpha((int) (60 + 195 * v));
            float rr = r * (0.35f + 0.65f * v);
            c.drawArc(new RectF(cx - rr, cy - rr, cx + rr, cy + rr), startAng + 1, 28, true, fill);
            fill.setAlpha(255);
            double a = Math.toRadians(-90 + k * 30);
            px[k] = cx + (float) Math.cos(a) * r * 0.55f;
            py[k] = cy + (float) Math.sin(a) * r * 0.55f;
            float lx = cx + (float) Math.cos(a) * (r + 18 * dp), ly = cy + (float) Math.sin(a) * (r + 18 * dp);
            label(c, UA_NOTES[k], lx, ly, 12, v > 0.35f ? Color.WHITE : LABEL, Paint.Align.CENTER, v > 0.35f);
            label(c, EN_NOTES[k], lx, ly + 12 * dp, 9, LABEL, Paint.Align.CENTER, false);
        }
        stroke.setColor(GRID);
        stroke.setStrokeWidth(1 * dp);
        c.drawOval(oval, stroke);
        // Polygon through the sounding notes: chord shapes become recognisable triangles.
        path.reset();
        int n = 0;
        for (int k = 0; k < 12; k++) {
            if (chroma[k] / max < 0.35f) continue;
            if (n++ == 0) path.moveTo(px[k], py[k]); else path.lineTo(px[k], py[k]);
        }
        if (n >= 2) {
            path.close();
            stroke.setColor(Color.WHITE);
            stroke.setStrokeWidth(2 * dp);
            c.drawPath(path, stroke);
        }
        label(c, chordName.isEmpty() ? "Заграйте акорд або заспівайте" : chordName, w / 2, 22 * dp, 16,
                chordName.isEmpty() ? SOFT : CYAN, Paint.Align.CENTER, true);
        label(c, "Усі октави однієї ноти складаються в один сектор", w / 2, h - 6 * dp, 10, LABEL, Paint.Align.CENTER, false);
    }

    // ================================================================= 3. intervals

    void drawIntervals(Canvas c, float w, float h) {
        float base = SignalGenerator.frequency;
        float ratio = SignalGenerator.intervalRatio;
        float detune = SignalGenerator.intervalDetuneHz;
        int p = intervalP, q = intervalQ;
        float t = now();

        // Lissajous figure (top): x = lower tone, y = upper tone. Detuning makes it rotate.
        float size = Math.min(w * 0.42f, h * 0.27f);
        float cx = w / 2, cy = 40 * dp + size;
        stroke.setColor(GRID);
        stroke.setStrokeWidth(1 * dp);
        c.drawRect(cx - size, cy - size, cx + size, cy + size, stroke);
        double drift = 2 * Math.PI * detune * (t % 1000) + t * 0.15; // slow rotation even when exact
        path.reset();
        int pts = 1500;
        float cycles = Math.max(q, 1) * (detune > 0 ? 3 : 1);
        for (int i = 0; i <= pts; i++) {
            double u = 2 * Math.PI * cycles * i / pts;
            float x = cx + size * 0.92f * (float) Math.sin(u);
            float y = cy - size * 0.92f * (float) Math.sin(u * ratio + drift);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        stroke.setColor(CYAN);
        stroke.setStrokeWidth(1.6f * dp);
        c.drawPath(path, stroke);

        int complexity = p + q;
        String feel = complexity <= 5 ? "дуже злитно, консонанс" : complexity <= 9 ? "приємно, консонанс"
                : complexity <= 15 ? "м'яка напруга" : "різко, дисонанс";
        int col = complexity <= 9 ? GREEN : complexity <= 15 ? YELLOW : PINK;
        label(c, String.format(Locale.US, "%s  %d:%d", intervalName, p, q), cx, 22 * dp, 16, col, Paint.Align.CENTER, true);
        label(c, feel + (detune > 0 ? String.format(Locale.US, " · розстроєно на %.1f Гц", detune) : ""),
                cx, 36 * dp, 11, SOFT, Paint.Align.CENTER, false);

        // Combined wave (bottom).
        float top = cy + size + 26 * dp, bottom = h - 22 * dp;
        float mid = (top + bottom) / 2, amp = (bottom - top) / 2 * 0.9f;
        stroke.setColor(GRID);
        stroke.setStrokeWidth(1 * dp);
        c.drawLine(0, mid, w, mid, stroke);
        double seconds;
        String caption;
        if (detune > 0.05f && p == q) {
            seconds = Math.min(3.0, 3.0 / detune);
            caption = String.format(Locale.US, "Биття: гучність пульсує %.1f раз/с (= різниця частот)", detune);
        } else {
            seconds = 3.0 * q / base;
            caption = String.format(Locale.US, "Спільна хвиля повторюється кожні %d період(и) нижньої ноти (%.1f мс)",
                    q, 1000.0 * q / base);
        }
        path.reset();
        int samples = (int) Math.max(w, 1);
        for (int i = 0; i < samples; i++) {
            double tt = seconds * i / samples;
            double v = 0.5 * Math.sin(2 * Math.PI * base * tt) + 0.5 * Math.sin(2 * Math.PI * (base * ratio + detune) * tt);
            float y = mid - (float) v * amp;
            if (i == 0) path.moveTo(i, y); else path.lineTo(i, y);
        }
        stroke.setColor(YELLOW);
        stroke.setStrokeWidth(1.2f * dp);
        c.drawPath(path, stroke);
        label(c, caption, 6 * dp, top - 6 * dp, 10.5f, SOFT, Paint.Align.LEFT, false);
        label(c, String.format(Locale.US, "Нижня %.0f Гц · верхня %.1f Гц", base, base * ratio + detune),
                6 * dp, h - 6 * dp, 10, LABEL, Paint.Align.LEFT, false);
    }

    // ================================================================= 4. sound in air

    private final Random rnd = new Random(7);
    private final float[] jitterX = new float[64 * 14], jitterY = new float[64 * 14];

    {
        for (int i = 0; i < jitterX.length; i++) {
            jitterX[i] = rnd.nextFloat() - 0.5f;
            jitterY[i] = rnd.nextFloat() - 0.5f;
        }
    }

    /**
     * @param re,im  Fourier coefficients of one period (index = harmonic), or null if no pitch
     * @param f0     fundamental in Hz (0 if none)
     * @param levelDb RMS level in dBFS
     */
    void drawAir(Canvas c, float w, float h, float[] re, float[] im, float f0, float levelDb) {
        int cols = 56, rows = 12;
        float top = 46 * dp, bottom = h * 0.62f;
        float spacing = w / cols;
        boolean tonal = re != null && f0 > 0;
        double lambda = tonal ? 343.0 / f0 : 1.0; // metres
        float loud = Math.max(0f, Math.min(1f, (levelDb + 60) / 50f));
        float slow = tonal ? f0 / 0.6f : 1;
        double theta = 2 * Math.PI * now() * 0.6; // visual: 0.6 periods per second

        int kmax = re == null ? 0 : re.length - 1;
        double norm = 0, dispNorm = 0;
        for (int k = 1; k <= kmax; k++) {
            norm += Math.hypot(re[k], im[k]);
            dispNorm += Math.hypot(re[k], im[k]) / k;
        }
        if (norm <= 0) norm = 1;
        if (dispNorm <= 0) dispNorm = 1;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                float x0 = (col + 0.5f) * spacing + jitterX[i] * spacing * 0.5f;
                float y0 = top + (row + 0.5f) * (bottom - top) / rows + jitterY[i] * 6 * dp;
                float dx;
                if (tonal) {
                    // Two wavelengths across the screen; displacement is the integral of pressure.
                    double phase = theta - 2 * Math.PI * 2 * x0 / w;
                    double s = 0;
                    for (int k = 1; k <= kmax; k++) {
                        s += Math.hypot(re[k], im[k]) * Math.sin(k * phase + Math.atan2(im[k], re[k])) / k;
                    }
                    dx = (float) (s / dispNorm) * spacing * 2.6f * (0.4f + 0.6f * loud);
                } else {
                    dx = (rnd.nextFloat() - 0.5f) * spacing * 1.2f * loud;
                }
                fill.setColor(0xFF64B5F6);
                c.drawCircle(x0 + dx, y0, 2.2f * dp, fill);
            }
        }

        // Pressure curve below the particles.
        float pTop = bottom + 14 * dp, pBot = h - 34 * dp;
        float mid = (pTop + pBot) / 2, amp = (pBot - pTop) / 2 * 0.9f;
        stroke.setColor(GRID);
        stroke.setStrokeWidth(1 * dp);
        c.drawLine(0, mid, w, mid, stroke);
        label(c, "тиск", 4 * dp, pTop + 8 * dp, 10, LABEL, Paint.Align.LEFT, false);
        if (tonal) {
            path.reset();
            for (int x = 0; x <= (int) w; x += 2) {
                double phase = theta - 2 * Math.PI * 2 * x / w;
                double s = 0;
                for (int k = 1; k <= kmax; k++) s += Math.hypot(re[k], im[k]) * Math.cos(k * phase + Math.atan2(im[k], re[k]));
                float y = mid - (float) (s / norm) * amp;
                if (x == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            stroke.setColor(YELLOW);
            stroke.setStrokeWidth(2 * dp);
            c.drawPath(path, stroke);
            // Wavelength ruler: one wavelength is half the screen.
            float ry = h - 18 * dp;
            stroke.setColor(CYAN);
            stroke.setStrokeWidth(1.5f * dp);
            c.drawLine(w * 0.25f, ry, w * 0.75f, ry, stroke);
            c.drawLine(w * 0.25f, ry - 5 * dp, w * 0.25f, ry + 5 * dp, stroke);
            c.drawLine(w * 0.75f, ry - 5 * dp, w * 0.75f, ry + 5 * dp, stroke);
            String len = lambda >= 1 ? String.format(Locale.US, "%.2f м", lambda) : String.format(Locale.US, "%.1f см", lambda * 100);
            label(c, "довжина хвилі λ = 343 / " + Math.round(f0) + " = " + len, w / 2, h - 4 * dp, 11, CYAN, Paint.Align.CENTER, true);
            label(c, String.format(Locale.US, "%s · сповільнено в %.0f разів", SpectrumMath.formatHz(f0), slow),
                    w / 2, 18 * dp, 13, Color.WHITE, Paint.Align.CENTER, true);
            label(c, "Згущення — стиснене повітря (тиск вищий), розрідження — нижчий", w / 2, 34 * dp, 10.5f, SOFT,
                    Paint.Align.CENTER, false);
        } else {
            label(c, levelDb < -60 ? "Тиша: молекули майже не рухаються" : "Шум: молекули штовхаються хаотично, без хвилі",
                    w / 2, 18 * dp, 13, Color.WHITE, Paint.Align.CENTER, true);
            label(c, "Свисніть — побачите рівну хвилю, що біжить праворуч", w / 2, 34 * dp, 10.5f, SOFT, Paint.Align.CENTER, false);
        }
    }

    // ================================================================= 6. room echo

    private static final int ENV_CHUNK = 256;
    private float[] env = new float[0];     // dBFS per chunk, ring buffer
    private int envHead, envRate;
    private long lastIndex = -1;
    private int captureLeft = -1;           // chunks still to capture after an onset
    private float[] captured;               // decay right after the latest clap
    private int capturedLen;
    private float rt60 = Float.NaN;
    private float edtStart, edtSlope;       // fitted line for display (dB at t=0, dB per second)
    private float floorDb = -90;

    void echoFrame(AudioEngine.Frame f) {
        int rate = f.sampleRate;
        if (env.length == 0 || envRate != rate) {
            envRate = rate;
            env = new float[3 * rate / ENV_CHUNK];
            Arrays.fill(env, -120);
            lastIndex = -1;
        }
        long missed = lastIndex < 0 ? 1 : Math.max(1, f.index - lastIndex);
        lastIndex = f.index;
        int fresh = (int) Math.min(f.time.length, missed * AudioEngine.HOP);
        if (f.tape != null && !f.tapePlaying) return;
        int start = f.time.length - fresh;
        for (int s = start; s + ENV_CHUNK <= f.time.length; s += ENV_CHUNK) {
            double sum = 0;
            for (int i = s; i < s + ENV_CHUNK; i++) sum += f.time[i] * f.time[i];
            float db = (float) (10 * Math.log10(sum / ENV_CHUNK + 1e-14));
            pushEnv(db);
        }
    }

    private void pushEnv(float db) {
        // Floor: 20th percentile of the last second.
        int second = envRate / ENV_CHUNK;
        if (captureLeft < 0) {
            float[] recent = new float[second];
            for (int i = 0; i < second; i++) recent[i] = env[(envHead - 1 - i + env.length) % env.length];
            Arrays.sort(recent);
            floorDb = recent[second / 5];
        }
        env[envHead] = db;
        envHead = (envHead + 1) % env.length;
        if (captureLeft < 0 && db > floorDb + 25 && db > -45) {
            captureLeft = (int) (1.6f * envRate / ENV_CHUNK);
            captured = new float[captureLeft + 4];
            capturedLen = 0;
        }
        if (captureLeft >= 0) {
            if (capturedLen < captured.length) captured[capturedLen++] = db;
            if (--captureLeft < 0) analyseDecay();
        }
    }

    /** Schroeder backward integration, then T20 (or T10) extrapolated to 60 dB. */
    private void analyseDecay() {
        int n = capturedLen;
        int peak = 0;
        for (int i = 1; i < Math.min(n, 12); i++) if (captured[i] > captured[peak]) peak = i;
        double noise = Math.pow(10, floorDb / 10);
        double[] e = new double[n];
        for (int i = peak; i < n; i++) e[i] = Math.max(0, Math.pow(10, captured[i] / 10) - noise);
        double[] edc = new double[n];
        double acc = 0;
        for (int i = n - 1; i >= peak; i--) { acc += e[i]; edc[i] = acc; }
        if (edc[peak] <= 0) { rt60 = Float.NaN; return; }
        // A sustained sound (a held note, music) is not a decay: it must have dropped clearly by the end.
        if (captured[n - 1] > captured[peak] - 15) { rt60 = Float.NaN; return; }
        float dt = (float) ENV_CHUNK / envRate;
        int i5 = -1, i25 = -1, i15 = -1;
        for (int i = peak; i < n; i++) {
            double d = 10 * Math.log10(edc[i] / edc[peak] + 1e-12);
            if (i5 < 0 && d <= -5) i5 = i;
            if (i15 < 0 && d <= -15) i15 = i;
            if (i25 < 0 && d <= -25) { i25 = i; break; }
        }
        int end = i25 > 0 ? i25 : i15;
        float drop = i25 > 0 ? 20 : 10;
        if (i5 < 0 || end <= i5) { rt60 = Float.NaN; return; }
        float seconds = (end - i5) * dt;
        rt60 = seconds * 60 / drop;
        edtSlope = -60 / rt60;
        edtStart = captured[peak];
    }

    void drawEcho(Canvas c, float w, float h) {
        float left = 40 * dp, right = w - 8 * dp, top = 60 * dp, bottom = h - 26 * dp;
        float dbTop = 0, dbBottom = -90;
        stroke.setStrokeWidth(1 * dp);
        for (int db = 0; db >= -90; db -= 15) {
            float y = top + (dbTop - db) / (dbTop - dbBottom) * (bottom - top);
            stroke.setColor(GRID);
            c.drawLine(left, y, right, y, stroke);
            label(c, String.valueOf(db), left - 4 * dp, y + 4 * dp, 9, LABEL, Paint.Align.RIGHT, false);
        }
        boolean showDecay = captured != null && captureLeft < 0 && capturedLen > 0;
        float seconds = showDecay ? (float) capturedLen * ENV_CHUNK / envRate : 3f;
        for (int s = 0; s <= (int) seconds; s++) {
            float x = left + s / seconds * (right - left);
            label(c, s + " с", x, h - 10 * dp, 9, LABEL, Paint.Align.CENTER, false);
        }
        path.reset();
        int n = showDecay ? capturedLen : env.length;
        for (int i = 0; i < n; i++) {
            float db = showDecay ? captured[i] : env[(envHead + i) % env.length];
            float x = left + (float) i / Math.max(1, n - 1) * (right - left);
            float y = top + (dbTop - Math.max(dbBottom, Math.min(dbTop, db))) / (dbTop - dbBottom) * (bottom - top);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        stroke.setColor(showDecay ? YELLOW : 0xFF64B5F6);
        stroke.setStrokeWidth(1.6f * dp);
        c.drawPath(path, stroke);

        if (showDecay && !Float.isNaN(rt60)) {
            float x0 = left, y0 = top + (dbTop - edtStart) / (dbTop - dbBottom) * (bottom - top);
            float t1 = Math.min(seconds, 60f / -edtSlope);
            float x1 = left + t1 / seconds * (right - left);
            float y1 = top + (dbTop - (edtStart + edtSlope * t1)) / (dbTop - dbBottom) * (bottom - top);
            stroke.setColor(PINK);
            stroke.setStrokeWidth(2 * dp);
            c.drawLine(x0, y0, x1, y1, stroke);
            String kind = rt60 < 0.3f ? "сухо: вулиця, м'які меблі, килими"
                    : rt60 < 0.8f ? "звичайна житлова кімната"
                    : rt60 < 2f ? "гулко: ванна, сходи, зал" : "дуже гулко: собор, тунель";
            label(c, String.format(Locale.US, "Час згасання RT60 ≈ %.2f с", rt60), w / 2, 22 * dp, 17, CYAN, Paint.Align.CENTER, true);
            label(c, kind + " · рожева лінія — спад на 60 дБ", w / 2, 40 * dp, 11, SOFT, Paint.Align.CENTER, false);
        } else if (captureLeft >= 0) {
            label(c, "Слухаю луну…", w / 2, 22 * dp, 17, YELLOW, Paint.Align.CENTER, true);
        } else {
            label(c, "Ляскніть у долоні один раз і тихо", w / 2, 22 * dp, 17, Color.WHITE, Paint.Align.CENTER, true);
            label(c, showDecay ? "Не вдалося виміряти — спробуйте голосніше і в тиші"
                    : "Графік показує гучність за останні 3 секунди", w / 2, 40 * dp, 11, SOFT, Paint.Align.CENTER, false);
        }
    }

    // ================================================================= 7. sampling

    void drawSampling(Canvas c, float w, float h) {
        float f = sampleSignalHz, fs = sampleRateHz;
        float nyq = fs / 2;
        float alias = Math.abs(f - Math.round(f / fs) * fs);
        int nSamples = 24;
        double window = nSamples / fs;
        float left = 8 * dp, right = w - 8 * dp, top = 70 * dp, bottom = h - 40 * dp;
        float mid = (top + bottom) / 2, amp = (bottom - top) / 2 * 0.85f;
        double phase0 = 0.3 + now() * 0.0; // static picture
        stroke.setColor(GRID);
        stroke.setStrokeWidth(1 * dp);
        c.drawLine(left, mid, right, mid, stroke);

        // Real (continuous) signal.
        path.reset();
        int px = (int) (right - left);
        for (int i = 0; i <= px; i++) {
            double t = window * i / px;
            float y = mid - amp * (float) Math.sin(2 * Math.PI * f * t + phase0);
            if (i == 0) path.moveTo(left + i, y); else path.lineTo(left + i, y);
        }
        stroke.setColor(0x9964B5F6);
        stroke.setStrokeWidth(1.2f * dp);
        c.drawPath(path, stroke);

        // What the samples reconstruct to (sinc interpolation over a wider set of samples).
        path.reset();
        int extra = 40;
        for (int i = 0; i <= px; i += 2) {
            double t = window * i / px;
            double sum = 0;
            for (int k = -extra; k <= nSamples + extra; k++) {
                double tk = k / fs;
                double x = Math.PI * fs * (t - tk);
                double sinc = Math.abs(x) < 1e-9 ? 1 : Math.sin(x) / x;
                sum += Math.sin(2 * Math.PI * f * tk + phase0) * sinc;
            }
            float y = mid - amp * (float) sum;
            if (i == 0) path.moveTo(left + i, y); else path.lineTo(left + i, y);
        }
        boolean aliased = f > nyq;
        stroke.setColor(aliased ? PINK : GREEN);
        stroke.setStrokeWidth(2.4f * dp);
        c.drawPath(path, stroke);

        // Sample points.
        for (int k = 0; k <= nSamples; k++) {
            double t = k / fs;
            float x = left + (float) (t / window) * (right - left);
            float y = mid - amp * (float) Math.sin(2 * Math.PI * f * t + phase0);
            stroke.setColor(0x66FFFFFF);
            stroke.setStrokeWidth(1 * dp);
            c.drawLine(x, mid, x, y, stroke);
            fill.setColor(Color.WHITE);
            c.drawCircle(x, y, 3.5f * dp, fill);
        }

        label(c, String.format(Locale.US, "Сигнал %s · дискретизація %s (%d точок на екрані)",
                SpectrumMath.formatHz(f), SpectrumMath.formatHz(fs), nSamples), w / 2, 20 * dp, 12, Color.WHITE, Paint.Align.CENTER, true);
        label(c, String.format(Locale.US, "Межа Найквіста = fs / 2 = %s", SpectrumMath.formatHz(nyq)),
                w / 2, 36 * dp, 11, SOFT, Paint.Align.CENTER, false);
        if (aliased) {
            label(c, String.format(Locale.US, "Вище за межу! Точки описують хибну хвилю %s — це аліасинг", SpectrumMath.formatHz(alias)),
                    w / 2, 54 * dp, 11.5f, PINK, Paint.Align.CENTER, true);
        } else {
            label(c, String.format(Locale.US, "%.1f точки на період — хвилю відновлено правильно", fs / f),
                    w / 2, 54 * dp, 11.5f, GREEN, Paint.Align.CENTER, true);
        }
        label(c, "блакитна — справжній звук · точки — вибірки · " + (aliased ? "рожева" : "зелена") + " — що «бачить» телефон",
                w / 2, h - 14 * dp, 10, LABEL, Paint.Align.CENTER, false);
    }
}
