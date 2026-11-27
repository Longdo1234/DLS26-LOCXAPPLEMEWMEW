package com.applemewmew.dlsauto.service;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.WindowManager;

import com.applemewmew.dlsauto.R;
import com.applemewmew.dlsauto.ai.MatchBotEngine;
import com.applemewmew.dlsauto.model.BotConfig;
import com.applemewmew.dlsauto.security.CopyrightGuard;
import com.applemewmew.dlsauto.ui.FloatingOverlayManager;
import com.applemewmew.dlsauto.ui.MainActivity;
import com.applemewmew.dlsauto.vision.RgbaImage;
import com.applemewmew.dlsauto.vision.ScreenGeometry;

/**
 * Foreground MediaProjection Capture & 100-Match AFK Service.
 * Fully compliant with Android 10-14+ foregroundServiceType="mediaProjection" & callback rules.
 */
public final class ScreenCaptureService extends Service {

    public static final String ACTION_START = "com.applemewmew.dlsauto.START_CAPTURE";
    public static final String ACTION_STOP = "com.applemewmew.dlsauto.STOP_CAPTURE";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "dls_auto_pro_channel";
    private static final int NOTIF_ID = 2601;

    private static volatile ScreenCaptureService sInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler workerHandler;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private PowerManager.WakeLock wakeLock;

    private BotConfig config;
    private ScreenGeometry geometry;
    private MatchBotEngine engine;
    private FloatingOverlayManager overlayManager;

    private volatile boolean botPlaying = false;
    private volatile RgbaImage latestFrame = null;
    private volatile MatchBotEngine.StepSnapshot latestSnapshot = new MatchBotEngine.StepSnapshot();

    public static ScreenCaptureService getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        createNotificationChannel();
        workerThread = new HandlerThread("DlsAutoVisionWorker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        config = BotConfig.load(this);
        engine = new MatchBotEngine(config, BotConfig.getTemplatesDir(this), BotConfig.getBrainFile(this));
        refreshScreenGeometry();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelfCleanly();
            return START_NOT_STICKY;
        }

        if (!CopyrightGuard.isIntact(this)) {
            stopSelfCleanly();
            return START_NOT_STICKY;
        }

        Notification notif = buildNotification("Menu Nổi đang sẵn sàng — Mở DLS để treo 100 trận");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, notif);
        }

        if (ACTION_START.equals(intent.getAction()) && mediaProjection == null) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent resultData = getParcelableIntent(intent, EXTRA_RESULT_DATA);
            if (resultData == null) {
                stopSelfCleanly();
                return START_NOT_STICKY;
            }
            setupMediaProjection(resultCode, resultData);
        }

        if (overlayManager == null) {
            overlayManager = new FloatingOverlayManager(this);
            overlayManager.show();
        }
        updateWakeLock();
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private static Intent getParcelableIntent(Intent src, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return src.getParcelableExtra(key, Intent.class);
        }
        return src.getParcelableExtra(key);
    }

    private void setupMediaProjection(int resultCode, Intent resultData) {
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpm.getMediaProjection(resultCode, resultData);
            if (mediaProjection == null) {
                stopSelfCleanly();
                return;
            }
            // Mandatory on Android 14 (API 34+) BEFORE createVirtualDisplay!
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    mainHandler.post(() -> stopSelfCleanly());
                }
            }, mainHandler);

            rebuildVirtualDisplay();
            scheduleNextLoop(300L);
        } catch (Throwable t) {
            stopSelfCleanly();
        }
    }

    public synchronized void refreshScreenGeometry() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);

        int cutLeft = 0, cutRight = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                DisplayCutout cutout = display.getCutout();
                if (cutout != null) {
                    // In landscape, safe insets may appear on left/right (or top/bottom if currently portrait)
                    cutLeft = Math.max(cutout.getSafeInsetLeft(), cutout.getSafeInsetTop());
                    cutRight = Math.max(cutout.getSafeInsetRight(), cutout.getSafeInsetBottom());
                }
            } catch (Throwable ignored) {}
        }

        geometry = new ScreenGeometry(dm.widthPixels, dm.heightPixels, cutLeft, cutRight, config.safeMarginOverridePx);
        AutoAccessibilityService acc = AutoAccessibilityService.getInstance();
        if (acc != null) {
            acc.setScreenGeometry(geometry);
        }
    }

    private synchronized void rebuildVirtualDisplay() {
        if (mediaProjection == null) return;
        refreshScreenGeometry();

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);

        // Capture directly at aspect-preserved 720p when in Landscape!
        boolean isLandscapeNow = dm.widthPixels >= dm.heightPixels;
        int capW = isLandscapeNow ? geometry.normWidth : ScreenGeometry.NORM_HEIGHT;
        int capH = isLandscapeNow ? ScreenGeometry.NORM_HEIGHT : geometry.normWidth;

        ImageReader oldReader = imageReader;
        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);

        if (virtualDisplay == null) {
            // Create VirtualDisplay ONLY ONCE (Android 14+ forbids calling createVirtualDisplay twice!)
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "DlsAutoProCapture",
                    capW,
                    capH,
                    dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    workerHandler
            );
        } else {
            // Seamlessly resize & rebind surface on Portrait <-> Landscape rotation (Android 8 - 16 safe!)
            virtualDisplay.resize(capW, capH, dm.densityDpi);
            virtualDisplay.setSurface(imageReader.getSurface());
        }

        if (oldReader != null) {
            try { oldReader.close(); } catch (Throwable ignored) {}
        }
    }

    private void scheduleNextLoop(long delayMs) {
        if (workerHandler == null) return;
        workerHandler.removeCallbacks(loopRunnable);
        workerHandler.postDelayed(loopRunnable, Math.max(60L, delayMs));
    }

    private final Runnable loopRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaProjection == null || imageReader == null) return;

            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            boolean isLandscapeNow = dm.widthPixels >= dm.heightPixels;
            int expectedW = isLandscapeNow ? geometry.normWidth : ScreenGeometry.NORM_HEIGHT;
            if (Math.abs(imageReader.getWidth() - expectedW) > 8) {
                rebuildVirtualDisplay();
                scheduleNextLoop(350L);
                return;
            }

            RgbaImage frame = acquireLatestNormalizedFrame();
            if (frame != null) {
                latestFrame = frame;
            }

            AutoAccessibilityService acc = AutoAccessibilityService.getInstance();
            if (isLandscapeNow && (latestFrame == null || latestFrame.isBlankOrBlack()) && acc != null) {
                RgbaImage hwFallback = acc.captureFallbackScreenshot(geometry.normWidth, ScreenGeometry.NORM_HEIGHT);
                if (hwFallback != null && !hwFallback.isBlankOrBlack()) {
                    latestFrame = hwFallback;
                }
            }

            if (botPlaying && isLandscapeNow && latestFrame != null) {
                if (latestFrame.isBlankOrBlack()) {
                    latestSnapshot.state = "NO_VIDEO_LAYER";
                    latestSnapshot.actionDetail = "Giả lập PC ẩn lớp 3D OpenGL (Điện thoại Android thật chạy bình thường!)";
                    if (overlayManager != null) {
                        mainHandler.post(() -> overlayManager.onStepUpdate(latestSnapshot, botPlaying));
                    }
                } else if (acc != null) {
                    acc.setScreenGeometry(geometry);
                    MatchBotEngine.StepSnapshot snap = engine.step(latestFrame, geometry, acc, false);
                    latestSnapshot = snap;
                    if (snap.limitOrOnlineStopped) {
                        botPlaying = false;
                        updateWakeLock();
                        updateNotificationText(snap.stopReason != null ? snap.stopReason : "Đã dừng tự động");
                    }
                    if (overlayManager != null) {
                        mainHandler.post(() -> overlayManager.onStepUpdate(snap, botPlaying));
                    }
                } else {
                    latestSnapshot.actionDetail = "Chưa bật Dịch vụ Trợ năng DLS Auto!";
                    if (overlayManager != null) {
                        mainHandler.post(() -> overlayManager.onStepUpdate(latestSnapshot, botPlaying));
                    }
                }
            } else if (botPlaying && !isLandscapeNow) {
                latestSnapshot.actionDetail = "Hãy xoay ngang vào game DLS...";
                if (overlayManager != null) {
                    mainHandler.post(() -> overlayManager.onStepUpdate(latestSnapshot, botPlaying));
                }
            }

            long nextDelay;
            if (!botPlaying) {
                nextDelay = 650L;
            } else if (engine.isFastSkipMode()) {
                nextDelay = config.fastSkipIntervalMs;
            } else {
                nextDelay = config.loopIntervalMs;
            }
            scheduleNextLoop(nextDelay);
        }
    };

    public synchronized RgbaImage acquireLatestNormalizedFrame() {
        if (imageReader == null) return latestFrame;
        Image img = null;
        try {
            img = imageReader.acquireLatestImage();
            if (img == null) return latestFrame;
            Image.Plane[] planes = img.getPlanes();
            if (planes == null || planes.length == 0) return latestFrame;
            Image.Plane p = planes[0];
            return RgbaImage.fromImagePlaneBuffer(
                    p.getBuffer(),
                    img.getWidth(),
                    img.getHeight(),
                    p.getPixelStride(),
                    p.getRowStride(),
                    img.getWidth(),
                    img.getHeight()
            );
        } catch (Throwable t) {
            return latestFrame;
        } finally {
            if (img != null) {
                try { img.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public RgbaImage getLatestLandscapeFrame() {
        RgbaImage f = acquireLatestNormalizedFrame();
        if (f != null && f.width >= f.height) return f;
        return latestFrame;
    }

    public void setBotPlaying(boolean play) {
        if (!CopyrightGuard.isIntact(this)) {
            this.botPlaying = false;
            return;
        }
        this.botPlaying = play;
        if (!play) {
            AutoAccessibilityService acc = AutoAccessibilityService.getInstance();
            if (acc != null) acc.releaseAll();
            engine.getLearner().save();
        }
        updateWakeLock();
        updateNotificationText(play
                ? "Đang treo DLS (" + engine.matchesCompleted + "/" + (config.maxMatches > 0 ? config.maxMatches : "∞") + " trận)"
                : "Đang tạm dừng — Bấm ▶ trên Menu Nổi để tiếp tục");
        if (overlayManager != null) {
            mainHandler.post(() -> overlayManager.onStepUpdate(latestSnapshot, botPlaying));
        }
    }

    public boolean isBotPlaying() {
        return botPlaying;
    }

    public BotConfig getConfig() {
        return config;
    }

    public ScreenGeometry getGeometry() {
        return geometry;
    }

    public MatchBotEngine getEngine() {
        return engine;
    }

    public MatchBotEngine.StepSnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    public synchronized void reloadAndApplyConfig() {
        this.config = BotConfig.load(this);
        refreshScreenGeometry();
        this.engine.updateConfig(this.config);
        updateWakeLock();
    }

    private void updateWakeLock() {
        try {
            if (botPlaying && config.keepScreenAwake) {
                if (wakeLock == null) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    @SuppressWarnings("deprecation")
                    int level = PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE;
                    wakeLock = pm.newWakeLock(level, "DlsAutoPro::Marathon100WakeLock");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(8 * 60 * 60 * 1000L); // Up to 8 hours for 100 matches
                }
            } else if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {}
    }

    public void stopSelfCleanly() {
        botPlaying = false;
        AutoAccessibilityService acc = AutoAccessibilityService.getInstance();
        if (acc != null) acc.releaseAll();
        if (engine != null) engine.getLearner().save();

        if (workerHandler != null) workerHandler.removeCallbacksAndMessages(null);
        if (overlayManager != null) {
            overlayManager.destroy();
            overlayManager = null;
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Throwable ignored) {}
            imageReader = null;
        }
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Throwable ignored) {}
            mediaProjection = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Throwable ignored) {}
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopSelfCleanly();
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "DLS Auto Pro (Apple Mew Mew)",
                    NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("Trạng thái chạy nền treo 100 trận DLS Offline");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_launcher)
         .setContentTitle("DLS Auto Pro • Apple Mew Mew")
         .setContentText(text)
         .setContentIntent(pi)
         .setOngoing(true);
        return b.build();
    }

    private void updateNotificationText(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable ignored) {}
    }
}
