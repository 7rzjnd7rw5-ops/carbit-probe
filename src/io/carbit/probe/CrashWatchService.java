package io.carbit.probe;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 独立进程(:watch)的死亡取证器。
 * 主进程每 2 秒写一次 hb.txt；这里发现 hb 超过 15 秒没更新，就认为主进程被系统直接带走
 * （这类死法没有 Java 栈），立刻把现场能捡到的东西全写下来：
 *  - 主进程最后写的状态 + 最后几十行日志
 *  - /proc/meminfo（看是不是低内存杀的）
 *  - logcat 各缓冲区尾部（best effort，读不到就记读不到）
 *  - 当前存活线程/进程列表里还有没有 carbit 的 ok
 * 结果写到 files/watchdog-<时间>.txt，下次 App 启动会自动读出来并随日志一起上传。
 */
public class CrashWatchService extends Service {

    private Thread loop;
    private volatile boolean running = true;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (loop == null) {
            loop = new Thread(new Runnable() { @Override public void run() { watch(); } }, "crash-watch");
            loop.start();
        }
        return START_STICKY;
    }

    private void watch() {
        File hb = new File(getFilesDir(), "hb.txt");
        File flag = new File(getFilesDir(), "watchdog.flag");
        while (running) {
            try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            try {
                if (!hb.exists() || flag.exists()) continue;
                long age = System.currentTimeMillis() - hb.lastModified();
                if (age < 15000) continue;
                dump(age);
                new FileOutputStream(flag).close();
            } catch (Throwable ignored) { }
        }
    }

    private void dump(long ageMs) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        sb.append("# 看门狗：主进程心跳停摆 ").append(ageMs / 1000).append(" 秒，判定被系统直接带走\n");
        sb.append("# 记录时间 ").append(f.format(new Date())).append("\n\n");

        sb.append("===== 1) 主进程最后状态（hb.txt） =====\n");
        sb.append(readFile(new File(getFilesDir(), "hb.txt"), 20000)).append("\n");

        sb.append("===== 2) 内存压力（/proc/meminfo） =====\n");
        sb.append(head("/proc/meminfo", 24)).append("\n");
        sb.append("===== 2b) PSI memory（有就说明在回收内存） =====\n");
        sb.append(head("/proc/pressure/memory", 6)).append("\n");

        sb.append("===== 3) 自己进程是否还活着 =====\n");
        sb.append(sh("ps -A | grep -i carbit")).append("\n");

        sb.append("===== 4) logcat crash 缓冲尾部 =====\n");
        sb.append(sh("logcat -d -b crash -v time -t 200")).append("\n");
        sb.append("===== 5) logcat main 尾部（含本应用） =====\n");
        sb.append(sh("logcat -d -b main -v time -t 400")).append("\n");
        sb.append("===== 6) 内核日志尾部（无权限时为空） =====\n");
        sb.append(sh("dmesg -t | tail -60")).append("\n");

        sb.append("===== 7) 掉帧现场 trace.txt 尾部 =====\n");
        sb.append(tail(new File(getFilesDir(), "trace.txt"), 12000)).append("\n");

        String name = "watchdog-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        try {
            FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), name));
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) { }
    }

    private String readFile(File f, int max) {
        try {
            byte[] b = new byte[max];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(b);
            in.close();
            if (n <= 0) return "(空)";
            return new String(b, 0, n, "UTF-8");
        } catch (Throwable t) { return "(读不到: " + t + ")"; }
    }

    private String head(String path, int lines) {
        return sh("head -n " + lines + " " + path);
    }

    private String tail(File f, int bytes) {
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long from = Math.max(0, len - bytes);
            raf.seek(from);
            byte[] b = new byte[(int) (len - from)];
            raf.readFully(b);
            raf.close();
            return new String(b, "UTF-8");
        } catch (Throwable t) { return "(读不到: " + t + ")"; }
    }

    /** 跑一条 shell，超时 2 秒，把标准输出+错误都收回来 */
    private String sh(String cmd) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            InputStream in = p.getInputStream();
            InputStream err = p.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + 2000;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < deadline) {
                int n = in.available() > 0 ? in.read(buf) : -1;
                if (n > 0) bos.write(buf, 0, n);
                while (err.available() > 0 && err.read(buf) > 0) { }
                if (n <= 0) { try { Thread.sleep(60); } catch (Exception e) { break; } }
                if (bos.size() > 120000) break;
            }
            try { p.destroy(); } catch (Throwable ignored) { }
            out.append(bos.toString("UTF-8"));
        } catch (Throwable t) {
            out.append("(执行失败: ").append(t).append(")");
        }
        if (out.length() == 0) out.append("(无输出，可能被系统权限拦了)");
        return out.append("\n").toString();
    }

    @Override public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
