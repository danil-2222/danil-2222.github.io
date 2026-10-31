package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Живе піаніно": every player is one key. A song scrolls on the screen; when your note reaches the line
 * you sing it (any octave — children and adults sing together). The phone recognises the note name of
 * the voice and scores every player and the whole team.
 */
final class HumanPianoGame implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); }

    enum State { READY, INTRO, PLAY, RESULT }

    static final int RATE = 48000;
    static final float BEAT_S = 0.75f, INTRO_NOTE_S = 1.1f, COUNT_BEATS = 4;
    static final String[] NOTE_NAMES = {"до", "ре", "мі", "фа", "соль", "ля"};
    static final int[] NOTE_PC = {0, 2, 4, 5, 7, 9};
    static final int[] COLORS = {0xFFFF5252, 0xFFFFAB40, 0xFFFFEB3B, 0xFF69F0AE, 0xFF40C4FF, 0xFFB388FF};
    static final String[] SONGS = {"Жили у бабусі", "Зірочка (Twinkle)", "Гама вгору і вниз"};
    /** {note index, beats}. */
    static final int[][][] SONG_DATA = {
            {{3, 1}, {2, 1}, {1, 1}, {0, 1}, {4, 2}, {4, 2}, {3, 1}, {2, 1}, {1, 1}, {0, 1}, {4, 2}, {4, 2},
                    {3, 1}, {5, 1}, {5, 1}, {3, 1}, {2, 1}, {4, 1}, {4, 1}, {2, 1}, {1, 1}, {2, 1}, {3, 1}, {1, 1}, {0, 2}, {0, 2}},
            {{0, 1}, {0, 1}, {4, 1}, {4, 1}, {5, 1}, {5, 1}, {4, 2}, {3, 1}, {3, 1}, {2, 1}, {2, 1}, {1, 1}, {1, 1}, {0, 2}},
            {{0, 1}, {1, 1}, {2, 1}, {3, 1}, {4, 1}, {5, 1}, {4, 1}, {3, 1}, {2, 1}, {1, 1}, {0, 2}},
    };

    int song;
    private final LessonKit k;
    private final Sound sound;
    private final List<String> players = new ArrayList<>();
    private State state = State.READY;
    private float clock, startAt, songAt;
    private int[][] notes;          // {note, startBeat*100, beats}
    private int[] judged;           // 0 = pending, 1 = hit, -1 = missed
    private int[] good, frames;     // per note: frames with the right pitch class, frames heard
    private int sungPc = -1;
    private float sungHz;

    HumanPianoGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        setPlayers("Гравець 1, Гравець 2, Гравець 3");
    }

    void setPlayers(String csv) {
        players.clear();
        for (String s : csv.split("[,;\n]")) if (!s.trim().isEmpty()) players.add(s.trim());
        if (players.isEmpty()) players.add("Гравець 1");
        while (players.size() > 6) players.remove(players.size() - 1);
        state = State.READY;
    }

    String playersCsv() {
        StringBuilder b = new StringBuilder();
        for (String p : players) b.append(b.length() > 0 ? ", " : "").append(p);
        return b.toString();
    }

    /** Distinct notes of the current song, in order 0..5. */
    static List<Integer> notesUsed(int song) {
        boolean[] u = new boolean[6];
        for (int[] n : SONG_DATA[song]) u[n[0]] = true;
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < 6; i++) if (u[i]) out.add(i);
        return out;
    }

    /** Which player sings note i. */
    int playerOf(int note) {
        List<Integer> used = notesUsed(song);
        return used.indexOf(note) % players.size();
    }

    static float introSeconds(int song) { return notesUsed(song).size() * INTRO_NOTE_S + COUNT_BEATS * BEAT_S; }

    /** Seconds from start() to the first note of the song. */
    static float songOffset(int song) { return introSeconds(song); }

    static float noteHz(int note) { return LessonKit.midiToHz(60 + NOTE_PC[note]); }

    void start() {
        int[][] d = SONG_DATA[song];
        notes = new int[d.length][3];
        int beat = 0;
        for (int i = 0; i < d.length; i++) {
            notes[i][0] = d[i][0];
            notes[i][1] = beat;
            notes[i][2] = d[i][1];
            beat += d[i][1];
        }
        judged = new int[d.length];
        good = new int[d.length];
        frames = new int[d.length];
        // Intro: every key once (so each player hears their note), then a count-in.
        List<Integer> used = notesUsed(song);
        float[] pcm = new float[(int) (introSeconds(song) * RATE) + RATE / 2];
        for (int i = 0; i < used.size(); i++) {
            LessonKit.addNote(pcm, (int) (i * INTRO_NOTE_S * RATE), (int) (0.9f * RATE), noteHz(used.get(i)), 0.5f, RATE);
        }
        int countAt = (int) (used.size() * INTRO_NOTE_S * RATE);
        for (int b = 0; b < COUNT_BEATS; b++) {
            LessonKit.addTone(pcm, countAt + (int) (b * BEAT_S * RATE), RATE / 30, b == COUNT_BEATS - 1 ? 1800 : 1200, 0.4f, RATE, RATE / 400);
        }
        sound.play(pcm, false);
        startAt = clock;
        songAt = clock + songOffset(song);
        state = State.INTRO;
    }

    String state() { return state.name(); }

    /** Share of notes hit by player p (0..1), or NaN if the player had no notes. */
    float accuracy(int p) {
        int n = 0, hit = 0;
        for (int i = 0; i < notes.length; i++) {
            if (playerOf(notes[i][0]) != p) continue;
            n++;
            if (judged[i] == 1) hit++;
        }
        return n == 0 ? Float.NaN : (float) hit / n;
    }

    float teamAccuracy() {
        int hit = 0;
        for (int j : judged) if (j == 1) hit++;
        return (float) hit / judged.length;
    }

    int[] judged() { return judged; }

    // ---------------------------------------------------------------- listening

    @Override
    public void onFrame(AudioEngine.Frame f) {
        clock += (float) AudioEngine.HOP / f.sampleRate;
        float hz = f.rmsDb - f.calibrationDb > -50 ? LessonRenderer.yin(f.time, f.sampleRate) : 0;
        sungHz = hz;
        sungPc = hz > 60 ? ((Math.round(69 + 12 * (float) (Math.log(hz / 440.0) / Math.log(2))) % 12) + 12) % 12 : -1;
        if (state == State.INTRO && clock >= songAt) state = State.PLAY;
        if (state != State.PLAY) return;
        // The window analysed by YIN ends now, so judge against a slightly earlier moment (half a window).
        float t = clock - songAt - 0.5f * f.time.length / f.sampleRate;
        for (int i = 0; i < notes.length; i++) {
            if (judged[i] != 0) continue;
            float t0 = notes[i][1] * BEAT_S, t1 = (notes[i][1] + notes[i][2]) * BEAT_S;
            // Judge the body of the note (a little late: phones and singers both lag), not its edges.
            if (t >= t0 + 0.05f && t <= t1 + 0.1f) {
                frames[i]++;
                if (sungPc == NOTE_PC[notes[i][0]]) good[i]++;
            }
            if (t > t1 + 0.1f) {
                int span = Math.max(1, frames[i]);
                judged[i] = good[i] >= 0.45f * span ? 1 : -1;
            }
        }
        if (judged[judged.length - 1] != 0) state = State.RESULT;
    }

    // ---------------------------------------------------------------- drawing

    @Override public boolean animating() { return state == State.INTRO || state == State.PLAY; }

    @Override public boolean onTouch(MotionEvent e, int w, int h) { return false; }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Живе піаніно — «" + SONGS[song] + "»", 8 * dp, 22 * dp, 15, LessonKit.WHITE, Paint.Align.LEFT, true);
        k.label(c, "Кожен — одна клавіша. Співайте свою ноту, коли вона на лінії", 8 * dp, 40 * dp, 11, LessonKit.SOFT, Paint.Align.LEFT, false);
        List<Integer> used = notesUsed(song);
        if (state == State.RESULT) { drawResult(c, w, h, used); return; }

        // Lanes: one per note, labelled with the player.
        float top = 56 * dp, bottom = h - 70 * dp, laneH = (bottom - top) / used.size();
        float lineX = w * 0.2f, pxPerS = (w - lineX) / 4f;
        float t = state == State.PLAY ? clock - songAt : state == State.INTRO ? clock - songAt : -99;
        for (int li = 0; li < used.size(); li++) {
            int note = used.get(li);
            float y0 = bottom - (li + 1) * laneH;
            k.rect(c, 0, y0 + 1, w, y0 + laneH - 1, li % 2 == 0 ? 0xFF0B1220 : 0xFF0E1628);
            boolean singing = sungPc == NOTE_PC[note];
            k.label(c, NOTE_NAMES[note], 6 * dp, y0 + laneH * 0.45f, 13, COLORS[note], Paint.Align.LEFT, true);
            k.label(c, players.get(playerOf(note)), 6 * dp, y0 + laneH * 0.45f + 14 * dp, 10, singing ? LessonKit.WHITE : LessonKit.LABEL,
                    Paint.Align.LEFT, singing);
            if (singing) k.rect(c, lineX - 6 * dp, y0 + 3, lineX, y0 + laneH - 3, COLORS[note]);
        }
        k.line(c, lineX, top, lineX, bottom, LessonKit.WHITE, 2.5f);
        if (notes != null) {
            for (int i = 0; i < notes.length; i++) {
                float x0 = lineX + (notes[i][1] * BEAT_S - t) * pxPerS, x1 = x0 + notes[i][2] * BEAT_S * pxPerS - 4 * dp;
                if (x1 < lineX || x0 > w) continue;
                int li = used.indexOf(notes[i][0]);
                float y0 = bottom - (li + 1) * laneH;
                int col = judged[i] == 1 ? LessonKit.GREEN : judged[i] == -1 ? 0x55FF007F : COLORS[notes[i][0]];
                k.fill.setColor(col);
                c.drawRoundRect(new RectF(Math.max(x0, lineX), y0 + laneH * 0.22f, x1, y0 + laneH * 0.78f), 6 * dp, 6 * dp, k.fill);
            }
        }
        String msg;
        if (state == State.READY) msg = "Розподіліть ноти і натисніть «▶ Грати»";
        else if (state == State.INTRO && clock - startAt < used.size() * INTRO_NOTE_S) {
            int i = Math.min(used.size() - 1, (int) ((clock - startAt) / INTRO_NOTE_S));
            msg = "Запам'ятайте: " + players.get(playerOf(used.get(i))) + " — «" + NOTE_NAMES[used.get(i)] + "»";
        } else if (state == State.INTRO) msg = "Готуємось… " + (int) Math.ceil(songAt - clock);
        else msg = sungPc >= 0 ? String.format(Locale.US, "Чую %.0f Гц", sungHz) : "Співайте!";
        k.label(c, msg, w / 2f, h - 40 * dp, 15, LessonKit.YELLOW, Paint.Align.CENTER, true);
    }

    private void drawResult(Canvas c, int w, int h, List<Integer> used) {
        float dp = k.dp;
        float team = teamAccuracy();
        int stars = team >= 0.9f ? 3 : team >= 0.7f ? 2 : team >= 0.4f ? 1 : 0;
        k.label(c, "★★★".substring(0, stars) + "☆☆☆".substring(stars), w / 2f, 92 * dp, 34, LessonKit.YELLOW, Paint.Align.CENTER, true);
        k.label(c, String.format(Locale.US, "Команда влучила в %.0f%% нот", team * 100), w / 2f, 122 * dp, 16, LessonKit.WHITE, Paint.Align.CENTER, true);
        float y = 160 * dp;
        for (int p = 0; p < players.size(); p++) {
            float a = accuracy(p);
            if (Float.isNaN(a)) continue;
            StringBuilder ns = new StringBuilder();
            for (int note : used) if (playerOf(note) == p) ns.append(ns.length() > 0 ? ", " : "").append(NOTE_NAMES[note]);
            k.label(c, players.get(p) + " (" + ns + ")", 20 * dp, y, 14, LessonKit.WHITE, Paint.Align.LEFT, false);
            k.label(c, String.format(Locale.US, "%.0f%%", a * 100), w - 20 * dp, y, 15, a >= 0.8f ? LessonKit.GREEN : a >= 0.5f
                    ? LessonKit.YELLOW : LessonKit.PINK, Paint.Align.RIGHT, true);
            y += 26 * dp;
        }
        // The song as coloured blocks: green — hit, pink — missed.
        float x = 16 * dp, bw = (w - 32 * dp) / Math.max(1, notes.length);
        for (int i = 0; i < notes.length; i++) {
            k.rect(c, x + i * bw + 1, y + 10 * dp, x + (i + 1) * bw - 1, y + 26 * dp, judged[i] == 1 ? LessonKit.GREEN : LessonKit.PINK);
        }
        k.label(c, "«▶ Грати» — ще раз", w / 2f, h - 24 * dp, 13, LessonKit.CYAN, Paint.Align.CENTER, false);
    }
}
