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
    public enum Mode { LINE, BARS, RTA, WATERFALL, OSCILLOSCOPE, TUNER, FOURIER, PITCH, CHROMA, INTERVALS, AIR, ECHO, SAMPLING, SONAR, LESSON }

    public interface Callbacks {
        void onToggleFullscreen();

        /** Tape mode: the user tapped a moment on the tape spectrogram. */
        void onSeek(int samplePosition);
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

    // Tape ("time machine") mode.
    private AudioEngine.Tape tape;
    private int tapeFft = 4096;
    private AudioEngine.WindowType tapeWindow = AudioEngine.WindowType.HANN;
    private float tapeOffsetDb;
    private final TapeSpectrogram tapeRenderer = new TapeSpectrogram();
    private Bitmap tapeBitmap;
    private TapeSpectrogram.Params tapeParams;
    private boolean tapeRendering;

    private float cursorX = -1;
    private boolean cursorRemovePending;
    private float scopeScale = 1f;
    private float tunerCents;

    // Learning mode: plain-language description and Fourier epicycles.
    private boolean showHints;
    private SoundExplainer.Result explanation;
    private static final int EPI_K = 12;
    private final float[] epiRe = new float[EPI_K + 1], epiIm = new float[EPI_K + 1];
    private float epiF0;
    private boolean epiValid;
    private final long animStart = System.currentTimeMillis();
    private LessonRenderer lesson;
    private SonarRenderer sonar;
    private LessonView lessonView;
    private static final int[] HARM_COLORS = {
            0xFF00F0FF, 0xFFFFE600, 0xFF39FF14, 0xFFFF8800, 0xFFFF007F, 0xFFB388FF,
            0xFF64FFDA, 0xFFFF5252, 0xFF82B1FF, 0xFFFFD180, 0xFFCCFF90, 0xFFEA80FC};
    private float lastFocusX;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        lesson = new LessonRenderer(dp);
        sonar = new SonarRenderer(dp);
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
        requestTapeImage();
        invalidate();
    }

    public void setPalette(Palettes.Palette p) { palette = p; requestTapeImage(); invalidate(); }

    /** Enters (non-null) or leaves (null) tape mode. */
    public void setTape(AudioEngine.Tape t) {
        tape = t;
        if (t == null) {
            tapeBitmap = null;
            tapeParams = null;
        }
        if (t != null && t.rate != lastSampleRate) {
            lastSampleRate = t.rate;
            updateRange();
        }
        clearWaterfall();
        requestTapeImage();
        invalidate();
    }

    /** Analysis settings for the tape spectrogram; offsetDb = input gain + calibration. */
    public void setTapeAnalysis(int fftSize, AudioEngine.WindowType window, float offsetDb) {
        tapeFft = Math.min(fftSize, 8192);
        tapeWindow = window;
        tapeOffsetDb = offsetDb;
        requestTapeImage();
    }

    private void requestTapeImage() {
        if (tape == null || mode != Mode.WATERFALL || plot.width() <= 0 || plot.height() <= 0) return;
        TapeSpectrogram.Params p = new TapeSpectrogram.Params();
        p.tape = tape;
        p.width = (int) plot.width();
        p.height = (int) plot.height();
        p.log = logScale;
        p.lo = lo;
        p.hi = hi;
        p.fftSize = tapeFft;
        p.window = tapeWindow;
        p.offsetDb = tapeOffsetDb;
        p.dbTop = dbTop;
        p.dbBottom = dbBottom;
        p.palette = palette;
        if (p.sameAs(tapeParams)) return;
        tapeParams = p;
        tapeRendering = true;
        tapeRenderer.request(p, new TapeSpectrogram.Callback() {
            @Override public void onRendered(Bitmap b, TapeSpectrogram.Params params) {
                if (params != tapeParams) return;
                tapeBitmap = b;
                tapeRendering = false;
                invalidate();
            }
        });
        invalidate();
    }

    public void setLogScale(boolean log) {
        logScale = log;
        u0 = 0f;
        u1 = 1f;
        updateRange();
        clearWaterfall();
        requestTapeImage();
        invalidate();
    }

    public void setShowPeakHold(boolean show) { showPeakHold = show; invalidate(); }

    public void setShowPeakLabels(boolean show) { showPeakLabels = show; invalidate(); }

    public void setShowHints(boolean show) {
        showHints = show;
        if (!show) explanation = null;
        invalidate();
    }

    public SoundExplainer.Result getExplanation() { return explanation; }

    public void setSonarFrame(SonarProcessor.Frame f) {
        int histW = (int) (getWidth() - 34 * dp);
        int histH = (int) (getHeight() * 0.66f - 46 * dp);
        sonar.onFrame(f, palette, histW, histH);
        invalidate();
    }

    public void setSonarBand(String band) { sonar.setBand(band); }

    /** Shows an interactive lesson (Mode.LESSON). */
    public void showLesson(LessonView l) {
        lessonView = l;
        setMode(Mode.LESSON);
    }

    public void clearSonar() {
        sonar.clear();
        invalidate();
    }

    public void setDbRange(float top, float bottom) {
        dbTop = top;
        dbBottom = bottom;
        requestTapeImage();
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
        if (mode == Mode.WATERFALL && f.tape == null) {
            long missed = lastFrameIndex < 0 ? 1 : f.index - lastFrameIndex;
            pushWaterfallRow(f, (int) Math.max(1, Math.min(16, missed)));
        }
        lastFrameIndex = f.index;
        if (mode == Mode.RTA) computeRta(f);
        if (showHints || mode == Mode.FOURIER || mode == Mode.AIR) explanation = SoundExplainer.explain(f, a4);
        if (mode == Mode.FOURIER || mode == Mode.AIR) computeEpicycles(f);
        if (mode == Mode.LESSON && lessonView != null) lessonView.onFrame(f);
        if (mode == Mode.PITCH) lesson.pitchFrame(f);
        if (mode == Mode.CHROMA) lesson.chromaFrame(f, a4);
        if (mode == Mode.ECHO) lesson.echoFrame(f);
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
        requestTapeImage();
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
        if (mode == Mode.LESSON && lessonView != null) {
            boolean used = lessonView.onTouch(e, getWidth(), getHeight());
            invalidate();
            if (used) return true;
        }
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);
        if (!zoomable()) return true;
        if (e.getPointerCount() > 1 || scaleDetector.isInProgress()) {
            if (cursorRemovePending) cursorRemovePending = false;
            return true;
        }
        float x = e.getX();
        if (tape != null && mode == Mode.WATERFALL) {
            int a = e.getActionMasked();
            if ((a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) && e.getY() <= plot.bottom && callbacks != null) {
                float t = 1f - Math.max(0, Math.min(1, (e.getY() - plot.top) / plot.height()));
                callbacks.onSeek(Math.round(t * tape.pcm.length));
                cursorX = x;
                invalidate();
            }
            return true;
        }
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
        requestTapeImage();
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
            case LINE: drawFreqGrid(canvas, true); if (f != null) drawLine(canvas, f); drawDbLabels(canvas); drawHints(canvas, f); break;
            case BARS: drawFreqGrid(canvas, true); if (f != null) drawBars(canvas, f); drawDbLabels(canvas); drawHints(canvas, f); break;
            case RTA: drawRta(canvas, f); break;
            case WATERFALL: drawWaterfall(canvas); break;
            case OSCILLOSCOPE: drawScope(canvas, f); break;
            case TUNER: drawTuner(canvas, f); break;
            case FOURIER: drawFourier(canvas, f); break;
            case PITCH: lesson.drawPitch(canvas, getWidth(), getHeight(), a4); break;
            case CHROMA: lesson.drawChroma(canvas, getWidth(), getHeight()); break;
            case INTERVALS:
                lesson.drawIntervals(canvas, getWidth(), getHeight());
                postInvalidateOnAnimation();
                break;
            case AIR:
                lesson.drawAir(canvas, getWidth(), getHeight(), epiValid ? epiRe : null, epiIm, epiValid ? epiF0 : 0,
                        f != null ? f.rmsDb - f.calibrationDb : -120);
                postInvalidateOnAnimation();
                break;
            case ECHO: lesson.drawEcho(canvas, getWidth(), getHeight()); break;
            case SAMPLING: lesson.drawSampling(canvas, getWidth(), getHeight()); break;
            case SONAR: sonar.draw(canvas, getWidth(), getHeight()); break;
            case LESSON:
                if (lessonView != null) {
                    lessonView.draw(canvas, getWidth(), getHeight());
                    if (lessonView.animating()) postInvalidateOnAnimation();
                }
                break;
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
        if (f == null && mode != Mode.INTERVALS && mode != Mode.SAMPLING && mode != Mode.SONAR
                && mode != Mode.LESSON) {
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
        if (!showPeakLabels || showHints) return;
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
        int rows = repeat * Math.max(1, Math.round(dp * 0.7f));
        for (int r = 0; r < rows; r++) {
            wfHead = (wfHead - 1 + h) % h;
            wfBitmap.setPixels(wfRow, 0, w, 0, wfHead, w, 1);
            if (wfRows < h) wfRows++;
        }
    }

    private void drawTapeWaterfall(Canvas c) {
        if (tapeBitmap != null) {
            dst.set(plot.left, plot.top, plot.right, plot.bottom);
            c.drawBitmap(tapeBitmap, null, dst, null);
        }
        drawFreqGrid(c, false);
        float secs = tape.seconds();
        // Time labels on the left edge.
        double[] steps = {0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300};
        double step = steps[steps.length - 1];
        for (double st : steps) {
            if (secs / st <= plot.height() / (40 * dp)) { step = st; break; }
        }
        text.setTextAlign(Paint.Align.LEFT);
        grid.setColor(GRID);
        for (double t = 0; t <= secs + 1e-6; t += step) {
            float y = plot.top + (float) (1 - t / secs) * plot.height();
            c.drawLine(plot.left, y, plot.left + 6 * dp, y, grid);
            y = Math.max(plot.top + 7 * dp, Math.min(plot.bottom - 7 * dp, y));
            String s = formatTime((float) t);
            float tw = text.measureText(s);
            c.drawRect(plot.left + 7 * dp, y - 6 * dp, plot.left + tw + 11 * dp, y + 6 * dp, badge);
            c.drawText(s, plot.left + 9 * dp, y + 4 * dp, text);
        }
        AudioEngine.Frame f = frame;
        if (f != null && f.tape == tape) {
            float y = plot.top + (1 - (float) f.tapePosition / tape.pcm.length) * plot.height();
            stroke.setColor(Color.WHITE);
            stroke.setStrokeWidth(1.5f * dp);
            c.drawLine(plot.left, y, plot.right, y, stroke);
            String s = "▶ " + formatTime((float) f.tapePosition / tape.rate);
            text.setTextSize(12 * dp);
            float tw = text.measureText(s);
            float by = Math.max(plot.top + 14 * dp, Math.min(plot.bottom - 4 * dp, y - 4 * dp));
            c.drawRect(plot.right - tw - 10 * dp, by - 12 * dp, plot.right - 2 * dp, by + 3 * dp, badge);
            text.setColor(Color.WHITE);
            c.drawText(s, plot.right - tw - 6 * dp, by, text);
            text.setColor(LABEL);
            text.setTextSize(10 * dp);
        }
        if (tapeRendering) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(13 * dp);
            text.setColor(Color.parseColor("#00F0FF"));
            c.drawText("Будую спектрограму…", plot.centerX(), plot.centerY(), text);
            text.setColor(LABEL);
            text.setTextSize(10 * dp);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    static String formatTime(float seconds) {
        int m = (int) (seconds / 60);
        float sec = seconds - m * 60;
        return String.format(Locale.US, "%d:%04.1f", m, sec);
    }

    private void drawWaterfall(Canvas c) {
        if (tape != null) {
            drawTapeWaterfall(c);
            return;
        }
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
            float secPerRow = 1024f / f.sampleRate / Math.max(1, Math.round(dp * 0.7f));
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

    // ---------------------------------------------------------------- learning mode

    /** Harmonic markers on the spectrum plus a card that explains the sound in words. */
    private void drawHints(Canvas c, AudioEngine.Frame f) {
        SoundExplainer.Result r = explanation;
        if (!showHints || f == null || r == null) return;
        if (r.f0 > 0 && (r.kind == SoundExplainer.Kind.HARMONIC || r.kind == SoundExplainer.Kind.PURE_TONE)) {
            text.setTextSize(10 * dp);
            text.setTextAlign(Paint.Align.CENTER);
            for (int k = 1; k <= SoundExplainer.MAX_HARMONICS; k++) {
                float v = r.harmonicDb[k - 1];
                if (Float.isNaN(v)) continue;
                float hz = r.harmonicHz[k - 1];
                if (hz < lo || hz > hi) continue;
                float x = xOf(hz), y = yOf(v);
                int col = HARM_COLORS[(k - 1) % HARM_COLORS.length];
                fill.setColor(col);
                path.reset();
                path.moveTo(x, y - 4 * dp);
                path.lineTo(x - 4 * dp, y - 11 * dp);
                path.lineTo(x + 4 * dp, y - 11 * dp);
                path.close();
                c.drawPath(path, fill);
                text.setColor(col);
                c.drawText(k == 1 ? "f0" : k + "×", x, y - 14 * dp, text);
            }
            text.setTextAlign(Paint.Align.LEFT);
        }
        drawCard(c, r, plot.bottom - 6 * dp, true);
    }

    /** Draws the explanation card; anchorBottom = y of its bottom (or top if !fromBottom). */
    private void drawCard(Canvas c, SoundExplainer.Result r, float anchor, boolean fromBottom) {
        float pad = 8 * dp, maxW = Math.min(getWidth() - 16 * dp, 520 * dp);
        Paint title = new Paint(text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(13 * dp);
        title.setColor(Color.parseColor("#00F0FF"));
        text.setTextSize(11.5f * dp);
        java.util.List<String> rows = new java.util.ArrayList<>();
        for (String line : r.lines) rows.addAll(wrap(line, text, maxW - 2 * pad));
        float lineH = 15 * dp, titleH = 18 * dp;
        float h = pad * 2 + titleH + rows.size() * lineH;
        float top = fromBottom ? anchor - h : anchor;
        float left = 8 * dp;
        fill.setColor(Color.argb(225, 10, 16, 30));
        c.drawRoundRect(new RectF(left, top, left + maxW, top + h), 8 * dp, 8 * dp, fill);
        stroke.setColor(Color.argb(120, 0, 240, 255));
        stroke.setStrokeWidth(1 * dp);
        c.drawRoundRect(new RectF(left, top, left + maxW, top + h), 8 * dp, 8 * dp, stroke);
        c.drawText(r.title, left + pad, top + pad + 13 * dp, title);
        text.setColor(Color.parseColor("#CBD5E1"));
        float y = top + pad + titleH + 11 * dp;
        for (String row : rows) {
            c.drawText(row, left + pad, y, text);
            y += lineH;
        }
        text.setColor(LABEL);
        text.setTextSize(10 * dp);
    }

    private static java.util.List<String> wrap(String s, Paint p, float width) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String cand = line.length() == 0 ? word : line + " " + word;
            if (p.measureText(cand) > width && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(cand);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** Fourier series of the latest full period of the waveform, smoothed between frames. */
    private void computeEpicycles(AudioEngine.Frame f) {
        SoundExplainer.Result r = explanation;
        float f0 = r != null && r.f0 > 0 ? r.f0 : 0;
        float[] t = f.time;
        float period = f0 > 0 ? f.sampleRate / f0 : 0;
        if (f0 <= 0 || period * 2 > t.length || period < 4) {
            epiValid = false;
            return;
        }
        // Rising zero crossing that leaves one whole period after it.
        int start = -1;
        for (int i = (int) (t.length - period - 1); i > 0; i--) {
            if (t[i - 1] < 0 && t[i] >= 0) { start = i; break; }
        }
        if (start < 0) { epiValid = false; return; }
        final int m = 256;
        float[] x = new float[m];
        float peak = 1e-6f;
        for (int j = 0; j < m; j++) {
            float pos = start + j * period / m;
            int i0 = (int) pos;
            float fr = pos - i0;
            x[j] = t[i0] + (t[Math.min(t.length - 1, i0 + 1)] - t[i0]) * fr;
            peak = Math.max(peak, Math.abs(x[j]));
        }
        float a = Math.abs(f0 - epiF0) / Math.max(1, f0) > 0.03f || !epiValid ? 0f : 0.6f;
        for (int k = 1; k <= EPI_K; k++) {
            double re = 0, im = 0;
            for (int j = 0; j < m; j++) {
                double ang = 2 * Math.PI * k * j / m;
                re += x[j] * Math.cos(ang);
                im -= x[j] * Math.sin(ang);
            }
            float cr = (float) (2 * re / m / peak), ci = (float) (2 * im / m / peak);
            epiRe[k] = epiRe[k] * a + cr * (1 - a);
            epiIm[k] = epiIm[k] * a + ci * (1 - a);
        }
        epiF0 = f0;
        epiValid = true;
    }

    private void drawFourier(Canvas c, AudioEngine.Frame f) {
        float w = getWidth(), h = getHeight();
        text.setTextAlign(Paint.Align.LEFT);
        if (!epiValid) {
            grid.setColor(GRID);
            c.drawLine(0, h / 2, w, h / 2, grid);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(14 * dp);
            text.setColor(Color.WHITE);
            c.drawText("Кола працюють для звуків з висотою", w / 2, h * 0.42f, text);
            text.setTextSize(12 * dp);
            text.setColor(LABEL);
            c.drawText("Свисніть, заспівайте «а-а-а» або увімкніть генератор", w / 2, h * 0.42f + 22 * dp, text);
            text.setTextSize(10 * dp);
            text.setTextAlign(Paint.Align.LEFT);
            if (f != null) postInvalidateDelayed(100);
            return;
        }
        float cy = h * 0.42f;
        float cx = w * 0.24f;
        float waveX = w * 0.5f;
        double sum = 0;
        for (int k = 1; k <= EPI_K; k++) sum += Math.hypot(epiRe[k], epiIm[k]);
        float scale = (float) (Math.min(h * 0.34f, w * 0.22f) / Math.max(0.3, sum));
        // Slow, steady rotation so the eye can follow it (independent of the real pitch).
        double theta = 2 * Math.PI * ((System.currentTimeMillis() - animStart) / 1000.0 * 0.3);

        grid.setColor(GRID);
        c.drawLine(waveX, cy, w, cy, grid);
        float px = cx, py = cy;
        for (int k = 1; k <= EPI_K; k++) {
            float amp = (float) Math.hypot(epiRe[k], epiIm[k]) * scale;
            if (amp < 0.6f * dp) continue;
            double ang = k * theta + Math.atan2(epiIm[k], epiRe[k]);
            float nx = px - (float) Math.sin(ang) * amp;
            float ny = py - (float) Math.cos(ang) * amp;
            int col = HARM_COLORS[(k - 1) % HARM_COLORS.length];
            stroke.setColor(withAlpha(col, 90));
            stroke.setStrokeWidth(1 * dp);
            c.drawCircle(px, py, amp, stroke);
            stroke.setColor(col);
            stroke.setStrokeWidth(2 * dp);
            c.drawLine(px, py, nx, ny, stroke);
            px = nx;
            py = ny;
        }
        // Tip -> connector -> waveform drawn from the same sum, scrolling to the right.
        stroke.setColor(Color.argb(160, 255, 255, 255));
        stroke.setStrokeWidth(1 * dp);
        c.drawLine(px, py, waveX, py, stroke);
        fill.setColor(Color.WHITE);
        c.drawCircle(px, py, 3.5f * dp, fill);
        c.drawCircle(waveX, py, 3.5f * dp, fill);
        path.reset();
        float span = w - waveX;
        for (int i = 0; i <= (int) span; i += 2) {
            double th = theta - (i / span) * 2 * Math.PI * 2; // two periods across
            float y = 0;
            for (int k = 1; k <= EPI_K; k++) {
                y += (float) Math.hypot(epiRe[k], epiIm[k]) * (float) Math.cos(k * th + Math.atan2(epiIm[k], epiRe[k]));
            }
            float yy = cy - y * scale;
            if (i == 0) path.moveTo(waveX, yy); else path.lineTo(waveX + i, yy);
        }
        stroke.setColor(Palettes.accent(palette));
        stroke.setStrokeWidth(2 * dp);
        c.drawPath(path, stroke);

        // Legend: which circle is which frequency.
        text.setTextSize(11 * dp);
        float ly = h * 0.80f;
        float colW = w / 4f;
        int shown = 0;
        for (int k = 1; k <= EPI_K && shown < 8; k++) {
            float rel = (float) (Math.hypot(epiRe[k], epiIm[k]) / Math.max(1e-6, Math.hypot(epiRe[1], epiIm[1])));
            if (rel < 0.02f && k > 1) continue;
            int col = HARM_COLORS[(k - 1) % HARM_COLORS.length];
            float lx = 8 * dp + (shown % 4) * colW, yy = ly + (shown / 4) * 16 * dp;
            fill.setColor(col);
            c.drawCircle(lx + 4 * dp, yy - 4 * dp, 4 * dp, fill);
            text.setColor(Color.parseColor("#CBD5E1"));
            c.drawText(String.format(Locale.US, "%d× %s", k, SpectrumMath.tickLabel(Math.round(k * epiF0))), lx + 11 * dp, yy, text);
            shown++;
        }
        text.setTextSize(12 * dp);
        text.setColor(Color.WHITE);
        c.drawText("Кожне коло — одна гармоніка (k × f0). Їх сума малює хвилю праворуч.", 8 * dp, 16 * dp, text);
        text.setTextSize(10 * dp);
        text.setColor(LABEL);
        c.drawText(String.format(Locale.US, "f0 = %s · обертання сповільнено для наочності", SpectrumMath.formatHz(epiF0)),
                8 * dp, 30 * dp, text);
        postInvalidateOnAnimation();
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
