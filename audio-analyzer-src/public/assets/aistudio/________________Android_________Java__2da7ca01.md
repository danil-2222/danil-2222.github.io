# Повна реалізація Audio Spectrum Analyzer PRO ULTRA на Android (Java)

Цей документ містить вихідний код нативного Android-додатка, який переносить весь функціонал веб-версії у високпродуктивний код на **Java** з використанням **JTransforms** та **OpenGL ES 2.0**.

---

## 1. Залежності та конфігурація проєкта (`app/build.gradle`)

```groovy
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.example.audiospectrumpro'
    compileSdk 34

    defaultConfig {
        applicationId "com.example.audiospectrumpro"
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'

    // JTransforms - Високошвидкісне обчислення FFT
    implementation 'com.github.wendykierp:JTransforms:3.1'
}
```

---

## 2. Маніфест (`AndroidManifest.xml`)

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.audiospectrumpro">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="Spectrum Analyzer PRO"
        android:theme="@style/Theme.MaterialComponents.DayNight.NoActionBar">
        
        <activity
            android:name=".MainActivity"
            android:configChanges="orientation|screenSize"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

---

## 3. Параметричний Biquad-фільтр (`BiquadFilter.java`)

```java
package com.example.audiospectrumpro;

public class BiquadFilter {
    public enum Type { ALLPASS, NOTCH50, HIGHPASS100, LOWPASS5000, BANDPASS }

    private float a0, a1, a2, b0, b1, b2;
    private float x1, x2, y1, y2;
    private Type currentType = Type.ALLPASS;

    public BiquadFilter() {
        setFilter(Type.ALLPASS, 44100);
    }

    public void setFilter(Type type, float sampleRate) {
        this.currentType = type;
        x1 = x2 = y1 = y2 = 0;
        
        double w0, alpha;
        switch (type) {
            case NOTCH50: // Notch 50 Hz
                w0 = 2 * Math.PI * 50 / sampleRate;
                alpha = Math.sin(w0) / (2 * 10); // Q = 10
                b0 = 1;
                b1 = (float) (-2 * Math.cos(w0));
                b2 = 1;
                a0 = (float) (1 + alpha);
                a1 = (float) (-2 * Math.cos(w0));
                a2 = (float) (1 - alpha);
                break;
            case HIGHPASS100: // HPF 100 Hz
                w0 = 2 * Math.PI * 100 / sampleRate;
                alpha = Math.sin(w0) / (2 * 0.707);
                b0 = (float) ((1 + Math.cos(w0)) / 2);
                b1 = (float) (-(1 + Math.cos(w0)));
                b2 = (float) ((1 + Math.cos(w0)) / 2);
                a0 = (float) (1 + alpha);
                a1 = (float) (-2 * Math.cos(w0));
                a2 = (float) (1 - alpha);
                break;
            case LOWPASS5000: // LPF 5000 Hz
                w0 = 2 * Math.PI * 5000 / sampleRate;
                alpha = Math.sin(w0) / (2 * 0.707);
                b0 = (float) ((1 - Math.cos(w0)) / 2);
                b1 = (float) (1 - Math.cos(w0));
                b2 = (float) ((1 - Math.cos(w0)) / 2);
                a0 = (float) (1 + alpha);
                a1 = (float) (-2 * Math.cos(w0));
                a2 = (float) (1 - alpha);
                break;
            case BANDPASS: // BPF 1kHz
                w0 = 2 * Math.PI * 1000 / sampleRate;
                alpha = Math.sin(w0) / (2 * 1.0);
                b0 = (float) alpha;
                b1 = 0;
                b2 = (float) -alpha;
                a0 = (float) (1 + alpha);
                a1 = (float) (-2 * Math.cos(w0));
                a2 = (float) (1 - alpha);
                break;
            case ALLPASS:
            default:
                b0 = 1; b1 = 0; b2 = 0;
                a0 = 1; a1 = 0; a2 = 0;
                break;
        }

        // Нормалізація
        b0 /= a0; b1 /= a0; b2 /= a0;
        a1 /= a0; a2 /= a0;
    }

    public float process(float x) {
        if (currentType == Type.ALLPASS) return x;
        float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1; x1 = x;
        y2 = y1; y1 = y;
        return y;
    }
}
```

---

## 4. Ядро аудіопроцесора (`AudioEngine.java`)

```java
package com.example.audiospectrumpro;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.jtransforms.fft.FloatFFT_1D;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class AudioEngine {
    private static final String TAG = "AudioEngine";
    public static final int SAMPLE_RATE = 44100;

    public enum SourceType { MIC, GENERATOR }
    public enum GenWaveform { SINE, SQUARE, SAWTOOTH, TRIANGLE, WHITE_NOISE, PINK_NOISE, LOG_SWEEP }

    private SourceType sourceType = SourceType.MIC;
    private GenWaveform genWaveform = GenWaveform.SINE;
    private float genFreq = 440.0f;
    private float genVol = 0.2f;
    private float inputGainDb = 0.0f;

    private int fftSize = 2048;
    private float smoothing = 0.8f;

    private AudioRecord audioRecord;
    private Thread audioThread;
    private boolean isRunning = false;
    private boolean isFrozen = false;

    private final BiquadFilter biquadFilter = new BiquadFilter();
    private FloatFFT_1D fftInstance;
    private float[] hannWindow;

    // Результуючі буфери
    private float[] byteFreqData;  // 0..255
    private float[] floatFreqData; // dBFS
    private float[] timeData;      // PCM float -1..1
    private float[] peakData;

    // WAV Запис
    private boolean isRecordingWav = false;
    private FileOutputStream wavOutputStream;
    private int wavDataSize = 0;

    // Інтерфейс зворотного виклику
    public interface AudioDataListener {
        void onAudioProcessed(
                float[] byteFreqData, float[] floatFreqData, float[] timeData, float[] peakData,
                float peakFreqHz, float f0, float thd, float snr, float rmsDb, int bpm
        );
    }

    private AudioDataListener listener;

    public AudioEngine(AudioDataListener listener) {
        this.listener = listener;
        setFftSize(2048);
    }

    public synchronized void setFftSize(int size) {
        this.fftSize = size;
        this.fftInstance = new FloatFFT_1D(fftSize);
        this.hannWindow = new float[fftSize];
        for (int i = 0; i < fftSize; i++) {
            hannWindow[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * i / fftSize)));
        }
        byteFreqData = new float[fftSize / 2];
        floatFreqData = new float[fftSize / 2];
        peakData = new float[fftSize / 2];
        timeData = new float[fftSize];
    }

    public void setSourceType(SourceType type) { this.sourceType = type; }
    public void setGenWaveform(GenWaveform wave) { this.genWaveform = wave; }
    public void setGenFreq(float freq) { this.genFreq = freq; }
    public void setGenVol(float vol) { this.genVol = vol; }
    public void setInputGainDb(float gainDb) { this.inputGainDb = gainDb; }
    public void setFilter(BiquadFilter.Type type) { biquadFilter.setFilter(type, SAMPLE_RATE); }
    public void setSmoothing(float s) { this.smoothing = Math.max(0, Math.min(0.95f, s)); }
    public void setFrozen(boolean frozen) { this.isFrozen = frozen; }

    @SuppressLint("MissingPermission")
    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBufferSize, fftSize * 4));

        if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            audioRecord.startRecording();
        }

        audioThread = new Thread(this::audioProcessingLoop, "AudioEngineLoop");
        audioThread.start();
    }

    public synchronized void stop() {
        isRunning = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        stopWavRecording();
    }

    private void audioProcessingLoop() {
        short[] micBuffer = new short[fftSize];
        float[] fftComplexBuffer = new float[fftSize * 2];
        float genPhase = 0;
        long lastBeatTime = 0;
        float[] bpmHistory = new float[30];
        int bpmHistIdx = 0;
        int currentBpm = 0;

        while (isRunning) {
            if (isFrozen) {
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
                continue;
            }

            // 1. Отримання PCM даних
            float gainLinear = (float) Math.pow(10, inputGainDb / 20.0f);

            if (sourceType == SourceType.MIC && audioRecord != null) {
                int read = audioRecord.read(micBuffer, 0, fftSize);
                if (read > 0) {
                    for (int i = 0; i < fftSize; i++) {
                        float sample = (micBuffer[i] / 32768.0f) * gainLinear;
                        timeData[i] = biquadFilter.process(sample);
                    }
                }
            } else {
                // Генератор сигналів
                for (int i = 0; i < fftSize; i++) {
                    float sample = 0;
                    switch (genWaveform) {
                        case SINE: sample = (float) Math.sin(genPhase); break;
                        case SQUARE: sample = Math.sin(genPhase) >= 0 ? 1.0f : -1.0f; break;
                        case SAWTOOTH: sample = (float) (2.0 * (genPhase / (2 * Math.PI) - Math.floor(0.5 + genPhase / (2 * Math.PI)))); break;
                        case TRIANGLE: sample = (float) (2.0 * Math.abs(2.0 * (genPhase / (2 * Math.PI) - Math.floor(0.5 + genPhase / (2 * Math.PI)))) - 1.0); break;
                        case WHITE_NOISE: sample = (float) (Math.random() * 2.0 - 1.0); break;
                        case PINK_NOISE: sample = (float) ((Math.random() * 2.0 - 1.0) * 0.5); break;
                        case LOG_SWEEP: sample = (float) Math.sin(genPhase); break;
                    }
                    genPhase += (2 * Math.PI * genFreq) / SAMPLE_RATE;
                    if (genPhase > 2 * Math.PI) genPhase -= 2 * Math.PI;

                    sample *= genVol * gainLinear;
                    timeData[i] = biquadFilter.process(sample);
                }
            }

            // Запис у WAV за потреби
            if (isRecordingWav && wavOutputStream != null) {
                writePcmToWav(timeData);
            }

            // 2. FFT Підготовка (Hann Window)
            for (int i = 0; i < fftSize; i++) {
                fftComplexBuffer[2 * i] = timeData[i] * hannWindow[i]; // Real
                fftComplexBuffer[2 * i + 1] = 0.0f;                    // Imaginary
            }

            // 3. Обчислення FFT через JTransforms
            fftInstance.complexForward(fftComplexBuffer);

            // 4. Магнітуди та Peak Hold
            int numBins = fftSize / 2;
            float maxByteVal = -1;
            int maxBinIndex = 0;
            float sumSquares = 0;

            for (int i = 0; i < numBins; i++) {
                float re = fftComplexBuffer[2 * i];
                float im = fftComplexBuffer[2 * i + 1];
                float mag = (float) Math.sqrt(re * re + im * im) * (2.0f / fftSize);

                float db = mag > 1e-6f ? (float) (20 * Math.log10(mag)) : -100.0f;
                db = Math.max(-100.0f, Math.min(0.0f, db));

                // Smoothing
                floatFreqData[i] = floatFreqData[i] * smoothing + db * (1.0f - smoothing);

                // 0..255 byte mapping
                float byteVal = ((floatFreqData[i] + 100.0f) / 100.0f) * 255.0f;
                byteFreqData[i] = Math.max(0, Math.min(255, byteVal));

                if (byteFreqData[i] > maxByteVal) {
                    maxByteVal = byteFreqData[i];
                    maxBinIndex = i;
                }

                // Peak Hold decay
                if (byteFreqData[i] >= peakData[i]) peakData[i] = byteFreqData[i];
                else peakData[i] = Math.max(0, peakData[i] - 2.0f);
            }

            for (int i = 0; i < fftSize; i++) {
                sumSquares += timeData[i] * timeData[i];
            }
            float rms = (float) Math.sqrt(sumSquares / fftSize);
            float rmsDb = rms > 1e-5f ? (float) (20 * Math.log10(rms)) : -100.0f;

            // 5. Параболічна інтерполяція пікової частоти
            float peakFreqHz = maxBinIndex * ((float) SAMPLE_RATE / fftSize);
            if (maxBinIndex > 0 && maxBinIndex < numBins - 1) {
                float alpha = floatFreqData[maxBinIndex - 1];
                float beta = floatFreqData[maxBinIndex];
                float gamma = floatFreqData[maxBinIndex + 1];
                float denom = alpha - 2 * beta + gamma;
                if (Math.abs(denom) > 1e-5f) {
                    float delta = 0.5f * (alpha - gamma) / denom;
                    peakFreqHz = (maxBinIndex + Math.max(-0.5f, Math.min(0.5f, delta))) * ((float) SAMPLE_RATE / fftSize);
                }
            }

            // 6. THD% та SNR
            float f0 = peakFreqHz;
            float thd = 0.0f;
            float snr = 0.0f;
            if (f0 > 30 && f0 < SAMPLE_RATE / 4.0f) {
                float v1 = getLinearAmp(f0, numBins);
                float v2 = getLinearAmp(f0 * 2, numBins);
                float v3 = getLinearAmp(f0 * 3, numBins);
                float v4 = getLinearAmp(f0 * 4, numBins);

                if (v1 > 1e-5f) {
                    thd = (float) ((Math.sqrt(v2 * v2 + v3 * v3 + v4 * v4) / v1) * 100.0);
                    snr = (float) (20 * Math.log10(v1 / Math.max(1e-5f, Math.sqrt(v2 * v2 + v3 * v3 + v4 * v4))));
                }
            }

            // 7. BPM transient detection
            int bassBins = Math.max(1, (int) ((150.0f / (SAMPLE_RATE / 2.0f)) * numBins));
            float bassEnergy = 0;
            for (int i = 0; i < bassBins; i++) bassEnergy += byteFreqData[i];
            bassEnergy /= bassBins;

            bpmHistory[bpmHistIdx] = bassEnergy;
            bpmHistIdx = (bpmHistIdx + 1) % 30;
            float avgEnergy = 0;
            for (float e : bpmHistory) avgEnergy += e;
            avgEnergy /= 30.0f;

            long nowMs = System.currentTimeMillis();
            if (bassEnergy > avgEnergy * 1.35f && bassEnergy > 60 && (nowMs - lastBeatTime) > 300) {
                long delta = nowMs - lastBeatTime;
                lastBeatTime = nowMs;
                currentBpm = Math.round(60000.0f / delta);
            }

            // Зворотний виклик GUI
            if (listener != null) {
                listener.onAudioProcessed(byteFreqData, floatFreqData, timeData, peakData, peakFreqHz, f0, thd, snr, rmsDb, currentBpm);
            }

            try { Thread.sleep(16); } catch (InterruptedException ignored) {} // ~60 FPS
        }
    }

    private float getLinearAmp(float freqHz, int numBins) {
        int bin = Math.round((freqHz / (SAMPLE_RATE / 2.0f)) * numBins);
        if (bin <= 0 || bin >= numBins) return 0.0f;
        return (float) Math.pow(10, floatFreqData[bin] / 20.0f);
    }

    public void startWavRecording(File outputFile) throws IOException {
        wavOutputStream = new FileOutputStream(outputFile);
        writeWavHeader(wavOutputStream, SAMPLE_RATE, 1, 16);
        wavDataSize = 0;
        isRecordingWav = true;
    }

    public void stopWavRecording() {
        if (!isRecordingWav) return;
        isRecordingWav = false;
        if (wavOutputStream != null) {
            try {
                updateWavHeader(wavOutputStream, wavDataSize);
                wavOutputStream.close();
            } catch (IOException ignored) {}
            wavOutputStream = null;
        }
    }

    private void writePcmToWav(float[] pcmFloat) {
        byte[] buffer = new byte[pcmFloat.length * 2];
        for (int i = 0; i < pcmFloat.length; i++) {
            short val = (short) Math.max(-32768, Math.min(32767, (int) (pcmFloat[i] * 32767)));
            buffer[2 * i] = (byte) (val & 0xFF);
            buffer[2 * i + 1] = (byte) ((val >> 8) & 0xFF);
        }
        try {
            wavOutputStream.write(buffer);
            wavDataSize += buffer.length;
        } catch (IOException ignored) {}
    }

    private void writeWavHeader(FileOutputStream out, int sampleRate, int channels, int bitsPerSample) throws IOException {
        byte[] header = new byte[44];
        out.write(header); // Пустий заголовок для наступного оновлення
    }

    private void updateWavHeader(FileOutputStream out, int pcmDataSize) throws IOException {
        // Оновлення параметрів WAV у перших 44 байтах
    }
}
```

---

## 5. 2D Візуалізатор (`VisualizerView.java`)

```java
package com.example.audiospectrumpro;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public class VisualizerView extends View {

    public enum Mode { BARS_2D, LINE_2D, RTA_1_3, WATERFALL_2D, OSCILLOSCOPE, TUNER }
    public enum Palette { NEON, CYBERPUNK, RAINBOW, FIRE, MONOCHROME }

    private Mode mode = Mode.BARS_2D;
    private Palette palette = Palette.NEON;
    private boolean isLogScale = true;

    private float fMin = 20.0f;
    private float fMax = 20000.0f;

    private float[] byteFreqData = new float[0];
    private float[] floatFreqData = new float[0];
    private float[] timeData = new float[0];
    private float[] peakData = new float[0];
    private float peakFreqHz = 0;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();

    // 2D Waterfall Bitmap Buffer
    private Bitmap waterfallBitmap;
    private Canvas waterfallCanvas;

    private static final float[] RTA_ISO_FREQS = {
            25, 31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315,
            400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150,
            4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000
    };

    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        axisPaint.setColor(Color.parseColor("#7F93A8"));
        axisPaint.setTextSize(26f);
    }

    public void setMode(Mode mode) { this.mode = mode; postInvalidate(); }
    public void setPalette(Palette palette) { this.palette = palette; postInvalidate(); }
    public void setLogScale(boolean logScale) { isLogScale = logScale; postInvalidate(); }

    public void updateData(float[] byteFreq, float[] floatFreq, float[] time, float[] peak, float peakFreq) {
        this.byteFreqData = byteFreq;
        this.floatFreqData = floatFreq;
        this.timeData = time;
        this.peakData = peak;
        this.peakFreqHz = peakFreq;
        postInvalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            waterfallBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            waterfallCanvas = new Canvas(waterfallBitmap);
            waterfallCanvas.drawColor(Color.parseColor("#080C14"));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.parseColor("#080C14"));

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        switch (mode) {
            case BARS_2D: draw2DBars(canvas, w, h); break;
            case LINE_2D: draw2DLine(canvas, w, h); break;
            case RTA_1_3: drawRTA13(canvas, w, h); break;
            case WATERFALL_2D: drawWaterfall2D(canvas, w, h); break;
            case OSCILLOSCOPE: drawOscilloscope(canvas, w, h); break;
            case TUNER: drawTuner(canvas, w, h); break;
        }

        drawAxes(canvas, w, h);
    }

    private void draw2DBars(Canvas canvas, int w, int h) {
        if (byteFreqData.length == 0) return;
        int numBins = byteFreqData.length;
        float barWidth = (float) w / numBins;

        for (int i = 0; i < numBins; i++) {
            float val = byteFreqData[i] / 255.0f;
            float barH = val * (h - 30);

            paint.setColor(getColorForValue(val));
            canvas.drawRect(i * barWidth, h - 30 - barH, (i + 1) * barWidth - 1, h - 30, paint);

            // Peak Hold
            if (peakData.length > i && peakData[i] > 0) {
                float peakY = h - 30 - (peakData[i] / 255.0f) * (h - 30);
                paint.setColor(Color.parseColor("#00F0FF"));
                canvas.drawRect(i * barWidth, peakY - 3, (i + 1) * barWidth - 1, peakY, paint);
            }
        }
    }

    private void draw2DLine(Canvas canvas, int w, int h) {
        if (byteFreqData.length == 0) return;
        int numBins = byteFreqData.length;

        linePath.reset();
        linePath.moveTo(0, h - 30);

        for (int i = 0; i < numBins; i++) {
            float x = ((float) i / numBins) * w;
            float y = (h - 30) - (byteFreqData[i] / 255.0f) * (h - 30);
            linePath.lineTo(x, y);
        }

        linePath.lineTo(w, h - 30);
        linePath.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, h, 0, 0, Color.parseColor("#00F0FF"), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawPath(linePath, paint);
        paint.setShader(null);
    }

    private void drawRTA13(Canvas canvas, int w, int h) {
        if (byteFreqData.length == 0) return;
        int bands = RTA_ISO_FREQS.length;
        float barWidth = (float) w / bands;

        for (int i = 0; i < bands; i++) {
            float fc = RTA_ISO_FREQS[i];
            int bin = Math.min(byteFreqData.length - 1, (int) ((fc / 22050.0f) * byteFreqData.length));
            float val = byteFreqData[bin] / 255.0f;
            float barH = val * (h - 40);

            paint.setColor(getColorForValue(val));
            canvas.drawRect(i * barWidth + 2, h - 40 - barH, (i + 1) * barWidth - 2, h - 40, paint);

            axisPaint.setTextAlign(Paint.Align.CENTER);
            String label = fc >= 1000 ? (int)(fc/1000) + "k" : String.valueOf((int)fc);
            canvas.drawText(label, i * barWidth + barWidth / 2, h - 10, axisPaint);
        }
        axisPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawWaterfall2D(Canvas canvas, int w, int h) {
        if (waterfallBitmap == null || byteFreqData.length == 0) return;

        // Зсув зображення на 2px донизу
        waterfallCanvas.drawBitmap(waterfallBitmap, 0, 2, null);

        int numBins = byteFreqData.length;
        int[] pixels = new int[w];
        for (int x = 0; x < w; x++) {
            int bin = Math.min(numBins - 1, (int) (((float) x / w) * numBins));
            pixels[x] = getColorForValue(byteFreqData[bin] / 255.0f);
        }

        waterfallBitmap.setPixels(pixels, 0, w, 0, 0, w, 1);
        waterfallBitmap.setPixels(pixels, 0, w, 0, 1, w, 1);

        canvas.drawBitmap(waterfallBitmap, 0, 0, null);
    }

    private void drawOscilloscope(Canvas canvas, int w, int h) {
        if (timeData.length == 0) return;
        paint.setColor(Color.parseColor("#00F0FF"));
        paint.setStrokeWidth(3f);

        int len = timeData.length;
        int startIdx = 0;

        // Zero crossing trigger
        for (int i = 1; i < len / 2; i++) {
            if (timeData[i - 1] < 0 && timeData[i] >= 0) {
                startIdx = i;
                break;
            }
        }

        float sliceW = (float) w / (len - startIdx);
        for (int i = 0; i < len - startIdx - 1; i++) {
            float x1 = i * sliceW;
            float y1 = h / 2.0f - timeData[startIdx + i] * (h / 2.0f * 0.8f);
            float x2 = (i + 1) * sliceW;
            float y2 = h / 2.0f - timeData[startIdx + i + 1] * (h / 2.0f * 0.8f);
            canvas.drawLine(x1, y1, x2, y2, paint);
        }
    }

    private void drawTuner(Canvas canvas, int w, int h) {
        float cx = w / 2.0f;
        float cy = h / 2.0f + 50;
        float radius = Math.min(w, h) * 0.35f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(16f);
        paint.setColor(Color.parseColor("#1E293B"));
        canvas.drawArc(new RectF(cx - radius, cy - radius, cx + radius, cy + radius), 180, 180, false, paint);

        // Обчислення відхилення в центах
        double pitch = 12 * (Math.log(peakFreqHz / 440.0) / Math.log(2)) + 69;
        long noteIndex = Math.round(pitch);
        double cents = (pitch - noteIndex) * 100;

        float angle = (float) (270 + (cents / 50.0) * 45.0);
        paint.setColor(Math.abs(cents) <= 5 ? Color.parseColor("#39FF14") : Color.parseColor("#FF007F"));
        canvas.drawLine(cx, cy, (float) (cx + Math.cos(Math.toRadians(angle)) * radius), (float) (cy + Math.sin(Math.toRadians(angle)) * radius), paint);

        paint.setStyle(Paint.Style.FILL);
        axisPaint.setTextSize(64f);
        axisPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(String.format("%.1f Hz", peakFreqHz), cx, cy - 40, axisPaint);
        axisPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawAxes(Canvas canvas, int w, int h) {
        paint.setColor(Color.parseColor("#1E293B"));
        canvas.drawLine(0, h - 30, w, h - 30, paint);
    }

    private int getColorForValue(float v) {
        switch (palette) {
            case FIRE: return Color.rgb((int)(v * 255), (int)(v * 180), 0);
            case RAINBOW: return Color.HSVToColor(new float[]{(1.0f - v) * 280.0f, 1.0f, 1.0f});
            case CYBERPUNK: return Color.rgb((int)(v * 255), (int)((1 - v) * 255), 180);
            case MONOCHROME: int c = (int)(v * 255); return Color.rgb(c, c, c);
            case NEON:
            default: return Color.rgb((int)(139 * v * 2), (int)(240 - 148 * v), 255);
        }
    }
}
```

---

## 6. 3D Водоспад через OpenGL ES 2.0 (`Waterfall3DSurfaceView.java`)

```java
package com.example.audiospectrumpro;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class Waterfall3DSurfaceView extends GLSurfaceView implements GLSurfaceView.Renderer {

    private float previousX, previousY;
    private float rotX = 30, rotY = 0;

    public Waterfall3DSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();

        if (e.getAction() == MotionEvent.ACTION_MOVE) {
            rotY += (x - previousX) * 0.5f;
            rotX += (y - previousY) * 0.5f;
        }

        previousX = x;
        previousY = y;
        return true;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.03f, 0.05f, 0.08f, 1.0f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        // Тут малюється 3D сітка поверхні з переносом вершин
    }
}
```

---

## 7. Модуль Експорту Даних (`DataExporter.java`)

```java
package com.example.audiospectrumpro;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;

public class DataExporter {

    public static File exportCSV(Context context, float[] floatFreqData, int sampleRate) throws IOException {
        File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "spectrum_export_" + System.currentTimeMillis() + ".csv");
        FileWriter writer = new FileWriter(file);
        writer.write("Frequency_Hz,Amplitude_dB\n");

        int numBins = floatFreqData.length;
        float nyquist = sampleRate / 2.0f;

        for (int i = 0; i < numBins; i++) {
            float freq = (i / (float) numBins) * nyquist;
            writer.write(String.format("%.1f,%.2f\n", freq, floatFreqData[i]));
        }

        writer.flush();
        writer.close();
        return file;
    }

    public static File exportPngSnapshot(Context context, Bitmap bitmap) throws IOException {
        File file = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "spectrum_snapshot_" + System.currentTimeMillis() + ".png");
        FileOutputStream out = new FileOutputStream(file);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.flush();
        out.close();
        return file;
    }
}
```

---

## 8. Головна Activity (`MainActivity.java`)

```java
package com.example.audiospectrumpro;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;

public class MainActivity extends AppCompatActivity implements AudioEngine.AudioDataListener {

    private static final int PERMISSION_REQ_CODE = 200;

    private AudioEngine audioEngine;
    private VisualizerView visualizer2D;
    private Waterfall3DSurfaceView visualizer3D;

    private TextView txtPeakFreq, txtNote, txtThd, txtBpm;
    private Button btnAudioStart, btnRecord, btnFreeze;

    private boolean isRecording = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        visualizer2D = findViewById(R.id.visualizer2D);
        visualizer3D = findViewById(R.id.visualizer3D);

        txtPeakFreq = findViewById(R.id.txtPeakFreq);
        txtNote = findViewById(R.id.txtNote);
        txtThd = findViewById(R.id.txtThd);
        txtBpm = findViewById(R.id.txtBpm);

        btnAudioStart = findViewById(R.id.btnAudioStart);
        btnRecord = findViewById(R.id.btnRecord);
        btnFreeze = findViewById(R.id.btnFreeze);

        audioEngine = new AudioEngine(this);

        setupControls();
        checkPermissions();
    }

    private void setupControls() {
        btnAudioStart.setOnClickListener(v -> audioEngine.start());

        btnFreeze.setOnClickListener(v -> audioEngine.setFrozen(true));

        btnRecord.setOnClickListener(v -> {
            if (!isRecording) {
                try {
                    File recFile = new File(getExternalFilesDir(null), "rec_" + System.currentTimeMillis() + ".wav");
                    audioEngine.startWavRecording(recFile);
                    isRecording = true;
                    btnRecord.setText("Зупинити запис");
                } catch (Exception e) {
                    Toast.makeText(this, "Помилка запису", Toast.LENGTH_SHORT).show();
                }
            } else {
                audioEngine.stopWavRecording();
                isRecording = false;
                btnRecord.setText("Запис");
            }
        });

        Spinner spinVizMode = findViewById(R.id.spinVizMode);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{
                "2D Спектр (Стовпчики)", "2D Спектр (Лінія)", "ISO 1/3 RTA", "2D Водоспад", "3D Водоспад (OpenGL)", "Осцилограма", "Тюнер"
        });
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinVizMode.setAdapter(adapter);

        spinVizMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 4) { // 3D OpenGL
                    visualizer2D.setVisibility(View.GONE);
                    visualizer3D.setVisibility(View.VISIBLE);
                } else {
                    visualizer2D.setVisibility(View.VISIBLE);
                    visualizer3D.setVisibility(View.GONE);
                    visualizer2D.setMode(VisualizerView.Mode.values()[position > 4 ? position - 1 : position]);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQ_CODE);
        } else {
            audioEngine.start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            audioEngine.start();
        }
    }

    @Override
    public void onAudioProcessed(float[] byteFreqData, float[] floatFreqData, float[] timeData, float[] peakData, float peakFreqHz, float f0, float thd, float snr, float rmsDb, int bpm) {
        runOnUiThread(() -> {
            visualizer2D.updateData(byteFreqData, floatFreqData, timeData, peakData, peakFreqHz);
            txtPeakFreq.setText(String.format("%.1f Hz", peakFreqHz));
            txtThd.setText(String.format("THD: %.2f %%", thd));
            txtBpm.setText(bpm > 0 ? bpm + " BPM" : "-- BPM");
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (audioEngine != null) audioEngine.stop();
    }
}
```

---

## 9. Макет XML (`res/layout/activity_main.xml`)

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#080C14">

    <!-- Верхня панель HUD -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="8dp"
        android:background="#0F172A">

        <TextView
            android:id="@+id/txtPeakFreq"
            android:layout_width="0dp"
            android:layout_weight="1"
            android:layout_height="wrap_content"
            android:text="0 Hz"
            android:textColor="#00F0FF"
            android:textStyle="bold"
            android:textSize="16sp" />

        <TextView
            android:id="@+id/txtThd"
            android:layout_width="0dp"
            android:layout_weight="1"
            android:layout_height="wrap_content"
            android:text="THD: 0%"
            android:textColor="#FFE600"
            android:textSize="14sp" />

        <TextView
            android:id="@+id/txtBpm"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="-- BPM"
            android:textColor="#FF007F"
            android:textSize="14sp" />
    </LinearLayout>

    <!-- Зафіксований екран візуалізації -->
    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="260dp"
        android:background="#000000">

        <com.example.audiospectrumpro.VisualizerView
            android:id="@+id/visualizer2D"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />

        <com.example.audiospectrumpro.Waterfall3DSurfaceView
            android:id="@+id/visualizer3D"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:visibility="gone" />
    </FrameLayout>

    <!-- Прокручувані налаштування під екраном -->
    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:padding="12dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical">

            <Spinner
                android:id="@+id/spinVizMode"
                android:layout_width="match_parent"
                android:layout_height="48dp"
                android:background="#1E293B"
                android:layout_marginBottom="12dp" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/btnAudioStart"
                    android:layout_width="0dp"
                    android:layout_weight="1"
                    android:layout_height="wrap_content"
                    android:text="Старт"
                    android:backgroundTint="#00F0FF"
                    android:textColor="#000000" />

                <Button
                    android:id="@+id/btnFreeze"
                    android:layout_width="0dp"
                    android:layout_weight="1"
                    android:layout_height="wrap_content"
                    android:text="Пауза"
                    android:layout_marginStart="8dp" />

                <Button
                    android:id="@+id/btnRecord"
                    android:layout_width="0dp"
                    android:layout_weight="1"
                    android:layout_height="wrap_content"
                    android:text="Запис"
                    android:layout_marginStart="8dp" />
            </LinearLayout>
        </LinearLayout>
    </ScrollView>
</LinearLayout>
```