package io.carbit.probe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.view.accessibility.AccessibilityEvent;

/**
 * 系统级触摸注入。
 *
 * 为什么需要它：CarLife 的触控通道只能把点击送进 CarWith 自己的窗口，
 * 手机弹出的系统输入法（IME）是另一个应用的独立窗口，那条通道打不到它 ——
 * 所以"投屏画面上点输入框能弹出键盘，但点键盘上的字母没反应"。
 * AccessibilityService.dispatchGesture 是系统级注入，能落在当前最上层窗口（含 IME）。
 *
 * 用户在「设置 → 无障碍 → 极享」里打开后，本类常驻，MainActivity 直接把
 * 投屏画面坐标换算成屏幕坐标交给它。
 */
public class TouchBridgeService extends AccessibilityService {

    private static volatile TouchBridgeService inst;
    private Path path;
    private long downAt;
    private float lastX, lastY;

    public static boolean ready() {
        return inst != null && Build.VERSION.SDK_INT >= 24;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        try {
            Net.Log l = Rig.logSink();
            boolean readable = false;
            int winCount = -1;
            try {
                android.accessibilityservice.AccessibilityServiceInfo si = getServiceInfo();
                readable = si != null && (si.flags
                        & android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0;
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
                winCount = ws == null ? -1 : ws.size();
            } catch (Throwable t) { }
            if (l != null) l.line("✓ 触摸注入已就绪（无障碍已启用 / 窗口内容=" + (readable ? "可读" : "不可读，需要关掉再开一次")
                    + " / 可见窗口=" + winCount + "）");
        } catch (Throwable ignored) { }
    }

    @Override public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    public static void down(float x, float y) {
        TouchBridgeService s = inst;
        if (s == null) return;
        s.path = new Path();
        s.path.moveTo(x, y);
        s.downAt = System.currentTimeMillis();
        s.lastX = x; s.lastY = y;
    }

    public static void move(float x, float y) {
        TouchBridgeService s = inst;
        if (s == null || s.path == null) return;
        s.path.lineTo(x, y);
        s.lastX = x; s.lastY = y;
    }

    public static void up(final float x, final float y) {
        TouchBridgeService s = inst;
        if (s == null || s.path == null) return;
        Path p = s.path;
        s.path = null;
        if (Math.abs(x - s.lastX) > 1 || Math.abs(y - s.lastY) > 1) p.lineTo(x, y);
        final boolean[] ok = {false};
        long dur = System.currentTimeMillis() - s.downAt;
        if (dur < 100) dur = 100;
        if (dur > 800) dur = 800;
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
        boolean ret = false;
        String err = null;
        try {
            ret = s.dispatchGesture(b.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { ok[0] = true; }
                @Override public void onCancelled(GestureDescription g) { ok[0] = false; }
            }, null);
        } catch (Throwable t) { ok[0] = false; err = t.toString(); }
        lastDispatchReturn = ret;
        lastDispatchError = err;
        if (ret && !ok[0]) lastCancelMs = System.currentTimeMillis();
        lastOk = ok[0];
        if (!ok[0]) { Net.Log l = Rig.logSink(); if (l != null && (System.currentTimeMillis() - lastWarnMs > 2000)) { lastWarnMs = System.currentTimeMillis(); l.line("⚠ 系统注入被拒绝（MIUI 限制），这一次退回 CarLife 触控通道"); } }
    }

    private static volatile boolean lastOk = true;
    static volatile boolean lastDispatchReturn;
    static volatile String lastDispatchError;
    static volatile long lastCancelMs;
    private static long lastWarnMs;
    public static boolean lastOk() { return lastOk; }

    public interface Result { void onResult(boolean ok); }

    /** 带结果回调的点击：系统拒绝（MIUI 限制/服务被回收）时会回 false，调用方可以退回老通道。 */
    public static void tapWithResult(float x, float y, final Result cb) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 45));
        TouchBridgeService s = inst;
        if (s == null) { if (cb != null) cb.onResult(false); return; }
        try {
            s.dispatchGesture(b.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { if (cb != null) cb.onResult(true); }
                @Override public void onCancelled(GestureDescription g) { if (cb != null) cb.onResult(false); }
            }, null);
        } catch (Throwable t) { if (cb != null) cb.onResult(false); }
    }


    // ==== 输入法窗口直点（不依赖 dispatchGesture，直接点 IME 窗口里的按键节点）====
    static volatile long lastImeClickMs;
    static volatile boolean imeSeen;
    static volatile String imeErr;
    private static long lastImeLogMs;

    private static android.view.accessibility.AccessibilityNodeInfo deepest(
            android.view.accessibility.AccessibilityNodeInfo n, float x, float y, int depth) {
        if (n == null || depth > 24) return null;
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        if (!r.contains((int) x, (int) y)) return null;
        int kids = n.getChildCount();
        for (int i = 0; i < kids; i++) {
            android.view.accessibility.AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            android.view.accessibility.AccessibilityNodeInfo hit = deepest(c, x, y, depth + 1);
            if (hit != null) return hit;
        }
        return n;
    }

    /** 若 (x,y) 落在输入法窗口里，就把那一点上的按键节点点下去。成功返回 true。 */
    public static boolean clickIme(float x, float y) {
        TouchBridgeService s = inst;
        if (s == null || Build.VERSION.SDK_INT < 24) return false;
        imeSeen = false;
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = s.getWindows();
            if (ws == null) return false;
            for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                if (w == null) continue;
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                imeSeen = true;
                android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                android.view.accessibility.AccessibilityNodeInfo n = deepest(root, x, y, 0);
                int guard = 0;
                while (n != null && guard++ < 8) {
                    if (n.isClickable()) {
                        if (n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                            lastImeClickMs = System.currentTimeMillis();
                            {
                                CharSequence txt = n.getText();
                                CharSequence cd = n.getContentDescription();
                                android.graphics.Rect rb = new android.graphics.Rect();
                                n.getBoundsInScreen(rb);
                                lastImeLogMs = System.currentTimeMillis();
                                Net.Log l = Rig.logSink();
                                if (l != null) l.line("⌨ 键盘直点 (" + (int) x + "," + (int) y + ") → 点到节点"
                                        + " [" + (cd != null ? cd : (txt != null ? txt : "无标签")) + "]"
                                        + " 范围 " + rb.left + "," + rb.top + "-" + rb.right + "," + rb.bottom
                                        + " cls=" + n.getClassName());
                            }
                            return true;
                        }
                    }
                    n = n.getParent();
                }
            }
        } catch (Throwable t) {
            imeErr = t.toString();
        }
        return false;
    }

    /** 当前是否有输入法窗口（用于日志判断"键盘到底在不在真实窗口里"）。 */
    public static boolean imeSeen() { return imeSeen; }

    // ==== 无障碍窗口树 dump：验证虚拟屏(275)与输入法窗口是否在无障碍视野里 ====

    public static void dumpWindows() {
        AccessibilityService s = inst;
        Net.Log l = Rig.logSink();
        android.util.Log.i("CarbitProbe", "[AXDUMP] 开始 inst=" + (s != null) + " sink=" + (l != null));
        try {
            if (s == null) { if (l != null) l.line("✗ 无障碍未开启，dump 跳过"); return; }
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = s.getWindows();
            int n = ws == null ? -1 : ws.size();
            AX("=== 无障碍窗口 dump：共 " + n + " 个 ===");
            if (ws == null) return;
            for (int i = 0; i < n; i++) {
                android.view.accessibility.AccessibilityWindowInfo w = ws.get(i);
                if (w == null) continue;
                int t = w.getType();
                String tn = t == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ? "IME"
                        : t == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ? "APP"
                        : t == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM ? "SYS"
                        : ("T" + t);
                String disp = "?";
                try { Object v = w.getClass().getMethod("getDisplayId").invoke(w); disp = String.valueOf(v); } catch (Throwable ignored) { }
                AX("窗口#" + i + " " + tn + " display=" + disp + " layer=" + w.getLayer() + " active=" + w.isActive());
                android.view.accessibility.AccessibilityNodeInfo root = null;
                try { root = w.getRoot(); } catch (Throwable ignored) { }
                if (root == null) { AX("   root=null（拿不到节点，说明这一层不可读）"); continue; }
                walkDump(root, 1, l, new int[]{0});
            }
        } catch (Throwable t) {
            AX("dump 异常 " + t);
        }
    }

    private static void AX(String m) {
        android.util.Log.i("CarbitProbe", "[AXDUMP] " + m);
        try { Net.Log l = Rig.logSink(); if (l != null) l.line(m); } catch (Throwable ignored) { }
    }

    private static void walkDump(android.view.accessibility.AccessibilityNodeInfo nd, int d, Net.Log l, int[] cnt) {
        if (nd == null || d > 14 || cnt[0] > 150) return;
        cnt[0]++;
        android.graphics.Rect r = new android.graphics.Rect();
        nd.getBoundsInScreen(r);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d; i++) sb.append("· ");
        sb.append(nd.getClassName());
        sb.append(" [").append(r.left).append(",").append(r.top).append("][").append(r.right).append(",").append(r.bottom).append("]");
        if (nd.isEditable()) sb.append(" ✎");
        if (nd.isClickable()) sb.append(" 可点");
        CharSequence tx = nd.getText();
        CharSequence cd = nd.getContentDescription();
        String vid = null;
        try { vid = nd.getViewIdResourceName(); } catch (Throwable ignored) { }
        if (tx != null && tx.length() > 0) sb.append(" text=\"").append(tx).append("\"");
        if (cd != null && cd.length() > 0) sb.append(" desc=\"").append(cd).append("\"");
        if (vid != null) sb.append(" id=").append(vid);
        AX(sb.toString());
        int k = nd.getChildCount();
        for (int i = 0; i < k; i++) {
            android.view.accessibility.AccessibilityNodeInfo c = null;
            try { c = nd.getChild(i); } catch (Throwable ignored) { }
            if (c != null) walkDump(c, d + 1, l, cnt);
        }
    }

    /** 单点点击（给"点一下就完事"的场景用）。 */
    public static void tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 45));
        TouchBridgeService s = inst;
        if (s == null) return;
        try { s.dispatchGesture(b.build(), null, null); } catch (Throwable ignored) { }
    }
}
