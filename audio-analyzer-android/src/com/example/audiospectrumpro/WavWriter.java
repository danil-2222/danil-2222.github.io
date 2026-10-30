package com.example.audiospectrumpro;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Writes 16-bit mono PCM WAV, patching the RIFF sizes on close. */
final class WavWriter {
    private final RandomAccessFile out;
    private final int sampleRate;
    private int dataBytes;
    private boolean closed;
    private byte[] scratch = new byte[0];

    WavWriter(File file, int sampleRate) throws IOException {
        this.sampleRate = sampleRate;
        out = new RandomAccessFile(file, "rw");
        out.setLength(0);
        writeHeader(0);
    }

    synchronized void write(float[] samples, int len) throws IOException {
        if (closed) return;
        if (scratch.length < len * 2) scratch = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, samples[i])) * 32767f);
            scratch[2 * i] = (byte) v;
            scratch[2 * i + 1] = (byte) (v >> 8);
        }
        out.write(scratch, 0, len * 2);
        dataBytes += len * 2;
    }

    synchronized void writePcm(short[] samples, int off, int len) throws IOException {
        if (closed) return;
        int chunk = 8192;
        if (scratch.length < chunk * 2) scratch = new byte[chunk * 2];
        for (int p = 0; p < len; p += chunk) {
            int n = Math.min(chunk, len - p);
            for (int i = 0; i < n; i++) {
                short v = samples[off + p + i];
                scratch[2 * i] = (byte) v;
                scratch[2 * i + 1] = (byte) (v >> 8);
            }
            out.write(scratch, 0, n * 2);
            dataBytes += n * 2;
        }
    }

    synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        out.seek(0);
        writeHeader(dataBytes);
        out.close();
    }

    int seconds() { return dataBytes / 2 / sampleRate; }

    private void writeHeader(int dataLen) throws IOException {
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, 36 + dataLen); put(h, 8, "WAVE");
        put(h, 12, "fmt "); le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1);
        le32(h, 24, sampleRate); le32(h, 28, sampleRate * 2); le16(h, 32, 2); le16(h, 34, 16);
        put(h, 36, "data"); le32(h, 40, dataLen);
        out.write(h);
    }

    private static void put(byte[] b, int off, String s) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void le16(byte[] b, int off, int v) { b[off] = (byte) v; b[off + 1] = (byte) (v >> 8); }

    private static void le32(byte[] b, int off, int v) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (v >> (8 * i));
    }
}
