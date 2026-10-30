package com.example.audiospectrumpro;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity implements AudioEngine.Listener {
    private static final int REQ_MIC = 200;
    private static final int MODE_3D = 4;
    private static final int[] FFT_SIZES = {1024, 2048, 4096, 8192, 16384};
    private static final VisualizerView.Mode[] MODES_2D = {
            VisualizerView.Mode.LINE, VisualizerView.Mode.BARS, VisualizerView.Mode.RTA,
            VisualizerView.Mode.WATERFALL, null, VisualizerView.Mode.OSCILLOSCOPE, VisualizerView.Mode.TUNER
    };

    private AudioEngine engine;
    private VisualizerView viz2d;
    private Waterfall3DView viz3d;
    private TextView txtPeak, txtNote, txtBpm, txtThd, txtSnr, txtRms;
    private TextView lblSmoothing, lblGain, lblGenFreq, lblGenVol;
    private Button btnStart, btnFreeze, btnRecord;
    private View genPanel;
    private SharedPreferences prefs;

    private int mode;
    private boolean resumeOnStart = true;
    private File recordingFile;
    private AudioEngine.Frame lastFrame;
    private final AtomicReference<AudioEngine.Frame> pending = new AtomicReference<>();
    private final Runnable deliver = new Runnable() {
        @Override public void run() {
            AudioEngine.Frame f = pending.getAndSet(null);
            if (f != null) showFrame(f);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(0xFF0F172A);
        getWindow().setNavigationBarColor(0xFF080C14);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        engine = new AudioEngine(this);

        viz2d = (VisualizerView) findViewById(R.id.visualizer2D);
        viz3d = (Waterfall3DView) findViewById(R.id.visualizer3D);
        txtPeak = (TextView) findViewById(R.id.txtPeakFreq);
        txtNote = (TextView) findViewById(R.id.txtNote);
        txtBpm = (TextView) findViewById(R.id.txtBpm);
        txtThd = (TextView) findViewById(R.id.txtThd);
        txtSnr = (TextView) findViewById(R.id.txtSnr);
        txtRms = (TextView) findViewById(R.id.txtRms);
        lblSmoothing = (TextView) findViewById(R.id.lblSmoothing);
        lblGain = (TextView) findViewById(R.id.lblGain);
        lblGenFreq = (TextView) findViewById(R.id.lblGenFreq);
        lblGenVol = (TextView) findViewById(R.id.lblGenVol);
        btnStart = (Button) findViewById(R.id.btnStart);
        btnFreeze = (Button) findViewById(R.id.btnFreeze);
        btnRecord = (Button) findViewById(R.id.btnRecord);
        genPanel = findViewById(R.id.genPanel);

        setupButtons();
        setupSpinners();
        setupSliders();
        setupChecks();
    }

    // ---------------------------------------------------------------- controls

    private void setupButtons() {
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (engine.isRunning()) {
                    resumeOnStart = false;
                    stopEngine();
                } else {
                    resumeOnStart = true;
                    startEngine();
                }
            }
        });
        btnFreeze.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                engine.setFrozen(!engine.isFrozen());
                btnFreeze.setText(engine.isFrozen() ? "Продовжити" : "Пауза");
            }
        });
        btnRecord.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (engine.isRecording()) finishRecording();
                else beginRecording();
            }
        });
        findViewById(R.id.btnCsv).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (lastFrame == null) { toast("Немає даних — запустіть аналіз"); return; }
                try {
                    toast("CSV збережено: " + Exporter.saveCsv(MainActivity.this, lastFrame));
                } catch (Exception e) {
                    toast("Помилка збереження CSV: " + e.getMessage());
                }
            }
        });
        findViewById(R.id.btnPng).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (mode == MODE_3D) { toast("Знімок доступний у 2D-режимах"); return; }
                try {
                    toast("PNG збережено: " + Exporter.savePng(MainActivity.this, viz2d.snapshot()));
                } catch (Exception e) {
                    toast("Помилка збереження PNG: " + e.getMessage());
                }
            }
        });
        findViewById(R.id.btnResetPeaks).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                engine.resetPeakHold();
                viz2d.resetPeaks();
            }
        });
    }

    private void setupSpinners() {
        spinner(R.id.spinMode, "mode", 0, new String[]{
                "Спектр (лінія)", "Спектр (стовпчики)", "1/3 октави (RTA)", "Спектрограма (водоспад)",
                "3D водоспад (OpenGL)", "Осцилограф", "Тюнер"
        }, new OnPick() {
            @Override public void pick(int i) {
                mode = i;
                if (i == MODE_3D) {
                    viz2d.setVisibility(View.GONE);
                    viz3d.setVisibility(View.VISIBLE);
                } else {
                    viz3d.setVisibility(View.GONE);
                    viz2d.setVisibility(View.VISIBLE);
                    viz2d.setMode(MODES_2D[i]);
                }
            }
        });
        spinner(R.id.spinPalette, "palette", 0, new String[]{
                "Спектр", "Неон", "Вогонь", "Кіберпанк", "Монохром"
        }, new OnPick() {
            @Override public void pick(int i) {
                Palettes.Palette p = Palettes.Palette.values()[i];
                viz2d.setPalette(p);
                viz3d.setPalette(p);
            }
        });
        String[] fftLabels = new String[FFT_SIZES.length];
        for (int i = 0; i < FFT_SIZES.length; i++) {
            fftLabels[i] = String.format(Locale.US, "%d  (Δf = %.1f Гц)", FFT_SIZES[i],
                    (float) AudioEngine.SAMPLE_RATE / FFT_SIZES[i]);
        }
        spinner(R.id.spinFft, "fft", 2, fftLabels, new OnPick() {
            @Override public void pick(int i) { engine.setFftSize(FFT_SIZES[i]); }
        });
        spinner(R.id.spinFilter, "filter", 0, new String[]{
                "Без фільтра", "Режекторний 50 Гц", "ФВЧ 100 Гц", "ФНЧ 5 кГц", "Смуговий 1 кГц", "ФВЧ 500 Гц"
        }, new OnPick() {
            @Override public void pick(int i) { engine.setFilter(BiquadFilter.Type.values()[i]); }
        });
        spinner(R.id.spinSource, "source", 0, new String[]{"Мікрофон", "Тестовий генератор"}, new OnPick() {
            @Override public void pick(int i) {
                engine.setSourceType(AudioEngine.SourceType.values()[i]);
                genPanel.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
            }
        });
        spinner(R.id.spinWave, "wave", 0, new String[]{
                "Синус", "Меандр", "Пилка", "Трикутник", "Білий шум", "Рожевий шум", "Лог. свіп 20 Гц–20 кГц"
        }, new OnPick() {
            @Override public void pick(int i) { engine.setGenWaveform(AudioEngine.GenWaveform.values()[i]); }
        });
    }

    private void setupSliders() {
        slider(R.id.seekSmoothing, "smoothing", 95, 60, new OnPick() {
            @Override public void pick(int p) {
                engine.setSmoothing(p / 100f);
                lblSmoothing.setText("Згладжування: " + p + " %");
            }
        });
        slider(R.id.seekGain, "gain", 60, 20, new OnPick() {
            @Override public void pick(int p) {
                int db = p - 20;
                engine.setInputGainDb(db);
                lblGain.setText(String.format(Locale.US, "Підсилення входу: %+d дБ", db));
            }
        });
        slider(R.id.seekGenFreq, "genFreq", 1000, 566, new OnPick() {
            @Override public void pick(int p) {
                float f = (float) (20 * Math.pow(1000, p / 1000.0));
                engine.setGenFreq(f);
                lblGenFreq.setText("Частота генератора: " + SpectrumMath.formatHz(f));
            }
        });
        slider(R.id.seekGenVol, "genVol", 100, 50, new OnPick() {
            @Override public void pick(int p) {
                engine.setGenVol(p / 100f);
                float db = p > 0 ? (float) (20 * Math.log10(p / 100.0)) : -120f;
                lblGenVol.setText(String.format(Locale.US, "Рівень генератора: %d %% (%.1f dBFS)", p, db));
            }
        });
    }

    private void setupChecks() {
        check(R.id.chkLog, "log", true, new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                viz2d.setLogScale(on);
                viz3d.setLogScale(on);
            }
        });
        check(R.id.chkPeak, "peak", true, new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { viz2d.setShowPeakHold(on); }
        });
    }

    private interface OnPick { void pick(int value); }

    private void spinner(int id, final String key, int def, String[] items, final OnPick onPick) {
        Spinner s = (Spinner) findViewById(id);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        int saved = prefs.getInt(key, def);
        s.setSelection(saved >= 0 && saved < items.length ? saved : def);
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                prefs.edit().putInt(key, pos).apply();
                onPick.pick(pos);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        // Apply immediately so engine/views match the restored selection before the first callback.
        onPick.pick(s.getSelectedItemPosition());
    }

    private void slider(int id, final String key, int max, int def, final OnPick onPick) {
        SeekBar s = (SeekBar) findViewById(id);
        s.setMax(max);
        s.setProgress(Math.min(max, prefs.getInt(key, def)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                prefs.edit().putInt(key, p).apply();
                onPick.pick(p);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        onPick.pick(s.getProgress());
    }

    private void check(int id, final String key, boolean def, final CompoundButton.OnCheckedChangeListener l) {
        CheckBox c = (CheckBox) findViewById(id);
        c.setChecked(prefs.getBoolean(key, def));
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean(key, on).apply();
                l.onCheckedChanged(b, on);
            }
        });
        l.onCheckedChanged(c, c.isChecked());
    }

    // ---------------------------------------------------------------- engine lifecycle

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startEngine() {
        if (!hasMic()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        engine.start(true);
        onEngineStateChanged();
    }

    private void stopEngine() {
        if (engine.isRecording()) finishRecording();
        engine.stop();
        onEngineStateChanged();
    }

    private void onEngineStateChanged() {
        boolean on = engine.isRunning();
        btnStart.setText(on ? "Стоп" : "Старт");
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            engine.start(true);
        } else {
            toast("Без дозволу на мікрофон працює лише тестовий генератор");
            engine.start(false);
        }
        onEngineStateChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        viz3d.onResume();
        if (resumeOnStart && !engine.isRunning()) startEngine();
    }

    @Override
    protected void onPause() {
        super.onPause();
        viz3d.onPause();
        if (engine.isRunning()) stopEngine();
    }

    private void beginRecording() {
        if (!engine.isRunning()) { toast("Спершу запустіть аналіз"); return; }
        try {
            recordingFile = new File(getFilesDir(), "rec_" + Exporter.stamp() + ".wav");
            engine.startRecording(recordingFile);
            btnRecord.setText("Стоп запису");
        } catch (Exception e) {
            toast("Помилка запису: " + e.getMessage());
        }
    }

    private void finishRecording() {
        int secs = engine.stopRecording();
        btnRecord.setText("Запис WAV");
        if (secs < 0 || recordingFile == null) return;
        try {
            toast("WAV (" + secs + " с) збережено: " + Exporter.saveWav(this, recordingFile));
        } catch (Exception e) {
            toast("Помилка збереження WAV: " + e.getMessage());
        }
        recordingFile = null;
    }

    // ---------------------------------------------------------------- frames

    @Override
    public void onFrame(AudioEngine.Frame frame) {
        if (pending.getAndSet(frame) == null) runOnUiThread(deliver);
    }

    @Override
    public void onError(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() { toast(message); }
        });
    }

    private void showFrame(AudioEngine.Frame f) {
        lastFrame = f;
        if (mode == MODE_3D) viz3d.addFrame(f); else viz2d.setFrame(f);

        boolean signal = f.peakDb > -90;
        txtPeak.setText(signal ? SpectrumMath.formatHz(f.peakFreqHz) : "— Hz");
        if (signal && f.peakFreqHz > 25) {
            float[] cents = new float[1];
            String note = SpectrumMath.noteName(f.peakFreqHz, cents);
            txtNote.setText(String.format(Locale.US, "%s %+.0f¢", note, cents[0]));
        } else {
            txtNote.setText("—");
        }
        txtBpm.setText(f.bpm > 0 ? f.bpm + " BPM" : "— BPM");
        txtThd.setText(signal ? String.format(Locale.US, "THD %.2f%%", f.thd) : "THD —");
        txtSnr.setText(signal && f.snr != 0 ? String.format(Locale.US, "SNR %.1f dB", f.snr) : "SNR —");
        txtRms.setText(String.format(Locale.US, "RMS %.1f dB", Math.max(-120f, f.rmsDb)));
        if (engine.isRecording()) btnRecord.setText("Стоп запису (" + engine.recordingSeconds() + " с)");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
