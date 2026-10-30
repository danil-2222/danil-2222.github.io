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
}
