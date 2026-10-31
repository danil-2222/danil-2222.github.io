package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Acoustic modem (multi-tone FSK). Each byte is sent as two 4-bit symbols; every symbol is one of
 * 17 tones chosen relative to the previous one, so two neighbouring symbols never share a tone and
 * the receiver can always see where one ends. Frame: preamble, length, bytes, XOR checksum, end tone.
 */
final class ModemLesson implements LessonView {
    static final int TONES = 17;
    static final float BASE_HZ = 1600, STEP_HZ = 140, PRE_HZ = 1200, END_HZ = 4300;
    static final float SYMBOL_S = 0.07f, PRE_S = 0.4f, END_S = 0.2f;
    static final int MAX_BYTES = 48;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int SYM_PRE = TONES, SYM_END = TONES + 1, SYM_NONE = -1;

    static float toneHz(int sym) {
        return sym == SYM_PRE ? PRE_HZ : sym == SYM_END ? END_HZ : BASE_HZ + STEP_HZ * sym;
    }

    // ---------------------------------------------------------------- transmitter

    static float[] encode(String text, int rate) {
        byte[] raw = text.getBytes(UTF8);
        int n = Math.min(raw.length, MAX_BYTES);
        byte[] frame = new byte[n + 2];
        frame[0] = (byte) n;
        byte xor = 0;
        for (int i = 0; i < n; i++) {
            frame[1 + i] = raw[i];
            xor ^= raw[i];
        }
        frame[n + 1] = xor;
        int symLen = Math.round(SYMBOL_S * rate);
        int pre = Math.round(PRE_S * rate), end = Math.round(END_S * rate);
        int total = rate / 10 + pre + frame.length * 2 * symLen + end + rate / 5;
        float[] out = new float[total];
        int pos = rate / 10;
        int fade = rate / 200;
        LessonKit.addTone(out, pos, pre, PRE_HZ, 0.8f, rate, fade);
        pos += pre;
        int prev = 0;
        for (byte b : frame) {
            int[] nib = {(b >> 4) & 0xF, b & 0xF};
            for (int v : nib) {
                int sym = (prev + 1 + v) % TONES;
                LessonKit.addTone(out, pos, symLen, toneHz(sym), 0.8f, rate, fade);
                pos += symLen;
                prev = sym;
            }
        }
        LessonKit.addTone(out, pos, end, END_HZ, 0.8f, rate, fade);
        return out;
    }

    // ---------------------------------------------------------------- receiver

    private final LessonKit k;
    private final LessonKit.SampleTap tap = new LessonKit.SampleTap();
    private float[] carry = new float[0];
    private int runSym = SYM_NONE, runLen, silence;
    private boolean receiving;
    private int prev;
    private final List<Integer> nibbles = new ArrayList<>();
    private String status = "Слухаю… Надішліть повідомлення з цього або іншого телефона";
    private String received = "";
    private boolean receivedOk;
    String sent = "";

    // Recent per-block symbols for the "piano roll" display.
    private static final int HISTORY = 600;
    private final int[] history = new int[HISTORY];
    private int histHead;

    ModemLesson(float dp) {
        k = new LessonKit(dp);
        java.util.Arrays.fill(history, SYM_NONE);
    }

    void reset() {
        receiving = false;
        nibbles.clear();
        runSym = SYM_NONE;
        runLen = 0;
        status = "Слухаю… Надішліть повідомлення з цього або іншого телефона";
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float[] fresh = tap.take(f);
        if (fresh.length == 0) return;
        int block = f.sampleRate / 100; // 10 ms
        float[] buf = new float[carry.length + fresh.length];
        System.arraycopy(carry, 0, buf, 0, carry.length);
        System.arraycopy(fresh, 0, buf, carry.length, fresh.length);
        int off = 0;
        for (; off + block <= buf.length; off += block) onBlock(detect(buf, off, block, f.sampleRate));
        carry = java.util.Arrays.copyOfRange(buf, off, buf.length);
    }

    /** The dominant modem tone in one block, or SYM_NONE. */
    static int detect(float[] x, int off, int n, int rate) {
        double energy = 0;
        for (int i = 0; i < n; i++) energy += x[off + i] * x[off + i];
        if (energy / n < 1e-6) return SYM_NONE; // below about -60 dBFS
        int best = SYM_NONE;
        double bestP = 0;
        for (int s = 0; s <= SYM_END; s++) {
            double p = LessonKit.goertzel(x, off, n, toneHz(s), rate);
            if (p > bestP) { bestP = p; best = s; }
        }
        // Share of the block's energy in that tone (1.0 for a clean tone).
        double share = bestP / (energy * n / 2);
        return share > 0.35 ? best : SYM_NONE;
    }

    private void onBlock(int sym) {
        history[histHead] = sym;
        histHead = (histHead + 1) % HISTORY;
        if (sym == SYM_NONE) {
            if (++silence > 100 && receiving) fail("Сигнал обірвався");
        } else {
            silence = 0;
        }
        if (sym == runSym) {
            runLen++;
            return;
        }
        if (runSym != SYM_NONE && runLen >= 3) token(runSym, runLen);
        runSym = sym;
        runLen = 1;
    }

    private void token(int sym, int len) {
        if (sym == SYM_PRE) {
            if (len >= 15) {
                receiving = true;
                prev = 0;
                nibbles.clear();
                status = "Приймаю…";
            }
            return;
        }
        if (!receiving) return;
        if (sym == SYM_END) {
            finish();
            return;
        }
        int nib = ((sym - prev - 1) % TONES + TONES) % TONES;
        prev = sym;
        if (nib > 15) {
            fail("Пошкоджений символ");
            return;
        }
        nibbles.add(nib);
        status = "Приймаю… " + nibbles.size() / 2 + " байт";
    }

    private void finish() {
        receiving = false;
        if (nibbles.size() < 4 || nibbles.size() % 2 != 0) {
            fail("Неповне повідомлення");
            return;
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i + 1 < nibbles.size(); i += 2) b.write((nibbles.get(i) << 4) | nibbles.get(i + 1));
        byte[] frame = b.toByteArray();
        int n = frame[0] & 0xFF;
        if (frame.length != n + 2) {
            fail("Неправильна довжина");
            return;
        }
        byte xor = 0;
        for (int i = 1; i <= n; i++) xor ^= frame[i];
        receivedOk = xor == frame[n + 1];
        received = new String(frame, 1, n, UTF8);
        status = receivedOk ? "Отримано ✓ контрольна сума збіглася" : "Отримано з помилками ✗";
    }

    private void fail(String why) {
        receiving = false;
        nibbles.clear();
        status = why + " — спробуйте ще раз ближче й гучніше";
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Звуковий модем", 8 * dp, 20 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, status, 8 * dp, 36 * dp, 11, receiving ? LessonKit.YELLOW : LessonKit.SOFT, Paint.Align.LEFT, false);

        // Piano roll: which tone was heard in each 10 ms block (last 6 s).
        float left = 44 * dp, right = w - 8 * dp, top = 48 * dp, bottom = h * 0.62f;
        int rows = SYM_END + 1;
        float rh = (bottom - top) / rows;
        k.rect(c, left, top, right, bottom, 0xFF0B1220);
        for (int s = 0; s < rows; s++) {
            float y = bottom - (s + 1) * rh;
            if (s == SYM_PRE || s == SYM_END || s % 4 == 0) {
                String lab = s == SYM_PRE ? "старт" : s == SYM_END ? "кінець" : String.format(Locale.US, "%.1fk", toneHz(s) / 1000);
                k.label(c, lab, left - 4 * dp, y + rh * 0.8f, 8.5f, LessonKit.LABEL, Paint.Align.RIGHT, false);
            }
        }
        float bw = (right - left) / HISTORY;
        for (int i = 0; i < HISTORY; i++) {
            int s = history[(histHead + i) % HISTORY];
            if (s == SYM_NONE) continue;
            int row = s == SYM_PRE ? 0 : s == SYM_END ? SYM_END : s + 1;
            int col = s == SYM_PRE ? LessonKit.GREEN : s == SYM_END ? LessonKit.PINK : LessonKit.CYAN;
            float y = bottom - (row + 1) * rh;
            k.rect(c, left + i * bw, y + 1, left + (i + 1) * bw + 0.5f, y + rh - 1, col);
        }
        k.label(c, "← 6 с · кожна смужка — тон, яким передано 4 біти", left, bottom + 12 * dp, 9.5f, LessonKit.LABEL,
                Paint.Align.LEFT, false);

        // Messages.
        float y = bottom + 34 * dp;
        k.label(c, "Отримано:", 8 * dp, y, 11, LessonKit.LABEL, Paint.Align.LEFT, false);
        k.label(c, received.isEmpty() ? "—" : received, 8 * dp, y + 22 * dp, 18,
                received.isEmpty() ? LessonKit.SOFT : receivedOk ? LessonKit.GREEN : LessonKit.PINK, Paint.Align.LEFT, true);
        if (!sent.isEmpty()) {
            k.label(c, "Надіслано: " + sent, 8 * dp, y + 42 * dp, 11, LessonKit.LABEL, Paint.Align.LEFT, false);
        }
    }
}
