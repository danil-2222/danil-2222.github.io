import React, { useEffect, useRef, useState } from 'react';
import { ColorPalette, RTA_ISO_FREQS, VisualizerMode } from '../types';
import { getColorForValue } from '../utils/palette';

interface Visualizer2DProps {
  mode: VisualizerMode;
  palette: ColorPalette;
  isLogScale: boolean;
  sampleRate: number;
  getByteFreqData: () => Uint8Array;
  getFloatFreqData: () => Float32Array;
  getTimeData: () => Float32Array;
  getPeakData: () => Float32Array;
  peakFreqHz: number;
  noteName: string;
  noteCents: number;
}

export const Visualizer2D: React.FC<Visualizer2DProps> = ({
  mode,
  palette,
  isLogScale,
  sampleRate,
  getByteFreqData,
  getFloatFreqData,
  getTimeData,
  getPeakData,
  peakFreqHz,
  noteName,
  noteCents,
}) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const waterfallCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const [hoverInfo, setHoverInfo] = useState<{ x: number; y: number; freq: number; db: number } | null>(null);

  // Initialize or resize offscreen waterfall canvas
  useEffect(() => {
    if (!waterfallCanvasRef.current) {
      waterfallCanvasRef.current = document.createElement('canvas');
      waterfallCanvasRef.current.width = 1200;
      waterfallCanvasRef.current.height = 600;
      const ctx = waterfallCanvasRef.current.getContext('2d');
      if (ctx) {
        ctx.fillStyle = '#080C14';
        ctx.fillRect(0, 0, 1200, 600);
      }
    }
  }, []);

  useEffect(() => {
    let animId: number;

    const render = () => {
      const canvas = canvasRef.current;
      if (!canvas) {
        animId = requestAnimationFrame(render);
        return;
      }

      const ctx = canvas.getContext('2d', { alpha: false });
      if (!ctx) {
        animId = requestAnimationFrame(render);
        return;
      }

      const w = canvas.width;
      const h = canvas.height;

      // Dark background
      ctx.fillStyle = '#080C14';
      ctx.fillRect(0, 0, w, h);

      const byteData = getByteFreqData();
      const floatData = getFloatFreqData();
      const timeData = getTimeData();
      const peakData = getPeakData();

      switch (mode) {
        case 'BARS_2D':
          drawBars(ctx, w, h, byteData, peakData, palette, isLogScale, sampleRate);
          break;
        case 'LINE_2D':
          drawLine(ctx, w, h, byteData, palette, isLogScale, sampleRate);
          break;
        case 'RTA_1_3':
          drawRTA(ctx, w, h, byteData, palette, sampleRate);
          break;
        case 'WATERFALL_2D':
          drawWaterfall(ctx, w, h, byteData, palette);
          break;
        case 'OSCILLOSCOPE':
          drawOscilloscope(ctx, w, h, timeData);
          break;
        case 'TUNER':
          drawTuner(ctx, w, h, peakFreqHz, noteName, noteCents);
          break;
      }

      // Draw Grid / Axes
      if (mode === 'BARS_2D' || mode === 'LINE_2D') {
        drawAxesAndGrid(ctx, w, h, sampleRate, isLogScale);
      }

      animId = requestAnimationFrame(render);
    };

    animId = requestAnimationFrame(render);
    return () => cancelAnimationFrame(animId);
  }, [mode, palette, isLogScale, sampleRate, peakFreqHz, noteName, noteCents, getByteFreqData, getFloatFreqData, getTimeData, getPeakData]);

  // --- 1. 2D BARS ---
  const drawBars = (
    ctx: CanvasRenderingContext2D,
    w: number,
    h: number,
    byteData: Uint8Array,
    peakData: Float32Array,
    curPalette: ColorPalette,
    logScale: boolean,
    sRate: number
  ) => {
    if (byteData.length === 0) return;
    const bottomY = h - 30;
    const chartHeight = bottomY - 10;
    const numBins = byteData.length;
    const nyquist = sRate / 2;

    const barCount = Math.min(180, Math.floor(w / 3.5));
    const barWidth = w / barCount;

    for (let i = 0; i < barCount; i++) {
      let binIndex: number;
      if (logScale) {
        // Logarithmic distribution: 20 Hz to Nyquist
        const fMin = 20;
        const fMax = nyquist;
        const f = fMin * Math.pow(fMax / fMin, i / barCount);
        binIndex = Math.floor((f / nyquist) * numBins);
      } else {
        binIndex = Math.floor((i / barCount) * numBins);
      }
      binIndex = Math.max(0, Math.min(numBins - 1, binIndex));

      const val = byteData[binIndex] / 255.0;
      const barH = val * chartHeight;
      const x = i * barWidth;
      const y = bottomY - barH;

      ctx.fillStyle = getColorForValue(val, curPalette);
      ctx.fillRect(x + 0.5, y, Math.max(1, barWidth - 1), barH);

      // Peak Hold Cap
      const peakVal = peakData[binIndex] / 255.0;
      if (peakVal > 0.02) {
        const peakY = bottomY - peakVal * chartHeight;
        ctx.fillStyle = '#00F0FF';
        ctx.shadowColor = '#00F0FF';
        ctx.shadowBlur = 4;
        ctx.fillRect(x + 0.5, peakY - 2, Math.max(1, barWidth - 1), 2.5);
        ctx.shadowBlur = 0;
      }
    }
  };

  // --- 2. 2D LINE ---
  const drawLine = (
    ctx: CanvasRenderingContext2D,
    w: number,
    h: number,
    byteData: Uint8Array,
    curPalette: ColorPalette,
    logScale: boolean,
    sRate: number
  ) => {
    if (byteData.length === 0) return;
    const bottomY = h - 30;
    const chartHeight = bottomY - 10;
    const numBins = byteData.length;
    const nyquist = sRate / 2;

    const pointsCount = Math.min(300, w);
    const points: [number, number][] = [];

    for (let i = 0; i < pointsCount; i++) {
      let binIndex: number;
      if (logScale) {
        const f = 20 * Math.pow(nyquist / 20, i / pointsCount);
        binIndex = Math.floor((f / nyquist) * numBins);
      } else {
        binIndex = Math.floor((i / pointsCount) * numBins);
      }
      binIndex = Math.max(0, Math.min(numBins - 1, binIndex));

      const val = byteData[binIndex] / 255.0;
      const x = (i / pointsCount) * w;
      const y = bottomY - val * chartHeight;
      points.push([x, y]);
    }

    if (points.length < 2) return;

    // Gradient fill under the curve
    const gradient = ctx.createLinearGradient(0, 0, 0, bottomY);
    gradient.addColorStop(0, curPalette === 'CYBERPUNK' ? 'rgba(255, 0, 127, 0.65)' : 'rgba(0, 240, 255, 0.65)');
    gradient.addColorStop(0.7, 'rgba(0, 160, 255, 0.15)');
    gradient.addColorStop(1, 'rgba(0, 0, 0, 0)');

    ctx.beginPath();
    ctx.moveTo(points[0][0], bottomY);
    ctx.lineTo(points[0][0], points[0][1]);
    for (let i = 1; i < points.length; i++) {
      const prev = points[i - 1];
      const cur = points[i];
      const mx = (prev[0] + cur[0]) / 2;
      const my = (prev[1] + cur[1]) / 2;
      ctx.quadraticCurveTo(prev[0], prev[1], mx, my);
    }
    ctx.lineTo(w, points[points.length - 1][1]);
    ctx.lineTo(w, bottomY);
    ctx.closePath();

    ctx.fillStyle = gradient;
    ctx.fill();

    // Glowing Neon Stroke
    ctx.beginPath();
    ctx.moveTo(points[0][0], points[0][1]);
    for (let i = 1; i < points.length; i++) {
      const prev = points[i - 1];
      const cur = points[i];
      const mx = (prev[0] + cur[0]) / 2;
      const my = (prev[1] + cur[1]) / 2;
      ctx.quadraticCurveTo(prev[0], prev[1], mx, my);
    }
    ctx.strokeStyle = curPalette === 'CYBERPUNK' ? '#FF007F' : '#00F0FF';
    ctx.lineWidth = 2.5;
    ctx.shadowColor = curPalette === 'CYBERPUNK' ? '#FF007F' : '#00F0FF';
    ctx.shadowBlur = 8;
    ctx.stroke();
    ctx.shadowBlur = 0;
  };

  // --- 3. ISO 1/3 OCTAVE RTA ---
  const drawRTA = (
    ctx: CanvasRenderingContext2D,
    w: number,
    h: number,
    byteData: Uint8Array,
    curPalette: ColorPalette,
    sRate: number
  ) => {
    if (byteData.length === 0) return;
    const bands = RTA_ISO_FREQS.length;
    const bottomY = h - 35;
    const chartHeight = bottomY - 15;
    const barWidth = w / bands;
    const nyquist = sRate / 2;

    ctx.font = '10px "JetBrains Mono", monospace';
    ctx.textAlign = 'center';

    for (let i = 0; i < bands; i++) {
      const fc = RTA_ISO_FREQS[i];
      const bin = Math.min(byteData.length - 1, Math.floor((fc / nyquist) * byteData.length));
      const val = byteData[bin] / 255.0;
      const barH = val * chartHeight;
      const x = i * barWidth;
      const y = bottomY - barH;

      // Glow & Bar
      ctx.fillStyle = getColorForValue(val, curPalette);
      ctx.fillRect(x + 2, y, barWidth - 4, barH);

      // Subtle Top Accent
      ctx.fillStyle = '#FFFFFF';
      ctx.fillRect(x + 2, y, barWidth - 4, 1.5);

      // Band text label
      ctx.fillStyle = '#7F93A8';
      const label = fc >= 1000 ? `${fc / 1000}k` : `${fc}`;
      ctx.fillText(label, x + barWidth / 2, h - 12);
    }
  };

  // --- 4. 2D WATERFALL / SPECTROGRAM ---
  const drawWaterfall = (
    ctx: CanvasRenderingContext2D,
    w: number,
    h: number,
    byteData: Uint8Array,
    curPalette: ColorPalette
  ) => {
    const off = waterfallCanvasRef.current;
    if (!off || byteData.length === 0) return;

    if (off.width !== w || off.height !== h) {
      off.width = w;
      off.height = h;
      const offCtx = off.getContext('2d');
      if (offCtx) {
        offCtx.fillStyle = '#080C14';
        offCtx.fillRect(0, 0, w, h);
      }
    }

    const offCtx = off.getContext('2d', { willReadFrequently: true });
    if (!offCtx) return;

    // Scroll downwards by 2 pixels
    offCtx.drawImage(off, 0, 0, w, h - 2, 0, 2, w, h - 2);

    // Draw the newest row of FFT bins at the top (y = 0..1)
    const numBins = byteData.length;
    const imgData = offCtx.createImageData(w, 2);
    const data = imgData.data;

    for (let x = 0; x < w; x++) {
      const bin = Math.min(numBins - 1, Math.floor((x / w) * numBins));
      const val = byteData[bin] / 255.0;

      // Color lookup
      const colStr = getColorForValue(val, curPalette);
      // Parse rgb
      const rgb = colStr.match(/\d+/g);
      const r = rgb ? parseInt(rgb[0], 10) : 0;
      const g = rgb ? parseInt(rgb[1], 10) : 0;
      const b = rgb ? parseInt(rgb[2], 10) : 0;

      for (let row = 0; row < 2; row++) {
        const idx = (row * w + x) * 4;
        data[idx] = r;
        data[idx + 1] = g;
        data[idx + 2] = b;
        data[idx + 3] = 255;
      }
    }

    offCtx.putImageData(imgData, 0, 0);

    // Render offscreen canvas onto primary canvas
    ctx.drawImage(off, 0, 0);

    // Overlay subtle timeline tick markers
    ctx.fillStyle = 'rgba(255, 255, 255, 0.4)';
    ctx.font = '10px "JetBrains Mono", monospace';
    ctx.fillText('0s (Live)', 10, 16);
    ctx.fillText('-5s', 10, h / 2);
    ctx.fillText('-10s', 10, h - 16);
  };

  // --- 5. OSCILLOSCOPE ---
  const drawOscilloscope = (
    ctx: CanvasRenderingContext2D,
    w: number,
    h: number,
    timeData: Float32Array
  ) => {
    if (timeData.length === 0) return;

    const centerY = h / 2;

    // Grid center line
    ctx.strokeStyle = 'rgba(30, 41, 59, 0.8)';
    ctx.lineWidth = 1;
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(0, centerY);
    ctx.lineTo(w, centerY);
    ctx.stroke();
    ctx.setLineDash([]);

    // Zero-crossing trigger search for stable display
    let startIdx = 0;
    const searchLimit = Math.min(500, timeData.length / 2);
    for (let i = 1; i < searchLimit; i++) {
      if (timeData[i - 1] < 0 && timeData[i] >= 0) {
        startIdx = i;
        break;
      }
    }

    const samplesToDraw = Math.min(1024, timeData.length - startIdx);
    const sliceW = w / samplesToDraw;

    ctx.beginPath();
    ctx.strokeStyle = '#00F0FF';
    ctx.lineWidth = 2.2;
    ctx.shadowColor = '#00F0FF';
    ctx.shadowBlur = 6;

    for (let i = 0; i < samplesToDraw; i++) {
      const s = timeData[startIdx + i];
      const x = i * sliceW;
      const y = centerY - s * (h * 0.45);
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();
    ctx.shadowBlur = 0;
  };

  // --- 6. TUNER ---
  const drawTuner = (
    ctx: CanvasRenderingContext2D,
    w: number,
    h: number,
    peakFreq: number,
    note: string,
    cents: number
  ) => {
    const cx = w / 2;
    const cy = h / 2 + 50;
    const radius = Math.min(w, h) * 0.36;

    // Arc track
    ctx.beginPath();
    ctx.arc(cx, cy, radius, Math.PI, 2 * Math.PI, false);
    ctx.strokeStyle = '#1E293B';
    ctx.lineWidth = 16;
    ctx.lineCap = 'round';
    ctx.stroke();

    // Cent tick markers
    for (let c = -50; c <= 50; c += 10) {
      const angle = Math.PI + ((c + 50) / 100) * Math.PI;
      const isCenter = c === 0;
      const tickLen = isCenter ? 20 : 10;
      const x1 = cx + Math.cos(angle) * (radius - 12);
      const y1 = cy + Math.sin(angle) * (radius - 12);
      const x2 = cx + Math.cos(angle) * (radius - 12 - tickLen);
      const y2 = cy + Math.sin(angle) * (radius - 12 - tickLen);

      ctx.beginPath();
      ctx.moveTo(x1, y1);
      ctx.lineTo(x2, y2);
      ctx.strokeStyle = isCenter ? '#39FF14' : '#64748B';
      ctx.lineWidth = isCenter ? 3 : 1.5;
      ctx.stroke();

      if (c % 20 === 0) {
        ctx.fillStyle = '#94A3B8';
        ctx.font = '11px "JetBrains Mono", monospace';
        ctx.textAlign = 'center';
        const tx = cx + Math.cos(angle) * (radius - 38);
        const ty = cy + Math.sin(angle) * (radius - 38);
        ctx.fillText(`${c > 0 ? '+' : ''}${c}`, tx, ty);
      }
    }

    // In-tune threshold (within +/- 5 cents)
    const inTune = Math.abs(cents) <= 5 && peakFreq > 20;
    const needleColor = inTune ? '#39FF14' : '#FF007F';

    // Needle
    const clampedCents = Math.max(-50, Math.min(50, cents));
    const needleAngle = Math.PI + ((clampedCents + 50) / 100) * Math.PI;

    ctx.beginPath();
    ctx.moveTo(cx, cy);
    const nx = cx + Math.cos(needleAngle) * (radius - 6);
    const ny = cy + Math.sin(needleAngle) * (radius - 6);
    ctx.lineTo(nx, ny);
    ctx.strokeStyle = needleColor;
    ctx.lineWidth = 4;
    ctx.shadowColor = needleColor;
    ctx.shadowBlur = 12;
    ctx.stroke();
    ctx.shadowBlur = 0;

    // Center pivot circle
    ctx.beginPath();
    ctx.arc(cx, cy, 10, 0, 2 * Math.PI);
    ctx.fillStyle = needleColor;
    ctx.fill();

    // Large Note Display
    ctx.textAlign = 'center';
    ctx.font = 'bold 54px "Space Grotesk", sans-serif';
    ctx.fillStyle = inTune ? '#39FF14' : '#FFFFFF';
    ctx.fillText(note, cx, cy - 80);

    // Freq & Cents details
    ctx.font = '600 22px "JetBrains Mono", monospace';
    ctx.fillStyle = '#00F0FF';
    ctx.fillText(peakFreq > 10 ? `${peakFreq.toFixed(1)} Hz` : '-- Hz', cx, cy - 35);

    ctx.font = '14px "JetBrains Mono", monospace';
    ctx.fillStyle = inTune ? '#39FF14' : '#F43F5E';
    ctx.fillText(
      peakFreq > 10 ? `${cents > 0 ? '+' : ''}${cents} cents ${inTune ? '● IN TUNE' : ''}` : 'NO SIGNAL',
      cx,
      cy - 12
    );
  };

  // --- Grid & Axes ---
  const drawAxesAndGrid = (
    ctx: CanvasRenderingContext2D,
    w: number,
    h: number,
    sRate: number,
    logScale: boolean
  ) => {
    const bottomY = h - 30;
    const nyquist = sRate / 2;

    // Horizontal baseline
    ctx.strokeStyle = '#1E293B';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(0, bottomY);
    ctx.lineTo(w, bottomY);
    ctx.stroke();

    // Frequency markers
    const freqsToLabel = logScale
      ? [50, 100, 250, 500, 1000, 2500, 5000, 10000, 20000]
      : [2000, 5000, 10000, 15000, 20000];

    ctx.fillStyle = '#64748B';
    ctx.font = '10px "JetBrains Mono", monospace';
    ctx.textAlign = 'center';

    for (const f of freqsToLabel) {
      if (f > nyquist) continue;
      let x = 0;
      if (logScale) {
        x = (Math.log(f / 20) / Math.log(nyquist / 20)) * w;
      } else {
        x = (f / nyquist) * w;
      }

      ctx.strokeStyle = 'rgba(30, 41, 59, 0.4)';
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, bottomY);
      ctx.stroke();

      const label = f >= 1000 ? `${f / 1000}k` : `${f}`;
      ctx.fillText(label, x, h - 12);
    }

    // dB horizontal grid lines (-12, -24, -36, -48, -60, -72 dB)
    const dbSteps = [-12, -24, -36, -48, -60, -72];
    ctx.textAlign = 'left';
    for (const db of dbSteps) {
      const normY = ((db + 100) / 100) * (bottomY - 10);
      const y = bottomY - normY;

      ctx.strokeStyle = 'rgba(30, 41, 59, 0.3)';
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(w, y);
      ctx.stroke();

      ctx.fillStyle = '#475569';
      ctx.fillText(`${db}dB`, 8, y - 4);
    }
  };

  // Hover tracker
  const handleMouseMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const rect = canvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const w = rect.width;
    const h = rect.height;

    const nyquist = sampleRate / 2;
    let freq = 0;
    if (isLogScale) {
      freq = 20 * Math.pow(nyquist / 20, x / w);
    } else {
      freq = (x / w) * nyquist;
    }

    const bottomY = h - 30;
    const db = -100 + ((bottomY - y) / (bottomY - 10)) * 100;

    setHoverInfo({ x, y, freq, db });
  };

  const handleMouseLeave = () => {
    setHoverInfo(null);
  };

  return (
    <div className="relative w-full h-full select-none overflow-hidden">
      <canvas
        ref={canvasRef}
        width={1280}
        height={540}
        className="w-full h-full block cursor-crosshair touch-none"
        onMouseMove={handleMouseMove}
        onMouseLeave={handleMouseLeave}
      />

      {/* Interactive Crosshair Tooltip */}
      {hoverInfo && (mode === 'BARS_2D' || mode === 'LINE_2D') && (
        <div
          className="absolute pointer-events-none z-10 px-2 py-1 bg-slate-900/90 border border-cyan-500/50 rounded text-xs font-mono text-cyan-300 shadow-lg backdrop-blur-sm -translate-x-1/2 -translate-y-10"
          style={{ left: hoverInfo.x, top: hoverInfo.y }}
        >
          {hoverInfo.freq.toFixed(1)} Hz | {hoverInfo.db.toFixed(1)} dBFS
        </div>
      )}
    </div>
  );
};
