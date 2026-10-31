package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.List;

/** Shows the newest entries of the lab journal. */
final class JournalView implements LessonView {
    private final LessonKit k;
    String note = "";

    JournalView(float dp) { k = new LessonKit(dp); }

    @Override public void onFrame(AudioEngine.Frame f) { }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        List<String[]> rows = Journal.rows();
        k.label(c, "📒 Журнал вимірів", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Записів: " + rows.size() + (note.isEmpty() ? "" : " · " + note), 8 * dp, 40 * dp, 11.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        if (rows.isEmpty()) {
            k.label(c, "Поки порожньо — зробіть будь-який експеримент", w / 2f, h * 0.4f, 13, LessonKit.LABEL, Paint.Align.CENTER, false);
            return;
        }
        float y = 64 * dp, rowH = 34 * dp;
        int fit = (int) ((h - y) / rowH);
        String lastDay = "";
        for (int i = rows.size() - 1; i >= 0 && fit > 0; i--, fit--) {
            String[] r = rows.get(i);
            String day = r[0].length() >= 10 ? r[0].substring(0, 10) : r[0];
            String time = r[0].length() >= 16 ? r[0].substring(11, 16) : "";
            if (!day.equals(lastDay)) {
                k.label(c, day, 8 * dp, y + 4 * dp, 10, LessonKit.LABEL, Paint.Align.LEFT, true);
                lastDay = day;
                y += 16 * dp;
                fit--;
            }
            double v;
            try { v = Double.parseDouble(r[3]); } catch (NumberFormatException e) { v = Double.NaN; }
            String val = Math.abs(v) >= 100 ? String.format(java.util.Locale.US, "%.1f", v) : String.format(java.util.Locale.US, "%.2f", v);
            k.label(c, time + "  " + r[1], 8 * dp, y + 12 * dp, 11, LessonKit.CYAN, Paint.Align.LEFT, false);
            k.label(c, r[2], 8 * dp, y + 27 * dp, 12, LessonKit.WHITE, Paint.Align.LEFT, false);
            k.label(c, val + " " + r[4], w - 8 * dp, y + 27 * dp, 13, LessonKit.YELLOW, Paint.Align.RIGHT, true);
            k.line(c, 8 * dp, y + rowH, w - 8 * dp, y + rowH, LessonKit.GRID, 1);
            y += rowH;
        }
    }
}
