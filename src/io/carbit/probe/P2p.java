package io.carbit.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Looper;

/**
 * 让本机自己建一个 Wi-Fi Direct 组（默认成为 GO）。
 *
 * 目的：CarWith 的 adb 模式把服务端 socket 绑在 p2p 接口地址上（192.168.49.x:10113），
 * 组一旦解散，那个地址就没了，本机连它必超时。先把组建起来，地址才重新可用，
 * 这样才能在同一台机器上用 loopback 扮演“车机”去连 CarWith 的服务端。
 */
public class P2p {

    public interface Cb {
        void done(boolean ok, String detail);
    }

    public static void createGroup(final Context ctx, final Cb cb) {
        final WifiP2pManager mgr =
                (WifiP2pManager) ctx.getApplicationContext().getSystemService(Context.WIFI_P2P_SERVICE);
        if (mgr == null) {
            cb.done(false, "取不到 WifiP2pManager（系统可能没开 p2p）");
            return;
        }
        final WifiP2pManager.Channel ch =
                mgr.initialize(ctx.getApplicationContext(), Looper.getMainLooper(),
                        new WifiP2pManager.ChannelListener() {
                            @Override public void onChannelDisconnected() { }
                        });

        final BroadcastReceiver rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(i.getAction())) return;
                WifiP2pInfo info = i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                if (info == null || !info.groupFormed) return;
                try { c.unregisterReceiver(this); } catch (Throwable ignored) { }
                String go = info.groupOwnerAddress == null
                        ? "null" : info.groupOwnerAddress.getHostAddress();
                cb.done(true, (info.isGroupOwner ? "本机=GO" : "本机=CLIENT") + "，组主地址 " + go);
            }
        };
        try {
            ctx.getApplicationContext().registerReceiver(rx,
                    new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION));
        } catch (Throwable t) {
            cb.done(false, "注册 p2p 广播失败: " + t.getMessage());
            return;
        }

        mgr.createGroup(ch, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { }
            @Override public void onFailure(int reason) {
                try { ctx.getApplicationContext().unregisterReceiver(rx); } catch (Throwable ignored) { }
                cb.done(false, "createGroup 失败 reason=" + reason + "(" + reasonText(reason) + ")");
            }
        });
    }


    // ─────────── 直连仪表：走 Wi-Fi Direct，不占手机 Wi-Fi，不影响流量 ───────────

    /** 二维码里带出来的目标设备名/MAC（官方 QrResult 里就是 name / mac 两个字段） */
    public static volatile String wantName = null;
    public static volatile String wantMac = null;

    public interface Cb2 {
        void log(String s);
        void done(boolean ok, String msg);
    }

    /** 扫 P2P 设备 → 匹配仪表 → connect(groupOwnerIntent=-1, WPS=PBC) → 取组主地址。 */
    /** 猜组主地址：本机 p2p0 是 192.168.13.20 时组主就是同网段的 .1；本机自己就是 .1 时直接用自己。 */
    static String goIpGuess() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (ni == null || !ni.getName().startsWith("p2p")) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address) {
                        String ip = a.getHostAddress();
                        String[] q = ip.split("\\.");
                        if (q.length == 4) {
                            if (q[3].equals("1")) return ip;
                            return q[0] + "." + q[1] + "." + q[2] + ".1";
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return "192.168.13.1";
    }

    private static volatile boolean connectedOnce;

    public static void directConnect(final Context ctx, final Cb2 cb) {
        connectedOnce = false;
        // 一个回调只能生效一次：复用已有组和"连接成功"两条路径可能同时命中，重复 done(true) 会触发两遍开流
        final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        final Cb2 cbo = new Cb2() {
            @Override public void log(String msg) { cb.log(msg); }
            @Override public void done(boolean ok, String msg) { if (fired.compareAndSet(false, true)) cb.done(ok, msg); }
        };
        final android.content.Context app = ctx.getApplicationContext();
        final WifiP2pManager mgr = (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE);
        if (mgr == null) { cbo.done(false, "系统没有 WifiP2pManager"); return; }
        final WifiP2pManager.Channel ch = mgr.initialize(app, Looper.getMainLooper(),
                new WifiP2pManager.ChannelListener() {
                    @Override public void onChannelDisconnected() { cbo.log("p2p 通道断开"); }
                });

        // 已经有现成组：直接复用，避免 connect reason=2（wifi 正忙）白跑一趟
        try {
            mgr.requestGroupInfo(ch, new WifiP2pManager.GroupInfoListener() {
                @Override public void onGroupInfoAvailable(android.net.wifi.p2p.WifiP2pGroup grp) {
                    if (grp != null && grp.getNetworkName() != null) {
                        // 组主地址不能写死：本机当 GO 时组主就是本机（192.168.49.1 那侧），
                        // 只有 CLIENT 时连的才是对方。这里如实回，别把 192.168.13.1 当成永远对。
                        String go = grp.isGroupOwner() ? "本机(GO)" : "组主(对端)";
                        if (!connectedOnce) {
                            connectedOnce = true;
                            cbo.log("检测到已有 P2P 组：" + grp.getNetworkName()
                                    + (grp.isGroupOwner() ? "（本机=GO）" : "（本机=CLIENT）") + "，直接复用，不重新连");
                            // 必须带上"组主地址"：MainActivity 从这句话里取 IP 放进"投给"输入框并置 p2pReady。
                            // 之前只回"本机=CLIENT(已有组)，组主(对端)"，取不到 IP → p2pReady 一直 false
                            // → doStartAll 又去直连 → 死循环，永远开不了流（13:12 那次就是这么卡住的）。
                            cbo.done(true, (grp.isGroupOwner() ? "本机=GO(已有组)" : "本机=CLIENT(已有组)")
                                    + "，组主地址 " + goIpGuess());
                        }
                    }
                }
            });
        } catch (Throwable ignored) { }

        final BroadcastReceiver rx = new BroadcastReceiver() {
            private boolean connecting = false;
            private boolean done = false;

            @Override public void onReceive(Context c, final Intent i) {
                try { handle(c, i); } catch (Throwable t) { cbo.log("p2p 回调异常: " + t); }
            }

            private void handle(Context c, Intent i) {
                String a = i.getAction();
                if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a)) {
                    mgr.requestPeers(ch, new WifiP2pManager.PeerListListener() {
                        @Override public void onPeersAvailable(WifiP2pDeviceList peers) {
                            if (done || peers == null) return;
                            WifiP2pDevice pick = null;
                            String names = "";
                            for (WifiP2pDevice d : peers.getDeviceList()) {
                                if (d.deviceName == null || d.deviceName.length() == 0) continue;
                                names = names + d.deviceName + "(" + d.deviceAddress + ") ";
                                if (wantMac != null && wantMac.equalsIgnoreCase(d.deviceAddress)) { pick = d; break; }
                                if (wantName != null && wantName.equalsIgnoreCase(d.deviceName)) { pick = d; break; }
                                String up = d.deviceName.toUpperCase();
                                if (up.contains("VOGE") || up.contains("DS625") || up.contains("EASYCONN")) pick = d;
                            }
                            if (pick == null && peers.getDeviceList().size() == 1) {
                                pick = peers.getDeviceList().iterator().next();
                            }
                            cbo.log("P2P 设备: " + (names.length() == 0 ? "（空）" : names));
                            if (pick == null) { cbo.log("没找到像仪表的目标，继续扫…"); return; }
                            if (connecting) return;
                            connecting = true;
                            cbo.log("匹配到 " + pick.deviceName + " / " + pick.deviceAddress + "，正在连接（WPS=PBC，GO 意图=-1）");
                            WifiP2pConfig cfg = new WifiP2pConfig();
                            cfg.deviceAddress = pick.deviceAddress;
                            cfg.groupOwnerIntent = -1;
                            cfg.wps.setup = 0;   // PBC
                            mgr.connect(ch, cfg, new WifiP2pManager.ActionListener() {
                                @Override public void onSuccess() { cbo.log("connect 已发出，等组网回调…"); }
                                @Override public void onFailure(int reason) {
                                    connecting = false;
                                    cbo.log("connect 失败 reason=" + reason + "(" + reasonText(reason) + ")");
                                }
                            });
                        }
                    });
                    return;
                }
                if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                    WifiP2pInfo info = i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                    if (info == null || !info.groupFormed || done) return;
                    done = true;
                    try { c.unregisterReceiver(this); } catch (Throwable ignored) { }
                    String go = info.groupOwnerAddress == null ? "null" : info.groupOwnerAddress.getHostAddress();
                    cbo.done(true, (info.isGroupOwner ? "本机=GO" : "本机=CLIENT") + "，组主地址 " + go);
                }
            }
        };

        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        try { app.registerReceiver(rx, f); } catch (Throwable t) { cbo.done(false, "注册广播失败: " + t); return; }

        mgr.discoverPeers(ch, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { cbo.log("开始搜索 P2P 设备（约 10 秒）…"); }
            @Override public void onFailure(int reason) {
                if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                    try { app.unregisterReceiver(rx); } catch (Throwable ignored) { }
                    cbo.done(false, "这台手机不支持 Wi-Fi Direct");
                } else {
                    cbo.log("discoverPeers 失败 reason=" + reason + "(" + reasonText(reason) + ")，2 秒后重试");
                    new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() { mgr.discoverPeers(ch, null); }
                    }, 2000);
                }
            }
        });
    }

    /** 只查"此刻有没有现成的 P2P 组"，不建组、不连、不改任何状态：给启动自检轮询用。 */
    public interface CbGroup { void done(boolean ok, String detail); }

    public static void queryGroup(final Context ctx, final CbGroup cb) {
        final android.content.Context app = ctx.getApplicationContext();
        final WifiP2pManager mgr = (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE);
        if (mgr == null) { cb.done(false, "系统没有 WifiP2pManager"); return; }
        final WifiP2pManager.Channel ch = mgr.initialize(app, Looper.getMainLooper(), null);
        final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        final android.os.Handler h = new android.os.Handler(Looper.getMainLooper());
        try {
            mgr.requestGroupInfo(ch, new WifiP2pManager.GroupInfoListener() {
                @Override public void onGroupInfoAvailable(android.net.wifi.p2p.WifiP2pGroup grp) {
                    if (!fired.compareAndSet(false, true)) return;
                    if (grp != null && grp.getNetworkName() != null) {
                        cb.done(true, "组 " + grp.getNetworkName()
                                + (grp.isGroupOwner() ? "（本机=GO）" : "（本机=CLIENT）")
                                + "，组主地址 " + goIpGuess());
                    } else {
                        cb.done(false, "没有现成的组");
                    }
                }
            });
        } catch (Throwable t) { cb.done(false, "查询异常 " + t); return; }
        // 有些机型 requestGroupInfo 会不回：3 秒兜底当"没有"，免得轮询卡死
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (fired.compareAndSet(false, true)) cb.done(false, "查询超时");
            }
        }, 3000);
    }

    static String reasonText(int r) {
        switch (r) {
            case WifiP2pManager.P2P_UNSUPPORTED: return "设备不支持 p2p";
            case WifiP2pManager.BUSY: return "wifi 正忙";
            case WifiP2pManager.ERROR: return "内部错误";
            default: return "未知";
        }
    }
}
