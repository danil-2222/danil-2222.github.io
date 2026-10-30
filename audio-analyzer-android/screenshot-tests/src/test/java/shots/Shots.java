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
}
