import React from 'react';
import { VisualizerMode, ColorPalette, AudioMetrics } from '../types';
import { Visualizer2D } from './Visualizer2D';
import { Visualizer3D } from './Visualizer3D';
import { Play, Square, Pause, Disc, Wifi, BatteryMedium, Signal } from 'lucide-react';

interface AndroidDeviceViewProps {
  mode: VisualizerMode;
  setMode: (m: VisualizerMode) => void;
  palette: ColorPalette;
  isLogScale: boolean;
  sampleRate: number;
  getByteFreqData: () => Uint8Array;
  getFloatFreqData: () => Float32Array;
  getTimeData: () => Float32Array;
  getPeakData: () => Float32Array;
  metrics: AudioMetrics;
  isRunning: boolean;
  isFrozen: boolean;
  isRecording: boolean;
  onToggleAudio: () => void;
  onToggleFreeze: () => void;
  onToggleRecord: () => void;
}

const ANDROID_SPINNER_OPTIONS: { label: string; mode: VisualizerMode }[] = [
  { label: '2D Спектр (Стовпчики)', mode: 'BARS_2D' },
  { label: '2D Спектр (Лінія)', mode: 'LINE_2D' },
  { label: 'ISO 1/3 RTA', mode: 'RTA_1_3' },
  { label: '2D Водоспад', mode: 'WATERFALL_2D' },
  { label: '3D Водоспад (OpenGL)', mode: 'WATERFALL_3D' },
  { label: 'Осцилограма', mode: 'OSCILLOSCOPE' },
  { label: 'Тюнер', mode: 'TUNER' },
];

export const AndroidDeviceView: React.FC<AndroidDeviceViewProps> = ({
  mode,
  setMode,
  palette,
  isLogScale,
  sampleRate,
  getByteFreqData,
  getFloatFreqData,
  getTimeData,
  getPeakData,
  metrics,
  isRunning,
  isFrozen,
  isRecording,
  onToggleAudio,
  onToggleFreeze,
  onToggleRecord,
}) => {
  return (
    <div className="flex flex-col items-center justify-center p-4">
      {/* Android Device Bezel */}
      <div className="w-[380px] h-[780px] bg-[#0c1017] rounded-[48px] p-3 shadow-[0_25px_60px_-15px_rgba(0,0,0,0.9)] border-4 border-slate-700/80 relative flex flex-col overflow-hidden">
        {/* Device Notch & Speaker */}
        <div className="absolute top-0 left-1/2 -translate-x-1/2 w-36 h-5 bg-[#0c1017] rounded-b-2xl z-40 flex items-center justify-center gap-2">
          <div className="w-12 h-1 bg-slate-700 rounded-full" />
          <div className="w-2.5 h-2.5 rounded-full bg-slate-800 border border-slate-700" />
        </div>

        {/* Device Screen Area */}
        <div className="w-full h-full bg-[#080C14] rounded-[36px] flex flex-col overflow-hidden text-slate-100 font-sans border border-slate-800/60">
          {/* Status Bar */}
          <div className="w-full h-7 bg-[#080C14] flex items-center justify-between px-6 pt-1 text-[11px] font-mono text-slate-400 z-30 select-none">
            <span>09:41</span>
            <div className="flex items-center gap-1.5">
              <Signal className="w-3 h-3 text-slate-400" />
              <Wifi className="w-3 h-3 text-slate-400" />
              <BatteryMedium className="w-3.5 h-3.5 text-slate-400" />
            </div>
          </div>

          {/* App Header (Spectrum Analyzer PRO) */}
          <div className="bg-[#0b121e] px-4 py-2 flex items-center justify-between border-b border-slate-800">
            <span className="font-bold text-sm tracking-wide text-white">Spectrum Analyzer PRO</span>
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
              ULTRA
            </span>
          </div>

          {/* 1. Верхня панель HUD (res/layout/activity_main.xml) */}
          <div className="w-full flex items-center justify-between px-3 py-2 bg-[#0F172A] border-b border-slate-800 text-xs font-mono">
            {/* txtPeakFreq */}
            <div className="flex-1">
              <span className="text-[#00F0FF] font-bold text-sm block">
                {metrics.peakFreqHz > 10 ? `${metrics.peakFreqHz.toFixed(1)} Hz` : '0 Hz'}
              </span>
              <span className="text-[10px] text-slate-400">{metrics.noteName}</span>
            </div>

            {/* txtThd */}
            <div className="flex-1 text-center">
              <span className="text-[#FFE600] font-semibold block">
                THD: {metrics.thd.toFixed(2)}%
              </span>
              <span className="text-[10px] text-slate-400">SNR: {metrics.snr.toFixed(1)}dB</span>
            </div>

            {/* txtBpm */}
            <div className="text-right">
              <span className="text-[#FF007F] font-bold block">
                {metrics.bpm > 0 ? `${metrics.bpm} BPM` : '-- BPM'}
              </span>
              <span className="text-[10px] text-slate-400">{metrics.rmsDb.toFixed(1)} dBFS</span>
            </div>
          </div>

          {/* 2. Зафіксований екран візуалізації: 260dp */}
          <div className="w-full h-[260px] bg-black relative shrink-0 border-b border-slate-800/80">
            {mode === 'WATERFALL_3D' ? (
              <Visualizer3D palette={palette} getByteFreqData={getByteFreqData} />
            ) : (
              <Visualizer2D
                mode={mode}
                palette={palette}
                isLogScale={isLogScale}
                sampleRate={sampleRate}
                getByteFreqData={getByteFreqData}
                getFloatFreqData={getFloatFreqData}
                getTimeData={getTimeData}
                getPeakData={getPeakData}
                peakFreqHz={metrics.peakFreqHz}
                noteName={metrics.noteName}
                noteCents={metrics.noteCents}
              />
            )}
          </div>

          {/* 3. Прокручувані налаштування під екраном (ScrollView in activity_main.xml) */}
          <div className="flex-1 overflow-y-auto p-3 space-y-3">
            {/* Spinner (spinVizMode) */}
            <div>
              <label className="text-[11px] font-mono text-slate-400 mb-1 block">Режим візуалізації (Spinner):</label>
              <select
                value={mode}
                onChange={(e) => setMode(e.target.value as VisualizerMode)}
                className="w-full h-11 bg-[#1E293B] border border-slate-700 rounded-lg px-3 text-xs font-mono text-cyan-300 focus:outline-none focus:border-cyan-500"
              >
                {ANDROID_SPINNER_OPTIONS.map((opt) => (
                  <option key={opt.mode} value={opt.mode} className="bg-slate-900 text-slate-200">
                    {opt.label}
                  </option>
                ))}
              </select>
            </div>

            {/* Buttons Row (btnAudioStart, btnFreeze, btnRecord) */}
            <div className="flex items-center gap-2 pt-1">
              {/* btnAudioStart */}
              <button
                onClick={onToggleAudio}
                className={`flex-1 h-11 rounded-lg font-bold text-xs uppercase transition flex items-center justify-center gap-1.5 shadow-md ${
                  isRunning
                    ? 'bg-rose-600 hover:bg-rose-500 text-white shadow-rose-900/30'
                    : 'bg-[#00F0FF] hover:bg-cyan-300 text-slate-950 shadow-cyan-500/20'
                }`}
              >
                {isRunning ? <Square className="w-3.5 h-3.5" /> : <Play className="w-3.5 h-3.5 fill-current" />}
                {isRunning ? 'Стоп' : 'Старт'}
              </button>

              {/* btnFreeze */}
              <button
                onClick={onToggleFreeze}
                disabled={!isRunning}
                className={`flex-1 h-11 rounded-lg font-semibold text-xs border transition flex items-center justify-center gap-1.5 ${
                  isFrozen
                    ? 'bg-amber-500/20 border-amber-500/50 text-amber-300'
                    : 'bg-[#1E293B] border-slate-700 text-slate-300 hover:bg-slate-700/80 disabled:opacity-50'
                }`}
              >
                <Pause className="w-3.5 h-3.5" />
                {isFrozen ? 'Продовжити' : 'Пауза'}
              </button>

              {/* btnRecord */}
              <button
                onClick={onToggleRecord}
                disabled={!isRunning}
                className={`flex-1 h-11 rounded-lg font-semibold text-xs border transition flex items-center justify-center gap-1.5 ${
                  isRecording
                    ? 'bg-red-500/20 border-red-500 text-red-400 animate-pulse'
                    : 'bg-[#1E293B] border-slate-700 text-slate-300 hover:bg-slate-700/80 disabled:opacity-50'
                }`}
              >
                <Disc className="w-3.5 h-3.5" />
                {isRecording ? 'Зупинити' : 'Запис'}
              </button>
            </div>

            {/* Android Audio Engine Status Card */}
            <div className="p-3 bg-slate-900/60 rounded-xl border border-slate-800 text-[11px] font-mono space-y-1">
              <div className="flex justify-between text-slate-400">
                <span>Audio Engine:</span>
                <span className={isRunning ? 'text-emerald-400' : 'text-slate-500'}>
                  {isRunning ? (isFrozen ? 'FROZEN' : 'ACTIVE') : 'IDLE'}
                </span>
              </div>
              <div className="flex justify-between text-slate-400">
                <span>Sample Rate:</span>
                <span className="text-slate-200">{sampleRate} Hz</span>
              </div>
              <div className="flex justify-between text-slate-400">
                <span>Recording WAV:</span>
                <span className={isRecording ? 'text-red-400 font-bold' : 'text-slate-500'}>
                  {isRecording ? 'REC ●' : 'OFF'}
                </span>
              </div>
            </div>
          </div>

          {/* Android Bottom Navigation Bar */}
          <div className="w-full h-8 bg-[#080C14] flex items-center justify-center gap-8 border-t border-slate-800/80">
            <div className="w-3 h-3 border-2 border-slate-500 rounded-sm" />
            <div className="w-3 h-3 rounded-full border-2 border-slate-500" />
            <div className="w-0 h-0 border-t-[5px] border-t-transparent border-b-[5px] border-b-transparent border-r-[8px] border-r-slate-500" />
          </div>
        </div>
      </div>
    </div>
  );
};
