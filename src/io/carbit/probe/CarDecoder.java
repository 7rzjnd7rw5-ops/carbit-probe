package io.carbit.probe;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;

import java.nio.ByteBuffer;

/**
 * 把 CarWith 推来的 H264 解成 Bitmap，供 10920 重新按仪表要的尺寸编码。
 * 解码器输出到 ImageReader(YUV_420_888)，取最新一帧转 ARGB。
 */
public final class CarDecoder {
    private MediaCodec dec;
    private ImageReader reader;
    private HandlerThread ht;
    private final int w, h;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private volatile Bitmap latestBmp;
    private volatile long latestMs;
    private volatile boolean released;

    public CarDecoder(int width, int height) {
        this.w = width; this.h = height;
    }

    public synchronized boolean start() {
        if (dec != null) return true;
        try {
            ht = new HandlerThread("cardec");
            ht.start();
            reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img != null) { latestBmp = toBitmap(img); latestMs = System.currentTimeMillis(); }
                    } catch (Throwable ignored) {
                    } finally { if (img != null) img.close(); }
                }
            }, new Handler(ht.getLooper()));
            dec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
            dec.configure(f, reader.getSurface(), null, 0);
            dec.start();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public synchronized void push(byte[] au) {
        if (released || au == null || au.length == 0) return;
        if (!start()) return;
        try {
            int in = dec.dequeueInputBuffer(8000);
            if (in >= 0) {
                ByteBuffer ib = dec.getInputBuffer(in);
                if (ib != null) {
                    ib.clear();
                    if (au.length > ib.capacity()) { dec.queueInputBuffer(in, 0, 0, 0, 0); return; }
                    ib.put(au);
                    dec.queueInputBuffer(in, 0, au.length, System.nanoTime() / 1000, 0);
                }
            }
            int out;
            int guard = 0;
            while ((out = dec.dequeueOutputBuffer(info, 0)) >= 0 && guard++ < 8) {
                dec.releaseOutputBuffer(out, true);   // true = 渲染到 ImageReader 的 Surface
            }
        } catch (Throwable ignored) {
        }
    }

    public Bitmap fresh(long maxAgeMs) {
        Bitmap b = latestBmp;
        if (b == null) return null;
        if (System.currentTimeMillis() - latestMs > maxAgeMs) return null;
        return b;
    }

    public synchronized void release() {
        released = true;
        try { if (dec != null) { dec.stop(); dec.release(); } } catch (Throwable ignored) {}
        try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
        try { if (ht != null) ht.quitSafely(); } catch (Throwable ignored) {}
    }

    private static Bitmap toBitmap(Image img) {
        int width = img.getWidth(), height = img.getHeight();
        Image.Plane[] ps = img.getPlanes();
        ByteBuffer yb = ps[0].getBuffer(), ub = ps[1].getBuffer(), vb = ps[2].getBuffer();
        int yRow = ps[0].getRowStride(), yPix = ps[0].getPixelStride();
        int uRow = ps[1].getRowStride(), uPix = ps[1].getPixelStride();
        int vRow = ps[2].getRowStride(), vPix = ps[2].getPixelStride();
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            int yOff = y * yRow;
            int uvY = y / 2;
            for (int x = 0; x < width; x++) {
                int Y = (yb.get(yOff + x * yPix) & 0xFF);
                int uIdx = uvY * uRow + (x / 2) * uPix;
                int vIdx = uvY * vRow + (x / 2) * vPix;
                int U = (ub.get(uIdx) & 0xFF) - 128;
                int V = (vb.get(vIdx) & 0xFF) - 128;
                int r = (int) (Y + 1.370705f * V);
                int g = (int) (Y - 0.344136f * U - 0.714136f * V);
                int b = (int) (Y + 1.775f * U);
                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);
                argb[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
    }
}
