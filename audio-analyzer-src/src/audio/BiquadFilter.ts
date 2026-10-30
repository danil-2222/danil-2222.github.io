import { FilterType } from '../types';

export class BiquadFilter {
  private a0: number = 1;
  private a1: number = 0;
  private a2: number = 0;
  private b0: number = 1;
  private b1: number = 0;
  private b2: number = 0;
  private x1: number = 0;
  private x2: number = 0;
  private y1: number = 0;
  private y2: number = 0;
  private currentType: FilterType = 'ALLPASS';

  constructor(type: FilterType = 'ALLPASS', sampleRate: number = 44100) {
    this.setFilter(type, sampleRate);
  }

  public setFilter(type: FilterType, sampleRate: number = 44100): void {
    this.currentType = type;
    this.x1 = 0;
    this.x2 = 0;
    this.y1 = 0;
    this.y2 = 0;

    let w0 = 0;
    let alpha = 0;

    switch (type) {
      case 'NOTCH50': {
        // Notch 50 Hz, Q = 10
        w0 = (2 * Math.PI * 50) / sampleRate;
        alpha = Math.sin(w0) / (2 * 10);
        this.b0 = 1;
        this.b1 = -2 * Math.cos(w0);
        this.b2 = 1;
        this.a0 = 1 + alpha;
        this.a1 = -2 * Math.cos(w0);
        this.a2 = 1 - alpha;
        break;
      }
      case 'HIGHPASS100': {
        // HPF 100 Hz, Q = 0.707
        w0 = (2 * Math.PI * 100) / sampleRate;
        alpha = Math.sin(w0) / (2 * 0.707);
        this.b0 = (1 + Math.cos(w0)) / 2;
        this.b1 = -(1 + Math.cos(w0));
        this.b2 = (1 + Math.cos(w0)) / 2;
        this.a0 = 1 + alpha;
        this.a1 = -2 * Math.cos(w0);
        this.a2 = 1 - alpha;
        break;
      }
      case 'LOWPASS5000': {
        // LPF 5000 Hz, Q = 0.707
        w0 = (2 * Math.PI * 5000) / sampleRate;
        alpha = Math.sin(w0) / (2 * 0.707);
        this.b0 = (1 - Math.cos(w0)) / 2;
        this.b1 = 1 - Math.cos(w0);
        this.b2 = (1 - Math.cos(w0)) / 2;
        this.a0 = 1 + alpha;
        this.a1 = -2 * Math.cos(w0);
        this.a2 = 1 - alpha;
        break;
      }
      case 'BANDPASS': {
        // BPF 1000 Hz, Q = 1.0
        w0 = (2 * Math.PI * 1000) / sampleRate;
        alpha = Math.sin(w0) / (2 * 1.0);
        this.b0 = alpha;
        this.b1 = 0;
        this.b2 = -alpha;
        this.a0 = 1 + alpha;
        this.a1 = -2 * Math.cos(w0);
        this.a2 = 1 - alpha;
        break;
      }
      case 'ALLPASS':
      default: {
        this.b0 = 1;
        this.b1 = 0;
        this.b2 = 0;
        this.a0 = 1;
        this.a1 = 0;
        this.a2 = 0;
        break;
      }
    }

    // Normalization by a0
    this.b0 /= this.a0;
    this.b1 /= this.a0;
    this.b2 /= this.a0;
    this.a1 /= this.a0;
    this.a2 /= this.a0;
    this.a0 = 1;
  }

  public process(x: number): number {
    if (this.currentType === 'ALLPASS') return x;
    const y =
      this.b0 * x +
      this.b1 * this.x1 +
      this.b2 * this.x2 -
      this.a1 * this.y1 -
      this.a2 * this.y2;
    this.x2 = this.x1;
    this.x1 = x;
    this.y2 = this.y1;
    this.y1 = y;
    return y;
  }

  public processBuffer(input: Float32Array, output: Float32Array): void {
    if (this.currentType === 'ALLPASS') {
      output.set(input);
      return;
    }
    for (let i = 0; i < input.length; i++) {
      output[i] = this.process(input[i]);
    }
  }

  public getType(): FilterType {
    return this.currentType;
  }
}
