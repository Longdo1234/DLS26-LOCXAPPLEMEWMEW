package com.applemewmew.dlsauto.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.applemewmew.dlsauto.R;
import com.applemewmew.dlsauto.ai.BrainLearner;
import com.applemewmew.dlsauto.model.BotConfig;
import com.applemewmew.dlsauto.security.CopyrightGuard;
import com.applemewmew.dlsauto.service.AutoAccessibilityService;
import com.applemewmew.dlsauto.service.ScreenCaptureService;
import com.applemewmew.dlsauto.vision.ScreenGeometry;

import java.io.File;

/**
 * Main Application Activity:
 * - Launches with mandatory 3-second animated "Bản quyền thuộc về Apple Mew Mew / Mọi tính năng đều miễn phí" splash
 * - 3 Clean Vietnamese Tabs:
 *   [1] TRANG CHỦ: Permission Checklist, Android Screen Geometry Status, Start Floating Menu, Open DLS, Live AI Stats
 *   [2] CÀI ĐẶT & MẪU: 100-Match Target Selector, Cutout Safe-Margin, WakeLock, Template Thumbnails & DLS25/DLS26 Bundle Import/Export
 *   [3] HƯỚNG DẪN: Complete Step-by-Step Illustrated Vietnamese Tester & User Manual (including Android 13-15 Restricted Settings guide!)
 */
public final class MainActivity extends Activity {

    private static final int REQ_MEDIA_PROJECTION = 1926;
    private static final int REQ_NOTIFICATIONS = 1927;

    private static boolean sIntroShownInProcess = false;

    private FrameLayout rootStack;
    private LinearLayout mainContainer;
    private ScrollView tabHomeScroll;
    private ScrollView tabSettingsScroll;
    private ScrollView tabGuideScroll;

    private Button tabBtnHome;
    private Button tabBtnSettings;
    private Button tabBtnGuide;

    private TextView overlayStatusTv;
    private TextView accessStatusTv;
    private TextView batteryStatusTv;
    private TextView screenGeomTv;
    private TextView statsSummaryTv;
    private Button mainStartBtn;

    private TextView matchTargetValueTv;
    private TextView safeMarginValueTv;
    private Button wakeToggleBtn;
    private Button glyphSnapToggleBtn;
    private LinearLayout templateListContainer;

    private BotConfig config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        config = BotConfig.load(this);

        rootStack = new FrameLayout(this);
        rootStack.setBackgroundColor(Color.rgb(6, 11, 22));

        mainContainer = buildMainUi();
        rootStack.addView(mainContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (!sIntroShownInProcess || !CopyrightGuard.isSplashCompleted()) {
            mainContainer.setVisibility(View.INVISIBLE);
            BrandIntroView introView = new BrandIntroView(this, () -> {
                sIntroShownInProcess = true;
                mainContainer.setVisibility(View.VISIBLE);
                refreshDashboardStates();
            });
            rootStack.addView(introView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        setContentView(rootStack);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIFICATIONS);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        config = BotConfig.load(this);
        if (CopyrightGuard.isSplashCompleted()) {
            refreshDashboardStates();
            populateTemplateList();
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private LinearLayout buildMainUi() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundColor(Color.rgb(6, 11, 22));

        // Top Brand Header
        LinearLayout topHeader = new LinearLayout(this);
        topHeader.setOrientation(LinearLayout.VERTICAL);
        topHeader.setPadding(dp(16), dp(14), dp(16), dp(10));
        GradientDrawable headBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { Color.rgb(10, 25, 44), Color.rgb(8, 36, 38) }
        );
        topHeader.setBackground(headBg);

        TextView appTitle = new TextView(this);
        appTitle.setText("⚽ DLS AUTO PRO • 100 TRẬN OFFLINE");
        appTitle.setTextColor(Color.rgb(250, 204, 21));
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        topHeader.addView(appTitle);

        TextView brandSub = new TextView(this);
        brandSub.setText(getString(R.string.copyright_line_1) + " • " + getString(R.string.copyright_line_2));
        brandSub.setTextColor(Color.rgb(110, 231, 183));
        brandSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        brandSub.setPadding(0, dp(2), 0, 0);
        topHeader.addView(brandSub);
        wrapper.addView(topHeader);

        // Navigation Tab Bar
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        tabBar.setBackgroundColor(Color.rgb(11, 19, 34));

        tabBtnHome = makeTabBtn("⚡ TRANG CHỦ", true);
        tabBtnSettings = makeTabBtn("⚙ CÀI ĐẶT & MẪU", false);
        tabBtnGuide = makeTabBtn("📖 HƯỚNG DẪN", false);

        tabBtnHome.setOnClickListener(v -> switchTab(0));
        tabBtnSettings.setOnClickListener(v -> switchTab(1));
        tabBtnGuide.setOnClickListener(v -> switchTab(2));

        tabBar.addView(tabBtnHome, new LinearLayout.LayoutParams(0, dp(40), 1f));
        tabBar.addView(space(dp(6)));
        tabBar.addView(tabBtnSettings, new LinearLayout.LayoutParams(0, dp(40), 1f));
        tabBar.addView(space(dp(6)));
        tabBar.addView(tabBtnGuide, new LinearLayout.LayoutParams(0, dp(40), 1f));
        wrapper.addView(tabBar);

        // Tab Body Stack
        FrameLayout bodyStack = new FrameLayout(this);
        tabHomeScroll = buildHomeTab();
        tabSettingsScroll = buildSettingsTab();
        tabGuideScroll = buildGuideTab();

        bodyStack.addView(tabHomeScroll);
        bodyStack.addView(tabSettingsScroll);
        bodyStack.addView(tabGuideScroll);
        tabSettingsScroll.setVisibility(View.GONE);
        tabGuideScroll.setVisibility(View.GONE);

        wrapper.addView(bodyStack, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return wrapper;
    }

    private void switchTab(int idx) {
        tabHomeScroll.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        tabSettingsScroll.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        tabGuideScroll.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        styleTabBtn(tabBtnHome, idx == 0);
        styleTabBtn(tabBtnSettings, idx == 1);
        styleTabBtn(tabBtnGuide, idx == 2);
        if (idx == 0) refreshDashboardStates();
        if (idx == 1) populateTemplateList();
    }

    private ScrollView buildHomeTab() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(28));

        // Card 1: Permissions Checklist
        LinearLayout permCard = makeCard();
        permCard.addView(sectionTitle("1. Kiểm Tra & Cấp Quyền Điện Thoại (Chỉ Làm 1 Lần)"));

        overlayStatusTv = bodyText("");
        Button overlayBtn = makePrimaryBtn("Bật Quyền Nút Nổi Đè Lên Game", Color.rgb(37, 99, 235));
        overlayBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        permCard.addView(overlayStatusTv);
        permCard.addView(space(dp(4)));
        permCard.addView(overlayBtn);
        permCard.addView(space(dp(10)));

        accessStatusTv = bodyText("");
        LinearLayout accRow = new LinearLayout(this);
        accRow.setOrientation(LinearLayout.HORIZONTAL);
        Button accBtn = makePrimaryBtn("Mở Cài Đặt Trợ Năng", Color.rgb(14, 116, 144));
        accBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button appInfoBtn = makePrimaryBtn("Mở Quyền Hạn Chế (Android 13+)", Color.rgb(71, 85, 105));
        appInfoBtn.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this, "Nếu dòng Trợ năng bị mờ trên Android 13+, bấm dấu ⋮ góc trên phải -> 'Cho phép cài đặt bị hạn chế'!", Toast.LENGTH_LONG).show();
        });
        accRow.addView(accBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        accRow.addView(space(dp(6)));
        accRow.addView(appInfoBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        permCard.addView(accessStatusTv);
        permCard.addView(space(dp(4)));
        permCard.addView(accRow);
        permCard.addView(space(dp(10)));

        batteryStatusTv = bodyText("");
        Button batBtn = makePrimaryBtn("Cho Phép Chạy Nền Không Bị Tắt Ngầm (100 Trận)", Color.rgb(79, 70, 229));
        batBtn.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Throwable t) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });
        permCard.addView(batteryStatusTv);
        permCard.addView(space(dp(4)));
        permCard.addView(batBtn);
        col.addView(permCard);
        col.addView(space(dp(12)));

        // Card 2: Launch & Game Start
        LinearLayout runCard = makeCard();
        runCard.addView(sectionTitle("2. Bắt Đầu Menu Nổi & Vào Game DLS"));
        screenGeomTv = bodyText("");
        runCard.addView(screenGeomTv);
        runCard.addView(space(dp(8)));

        mainStartBtn = makePrimaryBtn("🚀 BẬT MENU NỔI & CẤP QUYỀN ĐỌC MÀN HÌNH", Color.rgb(16, 185, 129));
        mainStartBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        mainStartBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        mainStartBtn.setOnClickListener(v -> onToggleServiceClicked());
        runCard.addView(mainStartBtn);
        runCard.addView(space(dp(8)));

        Button openDlsBtn = makePrimaryBtn("⚽ MỞ NGAY GAME DREAM LEAGUE SOCCER", Color.rgb(217, 119, 6));
        openDlsBtn.setOnClickListener(v -> launchDlsGame());
        runCard.addView(openDlsBtn);
        col.addView(runCard);
        col.addView(space(dp(12)));

        // Card 3: Live Session & AI Brain Stats
        LinearLayout statCard = makeCard();
        statCard.addView(sectionTitle("3. Thống Kê Treo & Trí Tuệ Nhân Tạo AI"));
        statsSummaryTv = bodyText("");
        statCard.addView(statsSummaryTv);
        col.addView(statCard);

        sv.addView(col);
        return sv;
    }

    private ScrollView buildSettingsTab() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(28));

        // Match count card
        LinearLayout matchCard = makeCard();
        matchCard.addView(sectionTitle("Số Trận Hoàn Thành Nhiệm Vụ (Mặc Định 100 Trận)"));
        matchTargetValueTv = bodyText("");
        matchTargetValueTv.setTextColor(Color.rgb(52, 211, 153));
        matchTargetValueTv.setTypeface(Typeface.DEFAULT_BOLD);
        matchCard.addView(matchTargetValueTv);
        matchCard.addView(space(dp(6)));

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        int[] presets = new int[] { 10, 50, 100, 0 };
        String[] presetLabels = new String[] { "10 trận", "50 trận", "100 trận", "Vô hạn ∞" };
        for (int i = 0; i < presets.length; i++) {
            final int val = presets[i];
            Button b = makePrimaryBtn(presetLabels[i], val == 100 ? Color.rgb(16, 185, 129) : Color.rgb(51, 65, 85));
            b.setOnClickListener(v -> {
                config.maxMatches = val;
                saveAndSyncConfig();
            });
            presetRow.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (i < presets.length - 1) presetRow.addView(space(dp(5)));
        }
        matchCard.addView(presetRow);
        col.addView(matchCard);
        col.addView(space(dp(12)));

        // Android Screen & Battery Options
        LinearLayout optCard = makeCard();
        optCard.addView(sectionTitle("Tối Ưu Khung Hình Điện Thoại Android & Treo Đêm"));

        wakeToggleBtn = makePrimaryBtn("", Color.rgb(14, 116, 144));
        wakeToggleBtn.setOnClickListener(v -> {
            config.keepScreenAwake = !config.keepScreenAwake;
            saveAndSyncConfig();
        });
        optCard.addView(wakeToggleBtn);
        optCard.addView(space(dp(6)));

        glyphSnapToggleBtn = makePrimaryBtn("", Color.rgb(37, 99, 235));
        glyphSnapToggleBtn.setOnClickListener(v -> {
            config.autoSnapGlyphButtons = !config.autoSnapGlyphButtons;
            saveAndSyncConfig();
        });
        optCard.addView(glyphSnapToggleBtn);
        optCard.addView(space(dp(10)));

        safeMarginValueTv = bodyText("");
        optCard.addView(safeMarginValueTv);
        optCard.addView(space(dp(4)));

        LinearLayout marginRow = new LinearLayout(this);
        marginRow.setOrientation(LinearLayout.HORIZONTAL);
        int[] margins = new int[] { -1, 0, 24, 42, 60 };
        String[] mLabels = new String[] { "Tự động", "0px", "24px", "42px", "60px" };
        for (int i = 0; i < margins.length; i++) {
            final int mv = margins[i];
            Button mb = makePrimaryBtn(mLabels[i], Color.rgb(51, 65, 85));
            mb.setOnClickListener(v -> {
                config.safeMarginOverridePx = mv;
                saveAndSyncConfig();
            });
            marginRow.addView(mb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (i < margins.length - 1) marginRow.addView(space(dp(4)));
        }
        optCard.addView(marginRow);
        col.addView(optCard);
        col.addView(space(dp(12)));

        // Template Management & DLS25/DLS26 Preset Share
        LinearLayout tplCard = makeCard();
        tplCard.addView(sectionTitle("Bộ Mẫu Nhận Diện (DLS25 Nạp Sẵn • Hỗ Trợ Cắt DLS26)"));
        tplCard.addView(bodyText("Mẹo: Để cắt lại mẫu trực tiếp trên điện thoại (hoặc chuyển sang bản DLS26), hãy bật Menu Nổi trong game rồi bấm nút [✂ CẮT NÚT MẪU]."));
        tplCard.addView(space(dp(8)));

        LinearLayout shareRow = new LinearLayout(this);
        shareRow.setOrientation(LinearLayout.HORIZONTAL);
        Button exportBtn = makePrimaryBtn("📤 Sao Chép Gói Mẫu", Color.rgb(16, 185, 129));
        exportBtn.setOnClickListener(v -> exportBundleToClipboard());

        Button importBtn = makePrimaryBtn("📥 Dán Gói Mẫu", Color.rgb(124, 58, 237));
        importBtn.setOnClickListener(v -> importBundleFromClipboard());

        Button resetBtn = makePrimaryBtn("🔄 Khôi Phục Gốc", Color.rgb(190, 18, 60));
        resetBtn.setOnClickListener(v -> confirmResetDefaultTemplates());

        shareRow.addView(exportBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        shareRow.addView(space(dp(5)));
        shareRow.addView(importBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        shareRow.addView(space(dp(5)));
        shareRow.addView(resetBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tplCard.addView(shareRow);
        tplCard.addView(space(dp(10)));

        templateListContainer = new LinearLayout(this);
        templateListContainer.setOrientation(LinearLayout.VERTICAL);
        tplCard.addView(templateListContainer);
        col.addView(tplCard);

        sv.addView(col);
        return sv;
    }

    private ScrollView buildGuideTab() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(32));

        col.addView(guideCard(
                "🌟 Giới Thiệu Bản APK Độc Lập",
                "• Phát triển & Bản quyền: Apple Mew Mew — Mọi tính năng hoàn toàn miễn phí 100%.\n"
                + "• Dành riêng cho chế độ Đá Với Máy Offline (Career / Exhibition) trên điện thoại Android (màn hình dài 20:9, 19.5:9, 18:9 và 16:9).\n"
                + "• AI điều khiển đa điểm 2 ngón thực thụ: Ngón trái xoay giữ Joystick dẫn bóng lắt léo + Ngón phải nhấn giữ nạp lực A/B/C theo mili-giây (chọc khe đón đà, bật tường 1-2, căng ngang cột 2, sút góc ngược thủ môn, kèm người hướng khung thành, GK phát bóng tự động)."
        ));
        col.addView(space(dp(10)));

        col.addView(guideCard(
                "🚀 Bước 1: Cấp 2 Quyền Cơ Bản (Chỉ Làm Lần Đầu)",
                "1. Tại tab [⚡ TRANG CHỦ], bấm nút 'Bật Quyền Nút Nổi Đè Lên Game' -> gạt BẬT cho DLS Auto Pro.\n"
                + "2. Bấm 'Mở Cài Đặt Trợ Năng' -> tìm mục 'Dịch vụ đã tải xuống' -> chọn 'DLS Auto - AI Đa Điểm (Apple Mew Mew)' -> gạt BẬT.\n"
                + "💡 LƯU Ý QUAN TRỌNG CHO ANDROID 13 / 14 / 15:\n"
                + "Nếu dòng Trợ năng bị làm mờ và báo 'Cài đặt bị hạn chế', bạn chỉ cần quay lại app bấm nút 'Mở Quyền Hạn Chế (Android 13+)' -> bấm dấu 3 chấm (⋮) ở góc trên bên phải màn hình Thông tin ứng dụng -> chọn 'Cho phép cài đặt bị hạn chế', sau đó vào lại Trợ năng sẽ bật được ngay!"
        ));
        col.addView(space(dp(10)));

        col.addView(guideCard(
                "⚽ Bước 2: Khởi Động Menu Nổi & Treo 100 Trận",
                "1. Bấm nút xanh '🚀 BẬT MENU NỔI & CẤP QUYỀN ĐỌC MÀN HÌNH'.\n"
                + "2. Khi hộp thoại Android hiện lên: nếu máy hỏi 'Một ứng dụng' hay 'Toàn màn hình', hãy chọn 'TOÀN MÀN HÌNH (Entire Screen)' rồi bấm 'Bắt đầu ngay'.\n"
                + "3. Một viên thuốc nổi nhỏ gọn sẽ hiện ở mép trên màn hình. Bấm '⚽ MỞ NGAY GAME DREAM LEAGUE SOCCER'.\n"
                + "4. Khi đã vào màn hình chính của DLS, chạm vào viên thuốc nổi -> bấm '▶ CHẠY TREO'. Bot sẽ tự động vào trận, tự bỏ qua cảnh quay chậm/ăn mừng, tự sang Hiệp 2, tự bấm Continue hết trận và đá liên tục đủ 100 trận!"
        ));
        col.addView(space(dp(10)));

        col.addView(guideCard(
                "🎮 Bước 3: Cài Đặt Khuyên Dùng Trong Game DLS",
                "Vào biểu tượng Bánh răng (Settings) trong game DLS -> thẻ Controls:\n"
                + "• Auto Switch (Tự đổi người): Để Medium hoặc High.\n"
                + "• Kick Assist (Hỗ trợ chuyền/sút): Để Low hoặc High.\n"
                + "• Analog Stick: Để ON (Mặc định)."
        ));
        col.addView(space(dp(10)));

        col.addView(guideCard(
                "🎯 Bước 4: Căn Lại Nút Hoặc Cắt Mẫu Mới (DLS26 / Máy Màn Dài Đặc Biệt)",
                "Bộ 11 ảnh mẫu chuẩn DLS25 đã được tích hợp sẵn và tự động co giãn theo tỷ lệ màn hình điện thoại (kể cả tự bắt dính chữ A/B trên sân). Tuy nhiên nếu máy bạn chỉnh cỡ nút khác hoặc muốn dùng cho bản DLS26:\n"
                + "• [🎯 ĐẶT TÂM NÚT]: Bấm khi đang ở trong sân đấu -> kéo 4 vòng tròn JOY, B, A, C đè khít lên 4 nút điều khiển của bạn -> bấm '💾 LƯU TÂM NÚT'.\n"
                + "• [✂ CẮT NÚT MẪU]: Bấm ngay tại màn hình có nút cần dạy lại -> chọn tên ô (ví dụ pre_match_play cho nút Play xanh lá, post_match_continue cho nút Continue...) -> dùng ngón tay kéo khung xanh bao gọn nút đó -> bấm '💾 LƯU NÚT MẪU NÀY'.\n"
                + "• [👁 SOI THỬ]: Kiểm tra ngay trên màn hình hiện tại xem bot đang nhận diện khớp bao nhiêu phần trăm mà không tự bấm."
        ));
        col.addView(space(dp(10)));

        col.addView(guideCard(
                "🌙 Bước 5: Mẹo Treo Đêm Mát Máy & An Toàn Tuyệt Đối",
                "• Khi treo 100 trận ban đêm, hãy mở Menu Nổi bấm [🌙 TỐI MÀN 1%]: Đèn nền vật lý của điện thoại sẽ hạ xuống 1% giúp cực kỳ mát máy và tiết kiệm pin, trong khi AI bên trong vẫn nhìn thấy hình ảnh game sáng 100%!\n"
                + "• Chốt chặn an toàn: Công cụ chỉ hoạt động ở chế độ đá Offline với máy. Nếu vô tình lọt vào màn hình đá Online (Dream League Live), bot sẽ tự động khóa thao tác và dừng ngay lập tức."
        ));

        sv.addView(col);
        return sv;
    }

    private void refreshDashboardStates() {
        boolean overlayOk = Settings.canDrawOverlays(this);
        overlayStatusTv.setText(overlayOk
                ? "✔ Quyền Nút Nổi (Overlay): ĐÃ BẬT"
                : "⚠ Quyền Nút Nổi (Overlay): CHƯA BẬT (Cần cấp quyền để hiện nút điều khiển trên game)");
        overlayStatusTv.setTextColor(overlayOk ? Color.rgb(52, 211, 153) : Color.rgb(251, 191, 36));

        boolean accOk = isAccessibilityServiceEnabled();
        accessStatusTv.setText(accOk
                ? "✔ Dịch vụ Trợ năng 2 Ngón (DLS Auto): ĐANG HOẠT ĐỘNG"
                : "⚠ Dịch vụ Trợ năng (DLS Auto): CHƯA BẬT (Cần bật để AI xoay Joystick & nhấn lực A/B/C)");
        accessStatusTv.setTextColor(accOk ? Color.rgb(52, 211, 153) : Color.rgb(251, 191, 36));

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean batOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryStatusTv.setText(batOk
                ? "✔ Chạy nền không giới hạn pin: ĐÃ TỐI ƯU CHO 100 TRẬN"
                : "• Tối ưu hóa pin: Đang bật mặc định (Khuyên tắt tiết kiệm pin để treo 100 trận không bị ngắt)");
        batteryStatusTv.setTextColor(batOk ? Color.rgb(52, 211, 153) : Color.rgb(148, 163, 184));

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int lw = Math.max(dm.widthPixels, dm.heightPixels);
        int lh = Math.max(1, Math.min(dm.widthPixels, dm.heightPixels));
        ScreenGeometry g = new ScreenGeometry(lw, lh, 0, 0, config.safeMarginOverridePx);
        float ratio = (lw * 9.0f) / lh;
        screenGeomTv.setText(String.format(
                "📱 Khung hình máy: %d×%d (Tỷ lệ ~%.1f:9)\n🎯 Chuẩn hóa Android: %d×%d (Neo góc đa màn hình • %s)",
                lw, lh, ratio, g.normWidth, g.normHeight,
                config.calibratedOnPhone ? "Đã căn nút thủ công" : "Tự động căn nút A/B/C"
        ));

        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc != null) {
            mainStartBtn.setText("🛑 TẮT DỊCH VỤ MENU NỔI (ĐANG MỞ)");
            ((GradientDrawable) mainStartBtn.getBackground()).setColor(Color.rgb(190, 18, 60));
        } else {
            mainStartBtn.setText("🚀 BẬT MENU NỔI & CẤP QUYỀN ĐỌC MÀN HÌNH");
            ((GradientDrawable) mainStartBtn.getBackground()).setColor(Color.rgb(16, 185, 129));
        }

        int done = svc != null ? svc.getEngine().matchesCompleted : 0;
        int avg = svc != null ? svc.getEngine().getAverageMatchSec() : 0;
        BrainLearner learner = svc != null
                ? svc.getEngine().getLearner()
                : new BrainLearner(BotConfig.getBrainFile(this));
        statsSummaryTv.setText(
                "• Tiến độ phiên hiện tại: " + done + " / " + (config.maxMatches > 0 ? config.maxMatches : "∞") + " trận"
                + (avg > 0 ? " (Trung bình ~" + avg + "s/trận)" : "") + "\n"
                + "• AI Chiến thuật đã học: " + learner.totalUpdates + " pha bóng (Tỷ lệ tiến bóng: " + learner.getSuccessRatePct() + "%)\n"
                + "• Tổng cú sút AI thực hiện: " + learner.shotsTaken + " | Chuỗi phối hợp ≥4 chạm: " + learner.passChainsCompleted
        );

        matchTargetValueTv.setText("Mục tiêu hiện tại: " + (config.maxMatches > 0 ? (config.maxMatches + " trận liên tiếp") : "Treo vô hạn (∞)"));
        wakeToggleBtn.setText("Giữ sáng màn hình khi treo (WakeLock): " + (config.keepScreenAwake ? "ĐANG BẬT ✔" : "ĐANG TẮT"));
        glyphSnapToggleBtn.setText("Tự động bắt dính tâm nút A/B/C trên sân: " + (config.autoSnapGlyphButtons ? "ĐANG BẬT ✔" : "ĐANG TẮT"));
        safeMarginValueTv.setText("Bù lề camera đục lỗ / tai thỏ hiện tại: "
                + (config.safeMarginOverridePx < 0 ? "Tự động theo điện thoại" : (config.safeMarginOverridePx + " px")));
    }

    private void populateTemplateList() {
        if (templateListContainer == null) return;
        templateListContainer.removeAllViews();
        File tplDir = BotConfig.getTemplatesDir(this);

        for (String slotKey : BotConfig.SLOT_ORDER) {
            BotConfig.TemplateSlot slot = config.templates.get(slotKey);
            if (slot == null) continue;
            File png = new File(tplDir, slot.file);
            boolean exists = png.exists() && png.length() > 32;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(6), dp(8), dp(6));
            GradientDrawable rBg = new GradientDrawable();
            rBg.setColor(Color.rgb(15, 25, 44));
            rBg.setCornerRadius(dp(10));
            row.setBackground(rBg);

            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(Color.rgb(7, 13, 24));
            if (exists) {
                Bitmap b = BitmapFactory.decodeFile(png.getAbsolutePath());
                if (b != null) iv.setImageBitmap(b);
            }
            row.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(38)));
            row.addView(space(dp(10)));

            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            TextView nameTv = new TextView(this);
            nameTv.setText((exists ? "✔ " : "• ") + slotKey + " [" + slot.anchor + (slot.phoneNative ? " • Đã cắt trên máy" : "") + "]");
            nameTv.setTextColor(exists ? Color.rgb(52, 211, 153) : Color.rgb(148, 163, 184));
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            infoCol.addView(nameTv);

            TextView descTv = new TextView(this);
            descTv.setText(slot.label + (exists ? "" : " (Tùy chọn - chưa cắt)"));
            descTv.setTextColor(Color.rgb(203, 213, 225));
            descTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            infoCol.addView(descTv);

            row.addView(infoCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            templateListContainer.addView(row);
            templateListContainer.addView(space(dp(6)));
        }
    }

    private void onToggleServiceClicked() {
        if (!CopyrightGuard.isIntact(this)) {
            Toast.makeText(this, "Ứng dụng bị khóa do chỉnh sửa bản quyền!", Toast.LENGTH_LONG).show();
            return;
        }
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc != null) {
            svc.stopSelfCleanly();
            refreshDashboardStates();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Hãy cấp 'Quyền Nút Nổi Đè Lên Game' ở mục 1 trước nhé!", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Lưu ý: Hãy bật 'Dịch vụ Trợ năng DLS Auto' ở mục 1 để bot có thể bấm được nhé!", Toast.LENGTH_LONG).show();
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent svcIntent = new Intent(this, ScreenCaptureService.class);
                svcIntent.setAction(ScreenCaptureService.ACTION_START);
                svcIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
                svcIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent);
                } else {
                    startService(svcIntent);
                }
                Toast.makeText(this, "✔ Đã bật Menu Nổi! Hãy mở game DLS và bấm ▶ CHẠY TREO.", Toast.LENGTH_LONG).show();
                mainStartBtn.postDelayed(this::refreshDashboardStates, 350L);
            } else {
                Toast.makeText(this, "Bạn đã hủy cấp quyền đọc màn hình.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void launchDlsGame() {
        PackageManager pm = getPackageManager();
        String[] pkgs = new String[] {
                "com.firsttouchgames.dls7",
                "com.firsttouchgames.dr26",
                "com.firsttouchgames.dr25"
        };
        for (String p : pkgs) {
            Intent launch = pm.getLaunchIntentForPackage(p);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
                return;
            }
        }
        Toast.makeText(this, "Không tìm thấy gói DLS mặc định — Hãy tự mở icon game DLS ngoài màn hình chính nhé!", Toast.LENGTH_LONG).show();
    }

    private boolean isAccessibilityServiceEnabled() {
        if (AutoAccessibilityService.getInstance() != null) return true;
        try {
            ComponentName expected = new ComponentName(this, AutoAccessibilityService.class);
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(enabled)) return false;
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(enabled);
            while (splitter.hasNext()) {
                ComponentName cn = ComponentName.unflattenFromString(splitter.next());
                if (cn != null && cn.equals(expected)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void saveAndSyncConfig() {
        config.save(this);
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc != null) svc.reloadAndApplyConfig();
        refreshDashboardStates();
    }

    private void exportBundleToClipboard() {
        try {
            String bundle = config.exportBundleString(this);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("dlsauto_preset", bundle));
            Toast.makeText(this, "✔ Đã sao chép trọn bộ cấu hình & ảnh mẫu (.dlsauto) vào bộ nhớ tạm!", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Lỗi xuất gói: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importBundleFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
                Toast.makeText(this, "Bộ nhớ tạm đang trống! Hãy sao chép chuỗi gói mẫu .dlsauto trước.", Toast.LENGTH_LONG).show();
                return;
            }
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
            if (cs == null) return;
            config = BotConfig.importBundleString(this, cs.toString());
            ScreenCaptureService svc = ScreenCaptureService.getInstance();
            if (svc != null) svc.reloadAndApplyConfig();
            populateTemplateList();
            refreshDashboardStates();
            Toast.makeText(this, "✔ Đã nhập thành công bộ mẫu & cài đặt mới!", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Dữ liệu dán không đúng định dạng .dlsauto!", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmResetDefaultTemplates() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Khôi phục bộ mẫu gốc DLS25?")
                .setMessage("Toàn bộ ảnh mẫu và vị trí nút sẽ quay về mặc định chuẩn ban đầu (100 trận).")
                .setPositiveButton("Khôi phục", (d, w) -> {
                    try {
                        BotConfig.resetTemplatesToDefaultDls25(this);
                        config = BotConfig.load(this);
                        ScreenCaptureService svc = ScreenCaptureService.getInstance();
                        if (svc != null) svc.reloadAndApplyConfig();
                        populateTemplateList();
                        refreshDashboardStates();
                        Toast.makeText(this, "✔ Đã khôi phục bộ mẫu gốc!", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private LinearLayout makeCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(12, 21, 38));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.rgb(30, 41, 59));
        c.setBackground(bg);
        return c;
    }

    private LinearLayout guideCard(String title, String text) {
        LinearLayout c = makeCard();
        c.addView(sectionTitle(title));
        TextView tv = bodyText(text);
        tv.setLineSpacing(dp(3), 1.05f);
        c.addView(tv);
        return c;
    }

    private TextView sectionTitle(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Color.rgb(250, 204, 21));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(6));
        return tv;
    }

    private TextView bodyText(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Color.rgb(226, 232, 240));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        return tv;
    }

    private Button makePrimaryBtn(String text, int color) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        return b;
    }

    private Button makeTabBtn(String text, boolean active) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(6), dp(4), dp(6), dp(4));
        styleTabBtn(b, active);
        return b;
    }

    private void styleTabBtn(Button b, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        if (active) {
            g.setColor(Color.rgb(16, 185, 129));
            b.setTextColor(Color.rgb(6, 11, 22));
        } else {
            g.setColor(Color.rgb(20, 32, 54));
            b.setTextColor(Color.rgb(203, 213, 225));
        }
        b.setBackground(g);
    }

    private View space(int px) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        return v;
    }
}
