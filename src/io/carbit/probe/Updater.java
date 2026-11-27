package io.carbit.probe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * App 内更新：从 NAS 静态站拉 version.json，比 versionCode，下载后校验 sha256 再拉起安装。
 *
 * version.json 约定：
 * {"versionCode":14,"versionName":"1.4","size":270000,"sha256":"...","url":"/CarbitProbe.apk"}
 */
public final class Updater {

    public interface Log {
        void line(String s);
    }

    public interface Progress {
        void onProgress(int percent);
    }

    public static final class Info {
        public int versionCode;
        public String versionName;
        public long size;
        public String sha256;
        public String url;
    }

    /** 安装包落在这里，由 ApkProvider 通过 content:// 授权给系统安装器。 */
    public static File apkFile(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        return new File(dir, "CarbitProbe-update.apk");
    }

    /**
     * 依次尝试候选地址。base 形如 http://host:8088/carbit，version.json 放在上一级：
     * http://host:8088/carbit-version.json
     *
     * @return 成功返回 Info，全部失败返回 null
     */
    public static Info check(String[] bases, Log log) {
        for (String base : bases) {
            if (base == null || base.isEmpty()) continue;
            String root = base;
            int slash = root.lastIndexOf('/');
            if (slash > 7) root = root.substring(0, slash);
            String url = root + "/carbit-version.json";
            if (log != null) log.line("查询 " + url);
            Info info = fetch(url, log);
            if (info != null && info.versionCode > 0) return info;
        }
        return null;
    }

    private static Info fetch(String url, Log log) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code != 200) {
                if (log != null) log.line("  HTTP " + code);
                return null;
            }
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String body = bos.toString("UTF-8").trim();
            if (log != null) log.line("  响应 " + body);
            JSONObject o = new JSONObject(body);
            Info i = new Info();
            i.versionCode = o.optInt("versionCode", 0);
            i.versionName = o.optString("versionName", "?");
            i.size = o.optLong("size", 0);
            i.sha256 = o.optString("sha256", "");
            i.url = o.optString("url", "/CarbitProbe.apk");
            return i;
        } catch (Throwable t) {
            if (log != null) log.line("  失败 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 下载到 apkFile(ctx)，校验 sha256；成功返回文件，失败返回 null。 */
    public static File download(Context ctx, String baseUrl, Info info, Log log, Progress cb) {
        String url = info.url;
        if (!url.startsWith("http")) {
            String root = baseUrl;
            int slash = root.lastIndexOf('/');
            if (slash > 7) root = root.substring(0, slash);
            url = root + (url.startsWith("/") ? url : "/" + url);
        }
        if (log != null) log.line("下载 " + url);
        File dest = apkFile(ctx);
        HttpURLConnection c = null;
        FileOutputStream out = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code != 200) {
                if (log != null) log.line("下载失败 HTTP " + code);
                return null;
            }
            long total = c.getContentLengthLong();
            InputStream in = c.getInputStream();
            out = new FileOutputStream(dest);
            byte[] buf = new byte[16384];
            long done = 0;
            int n;
            int lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct / 10 != lastPct / 10) {
                        lastPct = pct;
                        if (log != null) log.line("  已下载 " + pct + "%（" + done + "/" + total + "）");
                        if (cb != null) cb.onProgress(pct);
                    }
                }
            }
            out.flush();
            out.close();
            out = null;
            in.close();

            if (info.sha256 != null && info.sha256.length() == 64) {
                String actual = sha256(dest);
                if (log != null) log.line("  sha256 " + actual);
                if (!actual.equalsIgnoreCase(info.sha256)) {
                    if (log != null) log.line("  ✗ 校验不一致，删除文件不安装");
                    dest.delete();
                    return null;
                }
                if (log != null) log.line("  ✓ 校验通过");
            }
            return dest;
        } catch (Throwable t) {
            if (log != null) log.line("下载异常 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
            if (c != null) c.disconnect();
        }
    }

    /** 拉起系统安装器；需要"允许安装未知来源"时自动跳设置页。 */
    public static void install(Context ctx, File apk, Log log) {
        try {
            if (Build.VERSION.SDK_INT >= 26
                    && !ctx.getPackageManager().canRequestPackageInstalls()) {
                if (log != null) log.line("需要授予“安装未知应用”权限，正在打开设置…");
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(s);
                return;
            }
            Uri u = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            if (log != null) log.line("已拉起安装器");
        } catch (Throwable t) {
            if (log != null) log.line("拉起安装器失败: " + t.getMessage());
        }
    }

    public static String sha256(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static int installedVersionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private Updater() {
    }
}
