package com.example.audiospectrumpro;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lab journal: experiments append their results here (date, experiment, quantity, value, unit). Stored as
 * CSV in the app's files, shown in "Журнал вимірів" and exported to Downloads or shared.
 */
final class Journal {
    static final String HEADER = "date,experiment,quantity,value,unit";
    private static Context context;

    private Journal() {}

    static void init(Context ctx) { context = ctx.getApplicationContext(); }

    static File file() { return context == null ? null : new File(context.getFilesDir(), "journal.csv"); }

    static String quote(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }

    /** Adds one result. Silently does nothing before init (e.g. in a plain unit test). */
    static synchronized void add(String experiment, String quantity, double value, String unit) {
        File f = file();
        if (f == null || Double.isNaN(value) || Double.isInfinite(value)) return;
        try {
            boolean fresh = !f.exists();
            Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
            if (fresh) w.write(HEADER + "\n");
            String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            w.write(date + "," + quote(experiment) + "," + quote(quantity) + "," + String.format(Locale.US, "%.4f", value) + "," + quote(unit) + "\n");
            w.close();
        } catch (IOException ignored) { }
    }

    static synchronized String csv() {
        File f = file();
        if (f == null || !f.exists()) return HEADER + "\n";
        StringBuilder b = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
            r.close();
        } catch (IOException ignored) { }
        return b.toString();
    }

    /** Rows without the header: {date, experiment, quantity, value, unit}. */
    static List<String[]> rows() {
        List<String[]> out = new ArrayList<>();
        String[] lines = csv().split("\n");
        for (int i = 1; i < lines.length; i++) {
            List<String> cells = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean q = false;
            String s = lines[i];
            for (int j = 0; j < s.length(); j++) {
                char ch = s.charAt(j);
                if (q) {
                    if (ch == '"' && j + 1 < s.length() && s.charAt(j + 1) == '"') { cur.append('"'); j++; }
                    else if (ch == '"') q = false;
                    else cur.append(ch);
                } else if (ch == '"') q = true;
                else if (ch == ',') { cells.add(cur.toString()); cur.setLength(0); }
                else cur.append(ch);
            }
            cells.add(cur.toString());
            if (cells.size() == 5) out.add(cells.toArray(new String[0]));
        }
        return out;
    }

    static synchronized void clear() {
        File f = file();
        if (f != null && f.exists()) f.delete();
    }
}
