package com.applemewmew.dlsauto.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;

import com.applemewmew.dlsauto.R;
import com.applemewmew.dlsauto.security.CopyrightGuard;

/**
 * Custom Canvas-Rendered 3-Second Animated Copyright Intro View.
 * Displays smooth glowing typewriter + shimmer reveal of:
 *   Line 1: "Bản quyền thuộc về Apple Mew Mew"
 *   Line 2: "Mọi tính năng đều miễn phí"
 * Verifies integrity & render duration (~3.0s) before unlocking the application.
 */
public final class BrandIntroView extends View {

    private static final long INTRO_DURATION_MS = 3050L;

    private final String line1;
    private final String line2;
    private final boolean intactAtBoot;
    private final Runnable onUnlockComplete;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint1 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF cardRect = new RectF();
    private final RectF barRect = new RectF();
    private final RectF barFillRect = new RectF();

    private long startUptimeMs = 0L;
    private boolean finished = false;

    public BrandIntroView(Context context, Runnable onUnlockComplete) {
        super(context);
        this.onUnlockComplete = onUnlockComplete;
        this.line1 = context.getString(R.string.copyright_line_1);
        this.line2 = context.getString(R.string.copyright_line_2);
        this.intactAtBoot = CopyrightGuard.isIntact(context);

        ringPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.6f));

        cardPaint.setColor(Color.argb(215, 11, 24, 42));

        badgePaint.setColor(Color.rgb(16, 185, 129));
        badgePaint.setTextAlign(Paint.Align.CENTER);
        badgePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        badgePaint.setTextSize(sp(12.5f));
        badgePaint.setLetterSpacing(0.14f);

        textPaint1.setTextAlign(Paint.Align.CENTER);
        textPaint1.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint1.setTextSize(sp(21.5f));
        textPaint1.setShadowLayer(dp(8f), 0f, dp(2f), Color.argb(160, 250, 204, 21));

        textPaint2.setTextAlign(Paint.Align.CENTER);
        textPaint2.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint2.setTextSize(sp(16.5f));
        textPaint2.setShadowLayer(dp(6f), 0f, dp(1.5f), Color.argb(150, 16, 185, 129));

        subPaint.setTextAlign(Paint.Align.CENTER);
        subPaint.setColor(Color.rgb(148, 163, 184));
        subPaint.setTextSize(sp(12f));

        barBgPaint.setColor(Color.argb(90, 51, 65, 85));
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (startUptimeMs == 0L) {
            startUptimeMs = SystemClock.uptimeMillis();
        }
        long elapsed = SystemClock.uptimeMillis() - startUptimeMs;
        float progress = Math.min(1.0f, elapsed / (float) INTRO_DURATION_MS);

        // Background gradient
        bgPaint.setShader(new LinearGradient(
                0, 0, w, h,
                intactAtBoot
                        ? new int[] { Color.rgb(5, 10, 22), Color.rgb(9, 25, 42), Color.rgb(6, 28, 30) }
                        : new int[] { Color.rgb(35, 6, 12), Color.rgb(55, 10, 18), Color.rgb(25, 5, 10) },
                null,
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(0, 0, w, h, bgPaint);

        // Subtle animated radar pulse rings behind center card
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        for (int i = 0; i < 3; i++) {
            float wave = (progress * 1.4f + i * 0.33f) % 1.0f;
            float rad = dp(70f) + wave * dp(155f);
            int alpha = Math.round((1.0f - wave) * 48f);
            ringPaint.setStrokeWidth(dp(1.5f));
            ringPaint.setColor(intactAtBoot
                    ? Color.argb(alpha, 16, 215, 148)
                    : Color.argb(alpha, 244, 63, 94));
            canvas.drawCircle(cx, cy, rad, ringPaint);
        }

        float cardW = Math.min(w - dp(32f), dp(460f));
        float cardH = dp(235f);
        cardRect.set(cx - cardW * 0.5f, cy - cardH * 0.5f, cx + cardW * 0.5f, cy + cardH * 0.5f);
        canvas.drawRoundRect(cardRect, dp(22f), dp(22f), cardPaint);

        borderPaint.setShader(new LinearGradient(
                cardRect.left, cardRect.top, cardRect.right, cardRect.bottom,
                intactAtBoot
                        ? new int[] { Color.rgb(16, 185, 129), Color.rgb(250, 204, 21), Color.rgb(56, 189, 248) }
                        : new int[] { Color.rgb(244, 63, 94), Color.rgb(251, 113, 133) },
                null,
                Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(cardRect, dp(22f), dp(22f), borderPaint);

        if (!intactAtBoot) {
            // Tampered / Cracked APK Lock Screen!
            textPaint1.setShader(null);
            textPaint1.setColor(Color.rgb(251, 113, 133));
            textPaint1.setTextSize(sp(18f));
            canvas.drawText("⚠ PHÁT HIỆN CHỈNH SỬA BẢN QUYỀN", cx, cy - dp(22f), textPaint1);

            textPaint2.setShader(null);
            textPaint2.setColor(Color.WHITE);
            textPaint2.setTextSize(sp(14f));
            canvas.drawText("Bản quyền gốc đã bị thay đổi trái phép (Crack).", cx, cy + dp(12f), textPaint2);
            canvas.drawText("Ứng dụng đã tự động khóa toàn bộ tính năng!", cx, cy + dp(38f), subPaint);
            return;
        }

        // Auto-fit text size on narrow portrait screens so Line 1 never clips
        float maxTextW = cardW - dp(28f);
        textPaint1.setTextSize(sp(21.5f));
        while (textPaint1.measureText(line1) > maxTextW && textPaint1.getTextSize() > sp(13.5f)) {
            textPaint1.setTextSize(textPaint1.getTextSize() - 1.0f);
        }
        textPaint2.setTextSize(sp(16.5f));
        while (textPaint2.measureText(line2) > maxTextW && textPaint2.getTextSize() > sp(12f)) {
            textPaint2.setTextSize(textPaint2.getTextSize() - 1.0f);
        }

        // Top esports header pill
        canvas.drawText("⚽ DLS AUTO PRO • ANDROID EDITION", cx, cardRect.top + dp(38f), badgePaint);

        // Typewriter reveal for Line 1 (0.08 -> 0.54) and Line 2 (0.44 -> 0.80)
        float t1Frac = clamp01((progress - 0.06f) / 0.46f);
        float t2Frac = clamp01((progress - 0.42f) / 0.36f);

        int chars1 = Math.round(line1.length() * t1Frac);
        int chars2 = Math.round(line2.length() * t2Frac);
        String visible1 = line1.substring(0, Math.max(0, Math.min(line1.length(), chars1)));
        String visible2 = line2.substring(0, Math.max(0, Math.min(line2.length(), chars2)));

        // Animated gold-cyan-white shimmer across Line 1
        float shimmerX = cardRect.left + (cardW * 1.6f * progress) - cardW * 0.3f;
        textPaint1.setShader(new LinearGradient(
                shimmerX - dp(90f), cy, shimmerX + dp(90f), cy,
                new int[] { Color.rgb(250, 204, 21), Color.rgb(255, 251, 235), Color.rgb(56, 189, 248), Color.rgb(250, 204, 21) },
                null,
                Shader.TileMode.MIRROR
        ));
        canvas.drawText(visible1, cx, cy - dp(8f), textPaint1);

        // Crisp emerald-mint gradient for Line 2
        textPaint2.setShader(new LinearGradient(
                cardRect.left, cy, cardRect.right, cy,
                new int[] { Color.rgb(52, 211, 153), Color.rgb(167, 243, 208), Color.rgb(56, 189, 248) },
                null,
                Shader.TileMode.CLAMP
        ));
        if (!visible2.isEmpty()) {
            canvas.drawText(visible2, cx, cy + dp(30f), textPaint2);
        }

        // Progress bar at bottom of card
        float barW = cardW - dp(56f);
        float barTop = cardRect.bottom - dp(42f);
        barRect.set(cx - barW * 0.5f, barTop, cx + barW * 0.5f, barTop + dp(6f));
        canvas.drawRoundRect(barRect, dp(3f), dp(3f), barBgPaint);

        barFillRect.set(barRect.left, barRect.top, barRect.left + barW * progress, barRect.bottom);
        barFillPaint.setShader(new LinearGradient(
                barRect.left, barRect.top, barRect.right, barRect.bottom,
                new int[] { Color.rgb(16, 185, 129), Color.rgb(250, 204, 21) },
                null,
                Shader.TileMode.CLAMP
        ));
        canvas.drawRoundRect(barFillRect, dp(3f), dp(3f), barFillPaint);

        canvas.drawText("Đang xác thực bản quyền & nạp trí tuệ nhân tạo...", cx, cardRect.bottom - dp(15f), subPaint);

        if (elapsed < INTRO_DURATION_MS) {
            postInvalidateOnAnimation();
        } else if (!finished) {
            finished = true;
            CopyrightGuard.markSplashRendered(getContext(), line1, line2, elapsed, w, h, getAlpha());
            if (CopyrightGuard.isSplashCompleted() && onUnlockComplete != null) {
                animate().alpha(0f).setDuration(260L).withEndAction(() -> {
                    setVisibility(GONE);
                    onUnlockComplete.run();
                }).start();
            } else {
                invalidate();
            }
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
