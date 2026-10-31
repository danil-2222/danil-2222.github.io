package shots;

import android.Manifest;
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
}
