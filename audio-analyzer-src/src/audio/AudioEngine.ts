import {
  AudioEngineConfig,
  AudioMetrics,
  AudioSourceType,
  FilterType,
  GenWaveform,
} from '../types';
import { BiquadFilter } from './BiquadFilter';
import { encodeWAV } from './WavEncoder';

const NOTE_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];

export class AudioEngine {
  private ctx: AudioContext | null = null;
  private analyser: AnalyserNode | null = null;
  private gainNode: GainNode | null = null;
  private filterNode: BiquadFilterNode | null = null;

  // Source Nodes
  private micStream: MediaStream | null = null;
  private micSource: MediaStreamAudioSourceNode | null = null;

  // Generator Nodes
  private genOsc: OscillatorNode | null = null;
  private genGain: GainNode | null = null;
  private genNoiseNode: AudioBufferSourceNode | null = null;
  private sweepInterval: number | null = null;

  // File Audio
  private fileAudioElement: HTMLAudioElement | null = null;
  private fileSourceNode: MediaElementAudioSourceNode | null = null;

  // Configuration
  private config: AudioEngineConfig = {
    fftSize: 2048,
    smoothing: 0.8,
    inputGainDb: 0.0,
    filterType: 'ALLPASS',
    sourceType: 'MIC',
    genWaveform: 'SINE',
    genFreq: 440.0,
    genVol: 0.2,
  };

  private biquadSoftwareFilter = new BiquadFilter();

  // Buffers
  private byteFreqData: Uint8Array = new Uint8Array(1024);
  private floatFreqData: Float32Array = new Float32Array(1024);
  private timeData: Float32Array = new Float32Array(2048);
  private peakData: Float32Array = new Float32Array(1024);

  // Status
  private isRunning = false;
  private isFrozen = false;
  private isRecording = false;
  private recordedChunks: Float32Array[] = [];

  // Metrics
  private metrics: AudioMetrics = {
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

  // BPM Detection State
  private lastBeatTime = 0;
  private bpmHistory: number[] = new Array(30).fill(0);
  private bpmHistIdx = 0;
  private currentBpm = 0;

  // Listeners
  private onMetricsUpdate?: (metrics: AudioMetrics) => void;
  private animationFrameId: number | null = null;

  constructor(onMetrics?: (metrics: AudioMetrics) => void) {
    this.onMetricsUpdate = onMetrics;
  }

  public async init(): Promise<void> {
    if (this.ctx) return;
    const AudioCtxClass = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    this.ctx = new AudioCtxClass();

    this.analyser = this.ctx.createAnalyser();
    this.analyser.fftSize = this.config.fftSize;
    this.analyser.smoothingTimeConstant = this.config.smoothing;

    this.gainNode = this.ctx.createGain();
    this.setGainDb(this.config.inputGainDb);

    this.filterNode = this.ctx.createBiquadFilter();
    this.applyBiquadFilterNode(this.config.filterType);

    this.updateBufferSizes();
  }

  private updateBufferSizes(): void {
    const bins = this.config.fftSize / 2;
    this.byteFreqData = new Uint8Array(bins);
    this.floatFreqData = new Float32Array(bins);
    this.peakData = new Float32Array(bins);
    this.timeData = new Float32Array(this.config.fftSize);
    this.biquadSoftwareFilter.setFilter(this.config.filterType, this.getSampleRate());
  }

  public getSampleRate(): number {
    return this.ctx ? this.ctx.sampleRate : 44100;
  }

  public async start(): Promise<boolean> {
    await this.init();
    if (!this.ctx) return false;

    if (this.ctx.state === 'suspended') {
      await this.ctx.resume();
    }

    this.isRunning = true;
    this.isFrozen = false;

    await this.setupSource(this.config.sourceType);
    this.startProcessingLoop();
    return true;
  }

  public stop(): void {
    this.isRunning = false;
    if (this.animationFrameId !== null) {
      cancelAnimationFrame(this.animationFrameId);
      this.animationFrameId = null;
    }
    this.stopCurrentSource();
    if (this.isRecording) {
      this.stopRecording();
    }
  }

  public setFrozen(frozen: boolean): void {
    this.isFrozen = frozen;
  }

  public isPaused(): boolean {
    return this.isFrozen;
  }

  public getIsRunning(): boolean {
    return this.isRunning;
  }

  public async setSourceType(type: AudioSourceType): Promise<void> {
    this.config.sourceType = type;
    if (this.isRunning) {
      await this.setupSource(type);
    }
  }

  public async setupSource(type: AudioSourceType): Promise<void> {
    this.stopCurrentSource();
    if (!this.ctx || !this.filterNode || !this.gainNode || !this.analyser) return;

    // Disconnect pipeline to rebuild cleanly
    try {
      this.filterNode.disconnect();
      this.gainNode.disconnect();
    } catch {
      // ignore
    }

    // Pipeline: Source -> Filter -> Gain -> Analyser
    this.filterNode.connect(this.gainNode);
    this.gainNode.connect(this.analyser);

    if (type === 'MIC') {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          audio: {
            echoCancellation: false,
            noiseSuppression: false,
            autoGainControl: false,
          },
        });
        this.micStream = stream;
        this.micSource = this.ctx.createMediaStreamSource(stream);
        this.micSource.connect(this.filterNode);
      } catch (err) {
        console.warn('Microphone permission denied or not available, falling back to generator', err);
        this.config.sourceType = 'GENERATOR';
        await this.setupGenerator();
      }
    } else if (type === 'GENERATOR') {
      await this.setupGenerator();
    } else if (type === 'FILE') {
      if (this.fileSourceNode) {
        this.fileSourceNode.connect(this.filterNode);
        // Also connect to destination for user to hear audio
        this.gainNode.connect(this.ctx.destination);
        if (this.fileAudioElement) {
          this.fileAudioElement.play().catch(() => {});
        }
      }
    }
  }

  private stopCurrentSource(): void {
    if (this.micStream) {
      this.micStream.getTracks().forEach((track) => track.stop());
      this.micStream = null;
    }
    if (this.micSource) {
      try {
        this.micSource.disconnect();
      } catch {}
      this.micSource = null;
    }
    if (this.genOsc) {
      try {
        this.genOsc.stop();
        this.genOsc.disconnect();
      } catch {}
      this.genOsc = null;
    }
    if (this.genNoiseNode) {
      try {
        this.genNoiseNode.stop();
        this.genNoiseNode.disconnect();
      } catch {}
      this.genNoiseNode = null;
    }
    if (this.sweepInterval !== null) {
      window.clearInterval(this.sweepInterval);
      this.sweepInterval = null;
    }
    if (this.fileAudioElement) {
      this.fileAudioElement.pause();
    }
  }

  // --- Audio Generator Implementation ---
  public async setupGenerator(): Promise<void> {
    if (!this.ctx || !this.filterNode) return;

    if (this.config.genWaveform === 'WHITE_NOISE') {
      this.setupWhiteNoise();
    } else if (this.config.genWaveform === 'PINK_NOISE') {
      this.setupPinkNoise();
    } else if (this.config.genWaveform === 'LOG_SWEEP') {
      this.setupLogSweep();
    } else {
      // Standard waveforms: SINE, SQUARE, SAWTOOTH, TRIANGLE
      this.genOsc = this.ctx.createOscillator();
      this.genGain = this.ctx.createGain();

      const typeMap: Record<string, OscillatorType> = {
        SINE: 'sine',
        SQUARE: 'square',
        SAWTOOTH: 'sawtooth',
        TRIANGLE: 'triangle',
      };
      this.genOsc.type = typeMap[this.config.genWaveform] || 'sine';
      this.genOsc.frequency.setValueAtTime(this.config.genFreq, this.ctx.currentTime);

      this.genGain.gain.setValueAtTime(this.config.genVol, this.ctx.currentTime);

      this.genOsc.connect(this.genGain);
      this.genGain.connect(this.filterNode);
      // Optional destination connection so user can hear the signal generator at low volume
      this.genGain.connect(this.ctx.destination);
      this.genOsc.start();
    }
  }

  private setupWhiteNoise(): void {
    if (!this.ctx || !this.filterNode) return;
    const bufferSize = this.ctx.sampleRate * 2;
    const buffer = this.ctx.createBuffer(1, bufferSize, this.ctx.sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < bufferSize; i++) {
      data[i] = Math.random() * 2 - 1;
    }

    this.genNoiseNode = this.ctx.createBufferSource();
    this.genNoiseNode.buffer = buffer;
    this.genNoiseNode.loop = true;

    this.genGain = this.ctx.createGain();
    this.genGain.gain.setValueAtTime(this.config.genVol * 0.5, this.ctx.currentTime);

    this.genNoiseNode.connect(this.genGain);
    this.genGain.connect(this.filterNode);
    this.genGain.connect(this.ctx.destination);
    this.genNoiseNode.start();
  }

  private setupPinkNoise(): void {
    if (!this.ctx || !this.filterNode) return;
    const bufferSize = this.ctx.sampleRate * 2;
    const buffer = this.ctx.createBuffer(1, bufferSize, this.ctx.sampleRate);
    const data = buffer.getChannelData(0);

    // Paul Kellet's filter method for pink noise
    let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
    for (let i = 0; i < bufferSize; i++) {
      const white = Math.random() * 2 - 1;
      b0 = 0.99886 * b0 + white * 0.0555179;
      b1 = 0.99332 * b1 + white * 0.0750759;
      b2 = 0.969 * b2 + white * 0.153852;
      b3 = 0.8665 * b3 + white * 0.3104856;
      b4 = 0.55 * b4 + white * 0.5329522;
      b5 = -0.7616 * b5 - white * 0.016898;
      data[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11;
      b6 = white * 0.115926;
    }

    this.genNoiseNode = this.ctx.createBufferSource();
    this.genNoiseNode.buffer = buffer;
    this.genNoiseNode.loop = true;

    this.genGain = this.ctx.createGain();
    this.genGain.gain.setValueAtTime(this.config.genVol * 0.6, this.ctx.currentTime);

    this.genNoiseNode.connect(this.genGain);
    this.genGain.connect(this.filterNode);
    this.genGain.connect(this.ctx.destination);
    this.genNoiseNode.start();
  }

  private setupLogSweep(): void {
    if (!this.ctx || !this.filterNode) return;
    this.genOsc = this.ctx.createOscillator();
    this.genGain = this.ctx.createGain();
    this.genOsc.type = 'sine';

    let currentFreq = 20;
    this.genOsc.frequency.setValueAtTime(currentFreq, this.ctx.currentTime);
    this.genGain.gain.setValueAtTime(this.config.genVol, this.ctx.currentTime);

    this.genOsc.connect(this.genGain);
    this.genGain.connect(this.filterNode);
    this.genGain.connect(this.ctx.destination);
    this.genOsc.start();

    // 5-second log sweep 20 Hz to 20,000 Hz repeat
    const startFreq = 20;
    const endFreq = 20000;
    const duration = 5; // seconds
    let startTime = performance.now();

    this.sweepInterval = window.setInterval(() => {
      if (!this.ctx || !this.genOsc) return;
      const elapsed = ((performance.now() - startTime) / 1000) % duration;
      const factor = elapsed / duration;
      currentFreq = startFreq * Math.pow(endFreq / startFreq, factor);
      this.genOsc.frequency.setValueAtTime(currentFreq, this.ctx.currentTime);
    }, 20);
  }

  public setGenWaveform(waveform: GenWaveform): void {
    this.config.genWaveform = waveform;
    if (this.isRunning && this.config.sourceType === 'GENERATOR') {
      this.setupGenerator();
    }
  }

  public setGenFreq(freq: number): void {
    this.config.genFreq = Math.max(10, Math.min(22000, freq));
    if (this.genOsc && this.ctx && this.config.genWaveform !== 'LOG_SWEEP') {
      this.genOsc.frequency.setValueAtTime(this.config.genFreq, this.ctx.currentTime);
    }
  }

  public setGenVol(vol: number): void {
    this.config.genVol = Math.max(0, Math.min(1, vol));
    if (this.genGain && this.ctx) {
      this.genGain.gain.setValueAtTime(this.config.genVol, this.ctx.currentTime);
    }
  }

  // --- Audio File Handling ---
  public loadAudioFile(file: File): void {
    if (!this.ctx) this.init();
    if (!this.ctx) return;

    if (this.fileAudioElement) {
      this.fileAudioElement.pause();
      this.fileAudioElement.src = '';
    }

    const url = URL.createObjectURL(file);
    this.fileAudioElement = new Audio(url);
    this.fileAudioElement.loop = true;
    this.fileAudioElement.crossOrigin = 'anonymous';

    this.fileSourceNode = this.ctx.createMediaElementSource(this.fileAudioElement);
    this.setSourceType('FILE');
  }

  public playFile(): void {
    if (this.fileAudioElement) {
      this.fileAudioElement.play();
    }
  }

  public pauseFile(): void {
    if (this.fileAudioElement) {
      this.fileAudioElement.pause();
    }
  }

  // --- Gain & Filter Controls ---
  public setGainDb(gainDb: number): void {
    this.config.inputGainDb = gainDb;
    if (this.gainNode && this.ctx) {
      const linear = Math.pow(10, gainDb / 20);
      this.gainNode.gain.setValueAtTime(linear, this.ctx.currentTime);
    }
  }

  public setFftSize(size: number): void {
    this.config.fftSize = size;
    if (this.analyser) {
      this.analyser.fftSize = size;
    }
    this.updateBufferSizes();
  }

  public setSmoothing(s: number): void {
    this.config.smoothing = Math.max(0, Math.min(0.95, s));
    if (this.analyser) {
      this.analyser.smoothingTimeConstant = this.config.smoothing;
    }
  }

  public setFilterType(type: FilterType): void {
    this.config.filterType = type;
    this.applyBiquadFilterNode(type);
    this.biquadSoftwareFilter.setFilter(type, this.getSampleRate());
  }

  private applyBiquadFilterNode(type: FilterType): void {
    if (!this.filterNode || !this.ctx) return;
    const sampleRate = this.ctx.sampleRate;

    switch (type) {
      case 'NOTCH50':
        this.filterNode.type = 'notch';
        this.filterNode.frequency.setValueAtTime(50, this.ctx.currentTime);
        this.filterNode.Q.setValueAtTime(10, this.ctx.currentTime);
        break;
      case 'HIGHPASS100':
        this.filterNode.type = 'highpass';
        this.filterNode.frequency.setValueAtTime(100, this.ctx.currentTime);
        this.filterNode.Q.setValueAtTime(0.707, this.ctx.currentTime);
        break;
      case 'LOWPASS5000':
        this.filterNode.type = 'lowpass';
        this.filterNode.frequency.setValueAtTime(5000, this.ctx.currentTime);
        this.filterNode.Q.setValueAtTime(0.707, this.ctx.currentTime);
        break;
      case 'BANDPASS':
        this.filterNode.type = 'bandpass';
        this.filterNode.frequency.setValueAtTime(1000, this.ctx.currentTime);
        this.filterNode.Q.setValueAtTime(1.0, this.ctx.currentTime);
        break;
      case 'ALLPASS':
      default:
        this.filterNode.type = 'allpass';
        this.filterNode.frequency.setValueAtTime(1000, this.ctx.currentTime);
        this.filterNode.Q.setValueAtTime(1.0, this.ctx.currentTime);
        break;
    }
  }

  // --- Real-time Recording ---
  public startRecording(): void {
    this.recordedChunks = [];
    this.isRecording = true;
  }

  public stopRecording(): Blob {
    this.isRecording = false;
    const blob = encodeWAV(this.recordedChunks, this.getSampleRate(), 1);
    this.recordedChunks = [];
    return blob;
  }

  public getIsRecording(): boolean {
    return this.isRecording;
  }

  // --- Processing Loop & Signal Analysis ---
  private startProcessingLoop(): void {
    const loop = () => {
      if (!this.isRunning) return;

      if (!this.isFrozen && this.analyser) {
        // Read FFT frequency and PCM time data
        this.analyser.getByteFrequencyData(this.byteFreqData as any);
        this.analyser.getFloatFrequencyData(this.floatFreqData as any);
        this.analyser.getFloatTimeDomainData(this.timeData as any);

        // Record PCM chunk if recording
        if (this.isRecording) {
          const chunk = new Float32Array(this.timeData.length);
          chunk.set(this.timeData);
          this.recordedChunks.push(chunk);
        }

        // Peak Hold update
        const numBins = this.byteFreqData.length;
        let maxByteVal = -1;
        let maxBinIndex = 0;
        let sumSquares = 0;
        let maxAbsSample = 0;

        for (let i = 0; i < numBins; i++) {
          const val = this.byteFreqData[i];
          if (val > maxByteVal) {
            maxByteVal = val;
            maxBinIndex = i;
          }
          if (val >= this.peakData[i]) {
            this.peakData[i] = val;
          } else {
            this.peakData[i] = Math.max(0, this.peakData[i] - 1.5);
          }
        }

        // Time domain analysis (RMS and Peak)
        for (let i = 0; i < this.timeData.length; i++) {
          const s = this.timeData[i];
          sumSquares += s * s;
          const abs = Math.abs(s);
          if (abs > maxAbsSample) maxAbsSample = abs;
        }

        const rms = Math.sqrt(sumSquares / this.timeData.length);
        const rmsDb = rms > 1e-5 ? 20 * Math.log10(rms) : -100;
        const peakDb = maxAbsSample > 1e-5 ? 20 * Math.log10(maxAbsSample) : -100;

        // Parabolic Interpolation for Sub-Bin Peak Frequency
        const sampleRate = this.getSampleRate();
        let peakFreqHz = maxBinIndex * (sampleRate / this.config.fftSize);

        if (maxBinIndex > 0 && maxBinIndex < numBins - 1) {
          const alpha = this.floatFreqData[maxBinIndex - 1];
          const beta = this.floatFreqData[maxBinIndex];
          const gamma = this.floatFreqData[maxBinIndex + 1];
          const denom = alpha - 2 * beta + gamma;
          if (Math.abs(denom) > 1e-5) {
            const delta = (0.5 * (alpha - gamma)) / denom;
            const clampedDelta = Math.max(-0.5, Math.min(0.5, delta));
            peakFreqHz = (maxBinIndex + clampedDelta) * (sampleRate / this.config.fftSize);
          }
        }

        // Pitch & Note Calculation
        let noteName = '--';
        let noteCents = 0;
        if (peakFreqHz > 20 && peakFreqHz < 15000) {
          const pitch = 12 * (Math.log(peakFreqHz / 440.0) / Math.log(2)) + 69;
          const noteIndex = Math.round(pitch);
          noteCents = Math.round((pitch - noteIndex) * 100);
          const noteNumber = ((noteIndex % 12) + 12) % 12;
          const octave = Math.floor(noteIndex / 12) - 1;
          noteName = `${NOTE_NAMES[noteNumber]}${octave}`;
        }

        // THD% & SNR Calculation
        const f0 = peakFreqHz;
        let thd = 0;
        let snr = 0;
        if (f0 > 30 && f0 < sampleRate / 4) {
          const v1 = this.getLinearAmp(f0, numBins, sampleRate);
          const v2 = this.getLinearAmp(f0 * 2, numBins, sampleRate);
          const v3 = this.getLinearAmp(f0 * 3, numBins, sampleRate);
          const v4 = this.getLinearAmp(f0 * 4, numBins, sampleRate);

          if (v1 > 1e-5) {
            const harmonicSum = Math.sqrt(v2 * v2 + v3 * v3 + v4 * v4);
            thd = (harmonicSum / v1) * 100;
            snr = 20 * Math.log10(v1 / Math.max(1e-5, harmonicSum));
          }
        }

        // BPM Transient Detection
        const bassBins = Math.max(1, Math.floor((150.0 / (sampleRate / 2)) * numBins));
        let bassEnergy = 0;
        for (let i = 0; i < bassBins; i++) {
          bassEnergy += this.byteFreqData[i];
        }
        bassEnergy /= bassBins;

        this.bpmHistory[this.bpmHistIdx] = bassEnergy;
        this.bpmHistIdx = (this.bpmHistIdx + 1) % 30;

        let avgEnergy = 0;
        for (const e of this.bpmHistory) avgEnergy += e;
        avgEnergy /= 30.0;

        const nowMs = performance.now();
        if (
          bassEnergy > avgEnergy * 1.35 &&
          bassEnergy > 55 &&
          nowMs - this.lastBeatTime > 280
        ) {
          const delta = nowMs - this.lastBeatTime;
          this.lastBeatTime = nowMs;
          if (delta < 2000) {
            this.currentBpm = Math.round(60000.0 / delta);
          }
        }

        this.metrics = {
          peakFreqHz: Math.max(0, peakFreqHz),
          noteName,
          noteCents,
          f0,
          thd: Math.max(0, Math.min(100, thd)),
          snr: Math.max(-50, Math.min(120, snr)),
          rmsDb: Math.max(-100, Math.min(0, rmsDb)),
          peakDb: Math.max(-100, Math.min(0, peakDb)),
          bpm: this.currentBpm,
        };

        if (this.onMetricsUpdate) {
          this.onMetricsUpdate(this.metrics);
        }
      }

      this.animationFrameId = requestAnimationFrame(loop);
    };

    this.animationFrameId = requestAnimationFrame(loop);
  }

  private getLinearAmp(freqHz: number, numBins: number, sampleRate: number): number {
    const bin = Math.round((freqHz / (sampleRate / 2.0)) * numBins);
    if (bin <= 0 || bin >= numBins) return 0;
    return Math.pow(10, this.floatFreqData[bin] / 20.0);
  }

  // Buffers access for Visualizers
  public getByteFreqData(): Uint8Array {
    return this.byteFreqData;
  }

  public getFloatFreqData(): Float32Array {
    return this.floatFreqData;
  }

  public getTimeData(): Float32Array {
    return this.timeData;
  }

  public getPeakData(): Float32Array {
    return this.peakData;
  }

  public getMetrics(): AudioMetrics {
    return this.metrics;
  }

  public getConfig(): AudioEngineConfig {
    return this.config;
  }

  // CSV Data Exporter
  public exportCSV(): string {
    const numBins = this.floatFreqData.length;
    const nyquist = this.getSampleRate() / 2.0;
    let csv = 'Frequency_Hz,Amplitude_dB\n';
    for (let i = 0; i < numBins; i++) {
      const freq = ((i / numBins) * nyquist).toFixed(1);
      const db = this.floatFreqData[i].toFixed(2);
      csv += `${freq},${db}\n`;
    }
    return csv;
  }
}
