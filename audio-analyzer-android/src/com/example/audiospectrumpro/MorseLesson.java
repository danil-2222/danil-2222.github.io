package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Morse code: sends text as beeps and decodes beeps, whistles or a held note from the microphone. */
final class MorseLesson implements LessonView {
    static final float TONE_HZ = 750, UNIT_S = 0.08f;

    private static final String[] LATIN = {
            "A.-", "B-...", "C-.-.", "D-..", "E.", "F..-.", "G--.", "H....", "I..", "J.---", "K-.-", "L.-..", "M--",
            "N-.", "O---", "P.--.", "Q--.-", "R.-.", "S...", "T-", "U..-", "V...-", "W.--", "X-..-", "Y-.--", "Z--..",
            "0-----", "1.----", "2..---", "3...--", "4....-", "5.....", "6-....", "7--...", "8---..", "9----.",
            "?..--..", "..-.-.-", ",--..--"};
    private static final String[] CYRILLIC = {
            "А.-", "Б-...", "В.--", "Г--.", "Ґ--.-.", "Д-..", "Е.", "Є..-..", "Ж...-", "З--..", "И-.--", "І..", "Ї.---.",
            "Й.---", "К-.-", "Л.-..", "М--", "Н-.", "О---", "П.--.", "Р.-.", "С...", "Т-", "У..-", "Ф..-.", "Х....",
            "Ц-.-.", "Ч---.", "Ш----", "Щ--.-", "Ь-..-", "Ю..--", "Я.-.-",
            "0-----", "1.----", "2..---", "3...--", "4....-", "5.....", "6-....", "7--...", "8---..", "9----."};

    private static Map<Character, String> encodeMap(String[] t) {
        Map<Character, String> m = new HashMap<>();
        for (String s : t) m.put(s.charAt(0), s.substring(1));
        return m;
    }

    private static Map<String, Character> decodeMap(String[] t) {
        Map<String, Character> m = new HashMap<>();
        for (String s : t) if (!m.containsKey(s.substring(1))) m.put(s.substring(1), s.charAt(0));
        return m;
    }

    private static final Map<Character, String> ENC_LAT = encodeMap(LATIN), ENC_CYR = encodeMap(CYRILLIC);
    private static final Map<String, Character> DEC_LAT = decodeMap(LATIN), DEC_CYR = decodeMap(CYRILLIC);

    boolean cyrillic = true;

    /** Dots and dashes for the text, letters separated by spaces, words by " / ". */
    static String toMorse(String text) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if (ch == ' ') { sb.append("/ "); continue; }
            String code = ENC_CYR.containsKey(ch) ? ENC_CYR.get(ch) : ENC_LAT.get(ch);
            if (code != null) sb.append(code).append(' ');
        }
        return sb.toString().trim();
    }

    static float[] encode(String text, int rate) {
        String morse = toMorse(text);
        int unit = Math.round(UNIT_S * rate);
        int len = rate / 5;
        for (char ch : morse.toCharArray()) len += (ch == '-' ? 4 : ch == '.' ? 2 : ch == '/' ? 4 : 2) * unit;
        float[] out = new float[len + rate / 5];
        int pos = rate / 5, fade = rate / 250;
        for (char ch : morse.toCharArray()) {
            if (ch == '.' || ch == '-') {
                int on = (ch == '-' ? 3 : 1) * unit;
                LessonKit.addTone(out, pos, on, TONE_HZ, 0.8f, rate, fade);
                pos += on + unit;
            } else if (ch == ' ') {
                pos += 2 * unit; // letter gap = 3 units including the element gap
            } else if (ch == '/') {
                pos += 4 * unit;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- receiver

    private final LessonKit k;
    private final LessonKit.SampleTap tap = new LessonKit.SampleTap();
    private float[] carry = new float[0];
    private float floorDb = -60;
    private boolean on;
    private int runBlocks;
    private float unitMs = 80;
    private final float[] recentOn = new float[8];
    private int recentCount;
    private final StringBuilder code = new StringBuilder();
    private final StringBuilder textOut = new StringBuilder();
    private boolean letterOpen, wordOpen;
    private static final int HISTORY = 1000; // 10 s of 10 ms blocks
    private final boolean[] history = new boolean[HISTORY];
    private int histHead;
    String sentMorse = "";

    MorseLesson(float dp) { k = new LessonKit(dp); }

    void clear() {
        code.setLength(0);
        textOut.setLength(0);
        letterOpen = wordOpen = false;
    }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        float[] fresh = tap.take(f);
        if (fresh.length == 0) return;
        int block = f.sampleRate / 100;
        float[] buf = new float[carry.length + fresh.length];
        System.arraycopy(carry, 0, buf, 0, carry.length);
        System.arraycopy(fresh, 0, buf, carry.length, fresh.length);
        int off = 0;
        for (; off + block <= buf.length; off += block) {
            double e = 0;
            for (int i = 0; i < block; i++) e += buf[off + i] * buf[off + i];
            onBlock((float) (10 * Math.log10(e / block + 1e-14)));
        }
        carry = java.util.Arrays.copyOfRange(buf, off, buf.length);
    }

    private void onBlock(float db) {
        // Slowly rising, quickly falling noise floor; hysteresis between on and off.
        floorDb = db < floorDb ? floorDb * 0.7f + db * 0.3f : floorDb + 0.02f;
        boolean now = on ? db > floorDb + 9 : db > floorDb + 15 && db > -60;
        history[histHead] = now;
        histHead = (histHead + 1) % HISTORY;
        if (now == on) {
            runBlocks++;
            float ms = runBlocks * 10;
            if (!on) {
                if (letterOpen && ms > unitMs * 2f) endLetter();
                if (wordOpen && ms > unitMs * 5f) {
                    textOut.append(' ');
                    wordOpen = false;
                }
            }
            return;
        }
        float ms = runBlocks * 10;
        if (on && ms >= 25) element(ms);
        on = now;
        runBlocks = 1;
    }

    private void element(float ms) {
        recentOn[recentCount % recentOn.length] = ms;
        recentCount++;
        int n = Math.min(recentCount, recentOn.length);
        float min = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) min = Math.min(min, recentOn[i]);
        // The shortest recent element is a dot; only trust it once both kinds have been seen.
        float max = 0;
        for (int i = 0; i < n; i++) max = Math.max(max, recentOn[i]);
        if (n >= 3 && max > min * 2) unitMs = Math.max(40, Math.min(300, min));
        code.append(ms > unitMs * 2 ? '-' : '.');
        letterOpen = true;
        wordOpen = true;
    }

    private void endLetter() {
        Character ch = (cyrillic ? DEC_CYR : DEC_LAT).get(code.toString());
        textOut.append(ch != null ? ch : '�');
        if (textOut.length() > 60) textOut.delete(0, textOut.length() - 60);
        code.setLength(0);
        letterOpen = false;
    }

    @Override public boolean animating() { return false; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Азбука Морзе", 8 * dp, 20 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, String.format(Locale.US, "Крапка ≈ %.0f мс · тире ≈ %.0f мс · %s", unitMs, unitMs * 3,
                cyrillic ? "кирилиця" : "латиниця"), 8 * dp, 36 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);

        float left = 8 * dp, right = w - 8 * dp, top = 50 * dp, bottom = top + 34 * dp;
        k.rect(c, left, top, right, bottom, 0xFF0B1220);
        float bw = (right - left) / HISTORY;
        for (int i = 0; i < HISTORY; i++) {
            if (history[(histHead + i) % HISTORY]) k.rect(c, left + i * bw, top + 4 * dp, left + (i + 1) * bw + 0.6f, bottom - 4 * dp, LessonKit.CYAN);
        }
        k.label(c, "← 10 с: смужки — звук увімкнено", left, bottom + 12 * dp, 9.5f, LessonKit.LABEL, Paint.Align.LEFT, false);

        float y = bottom + 40 * dp;
        k.label(c, "Зараз: " + (code.length() == 0 ? "—" : code.toString()), 8 * dp, y, 16, LessonKit.YELLOW, Paint.Align.LEFT, true);
        k.label(c, "Розшифровано:", 8 * dp, y + 26 * dp, 11, LessonKit.LABEL, Paint.Align.LEFT, false);
        String t = textOut.length() == 0 ? "свисніть або натисніть «Надіслати»" : textOut.toString();
        k.label(c, t, 8 * dp, y + 50 * dp, textOut.length() == 0 ? 13 : 20, textOut.length() == 0 ? LessonKit.SOFT : LessonKit.GREEN,
                Paint.Align.LEFT, true);
        if (!sentMorse.isEmpty()) {
            k.label(c, "Надіслано: " + (sentMorse.length() > 48 ? sentMorse.substring(0, 48) + "…" : sentMorse),
                    8 * dp, y + 74 * dp, 11, LessonKit.LABEL, Paint.Align.LEFT, false);
        }
        float ty = h - 40 * dp;
        String[] table = cyrillic
                ? new String[]{"А .-", "Б -...", "В .--", "Г --.", "Д -..", "Е .", "О ---", "С ...", "Т -", "Я .-.-"}
                : new String[]{"A .-", "B -...", "C -.-.", "D -..", "E .", "O ---", "S ...", "T -", "SOS ...---..."};
        float cw = (w - 16 * dp) / 5;
        for (int i = 0; i < table.length; i++) {
            k.label(c, table[i], 8 * dp + (i % 5) * cw, ty + (i / 5) * 16 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);
        }
    }
}
