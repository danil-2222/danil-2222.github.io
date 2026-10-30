package com.example.audiospectrumpro;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity implements AudioEngine.Listener, VisualizerView.Callbacks {
    private static final int REQ_MIC = 200;
    private static final int MODE_3D = 4;
    private static final int[] FFT_SIZES = {1024, 2048, 4096, 8192, 16384, 32768};
    private static final int[] RATES = {48000, 44100};
    private static final int[] MIC_SOURCES = {
            AudioEngine.SOURCE_UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.CAMCORDER
    };
    private static final float[][] DB_RANGES = {{0, -120}, {0, -100}, {-20, -120}, {-30, -90}, {0, -60}, {10, -140}};
    private static final VisualizerView.Mode[] MODES_2D = {
            VisualizerView.Mode.LINE, VisualizerView.Mode.BARS, VisualizerView.Mode.RTA,
            VisualizerView.Mode.WATERFALL, null, VisualizerView.Mode.OSCILLOSCOPE, VisualizerView.Mode.TUNER
    };
    private static final int ACCENT = 0xFF00F0FF;
    private static final int MUTED = 0xFF7F93A8;

    private AudioEngine engine;
    private final GeneratorPlayer player = new GeneratorPlayer();
    private VisualizerView viz2d;
    private Waterfall3DView viz3d;
    private TextView txtPeak, txtNote, txtRms, txtThd, txtThdN, txtSnr;
    private TextView lblSmoothing, lblGain, lblCal, lblGenFreq, lblGenVol, lblA4;
    private Button btnStart, btnFreeze, btnRecord, btnReference;
    private LinearLayout root, displayColumn, controlsColumn;
    private View[] panels;
    private TextView[] tabs;
    private CheckBox chkPlay;
    private Spinner spinFft;
    private SharedPreferences prefs;

    private int mode;
    private int rangeIndex;
    private float calibration;
    private float a4 = 440f;
    private boolean fullscreen;
    private boolean resumeOnStart = true;
    private boolean ready;
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

        root = (LinearLayout) findViewById(R.id.root);
        displayColumn = (LinearLayout) findViewById(R.id.displayColumn);
        controlsColumn = (LinearLayout) findViewById(R.id.controlsColumn);
        viz2d = (VisualizerView) findViewById(R.id.visualizer2D);
        viz3d = (Waterfall3DView) findViewById(R.id.visualizer3D);
        viz2d.setCallbacks(this);
        txtPeak = text(R.id.txtPeakFreq);
        txtNote = text(R.id.txtNote);
        txtRms = text(R.id.txtRms);
        txtThd = text(R.id.txtThd);
        txtThdN = text(R.id.txtThdN);
        txtSnr = text(R.id.txtSnr);
        lblSmoothing = text(R.id.lblSmoothing);
        lblGain = text(R.id.lblGain);
        lblCal = text(R.id.lblCal);
        lblGenFreq = text(R.id.lblGenFreq);
        lblGenVol = text(R.id.lblGenVol);
        lblA4 = text(R.id.lblA4);
        btnStart = (Button) findViewById(R.id.btnStart);
        btnFreeze = (Button) findViewById(R.id.btnFreeze);
        btnRecord = (Button) findViewById(R.id.btnRecord);
        btnReference = (Button) findViewById(R.id.btnReference);
        chkPlay = (CheckBox) findViewById(R.id.chkPlay);

        setupTabs();
        setupButtons();
        setupSpinners();
        setupSliders();
        setupChecks();
        applyOrientation(getResources().getConfiguration());
        ready = true;
    }

    private TextView text(int id) { return (TextView) findViewById(id); }

    // ---------------------------------------------------------------- layout

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyOrientation(c);
    }

    private void applyOrientation(Configuration c) {
        boolean land = c.orientation == Configuration.ORIENTATION_LANDSCAPE;
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        displayColumn.setLayoutParams(land
                ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.7f)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.4f));
        controlsColumn.setLayoutParams(land
                ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override
    public void onToggleFullscreen() {
        fullscreen = !fullscreen;
        controlsColumn.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        int flags = fullscreen
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : 0;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (fullscreen && !prefs.getBoolean("fullscreenHint", false)) {
            toast("Довге натискання на графік — вийти з повного екрана");
            prefs.edit().putBoolean("fullscreenHint", true).apply();
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) onToggleFullscreen(); else super.onBackPressed();
    }

    private void setupTabs() {
        panels = new View[]{findViewById(R.id.panelMain), findViewById(R.id.panelDisplay),
                findViewById(R.id.panelAnalysis), findViewById(R.id.panelGen)};
        tabs = new TextView[]{text(R.id.tabMain), text(R.id.tabDisplay), text(R.id.tabAnalysis), text(R.id.tabGen)};
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTab(idx); }
            });
        }
        selectTab(prefs.getInt("tab", 0));
    }

    private void selectTab(int idx) {
        if (idx < 0 || idx >= panels.length) idx = 0;
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == idx ? ACCENT : MUTED);
            tabs[i].setBackgroundColor(i == idx ? 0xFF16233A : 0x00000000);
        }
        prefs.edit().putInt("tab", idx).apply();
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
                if (engine.isRecording()) finishRecording(); else beginRecording();
            }
        });
        btnReference.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = viz2d.toggleReference();
                btnReference.setText(on ? "Еталон ✓" : "Еталон");
                if (on) toast("Поточний спектр збережено як еталон (пунктир)");
            }
        });
        findViewById(R.id.btnFullscreen).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onToggleFullscreen(); }
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
        spinFft = (Spinner) findViewById(R.id.spinFft);
        refreshFftLabels(RATES[Math.max(0, Math.min(RATES.length - 1, prefs.getInt("rate", 0)))]);
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
        spinner(R.id.spinSource, "source", 0, new String[]{"Мікрофон", "Внутрішній генератор (без мікрофона)"}, new OnPick() {
            @Override public void pick(int i) { engine.setSourceType(AudioEngine.SourceType.values()[i]); }
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
        String[] ranges = new String[DB_RANGES.length];
        for (int i = 0; i < ranges.length; i++) {
            ranges[i] = String.format(Locale.US, "%+.0f … %.0f дБ", DB_RANGES[i][0], DB_RANGES[i][1]).replace("+0 ", "0 ");
        }
        spinner(R.id.spinRange, "range", 0, ranges, new OnPick() {
            @Override public void pick(int i) {
                rangeIndex = i;
                applyLevels();
            }
        });
        spinner(R.id.spinMic, "mic", 0, new String[]{
                "Необроблений, без АРП (рекомендовано)", "Розпізнавання голосу", "Стандартний мікрофон", "Мікрофон відеокамери"
        }, new OnPick() {
            @Override public void pick(int i) {
                engine.setMicSource(MIC_SOURCES[i]);
                restartIfRunning();
            }
        });
        spinner(R.id.spinRate, "rate", 0, new String[]{"48 000 Гц", "44 100 Гц"}, new OnPick() {
            @Override public void pick(int i) {
                engine.setSampleRate(RATES[i]);
                refreshFftLabels(RATES[i]);
                restartIfRunning();
            }
        });
        spinner(R.id.spinFft, "fft", 2, null, new OnPick() {
            @Override public void pick(int i) { engine.setFftSize(FFT_SIZES[i]); }
        });
        spinner(R.id.spinWindow, "window", 0, new String[]{
                "Ганна (універсальне)", "Хеммінга", "Блекмана–Гарріса (мінімум витоку)",
                "Flat-top (точна амплітуда)", "Прямокутне"
        }, new OnPick() {
            @Override public void pick(int i) { engine.setWindow(AudioEngine.WindowType.values()[i]); }
        });
        spinner(R.id.spinFilter, "filter", 0, new String[]{
                "Без фільтра", "Режекторний 50 Гц", "ФВЧ 100 Гц", "ФНЧ 5 кГц", "Смуговий 1 кГц", "ФВЧ 500 Гц"
        }, new OnPick() {
            @Override public void pick(int i) { engine.setFilter(BiquadFilter.Type.values()[i]); }
        });
        spinner(R.id.spinWave, "wave", 0, new String[]{
                "Синус", "Меандр", "Пилка", "Трикутник", "Білий шум", "Рожевий шум",
                "Лог. свіп 20 Гц – 20 кГц (8 с)", "Два тони: f + 7 кГц (IMD)"
        }, new OnPick() {
            @Override public void pick(int i) { SignalGenerator.waveform = SignalGenerator.Waveform.values()[i]; }
        });
    }

    private void refreshFftLabels(int rate) {
        String[] labels = new String[FFT_SIZES.length];
        for (int i = 0; i < FFT_SIZES.length; i++) {
            labels[i] = String.format(Locale.US, "%d   Δf %.2f Гц, %.0f мс", FFT_SIZES[i],
                    (float) rate / FFT_SIZES[i], 1000f * FFT_SIZES[i] / rate);
        }
        int sel = spinFft.getSelectedItemPosition();
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinFft.setAdapter(a);
        if (sel >= 0) spinFft.setSelection(sel);
    }

    private void setupSliders() {
        slider(R.id.seekSmoothing, "smoothing", 97, 60, new OnPick() {
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
        slider(R.id.seekCal, "cal", 140, 20, new OnPick() {
            @Override public void pick(int p) {
                calibration = p - 20;
                engine.setCalibrationDb(calibration);
                lblCal.setText(calibration == 0
                        ? "Калібрування: вимкнено (рівні в dBFS)"
                        : String.format(Locale.US, "Калібрування: %+.0f дБ (0 dBFS = %.0f дБ)", calibration, calibration));
                applyLevels();
            }
        });
        slider(R.id.seekA4, "a4", 30, 15, new OnPick() {
            @Override public void pick(int p) {
                a4 = 425 + p;
                viz2d.setA4(a4);
                lblA4.setText(String.format(Locale.US, "Тюнер: A4 = %.0f Гц", a4));
            }
        });
        slider(R.id.seekGenFreq, "genFreq", 1000, 566, new OnPick() {
            @Override public void pick(int p) {
                float f = (float) (20 * Math.pow(1000, p / 1000.0));
                // Snap to round values near common test frequencies.
                float[] snaps = {50, 100, 440, 1000, 10000};
                for (float s : snaps) if (Math.abs(f / s - 1) < 0.012f) f = s;
                SignalGenerator.frequency = f;
                lblGenFreq.setText("Частота: " + SpectrumMath.formatHz(f));
            }
        });
        slider(R.id.seekGenVol, "genVol", 100, 50, new OnPick() {
            @Override public void pick(int p) {
                SignalGenerator.level = p / 100f;
                float db = p > 0 ? (float) (20 * Math.log10(p / 100.0)) : -120f;
                lblGenVol.setText(String.format(Locale.US, "Рівень: %d %% (%.1f dBFS)", p, db));
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
        check(R.id.chkPeakLabels, "peakLabels", true, new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { viz2d.setShowPeakLabels(on); }
        });
        // Loudspeaker output is never restored automatically on launch.
        chkPlay.setChecked(false);
        chkPlay.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (on) player.start(); else player.stop();
            }
        });
    }

    private void applyLevels() {
        float[] r = DB_RANGES[rangeIndex];
        viz2d.setDbRange(r[0] + calibration, r[1] + calibration);
        viz3d.setDbRange(r[0] + calibration, r[1] + calibration);
        viz2d.setUnit(calibration == 0 ? "dBFS" : "dB");
    }

    private interface OnPick { void pick(int value); }

    /** Binds a spinner to a saved preference; items == null keeps the adapter already set. */
    private void spinner(int id, final String key, int def, String[] items, final OnPick onPick) {
        Spinner s = (Spinner) findViewById(id);
        if (items != null) {
            ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            s.setAdapter(a);
        }
        int count = s.getAdapter().getCount();
        int saved = prefs.getInt(key, def);
        s.setSelection(saved >= 0 && saved < count ? saved : def);
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            int last = -1;

            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == last) return;
                last = pos;
                prefs.edit().putInt(key, pos).apply();
                onPick.pick(pos);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        // Apply immediately so engine and views match the restored selection.
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

    private void restartIfRunning() {
        if (!ready || !engine.isRunning()) return;
        engine.stop();
        engine.start(hasMic());
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
            toast("Без дозволу на мікрофон працює лише внутрішній генератор");
            engine.start(false);
        }
        onEngineStateChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        viz3d.onResume();
        if (resumeOnStart && !engine.isRunning()) startEngine();
        if (chkPlay.isChecked()) player.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        viz3d.onPause();
        player.stop();
        if (engine.isRunning()) stopEngine();
    }

    private void beginRecording() {
        if (!engine.isRunning()) { toast("Спершу запустіть аналіз"); return; }
        try {
            recordingFile = new File(getFilesDir(), "rec_" + Exporter.stamp() + ".wav");
            engine.startRecording(recordingFile);
            btnRecord.setText("■ 0 с");
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

    void showFrame(AudioEngine.Frame f) {
        lastFrame = f;
        if (mode == MODE_3D) viz3d.addFrame(f); else viz2d.setFrame(f);

        boolean signal = f.peakDb - f.calibrationDb > -100;
        txtPeak.setText(signal ? SpectrumMath.formatHz(f.peakFreqHz) : "— Hz");
        if (signal && f.tonal && f.peakFreqHz > 25) {
            float[] cents = new float[1];
            String note = SpectrumMath.noteName(f.peakFreqHz, a4, cents);
            txtNote.setText(String.format(Locale.US, "%s %+.0f¢", note, cents[0]));
        } else {
            txtNote.setText("—");
        }
        String unit = f.calibrationDb == 0 ? "dBFS" : "dB";
        String rms = String.format(Locale.US, "%.1f %s", Math.max(-140f, f.rmsDb), unit);
        if (f.bpm > 0) rms += " ♩" + f.bpm;
        txtRms.setText(f.clipPercent > 0 ? "⚠ CLIP " + rms : rms);
        txtRms.setTextColor(f.clipPercent > 0 ? 0xFFFF3B30 : 0xFF9AA9BC);
        boolean tonal = signal && f.tonal;
        txtThd.setText(tonal ? String.format(Locale.US, f.thd < 1 ? "THD %.3f%%" : "THD %.1f%%", f.thd) : "THD —");
        txtThdN.setText(tonal ? String.format(Locale.US, f.thdN < 1 ? "THD+N %.3f%%" : "THD+N %.1f%%", f.thdN) : "THD+N —");
        txtSnr.setText(tonal ? String.format(Locale.US, "SNR %.1f dB", f.snr) : "SNR —");
        if (engine.isRecording()) btnRecord.setText("■ " + engine.recordingSeconds() + " с");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
