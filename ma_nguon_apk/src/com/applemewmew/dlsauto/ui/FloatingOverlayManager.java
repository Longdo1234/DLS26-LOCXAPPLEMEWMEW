package com.applemewmew.dlsauto.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.applemewmew.dlsauto.ai.MatchBotEngine;
import com.applemewmew.dlsauto.model.BotConfig;
import com.applemewmew.dlsauto.service.AutoAccessibilityService;
import com.applemewmew.dlsauto.service.ScreenCaptureService;
import com.applemewmew.dlsauto.vision.RgbaImage;
import com.applemewmew.dlsauto.vision.ScreenGeometry;

import java.io.File;
import java.io.FileOutputStream;

/**
 * In-Game Floating Overlay Controller, 1% Hardware Backlight AFK Dimmer,
 * Interactive 4-Ring HUD Control Calibrator (JOY / A / B / C),
 * and On-Screen Finger Template Cropper (for any Android screen or DLS26 migration!).
 */
public final class FloatingOverlayManager {

    private final ScreenCaptureService service;
    private final WindowManager wm;

    private View pillView;
    private WindowManager.LayoutParams pillParams;
    private TextView pillText;

    private View panelView;
    private WindowManager.LayoutParams panelParams;
    private TextView statusTitleTv;
    private TextView statusActionTv;
    private Button togglePlayBtn;
    private Button dimBtn;

    private View dimPixelView;
    private boolean dimActive = false;

    private View calibratorOverlay;
    private View cropperOverlay;

    public FloatingOverlayManager(ScreenCaptureService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, service.getResources().getDisplayMetrics()));
    }

    private int overlayWindowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    public void show() {
        if (pillView != null) return;

        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);

        // 1. Collapsed Floating Pill (positioned top-right-center away from all DLS HUD & Radar!)
        LinearLayout pill = new LinearLayout(service);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(11), dp(6), dp(12), dp(6));

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.argb(218, 9, 20, 36));
        pillBg.setCornerRadius(dp(20));
        pillBg.setStroke(dp(1.5f), Color.rgb(16, 185, 129));
        pill.setBackground(pillBg);

        pillText = new TextView(service);
        pillText.setText("⚽ TẠM DỪNG • 0/100");
        pillText.setTextColor(Color.WHITE);
        pillText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        pillText.setTypeface(Typeface.DEFAULT_BOLD);
        pill.addView(pillText);

        pillParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        pillParams.gravity = Gravity.TOP | Gravity.START;
        pillParams.x = Math.round(dm.widthPixels * 0.62f);
        pillParams.y = dp(8);

        pill.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float touchX, touchY;
            private boolean dragged;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = pillParams.x;
                        initY = pillParams.y;
                        touchX = ev.getRawX();
                        touchY = ev.getRawY();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(ev.getRawX() - touchX);
                        int dy = Math.round(ev.getRawY() - touchY);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) dragged = true;
                        if (dragged && pillView != null) {
                            pillParams.x = initX + dx;
                            pillParams.y = initY + dy;
                            try { wm.updateViewLayout(pillView, pillParams); } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) {
                            showExpandedPanel();
                        }
                        return true;
                }
                return false;
            }
        });

        pillView = pill;
        try {
            wm.addView(pillView, pillParams);
        } catch (Throwable ignored) {}
    }

    private void showExpandedPanel() {
        if (panelView != null) return;
        if (pillView != null) pillView.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(service);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(238, 10, 22, 39));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1.5f), Color.rgb(250, 204, 21));
        card.setBackground(bg);

        // Header row
        LinearLayout header = new LinearLayout(service);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(service);
        title.setText("⚽ DLS Auto Pro • Apple Mew Mew");
        title.setTextColor(Color.rgb(250, 204, 21));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, tLp);

        Button minBtn = makeMiniBtn("Thu gọn —", Color.rgb(51, 65, 85));
        minBtn.setOnClickListener(v -> hideExpandedPanel());
        header.addView(minBtn);
        card.addView(header);

        statusTitleTv = new TextView(service);
        statusTitleTv.setTextColor(Color.rgb(52, 211, 153));
        statusTitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusTitleTv.setTypeface(Typeface.DEFAULT_BOLD);
        statusTitleTv.setPadding(0, dp(6), 0, dp(2));
        card.addView(statusTitleTv);

        statusActionTv = new TextView(service);
        statusActionTv.setTextColor(Color.rgb(203, 213, 225));
        statusActionTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        statusActionTv.setPadding(0, 0, 0, dp(8));
        card.addView(statusActionTv);

        // Row 1: Play/Pause + Dry Diagnostic
        LinearLayout row1 = new LinearLayout(service);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        togglePlayBtn = makeActionBtn("▶ CHẠY TREO", Color.rgb(16, 185, 129));
        togglePlayBtn.setOnClickListener(v -> {
            if (AutoAccessibilityService.getInstance() == null) {
                Toast.makeText(service, "⚠ Bạn chưa bật Dịch vụ Trợ năng 'DLS Auto' trong Cài đặt!", Toast.LENGTH_LONG).show();
                return;
            }
            boolean next = !service.isBotPlaying();
            service.setBotPlaying(next);
            if (next) hideExpandedPanel();
        });
        Button dryBtn = makeActionBtn("👁 SOI THỬ", Color.rgb(37, 99, 235));
        dryBtn.setOnClickListener(v -> runAndShowDiagnostic());
        row1.addView(togglePlayBtn, weightLp());
        row1.addView(spaceView(dp(6)));
        row1.addView(dryBtn, weightLp());
        card.addView(row1);

        card.addView(spaceView(dp(6)));

        // Row 2: Calibrate Controls (JOY/A/B/C) + On-Screen Template Cropper
        LinearLayout row2 = new LinearLayout(service);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button calibBtn = makeActionBtn("🎯 ĐẶT TÂM NÚT", Color.rgb(14, 116, 144));
        calibBtn.setOnClickListener(v -> openControlCalibrator());

        Button cropBtn = makeActionBtn("✂ CẮT NÚT MẪU", Color.rgb(124, 58, 237));
        cropBtn.setOnClickListener(v -> openTemplateCropper());
        row2.addView(calibBtn, weightLp());
        row2.addView(spaceView(dp(6)));
        row2.addView(cropBtn, weightLp());
        card.addView(row2);

        card.addView(spaceView(dp(6)));

        // Row 3: 1% Hardware Dim AFK + Reset Counter + Exit Overlay
        LinearLayout row3 = new LinearLayout(service);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        dimBtn = makeActionBtn(dimActive ? "☀ SÁNG LẠI" : "🌙 TỐI MÀN 1%", Color.rgb(71, 85, 105));
        dimBtn.setOnClickListener(v -> toggleHardwareDim());

        Button closeBtn = makeActionBtn("✕ TẮT NỔI", Color.rgb(190, 18, 60));
        closeBtn.setOnClickListener(v -> service.stopSelfCleanly());
        row3.addView(dimBtn, weightLp());
        row3.addView(spaceView(dp(6)));
        row3.addView(closeBtn, weightLp());
        card.addView(row3);

        panelParams = new WindowManager.LayoutParams(
                dp(318),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        panelParams.gravity = Gravity.CENTER;

        panelView = card;
        refreshStatusTexts(service.getLatestSnapshot(), service.isBotPlaying());
        try {
            wm.addView(panelView, panelParams);
        } catch (Throwable ignored) {}
    }

    private void hideExpandedPanel() {
        if (panelView != null) {
            try { wm.removeView(panelView); } catch (Throwable ignored) {}
            panelView = null;
        }
        if (pillView != null) {
            pillView.setVisibility(View.VISIBLE);
        }
    }

    public void onStepUpdate(MatchBotEngine.StepSnapshot snap, boolean playing) {
        String maxStr = snap.maxMatches > 0 ? String.valueOf(snap.maxMatches) : "∞";
        if (pillText != null) {
            pillText.setText((playing ? "🟢 " : "⏸ ") + snap.matchesCompleted + "/" + maxStr + " • " + snap.state);
        }
        refreshStatusTexts(snap, playing);
    }

    private void refreshStatusTexts(MatchBotEngine.StepSnapshot snap, boolean playing) {
        if (statusTitleTv == null || snap == null) return;
        String maxStr = service.getConfig().maxMatches > 0 ? String.valueOf(service.getConfig().maxMatches) : "∞";
        statusTitleTv.setText((playing ? "🟢 ĐANG CHẠY" : "⏸ ĐANG DỪNG")
                + " | Trận: " + service.getEngine().matchesCompleted + "/" + maxStr
                + " | " + snap.state);
        statusActionTv.setText(snap.actionDetail != null ? snap.actionDetail : "Sẵn sàng");
        if (togglePlayBtn != null) {
            togglePlayBtn.setText(playing ? "⏸ TẠM DỪNG" : "▶ CHẠY TREO");
            GradientDrawable g = (GradientDrawable) togglePlayBtn.getBackground();
            g.setColor(playing ? Color.rgb(217, 119, 6) : Color.rgb(16, 185, 129));
        }
    }

    /**
     * Dims the physical screen backlight to 1% via WindowManager.LayoutParams.screenBrightness
     * on a tiny 1x1 untouchable corner pixel so MediaProjection STILL sees 100% bright game frames!
     */
    private void toggleHardwareDim() {
        dimActive = !dimActive;
        if (dimActive) {
            if (dimPixelView == null) {
                dimPixelView = new View(service);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        1, 1,
                        overlayWindowType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                );
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.screenBrightness = 0.01f;
                try { wm.addView(dimPixelView, lp); } catch (Throwable ignored) {}
            }
            Toast.makeText(service, "🌙 Đã hạ đèn nền xuống 1% tiết kiệm pin (AI vẫn nhìn rõ 100%)!", Toast.LENGTH_SHORT).show();
        } else {
            if (dimPixelView != null) {
                try { wm.removeView(dimPixelView); } catch (Throwable ignored) {}
                dimPixelView = null;
            }
        }
        if (dimBtn != null) {
            dimBtn.setText(dimActive ? "☀ SÁNG LẠI" : "🌙 TỐI MÀN 1%");
        }
    }

    private void runAndShowDiagnostic() {
        hideExpandedPanel();
        pillView.setVisibility(View.INVISIBLE);
        pillView.postDelayed(() -> {
            service.refreshScreenGeometry();
            RgbaImage frame = service.getLatestLandscapeFrame();
            pillView.setVisibility(View.VISIBLE);
            if (frame == null) {
                Toast.makeText(service, "Hãy xoay ngang vào game DLS trước khi bấm Soi Thử!", Toast.LENGTH_LONG).show();
                return;
            }
            String report = service.getEngine().runDryDiagnostic(frame, service.getGeometry());
            showReportModal("👁 Kết Quả Nhận Diện Khung Hình Điện Thoại", report);
        }, 180L);
    }

    private void showReportModal(String heading, String content) {
        LinearLayout box = new LinearLayout(service);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(244, 10, 20, 36));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1.5f), Color.rgb(56, 189, 248));
        box.setBackground(bg);

        TextView ht = new TextView(service);
        ht.setText(heading);
        ht.setTextColor(Color.rgb(56, 189, 248));
        ht.setTypeface(Typeface.DEFAULT_BOLD);
        ht.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        box.addView(ht);

        ScrollView sv = new ScrollView(service);
        TextView body = new TextView(service);
        body.setText(content);
        body.setTextColor(Color.WHITE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        body.setPadding(0, dp(8), 0, dp(8));
        sv.addView(body);
        box.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)));

        Button ok = makeActionBtn("ĐÓNG", Color.rgb(16, 185, 129));
        ok.setOnClickListener(v -> {
            try { wm.removeView(box); } catch (Throwable ignored) {}
        });
        box.addView(ok);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(340), WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.CENTER;
        try { wm.addView(box, lp); } catch (Throwable ignored) {}
    }

    /**
     * Interactive 4-Ring On-Screen HUD Control Calibrator (JOY / B / A / C) directly over live DLS!
     */
    private void openControlCalibrator() {
        service.setBotPlaying(false);
        hideExpandedPanel();
        if (calibratorOverlay != null) return;
        if (pillView != null) pillView.setVisibility(View.GONE);

        service.refreshScreenGeometry();
        final ScreenGeometry geom = service.getGeometry();
        final BotConfig cfg = service.getConfig();
        final ScreenGeometry.ResolvedControls rc = geom.resolveControls(cfg);

        final float[][] ptsPhys = new float[][] {
                { geom.toPhysX(rc.joystick.x), geom.toPhysY(rc.joystick.y), dp(36) },
                { geom.toPhysX(rc.buttonB.x), geom.toPhysY(rc.buttonB.y), dp(26) },
                { geom.toPhysX(rc.buttonA.x), geom.toPhysY(rc.buttonA.y), dp(26) },
                { geom.toPhysX(rc.buttonC.x), geom.toPhysY(rc.buttonC.y), dp(26) }
        };
        final String[] names = new String[] { "JOY", "B", "A", "C" };

        FrameLayout root = new FrameLayout(service);

        View canvasView = new View(service) {
            private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint ringP = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint txtP = new Paint(Paint.ANTI_ALIAS_FLAG);
            private int draggingIdx = -1;

            {
                ringP.setStyle(Paint.Style.STROKE);
                ringP.setStrokeWidth(dp(2.5f));
                txtP.setColor(Color.WHITE);
                txtP.setTextAlign(Paint.Align.CENTER);
                txtP.setTypeface(Typeface.DEFAULT_BOLD);
                txtP.setTextSize(dp(13));
            }

            @Override
            protected void onDraw(Canvas c) {
                c.drawColor(Color.argb(95, 4, 12, 24));
                for (int i = 0; i < 4; i++) {
                    float x = ptsPhys[i][0], y = ptsPhys[i][1], r = ptsPhys[i][2];
                    fillP.setColor(i == 0 ? Color.argb(110, 14, 165, 233) : Color.argb(120, 16, 185, 129));
                    ringP.setColor(i == 0 ? Color.rgb(56, 189, 248) : Color.rgb(250, 204, 21));
                    c.drawCircle(x, y, r, fillP);
                    c.drawCircle(x, y, r, ringP);
                    c.drawText(names[i], x, y + dp(4.5f), txtP);
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                float x = ev.getX(), y = ev.getY();
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        draggingIdx = -1;
                        float bestD = dp(52);
                        for (int i = 0; i < 4; i++) {
                            float d = (float) Math.hypot(x - ptsPhys[i][0], y - ptsPhys[i][1]);
                            if (d < bestD) {
                                bestD = d;
                                draggingIdx = i;
                            }
                        }
                        return draggingIdx >= 0;
                    case MotionEvent.ACTION_MOVE:
                        if (draggingIdx >= 0) {
                            ptsPhys[draggingIdx][0] = x;
                            ptsPhys[draggingIdx][1] = y;
                            invalidate();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        draggingIdx = -1;
                        return true;
                }
                return false;
            }
        };
        root.addView(canvasView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = new LinearLayout(service);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(235, 15, 23, 42));
        barBg.setCornerRadius(dp(14));
        topBar.setBackground(barBg);

        TextView hint = new TextView(service);
        hint.setText("Kéo 4 vòng JOY / B / A / C trùng đúng nút trên màn hình:");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        topBar.addView(hint);
        topBar.addView(spaceView(dp(8)));

        Button autoBtn = makeMiniBtn("Tự động", Color.rgb(37, 99, 235));
        autoBtn.setOnClickListener(v -> {
            cfg.calibratedOnPhone = false;
            cfg.save(service);
            service.reloadAndApplyConfig();
            closeCalibrator();
            Toast.makeText(service, "Đã bật chế độ Tự động căn nút theo màn hình máy!", Toast.LENGTH_SHORT).show();
        });
        topBar.addView(autoBtn);
        topBar.addView(spaceView(dp(6)));

        Button saveBtn = makeMiniBtn("💾 LƯU TÂM NÚT", Color.rgb(16, 185, 129));
        saveBtn.setOnClickListener(v -> {
            cfg.calibratedOnPhone = true;
            cfg.calibratedNormW = geom.normWidth;
            cfg.joystick.x = geom.toNormX(ptsPhys[0][0]);
            cfg.joystick.y = geom.toNormY(ptsPhys[0][1]);
            cfg.buttonB.x = geom.toNormX(ptsPhys[1][0]);
            cfg.buttonB.y = geom.toNormY(ptsPhys[1][1]);
            cfg.buttonA.x = geom.toNormX(ptsPhys[2][0]);
            cfg.buttonA.y = geom.toNormY(ptsPhys[2][1]);
            cfg.buttonC.x = geom.toNormX(ptsPhys[3][0]);
            cfg.buttonC.y = geom.toNormY(ptsPhys[3][1]);
            cfg.save(service);
            service.reloadAndApplyConfig();
            closeCalibrator();
            Toast.makeText(service, "✔ Đã lưu vị trí JOY / A / B / C cho màn hình máy này!", Toast.LENGTH_SHORT).show();
        });
        topBar.addView(saveBtn);
        topBar.addView(spaceView(dp(6)));

        Button cancelBtn = makeMiniBtn("Hủy", Color.rgb(190, 18, 60));
        cancelBtn.setOnClickListener(v -> closeCalibrator());
        topBar.addView(cancelBtn);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        barLp.topMargin = dp(10);
        root.addView(topBar, barLp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        calibratorOverlay = root;
        try { wm.addView(calibratorOverlay, lp); } catch (Throwable ignored) {}
    }

    private void closeCalibrator() {
        if (calibratorOverlay != null) {
            try { wm.removeView(calibratorOverlay); } catch (Throwable ignored) {}
            calibratorOverlay = null;
        }
        if (pillView != null) pillView.setVisibility(View.VISIBLE);
    }

    /**
     * On-Screen Finger Template Cropper: Freezes live game frame and lets user drag a rectangle
     * around any button (DLS25 or DLS26!) to save directly as a phone-native template!
     */
    private void openTemplateCropper() {
        service.setBotPlaying(false);
        hideExpandedPanel();
        if (pillView != null) pillView.setVisibility(View.INVISIBLE);

        pillView.postDelayed(() -> {
            service.refreshScreenGeometry();
            final ScreenGeometry geom = service.getGeometry();
            final RgbaImage frozen = service.getLatestLandscapeFrame();
            if (frozen == null) {
                if (pillView != null) pillView.setVisibility(View.VISIBLE);
                Toast.makeText(service, "Hãy mở game DLS nằm ngang trước khi cắt mẫu!", Toast.LENGTH_LONG).show();
                return;
            }
            showCropperWithFrame(frozen, geom);
        }, 180L);
    }

    private void showCropperWithFrame(final RgbaImage frozen, final ScreenGeometry geom) {
        if (cropperOverlay != null) return;
        final Bitmap bmp = frozen.toBitmap();
        final RectF selPhys = new RectF(dp(180), dp(120), dp(320), dp(195));
        final String[] selectableSlots = new String[] {
                "home_career", "start_new_division", "lobby_play", "pre_match_play", "in_match_hud",
                "half_time_resume", "pause_resume", "post_match_continue",
                "replay_exit", "nav_back", "sub_confirm", "popup_close", "online_live_guard"
        };
        final int[] chosenSlotIdx = new int[] { 2 }; // Default pre_match_play

        FrameLayout root = new FrameLayout(service);

        View cropCanvas = new View(service) {
            private final Paint imgP = new Paint(Paint.FILTER_BITMAP_FLAG);
            private final Paint boxP = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint shadeP = new Paint();
            private float startX, startY;

            {
                boxP.setStyle(Paint.Style.STROKE);
                boxP.setStrokeWidth(dp(2.5f));
                boxP.setColor(Color.rgb(16, 235, 158));
                shadeP.setColor(Color.argb(65, 0, 0, 0));
            }

            @Override
            protected void onDraw(Canvas c) {
                RectF dst = new RectF(0, 0, getWidth(), getHeight());
                c.drawBitmap(bmp, null, dst, imgP);
                c.drawRect(dst, shadeP);
                c.save();
                c.clipRect(selPhys);
                c.drawBitmap(bmp, null, dst, imgP);
                c.restore();
                c.drawRect(selPhys, boxP);
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                float x = ev.getX(), y = ev.getY();
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = x;
                        startY = y;
                        selPhys.set(x, y, x + dp(10), y + dp(10));
                        invalidate();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                    case MotionEvent.ACTION_UP:
                        selPhys.set(
                                Math.min(startX, x),
                                Math.min(startY, y),
                                Math.max(startX, x),
                                Math.max(startY, y)
                        );
                        invalidate();
                        return true;
                }
                return false;
            }
        };
        root.addView(cropCanvas, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(service);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(238, 11, 22, 38));
        barBg.setCornerRadius(dp(14));
        bar.setBackground(barBg);

        final TextView slotLabelTv = new TextView(service);
        slotLabelTv.setText("✂ Kéo tay khoanh nút trên ảnh -> Đang chọn ô: [" + selectableSlots[chosenSlotIdx[0]] + "]");
        slotLabelTv.setTextColor(Color.rgb(250, 204, 21));
        slotLabelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        slotLabelTv.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(slotLabelTv);

        HorizontalScrollView hsv = new HorizontalScrollView(service);
        LinearLayout chipRow = new LinearLayout(service);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, dp(4), 0, dp(4));
        for (int i = 0; i < selectableSlots.length; i++) {
            final int idx = i;
            Button chip = makeMiniBtn(selectableSlots[i], Color.rgb(30, 58, 138));
            chip.setOnClickListener(v -> {
                chosenSlotIdx[0] = idx;
                slotLabelTv.setText("✂ Kéo tay khoanh nút trên ảnh -> Đang chọn ô: [" + selectableSlots[idx] + "]");
            });
            chipRow.addView(chip);
            chipRow.addView(spaceView(dp(4)));
        }
        hsv.addView(chipRow);
        bar.addView(hsv, new LinearLayout.LayoutParams(dp(460), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actRow = new LinearLayout(service);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        Button saveCropBtn = makeMiniBtn("💾 LƯU NÚT MẪU NÀY", Color.rgb(16, 185, 129));
        saveCropBtn.setOnClickListener(v -> {
            int cwView = Math.max(1, cropCanvas.getWidth());
            int chView = Math.max(1, cropCanvas.getHeight());
            int nx = Math.round((selPhys.left * frozen.width) / cwView);
            int ny = Math.round((selPhys.top * frozen.height) / chView);
            int nw = Math.round((selPhys.width() * frozen.width) / cwView);
            int nh = Math.round((selPhys.height() * frozen.height) / chView);
            if (nw < 14 || nh < 14) {
                Toast.makeText(service, "Khung cắt quá nhỏ! Hãy kéo ngón tay bao quanh nút rõ hơn.", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                String slotKey = selectableSlots[chosenSlotIdx[0]];
                RgbaImage cropped = frozen.crop(nx, ny, nw, nh);
                Bitmap croppedBmp = cropped.toBitmap();
                BotConfig cfg = service.getConfig();
                BotConfig.TemplateSlot slot = cfg.templates.get(slotKey);
                if (slot != null) {
                    File outPng = new File(BotConfig.getTemplatesDir(service), slot.file);
                    try (FileOutputStream fos = new FileOutputStream(outPng)) {
                        croppedBmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    }
                    slot.phoneNative = true;
                    slot.phoneNormW = frozen.width;
                    slot.anchor = (nx + nw / 2 < frozen.width * 0.36)
                            ? "LEFT"
                            : ((nx + nw / 2 > frozen.width * 0.64) ? "RIGHT" : "CENTER");
                    int padX = 56, padY = 46;
                    int rx = Math.max(0, nx - padX);
                    int ry = Math.max(0, ny - padY);
                    int rw = Math.min(frozen.width - rx, nw + padX * 2);
                    int rh = Math.min(frozen.height - ry, nh + padY * 2);
                    slot.roi = new BotConfig.Roi(rx, ry, rw, rh);
                    // Direct tap on the cropped button!
                    slot.tapDx = 0;
                    slot.tapDy = 0;
                    cfg.save(service);
                    service.reloadAndApplyConfig();
                    Toast.makeText(service, "✔ Đã lưu mẫu [" + slotKey + "] chuẩn theo màn hình điện thoại!", Toast.LENGTH_LONG).show();
                }
                croppedBmp.recycle();
                closeCropper(bmp);
            } catch (Throwable t) {
                Toast.makeText(service, "Lỗi lưu mẫu: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        actRow.addView(saveCropBtn);
        actRow.addView(spaceView(dp(8)));

        Button closeCropBtn = makeMiniBtn("Đóng", Color.rgb(190, 18, 60));
        closeCropBtn.setOnClickListener(v -> closeCropper(bmp));
        actRow.addView(closeCropBtn);
        bar.addView(actRow);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        barLp.topMargin = dp(6);
        root.addView(bar, barLp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        cropperOverlay = root;
        try { wm.addView(cropperOverlay, lp); } catch (Throwable ignored) {}
    }

    private void closeCropper(Bitmap bmp) {
        if (cropperOverlay != null) {
            try { wm.removeView(cropperOverlay); } catch (Throwable ignored) {}
            cropperOverlay = null;
        }
        if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        if (pillView != null) pillView.setVisibility(View.VISIBLE);
    }

    public void destroy() {
        closeCalibrator();
        closeCropper(null);
        if (dimPixelView != null) {
            try { wm.removeView(dimPixelView); } catch (Throwable ignored) {}
            dimPixelView = null;
        }
        if (panelView != null) {
            try { wm.removeView(panelView); } catch (Throwable ignored) {}
            panelView = null;
        }
        if (pillView != null) {
            try { wm.removeView(pillView); } catch (Throwable ignored) {}
            pillView = null;
        }
    }

    private Button makeActionBtn(String text, int color) {
        Button b = new Button(service);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        return b;
    }

    private Button makeMiniBtn(String text, int color) {
        Button b = new Button(service);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setMinimumHeight(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(8));
        b.setBackground(g);
        return b;
    }

    private LinearLayout.LayoutParams weightLp() {
        return new LinearLayout.LayoutParams(0, dp(38), 1f);
    }

    private View spaceView(int px) {
        View v = new View(service);
        v.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        return v;
    }
}
