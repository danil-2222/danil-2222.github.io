export type VisualizerMode =
  | 'BARS_2D'
  | 'LINE_2D'
  | 'RTA_1_3'
  | 'WATERFALL_2D'
  | 'WATERFALL_3D'
  | 'OSCILLOSCOPE'
  | 'TUNER';

export type ColorPalette = 'NEON' | 'CYBERPUNK' | 'RAINBOW' | 'FIRE' | 'MONOCHROME';

export type FilterType = 'ALLPASS' | 'NOTCH50' | 'HIGHPASS100' | 'LOWPASS5000' | 'BANDPASS';

export type AudioSourceType = 'MIC' | 'GENERATOR' | 'FILE';

export type GenWaveform =
  | 'SINE'
  | 'SQUARE'
  | 'SAWTOOTH'
  | 'TRIANGLE'
  | 'WHITE_NOISE'
  | 'PINK_NOISE'
  | 'LOG_SWEEP';

export interface AudioMetrics {
  peakFreqHz: number;
  noteName: string;
  noteCents: number;
  f0: number;
  thd: number;
  snr: number;
  rmsDb: number;
  peakDb: number;
  bpm: number;
}

export interface AudioEngineConfig {
  fftSize: number;
  smoothing: number;
  inputGainDb: number;
  filterType: FilterType;
  sourceType: AudioSourceType;
  genWaveform: GenWaveform;
  genFreq: number;
  genVol: number;
}

export const RTA_ISO_FREQS = [
  25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315,
  400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150,
  4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000
];
