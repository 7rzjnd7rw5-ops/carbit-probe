package io.carbit.probe;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 把 CarWith 那一路 H264 投给无极 DS625X 真仪表。
 *
 * 协议来源：CarWith 内置的 net.easyconn.carman 实现（MediaProjectService +
 * MediaProjectServerCtrlExecuteThread + TestMediaProjectService 这个“车机侧”参考客户端）。
 *
 * 角色：手机是 RV 服务端，仪表连进来。
 *   控制口 10711：8 字节小端头 cmdType(2)+cmdLen(2)+token(4)，其余全按小端。
 *     ＜- 16 REQ_RV_CONFIG_CAPTURE（32B：w,h short；fps int；encoder int；...；displayId）
 *     -＞ 17 RLY_RV_CONFIG_CAPTURE（int encoder + short w + short h，尺寸按 16 对齐）
 *     ＜- 48 REQ_GET_VERSION  -＞ 49 RLY_GET_VERSION（int 5002 + int 1）
 *     ＜- 64 REQ_HEARTBEAT    -＞ 65 RLY_HEARTBEAT（空体）
 *     ＜- 112 REQ_RV_DATA_START -＞ 113 RLY_RV_DATA_START（空体），此后开始供帧
 *   数据口 10710：仪表**拉**模式。
 *     ＜- 114 REQ_RV_DATA_NEXT（1 字节 displayId）
 *     -＞ int（小端，帧长）+ 该帧 Annex-B 数据；没有新帧时回长度 0，让仪表继续拉。
 */
public final class Rig {
    public static final int RV_CTRL_PORT = 10711;
    public static final int RV_DATA_PORT = 10710;
    /** 仪表反连进来的 PXC 主控口（CarWith: PXCService ServerSocket(10922)） */
    public static final int PXC_REVERSE_PORT = 10922;

    private static final short REQ_RV_CONFIG_CAPTURE = 16;
    private static final short RLY_RV_CONFIG_CAPTURE = 17;
    private static final short REQ_SCREEN_EVENT = 32;
    private static final short REQ_GET_VERSION = 48;
    private static final short RLY_GET_VERSION = 49;
    private static final short REQ_HEARTBEAT = 64;
    private static final short RLY_HEARTBEAT = 65;
    private static final short REQ_EXTEND = 96;
    private static final short REQ_RV_DATA_START = 112;
    private static final short RLY_RV_DATA_START = 113;
    private static final short REQ_RV_DATA_NEXT = 114;
    private static final short REQ_EXECUTE_SHELL = 272;
    private static final short RLY_UN_SUPPORT = 32514;

    private final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<byte[]>(8);

    private ServerSocket ctrlSs, dataSs, pxcRvsSs;
    private volatile Socket pxcRvsSock;
    /** 10922 上当前所有活着的会话，dropRound 要一次全掐掉（一条会话卡住时 pxcRvsSock 会指空）。 */
    private final java.util.List<Socket> rvsSocks =
            java.util.Collections.synchronizedList(new java.util.ArrayList<Socket>());

    /** 一次上车多跑几轮用的实验变体（0..VARIANT_COUNT-1），由界面按钮切换。 */
    public static volatile int variant = 0;
    /** 打开后每轮换下一个变体（不主动断开，等仪表自己重连）。 */
    // 10920 推流（RV 投屏）状态
    private static volatile H264Encoder rvEnc;
    private static volatile boolean rvArmed;

    /** 仪表已开流（供 FakeHu 决定是否暂停本地预览解码）。 */
    public static boolean rvArmedFlag() { return rvArmed; }
    private static int rvW = 1024, rvH = 608, rvBitrate = 4194304;
    private static int rvFrame, rvSent, rvNull, rvReqCount;
    private static long rvBytes;

    // CarWith 来的真帧：直接转给仪表（不再自己编码）
    private static volatile CarDecoder carDec;   // 保留字段占位，已不使用
    private static volatile byte[] rvCarFrame;
    private static volatile long rvCarMs;
    /** 待推队列：仪表按 0x0072 一帧一帧拉。8.33 起容量 90（约 3 秒），够吃掉仪表几百毫秒的停顿；
     *  20 帧只有 0.67 秒，18:11 那次就是被 715ms 的停拉顶爆，然后一路恶性循环。 */
    private static final ArrayBlockingQueue<byte[]> rvQ = new ArrayBlockingQueue<byte[]>(90);
    /** 队列峰值：仪表拉得比 CarWith 出得慢时，这个数就是"凭空多出来的延迟帧数" */
    private static volatile int rvQPeak = 0;
    /** 最近一个完整关键帧：仪表拉取时队列恰好空掉，就把这个 IDR 再发一次，避免"什么都不发→仪表停在 logo" */
    private static volatile byte[] rvLastKey;
    /** 预编好的黑场关键帧：仪表一问就回它，保证 10920 会话不被判超时 */
    private static volatile byte[] rvIdle;
    private static int rvIdleSent;
    private static long rvLastKeyAt;
    /** 上次真正回帧的时刻：用来判断"空队列"是真的断流，还是只是仪表拉得快。 */
    private static volatile long rvLastReplyMs;
    private static volatile long rvPulls;
    private static long rvRateAt;
    private static int rvDropped, rvKey, rvGaps, rvKeyDropped;   // 丢弃帧数 / 关键帧数 / 断流重同步次数 / 被丢掉的关键帧数
    private static long rvLastKeyMs, rvLastSize, rvStaticRun;
    private static volatile byte[] rvSpsPps;
    private static int rvCarCount, rvCarUsed;

    /** FakeHu 每收到一帧 CarWith 画面就登记一次（顺带记住 SPS/PPS）。 */
    /** 开流时通知外壳完全释放本地预览解码器（投屏期间不跑任何 MediaCodec）。 */
    public static volatile Runnable onArmed;
    /** 真帧断流时是否用测试画布兜底：会让编码器再起一路 codec，默认关闭。 */
    public static volatile boolean allowTestCanvas = false;
    private static volatile Net.Log slog;
    static Net.Log logSink() { return slog; }
    private static long lastBeatMs;
    private static void beat() {
        long now = System.currentTimeMillis();
        if (now - lastBeatMs < 1000) return;
        lastBeatMs = now;
        Net.Log lg = slog;
        if (lg == null) return;
        lg.line("▣ 阶段 CarWith帧=" + rvCarCount + " 推给仪表=" + rvSent
                + " rvArmed=" + rvArmed + " 预览降频=" + (rvArmed ? "是" : "否")
                + " 线程=" + Thread.currentThread().getName());
    }

    private static int spsLogged;
    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(b.length, 96);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", b[i] & 0xff));
        return sb.toString() + (b.length > n ? "…(" + b.length + "B)" : "");
    }

    private static long rvLastPullMs;
    private static byte[] rvLastOut;      // 上一次真正写给仪表的完整负载（含补的 SPS），空队列时原样重发保活

    static void noteCarFrame(byte[] p) {
        if (!rvArmed) FakeHu.previewAlt = Boolean.FALSE;
        if (p == null || p.length < 5) return;
        boolean key = isKeyFrame(p);
        if (hasSps(p)) {
            byte[] ps = extractPs(p);
            if (ps != null) {
                rvSpsPps = ps;
                if (spsLogged < 3) { spsLogged++; Net.Log lg = slog; if (lg != null) lg.line("   SPS原始: " + hex(ps)); }
            }
        }
        rvCarFrame = p;
        rvCarMs = System.currentTimeMillis();
        rvCarCount++;
        if (key) { rvKey++; rvLastKeyMs = rvCarMs; rvLastKey = p; rvLastKeyAt = rvCarMs; }
        enqueue(p, key);
        beat();
        // 注意：这里曾经再起一路 MediaCodec（CarDecoder 1280x480）去解码 CarWith 帧，
        // 而 CarWith 现在按仪表要求出的是 1024x608，尺寸对不上，MediaCodec 直接在 native 里崩，
        // 表现为"投出去第 1 帧后闪退且没有 Java 栈"。真帧直传不需要解码，已彻底移除。
    }

    /** 从一整帧里只抽出 SPS(7)/PPS(8) 两个 NAL（带起始码）。
     *  以前把整帧（SPS+PPS+IDR）都存成"参数集"，关键帧前又整段补一次 —— 等于把旧 IDR 也塞进去，
     *  仪表解码器收到两个 IDR 容易直接停摆。 */
    private static byte[] extractPs(byte[] p) {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        int n = p.length, i = 0;
        while (i + 3 < n) {
            int sc = startCodeLenAt(p, i);
            if (sc == 0) { i++; continue; }
            int t = p[i + sc] & 0x1f;
            int end = n;
            for (int j = i + sc + 1; j + 2 < n; j++) if (startCodeLenAt(p, j) > 0) { end = j; break; }
            if (t == 7 || t == 8) {
                for (int j = i; j < end; j++) bo.write(p[j]);
            }
            i = end;
        }
        return bo.size() > 0 ? bo.toByteArray() : null;
    }

    /** 最近一次从 CarWith 流里抽到的 SPS/PPS（中途开预览时用它补 csd，否则解码器永远配置不起来 → 黑屏） */
    public static byte[] lastPs() { return rvSpsPps; }

    /** 最近一帧关键帧（不带参数集）：中途开预览时先喂它，第一眼就是清晰画面，不会先糊几秒 */
    public static byte[] lastKey() { return rvLastKey; }

    private static int startCodeLenAt(byte[] b, int i) {
        if (i + 3 < b.length && b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) return 3;
        if (i + 4 < b.length && b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1) return 4;
        return 0;
    }

    /** 关键帧（IDR/SPS）判定，只扫前 200 字节，够用且便宜。 */
    private static boolean isKeyFrame(byte[] p) {
        int n = Math.min(p.length, 300);
        for (int i = 0; i + 4 < n; i++) {
            if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1) {
                int t = p[i + 3] & 0x1f;
                if (t == 5 || t == 7) return true;
                i += 2;
            } else if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 0 && p[i + 3] == 1 && i + 4 < n) {
                int t = p[i + 4] & 0x1f;
                if (t == 5 || t == 7) return true;
                i += 3;
            }
        }
        return false;
    }

    /**
     * 队列管理：仪表拉得慢时，绝不能从 GOP 中间抽帧（P 帧引用断了就是花屏）。
     * 满了就整段丢掉，等下一个关键帧重新开始，并在日志里记一次。
     */
    private static void enqueue(byte[] p, boolean key) {
        if (rvGaps > 0) {                 // 正在等关键帧：非关键帧一律丢
            if (!key) { rvDropped++; return; }
            rvGaps = 0;
            rvQ.clear();
            rvQ.offer(p);
            rvQPeak = Math.max(rvQPeak, rvQ.size());
            Net.Log lg = slog;
            if (lg != null) lg.line("   → 已对齐到新的关键帧，重新开始连续推流");
            return;
        }
        rvQPeak = Math.max(rvQPeak, rvQ.size());
        if (!rvQ.offer(p)) {
            // 8.33：满队列不再"整段清空 + 等下一个 IDR"。CarWith 基本只在开局发一次 IDR，
            // 一清空就永久没东西可推 → 仪表等不到帧就判超时、反复重连（18:11–18:13 那 39 次会话就是这来的）。
            // 改成滑动窗口：丢最旧的一帧，保住最近这一串连续帧，让仪表一直有帧可拉。
            byte[] oldest = rvQ.poll();
            if (oldest != null) {
                rvDropped++;
                if (isKeyFrame(oldest)) rvKeyDropped++;
            }
            if (!rvQ.offer(p)) rvDropped++;
            if (rvDropped == 1 || rvDropped % 300 == 0) {
                Net.Log lg = slog;
                if (lg != null) lg.line("   × 推得跟不上：按最旧帧滑出（累计丢 " + rvDropped + " 帧，其中关键帧 " + rvKeyDropped + "），仪表仍能持续拉到帧");
            }
        }
        // 等关键帧超时检测：CarWith 可能只在开局发一次 IDR，之后全是 P 帧
        if (rvGaps > 0 && rvGaps % 60 == 1) {
            Net.Log lg = slog;
            if (lg != null) lg.line("   ！丢了整段后已经 " + rvGaps + " 帧没等到关键帧 —— CarWith 大概只在开局发过一次 IDR，需要它重发");
        }
        // 画面静止检测：很久没有关键帧且每帧大小几乎一样 → CarWith 那边大概停在 logo 页
        if (rvCarCount > 60 && rvStaticRun == 0 && System.currentTimeMillis() - rvLastKeyMs > 5000) {
            rvStaticRun = 1;
            Net.Log lg = slog;
            if (lg != null) lg.line("   ！5 秒内没有关键帧、画面尺寸几乎不变 —— CarWith 可能停在 logo/CarLife 页，没在投真实界面");
        }
        if (key) rvStaticRun = 0;
    }

    private static boolean hasSps(byte[] p) {
        for (int i = 0; i + 4 < p.length && i < 200; i++) {
            if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1 && p[i + 3] == 0x67) return true;
            if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 0 && p[i + 3] == 1 && i + 4 < p.length && p[i + 4] == 0x67) return true;
        }
        return false;
    }

    /** 只为"别空回"预编一张 1024x608 的纯黑关键帧，编一次缓存，之后只重复发送。 */
    private synchronized byte[] buildIdle() {
        byte[] cur = rvIdle;
        if (cur != null) return cur;
        try {
            if (allowTestCanvas) {
                H264Encoder enc = new H264Encoder(rvW, rvH, rvBitrate, 5);
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(rvW, rvH, android.graphics.Bitmap.Config.ARGB_8888);
                drawWaiting(bmp);
                byte[] b = enc.encode(bmp);
                enc.release();
                if (b != null && b.length > 0) {
                    rvIdle = b;
                    if (log != null) log.line("   [10920] 「投屏准备中」占位帧已就绪 " + b.length + "B（" + rvW + "x" + rvH + "）");
                    return b;
                }
            }
        } catch (Throwable t) {
            if (log != null) log.line("   [10920] 占位帧生成失败 " + t);
        }
        return null;
    }

    /** 断流时给仪表看的占位画面：深底 + "投屏准备中…" + 转圈 + 小字说明，替代纯黑，让人一眼看出会话是活的。 */
    private static void drawWaiting(android.graphics.Bitmap bmp) {
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        int w = bmp.getWidth(), h = bmp.getHeight();
        c.drawColor(0xFF0B0F14);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        // 顶部一条极淡的渐变，避免纯黑看起来像"没信号"
        android.graphics.LinearGradient g = new android.graphics.LinearGradient(0, 0, 0, h,
                new int[]{0xFF16222E, 0xFF0B0F14}, null, android.graphics.Shader.TileMode.CLAMP);
        p.setShader(g); c.drawRect(0, 0, w, h, p); p.setShader(null);

        // 转圈（静态画一段弧，看着像在加载）
        p.setColor(0xFF2FA8FF); p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(3f, h / 90f));
        float cx = w / 2f, cy = h / 2f - h * 0.10f, r = h * 0.13f;
        c.drawArc(new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), -90, 260, false, p);
        p.setColor(0x66FFFFFF);
        c.drawArc(new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r), 170, 100, false, p);
        p.setStyle(android.graphics.Paint.Style.FILL);

        // 主标题
        p.setColor(0xFFF2F6FA); p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setFakeBoldText(true);
        p.setTextSize(h / 12f);
        c.drawText("投屏准备中…", cx, cy + h * 0.26f, p);

        // 副标题
        p.setFakeBoldText(false);
        p.setColor(0xFF8FA3B3);
        p.setTextSize(h / 22f);
        c.drawText("正在等 CarWith 出画面，稍等一下", cx, cy + h * 0.36f, p);
    }

    /** 车机要一帧：编一张画布推过去（4B 长度 + H264，官方 10920 数据口格式）。 */
    void serveRvFrame(java.io.OutputStream out) throws java.io.IOException {
        byte[] car = null;
        try { car = rvQ.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Throwable ignored) { }
        // 仪表几乎是"问一帧给一帧"的节奏，和 CarWith 的 30fps 打平，队列经常一瞬间是空的。
        // 先短等 20ms 让新帧进来，能明显减少"重复上一帧"的比例（重复帧多了仪表解码器容易停摆）。
        boolean resend = false;
        long nowMs = System.currentTimeMillis();
        if (car == null) {
            // 队列空（刚丢了整段 / 仪表拉得比 CarWith 出得慢）：这里以前每空一次就重发旧关键帧，
            // 结果仪表上"旧画面"和"新 P 帧"交替出现 → 一直闪。改成只有真断流 1.2 秒以上才补一帧旧 IDR 保活。
            byte[] k = rvLastKey;
            if (k != null && nowMs - rvLastReplyMs > 1200 && nowMs - rvLastKeyAt < 4000) { car = k; resend = true; }
        }
        long now2 = System.currentTimeMillis();
        if (rvRateAt == 0) rvRateAt = now2;
        if (now2 - rvRateAt >= 1000) {
            if (log != null) log.line("   [10920] 仪表拉帧 " + rvPulls + " 次 / 已推 " + rvSent
                    + " 帧（CarWith " + rvCarCount + " 帧 / 队列 " + rvQ.size() + "）");
            rvRateAt = now2;
        }
        if (car == null && rvLastOut != null) {
            // 仪表对 0x0072 是"问一次必须给一帧"：空回它会停拉、2 秒后整会话重连（13:09 那次 89 帧后静默）。
            // 所以队列空时把上一次真正发出去的字节原样再发一遍（P 帧重复顶多重复一帧画面，不断链、不插占位）。
            byte[] hdr = new byte[4];
            le32(hdr, 0, rvLastOut.length);
            out.write(hdr); out.write(rvLastOut); out.flush();
            rvSent++; rvIdleSent++; rvLastReplyMs = System.currentTimeMillis();
            if (rvIdleSent == 1 || rvIdleSent % 100 == 0)
                log.line("   [10920] 已推 " + rvSent + " 帧（重复上一帧保活 " + rvIdleSent + " 次 / " + rvLastOut.length
                        + "B / 队列 " + rvQ.size() + " / 累计丢 " + rvDropped + "）");
            return;
        }
        long age = System.currentTimeMillis() - rvCarMs;
        if (car != null) {
            boolean key = isKeyFrame(car);
            byte[] payload = car;
            byte[] sps = rvSpsPps;
            // 只有关键帧前面才补 SPS/PPS；普通 P 帧带上 SPS 反而会让仪表解码器重新初始化
            if (key && sps != null && sps != car && !hasSps(car)) {
                payload = new byte[sps.length + car.length];
                System.arraycopy(sps, 0, payload, 0, sps.length);
                System.arraycopy(car, 0, payload, sps.length, car.length);
            }
            byte[] hdr = new byte[4];
            le32(hdr, 0, payload.length);
            out.write(hdr); out.write(payload); out.flush();
            rvLastOut = payload;
            rvSent++; if (!resend) rvCarUsed++; rvBytes += payload.length;
            rvLastReplyMs = nowMs;
            if (rvSent == 1 || rvSent % 200 == 0) {
                log.line("   [10920] 已推 " + rvSent + " 帧（" + (resend ? "重发关键帧" : "原帧 " + rvCarUsed) + " / 本次 " + payload.length
                        + "B / " + (key ? "关键帧" : "P帧") + " / 帧龄 " + age + "ms / 队列 " + rvQ.size()
                        + " / 累计丢 " + rvDropped + " / 关键帧数 " + rvKey + " / 队列峰值 " + rvQPeak + "）");
            }
            return;
        }
        // 真帧断流：仪表对 0x0072 是"问一次要一帧"，空回它就 2 秒后整会话断开（日志里会话#3..#18 每 2s 一条）。
        // 所以这里必须发东西：把一张黑场关键帧预先编好、缓存起来反复发，成本只有一次编码。
        if (!allowTestCanvas) {
            rvNull++;
            if (rvSent == 0 && rvReqCount <= 1) log.line("   [10920] 还没有 CarWith 帧可推，先不回（避免另起 MediaCodec）");
            return;
        }
        byte[] idle = rvIdle;
        if (idle == null) idle = buildIdle();
        if (idle != null) {
            byte[] hdr = new byte[4];
            le32(hdr, 0, idle.length);
            out.write(hdr); out.write(idle); out.flush();
            rvSent++; rvIdleSent++; rvLastReplyMs = System.currentTimeMillis();
            if (rvIdleSent == 1 || rvIdleSent % 50 == 0)
                log.line("   [10920] 已推 " + rvSent + " 帧（「投屏准备中」占位 " + rvIdleSent + " 次 / " + idle.length
                        + "B / 还没有 CarWith 帧 / 队列 " + rvQ.size() + "）");
            return;
        }
        H264Encoder enc = rvEnc;
        if (enc == null) {
            try {
                enc = new H264Encoder(rvW, rvH, rvBitrate, 25);
                rvEnc = enc;
                log.line("   [10920] 测试画布编码器起来 " + rvW + "x" + rvH);
            } catch (Throwable t) { log.line("   [10920] 编码器失败 " + t); rvNull++; return; }
        }
        rvFrame++;
        byte[] f = enc.encode(H264Encoder.testCanvas(rvW, rvH, rvFrame, System.currentTimeMillis()));
        if (f == null) { rvNull++; return; }
        byte[] hdr = new byte[4];
        le32(hdr, 0, f.length);
        out.write(hdr); out.write(f); out.flush();
        rvSent++;
        if (rvSent == 1 || rvSent % 50 == 0) {
            log.line("   [10920] 已推 " + rvSent + " 帧（测试画布，CarWith 已断帧 " + age + "ms）");
        }
    }



    public static volatile boolean autoRotate = false;
    public static final int VARIANT_COUNT = 7;

    public static String variantName(int v) {
        switch (v) {
            case 0: return "0 咕咕行包名+全字段(gugu/50/token0/指纹)";
            case 1: return "1 咕咕行包名+无指纹";
            case 2: return "2 咕咕行包名+versionCode=1";
            case 3: return "3 咕咕行包名+supportFunction=0";
            case 4: return "4 官方 voge 包名+全字段";
            case 5: return "5 voge 包名+token/versionCode 字符串";
            case 6: return "6 voge 包名+pubkey/encHUID 空";
            default: return String.valueOf(v);
        }
    }

    /** 只掐当前这一轮 PXC 会话，逼仪表重连，用下一轮的变体再试。 */
    public void dropRound() {
        int n = 0;
        synchronized (rvsSocks) {
            for (Socket s : rvsSocks) { try { s.close(); n++; } catch (Throwable ignored) { } }
            rvsSocks.clear();
        }
        if (n == 0) { log.line("[PXC反连] 当前没有会话可断"); return; }
        log.line("[PXC反连] 已掐断 " + n + " 条会话，等仪表重连（下一轮变体 " + variantName(nextVariant()) + "）");
    }

    private int nextVariant() { return (variant + 1) % VARIANT_COUNT; }
    private volatile String carHuid = "", carChannel = "", carFlavor = "";
    private volatile long carSupportFunction = 0;
    private volatile int authFailCount = 0;

    // ---- 给界面用的"本轮体检"状态：什么时候可以换下一个变体 ----
    public static volatile long roundStartAt = 0;      // 当前这轮开始的毫秒时间
    public static volatile int roundNo = 0;            // 第几次会话
    public static volatile int roundAuthFails = 0;     // 本轮收到的 AUTH_FAIL 次数
    public static volatile boolean roundSuccess = false; // 本轮是否出现成功迹象
    public static volatile int roundSessions = 0;      // 本轮已重连几次（仪表 2 秒静默断会一直刷这个）
    private volatile int rvsSessionNo = 0;
    private volatile boolean p2cSession = false;
    private static final String APPSTATUS_JSON = "{\"displayRotation\":1,\"width\":1280,\"height\":720"
            + ",\"enableAccessibility\":false,\"enableAOAHid\":false,\"mode\":1}";
    private Socket ctrlSock, dataSock;
    private Net.Log log;
    void bindLog(Net.Log l) { log = l; slog = l; }
    private volatile boolean running;
    private volatile boolean streaming;
    private volatile int ctrlClients, dataClients;
    private volatile long framesPushed, bytesPushed;
    private volatile int cfgW = 0, cfgH = 0, cfgFps = 0, cfgEncoder = 2;
    private volatile Socket pxcSock;
    private volatile boolean pxcUp;
    private volatile int rvsFrames;

    public boolean isRunning() { return running; }
    public static int carFrames() { return rvCarCount; }
    public static int keyFrames() { return rvKey; }
    /** 断流后一直等不到关键帧（锁屏/熄屏后 CarWith 常不再发 IDR）：仪表画面会就此卡死 */
    public static boolean starved() {
        return rvGaps > 0 && System.currentTimeMillis() - rvLastKeyMs > 2000;
    }

    public int ctrlClients() { return ctrlClients; }
    public int dataClients() { return dataClients; }
    /** 统一设置推流尺寸：尺寸一变，之前按旧尺寸编好的占位帧就不能再发了（解码器会尺寸不符）。 */
    private static synchronized void setSize(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (w != rvW || h != rvH) {
            rvW = w; rvH = h;
            rvIdle = null;
        }
    }

    public int width() { return cfgW; }
    public int height() { return cfgH; }
    public long pushedFrames() { return framesPushed; }

    /** 给界面/日志用的一句话实况：绝不在仪表没连进来时报“成功”。 */
    public String statusText() {
        if (!running) return "投屏没在跑";
        if (ctrlClients == 0 && dataClients == 0)
            return "已开端口，等仪表连入（0 台）" + (pxcRvsSock != null ? "｜仪表 PXC 已连" : "｜等 PXC 反连");
        if (dataClients == 0) return "控制口已接入(" + ctrlClients + " 台)，等数据口连入";
        if (!streaming) return "仪表已接数据口，等它发 112 开始取流";
        // 下面这行不会执行到（streaming 时直接返回），保留结构清晰
        return "仪表取流中：" + framesPushed + " 帧";
    }

    public void start(final android.content.Context ctx, final String dashIp, String identity, Net.Log lg) {
        if (running) { lg.line("投屏已在跑：" + statusText()); return; }
        running = true;
        log = lg;
        appCtx = ctx;
        frames.clear();
        framesPushed = 0; bytesPushed = 0; streaming = false;
        rvSent = 0; rvBytes = 0; rvCarCount = 0; rvCarUsed = 0;
        rvDropped = 0; rvKey = 0; rvGaps = 0; rvQ.clear();
        rvLastKeyMs = System.currentTimeMillis(); rvStaticRun = 0;
        cfgW = 0; cfgH = 0; cfgFps = 0;

        final String idn = identity;
        new Thread(new Runnable() { @Override public void run() { pxcLoop(dashIp, idn); } }, "rig-pxc").start();

        new Thread(new Runnable() { @Override public void run() { acceptLoop(true); } }, "rig-ctrl").start();
        new Thread(new Runnable() { @Override public void run() { acceptLoop(false); } }, "rig-data").start();
        new Thread(new Runnable() { @Override public void run() { acceptPxcReverse(); } }, "rig-pxcrvs").start();
        // 10920/10921 也一起开：实车日志显示仪表会在 CHECK_SN 通过后 2 秒主动连 10921 并吐一坨
        // （08:05:54 那次没解析下来）。只在“被动监听”里开这两个口的话，单点“投给仪表”就抓不到它。
        for (int p : new int[]{10920, 10921}) {
            final int pp = p;
            new Thread(new Runnable() { @Override public void run() { acceptPxcLegacy(pp); } }, "rig-pxc" + pp).start();
        }
        new Thread(new Runnable() { @Override public void run() { statusLoop(); } }, "rig-status").start();
    }

    /**
     * PXC 长连：真实 CarWith 连上 10930 后是一直挂着的（心跳 cmd=0x70000000 每 2 秒左右）。
     * 之前我们发完一句就关 socket，仪表那边看到的就是“来了又走”，不会当成配对完成。
     */
    private void pxcLoop(String host, String identity) {
        int fails = 0, ups = 0;
        while (running) {
            Socket s = new Socket();
            try {
                s.connect(new java.net.InetSocketAddress(host, Proto.WAKE_PORT), 3000);
                s.setTcpNoDelay(true);
                s.setSoTimeout(1500);
                java.io.OutputStream out = s.getOutputStream();
                byte[] frame = Proto.wakeFrame(identity);
                out.write(frame);
                out.flush();
                pxcSock = s; pxcUp = true;
                ups++;
                if (ups <= 2 || ups % 10 == 0)
                    log.line("== PXC 已连 " + host + ":10930（第 " + ups + " 次；仪表回完探针就关 socket，属正常）");
                java.io.InputStream in = s.getInputStream();
                byte[] h = new byte[16];
                long lastBeat = 0;
                while (running) {
                    long now = System.currentTimeMillis();
                    if (now - lastBeat > 2500) {
                        byte[] beat = new byte[16];
                        le32(beat, 0, 0x70000000);
                        le32(beat, 4, 16);
                        le32(beat, 8, 0x70000000 ^ 16);
                        out.write(beat); out.flush();
                        lastBeat = now;
                    }
                    try {
                        if (!readFully(in, h, 16)) break;
                    } catch (java.net.SocketTimeoutException to) { continue; }
                    int cmd = le32(h, 0);
                    int total = le32(h, 4);
                    int magic = le32(h, 8);
                    if ((cmd ^ total) != magic || total < 16 || total > 65536) {
                        log.line("[PXC] 头异常 " + cmd + "/" + total);
                        continue;
                    }
                    byte[] body = new byte[total - 16];
                    if (body.length > 0 && !readFully(in, body, body.length)) break;
                    if (cmd == 0x70000000) continue;                       // 心跳
                    if (cmd == 0x70000011) continue;                       // 探针应答
                    if (cmd == 0x00030020) {                               // R2A_MIRROR_START：仪表要开投屏了
                        int w = body.length >= 4 ? le32(body, 0) : 0;
                        int ctl = body.length >= 8 ? le32(body, 4) : 0;
                        log.line("★★ [PXC] 仪表发来 MIRROR_START  width=" + w + " controlType=" + ctl
                                + " —— 它要开投屏了，接下来等 10711 连入");
                    } else {
                        log.line("[PXC] cmd=0x" + Integer.toHexString(cmd) + " len=" + body.length
                                + (body.length > 0 && body.length <= 200 ? " body=" + new String(body, java.nio.charset.StandardCharsets.UTF_8) : ""));
                    }
                }
            } catch (Throwable t) {
                if (running) {
                    fails++;
                    if (fails <= 2 || fails % 10 == 0) {
                        log.line("× PXC 主动连 " + host + ":10930 失败 " + t.getClass().getSimpleName()
                                + "（第 " + fails + " 次；10930 不通说明这个地址不是仪表）");
                    }
                }
            } finally {
                pxcUp = false;
                pxcSock = null;
                try { s.close(); } catch (Throwable t) { }
            }
            if (running) { try { Thread.sleep(3000); } catch (Throwable t) { break; } }
        }
    }

    /**
     * 仪表反连 10922（CarWith 里 PXCService 就在这个口上 accept）。
     * 实车已抓到：仪表连入 → CONNECT(0x10000) → 我们回 CONNECT_ACK(0x10001)
     * → 仪表发 0x10010 一坨 JSON（HUID/channel/flavor/mirrorMode/supportScreenTouch…）。
     * 之后必须常挂 + 心跳，仪表才会把“已连接”状态做实、才会发 MIRROR_START。
     */
    private void acceptPxcReverse() {
        try {
            ServerSocket ss = new ServerSocket(PXC_REVERSE_PORT);
            ss.setReuseAddress(true);
            pxcRvsSs = ss;
            log.line("监听 :" + PXC_REVERSE_PORT + " 已就绪（仪表反连的 PXC 主控口）");
            while (running) {
                final Socket c = ss.accept();
                c.setTcpNoDelay(true);
                c.setKeepAlive(true);
                pxcRvsSock = c;
                rvsSocks.add(c);
                log.line("★★ [PXC反连] 仪表连入 " + c.getInetAddress().getHostAddress()
                        + "（本轮第 " + (++rvsSessionNo) + " 次会话）");
                new Thread(new Runnable() { @Override public void run() { pxcReverseSession(c); } }, "pxcrvs-sess").start();
            }
        } catch (Throwable t) {
            if (running) log.line("监听 :" + PXC_REVERSE_PORT + " 停了 " + t);
        }
    }

    /**
     * 10920 / 10921：仪表在授权通过后会主动连过来（实车 08:05:54 有一次，被我们逐字节丢掉）。
     * 这里只做“留证据”的事：连入就报、收到什么就把整段 hex 打一行，一个字节都不回，socket 挂着。
     */
    private final java.util.List<ServerSocket> legacySs = java.util.Collections.synchronizedList(new java.util.ArrayList<ServerSocket>());

    private void acceptPxcLegacy(final int port) {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(port);
            ss.setReuseAddress(true);
            legacySs.add(ss);
            log.line("监听 :" + port + " 已就绪（仪表可能会在授权后连这里）");
            while (running) {
                final Socket c = ss.accept();
                c.setTcpNoDelay(true);
                c.setKeepAlive(true);
                rvsSocks.add(c);
                final String from = c.getInetAddress().getHostAddress();
                log.line("★★ [" + port + "] 仪表连入 " + from);
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            java.io.InputStream in = c.getInputStream();
                            java.io.OutputStream out = c.getOutputStream();
                            byte[] buf = new byte[512];
                            int n;
                            while (running) {
                                // 先按 8 字节 ReqBase（cmdType u16 / cmdLength u16 / token i32，全小端）读
                                byte[] hdr = new byte[8];
                                int got = readFullyN(in, hdr, 8);
                                if (got < 8) break;
                                int cmd = (hdr[0] & 0xFF) | ((hdr[1] & 0xFF) << 8);
                                int len = (hdr[2] & 0xFF) | ((hdr[3] & 0xFF) << 8);
                                byte[] body = new byte[len];
                                if (len > 0 && readFullyN(in, body, len) < len) {
                                    log.line("   [" + port + "] 只读到部分 body，放弃");
                                    break;
                                }
                                if (cmd == 0x0072) {
                                    rvReqCount++;
                                    if (rvReqCount == 1 || rvReqCount % 120 == 0) {
                                        log.line("   [" + port + "] cmd=0x0072 拉帧 #" + rvReqCount);
                                    }
                                } else {
                                    log.line("   [" + port + "] cmd=0x" + String.format("%04X", cmd) + " len=" + len
                                            + (len > 0 && len <= 64 ? " body=" + Proto.hex(body) : ""));
                                }
                                if (port == 10921 && cmd == 0x0040) {          // REQ_HEARTBEAT
                                    byte[] hb = new byte[8];
                                    hb[0] = 0x41; hb[1] = 0x00; hb[2] = 0x00; hb[3] = 0x00;
                                    out.write(hb); out.flush();
                                    continue;
                                }
                                if (port == 10921 && cmd == 0x0070) {          // REQ_RV_DATA_START：车机要开始拉流
                                    byte[] rs = new byte[8];
                                    rs[0] = 0x71; rs[1] = 0x00; rs[2] = 0x00; rs[3] = 0x00;
                                    out.write(rs); out.flush();
                                    rvArmed = true;
                                    FakeHu.previewAlt = Boolean.TRUE;
                                    log.line("   [10921] 已回 RLY_RV_DATA_START(0x0071)，等它连 10920 发 0x0072 拉帧");
                                    Runnable r = onArmed;
                                    if (r != null) { try { r.run(); } catch (Throwable t) { log.line("   armed 钩子异常 " + t); } }
                                    continue;
                                }
                                if (port == 10920 && cmd == 0x0072) {          // REQ_RV_DATA_NEXT：要一帧
                                    rvPulls++;
                                    long gapp = System.currentTimeMillis() - rvLastPullMs;
                                    if (rvLastPullMs != 0 && gapp > 200)
                                        log.line("   [10920] 仪表停拉 " + gapp + "ms 后才又来要帧（上次已推 " + rvSent
                                                + " 帧 / 队列 " + rvQ.size() + "）");
                                    rvLastPullMs = System.currentTimeMillis();
                                    serveRvFrame(out);
                                    continue;
                                }
                                if (cmd == 0x0010 && len >= 32) {   // REQ_RV_CONFIG_CAPTURE：车机来要画面
                                    int w = (short) ((body[0] & 0xFF) | ((body[1] & 0xFF) << 8));
                                    int h = (short) ((body[2] & 0xFF) | ((body[3] & 0xFF) << 8));
                                    int wantFps = le32(body, 4);
                                    int wantEnc = le32(body, 8);
                                    int bitrate = le32(body, 20);
                                    setSize((w & ~15), (h & ~15)); rvBitrate = bitrate > 0 ? bitrate : 4194304;
                                    log.line("   [" + port + "] RV_CONFIG_CAPTURE " + w + "x" + h + " fps=" + wantFps
                                            + " encoder=" + wantEnc + " bitrate=" + bitrate
                                            + " 要我们把 H264 推给它");
                                    // 回 RLY_RV_CONFIG_CAPTURE(0x0011)：8B ReqBase + 8B RlyConfigCapture
                                    //   RlyConfigCapture = encoder(int) captureWidth(short) captureHeight(short)
                                    byte[] ry = new byte[16];
                                    ry[0] = 0x11; ry[1] = 0x00;         // cmdType = 17
                                    ry[2] = 0x08; ry[3] = 0x00;         // length = 8
                                    le32(ry, 4, 0);                     // token 0（官方固定写 0）
                                    le32(ry, 8, 2);                     // encoder 2 = H264 硬编
                                    short cw = (short) (w & ~15);
                                    short ch = (short) (h & ~15);
                                    ry[12] = (byte) (cw & 0xFF); ry[13] = (byte) ((cw >> 8) & 0xFF);
                                    ry[14] = (byte) (ch & 0xFF); ry[15] = (byte) ((ch >> 8) & 0xFF);
                                    out.write(ry); out.flush();
                                    log.line("   [" + port + "] 已回 RLY_RV_CONFIG_CAPTURE(0x0011) encoder=2 "
                                            + cw + "x" + ch + " —— 等它连 10920 要帧");
                                }
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            rvsSocks.remove(c);
                            try { c.close(); } catch (Throwable ignored) { }
                            log.line("   [" + port + "] 连接关闭");
                        }
                    }
                }, "pxc" + port).start();
            }
        } catch (Throwable t) {
            if (running) log.line("监听 :" + port + " 停了 " + t);
        } finally {
            try { if (ss != null) ss.close(); } catch (Throwable ignored) { }
        }
    }

    /** 车机侧的 HUID，用来签 encrypedHUID（RSA 私钥加密、车机用公钥解密比对）。 */
    private static final String DASH_HUID = "CARBIT-EXAMPLE-UUID";
    private static java.security.KeyPair rsa;

    private static final String FAKE_IMEI = "869274050000000"; // Luhn 合法的 15 位，冒充 IMEI

    private static synchronized void ensureRsa() {
        if (rsa != null) return;
        // CarWith 的做法是把密钥对存 SharedPreferences 重复用；车机可能按 HUID 记住了上次的公钥，
        // 我们之前每次进程重启都换新钥匙，等于每轮都是陌生人。
        try {
            if (appCtx != null) {
                android.content.SharedPreferences sp = appCtx.getSharedPreferences("rig_rsa", 0);
                String pv = sp.getString("priv", ""), pb = sp.getString("pub", "");
                if (pv.length() > 0 && pb.length() > 0) {
                    java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
                    java.security.PrivateKey pri = kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(android.util.Base64.decode(pv, 2)));
                    java.security.PublicKey pub = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(android.util.Base64.decode(pb, 2)));
                    rsa = new java.security.KeyPair(pub, pri);
                    return;
                }
            }
        } catch (Throwable ignored) { }
        try {
            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
            g.initialize(1024);
            rsa = g.generateKeyPair();
            if (appCtx != null) {
                appCtx.getSharedPreferences("rig_rsa", 0).edit()
                    .putString("priv", b64(rsa.getPrivate().getEncoded()))
                    .putString("pub", b64(rsa.getPublic().getEncoded()))
                    .apply();
            }
        } catch (Throwable ignored) { }
    }

    private static String b64(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    private static String prefixToMask(int plen) {
        int m = plen <= 0 ? 0 : (plen >= 32 ? -1 : (-1 << (32 - plen)));
        return ((m >>> 24) & 255) + "." + ((m >>> 16) & 255) + "." + ((m >>> 8) & 255) + "." + (m & 255);
    }

    /** 照 CarWith EBT_P2C_CLIENT_INFO(0x80010)：P2C 通道建好后把手机自己的网络接口报给车机，
        车机要知道往哪个地址（p2p0 / wlan0）去连 10711/10710，才敢开 RV 投屏通道。 */
    public static android.content.Context appCtx;
    private static String phoneId() {
        // 与 0x10011 里的 phoneUUID 用同一串（CarWith 两处都是 IMEI）
        return FAKE_IMEI;
    }

    private static String p2cClientInfoJson() {
        StringBuilder nets = new StringBuilder("[");
        boolean first = true;
        try {
            java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                String nm = ni.getName() == null ? "" : ni.getName();
                if (ni.isLoopback() || !ni.isUp()) continue;
                if (!(nm.startsWith("p2p") || nm.startsWith("wlan") || nm.startsWith("ap"))) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof java.net.Inet4Address)) continue;
                    byte[] b = ia.getAddress().getAddress();
                    // byte 是有符号的，192 会变成 -64，必须 &0xFF（4.0 就栽在这，车机收到 "-64.-88.13.20"）
                    String addr = (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + "." + (b[3] & 0xFF);
                    String mask = prefixToMask(ia.getNetworkPrefixLength());
                    if (!first) nets.append(",");
                    first = false;
                    nets.append("{\"name\":\"").append(nm).append("\",\"addr\":\"")
                        .append(addr).append("\",\"mask\":\"").append(mask).append("\"}");
                }
            }
        } catch (Throwable ignored) { }
        nets.append("]");
        return "{\"phoneType\":0,\"phoneID\":\"" + phoneId() + "\""
                + ",\"phoneName\":\"" + android.os.Build.MODEL + "\""
                + ",\"packageName\":\"com.jczy.voge\""
                + ",\"netInterface\":" + nets + "}";
    }

    /** 最近一条活着的 PXC(10922) 输出流：输入指令走这里，不走 CarLife 的 Cmd 通道 */
    static volatile OutputStream pxcOut;

    /** 给别的类用：往当前 PXC 会话发命令；没有活会话返回 false。 */
    static boolean pxcSend(int cmd, byte[] body) {
        OutputStream o = pxcOut;
        if (o == null) return false;
        try { sendPxc(o, cmd, body); return true; } catch (Throwable t) { return false; }
    }

    /** 在已建好的 PXC 连接上发一条 16B 头的小端命令。 */
    private static void sendPxc(OutputStream out, int cmd, byte[] body) throws java.io.IOException {
        int total = 16 + (body == null ? 0 : body.length);
        byte[] b = new byte[total];
        le32(b, 0, cmd); le32(b, 4, total); le32(b, 8, cmd ^ total);
        if (body != null && body.length > 0) System.arraycopy(body, 0, b, 16, body.length);
        out.write(b); out.flush();
    }

    /** 照 CarWith ECP_R2A_AUTH_HUID(0x30010)：车机发来用我们公钥加密的数据，用私钥解。 */
    private static boolean rsaDecryptDump(byte[] body, StringBuilder out) {
        ensureRsa();
        if (rsa == null || body == null || body.length == 0) return false;
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, rsa.getPrivate());
            byte[] p = c.doFinal(body);
            if (out != null) out.append(Proto.hex(p.length > 64 ? java.util.Arrays.copyOf(p, 64) : p));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 照 CarWith RSAUtil：pubkey = Base64(X.509)，encryptedHUID = Base64(私钥加密 HUID)。 */
    private static String rsaPubKey() {
        ensureRsa();
        return rsa == null ? "" : b64(rsa.getPublic().getEncoded());
    }

    private static String rsaEncHuid() {
        ensureRsa();
        if (rsa == null) return "";
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, rsa.getPrivate());
            return b64(c.doFinal(DASH_HUID.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 冒充手机的 0x10011 应答体。字段表照抄官方 SDK ECP_C2P_CLIENT_INFO.reply()
     * （voge10 第 725–791 行）。变体只在“车机到底认哪一套身份”的假设之间切换，
     * 其余字段一律按官方给，免得同时改多个变量看不出因果。
     */
    private String phoneInfoJson() {
        ensureRsa();
        int v;
        if (autoRotate) v = (Math.max(1, rvsSessionNo) - 1) % VARIANT_COUNT;
        else v = variant;
        log.line("[PXC反连] 会话#" + rvsSessionNo + " 变体 = " + variantName(v));
        roundNo = rvsSessionNo;
        roundStartAt = System.currentTimeMillis();
        roundAuthFails = 0;
        roundSuccess = false;
        roundSessions = rvsSessionNo;

        String pkg = "com.caigetuxun.app.gugu";
        String verCode = "50";
        String token = "0";
        String pub = rsaPubKey();
        String enc = rsaEncHuid();
        String fp = "\"appVersionFingerPrint\":\"V:1.3.4(50)-voge-release\",";
        String sf = "128";
        switch (v) {
            case 1:  fp = ""; break;
            case 2:  verCode = "1"; break;
            case 3:  sf = "0"; break;
            case 4:  pkg = "com.jczy.voge"; break;
            case 5:  pkg = "com.jczy.voge"; verCode = "\"50\""; token = "\"0\""; break;
            case 6:  pkg = "com.jczy.voge"; pub = ""; enc = ""; break;
            default: break;
        }
        return "{"
                + "\"pxcVersion\":\"1.0.2\","
                + "\"phoneUUID\":\"" + FAKE_IMEI + "\","
                + "\"phoneBrand\":\"" + android.os.Build.BRAND + "\","
                + "\"phoneModel\":\"" + android.os.Build.MODEL + "\","
                + "\"phoneOsVersion\":\"" + android.os.Build.VERSION.SDK_INT + "\","
                + "\"phoneOs\":\"Android\","
                + "\"package\":\"" + pkg + "\","
                + "\"versionCode\":" + verCode + ","
                + "\"token\":" + token + ","
                + "\"pubkey\":\"" + pub + "\","
                + "\"encryptedHUID\":\"" + enc + "\","
                + "\"bluetoothName\":\"\","
                + "\"supportH264IFrame\":true,"
                + "\"supportFunction\":" + sf + ","
                + fp
                + "\"haveSysScreenInSetting\":true"
                + "}";
    }

    private void pxcReverseSession(Socket c) {
        try {
            java.io.InputStream in = c.getInputStream();
            java.io.OutputStream out = c.getOutputStream();
            pxcOut = out;
            byte[] h = new byte[16];
            long lastBeat = System.currentTimeMillis();
            long lastStatus = 0;
            long t0 = lastBeat;
            boolean alive = false;
            while (running && !c.isClosed()) {
                // 只在拿到能力 JSON 之后才喂心跳；握手前多发一个字节都可能被对端当协议错直接掐
                long now = System.currentTimeMillis();
                if (alive && now - lastBeat > 4000) {
                    byte[] beat = new byte[16];
                    le32(beat, 0, 0x70000000); le32(beat, 4, 16); le32(beat, 8, 0x70000000 ^ 16);
                    out.write(beat); out.flush();
                    lastBeat = now;
                }
                // 镜像还活着就每 3 秒重报一次前台状态，车机状态机一般靠这个刷“投屏中”
                if (p2cSession && alive && now - lastStatus > 3000) {
                    sendPxc(out, 0x00020020, APPSTATUS_JSON.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    lastStatus = now;
                }
                byte[] hdr = h;
                if (!readFully(in, hdr, 16)) break;
                int cmd = le32(hdr, 0);
                int total = le32(hdr, 4);
                int magic = le32(hdr, 8);
                if ((cmd ^ total) != magic || total < 16 || total > 262144) {
                    log.line("[PXC反连] 头异常 cmd=" + cmd + " total=" + total);
                    break;
                }
                byte[] body = new byte[total - 16];
                if (body.length > 0 && !readFully(in, body, body.length)) break;

                if (++rvsFrames <= 8) {
                    StringBuilder sb = new StringBuilder("[PXC反连] 帧#" + rvsFrames + " cmd=0x")
                            .append(Integer.toHexString(cmd)).append(" total=").append(total);
                    int n = Math.min(total, 48);
                    byte[] all = new byte[n];
                    System.arraycopy(hdr, 0, all, 0, 16);
                    if (n > 16) System.arraycopy(body, 0, all, 16, n - 16);
                    sb.append(" hex=").append(Proto.hex(all));
                    log.line(sb.toString());
                }

                if (cmd == 0x00010000) { // CONNECT
                    byte[] ack = new byte[16];
                    le32(ack, 0, 0x00010001); le32(ack, 4, 16); le32(ack, 8, 0x00010001 ^ 16);
                    out.write(ack); out.flush();
                    log.line("[PXC反连] CONNECT → 已回 CONNECT_ACK");
                    alive = true; lastBeat = System.currentTimeMillis();
                } else if (cmd == 0x00010010) { // 车机能力 JSON
                    alive = true;
                    lastBeat = System.currentTimeMillis();
                    carFlavor = grab(body, "flavor");
                    carHuid = grab(body, "HUID");
                    carChannel = grab(body, "channel");
                    try { carSupportFunction = Long.parseLong(grab(body, "supportFunction")); } catch (Throwable ignored) { }
                    log.line("[PXC反连] 车机能力 JSON " + body.length + "B：HUID=" + carHuid
                            + " channel=" + carChannel + " flavor=" + carFlavor
                            + " mirrorMode=" + grab(body, "mirrorMode")
                            + " supportScreenMirroring=" + grab(body, "supportScreenMirroring")
                            + " supportRVForAdb=" + grab(body, "supportRVForAdb")
                            + " supportScreenTouch=" + grab(body, "supportScreenTouch"));
                    // 真机 CarWith 收到车机信息后必须回一条 0x10011（CMD+1）+ 手机信息 JSON，
                    // 车机拿到这份“手机能力/身份”才会把“投屏”放出来。之前一直没回，所以它只握手不投。
                    byte[] info = phoneInfoJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    byte[] resp = new byte[16 + info.length];
                    le32(resp, 0, 0x00010011);
                    le32(resp, 4, 16 + info.length);
                    le32(resp, 8, 0x00010011 ^ (16 + info.length));
                    System.arraycopy(info, 0, resp, 16, info.length);
                    out.write(resp); out.flush();
                    lastBeat = System.currentTimeMillis();
                    log.line("[PXC反连] 已回 0x10011 手机信息 JSON（" + info.length + "B） pubkey="
                            + rsaPubKey().length() + "B encHUID=" + rsaEncHuid().length() + "B");
                    log.line("[PXC反连] 0x10011 全文: " + new String(info, java.nio.charset.StandardCharsets.UTF_8));
                    log.line("[PXC反连] 连接已建立并保持，等仪表自己开 RV 通道（0x40000 → MIRROR_START 0x30020）");
                } else if (cmd == 0x00030020) { // R2A_MIRROR_START
                    int w = body.length >= 4 ? le32(body, 0) : 0;
                    int ctl = body.length >= 8 ? le32(body, 4) : 0;
                    roundSuccess = true;
                    log.line("★★ [PXC反连] MIRROR_START width=" + w + " controlType=" + ctl
                            + " —— 仪表要投屏了，接下来等它连 10711/10710");
                } else if (cmd == 0x00020000 || cmd == 0x00030000 || cmd == 0x00033000
                        || cmd == 0x00034000 || cmd == 0x00080000) {
                    // 仪表另开一条反向控制连接（P2C/RV 等 channel），它发 CONN_TYPE_xxx，
                    // CarWith 的分支是 cmd+1 原样写回，然后才 onConnect 继续往下。
                    int r = cmd + 1;
                    byte[] ack = new byte[16];
                    le32(ack, 0, r); le32(ack, 4, 16); le32(ack, 8, r ^ 16);
                    out.write(ack); out.flush();
                    alive = true; lastBeat = System.currentTimeMillis();
                    log.line("[PXC反连] 0x" + Integer.toHexString(cmd) + " → 已回 0x" + Integer.toHexString(r)
                            + "（反向控制通道建立）");
                    if (cmd == 0x00020000) {
                        p2cSession = true;
                        // 4.2 的结果已经证明：0x80010 发在 PXC 口上只会被车机判 AUTH_FAIL（那一族是蓝牙 SDP 用的）。
                        // CarWith 真正在“开始投屏”时发的是 ECP_P2C_APPSTATUS_FOREGROUND(0x20020)：
                        // 告诉车机“我这边已经在前台镜像了”，车机据此切到投屏页并开 RV 通道。
                        String st = "{\"displayRotation\":1,\"width\":1280,\"height\":720"
                                + ",\"enableAccessibility\":false,\"enableAOAHid\":false,\"mode\":1}";
                        sendPxc(out, 0x00020020, st.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        log.line("[PXC反连] 已发 0x20020 APPSTATUS_FOREGROUND：" + st);
                        // ECP_P2C_MIRROR_CONTROL(0x20590) 只有车机 supportFunction 带 PHONE_CONTROL_MIRROR
                        // (1073741824) 时 CarWith 才会发；本仪表 supportFunction=128 没这位，别乱发。
                        if ((carSupportFunction & 1073741824L) != 0) {
                            byte[] js = "{\"control\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            byte[] mc = new byte[16 + js.length];
                            le32(mc, 0, 0x00020590);
                            le32(mc, 4, 16 + js.length);
                            le32(mc, 8, 0x00020590 ^ (16 + js.length));
                            System.arraycopy(js, 0, mc, 16, js.length);
                            out.write(mc); out.flush();
                            log.line("[PXC反连] 已发 0x20590 MIRROR_CONTROL control=1（车机支持手机控投屏）");
                        } else {
                            log.line("[PXC反连] 跳过 0x20590（车机 supportFunction=" + carSupportFunction
                                    + " 不含 PHONE_CONTROL_MIRROR）");
                        }
                    }
                } else if (cmd == 0x00030010) {
                    // R2A_AUTH_HUID：车机用我们的公钥加密一段数据发过来，我们用私钥解开再回 cmd+1。
                    // 解不开就等于身份不被承认，会直接吃 AUTH_FAIL。
                    StringBuilder d = new StringBuilder();
                    boolean ok = rsaDecryptDump(body, d);
                    int r = cmd + 1;
                    byte[] ack = new byte[16];
                    le32(ack, 0, r); le32(ack, 4, 16); le32(ack, 8, r ^ 16);
                    out.write(ack); out.flush();
                    log.line("[PXC反连] AUTH_HUID 0x30010 " + body.length + "B → 私钥解密"
                            + (ok ? "成功 plain=" + d : "失败（密钥对不上）") + "，已回 0x30011");
                } else if ((cmd & 0xFF000000) == (int) 0x80000000L) {
                    // 0x8xxxxxxx 是车机的错误码族（0x80000010=AUTH_FAIL）。CarWith 对这类是
                    // ResponseExecute.None —— 绝不能回 cmd+1，一回头车机就会当成协议错并发命刷屏。
                    if (cmd == 0x80000010) {
                        roundAuthFails++;
                        if (++authFailCount <= 3) {
                            log.line("★★ [PXC反连] 车机 AUTH_FAIL(0x80000010) 第 " + authFailCount
                                    + " 次：它不认我们这层身份（0x10011 的 pubkey/encryptedHUID 或 token 不合规）");
                        }
                    } else {
                        log.line("[PXC反连] 车机错误码 0x" + Integer.toHexString(cmd) + " len=" + body.length + "（不回，避免刷屏）");
                    }
                } else if (cmd == 0x00040000) {
                    roundSuccess = true;
                    log.line("★★ [PXC反连] 收到 0x40000（仪表开 RV 通道了）len=" + body.length);
                } else if (cmd == 0x70000000 || cmd == 0x70000011) {
                    // 心跳/应答，不刷屏
                } else {
                    // 有几条是“要数据”的，不能回空包——回空包车机就当校验失败，接着回 AUTH_FAIL。
                    if (cmd == 0x00010450) {
                        // ECP_C2P_QUERY_TIME：车机对时。应答体照 CarWith 的 process() 拼。
                        java.util.TimeZone tz = java.util.TimeZone.getDefault();
                        long nowMs = System.currentTimeMillis();
                        long gmtMs = nowMs + tz.getOffset(nowMs);
                        String dt = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss:SSS").format(new java.util.Date(nowMs));
                        String tj = "{\"time\":" + gmtMs + ",\"currentTime\":" + nowMs
                                + ",\"currentTimeZone\":\"" + tz.getID() + "\",\"dateTime\":\"" + dt + "\"}";
                        sendPxc(out, 0x00010451, tj.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        log.line("[PXC反连] 车机对时 → 已回 0x10451 " + tj);
                    } else if (cmd == 0x000103E0) {
                        // ECP_C2P_CHECK_SN：车机要授权校验，body 带自己的 SN。
                        // 手机要回 ECP_P2C_CHECK_SN_RESULT(0x201C0)，否则它认为这台车没授权。
                        String sn = grab(body, "sn");
                        String cs = grab(body, "client_set");
                        if ("?".equals(cs)) cs = "";
                        String rj = "{\"isOk\":true,\"errCode\":0,\"errMsg\":\"\",\"id\":\"" + sn
                                + "\",\"client_set\":\"" + cs + "\"}";
                        sendPxc(out, 0x000201C0, rj.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        log.line("[PXC反连] 车机 CHECK_SN sn=" + sn + " → 已回 0x201C0 授权通过");
                        // 官方 ECP_P2C_JUMP_TO_CAR_PAGE(0x20480)：授权通过后手机要主动把车机切到“车机页”，
                        // 不点这一下仪表就干挂着不动（上一轮实车挂住了 3 分钟一帧不发）。
                        sendPxc(out, 0x00020480, "{\"page\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        log.line("[PXC反连] 已发 0x20480 JUMP_TO_CAR_PAGE page=1");
                    } else if (cmd % 2 == 1) {
                        // 车机的 ACK 都是奇数（官方 ServerCtrlExecuteThread 对奇数命令直接丢弃），
                        // 回一条 ACK 等于在协议上多插一帧，车机只会判错。这里一个字都不发。
                        log.line("[PXC反连] 收到 ACK 0x" + Integer.toHexString(cmd) + " len=" + body.length + "（不回）");
                    } else if ((cmd & 0xFF000000) != 0x60000000 && (cmd & 0xFF000000) != 0x70000000) {
                        int r = cmd + 1;
                        byte[] ack = new byte[16];
                        le32(ack, 0, r); le32(ack, 4, 16); le32(ack, 8, r ^ 16);
                        out.write(ack); out.flush();
                        log.line("[PXC反连] 未登记命令 0x" + Integer.toHexString(cmd) + " → 已回 0x"
                                + Integer.toHexString(r) + " 空应答");
                    }
                    log.line("[PXC反连] cmd=0x" + Integer.toHexString(cmd) + " len=" + body.length
                            + (body.length > 0 && body.length <= 300 ? " body=" + new String(body, java.nio.charset.StandardCharsets.UTF_8) : ""));
                }
            }
        } catch (Throwable t) {
            log.line("[PXC反连] 会话结束 " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : "：" + t.getMessage()));
        } finally {
            if (pxcRvsSock == c) pxcRvsSock = null;
            rvsSocks.remove(c);
            try { c.close(); } catch (Throwable t) { }
        }
    }

    /** 读满 n 个字节，返回实际读到的字节数（对端关闭就提前返回）。 */
    private static int readFullyN(java.io.InputStream in, byte[] buf, int n) throws java.io.IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r <= 0) break;
            got += r;
        }
        return got;
    }

    /** 从 JSON 里粗取一个字符串/数字字段，不做完整解析 */
    private static String grab(byte[] b, String key) {
        String s = new String(b, java.nio.charset.StandardCharsets.UTF_8);
        int i = s.indexOf("\"" + key + "\"");
        if (i < 0) return "?";
        int colon = s.indexOf(':', i);
        if (colon < 0) return "?";
        int j = colon + 1;
        while (j < s.length() && (s.charAt(j) == ' ' || s.charAt(j) == '\t' || s.charAt(j) == '\n')) j++;
        if (j >= s.length()) return "?";
        char q = s.charAt(j);
        if (q == '"') {
            int e = s.indexOf('"', j + 1);
            return e < 0 ? "?" : s.substring(j + 1, e);
        }
        int e = j;
        while (e < s.length() && ",}\n\r \t".indexOf(s.charAt(e)) < 0) e++;
        return s.substring(j, e);
    }

    private void acceptLoop(boolean ctrl) {
        try {
            ServerSocket ss = new ServerSocket(ctrl ? RV_CTRL_PORT : RV_DATA_PORT);
            ss.setReuseAddress(true);
            if (ctrl) ctrlSs = ss; else dataSs = ss;
            log.line("监听 :" + (ctrl ? RV_CTRL_PORT : RV_DATA_PORT) + " 已就绪（手机是服务端，等仪表连入）");
            while (running) {
                final Socket c = ss.accept();
                c.setTcpNoDelay(true);
                c.setKeepAlive(true);
                final String tag = ctrl ? "[控制口]" : "[数据口]";
                final boolean isCtrl = ctrl;
                log.line(tag + " 仪表连入 " + c.getInetAddress().getHostAddress());
                if (ctrl) { ctrlSock = c; ctrlClients++; } else { dataSock = c; dataClients++; }
                new Thread(new Runnable() { @Override public void run() {
                    if (isCtrl) ctrlSession(c, tag); else dataSession(c, tag);
                } }, "rig-sess").start();
            }
        } catch (Throwable t) {
            if (running) log.line("监听 :" + (ctrl ? RV_CTRL_PORT : RV_DATA_PORT) + " 停了 " + t);
        }
    }

    // ---------- 控制口 ----------
    private void ctrlSession(Socket c, String tag) {
        try {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            byte[] h = new byte[8];
            while (running && !c.isClosed()) {
                if (!readFully(in, h, 8)) break;
                int cmd = le16(h, 0);
                int len = le16(h, 2);
                byte[] body = new byte[len];
                if (len > 0 && !readFully(in, body, len)) break;
                log.line(tag + " cmd=0x" + Integer.toHexString(cmd) + " len=" + len);
                switch (cmd) {
                    case REQ_RV_CONFIG_CAPTURE: onConfigCapture(body, out); break;
                    case REQ_GET_VERSION: {
                        byte[] r = new byte[8];
                        le32(r, 0, 5002); le32(r, 4, 1);
                        send(out, RLY_GET_VERSION, r);
                        break;
                    }
                    case REQ_HEARTBEAT: send(out, RLY_HEARTBEAT, null); break;
                    case REQ_RV_DATA_START:
                        streaming = true;
                        send(out, RLY_RV_DATA_START, null);
                        log.line(tag + " → 已回 113，开始供帧（等数据口来拉 114）");
                        break;
                    case REQ_SCREEN_EVENT: break;
                    case REQ_EXTEND: send(out, RLY_UN_SUPPORT, null); break;
                    case REQ_EXECUTE_SHELL: send(out, RLY_UN_SUPPORT, null); break;
                    default:
                        log.line(tag + " 未支持 cmd=0x" + Integer.toHexString(cmd));
                        send(out, RLY_UN_SUPPORT, null);
                        break;
                }
            }
        } catch (Throwable t) {
            log.line(tag + " 断开 " + t.getClass().getSimpleName());
        } finally {
            ctrlClients = Math.max(0, ctrlClients - 1);
            streaming = false;
            try { c.close(); } catch (Throwable t) { }
        }
    }

    private void onConfigCapture(byte[] b, OutputStream out) {
        try {
            int w = le16(b, 0), h = le16(b, 2);
            int fps = b.length >= 8 ? le32(b, 4) : 30;
            int enc = b.length >= 12 ? le32(b, 8) : 2;
            int displayId = b.length >= 16 ? (b[15] & 0xff) : 0;
            log.line("仪表要求 " + w + "x" + h + " @" + fps + "fps encoder=" + enc + " displayId=" + displayId);
            cfgW = (w / 16) * 16; cfgH = (h / 16) * 16; cfgFps = fps;
            setSize(cfgW, cfgH);
            cfgEncoder = 2; // H264
            byte[] r = new byte[8];
            le32(r, 0, cfgEncoder);
            r[4] = (byte) (cfgW & 0xff); r[5] = (byte) ((cfgW >> 8) & 0xff);
            r[6] = (byte) (cfgH & 0xff); r[7] = (byte) ((cfgH >> 8) & 0xff);
            send(out, RLY_RV_CONFIG_CAPTURE, r);
            log.line("→ 已回 17 接受 " + cfgW + "x" + cfgH);
        } catch (Throwable t) { log.line("× 解析 ReqConfigCapture 失败 " + t); }
    }

    // ---------- 数据口（拉模式） ----------
    private void dataSession(Socket c, String tag) {
        try {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            byte[] h = new byte[8];
            while (running && !c.isClosed()) {
                if (!readFully(in, h, 8)) break;
                int cmd = le16(h, 0);
                int len = le16(h, 2);
                if (len > 0) { byte[] b = new byte[len]; if (!readFully(in, b, len)) break; }
                if (cmd != REQ_RV_DATA_NEXT) {
                    log.line(tag + " 意外 cmd=0x" + Integer.toHexString(cmd));
                    continue;
                }
                byte[] f = frames.poll(2, TimeUnit.SECONDS);
                int n = f == null ? 0 : f.length;
                byte[] pre = new byte[4];
                le32(pre, 0, n);
                out.write(pre);
                if (f != null) out.write(f);
                out.flush();
                if (f != null) {
                    framesPushed++; bytesPushed += n;
                    if (framesPushed == 1 || framesPushed % 60 == 0) {
                        log.line(tag + " 已供 " + framesPushed + " 帧 / " + (bytesPushed / 1024) + "KB"
                                + (framesPushed == 1 ? "（首帧 " + n + "B）" : ""));
                    }
                }
            }
        } catch (Throwable t) {
            log.line(tag + " 断开 " + t.getClass().getSimpleName());
        } finally {
            dataClients = Math.max(0, dataClients - 1);
            try { c.close(); } catch (Throwable t) { }
        }
    }

    /** 把 CarWith 的一帧 H264（Annex-B）放进待取队列 */
    public void pushFrame(byte[] annexB) {
        if (!running || annexB == null || annexB.length == 0) return;
        if (dataClients == 0 || !streaming) return;
        if (!frames.offer(annexB)) { frames.poll(); frames.offer(annexB); }
    }

    private void statusLoop() {
        int tick = 0;
        while (running) {
            try { Thread.sleep(5000); } catch (Throwable t) { break; }
            tick++;
            if (rvSent == 0 && tick % 2 == 0) {
                log.line("… 还没推过帧（控制口 " + ctrlClients + " 台 / RV 已推 " + rvSent + " 帧）");
            }
        }
    }

    public void stop() {
        running = false;
        streaming = false;
        try { if (ctrlSock != null) ctrlSock.close(); } catch (Throwable t) { }
        try { if (dataSock != null) dataSock.close(); } catch (Throwable t) { }
        try { if (ctrlSs != null) ctrlSs.close(); } catch (Throwable t) { }
        try { if (pxcSock != null) pxcSock.close(); } catch (Throwable t) { }
        try { if (pxcRvsSock != null) pxcRvsSock.close(); } catch (Throwable t) { }
        try { if (pxcRvsSs != null) pxcRvsSs.close(); } catch (Throwable t) { }
        try { if (dataSs != null) dataSs.close(); } catch (Throwable t) { }
        synchronized (legacySs) {
            for (ServerSocket l : legacySs) { try { l.close(); } catch (Throwable t) { } }
            legacySs.clear();
        }
        synchronized (rvsSocks) {
            for (Socket c : rvsSocks) { try { c.close(); } catch (Throwable t) { } }
            rvsSocks.clear();
        }
        rvArmed = false;
        ctrlSock = null; dataSock = null; ctrlSs = null; dataSs = null;
        frames.clear();
        if (log != null) log.line("投屏已停止，本次共推给仪表 " + rvSent + " 帧（含重发关键帧）/ " + (rvBytes / 1024) + "KB"
                + "（CarWith 原帧 " + rvCarCount + " / 关键帧 " + rvKey + " / 丢 " + rvDropped + " / 重同步 " + rvGaps + "）");
    }

    // ---------- 小工具 ----------
    private void send(OutputStream out, short cmd, byte[] body) {
        try {
            int len = body == null ? 0 : body.length;
            byte[] h = new byte[8];
            h[0] = (byte) (cmd & 0xff); h[1] = (byte) ((cmd >> 8) & 0xff);
            h[2] = (byte) (len & 0xff); h[3] = (byte) ((len >> 8) & 0xff);
            // token 固定 0（CarWith 自己发的时候也是 0）
            out.write(h);
            if (body != null) out.write(body);
            out.flush();
        } catch (Throwable t) { log.line("× 回复 cmd=0x" + Integer.toHexString(cmd) + " 失败 " + t); }
    }

    private static boolean readFully(InputStream in, byte[] b, int n) throws java.io.IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r <= 0) return false;
            got += r;
        }
        return true;
    }

    private static void le32(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }
}
