package com.example.audiospectrumpro;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity implements AudioEngine.Listener, VisualizerView.Callbacks,
        SonarEngine.Listener {
    private static final int REQ_MIC = 200;
    private static final int REQ_OPEN = 201;
    private static final float[] SPEEDS = {1f, 0.5f, 0.25f};
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
            VisualizerView.Mode.WATERFALL, null, VisualizerView.Mode.OSCILLOSCOPE, VisualizerView.Mode.TUNER,
            VisualizerView.Mode.FOURIER, VisualizerView.Mode.PITCH, VisualizerView.Mode.CHROMA,
            VisualizerView.Mode.INTERVALS, VisualizerView.Mode.AIR, VisualizerView.Mode.ECHO, VisualizerView.Mode.SAMPLING
    };
    private static final int WAVE_BUFFER = 10;
    /** Mode spinner position -> index into MODES_2D: analyzer modes, then live learning views. */
    private static final int[] SPINNER_MODES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, -1, -2, -3, -4, -5, -6, -7};
    private static final int MODE_PITCH = 8, MODE_CHROMA = 9, MODE_INTERVALS = 10, MODE_AIR = 11, MODE_ECHO = 12,
            MODE_SAMPLING = 13, WAVE_INTERVAL = 9;
    private static final String[] INTERVAL_NAMES = {"Унісон", "Мала секунда", "Велика секунда", "Мала терція",
            "Велика терція", "Кварта", "Тритон", "Квінта", "Мала секста", "Велика секста", "Мала септима",
            "Велика септима", "Октава"};
    private static final int[][] INTERVAL_RATIOS = {{1, 1}, {16, 15}, {9, 8}, {6, 5}, {5, 4}, {4, 3}, {45, 32},
            {3, 2}, {8, 5}, {5, 3}, {16, 9}, {15, 8}, {2, 1}};
    private static final int MODE_FOURIER = 7, WAVE_HARMONICS = 8;
    private static final float[][] PRESETS = {
            {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},                                          // sine
            {1, .5f, .33f, .25f, .2f, .17f, .14f, .125f, .11f, .1f, .09f, .083f},           // saw
            {1, 0, .33f, 0, .2f, 0, .14f, 0, .11f, 0, .09f, 0},                             // square
            {1, .02f, .75f, .02f, .5f, .02f, .14f, .02f, .1f, .02f, .05f, 0},              // clarinet
            {1, .8f, 0, .6f, 0, 0, 0, .4f, 0, 0, 0, .2f},                                  // organ (octave stops)
            {.5f, .8f, 1, .7f, .4f, .3f, .5f, .6f, .3f, .1f, .05f, .05f},                  // sung "a"
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
    private View timeline;
    private SeekBar seekTape;
    private TextView txtTapeName, txtTapeTime;
    private Button btnTapePlay, btnTapeSpeed;
    private AudioEngine.Tape currentTape;
    private int savedPlayhead;
    private boolean loadingFile;
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
        timeline = findViewById(R.id.timeline);
        seekTape = (SeekBar) findViewById(R.id.seekTape);
        txtTapeName = text(R.id.txtTapeName);
        txtTapeTime = text(R.id.txtTapeTime);
        btnTapePlay = (Button) findViewById(R.id.btnTapePlay);
        btnTapeSpeed = (Button) findViewById(R.id.btnTapeSpeed);

        setupTabs();
        setupButtons();
        setupSpinners();
        setupSliders();
        setupChecks();
        setupTimeline();
        setupLearning();
        setupSonar();
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
        if (fullscreen) onToggleFullscreen();
        else if (currentTape != null) closeTape();
        else super.onBackPressed();
    }

    private void setupTabs() {
        panels = new View[]{findViewById(R.id.panelMain), findViewById(R.id.panelDisplay),
                findViewById(R.id.panelAnalysis), findViewById(R.id.panelGen), findViewById(R.id.panelLearn),
                findViewById(R.id.panelSonar)};
        tabs = new TextView[]{text(R.id.tabMain), text(R.id.tabDisplay), text(R.id.tabAnalysis), text(R.id.tabGen),
                text(R.id.tabLearn), text(R.id.tabSonar)};
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
                if (engine.isRecording()) finishRecording();
                else if (currentTape != null) toast("У режимі машини часу запис недоступний — збережіть фрагмент кнопкою WAV");
                else beginRecording();
            }
        });
        btnReference.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = viz2d.toggleReference();
                btnReference.setText(on ? "Еталон ✓" : "Еталон");
                if (on) toast("Поточний спектр збережено як еталон (пунктир)");
            }
        });
        findViewById(R.id.btnTimeMachine).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { enterTimeMachine(); }
        });
        findViewById(R.id.btnOpenFile).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
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
                "3D водоспад (OpenGL)", "Осцилограф", "Тюнер",
                "🎓 Фур'є: кола", "🎓 Мелодія голосу", "🎓 Нотне коло (ноти й акорди)",
                "🎓 Звук у повітрі", "🎓 Луна кімнати (RT60)", "🎓 Равлик вуха", "🎓 Струна, що співає",
                "🎓 Огинаюча звуку (ADSR)", "🎓 Фігури Хладні", "🎓 Що чує вухо (рівна гучність)",
                "🎓 Карта голосних", "🎓 Голосовий діапазон"
        }, new OnPick() {
            @Override public void pick(int i) { applyMode(i); }
        });
        spinner(R.id.spinSource, "source", 0, new String[]{"Мікрофон", "Внутрішній генератор (без мікрофона)"}, new OnPick() {
            @Override public void pick(int i) { engine.setSourceType(AudioEngine.SourceType.values()[i]); }
        });
        setupOtherSpinners();
    }

    /** Shows analyzer mode i (index into the mode spinner). */
    private void applyMode(int i) {
                if (ready && i >= 7) {
                    Spinner src = (Spinner) findViewById(R.id.spinSource);
                    if (src.getSelectedItemPosition() != 0) {
                        src.setSelection(0);
                        toast("🎓 Цей режим слухає мікрофон — джерело перемкнено на мікрофон");
                    }
                }
                mode = i;
                if (ready && sonar.isRunning()) sonar.stop();
                if (ready) {
                    btnSonar.setText("▶ Увімкнути ехолот");
                    findViewById(R.id.hud).setVisibility(View.VISIBLE);
                    viz2d.clearSonar();
                    if (resumeOnStart && !engine.isRunning() && currentTape == null) startEngine();
                }
                if (i == MODE_3D) {
                    viz2d.setVisibility(View.GONE);
                    viz3d.setVisibility(View.VISIBLE);
                } else {
                    viz3d.setVisibility(View.GONE);
                    viz2d.setVisibility(View.VISIBLE);
                    if (SPINNER_MODES[i] < 0) viz2d.showLesson(liveView(SPINNER_MODES[i]));
                    else viz2d.setMode(MODES_2D[SPINNER_MODES[i]]);
                }
    }

    /** Selects analyzer mode i in the spinner and applies it even if it was already selected. */
    private void useMode(int i) {
        Spinner sp = (Spinner) findViewById(R.id.spinMode);
        if (sp.getSelectedItemPosition() != i) sp.setSelection(i);
        applyMode(i);
    }

    /** Shows a learning visualisation without touching the analyzer mode spinner. */
    private void showLessonMode(VisualizerView.Mode m) {
        if (sonar.isRunning()) {
            sonar.stop();
            btnSonar.setText("▶ Увімкнути ехолот");
            findViewById(R.id.hud).setVisibility(View.VISIBLE);
            viz2d.clearSonar();
        }
        mode = -1;
        viz3d.setVisibility(View.GONE);
        viz2d.setVisibility(View.VISIBLE);
        viz2d.setMode(m);
    }

    private void setupOtherSpinners() {
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
            @Override public void pick(int i) {
                engine.setFftSize(FFT_SIZES[i]);
                updateTapeAnalysis();
            }
        });
        spinner(R.id.spinWindow, "window", 0, new String[]{
                "Ганна (універсальне)", "Хеммінга", "Блекмана–Гарріса (мінімум витоку)",
                "Flat-top (точна амплітуда)", "Прямокутне"
        }, new OnPick() {
            @Override public void pick(int i) {
                engine.setWindow(AudioEngine.WindowType.values()[i]);
                updateTapeAnalysis();
            }
        });
        spinner(R.id.spinFilter, "filter", 0, new String[]{
                "Без фільтра", "Режекторний 50 Гц", "ФВЧ 100 Гц", "ФНЧ 5 кГц", "Смуговий 1 кГц", "ФВЧ 500 Гц"
        }, new OnPick() {
            @Override public void pick(int i) { engine.setFilter(BiquadFilter.Type.values()[i]); }
        });
        spinner(R.id.spinWave, "wave", 0, new String[]{
                "Синус", "Меандр", "Пилка", "Трикутник", "Білий шум", "Рожевий шум",
                "Лог. свіп 20 Гц – 20 кГц (8 с)", "Два тони: f + 7 кГц (IMD)", "Конструктор гармонік (вкладка «Навчання»)",
                "Інтервал з двох нот (вкладка «Навчання»)", "Звук уроку"
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
                updateTapeAnalysis();
                lblGain.setText(String.format(Locale.US, "Підсилення входу: %+d дБ", db));
            }
        });
        slider(R.id.seekCal, "cal", 140, 20, new OnPick() {
            @Override public void pick(int p) {
                calibration = p - 20;
                engine.setCalibrationDb(calibration);
                updateTapeAnalysis();
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
        final int initial = s.getSelectedItemPosition();
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            // The spinner also reports its initial selection after layout; that one is already applied below.
            int last = initial;

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
        if (sonar.isRunning()) return;
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
        if (sonarWasOn) {
            sonarWasOn = false;
            startSonar();
        } else if (currentTape != null) {
            ensureEngine();
            engine.openTape(currentTape, savedPlayhead);
        } else if (resumeOnStart && !engine.isRunning()) {
            startEngine();
        }
        if (chkPlay.isChecked()) player.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sonar.isRunning()) stopSonar(false);
        viz3d.onPause();
        player.stop();
        if (currentTape != null) savedPlayhead = engine.getPlayhead();
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
        if (f.tape != null && f.tape == currentTape) {
            txtTapeTime.setText(VisualizerView.formatTime((float) f.tapePosition / f.tape.rate)
                    + " / " + VisualizerView.formatTime(f.tape.seconds()));
            if (!seekDragging) seekTape.setProgress(f.tapePosition / AudioEngine.HOP);
            btnTapePlay.setText(f.tapePlaying ? "❚❚" : "▶");
        }
    }

    // ---------------------------------------------------------------- sonar

    private static final float[][] SONAR_BANDS = {{18000, 22000}, {14000, 18000}, {2000, 8000}};
    private static final String[] SONAR_BAND_NAMES = {"Ультразвук 18–22 кГц (майже нечутно)",
            "Високий 14–18 кГц (тихий писк)", "Чутний 2–8 кГц (найнадійніше)"};
    private static final float[] SONAR_RANGES = {1, 2, 3, 5, 10};
    private final SonarEngine sonar = new SonarEngine(this);
    private boolean sonarWasOn;
    private int sonarBand, sonarRange = 2;
    private Button btnSonar;
    private TextView txtSonarStatus;
    private long lastSonarStatus;

    private void setupSonar() {
        btnSonar = (Button) findViewById(R.id.btnSonar);
        txtSonarStatus = text(R.id.txtSonarStatus);
        btnSonar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (sonar.isRunning()) stopSonar(true); else startSonar();
            }
        });
        spinner(R.id.spinSonarBand, "sonarBand", 0, SONAR_BAND_NAMES, new OnPick() {
            @Override public void pick(int i) {
                sonarBand = i;
                restartSonar();
            }
        });
        String[] ranges = new String[SONAR_RANGES.length];
        for (int i = 0; i < ranges.length; i++) ranges[i] = String.format(Locale.US, "до %.0f м", SONAR_RANGES[i]);
        spinner(R.id.spinSonarRange, "sonarRange", 2, ranges, new OnPick() {
            @Override public void pick(int i) {
                sonarRange = i;
                restartSonar();
            }
        });
        check(R.id.chkSonarMotion, "sonarMotion", false, new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                sonar.setMotion(on);
                viz2d.clearSonar();
            }
        });
        final TextView lbl = text(R.id.lblSonarLevel);
        slider(R.id.seekSonarLevel, "sonarLevel", 100, 70, new OnPick() {
            @Override public void pick(int p) {
                sonar.setLevel(Math.max(0.05f, p / 100f));
                lbl.setText("Гучність імпульсів: " + p + " %");
            }
        });
    }

    private void restartSonar() {
        if (!ready || !sonar.isRunning()) return;
        sonar.stop();
        viz2d.clearSonar();
        startSonar();
    }

    private void startSonar() {
        if (!hasMic()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        // The sonar needs the microphone and the speaker for itself.
        if (currentTape != null) closeTape();
        if (engine.isRunning()) stopEngine();
        chkPlay.setChecked(false);
        player.stop();
        viz3d.setVisibility(View.GONE);
        viz2d.setVisibility(View.VISIBLE);
        viz2d.setMode(VisualizerView.Mode.SONAR);
        findViewById(R.id.hud).setVisibility(View.GONE);
        viz2d.setSonarBand(SONAR_BAND_NAMES[sonarBand].split(" \\(")[0]);
        float[] band = SONAR_BANDS[sonarBand];
        sonar.setMotion(((CheckBox) findViewById(R.id.chkSonarMotion)).isChecked());
        sonar.start(band[0], band[1], SONAR_RANGES[sonarRange], AudioEngine.SOURCE_UNPROCESSED);
        btnSonar.setText(sonar.isRunning() ? "■ Вимкнути ехолот" : "▶ Увімкнути ехолот");
        if (sonar.isRunning()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** @param byUser false when paused by the system: the sonar resumes with the app. */
    private void stopSonar(boolean byUser) {
        sonar.stop();
        sonarWasOn = !byUser;
        btnSonar.setText("▶ Увімкнути ехолот");
        txtSonarStatus.setText("");
        if (!byUser) return;
        findViewById(R.id.hud).setVisibility(View.VISIBLE);
        viz2d.clearSonar();
        Spinner m = (Spinner) findViewById(R.id.spinMode);
        int i = m.getSelectedItemPosition();
        if (i == MODE_3D) {
            viz2d.setVisibility(View.GONE);
            viz3d.setVisibility(View.VISIBLE);
        } else {
            if (SPINNER_MODES[i] < 0) viz2d.showLesson(liveView(SPINNER_MODES[i]));
            else viz2d.setMode(MODES_2D[SPINNER_MODES[i]]);
        }
        if (resumeOnStart) startEngine();
    }

    @Override
    public void onSonar(final SonarProcessor.Frame frame) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!sonar.isRunning()) return;
                viz2d.setSonarFrame(frame);
                long now = System.currentTimeMillis();
                if (now - lastSonarStatus > 400) {
                    lastSonarStatus = now;
                    txtSonarStatus.setText(String.format(Locale.US, "Якість сигналу: %.0f дБ%s", frame.directSnrDb,
                            frame.directSnrDb < 25 ? " — слабко, спробуйте інший діапазон" : " — добре"));
                }
            }
        });
    }

    @Override
    public void onSonarError(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                toast(message);
                btnSonar.setText(sonar.isRunning() ? "■ Вимкнути ехолот" : "▶ Увімкнути ехолот");
            }
        });
    }

    // ---------------------------------------------------------------- learning

    private SeekBar[] harmSliders;
    private TextView[] harmLabels;
    private TextView lblHarmF0, txtLesson;

    private void setupLearning() {
        txtLesson = text(R.id.txtLesson);
        check(R.id.chkHints, "hints", true, new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { viz2d.setShowHints(on); }
        });
        lblHarmF0 = text(R.id.lblHarmF0);
        slider(R.id.seekHarmF0, "harmF0", 1000, 500, new OnPick() {
            @Override public void pick(int p) {
                float f = (float) (55 * Math.pow(16, p / 1000.0)); // 55 Hz .. 880 Hz
                for (float snap : new float[]{110, 220, 440}) if (Math.abs(f / snap - 1) < 0.015f) f = snap;
                harmF0 = f;
                if (ready) {
                    useHarmonics();
                    SignalGenerator.frequency = f;
                }
                float[] cents = new float[1];
                lblHarmF0.setText(String.format(Locale.US, "Основна частота f0: %s (%s)",
                        SpectrumMath.formatHz(f), SpectrumMath.noteName(f, a4, cents)));
                refreshHarmLabels();
            }
        });
        harmSliders = new SeekBar[12];
        harmLabels = new TextView[12];
        for (int k = 1; k <= 12; k++) {
            final int idx = k - 1;
            harmLabels[idx] = text(getResources().getIdentifier("lblH" + k, "id", getPackageName()));
            harmSliders[idx] = (SeekBar) findViewById(getResources().getIdentifier("seekH" + k, "id", getPackageName()));
            slider(harmSliders[idx].getId(), "harm" + k, 100, k == 1 ? 100 : 0, new OnPick() {
                @Override public void pick(int p) {
                    SignalGenerator.harmonics[idx] = p / 100f;
                    if (ready) useHarmonics();
                    refreshHarmLabels();
                }
            });
        }
        int[] presetIds = {R.id.btnPresetSine, R.id.btnPresetSaw, R.id.btnPresetSquare, R.id.btnPresetClarinet,
                R.id.btnPresetOrgan, R.id.btnPresetVoice};
        for (int i = 0; i < presetIds.length; i++) {
            final float[] preset = PRESETS[i];
            findViewById(presetIds[i]).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { applyPreset(preset); }
            });
        }
        final Button listen = (Button) findViewById(R.id.btnListen);
        listen.setText(chkPlay.isChecked() ? "■ Тиша" : "▶ Слухати");
        listen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                useHarmonics();
                chkPlay.setChecked(!chkPlay.isChecked());
                listen.setText(chkPlay.isChecked() ? "■ Тиша" : "▶ Слухати");
            }
        });

        setupLabControls();
        setupLearnHub();
    }

    private void setupLabControls() {
        spinner(R.id.spinInterval, "interval", 7, INTERVAL_NAMES, new OnPick() {
            @Override public void pick(int i) {
                LessonRenderer.intervalName = INTERVAL_NAMES[i];
                LessonRenderer.intervalP = INTERVAL_RATIOS[i][0];
                LessonRenderer.intervalQ = INTERVAL_RATIOS[i][1];
                SignalGenerator.intervalRatio = (float) INTERVAL_RATIOS[i][0] / INTERVAL_RATIOS[i][1];
            }
        });
        final TextView lblDetune = text(R.id.lblDetune);
        slider(R.id.seekDetune, "detune", 100, 0, new OnPick() {
            @Override public void pick(int p) {
                SignalGenerator.intervalDetuneHz = p / 10f;
                lblDetune.setText(p == 0 ? "Розстроювання: немає (чисті відношення)"
                        : String.format(Locale.US, "Розстроювання: +%.1f Гц → биття %.1f раз/с", p / 10f, p / 10f));
            }
        });
        final TextView lblSig = text(R.id.lblSampleSig), lblRate = text(R.id.lblSampleRate);
        slider(R.id.seekSampleSig, "sampleSig", 1000, 500, new OnPick() {
            @Override public void pick(int p) {
                float f = (float) (100 * Math.pow(160, p / 1000.0)); // 100 Hz .. 16 kHz
                LessonRenderer.sampleSignalHz = Math.round(f / 10) * 10f;
                lblSig.setText("Частота сигналу: " + SpectrumMath.formatHz(LessonRenderer.sampleSignalHz));
                viz2d.invalidate();
            }
        });
        slider(R.id.seekSampleRate, "sampleRate", 30, 14, new OnPick() {
            @Override public void pick(int p) {
                LessonRenderer.sampleRateHz = 1000 + 500 * p; // 1 .. 16 kHz
                lblRate.setText(String.format(Locale.US, "Частота дискретизації: %d Гц (Найквіст %d Гц)",
                        (int) LessonRenderer.sampleRateHz, (int) LessonRenderer.sampleRateHz / 2));
                viz2d.invalidate();
            }
        });
    }

    private void refreshHarmLabels() {
        if (harmLabels == null) return;
        float f0 = harmF0;
        for (int k = 1; k <= 12; k++) {
            if (harmLabels[k - 1] == null) continue;
            int pct = Math.round(SignalGenerator.harmonics[k - 1] * 100);
            String name = k == 1 ? "1× основний тон" : k + "×";
            harmLabels[k - 1].setText(String.format(Locale.US, "%s · %s · %d%%", name, SpectrumMath.formatHz(k * f0), pct));
            harmLabels[k - 1].setTextColor(pct > 0 ? 0xFFCBD5E1 : MUTED);
        }
    }

    /** Switches the generator to the harmonic constructor (keeps the analysis source as is). */
    private float harmF0 = 220f;

    private void useHarmonics() {
        SignalGenerator.waveform = SignalGenerator.Waveform.HARMONICS;
        SignalGenerator.frequency = harmF0;
        Spinner w = (Spinner) findViewById(R.id.spinWave);
        if (w.getSelectedItemPosition() != WAVE_HARMONICS) w.setSelection(WAVE_HARMONICS);
    }

    private void applyPreset(float[] preset) {
        for (int k = 0; k < 12; k++) harmSliders[k].setProgress(Math.round(preset[k] * 100));
        useHarmonics();
    }

    private void select(int spinnerId, int pos) {
        ((Spinner) findViewById(spinnerId)).setSelection(pos);
    }

    // ---------------------------------------------------------------- Sound Academy navigation

    private static final String[] CATEGORIES = {"Просто про звук", "Основи звуку", "Музика і голос", "Фізика й техніка",
            "Слух і сприйняття", "Передача й творчість", "Ігри", "Експерименти", "Ігри разом"};
    private static final String[] CATEGORY_DESC = {
            "Без графіків і цифр: ниточки, звірі, обличчя, пейзаж, колір",
            "Тон, гармоніки, струна, ряд Фур'є, шум, звук у повітрі",
            "Мелодія голосу, акорди, інтервали, огинаюча, еквалайзер, ефекти, пляшка",
            "Луна, дискретизація, стоячі хвилі, швидкість звуку, Хладні, інтерференція",
            "Равлик вуха, що чує вухо, межа слуху",
            "Малюнок у звук, звуковий модем, азбука Морзе",
            "Папуга, тренажер слуху і тренажер ритму",
            "Справжні виміри: температура повітря двома телефонами",
            "Для компанії: від 2 до 8 гравців, з одним або кількома телефонами"};
    /** {category, lesson id, title, one-line description} */
    private static final Object[][] LESSONS = {
            {0, 32, "Завіса з ниточок", "Свисніть — розгойдається «ваша» ниточка"},
            {0, 33, "На кого схожий звук", "Слон, кіт чи комар? Шепіт чи літак?"},
            {0, 34, "Обличчя звуку", "Обличчя, що реагує на звук"},
            {0, 35, "Пейзаж звуку", "Гори, дерева й зорі з вашого голосу"},
            {0, 36, "Колір звуку", "Якого кольору ваш голос?"},
            {0, 37, "Камінь у воді", "Звук розходиться кругами"},
            {0, 38, "М'ячик на сходах", "Співайте «до-ре-мі» — м'ячик стрибає"},
            {0, 39, "Повітряна кулька", "Що гучніше — то більша кулька"},
            {1, 1, "Чистий тон", "Свисніть — один пік на спектрі"},
            {1, 2, "Гармоніки", "Заспівайте «а-а-а» — бачите f0, 2×f0, 3×f0"},
            {1, 24, "Струна, що співає", "Співайте — струна коливається з вашими гармоніками"},
            {1, 3, "Звук із кіл", "Ряд Фур'є і конструктор гармонік"},
            {1, 4, "Меандр", "Чому в ньому лише непарні гармоніки"},
            {1, 5, "Шум проти тону", "Суцільна заливка замість ліній"},
            {1, 10, "Звук у повітрі", "Молекули, тиск і довжина хвилі"},
            {2, 7, "Мелодія голосу", "Висота голосу в часі на нотних лініях"},
            {2, 8, "Нотне коло", "Які ноти звучать і який це акорд"},
            {2, 30, "Карта голосних", "Форманти: чому «а» і «і» звучать по-різному"},
            {2, 31, "Голосовий діапазон", "Від найнижчої до найвищої ноти — і тип голосу"},
            {2, 9, "Інтервали", "Чому одні поєднання звучать гарно"},
            {2, 25, "Огинаюча звуку (ADSR)", "Чому піаніно і скрипка на одній ноті звучать по-різному"},
            {2, 28, "Еквалайзер своїми руками", "Бас, середина, високі — і що вони роблять зі звуком"},
            {2, 16, "Ефекти голосу", "Гелій, робот, телефон, луна — і що вони роблять зі спектром"},
            {2, 15, "Музична пляшка", "Резонанс: нота з пляшки і скільки налити води"},
            {3, 11, "Луна кімнати", "Ляск у долоні і час згасання RT60"},
            {3, 12, "Дискретизація", "Як телефон «бачить» звук; аліасинг"},
            {3, 17, "Стоячі хвилі", "Гучні й тихі місця кімнати на низьких нотах"},
            {3, 18, "Швидкість звуку", "Ляск перед стіною → 343 м/с"},
            {4, 23, "Равлик вуха", "Як вухо саме розкладає звук на частоти"},
            {4, 29, "Що чує вухо, а що — прилад", "Криві рівної гучності: чому бас «зникає»"},
            {4, 6, "Межа слуху", "Свіп 20 Гц – 20 кГц: де ви перестаєте чути"},
            {3, 26, "Фігури Хладні", "Пісок на пластині малює звук"},
            {3, 27, "Інтерференція", "Два динаміки: де звук підсилюється, а де гасне"},
            {3, 22, "Ехолот", "Відстань звуком, навіть ультразвуком"},
            {5, 13, "Малюнок у звук", "Намалюйте — і побачте малюнок на спектрограмі"},
            {5, 14, "Звуковий модем", "Передайте текст звуком між телефонами"},
            {5, 19, "Азбука Морзе", "Пищить і розшифровує крапки й тире"},
            {6, 20, "Тренажер слуху", "Яка нота вища, інтервали, повтори голосом"},
            {6, 49, "Ритм-дуель (1–2 телефони)", "Закривайте мікрофон пальцем у такт музики — хто точніший?"},
            {7, 50, "Термометр зі звуку (2 телефони)", "Швидкість звуку залежить від температури — виміряємо її!"},
            {8, 51, "Вітрило (2–8 гравців, 1 телефон)", "Хто сильніше, хто довше, хто рівніше дмухає"},
            {8, 52, "Музична пауза (2–6 телефонів)", "Музика замовкла — хто швидше закриє мікрофон?"},
            {8, 53, "Перетягування каната (2 команди)", "«У-у-у» проти «ш-ш-ш» — чия команда сильніша?"},
            {6, 40, "Папуга Кеша", "Скажіть щось — папуга повторить смішним голосом"},
            {6, 41, "Задуй свічки", "Дмухайте в мікрофон — гасіть свічки на торті"},
            {6, 42, "Розбий склянку голосом", "Знайдіть ноту склянки й утримайте її"},
            {6, 43, "Не розбуди дракона", "Крадіть монети в повній тиші"},
            {6, 44, "Повтори мелодію", "Чотири ноти, щораз довша послідовність"},
            {6, 45, "Голосовий літачок", "Висота голосу керує польотом"},
            {6, 46, "Відлуння ритму", "Почуйте оплески — повторіть"},
            {6, 47, "Хто гучніший", "Змагання на крик для компанії"},
            {6, 48, "Тепло — холодно", "Знайдіть схований телефон за писком"},
            {6, 21, "Тренажер ритму", "Плескайте під метроном — мілісекунди точності"},
    };

    /** Lessons that only show synthetic sound (the microphone is not used). */
    private static final java.util.Set<Integer> NO_MIC = new java.util.HashSet<>(java.util.Arrays.asList(3, 4, 6, 9, 12, 27, 28));
    /** Lessons that work with the microphone and also have a built-in demo sound. */
    private static final java.util.Set<Integer> MIC_AND_DEMO = new java.util.HashSet<>(java.util.Arrays.asList(5, 13, 14, 19, 20, 24, 26));

    private static String micBadge(int id) {
        return NO_MIC.contains(id) ? "🎹" : MIC_AND_DEMO.contains(id) ? "🎙🎹" : "🎙";
    }

    private static String micNote(int id) {
        if (NO_MIC.contains(id)) return "🎹 Мікрофон у цьому уроці не використовується — він показує штучний сигнал.";
        if (MIC_AND_DEMO.contains(id)) return "🎙🎹 Працює з мікрофоном, а також має вбудований демо-звук.";
        return "🎙 Урок слухає мікрофон — говоріть, співайте, свистіть.";
    }

    private LinearLayout dyn;
    private int learnCategory;
    private int sourceBeforeLesson = -1;
    private int activeLesson;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private void setupLearnHub() {
        dyn = (LinearLayout) findViewById(R.id.lessonDynamic);
        LinearLayout cats = (LinearLayout) findViewById(R.id.learnCategories);
        for (int c = 0; c < CATEGORIES.length; c++) {
            final int cat = c;
            Button b = card(CATEGORIES[c], CATEGORY_DESC[c]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showCategory(cat); }
            });
            cats.addView(b);
        }
        findViewById(R.id.btnLearnHome).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLearnPage(0); }
        });
        findViewById(R.id.btnLearnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                leaveLesson();
                showCategory(learnCategory);
            }
        });
    }

    /** A themed button restyled like the XML ones (a defStyleRes alone would drop the background). */
    private Button styledButton(int tint) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(0xFFE2E8F0);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tint));
        return b;
    }

    private Button card(String title, String desc) {
        Button b = styledButton(0xFF16233A);
        b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        b.setTextSize(14);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.text.SpannableString t = new android.text.SpannableString(title + "\n" + desc);
        t.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length(), 0);
        t.setSpan(new android.text.style.RelativeSizeSpan(0.82f), title.length() + 1, t.length(), 0);
        t.setSpan(new android.text.style.ForegroundColorSpan(MUTED), title.length() + 1, t.length(), 0);
        b.setText(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** 0 = sections, 1 = lessons of a section, 2 = one lesson. */
    private void showLearnPage(int page) {
        findViewById(R.id.learnHome).setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        findViewById(R.id.learnList).setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.learnLesson).setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.controlsScroll).scrollTo(0, 0);
    }

    private void showCategory(int c) {
        learnCategory = c;
        text(R.id.txtCategoryTitle).setText(CATEGORIES[c]);
        LinearLayout list = (LinearLayout) findViewById(R.id.lessonList);
        list.removeAllViews();
        for (final Object[] l : LESSONS) {
            if ((Integer) l[0] != c) continue;
            Button b = card(micBadge((Integer) l[1]) + "  " + l[2], (String) l[3]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openLesson((Integer) l[1], (String) l[2]); }
            });
            list.addView(b);
        }
        showLearnPage(1);
    }

    void openLesson(int id, String title) {
        if (id == 22) { // the sonar has its own tab
            selectTab(5);
            return;
        }
        activeLesson = id;
        text(R.id.txtLessonTitle).setText(title);
        if (sourceBeforeLesson < 0) sourceBeforeLesson = ((Spinner) findViewById(R.id.spinSource)).getSelectedItemPosition();
        showLearnPage(2);
        runExperiment(id);
    }

    /** Stops lesson sounds and returns the display to the analyzer mode chosen in the spinner. */
    private void leaveLesson() {
        if (activeLesson == 0) return;
        activeLesson = 0;
        SignalGenerator.eqOn = false;
        if (sourceBeforeLesson >= 0) {
            select(R.id.spinSource, sourceBeforeLesson);
            sourceBeforeLesson = -1;
        }
        chkPlay.setChecked(false);
        ((Button) findViewById(R.id.btnListen)).setText("▶ Слухати");
        if (currentTape != null) closeTape();
        dyn.removeAllViews();
        useMode(((Spinner) findViewById(R.id.spinMode)).getSelectedItemPosition());
    }

    // ---------------------------------------------------------------- lesson sound & dynamic controls

    private int bufferStopToken;

    /** Plays a prepared sound through the speaker (and the internal source, if selected). */
    private void playBuffer(float[] pcm, boolean loop) {
        SignalGenerator.buffer = pcm;
        SignalGenerator.bufferLoop = loop;
        SignalGenerator.bufferVersion++;
        SignalGenerator.waveform = SignalGenerator.Waveform.BUFFER;
        Spinner w = (Spinner) findViewById(R.id.spinWave);
        if (w.getSelectedItemPosition() != WAVE_BUFFER) w.setSelection(WAVE_BUFFER);
        if (!chkPlay.isChecked()) chkPlay.setChecked(true);
        final int token = ++bufferStopToken;
        if (!loop) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { if (token == bufferStopToken) chkPlay.setChecked(false); }
            }, pcm.length * 1000L / 48000 + 600);
        }
    }

    private void stopBuffer() {
        bufferStopToken++;
        chkPlay.setChecked(false);
    }

    private LinearLayout.LayoutParams full() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return lp;
    }

    private Button dynButton(LinearLayout parent, String label, View.OnClickListener l) {
        Button b = styledButton(0xFF1E293B);
        b.setText(label);
        b.setOnClickListener(l);
        if (parent == dyn) {
            b.setLayoutParams(full());
        } else {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(dp(4));
            b.setLayoutParams(lp);
        }
        parent.addView(b);
        return b;
    }

    private LinearLayout dynRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(full());
        dyn.addView(row);
        return row;
    }

    private TextView dynLabel(String s) {
        TextView t = new TextView(this, null, 0, R.style.Label);
        t.setText(s);
        t.setLayoutParams(full());
        dyn.addView(t);
        return t;
    }

    private interface Value { float of(int p); }

    /** Slider whose label is String.format(fmt, value.of(progress)), kept up to date. */
    private SeekBar dynSlider(final String fmt, final String key, int max, int def, final Value value, final OnPick onPick) {
        final TextView t = dynLabel("");
        SeekBar sb = new SeekBar(this);
        sb.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));
        sb.setMax(max);
        sb.setProgress(Math.min(max, prefs.getInt(key, def)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                prefs.edit().putInt(key, p).apply();
                t.setText(String.format(Locale.US, fmt, value.of(p)));
                onPick.pick(p);
                viz2d.invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        dyn.addView(sb);
        t.setText(String.format(Locale.US, fmt, value.of(sb.getProgress())));
        onPick.pick(sb.getProgress());
        return sb;
    }

    private Spinner dynSpinner(String label, String[] items, int def, final OnPick onPick) {
        dynLabel(label);
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setBackgroundColor(0xFF1E293B);
        sp.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        sp.setSelection(def);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) { onPick.pick(pos); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        dyn.addView(sp);
        return sp;
    }

    private EditText dynEdit(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextColor(0xFFE2E8F0);
        e.setHintTextColor(MUTED);
        e.setLayoutParams(full());
        dyn.addView(e);
        return e;
    }

    private void ensureLive(int source) {
        if (currentTape != null) closeTape();
        select(R.id.spinSource, source);
        if (!engine.isRunning()) {
            resumeOnStart = true;
            startEngine();
        }
    }

    // ---------------------------------------------------------------- the new lessons

    private DrawLesson drawLesson;
    private ModemLesson modemLesson;
    private MorseLesson morseLesson;
    private BottleLesson bottleLesson;
    private RoomLesson roomLesson;
    private SpeedLesson speedLesson;
    private EarLesson earLesson;
    private RhythmLesson rhythmLesson;
    private float density() { return getResources().getDisplayMetrics().density; }

    private String lessonDraw() {
        if (drawLesson == null) {
            drawLesson = new DrawLesson(density());
            drawLesson.writeText("ПРИВІТ");
        }
        ensureLive(1);
        chkPlay.setChecked(false);
        viz2d.showLesson(drawLesson);
        LinearLayout row = dynRow();
        dynButton(row, "Очистити", new View.OnClickListener() {
            @Override public void onClick(View v) { drawLesson.clear(); viz2d.showLesson(drawLesson); }
        });
        final Button eraser = dynButton(row, "Гумка", null);
        eraser.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                drawLesson.eraser = !drawLesson.eraser;
                eraser.setText(drawLesson.eraser ? "Пензлик" : "Гумка");
                viz2d.showLesson(drawLesson);
            }
        });
        dynButton(row, "Смайлик", new View.OnClickListener() {
            @Override public void onClick(View v) { drawLesson.smiley(); viz2d.showLesson(drawLesson); }
        });
        final EditText name = dynEdit("Ваше ім'я або слово", prefs.getString("drawText", "ПРИВІТ"));
        dynButton(dyn, "Написати текст на полотні", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String t = name.getText().toString().toUpperCase(Locale.ROOT);
                prefs.edit().putString("drawText", t).apply();
                drawLesson.writeText(t);
                viz2d.showLesson(drawLesson);
            }
        });
        Button play = dynButton(dyn, "▶ Програти і показати на спектрограмі", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (drawLesson.isEmpty()) { toast("Спершу щось намалюйте"); return; }
                float[] pcm = drawLesson.synthesize(48000);
                useMode(3);
                playBuffer(pcm, false);
            }
        });
        play.setTextColor(0xFF00F0FF);
        dynButton(dyn, "✎ Повернутися до малювання", new View.OnClickListener() {
            @Override public void onClick(View v) { stopBuffer(); viz2d.showLesson(drawLesson); }
        });
        return "Спектрограма показує, які частоти звучать у кожну мить. А що як піти навпаки — намалювати "
                + "спектрограму і перетворити її на звук?\n\n"
                + "Кожна клітинка полотна — короткий тон: стовпчик задає частоту, рядок — момент часу. Натисніть "
                + "«Програти» — застосунок складе тони і покаже їх на спектрограмі: малюнок з'явиться знову.\n\n"
                + "Найцікавіше: оберіть на вкладці «Керування» джерело «Мікрофон» і програйте ще раз — малюнок "
                + "пролетить через повітря з динаміка в мікрофон. Музиканти ховали так картинки у свої треки.";
    }

    private String lessonModem() {
        if (modemLesson == null) modemLesson = new ModemLesson(density());
        ensureLive(0);
        stopBuffer();
        modemLesson.reset();
        viz2d.showLesson(modemLesson);
        final EditText msg = dynEdit("Повідомлення (до 48 байт)", prefs.getString("modemText", "Привіт! 👋"));
        Button send = dynButton(dyn, "📤 Надіслати звуком", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String t = msg.getText().toString();
                if (t.isEmpty()) return;
                prefs.edit().putString("modemText", t).apply();
                modemLesson.sent = t;
                modemLesson.reset();
                playBuffer(ModemLesson.encode(t, 48000), false);
            }
        });
        send.setTextColor(0xFF00F0FF);
        return "Так працювали модеми, факси і так досі «розмовляють» Wi-Fi та мобільний зв'язок — тільки "
                + "радіохвилями: інформацію кодують частотами.\n\n"
                + "Кожна буква перетворюється на байти, байт — на дві «половинки» по 4 біти, а кожна половинка — "
                + "на один із 17 тонів. На смужках угорі видно, як повідомлення «звучить».\n\n"
                + "Можна навіть одним телефоном: він сам себе почує. А ще краще — на двох: відкрийте цей урок на "
                + "другому телефоні і надішліть повідомлення з першого. Контрольна сума перевірить, чи все дійшло без помилок.";
    }

    private String lessonMorse() {
        if (morseLesson == null) morseLesson = new MorseLesson(density());
        ensureLive(0);
        stopBuffer();
        viz2d.showLesson(morseLesson);
        dynSpinner("Абетка для розшифрування", new String[]{"Кирилиця", "Латиниця"}, morseLesson.cyrillic ? 0 : 1,
                new OnPick() {
                    @Override public void pick(int i) { morseLesson.cyrillic = i == 0; viz2d.invalidate(); }
                });
        final EditText msg = dynEdit("Текст", prefs.getString("morseText", "SOS"));
        LinearLayout row = dynRow();
        dynButton(row, "📤 Надіслати Морзе", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String t = msg.getText().toString();
                prefs.edit().putString("morseText", t).apply();
                morseLesson.sentMorse = MorseLesson.toMorse(t);
                playBuffer(MorseLesson.encode(t, 48000), false);
            }
        });
        dynButton(row, "Очистити", new View.OnClickListener() {
            @Override public void onClick(View v) { morseLesson.clear(); viz2d.invalidate(); }
        });
        return "Азбука Морзе — найпростіший цифровий код: кожна буква — це короткі (крапка) і довгі (тире) сигнали. "
                + "Тире втричі довше за крапку, пауза між буквами — теж три крапки.\n\n"
                + "Надішліть текст — почуєте писк і побачите смужки. Або передавайте самі: свистіть коротко й "
                + "довго (SOS — ... --- ...). Застосунок сам підлаштовується під вашу швидкість.";
    }

    private String lessonBottle() {
        if (bottleLesson == null) bottleLesson = new BottleLesson(density());
        ensureLive(0);
        stopBuffer();
        viz2d.showLesson(bottleLesson);
        final SeekBar[] sliders = new SeekBar[3];
        dynSpinner("Тип пляшки", BottleLesson.PRESETS, prefs.getInt("bottlePreset", 0), new OnPick() {
            boolean first = true;

            @Override public void pick(int i) {
                prefs.edit().putInt("bottlePreset", i).apply();
                float[] d = BottleLesson.PRESET_DIMS[i];
                // The spinner reports its initial selection too: keep the user's saved sizes then.
                if (first) { first = false; return; }
                if (sliders[0] != null) {
                    sliders[0].setProgress(Math.round((d[0] - 100) / 10));
                    sliders[1].setProgress(Math.round(d[1] - 10));
                    sliders[2].setProgress(Math.round(d[2] - 10));
                }
            }
        });
        sliders[0] = dynSlider("Об'єм пляшки: %.0f мл", "bottleVol", 190, 40, new Value() {
            @Override public float of(int p) { return 100 + p * 10; }
        }, new OnPick() {
            @Override public void pick(int p) { bottleLesson.volumeMl = 100 + p * 10; }
        });
        sliders[1] = dynSlider("Діаметр шийки: %.0f мм", "bottleDia", 30, 11, new Value() {
            @Override public float of(int p) { return 10 + p; }
        }, new OnPick() {
            @Override public void pick(int p) { bottleLesson.neckDiaMm = 10 + p; }
        });
        sliders[2] = dynSlider("Довжина шийки: %.0f мм", "bottleLen", 110, 15, new Value() {
            @Override public float of(int p) { return 10 + p; }
        }, new OnPick() {
            @Override public void pick(int p) { bottleLesson.neckLenMm = 10 + p; }
        });
        dynSlider("Налито води: %.0f%%", "bottleWater", 90, 0, new Value() {
            @Override public float of(int p) { return p; }
        }, new OnPick() {
            @Override public void pick(int p) { bottleLesson.waterPct = p; }
        });
        dynButton(dyn, "♪ Зіграти розрахункову ноту", new View.OnClickListener() {
            @Override public void onClick(View v) {
                float[] pcm = new float[48000];
                LessonKit.addTone(pcm, 0, pcm.length, bottleLesson.predictHz(), 0.7f, 48000, 2400);
                playBuffer(pcm, false);
            }
        });
        return "Дмухніть через шийку пляшки — вона загуде. Повітря в шийці працює як поршень, а повітря всередині — "
                + "як пружина. Така система має власну частоту — резонанс Гельмгольца.\n\n"
                + "Більше повітря всередині — нижча нота; доливаєте воду — повітря менше, нота вища. Вузька й довга "
                + "шийка — теж нижча нота.\n\n"
                + "Задайте розміри своєї пляшки, дмухніть — і порівняйте розрахунок із тим, що почув мікрофон. "
                + "Унизу таблиця: скільки води налити в кожну пляшку, щоб зіграти гаму.";
    }

    private short[] fxSource;
    private int fxRate = 48000;

    private String lessonVoiceFx() {
        ensureLive(0);
        stopBuffer();
        fxSource = null;
        useMode(3);
        dynLabel("1) Скажіть фразу в мікрофон.  2) Натисніть ефект.");
        LinearLayout row = null;
        for (int i = 0; i < VoiceFx.NAMES.length; i++) {
            if (i % 3 == 0) row = dynRow();
            final int fx = i;
            dynButton(row, VoiceFx.NAMES[i], new View.OnClickListener() {
                @Override public void onClick(View v) { applyVoiceFx(fx); }
            });
        }
        dynButton(dyn, "🎙 Записати нову фразу", new View.OnClickListener() {
            @Override public void onClick(View v) {
                fxSource = null;
                if (currentTape != null) closeTape();
                ensureLive(0);
                useMode(3);
                toast("Скажіть фразу, потім натисніть ефект");
            }
        });
        return "Застосунок пам'ятає останні секунди звуку. Скажіть щось і натисніть ефект — запис обробиться і "
                + "відкриється в «машині часу»: зі спектрограмою і відтворенням.\n\n"
                + "Порівнюйте спектрограми: гелій піднімає всі частоти, телефон обрізає низ і верх, робот подвоює "
                + "кожну лінію, луна повторює малюнок, а задом наперед — перевертає його в часі.";
    }

    private void applyVoiceFx(int fx) {
        if (fxSource == null) {
            AudioEngine.Tape h = engine.captureHistory();
            if (h == null) { toast("Спершу скажіть щось у мікрофон"); return; }
            int n = Math.min(h.pcm.length, h.rate * 4);
            short[] last = new short[n];
            System.arraycopy(h.pcm, h.pcm.length - n, last, 0, n);
            fxSource = VoiceFx.trim(last, h.rate);
            fxRate = h.rate;
            if (fxSource == null) { toast("Не чути голосу — скажіть фразу голосніше"); return; }
        }
        short[] out = VoiceFx.apply(fx, fxSource, fxRate);
        if (currentTape != null) engine.closeTape();
        AudioEngine.Tape t = new AudioEngine.Tape(out, fxRate, "Ефект: " + VoiceFx.NAMES[fx], false);
        useMode(3);
        openTape(t, 0);
        engine.setPlaying(true);
        txtLesson.setText(VoiceFx.EXPLAIN[fx] + "\n\nНатисніть інший ефект або «Записати нову фразу».");
    }

    private String lessonRoom() {
        if (roomLesson == null) roomLesson = new RoomLesson(density());
        ensureLive(0);
        roomLesson.resetHistory();
        viz2d.showLesson(roomLesson);
        dynSlider("Довжина кімнати: %.1f м", "roomL", 80, 25, new Value() {
            @Override public float of(int p) { return 2 + p / 10f; }
        }, new OnPick() {
            @Override public void pick(int p) { roomLesson.lengthM = 2 + p / 10f; }
        });
        dynSlider("Ширина кімнати: %.1f м", "roomW", 80, 15, new Value() {
            @Override public float of(int p) { return 2 + p / 10f; }
        }, new OnPick() {
            @Override public void pick(int p) { roomLesson.widthM = 2 + p / 10f; }
        });
        dynSlider("Висота стелі: %.1f м", "roomH", 20, 6, new Value() {
            @Override public float of(int p) { return 2 + p / 10f; }
        }, new OnPick() {
            @Override public void pick(int p) { roomLesson.heightM = 2 + p / 10f; }
        });
        final SeekBar tone = dynSlider("Частота тону: %.0f Гц", "roomTone", 1000, 500, new Value() {
            @Override public float of(int p) { return (float) (25 * Math.pow(12, p / 1000.0)); }
        }, new OnPick() {
            @Override public void pick(int p) { roomLesson.toneHz = (float) (25 * Math.pow(12, p / 1000.0)); // 25 .. 300 Hz
                SignalGenerator.frequency = roomLesson.toneHz; }
        });
        LinearLayout row = dynRow();
        dynButton(row, "◀ мода", new View.OnClickListener() {
            @Override public void onClick(View v) { jumpMode(tone, -1); }
        });
        final Button play = dynButton(row, "▶ Тон", null);
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !(chkPlay.isChecked() && SignalGenerator.waveform == SignalGenerator.Waveform.SINE);
                SignalGenerator.waveform = SignalGenerator.Waveform.SINE;
                select(R.id.spinWave, 0);
                SignalGenerator.frequency = roomLesson.toneHz;
                chkPlay.setChecked(on);
                play.setText(on ? "■ Тиша" : "▶ Тон");
            }
        });
        dynButton(row, "мода ▶", new View.OnClickListener() {
            @Override public void onClick(View v) { jumpMode(tone, 1); }
        });
        dynButton(dyn, "Скинути графік", new View.OnClickListener() {
            @Override public void onClick(View v) { roomLesson.resetHistory(); }
        });
        return "Низький звук у кімнаті відбивається від стін і накладається сам на себе. На певних частотах (модах) "
                + "утворюються стоячі хвилі: в одних місцях хвилі додаються — гучно (пучність), в інших гасять одна "
                + "одну — тихо (вузол).\n\n"
                + "Введіть розміри кімнати — угорі з'являться її моди. Увімкніть тон на одній із мод і повільно "
                + "пройдіться з телефоном: графік покаже різницю між гучними й тихими місцями — буває 20 дБ і більше! "
                + "Саме тому бас у кімнаті звучить по-різному в різних кутках.";
    }

    private void jumpMode(SeekBar tone, int dir) {
        java.util.List<float[]> modes = roomLesson.modes();
        float cur = roomLesson.toneHz, target = Float.NaN;
        for (float[] m : modes) {
            if (dir > 0 && m[0] > cur + 0.5f) { target = m[0]; break; }
            if (dir < 0 && m[0] < cur - 0.5f) target = m[0];
        }
        if (Float.isNaN(target)) return;
        tone.setProgress((int) Math.round(1000 * Math.log(target / 25.0) / Math.log(12)));
        roomLesson.toneHz = target;
        SignalGenerator.frequency = target;
    }

    private String lessonSpeed() {
        if (speedLesson == null) speedLesson = new SpeedLesson(density());
        ensureLive(0);
        stopBuffer();
        viz2d.showLesson(speedLesson);
        dynSlider("Відстань до стіни: %.0f м", "speedDist", 48, 8, new Value() {
            @Override public float of(int p) { return 2 + p; }
        }, new OnPick() {
            @Override public void pick(int p) { speedLesson.distanceM = 2 + p; }
        });
        return "Звук летить приблизно 343 метри за секунду — у мільйон разів повільніше за світло. Це можна виміряти "
                + "самому!\n\n"
                + "Станьте навпроти великої стіни (надворі, 10–30 м), виміряйте відстань кроками і задайте її повзунком. "
                + "Ляскніть один раз: звук долетить до стіни і повернеться. Застосунок знайде луну на графіку і "
                + "порахує швидкість = 2 × відстань ÷ затримку.";
    }

    private String lessonEar() {
        if (earLesson == null) {
            earLesson = new EarLesson(density(), 48000, new EarLesson.Sound() {
                @Override public void play(float[] pcm) { playBuffer(pcm, false); }
            });
        }
        ensureLive(0);
        viz2d.showLesson(earLesson);
        final LinearLayout answers = new LinearLayout(this);
        answers.setOrientation(LinearLayout.VERTICAL);
        dynSpinner("Гра", new String[]{"Яка нота вища?", "Вгадай інтервал", "Повтори ноту голосом"},
                earLesson.game.ordinal(), new OnPick() {
                    @Override public void pick(int i) {
                        earLesson.setGame(EarLesson.Game.values()[i]);
                        buildAnswers(answers);
                        viz2d.invalidate();
                    }
                });
        LinearLayout row = dynRow();
        Button go = dynButton(row, "▶ Нове завдання", new View.OnClickListener() {
            @Override public void onClick(View v) { earLesson.newTask(); viz2d.invalidate(); }
        });
        go.setTextColor(0xFF00F0FF);
        dynButton(row, "🔁 Ще раз", new View.OnClickListener() {
            @Override public void onClick(View v) { earLesson.replay(); }
        });
        answers.setLayoutParams(full());
        dyn.addView(answers);
        buildAnswers(answers);
        return "Слух можна тренувати, як м'язи. Почніть із «Яка нота вища?»: кожна правильна відповідь зменшує "
                + "різницю між нотами — так ви дізнаєтесь, наскільки тонкий у вас слух (100 центів = півтон; "
                + "музиканти чують 5–10 центів).\n\n"
                + "«Вгадай інтервал» вчить чути відстань між нотами, як у музиці. «Повтори ноту голосом» — "
                + "заспівайте почуту ноту і тримайте, поки смужка не заповниться.";
    }

    private void buildAnswers(LinearLayout answers) {
        answers.removeAllViews();
        String[] opts = earLesson.game == EarLesson.Game.HIGHER ? new String[]{"Перша вища", "Друга вища"}
                : earLesson.game == EarLesson.Game.INTERVAL ? EarLesson.INTERVAL_NAMES : new String[0];
        LinearLayout row = null;
        for (int i = 0; i < opts.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(full());
                answers.addView(row);
            }
            final int choice = i;
            dynButton(row, opts[i], new View.OnClickListener() {
                @Override public void onClick(View v) { earLesson.answer(choice); viz2d.invalidate(); }
            });
        }
    }

    private String lessonRhythm() {
        if (rhythmLesson == null) rhythmLesson = new RhythmLesson(density());
        ensureLive(0);
        stopBuffer();
        rhythmLesson.reset();
        viz2d.showLesson(rhythmLesson);
        dynSlider("Темп: %.0f ударів за хвилину", "rhythmBpm", 130, 40, new Value() {
            @Override public float of(int p) { return 50 + p; }
        }, new OnPick() {
            @Override public void pick(int p) {
                rhythmLesson.bpm = 50 + p;
                if (chkPlay.isChecked() && SignalGenerator.waveform == SignalGenerator.Waveform.BUFFER && SignalGenerator.bufferLoop) {
                    playBuffer(RhythmLesson.metronome(rhythmLesson.bpm, 48000), true);
                }
            }
        });
        LinearLayout row = dynRow();
        final Button start = dynButton(row, "▶ Старт метронома", null);
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (chkPlay.isChecked()) {
                    stopBuffer();
                    start.setText("▶ Старт метронома");
                } else {
                    rhythmLesson.reset();
                    playBuffer(RhythmLesson.metronome(rhythmLesson.bpm, 48000), true);
                    start.setText("■ Стоп");
                }
            }
        });
        dynButton(row, "Скинути", new View.OnClickListener() {
            @Override public void onClick(View v) { rhythmLesson.reset(); viz2d.invalidate(); }
        });
        return "Відчуття ритму теж тренується. Увімкніть метроном і плескайте разом із ним.\n\n"
                + "Мікрофон чує і клацання метронома, і ваші оплески — тому застосунок точно знає, на скільки "
                + "мілісекунд ви поспішаєте чи запізнюєтесь, і наскільки рівно плескаєте. Барабанщики тримають "
                + "розкид у межах ±10–20 мс. Спробуйте повільний і швидкий темп — що легше?";
    }

    // ---------------------------------------------------------------- visual lessons (v8)

    private CochleaLesson cochleaLesson;
    private StringLesson stringLesson;
    private EnvelopeLesson envelopeLesson;
    private ChladniLesson chladniLesson;
    private InterferenceLesson interferenceLesson;
    private EqualizerLesson equalizerLesson;
    private LoudnessLesson loudnessLesson;

    private CochleaLesson cochlea() { if (cochleaLesson == null) cochleaLesson = new CochleaLesson(density()); return cochleaLesson; }
    private StringLesson string() { if (stringLesson == null) stringLesson = new StringLesson(density()); return stringLesson; }
    private EnvelopeLesson envelope() { if (envelopeLesson == null) envelopeLesson = new EnvelopeLesson(density()); return envelopeLesson; }
    private ChladniLesson chladni() { if (chladniLesson == null) chladniLesson = new ChladniLesson(density()); return chladniLesson; }
    private LoudnessLesson loudness() { if (loudnessLesson == null) loudnessLesson = new LoudnessLesson(density()); return loudnessLesson; }

    /** Live lesson views that can also be picked as a display mode (negative codes in SPINNER_MODES). */
    private LessonView liveView(int code) {
        switch (code) {
            case -1: return cochlea();
            case -2: return string();
            case -3: return envelope();
            case -4: chladni().live = true; return chladni();
            case -6: return vowels();
            case -7: return range();
            default: return loudness();
        }
    }

    private VowelLesson vowelLesson;
    private VoiceRangeLesson rangeLesson;

    private VowelLesson vowels() {
        if (vowelLesson == null) {
            vowelLesson = new VowelLesson(density());
            vowelLesson.voice = prefs.getInt("vowelVoice", 0);
        }
        return vowelLesson;
    }

    private VoiceRangeLesson range() { if (rangeLesson == null) rangeLesson = new VoiceRangeLesson(density()); return rangeLesson; }

    private final java.util.Map<Integer, LessonView> simpleViews = new java.util.HashMap<>();

    /** "Simple" lessons: one picture, no controls, a few plain words. */
    private String lessonSimple(int n) {
        ensureLive(0);
        stopBuffer();
        LessonView v = simpleViews.get(n);
        if (v == null) {
            float d = density();
            switch (n) {
                case 32: v = new ThreadsLesson(d); break;
                case 33: v = new AnimalsLesson(d); break;
                case 34: v = new FaceLesson(d); break;
                case 35: v = new LandscapeLesson(d); break;
                case 36: v = new ColorLesson(d); break;
                case 37: v = new RippleLesson(d); break;
                case 38: v = new StairsLesson(d); break;
                default: v = new BalloonLesson(d); break;
            }
            simpleViews.put(n, v);
        }
        viz2d.showLesson(v);
        switch (n) {
            case 32: return "Свисніть, загудіть, заспівайте. Кожна ниточка відгукується лише на свою висоту звуку — "
                    + "як гойдалка, яку штовхають у такт. Так само всередині вуха: тисячі «ниточок», кожна для свого звуку.";
            case 33: return "Велике тіло — низький голос (слон, ведмідь), маленьке — високий (кошеня, комар). "
                    + "Скажіть щось низько, а потім пискніть — подивіться, на кого схожі.";
            case 34: return "Обличчя «чує» звук: високий піднімає брови, гучний відкриває рот, чистий свист — усмішка, "
                    + "шум — кривиться, різкий звук — жмуриться. А тиша — спить.";
            case 35: return "Ваш звук перетворюється на краєвид: низькі звуки ростуть горами, середні — деревами, "
                    + "високі — зірками й птахами, а гучність — це вітер.";
            case 36: return "Увесь звук складається в один колір: багато низьких — червоний і «теплий», багато високих — "
                    + "синій і «холодний». Порівняйте кольори голосів друзів!";
            case 37: return "Звук біжить повітрям кругами, як хвилі від каменя. Низький звук — довгі хвилі (метри), "
                    + "високий — короткі (сантиметри).";
            case 38: return "Співайте «до-ре-мі-фа-соль-ля-сі-до». Коли влучаєте в ноту, м'ячик скаче на сходинці. "
                    + "Якщо фальшивите — він висить між сходинками.";
            default: return "Шепніть, скажіть, крикніть: кулька росте з гучністю. Звук — це тиск повітря, "
                    + "як у кульці. Тільки не луснути!";
        }
    }

    private CandlesGame candlesGame;
    private GlassGame glassGame;
    private DragonGame dragonGame;
    private SimonGame simonGame;
    private PlaneGame planeGame;
    private RhythmEchoGame echoGame;
    private LoudGame loudGame;
    private HotColdGame hotColdGame;


    private String lessonGame(int n) {
        ensureLive(0);
        stopBuffer();
        float d = density();
        switch (n) {
            case 41: {
                if (candlesGame == null) candlesGame = new CandlesGame(d);
                candlesGame.reset();
                viz2d.showLesson(candlesGame);
                dynButton(dyn, "🎂 Нові свічки", new View.OnClickListener() {
                    @Override public void onClick(View v) { candlesGame.reset(); }
                });
                return "Задуйте всі свічки на торті! Дмухайте прямо в мікрофон — він унизу телефона. Свист чи голос не "
                        + "рахуються: застосунок відрізняє видих (шум без висоти, багато низького «гулу» від потоку повітря) "
                        + "від інших звуків. Чим сильніший видих — тим швидше гаснуть свічки.";
            }
            case 42: {
                if (glassGame == null) {
                    glassGame = new GlassGame(d, new GlassGame.Sound() {
                        @Override public void play(float[] pcm) { playBuffer(pcm, false); }
                    });
                } else {
                    glassGame.newGlass();
                }
                viz2d.showLesson(glassGame);
                LinearLayout row = dynRow();
                dynButton(row, "🔔 Постукати по склянці", new View.OnClickListener() {
                    @Override public void onClick(View v) { glassGame.ping(); }
                });
                dynButton(row, "🥛 Нова склянка", new View.OnClickListener() {
                    @Override public void onClick(View v) { glassGame.newGlass(); }
                });
                return "Кожна склянка має свою ноту — постукайте, щоб почути. Заспівайте цю ноту (можна в будь-якій "
                        + "октаві) і тримайте рівно 2 секунди: склянка розгойдуватиметься все сильніше і трісне.\n\n"
                        + "Це резонанс: маленькі поштовхи точно в такт складаються в великі коливання. Так справжні співаки "
                        + "розбивають келихи, а солдати не крокують в ногу по мостах.";
            }
            case 43: {
                if (dragonGame == null) dragonGame = new DragonGame(d);
                dragonGame.reset();
                viz2d.showLesson(dragonGame);
                dynButton(dyn, "🐉 Нова гра", new View.OnClickListener() {
                    @Override public void onClick(View v) { dragonGame.reset(); }
                });
                return "Дракон спить на скарбах. Торкайтеся екрана, щоб красти монети, але не шуміть: застосунок спершу "
                        + "слухає, яка в кімнаті тиша, і будь-який звук гучніший за неї розбудить дракона. "
                        + "Грайте компанією: хто перший засміявся — програв!";
            }
            case 44: {
                if (simonGame == null) {
                    simonGame = new SimonGame(d, new SimonGame.Sound() {
                        @Override public void play(float[] pcm) { playBuffer(pcm, false); }
                    });
                }
                viz2d.showLesson(simonGame);
                dynButton(dyn, "▶ Почати", new View.OnClickListener() {
                    @Override public void onClick(View v) { simonGame.start(); }
                });
                return "Застосунок зіграє кольорові ноти: до, мі, соль, ля. Повторіть їх голосом або свистом по черзі — "
                        + "будь-якою октавою. Кожного раунду додається ще одна нота. Скільки запам'ятаєте?\n\n"
                        + "Між однаковими нотами поспіль зробіть коротку паузу.";
            }
            case 45: {
                if (planeGame == null) planeGame = new PlaneGame(d);
                viz2d.showLesson(planeGame);
                dynSpinner("Мій голос", new String[]{"Низький (чоловічий)", "Високий (жіночий, дитячий)"},
                        planeGame.centerMidi > 56 ? 1 : 0, new OnPick() {
                            @Override public void pick(int i) { planeGame.centerMidi = i == 0 ? 52 : 62; }
                        });
                dynButton(dyn, "▶ Старт", new View.OnClickListener() {
                    @Override public void onClick(View v) { planeGame.start(); }
                });
                return "Тягніть голосом «у-у-у»: вище нота — літачок піднімається, нижче — опускається, замовкнете — падає. "
                        + "Пролітайте в проміжки між горами. Біла крапка показує, куди «тягне» ваш голос.";
            }
            case 46: {
                if (echoGame == null) {
                    echoGame = new RhythmEchoGame(d, new RhythmEchoGame.Sound() {
                        @Override public void play(float[] pcm) { playBuffer(pcm, false); }
                    });
                }
                viz2d.showLesson(echoGame);
                LinearLayout row = dynRow();
                dynButton(row, "▶ Послухати ритм", new View.OnClickListener() {
                    @Override public void onClick(View v) { echoGame.play(); }
                });
                dynButton(row, "Рівень 1", new View.OnClickListener() {
                    @Override public void onClick(View v) { echoGame.level = 0; viz2d.invalidate(); }
                });
                return "Телефон проплескає ритм — повторіть його оплесками. Важливі проміжки між оплесками: похибка до "
                        + "0,1 секунди зараховується. Кожен рівень — складніший ритм.";
            }
            case 47: {
                if (loudGame == null) loudGame = new LoudGame(d);
                viz2d.showLesson(loudGame);
                LinearLayout row = dynRow();
                dynButton(row, "📣 Хід гравця", new View.OnClickListener() {
                    @Override public void onClick(View v) { loudGame.startTurn(); }
                });
                dynButton(row, "Нова гра", new View.OnClickListener() {
                    @Override public void onClick(View v) { loudGame.newGame(); viz2d.invalidate(); }
                });
                return "Гравці по черзі тримають телефон на однаковій відстані від рота (наприклад, на витягнутій руці) "
                        + "і натискають «Хід гравця». За 3 секунди зараховується найгучніша мить. Хто переможе?";
            }
            default: {
                if (hotColdGame == null) hotColdGame = new HotColdGame(d);
                final HotColdGame g = hotColdGame;
                viz2d.showLesson(g);
                dynSpinner("Цей телефон", new String[]{"Шукач (слухає)", "Маячок (ховаємо, пищить)"}, g.beacon ? 1 : 0, new OnPick() {
                    @Override public void pick(int i) {
                        g.beacon = i == 1;
                        if (g.beacon) playBuffer(HotColdGame.beep(48000), true); else stopBuffer();
                        viz2d.invalidate();
                    }
                });
                return "Потрібно два телефони. Один — «маячок»: його ховають, і він тихо пищить на високій ноті. "
                        + "Другий — «шукач»: ходіть з ним і дивіться на термометр. Шукач чує лише писк маячка й не реагує "
                        + "на розмови, тож можна підказувати «тепло-холодно» голосом.";
            }
        }
    }

    private DuelGame duelGame;

    private String lessonDuel() {
        ensureLive(0);
        stopBuffer();
        if (duelGame == null) {
            duelGame = new DuelGame(density(), new DuelGame.Sound() {
                @Override public void play(float[] pcm, boolean loop) { playBuffer(pcm, loop); }
                @Override public void stop() { stopBuffer(); }
            });
        }
        final DuelGame g = duelGame;
        viz2d.showLesson(g);
        dynSpinner("Хто я", new String[]{"Граю сам", "Гравець 1 (починає)", "Гравець 2 (чекає на старт)"}, g.role.ordinal(),
                new OnPick() {
                    @Override public void pick(int i) { g.role = DuelGame.Role.values()[i]; viz2d.invalidate(); }
                });
        dynSpinner("Тон-«кнопка»", new String[]{"Майже нечутний (17–18 кГц)", "Чутний (якщо телефон не реагує)"}, g.audible ? 1 : 0,
                new OnPick() {
                    @Override public void pick(int i) { g.audible = i == 1; }
                });
        Button go = dynButton(dyn, "▶ Почати", new View.OnClickListener() {
            @Override public void onClick(View v) { g.start(); }
        });
        go.setTextColor(0xFF00F0FF);
        return "Музика грає, мітки біжать до білої лінії. Коли мітка на лінії — закрийте пальцем мікрофон (отвір унизу "
                + "телефона) і відпустіть. Ідеально — 100 очок, добре — 50, серія з п'яти влучань подвоює очки.\n\n"
                + "Як це працює: телефон грає ще й тихий високий тон і весь час слухає його своїм мікрофоном. Палець "
                + "приглушує тон — це і є «натискання». Затримку динаміка й мікрофона застосунок вимірює сам.\n\n"
                + "Удвох: покладіть телефони поруч. Другий гравець обирає «Гравець 2» і тисне «Почати» — телефон чекає. "
                + "Перший гравець тисне «Почати»: його телефон звуком дає сигнал старту, і обидва починають разом. "
                + "Наприкінці телефони звуком обмінюються рахунками — і кожен покаже, хто переміг.";
    }

    private TemperatureExperiment temperature;

    private String lessonTemperature() {
        ensureLive(0);
        stopBuffer();
        if (temperature == null) {
            temperature = new TemperatureExperiment(density(), new TemperatureExperiment.Sound() {
                @Override public void play(float[] pcm, boolean loop) { playBuffer(pcm, loop); }
            });
        }
        final TemperatureExperiment t = temperature;
        viz2d.showLesson(t);
        dynSpinner("Роль цього телефона", new String[]{"A — ведучий (вимірює)", "B — відповідач"}, t.role.ordinal(),
                new OnPick() {
                    @Override public void pick(int i) { t.setRole(TemperatureExperiment.Role.values()[i]); viz2d.invalidate(); }
                });
        dynSlider("Відстань між телефонами (на A): %.2f м", "temp_dist", 96, 16, new Value() {
            @Override public float of(int p) { return 0.2f + 0.05f * p; }
        }, new OnPick() {
            @Override public void pick(int p) { t.distance = 0.2f + 0.05f * p; }
        });
        LinearLayout row = dynRow();
        Button go = dynButton(row, "📏 Виміряти", new View.OnClickListener() {
            @Override public void onClick(View v) { t.measure(); viz2d.invalidate(); }
        });
        go.setTextColor(0xFF00F0FF);
        dynButton(row, "Почати заново", new View.OnClickListener() {
            @Override public void onClick(View v) { t.clearResults(); viz2d.invalidate(); }
        });
        return "Звук у теплому повітрі біжить швидше: c ≈ 331,3 + 0,606·T м/с (T у °C). При 0 °C — 331 м/с, при 25 °C — 346 м/с. "
                + "Отже, якщо точно виміряти швидкість звуку, можна дізнатися температуру.\n\n"
                + "Проблема: годинники двох телефонів не синхронізовані. Рішення — «луна у дві сторони»: A пікає, B чує і пікає у "
                + "відповідь. Кожен телефон своїм мікрофоном міряє час між двома піками, які почув, — ΔA і ΔB. B передає своє ΔB "
                + "звуком (модемом). Різниця ΔA − ΔB = 2·d / c плюс сталий доданок — усі затримки динаміків, мікрофонів і реакції "
                + "скорочуються!\n\n"
                + "Як робити:\n"
                + "1. На другому телефоні оберіть «B — відповідач». На своєму — «A — ведучий».\n"
                + "2. Покладіть телефони низом (мікрофонами й динаміками) один до одного на відстані 0,5 м. Виставте її повзунком "
                + "і натисніть «Виміряти» 2–3 рази.\n"
                + "3. Відсуньте ОДИН телефон по рулетці ще на 2–3 м (не повертаючи) і повторіть. Застосунок знайде швидкість за "
                + "нахилом — сталі похибки зникнуть.\n\n"
                + "Точність: 1 см помилки на 3 м — це 0,3 % швидкості, тобто ±1 м/с ≈ ±2 °C, тож міряйте рулеткою й тримайте телефони "
                + "нерухомо. Гучність — на максимум, навколо тиша. Вологість теж трохи прискорює звук (до +0,5 м/с), тому у "
                + "вологому повітрі результат буде на 1 °C вищим.";
    }

    private WindGame windGame;

    private String lessonWind() {
        ensureLive(0);
        stopBuffer();
        if (windGame == null) {
            windGame = new WindGame(density());
            windGame.setPlayers(prefs.getString("wind_players", "Гравець 1, Гравець 2"));
        }
        final WindGame g = windGame;
        viz2d.showLesson(g);
        dynSpinner("Змагання", WindGame.MODE_NAMES, 0, new OnPick() {
            @Override public void pick(int i) { g.setMode(i); viz2d.invalidate(); }
        });
        dynLabel("Гравці (через кому, до 8):");
        final EditText names = dynEdit("Аня, Богдан, Віка", g.playersCsv());
        LinearLayout row = dynRow();
        dynButton(row, "👥 Зберегти гравців", new View.OnClickListener() {
            @Override public void onClick(View v) {
                g.setPlayers(names.getText().toString());
                prefs.edit().putString("wind_players", g.playersCsv()).apply();
                names.setText(g.playersCsv());
                viz2d.invalidate();
            }
        });
        Button go = dynButton(row, "▶ Старт", new View.OnClickListener() {
            @Override public void onClick(View v) { g.start(); }
        });
        go.setTextColor(0xFF00F0FF);
        return "Турнір з дмухання на одному телефоні: передавайте його по колу — так чесно, бо мікрофон один для всіх.\n\n"
                + "💨 Хто сильніше — 4 секунди, рахується найсильніший порив (кулька).\n"
                + "⏱ Хто довше — скільки секунд дмухаєте без перерви, хоч і слабко (вітряк).\n"
                + "🔥 Сильно і довго — скільки протримаєтесь вище білої риски (вітрильник пливе до фінішу).\n"
                + "🎯 Контроль — 12 секунд тримайте м'ячик у зеленій смузі, що повільно хвилюється.\n"
                + "🏆 Чемпіон — усі чотири підряд, кожне до 100 очок.\n\n"
                + "Застосунок відрізняє видих від голосу: спів чи крик не рахуються («Це не вітер!»). Тримайте телефон "
                + "однаково, ~10 см від рота. Не більше 20 секунд за раз, між спробами відпочивайте — щоб не паморочилось.";
    }

    private MusicPauseGame musicPause;

    private String lessonMusicPause() {
        ensureLive(0);
        stopBuffer();
        if (musicPause == null) {
            musicPause = new MusicPauseGame(density(), new MusicPauseGame.Sound() {
                @Override public void play(float[] pcm, boolean loop) { playBuffer(pcm, loop); }
                @Override public void stop() { stopBuffer(); }
            });
        }
        final MusicPauseGame g = musicPause;
        viz2d.showLesson(g);
        dynSpinner("Хто я", MusicPauseGame.ROLES, g.role, new OnPick() {
            @Override public void pick(int i) { g.role = i; viz2d.invalidate(); }
        });
        Button go = dynButton(dyn, "▶ Почати", new View.OnClickListener() {
            @Override public void onClick(View v) { g.start(); }
        });
        go.setTextColor(0xFF00F0FF);
        return "Гра для компанії з кількома телефонами. Один телефон — ведучий: він грає музику, яка раптово замовкає "
                + "(5 раундів). Кожен гравець бере свій телефон, обирає свій номер (1–5, у всіх різні) і тисне «Почати» "
                + "ДО того, як ведучий запустить музику.\n\n"
                + "Щойно музика замовкла — закрийте пальцем мікрофон (отвір унизу телефона). Телефон міряє вашу реакцію "
                + "від миті, коли САМ почув паузу, тож не важливо, хто стоїть ближче. Закрили, поки музика ще грає, — "
                + "фальстарт (+0,4 с штрафу).\n\n"
                + "Наприкінці ведучий подає звуковий сигнал, і телефони по черзі «пропищать» свої результати — у кожного "
                + "з'явиться загальна таблиця. Тримайте телефони поруч і не шуміть ці 15 секунд.\n\n"
                + "Немає друзів поруч? Оберіть «Тренування» — телефон сам грає музику і сам міряє реакцію.";
    }

    private TugGame tugGame;

    private String lessonTug() {
        ensureLive(0);
        stopBuffer();
        if (tugGame == null) tugGame = new TugGame(density());
        final TugGame g = tugGame;
        g.newMatch();
        viz2d.showLesson(g);
        LinearLayout row = dynRow();
        Button go = dynButton(row, "▶ Раунд", new View.OnClickListener() {
            @Override public void onClick(View v) { g.start(); }
        });
        go.setTextColor(0xFF00F0FF);
        dynButton(row, "Новий матч", new View.OnClickListener() {
            @Override public void onClick(View v) { g.newMatch(); }
        });
        return "Покладіть телефон посередині між двома командами (по 1–5 людей). Команда «Гул» 🐻 тягне довгим низьким "
                + "«у-у-у», команда «Шум» 🐍 — шипінням «ш-ш-ш». Хто сильніший — той тягне канат до себе. Раунд — до "
                + "лінії або 30 секунд, матч — до двох перемог.\n\n"
                + "Чому саме так: «у» — це низькі частоти (до ~900 Гц), «ш» — високі (2,5–12 кГц). Телефон порівнює "
                + "енергію у двох смугах, тож гра працює для будь-яких голосів — і дитячих, і дорослих. Подивіться на "
                + "спектр у вкладці «Вигляд» — побачите дві різні «гори».\n\n"
                + "Порада: не кричіть у телефон з упору — сидіть на однаковій відстані, і хай переможе командна гра!";
    }

    private ParrotLesson parrotLesson;
    private int parrotVoice = 1;

    private String lessonParrot() {
        ensureLive(0);
        stopBuffer();
        if (parrotLesson == null) {
            parrotLesson = new ParrotLesson(density(), new ParrotLesson.Speaker() {
                @Override public float[] speak(short[] phrase, int rate) {
                    if (phrase.length < rate / 5) return null;
                    short[] out = VoiceFx.apply(parrotVoice, phrase, rate);
                    float[] pcm = new float[(int) ((long) out.length * 48000 / rate)];
                    for (int i = 0; i < pcm.length; i++) pcm[i] = out[Math.min(out.length - 1, (int) ((long) i * rate / 48000))] / 32768f * 0.5f;
                    playBuffer(pcm, false);
                    return pcm;
                }
            });
        }
        viz2d.showLesson(parrotLesson);
        dynSpinner("Голос папуги", new String[]{"Папуга (тоненько)", "Велетень (басом)", "Робот", "Луна", "Задом наперед"}, 0,
                new OnPick() {
                    @Override public void pick(int i) { parrotVoice = new int[]{1, 2, 3, 5, 6}[i]; }
                });
        return "Скажіть коротку фразу і замовкніть — папуга Кеша запам'ятає й повторить її. Поки він говорить, "
                + "він не слухає, тож не повторює сам себе.\n\n"
                + "Спробуйте різні голоси: папуга прискорює запис (голос стає вищим), велетень сповільнює, робот "
                + "множить на гудіння, а «задом наперед» — перевертає.";
    }

    private String lessonVowels() {
        ensureLive(0);
        stopBuffer();
        final VowelLesson v = vowels();
        viz2d.showLesson(v);
        dynSpinner("Чий голос", VowelLesson.VOICES, v.voice, new OnPick() {
            @Override public void pick(int i) {
                v.voice = i;
                prefs.edit().putInt("vowelVoice", i).apply();
                viz2d.invalidate();
            }
        });
        return "Протягніть по черзі «і-і-і», «е-е-е», «а-а-а», «о-о-о», «у-у-у» на одній ноті. Точка на карті "
                + "переміщатиметься, хоча висота голосу не змінюється!\n\n"
                + "Рот і горло — це трубка-резонатор. Положення язика й губ змінює її резонанси — форманти. "
                + "Перша (F1) росте, коли рот відкривається ширше (і → а). Друга (F2) росте, коли язик іде вперед "
                + "(у → і). Саме за F1 і F2 мозок упізнає голосну — і за ними ж розмовляють співаки, щоб звучати «округло».\n\n"
                + "Оберіть свій тип голосу: у жінок і дітей форманти вищі, бо трубка коротша.";
    }

    private String lessonRange() {
        ensureLive(0);
        stopBuffer();
        final VoiceRangeLesson r = range();
        viz2d.showLesson(r);
        LinearLayout row = dynRow();
        dynButton(row, "Почати заново", new View.OnClickListener() {
            @Override public void onClick(View v) { r.reset(); viz2d.invalidate(); }
        });
        dynButton(row, "♪ Дати ноту до", new View.OnClickListener() {
            @Override public void onClick(View v) {
                float[] pcm = new float[48000 * 2];
                LessonKit.addNote(pcm, 0, pcm.length, LessonKit.midiToHz(48), 0.9f, 48000);
                playBuffer(pcm, false);
            }
        });
        return "Заспівайте найнижчу ноту, яку можете впевнено втримати, і плавно піднімайтесь (на «а» або «у») "
                + "до найвищої. Кожна нота, яку ви втримаєте хоча б 0,3 с, засвітиться на клавіатурі.\n\n"
                + "За діапазоном застосунок підкаже, до якого типу голосу він найближчий: бас, баритон, тенор, "
                + "альт, меццо-сопрано чи сопрано. Це лише орієнтир — справжній тип визначає ще й тембр, і де "
                + "голосу зручно. Не напружуйтесь на крайніх нотах!";
    }

    private String lessonCochlea() {
        ensureLive(0);
        stopBuffer();
        viz2d.showLesson(cochlea());
        final Button sweep = dynButton(dyn, "▶ Свіп через динамік: подивіться, як «біжить» звук", null);
        sweep.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !chkPlay.isChecked();
                SignalGenerator.waveform = SignalGenerator.Waveform.LOG_SWEEP;
                select(R.id.spinWave, 6);
                chkPlay.setChecked(on);
                sweep.setText(on ? "■ Зупинити свіп" : "▶ Свіп через динамік: подивіться, як «біжить» звук");
            }
        });
        return "Усередині вуха — равлик, скручена трубка з рідиною, а вздовж неї натягнута мембрана. Біля входу вона "
                + "вузька й туга і відгукується на високі звуки, у глибині — широка й м'яка і відгукується на низькі.\n\n"
                + "Тобто вухо саме розкладає звук на частоти — як цей застосунок! Кожна волоскова клітина «слухає» свою "
                + "частоту. Свисніть: засвітиться одне місце. Скажіть «ш-ш-ш»: засвітиться біля входу. Низький гул — у глибині. "
                + "З віком першими зношуються клітини біля входу, тому слабшає чутність високих звуків.";
    }

    private String lessonString() {
        ensureLive(0);
        stopBuffer();
        viz2d.showLesson(string());
        LinearLayout row = dynRow();
        dynButton(row, "🎙 Мікрофон", new View.OnClickListener() {
            @Override public void onClick(View v) { chkPlay.setChecked(false); select(R.id.spinSource, 0); }
        });
        dynButton(row, "🎹 Демо: пилка", new View.OnClickListener() {
            @Override public void onClick(View v) {
                harmF0 = 196;
                applyPreset(PRESETS[1]);
                select(R.id.spinSource, 1);
            }
        });
        dynButton(row, "🎹 Демо: кларнет", new View.OnClickListener() {
            @Override public void onClick(View v) {
                harmF0 = 196;
                applyPreset(PRESETS[3]);
                select(R.id.spinSource, 1);
            }
        });
        return "Струна, затиснута з двох кінців, може коливатися лише так, щоб на кінцях були вузли: цілою дугою (1×), "
                + "двома половинками (2×), трьома третинами (3×)… Кожен такий спосіб — мода, і звучить вона на кратній "
                + "частоті. Звідси гармоніки!\n\n"
                + "Справжня струна коливається всіма модами одразу — угорі видно їхню суму. Заспівайте ноту: "
                + "застосунок знайде гармоніки вашого голосу й покаже, яка струна звучала б так само. Порівняйте демо "
                + "«пилка» (всі моди) і «кларнет» (лише непарні).";
    }

    private String lessonEnvelope() {
        ensureLive(0);
        stopBuffer();
        viz2d.showLesson(envelope());
        return "Огинаюча — це як змінюється гучність протягом одного звуку. Її описують чотирма фазами:\n"
                + "A (attack) — наростання, D (decay) — спад після піку, S (sustain) — витримка, R (release) — згасання.\n\n"
                + "Ляскніть у долоні, смикніть струну, вдарте ложкою по чашці, а потім протягніть «а-а-а» чи заграйте "
                + "на дудочці. Навіть на однаковій ноті ми розрізняємо інструменти саме за огинаючою: піаніно — удар і "
                + "згасання, скрипка — плавний початок і довга витримка.";
    }

    private String lessonChladni() {
        ensureLive(0);
        stopBuffer();
        final ChladniLesson ch = chladni();
        ch.live = true;
        viz2d.showLesson(ch);
        dynSpinner("Що «грає» на пластину", new String[]{"Мікрофон: свистіть або співайте", "Генератор: повзунок частоти"}, 0,
                new OnPick() {
                    @Override public void pick(int i) { ch.live = i == 0; viz2d.invalidate(); }
                });
        dynSlider("Частота генератора: %.0f Гц", "chladniHz", 1000, 450, new Value() {
            @Override public float of(int p) { return (float) (100 * Math.pow(40, p / 1000.0)); }
        }, new OnPick() {
            @Override public void pick(int p) {
                ch.driveHz = (float) (100 * Math.pow(40, p / 1000.0));
                if (SignalGenerator.waveform == SignalGenerator.Waveform.SINE) SignalGenerator.frequency = ch.driveHz;
            }
        });
        final Button play = dynButton(dyn, "▶ Озвучити тон", null);
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !chkPlay.isChecked();
                SignalGenerator.waveform = SignalGenerator.Waveform.SINE;
                select(R.id.spinWave, 0);
                SignalGenerator.frequency = ch.driveHz;
                chkPlay.setChecked(on);
                play.setText(on ? "■ Тиша" : "▶ Озвучити тон");
            }
        });
        return "У XVIII столітті Ернст Хладні сипав пісок на металеву пластину і водив по краю смичком. Пластина "
                + "коливалася, і пісок зістрибував з місць, що рухаються, збираючись на нерухомих лініях — вузлах.\n\n"
                + "Кожна частота резонансу дає свій візерунок. Свистіть вище й нижче або рухайте повзунок: що вища "
                + "частота, то більше ліній і дрібніший малюнок. Ті самі вузли й пучності, що у струни, тільки у двох вимірах. "
                + "Так перевіряють деки скрипок і гітар.";
    }

    private String lessonInterference() {
        if (interferenceLesson == null) interferenceLesson = new InterferenceLesson(density());
        final InterferenceLesson il = interferenceLesson;
        stopBuffer();
        viz2d.showLesson(il);
        dynSlider("Частота: %.0f Гц", "interfHz", 1000, 600, new Value() {
            @Override public float of(int p) { return (float) (100 * Math.pow(20, p / 1000.0)); }
        }, new OnPick() {
            @Override public void pick(int p) { il.freqHz = (float) (100 * Math.pow(20, p / 1000.0)); }
        });
        LinearLayout row = dynRow();
        final Button phase = dynButton(row, "Протифаза: ні", null);
        phase.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                il.antiPhase = !il.antiPhase;
                phase.setText(il.antiPhase ? "Протифаза: так" : "Протифаза: ні");
            }
        });
        final Button view = dynButton(row, "Показати гучність", null);
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                il.loudnessView = !il.loudnessView;
                view.setText(il.loudnessView ? "Показати хвилю" : "Показати гучність");
            }
        });
        return "Два динаміки грають одну ноту. Де гребінь однієї хвилі зустрічає гребінь іншої — звук підсилюється, "
                + "де гребінь зустрічає западину — гасне. Виходять смуги гучних і тихих місць.\n\n"
                + "Перетягніть динаміки пальцем, змініть частоту: що вища нота (коротша хвиля), то частіші смуги. "
                + "Увімкніть «протифазу» — посередині між динаміками стане тихо. На цьому принципі працює шумоподавлення "
                + "в навушниках: мікрофон чує шум, а динамік грає його ж у протифазі.";
    }

    private String lessonEqualizer() {
        if (equalizerLesson == null) equalizerLesson = new EqualizerLesson(density());
        ensureLive(1);
        SignalGenerator.eqOn = true;
        SignalGenerator.eqVersion++;
        viz2d.showLesson(equalizerLesson);
        final float[][] sources = new float[1][];
        dynSpinner("Що звучить", new String[]{"Музика (акорди, бас, тарілки)", "Рожевий шум", "Ваш запис (останні 4 с)"}, 0,
                new OnPick() {
                    @Override public void pick(int i) {
                        if (i == 0) sources[0] = EqualizerLesson.music(48000);
                        else if (i == 1) {
                            float[] n = new float[48000 * 4];
                            SignalGenerator g = new SignalGenerator();
                            SignalGenerator.Waveform keep = SignalGenerator.waveform;
                            SignalGenerator.waveform = SignalGenerator.Waveform.PINK_NOISE;
                            float lvl = SignalGenerator.level;
                            SignalGenerator.level = 0.5f;
                            boolean eq = SignalGenerator.eqOn;
                            SignalGenerator.eqOn = false;
                            g.fill(n, n.length, 48000);
                            SignalGenerator.eqOn = eq;
                            SignalGenerator.level = lvl;
                            SignalGenerator.waveform = keep;
                            sources[0] = n;
                        } else {
                            AudioEngine.Tape h = engine.captureHistory();
                            if (h == null) { toast("Історія порожня"); return; }
                            int n = Math.min(h.pcm.length, h.rate * 4);
                            float[] x = new float[n];
                            for (int k = 0; k < n; k++) x[k] = h.pcm[h.pcm.length - n + k] / 32768f;
                            LessonKit.normalize(x, 0.5f);
                            sources[0] = x;
                        }
                        if (chkPlay.isChecked()) playBuffer(sources[0], true);
                    }
                });
        LinearLayout row = dynRow();
        for (int i = 0; i < 3; i++) {
            final int p = i;
            dynButton(row, EqualizerLesson.PRESETS[i], new View.OnClickListener() {
                @Override public void onClick(View v) { EqualizerLesson.setGains(EqualizerLesson.PRESET_GAINS[p]); viz2d.invalidate(); }
            });
        }
        LinearLayout row2 = dynRow();
        for (int i = 3; i < EqualizerLesson.PRESETS.length; i++) {
            final int p = i;
            dynButton(row2, EqualizerLesson.PRESETS[i], new View.OnClickListener() {
                @Override public void onClick(View v) { EqualizerLesson.setGains(EqualizerLesson.PRESET_GAINS[p]); viz2d.invalidate(); }
            });
        }
        final Button play = dynButton(dyn, "▶ Слухати", null);
        play.setTextColor(0xFF00F0FF);
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (chkPlay.isChecked()) { stopBuffer(); play.setText("▶ Слухати"); return; }
                if (sources[0] == null) sources[0] = EqualizerLesson.music(48000);
                playBuffer(sources[0], true);
                play.setText("■ Тиша");
            }
        });
        return "Еквалайзер підсилює або послаблює окремі смуги частот — як ручки «бас» і «високі» на колонці, "
                + "тільки їх десять. Кожен кружечок — фільтр на одній октаві.\n\n"
                + "Увімкніть музику й потягніть кружечки: синя заливка — спектр того, що звучить, жовта лінія — "
                + "що робить еквалайзер. Спробуйте «Телефон»: лишається тільки середина, і звук стає «плоским». "
                + "Приберіть 2–4 кГц — голос стане далеким; підніміть — зблизиться. Так звукорежисери «розставляють» "
                + "інструменти в міксі.";
    }

    private String lessonLoudness() {
        ensureLive(0);
        stopBuffer();
        viz2d.showLesson(loudness());
        dynButton(dyn, "▶ Почути: 50 Гц, 1 кГц і 4 кГц з однаковою силою", new View.OnClickListener() {
            @Override public void onClick(View v) {
                int rate = 48000;
                float[] pcm = new float[rate * 4];
                float[] f = {50, 1000, 4000};
                for (int i = 0; i < 3; i++) LessonKit.addTone(pcm, i * rate * 4 / 3, rate, f[i], 0.6f, rate, rate / 50);
                playBuffer(pcm, false);
            }
        });
        return "Мікрофон чесно міряє тиск, а вухо — ні. Ми найкраще чуємо 2–5 кГц (там плач дитини й шипіння змії), "
                + "а бас і дуже високі звуки — значно гірше. Тихий бас просто «зникає».\n\n"
                + "Лінії на графіку — криві рівної гучності: усі точки однієї лінії здаються вуху однаково гучними. "
                + "Рожева — поріг чутності, нижче неї звук не чути взагалі. Стовпчики — ваш звук зараз, колір — наскільки "
                + "гучно його чує вухо (у фонах).\n\n"
                + "Тому гучномовець «тонкоком» додає басу на малій гучності, а сирени пищать на 2–4 кГц.";
    }

    private void runExperiment(int n) {
        if (currentTape != null) closeTape();
        ((CheckBox) findViewById(R.id.chkHints)).setChecked(true);
        findViewById(R.id.intervalControls).setVisibility(n == 9 ? View.VISIBLE : View.GONE);
        findViewById(R.id.samplingControls).setVisibility(n == 12 ? View.VISIBLE : View.GONE);
        findViewById(R.id.harmonicControls).setVisibility(n == 3 || n == 4 ? View.VISIBLE : View.GONE);
        dyn.removeAllViews();
        String text;
        switch (n) {
            case 13: text = lessonDraw(); break;
            case 14: text = lessonModem(); break;
            case 15: text = lessonBottle(); break;
            case 16: text = lessonVoiceFx(); break;
            case 17: text = lessonRoom(); break;
            case 18: text = lessonSpeed(); break;
            case 19: text = lessonMorse(); break;
            case 20: text = lessonEar(); break;
            case 21: text = lessonRhythm(); break;
            case 23: text = lessonCochlea(); break;
            case 24: text = lessonString(); break;
            case 25: text = lessonEnvelope(); break;
            case 26: text = lessonChladni(); break;
            case 27: text = lessonInterference(); break;
            case 28: text = lessonEqualizer(); break;
            case 29: text = lessonLoudness(); break;
            case 30: text = lessonVowels(); break;
            case 31: text = lessonRange(); break;
            case 32: case 33: case 34: case 35: case 36: case 37: case 38: case 39: text = lessonSimple(n); break;
            case 40: text = lessonParrot(); break;
            case 49: text = lessonDuel(); break;
            case 50: text = lessonTemperature(); break;
            case 51: text = lessonWind(); break;
            case 52: text = lessonMusicPause(); break;
            case 53: text = lessonTug(); break;
            case 41: case 42: case 43: case 44: case 45: case 46: case 47: case 48: text = lessonGame(n); break;
            case 7:
                showLessonMode(MODES_2D[MODE_PITCH]);
                select(R.id.spinSource, 0);
                chkPlay.setChecked(false);
                text = "Співайте, наспівуйте або просто говоріть. Лінія показує висоту голосу в часі поверх нотних ліній "
                        + "(до, ре, мі…). Зелена — точно в ноту, жовта — трохи мимо, рожева — між нотами.\n\n"
                        + "Спробуйте: тримайте одну ноту рівно (пряма лінія); заспівайте гаму; зробіть вібрато (хвиля). "
                        + "У звичайній мові висота постійно «їде» — це інтонація: питання закінчується вгору.";
                break;
            case 8:
                showLessonMode(MODES_2D[MODE_CHROMA]);
                select(R.id.spinSource, 0);
                chkPlay.setChecked(false);
                text = "Нотне коло «згортає» всі октави: до будь-якої висоти потрапляє в сектор «до». "
                        + "Яскраві сектори — ноти, що звучать зараз.\n\n"
                        + "Заграйте акорд на гітарі чи піаніно (або увімкніть пісню поряд): ноти з'єднаються фігурою, "
                        + "а вгорі з'явиться назва — «до мажор», «ля мінор»… Мажор і мінор — це трикутники різної форми.";
                break;
            case 9:
                SignalGenerator.waveform = SignalGenerator.Waveform.INTERVAL;
                SignalGenerator.frequency = 220f;
                select(R.id.spinWave, WAVE_INTERVAL);
                showLessonMode(MODES_2D[MODE_INTERVALS]);
                select(R.id.spinSource, 1);
                chkPlay.setChecked(true);
                text = "Дві ноти звучать разом. Вгорі — фігура Ліссажу: по горизонталі нижня нота, по вертикалі верхня. "
                        + "Чим простіше відношення частот (октава 2:1, квінта 3:2), тим простіша фігура і тим «злитніше» "
                        + "звучить. Складні відношення (мала секунда 16:15, тритон 45:32) дають заплутану фігуру і напругу.\n\n"
                        + "Оберіть «Унісон» і додайте розстроювання: почуєте «вау-вау» — биття. Так гітаристи настроюють струни: "
                        + "биття зникає, коли ноти збіглися.";
                break;
            case 10:
                showLessonMode(MODES_2D[MODE_AIR]);
                select(R.id.spinSource, 0);
                chkPlay.setChecked(false);
                text = "Звук — це хвиля тиску в повітрі. Молекули не летять від вас до телефона: вони коливаються на місці, "
                        + "створюючи згущення і розрідження, що біжать зі швидкістю 343 м/с.\n\n"
                        + "Свисніть або заспівайте: внизу видно довжину хвилі. Низька нота (100 Гц) — 3,4 м, "
                        + "високий свист (3 кГц) — лише 11 см. Тому басом легко «пробити» стіну, а високі звуки глушаться.";
                break;
            case 11:
                showLessonMode(MODES_2D[MODE_ECHO]);
                select(R.id.spinSource, 0);
                chkPlay.setChecked(false);
                text = "Після різкого звуку кімната ще деякий час «дзвенить» — звук відбивається від стін. "
                        + "Час, за який він стихає на 60 дБ (у мільйон разів), називається RT60.\n\n"
                        + "Будьте в тиші і один раз голосно ляскніть у долоні. Порівняйте кімнату з килимом, кухню, ванну "
                        + "і сходи: м'які речі поглинають звук, тверді стіни — відбивають.";
                break;
            case 12:
                showLessonMode(MODES_2D[MODE_SAMPLING]);
                chkPlay.setChecked(false);
                text = "Телефон не записує хвилю цілком — лише вимірює її багато разів на секунду (точки). "
                        + "За точками він потім відновлює звук.\n\n"
                        + "Поки частота сигналу менша за половину частоти дискретизації (межа Найквіста), відновлення "
                        + "правильне. Підніміть частоту вище — точки «зіграють» зовсім іншу, нижчу хвилю. Це аліасинг. "
                        + "Тому звук пишуть із частотою 44,1–48 кГц: це вдвічі більше за межу слуху (20 кГц).";
                break;
            case 1:
                useMode(0);
                select(R.id.spinSource, 0);
                chkPlay.setChecked(false);
                text = "Свисніть рівно. На спектрі з'явиться один гострий пік — це чистий тон, майже синусоїда.\n\n"
                        + "Свистіть вище й нижче: пік їде вправо (вища частота) і вліво. Частота вдвічі вища — це октава: "
                        + "та сама нота, тільки вище.\n\nКартка внизу графіка називає частоту й ноту.";
                break;
            case 2:
                useMode(0);
                select(R.id.spinSource, 0);
                chkPlay.setChecked(false);
                text = "Протягніть «а-а-а» на одній ноті. Тепер піків багато, і вони стоять на однакових відстанях: "
                        + "f0, 2×f0, 3×f0… Це гармоніки, вони позначені трикутниками.\n\n"
                        + "Висоту ми чуємо за першою (f0), а від сили решти залежить тембр.\n\n"
                        + "Не змінюючи ноти, перейдіть на «і» чи «у»: піки лишаються на місцях, але змінюється, які гучніші "
                        + "(форманти). Саме так ми розрізняємо голосні!";
                break;
            case 3:
                applyPreset(PRESETS[1]);
                showLessonMode(MODES_2D[MODE_FOURIER]);
                select(R.id.spinSource, 1);
                chkPlay.setChecked(true);
                text = "Будь-який повторюваний звук можна скласти з синусоїд — це ряд Фур'є.\n\n"
                        + "Кожне коло обертається зі своєю частотою: 1×f0, 2×f0, 3×f0… Його розмір — гучність гармоніки. "
                        + "Кінчик останнього кола малює хвилю праворуч. Зараз звучить «пилка».\n\n"
                        + "Рухайте повзунки гармонік нижче або тисніть «Меандр», «Кларнет» — дивіться, як змінюються кола, "
                        + "хвиля і звук. Оберіть джерело «Мікрофон» і заспівайте — кола покажуть ваш голос.";
                break;
            case 4:
                applyPreset(PRESETS[2]);
                useMode(0);
                select(R.id.spinSource, 1);
                chkPlay.setChecked(true);
                text = "Меандр (прямокутна хвиля) складається лише з непарних гармонік: 1×, 3×, 5×, 7×… "
                        + "з гучністю 1, 1/3, 1/5…\n\nПодивіться на трикутники: парних немає. Тому меандр звучить "
                        + "«порожнисто», як кларнет.\n\nНатисніть «Пилка» в конструкторі — з'являться всі гармоніки, "
                        + "і звук стане яскравішим.";
                break;
            case 5:
                SignalGenerator.waveform = SignalGenerator.Waveform.PINK_NOISE;
                select(R.id.spinWave, 5);
                useMode(3);
                select(R.id.spinSource, 1);
                chkPlay.setChecked(true);
                text = "Рожевий шум: енергія розмазана по всіх частотах, тому спектрограма — суцільна заливка, а не лінії. "
                        + "Чистий тон дав би одну тонку вертикальну лінію.\n\n"
                        + "Оберіть джерело «Мікрофон» і скажіть «ш-ш-ш» — це теж шум, але переважно високий (праворуч), "
                        + "а «ф-ф-ф» тихіший і ширший.";
                break;
            default:
                SignalGenerator.waveform = SignalGenerator.Waveform.LOG_SWEEP;
                select(R.id.spinWave, 6);
                useMode(3);
                select(R.id.spinSource, 1);
                chkPlay.setChecked(true);
                text = "Частота плавно росте від 20 Гц до 20 кГц за 8 секунд — на спектрограмі це діагональ.\n\n"
                        + "Слухайте: де звук зникає вгорі, там межа вашого слуху (у молодих 17–20 кГц, з віком нижче). "
                        + "Найнижчі частоти не відтворює сам динамік телефона.\n\nЗробіть гучність помірною!";
                break;
        }
        ((Button) findViewById(R.id.btnListen)).setText(chkPlay.isChecked() ? "■ Тиша" : "▶ Слухати");
        txtLesson.setText(micNote(n) + "\n\n" + text);
        if (!engine.isRunning()) {
            resumeOnStart = true;
            startEngine();
        }
    }

    // ---------------------------------------------------------------- time machine & files

    private boolean seekDragging;

    private void setupTimeline() {
        seekTape.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                if (fromUser) engine.seek(p * AudioEngine.HOP);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seekDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { seekDragging = false; }
        });
        btnTapePlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean p = !engine.isPlaying();
                engine.setPlaying(p);
                btnTapePlay.setText(p ? "❚❚" : "▶");
            }
        });
        findViewById(R.id.btnTapeBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { step(-1); }
        });
        findViewById(R.id.btnTapeFwd).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { step(1); }
        });
        btnTapeSpeed.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int i = 0;
                for (int k = 0; k < SPEEDS.length; k++) if (SPEEDS[k] == engine.getSpeed()) i = k;
                float sp = SPEEDS[(i + 1) % SPEEDS.length];
                engine.setSpeed(sp);
                btnTapeSpeed.setText(sp == 1f ? "1×" : sp == 0.5f ? "½×" : "¼×");
                if (sp < 1f) toast("Сповільнене відтворення: звук нижчий за тоном, аналіз — справжній");
            }
        });
        findViewById(R.id.btnTapeSave).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveTape(); }
        });
        findViewById(R.id.btnTapeClose).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeTape(); }
        });
    }

    private void step(int frames) {
        engine.setPlaying(false);
        btnTapePlay.setText("▶");
        engine.seek(engine.getPlayhead() + frames * AudioEngine.HOP);
    }

    @Override
    public void onSeek(int samplePosition) {
        engine.seek(samplePosition);
    }

    private void ensureEngine() {
        if (engine.isRunning()) return;
        engine.start(hasMic());
        resumeOnStart = true;
        onEngineStateChanged();
    }

    private void updateTapeAnalysis() {
        if (viz2d == null) return;
        float gainDb = (float) (20 * Math.log10(engine.getGainLinear()));
        viz2d.setTapeAnalysis(engine.getFftSize(), engine.getWindow(), gainDb + engine.getCalibrationDb());
    }

    private void enterTimeMachine() {
        if (currentTape != null) {
            toast("Машина часу вже відкрита");
            return;
        }
        AudioEngine.Tape t = engine.captureHistory();
        if (t == null || t.pcm.length < AudioEngine.HOP * 4) {
            toast("Історія ще порожня — запустіть аналіз і зачекайте кілька секунд");
            return;
        }
        if (engine.isRecording()) finishRecording();
        openTape(t, t.pcm.length);
        toast("Машина часу: " + Math.round(t.seconds()) + " с історії. Тягніть повзунок або торкніться спектрограми");
    }

    private void openTape(AudioEngine.Tape t, int position) {
        currentTape = t;
        savedPlayhead = position;
        ensureEngine();
        engine.setSpeed(1f);
        btnTapeSpeed.setText("1×");
        engine.openTape(t, position);
        txtTapeName.setText((t.history ? "⟲ " : "♪ ") + t.name + "  ·  " + t.rate + " Гц");
        seekTape.setMax(Math.max(1, t.pcm.length / AudioEngine.HOP));
        seekTape.setProgress(position / AudioEngine.HOP);
        timeline.setVisibility(View.VISIBLE);
        engine.setFrozen(false);
        btnFreeze.setText("Пауза");
        viz2d.setTape(t);
        updateTapeAnalysis();
    }

    private void closeTape() {
        engine.closeTape();
        currentTape = null;
        timeline.setVisibility(View.GONE);
        viz2d.setTape(null);
        if (!resumeOnStart && engine.isRunning()) stopEngine();
    }

    private void pickFile() {
        if (loadingFile) { toast("Файл ще завантажується…"); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*", "application/ogg"});
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (Exception e) {
            toast("Не знайдено програми для вибору файлів");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            loadFile(data.getData());
        }
    }

    void loadFile(final Uri uri) {
        loadingFile = true;
        toast("Відкриваю файл…");
        timeline.setVisibility(View.VISIBLE);
        txtTapeName.setText("Завантаження…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final AudioFileLoader.Result r = AudioFileLoader.load(MainActivity.this, uri,
                            new AudioFileLoader.Progress() {
                                @Override public void onProgress(final int percent) {
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() { txtTapeName.setText("Завантаження… " + percent + " %"); }
                                    });
                                }
                            });
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            loadingFile = false;
                            if (currentTape != null) engine.closeTape();
                            openTape(r.tape, 0);
                            String msg = r.tape.name + ": " + VisualizerView.formatTime(r.tape.seconds());
                            if (r.truncated) msg += " (обрізано до " + AudioFileLoader.MAX_SECONDS / 60 + " хв)";
                            toast(msg);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            loadingFile = false;
                            if (currentTape == null) timeline.setVisibility(View.GONE);
                            else txtTapeName.setText((currentTape.history ? "⟲ " : "♪ ") + currentTape.name);
                            toast(e.getMessage() != null ? e.getMessage() : "Не вдалося відкрити файл");
                        }
                    });
                } catch (final OutOfMemoryError e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            loadingFile = false;
                            if (currentTape == null) timeline.setVisibility(View.GONE);
                            toast("Файл завеликий для пам'яті телефона");
                        }
                    });
                }
            }
        }, "FileLoader").start();
    }

    private void saveTape() {
        final AudioEngine.Tape t = currentTape;
        if (t == null) return;
        toast("Зберігаю WAV…");
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    msg = "WAV збережено: " + Exporter.saveTape(MainActivity.this, t);
                } catch (Exception e) {
                    msg = "Помилка збереження: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() { toast(m); }
                });
            }
        }, "TapeSaver").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
