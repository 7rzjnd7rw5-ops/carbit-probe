package io.carbit.probe;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 包体检：把一个系统包（如 CarWith）的组件全部摊开。
 *
 * 目的不是"看懂 APK"，而是回答一个具体问题：
 *   这个 App 里到底有没有一个"能被第三方拉起/能被捕获的车机输出页"，
 *   还是说它只有设置页、真正的车机界面根本不在手机上。
 *
 * 输出：ApplicationInfo 关键位 + Activity/Service/Receiver/Provider 全表
 *      （含 exported / 权限 / intent-filter / meta-data）。
 */
public final class Dump {

    public interface Log {
        void line(String s);
    }

    /** 名字里带这些词的可疑组件（最可能是车机界面/投屏通道）。 */
    private static final String[] HOT = {
            "launcher", "home", "desk", "car", "display", "project", "mirror",
            "screen", "vdisplay", "presentation", "cast", "session", "connect"
    };

    public static String report(Context ctx, String pkg, Log log) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = ctx.getPackageManager();

        int flags = PackageManager.GET_ACTIVITIES
                | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS
                | PackageManager.GET_PROVIDERS
                | PackageManager.GET_META_DATA
                | PackageManager.GET_PERMISSIONS
                | PackageManager.MATCH_DISABLED_COMPONENTS;

        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(pkg, flags);
        } catch (NameNotFoundException e) {
            String s = "没有安装 " + pkg;
            if (log != null) log.line(s);
            return s;
        } catch (Throwable t) {
            String s = "读 " + pkg + " 失败: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            if (log != null) log.line(s);
            return s;
        }

        ApplicationInfo ai = pi.applicationInfo;

        sb.append("=== 包体检 ").append(label(pm, pkg)).append(" (").append(pkg).append(") ===\n");
        sb.append("versionName ").append(pi.versionName)
          .append("  versionCode ").append(pi.versionCode)
          .append("  targetSdk ").append(ai == null ? "?" : ai.targetSdkVersion).append("\n");
        sb.append("uid ").append(ai == null ? "?" : ai.uid)
          .append("  enabled ").append(ai == null ? "?" : ai.enabled)
          .append("  flags ").append(ai == null ? "?" : flagsText(ai.flags)).append("\n");
        if (ai != null) {
            sb.append("sourceDir ").append(ai.sourceDir).append("\n");
            sb.append("process ").append(ai.processName).append("\n");
        }
        if (pi.requestedPermissions != null && pi.requestedPermissions.length > 0) {
            sb.append("requestedPermissions (").append(pi.requestedPermissions.length).append("):\n");
            for (String p : pi.requestedPermissions) sb.append("  · ").append(p).append("\n");
        }

        // Activity
        ActivityInfo[] acts = pi.activities;
        sb.append("\n--- Activity 共 ").append(acts == null ? 0 : acts.length).append(" 个 ---\n");
        if (acts != null) {
            List<ActivityInfo> ordered = new ArrayList<ActivityInfo>();
            Collections.addAll(ordered, acts);
            sortExportFirst(ordered);
            for (ActivityInfo a : ordered) {
                sb.append("  ").append(a.exported ? "[导出]" : "[内部]").append(" ")
                  .append(a.name)
                  .append("  launchMode=").append(launchModeText(a.launchMode))
                  .append("  enabled=").append(a.enabled);
                if (a.permission != null) sb.append("  perm=").append(a.permission);
                sb.append(hotTag(a.name)).append("\n");
                meta(sb, a);
            }
        }

        // Service
        ServiceInfo[] svcs = pi.services;
        sb.append("\n--- Service 共 ").append(svcs == null ? 0 : svcs.length).append(" 个 ---\n");
        if (svcs != null) {
            for (ServiceInfo s : svcs) {
                sb.append("  ").append(s.exported ? "[导出]" : "[内部]").append(" ")
                  .append(s.name)
                  .append("  enabled=").append(s.enabled)
                  .append(hotTag(s.name)).append("\n");
                meta(sb, s);
            }
        }

        // Receiver
        ActivityInfo[] recs = pi.receivers;
        sb.append("\n--- Receiver 共 ").append(recs == null ? 0 : recs.length).append(" 个 ---\n");
        if (recs != null) {
            for (ActivityInfo r : recs) {
                sb.append("  ").append(r.exported ? "[导出]" : "[内部]").append(" ")
                  .append(r.name).append("\n");
            }
        }

        // Provider
        ProviderInfo[] provs = pi.providers;
        sb.append("\n--- Provider 共 ").append(provs == null ? 0 : provs.length).append(" 个 ---\n");
        if (provs != null) {
            for (ProviderInfo p : provs) {
                sb.append("  ").append(p.exported ? "[导出]" : "[内部]").append(" ")
                  .append(p.name).append("  authority=").append(p.authority).append("\n");
            }
        }

        String text = sb.toString();
        if (log != null) {
            for (String ln : text.split("\n")) log.line(ln);
        }
        return text;
    }

    /** 全部 Activity（含未导出，用于逐个试启，拿不到就记 SecurityException）。 */
    public static List<String> allActivities(Context ctx, String pkg) {
        List<String> out = new ArrayList<String>();
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg,
                    PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (pi.activities == null) return out;
            for (ActivityInfo ai : pi.activities) {
                if (ai == null || ai.name == null) continue;
                out.add((ai.exported ? "E " : "I ") + ai.name);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void sortExportFirst(List<ActivityInfo> list) {
        Collections.sort(list, new Comparator<ActivityInfo>() {
            @Override public int compare(ActivityInfo a, ActivityInfo b) {
                if (a.exported == b.exported) return a.name.compareTo(b.name);
                return a.exported ? -1 : 1;
            }
        });
    }

    private static String hotTag(String name) {
        String low = name.toLowerCase();
        for (String k : HOT) {
            if (low.contains(k)) return "   <<< 名字命中 " + k;
        }
        return "";
    }

    private static void meta(StringBuilder sb, ComponentInfo ci) {
        if (ci.metaData == null || ci.metaData.isEmpty()) return;
        sb.append("        meta-data: ").append(ci.metaData.keySet()).append("\n");
    }

    private static String launchModeText(int m) {
        switch (m) {
            case ActivityInfo.LAUNCH_MULTIPLE: return "standard";
            case ActivityInfo.LAUNCH_SINGLE_TOP: return "singleTop";
            case ActivityInfo.LAUNCH_SINGLE_TASK: return "singleTask";
            case ActivityInfo.LAUNCH_SINGLE_INSTANCE: return "singleInstance";
            default: return String.valueOf(m);
        }
    }

    private static String flagsText(int flags) {
        StringBuilder sb = new StringBuilder();
        if ((flags & ApplicationInfo.FLAG_SYSTEM) != 0) sb.append("SYSTEM ");
        if ((flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) sb.append("UPDATED_SYSTEM ");
        if ((flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) sb.append("DEBUGGABLE ");
        if ((flags & ApplicationInfo.FLAG_PERSISTENT) != 0) sb.append("PERSISTENT ");
        sb.append("(0x").append(Integer.toHexString(flags)).append(")");
        return sb.toString();
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private Dump() {
    }
}
