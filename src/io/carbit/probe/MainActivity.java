package io.carbit.probe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.view.MotionEvent;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements Net.Log {

    static Context appCtx;

    private TextView statusView;
    private TextView runView;
    private TextView readyView;      // 本轮体检：什么时候可以换下一个变体
    private Button nextVarBtn;       // “变体+1”，可换时高亮
    private android.graphics.drawable.Drawable nextVarBtnBg;
    private Runnable roundTick;
    private TextView lastView;
    private TextView logView;
    private TextView infoView;
    private EditText ipField;
    private ImageView previewView;
    private TextureView previewTexture;
    private H264Preview preview;
    private Button menuBtn;
    private LinearLayout panel;
    private Capture capture;
    private FakeHu fakeHu;
    private Rig rig;

    private final StringBuilder logBuffer = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Net.Listener> listeners = new ArrayList<Net.Listener>();
    private volatile boolean busy;

    private static final int RC_P2P_PERM = 900;

    private static final int MAX_LOG_CHARS = 200_000;
    /** 主屏日志保留的行数与分级字号：新→旧 12sp→7sp。 */
    private final java.util.ArrayList<String> logLines = new java.util.ArrayList<>();
    private IconBtn updateBtn;                 // 更新按钮：检查时改成转圈动画
    private IconBtn directBtn;                 // 直连按钮：组网时转圈
    private long updateStartedAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appCtx = getApplicationContext();
        // 每次启动都从 0（PCM 走通道、本机 AudioTrack 出声）开始，不记忆上次的切换，
        // 免得上一轮按成了 1（CarLife 静默）把后面每次上车都带成哑的。
        FakeHu.AUDIO_MODE = 0;
        if (!hadCleanExit()) {
            line("");
            line("⚠ 上次没有正常退出记录（可能被系统直接带走，也可能只是被手动划掉，没有 Java 栈）");
            line("· 断流时只用一张预先编好的「投屏准备中」占位帧反复发，不做持续编码，不占 MediaCodec");
            Rig.allowTestCanvas = true;   // 只为预编一张黑场兜底帧（编一次就缓存），不做持续编码
        }
        try { new java.io.File(getFilesDir(), "clean-exit.flag").delete(); } catch (Throwable ignored) { }
        restoreLastSession();
        installCrashHandler();
        startCrashWatch();
        pickUpWatchdog();
        setContentView(buildUi());
        refreshNetworkInfo();
        try {
            vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) { }
        line("极享 · 车机投屏  v" + vn + " — 手机画面投到摩托仪表，音频留在本机");
        line(TouchBridgeService.ready() ? "· 触摸注入：无障碍已就绪（能点输入法键盘）"
                : ("· 触摸注入：无障碍没开 —— " + accessibilityState()));
        if (!TouchBridgeService.ready()) Toast.makeText(this, "提示：输入法键盘点不动时，用右下角「⌨ 打字」本机打字（不需要无障碍）", Toast.LENGTH_LONG).show();
        line("目标车: VOGE DS625X　modelid " + Proto.DASH_MODEL_ID + "　sn " + Proto.DASH_SN);
        line("流程：先在系统 Wi-Fi 里连上仪表（Wi-Fi Direct 组网或仪表热点）→ 扫描 → 探针 → 监听");
        line(TouchBridgeService.ready()
                ? "✓ 系统级触摸注入已开启（键盘也能点）"
                : "提示：要让投屏里的输入法键盘点得动，去「设置 → 无障碍 → 极享」把开关打开（不开只能点 CarWith 自己窗口里的内容）");

        roundTick = new Runnable() {
            @Override public void run() {
                updateRound();
                ui.postDelayed(this, 1000);
            }
        };
        ui.post(roundTick);
        ui.postDelayed(new Runnable() { @Override public void run() { watchdogTick(); } }, 3000);

        // 手动锁屏 / 息屏会让 CarWith 的 MediaProjection 结束、10920/10921 全断，
        // 以前只能手动重来。这里监听亮屏/解锁，只要之前是在投屏，就自动重连一次。
        screenRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent it) {
                if (it == null) return;
                String a = it.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    // 锁屏后没人看手机了，预览解码器没必要留着占 CPU/电
                    if (previewOn) { try { stopPreviewNow("锁屏了，预览已自动关闭（省电省 CPU）"); } catch (Throwable ignored) { } }
                    return;
                }
                if (!(Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_USER_PRESENT.equals(a))) return;
                if (!wasStreaming) return;
                line("√ 亮屏了：上次投屏被锁屏打断，等解锁后自动重连");
                resumeWhenUnlocked(0);
            }
        };
        IntentFilter sif = new IntentFilter();
        sif.addAction(Intent.ACTION_SCREEN_OFF);
        sif.addAction(Intent.ACTION_SCREEN_ON);
        sif.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenRx, sif);
        try {
            android.content.IntentFilter axf = new android.content.IntentFilter("io.carbit.probe.AXDUMP");
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(axRx, axf, 2);
            else registerReceiver(axRx, axf);
        } catch (Throwable ignored) { }

        // adb 触发入口（MIUI 不给 shell 注入点击权限，只能这样跑验证）
        String mode = null;
        try { mode = getIntent().getStringExtra("mode"); } catch (Throwable ignored) { }
        if ("iccoa".equals(mode)) {
            line("[adb 触发] mode=iccoa");
            ui.postDelayed(new Runnable() { @Override public void run() { doIccoa(); } }, 500);
        }
        if ("rig".equals(mode)) { ui.postDelayed(new Runnable() { @Override public void run() { doRig(); } }, 300); }
        if ("fakehu".equals(mode)) {
            line("[adb 触发] mode=fakehu —— 假车机");
            ui.postDelayed(new Runnable() { @Override public void run() { doFakeHu(); } }, 500);
        }
        if ("fakehu".equals(mode)) {
            final String tap = getIntent().getStringExtra("tap");
            if (tap != null && tap.contains(",")) {
                ui.postDelayed(new Runnable() { @Override public void run() {
                    if (fakeHu == null) return;
                    String[] p = tap.split(",");
                    int tx = Integer.parseInt(p[0].trim()), ty = Integer.parseInt(p[1].trim());
                    fakeHu.sendTouch(FakeHu.TOUCH_DOWN, tx, ty);
                    ui.postDelayed(new Runnable() { @Override public void run() {
                        fakeHu.sendTouch(FakeHu.TOUCH_UP, tx, ty);
                        line("[自测] 已向车机发送点击 " + tx + "," + ty);
                    } }, 120);
                } }, 14000);
            }
        }
        if ("axdump".equals(mode)) {
            line("[adb 触发] mode=axdump —— 假车机 + 无障碍窗口树 dump");
            ui.postDelayed(new Runnable() { @Override public void run() { doFakeHu(); } }, 500);
            int[] ds = {22000, 34000, 46000, 58000};
            for (final int dd : ds) ui.postDelayed(new Runnable() { @Override public void run() { TouchBridgeService.dumpWindows(); } }, dd);
        }
        if ("tone".equals(mode)) {
            ui.postDelayed(new Runnable() { @Override public void run() { MediaAudio.playTestTone(1500); } }, 400);
        }
        if ("scan".equals(mode)) {
            ui.postDelayed(new Runnable() { @Override public void run() { startActivityForResult(new android.content.Intent(MainActivity.this, ScanActivity.class), 11); } }, 700);
        }
        if ("oneclick".equals(mode)) {
            ui.postDelayed(new Runnable() { @Override public void run() { doOneClick(); } }, 800);
        }
        if ("mic".equals(mode)) {
            ui.postDelayed(new Runnable() { @Override public void run() { AudioSelfTest.run(3); } }, 800);
        }
        if ("crashdemo".equals(mode)) {
            ui.postDelayed(new Runnable() { @Override public void run() { writeFakeWatchdog(); } }, 600);
        }
        if ("panel".equals(mode)) {
            ui.postDelayed(new Runnable() { @Override public void run() { if (menuBtn != null) menuBtn.performClick(); } }, 600);
        }
    }


    /** 宽撑满、高按给定比例算的容器：画面等比，不拉伸。 */
    static class AspectBox extends FrameLayout {
        private final float ratioW, ratioH;
        AspectBox(Context c, float w, float h) {
            super(c);
            ratioW = w; ratioH = h;
            setClipToPadding(false);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
            int h = (int) ((w * ratioH) / ratioW);
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }

    /** 统一圆角背景：色块 + 可选 1dp 描边，给卡片/胶囊用。 */
    private android.graphics.drawable.GradientDrawable roundBg(int color, int radiusDp, int strokeColor) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private View buildUi() {
        FrameLayout page = new FrameLayout(this);
        // 参考常见深色 App：不是纯黑，而是上浅下深的深蓝灰渐变 + 卡片层次
        android.graphics.drawable.GradientDrawable pageBg = new android.graphics.drawable.GradientDrawable();
        pageBg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
        pageBg.setColors(new int[]{0xFF151C26, 0xFF0B0F14});
        page.setBackground(pageBg);

        LinearLayout base = new LinearLayout(this);
        base.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        base.setPadding(pad, pad, pad, pad);
        page.addView(base, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── 顶部不再放标题和菜单按钮：全部操作统一收到最下面一排 ──
        menuBtn = new Button(this);
        menuBtn.setVisibility(View.GONE);

        // 运行状态条：不用进菜单就能看到当前在干什么
        runView = new TextView(this);
        runView.setText("空闲");
        runView.setTextColor(Color.parseColor("#9AA4B2"));
        runView.setTextSize(11);
        runView.setTypeface(Typeface.DEFAULT_BOLD);
        runView.setBackground(roundBg(0xFF1B2430, 10, 0));
        runView.setPadding(dp(6), dp(4), dp(6), dp(4));
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rvLp.topMargin = dp(6);
        runView.setVisibility(View.GONE);

        readyView = new TextView(this);
        readyView.setText("状态：还没开始，点“开始投屏”");
        readyView.setTextColor(0xFF9AA4B2);
        readyView.setTextSize(11);
        readyView.setTypeface(Typeface.DEFAULT_BOLD);
        readyView.setBackgroundColor(0xFF1B2430);
        readyView.setPadding(dp(6), dp(5), dp(6), dp(5));
        LinearLayout.LayoutParams rdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rdLp.topMargin = dp(4);
        readyView.setVisibility(View.GONE);

        // ── 顶部卡片：参照 CarWithin 的 hero card（图标 + 标题 + 状态胶囊 + 主按钮）──
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackground(roundBg(0xFF121A23, 20, 0xFF22303D));
        hero.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout heroTop = new LinearLayout(this);
        heroTop.setOrientation(LinearLayout.HORIZONTAL);
        heroTop.setGravity(Gravity.CENTER_VERTICAL);

        TextView heroIcon = new TextView(this);
        heroIcon.setText("极");
        heroIcon.setTextColor(0xFFFFFFFF);
        heroIcon.setTextSize(20);
        heroIcon.setTypeface(Typeface.DEFAULT_BOLD);
        heroIcon.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable iconBg = new android.graphics.drawable.GradientDrawable();
        iconBg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        iconBg.setColors(new int[]{0xFF2E8CFF, 0xFF1B5FD6});
        iconBg.setCornerRadius(dp(13));
        heroIcon.setBackground(iconBg);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(44), dp(44));
        ilp.rightMargin = dp(12);
        heroTop.addView(heroIcon, ilp);

        LinearLayout heroTitleCol = new LinearLayout(this);
        heroTitleCol.setOrientation(LinearLayout.VERTICAL);
        TextView heroTitle = new TextView(this);
        heroTitle.setText("极享");
        heroTitle.setTextColor(0xFFF2F6FA);
        heroTitle.setTextSize(20);
        heroTitle.setTypeface(Typeface.DEFAULT_BOLD);
        TextView heroSub = new TextView(this);
        heroSub.setText("车机投屏 · 手机画面 → 摩托仪表");
        heroSub.setTextColor(0xFF8C97A6);
        heroSub.setTextSize(11);
        heroTitleCol.addView(heroTitle);
        heroTitleCol.addView(heroSub);
        heroTop.addView(heroTitleCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        heroPill = new TextView(this);
        heroPill.setText("● 空闲");
        heroPill.setTextColor(0xFFCFE7FF);
        heroPill.setTextSize(11);
        heroPill.setTypeface(Typeface.DEFAULT_BOLD);
        heroPill.setPadding(dp(9), dp(5), dp(9), dp(5));
        heroPill.setBackground(roundBg(0x264C8DFF, 11, 0));
        heroTop.addView(heroPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hero.addView(heroTop);

        // 主按钮：CarWithin 那种 filled 大按钮，只是换成蓝色渐变（深色主题下不刺眼）
        heroBtn = new TextView(this);
        heroBtn.setText("开始投屏");
        heroBtn.setTextColor(0xFFFFFFFF);
        heroBtn.setTextSize(16);
        heroBtn.setTypeface(Typeface.DEFAULT_BOLD);
        heroBtn.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT);
        btnBg.setColors(new int[]{0xFF2E8CFF, 0xFF0A52DB});
        btnBg.setCornerRadius(dp(14));
        heroBtn.setBackground(btnBg);
        heroBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (rig != null && rig.isRunning()) doStopAll(); else doStartAll();
            }
        });
        // 主按钮不再占 hero 卡——挪到最底下那条空带里：不挡内容，也不用再为它留 2cm


        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.topMargin = dp(2);
        heroLp.bottomMargin = dp(4);
        base.addView(hero, heroLp);

        // ── 黑框预览：宽撑满屏幕，高按 CarWith 实际出流比例 1024:608 等比算，不拉伸 ──
        AspectBox pvWrap = new AspectBox(this, 1024f, 608f);
        pvWrap.setBackground(roundBg(0xFF0A0E13, 16, 0xFF243040));
        pvWrap.setClipToOutline(true);
        pvWrap.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16));
            }
        });
        previewView = new ImageView(this);
        previewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pvWrap.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewTexture = new TextureView(this);
        previewTexture.setOpaque(false);
        pvWrap.addView(previewTexture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewOffTip = new TextView(this);
        previewOffTip.setText("预览已关闭\n（省掉一路 MediaCodec，省电也省内存）");
        previewOffTip.setTextColor(0xFF7E8A98);
        previewOffTip.setTextSize(12);
        previewOffTip.setGravity(Gravity.CENTER);
        previewOffTip.setBackground(roundBg(0xFF0E1319, 0, 0));
        previewOffTip.setVisibility(View.GONE);
        pvWrap.addView(previewOffTip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 打字小胶囊：不点投屏里的键盘，直接在本机打字、把文本推进 CarWith 的输入框（不需要无障碍）
        pvInputChip = new TextView(this);
        pvInputChip.setText("⌨ 打字");
        pvInputChip.setTextSize(11);
        pvInputChip.setTextColor(0xFFCFE7FF);
        pvInputChip.setPadding(dp(9), dp(5), dp(9), dp(5));
        pvInputChip.setBackground(roundBg(0xCC16212E, 12, 0xFF3A4E63));
        pvInputChip.setVisibility(View.GONE);
        pvInputChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openTypeDialog(); }
        });
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.gravity = Gravity.END | Gravity.BOTTOM;
        chipLp.rightMargin = dp(8);
        chipLp.bottomMargin = dp(8);
        pvWrap.addView(pvInputChip, chipLp);

        TextView pvToneChip = new TextView(this);
        pvToneChip.setText("♪试听");
        pvToneChip.setTextSize(11);
        pvToneChip.setTextColor(0xFFCFE7FF);
        pvToneChip.setPadding(dp(9), dp(5), dp(9), dp(5));
        pvToneChip.setBackground(roundBg(0xCC16212E, 12, 0xFF3A4E63));
        pvToneChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                line("· 试听：往当前输出设备打 1.5 秒 440Hz（和设备/CarWith 无关）");
                line("· 试听时设备状态 " + MediaAudio.status());
                new Thread(new Runnable() { @Override public void run() { MediaAudio.playTestTone(1500); } }, "tone").start();
                ui.postDelayed(new Runnable() { @Override public void run() {
                    line("· 试听后设备状态 " + MediaAudio.status());
                } }, 1900);
            }
        });
        FrameLayout.LayoutParams toneLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toneLp.gravity = Gravity.END | Gravity.BOTTOM;
        toneLp.rightMargin = dp(160);
        toneLp.bottomMargin = dp(8);
        pvWrap.addView(pvToneChip, toneLp);
        pvToneChipRef = pvToneChip;

        // 输出档位：自动 / 本机扬声器 / 蓝牙 —— 蓝牙耳机到底有没有出声，用这个当场切
        pvOutChip = new TextView(this);
        pvOutChip.setText("输出:" + MediaAudio.prefName());
        pvOutChip.setTextSize(11);
        pvOutChip.setTypeface(Typeface.DEFAULT_BOLD);
        pvOutChip.setTextColor(0xFFCFE7FF);
        pvOutChip.setPadding(dp(9), dp(5), dp(9), dp(5));
        pvOutChip.setBackground(roundBg(0xCC16212E, 12, 0xFF3A4E63));
        pvOutChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String n = MediaAudio.cyclePref();
                pvOutChip.setText("输出:" + n);
                line("· 音频输出档位 → " + n + "，实际路由=" + MediaAudio.routedNow());
            }
        });
        FrameLayout.LayoutParams outLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        outLp.gravity = Gravity.END | Gravity.BOTTOM;
        outLp.rightMargin = dp(224);
        outLp.bottomMargin = dp(8);
        pvWrap.addView(pvOutChip, outLp);

        pvSpaceChip = new TextView(this);
        pvSpaceChip.setText("坐标:" + SPACE_NAMES[touchSpace]);
        pvSpaceChip.setTextSize(11);
        pvSpaceChip.setTextColor(0xFFCFE7FF);
        pvSpaceChip.setPadding(dp(9), dp(5), dp(9), dp(5));
        pvSpaceChip.setBackground(roundBg(0xCC16212E, 12, 0xFF3A4E63));
        pvSpaceChip.setVisibility(View.GONE);
        pvSpaceChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                touchSpace = (touchSpace + 1) % SPACE_NAMES.length;
                pvSpaceChip.setText("坐标:" + SPACE_NAMES[touchSpace]);
                line("· 触控坐标空间 → " + SPACE_NAMES[touchSpace]
                        + "（屏幕 " + screenW() + "x" + screenH() + " / 画面 " + FakeHu.VIDEO_W + "x" + FakeHu.VIDEO_H + "）");
            }
        });
        FrameLayout.LayoutParams spaceLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spaceLp.gravity = Gravity.END | Gravity.BOTTOM;
        spaceLp.rightMargin = dp(288);
        spaceLp.bottomMargin = dp(8);
        pvWrap.addView(pvSpaceChip, spaceLp);

        pvTapChip = new TextView(this);
        pvTapChip.setText("触摸:正常");
        pvTapChip.setTextSize(11);
        pvTapChip.setTypeface(Typeface.DEFAULT_BOLD);
        pvTapChip.setTextColor(0xFFCFE7FF);
        pvTapChip.setPadding(dp(9), dp(5), dp(9), dp(5));
        pvTapChip.setBackground(roundBg(0xCC16212E, 12, 0xFF3A4E63));
        pvTapChip.setVisibility(View.GONE);
        pvTapChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cycleTouchMode(); }
        });
        FrameLayout.LayoutParams tapLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tapLp.gravity = Gravity.END | Gravity.BOTTOM;
        tapLp.rightMargin = dp(96);
        tapLp.bottomMargin = dp(8);
        pvWrap.addView(pvTapChip, tapLp);

        pvModeBadge = new TextView(this);
        pvModeBadge.setText("· 只发按下 ·");
        pvModeBadge.setTextColor(0xFFFFD60A);
        pvModeBadge.setTextSize(10);
        pvModeBadge.setTypeface(Typeface.DEFAULT_BOLD);
        pvModeBadge.setPadding(dp(6), dp(3), dp(6), dp(3));
        pvModeBadge.setBackground(roundBg(0xB3000000, 8, 0xFFFFD60A));
        pvModeBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = dp(6);
        badgeLp.topMargin = dp(6);
        pvWrap.addView(pvModeBadge, badgeLp);

        pvDot = new View(this);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(0x00000000);
        dotBg.setStroke(dp(2), 0xFFFF3B30);
        pvDot.setBackground(dotBg);
        pvDot.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(14), dp(14));
        pvWrap.addView(pvDot, dotLp);
        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvLp.topMargin = dp(6);
        pvLp.bottomMargin = dp(6);
        // 预览即车机屏：手指在预览上滑动 = 给车机发触控（仪表本身没有触屏）
        pvWrap.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (fakeHu == null || !fakeHu.isRunning()) return false;
                int[] pt = FakeHu.mapToVideo(e.getX(), e.getY(),
                        v.getWidth(), v.getHeight(), FakeHu.VIDEO_W, FakeHu.VIDEO_H);
                // 无障碍已开启 → 走系统级注入（能打进系统输入法窗口，键盘字母才点得动）；
                // 没开 → 退回老路（CarLife 触控通道，只能打进 CarWith 自己的窗口）。
                if (TouchBridgeService.ready()) {
                    float sx = pt[0] * screenW() / (float) FakeHu.VIDEO_W;
                    float sy = pt[1] * screenH() / (float) FakeHu.VIDEO_H;
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            // 输入法窗口在真实窗口层里 → 直接点它的按键节点（比手势注入可靠，不会被 cancelled）
                            if (TouchBridgeService.clickIme(sx, sy)) return true;
                            if (System.currentTimeMillis() - lastImeMissAt > 3000) {
                                lastImeMissAt = System.currentTimeMillis();
                                line("· 键盘直点未命中（输入法窗口=" + (TouchBridgeService.imeSeen() ? "在" : "不在")
                                        + "）→ 这一次走手势/CarLife，点击会落到 CarWith 自己那层");
                            }
                            TouchBridgeService.down(sx, sy);
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            TouchBridgeService.move(sx, sy);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            TouchBridgeService.up(sx, sy);
                            if (!TouchBridgeService.lastOk()) {
                                // 系统注入被拒（MIUI 常见）→ 这一下退回 CarLife 触控通道，保证还能点
                                final int lx = pt[0], ly = pt[1];
                                fakeHu.sendTouch(FakeHu.TOUCH_DOWN, lx, ly);
                                ui.postDelayed(new Runnable() { @Override public void run() {
                                    fakeHu.sendTouch(FakeHu.TOUCH_UP, lx, ly);
                                } }, 130);
                            }
                            if (System.currentTimeMillis() - lastTouchLogAt > 3000) {
                                lastTouchLogAt = System.currentTimeMillis();
                                line("注入屏幕 (" + (int) sx + "," + (int) sy + ") ← 画面 (" + pt[0] + "," + pt[1]
                                        + ")  屏 " + screenW() + "x" + screenH()
                                        + (TouchBridgeService.lastOk() ? " 已送达"
                                        : (" 被拒绝(返回=" + TouchBridgeService.lastDispatchReturn
                                            + (TouchBridgeService.lastDispatchError == null ? "" : " " + TouchBridgeService.lastDispatchError) + ")")));
                            }
                            return true;
                    }
                    return false;
                }
                markTouch(pt[0], pt[1]);
                long nowMs = System.currentTimeMillis();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // 先不发：若是"长按切模式"，发了 DOWN 会漏一个按下到画面里，把键盘点掉
                        touchDownAt = nowMs;
                        dragging = false;
                        dragFromX = pt[0];
                        dragFromY = pt[1];
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        // 位移超 14px 才算拖动：这时补发 DOWN(起点)，之后每 20ms 一帧带真实起点的 MOVE
                        if (!dragging && (Math.abs(pt[0] - dragFromX) > 14 || Math.abs(pt[1] - dragFromY) > 14)) {
                            dragging = true;
                            dragLastSendAt = nowMs;
                            fakeHu.sendTouch(FakeHu.TOUCH_DOWN, cx((int) dragFromX), cy((int) dragFromY));
                            line("· 开始拖动 起点(" + (int) dragFromX + "," + (int) dragFromY + ")");
                        }
                        if (dragging) {
                            if (nowMs - dragLastSendAt >= 20) {
                                dragLastSendAt = nowMs;
                                fakeHu.sendDragMove(cx((int) dragFromX), cy((int) dragFromY), cx(pt[0]), cy(pt[1]));
                            }
                            return true;
                        }
                        fakeHu.sendTouch(FakeHu.TOUCH_MOVE, pt[0], pt[1]);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // 拖动收尾：发最后一帧 MOVE + UP(终点)，然后不再走"点击"逻辑，否则会多点一下
                        if (dragging) {
                            dragging = false;
                            fakeHu.sendDragMove(cx((int) dragFromX), cy((int) dragFromY), cx(pt[0]), cy(pt[1]));
                            fakeHu.sendTouch(FakeHu.TOUCH_UP, cx(pt[0]), cy(pt[1]));
                            line("· 拖动结束 终点(" + pt[0] + "," + pt[1] + ")");
                            return true;
                        }
                        // 长按 1.5 秒 = 切换"只发按下"模式：用来判断键盘是被"抬指"收起的，还是根本没收到我们
                        if (nowMs - touchDownAt > 1500) {
                            cycleTouchMode();
                            return true;
                        }
                        // 坐标空间：画面=1024x608（CarWith 发过来的视频坐标）；屏幕=手机真实分辨率
                        final int ux = touchSpace == 0 ? pt[0] : (int) (pt[0] * screenW() / (float) FakeHu.VIDEO_W);
                        final int uy = touchSpace == 0 ? pt[1] : (int) (pt[1] * screenH() / (float) FakeHu.VIDEO_H);
                        final FakeHu fh = fakeHu;
                        if (fh == null) return true;
                        if (System.currentTimeMillis() - lastTouchLogAt > 1000) {
                            lastTouchLogAt = System.currentTimeMillis();
                            line("· 触控(CarLife通道) 点 画面(" + ux + "," + uy + ") 屏 "
                                    + v.getWidth() + "x" + v.getHeight() + " → " + FakeHu.VIDEO_W + "x" + FakeHu.VIDEO_H
                                    + "  [" + TOUCH_NAMES[touchMode] + " / 坐标" + SPACE_NAMES[touchSpace] + "]");
                        }
                        fh.sendTouch(FakeHu.TOUCH_DOWN, ux, uy);
                        if (touchMode == 3) { line("· 仅按下：没发抬起（画面 " + ux + "," + uy + "）"); return true; }
                        if (touchMode == 1) {
                            ui.postDelayed(new Runnable() { @Override public void run() {
                                if (fakeHu != null) fakeHu.sendTouch(FakeHu.TOUCH_UP, ux, uy);
                            } }, 8);
                        } else if (touchMode == 2) {
                            ui.postDelayed(new Runnable() { @Override public void run() {
                                if (fakeHu != null) fakeHu.sendTouch(FakeHu.TOUCH_UP, ux, uy);
                            } }, 150);
                        } else {
                            ui.postDelayed(new Runnable() { @Override public void run() {
                                if (fakeHu != null) fakeHu.sendTouch(FakeHu.TOUCH_MOVE, ux, uy);
                            } }, 40);
                            ui.postDelayed(new Runnable() { @Override public void run() {
                                if (fakeHu != null) fakeHu.sendTouch(FakeHu.TOUCH_UP, ux, uy);
                            } }, 90);
                        }
                        return true;
                }
                return false;
            }
        });

        base.addView(pvWrap, pvLp);

        // ── 功能胶囊挪到预览框外：一行五个，宽度均分，预览框里只留落点圈 ──
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams chipRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipRowLp.bottomMargin = dp(6);
        base.addView(chipRow, chipRowLp);
        if (pvInputChip != null && pvInputChip.getParent() != null)
            ((ViewGroup) pvInputChip.getParent()).removeView(pvInputChip);
        if (pvTapChip != null && pvTapChip.getParent() != null)
            ((ViewGroup) pvTapChip.getParent()).removeView(pvTapChip);
        if (pvToneChipRef != null && pvToneChipRef.getParent() != null)
            ((ViewGroup) pvToneChipRef.getParent()).removeView(pvToneChipRef);
        if (pvOutChip != null && pvOutChip.getParent() != null)
            ((ViewGroup) pvOutChip.getParent()).removeView(pvOutChip);
        if (pvSpaceChip != null && pvSpaceChip.getParent() != null)
            ((ViewGroup) pvSpaceChip.getParent()).removeView(pvSpaceChip);
        TextView[] chips = { pvInputChip, pvTapChip, pvToneChipRef, pvOutChip, pvSpaceChip };
        for (TextView c : chips) {
            if (c == null) continue;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            clp.rightMargin = dp(4);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, 0, 0, 0);
            c.setTextSize(11);
            c.setSingleLine(true);
            c.setVisibility(View.VISIBLE);
            chipRow.addView(c, clp);
        }

        // ── 主屏底部常显日志：不用点进菜单也能看到跑成什么样 ──
        logView = new TextView(this);
        logView.setTextColor(Color.parseColor("#D7DEE8"));
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(false);   // 可选中会多一遍排版并长按弹菜单，日志区更爱跳
        logView.setPadding(dp(10), dp(8), dp(10), dp(8));
        logView.setBackground(roundBg(0xFF121A23, 16, 0xFF22303D));
        ScrollView mainLogScroll = new ScrollView(this);
        mainLogScroll.addView(logView);
        LinearLayout.LayoutParams mlsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mlsLp.topMargin = dp(4);
        base.addView(mainLogScroll, mlsLp);

        // ── 底部操作区：两排六列，按使用频次从高到低排，整屏不用滑 ──
        // 两排按钮。常用两个（开始/停止）给更大权重和字号；最下一排整体抬高，
        // 让整套按钮离屏幕底边约 2cm，手机支架上的手指够得到。
        int[] r1Ico = {IconBtn.PREVIEW, IconBtn.LINK, IconBtn.SCAN, IconBtn.RADAR};
        String[] r1Txt = {"预览", "直连投屏", "扫描", "探测"};
        float[] r1W = {1f, 1.15f, 0.95f, 0.95f};
        View.OnClickListener[] r1Act = {
                new View.OnClickListener() { @Override public void onClick(View v) { togglePreview(); } },
                new View.OnClickListener() { @Override public void onClick(View v) { doDirect(); } },
                new View.OnClickListener() { @Override public void onClick(View v) { scanQr(); } },
                new View.OnClickListener() { @Override public void onClick(View v) { doOneClick(); } }
        };
        int[] r2Ico = {IconBtn.UPLOAD, IconBtn.FILE, IconBtn.LOOP, IconBtn.GEAR};
        String[] r2Txt = {"推日志", "导出", "更新", "NAS"};
        View.OnClickListener[] r2Act = {
                new View.OnClickListener() { @Override public void onClick(View v) { uploadLogToNas(); } },
                new View.OnClickListener() { @Override public void onClick(View v) { exportLog(); } },
                new View.OnClickListener() { @Override public void onClick(View v) { doUpdate(); } },
                new View.OnClickListener() { @Override public void onClick(View v) { nasSettings(); } }
        };

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        for (int c = 0; c < r1Ico.length; c++) {
            IconBtn b = new IconBtn(this, r1Ico[c], r1Txt[c], 22, 9f);
            if (c == 0) { previewBtn = b; b.setActive(previewOn); }
            if (c == 1) directBtn = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), r1W[c]);
            if (c > 0) lp.leftMargin = dp(5);
            row1.addView(b, lp);
            b.setOnClickListener(r1Act[c]);
        }
        LinearLayout.LayoutParams r1lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1lp.topMargin = dp(4);
        base.addView(row1, r1lp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        for (int c = 0; c < r2Ico.length; c++) {
            IconBtn b = new IconBtn(this, r2Ico[c], r2Txt[c], 17, 8.5f);
            if (c == 2) updateBtn = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (c > 0) lp.leftMargin = dp(5);
            row2.addView(b, lp);
            b.setOnClickListener(r2Act[c]);
        }
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2lp.topMargin = dp(4);
        r2lp.bottomMargin = dp(6);
        base.addView(row2, r2lp);

        // ── 底部主按钮：占掉原来那条 2cm 空带，做大做满，支架挡一点也按得到 ──
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        hbLp.topMargin = dp(10);
        base.addView(heroBtn, hbLp);

        // ── 二级菜单：全屏覆盖层，默认隐藏 ──
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackgroundColor(Color.parseColor("#101216"));

        LinearLayout panelHead = new LinearLayout(this);
        panelHead.setOrientation(LinearLayout.HORIZONTAL);
        panelHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView panelTitle = new TextView(this);
        panelTitle.setText("设置 / 诊断");
        panelTitle.setTextColor(Color.WHITE);
        panelTitle.setTextSize(15);
        panelTitle.setTypeface(Typeface.DEFAULT_BOLD);
        panelHead.addView(panelTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button closeBtn = new Button(this);
        closeBtn.setText("返回");
        closeBtn.setAllCaps(false);
        closeBtn.setTextSize(11);
        closeBtn.setPadding(0, 0, 0, 0);
        closeBtn.setMinHeight(0);
        closeBtn.setMinimumHeight(0);
        panelHead.addView(closeBtn, new LinearLayout.LayoutParams(dp(56), dp(28)));
        panel.addView(panelHead);

        statusView = new TextView(this);
        statusView.setTextColor(Color.parseColor("#9AA4B2"));
        statusView.setTextSize(12);
        statusView.setPadding(0, dp(10), 0, dp(4));
        panel.addView(statusView);

        infoView = new TextView(this);
        infoView.setText(dashInfoText(Proto.DASH_SSID, Proto.DASH_PWD,
                Proto.DASH_MODEL_ID, Proto.DASH_SN, Proto.DASH_UUID));
        infoView.setTextColor(Color.parseColor("#7FD1AE"));
        infoView.setTextSize(12);
        infoView.setTypeface(Typeface.MONOSPACE);
        infoView.setBackgroundColor(Color.parseColor("#14181D"));
        infoView.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ivLp.topMargin = dp(6);
        panel.addView(infoView, ivLp);

        LinearLayout ipRow = new LinearLayout(this);
        ipRow.setOrientation(LinearLayout.HORIZONTAL);
        ipRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView ipLabel = new TextView(this);
        ipLabel.setText("仪表 IP ");
        ipLabel.setTextColor(Color.parseColor("#C7D0DB"));
        ipLabel.setTextSize(14);
        ipRow.addView(ipLabel);
        ipField = new EditText(this);
        ipField.setHint("192.168.49.1");
        ipField.setTextColor(Color.WHITE);
        ipField.setHintTextColor(Color.parseColor("#5A6472"));
        ipField.setTextSize(14);
        ipField.setInputType(InputType.TYPE_CLASS_TEXT);
        ipField.setSingleLine(true);
        ipField.setBackgroundColor(Color.parseColor("#1B2430"));
        ipField.setPadding(dp(8), dp(6), dp(8), dp(6));
        ipRow.addView(ipField, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(ipRow);

        panel.addView(iconRow(new int[]{IconBtn.LOOP, IconBtn.GEAR},
                new String[]{"检查更新", "NAS 设置"},
                new View.OnClickListener[]{
                        new View.OnClickListener() { @Override public void onClick(View v) { doUpdate(); } },
                        new View.OnClickListener() { @Override public void onClick(View v) { nasSettings(); } }
                }));

        lastView = new TextView(this);
        lastView.setText("上次操作结果会显示在这里");
        lastView.setTextColor(Color.parseColor("#9AA4B2"));
        lastView.setTextSize(12);
        lastView.setTypeface(Typeface.DEFAULT_BOLD);
        lastView.setBackgroundColor(Color.parseColor("#1B2430"));
        lastView.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lvLp.topMargin = dp(6);
        panel.addView(lastView, lvLp);

        ScrollView panelScroll = new ScrollView(this);
        panelScroll.setBackgroundColor(Color.parseColor("#101216"));
        panelScroll.addView(panel);
        panelScroll.setVisibility(View.GONE);
        // 菜单已取消：panel 只保留字段（ipField 等）供逻辑使用，不再挂到界面上
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { panelScroll.setVisibility(View.GONE); }
        });

        return page;
    }

    /** 每秒刷新“本轮体检”一行：告诉用户什么时候可以点下一个变体。 */
    private void updateRound() {
        if (readyView == null) return;
        long start = Rig.roundStartAt;
        if (start == 0) {
            readyView.setText("状态：还没开始，点“开始投屏”");
            readyView.setTextColor(0xFF9AA4B2);
            readyView.setBackgroundColor(0xFF1B2430);
            if (nextVarBtn != null) { nextVarBtn.setText("变体+1"); nextVarBtn.setBackground(nextVarBtnBg); }
            return;
        }
        int secs = (int) ((System.currentTimeMillis() - start) / 1000);
        String head = "第" + Rig.roundNo + "轮 · " + Rig.variantName(Rig.variant);
        if (Rig.roundSuccess) {
            readyView.setText("★ " + head + "：有反应了（MIRROR_START / 0x40000）已跑 " + secs
                    + "s → 先别换，等它连 10710/10711");
            readyView.setTextColor(0xFFFFC04D);
            readyView.setBackgroundColor(0xFF2A2415);
            if (nextVarBtn != null) { nextVarBtn.setText("变体+1"); nextVarBtn.setBackground(nextVarBtnBg); }
        } else if (Rig.roundAuthFails > 0) {
            readyView.setText("✔ " + head + " 已判死：AUTH_FAIL×" + Rig.roundAuthFails + "（" + secs
                    + "s）→ 可以点“变体+1”，再点“换一轮”");
            readyView.setTextColor(0xFF57C88A);
            readyView.setBackgroundColor(0xFF14301F);
            if (nextVarBtn != null) { nextVarBtn.setText("变体+1 ▶"); nextVarBtn.setBackgroundColor(0xFF57C88A); }
        } else if (Rig.roundSessions >= 3) {
            readyView.setText("✔ " + head + " 已重连 " + Rig.roundSessions + " 次、每次只握手不说话（" + secs
                    + "s）→ 判死，点“变体+1”，再点“换一轮”");
            readyView.setTextColor(0xFF57C88A);
            readyView.setBackgroundColor(0xFF14301F);
            if (nextVarBtn != null) { nextVarBtn.setText("变体+1 ▶"); nextVarBtn.setBackgroundColor(0xFF57C88A); }
        } else if (secs >= 40) {
            readyView.setText("✔ " + head + " 跑满 " + secs + "s 无成功迹象 → 可以点“变体+1”，再点“换一轮”");
            readyView.setTextColor(0xFF57C88A);
            readyView.setBackgroundColor(0xFF14301F);
            if (nextVarBtn != null) { nextVarBtn.setText("变体+1 ▶"); nextVarBtn.setBackgroundColor(0xFF57C88A); }
        } else {
            readyView.setText("● " + head + " 已跑 " + secs + "s／" + 40 + "s（满 40s 或收到 AUTH_FAIL 才提示换）");
            readyView.setTextColor(0xFF9AA4B2);
            readyView.setBackgroundColor(0xFF1B2430);
            if (nextVarBtn != null) { nextVarBtn.setText("变体+1"); nextVarBtn.setBackground(nextVarBtnBg); }
        }
    }

    private void pickVariant(int v) {
        Rig.autoRotate = false;
        Rig.variant = v;
        line("当前变体 → " + Rig.variantName(v));
    }

    private LinearLayout iconRow(int[] icons, String[] labels, View.OnClickListener[] handlers) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(6);
        row.setLayoutParams(rowLp);
        for (int i = 0; i < labels.length; i++) {
            IconBtn b = new IconBtn(this, icons[i], labels[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            if (i > 0) lp.leftMargin = dp(5);
            row.addView(b, lp);
            b.setOnClickListener(handlers[i]);
        }
        return row;
    }

    private LinearLayout buttonRow(String[] labels, View.OnClickListener[] handlers) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        row.setLayoutParams(rowLp);
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setAllCaps(false);
            b.setTextSize(11);
            b.setPadding(0, 0, 0, 0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1f);
            if (i > 0) lp.leftMargin = dp(6);
            row.addView(b, lp);
            b.setOnClickListener(handlers[i]);
        }
        return row;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------- 网络信息 ----------------

    private void refreshNetworkInfo() {
        StringBuilder sb = new StringBuilder();
        List<String> ips = Net.localIpv4();
        sb.append("本机地址: ").append(ips.isEmpty() ? "（无）" : join(ips));
        String gw = gateway();
        sb.append("　网关: ").append(gw == null ? "（取不到）" : gw);
        statusView.setText(sb.toString());
        if (ipField.getText().toString().trim().isEmpty() && gw != null) {
            ipField.setText(gw);
        }
        line("本机 IPv4: " + (ips.isEmpty() ? "（无）" : join(ips)) + "，网关: " + gw);
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private String gateway() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            // 连仪表时蜂窝只会误导：优先 Wi-Fi，其次以太网，蜂窝垫底
            String wifi = null, eth = null, any = null;
            for (Network n : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                String nic = lp.getInterfaceName();
                if (nic != null && (nic.startsWith("rmnet") || nic.startsWith("ccmni"))) continue;
                String v4 = null, v6 = null;
                for (RouteInfo r : lp.getRoutes()) {
                    if (!r.isDefaultRoute() || r.getGateway() == null) continue;
                    String a = r.getGateway().getHostAddress();
                    if (r.getGateway() instanceof java.net.Inet4Address) { v4 = a; break; }
                    if (v6 == null) v6 = a;
                }
                String cand = v4 != null ? v4 : v6;
                if (cand == null) continue;
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = cand;
                else if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) eth = cand;
                else if (any == null) any = cand;
            }
            if (wifi != null) return wifi;
            if (eth != null) return eth;
            if (any != null) return any;
            // 兜底：活动网络的 /24 网关
            Network active = cm.getActiveNetwork();
            LinkProperties lp = active == null ? null : cm.getLinkProperties(active);
            if (lp != null) {
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    if (la.getAddress() instanceof java.net.Inet4Address) {
                        String a = la.getAddress().getHostAddress();
                        int dot = a.lastIndexOf('.');
                        if (dot > 0) return a.substring(0, dot) + ".1";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 目标地址：地址框优先；fe80:: 没带 scope 时自动补 %wlan 接口名。 */
    private String target() {
        String t = ipField == null ? "" : ipField.getText().toString().trim();
        if (t.isEmpty()) t = "192.168.49.1";
        if (t.startsWith("fe80") && t.indexOf('%') < 0) {
            String nic = Net.wlanName();
            if (nic != null) t = t + "%" + nic;
        }
        return t;
    }

    // ---------------- 动作 ----------------

    private int pipelineStage = 0;
    private volatile String foundHost;
    private final Runnable pipelineTick = new Runnable() {
        @Override public void run() {
            if (busy) { ui.postDelayed(this, 800); return; }
            if (pipelineStage == 1) {
                if (foundHost != null) {
                    line("mDNS 已定位 " + foundHost + "，跳过子网扫描");
                    pipelineStage = 3;
                    setRun("● 运行中：一键探测（第 3/4 步 唤醒探针）", C_RUN);
                    doProbe();
                } else {
                    pipelineStage = 2;
                    setRun("● 运行中：一键探测（第 2/4 步 子网扫描兜底）", C_RUN);
                    doSubnet();
                }
                ui.postDelayed(this, 800);
            } else if (pipelineStage == 2) {
                pipelineStage = 3;
                setRun("● 运行中：一键探测（第 3/4 步 唤醒探针）", C_RUN);
                doProbe();
                ui.postDelayed(this, 800);
            } else if (pipelineStage == 3) {
                pipelineStage = 0;
                doListen();
                setRun("● 监听中：一键探测已走完，等仪表连入 10920–10922", C_RUN);
                line("流程走完：等 30 秒 → 点“停止监听” → “推送日志到NAS”");
            }
        }
    };

    private static final int C_IDLE = 0xFF9AA4B2;
    private static final int C_RUN = 0xFFFFC04D;
    private static final int C_OK = 0xFF57C88A;
    private static final int C_BAD = 0xFFE05B5B;

    /** 顶部运行条 + 底部结果横条同时更新；完成/失败顺带弹 Toast。 */
    private volatile String lastRun = "-";
    private String vn = "?";

    private void setRun(final String text, final int color) {
        lastRun = text;
        ui.post(new Runnable() {
            @Override public void run() {
                if (runView != null) {
                    runView.setText(text);
                    runView.setTextColor(color);
                    runView.setBackgroundColor(color == C_RUN ? 0xFF2A2415 : 0xFF1B2430);
                }
                if (heroPill != null) {
                    heroPill.setText("● " + (text.length() > 16 ? text.substring(0, 16) + "…" : text));
                    heroPill.setTextColor(color == C_OK ? 0xFF9CE8B8 : (color == C_BAD ? 0xFFFF9C9C : 0xFFCFE7FF));
                    heroPill.setBackground(roundBg(color == C_OK ? 0x2635C97F : (color == C_BAD ? 0x26FF5C5C : 0x264C8DFF), 11, 0));
                }
                if (heroBtn != null) {
                    boolean running = rig != null && rig.isRunning();
                    heroBtn.setText(running ? "全部停止" : "开始投屏");
                    android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                    g.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT);
                    g.setColors(running ? new int[]{0xFFE05555, 0xFFB32323} : new int[]{0xFF2E8CFF, 0xFF0A52DB});
                    g.setCornerRadius(dp(14));
                    heroBtn.setBackground(g);
                }
                if (lastView != null) {
                    lastView.setText(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                            + "  " + text);
                    lastView.setTextColor(color);
                }
                if (color == C_OK || color == C_BAD) {
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void doOneClick() {
        if (pipelineStage != 0) {
            line("一键探测已经在跑，别重复点");
            return;
        }
        line("");
        foundHost = null;
        line("=== 一键探测：mDNS 发现 → 子网扫描兜底 → 唤醒探针 → 被动监听 ===");
        setRun("● 运行中：一键探测（第 1/4 步 mDNS）", C_RUN);
        pipelineStage = 1;
        doMdns();
        ui.postDelayed(pipelineTick, 1500);
    }

    /**
     * mDNS 发现 _EasyConn._tcp.local. ——官方 SDK 就是靠这个找到仪表的。
     * 比盲扫子网靠谱：应答里直接带 packagename / flavor / huid / channel。
     */
    private void doMdns() {
        if (busy) {
            line("mDNS 发现跳过：当前有其他探测在跑");
            return;
        }
        busy = true;
        final List<String> targets = new ArrayList<String>();
        String gw = gateway();
        if (gw != null && !gw.contains(":")) targets.add(gw);
        for (String p : Net.prefixes24()) {
            if (targets.size() >= 4) break;
            targets.add(p + ".1");
        }
        line("");
        line("=== mDNS 发现 " + Mdns.SERVICE + " ===");
        line("单播目标: " + (targets.isEmpty() ? "（无，只发多播）" : join(targets)));
        new Thread(new Runnable() {
            @Override public void run() {
                final Mdns.Log mlg = new Mdns.Log() {
                    @Override public void line(String s) { MainActivity.this.line(s); }
                };
                final List<Mdns.Entry> list = Mdns.discover(targets, mlg, 4500);
                if (!list.isEmpty()) {
                    String t = Mdns.pickTarget(list);
                    java.util.List<String> selfs = Net.localIpv4();
                    if (t != null && t.equals("0")) t = null;
                    if (t != null && selfs != null && selfs.contains(t)) {
                        line("mDNS 命中的是本机自己 " + t + "，不是仪表，忽略");
                        t = null;
                    }
                    if (t != null && !t.contains(":")) {
                        // 只有 10930 真的开着才算仪表：手机自己的地址也会在 mDNS 里冒出来
                        if (!Net.portOpen(t, Proto.WAKE_PORT, 800)) {
                            line("mDNS 候选 " + t + " 的 10930 不通，不是仪表，忽略");
                            t = null;
                        }
                    }
                    final String tf = t;
                    if (tf != null && !tf.contains(":")) {
                        foundHost = tf;
                        ui.post(new Runnable() {
                            @Override public void run() { ipField.setText(tf); }
                        });
                        line("已把仪表地址写进地址框: " + tf);
                    }
                    String flavor = null;
                    for (Mdns.Entry e : list) {
                        String f = e.get("flavor");
                        if (f != null && !f.isEmpty() && flavor == null) flavor = f;
                    }
                    if (flavor != null) {
                        line("仪表广播的 flavor = " + flavor + "（这就是它认的白名单编号）");
                        for (String[] row : Proto.FLAVOR_TABLE) {
                            if (row[0].equals(flavor)) {
                                line("  该 flavor 对应包名: " + row[1]);
                            }
                        }
                    }
                } else {
                    line("mDNS 没应答。下一步走子网扫描也可以，但更可能是没连上仪表链路。");
                }
                busy = false;
            }
        }, "mdns").start();
    }

    /** 扫本机所在所有 /24 网段，找开着 Carbit 端口的仪表，命中就填进地址框。 */
    private void doSubnet() {
        if (busy) return;
        busy = true;
        final List<String> prefixes = Net.prefixes24();
        line("");
        line("=== 子网扫描 " + (prefixes.isEmpty() ? "（本机没有 IPv4 网段）" : join(prefixes)) + " ===");
        new Thread(new Runnable() {
            @Override public void run() {
                Net.Log lg = new Net.Log() {
                    @Override public void line(String s) { MainActivity.this.line(s); }
                };
                foundHost = null;
                final java.util.List<String> self = Net.localIpv4();
                for (String p : prefixes) {
                    List<String> hits = Net.scan24(p, Proto.WAKE_PORT, 250, lg);
                    for (String h : hits) { if (!self.contains(h)) { foundHost = h; break; } }
                    if (foundHost != null) break;
                }
                if (foundHost == null && prefixes.isEmpty()) {
                    line("没有 IPv4 网段（可能是纯 IPv6 链路），改用手填地址试探针");
                } else if (foundHost == null) {
                    line("10930 全段没人应答，改扫 10920-10922（仪表可能已经在等连入）");
                    for (String p : prefixes) {
                        for (int port : Proto.PXC_PORTS) {
                            List<String> hits = Net.scan24(p, port, 250, lg);
                            for (String h : hits) { if (!self.contains(h)) { foundHost = h; break; } }
                            if (foundHost != null) break;
                        }
                        if (foundHost != null) break;
                    }
                }
                if (foundHost != null) {
                    line("命中主机 " + foundHost + "，已写进地址框");
                    ui.post(new Runnable() {
                        @Override public void run() { ipField.setText(foundHost); }
                    });
                } else {
                    line("整段扫完没找到：要么仪表没连上手机，要么链路只有 IPv6");
                }
                busy = false;
            }
        }, "subnet").start();
    }

    private void doScan() {
        if (busy) return;
        busy = true;
        final String host = target();
        line("");
        line("=== 端口扫描 " + host + " ===");
        new Thread(new Runnable() {
            @Override public void run() {
                int[] ports = new int[]{10920, 10921, 10922, Proto.WAKE_PORT};
                for (int p : ports) {
                    long t0 = System.currentTimeMillis();
                    boolean open = Net.portOpen(host, p, 2500);
                    line("  :" + p + (open ? "  开放" : "  拒绝/超时")
                            + "  (" + (System.currentTimeMillis() - t0) + "ms)");
                }
                line("扫描结束。10930 开放但探针无应答 → 身份问题；10920-10922 开放 → 仪表可能主动连入。");
                busy = false;
            }
        }, "scan").start();
    }

    private void doProbe() {
        if (busy) return;
        busy = true;
        final String host = target();
        line("");
        line("=== 唤醒探针 " + host + ":" + Proto.WAKE_PORT + " ===");
        setRun("● 运行中：唤醒探针（6 种身份 × 3s）", C_RUN);
        new Thread(new Runnable() {
            @Override public void run() {
                String winner = null;
                for (String identity : Proto.IDENTITIES) {
                    line("- 身份 " + identity);
                    String ack = Net.wake(host, identity, MainActivity.this);
                    if (ack != null) {
                        winner = identity;
                        line("  ✔ 被应答: " + ack);
                        break;
                    }
                }
                if (winner == null) {
                    line("全部身份未被应答。若 10930 端口本身连不上，检查是否真的连上了仪表的 Wi-Fi。");
                    setRun("探针结束：6 种身份全无应答", C_BAD);
                } else {
                    line("结论：仪表承认身份 " + winner + "。接下来点“被动监听”，等它连 10920/10710/10711。");
                    setRun("探针成功：仪表承认 " + winner, C_OK);
                    rvDirection(host);
                }
                busy = false;
            }
        }, "probe").start();
    }

    /**
     * 单机路线验证：让本机自己既当“车机”又是“手机”。
     * 步骤：拉起 CarWith 的导出组件 CarlifeAdbActivity（进入 adb 服务端模式）
     *      → 本机建 p2p 组（让 192.168.49.x 地址重新存在）
     *      → 对 10113 逐个候选地址打 EasyConn 握手，把回包全部记录。
     */
    private void doIccoa() {
        if (directBtn != null) directBtn.setBusy(true, "组网中");
        if (!hasP2pPerm()) {
            if (directBtn != null) directBtn.setBusy(false, null);
            java.util.ArrayList<String> need = new java.util.ArrayList<String>();
            need.add("android.permission.ACCESS_FINE_LOCATION");
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                need.add("android.permission.NEARBY_WIFI_DEVICES");
            }
            requestPermissions(need.toArray(new String[0]), RC_P2P_PERM);
            line("需要“附近设备/位置”权限才能建 Wi‑Fi Direct 组，请在弹窗里点允许。");
            return;
        }
        line("");
        line("=== 单机试探：CarWith 服务端(10113) ← 本机扮演车机 ===");
        setRun("● 单机试探中…", C_RUN);

        line("建 p2p 组…（要先有地址，CarWith 才有地方绑服务端）");
        P2p.createGroup(this, new P2p.Cb() {
            @Override public void done(boolean ok, String detail) {
                line(ok ? ("p2p 组已建立：" + detail) : ("p2p 建组失败：" + detail));
                if (!ok) { probe10113(); return; }
                line("地址就绪，现在拉起 CarWith 的 adb 模式入口，让它在新地址上监听…");
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            Intent it = new Intent();
                            it.setClassName("com.miui.carlink",
                                    "com.miui.carlink.castfwk.usb.CarlifeAdbActivity");
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(it);
                            line("已拉起 CarlifeAdbActivity");
                        } catch (Throwable t) {
                            line("拉起失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
                        }
                        line("等 3 秒让它绑端口…");
                        ui.postDelayed(new Runnable() {
                            @Override public void run() { probe10113(); }
                        }, 3000);
                    }
                }, 1500);
            }
        });
    }

    /** 一键上车：先起假车机取流，2.5 秒后再把端口和推流开起来。 */
    private void doStartAll() {
        // 联动：还没和仪表组网，就先直连，连上后自动接着开流（一个动作走到底）
        if (!p2pReady && foundHost == null) {
            line("");
            line("还没连上仪表 → 先自动直连，组网成功即自动开投屏");
            doDirect(true);
            return;
        }
        line("");
        line("=== 开始投屏（假车机取流 → 投给仪表） ===");
        wasStreaming = true;
        // 尊重你最后一次的手动选择：上次开预览就继续开，上次关着就保持关（不再无脑关掉）
        if (previewPref) {
            H264Preview pv = ensurePreview();
            if (fakeHu != null) fakeHu.setPreview(pv);
            line("· 按上次的选择，预览保持开启");
        } else if (previewOn) {
            stopPreviewNow("开始投屏，预览自动关闭（要打字时点预览或右下角「⌨ 打字」）");
        }
        doFakeHu();
        ui.postDelayed(new Runnable() {
            @Override public void run() { doRig(); }
        }, 2500);
    }

    private void doStopAll() {
        wasStreaming = false;
        line("");
        line("=== 全部停止 ===");
        if (fakeHu != null) { try { fakeHu.stop(); } catch (Throwable ignored) { } fakeHu = null; }
        if (preview != null) {
            try { preview.stop(); } catch (Throwable ignored) { }
            preview = null;
        }
        if (previewTexture != null && previewOn) previewTexture.setVisibility(View.VISIBLE);
        if (previewOffTip != null) previewOffTip.setVisibility(previewOn ? View.GONE : View.VISIBLE);
        if (pvInputChip != null) pvInputChip.setVisibility(previewOn ? View.VISIBLE : View.GONE);
        if (pvTapChip != null) pvTapChip.setVisibility(previewOn ? View.VISIBLE : View.GONE);
        if (chipRow != null) chipRow.setVisibility(View.VISIBLE);
        stopKeepAlive();
        stopRig();
        p2pReady = false;
        directBusy = false;
        setRun("● 已全部停止", C_IDLE);
    }


    private long lastTouchLogAt;
    private long lastImeMissAt;
    private boolean wasStreaming;
    private BroadcastReceiver screenRx;

    private final BroadcastReceiver axRx = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            line("[广播] 触发无障碍窗口 dump");
            TouchBridgeService.dumpWindows();
        }
    };

    private int screenW() {
        try { return getResources().getDisplayMetrics().widthPixels; } catch (Throwable t) { return 1080; }
    }

    private int screenH() {
        try { return getResources().getDisplayMetrics().heightPixels; } catch (Throwable t) { return 2400; }
    }

    /** 直连：走 Wi‑Fi Direct 找仪表，用的是 p2p 独立接口，手机原来的流量/Wi‑Fi 不受影响。 */
    private volatile boolean directBusy = false;
    /** 本机预览开关：关掉就完全不建 MediaCodec，用来对比"有没有预览对推流的影响"。 */
    private volatile boolean previewOn = true;
    private volatile boolean previewPref = true;
    private IconBtn previewBtn;
    private TextView previewOffTip;
    private TextView pvInputChip;
    private TextView heroBtn, heroPill;
    /** P2P 组网是否已完成：直连成功后为 true，开始投屏时据此决定要不要先组网。 */
    private volatile boolean p2pReady = false;

    private void doDirect() { doDirect(false); }

    private void doDirect(final boolean thenStart) {
        if (directBusy) { line("直连正在进行，等这一轮结束再点"); return; }
        directBusy = true;
        ui.postDelayed(new Runnable() { @Override public void run() { directBusy = false; } }, 25000);
        if (!hasP2pPerm()) {
            java.util.ArrayList<String> need = new java.util.ArrayList<String>();
            need.add("android.permission.ACCESS_FINE_LOCATION");
            if (android.os.Build.VERSION.SDK_INT >= 33) need.add("android.permission.NEARBY_WIFI_DEVICES");
            requestPermissions(need.toArray(new String[0]), RC_P2P_PERM);
            line("先用“附近设备/位置”权限，才能做 Wi‑Fi Direct 搜索。");
            return;
        }
        line("");
        line("=== Wi‑Fi Direct 直连仪表（不切热点、不影响手机流量） ===");
        if (P2p.wantName != null || P2p.wantMac != null) {
            line("目标：name=" + P2p.wantName + " mac=" + P2p.wantMac + "（来自扫码）");
        } else {
            line("没扫码记录 → 现场扫描：Wi‑Fi Direct 直接搜附近设备，按名字含 VOGE/DS625/EASYCONN 或唯一设备来认仪表");
        }
        setRun("● 搜索 P2P 设备中…", C_RUN);
        P2p.directConnect(this, new P2p.Cb2() {
            @Override public void log(final String s) { line("  " + s); }
            @Override public void done(final boolean ok, final String msg) {
                if (!ok) {
                    if (directBtn != null) directBtn.setBusy(false, null);
                    line("直连失败：" + msg + (thenStart ? "（投屏已取消）" : ""));
                    p2pReady = false;
                    setRun("✗ Wi‑Fi Direct 直连失败", C_BAD);
                    return;
                }
                line("组网成功：" + msg);
                String ip = msg.contains("组主地址 ") ? msg.substring(msg.lastIndexOf(' ') + 1) : null;
                if ((ip == null || !ip.contains(".")) && msg.contains("已有组")) {
                    // 兜底：复用已有组时即使没解析出地址，也要认"已组网"，否则会一直循环直连、永远开不了流
                    CharSequence t = ipField == null ? null : ipField.getText();
                    if (t != null && t.toString().contains(".")) ip = t.toString().trim();
                    else ip = io.carbit.probe.P2p.goIpGuess();
                    line("复用已有组，按组主地址 " + ip + " 直接开流");
                }
                if (ip != null && ip.contains(".")) {
                    final String ipFixed = ip;
                    ui.post(new Runnable() {
                        @Override public void run() { if (ipField != null) ipField.setText(ipFixed); }
                    });
                    foundHost = ip;
                    p2pReady = true;
                }
                if (directBtn != null) { directBtn.setBusy(false, null); directBtn.ping(); }
                startHeartbeat();
                if (thenStart) {
                    setRun("✓ 组网成功，1 秒后自动开始投屏…", C_OK);
                    line("✓ 组网成功 → 自动开始投屏（不用再点第二个按钮）");
                    ui.postDelayed(new Runnable() {
                        @Override public void run() { doStartAll(); }
                    }, 1000);
                } else {
                    setRun("✓ 直连成功，可以投给仪表了", C_OK);
                }
            }
        });
    }

    /** 组网成功后每秒打一条心跳：进程若在组网瞬间被系统收走，日志会停在最后一条心跳处。 */
    private void startHeartbeat() {
        // 只在关键节点报一次（原来每秒一条，把日志区刷满了，正常跑的时候完全没必要）
        int[] marks = {1, 5, 15, 30, 60, 120};
        for (final int n : marks) {
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    line("♥ 组网后 " + n + "s 仍然存活");
                }
            }, 1000L * n);
        }
    }


    /** 无障碍的系统层真实状态：开关是关的，还是开着但服务没连上。 */
    private String accessibilityState() {
        try {
            String enabled = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean on = enabled != null && enabled.toLowerCase().contains("io.carbit.probe");
            return on ? "系统开关=已启用，但服务没连上（多半是更新后绑定丢了，去设置里关掉再开一次）"
                      : "系统开关=未启用（设置 → 无障碍 → 极享 把开关打开）";
        } catch (Throwable t) { return "读不到状态 " + t; }
    }


    /** 亮屏后不一定已经解锁，锁着屏起不了投屏：等到真正解锁再重连，最多等 40 秒。 */
    private void resumeWhenUnlocked(final int attempt) {
        if (!wasStreaming) return;
        android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = km != null && km.isKeyguardLocked();
        if (locked && attempt < 20) {
            if (attempt == 0) line("· 还锁着屏，等解锁后自动重连（最多等 40 秒）");
            ui.postDelayed(new Runnable() { @Override public void run() { resumeWhenUnlocked(attempt + 1); } }, 2000);
            return;
        }
        if (rigAlive()) { line("· 投屏其实还活着，不用重连"); return; }
        line("√ 解锁了：自动重连投屏（不想自动就点「全部停止」）");
        doStartAll();
        // 重连后再确认一次，不行就再拉一遍
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (wasStreaming && !rigAlive()) { line("· 第一次重连没起来，再试一次"); doStartAll(); }
        } }, 4000);
    }

    private boolean rigAlive() {
        return rig != null && rig.isRunning() && !Rig.starved();
    }

    /** 每 3 秒看一眼：投屏"活着"但其实收不到关键帧了（锁屏/熄屏后 CarWith 不再发 IDR），就整段重连。 */
    private void watchdogTick() {
        try {
            if (wasStreaming) {
                if (rig == null || !rig.isRunning()) {
                    if (Rig.carFrames() > 0) { line("· 投屏断了，自动重连一次"); doStartAll(); }
                } else if (Rig.starved()) {
                    line("· 画面卡死（锁屏后 CarWith 不再发关键帧），自动重连一次");
                    doStartAll();
                }
            }
        } catch (Throwable ignored) { }
        ui.postDelayed(new Runnable() { @Override public void run() { watchdogTick(); } }, 3000);
    }

    private TextView pvModeBadge, pvTapChip, pvToneChipRef, pvOutChip;
    private LinearLayout chipRow;
    private int touchMode;   // 0=按下+移动+抬(40/90ms) 1=快(按下+抬 同一拍) 2=慢(按下,150ms,抬) 3=只发按下
    private long touchDownAt;
    // 拖动（滑动手势）：DOWN 只在真正判定为拖动时才补发，起点/当前点分开送
    private boolean dragging;
    private float dragFromX, dragFromY;
    private long dragLastSendAt;

    private int cx(int v) { return touchSpace == 0 ? v : (int) (v * screenW() / (float) FakeHu.VIDEO_W); }
    private int cy(int v) { return touchSpace == 0 ? v : (int) (v * screenH() / (float) FakeHu.VIDEO_H); }
    private View pvDot;
    /** 在预览画面上打一个落点十字，用来核对"是不是点飘了"。 */
    private static final String[] SPACE_NAMES = {"画面", "屏幕"};
    private int touchSpace = 0;
    private TextView pvSpaceChip;

    private static final String[] TOUCH_NAMES = {"正常", "快点", "慢点", "仅按下"};
    private static final String[] TOUCH_DESC = {
            "按下 → 同点移动(40ms) → 抬起(90ms)",
            "按下 → 抬起（同一拍，无移动）",
            "按下 → 等 150ms → 抬起（无移动）",
            "只发按下，不发抬起"};

    /** 胶囊按钮和长按都走这里，保证状态与提示一致。 */
    private void cycleTouchMode() {
        touchMode = (touchMode + 1) % TOUCH_NAMES.length;
        String msg = "触摸序列：" + TOUCH_NAMES[touchMode] + "（" + TOUCH_DESC[touchMode] + "）";
        line("· " + msg);
        if (pvModeBadge != null) pvModeBadge.setVisibility(touchMode == 3 ? View.VISIBLE : View.GONE);
        if (pvTapChip != null) {
            pvTapChip.setText("触摸:" + TOUCH_NAMES[touchMode]);
            pvTapChip.setTextColor(touchMode == 0 ? 0xFFCFE7FF : 0xFFFFD60A);
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void markTouch(int vx, int vy) {
        try {
            if (pvDot == null || pvDot.getParent() == null) return;
            View wrap = (View) pvDot.getParent();
            int w = wrap.getWidth(), h = wrap.getHeight();
            if (w <= 0) return;
            pvDot.setVisibility(View.VISIBLE);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pvDot.getLayoutParams();
            lp.leftMargin = Math.max(0, vx * w / FakeHu.VIDEO_W - dp(7));
            lp.topMargin = Math.max(0, vy * h / FakeHu.VIDEO_H - dp(7));
            pvDot.setLayoutParams(lp);
            pvDot.removeCallbacks(dotHide);
            pvDot.postDelayed(dotHide, 900);
        } catch (Throwable ignored) { }
    }

    private final Runnable dotHide = new Runnable() { @Override public void run() {
        if (pvDot != null) pvDot.setVisibility(View.INVISIBLE);
    } };

    private boolean hasP2pPerm() {
        try {
            if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != RC_P2P_PERM) return;
        boolean ok = res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        line(ok ? "权限已授权，继续试探" : "权限未授予，p2p 建不了组（可在系统设置里给“附近设备”权限后重试）");
        if (ok) {
            ui.postDelayed(new Runnable() { @Override public void run() { doIccoa(); } }, 300);
        }
    }

    private void probe10113() {
        new Thread(new Runnable() {
            @Override public void run() {
                line("--- 试探 10113 ---");
                List<String> cands = new ArrayList<String>();
                cands.add("127.0.0.1");
                cands.add("192.168.49.1");
                cands.add("192.168.49.23");
                for (String ip : Net.localIpv4()) {
                    String a = ip.contains(" ") ? ip.substring(ip.indexOf(' ') + 1) : ip;
                    if (!cands.contains(a)) cands.add(a);
                }
                for (String h : cands) {
                    Net.tap(h, Proto.ICCOA_PORT, new Net.Log() {
                        @Override public void line(String s) { MainActivity.this.line(s); }
                    });
                }
                line("--- 试探结束。有回包=协议同源，可继续；全超时=p2p 地址仍不可用 ---");
                setRun("单机试探结束（看日志结论）", C_OK);
            }
        }, "iccoa").start();
    }

    /** 探针成功后立刻判断 RV 通道方向：仪表是监听方还是连接方。 */
    private void rvDirection(final String host) {
        new Thread(new Runnable() {
            @Override public void run() {
                line("--- RV 通道方向探测 ---");
                for (int p : Proto.RV_PORTS) {
                    boolean open = Net.portOpen(host, p, 1500);
                    line("  " + host + ":" + p + (open ? "  仪表在监听（手机需连入）" : "  无人监听（应等仪表连来手机）"));
                }
                line("--- 方向探测结束 ---");
            }
        }, "rvdir").start();
    }

    private void doListen() {
        if (!listeners.isEmpty()) {
            line("监听已在运行。");
            return;
        }
        line("");
        line("=== 被动监听 10920-10922 + RV 10710/10711 ===");
        setRun("● 监听中：等仪表连入 1092x / 1071x", C_RUN);
        for (int[] ports : new int[][]{Proto.PXC_PORTS, Proto.RV_PORTS}) {
            for (int p : ports) {
                Net.Listener l = Net.listen(p, this);
                listeners.add(l);
                l.start();
            }
        }
        line("现在去仪表上操作：按仪表按键进入投屏/扫码流程，让它主动连手机。");
    }

    private void stopListen() {
        if (listeners.isEmpty()) {
            line("没有在跑的监听。");
            setRun("没有在跑的监听", C_IDLE);
            return;
        }
        for (Net.Listener l : listeners) l.stop();
        listeners.clear();
        line("监听已停止。");
        setRun("监听已停止（可点“推送日志到NAS”）", C_OK);
    }

    private String dashInfoText(String ssid, String pwd, String modelId, String sn, String uuid) {
        return "仪表热点  " + ssid + "   密码  " + pwd + "\n"
                + "modelid " + modelId + "   sn " + sn + "\n"
                + "UUID " + uuid;
    }

    // ---------------- CarWith 调起与捕获 ----------------

    private void pickCarWith() {
        final List<String[]> list = CarWith.all(this);
        line("");
        line("=== 车机互联类应用（已安装）===");
        if (list.isEmpty()) {
            line("一个都没扫到。CarWith 是小米系统内置件（com.miui.carlink），没有桌面图标，");
            line("入口在 设置 → 连接与共享 → CarWith。也可以手动输入包名试");
            manualPackage();
            return;
        }
        for (String[] r : list) line("  · " + r[1] + "  (" + r[0] + ")");
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) items[i] = list.get(i)[1] + "\n" + list.get(i)[0];
        new android.app.AlertDialog.Builder(this)
                .setTitle("调起哪个")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        openPkg(list.get(which)[0], list.get(which)[1]);
                    }
                })
                .setNeutralButton("手动输入包名", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { manualPackage(); }
                })
                .show();
    }

    /** 先走启动器入口，不行就列出该包所有导出 Activity 让人挑。 */
    private void openPkg(String pkg, String label) {
        if (CarWith.launch(this, pkg)) {
            line("已调起 " + label + " (" + pkg + ")");
            Toast.makeText(this, "起来后点\u201c开始捕获\u201d，系统弹窗选\u201c仅此应用\u201d", Toast.LENGTH_LONG).show();
            return;
        }
        final List<String> acts = CarWith.activities(this, pkg);
        if (acts.isEmpty()) {
            line(pkg + " 没有可用的启动入口（可能需要在系统设置里打开）");
            return;
        }
        line(pkg + " 没有启动器入口，列出其 " + acts.size() + " 个 Activity");
        new android.app.AlertDialog.Builder(this)
                .setTitle("选一个 Activity 启动")
                .setItems(acts.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        boolean ok = CarWith.launchActivity(MainActivity.this, pkg, acts.get(which));
                        line((ok ? "已启动 " : "启动失败 ") + acts.get(which));
                    }
                })
                .show();
    }

    private void manualPackage() {
        final EditText e = new EditText(this);
        e.setHint("com.miui.carlink");
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        new android.app.AlertDialog.Builder(this)
                .setTitle("手动输入包名")
                .setView(e)
                .setPositiveButton("启动", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String pkg = e.getText().toString().trim();
                        if (pkg.isEmpty()) return;
                        if (!CarWith.installed(MainActivity.this, pkg)) {
                            line("没有安装 " + pkg);
                            return;
                        }
                        openPkg(pkg, CarWith.label(MainActivity.this, pkg));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void requestCapture() {
        try {
            android.media.projection.MediaProjectionManager mpm =
                    (android.media.projection.MediaProjectionManager)
                            getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), 21);
        } catch (Throwable t) {
            line("请求录屏授权失败: " + t.getMessage());
        }
    }

    private void startCaptureNow(int resultCode, android.content.Intent data) {
        if (capture != null && capture.isRunning()) {
            line("捕获已经在跑");
            return;
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        if (w % 2 != 0) w--;
        if (h % 2 != 0) h--;
        capture = new Capture();
        boolean ok = capture.start(this, resultCode, data, w, h, dm.densityDpi,
                new Capture.Log() {
                    @Override public void line(String s) { MainActivity.this.line(s); }
                },
                new Capture.Preview() {
                    @Override public void onFrame(final android.graphics.Bitmap bmp, final int frames, final double fps) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (previewTexture != null) previewTexture.setVisibility(View.GONE);
                                if (previewView != null) previewView.setImageBitmap(bmp);
                            }
                        });
                    }
                });
        if (ok) {
            line("捕获尺寸 " + w + "x" + h + "，已接入 ImageReader；下一步接 MediaCodec H264 推 10710");
        }
    }

    private void stopCapture() {
        if (capture == null || !capture.isRunning()) {
            line("没有在跑的捕获");
            return;
        }
        line("停止捕获：共 " + capture.frames() + " 帧，平均 "
                + String.format(Locale.US, "%.1f", capture.fps()) + " fps");
        capture.stop();
        capture = null;
    }

    // ---------------- CarWith 取证：解剖 / 逐页截帧 --------------

    private static final String PKG_CARWITH = "com.miui.carlink";
    private static final String PKG_CARLIFE = "com.baidu.carlife.xiaomi";

    /** 把 CarWith 的全部组件摊开打印，并自动推一份 txt 到 NAS。 */
    private void dissect() {
        line("");
        line("=== 解剖 CarWith ===");
        setRun("● 运行中：解剖 CarWith 组件", C_RUN);
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder all = new StringBuilder();
                String[] pkgs = new String[]{PKG_CARWITH, PKG_CARLIFE};
                for (String pkg : pkgs) {
                    if (!CarWith.installed(MainActivity.this, pkg)) {
                        line("跳过（未安装）" + pkg);
                        continue;
                    }
                    line("");
                    all.append(Dump.report(MainActivity.this, pkg, new Dump.Log() {
                        @Override public void line(String s) { MainActivity.this.line(s); }
                    })).append("\n\n");
                }
                if (all.length() == 0) return;
                String name = "dump-" + ts() + ".txt";
                pushBytes(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), name);
                setRun("解剖完成（结果已尝试推 NAS，见上方日志）", C_OK);
            }
        }, "dissect").start();
    }

    /**
     * 逐页试启：把 CarWith 每个导出 Activity 依次拉到前台，1.4s 后截一帧推 NAS。
     * 人不用盯着屏幕，日志 + 图片会把"这个 App 到底有哪些页面"直接摊出来。
     */
    private void sweep() {
        if (capture == null || !capture.isRunning()) {
            line("先点「开始捕获」并授权——逐页截帧要靠 MediaProjection 出图");
            return;
        }
        line("");
        line("=== 逐页试启 CarWith 并截帧 ===");
        setRun("● 运行中：逐页试启 + 截帧", C_RUN);
        new Thread(new Runnable() {
            @Override public void run() {
                List<String> acts = Dump.allActivities(MainActivity.this, PKG_CARWITH);
                if (acts.isEmpty()) {
                    line("拿不到 " + PKG_CARWITH + " 的 Activity 列表（可能被系统隐藏）");
                    return;
                }
                int total = acts.size();
                line("共 " + total + " 个 Activity，导出的一律试启");
                int shots = 0;
                for (int i = 0; i < total; i++) {
                    String tag = acts.get(i);
                    String cls = tag.substring(2);
                    boolean exported = tag.charAt(0) == 'E';
                    String seq = "[" + (i + 1) + "/" + total + "] ";
                    if (!exported) {
                        line(seq + "跳过（未导出，第三方拉不起）" + cls);
                        continue;
                    }
                    String err = launchErr(cls);
                    if (err != null) {
                        line(seq + "起不来 " + shortName(cls) + " : " + err);
                        continue;
                    }
                    line(seq + "已启动 " + shortName(cls));
                    sleep(1400);
                    android.graphics.Bitmap b = capture.snap();
                    if (b == null) {
                        line("      没抓到帧（可能这页是空的/还在加载）");
                        continue;
                    }
                    String nm = "shot-" + pad(i) + "-" + safe(shortName(cls)) + ".jpg";
                    shots++;
                    uploadShot(b, nm);
                    sleep(250);
                }
                line("=== 结束：成功截了 " + shots + " 张，已推 NAS ===");
                setRun("逐页试启完成：截 " + shots + " 张", shots > 0 ? C_OK : C_BAD);
                ui.post(new Runnable() {
                    @Override public void run() {
                        try {
                            android.content.Intent back = new android.content.Intent(
                                    MainActivity.this, MainActivity.class);
                            back.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                            startActivity(back);
                        } catch (Throwable ignored) { }
                    }
                });
            }
        }, "sweep").start();
    }

    /** 手动截当前一帧推 NAS（比如手动在 CarWith 里翻到某页后按）。 */
    private void snapUpload() {
        if (capture == null || !capture.isRunning()) {
            line("先点「开始捕获」并授权");
            return;
        }
        final android.graphics.Bitmap b = capture.snap();
        if (b == null) {
            line("还没有画面，等一两秒再按");
            return;
        }
        line("抓帧 " + b.getWidth() + "×" + b.getHeight() + "，正在推");
        setRun("● 上传中：抓帧", C_RUN);
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = uploadShot(b, "frame-" + ts() + ".jpg");
                setRun(ok ? "✓ 抓帧已上传" : "抓帧上传失败（已存本地）", ok ? C_OK : C_BAD);
            }
        }, "snap-up").start();
    }

    /** 启动指定 Activity，成功返回 null，失败返回原因。 */
    private String launchErr(String cls) {
        try {
            android.content.Intent i = new android.content.Intent();
            i.setClassName(PKG_CARWITH, cls);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return null;
        } catch (Throwable t) {
            String m = t.getMessage();
            return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        }
    }

    /** 推一张 JPEG：先外网、再内网，都不行就落本地下载目录。 */
    private boolean uploadShot(android.graphics.Bitmap b, String name) {
        byte[] jpg = jpeg(b);
        if (jpg == null) return false;
        String[] cfg = nasCfg();
        String base = trimSlash(cfg[0]);
        String lan = trimSlash(cfg[1]);
        boolean ok = !base.isEmpty() && doUpload(base, name, jpg, cfg[2]);
        if (!ok && !lan.isEmpty() && !lan.equals(base)) {
            line("外网不通，回退内网: " + name);
            ok = doUpload(lan, name, jpg, cfg[2]);
        }
        if (!ok) {
            saveToDownloads(name, jpg);
            line("两条地址都没推上去，已存本地 下载/CarbitProbe/" + name);
        }
        return ok;
    }

    private void pushBytes(byte[] data, String name) {
        String[] cfg = nasCfg();
        String base = trimSlash(cfg[0]);
        String lan = trimSlash(cfg[1]);
        line("");
        line("推送 " + name + "  " + data.length + "B");
        boolean ok = !base.isEmpty() && doUpload(base, name, data, cfg[2]);
        if (!ok && !lan.isEmpty() && !lan.equals(base)) ok = doUpload(lan, name, data, cfg[2]);
        if (!ok) line("没推上去：确认网络与 NAS 地址");
    }

    private byte[] jpeg(android.graphics.Bitmap b) {
        try {
            int max = 720;
            int w = b.getWidth(), h = b.getHeight();
            android.graphics.Bitmap out = b;
            if (w > max && w > 0) {
                int nw = max;
                int nh = (int) ((long) h * max / w);
                if (nh < 1) nh = 1;
                out = android.graphics.Bitmap.createScaledBitmap(b, nw, nh, true);
            }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 68, bo);
            if (out != b) out.recycle();
            return bo.toByteArray();
        } catch (Throwable t) {
            line("JPEG 编码失败: " + t.getMessage());
            return null;
        }
    }

    private static String ts() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    private static String pad(int i) {
        return i < 10 ? "0" + i : String.valueOf(i);
    }

    private static String shortName(String cls) {
        int p = cls.lastIndexOf('.');
        return p >= 0 ? cls.substring(p + 1) : cls;
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Throwable ignored) { }
    }

    private void scanQr() {
        startActivityForResult(new android.content.Intent(this, ScanActivity.class), 11);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 21) {
            if (resultCode == RESULT_OK && data != null) {
                startCaptureNow(resultCode, data);
            } else {
                line("录屏授权被取消");
            }
            return;
        }
        if (requestCode != 11 || resultCode != RESULT_OK || data == null) return;
        String qr = data.getStringExtra("qr");
        if (qr == null) return;
        line("");
        line("=== 扫到二维码 ===");
        line(qr);
        android.net.Uri u = android.net.Uri.parse(qr);
        String ssid = u.getQueryParameter("ssid");
        String pwd = u.getQueryParameter("pwd");
        String modelId = u.getQueryParameter("modelid");
        String sn = u.getQueryParameter("sn");
        if (ssid == null || pwd == null) {
            line("这条码里没有 ssid/pwd，可能不是仪表配网码");
            Toast.makeText(this, "识别到的内容不含 Wi-Fi 信息", Toast.LENGTH_LONG).show();
            return;
        }
        String uuid = u.getQueryParameter("uuid");
        String qrName = u.getQueryParameter("name");
        String qrMac = u.getQueryParameter("mac");
        P2p.wantName = qrName;
        P2p.wantMac = qrMac;
        if (qrName != null || qrMac != null) line("码里的 P2P 目标：name=" + qrName + " mac=" + qrMac);
        infoView.setText(dashInfoText(ssid, pwd,
                modelId == null ? "?" : modelId,
                sn == null ? "?" : sn,
                uuid == null ? "（屏上显示）" : uuid));
        line("ssid=" + ssid + "  pwd=" + pwd + "  modelid=" + modelId + "  sn=" + sn);
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("wifi", pwd));
            Toast.makeText(this, "已识别并复制密码 " + pwd + "，去连 " + ssid, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
        line("扫码完成 → 自动接着走 Wi‑Fi Direct 直连，组网成功即开投屏");
        ui.postDelayed(new Runnable() { @Override public void run() { doDirect(true); } }, 500);
    }

    private void clearLog() {
        logBuffer.setLength(0);
        logLines.clear();
        logView.setText("");
        line("日志已清空");
    }

    private void copyPassword() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("wifi", Proto.DASH_PWD));
            Toast.makeText(this, "已复制密码 " + Proto.DASH_PWD + "，去 Wi-Fi 里连 " + Proto.DASH_SSID,
                    Toast.LENGTH_LONG).show();
            line("已复制热点密码 " + Proto.DASH_PWD + "（热点名 " + Proto.DASH_SSID + "）");
        } catch (Exception e) {
            Toast.makeText(this, "复制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void openWifiSettings() {
        try {
            android.content.Intent i = new android.content.Intent("android.settings.WIFI_SETTINGS");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            line("已打开 Wi-Fi 设置：连 " + Proto.DASH_SSID + " / " + Proto.DASH_PWD);
        } catch (Exception e) {
            Toast.makeText(this, "打不开 Wi-Fi 设置: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 写一份到公共“下载”目录，返回 content Uri；不支持则返回 null。 */
    private android.net.Uri saveToDownloads(String name, byte[] data) {
        if (android.os.Build.VERSION.SDK_INT < 29) return null;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/CarbitProbe");
            android.net.Uri uri = getContentResolver()
                    .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            java.io.OutputStream os = getContentResolver().openOutputStream(uri);
            os.write(data);
            os.close();
            return uri;
        } catch (Exception e) {
            line("写下载目录失败: " + e.getMessage());
            return null;
        }
    }

    /** 把旧版（以及本版）留在 App 私有目录里的 carbit-*.txt 全部捞到“下载”目录。 */
    private void extractOldLogs() {
        line("");
        line("=== 提取历史日志 ===");
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File[] all = dir.listFiles();
        if (all == null || all.length == 0) {
            line("私有目录为空: " + dir.getAbsolutePath());
            Toast.makeText(this, "这个目录里没有日志文件", Toast.LENGTH_LONG).show();
            return;
        }
        java.util.List<File> found = new java.util.ArrayList<File>();
        for (File f : all) {
            if (f.isFile() && f.getName().startsWith("carbit-") && f.getName().endsWith(".txt")) {
                found.add(f);
            }
        }
        line("私有目录 " + dir.getAbsolutePath() + " 下找到 " + found.size() + " 个日志");
        if (found.isEmpty()) {
            Toast.makeText(this, "没找到 carbit-*.txt，可能那份还在别的路径", Toast.LENGTH_LONG).show();
            return;
        }
        // 按修改时间倒序，最新的排前面
        java.util.Collections.sort(found, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        android.net.Uri newest = null;
        int ok = 0;
        for (File f : found) {
            try {
                byte[] data = readAll(f);
                android.net.Uri uri = saveToDownloads(f.getName(), data);
                if (uri != null) {
                    ok++;
                    if (newest == null) newest = uri;
                    line("  已提取 " + f.getName() + "  (" + data.length + "B, "
                            + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(f.lastModified())) + ")");
                } else {
                    // Android 9 及以下没有 MediaStore.Downloads，就只列出来
                    line("  无法写下载目录（系统版本过低）: " + f.getAbsolutePath());
                }
            } catch (Exception e) {
                line("  读取失败 " + f.getName() + ": " + e.getMessage());
            }
        }
        Toast.makeText(this, "已提取 " + ok + " 份到 下载/CarbitProbe/", Toast.LENGTH_LONG).show();
        line("共提取 " + ok + " 份，位置 下载/CarbitProbe/");
        if (newest != null) shareLog(newest);
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    // ---------------- 上传到 NAS（HTTP 接收器） ----------------

    private static final String NAS_PREF = "nas_cfg";
    private static final String DEF_URL = "http://YOUR-NAS-HOST:8088/carbit";
    private static final String DEF_URL_LAN = "http://192.168.1.10:8088/carbit";
    private static final String DEF_TOKEN = "YOUR_TOKEN_HERE";

    // 返回 [外网地址, 内网地址, token]
    private String[] nasCfg() {
        android.content.SharedPreferences sp = getSharedPreferences(NAS_PREF, MODE_PRIVATE);
        return new String[]{
                sp.getString("url", DEF_URL),
                sp.getString("lan", DEF_URL_LAN),
                sp.getString("token", DEF_TOKEN)
        };
    }

    private void nasSettings() {
        final String[] cfg = nasCfg();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);

        final EditText fUrl = field(box, "外网地址（优先使用）", cfg[0], InputType.TYPE_CLASS_TEXT);
        final EditText fLan = field(box, "内网地址（外网不通时回退）", cfg[1], InputType.TYPE_CLASS_TEXT);
        final EditText fToken = field(box, "令牌 token", cfg[2], InputType.TYPE_CLASS_TEXT);

        TextView tip = new TextView(this);
        tip.setText("外网 " + DEF_URL + "\n内网 " + DEF_URL_LAN
                + "\n推送时先试外网，失败自动回退内网；连着仪表热点时两个都不通");
        tip.setTextSize(11);
        tip.setTextColor(Color.parseColor("#6B7684"));
        tip.setPadding(0, dp(8), 0, 0);
        box.addView(tip);

        new android.app.AlertDialog.Builder(this)
                .setTitle("日志推送设置")
                .setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        getSharedPreferences(NAS_PREF, MODE_PRIVATE).edit()
                                .putString("url", fUrl.getText().toString().trim())
                                .putString("lan", fLan.getText().toString().trim())
                                .putString("token", fToken.getText().toString().trim())
                                .apply();
                        line("推送设置已保存: " + fUrl.getText() + " / " + fLan.getText());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText field(LinearLayout parent, String hint, String value, int inputType) {
        TextView label = new TextView(this);
        label.setText(hint);
        label.setTextSize(12);
        label.setTextColor(Color.parseColor("#6B7684"));
        parent.addView(label);
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setTextSize(14);
        parent.addView(e);
        return e;
    }

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        while (u.length() > 0 && u.charAt(u.length() - 1) == '/') {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    // ---------------- App 内更新 ----------------

    private void doUpdate() {
        if (updateStartedAt != 0) { line("正在检查中，别急着点第二下"); return; }
        updateStartedAt = System.currentTimeMillis();
        if (updateBtn != null) { updateBtn.setBusy(true, "检查中"); updateBtn.ping(); }
        final String[] cfg = nasCfg();
        final int cur = Updater.installedVersionCode(this);
        line("");
        line("=== 检查更新（本机 versionCode " + cur + "）===");
        new Thread(new Runnable() {
            @Override public void run() {
                Updater.Log ulog = new Updater.Log() {
                    @Override public void line(String s) { MainActivity.this.line("  " + s); }
                };
                final Updater.Info info = Updater.check(
                        new String[]{trimSlash(cfg[0]), trimSlash(cfg[1])}, ulog);
                if (info == null) {
                    finishUpdate("检查失败：没连上 NAS（连着仪表网络时属正常，换流量再试）");
                    return;
                }
                if (info.versionCode <= cur) {
                    finishUpdate("已是最新 v" + info.versionName + "(" + info.versionCode + ")");
                    return;
                }
                finishUpdate("发现新版 v" + info.versionName + "(" + info.versionCode + ")");
                final String base0 = trimSlash(cfg[0]);
                final String base1 = trimSlash(cfg[1]);
                ui.post(new Runnable() {
                    @Override public void run() {
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("更新到 v" + info.versionName)
                                .setMessage("大小 " + info.size + " 字节\nsha256 " + info.sha256
                                        + "\n\n现在下载并安装？")
                                .setPositiveButton("下载安装", new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d, int w) {
                                        startUpdateDownload(info, base0, base1);
                                    }
                                })
                                .setNegativeButton("稍后", null)
                                .show();
                    }
                });
            }
        }, "update-check").start();
    }

    /** 收尾：至少让转圈动画走满 1.2 秒，然后恢复到原图标并给一个明确结果。 */
    private void finishUpdate(final String result) {
        line(result);
        long spent = System.currentTimeMillis() - updateStartedAt;
        long wait = Math.max(0, 1200 - spent);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                updateStartedAt = 0;
                if (updateBtn != null) { updateBtn.setBusy(false, null); updateBtn.ping(); }
                try { Toast.makeText(MainActivity.this, result, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
            }
        }, wait);
    }

    private void startUpdateDownload(final Updater.Info info, final String base0, final String base1) {
        line("开始下载…");
        new Thread(new Runnable() {
            @Override public void run() {
                Updater.Log ulog = new Updater.Log() {
                    @Override public void line(String s) { MainActivity.this.line("  " + s); }
                };
                File apk = Updater.download(MainActivity.this, base0, info, ulog, null);
                if (apk == null && !base1.isEmpty() && !base1.equals(base0)) {
                    line("外网下载不通，回退内网…");
                    apk = Updater.download(MainActivity.this, base1, info, ulog, null);
                }
                if (apk == null) {
                    line("下载失败，未安装（文件可能不完整或被 sha256 校验拦下）");
                    return;
                }
                line("下载完成 " + apk.getAbsolutePath() + "（" + apk.length() + "B）");
                Updater.install(MainActivity.this, apk, ulog);
            }
        }, "update-dl").start();
    }

    private void uploadLogToNas() {
        final String[] cfg = nasCfg();
        final String base = trimSlash(cfg[0]);
        final String lan = trimSlash(cfg[1]);
        if (base.isEmpty() && lan.isEmpty()) {
            Toast.makeText(this, "先点“NAS 设置”填服务地址", Toast.LENGTH_LONG).show();
            nasSettings();
            return;
        }
        final String name = "carbit-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        String body = logBuffer.toString();
        // 一次多抓点：上次崩溃现场（看门狗）+ 上次会话快照，都直接塞进同一个上传文件
        try {
            StringBuilder extra = new StringBuilder();
            for (String n : watchdogFiles) {
                java.io.File f = new java.io.File(getFilesDir(), n);
                if (!f.exists()) continue;
                extra.append("\n\n========== 看门狗现场 ").append(n).append(" ==========\n")
                     .append(new String(readAll(f), java.nio.charset.StandardCharsets.UTF_8));
            }
            java.io.File ls = new java.io.File(getFilesDir(), "last-session.txt");
            if (ls.exists()) {
                extra.append("\n\n========== 上轮会话快照 last-session.txt ==========\n")
                     .append(new String(readAll(ls), java.nio.charset.StandardCharsets.UTF_8));
            }
            body = body + extra;
        } catch (Throwable ignored) { }
        try {
            java.io.File tf = new java.io.File(getFilesDir(), "trace.txt");
            if (tf.exists()) {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(tf, "r");
                long len = raf.length();
                long from = Math.max(0, len - 200000);
                raf.seek(from);
                StringBuilder sb = new StringBuilder("\n\n========== 秒级落盘 trace（进程被杀也留得下） ==========\n");
                byte[] buf = new byte[(int) (len - from)];
                raf.readFully(buf);
                raf.close();
                sb.append(new String(buf, java.nio.charset.StandardCharsets.UTF_8));
                body = body + sb;
            }
        } catch (Throwable ignored) { }
        final byte[] data = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        line("");
        line("=== 推送日志到服务器 ===");
        line("外网 " + base + " / 内网 " + lan + "  文件 " + name + "  " + data.length + "B");
        // 先在本地留一份，免得网络不通时日志丢在半路
        saveToDownloads(name, data);
        setRun("● 上传中：正在推送日志…", C_RUN);
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = !base.isEmpty() && doUpload(base, name, data, cfg[2]);
                if (!ok && !lan.isEmpty() && !lan.equals(base)) {
                    line("外网不通，回退内网重试…");
                    ok = doUpload(lan, name, data, cfg[2]);
                }
                if (!ok) {
                    line("两条地址都没推上去：确认 Wi‑Fi、地址、token（连着仪表热点 VOGE-XXXX 时外网和内网都不通）");
                    line("日志已在本地留了一份：下载/CarbitProbe/" + name);
                    setRun("上传失败 · 已存本地 " + name, C_BAD);
                } else {
                    setRun("✓ 日志已上传 " + name, C_OK);
                }
            }
        }, "nas-upload").start();
    }

    /** 上传一次，成功返回 true；失败原因写进日志 */
    private boolean doUpload(String base, String name, byte[] data, String token) {
        java.net.HttpURLConnection c = null;
        try {
            String q = "?name=" + java.net.URLEncoder.encode(name, "UTF-8");
            if (token != null && !token.isEmpty()) {
                q += "&token=" + java.net.URLEncoder.encode(token, "UTF-8");
            }
            java.net.URL u = new java.net.URL(base + "/upload" + q);
            c = (java.net.HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            c.setFixedLengthStreamingMode(data.length);
            java.io.OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = "";
            if (in != null) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                body = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (code == 200) {
                line("✓ " + base + " 推送成功 HTTP 200  " + body);
                return true;
            } else if (code == 401) {
                line("✗ " + base + " 被拒 HTTP 401：token 不对  " + body);
            } else {
                line("✗ " + base + " 失败 HTTP " + code + "  " + body);
            }
        } catch (Exception e) {
            line("✗ " + base + " 异常 " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
        return false;
    }

    private void exportLog() {
        String name = "carbit-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        String content = logBuffer.toString();
        android.net.Uri shared = null;
        String shownPath = null;

        // 1) 写进 App 私有目录（adb pull 用）
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File f = new File(dir, name);
            FileWriter w = new FileWriter(f);
            w.write(content);
            w.close();
            line("私有目录副本 → " + f.getAbsolutePath());
        } catch (Exception e) {
            line("私有目录写入失败: " + e.getMessage());
        }

        // 2) 写进公共“下载”目录，文件管理器直接可见（Android 10+ 走 MediaStore，免权限）
        shared = saveToDownloads(name, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (shared != null) shownPath = "下载/CarbitProbe/" + name;

        if (shownPath != null) {
            Toast.makeText(this, "已存到 " + shownPath + "\n点分享可直接发出去", Toast.LENGTH_LONG).show();
            line("公共目录 → " + shownPath);
        } else {
            Toast.makeText(this, "已导出到 App 私有目录，需用 adb 拉取", Toast.LENGTH_LONG).show();
        }

        // 3) 自动弹分享面板
        if (shared != null) {
            shareLog(shared);
        }
    }

    private void shareLog(android.net.Uri uri) {
        try {
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            send.putExtra(android.content.Intent.EXTRA_SUBJECT, "极享 · 车机投屏日志");
            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(send, "发送日志"));
        } catch (Exception e) {
            line("分享失败: " + e.getMessage());
        }
    }

    // ---------------- 日志 ----------------

    /** 每 5 秒把当前日志落一份到 files/last-session.txt，进程被杀也留得下证据。 */
    private void maybeSnapshot() {
        long now = System.currentTimeMillis();
        if (snapshotBusy || now - lastSnapshot < 5000) return;
        lastSnapshot = now;
        snapshotBusy = true;
        final String data = logBuffer.toString();
        new Thread(new Runnable() {
            @Override public void run() {
                try { writeSession(data); } catch (Throwable ignored) { }
                snapshotBusy = false;
            }
        }, "log-snap").start();
    }

    private void writeSession(String data) throws java.io.IOException {
        java.io.File f = new java.io.File(getFilesDir(), "last-session.txt");
        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
        try { o.write(data.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } finally { try { o.close(); } catch (Throwable ignored) { } }
    }

    /** 启动时把上次残留（含崩溃栈）并进本次日志，免得闪退之后什么都拿不到。 */
    private void restoreLastSession() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "last-session.txt");
            if (!f.exists()) return;
            byte[] d = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try { int off = 0, n; while ((n = in.read(d, off, d.length - off)) > 0) off += n; } finally { in.close(); }
            String txt = new String(d, java.nio.charset.StandardCharsets.UTF_8);
            String tail = txt.length() > 8000 ? txt.substring(txt.length() - 8000) : txt;
            logBuffer.append("========== 上次会话残留（进程被杀/闪退前的最后一段） ==========\n");
            logBuffer.append(tail).append("\n");
            logBuffer.append("========== 残留结束，以下是本次 ==========\n");
            f.delete();
        } catch (Throwable ignored) { }
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread th, Throwable ex) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    ex.printStackTrace(new java.io.PrintWriter(sw));
                    String head = "\n!!! 崩溃 " + th.getName() + " → " + ex + "\n" + sw.toString() + "\n";
                    String all = logBuffer + head;
                    writeSession(all);
                } catch (Throwable ignored) { }
                if (def != null) def.uncaughtException(th, ex);
            }
        });
    }

    private static final SimpleDateFormat STAMP = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private long lastSnapshot;
    private volatile boolean snapshotBusy;

    /** 每次日志同步落盘（flush），进程被系统直接杀掉时磁盘上仍然有最后一行，用来抓"无栈崩溃"。 */
    private java.io.Writer traceW;
    private void trace(String stamped) {
        try {
            if (traceW == null) {
                traceW = new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(new java.io.File(getFilesDir(), "trace.txt"), true),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            traceW.write(stamped);
            traceW.flush();
        } catch (Throwable ignored) { }
    }

    @Override
    public void line(final String s) {
        try { android.util.Log.i("CarbitProbe", s); } catch (Throwable ignored) { }
        try {
            String st;
            synchronized (STAMP) { st = STAMP.format(new Date()) + "  " + s + "\n"; }
            trace(st);
        } catch (Throwable ignored) { }
        ui.post(new Runnable() {
            @Override public void run() {
                String stamped;
                synchronized (STAMP) { stamped = STAMP.format(new Date()) + "  " + s + "\n"; }
                logBuffer.append(stamped);
                if (logBuffer.length() > MAX_LOG_CHARS) {
                    logBuffer.delete(0, logBuffer.length() - MAX_LOG_CHARS / 2);
                }
                // 日志分级显示：最新一行最大，越旧越小，一屏能塞更多行
                logLines.add(stamped.trim());
                while (logLines.size() > 60) logLines.remove(0);
                redrawLog();
                maybeSnapshot();
                scrollLogToBottom();
            }
        });
    }

    /** 起独立进程的死亡取证器（:watch），并在主进程每 2 秒写一次心跳，它靠心跳判断我们是不是被杀了。 */
    private void startCrashWatch() {
        try { startService(new android.content.Intent(this, CrashWatchService.class)); } catch (Throwable ignored) { }
        // 专职崩溃抓取进程：把本进程 pid 告诉它，它去探 /proc 并实时扒 logcat
        try {
            android.content.Intent i = new android.content.Intent(this, CrashCatcherService.class);
            i.putExtra("pid", android.os.Process.myPid());
            startService(i);
        } catch (Throwable ignored) { }
        ui.post(new Runnable() { @Override public void run() { heartbeatTick(); } });
    }

    private long hbSeq = 0;
    private void heartbeatTick() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("t=").append(System.currentTimeMillis())
              .append(" pid=").append(android.os.Process.myPid())
              .append(" seq=").append(++hbSeq).append("\n");
            sb.append("state=").append(lastRun == null ? "-" : lastRun).append("\n");
            sb.append("rvArmed=").append(Rig.rvArmedFlag())
              .append(" p2pReady=").append(p2pReady)
              .append(" fakeHu=").append(fakeHu != null && fakeHu.isRunning()).append("\n");
            Runtime rt = Runtime.getRuntime();
            sb.append("heap used=").append((rt.totalMemory() - rt.freeMemory()) / 1024)
              .append("KB of max=").append(rt.maxMemory() / 1024).append("KB")
              .append(" threads=").append(Thread.activeCount()).append("\n");
            int n = logLines.size();
            for (int i = Math.max(0, n - 24); i < n; i++) sb.append("  |").append(logLines.get(i)).append("\n");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "hb.txt"));
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) { }
        ui.postDelayed(new Runnable() { @Override public void run() { heartbeatTick(); } }, 2000);
    }

    /** 上次进程被直接带走时，看门狗已经留了现场：这里读出来，进日志、进上传包，然后把标记清掉。 */
    private void pickUpWatchdog() {
        try {
            java.io.File dir = getFilesDir();
            String[] names = dir.list();
            if (names == null) return;
            java.util.Arrays.sort(names);
            for (String n : names) {
                if (!n.startsWith("watchdog-") || !n.endsWith(".txt")) continue;
                StringBuilder b = new StringBuilder();
                b.append(new String(readAll(new java.io.File(dir, n)), java.nio.charset.StandardCharsets.UTF_8));
                line("");
                line("=== 上次崩溃现场（看门狗 " + n + "） ===");
                for (String ln : b.toString().split("\n")) {
                    if (ln.trim().length() == 0) continue;
                    line(ln);
                }
                watchdogFiles.add(n);
            }
            // 清掉标记，让这一次再死还能被抓到
            new java.io.File(dir, "watchdog.flag").delete();
            // hb 先顶一下时间戳，避免启动瞬间被判死
            new java.io.File(dir, "hb.txt").setLastModified(System.currentTimeMillis());
            // 弹不弹窗只看系统给的真实退出原因：划掉/更新/正常退出都不弹，只有真崩溃才弹
            final String digest = realExitDigest();
            if (digest != null) {
                final String digName = digest;
                ui.postDelayed(new Runnable() {
                    @Override public void run() { showCrashDialog(digName); }
                }, 800);
            }
        } catch (Throwable t) { line("读崩溃现场失败: " + t); }
    }


    /** 用 ApplicationExitInfo 判"上次是不是真崩了"。
     *  心跳断供只能说明进程没了 —— 用户划掉、覆盖安装、系统省电清理都会让心跳断供，
     *  以前这些全被当成崩溃弹窗，纯属误报。系统这边其实给了准确的 reason。 */
    private String realExitDigest() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences("rig", MODE_PRIVATE);
            long seen = sp.getLong("seenExitTs", 0L);
            long now = System.currentTimeMillis();
            sp.edit().putLong("seenExitTs", now).apply();

            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.util.List<android.app.ApplicationExitInfo> list =
                    am.getHistoricalProcessExitReasons(getPackageName(), 0, 10);
            if (list == null || list.isEmpty()) { line("· 系统没给上次退出记录（首次安装或记录被清）"); return null; }
            StringBuilder all = new StringBuilder();
            android.app.ApplicationExitInfo hit = null;
            for (android.app.ApplicationExitInfo in : list) {
                // 只认"刚刚那次"：10 分钟内的记录，旧崩溃不翻出来
                if (in.getTimestamp() <= seen || in.getTimestamp() >= now) continue;
                if (now - in.getTimestamp() > 10 * 60 * 1000L) continue;
                String pn = in.getProcessName();
                int r = in.getReason();
                String rs = reasonText(r);
                all.append("· 上次退出：").append(pn).append(" ").append(rs)
                   .append(" 于 ").append(ts(in.getTimestamp())).append("\n");
                if (pn != null && pn.equals(getPackageName()) && isCrashReason(r)) {
                    if (hit == null || in.getTimestamp() > hit.getTimestamp()) hit = in;
                }
            }
            if (all.length() > 0) {
                line("=== 系统退出记录（ApplicationExitInfo） ===");
                for (String ln : all.toString().split("\n")) if (ln.trim().length() > 0) line(ln);
            }
            if (hit == null) { line("· 上次不是崩溃退出（划掉/更新/正常结束都不算），不弹崩溃窗"); return null; }
            return writeExitDigest(hit);
        } catch (Throwable t) {
            line("读退出记录失败（不影响使用）: " + t);
            return null;
        }
    }

    private static boolean isCrashReason(int r) {
        return r == android.app.ApplicationExitInfo.REASON_CRASH
            || r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
            || r == android.app.ApplicationExitInfo.REASON_ANR
            || r == android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
            || r == android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE;
    }

    private static String reasonText(int r) {
        switch (r) {
            case android.app.ApplicationExitInfo.REASON_CRASH: return "Java 崩溃(REASON_CRASH)";
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: return "native 崩溃(REASON_CRASH_NATIVE)";
            case android.app.ApplicationExitInfo.REASON_ANR: return "ANR 无响应(REASON_ANR)";
            case android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "初始化失败";
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "资源超限被系统杀";
            case android.app.ApplicationExitInfo.REASON_USER_REQUESTED: return "用户手动划掉(不算崩溃)";
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: return "内存不足被系统杀(不算崩溃)";
            case android.app.ApplicationExitInfo.REASON_SIGNALED: return "被信号杀死";
            case android.app.ApplicationExitInfo.REASON_EXIT_SELF: return "自己正常退出";
            case android.app.ApplicationExitInfo.REASON_OTHER: return "其他原因(含覆盖安装/系统清理)";
            case android.app.ApplicationExitInfo.REASON_FREEZER: return "被系统冻结";
            case 20: return "应用状态变化(覆盖安装/降级)";
            default: return "reason=" + r;
        }
    }

    private static String ts(long ms) {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date(ms));
    }

    private String writeExitDigest(android.app.ApplicationExitInfo in) {
        String name = "watchdog-exit-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        StringBuilder sb = new StringBuilder();
        sb.append("# 真正的崩溃（系统 ApplicationExitInfo 认定）\n");
        sb.append("进程: ").append(in.getProcessName()).append("\n");
        sb.append("原因: ").append(reasonText(in.getReason())).append("\n");
        sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(in.getTimestamp()))).append("\n");
        sb.append("意义: ").append(safe(in.getDescription())).append("\n");
        sb.append("状态码: ").append(in.getStatus()).append("  重要性: ").append(in.getImportance()).append("\n\n");
        sb.append("===== 系统给的 trace（有就给） =====\n");
        try (java.io.InputStream is = in.getTraceInputStream()) {
            if (is != null) {
                byte[] b = new byte[200000];
                int n = is.read(b);
                if (n > 0) sb.append(new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) { }
        try {
            java.io.File f = new java.io.File(getFilesDir(), name);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(sb.toString().getBytes("UTF-8")); fo.close();
            watchdogFiles.add(name);
            return name;
        } catch (Throwable t) { return null; }
    }

    private static String safe(Object o) { return o == null ? "(无)" : String.valueOf(o); }

    private final java.util.ArrayList<String> watchdogFiles = new java.util.ArrayList<>();

    /** 自测用：造一份假的崩溃现场并弹窗，验证弹窗与上传链路（不进 crash 报告统计）。 */
    private void writeFakeWatchdog() {
        String name = "watchdog-selftest-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        StringBuilder sb = new StringBuilder();
        sb.append("# 看门狗：主进程心跳停摆 18 秒，判定被系统直接带走（这是自测样本）\n");
        sb.append("t=").append(System.currentTimeMillis()).append(" pid=12345 seq=9\n");
        sb.append("state==== 开始投屏（自测） ===\n");
        sb.append("rvArmed=true p2pReady=true fakeHu=true\n");
        sb.append("heap used=48000KB of max=268435KB threads=31\n");
        sb.append("MemTotal: 11534336 kB\nMemAvailable: 1820000 kB\n");
        sb.append("some avg10=3.10 avg60=1.40 avg300=0.80 total=123456\n");
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(new java.io.File(getFilesDir(), name));
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) { }
        if (!watchdogFiles.contains(name)) watchdogFiles.add(name);
        showCrashDialog(name);
    }

    /** 上次是被系统直接带走的：弹窗说清楚发生了啥、怎么判出来的、要不要立刻把现场推上去。 */
    private void showCrashDialog(final String watchdogName) {
        String d = "（现场文件读不出来）";
        try {
            java.io.File f = new java.io.File(getFilesDir(), watchdogName);
            d = new String(readAll(f), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) { d = "（现场文件读不出来: " + t + "）"; }
        final String detail = d;

        // 摘要：取看门狗正文里最有用的前几段（判定原因 + 最后状态 + 内存）
        boolean live = watchdogName != null && watchdogName.startsWith("watchdog-live-");
        StringBuilder brief = new StringBuilder();
        brief.append(live
                ? "检测到上次主进程消失（:collie 抓取进程判定的）。\n\n"
                : "检测到上次运行是被系统直接带走的（没有 Java 崩溃栈）。\n\n");
        brief.append(live
                ? "判定方式：独立进程 :collie 常驻两条 logcat 流（crash 缓冲 + 只含本应用 pid 的 main 缓冲）实时落盘，"
                  + "并每 600ms 检查 /proc/<pid> 是否还在；主进程一消失立即收尾。\n\n"
                : "判定方式：主进程每 2 秒写一次心跳，独立进程的看门狗发现心跳断供超过 15 秒，"
                  + "说明进程已经不在了，且不是正常退出、也不是 Java 异常。\n\n");
        brief.append(live
                ? "已抓到：死前 crash 缓冲（tombstone/signal）+ 本应用 main 缓冲（含 AMS 的 kill 原因）+ 内存压力。\n"
                : "已抓到：内存压力 / logcat 各缓冲区 / 内核日志 / 掉帧现场。\n");
        String[] lines = detail.split("\n");
        int picked = 0;
        for (String ln : lines) {
            String t = ln.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("t=") || t.startsWith("state=") || t.startsWith("heap used=")
                    || t.startsWith("MemTotal") || t.startsWith("MemAvailable")
                    || t.startsWith("# 看门狗") || t.startsWith("some avg") || t.startsWith("full avg")) {
                brief.append("  ").append(t).append("\n");
                if (++picked >= 8) break;
            }
        }
        brief.append("\n详细现场已写进 ").append(watchdogName).append("，可直接推到 NAS。");

        new android.app.AlertDialog.Builder(this)
                .setTitle(live ? "⚠ 上次主进程消失：现场已实时录下" : "⚠ 上次崩溃：进程被系统带走")
                .setMessage(brief.toString())
                .setPositiveButton("推到 NAS", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { uploadCrashReport(watchdogName); }
                })
                .setNeutralButton("看完整日志", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        line("");
                        line("=== 崩溃现场全文（" + watchdogName + "） ===");
                        for (String ln : detail.split("\n")) if (!ln.trim().isEmpty()) line(ln);
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    /** 崩溃包单独一套格式：文件名 crash-时间.txt，头部有醒目的崩溃横幅，和普通 carbit-*.txt 区分开。 */
    private void uploadCrashReport(final String watchdogName) {
        final String[] cfg = nasCfg();
        final String base = trimSlash(cfg[0]);
        final String lan = trimSlash(cfg[1]);
        if (base.isEmpty() && lan.isEmpty()) {
            Toast.makeText(this, "先点“NAS”填服务地址", Toast.LENGTH_LONG).show();
            nasSettings();
            return;
        }
        final String name = "crash-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        StringBuilder b = new StringBuilder();
        b.append("########################################################\n");
        b.append("#  CARBIT 崩溃报告（与普通日志不同：这是进程被系统带走的现场取证）\n");
        b.append("########################################################\n");
        try {
            vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) { }
        int vcode = 0;
        try { vcode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Throwable ignored) { }
        b.append("app           : 极享 · 车机投屏 v").append(vn)
         .append(" (code ").append(vcode).append(")\n");
        b.append("device        : ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL)
         .append(" / Android ").append(android.os.Build.VERSION.RELEASE).append("\n");
        b.append("report time   : ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        b.append("watchdog file : ").append(watchdogName).append("\n");
        b.append("判定          : 心跳断供 + 无 Java 栈 → native 崩溃或被系统回收\n");
        b.append("########################################################\n\n");
        b.append("===== 崩溃现场（看门狗） =====\n");
        try {
            java.io.File f = new java.io.File(getFilesDir(), watchdogName);
            b.append(new String(readAll(f), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) { b.append("(读不到看门狗文件: ").append(t).append(")\n"); }

        b.append("\n===== 本机日志缓冲（本次启动后） =====\n").append(logBuffer.toString());
        try {
            java.io.File tf = new java.io.File(getFilesDir(), "trace.txt");
            if (tf.exists()) {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(tf, "r");
                long len = raf.length();
                long from = Math.max(0, len - 200000);
                raf.seek(from);
                byte[] raw = new byte[(int) (len - from)];
                raf.readFully(raw); raf.close();
                b.append("\n===== 秒级落盘 trace 尾部 =====\n")
                 .append(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) { }

        final byte[] data = b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        line("");
        line("=== 推送崩溃报告 ===");
        line("文件 " + name + "  " + data.length + "B（格式：CRASH 报告）");
        saveToDownloads(name, data);
        setRun("● 上传崩溃报告中…", C_RUN);
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = !base.isEmpty() && doUpload(base, name, data, cfg[2]);
                if (!ok && !lan.isEmpty() && !lan.equals(base)) ok = doUpload(lan, name, data, cfg[2]);
                final boolean f = ok;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (f) {
                            line("✓ 崩溃报告已上传 " + name);
                            setRun("✓ 崩溃报告已上传 " + name, C_OK);
                            Toast.makeText(MainActivity.this, "崩溃报告已推送到 NAS：" + name, Toast.LENGTH_LONG).show();
                        } else {
                            line("✗ 崩溃报告没推上去，本地留了一份：下载/CarbitProbe/" + name);
                            setRun("上传失败 · 已存本地 " + name, C_BAD);
                        }
                    }
                });
            }
        }, "crash-upload").start();
    }


    /** 主屏日志：按行龄给字号，最新 12sp，逐行递减到 7sp，屏幕上能同时看到更多条。 */
    /** 日志滚到底：先等一次布局，再直接滚到内容末尾，避免和重排打架造成"乱跳"。 */
    private void scrollLogToBottom() {
        final ScrollView parent = (logView == null) ? null : (ScrollView) logView.getParent();
        if (parent == null) return;
        parent.post(new Runnable() {
            @Override public void run() {
                int bottom = Math.max(0, logView.getHeight() - parent.getHeight());
                parent.scrollTo(0, bottom);
            }
        });
    }

    private void redrawLog() {
        if (logView == null) return;
        int n = logLines.size();
        StringBuilder sb = new StringBuilder(n * 48);
        for (int i = 0; i < n; i++) sb.append(logLines.get(i)).append("\n");
        // 字号统一（原来是"越旧越小"，每来一行全部行高都变，整块文字忽高忽低，看起来就在乱跳）
        logView.setTextSize(10.5f);
        logView.setLineSpacing(0f, 1.0f);
        logView.setText(sb);
    }

    /** 正常退出会写这一行；下次启动看到没有它，就说明上次是进程被系统带走（无 Java 栈的崩溃）。 */
    private void markCleanExit() {
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(
                    new java.io.File(getFilesDir(), "clean-exit.flag"));
            fos.write("ok".getBytes());
            fos.close();
        } catch (Throwable ignored) { }
    }

    private boolean hadCleanExit() {
        return new java.io.File(getFilesDir(), "clean-exit.flag").exists();
    }

    @Override
    protected void onDestroy() {
        try { if (screenRx != null) unregisterReceiver(screenRx); } catch (Throwable ignored) { }
        screenRx = null;
        markCleanExit();
        super.onDestroy();
        stopListen();
    }

    /** 假车机：单机自证——本机连 CarLife 的 6 条 socket，走版本握手 + 视频初始化，把 H264 收回本地 */
    private void startKeepAlive() {
        try { startForegroundService(new android.content.Intent(this, KeepAliveService.class)); }
        catch (Throwable t) { startService(new android.content.Intent(this, KeepAliveService.class)); }
    }

    private void stopKeepAlive() {
        try { stopService(new android.content.Intent(this, KeepAliveService.class)); } catch (Throwable t) { }
    }

    private void doRig() {
        if (rig != null && rig.isRunning()) { line("投屏已在跑：" + rig.statusText()); return; }
        stopListen();   // 被动监听占着 10922/10710/10711，必须先放开，否则 Rig 绑不上
        final String host = target();
        line("");
        line("=== 投给仪表 " + host + " ===");
        line("前提：手机已连上仪表的热点(VOGE-XXXX)或 Wi-Fi Direct 组网");
        setRun("● 已开端口，等仪表连入(0台)", C_RUN);
        final Rig r = new Rig();
        r.bindLog(MainActivity.this);
        rig = r;
        Rig.onArmed = new Runnable() {
            @Override public void run() {
                // 以前不管开关都释放预览，导致"投屏一开始预览就黑"；现在只有用户关掉预览才释放
                if (fakeHu != null && !previewOn) {
                    fakeHu.releasePreview();
                    line("· 已释放本地预览解码器（预览开关是关的）");
                }
            }
        };
        final String identity = Proto.IDENTITIES.length > 0 ? Proto.IDENTITIES[0] : "net.easyconn.easyride.wws";
        new Thread(new Runnable() { @Override public void run() {
            r.start(MainActivity.this, host, identity, MainActivity.this);
        } }, "rig-start").start();
        if (fakeHu != null) fakeHu.setRig(r);
        line("提示：先点「假车机(单机取流)」拿到 CarWith 画面，帧才会往仪表灌；顺序反过来也行");
        line("注意：光开端口不等于投屏成功，日志出现「仪表连入 / 已供 N 帧」才算通");
    }

    private void stopRig() {
        if (rig == null) { line("投屏没在跑"); return; }
        rig.stop();
        rig = null;
        if (fakeHu != null) fakeHu.setRig(null);
        setRun("空闲", C_IDLE);
    }

    /** 按当前开关把预览解码器准备好；返回 null 表示这次不要预览。 */
    private H264Preview ensurePreview() {
        if (!previewOn) return null;
        if (preview != null && !preview.isRunning()) { try { preview.stop(); } catch (Throwable ignored) { } preview = null; }
        if (preview == null) {
            preview = new H264Preview(new H264Preview.Sink() {
                @Override public void log(final String s) { ui.post(new Runnable() { @Override public void run() { line(s); } }); }
            });
            preview.attach(previewTexture);
            preview.start();
        }
        preview.setStallHandler(new Runnable() { @Override public void run() {
            if (fakeHu != null) fakeHu.requestRestart();
        } });
        // 投屏已在跑时，CarWith 不会重发 SPS/PPS：用推流缓存里的参数集直接把解码器配起来，否则预览永远黑屏
        if (rig != null) {
            byte[] ps = Rig.lastPs();
            if (ps != null) preview.setCsd(ps);
            byte[] key = Rig.lastKey();
            if (ps != null && key != null) {
                byte[] comb = new byte[ps.length + key.length];
                System.arraycopy(ps, 0, comb, 0, ps.length);
                System.arraycopy(key, 0, comb, ps.length, key.length);
                preview.seed(comb);
            }
        }
        if (previewTexture != null) { previewTexture.setVisibility(View.VISIBLE); }
        if (previewView != null) previewView.setImageDrawable(null);
        if (previewOffTip != null) previewOffTip.setVisibility(View.GONE);
        return preview;
    }

    /** 「预览」按钮：开/关本机预览。关的那一刻就把 MediaCodec 放掉，再开自动重建。 */

    /** 真正把预览解码器拆掉（投屏连同锁屏都调这一步）。 */
    private void stopPreviewNow(String why) {
        previewOn = false;
        if (previewBtn != null) { previewBtn.setActive(false); }
        if (fakeHu != null) { fakeHu.setPreview(null); fakeHu.releasePreview(); }
        if (preview != null) { try { preview.stop(); } catch (Throwable ignored) { } preview = null; }
        if (previewTexture != null) previewTexture.setVisibility(View.GONE);
        if (previewOffTip != null) previewOffTip.setVisibility(View.VISIBLE);
        if (why != null) line("· " + why);
    }

    private void togglePreview() {
        previewOn = !previewOn;
        previewPref = previewOn;   // 记住这次选择，下次开始投屏按它来
        if (previewBtn != null) { previewBtn.setActive(previewOn); previewBtn.ping(); }
        if (!previewOn) {
            stopPreviewNow("预览已关闭（本机不再解码；投屏照常）");
        } else {
            H264Preview p = ensurePreview();
            if (fakeHu != null) fakeHu.setPreview(p);
            line("· 预览已打开" + (p == null ? "（但这次没建成）" : ""));
            Toast.makeText(this, "预览已打开", Toast.LENGTH_SHORT).show();
        }
    }

    /** 本机打字 → 直接推给 CarWith 的输入框（走协议 C2P_INPUT_TEXT_CHANGE，不依赖无障碍/注入）。 */
    private void openTypeDialog() {
        if (fakeHu == null || !fakeHu.isRunning()) { Toast.makeText(this, "先开始投屏，再来打字", Toast.LENGTH_SHORT).show(); return; }
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setSingleLine(true);
        et.setTextSize(17);
        et.setHint("想输入的内容（推给车机当前输入框）");
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("打字")
                .setView(et)
                .setNegativeButton("关闭", null)
                .setNeutralButton("搜索", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (fakeHu != null) { fakeHu.sendInputAction(3); line("⌨ 已发送 搜索 动作"); }
                    }
                })
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (fakeHu != null) { fakeHu.sendInputAction(6); line("⌨ 已发送 确定(DONE) 动作"); }
                    }
                })
                .create();
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) {
                et.requestFocus();
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s2, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s2, int st, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                if (fakeHu == null) return;
                String t = e == null ? "" : e.toString();
                if (System.currentTimeMillis() - lastTypeSentAt < 120 && t.length() > 0) return;  // 粗节流，别每个字符刷一次
                lastTypeSentAt = System.currentTimeMillis();
                fakeHu.sendTextInput(t);
                if (System.currentTimeMillis() - lastTypeLogAt > 2500) {
                    lastTypeLogAt = System.currentTimeMillis();
                    line("⌨ 文本已推给 CarWith：" + (t.length() > 18 ? t.substring(0, 18) + "…" : t));
                }
            }
        });
        dlg.show();
        // 主界面是 adjustNothing：键盘弹出时底部按钮纹丝不动；只有弹窗自己让位，输入框不会被键盘顶出屏幕
        if (dlg.getWindow() != null) {
            dlg.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    private long lastTypeSentAt, lastTypeLogAt;

    private void doFakeHu() {
        if (fakeHu != null && fakeHu.isRunning()) { line("假车机已在跑"); return; }
        line("=== 假车机启动（本机回环，无需第二台设备） ===");
        H264Preview p = ensurePreview();
        if (p == null) line("· 本次不开本机预览（预览按钮处于关闭状态）");
        startKeepAlive();   // 锁屏后继续推流：前台服务 + WakeLock
        final FakeHu fh = new FakeHu();
        fh.setPreview(previewOn ? preview : null);
        if (rig != null && rig.isRunning()) fh.setRig(rig);
        fakeHu = fh;
        fh.start(this, new FakeHu.Sink() {
            @Override public void log(final String s) { ui.post(new Runnable() { @Override public void run() { line(s); } }); }
        });
    }
}
