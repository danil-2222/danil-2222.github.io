package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Tug of war by sound with one phone in the middle. Team "Гул" hums a low "у-у-у", team "Шум" hisses
 * "ш-ш-ш". The phone compares low-band and high-band energy: whichever team is stronger pulls the rope.
 * Works for any voices (children and adults), because it does not depend on pitch.
 */
final class TugGame implements LessonView {
    enum State { READY, COUNTDOWN, PULL, ROUND_OVER, MATCH_OVER }

    static final float ROUND_S = 30, COUNT_S = 3, SPEED = 0.16f;
    static final int WIN_ROUNDS = 2;

    String teamLow = "Гул", teamHigh = "Шум";
    private final LessonKit k;
    private State state = State.READY;
    private float clock, phaseStart;
    private float rope;                 // -1 = low team wins, +1 = high team wins
    private float force, loud;
    private int winsLow, winsHigh, lastWinner;

    TugGame(float dp) { k = new LessonKit(dp); }

    void start() {
        if (state == State.MATCH_OVER || state == State.READY) { winsLow = winsHigh = 0; }
        rope = 0;
        state = State.COUNTDOWN;
        phaseStart = clock;
    }

    void newMatch() {
        winsLow = winsHigh = 0;
        rope = 0;
        state = State.READY;
    }

    String state() { return state.name(); }
    float rope() { return rope; }
    int winsLow() { return winsLow; }
    int winsHigh() { return winsHigh; }
    float force() { return force; }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float dt = (float) AudioEngine.HOP / f.sampleRate;
        clock += dt;
        double low = LessonKit.bandPower(f, 70, 900), high = LessonKit.bandPower(f, 2500, 12000);
        float level = (float) (10 * Math.log10(low + high + 1e-20));
        loud = Math.max(0, Math.min(1, (level + 55) / 30));
        float diffDb = (float) (10 * Math.log10((high + 1e-20) / (low + 1e-20)));
        // Voice harmonics leak a little into the high band and hiss a little into the low one: centre the scale.
        float target = loud <= 0 ? 0 : Math.max(-1, Math.min(1, (diffDb + 1) / 10f)) * loud;
        force += (target - force) * 0.3f;

        float t = clock - phaseStart;
        if (state == State.COUNTDOWN && t >= COUNT_S) { state = State.PULL; phaseStart = clock; }
        if (state != State.PULL) return;
        rope = Math.max(-1, Math.min(1, rope + force * SPEED * dt));
        if (rope <= -1 || rope >= 1 || t >= ROUND_S) {
            lastWinner = rope < -0.05f ? -1 : rope > 0.05f ? 1 : 0;
            if (lastWinner < 0) winsLow++;
            if (lastWinner > 0) winsHigh++;
            state = winsLow >= WIN_ROUNDS || winsHigh >= WIN_ROUNDS ? State.MATCH_OVER : State.ROUND_OVER;
        }
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Перетягування каната", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Телефон — посередині між командами", 8 * dp, 40 * dp, 11.5f, LessonKit.SOFT, Paint.Align.LEFT, false);

        // Teams and score.
        k.label(c, "🐻 " + teamLow, 16 * dp, 76 * dp, 17, LessonKit.ORANGE, Paint.Align.LEFT, true);
        k.label(c, "«у-у-у»", 16 * dp, 94 * dp, 12, LessonKit.ORANGE, Paint.Align.LEFT, false);
        k.label(c, teamHigh + " 🐍", w - 16 * dp, 76 * dp, 17, LessonKit.CYAN, Paint.Align.RIGHT, true);
        k.label(c, "«ш-ш-ш»", w - 16 * dp, 94 * dp, 12, LessonKit.CYAN, Paint.Align.RIGHT, false);
        k.label(c, winsLow + " : " + winsHigh, w / 2f, 84 * dp, 24, LessonKit.WHITE, Paint.Align.CENTER, true);

        // Field: centre line, win lines, rope with a flag.
        float cy = h * 0.45f, left = 24 * dp, right = w - 24 * dp, mid = w / 2f, half = (right - left) / 2;
        k.rect(c, left, cy - 50 * dp, mid, cy + 50 * dp, 0x22FF8800);
        k.rect(c, mid, cy - 50 * dp, right, cy + 50 * dp, 0x2200F0FF);
        k.line(c, mid, cy - 56 * dp, mid, cy + 56 * dp, LessonKit.WHITE, 1.5f);
        float flag = mid + rope * half * 0.85f;
        float shift = rope * half * 0.85f;
        k.line(c, left - 30 * dp + shift, cy, right + 30 * dp + shift, cy, 0xFFD7B98E, 6);
        k.fill.setColor(LessonKit.PINK);
        c.drawRect(flag - 2 * dp, cy - 30 * dp, flag + 2 * dp, cy, k.fill);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(flag + 2 * dp, cy - 30 * dp);
        p.lineTo(flag + 20 * dp, cy - 23 * dp);
        p.lineTo(flag + 2 * dp, cy - 16 * dp);
        p.close();
        c.drawPath(p, k.fill);
        k.label(c, "🧍🧍", left + 6 * dp + shift, cy + 40 * dp, 22, LessonKit.WHITE, Paint.Align.LEFT, false);
        k.label(c, "🧍🧍", right - 6 * dp + shift, cy + 40 * dp, 22, LessonKit.WHITE, Paint.Align.RIGHT, false);
        k.line(c, mid - half * 0.85f, cy - 56 * dp, mid - half * 0.85f, cy + 56 * dp, LessonKit.ORANGE, 2);
        k.line(c, mid + half * 0.85f, cy - 56 * dp, mid + half * 0.85f, cy + 56 * dp, LessonKit.CYAN, 2);

        // Force meter.
        float my = cy + 80 * dp;
        k.rect(c, left, my, right, my + 12 * dp, 0xFF1E293B);
        if (force < 0) k.rect(c, mid + force * half, my, mid, my + 12 * dp, LessonKit.ORANGE);
        else k.rect(c, mid, my, mid + force * half, my + 12 * dp, LessonKit.CYAN);
        k.label(c, "хто зараз сильніший", mid, my + 28 * dp, 10.5f, LessonKit.LABEL, Paint.Align.CENTER, false);

        String msg;
        int col = LessonKit.YELLOW;
        float t = clock - phaseStart;
        switch (state) {
            case READY: msg = "Натисніть «▶ Раунд» — гра до двох перемог"; break;
            case COUNTDOWN: msg = "Приготуватись… " + (int) Math.ceil(COUNT_S - t); col = LessonKit.WHITE; break;
            case PULL: msg = String.format(Locale.US, "ТЯГНІТЬ! %.0f с", ROUND_S - t); col = LessonKit.GREEN; break;
            case ROUND_OVER: msg = (lastWinner == 0 ? "Нічия в раунді" : "Раунд за командою «" + (lastWinner < 0 ? teamLow : teamHigh) + "»")
                    + " · «▶ Раунд» — далі"; break;
            default: msg = "🏆 Перемогла команда «" + (winsLow > winsHigh ? teamLow : teamHigh) + "»!"; col = LessonKit.GREEN; break;
        }
        k.label(c, msg, mid, h - 30 * dp, 15, col, Paint.Align.CENTER, true);
    }
}
