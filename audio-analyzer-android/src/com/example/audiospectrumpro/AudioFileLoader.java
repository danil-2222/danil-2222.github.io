package com.example.audiospectrumpro;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Decodes an audio file into a mono {@link AudioEngine.Tape}. WAV is parsed directly
 * (16/24/32-bit PCM and 32-bit float); everything else goes through the platform decoder.
 */
final class AudioFileLoader {
    static final int MAX_SECONDS = 15 * 60;

    /** Thrown with a user-facing message. */
    static final class LoadException extends Exception {
        LoadException(String message) { super(message); }
    }

    /** Collects mono samples, growing as needed, up to a hard limit. */
    private static final class PcmBuffer {
        short[] data = new short[1 << 16];
        int size;
        final int limit;
        boolean truncated;

        PcmBuffer(int limit) { this.limit = limit; }

        boolean add(short s) {
            if (size >= limit) { truncated = true; return false; }
            if (size == data.length) {
                short[] n = new short[(int) Math.min((long) limit, data.length * 2L)];
                System.arraycopy(data, 0, n, 0, size);
                data = n;
            }
            data[size++] = s;
            return true;
        }

        short[] toArray() {
            short[] out = new short[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }

    interface Progress { void onProgress(int percent); }

    /** Result of a load; truncated is set when the file was longer than MAX_SECONDS. */
    static final class Result {
        AudioEngine.Tape tape;
        boolean truncated;
    }

    static Result load(Context ctx, Uri uri, Progress progress) throws LoadException {
        String name = displayName(ctx, uri);
        try {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new LoadException("Не вдалося відкрити файл");
            try {
                Result r = tryWav(new BufferedInputStream(in, 1 << 16), name, progress);
                if (r != null) return r;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new LoadException("Помилка читання: " + e.getMessage());
        }
        return decode(ctx, uri, name, progress);
    }

    static String displayName(Context ctx, Uri uri) {
        String name = uri.getLastPathSegment();
        try {
            Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
                c.close();
            }
        } catch (Exception ignored) {}
        return name != null ? name : "audio";
    }

    // ---------------------------------------------------------------- WAV

    /** @return the decoded tape, or null if the stream is not a WAV this parser handles. */
    static Result tryWav(InputStream raw, String name, Progress progress) throws IOException, LoadException {
        DataInputStream in = new DataInputStream(raw);
        byte[] id = new byte[4];
        if (!readFully(in, id) || !"RIFF".equals(new String(id, "US-ASCII"))) return null;
        readLe32(in);
        if (!readFully(in, id) || !"WAVE".equals(new String(id, "US-ASCII"))) return null;

        int format = 0, channels = 0, rate = 0, bits = 0;
        long dataLen = -1;
        while (readFully(in, id)) {
            String chunk = new String(id, "US-ASCII");
            long len = readLe32(in) & 0xFFFFFFFFL;
            if (chunk.equals("fmt ")) {
                format = readLe16(in);
                channels = readLe16(in);
                rate = readLe32(in);
                readLe32(in);
                readLe16(in);
                bits = readLe16(in);
                long rest = len - 16;
                if (format == 0xFFFE && rest >= 10) { // WAVE_FORMAT_EXTENSIBLE: real format in the GUID
                    readLe16(in);
                    readLe16(in);
                    readLe32(in);
                    format = readLe16(in);
                    rest -= 10;
                }
                skip(in, rest + (len & 1));
            } else if (chunk.equals("data")) {
                dataLen = len;
                break;
            } else {
                skip(in, len + (len & 1));
            }
        }
        if (dataLen < 0 || channels <= 0 || rate <= 0) return null;
        boolean pcm = format == 1 && (bits == 8 || bits == 16 || bits == 24 || bits == 32);
        boolean flt = format == 3 && bits == 32;
        if (!pcm && !flt) return null; // let the platform decoder try (ADPCM, A-law, ...)

        int bytesPerSample = bits / 8;
        int frameBytes = bytesPerSample * channels;
        if (dataLen == 0xFFFFFFFFL || dataLen == 0) dataLen = Long.MAX_VALUE; // streamed WAV
        long totalFrames = dataLen / frameBytes;
        PcmBuffer out = new PcmBuffer(Math.max(1, (int) Math.min((long) rate * MAX_SECONDS, totalFrames)));
        byte[] buf = new byte[frameBytes * 4096];
        long frames = 0;
        int lastPct = -1;
        outer:
        while (frames < totalFrames) {
            int want = (int) Math.min(buf.length / frameBytes, totalFrames - frames) * frameBytes;
            int got = 0;
            while (got < want) {
                int r = in.read(buf, got, want - got);
                if (r < 0) break;
                got += r;
            }
            int n = got / frameBytes;
            if (n == 0) break;
            for (int f = 0; f < n; f++) {
                double sum = 0;
                for (int c = 0; c < channels; c++) {
                    int o = f * frameBytes + c * bytesPerSample;
                    sum += sample(buf, o, bits, flt);
                }
                if (!out.add(toShort(sum / channels))) break outer;
            }
            frames += n;
            if (got < want) break;
            if (progress != null && totalFrames != Long.MAX_VALUE / frameBytes) {
                int pct = (int) (100 * frames / totalFrames);
                if (pct != lastPct) progress.onProgress(lastPct = pct);
            }
        }
        if (out.size == 0) throw new LoadException("Файл не містить звуку");
        Result r = new Result();
        r.tape = new AudioEngine.Tape(out.toArray(), rate, name, false);
        r.truncated = out.truncated;
        return r;
    }

    private static double sample(byte[] b, int o, int bits, boolean flt) {
        switch (bits) {
            case 8: return ((b[o] & 0xFF) - 128) / 128.0;
            case 16: return (short) ((b[o] & 0xFF) | (b[o + 1] << 8)) / 32768.0;
            case 24: return ((b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | (b[o + 2] << 16)) / 8388608.0;
            default: {
                int v = (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | (b[o + 3] << 24);
                return flt ? Float.intBitsToFloat(v) : v / 2147483648.0;
            }
        }
    }

    private static short toShort(double v) {
        return (short) Math.round(Math.max(-1.0, Math.min(1.0, v)) * 32767.0);
    }

    private static boolean readFully(DataInputStream in, byte[] b) throws IOException {
        try {
            in.readFully(b);
            return true;
        } catch (EOFException e) {
            return false;
        }
    }

    private static int readLe16(DataInputStream in) throws IOException {
        int a = in.readUnsignedByte(), b = in.readUnsignedByte();
        return a | (b << 8);
    }

    private static int readLe32(DataInputStream in) throws IOException {
        int a = in.readUnsignedByte(), b = in.readUnsignedByte(), c = in.readUnsignedByte(), d = in.readUnsignedByte();
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    private static void skip(DataInputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) return;
                s = 1;
            }
            n -= s;
        }
    }

    // ---------------------------------------------------------------- platform decoder

    private static Result decode(Context ctx, Uri uri, String name, Progress progress) throws LoadException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new LoadException("У файлі немає звукової доріжки");
            ex.selectTrack(track);
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            long durationUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            PcmBuffer out = new PcmBuffer(rate * MAX_SECONDS);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            int lastPct = -1;
            while (!outputDone) {
                if (!inputDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer b = codec.getInputBuffer(ii);
                        int n = b == null ? -1 : ex.readSampleData(b, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0 && !out.truncated) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ShortBuffer sb = ob.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                        int frames = sb.remaining() / channels;
                        for (int f = 0; f < frames; f++) {
                            int sum = 0;
                            for (int c = 0; c < channels; c++) sum += sb.get(f * channels + c);
                            if (!out.add((short) (sum / channels))) break;
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || out.truncated) outputDone = true;
                    if (progress != null && durationUs > 0) {
                        int pct = (int) Math.min(100, 100L * info.presentationTimeUs / durationUs);
                        if (pct != lastPct) progress.onProgress(lastPct = pct);
                    }
                }
            }
            if (out.size == 0) throw new LoadException("Файл не містить звуку");
            Result r = new Result();
            r.tape = new AudioEngine.Tape(out.toArray(), rate, name, false);
            r.truncated = out.truncated;
            return r;
        } catch (LoadException e) {
            throw e;
        } catch (Exception e) {
            throw new LoadException("Формат не підтримується: " + e.getMessage());
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                codec.release();
            }
            ex.release();
        }
    }

    private AudioFileLoader() {}
}
