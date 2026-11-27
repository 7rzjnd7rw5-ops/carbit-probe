package io.carbit.probe;

/** 每秒汇总一次“我们实际喂给 AudioTrack 的音频”到底有多响、有多少包。 */
public class AudioStat {
    private static long winStart = System.currentTimeMillis();
    private static int pk, pkWin, rmsSum, rmsN, pktWin, pktTotal;
    private static double sqWin;

    public static synchronized void add(int peak, int rms) {
        if (peak > pkWin) pkWin = peak;
        if (peak > pk) pk = peak;
        sqWin += (double) rms * rms; rmsN++;
        pktWin++;
    }

    public static synchronized boolean shouldReport() {
        return System.currentTimeMillis() - winStart >= 1000;
    }

    public static synchronized String summary() {
        int rms = rmsN > 0 ? (int) Math.sqrt(sqWin / rmsN) : 0;
        String s = "近1秒 包数=" + pktWin + " 峰值=" + pkWin + " RMS=" + rms
                + "（窗口累计包=" + (pktTotal + pktWin) + " 历史峰值=" + Math.max(pk, pkWin) + "）";
        pktTotal += pktWin;
        winStart = System.currentTimeMillis();
        pkWin = 0; sqWin = 0; rmsN = 0; pktWin = 0;
        return s;
    }
}
