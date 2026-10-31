package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Air temperature from the speed of sound, with two phones and no shared clock ("BeepBeep" ranging).
 * Phone A chirps; phone B hears it and chirps back. Each phone measures, on its own microphone clock,
 * the time between the two chirps it heard: ΔA and ΔB. Then ΔA − ΔB = (2·d − dAA − dBB) / c, where d is
 * the distance between the phones; unknown delays of speakers, microphones and reaction all cancel.
 * Measuring at two or more distances gives c from the slope, and T = (c − 331.3) / 0.606.
 */
final class TemperatureExperiment implements LessonView {
    interface Sound {
        void play(float[] pcm, boolean loop);
    }

    enum Role { LEADER, RESPONDER }
    enum State { IDLE, A_SELF, A_OTHER, A_MSG, B_ARMED, B_WAIT_PLAY, B_SELF, B_SEND }

    static final int OUT_RATE = 48000;
    static final float CHIRP_S = 0.02f, F0 = 6000, F1 = 10000;
    /** Assumed speaker-to-microphone distance inside one phone (only used with a single distance). */
    static final float SELF_M = 0.12f;
    static final float REPLY_MS = 400;

    static final class Measurement {
        final float distance;
        final double diffS;
        Measurement(float distance, double diffS) { this.distance = distance; this.diffS = diffS; }
    }

    Role role = Role.LEADER;
    float distance = 1f;
    /** Ruler mode: the temperature is known, the distance is measured. */
    boolean ruler;
    float tempC = 20;
    private boolean calibrateNext;
    private double calibD = Double.NaN, lastDistance = Double.NaN;
    private float calibDist;

    private final LessonKit k;
    private final Sound sound;
    private final ModemLesson decoder;
    private long lastIndex = -1;
    private long lostSamples;
    private final List<Measurement> results = new ArrayList<>();
    private State state = State.IDLE;
    private String status = "";
    private float clockMs, deadline, playAt;

    // Microphone history (absolute sample counter) and the matched-filter templates.
    private int rate;
    private float[] ring;
    private long total;
    private float[] tplSin, tplCos;
    private final BiquadFilter hp1 = new BiquadFilter(), hp2 = new BiquadFilter();
    private double floor = 1e-9;
    private long lastDetect = Long.MIN_VALUE / 2;
    private final List<Long> pending = new ArrayList<>();

    private double firstArrival;          // refined sample position of the first chirp heard
    private double deltaA = Double.NaN, deltaB = Double.NaN;
    private String sent = "";
    private int exchanges;

    TemperatureExperiment(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        decoder = new ModemLesson(dp);
        status = "Оберіть роль: A — ведучий, B — відповідач";
    }

    // ---------------------------------------------------------------- public API

    static float[] chirp(int rate) {
        int n = Math.round(CHIRP_S * rate);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / rate;
            double ph = 2 * Math.PI * (F0 * t + (F1 - F0) * t * t / (2 * CHIRP_S));
            double win = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            out[i] = (float) (win * Math.sin(ph));
        }
        return out;
    }

    static float[] chirpQuadrature(int rate) {
        int n = Math.round(CHIRP_S * rate);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / rate;
            double ph = 2 * Math.PI * (F0 * t + (F1 - F0) * t * t / (2 * CHIRP_S));
            double win = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
            out[i] = (float) (win * Math.cos(ph));
        }
        return out;
    }

    /** Sound to emit: the chirp with short silence around it, at the loudspeaker rate. */
    static float[] chirpSound() {
        float[] c = chirp(OUT_RATE);
        float[] out = new float[c.length + OUT_RATE / 5];
        for (int i = 0; i < c.length; i++) out[OUT_RATE / 20 + i] = 0.9f * c[i];
        return out;
    }

    void setRole(Role r) {
        role = r;
        pending.clear();
        decoder.reset();
        decoder.clearReceived();
        if (r == Role.RESPONDER) {
            state = State.B_ARMED;
            status = "👂 Чекаю на «пік» від телефона A…";
        } else {
            state = State.IDLE;
            status = "Поставте телефони і натисніть «Виміряти»";
        }
    }

    /** Leader: one measurement at the current distance. */
    void measure() {
        if (role != Role.LEADER) return;
        pending.clear();
        decoder.reset();
        decoder.clearReceived();
        deltaA = deltaB = Double.NaN;
        state = State.A_SELF;
        deadline = clockMs + 3000;
        status = "📢 Пік!";
        sound.play(chirpSound(), false);
    }

    /** Ruler: calibrate at the distance set on the slider (the next measurement). */
    void calibrate() {
        calibrateNext = true;
        measure();
    }

    double lastDistance() { return lastDistance; }

    /** Distance from the time difference D at the known temperature. */
    double rulerDistance(double d) {
        double c = 331.3 + 0.606 * tempC;
        return Double.isNaN(calibD) ? c * d / 2 + SELF_M : calibDist + c * (d - calibD) / 2;
    }

    void clearResults() {
        results.clear();
        if (role == Role.LEADER) setRole(Role.LEADER);
    }

    int count() { return results.size(); }
    String state() { return state.name(); }
    String sent() { return sent; }
    double deltaA() { return deltaA; }
    double deltaB() { return deltaB; }
    int exchanges() { return exchanges; }

    /** Speed of sound from all measurements, or NaN. */
    double speed() {
        int n = results.size();
        if (n == 0) return Double.NaN;
        double sx = 0, sy = 0;
        for (Measurement m : results) { sx += m.distance; sy += m.diffS; }
        double mx = sx / n, my = sy / n, sxx = 0, sxy = 0;
        for (Measurement m : results) {
            sxx += (m.distance - mx) * (m.distance - mx);
            sxy += (m.distance - mx) * (m.diffS - my);
        }
        if (spread() >= 0.2 && sxx > 0 && sxy > 0) return 2 * sxx / sxy;     // slope of D(d) is 2/c
        if (my <= 0 || mx <= SELF_M) return Double.NaN;
        return 2 * (mx - SELF_M) / my;                                       // one distance: rough
    }

    /** Spread of measured distances (m). Two distances at least 1 m apart make the result precise. */
    float spread() {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (Measurement m : results) { lo = Math.min(lo, m.distance); hi = Math.max(hi, m.distance); }
        return results.isEmpty() ? 0 : hi - lo;
    }

    static double temperature(double c) { return (c - 331.3) / 0.606; }

    // ---------------------------------------------------------------- listening

    @Override
    public void onFrame(AudioEngine.Frame f) {
        if (rate != f.sampleRate || ring == null) init(f.sampleRate);
        if (state == State.A_OTHER || state == State.A_MSG) decoder.onFrame(f);
        float[] fresh = take(f);
        int block = rate / 100;
        for (int i = 0; i < fresh.length; i++) ring[(int) ((total + i) % ring.length)] = fresh[i];
        float[] hp = fresh.clone();
        hp1.process(hp, hp.length);
        hp2.process(hp, hp.length);
        // Energy jump in the 6-10 kHz region, 10 ms at a time.
        for (int off = 0; off + block <= hp.length; off += block) {
            double e = 0;
            for (int i = 0; i < block; i++) e += hp[off + i] * hp[off + i];
            e /= block;
            long at = total + off;
            boolean listening = state == State.A_SELF || state == State.A_OTHER || state == State.B_ARMED || state == State.B_SELF;
            if (listening && e > floor * 25 && e > 1e-8 && at - lastDetect > rate * 0.15) {
                lastDetect = at;
                pending.add(at);
            }
            // Background level: follows quiet moments at once and louder steady sound within ~0.2 s.
            floor = e < floor ? Math.max(e, 1e-10) : floor + 0.1 * (e - floor);
        }
        total += fresh.length;
        clockMs += fresh.length * 1000f / rate;
        // Refine detections once enough audio after them has arrived.
        while (!pending.isEmpty() && total >= pending.get(0) + (long) (0.06 * rate) + tplSin.length) {
            double pos = refine(pending.remove(0));
            if (!Double.isNaN(pos)) onChirp(pos);
        }
        tick();
    }

    /**
     * New samples since the previous frame. If frames were skipped for longer than one analysis window,
     * the missing stretch is filled with silence so the sample counter (our clock) stays exact.
     */
    private float[] take(AudioEngine.Frame f) {
        if (f.tape != null) { lastIndex = f.index; return new float[0]; }
        long missed = lastIndex < 0 ? 1 : f.index - lastIndex;
        lastIndex = f.index;
        if (missed <= 0) return new float[0];
        long want = missed * AudioEngine.HOP;
        int have = (int) Math.min(f.time.length, want);
        int gap = (int) Math.min(want - have, rate * 2L);
        lostSamples += gap;
        float[] out = new float[gap + have];
        System.arraycopy(f.time, f.time.length - have, out, gap, have);
        return out;
    }

    long lostSamples() { return lostSamples; }

    private void init(int r) {
        rate = r;
        ring = new float[r * 4];
        total = 0;
        tplSin = chirp(r);
        tplCos = chirpQuadrature(r);
        hp1.setCustom(true, 5200, 0.707, r);
        hp2.setCustom(true, 5200, 0.707, r);
        pending.clear();
    }

    /** Matched filter around a detection: the first strong peak of the correlation envelope, sub-sample. */
    private double refine(long approx) {
        int n = tplSin.length;
        long from = approx - (long) (0.03 * rate), to = approx + (long) (0.03 * rate);
        if (from < total - ring.length + n) from = total - ring.length + n;
        if (to + n > total) to = total - n;
        if (to <= from + 2) return Double.NaN;
        int len = (int) (to - from);
        double[] env = new double[len];
        double max = 0, energy = 0;
        for (int p = 0; p < len; p++) {
            double cs = 0, cc = 0;
            long base = from + p;
            for (int i = 0; i < n; i++) {
                float x = ring[(int) ((base + i) % ring.length)];
                cs += x * tplSin[i];
                cc += x * tplCos[i];
            }
            env[p] = Math.sqrt(cs * cs + cc * cc);
            if (env[p] > max) max = env[p];
        }
        if (max <= 0) return Double.NaN;
        // Is it really our chirp? Compare with the energy of the best-matching stretch.
        int best = 0;
        for (int p = 1; p < len; p++) if (env[p] > env[best]) best = p;
        double tplE = 0;
        for (int i = 0; i < n; i++) tplE += tplSin[i] * tplSin[i];
        for (int i = 0; i < n; i++) {
            float x = ring[(int) ((from + best + i) % ring.length)];
            energy += x * x;
        }
        if (max < 0.5 * Math.sqrt(energy * tplE)) return Double.NaN;
        // The direct sound comes first; later and sometimes stronger peaks are reflections.
        for (int p = 1; p + 1 < len; p++) {
            if (env[p] >= 0.3 * max && env[p] >= env[p - 1] && env[p] >= env[p + 1]) {
                double a = env[p - 1], b = env[p], c = env[p + 1];
                double den = a - 2 * b + c;
                double d = den != 0 ? 0.5 * (a - c) / den : 0;
                return from + p + Math.max(-0.5, Math.min(0.5, d));
            }
        }
        return Double.NaN;
    }

    private void onChirp(double pos) {
        switch (state) {
            case A_SELF:
                firstArrival = pos;
                state = State.A_OTHER;
                deadline = clockMs + 3000;
                status = "👂 Чекаю відповідь телефона B…";
                break;
            case A_OTHER:
                if (pos - firstArrival < 0.2 * rate) break;          // own reflections
                deltaA = (pos - firstArrival) / rate;
                state = State.A_MSG;
                deadline = clockMs + 6000;
                status = "📡 Чекаю, поки B передасть свій час…";
                break;
            case B_ARMED:
                firstArrival = pos;
                state = State.B_WAIT_PLAY;
                playAt = clockMs + REPLY_MS - 60;
                status = "Почув A — відповідаю…";
                break;
            case B_SELF:
                if (pos - firstArrival < 0.2 * rate) break;
                deltaB = (pos - firstArrival) / rate;
                sent = "B:" + Math.round(deltaB * 1e6);
                float[] msg = ModemLesson.encode(sent, OUT_RATE);
                sound.play(msg, false);
                state = State.B_SEND;
                deadline = clockMs + msg.length * 1000f / OUT_RATE + 700;
                exchanges++;
                status = String.format(Locale.US, "📡 Передаю свій час ΔB = %.3f мс", deltaB * 1000);
                break;
            default:
                break;
        }
    }

    private void tick() {
        switch (state) {
            case A_SELF:
                if (clockMs > deadline) fail("Телефон не почув власний «пік». Збільште гучність.");
                break;
            case A_OTHER:
                if (clockMs > deadline) fail("Відповіді від B немає. Чи вибрано на ньому «Відповідач»? Гучність на максимум.");
                break;
            case A_MSG: {
                String msg = decoder.receivedText();
                if (msg != null && msg.startsWith("B:")) {
                    try {
                        deltaB = Long.parseLong(msg.substring(2).trim()) / 1e6;
                    } catch (NumberFormatException e) {
                        fail("Повідомлення від B пошкоджене.");
                        break;
                    }
                    double d = deltaA - deltaB;
                    if (d <= 0 || d > 0.06) {
                        fail(String.format(Locale.US, "Дивний результат (%.2f мс) — повторіть.", d * 1000));
                        break;
                    }
                    state = State.IDLE;
                    if (ruler) {
                        if (calibrateNext) {
                            calibD = d;
                            calibDist = distance;
                            calibrateNext = false;
                            status = "✓ Калібровано на " + fmtM(distance) + ". Тепер розставляйте телефони як завгодно";
                        } else {
                            lastDistance = rulerDistance(d);
                            status = String.format(Locale.US, "📏 Відстань: %.2f м", lastDistance);
                        }
                        break;
                    }
                    results.add(new Measurement(distance, d));
                    status = String.format(Locale.US, "✓ %s: різниця часу %.3f мс",
                            fmtM(distance), d * 1000);
                } else if (clockMs > deadline) {
                    fail("Не почув повідомлення від B. Тиша навколо допоможе.");
                }
                break;
            }
            case B_WAIT_PLAY:
                if (clockMs >= playAt) {
                    sound.play(chirpSound(), false);
                    state = State.B_SELF;
                    deadline = clockMs + 2500;
                }
                break;
            case B_SELF:
                if (clockMs > deadline) {
                    state = State.B_ARMED;
                    status = "✗ Не почув свій «пік». Гучніше!";
                }
                break;
            case B_SEND:
                if (clockMs > deadline) {
                    state = State.B_ARMED;
                    pending.clear();
                    lastDetect = total;
                    status = String.format(Locale.US, "✓ Надіслано ΔB = %.3f мс. Чекаю знову…", deltaB * 1000);
                }
                break;
            default:
                break;
        }
    }

    private void fail(String why) {
        state = State.IDLE;
        status = "✗ " + why;
    }

    private static String fmtM(float m) { return String.format(Locale.US, "%.2f м", m); }

    // ---------------------------------------------------------------- drawing

    @Override public boolean animating() { return state != State.IDLE && state != State.B_ARMED; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, ruler ? "📏 Рулетка зі звуку" : "🌡 Температура повітря за швидкістю звуку", 8 * dp, 22 * dp, 15, LessonKit.WHITE,
                Paint.Align.LEFT, true);
        k.label(c, role == Role.LEADER ? "цей телефон — A (ведучий)" : "цей телефон — B (відповідач)", 8 * dp, 40 * dp, 11.5f,
                LessonKit.SOFT, Paint.Align.LEFT, false);

        // Scheme: two phones and the sound travelling both ways.
        float y = 78 * dp, ax = w * 0.14f, bx = w * 0.58f;
        drawPhone(c, ax, y, "A", role == Role.LEADER);
        drawPhone(c, bx, y, "B", role == Role.RESPONDER);
        k.line(c, ax + 16 * dp, y - 6 * dp, bx - 16 * dp, y - 6 * dp, LessonKit.CYAN, 2);
        k.line(c, bx - 16 * dp, y + 6 * dp, ax + 16 * dp, y + 6 * dp, LessonKit.YELLOW, 2);
        k.label(c, "пік A →", (ax + bx) / 2, y - 12 * dp, 10, LessonKit.CYAN, Paint.Align.CENTER, false);
        k.label(c, "← пік B", (ax + bx) / 2, y + 22 * dp, 10, LessonKit.YELLOW, Paint.Align.CENTER, false);
        k.label(c, ruler ? (Double.isNaN(lastDistance) ? "d = ?" : String.format(Locale.US, "d = %.2f м", lastDistance)) : "d = " + fmtM(distance),
                (ax + bx) / 2, y + 40 * dp, 12, LessonKit.WHITE, Paint.Align.CENTER, true);

        int sc = status.startsWith("✗") ? LessonKit.PINK : LessonKit.CYAN;
        k.text.setTextSize(12 * dp);
        k.text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        float maxW = w - 110 * dp;
        String s1 = status, s2 = "";
        if (k.text.measureText(status) > maxW) {
            int cut = status.lastIndexOf(' ', Math.max(1, (int) (status.length() * maxW / k.text.measureText(status))));
            if (cut > 0) { s1 = status.substring(0, cut); s2 = status.substring(cut + 1); }
        }
        k.label(c, s1, 8 * dp, 136 * dp, 12, sc, Paint.Align.LEFT, true);
        if (!s2.isEmpty()) k.label(c, s2, 8 * dp, 152 * dp, 12, sc, Paint.Align.LEFT, true);

        double cs = speed();
        if (ruler && role == Role.LEADER) { drawRuler(c, w, h); return; }
        drawThermometer(c, w - 46 * dp, 60 * dp, h - 20 * dp, cs);

        float top = 166 * dp, right = w - 96 * dp;
        if (role == Role.RESPONDER) {
            k.label(c, "Цей телефон тільки відповідає.", 8 * dp, top + 10 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
            k.label(c, "Результат з'явиться на телефоні A.", 8 * dp, top + 28 * dp, 12, LessonKit.SOFT, Paint.Align.LEFT, false);
            if (exchanges > 0) k.label(c, "Відповідей: " + exchanges, 8 * dp, top + 50 * dp, 12, LessonKit.LABEL, Paint.Align.LEFT, false);
            return;
        }
        k.label(c, "Виміри (відстань → додатковий час):", 8 * dp, top + 6 * dp, 11, LessonKit.LABEL, Paint.Align.LEFT, false);
        int first = Math.max(0, results.size() - 7);
        for (int i = first; i < results.size(); i++) {
            Measurement m = results.get(i);
            k.label(c, String.format(Locale.US, "%d.  %s  →  %.3f мс", i + 1, fmtM(m.distance), m.diffS * 1000),
                    14 * dp, top + (24 + 17 * (i - first)) * dp, 12, LessonKit.WHITE, Paint.Align.LEFT, false);
        }
        if (!Double.isNaN(cs)) {
            RectF box = new RectF(8 * dp, h - 88 * dp, right, h - 10 * dp);
            k.fill.setColor(0xFF14213A);
            c.drawRoundRect(box, 10 * dp, 10 * dp, k.fill);
            boolean precise = spread() >= 0.2f;
            k.label(c, String.format(Locale.US, "Швидкість звуку: %.1f м/с", cs), box.left + 12 * dp, box.top + 24 * dp, 15,
                    LessonKit.WHITE, Paint.Align.LEFT, true);
            k.label(c, String.format(Locale.US, "Температура повітря: %.1f °C", temperature(cs)), box.left + 12 * dp, box.top + 46 * dp, 15,
                    LessonKit.GREEN, Paint.Align.LEFT, true);
            k.label(c, precise ? "за нахилом через " + results.size() + " вимірів на різних відстанях"
                            : "орієнтовно (одна відстань) — зробіть вимір ще на іншій відстані",
                    box.left + 12 * dp, box.top + 66 * dp, 10.5f, precise ? LessonKit.LABEL : LessonKit.YELLOW, Paint.Align.LEFT, false);
        }
    }

    private void drawRuler(Canvas c, int w, int h) {
        float dp = k.dp;
        float cx = w / 2f, y = h * 0.42f;
        k.label(c, Double.isNaN(lastDistance) ? "—" : String.format(Locale.US, "%.2f м", lastDistance), cx, y, 48, LessonKit.GREEN,
                Paint.Align.CENTER, true);
        k.label(c, String.format(Locale.US, "швидкість звуку при %.0f °C: %.1f м/с", tempC, 331.3 + 0.606 * tempC), cx, y + 30 * dp, 12,
                LessonKit.SOFT, Paint.Align.CENTER, false);
        k.label(c, Double.isNaN(calibD) ? "без калібрування: ±10 см" : "відкалібровано на " + fmtM(calibDist) + ": ±2–3 см", cx, y + 50 * dp, 12,
                Double.isNaN(calibD) ? LessonKit.YELLOW : LessonKit.LABEL, Paint.Align.CENTER, false);
    }

    private void drawPhone(Canvas c, float x, float y, String name, boolean me) {
        float dp = k.dp;
        RectF r = new RectF(x - 14 * dp, y - 26 * dp, x + 14 * dp, y + 26 * dp);
        k.fill.setColor(me ? 0xFF1E3A5F : 0xFF1E293B);
        c.drawRoundRect(r, 5 * dp, 5 * dp, k.fill);
        k.label(c, name, x, y + 6 * dp, 16, me ? LessonKit.CYAN : LessonKit.SOFT, Paint.Align.CENTER, true);
    }

    private void drawThermometer(Canvas c, float x, float top, float bottom, double cs) {
        float dp = k.dp;
        float bulb = 14 * dp, tubeW = 10 * dp;
        float tubeBottom = bottom - bulb * 1.6f;
        k.fill.setColor(0xFF1E293B);
        c.drawRoundRect(new RectF(x - tubeW / 2, top, x + tubeW / 2, tubeBottom + 4 * dp), tubeW / 2, tubeW / 2, k.fill);
        c.drawCircle(x, bottom - bulb, bulb, k.fill);
        // Scale -10..40 °C.
        for (int t = -10; t <= 40; t += 10) {
            float yy = tubeBottom - (t + 10) / 50f * (tubeBottom - top - 6 * dp);
            k.line(c, x + tubeW / 2, yy, x + tubeW / 2 + 5 * dp, yy, LessonKit.LABEL, 1);
            k.label(c, String.valueOf(t), x + tubeW / 2 + 7 * dp, yy + 4 * dp, 9.5f, LessonKit.LABEL, Paint.Align.LEFT, false);
        }
        int col = LessonKit.PINK;
        k.fill.setColor(col);
        c.drawCircle(x, bottom - bulb, bulb * 0.75f, k.fill);
        if (!Double.isNaN(cs)) {
            float t = (float) Math.max(-10, Math.min(40, temperature(cs)));
            float yy = tubeBottom - (t + 10) / 50f * (tubeBottom - top - 6 * dp);
            c.drawRect(x - tubeW / 4, yy, x + tubeW / 4, bottom - bulb, k.fill);
        }
    }
}
