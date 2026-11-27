package io.carbit.probe;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * 自检：一边用 AudioTrack 放 440Hz 满幅正弦，一边用麦克风录音，
 * 看麦克风是否真的听到 440Hz 能量 —— 用来区分"代码没出声"还是"扬声器/系统把声音吞了"。
 */
public class AudioSelfTest {

    private static final String TAG = "CarbitProbe";

    public static void run(final int seconds) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int rate = 44100;
                    AudioRecord rec = null;
                    try {
                        int minRec = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT);
                        rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                                Math.max(minRec, rate));
                    } catch (Throwable t) {
                        android.util.Log.i(TAG, "[自检] 创建录音器失败 " + t);
                    }
                    if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
                        android.util.Log.i(TAG, "[自检] 录音不可用（多半是没给录音权限）");
                        if (rec != null) try { rec.release(); } catch (Throwable ignored) { }
                        return;
                    }
                    rec.startRecording();
                    MediaAudio.playTestTone(seconds * 1000);
                    int total = rate * seconds;
                    short[] buf = new short[total];
                    int got = 0;
                    while (got < total) {
                        int n = rec.read(buf, got, Math.min(4096, total - got));
                        if (n <= 0) break;
                        got += n;
                    }
                    try { rec.stop(); rec.release(); } catch (Throwable ignored) { }

                    double rms = 0;
                    for (int i = 0; i < got; i++) rms += (double) buf[i] * buf[i];
                    rms = got > 0 ? Math.sqrt(rms / got) : 0;
                    // Goertzel 取 440Hz 能量
                    double target = 440.0, mag = 0;
                    for (double f : new double[]{440.0, 300.0, 800.0}) {
                        double w = 2 * Math.PI * f / rate;
                        double re = 0, im = 0;
                        for (int i = 0; i < got; i++) { re += buf[i] * Math.cos(w * i); im += buf[i] * Math.sin(w * i); }
                        double m = Math.sqrt(re * re + im * im) / Math.max(1, got);
                        if (f == 440.0) mag = m;
                        android.util.Log.i(TAG, String.format("[自检] 频点 %.0fHz 幅度 %.2f", f, m));
                    }
                    android.util.Log.i(TAG, String.format("[自检] 录音 %d 采样 麦克风RMS %.1f, 440Hz幅度 %.2f -> %s",
                            got, rms, mag, mag > 3 ? "扬声器确实在响" : "麦克风没听到声音（扬声器/路由有问题）"));
                } catch (Throwable t) {
                    android.util.Log.i(TAG, "[自检] 异常 " + t);
                }
            }
        }, "audio-selftest").start();
    }
}
