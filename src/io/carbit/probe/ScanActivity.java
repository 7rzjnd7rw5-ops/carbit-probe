package io.carbit.probe;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.view.TextureView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;

/**
 * 相机扫码页。
 *
 * 刻意不用 SurfaceView 预览：预览面在页面重建时最容易出 "Session has been closed"
 * 和拉伸变形，而且 SurfaceView 会被系统随手缩放。改成「相机只输出到 ImageReader
 * → 自己把灰度帧按填满(center-crop)方式画到 View 上」：铺满、比例正确、转正，
 * 也不存在预览会话打架。
 */
public class ScanActivity extends Activity {

    private static final int REQ_CAMERA = 21;
    private static final int REQ_PHOTO = 22;

    private android.widget.ImageView previewImg;
    private android.graphics.Bitmap previewBmp;
    private int[] previewPix;
    private long lastPreviewMs;
    private android.util.Size previewSize;
    private volatile boolean torchOn = false;
    private TextView hint;
    private FrameLayout root;

    private CameraManager cameraManager;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private HandlerThread bgThread;
    private Handler bg;
    private boolean decoding;
    private int sensorOrientation = 90;
    /** 人工微调量：实测 K80 Pro 上按传感器方向算会多转 90°，默认先扣掉这一档。 */
    private int userRot = 0;

    /** 实际用于旋转的角度 = 传感器方向 - 屏幕方向 + 人工微调。 */
    private int previewRotation() {
        int displayDeg;
        try {
            displayDeg = getWindowManager().getDefaultDisplay().getRotation() * 90;
        } catch (Throwable t) { displayDeg = 0; }
        return ((sensorOrientation - displayDeg + userRot) % 360 + 360) % 360;
    }

    private CameraCharacteristics activeChars;
    private float zoomRatio = 1f;
    private long lastDecodeMs;
    private long lastDrawAt = 0;
    private long lastStatAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        bgThread = new HandlerThread("scan");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            openCamera();
        }
    }

    // ---------------- 界面 ----------------

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewImg = new android.widget.ImageView(this);
        previewImg.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        previewImg.setBackgroundColor(Color.BLACK);
        root.addView(previewImg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        previewImg.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() != android.view.MotionEvent.ACTION_DOWN) return true;
                tapFocus(e.getX() / v.getWidth(), e.getY() / v.getHeight());
                uiHint("正在对焦…");
                return true;
            }
        });

        View guide = new GuideView(this);
        root.addView(guide, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(16), dp(22));
        bar.setBackgroundColor(Color.parseColor("#B3000000"));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        root.addView(bar, barLp);

        hint = new TextView(this);
        hint.setText("对准仪表屏幕上的二维码");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(15);
        hint.setTypeface(Typeface.DEFAULT_BOLD);
        hint.setGravity(Gravity.CENTER);
        bar.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        bar.addView(row, rowLp);

        Button album = new Button(this);
        album.setAllCaps(false);
        album.setText("从相册选图");
        row.addView(album, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        album.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickPhoto(); }
        });

        Button torch = new Button(this);
        torch.setAllCaps(false);
        torch.setText("手电筒");
        LinearLayout.LayoutParams torchLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        torchLp.leftMargin = dp(8);
        row.addView(torch, torchLp);
        torch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                torchOn = !torchOn;
                v.setSelected(torchOn);
                applyTorch();
                uiHint(torchOn ? "手电筒已开" : "手电筒已关");
            }
        });

        Button zoomBtn = new Button(this);
        zoomBtn.setAllCaps(false);
        zoomBtn.setText("1x");
        zoomBtn.setTextSize(11);
        LinearLayout.LayoutParams zLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        zLp.leftMargin = dp(8);
        row.addView(zoomBtn, zLp);
        zoomBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                zoomRatio = zoomRatio < 1.4f ? 2f : (zoomRatio < 2.4f ? 3f : 1f);
                ((Button) v).setText(zoomRatio == 1f ? "1x" : (zoomRatio == 2f ? "2x" : "3x"));
                CaptureRequest.Builder b = reqBuilder;
                if (b != null && session != null) {
                    try { applyZoom(b); session.setRepeatingRequest(b.build(), null, bg); } catch (Throwable ignored) { }
                }
                uiHint("变焦 " + String.valueOf(zoomRatio).replace(".0", "") + "x，画面越小码越大越好扫");
            }
        });

        Button flip = new Button(this);
        flip.setAllCaps(false);
        flip.setText("转90°");
        flip.setTextSize(11);
        LinearLayout.LayoutParams flipLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        flipLp.leftMargin = dp(8);
        row.addView(flip, flipLp);
        flip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                userRot = userRot + 90;            // 每次顺时针再转 90°，错了就再点
                if (userRot > 270) userRot = -180;
                uiHint("已再转 90°（当前微调 " + userRot + "°，转到正为止）");
            }
        });

        Button close = new Button(this);
        close.setAllCaps(false);
        close.setText("取消");
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        closeLp.leftMargin = dp(8);
        row.addView(close, closeLp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        return root;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 灰度帧铺满屏幕：按"填满(center-crop)"算缩放，不拉伸、不留大片黑边。 */
    static class PreviewCanvas extends View {
        private Bitmap frame;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();

        PreviewCanvas(Context c) { super(c); }

        void setFrame(Bitmap b) {
            frame = b;
            postInvalidateOnAnimation();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap b = frame;
            if (b == null || b.isRecycled()) return;
            float vw = getWidth(), vh = getHeight();
            float scale = Math.max(vw / b.getWidth(), vh / b.getHeight());
            float dw = b.getWidth() * scale, dh = b.getHeight() * scale;
            float left = (vw - dw) / 2f, top = (vh - dh) / 2f;
            src.set(0, 0, b.getWidth(), b.getHeight());
            dst.set(left, top, left + dw, top + dh);
            canvas.drawBitmap(b, src, dst, paint);
        }
    }

    /** 中间取景方框 + 四角标记，框外压暗，提示把二维码框进来。 */
    static class GuideView extends View {
        private final Paint scrim = new Paint();
        private final Paint corner = new Paint();
        private final Paint border = new Paint();
        private final RectF box = new RectF();

        GuideView(Context c) {
            super(c);
            scrim.setColor(0x55000000);
            corner.setColor(0xFF57C88A);
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(6f);
            border.setColor(0x33FFFFFF);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(2f);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float side = Math.min(getWidth(), getHeight()) * 0.62f;
            float cx = getWidth() / 2f, cy = getHeight() * 0.45f;
            box.set(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2);
            canvas.drawRect(0, 0, getWidth(), box.top, scrim);
            canvas.drawRect(0, box.bottom, getWidth(), getHeight(), scrim);
            canvas.drawRect(0, box.top, box.left, box.bottom, scrim);
            canvas.drawRect(box.right, box.top, getWidth(), box.bottom, scrim);
            canvas.drawRect(box, border);
            float len = side / 5f;
            canvas.drawLine(box.left, box.top, box.left + len, box.top, corner);
            canvas.drawLine(box.left, box.top, box.left, box.top + len, corner);
            canvas.drawLine(box.right - len, box.top, box.right, box.top, corner);
            canvas.drawLine(box.right, box.top, box.right, box.top + len, corner);
            canvas.drawLine(box.left, box.bottom - len, box.left, box.bottom, corner);
            canvas.drawLine(box.left, box.bottom, box.left + len, box.bottom, corner);
            canvas.drawLine(box.right, box.bottom - len, box.right, box.bottom, corner);
            canvas.drawLine(box.right - len, box.bottom, box.right, box.bottom, corner);
        }
    }

    // ---------------- 相机 ----------------

    /** 明确挑后置摄像头：id[0] 在某些机型上是前置，前置画面天生左右镜像，二维码看着就"反"。 */
    private String pickBackCamera() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
            }
        } catch (Throwable ignored) { }
        try {
            return cameraManager.getCameraIdList()[0];
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void openCamera() {
        try {
            String id = pickBackCamera();
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
            activeChars = ch;
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            // 直接要 JPEG：相机自己出色度，省掉手工取 U/V 平面（那套在部分机型上会灰/绿）
            Size[] sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) {
                sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(ImageFormat.YUV_420_888);
            }
            if (sizes == null || sizes.length == 0) {
                uiHint("相机没有可用输出尺寸");
                return;
            }
            Size chosen = pickSize(sizes);
            try {
                int dispDeg = getWindowManager().getDefaultDisplay().getRotation() * 90;
                android.util.Log.i("CarbitProbe", "[扫码] 相机=" + id + " 传感器方向=" + sensorOrientation
                        + " 屏幕方向=" + dispDeg + " 取流=" + chosen.getWidth() + "x" + chosen.getHeight()
                        + " 旋转=" + previewRotation());
                uiHint("相机 " + chosen.getWidth() + "x" + chosen.getHeight()
                        + " · 传感器 " + sensorOrientation + "° · 屏幕 " + dispDeg + "°");
            } catch (Throwable ignored) { }
            useJpeg = true;
            reader = ImageReader.newInstance(chosen.getWidth(), chosen.getHeight(),
                    ImageFormat.JPEG, 2);
            previewSize = chosen;
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) { onFrame(r); }
            }, bg);
            cameraManager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice c) {
                    camera = c;
                    startSession();
                }

                @Override public void onDisconnected(CameraDevice c) { c.close(); camera = null; }

                @Override public void onError(CameraDevice c, int code) {
                    c.close();
                    camera = null;
                    uiHint("相机打开失败: " + code);
                }
            }, bg);
        } catch (Exception e) {
            uiHint("相机不可用: " + e.getMessage());
        }
    }

    /**
     * K80 Pro 主摄（光影猎人 800）YUV 输出最大 1920x1080。
     * 屏是 2K、仪表 7 寸，扫的是屏幕上显示的码：优先拿满 1080p 的采样点，
     * 小尺寸代码方块才够多像素；解码用节流控制开销。
     */
    /** 中央 30% 区域作为对焦/测光点 */
    private android.hardware.camera2.params.MeteringRectangle[] centerMetering() {
        if (activeChars == null) return null;
        try {
            android.graphics.Rect a = activeChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            int w = a.width(), h = a.height();
            int cw = (int) (w * 0.3f), ch = (int) (h * 0.3f);
            int l = a.left + (w - cw) / 2, t = a.top + (h - ch) / 2;
            android.graphics.Rect r = new android.graphics.Rect(l, t, l + cw, t + ch);
            return new android.hardware.camera2.params.MeteringRectangle[]{
                    new android.hardware.camera2.params.MeteringRectangle(r, 1000)};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 数码变焦：手机上直接把画面推近，相当于把码放大，远一点也能扫 */
    private void applyZoom(CaptureRequest.Builder b) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                float max = 4f;
                if (activeChars != null) {
                    Float mx = activeChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) == null
                            ? null : activeChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE).getUpper();
                    if (mx != null) max = mx;
                }
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, Math.min(zoomRatio, max));
            } else if (activeChars != null) {
                android.graphics.Rect a = activeChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                float z = Math.max(1f, zoomRatio);
                int cw = (int) (a.width() / z), ch = (int) (a.height() / z);
                int l = (a.width() - cw) / 2 + a.left, t = (a.height() - ch) / 2 + a.top;
                b.set(CaptureRequest.SCALER_CROP_REGION,
                        new android.graphics.Rect(l, t, l + cw, t + ch));
            }
        } catch (Throwable ignored) { }
    }

    private Size pickSize(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() == 1920 && s.getHeight() == 1080) { best = s; break; }
        }
        if (best == null) {
            for (Size s : sizes) {
                if (s.getWidth() == 1600 && s.getHeight() == 1200) { best = s; break; }
            }
        }
        if (best == null) {
            int bestCost = Integer.MAX_VALUE;
            for (Size s : sizes) {
                if (s.getWidth() * s.getHeight() > 1920 * 1080) continue;
                int cost = Math.abs(s.getWidth() * s.getHeight() - 1280 * 720);
                if (cost < bestCost) { bestCost = cost; best = s; }
            }
        }
        return best == null ? sizes[0] : best;
    }

    /** 点哪对哪：以点击位置为中心建测光/对焦矩形，并发一次 AF 触发（连续对焦模式下也会立即重对）。 */
    private void tapFocus(float fx, float fy) {
        try {
            if (activeChars == null) return;
            android.graphics.Rect a = activeChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            int w = a.width(), h = a.height();
            int side = (int) (Math.min(w, h) * 0.25f);
            int cx = a.left + (int) (fx * w), cy = a.top + (int) (fy * h);
            int l = Math.max(a.left, cx - side / 2), t = Math.max(a.top, cy - side / 2);
            android.graphics.Rect r = new android.graphics.Rect(l, t, Math.min(a.right, l + side), Math.min(a.bottom, t + side));
            android.hardware.camera2.params.MeteringRectangle mr =
                    new android.hardware.camera2.params.MeteringRectangle(r, 1000);
            android.hardware.camera2.params.MeteringRectangle[] arr =
                    new android.hardware.camera2.params.MeteringRectangle[]{mr};
            CaptureRequest.Builder b = reqBuilder;
            if (b == null || session == null) return;
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arr);
            b.set(CaptureRequest.CONTROL_AE_REGIONS, arr);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.setRepeatingRequest(b.build(), null, bg);
            bg.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        CaptureRequest.Builder b2 = reqBuilder;
                        if (b2 == null || session == null) return;
                        b2.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                        b2.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        session.setRepeatingRequest(b2.build(), null, bg);
                    } catch (Throwable ignored) { }
                }
            }, 1200);
        } catch (Throwable ignored) { }
    }

    private void startSession() {
        try {
            Surface imageSurface = reader.getSurface();
            java.util.List<Surface> targets = new java.util.ArrayList<Surface>();
            targets.add(imageSurface);
            camera.createCaptureSession(targets,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(reader.getSurface());
                                b.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
                                // 对焦/测光都锁在画面中央（取景框位置），避免对到背景或车灯上
                                android.hardware.camera2.params.MeteringRectangle[] mr =
                                        centerMetering();
                                if (mr != null) {
                                    try { b.set(CaptureRequest.CONTROL_AF_REGIONS, mr); } catch (Throwable ignored) { }
                                    try { b.set(CaptureRequest.CONTROL_AE_REGIONS, mr); } catch (Throwable ignored) { }
                                }
                                try {
                                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                            useJpeg ? new android.util.Range<Integer>(15, 30)
                                                    : new android.util.Range<Integer>(30, 30));
                                } catch (Throwable ignored) { }
                                // 扫码要"清晰锐利"，防抖与降噪都会糊掉码的边缘
                                try { b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF); } catch (Throwable ignored) { }
                                try { b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF); } catch (Throwable ignored) { }
                                try { b.set(CaptureRequest.NOISE_REDUCTION_MODE,
                                        CaptureRequest.NOISE_REDUCTION_MODE_FAST); } catch (Throwable ignored) { }
                                applyZoom(b);
                                if (supportsTorch()) {
                                    b.set(CaptureRequest.FLASH_MODE, torchOn
                                            ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                                }
                                reqBuilder = b;
                                s.setRepeatingRequest(b.build(), null, bg);
                                uiHint("对准仪表屏幕上的二维码");
                            } catch (Exception e) {
                                uiHint("预览失败: " + e.getMessage());
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            uiHint("相机配置失败");
                        }
                    }, bg);
        } catch (Exception e) {
            uiHint("会话创建失败: " + e.getMessage());
        }
    }

    private void uiHint(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() { if (hint != null) hint.setText(s); }
        });
    }

    private volatile boolean useJpeg = false;

    private void onFrame(ImageReader r) {
        if (useJpeg) { onFrameJpeg(r); return; }
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null || decoding) return;
            int w = img.getWidth(), h = img.getHeight();
            java.nio.ByteBuffer yBuf = img.getPlanes()[0].getBuffer();
            int rowStride = img.getPlanes()[0].getRowStride();
            byte[] y = new byte[w * h];
            if (rowStride == w) {
                yBuf.get(y, 0, Math.min(yBuf.remaining(), w * h));
            } else {
                byte[] row = new byte[rowStride];
                int pos = 0;
                for (int rowIdx = 0; rowIdx < h && yBuf.remaining() > 0; rowIdx++) {
                    int len = Math.min(rowStride, yBuf.remaining());
                    yBuf.get(row, 0, len);
                    int copy = Math.min(w, len);
                    System.arraycopy(row, 0, y, pos, copy);
                    pos += copy;
                }
            }

            // 取色度平面（NV21 用，预览要彩色，不能只画亮度）
            byte[] crU = null, crV = null;
            try {
                android.media.Image.Plane pu = img.getPlanes()[1], pv = img.getPlanes()[2];
                java.nio.ByteBuffer bu = pu.getBuffer(), bv = pv.getBuffer();
                int cw = w / 2, chh = h / 2;
                int uStride = pu.getPixelStride(), vStride = pv.getPixelStride();
                int uRow = pu.getRowStride(), vRow = pv.getRowStride();
                byte[] cu = new byte[cw * chh], cv = new byte[cw * chh];
                for (int yy = 0; yy < chh; yy++) {
                    for (int xx = 0; xx < cw; xx++) {
                        int iu = yy * uRow + xx * uStride, iv = yy * vRow + xx * vStride;
                        cu[yy * cw + xx] = iu < bu.capacity() ? bu.get(iu) : 0;
                        cv[yy * cw + xx] = iv < bv.capacity() ? bv.get(iv) : 0;
                    }
                }
                crU = cu; crV = cv;
            } catch (Throwable ignored) { }

            // 传感器多按横向算，竖屏用时要把帧转正
            int rot = previewRotation();
            byte[] upright = y;
            int uw = w, uh = h;
            if (rot == 90) { upright = rotate90(y, w, h); uw = h; uh = w; }
            else if (rot == 180) { upright = rotate180(y, w, h); }
            else if (rot == 270) { upright = rotate270(y, w, h); uw = h; uh = w; }
            // 色度要单独转：V/U 交错之后再整体旋转会把配对搅乱（画面发绿就是这一步错的）
            int ccw = w / 2, cch = h / 2;
            if (crU != null && rot != 0) {
                if (rot == 90) { crU = rotate90(crU, ccw, cch); crV = rotate90(crV, ccw, cch); }
                else if (rot == 180) { crU = rotate180(crU, ccw, cch); crV = rotate180(crV, ccw, cch); }
                else if (rot == 270) { crU = rotate270(crU, ccw, cch); crV = rotate270(crV, ccw, cch); }
            }

            if (System.currentTimeMillis() - lastStatAt > 2000) {
                lastStatAt = System.currentTimeMillis();
                double sum = 0, sq = 0; int mn = 255, mx = 0;
                for (int i = 0; i < uw * uh; i++) {
                    int v = upright[i] & 0xff; sum += v; sq += v * v;
                    if (v < mn) mn = v; if (v > mx) mx = v;
                }
                int n = uw * uh;
                double mean = sum / n;
                android.util.Log.i("CarbitProbe", "[扫码] 帧 " + uw + "x" + uh + " rot=" + rot
                        + " 均值=" + String.format("%.1f", mean)
                        + " 标准差=" + String.format("%.1f", Math.sqrt(sq / n - mean * mean))
                        + " min=" + mn + " max=" + mx);
            }
            drawPreview(upright, uw, uh, crU, crV);
            long now = System.currentTimeMillis();
            if (now - lastDecodeMs < 110) { return; }   // 1080p 下最多 9 次/秒
            lastDecodeMs = now;
            decoding = true;
            String text = decodeYuv(upright, uw, uh);
            if (text == null) text = decodeYuvCrop(upright, uw, uh, 0.68f);   // 二维码在取景框里，裁中间再解一次
            if (text == null) text = decodeYuvInverted(upright, uw, uh);      // 屏太亮/反光时反相再解一次
            if (text == null) text = decodeYuv(flipH(upright, uw, uh), uw, uh);        // 画面镜像时
            if (text == null) text = decodeYuvCrop(flipH(upright, uw, uh), uw, uh, 0.68f);
            if (text == null && upright != y) text = decodeYuv(y, w, h);
            decoding = false;
            if (text != null) done(text);
        } catch (Exception ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    private static byte[] rotate90(byte[] src, int w, int h) {
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int rowBase = y * w;
            for (int x = 0; x < w; x++) {
                out[(x * h) + (h - 1 - y)] = src[rowBase + x];
            }
        }
        return out;
    }

    private static byte[] rotate180(byte[] src, int w, int h) {
        byte[] out = new byte[w * h];
        for (int i = 0, j = w * h - 1; i < w * h; i++, j--) out[j] = src[i];
        return out;
    }

    private static byte[] rotate270(byte[] src, int w, int h) {
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int rowBase = y * w;
            for (int x = 0; x < w; x++) {
                out[(w - 1 - x) * h + y] = src[rowBase + x];
            }
        }
        return out;
    }

    private static Bitmap toBitmap(byte[] luma, int w, int h, int targetW) {
        int step = Math.max(1, w / targetW);
        int ow = w / step, oh = h / step;
        int[] pix = new int[ow * oh];
        for (int y = 0; y < oh; y++) {
            int srow = (y * step) * w;
            for (int x = 0; x < ow; x++) {
                int v = luma[srow + x * step] & 0xff;
                pix[y * ow + x] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }
        return Bitmap.createBitmap(pix, ow, oh, Bitmap.Config.ARGB_8888);
    }

    private CaptureRequest.Builder reqBuilder;

    private boolean supportsTorch() {
        try {
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(
                    cameraManager.getCameraIdList()[0]);
            Boolean f = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            return f != null && f;
        } catch (Throwable t) { return false; }
    }

    private void applyTorch() {
        CaptureRequest.Builder b = reqBuilder;
        if (b == null || session == null) return;
        try {
            if (supportsTorch()) {
                b.set(CaptureRequest.FLASH_MODE, torchOn
                        ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                session.setRepeatingRequest(b.build(), null, bg);
            }
        } catch (Throwable ignored) { }
    }

    /** JPEG 帧：解一次图，彩色预览 + zxing 识别都用它（方向由 Matrix 转正，所见即所解）。 */
    private void onFrameJpeg(ImageReader r) {
        Image img = null;
        java.nio.ByteBuffer buf;
        byte[] data;
        try {
            img = r.acquireLatestImage();
            if (img == null || decoding) return;
            buf = img.getPlanes()[0].getBuffer();
            data = new byte[buf.remaining()];
            buf.get(data);
        } catch (Throwable t) {
            if (img != null) img.close();
            return;
        } finally {
            if (img != null) img.close();
        }
        long now = System.currentTimeMillis();
        if (now - lastDecodeMs < 100) return;
        lastDecodeMs = now;
        decoding = true;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 2;                     // 1920x1080 → 960x540，够解也够看
            o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            android.graphics.Bitmap raw = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (raw == null) return;
            int rot = previewRotation();
            android.graphics.Bitmap uprightBmp = raw;
            if (rot != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rot);
                uprightBmp = android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
                if (uprightBmp != raw) raw.recycle();
            }
            // 预览
            final android.graphics.Bitmap show = uprightBmp;
            if (now - lastPreviewMs > 100) {
                lastPreviewMs = now;
                runOnUiThread(new Runnable() {
                    @Override public void run() { if (previewImg != null) previewImg.setImageBitmap(show); }
                });
            }
            // 识别：整图 → 中间裁切 → 反相
            int bw = uprightBmp.getWidth(), bh = uprightBmp.getHeight();
            String text = zx(uprightBmp, 0, 0, bw, bh);
            if (text == null) {
                int cw = (int) (bw * 0.68f), chh = (int) (bh * 0.68f);
                text = zx(uprightBmp, (bw - cw) / 2, (bh - chh) / 2, cw, chh);
            }
            if (text != null) done(text);
        } catch (Throwable t) {
            // 静默：换帧继续
        } finally {
            decoding = false;
        }
    }

    /** 用当前 Bitmap 的一块做 QR 识别 */
    private String zx(android.graphics.Bitmap bmp, int left, int top, int w, int h) {
        try {
            int[] pix = new int[w * h];
            bmp.getPixels(pix, 0, w, left, top, w, h);
            int[] lum = new int[w * h];
            for (int i = 0; i < pix.length; i++) {
                int p = pix[i];
                int r = (p >> 16) & 0xff, gg = (p >> 8) & 0xff, bl = p & 0xff;
                lum[i] = (r * 30 + gg * 59 + bl * 11) / 100;
            }
            com.google.zxing.RGBLuminanceSource src = new com.google.zxing.RGBLuminanceSource(w, h, lum);
            MultiFormatReader reader = new MultiFormatReader();
            java.util.HashMap<com.google.zxing.DecodeHintType, Object> hints =
                    new java.util.HashMap<com.google.zxing.DecodeHintType, Object>();
            hints.put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS,
                    java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
            hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE);
            reader.setHints(hints);
            return reader.decode(new BinaryBitmap(new HybridBinarizer(src))).getText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** V/U 交错成 NV21 的色度段 */
    private static byte[] interleave(byte[] cu, byte[] cv, int n) {
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) { out[i * 2] = cv[i]; out[i * 2 + 1] = cu[i]; }
        return out;
    }

    /**
     * 把转正后的帧画到 ImageView：走 YUV → JPEG → Bitmap，彩色、方向和比例都跟 zxing 拿到的一致。
     * （只画亮度会变成黑白，扫码能解但人眼很难判断对没对准）
     */
    private void drawPreview(byte[] uprightY, int uw, int uh, byte[] cu, byte[] cv) {
        long now = System.currentTimeMillis();
        if (now - lastPreviewMs < 100) return;      // 约 10fps
        lastPreviewMs = now;
        try {
            int cSize = uw * uh / 2;
            if (cu == null || cv == null || cu.length < cSize) {
                cu = new byte[cSize]; cv = new byte[cSize];
                java.util.Arrays.fill(cu, (byte) 128);      // 没有色度就给中性灰，至少不是绿屏
                java.util.Arrays.fill(cv, (byte) 128);
            }
            byte[] nv21 = new byte[uw * uh + cSize * 2];
            System.arraycopy(uprightY, 0, nv21, 0, uw * uh);
            byte[] vu = interleave(cu, cv, cSize);
            System.arraycopy(vu, 0, nv21, uw * uh, cSize * 2);
            android.graphics.YuvImage yi = new android.graphics.YuvImage(
                    nv21, android.graphics.ImageFormat.NV21, uw, uh, null);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1 << 20);
            yi.compressToJpeg(new android.graphics.Rect(0, 0, uw, uh), 55, bos);
            final byte[] jpg = bos.toByteArray();
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        android.graphics.BitmapFactory.Options o =
                                new android.graphics.BitmapFactory.Options();
                        o.inSampleSize = 2;
                        o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                        android.graphics.Bitmap b = android.graphics.BitmapFactory
                                .decodeByteArray(jpg, 0, jpg.length, o);
                        if (b != null && previewImg != null) previewImg.setImageBitmap(b);
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    /** 只解中间一块（取景框覆盖区域），等效于放大二维码，弱光/远距离时命中率高很多。 */
    private String decodeYuvCrop(byte[] y, int w, int h, float ratio) {
        int cw = (int) (w * ratio), ch = (int) (h * ratio);
        int left = (w - cw) / 2, top = (h - ch) / 2;
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(y, w, h, left, top, cw, ch, false);
            MultiFormatReader reader = new MultiFormatReader();
            java.util.HashMap<com.google.zxing.DecodeHintType, Object> hints =
                    new java.util.HashMap<com.google.zxing.DecodeHintType, Object>();
            hints.put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS,
                    java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
            hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE);
            reader.setHints(hints);
            return reader.decode(new BinaryBitmap(new HybridBinarizer(src))).getText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 左右镜像（前置相机/翻转过的画面就是这个形态，zxing 只认正像）。 */
    private static byte[] flipH(byte[] src, int w, int h) {
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int x = 0; x < w; x++) out[base + x] = src[base + (w - 1 - x)];
        }
        return out;
    }

    /** 反相再解一次：仪表屏是亮的、码是深色时，二值化容易糊，反相能救回来。 */
    private String decodeYuvInverted(byte[] y, int w, int h) {
        byte[] inv = new byte[w * h];
        for (int i = 0; i < w * h; i++) inv[i] = (byte) (255 - (y[i] & 0xFF));
        return decodeYuv(inv, w, h);
    }

    private String decodeYuv(byte[] y, int w, int h) {
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(src));
            MultiFormatReader reader = new MultiFormatReader();
            reader.setHints(new java.util.HashMap<com.google.zxing.DecodeHintType, Object>() {{
                put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS,
                        Collections.singletonList(BarcodeFormat.QR_CODE));
                put(com.google.zxing.DecodeHintType.TRY_HARDER, Boolean.TRUE);
            }});
            Result result = reader.decode(bitmap);
            return result.getText();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 相册 --------------

    private void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(i, REQ_PHOTO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PHOTO || resultCode != RESULT_OK || data == null) return;
        try {
            Bitmap bmp = BitmapFactory.decodeStream(getContentResolver().openInputStream(data.getData()));
            if (bmp == null) { hint.setText("图片读不出来"); return; }
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);
            RGBLuminanceSource src = new RGBLuminanceSource(w, h, pixels);
            BinaryBitmap bb = new BinaryBitmap(new HybridBinarizer(src));
            Result r = new MultiFormatReader().decode(bb);
            if (r != null) done(r.getText());
        } catch (Exception e) {
            hint.setText("这张图里没找到二维码，换一张试试");
        }
    }

    // ---------------- 结果 --------------

    private void done(final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                hint.setText("识别成功: " + text);
                Intent out = new Intent();
                out.putExtra("qr", text);
                setResult(RESULT_OK, out);
                finish();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        if (requestCode == REQ_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                hint.setText("没有相机权限，只能用“从相册选图”");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { if (session != null) { session.close(); session = null; } } catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        try { if (camera != null) camera.close(); } catch (Exception ignored) { }
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        if (bgThread != null) bgThread.quitSafely();
    }
}
