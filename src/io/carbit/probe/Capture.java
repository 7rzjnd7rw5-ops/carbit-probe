package io.carbit.probe;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;

import java.nio.ByteBuffer;

/**
 * 屏幕捕获（为"只投 CarWith"服务）。
 *
 * 数据流：MediaProjection（Android 14 用户可选"仅这一个应用"→ 只剩 CarWith 的画面）
 *        → VirtualDisplay（仪表尺寸）
 *        → ImageReader(RGBA_8888) 取帧
 *        → [后续接 MediaCodec H264] → 10710
 *
 * 当前版本只取帧、算帧率、回一张缩略图给界面，用来确认"捕获到的确实是 CarWith"。
 */
public final class Capture {

    public interface Log {
        void line(String s);
    }

    public interface Preview {
        void onFrame(Bitmap bmp, int frames, double fps);
    }

    /** 编码回调（下一步接 H264 时用，先留口子）。 */
    public interface EncoderTap {
        void onRgba(Image image);
    }

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;

    private long frameCount;
    private long startMs;
    private int w;
    private int h;
    private EncoderTap tap;
    private volatile Bitmap lastBmp;
    private volatile int lastFrameNo = -1;

    public int width() { return w; }
    public int height() { return h; }
    public boolean isRunning() { return running; }

    /** 最近一帧的副本（取证截图用）。没有画面时返回 null。 */
    public Bitmap snap() {
        Bitmap b = lastBmp;
        if (b == null) return null;
        try {
            return b.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 自 fromFrame 之后有没有更新过（用来确认新页面确实画上来了）。 */
    public int frameNo() { return lastFrameNo; }

    public void setEncoderTap(EncoderTap t) { this.tap = t; }

    /**
     * @param resultCode createScreenCaptureIntent 的返回码
     * @param data       同上返回的 Intent
     */
    public boolean start(Context ctx, int resultCode, Intent data, int width, int height,
                         int dpi, final Log log, final Preview preview) {
        if (running) return false;
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) {
                if (log != null) log.line("拿不到 MediaProjection（授权被取消？）");
                return false;
            }
            thread = new HandlerThread("cap");
            thread.start();
            handler = new Handler(thread.getLooper());

            // Android 14 起必须先注册回调，否则 createVirtualDisplay 抛 IllegalState
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (log != null) log.line("系统回收了录屏授权，捕获停止");
                    running = false;
                }
            }, handler);

            w = width;
            h = height;
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
            display = projection.createVirtualDisplay(
                    "CarWith-Mirror", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);

            running = true;
            frameCount = 0;
            startMs = System.currentTimeMillis();

            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                private int tick;
                @Override public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null) return;
                        frameCount++;
                        if (tap != null) {
                            tap.onRgba(img);
                            return;
                        }
                        // 每 8 帧出一张缩略图，足够人眼确认画面
                        if ((tick++ % 8) != 0) return;
                        Bitmap bmp = toBitmap(img);
                        lastBmp = bmp;
                        lastFrameNo++;
                        if (preview == null) return;
                        double fps = frameCount * 1000.0
                                / Math.max(1, System.currentTimeMillis() - startMs);
                        preview.onFrame(bmp, (int) frameCount, fps);
                    } catch (Throwable t) {
                        // 单帧失败不影响整体
                    } finally {
                        if (img != null) try { img.close(); } catch (Throwable ignored) { }
                    }
                }
            }, handler);

            if (log != null) {
                log.line("捕获已启动 " + w + "×" + h + " dpi=" + dpi);
                log.line("提示：授权弹窗里选「仅此应用 / CarWith」，投出去的才只有车机界面");
            }
            return true;
        } catch (Throwable t) {
            if (log != null) log.line("启动捕获失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            stop();
            return false;
        }
    }

    public double fps() {
        long dt = System.currentTimeMillis() - startMs;
        return dt <= 0 ? 0 : frameCount * 1000.0 / dt;
    }

    public long frames() { return frameCount; }

    public void stop() {
        running = false;
        try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
        try { if (display != null) display.release(); } catch (Throwable ignored) { }
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
        try { if (thread != null) thread.quitSafely(); } catch (Throwable ignored) { }
        reader = null;
        display = null;
        projection = null;
        thread = null;
    }

    /** Image(RGBA_8888) → Bitmap，处理 rowStride 不对齐的情况。 */
    private static Bitmap toBitmap(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = img.getWidth();
        int height = img.getHeight();
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (rowStride == width * pixelStride) {
            bmp.copyPixelsFromBuffer(buf);
        } else {
            int rowBytes = width * pixelStride;
            byte[] row = new byte[rowBytes];
            int[] pixels = new int[width];
            for (int y = 0; y < height; y++) {
                buf.position(y * rowStride);
                buf.get(row, 0, rowBytes);
                for (int x = 0; x < width; x++) {
                    int o = x * 4;
                    int r = row[o] & 0xFF, g = row[o + 1] & 0xFF;
                    int b = row[o + 2] & 0xFF, a = row[o + 3] & 0xFF;
                    pixels[x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
                bmp.setPixels(pixels, 0, width, 0, y, width, 1);
            }
        }
        return bmp;
    }
}
