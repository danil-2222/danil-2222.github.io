export function encodeWAV(
  samples: Float32Array[],
  sampleRate: number = 44100,
  numChannels: number = 1
): Blob {
  let totalLength = 0;
  for (const chunk of samples) {
    totalLength += chunk.length;
  }

  const bytesPerSample = 2; // 16-bit PCM
  const blockAlign = numChannels * bytesPerSample;
  const byteRate = sampleRate * blockAlign;
  const dataSize = totalLength * blockAlign;
  const buffer = new ArrayBuffer(44 + dataSize);
  const view = new DataView(buffer);

  function writeString(offset: number, str: string) {
    for (let i = 0; i < str.length; i++) {
      view.setUint8(offset + i, str.charCodeAt(i));
    }
  }

  /* RIFF chunk descriptor */
  writeString(0, 'RIFF');
  view.setUint32(4, 36 + dataSize, true);
  writeString(8, 'WAVE');

  /* "fmt " sub-chunk */
  writeString(12, 'fmt ');
  view.setUint32(16, 16, true); // 16 for PCM
  view.setUint16(20, 1, true); // Linear quantization (PCM)
  view.setUint16(22, numChannels, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, byteRate, true);
  view.setUint16(32, blockAlign, true);
  view.setUint16(34, bytesPerSample * 8, true);

  /* "data" sub-chunk */
  writeString(36, 'data');
  view.setUint32(40, dataSize, true);

  // Write PCM samples
  let offset = 44;
  for (const chunk of samples) {
    for (let i = 0; i < chunk.length; i++) {
      let s = Math.max(-1, Math.min(1, chunk[i]));
      const intSample = s < 0 ? s * 0x8000 : s * 0x7fff;
      view.setInt16(offset, intSample, true);
      offset += 2;
    }
  }

  return new Blob([buffer], { type: 'audio/wav' });
}
