import React, { useRef } from 'react';
import {
  AudioSourceType,
  ColorPalette,
  FilterType,
  GenWaveform,
  VisualizerMode,
} from '../types';
import {
  Mic,
  Activity,
  Upload,
  Volume2,
  Sliders,
  Filter,
  BarChart2,
  FileSpreadsheet,
  Camera,
  Disc,
  Palette,
  Play,
  Pause,
  RotateCcw,
} from 'lucide-react';

interface AudioControlsProps {
  sourceType: AudioSourceType;
  setSourceType: (s: AudioSourceType) => void;
  genWaveform: GenWaveform;
  setGenWaveform: (w: GenWaveform) => void;
  genFreq: number;
  setGenFreq: (f: number) => void;
  genVol: number;
  setGenVol: (v: number) => void;
  inputGainDb: number;
  setInputGainDb: (g: number) => void;
  filterType: FilterType;
  setFilterType: (f: FilterType) => void;
  fftSize: number;
  setFftSize: (s: number) => void;
  smoothing: number;
  setSmoothing: (s: number) => void;
  isLogScale: boolean;
  setIsLogScale: (l: boolean) => void;
  palette: ColorPalette;
  setPalette: (p: ColorPalette) => void;
  mode: VisualizerMode;
  setMode: (m: VisualizerMode) => void;
  onUploadFile: (file: File) => void;
  onExportCSV: () => void;
  onTakeSnapshot: () => void;
  isRecording: boolean;
  onToggleRecord: () => void;
}

const PRESET_FREQS = [
  { label: '50 Hz', val: 50 },
  { label: '100 Hz', val: 100 },
  { label: '440 Hz (A4)', val: 440 },
  { label: '1 kHz', val: 1000 },
  { label: '5 kHz', val: 5000 },
  { label: '10 kHz', val: 10000 },
];

const PALETTE_OPTIONS: { id: ColorPalette; label: string; preview: string }[] = [
  { id: 'NEON', label: 'Neon Cyber', preview: 'from-blue-600 via-cyan-400 to-teal-300' },
  { id: 'CYBERPUNK', label: 'Cyberpunk', preview: 'from-purple-600 via-pink-500 to-yellow-400' },
  { id: 'RAINBOW', label: 'Rainbow HSV', preview: 'from-indigo-500 via-emerald-400 to-rose-500' },
  { id: 'FIRE', label: 'Solar Fire', preview: 'from-red-600 via-orange-500 to-amber-300' },
  { id: 'MONOCHROME', label: 'Phosphor Mono', preview: 'from-slate-700 via-slate-400 to-white' },
];

export const AudioControls: React.FC<AudioControlsProps> = ({
  sourceType,
  setSourceType,
  genWaveform,
  setGenWaveform,
  genFreq,
  setGenFreq,
  genVol,
  setGenVol,
  inputGainDb,
  setInputGainDb,
  filterType,
  setFilterType,
  fftSize,
  setFftSize,
  smoothing,
  setSmoothing,
  isLogScale,
  setIsLogScale,
  palette,
  setPalette,
  mode,
  setMode,
  onUploadFile,
  onExportCSV,
  onTakeSnapshot,
  isRecording,
  onToggleRecord,
}) => {
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      onUploadFile(e.target.files[0]);
    }
  };

  return (
    <div className="bg-[#0b121e] border border-slate-800/80 rounded-2xl p-4 shadow-xl space-y-5 text-slate-200">
      {/* 1. Source Selector */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-mono font-semibold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
            <Mic className="w-3.5 h-3.5 text-cyan-400" />
            Аудіо-джерело (Input Source)
          </span>
          <span className="text-[11px] font-mono text-cyan-400 bg-cyan-950/50 px-2 py-0.5 rounded border border-cyan-800/40">
            {sourceType}
          </span>
        </div>

        <div className="grid grid-cols-3 gap-2">
          <button
            onClick={() => setSourceType('MIC')}
            className={`flex items-center justify-center gap-2 py-2 px-3 rounded-xl text-xs font-semibold transition border ${
              sourceType === 'MIC'
                ? 'bg-cyan-500/15 border-cyan-500 text-cyan-300 shadow-sm'
                : 'bg-slate-900/60 border-slate-800 text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
            }`}
          >
            <Mic className="w-4 h-4" />
            Мікрофон
          </button>

          <button
            onClick={() => setSourceType('GENERATOR')}
            className={`flex items-center justify-center gap-2 py-2 px-3 rounded-xl text-xs font-semibold transition border ${
              sourceType === 'GENERATOR'
                ? 'bg-cyan-500/15 border-cyan-500 text-cyan-300 shadow-sm'
                : 'bg-slate-900/60 border-slate-800 text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
            }`}
          >
            <Activity className="w-4 h-4" />
            Генератор
          </button>

          <button
            onClick={() => {
              setSourceType('FILE');
              fileInputRef.current?.click();
            }}
            className={`flex items-center justify-center gap-2 py-2 px-3 rounded-xl text-xs font-semibold transition border ${
              sourceType === 'FILE'
                ? 'bg-cyan-500/15 border-cyan-500 text-cyan-300 shadow-sm'
                : 'bg-slate-900/60 border-slate-800 text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
            }`}
          >
            <Upload className="w-4 h-4" />
            Аудіофайл
          </button>
          <input
            type="file"
            ref={fileInputRef}
            onChange={handleFileChange}
            accept="audio/*"
            className="hidden"
          />
        </div>
      </div>

      {/* 2. Audio Generator Sub-Panel (if active) */}
      {sourceType === 'GENERATOR' && (
        <div className="p-3.5 bg-slate-900/80 rounded-xl border border-cyan-500/30 space-y-3.5">
          <div className="flex items-center justify-between">
            <span className="text-xs font-mono font-medium text-cyan-300 flex items-center gap-1.5">
              <Activity className="w-3.5 h-3.5 text-cyan-400" />
              Параметри генератора сигналів
            </span>
            <span className="text-xs font-mono text-slate-400">{genFreq.toFixed(1)} Hz</span>
          </div>

          {/* Waveform Selector */}
          <div className="flex flex-wrap gap-1.5">
            {(
              [
                'SINE',
                'SQUARE',
                'SAWTOOTH',
                'TRIANGLE',
                'WHITE_NOISE',
                'PINK_NOISE',
                'LOG_SWEEP',
              ] as GenWaveform[]
            ).map((wave) => (
              <button
                key={wave}
                onClick={() => setGenWaveform(wave)}
                className={`px-2.5 py-1 text-[11px] font-mono rounded-lg transition border ${
                  genWaveform === wave
                    ? 'bg-cyan-500 text-slate-950 font-bold border-cyan-400 shadow-sm'
                    : 'bg-slate-800/80 text-slate-400 hover:text-slate-200 border-slate-700/60'
                }`}
              >
                {wave.replace('_', ' ')}
              </button>
            ))}
          </div>

          {/* Frequency Slider & Presets */}
          {genWaveform !== 'WHITE_NOISE' && genWaveform !== 'PINK_NOISE' && genWaveform !== 'LOG_SWEEP' && (
            <div className="space-y-2 pt-1">
              <div className="flex items-center justify-between text-xs font-mono text-slate-400">
                <span>Частота сигналу:</span>
                <input
                  type="number"
                  value={Math.round(genFreq)}
                  onChange={(e) => setGenFreq(Number(e.target.value))}
                  min={10}
                  max={20000}
                  className="w-20 px-1.5 py-0.5 bg-slate-800 border border-slate-700 rounded text-right text-cyan-300 text-xs font-mono"
                />
              </div>

              <input
                type="range"
                min={20}
                max={15000}
                step={1}
                value={genFreq}
                onChange={(e) => setGenFreq(Number(e.target.value))}
                className="w-full accent-cyan-400 h-1.5 bg-slate-800 rounded-lg cursor-pointer"
              />

              {/* Quick Preset Buttons */}
              <div className="flex items-center gap-1.5 overflow-x-auto py-1">
                {PRESET_FREQS.map((p) => (
                  <button
                    key={p.val}
                    onClick={() => setGenFreq(p.val)}
                    className="px-2 py-0.5 text-[10px] font-mono bg-slate-800 hover:bg-slate-700 text-slate-300 rounded border border-slate-700 shrink-0 transition"
                  >
                    {p.label}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Volume Slider */}
          <div className="space-y-1">
            <div className="flex items-center justify-between text-xs font-mono text-slate-400">
              <span className="flex items-center gap-1">
                <Volume2 className="w-3.5 h-3.5" /> Гучність генератора:
              </span>
              <span>{Math.round(genVol * 100)}%</span>
            </div>
            <input
              type="range"
              min={0}
              max={1}
              step={0.01}
              value={genVol}
              onChange={(e) => setGenVol(Number(e.target.value))}
              className="w-full accent-cyan-400 h-1.5 bg-slate-800 rounded-lg cursor-pointer"
            />
          </div>
        </div>
      )}

      {/* 3. Biquad IIR Filter (BiquadFilter.java) */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-mono font-semibold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
            <Filter className="w-3.5 h-3.5 text-cyan-400" />
            Параметричний Biquad-фільтр
          </span>
          <span className="text-[11px] font-mono text-amber-400 bg-amber-950/40 px-2 py-0.5 rounded border border-amber-800/40">
            {filterType}
          </span>
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-3 gap-1.5">
          {(
            [
              { type: 'ALLPASS', label: 'ALLPASS (Bypass)' },
              { type: 'NOTCH50', label: 'Notch 50 Hz (Hum)' },
              { type: 'HIGHPASS100', label: 'HPF 100 Hz' },
              { type: 'LOWPASS5000', label: 'LPF 5000 Hz' },
              { type: 'BANDPASS', label: 'BPF 1 kHz' },
            ] as { type: FilterType; label: string }[]
          ).map((item) => (
            <button
              key={item.type}
              onClick={() => setFilterType(item.type)}
              className={`py-1.5 px-2.5 rounded-lg text-xs font-mono transition text-left border ${
                filterType === item.type
                  ? 'bg-amber-500/15 border-amber-500 text-amber-300 font-semibold'
                  : 'bg-slate-900/60 border-slate-800/80 text-slate-400 hover:text-slate-200'
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>
      </div>

      {/* 4. Input Gain & FFT Settings */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 pt-1">
        {/* Input Gain dB */}
        <div className="space-y-1.5">
          <div className="flex items-center justify-between text-xs font-mono text-slate-400">
            <span className="flex items-center gap-1">
              <Sliders className="w-3.5 h-3.5" /> Вхідне підсилення (Gain):
            </span>
            <span className="text-cyan-400 font-semibold">{inputGainDb > 0 ? `+${inputGainDb}` : inputGainDb} dB</span>
          </div>
          <input
            type="range"
            min={-24}
            max={24}
            step={1}
            value={inputGainDb}
            onChange={(e) => setInputGainDb(Number(e.target.value))}
            className="w-full accent-cyan-400 h-1.5 bg-slate-800 rounded-lg cursor-pointer"
          />
        </div>

        {/* Smoothing Factor */}
        <div className="space-y-1.5">
          <div className="flex items-center justify-between text-xs font-mono text-slate-400">
            <span>Згладжування FFT (Smoothing):</span>
            <span className="text-cyan-400 font-semibold">{smoothing.toFixed(2)}</span>
          </div>
          <input
            type="range"
            min={0}
            max={0.95}
            step={0.05}
            value={smoothing}
            onChange={(e) => setSmoothing(Number(e.target.value))}
            className="w-full accent-cyan-400 h-1.5 bg-slate-800 rounded-lg cursor-pointer"
          />
        </div>
      </div>

      {/* 5. FFT Size & Scale */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <div>
          <label className="text-xs font-mono text-slate-400 mb-1.5 block">Розмір FFT буфера (FFT Size):</label>
          <div className="grid grid-cols-5 gap-1">
            {[512, 1024, 2048, 4096, 8192].map((size) => (
              <button
                key={size}
                onClick={() => setFftSize(size)}
                className={`py-1 text-xs font-mono rounded border transition ${
                  fftSize === size
                    ? 'bg-cyan-500/20 border-cyan-500 text-cyan-300 font-bold'
                    : 'bg-slate-900/60 border-slate-800 text-slate-400 hover:text-slate-200'
                }`}
              >
                {size}
              </button>
            ))}
          </div>
        </div>

        <div>
          <label className="text-xs font-mono text-slate-400 mb-1.5 block">Частотна шкала (Frequency Scale):</label>
          <div className="grid grid-cols-2 gap-1.5">
            <button
              onClick={() => setIsLogScale(true)}
              className={`py-1.5 text-xs font-mono rounded-lg border transition ${
                isLogScale
                  ? 'bg-cyan-500/20 border-cyan-500 text-cyan-300 font-bold'
                  : 'bg-slate-900/60 border-slate-800 text-slate-400 hover:text-slate-200'
              }`}
            >
              Логарифмічна (Log)
            </button>
            <button
              onClick={() => setIsLogScale(false)}
              className={`py-1.5 text-xs font-mono rounded-lg border transition ${
                !isLogScale
                  ? 'bg-cyan-500/20 border-cyan-500 text-cyan-300 font-bold'
                  : 'bg-slate-900/60 border-slate-800 text-slate-400 hover:text-slate-200'
              }`}
            >
              Лінійна (Linear)
            </button>
          </div>
        </div>
      </div>

      {/* 6. Color Palettes */}
      <div>
        <span className="text-xs font-mono font-semibold uppercase tracking-wider text-slate-400 flex items-center gap-1.5 mb-2">
          <Palette className="w-3.5 h-3.5 text-cyan-400" />
          Колірна палітра (Color Palette)
        </span>
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-2">
          {PALETTE_OPTIONS.map((pal) => (
            <button
              key={pal.id}
              onClick={() => setPalette(pal.id)}
              className={`p-2 rounded-xl border text-left transition flex flex-col gap-1.5 ${
                palette === pal.id
                  ? 'bg-cyan-500/15 border-cyan-500 text-white shadow-sm'
                  : 'bg-slate-900/60 border-slate-800/80 text-slate-400 hover:text-slate-200'
              }`}
            >
              <div className={`w-full h-3 rounded bg-gradient-to-r ${pal.preview}`} />
              <span className="text-[11px] font-mono truncate">{pal.label}</span>
            </button>
          ))}
        </div>
      </div>

      {/* 7. Action Exports */}
      <div className="pt-2 border-t border-slate-800/80 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <button
            onClick={onToggleRecord}
            className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-mono rounded-xl border transition ${
              isRecording
                ? 'bg-red-500/20 border-red-500 text-red-400 animate-pulse'
                : 'bg-slate-800/80 border-slate-700 text-slate-300 hover:text-white'
            }`}
          >
            <Disc className="w-3.5 h-3.5" />
            {isRecording ? 'Зупинити WAV' : 'Запис у WAV'}
          </button>

          <button
            onClick={onExportCSV}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-mono rounded-xl bg-slate-800/80 border border-slate-700 text-slate-300 hover:text-white transition"
          >
            <FileSpreadsheet className="w-3.5 h-3.5 text-emerald-400" />
            Експорт CSV
          </button>

          <button
            onClick={onTakeSnapshot}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-mono rounded-xl bg-slate-800/80 border border-slate-700 text-slate-300 hover:text-white transition"
          >
            <Camera className="w-3.5 h-3.5 text-cyan-400" />
            Скріншот PNG
          </button>
        </div>

        <span className="text-[10px] font-mono text-slate-500">
          Audio Engine v1.0 PRO ULTRA
        </span>
      </div>
    </div>
  );
};
