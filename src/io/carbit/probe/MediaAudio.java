package io.carbit.probe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * 车机侧音频播放：CarLife 把音乐以 12 字节包头 + PCM 发给车机，
 * 这里直接在本机用 AudioTrack 播出来（音频不经过仪表，从手机扬声器/蓝牙耳机出）。
 *
 * 路由策略（8.17 重做）：
 *  - 只在"选定设备变了"时才重绑，插拔事件 500ms 去抖，避免 CarWith 反复开关采集流
 *    导致 remote-submix 不断增删 → 我们每秒重绑十几次 → 声音被打断/写线程被锁饿死。
 *  - 三档手动：自动（有线/USB/蓝牙 → 扬声器）/ 本机扬声器 / 蓝牙。
 *  - writePcm 不加锁，避免被 getDevices / setPreferredDevice 阻塞。
 */
public class MediaAudio {
    private static AudioManager am;
    private static volatile AudioTrack track;
    private static int curRate = 44100, curCh = 2, curBits = 16;
    private static MediaAudio.Sink sink;
    private static android.media.AudioDeviceCallback deviceCb;
    private static int boundDevId = -1;

    /** 0 自动、1 本机扬声器、2 蓝牙耳机 */
    private static int routePref = 0;
    private static final String[] PREF_NAMES = {"自动", "本机扬声器", "蓝牙"};

    public interface Sink { void log(String s); }

    public static void setSink(Sink s) { sink = s; }

    public static String prefName() { return PREF_NAMES[routePref]; }

    public static synchronized String cyclePref() {
        routePref = (routePref + 1) % PREF_NAMES.length;
        boundDevId = -1;
        applyRoute("手动切换输出");
        return PREF_NAMES[routePref];
    }

    public static synchronized String status() {
        if (track == null) return "轨未创建";
        try {
            AudioDeviceInfo d = track.getRoutedDevice();
            String dev = d == null ? "未知" : ("type=" + d.getType() + " 名=" + d.getProductName());
            int vol = am != null ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : -1;
            int max = am != null ? am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : -1;
            return "state=" + track.getPlayState() + " 写入样本=" + track.getPlaybackHeadPosition()
                    + " 欠载=" + track.getUnderrunCount() + " 实际路由=[" + dev + "] 音量=" + vol + "/" + max
                    + " 静音=" + (am != null && am.isStreamMute(AudioManager.STREAM_MUSIC));
        } catch (Throwable t) { return "查询失败 " + t; }
    }

    private static void log(String s) {
        android.util.Log.i("CarbitProbe", "[音频] " + s);
        try { if (sink != null) sink.log(s); } catch (Throwable ignored) { }
    }

    public static synchronized void ensure(int rate, int channels, int bits) {
        if (track != null && rate == curRate && channels == curCh && bits == curBits) return;
        release();
        curRate = rate; curCh = channels; curBits = bits;
        int chMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int enc = bits == 8 ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;
        int minBuf = AudioTrack.getMinBufferSize(rate, chMask, enc);
        int buf = Math.max(minBuf * 4, rate * 4);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        // 关键：不允许被采集。否则 CarLife 开播放采集(remote-submix)时
                        // 我们自己的输出也会被塞进采集通道 → 声音进了"假车机"里出不来，还自激回环。
                        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(enc).setSampleRate(rate).setChannelMask(chMask).build())
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track = t;
        try { t.play(); } catch (Throwable e) { log("AudioTrack play 失败 " + e); }
        try {
            am = (AudioManager) MainActivity.appCtx.getSystemService(Context.AUDIO_SERVICE);
            watchDevices();
            boundDevId = -1;
            applyRoute("创建音轨");
            int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (vol * 3 < max) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.min(max, Math.max(vol, max / 2)), 0);
                log("媒体音量偏低(" + vol + "/" + max + ")，已抬到 " + am.getStreamVolume(AudioManager.STREAM_MUSIC));
            }
            // 不申请 AUDIOFOCUS_GAIN：会 pause 掉正被 CarLife 采集的音乐 = 自己掐音源
        } catch (Throwable e) { log("音量处理异常 " + e); }
        log("本机音频输出就绪 " + rate + "Hz / " + channels + "ch / " + bits + "bit");
    }

    private static Handler routeHandler;

    private static void watchDevices() {
        if (deviceCb != null || am == null) return;
        try {
            if (routeHandler == null) routeHandler = new Handler(Looper.getMainLooper());
            deviceCb = new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { scheduleRoute("插上了设备"); }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { scheduleRoute("拔掉了设备"); }
            };
            am.registerAudioDeviceCallback(deviceCb, routeHandler);
        } catch (Throwable t) { log("设备监听挂不上 " + t); }
    }

    /** 500ms 去抖：CarWith 开关采集流会让 remote-submix 连增连删十几次，不能每次都真去重绑。 */
    private static void scheduleRoute(final String why) {
        if (routeHandler == null) routeHandler = new Handler(Looper.getMainLooper());
        routeHandler.removeCallbacksAndMessages(null);
        routeHandler.postDelayed(new Runnable() {
            @Override public void run() { applyRoute(why); }
        }, 500);
    }

    private static String deviceName(AudioDeviceInfo di) {
        String p = di.getProductName() == null ? "" : di.getProductName().toString();
        int t = di.getType();
        String kind = t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ? "内置扬声器"
                : t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ? "蓝牙耳机"
                : t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ? "蓝牙(通话)"
                : t == AudioDeviceInfo.TYPE_BLE_HEADSET ? "蓝牙(LE)耳机"
                : t == AudioDeviceInfo.TYPE_BLE_SPEAKER ? "蓝牙(LE)音箱"
                : t == AudioDeviceInfo.TYPE_HEARING_AID ? "助听器"
                : t == AudioDeviceInfo.TYPE_USB_HEADSET ? "USB 耳机"
                : (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) ? "有线耳机"
                : "设备";
        return kind + (p.length() > 0 ? "（" + p + "）" : "");
    }

    /** 只有"选定设备真的变了"才 setPreferredDevice；绝不选 remote-submix(type=25)。 */
    private static synchronized void applyRoute(String why) {
        if (am == null) return;
        try {
            AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            AudioDeviceInfo chosen = null;
            int[] auto = {
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            };
            int[] spk = {AudioDeviceInfo.TYPE_BUILTIN_SPEAKER};
            int[] bt = {
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            };
            int[] order = routePref == 1 ? spk : (routePref == 2 ? bt : auto);
            for (int want : order) {
                for (AudioDeviceInfo di : outs) {
                    if (di.getType() == want) { chosen = di; break; }
                }
                if (chosen != null) break;
            }
            StringBuilder all = new StringBuilder();
            for (AudioDeviceInfo di : outs) {
                if (all.length() > 0) all.append(" / ");
                all.append(di.getType());
            }
            AudioTrack t = track;
            if (chosen == null) {
                log("输出设备清单 type=[" + all + "] → 档位" + prefName() + " 没有匹配设备（" + why + "）");
                return;
            }
            if (t == null) {
                log("输出设备清单 type=[" + all + "] → 选定 " + deviceName(chosen) + "（" + why + "，音轨未建）");
                return;
            }
            if (chosen.getId() == boundDevId) return;   // 已经绑着它，不折腾
            boundDevId = chosen.getId();
            boolean ok = t.setPreferredDevice(chosen);
            log("音频输出 → " + deviceName(chosen) + " type=" + chosen.getType()
                    + "（档位" + prefName() + "，" + why + "）" + (ok ? "" : " 绑定未生效"));
        } catch (Throwable e) { log("音频路由异常 " + e); }
    }

    /** 本机试听：1.5 秒 440Hz 正弦，确认当前路由通路本身能出声（与 CarLife 无关）。 */
    public static synchronized void playTestTone(int ms) {
        try {
            if (track == null) ensure(44100, 2, 16);
            AudioTrack t = track;
            if (t == null) { log("试听失败：音轨为空"); return; }
            int rate = curRate;
            int frames = rate * ms / 1000;
            byte[] pcm = new byte[frames * 4];
            for (int i = 0; i < frames; i++) {
                short v = (short) (Short.MAX_VALUE * 0.25 * Math.sin(2 * Math.PI * 440 * i / (double) rate));
                pcm[i * 4] = (byte) (v & 0xff);
                pcm[i * 4 + 1] = (byte) ((v >> 8) & 0xff);
                pcm[i * 4 + 2] = pcm[i * 4];
                pcm[i * 4 + 3] = pcm[i * 4 + 1];
            }
            t.write(pcm, 0, pcm.length);
            log("试听 440Hz " + ms + "ms 已写入，路由=" + routedNow());
        } catch (Throwable e) { log("试听失败 " + e); }
    }

    public static synchronized String routedNow() {
        AudioTrack t = track;
        if (t == null) return "无 AudioTrack";
        try {
            AudioDeviceInfo di = t.getRoutedDevice();
            return di == null ? "未路由" : (deviceName(di) + " type=" + di.getType());
        } catch (Throwable e) { return "查不到(" + e + ")"; }
    }

    /** 热路径：不加锁、不算设备，纯写。 */
    public static void writePcm(byte[] pcm) {
        AudioTrack t = track;
        if (t == null || pcm == null || pcm.length == 0) {
            if (t == null) ensure(44100, 2, 16);
            return;
        }
        try { t.write(pcm, 0, pcm.length); } catch (Throwable ignored) { }
    }

    public static synchronized void release() {
        try { if (deviceCb != null && am != null) am.unregisterAudioDeviceCallback(deviceCb); } catch (Throwable ignored) { }
        deviceCb = null;
        boundDevId = -1;
        try { if (track != null) { track.stop(); track.release(); } } catch (Throwable t) { }
        track = null;
    }
}
