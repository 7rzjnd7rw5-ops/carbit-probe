package io.carbit.probe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 图标按钮：上面一个自绘图标、下面一行 9sp 小字。
 * 这样一排能塞四个还不挤，文字只是辅助说明。
 */
public class IconBtn extends LinearLayout {

    private boolean busy = false;
    private boolean active = false;

    /** 统一圆角底：常态是深灰蓝，打开/忙碌时带淡绿描边。 */
    private void paintBg(int color, boolean glow) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(getContext(), 14));
        if (glow) g.setStroke(dp(getContext(), 1), 0x4C4CC38A);
        setBackground(g);
    }

    public static final int SCAN = 0, LINK = 1, RADAR = 2, PLAY = 3, STOP = 4,
            UPLOAD = 5, FILE = 6, WIFI = 7, LOOP = 8, GEAR = 9, COPY = 10, CLOCK = 11,
            PREVIEW = 12;

    private final IconView iconView;
    private final TextView labelView;
    private final String normalLabel;
    private final float density;
    private android.animation.ValueAnimator spin;

    public IconBtn(Context c, int icon, String label) { this(c, icon, label, 20, 9f); }

    public IconBtn(Context c, int icon, String label, int iconDp, float textSp) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        paintBg(0xFF1A2230, false);
        setPadding(dp(c, 5), dp(c, 6), dp(c, 5), dp(c, 5));
        density = c.getResources().getDisplayMetrics().density;
        normalLabel = label;
        setClickable(true);

        IconView iv = new IconView(c, icon);
        iconView = iv;
        addView(iv, new LayoutParams(dp(c, iconDp), dp(c, iconDp)));

        TextView tv = new TextView(c);
        labelView = tv;
        tv.setText(label);
        tv.setTextSize(textSp);
        tv.setTextColor(Color.parseColor("#C8D3E0"));
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(Typeface.DEFAULT);
        tv.setMaxLines(1);
        addView(tv, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // 按下就有反馈：轻微缩小 + 变暗 + 描边高亮，松手复原（解决“点了没感觉”）
        setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (busy) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).start();
                        paintBg(0xFF2A3A50, active);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                        paintBg(0xFF1A2230, active);
                        break;
                }
                return false;
            }
        });
    }

    /** 忙碌态：图标不停转圈、字变提示（如“检查中…”），期间不可再点。 */
    public void setBusy(boolean b, String busyText) {
        busy = b;
        if (b) {
            paintBg(0xFF1B2E23, true);
            labelView.setText(busyText == null ? normalLabel : busyText);
            labelView.setTextColor(0xFF8ADFA0);
            spin = android.animation.ValueAnimator.ofFloat(0f, 360f);
            spin.setDuration(800);
            spin.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            spin.setInterpolator(new android.view.animation.LinearInterpolator());
            spin.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    iconView.setRotation((Float) a.getAnimatedValue());
                }
            });
            spin.start();
        } else {
            if (spin != null) { spin.cancel(); spin = null; }
            iconView.setRotation(0f);
            labelView.setText(normalLabel);
            labelView.setTextColor(Color.parseColor("#C8D3E0"));
            paintBg(0xFF1A2230, active);
        }
    }

    /** 开关型按钮的状态：开=绿字、图标点亮；关=灰字、图标压暗。 */
    public void setActive(boolean on) {
        active = on;
        paintBg(on ? 0xFF16302A : 0xFF1A2230, on);
        labelView.setText(normalLabel);
        labelView.setTextColor(on ? Color.parseColor("#8ADFA0") : Color.parseColor("#8C99A8"));
        iconView.setAlpha(on ? 1f : 0.45f);
    }

    /** 一次性的“吸睛”闪动：绿→灰，告诉用户这次点击生效了。 */
    public void ping() {
        animate().scaleX(1.06f).scaleY(1.06f).setDuration(90)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    }
                }).start();
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 极简线条图标，全部用 Canvas 画，不依赖资源文件。 */
    static class IconView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int type;

        IconView(Context c, int type) {
            super(c);
            this.type = type;
            p.setColor(Color.parseColor("#DCE7F3"));
            p.setStrokeWidth(1.6f * c.getResources().getDisplayMetrics().density);
            p.setStyle(Paint.Style.STROKE);
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

        @Override protected void onDraw(Canvas g) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float u = Math.min(w, h) / 2f;          // 半径基准
            RectF r;
            switch (type) {
                case SCAN:                          // 相机
                    r = new RectF(cx - u * 0.9f, cy - u * 0.55f, cx + u * 0.9f, cy + u * 0.65f);
                    g.drawRoundRect(r, u * 0.2f, u * 0.2f, p);
                    g.drawCircle(cx, cy + u * 0.05f, u * 0.35f, p);
                    g.drawRect(new RectF(cx - u * 0.3f, cy - u * 0.85f, cx + u * 0.3f, cy - u * 0.55f), p);
                    break;
                case LINK:                          // 两台设备互联
                    g.drawCircle(cx - u * 0.45f, cy, u * 0.35f, p);
                    g.drawCircle(cx + u * 0.45f, cy, u * 0.35f, p);
                    g.drawLine(cx - u * 0.12f, cy, cx + u * 0.12f, cy, p);
                    break;
                case RADAR:                         // 雷达
                    for (int i = 1; i <= 3; i++) {
                        g.drawArc(new RectF(cx - u * i * 0.3f, cy - u * i * 0.3f,
                                cx + u * i * 0.3f, cy + u * i * 0.3f), 200, 140, false, p);
                    }
                    g.drawCircle(cx, cy + u * 0.3f, u * 0.1f, p);
                    break;
                case PLAY:
                    { Path t = new Path();
                      t.moveTo(cx - u * 0.45f, cy - u * 0.55f);
                      t.lineTo(cx + u * 0.55f, cy);
                      t.lineTo(cx - u * 0.45f, cy + u * 0.55f);
                      t.close();
                      p.setStyle(Paint.Style.FILL);
                      g.drawPath(t, p);
                      p.setStyle(Paint.Style.STROKE); }
                    break;
                case STOP:
                    g.drawRect(new RectF(cx - u * 0.45f, cy - u * 0.45f, cx + u * 0.45f, cy + u * 0.45f), p);
                    break;
                case UPLOAD:                        // 上传到云端
                    g.drawArc(new RectF(cx - u * 0.8f, cy - u * 0.4f, cx + u * 0.8f, cy + u * 0.7f),
                            200, 140, false, p);
                    g.drawLine(cx, cy + u * 0.35f, cx, cy - u * 0.7f, p);
                    g.drawLine(cx, cy - u * 0.7f, cx - u * 0.3f, cy - u * 0.4f, p);
                    g.drawLine(cx, cy - u * 0.7f, cx + u * 0.3f, cy - u * 0.4f, p);
                    break;
                case FILE:                          // 文件
                    r = new RectF(cx - u * 0.55f, cy - u * 0.7f, cx + u * 0.55f, cy + u * 0.7f);
                    g.drawRect(r, p);
                    g.drawLine(cx - u * 0.28f, cy - u * 0.15f, cx + u * 0.28f, cy - u * 0.15f, p);
                    g.drawLine(cx - u * 0.28f, cy + u * 0.2f, cx + u * 0.28f, cy + u * 0.2f, p);
                    break;
                case WIFI:
                    g.drawCircle(cx, cy + u * 0.45f, u * 0.09f, p);
                    g.drawArc(new RectF(cx - u * 0.45f, cy - u * 0.15f, cx + u * 0.45f, cy + u * 0.75f), 225, 90, false, p);
                    g.drawArc(new RectF(cx - u * 0.8f, cy - u * 0.5f, cx + u * 0.8f, cy + u * 0.8f), 225, 90, false, p);
                    break;
                case LOOP:                          // 刷新/循环
                    g.drawArc(new RectF(cx - u * 0.6f, cy - u * 0.6f, cx + u * 0.6f, cy + u * 0.6f), 120, 260, false, p);
                    g.drawLine(cx + u * 0.45f, cy - u * 0.55f, cx + u * 0.6f, cy - u * 0.25f, p);
                    g.drawLine(cx + u * 0.3f, cy - u * 0.3f, cx + u * 0.6f, cy - u * 0.25f, p);
                    break;
                case GEAR:
                    g.drawCircle(cx, cy, u * 0.45f, p);
                    g.drawCircle(cx, cy, u * 0.15f, p);
                    for (int i = 0; i < 6; i++) {
                        double a = Math.toRadians(i * 60);
                        g.drawLine(cx + (float) Math.cos(a) * u * 0.45f, cy + (float) Math.sin(a) * u * 0.45f,
                                cx + (float) Math.cos(a) * u * 0.7f, cy + (float) Math.sin(a) * u * 0.7f, p);
                    }
                    break;
                case COPY:
                    g.drawRect(new RectF(cx - u * 0.55f, cy - u * 0.55f, cx + u * 0.25f, cy + u * 0.55f), p);
                    g.drawRect(new RectF(cx - u * 0.1f, cy - u * 0.7f, cx + u * 0.65f, cy + u * 0.35f), p);
                    break;
                case CLOCK:
                    g.drawCircle(cx, cy, u * 0.65f, p);
                    g.drawLine(cx, cy, cx, cy - u * 0.4f, p);
                    g.drawLine(cx, cy, cx + u * 0.3f, cy + u * 0.15f, p);
                    break;
                case PREVIEW:                       // 眼睛：预览开/关
                    { Path eye = new Path();
                      eye.moveTo(cx - u * 0.85f, cy);
                      eye.quadTo(cx, cy - u * 0.78f, cx + u * 0.85f, cy);
                      eye.quadTo(cx, cy + u * 0.78f, cx - u * 0.85f, cy);
                      eye.close();
                      g.drawPath(eye, p);
                      g.drawCircle(cx, cy, u * 0.26f, p); }
                    break;
                default:
                    g.drawCircle(cx, cy, u * 0.5f, p);
                    break;
            }
        }
    }
}
