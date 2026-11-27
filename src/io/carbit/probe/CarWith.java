package io.carbit.probe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 找车机互联类应用并调起。
 *
 * 关键教训（v1.5 实测）：CarWith 是小米系统内置件，包名 com.miui.carlink，
 * 没有桌面图标，入口在「设置 → 连接与共享 → CarWith」。按 CATEGORY_LAUNCHER 扫
 * 是扫不到的。所以这里做三层：
 *   1) 已知包名白名单直接探测（最可靠）
 *   2) 全量包名关键词匹配（需要 QUERY_ALL_PACKAGES 权限）
 *   3) 对目标包枚举所有导出 Activity，没有启动器入口也能直接拉起
 */
public final class CarWith {

    /** 已知/可能的 CarWith 及车机互联包名，按可能性排序。 */
    public static final String[] KNOWN = {
            "com.miui.carlink",              // 小米 CarWith（现役）
            "com.xiaomi.vcar",               // CarWith 旧/部分机型
            "com.miui.carwith",
            "com.xiaomi.carwith",
            "com.baidu.carlife.xiaomi",      // 小米定制 CarLife 组件
            "com.baidu.carlife",
            "com.miui.carlife",
            "com.xiaomi.mi_connect_service", // 小米互联
    };

    private static final String[] KEYS = {
            "carlink", "carwith", "car with", "vcar", "carlife", "iccoa",
            "carplay", "carbit", "easyconn", "voge", "无极",
            "车机", "互联", "投屏", "驾驶"
    };

    /** 已知包名里已安装的那些，返回 {包名, 应用名}。 */
    public static List<String[]> known(Context ctx) {
        List<String[]> out = new ArrayList<String[]>();
        for (String pkg : KNOWN) {
            if (!installed(ctx, pkg)) continue;
            out.add(new String[]{pkg, label(ctx, pkg)});
        }
        return out;
    }

    /**
     * 综合候选：已知包名优先，再用全量包名/应用名做关键词兜底。
     * 去重，保持顺序。
     */
    public static List<String[]> all(Context ctx) {
        Set<String> seen = new LinkedHashSet<String>();
        List<String[]> out = new ArrayList<String[]>();

        for (String[] row : known(ctx)) {
            if (seen.add(row[0])) out.add(row);
        }

        try {
            PackageManager pm = ctx.getPackageManager();
            List<PackageInfo> installed = pm.getInstalledPackages(0);
            for (PackageInfo pi : installed) {
                if (pi == null || pi.packageName == null) continue;
                String pkg = pi.packageName;
                if (pkg.equals(ctx.getPackageName())) continue;
                if (seen.contains(pkg)) continue;
                String name = label(ctx, pkg);
                String hay = (pkg + " " + name).toLowerCase();
                for (String k : KEYS) {
                    if (hay.contains(k)) {
                        seen.add(pkg);
                        out.add(new String[]{pkg, name});
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 有桌面入口的再补一遍（老路子，可能有全量枚举看不到的）
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
            for (ResolveInfo ri : list) {
                if (ri == null || ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || seen.contains(pkg)) continue;
                String name = ri.loadLabel(pm).toString();
                String hay = (pkg + " " + name).toLowerCase();
                for (String k : KEYS) {
                    if (hay.contains(k)) {
                        seen.add(pkg);
                        out.add(new String[]{pkg, name});
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return out;
    }

    /** 该包里所有 exported 的 Activity（全名）。 */
    public static List<String> activities(Context ctx, String pkg) {
        List<String> out = new ArrayList<String>();
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg,
                    PackageManager.GET_ACTIVITIES);
            if (pi.activities == null) return out;
            for (ActivityInfo ai : pi.activities) {
                if (ai == null || ai.name == null) continue;
                if (!ai.exported) continue;
                out.add(ai.name);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 优先用启动器入口；没有就用该包里最像主入口的导出 Activity。 */
    public static boolean launch(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return true;
            }
        } catch (Throwable ignored) {
        }
        // 退路：挑一个像主入口的 Activity
        for (String cls : activities(ctx, pkg)) {
            String low = cls.toLowerCase();
            if (low.contains("main") || low.contains("launch") || low.contains("home")
                    || low.contains("carwith") || low.contains("entry")) {
                if (launchActivity(ctx, pkg, cls)) return true;
            }
        }
        return false;
    }

    public static boolean launchActivity(Context ctx, String pkg, String cls) {
        try {
            Intent i = new Intent();
            i.setClassName(pkg, cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private CarWith() {
    }
}
