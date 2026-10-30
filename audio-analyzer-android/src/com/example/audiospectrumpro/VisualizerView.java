package com.example.audiospectrumpro;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.Arrays;
import java.util.Locale;

/**
 * 2D renderer for every non-OpenGL mode. One finger moves the cursor, two fingers zoom and
 * pan the frequency axis, double tap resets the zoom, long press toggles full screen.
 */
public class VisualizerView extends View {
    public enum Mode { LINE, BARS, RTA, WATERFALL, OSCILLOSCOPE, TUNER }

    public interface Callbacks {
        void onToggleFullscreen();
    }

    private static final int BG = Color.parseColor("#080C14");
    private static final int GRID = Color.parseColor("#1E293B");
    private static final int GRID_MINOR = Color.parseColor("#121B2B");
    private static final int LABEL = Color.parseColor("#7F93A8");
    private static final int CURSOR = Color.parseColor("#FF007F");
    private static final int REFERENCE = Color.parseColor("#A0AEC0");
    private static final int[] PEAK_COLORS = {Color.WHITE, Color.parseColor("#FFE600"), Color.parseColor("#39FF14")};
    private static final float[] RTA_FREQS = {
            20, 25, 31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800,
            1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000
    };

    private Mode mode = Mode.LINE;
    private Palettes.Palette palette = Palettes.Palette.SPECTRUM;
    private boolean logScale = true;
    private boolean showPeakHold = true;
    private boolean showPeakLabels = true;
    private float dbTop = 0f, dbBottom = -120f;
    private String unit = "dBFS";
    private float a4 = 440f;
    private AudioEngine.Frame frame;
    private Callbacks callbacks;

    // Visible part of the frequency axis, as fractions (0..1) of the full axis.
    private float u0 = 0f, u1 = 1f;
    private float lo = 20f, hi = 20000f;
    private int lastSampleRate = 48000;

    private float[] refDb;
    private float refBinHz;

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF plot = new RectF();
    private float[] cols = new float[1];
    private float[] peakCols = new float[1];
    private float[] barVals = new float[1];
    private float[] barPeaks = new float[1];
    private LinearGradient fillGradient;
    private Palettes.Palette gradientPalette;

    private Bitmap wfBitmap;
    private int[] wfRow;
    private int wfHead;
    private int wfRows;
    private long lastFrameIndex = -1;
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private final float[] rtaDb = new float[RTA_FREQS.length];
    private final float[] rtaPeak = new float[RTA_FREQS.length];

    private float cursorX = -1;
    private boolean cursorRemovePending;
    private float scopeScale = 1f;
    private float tunerCents;
    private float lastFocusX;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        grid.setStrokeWidth(1);
        text.setColor(LABEL);
        text.setTextSize(10 * dp);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setStrokeWidth(1.4f * dp);
        dashed.setColor(REFERENCE);
        dashed.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));
        badge.setColor(Color.argb(215, 15, 23, 42));
        Arrays.fill(rtaPeak, -200f);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                lastFocusX = d.getFocusX();
                return zoomable();
            }

            @Override public boolean onScale(ScaleGestureDetector d) {
                float spanPrev = Math.max(1f, d.getPreviousSpanX());
                float factor = Math.abs(d.getCurrentSpanX()) > 10 * dp ? d.getCurrentSpanX() / spanPrev : 1f;
                zoom(d.getFocusX(), factor);
                pan(d.getFocusX() - lastFocusX);
                lastFocusX = d.getFocusX();
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onDoubleTap(MotionEvent e) {
                resetZoom();
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (scaleDetector.isInProgress()) return;
                cursorX = -1;
                if (callbacks != null) callbacks.onToggleFullscreen();
            }
        });
    }

    // ---------------------------------------------------------------- configuration

    public void setCallbacks(Callbacks c) { callbacks = c; }

    public void setMode(Mode m) {
        mode = m;
        cursorX = -1;
        if (m == Mode.WATERFALL) clearWaterfall();
        invalidate();
    }

    public void setPalette(Palettes.Palette p) { palette = p; invalidate(); }

    public void setLogScale(boolean log) {
        logScale = log;
        u0 = 0f;
        u1 = 1f;
        updateRange();
        clearWaterfall();
        invalidate();
    }

    public void setShowPeakHold(boolean show) { showPeakHold = show; invalidate(); }

    public void setShowPeakLabels(boolean show) { showPeakLabels = show; invalidate(); }

    public void setDbRange(float top, float bottom) {
        dbTop = top;
        dbBottom = bottom;
        invalidate();
    }

    public void setUnit(String u) { unit = u; invalidate(); }

    public void setA4(float hz) { a4 = hz; invalidate(); }

    public void resetPeaks() { Arrays.fill(rtaPeak, -200f); }

    /** Stores the current spectrum as a reference trace, or clears it. @return true if now set. */
    public boolean toggleReference() {
        AudioEngine.Frame f = frame;
        if (refDb != null || f == null) {
            refDb = null;
            invalidate();
            return false;
        }
        refDb = f.spectrumDb.clone();
        refBinHz = f.binHz;
        invalidate();
        return true;
    }

    public void setFrame(AudioEngine.Frame f) {
        frame = f;
        if (f.sampleRate != lastSampleRate) {
            lastSampleRate = f.sampleRate;
            updateRange();
            clearWaterfall();
        }
        if (mode == Mode.WATERFALL) {
            long missed = lastFrameIndex < 0 ? 1 : f.index - lastFrameIndex;
            pushWaterfallRow(f, (int) Math.max(1, Math.min(16, missed)));
        }
        lastFrameIndex = f.index;
        if (mode == Mode.RTA) computeRta(f);
        if (mode == Mode.TUNER && f.peakFreqHz > 25) {
            float[] cents = new float[1];
            SpectrumMath.noteName(f.peakFreqHz, a4, cents);
            // Reset when the note changes so the needle does not sweep across the dial.
            tunerCents = Math.abs(cents[0] - tunerCents) > 30 ? cents[0] : tunerCents * 0.5f + cents[0] * 0.5f;
        }
        invalidate();
    }

    public Bitmap snapshot() {
        Bitmap b = Bitmap.createBitmap(Math.max(1, getWidth()), Math.max(1, getHeight()), Bitmap.Config.ARGB_8888);
        draw(new Canvas(b));
        return b;
    }

    // ---------------------------------------------------------------- zoom

    private boolean zoomable() {
        return mode == Mode.LINE || mode == Mode.BARS || mode == Mode.WATERFALL;
    }

    private float fullLo() { return logScale ? SpectrumMath.LOG_F_MIN : 0f; }

    private float fullHi() { return Math.min(24000f, lastSampleRate / 2f); }

    private void updateRange() {
        lo = SpectrumMath.freqAt(u0, logScale, fullLo(), fullHi());
        hi = SpectrumMath.freqAt(u1, logScale, fullLo(), fullHi());
    }

    private void zoom(float focusX, float factor) {
        if (factor <= 0 || plot.width() <= 0) return;
        float span = u1 - u0;
        float t = (focusX - plot.left) / plot.width();
        float center = u0 + t * span;
        float minSpan = logScale ? 0.02f : 0.004f;
        float newSpan = Math.max(minSpan, Math.min(1f, span / factor));
        u0 = center - t * newSpan;
        u1 = u0 + newSpan;
        clampWindow();
    }

    private void pan(float dx) {
        if (plot.width() <= 0) return;
        float du = -dx / plot.width() * (u1 - u0);
        u0 += du;
        u1 += du;
        clampWindow();
    }

    private void clampWindow() {
        float span = u1 - u0;
        if (u0 < 0) { u0 = 0; u1 = span; }
        if (u1 > 1) { u1 = 1; u0 = 1 - span; }
        updateRange();
        if (mode == Mode.WATERFALL) clearWaterfall();
        invalidate();
    }

    private void resetZoom() {
        u0 = 0;
        u1 = 1;
        clampWindow();
    }

    // ---------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);
        if (!zoomable()) return true;
        if (e.getPointerCount() > 1 || scaleDetector.isInProgress()) {
            if (cursorRemovePending) cursorRemovePending = false;
            return true;
        }
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (cursorX >= 0 && Math.abs(x - cursorX) < 24 * dp) cursorRemovePending = true;
                else cursorX = x;
                break;
            case MotionEvent.ACTION_MOVE:
                if (cursorRemovePending && cursorX >= 0 && Math.abs(x - cursorX) > 8 * dp) cursorRemovePending = false;
                if (!cursorRemovePending) cursorX = x;
                break;
            case MotionEvent.ACTION_UP:
                if (cursorRemovePending) cursorX = -1;
                cursorRemovePending = false;
                break;
        }
        invalidate();
        return true;
    }

    // ---------------------------------------------------------------- layout & drawing

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        plot.set(0, 0, w, h - 18 * dp);
        cols = new float[Math.max(1, w)];
        peakCols = new float[Math.max(1, w)];
        fillGradient = null;
        updateRange();
        clearWaterfall();
    }

    private void clearWaterfall() {
        int w = (int) plot.width(), h = (int) plot.height();
        if (w <= 0 || h <= 0) return;
        if (wfBitmap == null || wfBitmap.getWidth() != w || wfBitmap.getHeight() != h) {
            if (wfBitmap != null) wfBitmap.recycle();
            wfBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            wfRow = new int[w];
        }
        wfBitmap.eraseColor(Color.BLACK);
        wfHead = 0;
        wfRows = 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(BG);
        AudioEngine.Frame f = frame;
        switch (mode) {
            case LINE: drawFreqGrid(canvas, true); if (f != null) drawLine(canvas, f); drawDbLabels(canvas); break;
            case BARS: drawFreqGrid(canvas, true); if (f != null) drawBars(canvas, f); drawDbLabels(canvas); break;
            case RTA: drawRta(canvas, f); break;
            case WATERFALL: drawWaterfall(canvas); break;
            case OSCILLOSCOPE: drawScope(canvas, f); break;
            case TUNER: drawTuner(canvas, f); break;
        }
        if (f != null && cursorX >= 0 && zoomable()) drawCursor(canvas, f);
        if (zoomable() && (u0 > 0 || u1 < 1)) {
            String z = "Масштаб " + SpectrumMath.tickLabel(Math.round(lo)) + "–" + SpectrumMath.tickLabel(Math.round(hi)) + " Hz";
            float zw = text.measureText(z);
            canvas.drawRect(getWidth() - zw - 8 * dp, plot.top + 2 * dp, getWidth() - 2 * dp, plot.top + 16 * dp, badge);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(Color.parseColor("#00F0FF"));
            canvas.drawText(z, getWidth() - 5 * dp, plot.top + 12 * dp, text);
            text.setColor(LABEL);
            text.setTextAlign(Paint.Align.LEFT);
        }
        if (f == null) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(14 * dp);
            canvas.drawText("Натисніть «Старт»", getWidth() / 2f, plot.centerY(), text);
            text.setTextSize(10 * dp);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private float xOf(float freq) {
        return plot.left + SpectrumMath.posOf(freq, logScale, lo, hi) * plot.width();
    }

    private float yOf(float db) {
        float t = (dbTop - db) / (dbTop - dbBottom);
        return plot.top + Math.max(0, Math.min(1, t)) * plot.height();
    }

    private float level(float db) {
        return Math.max(0f, Math.min(1f, (db - dbBottom) / (dbTop - dbBottom)));
    }

    private int dbStep() {
        float span = dbTop - dbBottom;
        return span > 100 ? 20 : span > 50 ? 10 : 5;
    }

    private void drawDbGrid(Canvas c) {
        int step = dbStep();
        grid.setColor(GRID);
        for (int db = (int) Math.floor(dbTop / step) * step; db > dbBottom; db -= step) {
            float y = yOf(db);
            if (y >= plot.top + 2) c.drawLine(plot.left, y, plot.right, y, grid);
        }
    }

    /** dB labels, drawn after the data so bars never hide them. */
    private void drawDbLabels(Canvas c) {
        int step = dbStep();
        text.setTextAlign(Paint.Align.LEFT);
        boolean first = true;
        for (int db = (int) Math.floor(dbTop / step) * step; db > dbBottom; db -= step) {
            float y = yOf(db);
            if (y < plot.top + 2) continue;
            String s = first ? db + " " + unit : String.valueOf(db);
            first = false;
            float tw = text.measureText(s);
            c.drawRect(plot.left + 1 * dp, y + 1 * dp, plot.left + tw + 5 * dp, y + 14 * dp, badge);
            c.drawText(s, plot.left + 3 * dp, y + 11 * dp, text);
        }
    }

    private void drawFreqGrid(Canvas c, boolean dbLines) {
        if (dbLines) drawDbGrid(c);
        if (logScale) {
            grid.setColor(GRID_MINOR);
            for (float t : SpectrumMath.minorTicks(lo, hi)) {
                float x = xOf(t);
                c.drawLine(x, plot.top, x, plot.bottom, grid);
            }
        }
        grid.setColor(GRID);
        text.setTextAlign(Paint.Align.CENTER);
        float labelY = plot.bottom + 13 * dp;
        int maxLabels = Math.max(3, (int) (plot.width() / (38 * dp)));
        for (float t : SpectrumMath.majorTicks(logScale, lo, hi, maxLabels)) {
            float x = xOf(t);
            c.drawLine(x, plot.top, x, plot.bottom, grid);
            c.drawText(SpectrumMath.tickLabel(t), Math.max(9 * dp, Math.min(getWidth() - 9 * dp, x)), labelY, text);
        }
        c.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, grid);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void tracePath(float[] values, boolean closeToBottom) {
        path.reset();
        int n = values.length;
        if (closeToBottom) path.moveTo(plot.left, plot.bottom);
        for (int x = 0; x < n; x++) {
            float y = yOf(values[x]);
            if (x == 0 && !closeToBottom) path.moveTo(plot.left, y); else path.lineTo(plot.left + x, y);
        }
        if (closeToBottom) {
            path.lineTo(plot.left + n - 1, plot.bottom);
            path.close();
        }
    }

    private void drawLine(Canvas c, AudioEngine.Frame f) {
        SpectrumMath.columns(f.spectrumDb, f.binHz, logScale, lo, hi, cols);
        int[] lut = Palettes.lut(palette);
        if (fillGradient == null || gradientPalette != palette) {
            fillGradient = new LinearGradient(0, plot.top, 0, plot.bottom,
                    new int[]{withAlpha(lut[255], 210), withAlpha(lut[180], 140), withAlpha(lut[90], 60), withAlpha(lut[30], 10)},
                    null, Shader.TileMode.CLAMP);
            gradientPalette = palette;
        }

        tracePath(cols, true);
        fill.setStyle(Paint.Style.FILL);
        fill.setShader(fillGradient);
        c.drawPath(path, fill);
        fill.setShader(null);

        if (refDb != null) {
            if (peakCols.length != cols.length) peakCols = new float[cols.length];
            SpectrumMath.columns(refDb, refBinHz, logScale, lo, hi, peakCols);
            tracePath(peakCols, false);
            c.drawPath(path, dashed);
        }

        if (showPeakHold) {
            SpectrumMath.columns(f.peakHoldDb, f.binHz, logScale, lo, hi, peakCols);
            tracePath(peakCols, false);
            stroke.setColor(Color.argb(140, 255, 255, 255));
            stroke.setStrokeWidth(1f * dp);
            c.drawPath(path, stroke);
        }

        tracePath(cols, false);
        stroke.setColor(Palettes.accent(palette));
        stroke.setStrokeWidth(1.6f * dp);
        c.drawPath(path, stroke);

        drawPeakLabels(c, f);
    }

    private void drawBars(Canvas c, AudioEngine.Frame f) {
        float barW = 6 * dp, gap = 1.5f * dp;
        int bars = Math.max(8, (int) (plot.width() / barW));
        if (barVals.length != bars) {
            barVals = new float[bars];
            barPeaks = new float[bars];
        }
        SpectrumMath.columns(f.spectrumDb, f.binHz, logScale, lo, hi, barVals);
        SpectrumMath.columns(f.peakHoldDb, f.binHz, logScale, lo, hi, barPeaks);
        int[] lut = Palettes.lut(palette);
        float w = plot.width() / bars;
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < bars; i++) {
            float x0 = plot.left + i * w;
            fill.setColor(lut[(int) (level(barVals[i]) * 255)]);
            c.drawRect(x0, yOf(barVals[i]), x0 + w - gap, plot.bottom, fill);
            if (showPeakHold) {
                float py = yOf(barPeaks[i]);
                fill.setColor(Palettes.accent(palette));
                c.drawRect(x0, py - 2 * dp, x0 + w - gap, py, fill);
            }
        }
        if (refDb != null) {
            if (peakCols.length != cols.length) peakCols = new float[cols.length];
            SpectrumMath.columns(refDb, refBinHz, logScale, lo, hi, peakCols);
            tracePath(peakCols, false);
            c.drawPath(path, dashed);
        }
        drawPeakLabels(c, f);
    }

    /** Finds up to three strongest, well-separated local maxima in the visible range. */
    private int findPeaks(AudioEngine.Frame f, float[] freqs, float[] levels) {
        float[] db = f.spectrumDb;
        int i0 = Math.max(2, (int) Math.ceil(lo / f.binHz)), i1 = Math.min(db.length - 3, (int) (hi / f.binHz));
        int found = 0;
        float floor = dbBottom + 0.15f * (dbTop - dbBottom);
        for (int k = 0; k < freqs.length; k++) {
            int best = -1;
            for (int i = i0; i <= i1; i++) {
                if (db[i] <= floor || db[i] < db[i - 1] || db[i] < db[i + 1]) continue;
                if (best >= 0 && db[i] <= db[best]) continue;
                if (prominence(db, i) < 10) continue;
                float fi = i * f.binHz;
                boolean tooClose = false;
                for (int j = 0; j < found && !tooClose; j++) {
                    float px = xOf(freqs[j]), x = xOf(fi);
                    tooClose = Math.abs(px - x) < 40 * dp || Math.abs(fi - freqs[j]) < 3 * f.binHz;
                }
                if (!tooClose) best = i;
            }
            if (best < 0) break;
            if (found > 0 && db[best] < levels[0] - 40) break;
            float al = db[best - 1], be = db[best], ga = db[best + 1];
            float den = al - 2 * be + ga;
            float d = Math.abs(den) > 1e-6f ? Math.max(-0.5f, Math.min(0.5f, 0.5f * (al - ga) / den)) : 0;
            freqs[found] = (best + d) * f.binHz;
            levels[found] = be - 0.25f * (al - ga) * d;
            found++;
        }
        return found;
    }

    /** Height of bin i above the mean level of its +-1/6 octave neighbourhood (main lobe excluded). */
    private static float prominence(float[] db, int i) {
        int a = Math.max(1, (int) (i / 1.12f)), b = Math.min(db.length - 1, (int) Math.ceil(i * 1.12f));
        double sum = 0;
        int n = 0;
        for (int j = a; j <= b; j++) {
            if (Math.abs(j - i) <= 4) continue;
            sum += db[j];
            n++;
        }
        if (n < 4) {
            a = Math.max(1, i - 16);
            b = Math.min(db.length - 1, i + 16);
            for (int j = a; j <= b; j++) {
                if (Math.abs(j - i) <= 4) continue;
                sum += db[j];
                n++;
            }
        }
        return n == 0 ? 0 : db[i] - (float) (sum / n);
    }

    private final float[] peakFreqs = new float[3];
    private final float[] peakLevels = new float[3];

    private void drawPeakLabels(Canvas c, AudioEngine.Frame f) {
        if (!showPeakLabels) return;
        int n = findPeaks(f, peakFreqs, peakLevels);
        text.setTextSize(11 * dp);
        for (int k = n - 1; k >= 0; k--) {
            float x = xOf(peakFreqs[k]), y = yOf(peakLevels[k]);
            int col = PEAK_COLORS[k];
            fill.setColor(col);
            c.drawCircle(x, y, 3 * dp, fill);
            String s = SpectrumMath.formatHz(peakFreqs[k]) + String.format(Locale.US, "  %.1f", peakLevels[k]);
            float tw = text.measureText(s);
            boolean right = x + tw + 12 * dp < getWidth();
            float bx = right ? x + 6 * dp : x - 6 * dp - tw;
            float by = Math.max(plot.top + 14 * dp + k * 16 * dp, y - 8 * dp);
            c.drawRect(bx - 3 * dp, by - 11 * dp, bx + tw + 3 * dp, by + 4 * dp, badge);
            text.setColor(col);
            c.drawText(s, bx, by, text);
        }
        text.setColor(LABEL);
        text.setTextSize(10 * dp);
    }

    private void drawCursor(Canvas c, AudioEngine.Frame f) {
        float t = Math.max(0, Math.min(1, (cursorX - plot.left) / plot.width()));
        float freq = SpectrumMath.freqAt(t, logScale, lo, hi);
        float db = SpectrumMath.valueAtBin(f.spectrumDb, freq / f.binHz);
        stroke.setColor(CURSOR);
        stroke.setStrokeWidth(1.2f * dp);
        c.drawLine(cursorX, plot.top, cursorX, plot.bottom, stroke);
        String s = SpectrumMath.formatHz(freq);
        if (mode != Mode.WATERFALL) {
            s += String.format(Locale.US, "  %.1f %s", db, unit);
            float[] cents = new float[1];
            if (freq > 25) s += "  " + SpectrumMath.noteName(freq, a4, cents);
        }
        text.setTextSize(12 * dp);
        float tw = text.measureText(s);
        float bx = Math.min(getWidth() - tw - 8 * dp, Math.max(4 * dp, cursorX + 6 * dp));
        float by = plot.bottom - 10 * dp;
        c.drawRect(bx - 4 * dp, by - 13 * dp, bx + tw + 4 * dp, by + 5 * dp, badge);
        text.setColor(CURSOR);
        c.drawText(s, bx, by, text);
        text.setColor(LABEL);
        text.setTextSize(10 * dp);
    }

    private void computeRta(AudioEngine.Frame f) {
        float decay = 0.4f;
        for (int b = 0; b < RTA_FREQS.length; b++) {
            float fc = RTA_FREQS[b];
            float bandLo = fc * 0.8909f, bandHi = fc * 1.1225f; // fc * 2^(-1/6), fc * 2^(1/6)
            int i0 = (int) Math.ceil(bandLo / f.binHz), i1 = (int) Math.floor(bandHi / f.binHz);
            double p = 0;
            if (i1 >= i0 && i1 < f.spectrumDb.length) {
                for (int i = i0; i <= i1; i++) p += Math.pow(10, f.spectrumDb[i] / 10.0);
                p /= f.enbw;
            } else {
                p = Math.pow(10, SpectrumMath.valueAtBin(f.spectrumDb, fc / f.binHz) / 10.0);
            }
            float db = p > 0 ? (float) (10 * Math.log10(p)) : -200f;
            rtaDb[b] = db;
            rtaPeak[b] = db >= rtaPeak[b] ? db : rtaPeak[b] - decay;
        }
    }

    private void drawRta(Canvas c, AudioEngine.Frame f) {
        drawDbGrid(c);
        c.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, grid);
        int bands = RTA_FREQS.length;
        float w = plot.width() / bands;
        int[] lut = Palettes.lut(palette);
        text.setTextAlign(Paint.Align.CENTER);
        for (int b = 0; b < bands; b++) {
            float x0 = plot.left + b * w;
            if (f != null) {
                fill.setColor(lut[(int) (level(rtaDb[b]) * 255)]);
                c.drawRect(x0 + 1.5f * dp, yOf(rtaDb[b]), x0 + w - 1.5f * dp, plot.bottom, fill);
                if (showPeakHold) {
                    float py = yOf(rtaPeak[b]);
                    fill.setColor(Palettes.accent(palette));
                    c.drawRect(x0 + 1.5f * dp, py - 2 * dp, x0 + w - 1.5f * dp, py, fill);
                }
            }
            if (b % 3 == 1) c.drawText(SpectrumMath.tickLabel(RTA_FREQS[b]), x0 + w / 2, plot.bottom + 13 * dp, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        drawDbLabels(c);
    }

    private void pushWaterfallRow(AudioEngine.Frame f, int repeat) {
        if (wfBitmap == null) return;
        int w = wfBitmap.getWidth(), h = wfBitmap.getHeight();
        if (cols.length != w) cols = new float[w];
        SpectrumMath.columns(f.spectrumDb, f.binHz, logScale, lo, hi, cols);
        int[] lut = Palettes.lut(palette);
        for (int x = 0; x < w; x++) wfRow[x] = lut[(int) (level(cols[x]) * 255)];
        for (int r = 0; r < repeat; r++) {
            wfHead = (wfHead - 1 + h) % h;
            wfBitmap.setPixels(wfRow, 0, w, 0, wfHead, w, 1);
            if (wfRows < h) wfRows++;
        }
    }

    private void drawWaterfall(Canvas c) {
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
        drawFreqGrid(c, false);
        AudioEngine.Frame f = frame;
        if (f != null) {
            float secPerRow = 1024f / f.sampleRate;
            float secs = plot.height() * secPerRow;
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(String.format(Locale.US, "↓ %.0f с", secs), plot.left + 4 * dp, plot.bottom - 4 * dp, text);
        }
    }

    private void drawScope(Canvas c, AudioEngine.Frame f) {
        float cy = plot.centerY();
        grid.setColor(GRID);
        c.drawLine(0, cy, getWidth(), cy, grid);
        for (int i = 1; i < 10; i++) {
            float x = getWidth() * i / 10f;
            c.drawLine(x, plot.top, x, plot.bottom, grid);
        }
        for (int i = 1; i < 4; i++) {
            float y = plot.top + plot.height() * i / 4f;
            c.drawLine(0, y, getWidth(), y, grid);
        }
        if (f == null) return;
        float[] t = f.time;
        int len = t.length;
        int show = Math.min(len / 2, Math.max(256, f.sampleRate / 50));
        int start = 0;
        for (int i = 1; i < len - show; i++) {
            if (t[i - 1] < 0 && t[i] >= 0) { start = i; break; }
        }
        float max = 1e-5f;
        for (int i = start; i < start + show; i++) max = Math.max(max, Math.abs(t[i]));
        float target = Math.min(10000f, 0.9f / max);
        scopeScale = target < scopeScale ? target : scopeScale * 0.9f + target * 0.1f;
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
        float peakDbfs = (float) (20 * Math.log10(max));
        c.drawText(String.format(Locale.US, "%.1f мс/екран   пік %.1f dBFS   ×%.0f", ms, peakDbfs, scopeScale),
                4 * dp, plot.bottom + 13 * dp, text);
    }

    private void drawTuner(Canvas c, AudioEngine.Frame f) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h * 0.82f;
        float r = Math.min(w * 0.42f, h * 0.66f);
        RectF arc = new RectF(cx - r, cy - r, cx + r, cy + r);
        stroke.setStrokeWidth(10 * dp);
        stroke.setColor(GRID);
        c.drawArc(arc, 200, 140, false, stroke);
        stroke.setColor(Color.parseColor("#39FF14"));
        c.drawArc(arc, 267, 6, false, stroke);
        stroke.setStrokeWidth(1.5f * dp);
        stroke.setColor(LABEL);
        text.setTextAlign(Paint.Align.CENTER);
        for (int k = -50; k <= 50; k += 10) {
            double a = Math.toRadians(270 + k * 1.4);
            float r0 = r - 12 * dp, r1 = r - (k % 50 == 0 ? 22 : 17) * dp;
            c.drawLine(cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0,
                    cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1, stroke);
            if (k % 25 == 0 || k % 50 == 0) {
                float rl = r - 34 * dp;
                c.drawText((k > 0 ? "+" : "") + k, cx + (float) Math.cos(a) * rl, cy + (float) Math.sin(a) * rl + 4 * dp, text);
            }
        }

        boolean valid = f != null && f.peakDb - f.calibrationDb > -75 && f.peakFreqHz > 25;
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(Math.min(56 * dp, r * 0.42f));
        if (!valid) {
            text.setColor(LABEL);
            c.drawText("—", cx, cy - r * 0.36f, text);
        } else {
            float[] cents = new float[1];
            String note = SpectrumMath.noteName(f.peakFreqHz, a4, cents);
            boolean inTune = Math.abs(tunerCents) <= 5;
            int col = inTune ? Color.parseColor("#39FF14") : Math.abs(tunerCents) <= 15 ? Color.parseColor("#FFE600") : CURSOR;
            text.setColor(col);
            c.drawText(note, cx, cy - r * 0.36f, text);
            double a = Math.toRadians(270 + Math.max(-50, Math.min(50, tunerCents)) * 1.4);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            stroke.setColor(col);
            stroke.setStrokeWidth(4 * dp);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx + ca * r * 0.7f, cy + sa * r * 0.7f, cx + ca * (r + 8 * dp), cy + sa * (r + 8 * dp), stroke);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(14 * dp);
            text.setColor(Color.WHITE);
            c.drawText(String.format(Locale.US, "%.2f Hz   %+.0f ¢", f.peakFreqHz, tunerCents), cx, cy - r * 0.14f, text);
        }
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(10 * dp);
        text.setColor(LABEL);
        c.drawText(String.format(Locale.US, "A4 = %.0f Hz", a4), cx, h - 6 * dp, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
