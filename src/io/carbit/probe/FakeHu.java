package io.carbit.probe;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 假车机（CarLife HU 端）——单机路径验证核心。
 * CarWith 通过 CARLIFE_USB_ADB 拉起百度 CarLife 手机端后，手机端在本机 0.0.0.0 监听：
 *   Cmd 7240 / Video 8240 / Media 9240 / TTS 9241 / VR 9242 / Touch 9340 / Data 9440
 * 6 条 socket 连满即被判定为"车机已连接"，随后由车机侧驱动：
 *   1. 车机发 HU_PROTOCOL_VERSION(98305) {1:major,2:minor}
 *   2. 手机回 PROTOCOL_VERSION_MATCH_STATUS(65538) {1:matchStatus}
 *   3. 车机发 VIDEO_ENCODER_INIT(98311) {1:width,2:height,3:framerate,8:?,9:?}
 *   4. 手机回 VIDEO_ENCODER_INIT_DONE(65544) {1:encW,2:encH,3:fps}
 *   5. 车机发 VIDEO_ENCODER_START(98313)
 *   6. Video socket 持续吐 H264（12 字节头: len(4 BE) + 4 保留 + type(4 BE)=0x20001）
 * 头部字节序为大端。
 */
public class FakeHu {

    public interface Sink { void log(String s); }

    public static final int P_CMD = 7240, P_VIDEO = 8240, P_MEDIA = 9240,
            P_TTS = 9241, P_VR = 9242, P_TOUCH = 9340, P_DATA = 9440;

    private static final int HU_PROTOCOL_VERSION = 98305;
    private static final int MD_VERSION_MATCH = 65538;
    /** 向 CarWith 要的画面尺寸，直接对齐无极仪表的 RV_CONFIG_CAPTURE(1024x608)，省掉中间重编码 */
    public static final int VIDEO_W = 1024, VIDEO_H = 608;
    /**
     * 向 CarWith 要的帧率：实测仪表按 0x0072 一帧一帧拉，速度只有 ~10fps（236 帧/24 秒）。
     * 之前要 30fps，必然丢帧 → P 帧引用断链 → 仪表花屏。这里直接对齐拉取速度，从源头不丢帧。
     */
    public static volatile int VIDEO_FPS = 10;
    private static final int VIDEO_ENCODER_INIT = 98311;
    private static final int VIDEO_ENCODER_START = 98313;
    private static final int VIDEO_ENCODER_INIT_DONE = 65544;
    public static final int TOUCH_DOWN = 425986;
    public static final int TOUCH_UP = 425987;
    public static final int TOUCH_MOVE = 425988;
    public static final int MD_FEATURE_CONFIG_REQUEST = 65617;
    public static final int CMD_INPUT_TEXT_CHANGE = 66800;   // 0x104F0 车机→手机：文本变化
    public static final int CMD_INPUT_TEXT_ACTION = 66816;   // 0x104C0 车机→手机：IME 动作
    public static final int HU_FEATURE_CONFIG_RESPONSE = 98386;
    /** 让 CarLife 把音乐/导航声留在手机本地播放（耳机/蓝牙），不再走车机音频通道 */
    public static final String KEY_AUDIO_TRANSMISSION_MODE = "AUDIO_TRANSMISSION_MODE";

    private volatile boolean running = false;
    private final List<Socket> socks = java.util.Collections.synchronizedList(new ArrayList<Socket>());
    private Sink sink;
    private Context ctx;
    private long videoBytes = 0;
    private int videoFrames = 0;
    private Socket cmd, video, touch, media;
    private long mediaBytes;
    /** 音频模式：0=PCM 走 Media 通道（我们用 AudioTrack 在本机放）；1=CarLife 自己留本机。 */
    public static volatile int AUDIO_MODE = 0;
    private boolean audioInitDone;
    private boolean previewPausedLogged;
    private int audioPackets;
    private long lastMediaLog;
    private java.io.FileOutputStream capOut;
    private long capStart;
    private Rig rig;

    public void setRig(Rig r) { this.rig = r; }

    /** 解码端卡住时调用：重发编码器 INIT + START，逼手机端重建编码器、立刻吐一个新 IDR */
    public void requestRestart() {
        new Thread(new Runnable() { @Override public void run() {
            try {
                ByteArrayOutputStream q = new ByteArrayOutputStream();
                q.write(pbInt(1, VIDEO_W)); q.write(pbInt(2, VIDEO_H)); q.write(pbInt(3, VIDEO_FPS));
                sendCmd(VIDEO_ENCODER_INIT, q.toByteArray());
                Thread.sleep(120);
                sendCmd(VIDEO_ENCODER_START, new byte[0]);
                log("→ 重建编码器（INIT+START）以强制新关键帧");
            } catch (Throwable t) { log("× 重建编码器失败 " + t); }
        } }, "fakehu-restart").start();
    }
    private volatile OutputStream touchOut;
    private volatile Socket touchSocket;
    private static int touchSentCount;
    private long touchLostLogAt;

    private boolean touchClosed() {
        Socket ts = touchSocket;
        return ts == null || ts.isClosed() || !ts.isConnected();
    }

    private static String touchTypeName(int t) {
        return t == TOUCH_DOWN ? "按下" : t == TOUCH_UP ? "抬起" : t == TOUCH_MOVE ? "移动" : ("type" + t);
    }

    private void touchLostLog(String why) {
        long now = System.currentTimeMillis();
        if (now - touchLostLogAt < 1500) return;
        touchLostLogAt = now;
        log("⚠ " + why + " —— 这一次点击没送到 CarWith（所以没有震动，也没反应）");
    }

    /** 9340 触控通道断了就重连：以前只在开局连一次，中途断了之后所有点击都静默丢失。 */
    private void reconnectTouch() {
        if (reconnectingTouch) return;
        reconnectingTouch = true;
        new Thread(new Runnable() { @Override public void run() {
            try {
                for (int i = 0; i < 3; i++) {
                    Socket s = connectQuiet(P_TOUCH);
                    if (s != null) {
                        touchSocket = s;
                        touchOut = new BufferedOutputStream(s.getOutputStream(), 8192);
                        log("√ 触控通道 9340 已重连（第 " + (i + 1) + " 次）");
                        reconnectingTouch = false;
                        return;
                    }
                    try { Thread.sleep(600); } catch (Exception ignored) { }
                }
                log("× 触控通道 9340 重连失败（CarWith 那边没再监听）");
            } catch (Exception e) {
                log("× 触控通道重连异常 " + e);
            } finally { reconnectingTouch = false; }
        } }, "touch-reconnect").start();
    }
    private static boolean reconnectingTouch;
    private OutputStream cmdOut;
    private H264Preview preview;
    private File dumpFile;
    private FileOutputStream dumpOut;
    private long dumpBytes;

    private void log(String s) { if (sink != null) sink.log(s); }

    public void setPreview(H264Preview p) { preview = p; }

    /** 投屏开始后彻底停掉本地预览解码：release 掉 MediaCodec，而不是仅仅不喂帧。 */
    public void releasePreview() {
        try {
            H264Preview p = preview;
            preview = null;
            if (p != null) {
                p.stop();
                log("· 本地预览解码器已 release（省掉一路 MediaCodec）");
            }
        } catch (Throwable t) {
            log("· 释放预览解码器失败: " + t);
        }
    }

    public boolean isRunning() { return running; }
    public long getVideoBytes() { return videoBytes; }
    public int getVideoFrames() { return videoFrames; }

    public void start(final Context c, Sink s) {
        if (running) { log("假车机已在运行"); return; }
        ctx = c; sink = s; running = true;
        MediaAudio.setSink(new MediaAudio.Sink() { @Override public void log(String x) { s.log(x); } });
        new Thread(new Runnable() { @Override public void run() { loop(); } }, "fakehu").start();
    }

    private void loop() {
        try {
            log("== 假车机启动：先拉起 CarWith 的 CarLife USB/ADB 入口");
            pullUp();
            Thread.sleep(800);

            log("等待 Cmd 7240 监听 …");
            cmd = connectWithRetry(P_CMD, 60, 150);
            if (cmd == null) { log("× 7240 始终不监听，失败"); running = false; return; }
            log("√ 7240 已连上，继续连满 6 条");
            video = connectQuiet(P_VIDEO);
            media = connectQuiet(P_MEDIA); connectQuiet(P_TTS);
            touch = connectQuiet(P_TOUCH);
            connectQuiet(P_VR);
            // Data 9440 不计入 6 条，可选
            int n = socks.size();
            log("已连 socket 数：" + n + (n >= 6 ? "（≥6，手机应判为车机已连接）" : "（不足 6，可能不触发）"));

            cmdOut = new BufferedOutputStream(cmd.getOutputStream(), 32768);
            if (touch != null) { touchSocket = touch; touchOut = new BufferedOutputStream(touch.getOutputStream(), 8192); }

            // 1) 先发车机协议版本
            ByteArrayOutputStream v = new ByteArrayOutputStream();
            v.write(pbInt(1, 2)); v.write(pbInt(2, 0));
            sendCmd(HU_PROTOCOL_VERSION, v.toByteArray());
            log("→ HU_PROTOCOL_VERSION 2.0");

            // Cmd 读线程
            new Thread(new Runnable() { @Override public void run() { cmdRead(); } }, "fakehu-cmd").start();
            // Video 读线程
            if (video != null) new Thread(new Runnable() { @Override public void run() { videoRead(); } }, "fakehu-video").start();
            if (media != null) new Thread(new Runnable() { @Override public void run() { mediaRead(); } }, "fakehu-media").start();

            // 3) 2 秒后请求视频编码初始化
            Thread.sleep(2000);
            if (running) {
                ByteArrayOutputStream q = new ByteArrayOutputStream();
                q.write(pbInt(1, VIDEO_W)); q.write(pbInt(2, VIDEO_H)); q.write(pbInt(3, VIDEO_FPS));
                sendCmd(VIDEO_ENCODER_INIT, q.toByteArray());
                log("→ VIDEO_ENCODER_INIT " + VIDEO_W + "x" + VIDEO_H + "@" + VIDEO_FPS
                        + "（按仪表要的尺寸向 CarWith 取流；实测 CarWith 仍按 30fps 出流，这一项它不一定听）");
            }
            Thread.sleep(1500);
            if (running) sendFeatureConfig();
            // 7.6：不再发 LAUNCH_MODE_MUSIC(98336)——这条会让 CarLife/CarWith 自动切到音乐模式并把播放器拉起来
            if (running) log("· 已跳过 LAUNCH_MODE_MUSIC（不发 98336，避免自动开音乐）");
        } catch (Throwable t) {
            log("× 异常: " + t);
            running = false;
        }
    }

    private void pullUp() {
        try {
            ComponentName cn = new ComponentName("com.miui.carlink",
                    "com.miui.carlink.castfwk.usb.CarlifeAdbActivity");
            Intent it = new Intent();
            it.setComponent(cn);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable t) { log("拉起失败(可手动): " + t); }
    }

    private Socket connectWithRetry(int port, int times, int gapMs) {
        for (int i = 0; i < times && running; i++) {
            Socket s = connectQuiet(port);
            if (s != null) return s;
            try { Thread.sleep(gapMs); } catch (Exception e) { }
        }
        return null;
    }

    private Socket connectQuiet(int port) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
            s.setTcpNoDelay(true);
            s.setReceiveBufferSize(327680);
            socks.add(s);
            return s;
        } catch (Throwable t) {
            try { if (s != null) s.close(); } catch (Exception e) { }
            return null;
        }
    }

    /** 车机侧触控：坐标以 1280x720 编码画面为基准 */
    private java.util.concurrent.ExecutorService touchExec;
    private int pvTick;
    static volatile Boolean previewAlt;   // 由 Rig 置：true = 车机正在拉帧，预览降频

    /** 拖动中的 MOVE：字段 1/2 = 当前点，3/4 = 起点（跟零长度 MOVE 区分开，车机才会真的拖) */
    public void sendDragMove(final int fromX, final int fromY, final int x, final int y) {
        if (touchExec == null) touchExec = java.util.concurrent.Executors.newSingleThreadExecutor();
        touchExec.execute(new Runnable() { @Override public void run() {
            if (touchOut == null) { touchLostLog("触控通道为空（9340 没连上）"); reconnectTouch(); return; }
            try {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                o.write(pbInt(1, x));
                o.write(pbInt(2, y));
                o.write(pbInt(3, fromX));
                o.write(pbInt(4, fromY));
                byte[] pl2 = o.toByteArray();
                byte[] h = new byte[8];
                h[0] = (byte) (pl2.length >> 8); h[1] = (byte) pl2.length;
                h[4] = (byte) (TOUCH_MOVE >> 24); h[5] = (byte) (TOUCH_MOVE >> 16);
                h[6] = (byte) (TOUCH_MOVE >> 8); h[7] = (byte) TOUCH_MOVE;
                touchOut.write(h); touchOut.write(pl2); touchOut.flush();
                touchSentCount++;
            } catch (Throwable t) {
                touchOut = null;
                log("✗ 拖动发送失败（9340 通道已断，马上重连）：" + t);
                reconnectTouch();
            }
        } });
    }

    public void sendTouch(final int type, final int x, final int y) {
        if (touchExec == null) touchExec = java.util.concurrent.Executors.newSingleThreadExecutor();
        touchExec.execute(new Runnable() { @Override public void run() { sendTouchSync(type, x, y); } });
    }

    private void sendTouchSync(int type, int x, int y) {
        if (touchOut == null) {
            touchLostLog("触控通道为空（9340 没连上）");
            reconnectTouch();
            return;
        }
        try {
            byte[] pl = pbInt(1, x);
            byte[] pl2;
            if (type == TOUCH_MOVE) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                o.write(pl); o.write(pbInt(2, y)); o.write(pbInt(3, x)); o.write(pbInt(4, y));
                pl2 = o.toByteArray();
            } else {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                o.write(pl); o.write(pbInt(2, y));
                pl2 = o.toByteArray();
            }
            byte[] h = new byte[8];
            h[0] = (byte) (pl2.length >> 8); h[1] = (byte) pl2.length;
            h[4] = (byte) (type >> 24); h[5] = (byte) (type >> 16);
            h[6] = (byte) (type >> 8); h[7] = (byte) type;
            touchOut.write(h); touchOut.write(pl2); touchOut.flush();
            touchSentCount++;
            if (touchSentCount <= 40 || touchSentCount % 20 == 0) {
                log("· 触控已送出 #" + touchSentCount + " " + touchTypeName(type) + " (" + x + "," + y
                        + ") 包长=" + (8 + pl2.length) + " 通道=" + (touchClosed() ? "已断" : "正常"));
            }
        } catch (Throwable t) {
            touchOut = null;
            log("✗ 触控发送失败（9340 通道已断，马上重连）：" + t);
            reconnectTouch();
        }
    }

    /**
     * 把预览框里的触摸点映射到编码画面坐标。
     * 预览是 16:9 letterbox 居中，需先扣掉留边再按缩放比换算。
     */
    public static int[] mapToVideo(float tx, float ty, int viewW, int viewH, int videoW, int videoH) {
        if (viewW <= 0 || viewH <= 0) return new int[]{0, 0};
        float scale = Math.min((float) viewW / videoW, (float) viewH / videoH);
        float dispW = videoW * scale, dispH = videoH * scale;
        float offX = (viewW - dispW) / 2f, offY = (viewH - dispH) / 2f;
        int x = (int) ((tx - offX) / scale);
        int y = (int) ((ty - offY) / scale);
        if (x < 0) x = 0; if (x > videoW - 1) x = videoW - 1;
        if (y < 0) y = 0; if (y > videoH - 1) y = videoH - 1;
        return new int[]{x, y};
    }

    /** 车机→手机：把文本塞进 CarWith 当前聚焦的输入框（PXC C2P_INPUT_TEXT_CHANGE）。不用无障碍，也不用点键盘。 */
    public void sendTextInput(final String text) {
        exec().execute(new Runnable() { @Override public void run() {
            try {
                String esc = text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
                byte[] body = ("{\"text\":\"" + esc + "\"}").getBytes("UTF-8");
                // 实测：走 PXC 发 0x104F0，CarWith 直接回 0x80000010（拒绝）还会掐会话，
                // 反而影响投屏。这里不再走 PXC，只留旧 Cmd 通道的记录性尝试。
                sendCmd(CMD_INPUT_TEXT_CHANGE, body);
            } catch (Throwable t) { log("× 文本注入失败 " + t); }
        } });
    }

    /** 车机→手机：输入法动作（6=DONE / 2=GO / 3=SEARCH），相当于按键盘上的「确定/搜索」。 */
    public void sendInputAction(final int actionId) {
        exec().execute(new Runnable() { @Override public void run() {
            try {
                byte[] body = ("{\"actionId\":" + actionId + ",\"keyCode\":0}").getBytes("UTF-8");
                sendCmd(CMD_INPUT_TEXT_ACTION, body);
            } catch (Throwable t) { log("× 输入动作失败 " + t); }
        } });
    }

    private java.util.concurrent.ExecutorService cmdExec;
    private java.util.concurrent.ExecutorService exec() {
        if (cmdExec == null) cmdExec = java.util.concurrent.Executors.newSingleThreadExecutor();
        return cmdExec;
    }

    private synchronized void sendCmd(int type, byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int len = payload == null ? 0 : payload.length;
        out.write(new byte[]{(byte) (len >> 8), (byte) len, 0, 0,
                (byte) (type >> 24), (byte) (type >> 16), (byte) (type >> 8), (byte) type});
        if (len > 0) out.write(payload);
        cmdOut.write(out.toByteArray());
        cmdOut.flush();
    }

    private void cmdRead() {
        try {
            InputStream in = new BufferedInputStream(cmd.getInputStream(), 32768);
            byte[] head = new byte[8];
            while (running) {
                if (!readFully(in, head, 8)) break;
                int len = ((head[0] & 0xff) << 8) | (head[1] & 0xff);
                int type = ((head[4] & 0xff) << 24) | ((head[5] & 0xff) << 16)
                        | ((head[6] & 0xff) << 8) | (head[7] & 0xff);
                byte[] pl = new byte[len];
                if (len > 0 && !readFully(in, pl, len)) break;
                if (type == MD_VERSION_MATCH) {
                    int st = len > 0 ? (pl[len - 1] & 0xff) : -1;
                    log("← 版本匹配 matchStatus=" + st + (st == 1 ? "  √ 通过" : "  × 不匹配"));
                } else if (type == VIDEO_ENCODER_INIT_DONE) {
                    log("← VIDEO_ENCODER_INIT_DONE " + hex(pl) + "（编码器已按此尺寸就绪）");
                    try { sendCmd(VIDEO_ENCODER_START, new byte[0]); log("→ VIDEO_ENCODER_START"); }
                    catch (Exception e) { log("× START 发送失败 " + e); }
                } else if (type == MD_FEATURE_CONFIG_REQUEST) {
                    log("← 车机配置请求 65617，回：音频走手机本地");
                    sendFeatureConfig();
                } else {
                    log("← Cmd type=" + type + " len=" + len + (len > 0 && len < 40 ? " " + hex(pl) : ""));
                }
            }
        } catch (Throwable t) { log("Cmd 读结束: " + t); }
    }

    /** 只统计音频通道字节：为 0 说明音频没发给车机（留在手机本地播放） */
    private void mediaRead() {
        try {
            InputStream in = new BufferedInputStream(media.getInputStream(), 65536);
            byte[] head = new byte[12];
            int n = 0;
            log("Media 通道(9240) 已连上，开始等音频包");
            while (running) {
                if (System.currentTimeMillis() - lastMediaLog > 20000) {
                    log("… Media 通道 20 秒汇总：包 " + n + " 个 / " + mediaBytes
                            + " 字节，本机 AudioTrack 播放包 " + audioPackets
                            + (n == 0 ? "（0 包属正常：AUDIO_MODE=" + AUDIO_MODE + "，PCM 不经这条通道）" : ""));
                    lastMediaLog = System.currentTimeMillis();
                }
                if (!readFully(in, head, 12)) break;
                int len = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16)
                        | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
                int type = ((head[8] & 0xff) << 24) | ((head[9] & 0xff) << 16)
                        | ((head[10] & 0xff) << 8) | (head[11] & 0xff);
                if (len > 1024 * 1024 || len < 0) { log("× Media 长度异常 " + len); break; }
                if (len == 0) { continue; }
                byte[] pl = new byte[len];
                if (!readFully(in, pl, len)) break;
                mediaBytes += len;
                n++;
                if (n <= 3) log("← Media type=" + type + " len=" + len + " 头 " + hex(pl, 24));
                handleAudio(type, pl);
            }
        } catch (Throwable t) { log("Media 读结束: " + t); }
    }

    /** 196609=参数包{CarlifeMusicInit}，196610=音频数据包{12B 包头+PCM} */
    private void handleAudio(int type, byte[] pl) {
        if (type == 196609) {
            int i = 0, sr = 0, chCfg = 0, fmt = 0;
            try {
                while (i < pl.length) {
                    int tag = pl[i++] & 0xff;
                    int field = tag >> 3, wt = tag & 7;
                    if (wt != 0) break;
                    int v = 0, sh = 0, b;
                    do { b = pl[i++] & 0xff; v |= (b & 0x7f) << sh; sh += 7; } while ((b & 0x80) != 0 && i < pl.length);
                    if (field == 1) sr = v; else if (field == 2) chCfg = v; else if (field == 3) fmt = v;
                }
            } catch (Throwable t) { }
            // 2=立体声，1=单声道；16=16bit
            int channels = chCfg == 1 ? 1 : 2;
            int bits = fmt <= 8 ? 8 : 16;
            if (sr >= 8000 && sr <= 192000) {
                MediaAudio.ensure(sr, channels, bits);
                audioInitDone = true;
                log("音频参数：采样率 " + sr + " 声道配置 " + chCfg + " 采样格式 " + fmt);
            }
            return;
        }
        // 实测（5.0，CarLife 8.8.5）：真正在通道上跑的是 196614，不是 196610；
        // 196614 的载荷直接就是 PCM（没有 12 字节头），之前只认 196610 所以一包都没播。
        if (type < 196610 || type > 196616) return;
        if (!audioInitDone) { MediaAudio.ensure(48000, 2, 16); audioInitDone = true; }
        int off = 0;
        // CarLife 的 PCMPackageHead（见 PCMPackageHead.java）固定 12 字节：
        //   [0..3]=数据长度(BE) [4..7]=时间戳(BE) [8..11]=type(BE, 196614)
        // 判定用第 8..11 字节的 type 最稳（长度字段实测常是 0）。
        if (pl.length > 16) {
            int hLen = ((pl[0] & 0xff) << 24) | ((pl[1] & 0xff) << 16) | ((pl[2] & 0xff) << 8) | (pl[3] & 0xff);
            int hType = ((pl[8] & 0xff) << 24) | ((pl[9] & 0xff) << 16) | ((pl[10] & 0xff) << 8) | (pl[11] & 0xff);
            if (hLen == pl.length - 12 || hType == type) off = 12;
        }
        if (pl.length <= off) return;
        if (audioPackets < 5) {
            double rms = 0; int cnt = 0;
            for (int i = off + 1; i + 1 < pl.length; i += 2) { int v = pl[i]; rms += v * v; cnt++; }
            log("音频包#" + (audioPackets + 1) + " len=" + pl.length + " off=" + off
                    + " 前12B=" + hex(pl, 12) + " RMS=" + (cnt > 0 ? (int) Math.sqrt(rms / cnt) : 0));
        }
        byte[] pcm = new byte[pl.length - off];
        System.arraycopy(pl, off, pcm, 0, pcm.length);
        MediaAudio.writePcm(pcm);
        audioPackets++;
        if (audioPackets == 1 || audioPackets == 30 || audioPackets % 300 == 0) {
            log("… 音频实际输出 → " + MediaAudio.routedNow());
            log("… 本机已播放音频包 " + audioPackets + " 个（type=" + type + "，通道累计 " + mediaBytes + " 字节）");
        }
    }

    private void videoRead() {
        try {
            InputStream in = new BufferedInputStream(video.getInputStream(), 65536);
            byte[] head = new byte[12];
            try { dumpFile = new File(ctx.getExternalFilesDir(null), "fakehu.h264"); dumpOut = new FileOutputStream(dumpFile); } catch (Throwable t) { }
            while (running) {
                if (!readFully(in, head, 12)) break;
                int len = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16)
                        | ((head[2] & 0xff) << 8) | (head[3] & 0xff);
                int type = ((head[8] & 0xff) << 24) | ((head[9] & 0xff) << 16)
                        | ((head[10] & 0xff) << 8) | (head[11] & 0xff);
                if (len <= 0 || len > 4 * 1024 * 1024) { log("× Video 长度异常 " + len); break; }
                byte[] pl = new byte[len];
                if (!readFully(in, pl, len)) break;
                videoFrames++;
                videoBytes += len;
                try {
                    // 原来每帧都往磁盘写，长时间跑会一直长；加个 120MB 上限，超了就停写（现场留前面这段够分析）
                    if (dumpOut != null) {
                        if (dumpBytes > 120L * 1024 * 1024) dumpOut = null;
                        else { dumpOut.write(pl); dumpBytes += pl.length; }
                    }
                } catch (Throwable t) { }
                // 预览默认一直推（开关由界面上的「预览」按钮控制：关的时候这里 preview 就是 null）
                // 投屏中（车机已在拉帧）时预览降频到 ~5fps：解码器不再和推流抢 CPU，
                // 车机那边的延迟和抖动会小一圈；没投屏时预览照常 30fps。
                if (preview != null) {
                    pvTick++;
                    if (true) {
                        byte[] feed = pl;
                        // 预览中途打开时手上没有参数集，关键帧前缀一份（只对预览有效，不影响推给仪表的原始帧）
                        if (!preview.hasCsd() && rig != null) {
                            byte[] ps = Rig.lastPs();
                            if (ps != null) {
                                feed = new byte[ps.length + pl.length];
                                System.arraycopy(ps, 0, feed, 0, ps.length);
                                System.arraycopy(pl, 0, feed, ps.length, pl.length);
                            }
                        }
                        preview.push(feed);
                    }
                }
                if (rig != null) rig.pushFrame(pl);
                Rig.noteCarFrame(pl);
                if (videoFrames <= 3) log("← Video type=" + type + " len=" + len + " 头 " + hex(pl, 16));
                if (videoFrames % 300 == 0) log("… 已收 " + videoFrames + " 帧 / " + videoBytes + " 字节");
            }
        } catch (Throwable t) { log("Video 读结束: " + t); }
    }

    private boolean readFully(InputStream in, byte[] b, int n) {
        int off = 0;
        try {
            while (off < n) {
                int r = in.read(b, off, n - off);
                if (r < 0) return false;
                off += r;
            }
            return true;
        } catch (Exception e) { return false; }
    }

    public void stop() {
        running = false;
        touchOut = null;
        synchronized (socks) {
            for (Socket s : socks) { try { s.close(); } catch (Exception e) { } }
            socks.clear();
        }
        if (touchExec != null) { touchExec.shutdownNow(); touchExec = null; touchOut = null; }
        try { if (dumpOut != null) dumpOut.close(); } catch (Exception e) { }
        MediaAudio.release();
        log("假车机已停止，共收 " + videoFrames + " 帧 / " + videoBytes + " 字节"
                + (dumpFile != null ? "，落盘 " + dumpFile.getAbsolutePath() : ""));
    }

    // ---- protobuf / hex 小工具 ----
    /**
     * 车机特性配置。AUDIO_TRANSMISSION_MODE 的语义在 CarLife 里已经反编译确认
     * （com.baidu.carlife.core.audio.AudioUtil.isUSingBTForAudio()）：
     *   0 = 通道模式：CarLife 把 PCM 塞进 Media 通道推给车机，手机自己静音；
     *   1 = 本地/蓝牙模式：MediaChannelSend.writeAudioData() 直接 return -1 不发送，
     *       音乐、导航 TTS、VR 音频全部留在手机本地播（NaviAudioManager/VRAudioManager
     *       在 isUSingBTForAudio() 为真时同样跳过通道）。这就是我们要的“音频留本机”。
     * 4.8 起改成 1。日志里之前一条音频包都没收到，就是因为一直是 0 却没人往通道里发。
     */
    public void resendFeatureConfig() { sendFeatureConfig(); }

    private void sendFeatureConfig() {
        try {
            byte[] item = concat(pbStr(1, KEY_AUDIO_TRANSMISSION_MODE), pbInt(2, AUDIO_MODE));
            // 列表结构：1=cnt，2=repeated CarlifeFeatureConfig
            byte[] list = concat(pbInt(1, 1), pbStr(2, item));
            sendCmd(HU_FEATURE_CONFIG_RESPONSE, list);
            log("→ HU_FEATURE_CONFIG_RESPONSE AUDIO_TRANSMISSION_MODE=" + AUDIO_MODE
                    + (AUDIO_MODE == 1 ? "（CarLife 自己留本机播）" : "（PCM 走通道，本机 AudioTrack 放）"));
        } catch (Throwable t) { log("× 配置发送失败 " + t); }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static byte[] pbStr(int field, String v) {
        try { return pbStr(field, v.getBytes("UTF-8")); } catch (Exception e) { return new byte[0]; }
    }

    private static byte[] pbStr(int field, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(((field << 3) | 2) & 0xff);
        writeVarint(o, v.length);
        o.write(v, 0, v.length);
        return o.toByteArray();
    }

    private static byte[] pbInt(int field, int value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(((field << 3) | 0) & 0xff);
        writeVarint(o, value);
        return o.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream o, int v) {
        while (true) {
            int b = v & 0x7f;
            v >>>= 7;
            if (v != 0) o.write(b | 0x80); else { o.write(b); return; }
        }
    }

    private static String hex(byte[] b) { return hex(b, b.length); }

    private static String hex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(max, b.length);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i]));
        return sb.toString();
    }
}
