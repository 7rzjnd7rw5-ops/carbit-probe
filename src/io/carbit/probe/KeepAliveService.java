package io.carbit.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 前台保活：锁屏后仍让假车机线程与 CarLife 连接活着。
 * 只做两件事——挂常驻通知 + 持 PARTIAL_WAKE_LOCK，不参与任何业务逻辑。
 */
public class KeepAliveService extends Service {
    private static final String CH = "carbit_keepalive";
    private PowerManager.WakeLock lock;
    private WifiManager.WifiLock wifiLock;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "车机投屏运行中",
                    NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Notification n = b.setContentTitle("极享 · 车机投屏")
                .setContentText("假车机推流中，锁屏不中断")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
        startForeground(1, n);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carbit:fakehu");
        lock.setReferenceCounted(false);
        lock.acquire();
        acquireWifiLock();
    }

    /** 8.34：屏灭后不让 Wi-Fi 进省电。HIGH_PERF 老机型有，LOW_LATENCY 29+ 更狠。 */
    private void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "carbit:p2p");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Throwable t) {
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "carbit:p2p");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            } catch (Throwable ignored) { wifiLock = null; }
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Throwable t) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable t) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
