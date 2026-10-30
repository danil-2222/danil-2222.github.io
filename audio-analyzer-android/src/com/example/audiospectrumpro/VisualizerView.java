package com.example.audiospectrumpro;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;
import java.util.Locale;

public class VisualizerView extends View {
    public enum Mode { LINE, BARS, RTA, WATERFALL, OSCILLOSCOPE, TUNER }

    private static final int BG = Color.parseColor("#080C14");
    private static final int GRID = Color.parseColor("#1E293B");
    private static final int LABEL = Color.parseColor("#7F93A8");
    private static final float DB_TOP = 0f, DB_BOTTOM = -120f;
    private static final float WF_MAX = -20f, WF_MIN = -120f;
    private static final float[] RTA_FREQS = {
            20, 25, 31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800,
            1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000
    };
    private static final float[] LOG_TICKS = {20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000};
    private static final float[] LOG_MINOR = {30, 40, 60, 70, 80, 90, 300, 400, 600, 700, 800, 900,
            3000, 4000, 6000, 7000, 8000, 9000};

    private Mode mode = Mode.LINE;
    private Palettes.Palette palette = Palettes.Palette.SPECTRUM;
    private boolean logScale = true;
    private boolean showPeakHold = true;
    private AudioEngine.Frame frame;

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF plot = new RectF();
    private float[] cols = new float[0];
    private float[] peakCols = new float[0];

    private Bitmap wfBitmap;
    private int[] wfRow;
    private int wfHead;
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private final float[] rtaDb = new float[RTA_FREQS.length];
    private final float[] rtaPeak = new float[RTA_FREQS.length];

    private float cursorX = -1;
    private float scopeScale = 1f;

    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        grid.setColor(GRID);
        grid.setStrokeWidth(1);
        text.setColor(LABEL);
        text.setTextSize(10 * dp);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        Arrays.fill(rtaPeak, DB_BOTTOM);
    }

    public void setMode(Mode m) {
        mode = m;
        cursorX = -1;
        invalidate();
    }

    public void setPalette(Palettes.Palette p) { palette = p; invalidate(); }

    public void setLogScale(boolean log) {
        logScale = log;
        clearWaterfall();
        invalidate();
    }

    public void setShowPeakHold(boolean show) { showPeakHold = show; invalidate(); }

    public void resetPeaks() { Arrays.fill(rtaPeak, DB_BOTTOM); }

    public void setFrame(AudioEngine.Frame f) {
        frame = f;
        if (mode == Mode.WATERFALL) pushWaterfallRow(f);
        if (mode == Mode.RTA) computeRta(f);
        invalidate();
    }

    public Bitmap snapshot() {
        Bitmap b = Bitmap.createBitmap(Math.max(1, getWidth()), Math.max(1, getHeight()), Bitmap.Config.ARGB_8888);
        draw(new Canvas(b));
        return b;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        plot.set(0, 0, w, h - 18 * dp);
        cols = new float[Math.max(1, w)];
        peakCols = new float[Math.max(1, w)];
        clearWaterfall();
    }

    private void clearWaterfall() {
        if (wfBitmap != null) wfBitmap.recycle();
        wfBitmap = null;
        int w = (int) plot.width(), h = (int) plot.height();
        if (w <= 0 || h <= 0) return;
        wfBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        wfBitmap.eraseColor(Color.BLACK);
        wfRow = new int[w];
        wfHead = 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mode != Mode.LINE && mode != Mode.BARS && mode != Mode.WATERFALL) return false;
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (cursorX >= 0 && Math.abs(x - cursorX) < 24 * dp) {
                    cursorX = -2; // tapped the existing cursor: remove on release
                } else {
                    cursorX = x;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                cursorX = x;
                break;
            case MotionEvent.ACTION_UP:
                if (cursorX == -2) cursorX = -1;
                break;
        }
        invalidate();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(BG);
        AudioEngine.Frame f = frame;
        switch (mode) {
            case LINE: drawFreqGrid(canvas, f, true); if (f != null) drawLine(canvas, f); break;
            case BARS: drawFreqGrid(canvas, f, true); if (f != null) drawBars(canvas, f); break;
            case RTA: drawRta(canvas, f); break;
            case WATERFALL: drawWaterfall(canvas, f); break;
            case OSCILLOSCOPE: drawScope(canvas, f); break;
            case TUNER: drawTuner(canvas, f); break;
        }
        if (f != null && cursorX >= 0 && (mode == Mode.LINE || mode == Mode.BARS || mode == Mode.WATERFALL)) {
            drawCursor(canvas, f);
        }
        if (f == null) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(14 * dp);
            canvas.drawText("Натисніть «Старт»", getWidth() / 2f, plot.centerY(), text);
            text.setTextSize(10 * dp);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private float fMax(AudioEngine.Frame f) {
        return Math.min(20000f, (f != null ? f.sampleRate : AudioEngine.SAMPLE_RATE) / 2f);
    }

    private float xOf(float freq, float fMax) {
        return plot.left + SpectrumMath.posOf(freq, logScale, fMax) * plot.width();
    }

    private float yOf(float db) {
        float t = (DB_TOP - db) / (DB_TOP - DB_BOTTOM);
        return plot.top + Math.max(0, Math.min(1, t)) * plot.height();
    }

    private void drawFreqGrid(Canvas c, AudioEngine.Frame f, boolean dbLines) {
        float fMax = fMax(f);
        if (dbLines) {
            text.setTextAlign(Paint.Align.LEFT);
            for (int db = -20; db > DB_BOTTOM; db -= 20) {
                float y = yOf(db);
                c.drawLine(plot.left, y, plot.right, y, grid);
                c.drawText(db + " dB", plot.left + 3 * dp, y - 3 * dp, text);
            }
        }
        text.setTextAlign(Paint.Align.CENTER);
        float labelY = plot.bottom + 13 * dp;
        if (logScale) {
            grid.setAlpha(110);
            for (float t : LOG_MINOR) {
                float x = xOf(t, fMax);
                c.drawLine(x, plot.top, x, plot.bottom, grid);
            }
            grid.setAlpha(255);
            for (float t : LOG_TICKS) {
                if (t > fMax) break;
                float x = xOf(t, fMax);
                c.drawLine(x, plot.top, x, plot.bottom, grid);
                String s = t >= 1000 ? (int) (t / 1000) + "k" : String.valueOf((int) t);
                c.drawText(s, Math.max(8 * dp, Math.min(getWidth() - 8 * dp, x)), labelY, text);
            }
        } else {
            for (int k = 0; k <= (int) (fMax / 2000); k++) {
                float x = xOf(k * 2000, fMax);
                c.drawLine(x, plot.top, x, plot.bottom, grid);
                if (k % 2 == 0) c.drawText(k * 2 + "k", Math.max(8 * dp, Math.min(getWidth() - 8 * dp, x)), labelY, text);
            }
        }
        c.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, grid);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawLine(Canvas c, AudioEngine.Frame f) {
        float fMax = fMax(f);
        int n = cols.length;
        SpectrumMath.columns(f.spectrumDb, f.binHz, logScale, fMax, cols);
        int accent = Palettes.accent(palette);
        int[] lut = Palettes.lut(palette);

        path.reset();
        path.moveTo(plot.left, plot.bottom);
        for (int x = 0; x < n; x++) path.lineTo(plot.left + x, yOf(cols[x]));
        path.lineTo(plot.right, plot.bottom);
        path.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(new LinearGradient(0, plot.top, 0, plot.bottom,
                new int[]{withAlpha(lut[255], 200), withAlpha(lut[170], 120), withAlpha(lut[60], 30)},
                null, Shader.TileMode.CLAMP));
        c.drawPath(path, fill);
        fill.setShader(null);

        path.reset();
        for (int x = 0; x < n; x++) {
            float y = yOf(cols[x]);
            if (x == 0) path.moveTo(plot.left, y); else path.lineTo(plot.left + x, y);
        }
        stroke.setColor(accent);
        stroke.setStrokeWidth(1.6f * dp);
        c.drawPath(path, stroke);

        if (showPeakHold) {
            SpectrumMath.columns(f.peakHoldDb, f.binHz, logScale, fMax, peakCols);
            path.reset();
            for (int x = 0; x < n; x++) {
                float y = yOf(peakCols[x]);
                if (x == 0) path.moveTo(plot.left, y); else path.lineTo(plot.left + x, y);
            }
            stroke.setColor(Color.argb(150, 255, 255, 255));
            stroke.setStrokeWidth(1f * dp);
            c.drawPath(path, stroke);
        }
        drawPeakMarker(c, f, fMax);
    }

    private void drawBars(Canvas c, AudioEngine.Frame f) {
        float fMax = fMax(f);
        float barW = 6 * dp, gap = 1.5f * dp;
        int bars = Math.max(8, (int) (plot.width() / barW));
        float[] v = new float[bars];
        float[] pk = new float[bars];
        SpectrumMath.columns(f.spectrumDb, f.binHz, logScale, fMax, v);
        SpectrumMath.columns(f.peakHoldDb, f.binHz, logScale, fMax, pk);
        int[] lut = Palettes.lut(palette);
        float w = plot.width() / bars;
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < bars; i++) {
            float top = yOf(v[i]);
            float level = (v[i] - DB_BOTTOM) / (DB_TOP - DB_BOTTOM);
            fill.setColor(lut[clamp255(level * 1.3f)]);
            float x0 = plot.left + i * w;
            c.drawRect(x0, top, x0 + w - gap, plot.bottom, fill);
            if (showPeakHold) {
                float py = yOf(pk[i]);
                fill.setColor(Palettes.accent(palette));
                c.drawRect(x0, py - 2 * dp, x0 + w - gap, py, fill);
            }
        }
        drawPeakMarker(c, f, fMax);
    }

    private void drawPeakMarker(Canvas c, AudioEngine.Frame f, float fMax) {
        if (f.peakDb < -90 || f.peakFreqHz < SpectrumMath.LOG_F_MIN) return;
        float x = xOf(f.peakFreqHz, fMax), y = yOf(f.peakDb);
        fill.setColor(Color.WHITE);
        c.drawCircle(x, y, 3 * dp, fill);
        String s = SpectrumMath.formatHz(f.peakFreqHz);
        text.setColor(Color.WHITE);
        text.setTextAlign(x > getWidth() * 0.75f ? Paint.Align.RIGHT : Paint.Align.LEFT);
        c.drawText(s, x + (x > getWidth() * 0.75f ? -6 : 6) * dp, Math.max(12 * dp, y - 6 * dp), text);
        text.setColor(LABEL);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawCursor(Canvas c, AudioEngine.Frame f) {
        float fMax = fMax(f);
        float t = Math.max(0, Math.min(1, (cursorX - plot.left) / plot.width()));
        float freq = SpectrumMath.freqAt(t, logScale, fMax);
        float db = SpectrumMath.valueAtBin(f.spectrumDb, freq / f.binHz);
        stroke.setColor(Color.parseColor("#FF007F"));
        stroke.setStrokeWidth(1.2f * dp);
        c.drawLine(cursorX, plot.top, cursorX, plot.bottom, stroke);
        String s = SpectrumMath.formatHz(freq) + (mode == Mode.WATERFALL ? "" : String.format(Locale.US, "  %.1f dB", db));
        text.setTextSize(12 * dp);
        float tw = text.measureText(s);
        float bx = Math.min(getWidth() - tw - 10 * dp, Math.max(2 * dp, cursorX + 6 * dp));
        fill.setColor(Color.argb(210, 15, 23, 42));
        c.drawRect(bx - 4 * dp, plot.top + 4 * dp, bx + tw + 4 * dp, plot.top + 22 * dp, fill);
        text.setColor(Color.parseColor("#FF007F"));
        c.drawText(s, bx, plot.top + 18 * dp, text);
        text.setColor(LABEL);
        text.setTextSize(10 * dp);
    }

    private void computeRta(AudioEngine.Frame f) {
        float decay = 0.4f;
        for (int b = 0; b < RTA_FREQS.length; b++) {
            float fc = RTA_FREQS[b];
            float lo = fc * 0.8909f, hi = fc * 1.1225f; // fc * 2^(-1/6), fc * 2^(1/6)
            int i0 = (int) Math.ceil(lo / f.binHz), i1 = (int) Math.floor(hi / f.binHz);
            double p = 0;
            if (i1 >= i0 && i1 < f.spectrumDb.length) {
                for (int i = i0; i <= i1; i++) p += Math.pow(10, f.spectrumDb[i] / 10.0);
                p /= 1.5; // Hann window equivalent noise bandwidth
            } else {
                p = Math.pow(10, SpectrumMath.valueAtBin(f.spectrumDb, fc / f.binHz) / 10.0);
            }
            float db = p > 0 ? (float) (10 * Math.log10(p)) : DB_BOTTOM;
            rtaDb[b] = Math.max(DB_BOTTOM, db);
            rtaPeak[b] = rtaDb[b] >= rtaPeak[b] ? rtaDb[b] : rtaPeak[b] - decay;
        }
    }

    private void drawRta(Canvas c, AudioEngine.Frame f) {
        text.setTextAlign(Paint.Align.LEFT);
        for (int db = -20; db > DB_BOTTOM; db -= 20) {
            float y = yOf(db);
            c.drawLine(plot.left, y, plot.right, y, grid);
            c.drawText(db + " dB", plot.left + 3 * dp, y - 3 * dp, text);
        }
        c.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, grid);
        int bands = RTA_FREQS.length;
        float w = plot.width() / bands;
        int[] lut = Palettes.lut(palette);
        text.setTextAlign(Paint.Align.CENTER);
        for (int b = 0; b < bands; b++) {
            float x0 = plot.left + b * w;
            if (f != null) {
                float level = (rtaDb[b] - DB_BOTTOM) / (DB_TOP - DB_BOTTOM);
                fill.setColor(lut[clamp255(level * 1.3f)]);
                c.drawRect(x0 + 1.5f * dp, yOf(rtaDb[b]), x0 + w - 1.5f * dp, plot.bottom, fill);
                if (showPeakHold) {
                    float py = yOf(rtaPeak[b]);
                    fill.setColor(Palettes.accent(palette));
                    c.drawRect(x0 + 1.5f * dp, py - 2 * dp, x0 + w - 1.5f * dp, py, fill);
                }
            }
            if (b % 3 == 1) {
                float fc = RTA_FREQS[b];
                String s = fc >= 1000 ? ((fc % 1000 == 0) ? (int) (fc / 1000) + "k" : (fc / 1000) + "k") : String.valueOf((int) fc);
                c.drawText(s, x0 + w / 2, plot.bottom + 13 * dp, text);
            }
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void pushWaterfallRow(AudioEngine.Frame f) {
        if (wfBitmap == null) return;
        int w = wfBitmap.getWidth(), h = wfBitmap.getHeight();
        if (cols.length != w) cols = new float[w];
        SpectrumMath.columns(f.spectrumDb, f.binHz, logScale, fMax(f), cols);
        int[] lut = Palettes.lut(palette);
        for (int x = 0; x < w; x++) {
            wfRow[x] = lut[clamp255((cols[x] - WF_MIN) / (WF_MAX - WF_MIN))];
        }
        wfHead = (wfHead - 1 + h) % h;
        wfBitmap.setPixels(wfRow, 0, w, 0, wfHead, w, 1);
    }

    private void drawWaterfall(Canvas c, AudioEngine.Frame f) {
        if (wfBitmap != null) {
            int w = wfBitmap.getWidth(), h = wfBitmap.getHeight();
            int first = h - wfHead;
            src.set(0, wfHead, w, h);
            dst.set(plot.left, plot.top, plot.right, plot.top + first);
            c.drawBitmap(wfBitmap, src, dst, null);
            if (wfHead > 0) {
                src.set(0, 0, w, wfHead);
                dst.set(plot.left, plot.top + first, plot.right, plot.bottom);
                c.drawBitmap(wfBitmap, src, dst, null);
            }
        }
        drawFreqGrid(c, f, false);
    }

    private void drawScope(Canvas c, AudioEngine.Frame f) {
        float cy = plot.centerY();
        c.drawLine(0, cy, getWidth(), cy, grid);
        for (int i = 1; i < 10; i++) {
            float x = getWidth() * i / 10f;
            c.drawLine(x, plot.top, x, plot.bottom, grid);
        }
        if (f == null) return;
        float[] t = f.time;
        int len = t.length;
        int show = Math.min(len / 2, 1024);
        int start = 0;
        for (int i = 1; i < len - show; i++) {
            if (t[i - 1] < 0 && t[i] >= 0) { start = i; break; }
        }
        float max = 1e-4f;
        for (int i = start; i < start + show; i++) max = Math.max(max, Math.abs(t[i]));
        float target = Math.min(1000f, 0.9f / max);
        scopeScale = scopeScale * 0.85f + target * 0.15f;
        float half = plot.height() / 2f;
        path.reset();
        for (int i = 0; i < show; i++) {
            float x = (float) i / (show - 1) * getWidth();
            float y = cy - Math.max(-1f, Math.min(1f, t[start + i] * scopeScale)) * half;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        stroke.setColor(Palettes.accent(palette));
        stroke.setStrokeWidth(1.6f * dp);
        c.drawPath(path, stroke);
        float ms = show * 1000f / f.sampleRate;
        c.drawText(String.format(Locale.US, "%.1f мс   підсилення ×%.0f", ms, scopeScale), 4 * dp, plot.bottom + 13 * dp, text);
    }

    private void drawTuner(Canvas c, AudioEngine.Frame f) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h * 0.78f;
        float r = Math.min(w * 0.42f, h * 0.62f);
        RectF arc = new RectF(cx - r, cy - r, cx + r, cy + r);
        stroke.setStrokeWidth(10 * dp);
        stroke.setColor(GRID);
        c.drawArc(arc, 200, 140, false, stroke);
        stroke.setColor(Color.parseColor("#39FF14"));
        c.drawArc(arc, 268, 4, false, stroke);
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setColor(LABEL);
        for (int k = -50; k <= 50; k += 10) {
            double a = Math.toRadians(270 + k * 1.4);
            float r0 = r - 12 * dp, r1 = r - (k % 50 == 0 ? 22 : 17) * dp;
            c.drawLine(cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0,
                    cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1, stroke);
        }

        boolean valid = f != null && f.peakDb > -75 && f.peakFreqHz > 25;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(Math.min(56 * dp, r * 0.5f));
        if (!valid) {
            text.setColor(LABEL);
            c.drawText("—", cx, cy - r * 0.3f, text);
        } else {
            float[] cents = new float[1];
            String note = SpectrumMath.noteName(f.peakFreqHz, cents);
            boolean inTune = Math.abs(cents[0]) <= 5;
            int col = inTune ? Color.parseColor("#39FF14") : Color.parseColor("#FF007F");
            text.setColor(col);
            c.drawText(note, cx, cy - r * 0.3f, text);
            double a = Math.toRadians(270 + Math.max(-50, Math.min(50, cents[0])) * 1.4);
            stroke.setColor(col);
            stroke.setStrokeWidth(3 * dp);
            c.drawLine(cx, cy, cx + (float) Math.cos(a) * (r - 6 * dp), cy + (float) Math.sin(a) * (r - 6 * dp), stroke);
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(14 * dp);
            text.setColor(Color.WHITE);
            c.drawText(String.format(Locale.US, "%.2f Hz   %+.0f центів", f.peakFreqHz, cents[0]), cx, cy - r * 0.08f, text);
        }
        fill.setColor(Color.WHITE);
        c.drawCircle(cx, cy, 5 * dp, fill);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(10 * dp);
        text.setColor(LABEL);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, (int) (v * 255)));
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
