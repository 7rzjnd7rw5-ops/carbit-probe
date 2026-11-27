package io.carbit.probe;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * CarLife Video 通道（Annex-B H264）→ MediaCodec 硬解 → TextureView 预览。
 * 帧内为 Annex-B（00 00 00 01 起始码），首帧带 SPS/PPS，作为 csd-0/csd-1 交给解码器。
 */
public class H264Preview {

    public interface Sink { void log(String s); }

    private static final int MAX_QUEUE = 120;

    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<byte[]>(MAX_QUEUE);
    private final Sink sink;
    private TextureView textureView;
    private Surface surface;
    private MediaCodec codec;
    private volatile boolean running;
    private Thread worker;

    private byte[] sps, pps;
    // 默认值就是 CarWith 实际出流尺寸（VIDEO_W/H = 1024x608）：SPS 解析失败也不会拿错尺寸去 configure
    private int width = 1024, height = 608;
    private long framesIn, framesOut;
    private long ptsUs = 0;
    private boolean warnedSurface = false;
    private volatile boolean staleCodec;

    public H264Preview(Sink s) { this.sink = s; }

    public void attach(TextureView tv) {
        textureView = tv;
        if (tv.isAvailable() && tv.getSurfaceTexture() != null) {
            surface = new Surface(tv.getSurfaceTexture());
            log("预览 Surface 直接就绪");
        }
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                applyAspect();
                log("预览 Surface 就绪 " + w + "x" + h);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { applyAspect(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                try { if (surface != null) surface.release(); } catch (Throwable t) { }
                surface = null;
                // 关键：surface 没了必须把解码器一起丢掉，否则回来时还在往死 surface 上画 -> 黑屏
                staleCodec = true;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
    }

    /** 16:9 画面按比例居中，避免拉伸变形 */
    private void applyAspect() {
        if (textureView == null) return;
        final int vw = textureView.getWidth(), vh = textureView.getHeight();
        if (vw <= 0 || vh <= 0) return;
        final float videoAspect = (float) width / (float) height;
        final float viewAspect = (float) vw / (float) vh;
        final float sx, sy;
        if (viewAspect > videoAspect) { sx = videoAspect / viewAspect; sy = 1f; }
        else { sx = 1f; sy = viewAspect / videoAspect; }
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(sx, sy, vw / 2f, vh / 2f);
        textureView.setTransform(m);
    }

    /** 还在解码中（主界面判断要不要重建预览实例）。 */
    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        running = true;
        worker = new Thread(new Runnable() { @Override public void run() { loop(); } }, "h264dec");
        worker.start();
    }

    public void stop() {
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) {
            w.interrupt();
            try { w.join(1500); } catch (InterruptedException ignored) { }
        }
        // 线程停了才允许释放：否则 release 与 dequeueOutputBuffer 并发会把 codec 的 native 对象释放成野指针
        releaseCodec();
        queue.clear();
    }

    private volatile long lastRxMs, lastOutMs;
    private int inSinceOut;
    private Runnable stallHandler;
    private int stallRecoveries;

    public void setStallHandler(Runnable r) { stallHandler = r; }

    /** 关键帧（IDR=5 / SPS=7）不允许被挤掉，这是概率性黑屏的根因 */
    private boolean isKey(byte[] p) {
        int i = 0;
        while (i + 4 < p.length) {
            if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 0 && p[i + 3] == 1) {
                int t = p[i + 4] & 0x1f;
                if (t == 5 || t == 7) return true;
                i += 4;
            } else i++;
        }
        return false;
    }

    /** 中途开预览时，CarWith 不会再发 SPS/PPS —— 用推流缓存里的参数集把解码器先配起来。 */
    public void setCsd(byte[] ps) {
        if (ps == null || ps.length == 0) return;
        if (sps != null && pps != null) return;
        if (!scanCsd(ps)) return;
        log("从推流缓存补 SPS/PPS（" + ps.length + "B），预览可以配置了");
        if (codec == null && surface != null && surface.isValid()) config();
    }

    public boolean hasCsd() { return sps != null && pps != null; }

    /** 开预览时先用缓存里的关键帧把首图打出来（配合已配好的参数集，第一帧就是完整 IDR）。 */
    public void seed(byte[] buf) {
        if (buf == null || buf.length == 0) return;
        if (!hasCsd()) { if (!scanCsd(buf)) return; if (codec == null && surface != null && surface.isValid()) config(); }
        if (codec == null) return;
        queue.clear();
        if (queue.offer(buf)) log("已用缓存的关键帧打首图（预览立刻出画）");
    }

    public void push(byte[] packet) {
        if (!running || packet == null || packet.length == 0) return;
        lastRxMs = SystemClock.elapsedRealtime();
        boolean key = isKey(packet);
        // 以前这里限到 ~1.2fps 并丢 P 帧，结果参考链断裂 —— 预览看着糊成一片、字看不清。
        // 现在解码器是软解（不抢 CarWith 的硬编解码块），可以吃满 30fps，画面才清晰连贯。
        long now = SystemClock.elapsedRealtime();
        lastPushMs = now;
        if (!queue.offer(packet)) {
            if (key) {                 // 关键帧来了：清空积压，保住它
                queue.clear();
                queue.offer(packet);
            }                      // 非关键帧且队列满：直接丢这一帧，别动队头的老 IDR
        }
    }

    private void loop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                if (staleCodec) {
                    staleCodec = false;
                    releaseCodec();
                    queue.clear();
                    framesIn = 0; framesOut = 0;
                    log("Surface 变了，解码器已重建待命");
                }
                byte[] pkt = queue.poll(30, TimeUnit.MILLISECONDS);
                if (pkt != null) {
                    if (codec == null) {
                        if (!scanCsd(pkt)) {
                            // 手上没有 SPS/PPS：请求手机端重发编码器初始化，逼出新 IDR
                            if (!csdWarned) { csdWarned = true; log("⚠ 预览手里没有 SPS/PPS（多半是投屏中途才开的预览），等参数集补齐"); }
                            maybeRecover();
                            continue;
                        }
                        if (!config()) { maybeRecover(); continue; }
                    }
                    if (codec != null) queuePacket(pkt);
                }
                drain(info);
                maybeRecover();
            } catch (InterruptedException e) { break; }
            catch (Throwable t) { log("解码异常: " + t); try { Thread.sleep(50); } catch (Exception e2) { } }
        }
    }

    /** 从包里扫出 SPS(7)/PPS(8)，返回是否已两者齐备 */
    private boolean scanCsd(byte[] pkt) {
        int i = 0;
        while (i + 3 < pkt.length) {
            int sc = startCodeLen(pkt, i);
            if (sc > 0) {
                int start = i;
                int nalType = pkt[i + sc] & 0x1f;
                int next = findStart(pkt, i + sc);
                int end = next < 0 ? pkt.length : next;
                if (nalType == 7 && sps == null) {
                    sps = new byte[end - start];
                    System.arraycopy(pkt, start, sps, 0, sps.length);
                } else if (nalType == 8 && pps == null) {
                    pps = new byte[end - start];
                    System.arraycopy(pkt, start, pps, 0, pps.length);
                }
                i = end;
            } else i++;
        }
        if (sps != null && pps != null) {
            int[] wh = parseSps(sps);
            if (wh != null && Math.abs(wh[0] - 1024) <= 96 && Math.abs(wh[1] - 608) <= 96) {
                width = wh[0]; height = wh[1];
                if (!csdLogged) { csdLogged = true; log("SPS 解析 " + width + "x" + height + "（预览按此尺寸配置解码器）"); }
            } else if (!csdLogged) {
                csdLogged = true;
                log("⚠ SPS 解析结果 " + (wh == null ? "失败" : (wh[0] + "x" + wh[1]))
                        + " 与 CarWith 出流不符，按 1024x608 配置（只报这一次）");
            }
            return true;
        }
        return false;
    }

    private int findStart(byte[] b, int from) {
        for (int i = from; i + 2 < b.length; i++) if (startCodeLen(b, i) > 0) return i;
        return -1;
    }

    /** 起始码长度：00 00 01 → 3；00 00 00 01 → 4；不是起始码 → 0。 */
    private int startCodeLen(byte[] b, int i) {
        if (i + 2 >= b.length) return 0;
        if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) return 3;
        if (i + 3 < b.length && b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1) return 4;
        return 0;
    }

    /** 极简 SPS 解析：只取 width/height（用于预览比例，失败就退回 1280x720） */
    private int[] parseSps(byte[] spsWithStart) {
        try {
            int off = 4;
            // RBSP 反转义：00 00 03 里的 03 是防竞争字节，必须剔除，否则后面全部错位
            java.io.ByteArrayOutputStream un = new java.io.ByteArrayOutputStream();
            int zeros = 0;
            for (int i = off; i < spsWithStart.length; i++) {
                byte b = spsWithStart[i];
                if (zeros >= 2 && b == 3) { zeros = 0; continue; }
                un.write(b);
                zeros = b == 0 ? zeros + 1 : 0;
            }
            byte[] rbsp = un.toByteArray();
            int[] buf = new int[rbsp.length * 8];
            int n = 0;
            for (int i = 0; i < rbsp.length; i++)
                for (int b = 7; b >= 0; b--) buf[n++] = (rbsp[i] >> b) & 1;
            int pos = 0;
            pos += 8; // profile
            pos += 8; // constraint
            pos += 8; // level
            int ue = readUe(buf, pos); pos = ue >>> 16;
            if (ue == -1) return null;
            int ue2 = readUe(buf, pos); pos = ue2 >>> 16;
            if (ue2 == -1) return null;
            pos += 1;
            // pic_order_cnt_type
            int ue3 = readUe(buf, pos); pos = ue3 >>> 16;
            if (ue3 == -1) return null;
            if ((ue3 & 0xffff) == 0) pos += 1; else pos += 2;
            // max_num_ref_frames + gaps_in_frame_num_allowed_flag
            int ue4 = readUe(buf, pos); pos = ue4 >>> 16;
            if (ue4 == -1) return null;
            pos += 1;
            // pic_width_in_mbs_minus1 / pic_height_in_map_units_minus1
            int ue5 = readUe(buf, pos); pos = ue5 >>> 16;
            if (ue5 == -1) return null;
            int ue6 = readUe(buf, pos); pos = ue6 >>> 16;
            if (ue6 == -1) return null;
            int frameMbsOnly = (pos < buf.length) ? buf[pos] : 1;
            pos += 1;
            if (frameMbsOnly == 0) pos += 1;       // mb_adaptive_frame_field_flag
            // 宽高直接用 mbs 数 ×16（裁剪先不扣：宁可差 8 像素，也不能像上一版那样算成 10x4044）
            int w = ((ue5 & 0xffff) + 1) * 16;
            int h = ((ue6 & 0xffff) + 1) * 16 * (2 - frameMbsOnly);
            if (w >= 320 && w <= 2560 && h >= 240 && h <= 1600) return new int[]{w, h};
        } catch (Throwable t) { }
        return null;
    }

    /** 读 ue(v)，返回 (下一个位置 << 16) | 值 */
    private int readUe(int[] bits, int pos) {
        int zeros = 0;
        while (pos < bits.length && bits[pos] == 0) { zeros++; pos++; }
        if (pos >= bits.length) return -1;
        pos++;
        int v = 0;
        for (int i = 0; i < zeros; i++) {
            if (pos >= bits.length) return -1;
            v = (v << 1) | bits[pos++];
        }
        v += (1 << zeros) - 1;
        return (pos << 16) | (v & 0xffff);
    }

    private long lastPushMs;

    /** 软解（OMX.google.h264.decoder）：1fps 下 CPU 开销很小，但不会和 CarWith 抢硬件编解码器。 */
    private static MediaCodec createSoftDecoder() {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder() && info.getName().toLowerCase().contains("google.h264.decoder")) {
                    return MediaCodec.createByCodecName(info.getName());
                }
            }
        } catch (Throwable ignored) { }
        try { return MediaCodec.createDecoderByType("video/avc"); } catch (Throwable t) { return null; }
    }

    private boolean csdWarned;
    private long configFailAt;
    private boolean csdLogged;
    private boolean configWarned;

    private boolean config() {
        if (System.currentTimeMillis() - configFailAt < 3000) return false;   // 失败退避，避免每帧重试刷屏
        try {
            if (surface == null || !surface.isValid()) {
                if (!warnedSurface) { warnedSurface = true; log("Surface 未就绪，等一会再试"); }
                return false;
            }
            MediaFormat f = MediaFormat.createVideoFormat("video/avc", width, height);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            codec = createSoftDecoder();
            codec.configure(f, surface, null, 0);
            codec.start();
            log("解码器就绪 " + width + "x" + height);
            applyAspect();
            return true;
        } catch (Throwable t) {
            configFailAt = System.currentTimeMillis();
            if (!configWarned) { configWarned = true; log("解码器创建失败: " + t + "（3 秒内不再重试，避免刷屏）"); }
            codec = null;
            return false;
        }
    }

    private void queuePacket(byte[] pkt) throws Exception {
        int idx = codec.dequeueInputBuffer(8000);
        if (idx < 0) return;
        ByteBuffer in = codec.getInputBuffer(idx);
        if (in == null) return;
        in.clear();
        if (pkt.length > in.capacity()) return;
        in.put(pkt);
        codec.queueInputBuffer(idx, 0, pkt.length, ptsUs, 0);
        ptsUs += 33333;
        framesIn++;
    }

    private void drain(MediaCodec.BufferInfo info) {
        if (codec == null) return;
        int idx = codec.dequeueOutputBuffer(info, 0);
        int guard = 0;
        while (idx >= 0 && guard++ < 12) {
            codec.releaseOutputBuffer(idx, true);
            framesOut++;
            idx = codec.dequeueOutputBuffer(info, 0);
        }
    }

    /**
     * 卡死看门狗：有输入、却长时间没有任何输出，说明解码链断了（通常是丢了 IDR）。
     * 处理：丢解码器、清队列、清 SPS/PPS，并通知上层重新 INIT/START 逼出新关键帧。
     */
    private void maybeRecover() {
        long now = SystemClock.elapsedRealtime();
        if (codec == null) { lastFramesIn = framesIn; lastFramesOut = framesOut; lastOutMs = now; return; }
        if (framesOut != lastFramesOut) { lastFramesOut = framesOut; lastOutMs = now; inSinceOut = 0; return; }
        if (framesIn != lastFramesIn) { lastFramesIn = framesIn; inSinceOut++; }
        if (codec == null || now - lastOutMs < 1200) return;
        if (inSinceOut < 20) return;   // 数据本身还没进来，不算解码卡死
        /*** 真卡死 ***/
        stallRecoveries++;
        log("解码卡住（" + (now - lastOutMs) + "ms 无输出），重置解码器并请求新关键帧 #" + stallRecoveries);
        releaseCodec();
        sps = null; pps = null;
        queue.clear();
        framesIn = 0; framesOut = 0; inSinceOut = 0;
        lastFramesIn = 0; lastFramesOut = 0;
        lastOutMs = now;
        if (stallHandler != null) { try { stallHandler.run(); } catch (Throwable t) { } }
    }

    private long lastFramesOut = -1, lastFramesIn = -1;

    private void releaseCodec() {
        try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable t) { }
        codec = null;
    }

    private void log(String s) { if (sink != null) sink.log(s); }

    public long getFramesOut() { return framesOut; }
}
