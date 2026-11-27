package io.carbit.probe;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;

import java.nio.ByteBuffer;

/**
 * 最小硬编 H264 编码器：喂一张 Bitmap，吐一段 H264（关键帧自带 SPS/PPS）。
 * 用于 10920 数据口「车机发 114 拉一帧 → 我们回一帧」的推流验证。
 */
public final class H264Encoder {
    private final MediaCodec codec;
    private final int w, h;
    private final byte[] yuv;
    private byte[] spsPps;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private volatile boolean released;

    public H264Encoder(int width, int height, int bitrate, int fps) throws Exception {
        this.w = width; this.h = height;
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", width, height);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        codec = MediaCodec.createEncoderByType("video/avc");
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        yuv = new byte[width * height * 3 / 2];
    }

    /** 把一张 Bitmap 编成一帧，返回 H264 字节（关键帧前面拼 SPS/PPS）。失败返回 null。 */
    public synchronized byte[] encode(Bitmap bmp) {
        if (released) return null;
        try {
            argbToNv12(bmp);
            int in = codec.dequeueInputBuffer(20000);
            if (in < 0) return null;
            ByteBuffer ib = codec.getInputBuffer(in);
            if (ib == null) return null;
            ib.clear();
            ib.put(yuv);
            codec.queueInputBuffer(in, 0, yuv.length, System.nanoTime() / 1000, 0);

            long deadline = System.currentTimeMillis() + 60;
            while (System.currentTimeMillis() < deadline) {
                int out = codec.dequeueOutputBuffer(info, 5000);
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    ByteBuffer csd0 = codec.getOutputFormat().getByteBuffer("csd-0");
                    ByteBuffer csd1 = codec.getOutputFormat().getByteBuffer("csd-1");
                    spsPps = merge(csd0, csd1);
                    continue;
                }
                if (out < 0) continue;
                ByteBuffer ob = codec.getOutputBuffer(out);
                boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                boolean cfg = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                byte[] outBytes = null;
                if (ob != null && info.size > 0) {
                    ob.position(info.offset); ob.limit(info.offset + info.size);
                    byte[] raw = new byte[info.size];
                    ob.get(raw);
                    if (cfg) {
                        spsPps = (spsPps == null) ? raw : raw;
                    } else {
                        outBytes = (key && spsPps != null) ? merge(spsPps, raw) : raw;
                    }
                }
                codec.releaseOutputBuffer(out, false);
                if (outBytes != null) return outBytes;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public synchronized void release() {
        released = true;
        try { codec.stop(); } catch (Throwable ignored) {}
        try { codec.release(); } catch (Throwable ignored) {}
    }

    private static byte[] merge(ByteBuffer a, ByteBuffer b) {
        if (a == null && b == null) return null;
        int la = a == null ? 0 : a.remaining();
        int lb = b == null ? 0 : b.remaining();
        byte[] out = new byte[la + lb];
        if (a != null) { a.duplicate().get(out, 0, la); }
        if (b != null) { b.duplicate().get(out, la, lb); }
        return out;
    }

    private static byte[] merge(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** ARGB → NV12（Y 平面 + 交错 UV），顺手做一次缩小采样。 */
    private void argbToNv12(Bitmap src) {
        if (src.getWidth() != w || src.getHeight() != h) {
            src = Bitmap.createScaledBitmap(src, w, h, true);
        }
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int frame = w * h;
        int uvOff = frame;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int Y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuv[y * w + x] = (byte) (Y < 16 ? 16 : (Y > 235 ? 235 : Y));
            }
        }
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int c1 = px[y * w + x], c2 = px[y * w + Math.min(x + 1, w - 1)];
                int c3 = px[Math.min(y + 1, h - 1) * w + x], c4 = px[Math.min(y + 1, h - 1) * w + Math.min(x + 1, w - 1)];
                int r = ((c1 >> 16 & 0xFF) + (c2 >> 16 & 0xFF) + (c3 >> 16 & 0xFF) + (c4 >> 16 & 0xFF)) / 4;
                int g = ((c1 >> 8 & 0xFF) + (c2 >> 8 & 0xFF) + (c3 >> 8 & 0xFF) + (c4 >> 8 & 0xFF)) / 4;
                int b = ((c1 & 0xFF) + (c2 & 0xFF) + (c3 & 0xFF) + (c4 & 0xFF)) / 4;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                int idx = uvOff + (y / 2) * w + (x & ~1);
                yuv[idx] = (byte) u;
                yuv[idx + 1] = (byte) v;
            }
        }
    }

    /** 生成一张测试画布：黑底 + 大字帧号 + 时间。 */
    public static Bitmap testCanvas(int w, int h, int frame, long ms) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(h / 6f);
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText("RV 投屏", w / 2f, h / 2f - h / 12f, p);
        p.setColor(0xFF66CC66);
        p.setTextSize(h / 9f);
        cv.drawText("帧 " + frame, w / 2f, h / 2f + h / 5f, p);
        p.setColor(0xFFAAAAAA);
        p.setTextSize(h / 14f);
        cv.drawText("10920 拉帧测试  " + (ms % 100000) / 1000 + "s", w / 2f, h - h / 12f, p);
        return bmp;
    }
}
