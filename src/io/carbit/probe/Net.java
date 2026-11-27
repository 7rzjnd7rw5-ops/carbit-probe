package io.carbit.probe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 纯 socket 层：端口探测、唤醒探针、反向端口监听。 */
public final class Net {

    public interface Log {
        void line(String s);
    }

    private Net() {
    }

    /** TCP 端口是否可连（true = 有人 listen）。 */
    public static boolean portOpen(String host, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * 向 10930 发一次唤醒探针。
     *
     * @return 被应答时返回应答 body（含 true），未应答返回 null。
     */
    public static String wake(String host, String identity, Log log) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, Proto.WAKE_PORT), 3000);
            s.setSoTimeout(5000);
            byte[] frame = Proto.wakeFrame(identity);
            OutputStream out = s.getOutputStream();
            out.write(frame);
            out.flush();
            log.line("  -> " + frame.length + "B  " + Proto.hex(frame));
            log.line("     body: " + Proto.probeBody(identity));

            InputStream in = s.getInputStream();
            byte[] header = new byte[Proto.WAKE_HEADER_SIZE];
            if (!readFully(in, header)) {
                log.line("  <- 无应答（对端关闭或读超时）");
                return null;
            }
            ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int cmd = buf.getInt(0);
            int totalLen = buf.getInt(4);
            int magic = buf.getInt(8);
            log.line("  <- header cmd=0x" + Integer.toHexString(cmd)
                    + " totalLen=" + totalLen
                    + " xorOK=" + ((cmd ^ totalLen) == magic)
                    + " raw=" + Proto.hex(header));
            int payloadLen = totalLen - Proto.WAKE_HEADER_SIZE;
            if (payloadLen < 0 || payloadLen > 65536) {
                log.line("  <- 长度字段异常，放弃");
                return null;
            }
            byte[] payload = new byte[payloadLen];
            if (payloadLen > 0 && !readFully(in, payload)) {
                log.line("  <- 应答体读取失败");
                return null;
            }
            if (payloadLen > 0) {
                log.line("  <- " + payloadLen + "B  " + Proto.text(payload));
            }
            String ack = Proto.parseWakeAck(header, payload);
            return (ack != null && ack.contains("true")) ? ack : null;
        } catch (Exception e) {
            log.line("  !! " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n <= 0) return false;
            read += n;
        }
        return true;
    }

    /** 小端读 32 位。 */
    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    /** 监听一个反向端口，把每个连入连接的全部字节记录下来。 */
    public static final class Listener {
        private final int port;
        private final Log log;
        private ServerSocket server;
        private final List<Socket> clients = new ArrayList<Socket>();
        private volatile boolean running;

        Listener(int port, Log log) {
            this.port = port;
            this.log = log;
        }

        public int port() { return port; }

        public void start() {
            running = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        ServerSocket ss = new ServerSocket(port);
                        synchronized (Listener.this) { server = ss; }
                        log.line("监听 :" + port + " 已就绪，等仪表连入…");
                        while (running) {
                            Socket c = ss.accept();
                            synchronized (Listener.this) { clients.add(c); }
                            log.line("[" + port + "] 仪表连入 " + c.getInetAddress().getHostAddress()
                                    + " (本端 " + c.getLocalPort() + ")");
                            startReader(c);
                        }
                    } catch (Exception e) {
                        if (running) log.line("监听 :" + port + " 失败 " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
                    }
                }
            }, "listen-" + port).start();
        }

        private void startReader(final Socket c) {
            new Thread(new Runnable() {
                @Override public void run() {
                    final String tag = "[" + port + "]";
                    try {
                        c.setTcpNoDelay(true);
                        InputStream in = c.getInputStream();
                        OutputStream out = c.getOutputStream();
                        byte[] buf = new byte[256 * 1024];
                        int len = 0;
                        boolean handshaked = false;
                        byte[] chunk = new byte[16384];
                        int n;
                        while ((n = in.read(chunk)) > 0) {
                            if (len + n > buf.length) {
                                log.line(tag + " 缓冲溢出，丢弃累积数据");
                                len = 0;
                            }
                            System.arraycopy(chunk, 0, buf, len, n);
                            len += n;

                            int consumed;
                            while (len >= 16) {
                                consumed = handle(buf, len, out, tag, handshaked);
                                if (consumed == 0) break;
                                if (consumed == -1) {
                                    handshaked = true;
                                    consumed = 16;
                                }
                                len -= consumed;
                                System.arraycopy(buf, consumed, buf, 0, len);
                            }
                        }
                        log.line(tag + " 连接关闭");
                    } catch (Exception e) {
                        log.line(tag + " 读失败 " + e.getMessage());
                    } finally {
                        try { c.close(); } catch (IOException ignored) { }
                        synchronized (Listener.this) { clients.remove(c); }
                    }
                }
            }, "read-" + port).start();
        }

        public interface FrameHook { void onFrame(int cmd, byte[] payload, OutputStream out); }

        private FrameHook hook;

        public void setHook(FrameHook h) { this.hook = h; }

        /** 把数据广播给当前所有连入的好车子（仪表连的是我们，所以是我们写出去） */
        public int broadcast(byte[] data) {
            int n = 0;
            synchronized (this) {
                for (java.net.Socket c : clients) {
                    try { c.getOutputStream().write(data); c.getOutputStream().flush(); n++; }
                    catch (Exception e) { }
                }
            }
            return n;
        }

        public int clientCount() {
            synchronized (this) { return clients.size(); }
        }

        /**
         * 处理缓冲区头部的一帧。
         *
         * @return >0 表示消费掉的字节数；0 表示数据不够，等下一次读；-1 表示刚完成握手应答（消费 16B）
         */
        private int handle(byte[] b, int len, OutputStream out, String tag, boolean handshaked) {
            if (len < 16) return 0;

            int cmd = le32(b, 0);
            int total = le32(b, 4);
            int xor = le32(b, 8);

            // 1) EC 帧（16 字节头 + 体）
            if (total >= 16 && total <= 512 * 1024 && (cmd ^ total) == xor) {
                if (len < total) return 0;
                if (!handshaked && cmd == Proto.CMD_CONNECT) {
                    try {
                        out.write(Proto.ecHeader(Proto.CMD_CONNECT_ACK, 0));
                        out.flush();
                        log.line(tag + " == 收到 CONNECT(0x10000)，已回 CONNECT_ACK(0x10001)");
                    } catch (Exception e) {
                        log.line(tag + " 回握手失败: " + e.getMessage());
                    }
                    return -1;
                }
                int plen = total - 16;
                byte[] payload = new byte[plen];
                System.arraycopy(b, 16, payload, 0, plen);
                log.line(tag + " EC cmd=0x" + Integer.toHexString(cmd) + "(" + Proto.cmdName(cmd)
                        + ") body=" + plen + "B");
                if (plen > 0) {
                    log.line(tag + "    hex " + Proto.hex(payload));
                    log.line(tag + "    txt " + Proto.text(payload));
                }
                if (hook != null) {
                    try { hook.onFrame(cmd, payload, out); } catch (Throwable t) { }
                }
                return total;
            }

            // 2) PXC 串行帧 0x24 | CMD | LEN | payload | XOR | 0x0A
            if ((b[0] & 0xFF) == 0x24 && len >= 5) {
                int declared = b[2] & 0xFF;
                int payloadLen = declared - 4;
                if (payloadLen >= 0 && payloadLen <= 200) {
                    int endIndex = 4 + payloadLen;      // 0x0A 的位置（XOR 在 endIndex-1）
                    int need = endIndex + 1;
                    if (len >= need) {
                        if ((b[endIndex] & 0xFF) == Proto.PXC_END) {
                            byte checksum = 0;
                            for (int k = 0; k < endIndex; k++) checksum ^= b[k];
                            if (checksum == b[endIndex - 1]) {
                                byte[] payload = new byte[payloadLen];
                                System.arraycopy(b, 3, payload, 0, payloadLen);
                                log.line(tag + " PXC cmd=0x" + String.format("%02X", b[1])
                                        + " body=" + payloadLen + "B " + Proto.text(payload));
                                return need;
                            }
                        }
                    } else {
                        return 0;
                    }
                }
            }

            // 3) 认不出来：整段原样按十六进制打一行（以前逐字节丢，一次握手被拆成 14 行、还看不全），
            //    然后把这一整段消费掉。仪表在这类口上只会主动发一次，日志必须留住完整原始字节。
            log.line(tag + " 原始 " + len + "B " + Proto.hex(java.util.Arrays.copyOf(b, len)));
            return len;
        }

        public void stop() {
            running = false;
            try { if (server != null) server.close(); } catch (IOException ignored) { }
            synchronized (this) {
                for (Socket c : clients) {
                    try { c.close(); } catch (IOException ignored) { }
                }
                clients.clear();
            }
        }
    }

    public static Listener listen(int port, Log log) {
        return new Listener(port, log);
    }

    /** PXC 串行帧：0x24 | CMD | LEN | payload | XOR | 0x0A（LEN = payload + 4）。 */
    public static final class PxcFrame {
        public final byte command;
        public final byte[] payload;
        PxcFrame(byte command, byte[] payload) { this.command = command; this.payload = payload; }
    }

    public static final class Pxc {
        public static List<PxcFrame> parse(byte[] data) {
            List<PxcFrame> out = new ArrayList<PxcFrame>();
            int i = 0;
            while (i < data.length - 4) {
                if (data[i] != Proto.PXC_START) { i++; continue; }
                int declared = data[i + 2] & 0xFF;
                int payloadLen = declared - 4;
                if (payloadLen < 0 || i + 5 + payloadLen > data.length) { i++; continue; }
                int endIndex = i + 4 + payloadLen;
                if (data[endIndex] != Proto.PXC_END) { i++; continue; }
                byte checksum = 0;
                for (int k = i; k < endIndex - 1; k++) checksum ^= data[k];
                if (checksum != data[endIndex - 1]) { i++; continue; }
                byte[] payload = new byte[payloadLen];
                System.arraycopy(data, i + 3, payload, 0, payloadLen);
                out.add(new PxcFrame(data[i + 1], payload));
                i = endIndex + 1;
            }
            return out;
        }
    }

    /** 可扫的 /24 前缀，形如 "192.168.232"（排除蜂窝、回环、VPN）。 */
    public static List<String> prefixes24() {
        List<String> out = new ArrayList<String>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                if (!nic.isUp()) continue;
                String name = nic.getName();
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")
                        || name.startsWith("lo") || name.startsWith("dummy")) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    int dot = ip.lastIndexOf('.');
                    if (dot <= 0) continue;
                    String prefix = ip.substring(0, dot);
                    if (!out.contains(prefix)) out.add(prefix);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 无线/直连接口名（补 IPv6 link-local 的 scope 用）。 */
    public static String wlanName() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                if (!nic.isUp()) continue;
                String name = nic.getName();
                if (name.startsWith("wlan") || name.startsWith("p2p") || name.startsWith("radio")) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 并发扫 prefix24 网段某端口，返回开放的主机 IP。 */
    public static List<String> scan24(String prefix, int port, int timeoutMs, final Log log) {
        final List<String> hits = java.util.Collections.synchronizedList(new ArrayList<String>());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(48);
        for (int i = 1; i <= 254; i++) {
            final String host = prefix + "." + i;
            pool.execute(new Runnable() {
                @Override public void run() {
                    if (portOpen(host, port, timeoutMs)) {
                        hits.add(host);
                        if (log != null) log.line("  发现 " + host + ":" + port + " 开放");
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(90, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        return hits;
    }

    /** 本机所有非回环 IPv4 地址，形如 "wlan0 192.168.49.10"。 */
    /**
     * 试探一个 TCP 端口：先被动收一句，再发 EasyConn 身份帧、再发 CONNECT。
     * 用来验证 CarWith 的 10113 服务端是不是 EasyConn/ICCOA 那一套。
     */
    public static void tap(String host, int port, Log log) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 2000);
            s.setSoTimeout(2500);
            log.line("  ✔ 连上 " + host + ":" + port);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] b = new byte[8192];

            int n = readQuiet(in, b, log, "对端主动数据");
            if (n > 0) log.line("  <- 主动 " + n + "B  " + Proto.hex(b, 0, n));

            byte[] id = Proto.wakeFrame(Proto.IDENTITIES[0]);
            out.write(id); out.flush();
            log.line("  -> 身份帧 " + id.length + "B  " + Proto.hex(id));
            n = readQuiet(in, b, log, "身份帧应答");
            if (n > 0) log.line("  <- " + n + "B  " + Proto.hex(b, 0, n));

            byte[] con = Proto.ecHeader(Proto.CMD_CONNECT, 0);
            out.write(con); out.flush();
            log.line("  -> CONNECT(0x10000)  " + Proto.hex(con));
            n = readQuiet(in, b, log, "CONNECT 应答");
            if (n > 0) log.line("  <- " + n + "B  " + Proto.hex(b, 0, n));
            else log.line("  · 全程无有效回包");
        } catch (java.net.SocketTimeoutException te) {
            log.line("  ✗ " + host + ":" + port + "  连接超时（地址不可达，多半 p2p 接口没地址）");
        } catch (Exception e) {
            log.line("  ✗ " + host + ":" + port + "  " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static int readQuiet(InputStream in, byte[] b, Log log, String what) {
        try {
            return in.read(b);
        } catch (java.net.SocketTimeoutException te) {
            log.line("  · " + what + "：超时");
            return -1;
        } catch (Exception e) {
            log.line("  · " + what + "：断开 " + e.getMessage());
            return -1;
        }
    }

    public static List<String> localIpv4() {
        List<String> out = new ArrayList<String>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nics =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                java.net.NetworkInterface nic = nics.nextElement();
                if (!nic.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        out.add(nic.getName() + " " + a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
