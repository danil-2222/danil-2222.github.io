package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * What the ear hears versus what the meter measures: ISO 226:2003 equal-loudness contours with
 * the live sound in third-octave bands, each coloured by its perceived loudness in phon.
 */
final class LoudnessLesson implements LessonView {
    /** Assumed level of 0 dBFS when the microphone is not calibrated. */
    static final float DEFAULT_OFFSET = 120;

    private static final float[] F = {20, 25, 31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800,
            1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500};
    private static final float[] AF = {0.532f, 0.506f, 0.480f, 0.455f, 0.432f, 0.409f, 0.387f, 0.367f, 0.349f, 0.330f,
            0.315f, 0.301f, 0.288f, 0.276f, 0.267f, 0.259f, 0.253f, 0.250f, 0.246f, 0.244f, 0.243f, 0.243f, 0.243f,
            0.242f, 0.242f, 0.245f, 0.254f, 0.271f, 0.301f};
    private static final float[] LU = {-31.6f, -27.2f, -23.0f, -19.1f, -15.9f, -13.0f, -10.3f, -8.1f, -6.2f, -4.5f,
            -3.1f, -2.0f, -1.1f, -0.4f, 0.0f, 0.3f, 0.5f, 0.0f, -2.7f, -4.1f, -1.0f, 1.7f, 2.5f, 1.2f, -2.1f, -7.1f,
            -11.2f, -10.7f, -3.1f};
    private static final float[] TF = {78.5f, 68.7f, 59.5f, 51.1f, 44.0f, 37.5f, 31.5f, 26.5f, 22.1f, 17.9f, 14.4f,
            11.4f, 8.6f, 6.2f, 4.4f, 3.0f, 2.2f, 2.4f, 3.5f, 1.7f, -1.3f, -4.2f, -6.0f, -5.4f, -1.5f, 6.0f, 12.6f,
            13.9f, 12.3f};

    /** Sound pressure level (dB) that sounds as loud as `phon` at band i. */
    static float spl(int i, float phon) {
        double af = 4.47e-3 * (Math.pow(10, 0.025 * phon) - 1.15)
                + Math.pow(0.4 * Math.pow(10, (TF[i] + LU[i]) / 10 - 9), AF[i]);
        return (float) (10 / AF[i] * Math.log10(af) - LU[i] + 94);
    }

    /** Perceived loudness (phon) of a band at `db` SPL, or NaN below the threshold of hearing. */
    static float phon(int i, float db) {
        if (db < spl(i, 0)) return Float.NaN;
        float lo = 0, hi = 120;
        for (int it = 0; it < 30; it++) {
            float mid = (lo + hi) / 2;
            if (spl(i, mid) < db) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2;
    }

    private final LessonKit k;
    private final float[] bandDb = new float[F.length];
    private boolean calibrated;

    LoudnessLesson(float dp) {
        k = new LessonKit(dp);
        java.util.Arrays.fill(bandDb, -50);
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        calibrated = f.calibrationDb != 0;
        float offset = calibrated ? 0 : DEFAULT_OFFSET;
        for (int b = 0; b < F.length; b++) {
            float lo = F[b] * 0.8909f, hi = F[b] * 1.1225f;
            int i0 = Math.max(1, (int) Math.ceil(lo / f.binHz)), i1 = (int) Math.floor(hi / f.binHz);
            double p = 0;
            if (i1 >= i0 && i1 < f.spectrumDb.length) {
                for (int i = i0; i <= i1; i++) p += Math.pow(10, f.spectrumDb[i] / 10.0);
                p /= f.enbw;
            } else {
                p = Math.pow(10, SpectrumMath.valueAtBin(f.spectrumDb, F[b] / f.binHz) / 10.0);
            }
            float db = (float) (10 * Math.log10(p + 1e-20)) + offset;
            bandDb[b] = bandDb[b] * 0.6f + db * 0.4f;
        }
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        float left = 30 * dp, right = w - 8 * dp, top = 50 * dp, bottom = h - 46 * dp;
        float dbLo = -10, dbHi = 120;
        k.label(c, "Що чує вухо, а що — прилад", 8 * dp, 18 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Лінії — однакова гучність для вуха (ISO 226). Стовпчики — звук зараз.", 8 * dp, 34 * dp, 10.5f,
                LessonKit.SOFT, Paint.Align.LEFT, false);
        k.rect(c, left, top, right, bottom, 0xFF0B1220);
        for (int db = 0; db <= 120; db += 20) {
            float y = bottom - (db - dbLo) / (dbHi - dbLo) * (bottom - top);
            k.line(c, left, y, right, y, LessonKit.GRID, 1);
            k.label(c, String.valueOf(db), left - 3 * dp, y + 3 * dp, 9, LessonKit.LABEL, Paint.Align.RIGHT, false);
        }
        int n = F.length;
        float bw = (right - left) / n;
        // Bars coloured by perceived loudness.
        for (int b = 0; b < n; b++) {
            float db = Math.max(dbLo, Math.min(dbHi, bandDb[b]));
            float ph = phon(b, bandDb[b]);
            int col = Float.isNaN(ph) ? 0xFF334155 : Palettes.lut(Palettes.Palette.SPECTRUM)[(int) Math.min(255, ph / 100 * 255)];
            float y = bottom - (db - dbLo) / (dbHi - dbLo) * (bottom - top);
            k.rect(c, left + b * bw + 1, y, left + (b + 1) * bw - 1, bottom, col);
        }
        // Contours.
        float[] phons = {0, 20, 40, 60, 80, 100};
        for (float ph : phons) {
            k.path.reset();
            for (int b = 0; b < n; b++) {
                float y = bottom - (Math.min(dbHi, spl(b, ph)) - dbLo) / (dbHi - dbLo) * (bottom - top);
                float x = left + (b + 0.5f) * bw;
                if (b == 0) k.path.moveTo(x, y); else k.path.lineTo(x, y);
            }
            k.stroke.setColor(ph == 0 ? LessonKit.PINK : 0xCCFFFFFF);
            k.stroke.setStrokeWidth((ph == 0 ? 2.5f : 1.3f) * dp);
            c.drawPath(k.path, k.stroke);
            float ly = bottom - (spl(n - 1, ph) - dbLo) / (dbHi - dbLo) * (bottom - top);
            k.label(c, ph == 0 ? "поріг" : Math.round(ph) + " фон", right - 2 * dp, ly - 3 * dp, 9, ph == 0 ? LessonKit.PINK : LessonKit.SOFT,
                    Paint.Align.RIGHT, false);
        }
        for (int b = 1; b < n; b += 4) {
            k.label(c, SpectrumMath.tickLabel(F[b]), left + (b + 0.5f) * bw, bottom + 12 * dp, 9, LessonKit.LABEL, Paint.Align.CENTER, false);
        }
        int loudest = -1;
        for (int b = 0; b < n; b++) {
            float ph = phon(b, bandDb[b]);
            if (!Float.isNaN(ph) && (loudest < 0 || ph > phon(loudest, bandDb[loudest]))) loudest = b;
        }
        String s = loudest < 0 ? "Зараз нічого не чутно: усе нижче рожевого порогу"
                : String.format(Locale.US, "Найгучніше для вуха: %s Гц (%.0f фон)", SpectrumMath.tickLabel(F[loudest]), phon(loudest, bandDb[loudest]));
        k.label(c, s, 8 * dp, h - 24 * dp, 11.5f, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, calibrated ? "рівні з калібруванням" : "рівні приблизні (0 dBFS ≈ 120 дБ) — відкалібруйте у «Аналіз»",
                8 * dp, h - 8 * dp, 9.5f, LessonKit.LABEL, Paint.Align.LEFT, false);
    }
}
