package com.example.audiospectrumpro;

import android.content.Context;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.view.MotionEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Scrolling 3D spectrum history drawn as coloured line strips; drag to rotate. */
public class Waterfall3DView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final int ROWS = 72;
    private static final int COLS = 160;

    private static final String VS =
            "uniform mat4 u_mvp;\n" +
            "attribute vec3 a_pos;\n" +
            "attribute vec3 a_col;\n" +
            "varying vec3 v_col;\n" +
            "void main() { gl_Position = u_mvp * vec4(a_pos, 1.0); v_col = a_col; }\n";
    private static final String FS =
            "precision mediump float;\n" +
            "varying vec3 v_col;\n" +
            "void main() { gl_FragColor = vec4(v_col, 1.0); }\n";

    private final float[][] history = new float[ROWS][COLS];
    private int head;
    private final float[] scratch = new float[COLS];
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(ROWS * COLS * 6 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] proj = new float[16], view = new float[16], model = new float[16],
            tmp = new float[16], mvp = new float[16];

    private volatile Palettes.Palette palette = Palettes.Palette.SPECTRUM;
    private volatile boolean logScale = true;
    private volatile float dbMin = -120f, dbMax = -10f;
    private float rotX = 0, rotY = -25;
    private float lastX, lastY;
    private int program, uMvp, aPos, aCol;

    public Waterfall3DView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public void setPalette(Palettes.Palette p) { palette = p; requestRender(); }

    public void setLogScale(boolean log) {
        logScale = log;
        synchronized (history) {
            for (float[] row : history) java.util.Arrays.fill(row, 0f);
        }
        requestRender();
    }

    public void setDbRange(float top, float bottom) { dbMax = top; dbMin = bottom; }

    public void addFrame(AudioEngine.Frame f) {
        float fMax = Math.min(20000f, f.sampleRate / 2f);
        SpectrumMath.columns(f.spectrumDb, f.binHz, logScale, logScale ? SpectrumMath.LOG_F_MIN : 0f, fMax, scratch);
        synchronized (history) {
            head = (head + 1) % ROWS;
            float[] row = history[head];
            for (int i = 0; i < COLS; i++) {
                row[i] = Math.max(0f, Math.min(1f, (scratch[i] - dbMin) / (dbMax - dbMin)));
            }
        }
        requestRender();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            rotX += (x - lastX) * 0.3f;
            rotY = Math.max(-80, Math.min(10, rotY + (y - lastY) * 0.3f));
            requestRender();
        }
        lastX = x;
        lastY = y;
        return true;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.031f, 0.047f, 0.078f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VS));
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FS));
        GLES20.glLinkProgram(program);
        uMvp = GLES20.glGetUniformLocation(program, "u_mvp");
        aPos = GLES20.glGetAttribLocation(program, "a_pos");
        aCol = GLES20.glGetAttribLocation(program, "a_col");
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        Matrix.perspectiveM(proj, 0, 40f, (float) width / Math.max(1, height), 0.1f, 20f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        int[] lut = Palettes.lut(palette);
        vertices.clear();
        synchronized (history) {
            // Oldest row at the back (z = -1), newest at the front (z = +1).
            for (int age = ROWS - 1; age >= 0; age--) {
                float[] row = history[(head - age + ROWS) % ROWS];
                float z = 1f - 2f * age / (ROWS - 1);
                float fade = 0.35f + 0.65f * (1f - (float) age / ROWS);
                for (int c = 0; c < COLS; c++) {
                    float v = row[c];
                    int col = lut[Math.min(255, (int) (v * 255))];
                    vertices.put(-1.4f + 2.8f * c / (COLS - 1)).put(v * 0.9f).put(z);
                    vertices.put(Color.red(col) / 255f * fade).put(Color.green(col) / 255f * fade)
                            .put(Color.blue(col) / 255f * fade);
                }
            }
        }
        vertices.position(0);

        Matrix.setLookAtM(view, 0, 0f, 1.6f, 3.6f, 0f, 0.1f, 0f, 0f, 1f, 0f);
        Matrix.setIdentityM(model, 0);
        Matrix.rotateM(model, 0, -rotY - 25, 1f, 0f, 0f);
        Matrix.rotateM(model, 0, rotX, 0f, 1f, 0f);
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);

        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        vertices.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, vertices);
        GLES20.glEnableVertexAttribArray(aPos);
        vertices.position(3);
        GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, 24, vertices);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glLineWidth(2f);
        for (int r = 0; r < ROWS; r++) GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, r * COLS, COLS);
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }
}
