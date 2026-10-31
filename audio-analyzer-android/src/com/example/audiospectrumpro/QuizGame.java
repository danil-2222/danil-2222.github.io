package com.example.audiospectrumpro;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Sound quiz for a company: the phone plays a sound, the player (or team) whose turn it is picks one of
 * four answers on the screen. Instruments, frequencies, higher/lower, how many beeps, major or minor.
 * All sounds are synthesised, so every answer is exactly known.
 */
final class QuizGame implements LessonView {
    interface Sound { void play(float[] pcm, boolean loop); }

    static final int RATE = 48000, QUESTIONS = 10;
    static final int T_INSTRUMENT = 0, T_FREQ = 1, T_HIGHER = 2, T_BEEPS = 3, T_MODE = 4;
    static final String[] INSTRUMENTS = {"Флейта", "Скрипка", "Кларнет", "Піаніно", "Орган", "Дзвіночок"};

    static final class Question {
        int type;
        String text;
        String[] options;
        int answer;
        float[] pcm;
    }

    private final LessonKit k;
    private final Sound sound;
    private final List<String> players = new ArrayList<>();
    private int[] score;
    private java.util.Random rnd = new java.util.Random();
    private Question q;
    private int number, player, chosen = -1;
    private boolean finished;
    private final RectF[] boxes = new RectF[4];

    QuizGame(float dp, Sound sound) {
        k = new LessonKit(dp);
        this.sound = sound;
        setPlayers("Команда 1, Команда 2");
    }

    void setPlayers(String csv) {
        players.clear();
        for (String s : csv.split("[,;\n]")) if (!s.trim().isEmpty()) players.add(s.trim());
        if (players.isEmpty()) players.add("Гравець");
        while (players.size() > 6) players.remove(players.size() - 1);
        newGame(System.nanoTime());
    }

    String playersCsv() {
        StringBuilder b = new StringBuilder();
        for (String p : players) b.append(b.length() > 0 ? ", " : "").append(p);
        return b.toString();
    }

    void newGame(long seed) {
        rnd = new java.util.Random(seed);
        score = new int[players.size()];
        number = 0;
        player = 0;
        finished = false;
        q = make(rnd, rnd.nextInt(5));
        chosen = -1;
    }

    Question question() { return q; }
    int score(int p) { return score[p]; }
    boolean finished() { return finished; }
    int number() { return number; }

    void playQuestion() { if (q != null && !finished) sound.play(q.pcm, false); }

    /** Answer the current question; the next one comes with "Далі". */
    void answer(int i) {
        if (chosen >= 0 || finished) return;
        chosen = i;
        if (i == q.answer) score[player]++;
        // Let everybody hear the right answer again.
    }

    void next() {
        if (chosen < 0 && !finished) return;
        number++;
        if (number >= QUESTIONS) { finished = true; return; }
        player = (player + 1) % players.size();
        q = make(rnd, rnd.nextInt(5));
        chosen = -1;
        playQuestion();
    }

    // ---------------------------------------------------------------- questions

    static Question make(java.util.Random r, int type) {
        Question q = new Question();
        q.type = type;
        switch (type) {
            case T_INSTRUMENT: {
                int inst = r.nextInt(INSTRUMENTS.length);
                q.text = "Який це інструмент?";
                q.options = pickOptions(r, INSTRUMENTS, inst);
                q.answer = Arrays.asList(q.options).indexOf(INSTRUMENTS[inst]);
                float[] out = new float[(int) (2.2 * RATE)];
                float[] melody = {0, 4, 7, 12};
                for (int i = 0; i < melody.length; i++) {
                    instrumentNote(out, (int) (i * 0.5 * RATE), (int) (0.5 * RATE), LessonKit.midiToHz(60 + melody[i]), inst);
                }
                LessonKit.normalize(out, 0.7f);
                q.pcm = out;
                break;
            }
            case T_FREQ: {
                String[] names = {"100 Гц", "440 Гц", "1000 Гц", "4000 Гц"};
                float[] hz = {100, 440, 1000, 4000};
                int a = r.nextInt(4);
                q.text = "Яка частота цього тону?";
                q.options = names;
                q.answer = a;
                float[] out = new float[(int) (1.5 * RATE)];
                LessonKit.addTone(out, 0, out.length, hz[a], 0.6f, RATE, RATE / 50);
                q.pcm = out;
                break;
            }
            case T_HIGHER: {
                q.text = "Друга нота вища, нижча чи така сама?";
                q.options = new String[]{"Вища", "Нижча", "Така сама", "Не знаю 🙂"};
                int a = r.nextInt(3);
                float base = 57 + r.nextInt(10), step = 1 + r.nextInt(3);
                float second = a == 0 ? base + step : a == 1 ? base - step : base;
                float[] out = new float[(int) (1.8 * RATE)];
                LessonKit.addNote(out, 0, (int) (0.8 * RATE), LessonKit.midiToHz(base), 0.6f, RATE);
                LessonKit.addNote(out, (int) (0.9 * RATE), (int) (0.8 * RATE), LessonKit.midiToHz(second), 0.6f, RATE);
                q.answer = a;
                q.pcm = out;
                break;
            }
            case T_BEEPS: {
                int n = 4 + r.nextInt(6);
                q.text = "Скільки разів пікнуло?";
                int a = r.nextInt(4), first = n - a;
                q.options = new String[4];
                for (int i = 0; i < 4; i++) q.options[i] = String.valueOf(first + i);
                q.answer = a;
                float[] out = new float[(int) ((n * 0.16 + 0.5) * RATE)];
                for (int i = 0; i < n; i++) LessonKit.addTone(out, (int) (i * 0.16 * RATE), (int) (0.07 * RATE), 1500, 0.6f, RATE, RATE / 500);
                q.pcm = out;
                break;
            }
            default: {
                boolean major = r.nextBoolean();
                q.text = "Цей акорд веселий (мажор) чи сумний (мінор)?";
                q.options = new String[]{"Мажор 😊", "Мінор 😢", "Це не акорд", "Не знаю 🙂"};
                q.answer = major ? 0 : 1;
                float root = 55 + r.nextInt(8);
                float[] out = new float[(int) (2.0 * RATE)];
                float[] chord = {0, major ? 4 : 3, 7};
                for (float c : chord) LessonKit.addNote(out, 0, (int) (1.9 * RATE), LessonKit.midiToHz(root + c), 0.4f, RATE);
                LessonKit.normalize(out, 0.7f);
                q.pcm = out;
                break;
            }
        }
        return q;
    }

    private static String[] pickOptions(java.util.Random r, String[] all, int right) {
        List<String> pool = new ArrayList<>(Arrays.asList(all));
        pool.remove(all[right]);
        java.util.Collections.shuffle(pool, r);
        List<String> opts = new ArrayList<>(pool.subList(0, 3));
        opts.add(all[right]);
        java.util.Collections.shuffle(opts, r);
        return opts.toArray(new String[0]);
    }

    /** Six simple instrument timbres built from harmonics and envelopes. */
    static void instrumentNote(float[] out, int off, int len, float f, int inst) {
        for (int i = 0; i < len && off + i < out.length; i++) {
            double t = (double) i / RATE;
            double v = 0, env;
            switch (inst) {
                case 0:   // flute: almost pure, soft attack, a little breath
                    env = Math.min(1, t / 0.06) * Math.min(1, (len - i) / (RATE * 0.03));
                    v = Math.sin(2 * Math.PI * f * t) + 0.12 * Math.sin(4 * Math.PI * f * t) + 0.03 * (Math.random() * 2 - 1);
                    break;
                case 1: { // violin: sawtooth-like, vibrato
                    env = Math.min(1, t / 0.08) * Math.min(1, (len - i) / (RATE * 0.04));
                    double ff = f * (1 + 0.006 * Math.sin(2 * Math.PI * 5.5 * t));
                    for (int h = 1; h <= 12; h++) v += Math.sin(2 * Math.PI * ff * h * t) / h;
                    v *= 0.6;
                    break;
                }
                case 2:   // clarinet: odd harmonics
                    env = Math.min(1, t / 0.03) * Math.min(1, (len - i) / (RATE * 0.03));
                    for (int h = 1; h <= 11; h += 2) v += Math.sin(2 * Math.PI * f * h * t) / h;
                    break;
                case 3:   // piano: hammer attack, decay, slightly stretched harmonics
                    env = Math.exp(-t * 3) * Math.min(1, i / (RATE * 0.003));
                    for (int h = 1; h <= 8; h++) v += Math.sin(2 * Math.PI * f * h * (1 + 0.0004 * h * h) * t) * Math.exp(-t * h) / h;
                    break;
                case 4:   // organ: steady, many equal harmonics including the octave below
                    env = Math.min(1, t / 0.01) * Math.min(1, (len - i) / (RATE * 0.01));
                    v = 0.5 * Math.sin(Math.PI * f * t);
                    for (int h = 1; h <= 4; h++) v += 0.5 * Math.sin(2 * Math.PI * f * h * t);
                    break;
                default:  // bell: inharmonic partials, long ring
                    env = Math.exp(-t * 2.5) * Math.min(1, i / (RATE * 0.002));
                    double[] ratios = {1, 2.76, 5.40, 8.93};
                    for (int p = 0; p < ratios.length; p++) v += Math.sin(2 * Math.PI * f * 2 * ratios[p] * t) / (p + 1);
                    break;
            }
            out[off + i] += (float) (0.3 * env * v);
        }
    }

    // ---------------------------------------------------------------- UI

    @Override public void onFrame(AudioEngine.Frame f) { }

    @Override public boolean animating() { return false; }

    @Override
    public boolean onTouch(MotionEvent e, int w, int h) {
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        for (int i = 0; i < 4; i++) if (boxes[i] != null && boxes[i].contains(e.getX(), e.getY())) { answer(i); return true; }
        return true;
    }

    @Override
    public void draw(Canvas c, int w, int h) {
        float dp = k.dp;
        k.label(c, "Звуковий квіз", 8 * dp, 22 * dp, 16, LessonKit.WHITE, Paint.Align.LEFT, true);
        if (finished) { drawTable(c, w, h, "🏆 Підсумок"); return; }
        k.label(c, String.format(Locale.US, "Питання %d з %d · відповідає: %s", number + 1, QUESTIONS, players.get(player)),
                8 * dp, 40 * dp, 11.5f, LessonKit.SOFT, Paint.Align.LEFT, false);
        k.label(c, q.text, w / 2f, 76 * dp, 16, LessonKit.YELLOW, Paint.Align.CENTER, true);
        float bw = (w - 36 * dp) / 2, bh = 58 * dp, top = 96 * dp;
        for (int i = 0; i < 4; i++) {
            float x = 12 * dp + (i % 2) * (bw + 12 * dp), y = top + (i / 2) * (bh + 12 * dp);
            boxes[i] = new RectF(x, y, x + bw, y + bh);
            int col = 0xFF1E293B;
            if (chosen >= 0 && i == q.answer) col = 0xFF14532D;
            else if (chosen == i) col = 0xFF7F1D1D;
            k.fill.setColor(col);
            c.drawRoundRect(boxes[i], 12 * dp, 12 * dp, k.fill);
            k.label(c, q.options[i], boxes[i].centerX(), boxes[i].centerY() + 6 * dp, 16, LessonKit.WHITE, Paint.Align.CENTER, true);
        }
        float y = top + 2 * (bh + 12 * dp) + 18 * dp;
        String msg = chosen < 0 ? "🔊 «Ще раз» — послухати знову. Торкніться відповіді."
                : chosen == q.answer ? "✓ Правильно! «Далі» →" : "✗ Ні — правильна відповідь зелена. «Далі» →";
        k.label(c, msg, w / 2f, y, 13, chosen < 0 ? LessonKit.CYAN : chosen == q.answer ? LessonKit.GREEN : LessonKit.PINK, Paint.Align.CENTER, true);
        drawTable(c, w, h, "Рахунок");
    }

    private void drawTable(Canvas c, int w, int h, String title) {
        float dp = k.dp;
        Integer[] idx = new Integer[players.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { return Integer.compare(score[b], score[a]); }
        });
        float y = finished ? 70 * dp : h - (players.size() + 1) * 24 * dp - 6 * dp;
        k.label(c, title, 16 * dp, y, 14, LessonKit.WHITE, Paint.Align.LEFT, true);
        String[] medal = {"🥇", "🥈", "🥉"};
        for (int i = 0; i < idx.length; i++) {
            y += (finished ? 32 : 24) * dp;
            String place = finished && i < 3 ? medal[i] : (i + 1) + ".";
            k.label(c, place + " " + players.get(idx[i]), 16 * dp, y, finished ? 17 : 13, i == 0 ? LessonKit.YELLOW : LessonKit.SOFT,
                    Paint.Align.LEFT, i == 0);
            k.label(c, score[idx[i]] + " ✓", w - 16 * dp, y, finished ? 17 : 13, LessonKit.WHITE, Paint.Align.RIGHT, true);
        }
    }
}
