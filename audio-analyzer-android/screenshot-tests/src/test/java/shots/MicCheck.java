package shots;

import android.Manifest;
import android.graphics.RectF;
import android.app.Application;
import android.os.Looper;
import android.view.View;

import com.example.audiospectrumpro.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAudioRecord;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

import static org.robolectric.Shadows.shadowOf;

/** Every display mode and every lesson, fed through a simulated microphone. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
public class MicCheck {
    static final int RATE = 48000;
    static final List<String> report = new ArrayList<>();

    // ---------------------------------------------------------------- programmable microphone

    static volatile float[] signal = new float[0];
    static volatile boolean loop = true;
    static volatile int version;

    static final class Mic implements ShadowAudioRecord.AudioRecordSource {
        int pos, seen = -1;
        @Override public int readInShortArray(short[] buf, int off, int len, boolean blocking) {
            float[] s = signal;
            if (seen != version) { seen = version; pos = 0; }
            for (int i = 0; i < len; i++) {
                if (pos >= s.length && loop && s.length > 0) pos = 0;
                float v = pos < s.length ? s[pos++] : 0;
                buf[off + i] = (short) Math.max(-32768, Math.min(32767, v * 32767));
            }
            try { Thread.sleep(len * 1000L / RATE); } catch (InterruptedException ignored) {}
            return len;
        }
    }

    static void mic(float[] s, boolean repeat) {
        loop = repeat;
        signal = s;
        version++;
    }

    static void installMic() {
        final Map<android.media.AudioRecord, Mic> mics = java.util.Collections.synchronizedMap(new WeakHashMap<android.media.AudioRecord, Mic>());
        ShadowAudioRecord.setSourceProvider(new ShadowAudioRecord.AudioRecordSourceProvider() {
            @Override public ShadowAudioRecord.AudioRecordSource get(android.media.AudioRecord r) {
                Mic m = mics.get(r);
                if (m == null) { m = new Mic(); mics.put(r, m); }
                return m;
            }
        });
    }

    // ---------------------------------------------------------------- test signals

    static float[] tone(double hz, double sec, float amp) {
        float[] x = new float[(int) (sec * RATE)];
        for (int i = 0; i < x.length; i++) x[i] = (float) (amp * Math.sin(2 * Math.PI * hz * i / RATE));
        return x;
    }

    static float[] voice(double hz, double sec, float amp) {
        float[] x = new float[(int) (sec * RATE)];
        double ph = 0;
        for (int i = 0; i < x.length; i++) {
            double t = (double) i / RATE;
            ph += 2 * Math.PI * hz * (1 + 0.008 * Math.sin(2 * Math.PI * 5 * t)) / RATE;
            double v = 0;
            for (int h = 1; h <= 10; h++) v += Math.sin(h * ph) / h * (h == 3 ? 1.6 : 1);
            x[i] = (float) (amp * v * 0.5 * Math.min(1, t / 0.03));
        }
        return x;
    }

    static float[] concat(float[]... parts) {
        int n = 0;
        for (float[] p : parts) n += p.length;
        float[] out = new float[n];
        int o = 0;
        for (float[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }

    static float[] mix(float[] a, float[] b) {
        float[] out = new float[Math.max(a.length, b.length)];
        for (int i = 0; i < out.length; i++) out[i] = (i < a.length ? a[i] : 0) + (i < b.length ? b[i] : 0);
        return out;
    }

    static float[] silence(double sec) { return new float[(int) (sec * RATE)]; }

    static void burst(float[] x, int at, float amp, double decayS, Random r) {
        for (int i = 0; at + i < x.length && i < decayS * RATE * 3; i++) {
            x[at + i] += (float) ((r.nextDouble() * 2 - 1) * amp * Math.pow(10, -3 * i / (decayS * RATE)));
        }
    }

    static float[] noise(double sec, float amp) {
        Random r = new Random(1);
        float[] x = new float[(int) (sec * RATE)];
        float b = 0;
        for (int i = 0; i < x.length; i++) { b = 0.97f * b + 0.03f * (r.nextFloat() * 2 - 1); x[i] = (r.nextFloat() * 2 - 1) * amp * 0.5f + b * amp * 3; }
        return x;
    }

    // ---------------------------------------------------------------- helpers

    static Object call(Object t, String m, Class<?>[] types, Object... args) throws Exception {
        Method me = findMethod(t.getClass(), m, types);
        me.setAccessible(true);
        return me.invoke(t, args);
    }

    static Method findMethod(Class<?> c, String m, Class<?>[] types) throws Exception {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { return k.getDeclaredMethod(m, types); } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(m);
    }

    static Object get(Object o, String name) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    static Object staticCall(String cls, String m, Class<?>[] types, Object... args) throws Exception {
        Method me = Class.forName("com.example.audiospectrumpro." + cls).getDeclaredMethod(m, types);
        me.setAccessible(true);
        return me.invoke(null, args);
    }

    static Object staticField(String cls, String name) throws Exception {
        Field f = Class.forName("com.example.audiospectrumpro." + cls).getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    static void run(long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle(); }
    }

    static int id(MainActivity a, String name) { return a.getResources().getIdentifier(name, "id", a.getPackageName()); }

    static void select(MainActivity a, String spinner, int pos) {
        ((android.widget.Spinner) a.findViewById(id(a, spinner))).setSelection(pos);
        shadowOf(Looper.getMainLooper()).idle();
    }

    static void click(MainActivity a, String name) {
        a.findViewById(id(a, name)).performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    static void clickText(MainActivity a, String text) {
        View v = Shots.findText(a.getWindow().getDecorView(), text);
        if (v == null) throw new AssertionError("no view with text " + text);
        v.performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    static void lesson(MainActivity a, int n) throws Exception {
        click(a, "tabLearn");
        call(a, "openLesson", new Class<?>[]{int.class, String.class}, n, "Урок " + n);
        shadowOf(Looper.getMainLooper()).idle();
    }

    static void back(MainActivity a) {
        View b = a.findViewById(id(a, "btnLearnBack"));
        if (b.isShown()) { b.performClick(); shadowOf(Looper.getMainLooper()).idle(); }
    }

    static Object viz(MainActivity a) { return a.findViewById(id(a, "visualizer2D")); }

    static void shot(MainActivity a, String name) throws Exception { Shots.shot(a, name); }

    static void check(String what, boolean ok, String detail) {
        String line = (ok ? "OK   " : "FAIL ") + what + " — " + detail;
        report.add(line);
        System.out.println("CHECK " + line);
    }

    static String sourceOf(MainActivity a) throws Exception { return String.valueOf(get(get(a, "engine"), "sourceType")); }

    static float peakHz(MainActivity a) throws Exception {
        Object f = get(a, "lastFrame");
        return f == null ? -1 : (Float) get(f, "peakFreqHz");
    }

    // ---------------------------------------------------------------- the check

    @Test
    public void everythingThroughTheMicrophone() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Random r = new Random(3);

        // 1. Analyzer display modes 0..6 and live learning modes 7..16 with a sung G3.
        mic(voice(196, 3, 0.25f), true);
        String[] modeNames = {"Спектр лінія", "Стовпчики", "RTA", "Спектрограма", "3D", "Осцилограф", "Тюнер", "Фур'є", "Мелодія",
                "Нотне коло", "Повітря", "Луна", "Равлик", "Струна", "Огинаюча", "Хладні", "Рівна гучність"};
        for (int m = 0; m <= 16; m++) {
            select(a, "spinMode", m);
            run(m == 3 ? 3000 : 1800);
            float p = peakHz(a);
            boolean ok = "MIC".equals(sourceOf(a)) && p > 185 && p < 210 || (m >= 7 && p > 380 && p < 420);
            if (m == 4) {
                Object v3 = a.findViewById(id(a, "visualizer3D"));
                int head = (Integer) get(v3, "head");
                run(500);
                ok = ok && head != (Integer) get(v3, "head");
                check("Режим " + m + " " + modeNames[m], ok, "3D отримує кадри: head " + head + " → " + get(v3, "head") + ", пік " + p);
                continue;
            }
            shot(a, String.format("70_mode_%02d", m));
            check("Режим " + m + " " + modeNames[m], ok, "джерело " + sourceOf(a) + ", пік " + p + " Гц");
        }
        Object vz = viz(a);
        select(a, "spinMode", 8);
        run(1500);
        Object lr = get(vz, "lesson");
        float pitch = (Float) get(lr, "lastPitchHz");
        check("Мелодія: висота голосу", Math.abs(pitch - 196) < 4, pitch + " Гц (очікується 196)");
        mic(concat(voice(261.6, 2, 0.15f), mix(voice(329.6, 2, 0.15f), mix(voice(261.6, 2, 0.15f), voice(392, 2, 0.15f)))), true);
        select(a, "spinMode", 9);
        mic(mix(voice(261.6, 4, 0.12f), mix(voice(329.6, 4, 0.12f), voice(392, 4, 0.12f))), true);
        run(2500);
        check("Нотне коло: акорд до-мі-соль", String.valueOf(get(lr, "chordName")).startsWith("до мажор"), "розпізнано «" + get(lr, "chordName") + "»");
        shot(a, "71_chord");

        // 2. Lessons that listen to the microphone.
        mic(tone(1500, 2, 0.3f), true);
        lesson(a, 1); run(1500); shot(a, "72_l01_whistle");
        check("Урок 1 Чистий тон (свист)", "MIC".equals(sourceOf(a)) && Math.abs(peakHz(a) - 1500) < 5, "пік " + peakHz(a));
        back(a);
        mic(voice(220, 3, 0.25f), true);
        lesson(a, 2); run(1500); shot(a, "73_l02_voice");
        Object ex = call(vz, "getExplanation", new Class<?>[]{});
        check("Урок 2 Гармоніки (голос)", ex != null && Math.abs((Float) get(ex, "f0") - 220) < 4,
                "f0 = " + (ex == null ? "—" : get(ex, "f0")) + ", гармонік " + (ex == null ? 0 : get(ex, "harmonicCount")));
        back(a);
        mic(noise(3, 0.2f), true);
        lesson(a, 5); run(1500); shot(a, "74_l05_noise");
        ex = call(vz, "getExplanation", new Class<?>[]{});
        check("Урок 5 Шум", ex != null && "NOISE".equals(String.valueOf(get(ex, "kind"))), "визначено як " + (ex == null ? "—" : get(ex, "kind")));
        back(a);
        mic(voice(196, 3, 0.25f), true);
        for (int[] l : new int[][]{{10, 0}, {23, 0}, {24, 0}}) {
            lesson(a, l[0]); run(1500); shot(a, "75_l" + l[0]);
            check("Урок " + l[0] + " живий голос", "MIC".equals(sourceOf(a)) && peakHz(a) > 0, "джерело " + sourceOf(a) + ", пік " + peakHz(a));
            back(a);
        }
        // Envelope: a plucked note.
        float[] pluck = silence(3);
        staticCall("LessonKit", "addNote", new Class<?>[]{float[].class, int.class, int.class, double.class, float.class, int.class},
                pluck, RATE / 2, RATE * 2, 220.0, 0.6f, RATE);
        mic(pluck, true);
        lesson(a, 25); run(3500); shot(a, "76_l25_envelope");
        Object env = get(a, "envelopeLesson");
        check("Урок 25 Огинаюча (щипок)", String.valueOf(get(env, "verdict")).startsWith("Удар або щипок"), "«" + get(env, "verdict") + "»");
        back(a);
        // Room echo: claps with RT60 = 0.6 s.
        float[] claps = silence(4);
        burst(claps, RATE / 2, 0.7f, 0.6, r);
        mic(claps, true);
        lesson(a, 11); run(4500); shot(a, "77_l11_echo");
        float rt = (Float) get(get(vz, "lesson"), "rt60");
        check("Урок 11 Луна (RT60 = 0,6 с)", Math.abs(rt - 0.6f) < 0.15f, "виміряно " + rt + " с");
        back(a);
        // Speed of sound: clap and echo from 10 m.
        float[] speed = silence(3);
        burst(speed, RATE / 2, 0.8f, 0.01, r);
        burst(speed, RATE / 2 + Math.round(2 * 10 / 343f * RATE), 0.2f, 0.01, r);
        mic(speed, true);
        lesson(a, 18); run(3500); shot(a, "78_l18_speed");
        float c = (Float) get(get(a, "speedLesson"), "speed");
        check("Урок 18 Швидкість звуку", Math.abs(c - 343) < 30, "виміряно " + c + " м/с");
        back(a);
        // Bottle: a breathy 225 Hz.
        mic(mix(tone(225, 3, 0.2f), noise(3, 0.01f)), true);
        lesson(a, 15); run(2000); shot(a, "79_l15_bottle");
        float bottle = (Float) get(get(a, "bottleLesson"), "heldHz");
        check("Урок 15 Музична пляшка", Math.abs(bottle - 225) < 5, "почуто " + bottle + " Гц");
        back(a);
        // Chladni following a whistle.
        mic(tone(900, 3, 0.3f), true);
        lesson(a, 26); run(1500); shot(a, "80_l26_chladni");
        float heard = (Float) get(get(a, "chladniLesson"), "heardHz");
        check("Урок 26 Хладні (свист 900 Гц)", Math.abs(heard - 900) < 10, "чую " + heard + " Гц");
        back(a);
        // Room modes: a 76 Hz tone getting louder and quieter as if walking.
        float[] room = tone(76, 6, 0.3f);
        for (int i = 0; i < room.length; i++) room[i] *= (float) (0.55 + 0.45 * Math.sin(2 * Math.PI * i / (3.0 * RATE)));
        mic(room, true);
        lesson(a, 17);
        Object rl = get(a, "roomLesson");
        Field th = rl.getClass().getDeclaredField("toneHz"); th.setAccessible(true); th.set(rl, 76f);
        run(4000); shot(a, "81_l17_room");
        float max = (Float) get(rl, "maxSeen"), min = (Float) get(rl, "minSeen");
        check("Урок 17 Стоячі хвилі", max - min > 6, String.format("різниця гучних і тихих місць %.1f дБ", max - min));
        back(a);
        // Loudness contours.
        mic(noise(3, 0.2f), true);
        lesson(a, 29); run(1500); shot(a, "82_l29_loudness");
        check("Урок 29 Що чує вухо", "MIC".equals(sourceOf(a)), "джерело " + sourceOf(a));
        back(a);
        // Pitch trail lesson and note circle lesson.
        mic(voice(246.9, 3, 0.25f), true);
        lesson(a, 7); run(1500); shot(a, "83_l07_pitch");
        check("Урок 7 Мелодія голосу (сі 247 Гц)", Math.abs((Float) get(get(vz, "lesson"), "lastPitchHz") - 246.9f) < 4,
                get(get(vz, "lesson"), "lastPitchHz") + " Гц");
        back(a);
        mic(mix(voice(220, 4, 0.12f), mix(voice(261.6, 4, 0.12f), voice(329.6, 4, 0.12f))), true);
        lesson(a, 8); run(2500); shot(a, "84_l08_chord");
        check("Урок 8 Нотне коло (ля-до-мі)", String.valueOf(get(get(vz, "lesson"), "chordName")).startsWith("ля мінор"),
                "«" + get(get(vz, "lesson"), "chordName") + "»");
        back(a);
        // Modem from "another phone".
        float[] modem = (float[]) staticCall("ModemLesson", "encode", new Class<?>[]{String.class, int.class}, "Тест 42 ✓", RATE);
        for (int i = 0; i < modem.length; i++) modem[i] *= 0.5f;
        lesson(a, 14);
        mic(concat(silence(0.5), modem, silence(1)), false);
        run(9000); shot(a, "85_l14_modem");
        Object ml = get(a, "modemLesson");
        check("Урок 14 Модем через мікрофон", "Тест 42 ✓".equals(get(ml, "received")) && (Boolean) get(ml, "receivedOk"),
                "отримано «" + get(ml, "received") + "»");
        back(a);
        // Morse from "another phone".
        float[] morse = (float[]) staticCall("MorseLesson", "encode", new Class<?>[]{String.class, int.class}, "МИР", RATE);
        for (int i = 0; i < morse.length; i++) morse[i] *= 0.5f;
        lesson(a, 19);
        mic(concat(silence(0.5), morse, silence(1.5)), false);
        run(9000); shot(a, "86_l19_morse");
        check("Урок 19 Морзе через мікрофон", String.valueOf(get(get(a, "morseLesson"), "textOut")).contains("МИР"),
                "розшифровано «" + get(get(a, "morseLesson"), "textOut") + "»");
        back(a);
        // Rhythm: metronome clicks heard by the mic plus claps 40 ms late.
        lesson(a, 21);
        float[] bar = (float[]) staticCall("RhythmLesson", "metronome", new Class<?>[]{int.class, int.class}, 90, RATE);
        int beat = Math.round(60f / 90 * RATE);
        for (int b = 0; b < 4; b++) burst(bar, b * beat + Math.round(0.04f * RATE), 0.6f, 0.02, r);
        mic(bar, true);
        run(7000); shot(a, "87_l21_rhythm");
        Object rh = get(a, "rhythmLesson");
        int cnt = Math.min((Integer) get(rh, "offsetCount"), 24);
        float[] offs = (float[]) get(rh, "offsets");
        float mean = 0;
        for (int i = 0; i < cnt; i++) mean += offs[i];
        mean = cnt > 0 ? mean / cnt : Float.NaN;
        check("Урок 21 Ритм (оплески +40 мс)", cnt > 3 && Math.abs(mean - 40) < 10, String.format("оплесків %d, в середньому %+.0f мс", cnt, mean));
        back(a);
        // Ear trainer, sing back mode.
        lesson(a, 20);
        Object ear = get(a, "earLesson");
        Class<?> game = Class.forName("com.example.audiospectrumpro.EarLesson$Game");
        call(ear, "setGame", new Class<?>[]{game}, Enum.valueOf((Class) game, "SING"));
        call(ear, "newTask", new Class<?>[]{});
        int target = (Integer) get(ear, "targetMidi");
        mic(voice(440 * Math.pow(2, (target - 69) / 12.0), 3, 0.25f), true);
        run(2500); shot(a, "88_l20_sing");
        check("Урок 20 Повтори ноту голосом", (Integer) get(ear, "score") >= 1, "ціль MIDI " + target + ", рахунок " + get(ear, "score"));
        back(a);
        // Voice effects from the microphone history.
        mic(concat(voice(180, 1.5, 0.25f), silence(1)), true);
        lesson(a, 16); run(3000);
        clickText(a, "Гелій"); run(1500); shot(a, "89_l16_fx");
        Object tape = get(a, "currentTape");
        check("Урок 16 Ефекти голосу (запис з мікрофона)", tape != null, tape == null ? "немає запису" : "відкрито «" + get(tape, "name") + "»");
        click(a, "btnTapeClose");
        back(a);

        // 3. Time machine with microphone history.
        click(a, "tabMain");
        mic(voice(330, 3, 0.25f), true);
        select(a, "spinMode", 3);
        run(4000);
        click(a, "btnTimeMachine");
        run(3000); shot(a, "90_timemachine");
        Object t2 = get(a, "currentTape");
        check("Машина часу (історія мікрофона)", t2 != null && ((short[]) get(t2, "pcm")).length > RATE * 3,
                t2 == null ? "не відкрилась" : ((short[]) get(t2, "pcm")).length / RATE + " с історії");
        click(a, "btnTapeClose");

        // 4. Sonar: the "microphone" hears the pings with a wall at 1.2 m.
        Class<?> sp = Class.forName("com.example.audiospectrumpro.SonarProcessor");
        Object proc = sp.getConstructor(int.class, float.class, float.class, float.class, int.class, float.class)
                .newInstance(RATE, 18000f, 22000f, 10f, RATE / 10, 3f);
        float[] ping = (float[]) proc.getClass().getMethod("pingBuffer", float.class).invoke(proc, 0.7f);
        float[] heard2 = new float[ping.length];
        int d = Math.round(2 * 1.2f / 343f * RATE);
        for (int i = 0; i < ping.length; i++) {
            heard2[i] = 0.5f * ping[i] + 0.02f * ping[(i - d + ping.length) % ping.length] + (r.nextFloat() - 0.5f) * 0.002f;
        }
        mic(heard2, true);
        click(a, "tabSonar");
        click(a, "btnSonar");
        run(3000); shot(a, "91_sonar");
        Object sonarR = get(vz, "sonar");
        Object last = get(sonarR, "last");
        float target2 = last == null ? Float.NaN : (Float) get(last, "targetM");
        check("Ехолот через мікрофон (стіна 1,2 м)", Math.abs(target2 - 1.2f) < 0.05f, "виміряно " + target2 + " м");
        click(a, "btnSonar");

        System.out.println("REPORT\n" + String.join("\n", report));
    }

    /** Formant synthesis (source-filter model): a glottal pulse train at f0 through resonators at F1, F2, F3. */
    static float[] vowel(double f0, double f1, double f2, double f3, double sec) {
        int n = (int) (sec * RATE);
        float[] x = new float[n];
        double ph = 0;
        // Glottal source: impulse train through two leaky integrators (about -12 dB per octave).
        double g1 = 0, g2 = 0, mean = 0;
        for (int i = 0; i < n; i++) {
            ph += f0 / RATE;
            double imp = 0;
            if (ph >= 1) { ph -= 1; imp = 1; }
            g1 = 0.985 * g1 + imp;
            g2 = 0.985 * g2 + g1;
            x[i] = (float) g2;
            mean += g2;
        }
        mean /= n;
        for (int i = 0; i < n; i++) x[i] -= mean;
        for (double[] fb : new double[][]{{f1, 80}, {f2, 110}, {f3, 150}}) {
            double r = Math.exp(-Math.PI * fb[1] / RATE), th = 2 * Math.PI * fb[0] / RATE;
            double a1 = 2 * r * Math.cos(th), a2 = -r * r, y1 = 0, y2 = 0;
            for (int i = 0; i < n; i++) {
                double y = x[i] + a1 * y1 + a2 * y2;
                y2 = y1;
                y1 = y;
                x[i] = (float) y;
            }
        }
        float m = 1e-6f;
        for (float v : x) m = Math.max(m, Math.abs(v));
        for (int i = 0; i < n; i++) x[i] *= 0.3f / m;
        return x;
    }

    @Test
    public void vowelsAndRange() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();

        lesson(a, 30);
        Object vl = get(a, "vowelLesson");
        String[][] cases = {{"а", "720", "1250", "2500"}, {"і", "290", "2250", "3000"}, {"у", "330", "760", "2400"},
                {"о", "500", "900", "2450"}, {"е", "490", "1800", "2550"}};
        for (String[] cs : cases) {
            mic(vowel(125, Double.parseDouble(cs[1]), Double.parseDouble(cs[2]), Double.parseDouble(cs[3]), 3), true);
            run(1800);
            String got = (String) call(vl, "currentVowel", new Class<?>[]{});
            check("Карта голосних «" + cs[0] + "»", cs[0].equals(got),
                    String.format("розпізнано «%s», F1 %.0f F2 %.0f", got, (Float) get(vl, "f1"), (Float) get(vl, "f2")));
            shot(a, "92_vowel_" + cs[1]);
        }
        back(a);

        lesson(a, 31);
        Object rl = get(a, "rangeLesson");
        int[] notes = {45, 48, 52, 55, 57, 60, 62, 64, 67};
        float[] song = new float[0];
        for (int m : notes) song = concat(song, voice(440 * Math.pow(2, (m - 69) / 12.0), 0.7, 0.25f));
        mic(song, false);
        run(8000);
        shot(a, "93_range");
        int lo = (Integer) call(rl, "lowest", new Class<?>[]{}), hi = (Integer) call(rl, "highest", new Class<?>[]{});
        int type = (Integer) call(rl, "voiceType", new Class<?>[]{});
        check("Голосовий діапазон", lo == 45 && hi == 67 && type == 1,
                "MIDI " + lo + "–" + hi + ", тип " + (type >= 0 ? new String[]{"бас", "баритон", "тенор", "альт", "меццо", "сопрано"}[type] : "—"));
        System.out.println("REPORT2\n" + String.join("\n", report));
    }

    @Test
    public void simpleLessonsAndParrot() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        click(a, "tabLearn");
        run(300);
        shot(a, "94_academy_simple");

        Object[][] plan = {
                {32, tone(1800, 3, 0.3f)}, {33, voice(130, 3, 0.25f)}, {34, tone(1200, 3, 0.3f)},
                {35, voice(110, 3, 0.3f)}, {36, voice(140, 3, 0.25f)}, {37, voice(150, 3, 0.25f)},
                {38, voice(329.6, 3, 0.25f)}, {39, voice(200, 3, 0.25f)}};
        for (Object[] p : plan) {
            int id = (Integer) p[0];
            mic((float[]) p[1], true);
            lesson(a, id);
            run(2500);
            shot(a, "95_simple_" + id);
            check("Простий урок " + id, "MIC".equals(sourceOf(a)) && peakHz(a) > 0, "джерело " + sourceOf(a) + ", пік " + peakHz(a));
            back(a);
        }
        // Face with noise (should frown) and the threads with a low hum.
        mic(noise(3, 0.2f), true);
        lesson(a, 34); run(2000); shot(a, "96_face_noise"); back(a);
        mic(voice(90, 3, 0.3f), true);
        lesson(a, 32); run(2500); shot(a, "96_threads_low"); back(a);

        // Parrot: one phrase, then silence.
        mic(silence(1), true);
        run(500);
        lesson(a, 40);
        mic(concat(silence(1), voice(170, 1.2, 0.3f), silence(4)), false);
        run(5000);
        shot(a, "97_parrot");
        Object pl = get(a, "parrotLesson");
        Class<?> sg = Class.forName("com.example.audiospectrumpro.SignalGenerator");
        Field bf = sg.getDeclaredField("buffer"); bf.setAccessible(true);
        int phrases = (Integer) call(pl, "phrases", new Class<?>[]{});
        float[] said = (float[]) bf.get(null);
        check("Папуга повторює фразу", phrases == 1 && said.length > RATE / 2,
                "повторено фраз: " + phrases + ", довжина відповіді " + String.format("%.2f с", said.length / (float) RATE));
        System.out.println("REPORT3\n" + String.join("\n", report));
    }

    static float[] blowNoise(double sec, float amp) {
        Random r = new Random(11);
        float[] x = new float[(int) (sec * RATE)];
        float lp = 0;
        for (int i = 0; i < x.length; i++) {
            float w = r.nextFloat() * 2 - 1;
            lp = 0.995f * lp + 0.05f * w;                 // strong low rumble of air on the mic
            x[i] = amp * (0.35f * w + 2.5f * lp);
        }
        return x;
    }

    static void waitFor(Object game, String state, long maxMs) throws Exception {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end && !state.equals(call(game, "state", new Class<?>[]{}))) run(50);
    }

    static float hzOf(int midi) { return (float) (440 * Math.pow(2, (midi - 69) / 12.0)); }

    @Test
    public void games() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Random r = new Random(5);

        // 1. Candles: a voice does nothing, a blow puts them out.
        mic(silence(1), true);
        lesson(a, 41);
        Object candles = get(a, "candlesGame");
        mic(voice(200, 2, 0.3f), true);
        run(2000);
        int afterVoice = (Integer) call(candles, "lit", new Class<?>[]{});
        mic(blowNoise(4, 0.25f), true);
        run(4000);
        shot(a, "98_g41_candles");
        check("Гра «Задуй свічки»", afterVoice == 8 && (Integer) call(candles, "lit", new Class<?>[]{}) == 0,
                "після голосу горить " + afterVoice + ", після видиху " + call(candles, "lit", new Class<?>[]{}));
        back(a);

        // 2. Glass: sing its note an octave down.
        mic(silence(1), true);
        lesson(a, 42);
        Object glass = get(a, "glassGame");
        int target = (Integer) call(glass, "targetMidi", new Class<?>[]{});
        mic(voice(hzOf(target - 12), 4, 0.25f), true);
        run(3500);
        shot(a, "98_g42_glass");
        check("Гра «Розбий склянку»", (Boolean) call(glass, "broken", new Class<?>[]{}), "нота склянки MIDI " + target + ", співали октавою нижче");
        back(a);

        // 3. Dragon: steal in silence, then a laugh wakes it.
        mic(silence(1), true);
        lesson(a, 43);
        Object dragon = get(a, "dragonGame");
        mic(noise(4, 0.0005f), true);
        run(2500);
        for (int i = 0; i < 3; i++) call(dragon, "steal", new Class<?>[]{});
        boolean sleeping = !(Boolean) call(dragon, "awake", new Class<?>[]{});
        mic(voice(250, 2, 0.2f), true);
        run(1000);
        shot(a, "98_g43_dragon");
        check("Гра «Не розбуди дракона»", sleeping && (Integer) call(dragon, "coins", new Class<?>[]{}) == 3 && (Boolean) call(dragon, "awake", new Class<?>[]{}),
                "монет " + call(dragon, "coins", new Class<?>[]{}) + ", прокинувся від голосу: " + call(dragon, "awake", new Class<?>[]{}));
        back(a);

        // 4. Simon: repeat two rounds.
        mic(silence(1), true);
        lesson(a, 44);
        Object simon = get(a, "simonGame");
        call(simon, "start", new Class<?>[]{});
        int[] pcs = {0, 4, 7, 9};
        for (int round = 0; round < 3; round++) {
            waitFor(simon, "LISTENING", 8000);
            @SuppressWarnings("unchecked") List<Integer> seq = new ArrayList<>((List<Integer>) call(simon, "sequence", new Class<?>[]{}));
            float[] song = silence(0.2);
            for (int note : seq) song = concat(song, voice(hzOf(48 + pcs[note]), 0.55, 0.25f), silence(0.35));
            int before = (Integer) call(simon, "level", new Class<?>[]{});
            mic(concat(song, silence(3)), false);
            // Wait until the game has judged this round (level grows, or the game is lost).
            long until = System.currentTimeMillis() + (song.length * 1000L / RATE) + 5000;
            while (System.currentTimeMillis() < until && (Integer) call(simon, "level", new Class<?>[]{}) == before
                    && !"LOST".equals(call(simon, "state", new Class<?>[]{}))) run(50);
        }
        shot(a, "98_g44_simon");
        int lvl = (Integer) call(simon, "level", new Class<?>[]{});
        check("Гра «Повтори мелодію»", lvl >= 4 && !"LOST".equals(call(simon, "state", new Class<?>[]{})),
                "пройдено раундів: " + (lvl - 1) + ", стан " + call(simon, "state", new Class<?>[]{}));
        back(a);

        // 5. Plane: an autopilot sings the pitch of the next gap.
        mic(silence(1), true);
        lesson(a, 45);
        Object plane = get(a, "planeGame");
        call(plane, "start", new Class<?>[]{});
        int center = (Integer) get(plane, "centerMidi");
        long end = System.currentTimeMillis() + 22000;
        while (System.currentTimeMillis() < end && !(Boolean) call(plane, "crashed", new Class<?>[]{})) {
            float gap = (Float) call(plane, "nextGap", new Class<?>[]{});
            float midi = center + (gap - 0.5f) * 14;
            mic(voice(hzOf(Math.round(midi)) * Math.pow(2, (midi - Math.round(midi)) / 12), 1, 0.25f), true);
            run(150);
        }
        shot(a, "98_g45_plane");
        check("Гра «Голосовий літачок»", (Integer) call(plane, "score", new Class<?>[]{}) >= 5,
                "пролетіли " + call(plane, "score", new Class<?>[]{}) + ", аварія: " + call(plane, "crashed", new Class<?>[]{}));
        // Silence: the plane must fall.
        mic(silence(2), true);
        call(plane, "start", new Class<?>[]{});
        float y0 = (Float) call(plane, "planeY", new Class<?>[]{});
        run(1500);
        check("Літачок падає в тиші", (Float) call(plane, "planeY", new Class<?>[]{}) < y0 - 0.2f, "висота " + y0 + " → " + call(plane, "planeY", new Class<?>[]{}));
        back(a);

        // 6. Rhythm echo: clap back two levels.
        mic(silence(1), true);
        lesson(a, 46);
        Object echo = get(a, "echoGame");
        for (int round = 0; round < 2; round++) {
            call(echo, "play", new Class<?>[]{});
            waitFor(echo, "LISTENING", 8000);
            float[] pat = (float[]) call(echo, "pattern", new Class<?>[]{});
            float total = 0;
            for (float g : pat) total += g;
            float[] cl = silence(0.6 + total * 0.5 + 3.2);
            float t = 0.6f;
            for (int i = 0; i <= pat.length; i++) {
                burst(cl, (int) (t * RATE), 0.6f, 0.01, r);
                if (i < pat.length) t += pat[i] * 0.5f + (r.nextFloat() - 0.5f) * 0.04f;   // a human is never exact
            }
            mic(cl, false);
            waitFor(echo, "RESULT", 8000);
            check("Гра «Відлуння ритму» раунд " + (round + 1), (Boolean) call(echo, "passed", new Class<?>[]{}), "оцінено: " + call(echo, "passed", new Class<?>[]{}));
        }
        shot(a, "98_g46_echo");
        back(a);

        // 7. Who is loudest.
        mic(silence(1), true);
        lesson(a, 47);
        Object loud = get(a, "loudGame");
        call(loud, "newGame", new Class<?>[]{});
        mic(voice(220, 4, 0.05f), true);
        call(loud, "startTurn", new Class<?>[]{});
        run(3800);
        mic(voice(260, 4, 0.4f), true);
        call(loud, "startTurn", new Class<?>[]{});
        run(3800);
        shot(a, "98_g47_loud");
        check("Гра «Хто гучніший»", (Integer) call(loud, "winner", new Class<?>[]{}) == 1,
                "результати " + call(loud, "results", new Class<?>[]{}) + ", переміг гравець " + ((Integer) call(loud, "winner", new Class<?>[]{}) + 1));
        back(a);

        // 8. Hot and cold: the beacon gets closer; talking nearby does not count.
        mic(silence(1), true);
        lesson(a, 48);
        Object hc = get(a, "hotColdGame");
        float[] beep = (float[]) staticCall("HotColdGame", "beep", new Class<?>[]{int.class}, RATE);
        float[] far = beep.clone(), near = beep.clone();
        for (int i = 0; i < beep.length; i++) { far[i] *= 0.003f; near[i] *= 0.4f; }
        mic(voice(150, 3, 0.3f), true);
        run(1500);
        float talk = (Float) call(hc, "heat", new Class<?>[]{});
        mic(far, true);
        run(2000);
        float cold = (Float) call(hc, "heat", new Class<?>[]{});
        mic(near, true);
        run(2000);
        float hot = (Float) call(hc, "heat", new Class<?>[]{});
        shot(a, "98_g48_hotcold");
        check("Гра «Тепло — холодно»", talk < 0.2f && hot > cold + 0.3f && hot > 0.7f,
                String.format("розмова %.2f, далеко %.2f, близько %.2f", talk, cold, hot));
        System.out.println("REPORT4\n" + String.join("\n", report));
    }

    static Object sf(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    /** What a phone's microphone hears during the duel: its own carrier (after latency), dipping when covered. */
    static float[] carrierPerformance(float carrierHz, float latencyMs, int[] skip, Random r) throws Exception {
        Class<?> dg = Class.forName("com.example.audiospectrumpro.DuelGame");
        float[] hits = (float[]) sf(dg, "HITS");
        int lead = (Integer) sf(dg, "LEAD_BEATS"), beats = (Integer) sf(dg, "SONG_BEATS");
        float beat = (Float) sf(dg, "BEAT_MS");
        int n = (int) (((lead + beats) * beat + 800 + latencyMs) / 1000 * RATE);
        float[] x = new float[n];
        int start = (int) (latencyMs / 1000 * RATE);
        float[] gain = new float[n];
        java.util.Arrays.fill(gain, 1f);
        java.util.Set<Integer> skipped = new java.util.HashSet<>();
        for (int sk : skip) skipped.add(sk);
        for (int i = 0; i < hits.length; i++) {
            if (skipped.contains(i)) continue;
            float t = (lead + hits[i]) * beat + latencyMs + (r.nextFloat() - 0.5f) * 60;      // ±30 ms human error
            int a = (int) (t / 1000 * RATE), b = a + (int) (0.15f * RATE);
            for (int j = Math.max(0, a); j < Math.min(n, b); j++) gain[j] = 0.08f;          // finger: about -22 dB
        }
        for (int i = start; i < n; i++) {
            x[i] = (float) (0.12 * gain[i] * Math.sin(2 * Math.PI * carrierHz * i / RATE)) + (r.nextFloat() - 0.5f) * 0.01f;
        }
        return x;
    }

    @Test
    public void rhythmDuel() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Random r = new Random(8);
        Class<?> roleCls = Class.forName("com.example.audiospectrumpro.DuelGame$Role");

        // Solo: 34 of 37 notes hit with a 150 ms phone latency.
        mic(silence(1), true);
        lesson(a, 49);
        Object g = get(a, "duelGame");
        Field role = g.getClass().getDeclaredField("role"); role.setAccessible(true);
        role.set(g, Enum.valueOf((Class) roleCls, "SOLO"));
        float[] perf = carrierPerformance(17000, 150, new int[]{5, 17, 30}, r);
        call(g, "start", new Class<?>[]{});
        mic(concat(perf, silence(2)), false);
        run(perf.length * 1000L / RATE + 1500);
        shot(a, "99_duel_solo");
        int perfect = (Integer) call(g, "perfect", new Class<?>[]{});
        int good = (Integer) get(g, "good"), miss = (Integer) get(g, "miss"), extra = (Integer) get(g, "extra");
        float lat = (Float) get(g, "latencyMs");
        check("Ритм-дуель наодинці", "RESULT".equals(call(g, "state", new Class<?>[]{})) && perfect + good == 34 && miss == 3 && extra == 0,
                String.format("ідеально %d, добре %d, мимо %d, зайвих %d, очки %d, затримка %.0f мс", perfect, good, miss, extra,
                        (Integer) call(g, "score", new Class<?>[]{}), lat));

        // Two phones, seen from player 2: start signal, the song, then player 1's score arrives as sound.
        role.set(g, Enum.valueOf((Class) roleCls, "PLAYER2"));
        mic(silence(1), true);
        run(300);
        call(g, "start", new Class<?>[]{});
        float[] go = (float[]) staticCall("ModemLesson", "encode", new Class<?>[]{String.class, int.class}, "GO", RATE);
        float[] p1 = (float[]) staticCall("ModemLesson", "encode", new Class<?>[]{String.class, int.class}, "P1:2950", RATE);
        for (int i = 0; i < go.length; i++) go[i] *= 0.5f;
        for (int i = 0; i < p1.length; i++) p1[i] *= 0.5f;
        float[] perf2 = carrierPerformance(18200, 220, new int[]{}, r);
        mic(concat(silence(0.5), go, silence(0.25), perf2, silence(1), p1, silence(9)), false);
        long until = System.currentTimeMillis() + 50000;
        while (System.currentTimeMillis() < until && !"RESULT".equals(call(g, "state", new Class<?>[]{}))) run(200);
        shot(a, "99_duel_two_phones");
        check("Ритм-дуель удвох (гравець 2)", "RESULT".equals(call(g, "state", new Class<?>[]{}))
                        && (Integer) call(g, "opponent", new Class<?>[]{}) == 2950 && (Integer) call(g, "perfect", new Class<?>[]{}) + (Integer) get(g, "good") == 37,
                String.format("старт за сигналом ✓, влучань %d з 37, мій рахунок %d, рахунок друга %d, затримка %.0f мс",
                        (Integer) call(g, "perfect", new Class<?>[]{}) + (Integer) get(g, "good"), (Integer) call(g, "score", new Class<?>[]{}),
                        (Integer) call(g, "opponent", new Class<?>[]{}), (Float) get(g, "latencyMs")));

        // Four phones, seen from player 4: three scores arrive in their slots.
        role.set(g, Enum.valueOf((Class) roleCls, "PLAYER4"));
        setField(g, "players", 4);
        mic(silence(1), true);
        run(300);
        call(g, "start", new Class<?>[]{});
        float[] perf4 = carrierPerformance(17600, 180, new int[]{3, 9}, r);
        float[] s1 = modem("P1:2950", 0.4f), s2 = modem("P2:3400", 0.4f), s3 = modem("P3:1800", 0.4f);
        mic(concat(silence(0.5), go, silence(0.25), perf4, silence(0.9), s1, silence(3.0 - s1.length / (double) RATE), s2,
                silence(3.0 - s2.length / (double) RATE), s3, silence(12)), false);
        until = System.currentTimeMillis() + 70000;
        while (System.currentTimeMillis() < until && !"RESULT".equals(call(g, "state", new Class<?>[]{}))) run(250);
        shot(a, "99_duel_four");
        Map<?, ?> others = (Map<?, ?>) call(g, "others", new Class<?>[]{});
        int mine = (Integer) call(g, "score", new Class<?>[]{});
        check("Ритм-дуель на чотирьох (гравець 4)", "RESULT".equals(call(g, "state", new Class<?>[]{})) && others.size() == 3
                        && Integer.valueOf(3400).equals(others.get(2)) && Integer.valueOf(1800).equals(others.get(3))
                        && (Integer) call(g, "perfect", new Class<?>[]{}) + (Integer) get(g, "good") == 35,
                "отримано " + others + ", мій рахунок " + mine + ", влучань " + ((Integer) call(g, "perfect", new Class<?>[]{}) + (Integer) get(g, "good")) + " з 37 (2 пропущено навмисно)");
        System.out.println("REPORT5\n" + String.join("\n", report));
    }

    /** Adds a 6-10 kHz chirp starting at an exact (sub-sample) time, like a real sound arriving. */
    static void chirpAt(float[] x, double atS, float amp) {
        int from = (int) Math.floor(atS * RATE);
        for (int i = Math.max(0, from); i < x.length && i < from + RATE / 50 + 2; i++) {
            double t = (double) i / RATE - atS;
            if (t < 0 || t >= 0.02) continue;
            double ph = 2 * Math.PI * (6000 * t + 4000 * t * t / (2 * 0.02));
            double win = 0.5 - 0.5 * Math.cos(2 * Math.PI * t / 0.02);
            x[i] += (float) (amp * win * Math.sin(ph));
        }
    }

    static void setField(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    @Test
    public void temperatureExperiment() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Random r = new Random(11);
        mic(silence(1), true);
        lesson(a, 50);
        Object t = get(a, "temperature");
        Class<?> roleCls = Class.forName("com.example.audiospectrumpro.TemperatureExperiment$Role");
        final double tempC = 8, c = 331.3 + 0.606 * tempC, self = 0.10;

        // Phone B: hears A, answers, and sends its own time between the two chirps.
        call(t, "setRole", new Class<?>[]{roleCls}, Enum.valueOf((Class) roleCls, "RESPONDER"));
        run(300);
        double d = 1.3, eA = 0.4, eB = eA + 0.47;
        float[] sigB = mix(noise(4, 0.002f), silence(4));
        chirpAt(sigB, eA + d / c, 0.25f);
        chirpAt(sigB, eA + d / c + 0.004, 0.1f);        // reflection
        chirpAt(sigB, eB + self / c, 0.7f);
        mic(sigB, false);
        run(2500);
        long expected = Math.round((eB + self / c - eA - d / c) * 1e6);
        String sent = (String) call(t, "sent", new Class<?>[]{});
        long got = sent.startsWith("B:") ? Long.parseLong(sent.substring(2)) : -1;
        shot(a, "99_temperature_B");
        check("Термометр: телефон B відповідає", Math.abs(got - expected) <= 15,
                String.format("надіслав %s, очікувалось B:%d (похибка %d мкс), пропущено кадрами %d семплів", sent, expected, got - expected, (Long) call(t, "lostSamples", new Class<?>[]{})));
        run(3000);

        // Phone A: measurements at three distances; B's time arrives through the modem.
        call(t, "setRole", new Class<?>[]{roleCls}, Enum.valueOf((Class) roleCls, "LEADER"));
        double[] dist = {0.5, 0.5, 1.5, 3.0};
        for (double dd : dist) {
            setField(t, "distance", (float) dd);
            mic(silence(1), true);
            run(300);
            call(t, "measure", new Class<?>[]{});
            double ea = 0.3, eb = ea + 0.43 + r.nextDouble() * 0.05;
            double tOwn = ea + self / c, tOther = eb + dd / c;
            long deltaB = Math.round((eb + self / c - (ea + dd / c)) * 1e6);
            float[] msg = (float[]) staticCall("ModemLesson", "encode", new Class<?>[]{String.class, int.class}, "B:" + deltaB, RATE);
            for (int i = 0; i < msg.length; i++) msg[i] *= 0.4f;
            float[] sig = mix(noise(7, 0.008f), concat(silence(tOther + 0.6), msg, silence(1)));
            chirpAt(sig, tOwn, 0.8f);
            chirpAt(sig, tOwn + 0.003, 0.3f);
            chirpAt(sig, tOther, (float) (0.3 / dd));
            chirpAt(sig, tOther + 0.005, (float) (dd > 2 ? 0.2 : 0.12 / dd));
            mic(sig, false);
            long until = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < until && !"IDLE".equals(call(t, "state", new Class<?>[]{}))) run(200);
        }
        int n = (Integer) call(t, "count", new Class<?>[]{});
        double speed = (Double) call(t, "speed", new Class<?>[]{});
        double temp = (speed - 331.3) / 0.606;
        shot(a, "99_temperature_A");
        check("Термометр: швидкість звуку і температура", n == dist.length && Math.abs(temp - tempC) < 1.0,
                String.format("вимірів %d, c = %.2f м/с (правда %.2f), T = %.2f °C (правда %.1f)", n, speed, c, temp, tempC));
        System.out.println("REPORT6\n" + String.join("\n", report));
    }

    /** Blow noise whose strength (0..1 on the game's scale) follows a function of time. */
    interface Curve { double at(double t); }

    static float[] blowCurve(double sec, Curve strength) {
        float[] unit = blowNoise(sec, 1f);
        double rms = 0;
        for (float v : unit) rms += v * v;
        rms = Math.sqrt(rms / unit.length);
        for (int i = 0; i < unit.length; i++) {
            double s = strength.at((double) i / RATE);
            double amp = s <= 0 ? 0 : Math.pow(10, (-50 + 40 * s) / 20) / rms;
            unit[i] *= (float) amp;
        }
        return unit;
    }

    /** Same corridor as WindGame.target(). */
    static double windTarget(double t) {
        return 0.5 + 0.22 * Math.sin(2 * Math.PI * t / 6.0) + 0.06 * Math.sin(2 * Math.PI * t / 2.3);
    }

    static float[] blowConst(double sec, final double s) {
        return blowCurve(sec, new Curve() { public double at(double t) { return s; } });
    }

    static float windAttempt(MainActivity a, Object g, float[] afterCountdown, int contest) throws Exception {
        int p = (Integer) call(g, "player", new Class<?>[]{});
        call(g, "start", new Class<?>[]{});
        mic(concat(silence(3.0), afterCountdown, silence(8)), false);
        long until = System.currentTimeMillis() + 40000;
        while (System.currentTimeMillis() < until) {
            run(200);
            String st = (String) call(g, "state", new Class<?>[]{});
            if (st.equals("DONE") || st.equals("TABLE")) break;
        }
        return (Float) call(g, "score", new Class<?>[]{int.class, int.class}, p, contest);
    }

    @Test
    public void windGame() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 51);
        Object g = get(a, "windGame");
        call(g, "setPlayers", new Class<?>[]{String.class}, "Аня, Богдан, Віка");

        // Strongest blow.
        call(g, "setMode", new Class<?>[]{int.class}, 0);
        float s1 = windAttempt(a, g, concat(silence(0.5), blowConst(1.2, 0.3)), 0);
        float s2 = windAttempt(a, g, concat(silence(0.5), blowConst(1.2, 0.8)), 0);
        float s3 = windAttempt(a, g, concat(silence(0.5), blowConst(1.2, 0.55)), 0);
        shot(a, "99_wind_strong_table");
        Integer[] rank = (Integer[]) call(g, "ranking", new Class<?>[]{});
        check("Вітрило: хто сильніше", Math.abs(s1 - 30) <= 8 && Math.abs(s2 - 80) <= 8 && Math.abs(s3 - 55) <= 8
                        && rank[0] == 1 && rank[1] == 2 && rank[2] == 0 && "TABLE".equals(call(g, "state", new Class<?>[]{})),
                String.format("сила %.0f / %.0f / %.0f (задано 30 / 80 / 55), місця: %d,%d,%d", s1, s2, s3, rank[0], rank[1], rank[2]));

        // Singing is not wind.
        call(g, "setMode", new Class<?>[]{int.class}, 0);
        float sv = windAttempt(a, g, concat(silence(0.5), voice(220, 1.5, 0.5f)), 0);
        check("Вітрило: спів не рахується", sv <= 5 && (Boolean) call(g, "voiceWarned", new Class<?>[]{}),
                String.format("сила від співу %.0f, попередження «Це не вітер» %s", sv, call(g, "voiceWarned", new Class<?>[]{})));

        // Longest blow.
        call(g, "setMode", new Class<?>[]{int.class}, 1);
        float l1 = windAttempt(a, g, concat(silence(0.5), blowConst(2.0, 0.4)), 1);
        float l2 = windAttempt(a, g, concat(silence(0.5), blowConst(5.0, 0.3)), 1);
        float l3 = windAttempt(a, g, concat(silence(0.5), blowConst(3.5, 0.6)), 1);
        check("Вітрило: хто довше", Math.abs(l1 - 2.0) <= 0.3 && Math.abs(l2 - 5.0) <= 0.3 && Math.abs(l3 - 3.5) <= 0.3,
                String.format("%.2f / %.2f / %.2f с (задано 2 / 5 / 3.5)", l1, l2, l3));

        // Strong and long: only the time above the bar counts.
        call(g, "setMode", new Class<?>[]{int.class}, 2);
        float k1 = windAttempt(a, g, concat(silence(0.5), blowConst(4.0, 0.75), blowConst(3.0, 0.3)), 2);
        float k2 = windAttempt(a, g, concat(silence(0.5), blowConst(6.0, 0.4)), 2);
        float k3 = windAttempt(a, g, concat(silence(0.5), blowConst(2.5, 0.9)), 2);
        shot(a, "99_wind_stronglong_table");
        check("Вітрило: сильно і довго", Math.abs(k1 - 4.0) <= 0.3 && k2 == 0 && Math.abs(k3 - 2.5) <= 0.3,
                String.format("%.2f / %.2f / %.2f с (задано 4 / 0 (слабко) / 2.5)", k1, k2, k3));

        // Control: following the corridor vs blowing steadily.
        call(g, "setMode", new Class<?>[]{int.class}, 3);
        float c1 = windAttempt(a, g, blowCurve(12.5, new Curve() {
            public double at(double t) { return windTarget(t); }
        }), 3);
        float c2 = windAttempt(a, g, blowConst(12.5, 0.5), 3);
        float c3 = windAttempt(a, g, silence(12.5), 3);
        check("Вітрило: контроль", c1 >= 75 && c2 < c1 - 20 && c3 < 5,
                String.format("слідує за смугою %.0f%%, рівно %.0f%%, тиша %.0f%%", c1, c2, c3));

        // Champion: four contests per player.
        call(g, "setPlayers", new Class<?>[]{String.class}, "Аня, Богдан");
        call(g, "setMode", new Class<?>[]{int.class}, 4);
        windAttempt(a, g, concat(silence(0.5), blowConst(1, 0.5)), 0);
        shot(a, "99_wind_champion_play");
        windAttempt(a, g, concat(silence(0.5), blowConst(2, 0.4)), 1);
        windAttempt(a, g, concat(silence(0.5), blowConst(2, 0.8)), 2);
        windAttempt(a, g, blowConst(12.5, 0.5), 3);
        for (int i = 0; i < 3; i++) windAttempt(a, g, concat(silence(0.5), blowConst(3, 0.9)), i);
        windAttempt(a, g, blowCurve(12.5, new Curve() {
            public double at(double t) { return windTarget(t); }
        }), 3);
        shot(a, "99_wind_champion_table");
        rank = (Integer[]) call(g, "ranking", new Class<?>[]{});
        check("Вітрило: чемпіон", "TABLE".equals(call(g, "state", new Class<?>[]{})) && rank[0] == 1,
                String.format("переможець — гравець %d (Богдан дмухав сильніше й рівніше)", rank[0]));
        System.out.println("REPORT7\n" + String.join("\n", report));
    }

    static float[] modem(String text, float gain) throws Exception {
        float[] m = (float[]) staticCall("ModemLesson", "encode", new Class<?>[]{String.class, int.class}, text, RATE);
        for (int i = 0; i < m.length; i++) m[i] *= gain;
        return m;
    }

    /** What a player's microphone hears: the host's music from afar, its own carrier and the finger on it. */
    static float[] pauseScene(long seed, double[] reactMs, double falseStartAtS, boolean withEnd, float carrierHz) throws Exception {
        float[] track = (float[]) staticCall("MusicPauseGame", "soundtrack", new Class<?>[]{long.class}, seed);
        float[] sched = (float[]) staticCall("MusicPauseGame", "schedule", new Class<?>[]{long.class}, seed);
        float[] end = modem("END", 1f);
        int trackLen = withEnd ? track.length : track.length - end.length;
        double t0 = 1.0;
        float[] x = new float[(int) ((t0 + 25) * RATE) + trackLen];
        for (int i = 0; i < trackLen; i++) x[(int) (t0 * RATE) + i] += 0.3f * track[i];
        float[] gainC = new float[x.length];
        java.util.Arrays.fill(gainC, 1f);
        double at = t0;
        for (int r = 0; r < sched.length; r++) {
            double stop = at + sched[r];
            int c0 = (int) ((stop + reactMs[r] / 1000) * RATE);
            for (int i = c0; i < c0 + RATE / 2; i++) gainC[i] = 0.12f;
            at = stop + 3.2;
        }
        if (falseStartAtS > 0) {
            int c0 = (int) ((t0 + falseStartAtS) * RATE);
            for (int i = c0; i < c0 + (int) (0.3 * RATE); i++) gainC[i] = 0.12f;
        }
        for (int i = 0; i < x.length; i++) {
            x[i] += gainC[i] * 0.12f * (float) Math.sin(2 * Math.PI * carrierHz * i / RATE);
            x[i] *= gainC[i] < 1 ? 0.5f : 1f;                  // finger also muffles everything else
        }
        return x;
    }

    static void waitState(Object g, String st, long maxMs) throws Exception {
        long until = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < until && !st.equals(call(g, "state", new Class<?>[]{}))) run(250);
    }

    @Test
    public void musicPause() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 52);
        Object g = get(a, "musicPause");
        long seed = 5;
        float[] sched = (float[]) staticCall("MusicPauseGame", "schedule", new Class<?>[]{long.class}, seed);
        double[] react = {250, 410, 180, 330, 600};
        double falseAt = 0;
        for (int r = 0; r < 2; r++) falseAt += sched[r] + 3.2;
        falseAt += 2.0;                                    // round 3, two seconds into the music

        // Player 2: music from the host, finger on the microphone, then everyone's results by sound.
        setField(g, "role", 2);
        call(g, "start", new Class<?>[]{});
        float[] scene = pauseScene(seed, react, falseAt, true, 17100);
        float[] track = (float[]) staticCall("MusicPauseGame", "soundtrack", new Class<?>[]{long.class}, seed);
        int endAt = (int) ((1.0 + track.length / (double) RATE) * RATE);
        float[] p1 = modem("P1:512:0", 0.3f), p3 = modem("P3:298:2", 0.3f);
        for (int i = 0; i < p1.length; i++) scene[endAt + (int) (0.9 * RATE) + i] += p1[i];
        for (int i = 0; i < p3.length; i++) scene[endAt + (int) (6.9 * RATE) + i] += p3[i];
        mic(scene, false);
        long until = System.currentTimeMillis() + 120000;
        boolean shotPlaying = false;
        while (System.currentTimeMillis() < until && !"TABLE".equals(call(g, "state", new Class<?>[]{}))) {
            run(250);
            List<?> rs = (List<?>) call(g, "reactions", new Class<?>[]{});
            if (!shotPlaying && rs.size() == 2) { shot(a, "99_pause_player"); shotPlaying = true; }
        }
        shot(a, "99_pause_table");
        List<?> rs = (List<?>) call(g, "reactions", new Class<?>[]{});
        StringBuilder sb = new StringBuilder();
        boolean ok = rs.size() == 5;
        for (int i = 0; i < rs.size(); i++) {
            float v = (Float) rs.get(i);
            sb.append(String.format("%.0f/%.0f ", v, react[i]));
            ok &= Math.abs(v - react[i]) <= 40;
        }
        Map<?, ?> res = (Map<?, ?>) call(g, "results", new Class<?>[]{});
        int fs = (Integer) call(g, "falseStarts", new Class<?>[]{});
        check("Музична пауза: реакція гравця", ok && fs == 1, "виміряно/задано мс: " + sb + "фальстартів " + fs);
        check("Музична пауза: обмін результатами", "TABLE".equals(call(g, "state", new Class<?>[]{})) && res.size() == 3
                        && "512:0".equals(res.get(1)) && "298:2".equals(res.get(3)),
                "таблиця: " + res);

        // Host: hears the END signal and the players' results.
        setField(g, "role", 0);
        mic(silence(1), true);
        run(300);
        call(g, "start", new Class<?>[]{});
        float[] end = modem("END", 0.5f);
        mic(concat(silence(1), end, silence(0.9), modem("P1:512:0", 0.3f), silence(1.5), modem("P2:731:1", 0.3f),
                silence(1.5), modem("P3:298:2", 0.3f), silence(12)), false);
        waitState(g, "TABLE", 60000);
        res = (Map<?, ?>) call(g, "results", new Class<?>[]{});
        List<?> rank = (List<?>) call(g, "ranking", new Class<?>[]{});
        shot(a, "99_pause_host");
        check("Музична пауза: таблиця ведучого", res.size() == 3 && ((int[]) rank.get(0))[0] == 3 && ((int[]) rank.get(2))[0] == 2,
                "отримано " + res + ", переможець — гравець " + ((int[]) rank.get(0))[0]);

        // Training on one phone: same scene without the END signal.
        setField(g, "role", 6);
        mic(silence(1), true);
        run(300);
        call(g, "start", new Class<?>[]{});
        mic(pauseScene(seed, react, 0, false, 16800), false);
        waitState(g, "TABLE", 90000);
        rs = (List<?>) call(g, "reactions", new Class<?>[]{});
        check("Музична пауза: тренування", "TABLE".equals(call(g, "state", new Class<?>[]{})) && rs.size() == 5,
                "раундів " + rs.size() + ": " + rs);
        System.out.println("REPORT8\n" + String.join("\n", report));
    }

    static float[] withRms(float[] x, double rms) {
        double e = 0;
        for (float v : x) e += v * v;
        double g = rms / Math.sqrt(e / x.length + 1e-20);
        float[] y = new float[x.length];
        for (int i = 0; i < x.length; i++) y[i] = (float) (x[i] * g);
        return y;
    }

    /** "ш-ш-ш": noise with the low part removed (energy mostly 2-8 kHz). */
    static float[] hiss(double sec, double rms) {
        Random r = new Random(17);
        float[] y = new float[(int) (sec * RATE)];
        for (int k = 0; k < 150; k++) {
            double f = 2000 + r.nextDouble() * 5500, ph = r.nextDouble() * 2 * Math.PI, w = 2 * Math.PI * f / RATE;
            for (int i = 0; i < y.length; i++) y[i] += (float) Math.sin(w * i + ph);
        }
        return withRms(y, rms);
    }

    static float[] hum(double f0, double sec, double rms) {
        return withRms(vowel(f0, 300, 870, 2240, sec), rms);
    }

    float tugRound(Object g, float[] sound) throws Exception {
        call(g, "start", new Class<?>[]{});
        mic(concat(silence(3), sound, silence(40)), false);
        long until = System.currentTimeMillis() + 50000;
        while (System.currentTimeMillis() < until) {
            run(250);
            String st = (String) call(g, "state", new Class<?>[]{});
            if (st.equals("ROUND_OVER") || st.equals("MATCH_OVER")) break;
        }
        return (Float) call(g, "rope", new Class<?>[]{});
    }

    @Test
    public void tugOfWar() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 53);
        Object g = get(a, "tugGame");

        // What does each sound alone do?  Children's "у", an adult's "у", a hiss, both together.
        String[] names = {"дитяче «у» (300 Гц)", "доросле «у» (120 Гц)", "«ш»", "«у»+«ш» порівну"};
        float[][] sounds = {hum(300, 3, 0.05), hum(120, 3, 0.05), hiss(3, 0.05), mix(hum(200, 3, 0.05), hiss(3, 0.05))};
        float[] forces = new float[4];
        for (int i = 0; i < 4; i++) {
            mic(sounds[i], true);
            run(2500);
            forces[i] = (Float) call(g, "force", new Class<?>[]{});
        }
        mic(silence(1), true);
        run(800);
        float quiet = (Float) call(g, "force", new Class<?>[]{});
        check("Канат: хто тягне", forces[0] < -0.6 && forces[1] < -0.6 && forces[2] > 0.6 && Math.abs(forces[3]) < 0.35 && Math.abs(quiet) < 0.05,
                String.format("%s %.2f, %s %.2f, %s %.2f, %s %.2f, тиша %.2f", names[0], forces[0], names[1], forces[1], names[2], forces[2],
                        names[3], forces[3], quiet));

        // A match: «Гул» wins, then «Шум» (louder), then «Гул» again although «Шум» also hisses.
        float r1 = tugRound(g, hum(250, 12, 0.05));
        shot(a, "99_tug_round");
        float r2 = tugRound(g, mix(hum(250, 12, 0.02), hiss(12, 0.06)));
        float r3 = tugRound(g, mix(hum(130, 14, 0.06), hiss(14, 0.025)));
        shot(a, "99_tug_match");
        check("Канат: матч до двох перемог", r1 <= -1 && r2 >= 1 && r3 < -0.3 && "MATCH_OVER".equals(call(g, "state", new Class<?>[]{}))
                        && (Integer) call(g, "winsLow", new Class<?>[]{}) == 2 && (Integer) call(g, "winsHigh", new Class<?>[]{}) == 1,
                String.format("канат після раундів: %.2f, %.2f, %.2f; рахунок %s:%s", r1, r2, r3, call(g, "winsLow", new Class<?>[]{}),
                        call(g, "winsHigh", new Class<?>[]{})));
        System.out.println("REPORT9\n" + String.join("\n", report));
    }

    @Test
    public void humanPiano() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 54);
        Object g = get(a, "pianoGame");
        call(g, "setPlayers", new Class<?>[]{String.class}, "Аня, Богдан, Віка");
        setField(g, "song", 0);
        int[][] song = ((int[][][]) staticField("HumanPianoGame", "SONG_DATA"))[0];
        int[] pc = {0, 2, 4, 5, 7, 9};
        float beat = 0.75f;
        float offset = (Float) staticCall("HumanPianoGame", "songOffset", new Class<?>[]{int.class}, 0);
        // Аня sings an octave low (adult), Богдан in the middle, Віка an octave high (child).
        double[] octave = {0.5, 1, 2};
        int[] notesOf = new int[3], hitsOf = new int[3];
        float[] x = new float[(int) ((offset + 30 * beat + 3) * RATE)];
        int b = 0, wrongLeft = 2, silentLeft = 1;
        for (int[] n : song) {
            int player = n[0] % 3;                       // notes 0..5 are all used, so the order index is the note
            notesOf[player]++;
            double hz = 261.63 * Math.pow(2, pc[n[0]] / 12.0) * octave[player];
            boolean wrong = player == 1 && n[1] == 1 && wrongLeft > 0 && b > 4;
            boolean silent = player == 2 && silentLeft > 0 && b > 10;
            if (wrong) { wrongLeft--; hz *= Math.pow(2, 1 / 12.0); }
            if (silent) silentLeft--;
            if (!wrong && !silent) hitsOf[player]++;
            if (!silent) {
                float[] v = voice(hz, n[1] * beat - 0.08, 0.3f);
                int at = (int) ((offset + b * beat + 0.12) * RATE);
                for (int i = 0; i < v.length; i++) x[at + i] += v[i];
            }
            b += n[1];
        }
        call(g, "start", new Class<?>[]{});
        mic(x, false);
        run((long) ((offset + 4 * beat) * 1000));
        shot(a, "99_piano_play");
        waitState(g, "RESULT", 60000);
        shot(a, "99_piano_result");
        StringBuilder sb = new StringBuilder();
        boolean ok = "RESULT".equals(call(g, "state", new Class<?>[]{}));
        for (int p2 = 0; p2 < 3; p2++) {
            float acc = (Float) call(g, "accuracy", new Class<?>[]{int.class}, p2);
            float want = (float) hitsOf[p2] / notesOf[p2];
            sb.append(String.format("гравець %d: %.0f%% (очікувалось %.0f%%)  ", p2 + 1, acc * 100, want * 100));
            ok &= Math.abs(acc - want) < 0.01;
        }
        check("Живе піаніно: хто влучив", ok, sb.toString());
        System.out.println("REPORT10\n" + String.join("\n", report));
    }

    @Test
    public void freezeGame() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 55);
        Object g = get(a, "freezeGame");
        long seed = 3;
        float[] track = (float[]) staticCall("FreezeGame", "soundtrack", new Class<?>[]{long.class}, seed);
        float[][] sch = (float[][]) staticCall("FreezeGame", "schedule", new Class<?>[]{long.class}, seed);
        for (double lat : new double[]{0.15, 0.8}) {
        float[] x = mix(noise(track.length / (double) RATE + 3, 0.003f), silence(1));
        for (int i = 0; i < track.length; i++) {          // music from the speaker with a little room echo
            int at = i + (int) (lat * RATE);
            x[at] += 0.5f * track[i];
            if (at + 2400 < x.length) x[at + 2400] += 0.15f * track[i];
            if (at + 5760 < x.length) x[at + 5760] += 0.07f * track[i];
        }
        double t = 2.5;
        double[] freezeStart = new double[6];
        for (int r = 0; r < 6; r++) { t += sch[0][r]; freezeStart[r] = t; t += sch[1][r]; }
        Random rnd = new Random(4);
        freezeStart = freezeStart.clone();
        for (int r = 0; r < 6; r++) freezeStart[r] += lat - 0.15;
        burst(x, (int) ((freezeStart[1] + 2.0) * RATE), 0.2f, 0.05, rnd);                // a clap
        float[] cough = voice(180, 0.3, 0.05f);
        for (int i = 0; i < cough.length; i++) x[(int) ((freezeStart[3] + 1.5) * RATE) + i] += cough[i];
        float[] breath = noise(1.0, 0.002f);                                               // barely audible
        for (int i = 0; i < breath.length; i++) x[(int) ((freezeStart[4] + 1.0) * RATE) + i] += breath[i];
        run(300);
        call(g, "start", new Class<?>[]{long.class}, seed);
        mic(x, false);
        run((long) ((freezeStart[1] + 2.3) * 1000));
        if (lat < 0.5) shot(a, "99_freeze_caught");
        waitState(g, "RESULT", 90000);
        shot(a, "99_freeze_result");
        boolean[] c = (boolean[]) call(g, "caught", new Class<?>[]{});
        check("Замри: хто зашумів", "RESULT".equals(call(g, "state", new Class<?>[]{})) && !c[0] && c[1] && !c[2] && c[3] && !c[4] && !c[5],
                String.format("спіймано в раундах: %s (очікувалось: 2 — оплески, 4 — кашель; тихе дихання в 5 — ні); тиша кімнати %.1f дБ",
                        java.util.Arrays.toString(c), (Float) call(g, "roomDb", new Class<?>[]{})) + String.format(", затримка динаміка %.2f с", lat));
        }
        System.out.println("REPORT11\n" + String.join("\n", report));
    }

    static float[] sing(List<Float> midi, double noteS, double shift) {
        List<float[]> parts = new ArrayList<>();
        parts.add(silence(0.4));
        for (float m : midi) {
            parts.add(voice(440 * Math.pow(2, (m + shift - 69) / 12.0), noteS - 0.04, 0.3f));
            parts.add(silence(0.04));
        }
        parts.add(silence(2.0));
        return concat(parts.toArray(new float[0][]));
    }

    @Test
    public void chainGame() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 56);
        Object g = get(a, "chainGame");
        call(g, "newMelody", new Class<?>[]{long.class}, 9L);
        List<List<Float>> versions = (List<List<Float>>) get(g, "versions");
        List<Float> orig = new ArrayList<>(versions.get(0));
        List<Float> v2 = new ArrayList<>(orig);
        v2.set(2, v2.get(2) + 2);                       // player 2 gets the middle note wrong
        List<Float> v3 = new ArrayList<>(v2);
        v3.set(4, v3.get(4) - 3);                       // player 3 also changes the ending
        Object[][] singers = {{orig, -5.0, 0.5}, {v2, 0.0, 0.6}, {v3, 7.0, 0.45}};
        for (Object[] sg : singers) {
            call(g, "record", new Class<?>[]{});
            mic(concat(silence(0.5), sing((List<Float>) sg[0], (Double) sg[2], (Double) sg[1])), false);
            long until = System.currentTimeMillis() + 30000;
            int before = versions.size();
            while (System.currentTimeMillis() < until && versions.size() == before) run(250);
            mic(silence(1), true);
            run(300);
        }
        shot(a, "99_chain_hidden");
        call(g, "finish", new Class<?>[]{});
        run(300);
        shot(a, "99_chain_result");
        StringBuilder sb = new StringBuilder();
        boolean ok = versions.size() == 4;
        List<Float>[] want = new List[]{orig, v2, v3};
        double[] shifts = {-5, 0, 7};
        for (int v = 1; v < versions.size(); v++) {
            List<Float> got = versions.get(v);
            boolean notesOk = got.size() == 5;
            for (int i = 0; notesOk && i < 5; i++) notesOk = Math.abs(got.get(i) - (want[v - 1].get(i) + shifts[v - 1])) < 0.35;
            ok &= notesOk;
            sb.append(String.format("гравець %d: %d нот %s, схожість %.0f%%; ", v, got.size(), notesOk ? "✓" : "✗",
                    (Float) call(g, "similarity", new Class<?>[]{int.class}, v) * 100));
        }
        float s1 = (Float) call(g, "similarity", new Class<?>[]{int.class}, 1);
        float s2 = (Float) call(g, "similarity", new Class<?>[]{int.class}, 2);
        float s3 = (Float) call(g, "similarity", new Class<?>[]{int.class}, 3);
        check("Зіпсований телефон: ноти і схожість", ok && s1 > 0.95 && Math.abs(s2 - 0.6) < 0.08 && Math.abs(s3 - 0.35) < 0.08,
                sb + "(очікувалось ~100 / 60 / 35%)");
        System.out.println("REPORT12\n" + String.join("\n", report));
    }

    static float[] whistle(double hz, double sec, float amp) {
        float[] x = new float[(int) (sec * RATE)];
        double ph = 0;
        Random r = new Random(5);
        for (int i = 0; i < x.length; i++) {
            ph += 2 * Math.PI * hz * (1 + 0.01 * Math.sin(2 * Math.PI * 6 * i / RATE)) / RATE;
            x[i] = (float) (amp * Math.sin(ph) + 0.01 * amp * (r.nextFloat() * 2 - 1));     // a little breath noise
        }
        return x;
    }

    @Test
    public void hotPotato() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 57);
        Object g = get(a, "potatoGame");
        String[] names = {"свист", "писк", "«у»", "«ш»", "видих"};
        float[][] sounds = {whistle(1800, 1.5, 0.2f), voice(560, 1.5, 0.2f), hum(140, 1.5, 0.08), hiss(1.5, 0.05), blowNoise(1.5, 0.2f)};
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        for (int s2 = 0; s2 < 5; s2++) {
            sb.append(names[s2]).append(" → ");
            for (int t = 0; t < 5; t++) {
                mic(silence(1), true);
                run(250);
                call(g, "setTask", new Class<?>[]{int.class}, t);
                mic(concat(sounds[s2], silence(1)), false);
                run(1700);
                boolean done = (Boolean) call(g, "done", new Class<?>[]{});
                if (done) sb.append(names[t]).append(' ');
                ok &= done == (s2 == t);
            }
            sb.append("; ");
        }
        // Ticks of the fuse alone must not complete any task.
        float[] ticks = (float[]) staticCall("HotPotatoGame", "fuse", new Class<?>[]{float.class}, 6f);
        float[] tickPart = java.util.Arrays.copyOfRange(ticks, 4 * RATE, 6 * RATE);
        boolean tickFalse = false;
        for (int t = 0; t < 5; t++) {
            mic(silence(1), true);
            run(250);
            call(g, "setTask", new Class<?>[]{int.class}, t);
            mic(concat(tickPart, silence(0.5)), false);
            run(2300);
            tickFalse |= (Boolean) call(g, "done", new Class<?>[]{});
        }
        check("Гаряча картопля: розпізнавання завдань", ok && !tickFalse, sb + "цокання зараховано: " + tickFalse);

        // A real round: fuse of 6 s, one pass by humming, then the bang.
        mic(silence(1), true);
        run(250);
        call(g, "start", new Class<?>[]{float.class, int.class}, 6f, 2);
        float[] roundMic = mix(concat(silence(4.0), hum(150, 1.0, 0.08), silence(8)), new float[0]);
        float[] fuse6 = (float[]) staticCall("HotPotatoGame", "fuse", new Class<?>[]{float.class}, 6f);
        for (int i = 0; i < fuse6.length && i < roundMic.length; i++) roundMic[i] += 0.3f * fuse6[i];   // the phone's own ticking
        mic(roundMic, false);
        run(5200);
        shot(a, "99_potato_pass");
        waitState(g, "BOOM", 15000);
        shot(a, "99_potato_boom");
        check("Гаряча картопля: раунд", "BOOM".equals(call(g, "state", new Class<?>[]{})) && (Integer) call(g, "passes", new Class<?>[]{}) == 1,
                "вибух ✓, передач " + call(g, "passes", new Class<?>[]{}));
        System.out.println("REPORT13\n" + String.join("\n", report));
    }

    @Test
    public void singNote() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 58);
        Object g = get(a, "singGame");
        call(g, "setPlayers", new Class<?>[]{String.class}, "Аня, Богдан, Віка");
        call(g, "newGame", new Class<?>[]{long.class}, 21L);
        // Аня: +5 cents, an octave lower.  Богдан: 30 cents flat.  Віка: a whole tone off on the 2nd note.
        double[][] plan = {{0.05, -12}, {-0.30, 0}, {0.0, 12}};
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        for (int round = 0; round < 3; round++) {
            for (int p2 = 0; p2 < 3; p2++) {
                List<Float> target = new ArrayList<>((List<Float>) call(g, "target", new Class<?>[]{}));
                List<Float> sungNotes = new ArrayList<>();
                for (int i = 0; i < target.size(); i++) {
                    float m = (float) (target.get(i) + plan[p2][1] + plan[p2][0]);
                    if (p2 == 2 && i == 1) m += 2;
                    sungNotes.add(m);
                }
                mic(silence(1), true);
                run(200);
                call(g, "turn", new Class<?>[]{});
                run((long) (target.size() * 800 + 900));                // the reference plays
                mic(concat(silence(0.3), sing(sungNotes, 0.8, 0), silence(1)), false);
                long until = System.currentTimeMillis() + 20000;
                while (System.currentTimeMillis() < until && !("SCORED".equals(call(g, "state", new Class<?>[]{}))
                        || "TABLE".equals(call(g, "state", new Class<?>[]{})))) run(200);
                float sc = (Float) call(g, "lastScore", new Class<?>[]{});
                float want = p2 == 0 ? 90 : p2 == 1 ? 40 : (target.size() >= 2 ? 100f * (target.size() - 1) / target.size() : 100);
                ok &= Math.abs(sc - want) <= 8;
                sb.append(String.format("р%d %s: %.0f (≈%.0f)  ", round + 1, new String[]{"Аня", "Богдан", "Віка"}[p2], sc, want));
                if (round == 1 && p2 == 2) shot(a, "99_sing_scored");
            }
        }
        shot(a, "99_sing_table");
        check("Заспівай ноту: точність у центах", ok && "TABLE".equals(call(g, "state", new Class<?>[]{})), sb.toString());
        System.out.println("REPORT14\n" + String.join("\n", report));
    }

    static double gz(float[] x, int off, int n, double hz) {
        double w = 2 * Math.PI * hz / RATE, cf = 2 * Math.cos(w), s1 = 0, s2 = 0;
        for (int i = 0; i < n && off + i < x.length; i++) { double v = x[off + i] + cf * s1 - s2; s2 = s1; s1 = v; }
        return s1 * s1 + s2 * s2 - cf * s1 * s2;
    }

    static double midiHz(double m) { return 440 * Math.pow(2, (m - 69) / 12); }

    /** The strongest semitone (by its fundamental) in a stretch of sound. */
    static int strongestSemitone(float[] x, int off, int n) {
        int best = 0;
        double bv = -1;
        for (int m = 40; m < 80; m++) { double v = gz(x, off, n, midiHz(m)); if (v > bv) { bv = v; best = m; } }
        return best;
    }

    @Test
    public void soundQuiz() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        lesson(a, 59);
        Object g = get(a, "quizGame");
        Class<?> qc = Class.forName("com.example.audiospectrumpro.QuizGame");
        java.lang.reflect.Method make = qc.getDeclaredMethod("make", Random.class, int.class);
        make.setAccessible(true);
        int[] checked = new int[5], wrong = new int[5];
        Random r = new Random(77);
        for (int k = 0; k < 250; k++) {
            int type = k % 5;
            Object q = make.invoke(null, r, type);
            float[] pcm = (float[]) get(q, "pcm");
            String[] opts = (String[]) get(q, "options");
            int ans = (Integer) get(q, "answer");
            boolean ok;
            switch (type) {
                case 0: {
                    java.util.Set<String> distinct = new java.util.HashSet<>(java.util.Arrays.asList(opts));
                    String inst = opts[ans];
                    ok = distinct.size() == 4;
                    if (inst.equals("Кларнет")) {      // odd harmonics only
                        double f = midiHz(60);
                        ok &= gz(pcm, 4800, 9600, 2 * f) < 0.02 * gz(pcm, 4800, 9600, f) && gz(pcm, 4800, 9600, 3 * f) > 0.03 * gz(pcm, 4800, 9600, f);
                    }
                    if (inst.equals("Флейта")) ok &= gz(pcm, 4800, 9600, 3 * midiHz(60)) < 0.01 * gz(pcm, 4800, 9600, midiHz(60));
                    if (inst.equals("Дзвіночок")) ok &= gz(pcm, 0, 9600, 2 * 2.76 * midiHz(60)) > 0.05 * gz(pcm, 0, 9600, 2 * midiHz(60));
                    break;
                }
                case 1: {
                    double[] hz = {100, 440, 1000, 4000};
                    int best = 0;
                    for (int i = 1; i < 4; i++) if (gz(pcm, 4800, 24000, hz[i]) > gz(pcm, 4800, 24000, hz[best])) best = i;
                    ok = best == ans;
                    break;
                }
                case 2: {
                    int m1 = strongestSemitone(pcm, 2400, 24000), m2 = strongestSemitone(pcm, (int) (0.9 * RATE) + 2400, 24000);
                    int truth = m2 > m1 ? 0 : m2 < m1 ? 1 : 2;
                    ok = truth == ans;
                    break;
                }
                case 3: {
                    int beeps = 0;
                    boolean on = false;
                    for (int off = 0; off + 480 <= pcm.length; off += 480) {
                        double e = 0;
                        for (int i = 0; i < 480; i++) e += pcm[off + i] * pcm[off + i];
                        boolean loud = e / 480 > 1e-3;
                        if (loud && !on) beeps++;
                        on = loud;
                    }
                    ok = String.valueOf(beeps).equals(opts[ans]);
                    break;
                }
                default: {
                    double bestMaj = 0, bestMin = 0;
                    for (int m = 50; m < 66; m++) {
                        double r0 = gz(pcm, 2400, 48000, midiHz(m)), r5 = gz(pcm, 2400, 48000, midiHz(m + 7));
                        bestMaj = Math.max(bestMaj, Math.min(Math.min(r0, r5), gz(pcm, 2400, 48000, midiHz(m + 4))));
                        bestMin = Math.max(bestMin, Math.min(Math.min(r0, r5), gz(pcm, 2400, 48000, midiHz(m + 3))));
                    }
                    ok = (bestMaj > bestMin ? 0 : 1) == ans;
                    break;
                }
            }
            checked[type]++;
            if (!ok) wrong[type]++;
        }
        check("Квіз: правильні відповіді справді правильні", wrong[0] + wrong[1] + wrong[2] + wrong[3] + wrong[4] == 0,
                String.format("перевірено незалежним аналізом звуку по 50 питань кожного типу; помилок: інструменти %d, частота %d, вище/нижче %d, піки %d, мажор/мінор %d",
                        wrong[0], wrong[1], wrong[2], wrong[3], wrong[4]));

        // The game flow: two teams, ten questions, a tap on the screen answers.
        call(g, "setPlayers", new Class<?>[]{String.class}, "Сови, Їжаки");
        call(g, "newGame", new Class<?>[]{long.class}, 5L);
        run(300);
        shot(a, "99_quiz_start");
        View v = (View) viz(a);
        RectF[] boxes = (RectF[]) get(g, "boxes");
        Object q0 = call(g, "question", new Class<?>[]{});
        RectF hit = boxes[(Integer) get(q0, "answer")];
        long t0 = android.os.SystemClock.uptimeMillis();
        v.dispatchTouchEvent(android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, hit.centerX(), hit.centerY(), 0));
        v.dispatchTouchEvent(android.view.MotionEvent.obtain(t0, t0 + 50, android.view.MotionEvent.ACTION_UP, hit.centerX(), hit.centerY(), 0));
        run(200);
        shot(a, "99_quiz_question");
        int tapScore = (Integer) call(g, "score", new Class<?>[]{int.class}, 0);
        call(g, "next", new Class<?>[]{});
        for (int i = 1; i < 10; i++) {
            Object q = call(g, "question", new Class<?>[]{});
            int ans = (Integer) get(q, "answer");
            call(g, "answer", new Class<?>[]{int.class}, i % 3 == 0 ? (ans + 1) % 4 : ans);    // every third answer wrong
            call(g, "next", new Class<?>[]{});
        }
        run(200);
        shot(a, "99_quiz_table");
        int s0 = (Integer) call(g, "score", new Class<?>[]{int.class}, 0), s1 = (Integer) call(g, "score", new Class<?>[]{int.class}, 1);
        check("Квіз: гра двома командами", tapScore == 1 && (Boolean) call(g, "finished", new Class<?>[]{}) && s0 == 4 && s1 == 3,
                String.format("дотик до правильної відповіді зараховано: %s; рахунок Сови %d, Їжаки %d (очікувалось 4 і 3)", tapScore == 1, s0, s1));
        System.out.println("REPORT15\n" + String.join("\n", report));
    }

    /** Beeps every 0.6 s whose loudness follows a walk towards (and then next to) the hidden phone. */
    static float[] approach(double hz, double sec, double ampFrom, double ampTo, double reachAtS) {
        float[] x = new float[(int) (sec * RATE)];
        for (int b = 0; b * 0.6 < sec; b++) {
            double t = b * 0.6, f = Math.min(1, t / reachAtS);
            double amp = ampFrom * Math.pow(ampTo / ampFrom, f);
            int at = (int) (t * RATE), len = (int) (0.15 * RATE);
            for (int i = 0; i < len && at + i < x.length; i++) {
                double env = Math.min(1, Math.min(i, len - i) / 240.0);
                x[at + i] += (float) (amp * env * Math.sin(2 * Math.PI * hz * i / RATE));
            }
        }
        return x;
    }

    @Test
    public void hideAndSeek() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 60);
        Object g = get(a, "hideSeek");
        String[] mode = {"3 кГц", "15 кГц"};
        double[] hz = {3000, 15000};
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        for (int m = 0; m < 2; m++) {
            setField(g, "hider", false);
            setField(g, "quiet", m == 1);
            mic(silence(1), true);
            run(300);
            call(g, "start", new Class<?>[]{});
            float[] go = modem("GO", 0.3f);
            // Walking from far away (very quiet) to right next to it in 12 s, people talking around.
            float[] walk = mix(approach(hz[m], 18, 0.0003, 0.4, 12), voice(200, 18, 0.05f));
            mic(concat(silence(0.5), go, walk), false);
            run((long) (500 + go.length * 1000L / RATE + 6000));
            int trendMid = (Integer) call(g, "trend", new Class<?>[]{});
            float heatMid = (Float) call(g, "heat", new Class<?>[]{});
            if (m == 0) shot(a, "99_hide_seeking");
            waitState(g, "FOUND", 25000);
            if (m == 0) shot(a, "99_hide_found");
            float found = (Float) call(g, "foundSeconds", new Class<?>[]{});
            boolean thisOk = "FOUND".equals(call(g, "state", new Class<?>[]{})) && trendMid == 1 && found > 8 && found < 13.5;
            ok &= thisOk;
            sb.append(String.format("%s: на півдорозі тепло %.2f і «тепліше» %s, знайдено за %.1f с (дійшли за 12 с) %s; ",
                    mode[m], heatMid, trendMid == 1 ? "✓" : "✗", found, thisOk ? "✓" : "✗"));
        }
        // Only talking, no hidden phone: never "found".
        setField(g, "quiet", false);
        mic(silence(1), true);
        run(300);
        call(g, "startNow", new Class<?>[]{});
        mic(concat(voice(300, 4, 0.3f), whistle(2500, 2, 0.2f), silence(1)), false);
        run(7500);
        boolean noFalse = !"FOUND".equals(call(g, "state", new Class<?>[]{}));
        check("Хованки: старт за сигналом і «знайшли»", ok && noFalse, sb + "розмова і свист без маячка: " + (noFalse ? "не знайдено ✓" : "хибне «знайшли» ✗"));
        System.out.println("REPORT16\n" + String.join("\n", report));
    }

    @Test
    public void spies() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 61);
        Object g = get(a, "spyGame");
        String plain = "ЇЖАК ЄНОТ ҐАВА ЩУКА", secretKey = "11";
        String enc = (String) staticCall("SpyGame", "caesar", new Class<?>[]{String.class, int.class}, plain, 11);
        String back = (String) staticCall("SpyGame", "caesar", new Class<?>[]{String.class, int.class}, enc, -11);
        // What the sender phone would play, heard by the receiver from a metre away with some noise.
        float[] msg = modem("S:" + enc, 0.25f);
        call(g, "listen", new Class<?>[]{});
        mic(mix(concat(silence(0.5), msg, silence(1.5)), noise(msg.length / (double) RATE + 2, 0.01f)), false);
        run(msg.length * 1000L / RATE + 2000);
        String got = (String) call(g, "cipher", new Class<?>[]{});
        // The receiver tries keys 1..11.
        String found = null;
        for (int key = 1; key <= 32; key++) {
            call(g, "setKey", new Class<?>[]{int.class}, key);
            if (plain.equals(call(g, "attempt", new Class<?>[]{}))) { found = String.valueOf(key); break; }
        }
        call(g, "cracked", new Class<?>[]{});
        run(200);
        shot(a, "99_spy_cracked");
        check("Шпигуни: шифр, передача звуком і злам", back.equals(plain) && !enc.equals(plain) && enc.equals(got) && secretKey.equals(found),
                "«" + plain + "» → «" + enc + "», прийнято «" + got + "», ключ знайдено перебором: " + found);
        System.out.println("REPORT17\n" + String.join("\n", report));
    }

    @Test
    public void cowboyDuel() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 62);
        Object g = get(a, "cowboy");
        long seed = 12;
        float[] track = (float[]) staticCall("CowboyDuel", "track", new Class<?>[]{long.class}, seed);
        float[] waits = (float[]) staticCall("CowboyDuel", "waits", new Class<?>[]{long.class}, seed);
        // Seen from player 2: player 1's track from a metre away, own carrier, a finger at these reactions.
        double[] react = {240, 310, 199, 420, 260};
        double t0 = 0.6;
        float[] x = new float[(int) ((t0 + track.length / (double) RATE + 12) * RATE)];
        for (int i = 0; i < track.length; i++) x[(int) (t0 * RATE) + i] += 0.3f * track[i];
        float[] gain = new float[x.length];
        java.util.Arrays.fill(gain, 1f);
        double at = t0;
        for (int r = 0; r < 5; r++) {
            at += waits[r];
            int c0 = (int) ((at + react[r] / 1000) * RATE);
            for (int i = c0; i < c0 + RATE / 3; i++) gain[i] = 0.1f;
            at += 2.5;
        }
        int falseAt = (int) ((t0 + 1.0) * RATE);                                  // twitchy finger before round 1
        for (int i = falseAt; i < falseAt + RATE / 5; i++) gain[i] = 0.1f;
        for (int i = 0; i < x.length; i++) x[i] = (x[i] + 0.12f * (float) Math.sin(2 * Math.PI * 18200 * i / RATE) * gain[i]) * (gain[i] < 1 ? 0.6f : 1f);
        float[] p1 = modem("P1:220,330,250,300,500", 0.3f);
        int endAt = (int) ((t0 + track.length / (double) RATE + 0.9) * RATE);
        for (int i = 0; i < p1.length; i++) x[endAt + i] += p1[i];
        setField(g, "me", 2);
        call(g, "start", new Class<?>[]{});
        mic(x, false);
        waitState(g, "RESULT", 90000);
        shot(a, "99_cowboy_result");
        List<?> mine = (List<?>) call(g, "mine", new Class<?>[]{});
        List<?> theirs = (List<?>) call(g, "theirs", new Class<?>[]{});
        int[] wins = (int[]) call(g, "wins", new Class<?>[]{});
        boolean ok = mine.size() == 5 && theirs.size() == 5;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; ok && i < 5; i++) {
            float v = (Float) mine.get(i);
            ok &= Math.abs(v - react[i]) <= 30;
            sb.append(String.format("%.0f/%.0f ", v, react[i]));
        }
        check("Ковбойська дуель (гравець 2)", ok && wins[0] == 3 && wins[1] == 2 && (Integer) call(g, "falseStarts", new Class<?>[]{}) == 1,
                "моя реакція виміряно/задано: " + sb + "; суперник " + theirs + "; раунди " + wins[0] + ":" + wins[1]
                        + "; фальстартів " + call(g, "falseStarts", new Class<?>[]{}));
        System.out.println("REPORT18\n" + String.join("\n", report));
    }

    @Test
    public void relay() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 63);
        Object g = get(a, "relayGame");
        StringBuilder sb = new StringBuilder();

        // Story mode, phone 2 of 3: hears phone 1 (and ignores phone 3's later message), adds a word.
        setField(g, "me", 2); setField(g, "phones", 3); setField(g, "auto", false);
        call(g, "reset", new Class<?>[]{});
        float[] m1 = modem("R1:Жив собі", 0.3f), m3 = modem("DONE:щось інше", 0.3f);
        mic(concat(silence(0.4), m1, silence(0.5), m3, silence(1)), false);
        run((m1.length + m3.length) * 1000L / RATE + 2000);
        String heard = (String) call(g, "story", new Class<?>[]{});
        call(g, "addAndSend", new Class<?>[]{String.class}, "веселий");
        String sent2 = (String) call(g, "lastSent", new Class<?>[]{});
        boolean ok1 = "Жив собі".equals(heard) && "R2:Жив собі веселий".equals(sent2);
        sb.append("історія: почув «").append(heard).append("», передав «").append(sent2).append("» ").append(ok1 ? "✓" : "✗").append("; ");
        shot(a, "99_relay_story");

        // Fast relay, last phone (3 of 3) passes the message back to phone 1 by itself.
        setField(g, "me", 3); setField(g, "auto", true);
        call(g, "reset", new Class<?>[]{});
        float[] m2 = modem("R2:Швидко!", 0.3f);
        mic(concat(silence(0.4), m2, silence(3)), false);
        run(m2.length * 1000L / RATE + 2500);
        String sent3 = (String) call(g, "lastSent", new Class<?>[]{});
        boolean ok2 = "DONE:Швидко!".equals(sent3);
        sb.append("автопередача: «").append(sent3).append("» ").append(ok2 ? "✓" : "✗").append("; ");

        // Phone 1 gets back a long story (about 85 bytes) in noise and times the lap.
        setField(g, "me", 1);
        call(g, "reset", new Class<?>[]{});
        mic(silence(1), true);
        run(200);
        call(g, "addAndSend", new Class<?>[]{String.class}, "Жив");
        String longStory = "Жив собі веселий кіт який любив співати пісні";
        float[] own = modem("R1:Жив", 1f);
        run(own.length * 1000L / RATE + 100);
        float[] back = modem("DONE:" + longStory, 0.3f);
        mic(mix(concat(silence(1.5), back, silence(2)), noise(back.length / (double) RATE + 3.5, 0.01f)), false);
        run(back.length * 1000L / RATE + 3000);
        shot(a, "99_relay_done");
        boolean ok3 = (Boolean) call(g, "finished", new Class<?>[]{}) && longStory.equals(call(g, "story", new Class<?>[]{}))
                && (Float) call(g, "lapSeconds", new Class<?>[]{}) > 1;
        sb.append(String.format("коло: повернулось «%s» (%d байт) за %.1f с %s", call(g, "story", new Class<?>[]{}),
                longStory.getBytes("UTF-8").length, (Float) call(g, "lapSeconds", new Class<?>[]{}), ok3 ? "✓" : "✗"));
        check("Естафета історії", ok1 && ok2 && ok3, sb.toString());
        System.out.println("REPORT19\n" + String.join("\n", report));
    }

    @Test
    public void echoSpeed() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 64);
        Object e = get(a, "echoSpeed");
        double c = 331.3 + 0.606 * 17;                                 // 17 °C
        double[] dist = {0.5, 0.5, 1.0, 1.5};
        Random r = new Random(3);
        for (double d : dist) {
            setField(e, "distance", (float) d);
            mic(silence(1), true);
            run(200);
            call(e, "measure", new Class<?>[]{});
            double t0 = 0.12 + r.nextDouble() * 0.1;                    // speaker latency, unknown to the app
            float[] x = mix(noise(1.2, 0.004f), silence(1.2));
            chirpAt(x, t0, 0.8f);                                      // own chirp, straight into the mic
            chirpAt(x, t0 + 0.0012, 0.3f);                              // the table
            chirpAt(x, t0 + 2 * d / c + 0.0004, (float) (0.12 / d));    // the wall (a little offset: mic not at the speaker)
            chirpAt(x, t0 + 2 * d / c + 0.004, (float) (0.05 / d));     // floor/ceiling path
            mic(x, false);
            run(1500);
        }
        shot(a, "99_echo_speed");
        int n = (Integer) call(e, "count", new Class<?>[]{});
        double sp = (Double) call(e, "speed", new Class<?>[]{});
        double t = (sp - 331.3) / 0.606;
        check("Швидкість звуку однією рукою", n == 4 && Math.abs(sp - c) < 1.5,
                String.format("вимірів %d, c = %.1f м/с (правда %.1f), T = %.1f °C (правда 17)", n, sp, c, t));
        System.out.println("REPORT20\n" + String.join("\n", report));
    }

    /** A 4 kHz tone from a source whose speed towards the listener is v(t). */
    static float[] doppler(double sec, Curve v, float amp) {
        float[] x = new float[(int) (sec * RATE)];
        double ph = 0, c = 343;
        for (int i = 0; i < x.length; i++) {
            double f = 4000 * c / (c - v.at((double) i / RATE));
            ph += 2 * Math.PI * f / RATE;
            x[i] = (float) (amp * Math.sin(ph));
        }
        return x;
    }

    @Test
    public void dopplerEffect() throws Exception {
        installMic();
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 0).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        mic(silence(1), true);
        lesson(a, 65);
        Object d = get(a, "doppler");
        setField(d, "source", false);
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        // Swung on a cord: 1 m radius, one turn per second -> 6.28 m/s.  Walking past: 1.4 m/s.
        final double swing = 2 * Math.PI * 1.0;
        Curve[] moves = {
                new Curve() { public double at(double t) { return swing * Math.sin(2 * Math.PI * t); } },
                new Curve() { public double at(double t) { return 1.4 * Math.tanh((3 - t) * 1.5); } }};
        double[] truth = {swing, 1.4};
        String[] names = {"шнурок", "крок повз"};
        for (int m = 0; m < 2; m++) {
            call(d, "start", new Class<?>[]{});
            mic(mix(doppler(6, moves[m], 0.05f), voice(180, 6, 0.02f)), false);
            run(6000);
            if (m == 0) shot(a, "99_doppler");
            float v = (Float) call(d, "speed", new Class<?>[]{});
            boolean good = Math.abs(v - truth[m]) < Math.max(0.15, truth[m] * 0.05);
            ok &= good;
            sb.append(String.format("%s: %.2f м/с (правда %.2f) %s; ", names[m], v, truth[m], good ? "✓" : "✗"));
        }
        check("Ефект Доплера: швидкість з висоти тону", ok, sb.toString());
        System.out.println("REPORT21\n" + String.join("\n", report));
    }
}
