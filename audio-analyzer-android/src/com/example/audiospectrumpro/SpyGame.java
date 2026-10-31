package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.Locale;

/**
 * Spies: one team encrypts a word with a Caesar cipher (every letter moved N places along the Ukrainian
 * alphabet) and sends it as sound. The other phone hears gibberish; turning the key slider until the text
 * makes sense cracks the code. Teaches what a cipher and a key are, and why short keys are weak.
 */
final class SpyGame implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); }

    static final String ABC = "АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯ";
    static final int MAX_CHARS = 20;

    boolean sender;
    int key = 3;
    private final LessonKit k;
    private final Sound sound;
    private final ModemLesson decoder;
    private String sent = "", cipher = "";
    private float clock, gotAt = -1, crackedAt = -1;
    private int tries;

    SpyGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        decoder = new ModemLesson(dp);
    }

    /** Move every letter by `shift` places (negative to decrypt); other characters stay. */
    static String caesar(String text, int shift) {
        StringBuilder b = new StringBuilder();
        int n = ABC.length();
        for (char ch : text.toUpperCase(new Locale("uk")).toCharArray()) {
            int i = ABC.indexOf(ch);
            b.append(i < 0 ? ch : ABC.charAt(((i + shift) % n + n) % n));
        }
        return b.toString();
    }

    void send(String plain) {
        String p = plain.trim().toUpperCase(new Locale("uk"));
        if (p.length() > MAX_CHARS) p = p.substring(0, MAX_CHARS);
        sent = p;
        sound.play(ModemLesson.encode("S:" + caesar(p, key), 48000), false);
    }

    void listen() {
        decoder.reset();
        decoder.clearReceived();
        cipher = "";
        gotAt = crackedAt = -1;
        tries = 0;
    }

    void setKey(int k2) {
        if (k2 != key && !sender && !cipher.isEmpty()) tries++;
        key = k2;
    }

    /** The receiver says "this is it!": time from receiving to cracking. */
    void cracked() { if (!cipher.isEmpty() && crackedAt < 0) crackedAt = clock; }

    String cipher() { return cipher; }
    String attempt() { return caesar(cipher, -key); }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        clock += (float) AudioEngine.HOP / f.sampleRate;
        if (sender) return;
        decoder.onFrame(f);
        String m = decoder.receivedText();
        if (m != null && m.startsWith("S:")) {
            decoder.clearReceived();
            cipher = m.substring(2);
            gotAt = clock;
            crackedAt = -1;
            tries = 0;
        }
    }

    @Override public boolean animating() { return !sender; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Шпигуни 🕵", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, sender ? "Ви — шифрувальник: зашифруйте і передайте звуком" : "Ви — дешифрувальник: зламайте шифр",
                8 * dp, 40 * dp, 11.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        // The cipher wheel idea as two alphabets: the top one and the one moved by the key.
        float y = 70 * dp, cell = (w - 16 * dp) / ABC.length();
        for (int i = 0; i < ABC.length(); i++) {
            float x = 8 * dp + cell * (i + 0.5f);
            k.label(c, String.valueOf(ABC.charAt(i)), x, y, 10, LessonKit.SOFT, Paint.Align.CENTER, false);
            k.label(c, String.valueOf(ABC.charAt((i + key) % ABC.length())), x, y + 16 * dp, 10, LessonKit.YELLOW, Paint.Align.CENTER, true);
        }
        k.label(c, "ключ: " + key + " (кожна буква зсувається на " + key + ")", w / 2f, y + 36 * dp, 12, LessonKit.CYAN, Paint.Align.CENTER, false);
        RectF box = new RectF(12 * dp, y + 52 * dp, w - 12 * dp, y + 190 * dp);
        k.fill.setColor(0xFF14213A);
        c.drawRoundRect(box, 12 * dp, 12 * dp, k.fill);
        if (sender) {
            k.label(c, "Таємне слово:", box.left + 12 * dp, box.top + 24 * dp, 12, LessonKit.LABEL, Paint.Align.LEFT, false);
            k.label(c, sent.isEmpty() ? "—" : sent, box.left + 12 * dp, box.top + 52 * dp, 20, LessonKit.WHITE, Paint.Align.LEFT, true);
            k.label(c, "Летить звуком:", box.left + 12 * dp, box.top + 86 * dp, 12, LessonKit.LABEL, Paint.Align.LEFT, false);
            k.label(c, sent.isEmpty() ? "—" : caesar(sent, key), box.left + 12 * dp, box.top + 116 * dp, 20, LessonKit.PINK, Paint.Align.LEFT, true);
            return;
        }
        if (cipher.isEmpty()) {
            k.label(c, "👂 Слухаю ефір…", box.centerX(), box.centerY(), 18, LessonKit.CYAN, Paint.Align.CENTER, true);
            return;
        }
        k.label(c, "Перехоплено:", box.left + 12 * dp, box.top + 24 * dp, 12, LessonKit.LABEL, Paint.Align.LEFT, false);
        k.label(c, cipher, box.left + 12 * dp, box.top + 52 * dp, 20, LessonKit.PINK, Paint.Align.LEFT, true);
        k.label(c, "З ключем " + key + " виходить:", box.left + 12 * dp, box.top + 86 * dp, 12, LessonKit.LABEL, Paint.Align.LEFT, false);
        k.label(c, attempt(), box.left + 12 * dp, box.top + 116 * dp, 20, crackedAt >= 0 ? LessonKit.GREEN : LessonKit.WHITE, Paint.Align.LEFT, true);
        String t = crackedAt >= 0 ? String.format(Locale.US, "🎉 Зламано за %.0f с і %d спроб!", crackedAt - gotAt, tries)
                : String.format(Locale.US, "⏱ %.0f с · спроб: %d · крутіть ключ, поки не вийде слово", clock - gotAt, tries);
        k.label(c, t, w / 2f, box.bottom + 26 * dp, 13, crackedAt >= 0 ? LessonKit.GREEN : LessonKit.YELLOW, Paint.Align.CENTER, true);
        k.label(c, "Підказка: найчастіші букви в українській — О, А, Н, И, І", w / 2f, box.bottom + 46 * dp, 10.5f, LessonKit.LABEL,
                Paint.Align.CENTER, false);
    }
}
