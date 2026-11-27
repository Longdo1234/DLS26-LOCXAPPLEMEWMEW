package com.applemewmew.dlsauto.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import com.applemewmew.dlsauto.ai.TouchDispatcher;
import com.applemewmew.dlsauto.security.CopyrightGuard;
import com.applemewmew.dlsauto.vision.RgbaImage;
import com.applemewmew.dlsauto.vision.ScreenGeometry;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Genuine 2-Finger Multi-Touch Accessibility Engine for Android 8.0+ (API 26+).
 * Supports:
 * - Continuous thumb dribbling on Joystick via StrokeDescription(..., willContinue = true)
 * - Simultaneous 2-Finger Aim + Millisecond Variable Power-Bar A/B/C Kick
 * - Simultaneous 2-Finger Goal-Side Jockey + Continuous B Press
 * - Simultaneous 2-Finger Joystick + Right-Screen Skill Swipe
 */
public final class AutoAccessibilityService extends AccessibilityService implements TouchDispatcher {

    private static volatile AutoAccessibilityService sInstance;

    private final Random rng = new Random();
    private volatile ScreenGeometry activeGeometry;

    private GestureDescription.StrokeDescription continuedJoyStroke = null;
    private float lastJoyPhysX = 0f;
    private float lastJoyPhysY = 0f;

    // Button-B finger held DOWN across consecutive pressing ticks (no lift between gestures)
    private GestureDescription.StrokeDescription continuedBStroke = null;
    private float lastBPhysX = 0f;
    private float lastBPhysY = 0f;

    public static AutoAccessibilityService getInstance() {
        return sInstance;
    }

    public void setScreenGeometry(ScreenGeometry geom) {
        this.activeGeometry = geom;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        CopyrightGuard.validateContext(this);
        sInstance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed; vision uses MediaProjection frames directly.
    }

    @Override
    public void onInterrupt() {
        releaseAll();
    }

    @Override
    public void onDestroy() {
        releaseAll();
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }

    @Override
    public boolean isReady() {
        return sInstance != null && activeGeometry != null;
    }

    private float mapX(int normX, int jitterPx) {
        ScreenGeometry g = activeGeometry;
        if (g == null) return normX;
        int j = jitterPx > 0 ? (rng.nextInt(jitterPx * 2 + 1) - jitterPx) : 0;
        return g.toPhysX(normX + j) + CopyrightGuard.coordCipherOffset();
    }

    private float mapY(int normY, int jitterPx) {
        ScreenGeometry g = activeGeometry;
        if (g == null) return normY;
        int j = jitterPx > 0 ? (rng.nextInt(jitterPx * 2 + 1) - jitterPx) : 0;
        return g.toPhysY(normY + j) + CopyrightGuard.coordCipherOffset();
    }

    @Override
    public synchronized void tap(int normX, int normY, int durationMs, int jitterPx) {
        releaseAll();
        float px = mapX(normX, jitterPx);
        float py = mapY(normY, jitterPx);
        if (px < 0 || py < 0 || px > 10000 || py > 10000) return;

        Path p = new Path();
        p.moveTo(px, py);
        long dur = Math.max(35L, Math.min(300L, durationMs));
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0L, dur));
            dispatchGesture(b.build(), null, null);
        } catch (Throwable ignored) {}
    }

    @Override
    public synchronized void holdJoystick(int joyCx, int joyCy, int targetX, int targetY, int jitterPx) {
        float cx = mapX(joyCx, 0);
        float cy = mapY(joyCy, 0);
        float tx = mapX(targetX, jitterPx);
        float ty = mapY(targetY, jitterPx);
        if (tx < 0 || ty < 0 || tx > 10000 || ty > 10000) return;

        long sliceDuration = 430L;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && continuedJoyStroke != null) {
            try {
                Path contPath = new Path();
                contPath.moveTo(lastJoyPhysX, lastJoyPhysY);
                contPath.lineTo(tx, ty);
                GestureDescription.StrokeDescription nextStroke =
                        continuedJoyStroke.continueStroke(contPath, 0L, sliceDuration, true);
                GestureDescription.Builder b = new GestureDescription.Builder();
                b.addStroke(nextStroke);
                addLiftHeldB(b);
                boolean ok = dispatchGesture(b.build(), new GestureResultCallback() {
                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        synchronized (AutoAccessibilityService.this) {
                            continuedJoyStroke = null;
                            continuedBStroke = null;
                        }
                    }
                }, null);
                if (ok) {
                    continuedJoyStroke = nextStroke;
                    lastJoyPhysX = tx;
                    lastJoyPhysY = ty;
                    return;
                }
            } catch (Throwable t) {
                continuedJoyStroke = null;
            }
        }

        // Fresh Joystick hold stroke from center -> directional target
        try {
            Path startPath = new Path();
            startPath.moveTo(cx, cy);
            startPath.lineTo(tx, ty);
            GestureDescription.StrokeDescription stroke = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new GestureDescription.StrokeDescription(startPath, 0L, sliceDuration, true)
                    : new GestureDescription.StrokeDescription(startPath, 0L, sliceDuration);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(stroke);
            continuedBStroke = null;
            boolean ok = dispatchGesture(b.build(), new GestureResultCallback() {
                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    synchronized (AutoAccessibilityService.this) {
                        continuedJoyStroke = null;
                        continuedBStroke = null;
                    }
                }
            }, null);
            if (ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                continuedJoyStroke = stroke;
                lastJoyPhysX = tx;
                lastJoyPhysY = ty;
            }
        } catch (Throwable ignored) {
            continuedJoyStroke = null;
        }
    }

    /** If the B finger is still held from a pressing tick, add the stroke that lifts it to this gesture. */
    private void addLiftHeldB(GestureDescription.Builder b) {
        if (continuedBStroke == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Path endPath = new Path();
                endPath.moveTo(lastBPhysX, lastBPhysY);
                b.addStroke(continuedBStroke.continueStroke(endPath, 0L, 12L, false));
            } catch (Throwable ignored) {}
        }
        continuedBStroke = null;
    }

    @Override
    public synchronized void aimAndPressButton(
            int joyCx,
            int joyCy,
            int joyTx,
            int joyTy,
            boolean useJoy,
            int btnX,
            int btnY,
            int holdMs,
            boolean releaseJoyAfter,
            int jitterPx
    ) {
        // End any previous continued dribble / pressing cleanly before firing atomic 2-finger kick
        continuedJoyStroke = null;
        continuedBStroke = null;

        float bx = mapX(btnX, jitterPx);
        float by = mapY(btnY, jitterPx);
        if (bx < 0 || by < 0 || bx > 10000 || by > 10000) return;

        long kickDur = Math.max(55L, Math.min(600L, holdMs));
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();

            if (useJoy) {
                float cx = mapX(joyCx, 0);
                float cy = mapY(joyCy, 0);
                float tx = mapX(joyTx, jitterPx);
                float ty = mapY(joyTy, jitterPx);
                Path joyPath = new Path();
                joyPath.moveTo(cx, cy);
                joyPath.lineTo(tx, ty);
                // Hold directional Joystick from 0ms through kick completion (+65ms follow-through!)
                b.addStroke(new GestureDescription.StrokeDescription(joyPath, 0L, kickDur + 95L));
            }

            Path btnPath = new Path();
            btnPath.moveTo(bx, by);
            // Start button press 30ms after Joystick direction engages so pass/shot aims accurately!
            long btnStart = useJoy ? 30L : 0L;
            b.addStroke(new GestureDescription.StrokeDescription(btnPath, btnStart, kickDur));

            dispatchGesture(b.build(), null, null);
        } catch (Throwable ignored) {}
    }

    /**
     * Pressing: joystick is dragged at the ball carrier while B stays held DOWN. On Android 8+ both fingers are
     * "continued" strokes, so consecutive ticks chain together without lifting B (the old one-shot 405ms press
     * released B for ~100-200ms every tick, so the tackle/pressure never really stayed on).
     */
    @Override
    public synchronized void holdDefendContain(
            int joyCx,
            int joyCy,
            int joyTx,
            int joyTy,
            int btnBX,
            int btnBY,
            int jitterPx
    ) {
        float cx = mapX(joyCx, 0);
        float cy = mapY(joyCy, 0);
        float tx = mapX(joyTx, jitterPx);
        float ty = mapY(joyTy, jitterPx);
        float bx = mapX(btnBX, jitterPx);
        float by = mapY(btnBY, jitterPx);
        if (bx < 0 || tx < 0 || bx > 10000 || tx > 10000) return;

        final long slice = 620L;   // longer than one bot tick so B never lets go if a tick is late

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 1) Chain onto the joystick finger that is already down (dribble or previous pressing tick)
            if (continuedJoyStroke != null) {
                try {
                    GestureDescription.Builder b = new GestureDescription.Builder();
                    Path joyPath = new Path();
                    joyPath.moveTo(lastJoyPhysX, lastJoyPhysY);
                    joyPath.lineTo(tx, ty);
                    GestureDescription.StrokeDescription nextJoy = continuedJoyStroke.continueStroke(joyPath, 0L, slice, true);
                    b.addStroke(nextJoy);

                    GestureDescription.StrokeDescription nextB;
                    float useBx = bx, useBy = by;
                    Path bPath = new Path();
                    if (continuedBStroke != null) {
                        useBx = lastBPhysX;
                        useBy = lastBPhysY;
                        bPath.moveTo(useBx, useBy);
                        nextB = continuedBStroke.continueStroke(bPath, 0L, slice, true);
                    } else {
                        bPath.moveTo(useBx, useBy);
                        nextB = new GestureDescription.StrokeDescription(bPath, 0L, slice, true);
                    }
                    b.addStroke(nextB);

                    boolean ok = dispatchGesture(b.build(), pressCancelCallback(), null);
                    if (ok) {
                        continuedJoyStroke = nextJoy;
                        continuedBStroke = nextB;
                        lastJoyPhysX = tx;
                        lastJoyPhysY = ty;
                        lastBPhysX = useBx;
                        lastBPhysY = useBy;
                        return;
                    }
                } catch (Throwable t) {
                    // fall through to a fresh press
                }
                continuedJoyStroke = null;
                continuedBStroke = null;
            }

            // 2) Fresh press: joystick centre -> carrier direction, B down, both kept alive for chaining
            try {
                GestureDescription.Builder b = new GestureDescription.Builder();
                Path joyPath = new Path();
                joyPath.moveTo(cx, cy);
                joyPath.lineTo(tx, ty);
                GestureDescription.StrokeDescription joy = new GestureDescription.StrokeDescription(joyPath, 0L, slice, true);
                Path bPath = new Path();
                bPath.moveTo(bx, by);
                GestureDescription.StrokeDescription bs = new GestureDescription.StrokeDescription(bPath, 0L, slice, true);
                b.addStroke(joy);
                b.addStroke(bs);
                boolean ok = dispatchGesture(b.build(), pressCancelCallback(), null);
                if (ok) {
                    continuedJoyStroke = joy;
                    continuedBStroke = bs;
                    lastJoyPhysX = tx;
                    lastJoyPhysY = ty;
                    lastBPhysX = bx;
                    lastBPhysY = by;
                    return;
                }
            } catch (Throwable ignored) {}
            continuedJoyStroke = null;
            continuedBStroke = null;
            return;
        }

        // Android < 8: one-shot gesture, slightly longer than a bot tick so the gap between presses is minimal
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();
            Path joyPath = new Path();
            joyPath.moveTo(cx, cy);
            joyPath.lineTo(tx, ty);
            b.addStroke(new GestureDescription.StrokeDescription(joyPath, 0L, 560L));
            Path pressBPath = new Path();
            pressBPath.moveTo(bx, by);
            b.addStroke(new GestureDescription.StrokeDescription(pressBPath, 0L, 540L));
            dispatchGesture(b.build(), null, null);
        } catch (Throwable ignored) {}
    }

    private GestureResultCallback pressCancelCallback() {
        return new GestureResultCallback() {
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                synchronized (AutoAccessibilityService.this) {
                    continuedJoyStroke = null;
                    continuedBStroke = null;
                }
            }
        };
    }

    @Override
    public synchronized void performSkillSwipe(
            int joyCx,
            int joyCy,
            int joyTx,
            int joyTy,
            int swipeX1,
            int swipeY1,
            int swipeX2,
            int swipeY2,
            int durationMs,
            int jitterPx
    ) {
        continuedJoyStroke = null;
        continuedBStroke = null;

        float cx = mapX(joyCx, 0);
        float cy = mapY(joyCy, 0);
        float tx = mapX(joyTx, jitterPx);
        float ty = mapY(joyTy, jitterPx);
        float sx1 = mapX(swipeX1, jitterPx);
        float sy1 = mapY(swipeY1, jitterPx);
        float sx2 = mapX(swipeX2, jitterPx);
        float sy2 = mapY(swipeY2, jitterPx);
        if (sx1 < 0 || sx2 < 0 || sx1 > 10000 || sx2 > 10000) return;

        long sDur = Math.max(60L, Math.min(180L, durationMs));
        try {
            GestureDescription.Builder b = new GestureDescription.Builder();

            Path joyPath = new Path();
            joyPath.moveTo(cx, cy);
            joyPath.lineTo(tx, ty);
            b.addStroke(new GestureDescription.StrokeDescription(joyPath, 0L, sDur + 140L));

            Path skillPath = new Path();
            skillPath.moveTo(sx1, sy1);
            skillPath.lineTo(sx2, sy2);
            b.addStroke(new GestureDescription.StrokeDescription(skillPath, 20L, sDur));

            dispatchGesture(b.build(), null, null);
        } catch (Throwable ignored) {}
    }

    @Override
    public synchronized void releaseAll() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && (continuedJoyStroke != null || continuedBStroke != null)) {
            try {
                GestureDescription.Builder b = new GestureDescription.Builder();
                boolean any = false;
                if (continuedJoyStroke != null) {
                    Path endPath = new Path();
                    endPath.moveTo(lastJoyPhysX, lastJoyPhysY);
                    b.addStroke(continuedJoyStroke.continueStroke(endPath, 0L, 12L, false));
                    any = true;
                }
                if (continuedBStroke != null) {
                    Path endB = new Path();
                    endB.moveTo(lastBPhysX, lastBPhysY);
                    b.addStroke(continuedBStroke.continueStroke(endB, 0L, 12L, false));
                    any = true;
                }
                if (any) dispatchGesture(b.build(), null, null);
            } catch (Throwable ignored) {}
        }
        continuedJoyStroke = null;
        continuedBStroke = null;
    }

    /**
     * Hardware-composited screenshot fallback on Android 11+ (API 30+) if VirtualDisplay ever returns
     * a blank OpenGL surface. Scales directly to normWidth x 720.
     */
    public RgbaImage captureFallbackScreenshot(final int targetW, final int targetH) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        final RgbaImage[] holder = new RgbaImage[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    try (HardwareBuffer hb = result.getHardwareBuffer()) {
                        Bitmap hwBmp = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                        if (hwBmp != null) {
                            Bitmap swBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false);
                            hwBmp.recycle();
                            if (swBmp != null) {
                                Bitmap scaled = (swBmp.getWidth() == targetW && swBmp.getHeight() == targetH)
                                        ? swBmp
                                        : Bitmap.createScaledBitmap(swBmp, targetW, targetH, true);
                                if (scaled != swBmp) swBmp.recycle();
                                holder[0] = RgbaImage.fromBitmap(scaled);
                                scaled.recycle();
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    latch.countDown();
                }
            });
            latch.await(900L, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {}
        return holder[0];
    }
}
