package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Speed of sound with one phone and a wall: the phone chirps and hears its own chirp, then the echo.
 * The echo travels 2·d further, so the delay grows by 2/c for every metre. Measuring at two or more
 * distances gives c from the slope (fixed offsets of the phone cancel), then the air temperature.
 */
final class EchoSpeedExperiment implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); }

    static final float CAPTURE_S = 0.7f;

    float distance = 1f;
    private final LessonKit k;
    private final Sound sound;
    private final LessonKit.GaplessTap tap = new LessonKit.GaplessTap();
    private final List<float[]> results = new ArrayList<>();       // {distance, delay s}
    private float[] buf = new float[0];
    private int len, rate = 48000;
    private boolean capturing;
    private float[] lastEnv;
    private int lastDirect = -1, lastEcho = -1;
    private String status = "Станьте перед стіною і натисніть «📏 Виміряти»";

    EchoSpeedExperiment(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
    }

    void measure() {
        capturing = true;
        len = 0;
        sound.play(TemperatureExperiment.chirpSound(), false);
        status = "📢 Пік!";
    }

    void clear() { results.clear(); lastEnv = null; status = "Виміри стерто"; }

    int count() { return results.size(); }
    String status() { return status; }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float[] fresh = tap.take(f);
        if (!capturing) return;
        rate = f.sampleRate;
        if (buf.length != (int) (CAPTURE_S * rate)) buf = new float[(int) (CAPTURE_S * rate)];
        int n = Math.min(fresh.length, buf.length - len);
        System.arraycopy(fresh, 0, buf, len, n);
        len += n;
        if (len >= buf.length) { capturing = false; analyse(); }
    }

    /** Correlation envelope with the chirp at every position (the matched filter). */
    static float[] envelope(float[] x, int rate) {
        float[] s = TemperatureExperiment.chirp(rate), q = TemperatureExperiment.chirpQuadrature(rate);
        int n = s.length, m = x.length - n;
        float[] env = new float[Math.max(0, m)];
        for (int p = 0; p < m; p++) {
            double a = 0, b = 0;
            for (int i = 0; i < n; i++) { a += x[p + i] * s[i]; b += x[p + i] * q[i]; }
            env[p] = (float) Math.sqrt(a * a + b * b);
        }
        return env;
    }

    static double refinePeak(float[] env, int p) {
        if (p <= 0 || p >= env.length - 1) return p;
        double a = env[p - 1], b = env[p], c = env[p + 1], den = a - 2 * b + c;
        return den != 0 ? p + Math.max(-0.5, Math.min(0.5, 0.5 * (a - c) / den)) : p;
    }

    private void analyse() {
        float[] env = envelope(buf, rate);
        lastEnv = env;
        int direct = 0;
        for (int i = 1; i < env.length; i++) if (env[i] > env[direct]) direct = i;
        if (env[direct] < 1e-3) { status = "✗ Не почув власний пік. Гучність на максимум!"; lastDirect = -1; return; }
        // The wall echo is expected near 2d/343; look within ±35 % of that (tables and hands echo earlier).
        double expect = 2 * distance / 343.0 * rate;
        int lo = Math.max(direct + (int) (0.0012 * rate), direct + (int) (expect * 0.65)), hi = Math.min(env.length - 2, direct + (int) (expect * 1.35) + 2);
        int echo = -1;
        for (int i = lo; i <= hi; i++) if (echo < 0 || env[i] > env[echo]) echo = i;
        float[] sorted = java.util.Arrays.copyOfRange(env, Math.min(env.length - 1, direct + (int) (0.05 * rate)), env.length);
        java.util.Arrays.sort(sorted);
        float noise = sorted.length > 0 ? sorted[sorted.length / 2] : 0;
        lastDirect = direct;
        lastEcho = echo;
        if (echo < 0 || env[echo] < 4 * noise + 1e-6) { status = "✗ Луни не чути. Ближче до стіни або гучніше."; return; }
        double delay = (refinePeak(env, echo) - refinePeak(env, direct)) / rate;
        results.add(new float[]{distance, (float) delay});
        Journal.add("Луна від стіни", String.format(Locale.US, "затримка луни на %.2f м", distance), delay * 1000, "мс");
        if (precise()) {
            Journal.add("Луна від стіни", "швидкість звуку", speed(), "м/с");
            Journal.add("Луна від стіни", "температура повітря", TemperatureExperiment.temperature(speed()), "°C");
        }
        status = String.format(Locale.US, "✓ %.2f м: луна на %.2f мс пізніше", distance, delay * 1000);
    }

    /** Speed of sound from all measurements (m/s), NaN if none. */
    double speed() {
        int n = results.size();
        if (n == 0) return Double.NaN;
        double mx = 0, my = 0;
        for (float[] r : results) { mx += r[0]; my += r[1]; }
        mx /= n;
        my /= n;
        double sxx = 0, sxy = 0, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (float[] r : results) {
            sxx += (r[0] - mx) * (r[0] - mx);
            sxy += (r[0] - mx) * (r[1] - my);
            lo = Math.min(lo, r[0]);
            hi = Math.max(hi, r[0]);
        }
        if (hi - lo >= 0.2 && sxy > 0) return 2 * sxx / sxy;
        return 2 * mx / my;
    }

    boolean precise() {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float[] r : results) { lo = Math.min(lo, r[0]); hi = Math.max(hi, r[0]); }
        return results.size() >= 2 && hi - lo >= 0.2f;
    }

    @Override public boolean animating() { return capturing; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Швидкість звуку: луна від стіни", 8 * dp, 22 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, String.format(Locale.US, "до стіни %.2f м · телефон низом до стіни", distance), 8 * dp, 40 * dp, 11.5f,
                LessonKit.SOFT, Paint.Align.LEFT, false);
        // The echogram: what the phone heard after its own chirp.
        RectF g = new RectF(12 * dp, 56 * dp, w - 12 * dp, 186 * dp);
        k.rect(c, g.left, g.top, g.right, g.bottom, 0xFF0B1220);
        if (lastEnv != null && lastDirect >= 0) {
            int span = (int) (0.03 * rate), from = Math.max(0, lastDirect - rate / 1000);
            float max = lastEnv[lastDirect];
            float prevX = g.left, prevY = g.bottom;
            for (int i = 0; i < span && from + i < lastEnv.length; i += 4) {
                float x = g.left + g.width() * i / span;
                float v = (float) Math.sqrt(lastEnv[from + i] / max);       // square-root scale shows weak echoes
                float y = g.bottom - v * g.height() * 0.95f;
                k.line(c, prevX, prevY, x, y, LessonKit.CYAN, 1.5f);
                prevX = x;
                prevY = y;
            }
            float xd = g.left + g.width() * (lastDirect - from) / span;
            k.label(c, "пік", xd + 3 * dp, g.top + 14 * dp, 10, LessonKit.WHITE, Paint.Align.LEFT, false);
            if (lastEcho >= 0) {
                float xe = g.left + g.width() * (lastEcho - from) / span;
                k.line(c, xe, g.top, xe, g.bottom, LessonKit.YELLOW, 1.5f);
                k.label(c, "луна", xe + 3 * dp, g.top + 14 * dp, 10, LessonKit.YELLOW, Paint.Align.LEFT, true);
            }
            k.label(c, "0 … 30 мс", g.right - 4 * dp, g.bottom - 4 * dp, 9.5f, LessonKit.LABEL, Paint.Align.RIGHT, false);
        } else {
            k.label(c, "тут з'явиться «ехограма»", g.centerX(), g.centerY(), 12, LessonKit.LABEL, Paint.Align.CENTER, false);
        }
        k.label(c, status, 12 * dp, g.bottom + 22 * dp, 12.5f, status.startsWith("✗") ? LessonKit.PINK : LessonKit.CYAN, Paint.Align.LEFT, true);
        float y = g.bottom + 46 * dp;
        for (int i = Math.max(0, results.size() - 6); i < results.size(); i++) {
            k.label(c, String.format(Locale.US, "%d.  %.2f м  →  %.3f мс", i + 1, results.get(i)[0], results.get(i)[1] * 1000), 20 * dp, y, 12,
                    LessonKit.WHITE, Paint.Align.LEFT, false);
            y += 17 * dp;
        }
        double cs = speed();
        if (!Double.isNaN(cs)) {
            RectF box = new RectF(12 * dp, h - 84 * dp, w - 12 * dp, h - 10 * dp);
            k.fill.setColor(0xFF14213A);
            c.drawRoundRect(box, 10 * dp, 10 * dp, k.fill);
            k.label(c, String.format(Locale.US, "Швидкість звуку: %.1f м/с", cs), box.left + 12 * dp, box.top + 24 * dp, 15, LessonKit.WHITE,
                    Paint.Align.LEFT, true);
            k.label(c, String.format(Locale.US, "Температура повітря ≈ %.1f °C", TemperatureExperiment.temperature(cs)), box.left + 12 * dp,
                    box.top + 46 * dp, 14, LessonKit.GREEN, Paint.Align.LEFT, true);
            k.label(c, precise() ? "за нахилом (кілька відстаней) — точно" : "одна відстань — орієнтовно; виміряйте ще на іншій",
                    box.left + 12 * dp, box.top + 64 * dp, 10.5f, precise() ? LessonKit.LABEL : LessonKit.YELLOW, Paint.Align.LEFT, false);
        }
    }
}
