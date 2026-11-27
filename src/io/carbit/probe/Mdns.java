package io.carbit.probe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 手写 mDNS（DNS-SD）发现器。
 *
 * 依据无极机车 1.3.4 反编译结果：官方 SDK 用 javax.jmdns 在每张网卡上
 * addServiceListener("_EasyConn._tcp.local.")，仪表作为 responder 广播 TXT：
 *   packagename / ec_name / huid / channel / flavor
 * 拿到这些字段就不必靠猜身份了 —— flavor 是仪表认的白名单编号。
 *
 * 只发标准查询、只解析应答，不做任何写入。
 */
public final class Mdns {

    /** 与官方 SDK 完全一致。 */
    public static final String SERVICE = "_EasyConn._tcp.local.";

    public static final int PORT = 5353;

    private static final int TYPE_A = 1;
    private static final int TYPE_PTR = 12;
    private static final int TYPE_TXT = 16;
    private static final int TYPE_AAAA = 28;
    private static final int TYPE_SRV = 33;

    public interface Log {
        void line(String s);
    }

    /** 一条解析出来的服务实例。 */
    public static final class Entry {
        public String instance;
        public String hostName;
        public String ip;
        public int port;
        public final Map<String, String> txt = new LinkedHashMap<String, String>();
        public String server;          // 应答来源 IP

        public String get(String key) {
            String v = txt.get(key.toLowerCase(Locale.US));
            return v == null ? "" : v;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("实例 ").append(instance == null ? "?" : instance).append('\n');
            sb.append("  地址 ").append(ip == null ? "?" : ip).append("  :").append(port).append('\n');
            if (hostName != null) sb.append("  host ").append(hostName).append('\n');
            if (!txt.isEmpty()) {
                for (Map.Entry<String, String> e : txt.entrySet()) {
                    sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private Mdns() {
    }

    /**
     * 查询 _EasyConn._tcp.local.，返回所有应答实例。
     *
     * @param unicastHosts 额外单播过去的地址（P2P 网里多播常被丢，单播给网关最有效）
     */
    public static List<Entry> discover(List<String> unicastHosts, Log log, long timeoutMs) {
        List<Entry> result = new ArrayList<Entry>();
        MulticastSocket ms = null;
        DatagramSocket us = null;
        try {
            byte[] query = buildQuery(SERVICE, TYPE_PTR);

            // 多播 socket：必须绑 5353 才能收到应答
            try {
                MulticastSocket m = new MulticastSocket(null);
                m.setReuseAddress(true);
                m.bind(new InetSocketAddress(PORT));
                m.setTimeToLive(255);
                m.setSoTimeout(400);
                ms = m;
            } catch (Throwable t) {
                if (log != null) log.line("多播绑定 5353 失败: " + t);
            }

            // 普通 UDP socket：只用来发单播查询
            try {
                DatagramSocket u = new DatagramSocket();
                u.setSoTimeout(50);
                us = u;
            } catch (Throwable t) {
                if (log != null) log.line("UDP socket 创建失败: " + t);
            }

            // 发查询：多播一次 + 每个单播目标一次
            if (ms != null) {
                try {
                    InetAddress group = InetAddress.getByName("224.0.0.251");
                    ms.send(new DatagramPacket(query, query.length, group, PORT));
                    if (log != null) log.line("已发多播查询 224.0.0.251:5353  " + SERVICE);
                } catch (Throwable t) {
                    if (log != null) log.line("多播发送失败: " + t);
                }
            }
            if (us != null && unicastHosts != null) {
                for (String h : unicastHosts) {
                    if (h == null || h.isEmpty()) continue;
                    try {
                        InetAddress addr = InetAddress.getByName(h);
                        us.send(new DatagramPacket(query, query.length, addr, PORT));
                        if (log != null) log.line("已发单播查询 " + h + ":5353");
                    } catch (Throwable t) {
                        if (log != null) log.line("单播 " + h + " 失败: " + t);
                    }
                }
            }
            if (ms == null && us == null) return result;
            if (ms == null) return result;

            long until = System.currentTimeMillis() + timeoutMs;
            Set<String> seen = new HashSet<String>();
            while (System.currentTimeMillis() < until) {
                byte[] buf = new byte[4096];
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                try {
                    ms.receive(pkt);
                } catch (SocketTimeoutException te) {
                    continue;
                } catch (IOException io) {
                    continue;
                }
                String src = pkt.getAddress() == null ? "?" : pkt.getAddress().getHostAddress();
                List<Entry> parsed;
                try {
                    parsed = parse(pkt.getData(), pkt.getLength(), src);
                } catch (Throwable t) {
                    if (log != null) log.line("解析 " + src + " 的应答失败: " + t);
                    continue;
                }
                for (Entry e : parsed) {
                    String key = (e.ip == null ? src : e.ip) + "|" + e.port + "|" + e.instance;
                    if (!seen.add(key)) continue;
                    result.add(e);
                    if (log != null) {
                        log.line("← mDNS 应答来自 " + src);
                        log.line(e.summary());
                    }
                }
            }
            if (result.isEmpty() && log != null) {
                log.line("mDNS 超时，没收到任何 _EasyConn 应答（仪表可能没在广播，或链路不通）");
            }
        } catch (Throwable t) {
            if (log != null) log.line("mDNS 异常: " + t);
        } finally {
            if (ms != null) ms.close();
            if (us != null) us.close();
        }
        return result;
    }

    // ---------------- DNS 组包 ----------------

    private static byte[] buildQuery(String name, int qtype) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // header: id=0 flags=0 qd=1 an=0 ns=0 ar=0
        out.write(0); out.write(0);
        out.write(0); out.write(0);
        out.write(0); out.write(1);
        out.write(0); out.write(0);
        out.write(0); out.write(0);
        out.write(0); out.write(0);
        // question
        String n = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        for (String part : n.split("\\.")) {
            out.write(part.length());
            for (int i = 0; i < part.length(); i++) out.write(part.charAt(i));
        }
        out.write(0);
        out.write(qtype >> 8); out.write(qtype & 0xFF);
        // class IN，最高位 0（多播；QU 位不设，让 responder 用多播回）
        out.write(0x00); out.write(0x01);
        return out.toByteArray();
    }

    // ---------------- DNS 解包 ----------------

    private static List<Entry> parse(byte[] d, int len, String src) {
        List<Entry> out = new ArrayList<Entry>();
        int qd = readU16(d, 4);
        int an = readU16(d, 6);
        int ns = readU16(d, 8);
        int ar = readU16(d, 10);

        // 所有记录汇总起来再拼实例
        Map<String, String[]> srv = new HashMap<String, String[]>();   // instance -> {port, target}
        Map<String, String> ptr = new HashMap<String, String>();       // service -> instance
        Map<String, Map<String, String>> txt = new HashMap<String, Map<String, String>>();
        Map<String, String> addrs = new HashMap<String, String>();     // hostName -> ip
        List<String> ptrTargets = new ArrayList<String>();

        int off = 12;
        for (int i = 0; i < qd; i++) {
            Name n = readName(d, off, len);
            off = n.next;
            off += 4;
        }
        int total = an + ns + ar;
        for (int i = 0; i < total && off < len; i++) {
            Name n = readName(d, off, len);
            off = n.next;
            if (off + 10 > len) break;
            int type = readU16(d, off);
            int cls = readU16(d, off + 2);
            long ttl = readU32(d, off + 4);
            int rdlen = readU16(d, off + 8);
            off += 10;
            int rdStart = off;
            if (off + rdlen > len) break;

            String owner = lower(n.name);
            if (type == TYPE_PTR) {
                Name target = readName(d, rdStart, len);
                ptr.put(owner, target.name);
                ptrTargets.add(target.name);
            } else if (type == TYPE_SRV) {
                int port = readU16(d, rdStart + 4);
                Name target = readName(d, rdStart + 6, len);
                srv.put(owner, new String[]{String.valueOf(port), target.name});
            } else if (type == TYPE_TXT) {
                txt.put(owner, parseTxt(d, rdStart, rdlen));
            } else if (type == TYPE_A) {
                if (rdlen >= 4) {
                    addrs.put(owner, (d[rdStart] & 0xFF) + "." + (d[rdStart + 1] & 0xFF)
                            + "." + (d[rdStart + 2] & 0xFF) + "." + (d[rdStart + 3] & 0xFF));
                }
            } else if (type == TYPE_AAAA) {
                if (rdlen >= 16) {
                    try {
                        byte[] raw = new byte[16];
                        System.arraycopy(d, rdStart, raw, 0, 16);
                        addrs.put(owner, InetAddress.getByAddress(raw).getHostAddress());
                    } catch (Throwable ignored) {
                    }
                }
            }
            off = rdStart + rdlen;
        }

        // 拼接
        for (String target : ptrTargets) {
            String key = lower(target);
            Entry e = new Entry();
            e.instance = target;
            e.server = src;
            String[] s = srv.get(key);
            if (s != null) {
                try { e.port = Integer.parseInt(s[0]); } catch (Throwable ignored) { }
                e.hostName = s[1];
                String ip = addrs.get(lower(s[1]));
                if (ip != null) e.ip = ip;
            }
            Map<String, String> t = txt.get(key);
            if (t != null) e.txt.putAll(t);
            if (e.ip == null) e.ip = src;
            out.add(e);
        }
        return out;
    }

    private static Map<String, String> parseTxt(byte[] d, int start, int rdlen) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        int i = start;
        int end = start + rdlen;
        while (i < end) {
            int l = d[i] & 0xFF;
            i++;
            if (l == 0 || i + l > end) break;
            String s = "";
            for (int k = 0; k < l; k++) s += (char) (d[i + k] & 0xFF);
            i += l;
            int eq = s.indexOf('=');
            if (eq > 0) map.put(s.substring(0, eq).toLowerCase(Locale.US), s.substring(eq + 1));
            else map.put(s.toLowerCase(Locale.US), "");
        }
        return map;
    }

    private static final class Name {
        String name;
        int next;
    }

    /** 读 DNS 名字，支持 0xC0 压缩指针。 */
    private static Name readName(byte[] d, int off, int len) {
        Name out = new Name();
        StringBuilder sb = new StringBuilder();
        int p = off;
        int guard = 0;
        boolean jumped = false;
        while (p < len && guard++ < 64) {
            int c = d[p] & 0xFF;
            if (c == 0) {
                p++;
                if (!jumped) out.next = p;
                break;
            }
            if ((c & 0xC0) == 0xC0) {
                int ptr = ((c & 0x3F) << 8) | (d[p + 1] & 0xFF);
                if (!jumped) out.next = p + 2;
                jumped = true;
                p = ptr;
                continue;
            }
            p++;
            if (p + c > len) break;
            for (int i = 0; i < c; i++) sb.append((char) (d[p + i] & 0xFF));
            p += c;
            if (p < len && (d[p] & 0xFF) != 0) sb.append('.');
        }
        if (!jumped && out.next == 0) out.next = p;
        out.name = sb.toString();
        return out;
    }

    private static int readU16(byte[] d, int off) {
        if (off + 2 > d.length) return 0;
        return ((d[off] & 0xFF) << 8) | (d[off + 1] & 0xFF);
    }

    private static long readU32(byte[] d, int off) {
        if (off + 4 > d.length) return 0;
        return ((long) (d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    /** 从 mDNS 结果里挑一个可用 IPv4 目标。 */
    public static String pickTarget(List<Entry> list) {
        for (Entry e : list) {
            if (e.ip != null && isIpv4(e.ip)) return e.ip;
        }
        for (Entry e : list) {
            if (e.ip != null) return e.ip;
        }
        return null;
    }

    private static boolean isIpv4(String ip) {
        return ip != null && ip.indexOf(':') < 0 && ip.indexOf('.') > 0;
    }
}
