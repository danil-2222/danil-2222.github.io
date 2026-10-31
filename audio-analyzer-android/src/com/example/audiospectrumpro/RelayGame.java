package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Relay of phones (2-5). Phone 1 starts a story with a word and sends it as sound; every next phone hears
 * the story, adds its own word and passes it on. The last phone sends the finished story back so phone 1
 * can read it. In "fast" mode phones pass the message on by themselves and phone 1 times the whole lap.
 */
final class RelayGame implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); }

    static final int MAX_BYTES = 90;

    int me = 1, phones = 3;
    boolean auto;
    private final LessonKit k;
    private final Sound sound;
    private final ModemLesson decoder;
    private String story = "", status = "", lastSent = "";
    private float clock, sendAt = -1, startedAt = -1, lapS = -1;
    private String pendingSend;
    private boolean finished;

    RelayGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        decoder = new ModemLesson(dp);
        reset();
    }

    void reset() {
        decoder.reset();
        decoder.clearReceived();
        story = "";
        finished = false;
        lapS = -1;
        startedAt = -1;
        sendAt = -1;
        pendingSend = null;
        status = me == 1 ? "Напишіть перше слово і натисніть «📡 Передати»" : "👂 Чекаю на телефон " + (me - 1) + "…";
    }

    static int bytes(String s) { return s.getBytes(Charset.forName("UTF-8")).length; }

    /** Add my word to the story and send it on. Returns false if the story would be too long. */
    boolean addAndSend(String word) {
        String w = word.trim();
        if (me != 1 && story.isEmpty()) { status = "Ще нічого не прийшло — зачекайте"; return false; }
        String next = (story.isEmpty() ? w : w.isEmpty() ? story : story + " " + w);
        if (bytes("R9:" + next) > MAX_BYTES) { status = "Задовго! Коротше слово, будь ласка"; return false; }
        story = next;
        send((me == phones ? "DONE:" : "R" + me + ":") + story);
        if (me == 1) startedAt = sendAt;
        return true;
    }

    private void send(String msg) {
        lastSent = msg;
        float[] pcm = ModemLesson.encode(msg, 48000);
        sound.play(pcm, false);
        sendAt = clock + pcm.length / 48000f;
        status = me == phones ? "🏁 Відправляю готову історію телефону 1…" : "📡 Передаю телефону " + (me + 1) + "…";
    }

    String story() { return story; }
    String lastSent() { return lastSent; }
    boolean finished() { return finished; }
    float lapSeconds() { return lapS; }

    @Override
    public void onFrame(AudioEngine.Frame f) {
        clock += (float) AudioEngine.HOP / f.sampleRate;
        if (pendingSend != null && clock >= sendAt) { String m = pendingSend; pendingSend = null; send(m); }
        if (clock < sendAt) return;                      // do not listen to ourselves
        decoder.onFrame(f);
        String m = decoder.receivedText();
        if (m == null) return;
        decoder.clearReceived();
        if (me == 1 && m.startsWith("DONE:")) {
            story = m.substring(5);
            finished = true;
            if (startedAt >= 0) lapS = clock - startedAt;
            status = lapS > 0 ? String.format(Locale.US, "🏁 Історія повернулась! Коло за %.1f с", lapS) : "🏁 Історія повернулась!";
            return;
        }
        String want = "R" + (me - 1) + ":";
        if (me > 1 && m.startsWith(want)) {
            story = m.substring(want.length());
            if (auto) {
                pendingSend = (me == phones ? "DONE:" : "R" + me + ":") + story;
                sendAt = clock + 0.6f;
                status = "Отримав — передаю далі автоматично";
            } else {
                status = "Прийшла історія! Додайте своє слово і «📡 Передати»";
            }
        }
    }

    @Override public boolean animating() { return true; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Естафета історії", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "ви — телефон " + me + " з " + phones + (auto ? " · швидка естафета" : " · кожен додає слово"), 8 * dp, 40 * dp, 11.5f,
                LessonKit.SOFT, Paint.Align.LEFT, false);
        // Chain of phones with the current one highlighted.
        float y = 76 * dp, gap = (w - 40 * dp) / Math.max(1, phones - 1);
        for (int i = 1; i <= phones; i++) {
            float x = 20 * dp + gap * (i - 1);
            if (i < phones) k.line(c, x, y, x + gap, y, LessonKit.GRID, 3);
            k.fill.setColor(i == me ? LessonKit.CYAN : 0xFF1E293B);
            c.drawRoundRect(new RectF(x - 12 * dp, y - 18 * dp, x + 12 * dp, y + 18 * dp), 5 * dp, 5 * dp, k.fill);
            k.label(c, String.valueOf(i), x, y + 6 * dp, 14, i == me ? 0xFF0B1220 : LessonKit.SOFT, Paint.Align.CENTER, true);
        }
        RectF box = new RectF(12 * dp, 112 * dp, w - 12 * dp, 240 * dp);
        k.fill.setColor(finished ? 0xFF14532D : 0xFF14213A);
        c.drawRoundRect(box, 12 * dp, 12 * dp, k.fill);
        k.label(c, finished ? "Історія повністю:" : "Історія зараз:", box.left + 12 * dp, box.top + 22 * dp, 12, LessonKit.LABEL,
                Paint.Align.LEFT, false);
        drawWrapped(c, story.isEmpty() ? "…" : story, box.left + 12 * dp, box.top + 52 * dp, box.width() - 24 * dp, 19);
        k.label(c, status, w / 2f, box.bottom + 28 * dp, 13, status.startsWith("🏁") ? LessonKit.GREEN : LessonKit.YELLOW,
                Paint.Align.CENTER, true);
        k.label(c, String.format(Locale.US, "довжина %d з %d байт", bytes(story), MAX_BYTES - 6), w / 2f, box.bottom + 48 * dp, 10.5f,
                LessonKit.LABEL, Paint.Align.CENTER, false);
    }

    private void drawWrapped(Canvas c, String s, float x, float y, float maxW, float size) {
        k.text.setTextSize(size * k.dp);
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        for (String wd : words) {
            String t = line.length() == 0 ? wd : line + " " + wd;
            if (k.text.measureText(t) > maxW && line.length() > 0) {
                k.label(c, line.toString(), x, y, size, LessonKit.WHITE, Paint.Align.LEFT, true);
                y += size * 1.4f * k.dp;
                line = new StringBuilder(wd);
            } else {
                line = new StringBuilder(t);
            }
        }
        k.label(c, line.toString(), x, y, size, LessonKit.WHITE, Paint.Align.LEFT, true);
    }
}
