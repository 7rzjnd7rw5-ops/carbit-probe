package io.carbit.probe;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 专门的崩溃抓取进程（android:process=":collie"），跟主进程完全隔离：主进程无论是 Java 异常、
 * native SIGSEGV 还是被系统/用户杀掉，它都活着，并且已经把"死之前的现场"实时写到了磁盘上。
 *
 * 和老的 CrashWatchService（:watch）的区别：
 *  - watch 靠"主进程 2 秒一次心跳，断供 15 秒才反应"，只能事后去 logcat 里翻，缓冲区可能已经滚掉；
 *  - 这里用两条常驻 logcat 流（crash 缓冲 + 只含本应用 pid 的 main 缓冲）边跑边落盘，
 *    再用 /proc/<pid> 每 600ms 探活，进程一消失立刻收尾 —— 崩溃前后那几秒一定在文件里。
 *
 * 拿不到的东西要说清楚：没有 root 就不能 ptrace 别的进程，SIGSEGV 的 tombstone 不能自己去 dump，
 * 但内核会把它写进 crash 缓冲，我们流式抓的就是这份；如果连 crash 缓冲也拿不到（权限/机型限制），
 * 只能给出"何时死的、死前在干什么、内存压力多少、谁杀的"这些间接证据。
 */
public class CrashCatcherService extends Service {

    private static final long CAP_BYTES = 6L * 1024 * 1024;   // 单个流文件上限，超了只保留尾部

    private volatile boolean running = true;
    private volatile int mainPid = -1;
    private Thread detectThread, crashThread, mainThread;
    private Process crashProc, mainProc;

    /** 常驻日志只留内存：约 10 秒、上限 4000 行，超了从头丢。 */
    private static final int RING_MAX = 4000;
    private static final java.util.ArrayDeque<String> crashRing = new java.util.ArrayDeque<String>();
    private static final java.util.ArrayDeque<String> mainRing = new java.util.ArrayDeque<String>();

    private static void pushRing(java.util.ArrayDeque<String> q, String line) {
        synchronized (q) {
            q.addLast(line);
            while (q.size() > RING_MAX) q.removeFirst();
        }
    }

    private static String drainTail(java.util.ArrayDeque<String> q, int n) {
        synchronized (q) {
            StringBuilder b = new StringBuilder();
            int skip = Math.max(0, q.size() - n);
            int i = 0;
            for (String s : q) { if (i++ >= skip) b.append(s).append('\n'); }
            return b.toString();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        int pid = intent == null ? 0 : intent.getIntExtra("pid", 0);
        if (pid > 0) mainPid = pid;
        if (detectThread == null) {
            detectThread = new Thread(new Runnable() { @Override public void run() { detectLoop(); } }, "collie-detect");
            detectThread.start();
        }
        if (crashThread == null) {
            crashThread = new Thread(new Runnable() { @Override public void run() { streamLog(true); } }, "collie-crash");
            crashThread.start();
        }
        if (mainThread == null) {
            mainThread = new Thread(new Runnable() { @Override public void run() { streamLog(false); } }, "collie-main");
            mainThread.start();
        }
        return START_STICKY;
    }

    /** 每 600ms 探一次主进程：/proc/<pid> 不存在就是死了（比心跳快得多，也不怕主线程被冻住）。 */
    private void detectLoop() {
        while (running) {
            try { Thread.sleep(600); } catch (InterruptedException e) { return; }
            if (!running) return;
            try {
                if (mainPid <= 0) mainPid = pidFromHeartbeat();
                if (mainPid <= 0) continue;
                File proc = new File("/proc/" + mainPid);
                if (proc.exists()) continue;
                // 进程没了：等一会让 logcat 把最后几行（含 AMS 的 kill 原因）刷出来
                try { Thread.sleep(900); } catch (InterruptedException e) { }
                dump(mainPid);
                running = false;
                stopSelf();
                return;
            } catch (Throwable ignored) { }
        }
    }

    /** 主进程心跳里带着自己的 pid，服务被系统重启后也能重新找回目标。 */
    private int pidFromHeartbeat() {
        try {
            File hb = new File(getFilesDir(), "hb.txt");
            if (!hb.exists()) return -1;
            RandomAccessFile raf = new RandomAccessFile(hb, "r");
            byte[] b = new byte[(int) Math.min(512, raf.length())];
            raf.readFully(b);
            raf.close();
            String s = new String(b, "UTF-8");
            int i = s.indexOf("pid=");
            if (i < 0) return -1;
            int j = i + 4, k = j;
            while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
            return Integer.parseInt(s.substring(j, k));
        } catch (Throwable t) { return -1; }
    }

    /**
     * 常驻 logcat 流：crash=true 抓 crash 缓冲（tombstone、signal 11、AMS 的 FATAL），
     * crash=false 只抓本应用 pid 的 main 缓冲（自己的日志 + 系统对我们的处置）。
     * 边读边落盘，进程死掉也不会丢。
     */
    private void streamLog(boolean crash) {
        // 只在内存里留最近 ~10 秒（约 4000 行），不常驻落盘：平时零磁盘写入，
        // 主进程一死才把这段尾巴写进现场文件。
        while (running) {
            Process p = null;
            try {
                String[] cmd = crash
                        ? new String[]{"/system/bin/logcat", "-b", "crash", "-v", "threadtime"}
                        : new String[]{"/system/bin/logcat", "-v", "threadtime", "--pid=" + Math.max(mainPid, 0)};
                p = Runtime.getRuntime().exec(cmd);
                if (crash) crashProc = p; else mainProc = p;
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                while (running && (line = br.readLine()) != null) {
                    if (crash) pushRing(crashRing, line); else pushRing(mainRing, line);
                }
            } catch (Throwable ignored) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            } finally {
                if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
            }
            if (running) { try { Thread.sleep(300); } catch (InterruptedException e) { return; } }
        }
    }

    /** 文件太大时只留最后 1.5MB（最有价值的是尾部）。 */
    private void trim(File f) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            long len = raf.length();
            long keep = 1536 * 1024;
            if (len <= keep) { raf.close(); return; }
            byte[] tail = new byte[(int) keep];
            raf.seek(len - keep);
            raf.readFully(tail);
            raf.setLength(0);
            raf.write(tail);
            raf.close();
        } catch (Throwable ignored) { }
    }

    /** 主进程已死：把两条实时流 + 内存/PSI/进程表/内核日志/秒级 trace 收成一个现场文件。 */
    private void dump(int pid) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        sb.append("# 崩溃抓取进程（:collie）：检测到主进程 pid=").append(pid)
          .append(" 消失（/proc 不存在），以下为它死前实时落盘的现场\n");
        sb.append("# 记录时间 ").append(f.format(new Date())).append("\n");
        sb.append("# 判定说明：本进程与主进程同 UID、不同进程，主进程被杀/A 掉/native 崩都不影响这里；\n");
        sb.append("#           没有 root 无法主动 ptrace，SIGSEGV 的 tombstone 只能从 crash 缓冲里取。\n\n");

        sb.append("===== 1) crash 缓冲（死前约 10 秒，尾部 400 行：tombstone / signal / FATAL） =====\n");
        sb.append(drainTail(crashRing, 400)).append("\n");

        sb.append("===== 2) 主进程 main 缓冲（死前约 10 秒，尾部 400 行，含 AMS 的 kill 原因） =====\n");
        sb.append(drainTail(mainRing, 400)).append("\n");

        sb.append("===== 3) 主进程最后写的心跳（hb.txt） =====\n");
        sb.append(headFile(new File(getFilesDir(), "hb.txt"), 20000)).append("\n");

        sb.append("===== 4) 内存压力 =====\n");
        sb.append(shell("head -n 24 /proc/meminfo")).append("\n");
        sb.append(shell("cat /proc/pressure/memory")).append("\n");

        sb.append("===== 5) 还有没有本应用的进程 =====\n");
        sb.append(shell("ps -A | grep -i carbit")).append("\n");

        sb.append("===== 6) 内核日志尾部（无权限时为空） =====\n");
        sb.append(shell("dmesg -t | tail -60")).append("\n");

        sb.append("===== 7) 秒级落盘 trace 尾部 =====\n");
        sb.append(tailBytes(new File(getFilesDir(), "trace.txt"), 24000)).append("\n");

        String name = "watchdog-live-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        try {
            FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), name));
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) { }
        // 标记交给下次启动的主界面去弹窗/上传
        try { new FileOutputStream(new File(getFilesDir(), "watchdog.flag")).close(); } catch (Throwable ignored) { }
    }

    private String tailLines(File f, int lines) {
        String all = tailBytes(f, 3 * 1024 * 1024);
        if (all.length() == 0) return "(空：这条流没抓到东西，可能机型限制读 logcat)\n";
        String[] arr = all.split("\n");
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, arr.length - lines);
        for (int i = from; i < arr.length; i++) sb.append(arr[i]).append("\n");
        return sb.toString();
    }

    private String tailBytes(File f, int bytes) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            long from = Math.max(0, len - bytes);
            raf.seek(from);
            byte[] b = new byte[(int) (len - from)];
            raf.readFully(b);
            raf.close();
            return new String(b, "UTF-8");
        } catch (Throwable t) { return "(读不到: " + t + ")\n"; }
    }

    private String headFile(File f, int max) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            byte[] b = new byte[(int) Math.min(max, raf.length())];
            raf.readFully(b);
            raf.close();
            return new String(b, "UTF-8");
        } catch (Throwable t) { return "(读不到: " + t + ")\n"; }
    }

    /** 跑一条 shell，收 2 秒内的输出（和 :watch 里那套一致，避免死等）。 */
    private String shell(String cmd) {
        StringBuilder out = new StringBuilder();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            InputStream in = p.getInputStream();
            long deadline = System.currentTimeMillis() + 2000;
            byte[] buf = new byte[4096];
            int n;
            while (System.currentTimeMillis() < deadline) {
                n = in.read(buf);
                if (n <= 0) break;
                out.append(new String(buf, 0, n, "UTF-8"));
                if (out.length() > 80000) break;
            }
        } catch (Throwable t) {
            out.append("(执行失败: ").append(t).append(")");
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
        if (out.length() == 0) out.append("(无输出，可能被权限拦了)");
        return out.append("\n").toString();
    }

    @Override public void onDestroy() {
        running = false;
        try { if (crashProc != null) crashProc.destroy(); } catch (Throwable ignored) { }
        try { if (mainProc != null) mainProc.destroy(); } catch (Throwable ignored) { }
        if (detectThread != null) detectThread.interrupt();
        if (crashThread != null) crashThread.interrupt();
        if (mainThread != null) mainThread.interrupt();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
