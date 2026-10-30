package com.example.audiospectrumpro;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

/**
 * Renders the spectrogram of a whole tape into a bitmap on a background thread. Time runs
 * downwards from the end of the tape (top) to its start (bottom), like the live waterfall.
 * Only the latest request is rendered; stale ones are dropped.
 */
final class TapeSpectrogram {
    interface Callback { void onRendered(Bitmap bitmap, Params params); }

    static final class Params {
        AudioEngine.Tape tape;
        int width, height;
        boolean log;
        float lo, hi;
        int fftSize;
        AudioEngine.WindowType window;
        float offsetDb;
        float dbTop, dbBottom;
        Palettes.Palette palette;

        boolean sameAs(Params o) {
            return o != null && tape == o.tape && width == o.width && height == o.height && log == o.log
                    && lo == o.lo && hi == o.hi && fftSize == o.fftSize && window == o.window
                    && offsetDb == o.offsetDb && dbTop == o.dbTop && dbBottom == o.dbBottom && palette == o.palette;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private Params pending;
    private Thread worker;

    void request(Params p, final Callback cb) {
        synchronized (lock) {
            pending = p;
            if (worker != null) return;
            worker = new Thread(new Runnable() {
                @Override public void run() { work(cb); }
            }, "TapeSpectrogram");
            worker.setPriority(Thread.MIN_PRIORITY + 1);
            worker.start();
        }
    }

    private void work(final Callback cb) {
        while (true) {
            final Params p;
            synchronized (lock) {
                p = pending;
                pending = null;
                if (p == null) { worker = null; return; }
            }
            final Bitmap b = render(p);
            boolean stale;
            synchronized (lock) { stale = pending != null; }
            if (!stale && b != null) {
                main.post(new Runnable() {
                    @Override public void run() { cb.onRendered(b, p); }
                });
            }
        }
    }

    static Bitmap render(Params p) {
        if (p.width <= 0 || p.height <= 0 || p.tape.pcm.length == 0) return null;
        short[] pcm = p.tape.pcm;
        int n = p.fftSize;
        FFT fft = new FFT(n);
        float[] window = AudioEngine.buildWindow(p.window, n);
        float wsum = 0;
        for (float v : window) wsum += v;
        float norm = 2f / wsum / 32768f;
        float[] re = new float[n], im = new float[n];
        float[] db = new float[n / 2], rowMax = new float[n / 2];
        float[] cols = new float[p.width];
        int[] lut = Palettes.lut(p.palette);
        int[] pixels = new int[p.width];
        float binHz = (float) p.tape.rate / n;
        float range = p.dbTop - p.dbBottom;
        Bitmap bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888);

        double samplesPerRow = (double) pcm.length / p.height;
        int subs = (int) Math.max(1, Math.min(4, Math.round(samplesPerRow / n)));
        for (int row = 0; row < p.height; row++) {
            // Row 0 is the end of the tape.
            double rowEnd = pcm.length - row * samplesPerRow;
            java.util.Arrays.fill(rowMax, AudioEngine.MIN_DB);
            for (int s = 0; s < subs; s++) {
                int center = (int) (rowEnd - (s + 0.5) * samplesPerRow / subs);
                int start = center - n / 2;
                for (int i = 0; i < n; i++) {
                    int idx = start + i;
                    re[i] = idx >= 0 && idx < pcm.length ? pcm[idx] * window[i] : 0f;
                    im[i] = 0f;
                }
                fft.forward(re, im);
                for (int i = 0; i < n / 2; i++) {
                    float m = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]) * norm;
                    float d = m > 1e-7f ? (float) (20 * Math.log10(m)) + p.offsetDb : AudioEngine.MIN_DB;
                    if (d > rowMax[i]) rowMax[i] = d;
                }
            }
            System.arraycopy(rowMax, 0, db, 0, db.length);
            SpectrumMath.columns(db, binHz, p.log, p.lo, p.hi, cols);
            for (int x = 0; x < p.width; x++) {
                float level = Math.max(0f, Math.min(1f, (cols[x] - p.dbBottom) / range));
                pixels[x] = lut[(int) (level * 255)];
            }
            bmp.setPixels(pixels, 0, p.width, 0, row, p.width, 1);
        }
        return bmp;
    }
}
