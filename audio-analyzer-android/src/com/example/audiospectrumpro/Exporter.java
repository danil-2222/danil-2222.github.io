package com.example.audiospectrumpro;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Saves exports into the shared Music / Pictures / Download folders (MediaStore on Android 10+,
 * the app's external files directory on older versions so no storage permission is needed).
 */
final class Exporter {
    private static final String SUBDIR = "SpectrumPro";

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    static String saveCsv(Context ctx, AudioEngine.Frame f) throws IOException {
        String name = "spectrum_" + stamp() + ".csv";
        Target t = open(ctx, name, "text/csv", Uri.parse("content://media/external/downloads"),
                Environment.DIRECTORY_DOWNLOADS);
        Writer w = new OutputStreamWriter(t.out, "UTF-8");
        w.write("frequency_hz,level_dbfs,peak_hold_dbfs\n");
        for (int i = 0; i < f.spectrumDb.length; i++) {
            w.write(String.format(Locale.US, "%.2f,%.2f,%.2f\n", i * f.binHz, f.spectrumDb[i], f.peakHoldDb[i]));
        }
        w.close();
        t.finish(ctx);
        return t.location;
    }

    static String savePng(Context ctx, Bitmap bmp) throws IOException {
        String name = "spectrum_" + stamp() + ".png";
        Target t = open(ctx, name, "image/png", MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                Environment.DIRECTORY_PICTURES);
        bmp.compress(Bitmap.CompressFormat.PNG, 100, t.out);
        t.out.close();
        t.finish(ctx);
        return t.location;
    }

    /** Copies a finished WAV from app storage to the shared Music folder and deletes the temp file. */
    static String saveWav(Context ctx, File tmp) throws IOException {
        Target t = open(ctx, tmp.getName(), "audio/wav", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                Environment.DIRECTORY_MUSIC);
        InputStream in = new FileInputStream(tmp);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) t.out.write(buf, 0, n);
        in.close();
        t.out.close();
        t.finish(ctx);
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        return t.location;
    }

    private static final class Target {
        OutputStream out;
        Uri uri;
        String location;

        void finish(Context ctx) {
            if (uri == null) return;
            ContentValues v = new ContentValues();
            v.put("is_pending", 0);
            ctx.getContentResolver().update(uri, v, null, null);
        }
    }

    private static Target open(Context ctx, String name, String mime, Uri collection, String publicDir)
            throws IOException {
        Target t = new Target();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put("relative_path", publicDir + "/" + SUBDIR);
            v.put("is_pending", 1);
            t.uri = cr.insert(collection, v);
            if (t.uri == null) throw new IOException("MediaStore insert failed");
            t.out = cr.openOutputStream(t.uri);
            if (t.out == null) throw new IOException("Cannot open output");
            t.location = publicDir + "/" + SUBDIR + "/" + name;
        } else {
            File dir = new File(ctx.getExternalFilesDir(publicDir), SUBDIR);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            File f = new File(dir, name);
            t.out = new FileOutputStream(f);
            t.location = f.getAbsolutePath();
        }
        return t;
    }

    private Exporter() {}
}
