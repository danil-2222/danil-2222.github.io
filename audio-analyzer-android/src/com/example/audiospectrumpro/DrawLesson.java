package com.example.audiospectrumpro;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;

/**
 * "Picture to sound": the user paints on a time x frequency grid; {@link #synthesize} turns it
 * into a sum of sine waves so the picture reappears on the spectrogram.
 */
final class DrawLesson implements LessonView {
    static final int COLS = 84;   // frequency
    static final int ROWS = 40;   // time
    static final float ROW_SECONDS = 0.15f;
    /** Frequencies sit on the default log axis (20 Hz - 24 kHz) where resolution is good: about 290 Hz .. 12.9 kHz. */
    static final float AXIS_FROM = 0.38f, AXIS_TO = 0.90f;

    private final LessonKit k;
    private final float[][] cells = new float[ROWS][COLS];
    boolean eraser;

    DrawLesson(float dp) { k = new LessonKit(dp); }

    static float columnHz(int col) {
        float t = AXIS_FROM + (AXIS_TO - AXIS_FROM) * col / (COLS - 1);
        return SpectrumMath.freqAt(t, true, SpectrumMath.LOG_F_MIN, 24000f);
    }

    void clear() {
        for (float[] r : cells) java.util.Arrays.fill(r, 0);
    }

    boolean isEmpty() {
        for (float[] r : cells) for (float v : r) if (v > 0) return false;
        return true;
    }

    /** Writes text across the grid (letters upright when shown on the spectrogram). */
    void writeText(String s) {
        clear();
        if (s.trim().isEmpty()) return;
        Bitmap b = Bitmap.createBitmap(COLS, ROWS, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        p.setLetterSpacing(0.18f);
        float size = ROWS * 0.8f;
        p.setTextSize(size);
        while (p.measureText(s) > COLS * 0.95f && size > 6) {
            size -= 0.5f;
            p.setTextSize(size);
        }
        // Long words get small; stretch them vertically so the letters still fill the canvas height.
        android.graphics.Rect bounds = new android.graphics.Rect();
        p.getTextBounds(s, 0, s.length(), bounds);
        float stretch = Math.max(1f, ROWS * 0.8f / Math.max(1, bounds.height()));
        c.translate(0, ROWS / 2f);
        c.scale(1, stretch);
        c.drawText(s, COLS / 2f, -bounds.exactCenterY(), p);
        for (int r = 0; r < ROWS; r++) {
            for (int col = 0; col < COLS; col++) {
                float a = Color.alpha(b.getPixel(col, r)) / 255f;
                cells[r][col] = a > 0.35f ? 1f : a > 0.15f ? 0.5f : 0f;
            }
        }
    }

    void smiley() {
        clear();
        for (int r = 0; r < ROWS; r++) {
            for (int col = 0; col < COLS; col++) {
                float x = (col - COLS / 2f) / (ROWS / 2f), y = (r - ROWS / 2f) / (ROWS / 2f);
                double d = Math.hypot(x, y);
                boolean ring = Math.abs(d - 0.85) < 0.08;
                boolean eyes = Math.hypot(x + 0.32, y + 0.25) < 0.12 || Math.hypot(x - 0.32, y + 0.25) < 0.12;
                boolean mouth = Math.abs(Math.hypot(x, y + 0.05) - 0.5) < 0.07 && y > 0.1;
                if (ring || eyes || mouth) cells[r][col] = 1f;
            }
        }
    }

    /**
     * Rows are played from the bottom of the grid to the top: the live spectrogram pushes older
     * rows down, so the picture ends up the right way up.
     */
    float[] synthesize(int rate) {
        int rowLen = Math.round(ROW_SECONDS * rate);
        float[] out = new float[rowLen * ROWS + rate / 2];
        double[] phase = new double[COLS];
        double[] w = new double[COLS];
        for (int col = 0; col < COLS; col++) w[col] = 2 * Math.PI * columnHz(col) / rate;
        for (int step = 0; step < ROWS; step++) {
            int row = ROWS - 1 - step;
            int prevRow = step == 0 ? -1 : row + 1;
            for (int col = 0; col < COLS; col++) {
                float a1 = cells[row][col], a0 = prevRow < 0 ? 0 : cells[prevRow][col];
                if (a0 == 0 && a1 == 0) { phase[col] += w[col] * rowLen; continue; }
                for (int i = 0; i < rowLen; i++) {
                    // Short crossfade between rows avoids clicks.
                    float t = Math.min(1f, i / (rowLen * 0.15f));
                    float a = a0 + (a1 - a0) * t;
                    out[step * rowLen + i] += (float) (a * Math.sin(phase[col] + w[col] * i));
                }
                phase[col] += w[col] * rowLen;
            }
        }
        // Fade out the last row.
        int end = rowLen * ROWS;
        for (int i = 0; i < rate / 20 && end + i < out.length; i++) out[end + i] = 0;
        LessonKit.normalize(out, 0.45f);
        return out;
    }

    private float gridLeft(int w) { return 40 * k.dp; }
    private float gridTop() { return 40 * k.dp; }

    @Override public void onFrame(AudioEngine.Frame f) {}

    @Override public boolean animating() { return false; }

    @Override
    public boolean onTouch(MotionEvent e, int w, int h) {
        int a = e.getActionMasked();
        if (a != MotionEvent.ACTION_DOWN && a != MotionEvent.ACTION_MOVE) return a == MotionEvent.ACTION_UP;
        float left = gridLeft(w), top = gridTop();
        float cw = (w - left - 8 * k.dp) / COLS, ch = (h - top - 26 * k.dp) / ROWS;
        int col = (int) ((e.getX() - left) / cw), row = (int) ((e.getY() - top) / ch);
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                int r = row + dr, cc = col + dc;
                if (r < 0 || r >= ROWS || cc < 0 || cc >= COLS) continue;
                boolean centre = dr == 0 && dc == 0;
                if (eraser) cells[r][cc] = 0;
                else if (centre) cells[r][cc] = 1f;
                else if (Math.abs(dr) + Math.abs(dc) == 1) cells[r][cc] = Math.max(cells[r][cc], 0.5f);
            }
        }
        return true;
    }

    @Override
    public void draw(Canvas c, int w, int h) {
        float left = gridLeft(w), top = gridTop();
        float right = w - 8 * k.dp, bottom = h - 26 * k.dp;
        float cw = (right - left) / COLS, ch = (bottom - top) / ROWS;
        k.label(c, eraser ? "Гумка: стирайте пальцем" : "Малюйте пальцем — це буде звук", 8 * k.dp, 18 * k.dp, 14,
                LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "вліво–вправо: частота (низька → висока) · знизу вгору: час", 8 * k.dp, 33 * k.dp, 10.5f,
                LessonKit.SOFT, Paint.Align.LEFT, false);
        k.rect(c, left, top, right, bottom, 0xFF0B1220);
        for (int r = 0; r < ROWS; r++) {
            for (int col = 0; col < COLS; col++) {
                float v = cells[r][col];
                if (v <= 0) continue;
                k.rect(c, left + col * cw + 0.5f, top + r * ch + 0.5f, left + (col + 1) * cw - 0.5f,
                        top + (r + 1) * ch - 0.5f, v >= 1 ? LessonKit.CYAN : 0xFF007C88);
            }
        }
        for (int col = 0; col <= COLS; col += 12) {
            float x = left + col * cw;
            k.line(c, x, top, x, bottom, LessonKit.GRID, 1);
            if (col < COLS) {
                float hz = columnHz(col);
                k.label(c, hz >= 1000 ? String.format(java.util.Locale.US, "%.1fk", hz / 1000) : String.valueOf(Math.round(hz)),
                        x, h - 10 * k.dp, 9, LessonKit.LABEL, Paint.Align.CENTER, false);
            }
        }
        k.label(c, "↑ час", left - 4 * k.dp, top + 10 * k.dp, 9, LessonKit.LABEL, Paint.Align.RIGHT, false);
        k.label(c, "Гц", right, h - 10 * k.dp, 9, LessonKit.LABEL, Paint.Align.RIGHT, false);
    }
}
