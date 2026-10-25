package com.mclick.timer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Locale;

/**
 * 自绘时间滚轮：中间数字大而清晰，上下渐变淡出，拖拽后自动吸附。
 * 不依赖 NumberPicker，避免系统默认字号过小的问题。
 */
public class WheelView extends View {

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float itemHeight;
    private final float baseTextSize;
    private final float density;

    private int minValue = 0;
    private int maxValue = 23;
    private float position = 0f;

    private float downY;
    private float lastY;
    private ValueAnimator animator;

    public WheelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        itemHeight = 46f * density;
        baseTextSize = 30f * context.getResources().getDisplayMetrics().scaledDensity;

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setColor(0xFF3E3930);

        linePaint.setColor(0xFFD9CFBA);
        linePaint.setStrokeWidth(Math.max(1f, density));

        setWillNotDraw(false);
        setClickable(true);
    }

    public void setRange(int min, int max) {
        minValue = min;
        maxValue = max;
        normalize();
        invalidate();
    }

    public void setValue(int value) {
        if (animator != null) {
            animator.cancel();
        }
        position = value - minValue;
        normalize();
        invalidate();
    }

    public int getValue() {
        int count = maxValue - minValue + 1;
        int v = Math.round(position);
        v = ((v % count) + count) % count;
        return v + minValue;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final int count = maxValue - minValue + 1;
        final int base = (int) Math.floor(position);

        for (int i = -2; i <= 2; i++) {
            float index = base + i;
            float dist = Math.abs(index - position);
            if (dist > 2.35f) {
                continue;
            }
            float offset = (index - position) * itemHeight;
            int value = ((int) index % count + count) % count + minValue;

            float scale = 1f - 0.17f * dist;
            if (scale < 0.62f) {
                scale = 0.62f;
            }
            textPaint.setTextSize(baseTextSize * scale);
            int alpha = (int) (255f * (1f - 0.30f * dist));
            if (alpha < 46) {
                alpha = 46;
            }
            textPaint.setAlpha(alpha);
            textPaint.setFakeBoldText(dist < 0.5f);

            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = cy + offset - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(String.format(Locale.US, "%02d", value), cx, baseline, textPaint);
        }

        float pad = 10f * density;
        canvas.drawLine(pad, cy - itemHeight / 2f, getWidth() - pad,
                cy - itemHeight / 2f, linePaint);
        canvas.drawLine(pad, cy + itemHeight / 2f, getWidth() - pad,
                cy + itemHeight / 2f, linePaint);

        float fadeHeight = getHeight() * 0.30f;
        fadePaint.setShader(new LinearGradient(0f, 0f, 0f, fadeHeight,
                0xFFEFE8D6, 0x00EFE8D6, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, getWidth(), fadeHeight, fadePaint);
        fadePaint.setShader(new LinearGradient(0f, getHeight() - fadeHeight, 0f, getHeight(),
                0x00EFE8D6, 0xFFEFE8D6, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, getHeight() - fadeHeight, getWidth(), getHeight(), fadePaint);
        fadePaint.setShader(null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (animator != null) {
                    animator.cancel();
                }
                downY = event.getY();
                lastY = downY;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                float y = event.getY();
                float dy = y - lastY;
                lastY = y;
                if (Math.abs(y - downY) > 2f * density) {
                    position -= dy / itemHeight;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                snapToNearest();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void snapToNearest() {
        final float from = position;
        final float to = Math.round(position);
        if (Math.abs(to - from) < 0.001f) {
            normalize();
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(170L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            position = (Float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                normalize();
                invalidate();
            }
        });
        animator.start();
    }

    private void normalize() {
        int count = maxValue - minValue + 1;
        if (count <= 0) {
            position = 0f;
            return;
        }
        position = ((position % count) + count) % count;
    }
}
