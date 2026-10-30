package com.example.audiospectrumpro;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.Locale;

/** Fish-finder style display: echo history (top), current echo profile (bottom), distance readout. */
final class SonarRenderer {
    private static final int GRID = 0xFF1E293B, LABEL = 0xFF7F93A8, SOFT = 0xFFCBD5E1;
    private static final int CYAN = 0xFF00F0FF, PINK = 0xFFFF007F, YELLOW = 0xFFFFE600;

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private Bitmap history;
    private int head;
    private int[] column;
    private SonarProcessor.Frame last;
    private float smoothTarget = Float.NaN;
    private String band = "";

    SonarRenderer(float dp) {
        this.dp = dp;
        stroke.setStyle(Paint.Style.STROKE);
    }

    void setBand(String b) { band = b; }

    void clear() {
        history = null;
        last = null;
        smoothTarget = Float.NaN;
    }

    void onFrame(SonarProcessor.Frame f, Palettes.Palette palette, int histW, int histH) {
        last = f;
        if (!Float.isNaN(f.targetM)) {
            smoothTarget = Float.isNaN(smoothTarget) || Math.abs(f.targetM - smoothTarget) > 0.15f
                    ? f.targetM : smoothTarget * 0.6f + f.targetM * 0.4f;
        } else {
            smoothTarget = Float.NaN;
        }
        int colW = Math.max(1, Math.round(3 * dp));
        int cols = Math.max(1, histW / colW);
        if (history == null || history.getWidth() != cols || history.getHeight() != histH) {
            if (histH <= 0) return;
            history = Bitmap.createBitmap(cols, histH, Bitmap.Config.ARGB_8888);
            history.eraseColor(Color.BLACK);
            column = new int[histH];
            head = 0;
        }
        int[] lut = Palettes.lut(palette);
        float[] p = f.motionMode ? f.motionDb : f.profileDb;
        float lo = f.motionMode ? -70 : -65, hi = f.motionMode ? -25 : -10;
        for (int y = 0; y < histH; y++) {
            float pos = (float) y / histH * (p.length - 1);
            int i0 = (int) pos;
            // Max over the samples this pixel covers keeps thin echoes visible.
            int i1 = Math.min(p.length - 1, (int) ((float) (y + 1) / histH * (p.length - 1)));
            float v = p[i0];
            for (int i = i0 + 1; i <= i1; i++) v = Math.max(v, p[i]);
            float level = Math.max(0, Math.min(1, (v - lo) / (hi - lo)));
            column[y] = lut[(int) (level * 255)];
        }
        history.setPixels(column, 0, 1, head, 0, 1, histH);
        head = (head + 1) % cols;
    }

    void draw(Canvas c, float w, float h) {
        float histTop = 46 * dp, histBottom = h * 0.66f, left = 34 * dp;
        float aTop = histBottom + 22 * dp, aBottom = h - 18 * dp;
        SonarProcessor.Frame f = last;

        // Header: distance readout.
        if (f == null) {
            label(c, "Ехолот вимкнено — натисніть «Увімкнути ехолот»", w / 2, 26 * dp, 14, SOFT, Paint.Align.CENTER, true);
            return;
        }
        boolean weak = f.directSnrDb < 25;
        String dist = Float.isNaN(smoothTarget) ? "—" : smoothTarget < 1
                ? String.format(Locale.US, "%.0f см", smoothTarget * 100) : String.format(Locale.US, "%.2f м", smoothTarget);
        label(c, (f.motionMode ? "Рух: " : "До перешкоди: ") + dist, 8 * dp, 24 * dp, 20, Float.isNaN(smoothTarget) ? SOFT : CYAN,
                Paint.Align.LEFT, true);
        label(c, band + String.format(Locale.US, " · сигнал %.0f дБ", f.directSnrDb), w - 8 * dp, 20 * dp, 10,
                weak ? PINK : LABEL, Paint.Align.RIGHT, false);
        if (weak) label(c, "слабко: спробуйте нижчий діапазон або гучніше", w - 8 * dp, 34 * dp, 10, PINK, Paint.Align.RIGHT, false);
        else label(c, f.motionMode ? "яскраве — те, що рухається" : "яскраве — сильне відлуння", w - 8 * dp, 34 * dp, 10, LABEL,
                Paint.Align.RIGHT, false);

        // Echo history: time left -> right (newest at the right), distance top -> bottom.
        if (history != null) {
            int cols = history.getWidth(), hh = history.getHeight();
            float colW = (w - left) / cols;
            int older = cols - head;
            src.set(head, 0, cols, hh);
            dst.set(left, histTop, left + older * colW, histBottom);
            c.drawBitmap(history, src, dst, null);
            if (head > 0) {
                src.set(0, 0, head, hh);
                dst.set(left + older * colW, histTop, w, histBottom);
                c.drawBitmap(history, src, dst, null);
            }
        }
        float range = f.rangeM;
        float step = range <= 1.2f ? 0.2f : range <= 3.1f ? 0.5f : range <= 6 ? 1f : 2f;
        stroke.setStrokeWidth(1);
        for (float d = 0; d <= range + 1e-3f; d += step) {
            float y = histTop + d / range * (histBottom - histTop);
            stroke.setColor(0x55FFFFFF);
            c.drawLine(left - 4 * dp, y, left, y, stroke);
            label(c, d < 1 && step < 0.5f ? Math.round(d * 100) + "см" : String.format(Locale.US, "%.1fм", d),
                    left - 5 * dp, y + 3 * dp, 9, LABEL, Paint.Align.RIGHT, false);
        }
        label(c, "← час (10 імпульсів/с)", left + 2 * dp, histBottom + 12 * dp, 9, LABEL, Paint.Align.LEFT, false);
        if (!Float.isNaN(smoothTarget)) {
            float y = histTop + smoothTarget / range * (histBottom - histTop);
            fill.setColor(Color.WHITE);
            c.drawCircle(w - 6 * dp, y, 4 * dp, fill);
        }

        // Current profile (A-scan): distance along x, strength along y.
        float[] p = f.motionMode ? f.motionDb : f.profileDb;
        float lo = -70, hi = 0;
        stroke.setColor(GRID);
        for (int db = 0; db >= -60; db -= 20) {
            float y = aTop + (hi - db) / (hi - lo) * (aBottom - aTop);
            c.drawLine(left, y, w, y, stroke);
            label(c, String.valueOf(db), left - 4 * dp, y + 3 * dp, 9, LABEL, Paint.Align.RIGHT, false);
        }
        path.reset();
        int px = (int) (w - left);
        for (int x = 0; x <= px; x++) {
            int i = Math.min(p.length - 1, Math.round((float) x / px * (p.length - 1)));
            float y = aTop + (hi - Math.max(lo, Math.min(hi, p[i]))) / (hi - lo) * (aBottom - aTop);
            if (x == 0) path.moveTo(left, y); else path.lineTo(left + x, y);
        }
        stroke.setColor(YELLOW);
        stroke.setStrokeWidth(1.4f * dp);
        c.drawPath(path, stroke);
        for (float d = 0; d <= range + 1e-3f; d += step) {
            float x = left + d / range * (w - left);
            label(c, String.format(Locale.US, d < 1 && step < 0.5f ? "%.1f" : "%.1f", d), x, h - 4 * dp, 9, LABEL, Paint.Align.CENTER, false);
        }
        if (!Float.isNaN(f.targetM)) {
            float x = left + f.targetM / range * (w - left);
            stroke.setColor(PINK);
            stroke.setStrokeWidth(1.5f * dp);
            c.drawLine(x, aTop, x, aBottom, stroke);
        }
        label(c, "відлуння зараз (м)", w - 4 * dp, aTop - 6 * dp, 9, LABEL, Paint.Align.RIGHT, false);
    }

    private void label(Canvas c, String s, float x, float y, float sizeDp, int color, Paint.Align align, boolean bold) {
        text.setTextSize(sizeDp * dp);
        text.setColor(color);
        text.setTextAlign(align);
        text.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        c.drawText(s, x, y, text);
    }
}
