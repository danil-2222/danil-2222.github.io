import React, { useEffect, useRef, useState } from 'react';
import { AudioEngine } from './audio/AudioEngine';
import {
  AudioMetrics,
  AudioSourceType,
  ColorPalette,
  FilterType,
  GenWaveform,
  VisualizerMode,
} from './types';
import { Visualizer2D } from './components/Visualizer2D';
import { Visualizer3D } from './components/Visualizer3D';
import { AudioControls } from './components/AudioControls';
import { AndroidDeviceView } from './components/AndroidDeviceView';
import { AndroidProjectHub } from './components/AndroidProjectHub';
import {
  Play,
  Square,
  Pause,
  Disc,
  Smartphone,
  Monitor,
  Code2,
  Download,
  Activity,
  Layers,
  Sparkles,
  Info,
} from 'lucide-react';

const INITIAL_METRICS: AudioMetrics = {
  peakFreqHz: 0,
  noteName: '--',
  noteCents: 0,
  f0: 0,
  thd: 0,
  snr: 0,
  rmsDb: -100,
  peakDb: -100,
  bpm: 0,
};

export default function App() {
  const [isRunning, setIsRunning] = useState(false);
  const [isFrozen, setIsFrozen] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [metrics, setMetrics] = useState<AudioMetrics>(INITIAL_METRICS);

  // Settings
  const [mode, setMode] = useState<VisualizerMode>('BARS_2D');
  const [palette, setPalette] = useState<ColorPalette>('NEON');
  const [isLogScale, setIsLogScale] = useState(true);

  // Audio Engine Config States
  const [sourceType, setSourceType] = useState<AudioSourceType>('GENERATOR');
  const [genWaveform, setGenWaveform] = useState<GenWaveform>('SINE');
  const [genFreq, setGenFreq] = useState(440);
  const [genVol, setGenVol] = useState(0.25);
  const [inputGainDb, setInputGainDb] = useState(0);
  const [filterType, setFilterType] = useState<FilterType>('ALLPASS');
  const [fftSize, setFftSize] = useState(2048);
  const [smoothing, setSmoothing] = useState(0.8);

  // View state: 'STUDIO' vs 'ANDROID'
  const [viewLayout, setViewLayout] = useState<'STUDIO' | 'ANDROID'>('STUDIO');
  const [showAndroidHub, setShowAndroidHub] = useState(false);
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  // Engine instance
  const engineRef = useRef<AudioEngine | null>(null);
  const visualizerContainerRef = useRef<HTMLDivElement | null>(null);

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 3000);
  };

  useEffect(() => {
    const engine = new AudioEngine((newMetrics) => {
      setMetrics(newMetrics);
    });
    engineRef.current = engine;

    return () => {
      engine.stop();
    };
  }, []);

  const handleToggleAudio = async () => {
    const engine = engineRef.current;
    if (!engine) return;

    if (isRunning) {
      engine.stop();
      setIsRunning(false);
      setIsFrozen(false);
      setIsRecording(false);
    } else {
      const ok = await engine.start();
      if (ok) {
        setIsRunning(true);
        setIsFrozen(false);
        showToast('Аудіопроцесор запущено');
      }
    }
  };

  const handleToggleFreeze = () => {
    const engine = engineRef.current;
    if (!engine || !isRunning) return;
    const next = !isFrozen;
    engine.setFrozen(next);
    setIsFrozen(next);
    showToast(next ? 'Пауза: спектр заморожено' : 'Відновлено аналіз');
  };

  const handleToggleRecord = () => {
    const engine = engineRef.current;
    if (!engine || !isRunning) return;

    if (!isRecording) {
      engine.startRecording();
      setIsRecording(true);
      showToast('Запис WAV розпочато...');
    } else {
      const blob = engine.stopRecording();
      setIsRecording(false);

      // Download WAV file
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `rec_spectrum_${Date.now()}.wav`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      showToast('WAV аудіофайл успішно збережено!');
    }
  };

  const handleExportCSV = () => {
    const engine = engineRef.current;
    if (!engine) return;
    const csvContent = engine.exportCSV();
    const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `spectrum_export_${Date.now()}.csv`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    showToast('CSV таблицю частот і амплітуд експортовано');
  };

  const handleTakeSnapshot = () => {
    const container = visualizerContainerRef.current;
    if (!container) return;
    const canvas = container.querySelector('canvas');
    if (!canvas) return;

    const dataUrl = canvas.toDataURL('image/png');
    const a = document.createElement('a');
    a.href = dataUrl;
    a.download = `spectrum_snapshot_${Date.now()}.png`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    showToast('Скріншот спектра збережено як PNG');
  };

  // Sync controls with engine
  const handleSetSourceType = async (s: AudioSourceType) => {
    setSourceType(s);
    if (engineRef.current) {
      await engineRef.current.setSourceType(s);
    }
  };

  const handleSetGenWaveform = (w: GenWaveform) => {
    setGenWaveform(w);
    engineRef.current?.setGenWaveform(w);
  };

  const handleSetGenFreq = (f: number) => {
    setGenFreq(f);
    engineRef.current?.setGenFreq(f);
  };

  const handleSetGenVol = (v: number) => {
    setGenVol(v);
    engineRef.current?.setGenVol(v);
  };

  const handleSetInputGainDb = (g: number) => {
    setInputGainDb(g);
    engineRef.current?.setGainDb(g);
  };

  const handleSetFilterType = (f: FilterType) => {
    setFilterType(f);
    engineRef.current?.setFilterType(f);
    showToast(`Фільтр: ${f}`);
  };

  const handleSetFftSize = (s: number) => {
    setFftSize(s);
    engineRef.current?.setFftSize(s);
  };

  const handleSetSmoothing = (s: number) => {
    setSmoothing(s);
    engineRef.current?.setSmoothing(s);
  };

  const handleUploadFile = (file: File) => {
    if (engineRef.current) {
      engineRef.current.loadAudioFile(file);
      setSourceType('FILE');
      if (!isRunning) {
        handleToggleAudio();
      }
      showToast(`Завантажено аудіофайл: ${file.name}`);
    }
  };

  return (
    <div className="min-h-screen bg-[#080C14] text-slate-100 flex flex-col font-sans">
      {/* Toast Notification */}
      {toastMessage && (
        <div className="fixed bottom-6 right-6 z-50 px-4 py-2.5 rounded-xl bg-cyan-950/90 border border-cyan-500/50 text-cyan-200 text-xs font-mono shadow-2xl backdrop-blur-md flex items-center gap-2 animate-in fade-in slide-in-from-bottom-2 duration-200">
          <Sparkles className="w-4 h-4 text-cyan-400" />
          <span>{toastMessage}</span>
        </div>
      )}

      {/* Header Bar */}
      <header className="border-b border-slate-800/80 bg-[#0A0F1A]/90 sticky top-0 z-30 backdrop-blur-md px-4 lg:px-6 py-3">
        <div className="max-w-7xl mx-auto flex flex-wrap items-center justify-between gap-3">
          {/* Brand */}
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-cyan-500 via-blue-600 to-indigo-600 flex items-center justify-center shadow-lg shadow-cyan-500/20 text-slate-950 font-black text-lg">
              <Activity className="w-5 h-5 text-white" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-base font-bold tracking-tight text-white flex items-center gap-2">
                  Audio Spectrum Analyzer
                  <span className="px-2 py-0.5 text-[10px] font-mono font-bold rounded-md bg-gradient-to-r from-cyan-500 to-blue-500 text-slate-950 shadow-sm">
                    PRO ULTRA
                  </span>
                </h1>
                <span className="hidden sm:inline-block text-[11px] font-mono px-2 py-0.5 rounded-full bg-slate-800 text-slate-400 border border-slate-700/60">
                  Android Java + Web
                </span>
              </div>
              <p className="text-[11px] text-slate-400 hidden sm:block">
                JTransforms FFT • OpenGL ES 3D Surface • Biquad IIR • THD & SNR Engine
              </p>
            </div>
          </div>

          {/* Quick Actions & View Mode */}
          <div className="flex items-center gap-2">
            {/* Start/Stop Button */}
            <button
              onClick={handleToggleAudio}
              className={`flex items-center gap-2 px-4 py-2 rounded-xl font-bold text-xs uppercase tracking-wider transition shadow-lg ${
                isRunning
                  ? 'bg-rose-600 hover:bg-rose-500 text-white shadow-rose-900/40'
                  : 'bg-gradient-to-r from-cyan-400 to-blue-500 hover:from-cyan-300 hover:to-blue-400 text-slate-950 shadow-cyan-500/25 active:scale-95'
              }`}
            >
              {isRunning ? <Square className="w-4 h-4 fill-current" /> : <Play className="w-4 h-4 fill-current" />}
              <span>{isRunning ? 'Зупинити' : 'Старт Аудіо'}</span>
            </button>

            {/* Freeze Button */}
            <button
              onClick={handleToggleFreeze}
              disabled={!isRunning}
              title={isFrozen ? 'Продовжити аналіз' : 'Заморозити кадр'}
              className={`p-2 rounded-xl border transition ${
                isFrozen
                  ? 'bg-amber-500/20 border-amber-500 text-amber-300'
                  : 'bg-slate-900/60 border-slate-800 text-slate-400 hover:text-white hover:bg-slate-800 disabled:opacity-40'
              }`}
            >
              <Pause className="w-4 h-4" />
            </button>

            {/* Record WAV Button */}
            <button
              onClick={handleToggleRecord}
              disabled={!isRunning}
              title={isRecording ? 'Зупинити запис' : 'Записати у WAV'}
              className={`p-2 rounded-xl border transition ${
                isRecording
                  ? 'bg-red-500/20 border-red-500 text-red-400 animate-pulse'
                  : 'bg-slate-900/60 border-slate-800 text-slate-400 hover:text-white hover:bg-slate-800 disabled:opacity-40'
              }`}
            >
              <Disc className="w-4 h-4" />
            </button>

            {/* Layout Toggle: Studio vs Android Phone */}
            <div className="hidden md:flex items-center bg-slate-900/80 p-1 rounded-xl border border-slate-800">
              <button
                onClick={() => setViewLayout('STUDIO')}
                className={`flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium rounded-lg transition ${
                  viewLayout === 'STUDIO'
                    ? 'bg-cyan-500/20 text-cyan-300 font-semibold'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                <Monitor className="w-3.5 h-3.5" />
                Студія
              </button>
              <button
                onClick={() => setViewLayout('ANDROID')}
                className={`flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium rounded-lg transition ${
                  viewLayout === 'ANDROID'
                    ? 'bg-cyan-500/20 text-cyan-300 font-semibold'
                    : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                <Smartphone className="w-3.5 h-3.5" />
                Android
              </button>
            </div>

            {/* Android Project Source Hub Modal Trigger */}
            <button
              onClick={() => setShowAndroidHub(true)}
              className="flex items-center gap-2 px-3 py-2 rounded-xl bg-slate-800/90 hover:bg-slate-700/90 border border-slate-700 text-xs font-mono text-cyan-300 transition"
            >
              <Code2 className="w-4 h-4 text-cyan-400" />
              <span className="hidden sm:inline">Android код (.zip)</span>
            </button>
          </div>
        </div>
      </header>

      {/* Top HUD: Real-time Audio Measurement Matrix */}
      <div className="border-b border-slate-800/80 bg-[#0b121e]/80 backdrop-blur-sm px-4 lg:px-6 py-2.5">
        <div className="max-w-7xl mx-auto grid grid-cols-3 sm:grid-cols-6 gap-3 text-xs font-mono">
          {/* Peak Frequency */}
          <div className="p-2 rounded-xl bg-slate-900/60 border border-slate-800 flex flex-col">
            <span className="text-[10px] text-slate-400 uppercase tracking-wider">Пікова частота</span>
            <div className="text-[#00F0FF] font-bold text-base mt-0.5 truncate">
              {metrics.peakFreqHz > 10 ? `${metrics.peakFreqHz.toFixed(1)} Hz` : '0 Hz'}
            </div>
          </div>

          {/* Musical Note & Pitch */}
          <div className="p-2 rounded-xl bg-slate-900/60 border border-slate-800 flex flex-col">
            <span className="text-[10px] text-slate-400 uppercase tracking-wider">Нота / Відхилення</span>
            <div className="text-white font-bold text-base mt-0.5 truncate flex items-baseline gap-1.5">
              <span>{metrics.noteName}</span>
              <span
                className={`text-[11px] font-normal ${
                  Math.abs(metrics.noteCents) <= 5 ? 'text-emerald-400 font-semibold' : 'text-slate-400'
                }`}
              >
                {metrics.peakFreqHz > 10 ? `${metrics.noteCents > 0 ? '+' : ''}${metrics.noteCents}c` : ''}
              </span>
            </div>
          </div>

          {/* THD% */}
          <div className="p-2 rounded-xl bg-slate-900/60 border border-slate-800 flex flex-col">
            <span className="text-[10px] text-slate-400 uppercase tracking-wider">Спотворення THD</span>
            <div className="text-[#FFE600] font-bold text-base mt-0.5 truncate">
              {metrics.thd.toFixed(2)} %
            </div>
          </div>

          {/* SNR (dB) */}
          <div className="p-2 rounded-xl bg-slate-900/60 border border-slate-800 flex flex-col">
            <span className="text-[10px] text-slate-400 uppercase tracking-wider">Сигнал / Шум (SNR)</span>
            <div className="text-cyan-300 font-bold text-base mt-0.5 truncate">
              {metrics.snr.toFixed(1)} dB
            </div>
          </div>

          {/* RMS / Peak dBFS */}
          <div className="p-2 rounded-xl bg-slate-900/60 border border-slate-800 flex flex-col">
            <span className="text-[10px] text-slate-400 uppercase tracking-wider">Рівень RMS / Peak</span>
            <div className="text-slate-200 font-bold text-base mt-0.5 truncate">
              {metrics.rmsDb.toFixed(1)} <span className="text-xs font-normal text-slate-400">dBFS</span>
            </div>
          </div>

          {/* BPM Detector */}
          <div className="p-2 rounded-xl bg-slate-900/60 border border-slate-800 flex flex-col">
            <span className="text-[10px] text-slate-400 uppercase tracking-wider">Темп (BPM)</span>
            <div className="text-[#FF007F] font-bold text-base mt-0.5 truncate flex items-center gap-1.5">
              <span>{metrics.bpm > 0 ? `${metrics.bpm}` : '--'}</span>
              <span className="text-xs font-normal text-slate-400">BPM</span>
            </div>
          </div>
        </div>
      </div>

      {/* Main Content Body */}
      <main className="flex-1 max-w-7xl w-full mx-auto p-4 lg:p-6 space-y-6">
        {viewLayout === 'ANDROID' ? (
          /* Android Layout View */
          <div className="flex flex-col items-center">
            <div className="mb-4 text-center">
              <h2 className="text-sm font-semibold text-cyan-300 flex items-center justify-center gap-1.5">
                <Smartphone className="w-4 h-4" />
                Емуляція Android екрана (`activity_main.xml`)
              </h2>
              <p className="text-xs text-slate-400">
                Візуалізатор працює з параметрами нативного Android додатку з документу
              </p>
            </div>

            <AndroidDeviceView
              mode={mode}
              setMode={setMode}
              palette={palette}
              isLogScale={isLogScale}
              sampleRate={engineRef.current?.getSampleRate() || 44100}
              getByteFreqData={() => engineRef.current?.getByteFreqData() || new Uint8Array(0)}
              getFloatFreqData={() => engineRef.current?.getFloatFreqData() || new Float32Array(0)}
              getTimeData={() => engineRef.current?.getTimeData() || new Float32Array(0)}
              getPeakData={() => engineRef.current?.getPeakData() || new Float32Array(0)}
              metrics={metrics}
              isRunning={isRunning}
              isFrozen={isFrozen}
              isRecording={isRecording}
              onToggleAudio={handleToggleAudio}
              onToggleFreeze={handleToggleFreeze}
              onToggleRecord={handleToggleRecord}
            />
          </div>
        ) : (
          /* Desktop Studio Layout View */
          <div className="space-y-6">
            {/* Visualizer Mode Switcher Tabs */}
            <div className="flex items-center gap-1.5 overflow-x-auto pb-1 border-b border-slate-800/80">
              {(
                [
                  { id: 'BARS_2D', label: '2D Стовпчики' },
                  { id: 'LINE_2D', label: '2D Лінія' },
                  { id: 'RTA_1_3', label: 'ISO 1/3 Octave RTA' },
                  { id: 'WATERFALL_2D', label: '2D Водоспад' },
                  { id: 'WATERFALL_3D', label: '3D OpenGL Водоспад' },
                  { id: 'OSCILLOSCOPE', label: 'Осцилограф' },
                  { id: 'TUNER', label: 'Тюнер' },
                ] as { id: VisualizerMode; label: string }[]
              ).map((tab) => (
                <button
                  key={tab.id}
                  onClick={() => setMode(tab.id)}
                  className={`px-3 py-1.5 rounded-xl text-xs font-mono font-medium transition shrink-0 ${
                    mode === tab.id
                      ? 'bg-cyan-500/20 text-cyan-300 font-bold border border-cyan-500/40 shadow-sm'
                      : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/50'
                  }`}
                >
                  {tab.label}
                </button>
              ))}
            </div>

            {/* Visualizer Screen Frame */}
            <div
              ref={visualizerContainerRef}
              className="w-full h-[400px] sm:h-[480px] bg-black rounded-2xl border border-slate-800 shadow-2xl relative overflow-hidden"
            >
              {mode === 'WATERFALL_3D' ? (
                <Visualizer3D
                  palette={palette}
                  getByteFreqData={() => engineRef.current?.getByteFreqData() || new Uint8Array(0)}
                />
              ) : (
                <Visualizer2D
                  mode={mode}
                  palette={palette}
                  isLogScale={isLogScale}
                  sampleRate={engineRef.current?.getSampleRate() || 44100}
                  getByteFreqData={() => engineRef.current?.getByteFreqData() || new Uint8Array(0)}
                  getFloatFreqData={() => engineRef.current?.getFloatFreqData() || new Float32Array(0)}
                  getTimeData={() => engineRef.current?.getTimeData() || new Float32Array(0)}
                  getPeakData={() => engineRef.current?.getPeakData() || new Float32Array(0)}
                  peakFreqHz={metrics.peakFreqHz}
                  noteName={metrics.noteName}
                  noteCents={metrics.noteCents}
                />
              )}

              {/* Status overlay if idle */}
              {!isRunning && (
                <div className="absolute inset-0 bg-black/70 backdrop-blur-xs flex flex-col items-center justify-center p-6 text-center z-20">
                  <div className="w-14 h-14 rounded-2xl bg-cyan-500/10 border border-cyan-500/30 flex items-center justify-center text-cyan-400 mb-4 animate-pulse">
                    <Activity className="w-7 h-7" />
                  </div>
                  <h3 className="text-lg font-bold text-white">Аудіопроцесор очікує запуску</h3>
                  <p className="text-xs text-slate-400 max-w-md mt-1 mb-4">
                    Натисніть кнопку нижче або «Старт Аудіо» вгорі для активації реального аналізатора через мікрофон, вбудований генератор або файл.
                  </p>
                  <button
                    onClick={handleToggleAudio}
                    className="flex items-center gap-2 px-6 py-2.5 rounded-xl bg-gradient-to-r from-cyan-400 to-blue-500 hover:from-cyan-300 hover:to-blue-400 text-slate-950 font-bold text-xs uppercase tracking-wider shadow-lg shadow-cyan-500/25 transition active:scale-95"
                  >
                    <Play className="w-4 h-4 fill-current" />
                    Запустити Spectrum Analyzer
                  </button>
                </div>
              )}
            </div>

            {/* Comprehensive Studio Control Center */}
            <AudioControls
              sourceType={sourceType}
              setSourceType={handleSetSourceType}
              genWaveform={genWaveform}
              setGenWaveform={handleSetGenWaveform}
              genFreq={genFreq}
              setGenFreq={handleSetGenFreq}
              genVol={genVol}
              setGenVol={handleSetGenVol}
              inputGainDb={inputGainDb}
              setInputGainDb={handleSetInputGainDb}
              filterType={filterType}
              setFilterType={handleSetFilterType}
              fftSize={fftSize}
              setFftSize={handleSetFftSize}
              smoothing={smoothing}
              setSmoothing={handleSetSmoothing}
              isLogScale={isLogScale}
              setIsLogScale={setIsLogScale}
              palette={palette}
              setPalette={setPalette}
              mode={mode}
              setMode={setMode}
              onUploadFile={handleUploadFile}
              onExportCSV={handleExportCSV}
              onTakeSnapshot={handleTakeSnapshot}
              isRecording={isRecording}
              onToggleRecord={handleToggleRecord}
            />
          </div>
        )}
      </main>

      {/* Android Project Hub Modal */}
      {showAndroidHub && <AndroidProjectHub onClose={() => setShowAndroidHub(false)} />}
    </div>
  );
}
