package shots;

import android.Manifest;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Spinner;

import com.example.audiospectrumpro.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34)
public class Shots {
    static final File OUT = new File(System.getProperty("shots.dir"));

    MainActivity setup() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        app.getSharedPreferences("settings", 0).edit().putInt("source", 1).commit();
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        return c.get();
    }

    static void gen(String wave, float freq, float level) throws Exception {
        Class<?> g = Class.forName("com.example.audiospectrumpro.SignalGenerator");
        Class<?> w = Class.forName("com.example.audiospectrumpro.SignalGenerator$Waveform");
        set(g, "waveform", Enum.valueOf((Class) w, wave));
        set(g, "frequency", freq);
        set(g, "level", level);
    }

    static void set(Class<?> c, String name, Object v) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    static void run(long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(20);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    static void select(MainActivity a, String idName, int pos) {
        int id = a.getResources().getIdentifier(idName, "id", a.getPackageName());
        ((Spinner) a.findViewById(id)).setSelection(pos);
        shadowOf(Looper.getMainLooper()).idle();
    }

    static void click(MainActivity a, String idName) {
        int id = a.getResources().getIdentifier(idName, "id", a.getPackageName());
        a.findViewById(id).performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    static void shot(MainActivity a, String name) throws Exception {
        View d = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(d.getWidth(), d.getHeight(), Bitmap.Config.ARGB_8888);
        d.draw(new Canvas(b));
        OUT.mkdirs();
        FileOutputStream o = new FileOutputStream(new File(OUT, name + ".png"));
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        o.close();
    }

    static void touch(View v, float x, float y, int action) {
        long t = SystemClock.uptimeMillis();
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, action, x, y, 0));
    }

    @Test @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void portrait() throws Exception {
        MainActivity a = setup();
        gen("SAWTOOTH", 220, 0.4f);
        run(1500);
        shot(a, "01_line");

        click(a, "tabDisplay");
        select(a, "spinMode", 1);
        gen("PINK_NOISE", 1000, 0.5f);
        run(1500);
        shot(a, "02_bars");

        click(a, "tabAnalysis");
        select(a, "spinMode", 2);
        run(2500);
        shot(a, "03_rta");

        click(a, "tabGen");
        select(a, "spinMode", 3);
        gen("LOG_SWEEP", 1000, 0.5f);
        run(9000);
        shot(a, "04_waterfall");

        click(a, "tabMain");
        select(a, "spinMode", 5);
        gen("DUAL_TONE", 300, 0.5f);
        run(1000);
        shot(a, "05_scope");

        select(a, "spinMode", 6);
        gen("SINE", 446, 0.5f);
        run(1500);
        shot(a, "06_tuner");

        select(a, "spinMode", 0);
        gen("SINE", 1000, 0.5f);
        run(1200);
        click(a, "btnReference");
        gen("SQUARE", 440, 0.3f);
        run(1500);
        int vid = a.getResources().getIdentifier("visualizer2D", "id", a.getPackageName());
        View viz = a.findViewById(vid);
        touch(viz, viz.getWidth() * 0.62f, viz.getHeight() * 0.5f, MotionEvent.ACTION_DOWN);
        touch(viz, viz.getWidth() * 0.62f, viz.getHeight() * 0.5f, MotionEvent.ACTION_UP);
        run(300);
        shot(a, "07_reference_cursor");

        click(a, "btnReference");
        touch(viz, viz.getWidth() * 0.62f, viz.getHeight() * 0.5f, MotionEvent.ACTION_DOWN);
        touch(viz, viz.getWidth() * 0.62f, viz.getHeight() * 0.5f, MotionEvent.ACTION_UP);
        java.lang.reflect.Method zoom = viz.getClass().getDeclaredMethod("zoom", float.class, float.class);
        zoom.setAccessible(true);
        float x440 = viz.getWidth() * (float) (Math.log(440 / 20.0) / Math.log(24000 / 20.0));
        zoom.invoke(viz, x440, 6f);
        gen("DUAL_TONE", 440, 0.5f);
        select(a, "spinMode", 0);
        run(1500);
        shot(a, "09_zoom");

        click(a, "btnFullscreen");
        gen("PINK_NOISE", 440, 0.5f);
        select(a, "spinMode", 3);
        run(5000);
        shot(a, "10_fullscreen_waterfall");
    }

    @Test @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void liveLearningModes() throws Exception {
        MainActivity a = setup();
        gen("SAWTOOTH", 196, 0.4f);
        select(a, "spinMode", 7);
        run(1500);
        shot(a, "44_live_fourier");
        select(a, "spinMode", 10);
        run(1500);
        shot(a, "45_live_air");
    }

    @Test @Config(sdk = 34, qualifiers = "w891dp-h411dp-land-xxhdpi")
    public void landscape() throws Exception {
        MainActivity a = setup();
        gen("SAWTOOTH", 110, 0.4f);
        run(1500);
        shot(a, "08_landscape");
    }

    static Object call(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method m = target.getClass().getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    /** Stereo 44.1 kHz test file: a short melody with harmonics, a chord and a noise burst. */
    static File makeWav() throws Exception {
        int rate = 44100, secs = 8;
        int n = rate * secs;
        float[] notes = {261.63f, 293.66f, 329.63f, 349.23f, 392f, 440f, 493.88f, 523.25f};
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i < n; i++) {
            double t = (double) i / rate;
            double v = 0;
            if (t < 4) {
                int k = (int) (t / 0.5);
                double f = notes[k], local = t - k * 0.5;
                double env = Math.exp(-local * 4);
                for (int h = 1; h <= 6; h++) v += env * Math.sin(2 * Math.PI * f * h * t) / h * 0.4;
            } else if (t < 6) {
                for (float f : new float[]{261.63f, 329.63f, 392f}) v += 0.2 * Math.sin(2 * Math.PI * f * t);
            } else if (t < 6.5) {
                v = (rnd.nextDouble() * 2 - 1) * 0.5;
            } else {
                double f = 200 * Math.pow(40, (t - 6.5) / 1.5);
                v = 0.4 * Math.sin(2 * Math.PI * 200 * 1.5 / Math.log(40) * (Math.pow(40, (t - 6.5) / 1.5) - 1));
            }
            short l = (short) (v * 32767), r = (short) (v * 0.8 * 32767);
            data.write(l & 0xFF); data.write((l >> 8) & 0xFF);
            data.write(r & 0xFF); data.write((r >> 8) & 0xFF);
        }
        byte[] pcm = data.toByteArray();
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2).putInt(rate)
                .putInt(rate * 4).putShort((short) 4).putShort((short) 16);
        h.put("data".getBytes()).putInt(pcm.length);
        File f = new File(OUT, "melody.wav");
        OUT.mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        o.write(h.array());
        o.write(pcm);
        o.close();
        return f;
    }

    @Test @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void timeMachine() throws Exception {
        MainActivity a = setup();
        select(a, "spinMode", 3);
        gen("LOG_SWEEP", 1000, 0.5f);
        run(6000);
        gen("SAWTOOTH", 330, 0.4f);
        run(3000);
        click(a, "btnTimeMachine");
        run(4000);
        shot(a, "11_time_machine_waterfall");

        Object engine = getField(a, "engine");
        call(engine, "seek", new Class<?>[]{int.class}, 48000 * 3);
        select(a, "spinMode", 0);
        run(1500);
        shot(a, "12_time_machine_line");

        call(engine, "setPlaying", new Class<?>[]{boolean.class}, true);
        run(1000);
        System.out.println("PLAYHEAD after 1s play: " + call(engine, "getPlayhead", new Class<?>[]{}));
        click(a, "btnTapeClose");

        File wav = makeWav();
        call(a, "loadFile", new Class<?>[]{android.net.Uri.class}, android.net.Uri.fromFile(wav));
        run(2000);
        select(a, "spinMode", 3);
        run(5000);
        shot(a, "13_file_waterfall");
        call(engine, "seek", new Class<?>[]{int.class}, 44100 * 5);
        select(a, "spinMode", 2);
        run(1500);
        shot(a, "14_file_rta_chord");
    }

    static Object getField(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    @Test @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void learning() throws Exception {
        MainActivity a = setup();
        click(a, "tabLearn");
        lesson(a, 3);
        run(2500);
        shot(a, "15_fourier_saw");

        click(a, "btnPresetSquare");
        run(2000);
        shot(a, "16_fourier_square");

        lesson(a, 4);
        run(2000);
        shot(a, "17_hints_square");

        click(a, "btnPresetVoice");
        run(2000);
        shot(a, "18_hints_voice");

        click(a, "btnPresetSine");
        run(2000);
        shot(a, "19_hints_sine");

        select(a, "spinMode", 0);
        gen("PINK_NOISE", 440, 0.5f);
        run(2000);
        shot(a, "20_hints_noise");
    }

    static void progress(MainActivity a, String idName, int value) {
        int id = a.getResources().getIdentifier(idName, "id", a.getPackageName());
        ((android.widget.SeekBar) a.findViewById(id)).setProgress(value);
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Mono 48 kHz: a clap (short burst) followed by an exponentially decaying noise tail, RT60 = 0.7 s. */
    static File makeClapWav() throws Exception {
        int rate = 48000, n = rate * 3;
        java.util.Random rnd = new java.util.Random(3);
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < n; i++) {
            double t = (double) i / rate, v = (rnd.nextDouble() * 2 - 1) * 0.0003;
            double since = t - 0.6;
            if (since >= 0) v += (rnd.nextDouble() * 2 - 1) * 0.6 * Math.pow(10, -3 * since / 0.7);
            short sv = (short) Math.max(-32768, Math.min(32767, v * 32767));
            data.write(sv & 0xFF); data.write((sv >> 8) & 0xFF);
        }
        byte[] pcm = data.toByteArray();
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(rate)
                .putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        h.put("data".getBytes()).putInt(pcm.length);
        File f = new File(OUT, "clap.wav");
        OUT.mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        o.write(h.array());
        o.write(pcm);
        o.close();
        return f;
    }

    @Test @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void lab() throws Exception {
        MainActivity a = setup();
        Object engine = getField(a, "engine");
        click(a, "tabLearn");

        lesson(a, 7);
        select(a, "spinSource", 1);
        float[] melody = {261.63f, 293.66f, 329.63f, 349.23f, 392f, 392f, 440f, 392f};
        for (float f : melody) { gen("SINE", f, 0.4f); run(600); }
        gen("SINE", 349.23f * 1.012f, 0.4f); // slightly sharp
        run(700);
        shot(a, "21_pitch");

        lesson(a, 9);
        run(1200);
        shot(a, "22_intervals_fifth");
        select(a, "spinInterval", 1);
        run(800);
        shot(a, "23_intervals_second");
        select(a, "spinInterval", 0);
        progress(a, "seekDetune", 20);
        run(800);
        shot(a, "24_intervals_beats");

        lesson(a, 10);
        select(a, "spinSource", 1);
        click(a, "btnPresetSaw");
        run(2000);
        shot(a, "25_air");

        lesson(a, 12);
        run(300);
        shot(a, "26_sampling_ok");
        progress(a, "seekSampleSig", 860); // ~7.9 kHz at fs = 8 kHz
        run(300);
        shot(a, "27_sampling_alias");

        File wav = makeWav();
        lesson(a, 8);
        call(a, "loadFile", new Class<?>[]{android.net.Uri.class}, android.net.Uri.fromFile(wav));
        run(1500);
        call(engine, "seek", new Class<?>[]{int.class}, (int) (44100 * 4.3));
        call(engine, "setPlaying", new Class<?>[]{boolean.class}, true);
        run(1200);
        call(engine, "setPlaying", new Class<?>[]{boolean.class}, false);
        run(300);
        shot(a, "28_chroma_chord");
        click(a, "btnTapeClose");

        File clap = makeClapWav();
        lesson(a, 11);
        call(a, "loadFile", new Class<?>[]{android.net.Uri.class}, android.net.Uri.fromFile(clap));
        run(1500);
        call(engine, "setPlaying", new Class<?>[]{boolean.class}, true);
        run(3500);
        shot(a, "29_echo");
    }

    static SonarFrames sonarFrames(boolean motion, int count) { return new SonarFrames(motion, count); }

    /** Synthetic recording: direct path, a wall at 1.5 m and a hand moving 0.9 m -> 0.35 m -> 0.7 m. */
    static final class SonarFrames {
        final java.util.List<Object> frames = new java.util.ArrayList<>();
        SonarFrames(boolean motion, int count) {
            int rate = 48000, P = 4800;
            com.example.audiospectrumpro.SonarProcessor sp =
                    new com.example.audiospectrumpro.SonarProcessor(rate, 18000, 22000, 10, P, 3f);
            float[] ping = sp.pingBuffer(0.8f);
            java.util.Random r = new java.util.Random(5);
            float[] buf = new float[2 * P];
            for (int n = 0; n < count; n++) {
                float tt = (float) n / count;
                float hand = tt < 0.5f ? 0.9f - tt * 1.1f : 0.35f + (tt - 0.5f) * 0.7f;
                float[] fresh = new float[P];
                int handDelay = Math.round(2 * hand / 343f * rate), wall = Math.round(2 * 1.5f / 343f * rate);
                for (int i = 0; i < P; i++) {
                    float v = 0.5f * ping[(i - 500 + P) % P];
                    v += 0.015f * ping[(i - 500 - wall + 2 * P) % P];
                    v += 0.006f * ping[(i - 500 - handDelay + 2 * P) % P];
                    v += 0.004f * ping[(i - 500 - Math.round(2 * 2.4f / 343f * rate) + 3 * P) % P];
                    v += (float) r.nextGaussian() * 0.002f;
                    fresh[i] = v;
                }
                System.arraycopy(buf, P, buf, 0, P);
                System.arraycopy(fresh, 0, buf, P, P);
                if (n > 0) frames.add(sp.process(buf, motion));
            }
        }
    }

    @Test @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void sonar() throws Exception {
        MainActivity a = setup();
        click(a, "tabSonar");
        click(a, "btnSonar"); // exercise the real start/stop path (shadow audio)
        run(500);
        click(a, "btnSonar");
        run(300);
        int vid = a.getResources().getIdentifier("visualizer2D", "id", a.getPackageName());
        Object viz = a.findViewById(vid);
        Class<?> modeCls = Class.forName("com.example.audiospectrumpro.VisualizerView$Mode");
        call(viz, "setMode", new Class<?>[]{modeCls}, Enum.valueOf((Class) modeCls, "SONAR"));
        call(viz, "setSonarBand", new Class<?>[]{String.class}, "Ультразвук 18–22 кГц");
        for (Object f : sonarFrames(false, 120).frames) {
            call(viz, "setSonarFrame", new Class<?>[]{com.example.audiospectrumpro.SonarProcessor.Frame.class}, f);
        }
        run(200);
        shot(a, "30_sonar_static");
        call(viz, "clearSonar", new Class<?>[]{});
        for (Object f : sonarFrames(true, 120).frames) {
            call(viz, "setSonarFrame", new Class<?>[]{com.example.audiospectrumpro.SonarProcessor.Frame.class}, f);
        }
        run(200);
        shot(a, "31_sonar_motion");
    }

    static void lesson(MainActivity a, int id) throws Exception {
        click(a, "tabLearn");
        call(a, "openLesson", new Class<?>[]{int.class, String.class}, id, "Урок " + id);
        shadowOf(Looper.getMainLooper()).idle();
    }

    static android.view.View findText(android.view.View v, String text) {
        if (v instanceof android.widget.TextView && text.equals(((android.widget.TextView) v).getText().toString())) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View r = findText(g.getChildAt(i), text);
                if (r != null) return r;
            }
        }
        return null;
    }

    static void clickText(MainActivity a, String text) {
        android.view.View v = findText(a.getWindow().getDecorView(), text);
        if (v == null) throw new AssertionError("no view with text " + text);
        v.performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    static void buffer(float[] pcm, boolean loop) throws Exception {
        Class<?> g = Class.forName("com.example.audiospectrumpro.SignalGenerator");
        Class<?> w = Class.forName("com.example.audiospectrumpro.SignalGenerator$Waveform");
        set(g, "buffer", pcm);
        set(g, "bufferLoop", loop);
        Field v = g.getDeclaredField("bufferVersion");
        v.setAccessible(true);
        v.set(null, (Integer) v.get(null) + 1);
        set(g, "waveform", Enum.valueOf((Class) w, "BUFFER"));
        set(g, "level", 0.5f);
    }

    static float[] burst(float[] dst, int at, int len, float amp, java.util.Random r, int rate, float decayMs) {
        for (int i = 0; i < len && at + i < dst.length; i++) {
            dst[at + i] += (float) ((r.nextDouble() * 2 - 1) * amp * Math.exp(-i * 1000.0 / rate / decayMs));
        }
        return dst;
    }

    @Test @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void academy() throws Exception {
        MainActivity a = setup();
        click(a, "tabLearn");
        run(300);
        shot(a, "32_academy_home");
        clickText(a, "Передача й творчість\nМалюнок у звук, звуковий модем, азбука Морзе");
        run(200);
        shot(a, "33_academy_list");

        lesson(a, 13);
        run(300);
        shot(a, "34_draw_editor");
        clickText(a, "▶ Програти і показати на спектрограмі");
        run(7000);
        shot(a, "35_draw_spectrogram");

        lesson(a, 14);
        select(a, "spinSource", 1);
        clickText(a, "📤 Надіслати звуком");
        run(9000);
        shot(a, "36_modem");

        lesson(a, 19);
        select(a, "spinSource", 1);
        clickText(a, "📤 Надіслати Морзе");
        run(5000);
        shot(a, "37_morse");

        lesson(a, 15);
        select(a, "spinSource", 1);
        gen("SINE", 230, 0.4f);
        run(1500);
        shot(a, "38_bottle");

        lesson(a, 17);
        select(a, "spinSource", 1);
        gen("SINE", 76, 0.4f);
        run(2500);
        shot(a, "39_room");

        lesson(a, 18);
        select(a, "spinSource", 1);
        int rate = 48000;
        float[] clap = new float[rate * 2];
        java.util.Random r = new java.util.Random(9);
        burst(clap, rate / 2, rate / 10, 0.9f, r, rate, 4);
        burst(clap, rate / 2 + Math.round(2 * 10 / 343f * rate), rate / 10, 0.2f, r, rate, 4);
        buffer(clap, false);
        run(2000);
        shot(a, "40_speed");

        lesson(a, 20);
        clickText(a, "▶ Нове завдання");
        run(300);
        clickText(a, "Перша вища");
        run(300);
        shot(a, "41_ear");

        lesson(a, 21);
        select(a, "spinSource", 1);
        Class<?> rl = Class.forName("com.example.audiospectrumpro.RhythmLesson");
        java.lang.reflect.Method m = rl.getDeclaredMethod("metronome", int.class, int.class);
        m.setAccessible(true);
        float[] bar = (float[]) m.invoke(null, 90, rate);
        int beat = Math.round(60f / 90 * rate);
        for (int b = 0; b < 4; b++) burst(bar, b * beat + Math.round(0.03f * rate), rate / 20, 0.7f, r, rate, 8);
        buffer(bar, true);
        run(6000);
        shot(a, "42_rhythm");

        lesson(a, 16);
        select(a, "spinSource", 1);
        click(a, "btnPresetVoice");
        gen("HARMONICS", 160, 0.5f);
        run(2500);
        gen("SINE", 160, 0.0f);
        run(500);
        clickText(a, "Гелій");
        run(2500);
        shot(a, "43_voicefx");
    }

    @Test @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void visualLessons() throws Exception {
        MainActivity a = setup();
        int rate = 48000;

        lesson(a, 23);
        select(a, "spinSource", 1);
        gen("SAWTOOTH", 300, 0.4f);
        run(1500);
        shot(a, "46_cochlea");

        lesson(a, 24);
        clickText(a, "🎹 Демо: пилка");
        run(2000);
        shot(a, "47_string");

        lesson(a, 25);
        select(a, "spinSource", 1);
        float[] pluck = new float[rate * 3];
        Class<?> kit = Class.forName("com.example.audiospectrumpro.LessonKit");
        java.lang.reflect.Method note = kit.getDeclaredMethod("addNote", float[].class, int.class, int.class, double.class, float.class, int.class);
        note.setAccessible(true);
        note.invoke(null, pluck, rate / 2, rate * 2, 220.0, 0.8f, rate);
        buffer(pluck, false);
        run(3000);
        shot(a, "48_envelope");

        lesson(a, 26);
        select(a, "spinSource", 1);
        gen("SINE", 900, 0.4f);
        run(1200);
        shot(a, "49_chladni_900");
        gen("SINE", 2600, 0.4f);
        run(1200);
        shot(a, "50_chladni_2600");

        lesson(a, 27);
        run(600);
        shot(a, "51_interference");
        clickText(a, "Показати гучність");
        run(400);
        shot(a, "52_interference_loudness");

        lesson(a, 28);
        clickText(a, "Телефон");
        clickText(a, "▶ Слухати");
        run(2500);
        shot(a, "53_equalizer");

        lesson(a, 29);
        select(a, "spinSource", 1);
        gen("PINK_NOISE", 440, 0.3f);
        run(2000);
        shot(a, "54_loudness");
    }

    /** A sung note: 196 Hz with harmonics and vibrato, 1.2 s on / 0.6 s off; one continuous stream per AudioRecord. */
    static final class Voice implements org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource {
        long n;
        double phase;
        @Override public int readInShortArray(short[] buf, int off, int len, boolean blocking) {
            int rate = 48000;
            for (int i = 0; i < len; i++, n++) {
                double t = (double) n / rate;
                double cyc = t % 1.8;
                double env = cyc < 1.2 ? Math.min(1, cyc / 0.03) * (1 - 0.3 * cyc) : 0;
                double f = 196 * (1 + 0.01 * Math.sin(2 * Math.PI * 5 * t));
                phase += 2 * Math.PI * f / rate;
                double v = 0;
                for (int h = 1; h <= 8; h++) v += Math.sin(h * phase) / h;
                buf[off + i] = (short) (env * v * 6000);
            }
            try { Thread.sleep(len * 1000L / rate); } catch (InterruptedException ignored) {}
            return len;
        }
    }

    static void fakeMicrophone() {
        // Robolectric asks the provider on every read, and activities of earlier tests keep their
        // engines running: give each AudioRecord its own continuous stream.
        final java.util.Map<android.media.AudioRecord, Voice> voices =
                java.util.Collections.synchronizedMap(new java.util.WeakHashMap<android.media.AudioRecord, Voice>());
        org.robolectric.shadows.ShadowAudioRecord.setSourceProvider(new org.robolectric.shadows.ShadowAudioRecord.AudioRecordSourceProvider() {
            @Override public org.robolectric.shadows.ShadowAudioRecord.AudioRecordSource get(android.media.AudioRecord r) {
                Voice v = voices.get(r);
                if (v == null) { v = new Voice(); voices.put(r, v); }
                return v;
            }
        });
    }

    @Test @Config(sdk = 34, qualifiers = "w411dp-h891dp-port-xxhdpi")
    public void liveModesFromMicrophone() throws Exception {
        fakeMicrophone();
        MainActivity a = setup();
        select(a, "spinSource", 0);
        // The reported scenario: visit a lesson that switches to the generator, come back, pick a live mode.
        lesson(a, 13);
        clickText(a, "← До уроків");
        click(a, "tabMain");
        Object eng = getField(a, "engine");
        System.out.println("MIC source after lesson: " + getField(eng, "sourceType") + " running=" + getField(eng, "running"));
        run(1500);
        Object lf = getField(a, "lastFrame");
        float[] tm = (float[]) getField(lf, "time");
        Class<?> lr = Class.forName("com.example.audiospectrumpro.LessonRenderer");
        java.lang.reflect.Method yin = lr.getDeclaredMethod("yin", float[].class, int.class);
        yin.setAccessible(true);
        double mx = 0; int zc = 0;
        for (int i = 1; i < tm.length; i++) { mx = Math.max(mx, Math.abs(tm[i])); if (tm[i - 1] < 0 && tm[i] >= 0) zc++; }
        System.out.println("MIC rate=" + getField(lf, "sampleRate") + " fft=" + getField(lf, "fftSize") + " peak=" + getField(lf, "peakFreqHz")
                + " yin=" + yin.invoke(null, tm, getField(lf, "sampleRate")) + " max=" + mx + " zeroCross=" + zc + " len=" + tm.length);
        for (int pos = 7; pos <= 16; pos++) {
            select(a, "spinMode", pos);
            run(2200);
            shot(a, String.format("60_mic_mode_%02d", pos));
        }
    }
}
