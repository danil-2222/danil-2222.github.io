package com.example.audiospectrumpro;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Two loudspeakers seen from above: their waves add where crests meet crests and cancel where a
 * crest meets a trough. Drag the speakers; change frequency and phase.
 */
final class InterferenceLesson implements LessonView {
    static final float WIDTH_M = 4f;

    volatile float freqHz = 500;
    volatile boolean antiPhase;
    /** Show the time-averaged loudness instead of the moving wave. */
    volatile boolean loudnessView;

    private final LessonKit k;
    private final float[] sx = {0.38f, 0.62f}, sy = {0.5f, 0.5f};   // fractions of the field
    private int gw, gh;
    private float[] cr, ci;
    private String key = "";
    private int[] px;
    private Bitmap bmp;
    private int dragging = -1;
    private final long start = System.currentTimeMillis();

    InterferenceLesson(float dp) { k = new LessonKit(dp); }

    private RectF field(int w, int h) {
        float dp = k.dp;
        return new RectF(4 * dp, 44 * dp, w - 4 * dp, h - 40 * dp);
    }

    private void compute(RectF r) {
        String nk = freqHz + "|" + antiPhase + "|" + sx[0] + sy[0] + sx[1] + sy[1] + "|" + (int) r.width() + "x" + (int) r.height();
        if (nk.equals(key)) return;
        key = nk;
        gw = 150;
        gh = Math.max(40, Math.round(gw * r.height() / r.width()));
        cr = new float[gw * gh];
        ci = new float[gw * gh];
        px = new int[gw * gh];
        bmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
        double kWave = 2 * Math.PI * freqHz / 343.0;
        double mPerCell = WIDTH_M / gw;
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                double re = 0, im = 0;
                for (int s = 0; s < 2; s++) {
                    double dx = (x - sx[s] * gw) * mPerCell, dy = (y - sy[s] * gh) * mPerCell;
                    double d = Math.max(0.05, Math.hypot(dx, dy));
                    double a = 1 / Math.sqrt(d);
                    double ph = -kWave * d + (s == 1 && antiPhase ? Math.PI : 0);
                    re += a * Math.cos(ph);
                    im += a * Math.sin(ph);
                }
                cr[y * gw + x] = (float) re;
                ci[y * gw + x] = (float) im;
            }
        }
    }

    @Override public void onFrame(AudioEngine.Frame f) {}

    @Override public boolean animating() { return true; }

    @Override
    public boolean onTouch(MotionEvent e, int w, int h) {
        RectF r = field(w, h);
        float fx = (e.getX() - r.left) / r.width(), fy = (e.getY() - r.top) / r.height();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = -1;
                for (int s = 0; s < 2; s++) {
                    if (Math.hypot((fx - sx[s]) * r.width(), (fy - sy[s]) * r.height()) < 30 * k.dp) dragging = s;
                }
                return dragging >= 0;
            case MotionEvent.ACTION_MOVE:
                if (dragging < 0) return false;
                sx[dragging] = Math.max(0.02f, Math.min(0.98f, fx));
                sy[dragging] = Math.max(0.02f, Math.min(0.98f, fy));
                return true;
            default:
                boolean was = dragging >= 0;
                dragging = -1;
                return was;
        }
    }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        RectF r = field(w, h);
        compute(r);
        double t = (System.currentTimeMillis() - start) / 1000.0;
        double wt = 2 * Math.PI * 0.7 * t; // slowed down
        float cw = (float) Math.cos(wt), sw = (float) Math.sin(wt);
        float norm = 1.2f;
        for (int i = 0; i < px.length; i++) {
            if (loudnessView) {
                float a = (float) Math.hypot(cr[i], ci[i]) / (2 * norm);
                int v = (int) (Math.min(1, a) * 255);
                px[i] = 0xFF000000 | (v << 16) | ((v * 3 / 4) << 8) | (40 + v / 6);
            } else {
                float v = (cr[i] * cw + ci[i] * sw) / norm;
                v = Math.max(-1, Math.min(1, v));
                int pos = (int) (Math.max(0, v) * 255), neg = (int) (Math.max(0, -v) * 255);
                px[i] = 0xFF000000 | ((20 + pos * 9 / 10) << 16) | ((20 + Math.max(pos, neg) / 3) << 8) | (30 + neg * 9 / 10);
            }
        }
        bmp.setPixels(px, 0, gw, 0, 0, gw, gh);
        Paint p = new Paint();
        p.setFilterBitmap(true);
        c.drawBitmap(bmp, null, r, p);
        for (int s = 0; s < 2; s++) {
            float x = r.left + sx[s] * r.width(), y = r.top + sy[s] * r.height();
            k.fill.setColor(LessonKit.WHITE);
            c.drawCircle(x, y, 9 * dp, k.fill);
            k.label(c, String.valueOf(s + 1), x, y + 4 * dp, 11, 0xFF0B1220, Paint.Align.CENTER, true);
        }
        float lambda = 343f / freqHz;
        double dist = Math.hypot((sx[0] - sx[1]) * WIDTH_M, (sy[0] - sy[1]) * WIDTH_M * r.height() / r.width());
        k.label(c, "Інтерференція двох джерел", 8 * dp, 18 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, String.format(Locale.US, "%.0f Гц · λ = %.2f м · між динаміками %.2f м%s", freqHz, lambda, dist,
                antiPhase ? " · протифаза" : ""), 8 * dp, 34 * dp, 10.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        k.label(c, loudnessView ? "яскраве — гучно, темне — звук гасне" : "червоне — стиснення, синє — розрідження, темні смуги — тиша",
                8 * dp, h - 24 * dp, 10.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        k.label(c, "поле 4 м завширки · тягніть динаміки пальцем · хвилю сповільнено", 8 * dp, h - 8 * dp, 9.5f, LessonKit.LABEL,
                Paint.Align.LEFT, false);
    }
}
