package com.applemewmew.dlsauto.vision;

import com.applemewmew.dlsauto.model.BotConfig;

/**
 * Native Android Multi-Aspect-Ratio Screen Geometry Mapper.
 * Supports 16:9 (1280x720), 18:9 (1440x720), 19.5:9 (1560x720), 20:9 (1600x720), and 21:9 (1680x720)
 * plus automatic DisplayCutout (camera punch-hole / notch) safe-margin compensation!
 */
public final class ScreenGeometry {

    public static final int NORM_HEIGHT = 720;
    public static final int BASE_16_9_WIDTH = 1280;

    public final int physWidth;
    public final int physHeight;
    public final int normWidth;
    public final int normHeight;
    public final int insetLeftNorm;
    public final int insetRightNorm;

    public ScreenGeometry(int physWidth, int physHeight, int cutoutLeftPx, int cutoutRightPx, int overrideMarginNorm) {
        // Landscape orientation dimensions when DLS is open
        int lw = Math.max(physWidth, physHeight);
        int lh = Math.max(1, Math.min(physWidth, physHeight));
        this.physWidth = lw;
        this.physHeight = lh;
        this.normHeight = NORM_HEIGHT;
        this.normWidth = Math.max(960, Math.round((lw * (float) NORM_HEIGHT) / lh));

        if (overrideMarginNorm >= 0) {
            this.insetLeftNorm = overrideMarginNorm;
            this.insetRightNorm = overrideMarginNorm;
        } else {
            this.insetLeftNorm = Math.min(90, Math.round((cutoutLeftPx * (float) NORM_HEIGHT) / lh));
            this.insetRightNorm = Math.min(90, Math.round((cutoutRightPx * (float) NORM_HEIGHT) / lh));
        }
    }

    public int extraWidthOver16x9() {
        return Math.max(0, normWidth - BASE_16_9_WIDTH);
    }

    /**
     * Signed width difference vs the 1280-wide reference HUD: positive on phones wider than 16:9,
     * NEGATIVE on tablets narrower than 16:9 (16:10, 4:3). Used so edge-anchored controls keep their
     * distance from the screen edge on every ratio instead of falling off / overlapping on tablets.
     */
    public int widthDeltaVs16x9() {
        return normWidth - BASE_16_9_WIDTH;
    }

    public int toPhysX(int normX) {
        int clamped = Math.max(2, Math.min(normWidth - 2, normX));
        return Math.round((clamped * (float) physWidth) / normWidth);
    }

    public int toPhysY(int normY) {
        int clamped = Math.max(2, Math.min(normHeight - 2, normY));
        return Math.round((clamped * (float) physHeight) / normHeight);
    }

    public int toNormX(float physX) {
        return Math.max(0, Math.min(normWidth - 1, Math.round((physX * normWidth) / Math.max(1, physWidth))));
    }

    public int toNormY(float physY) {
        return Math.max(0, Math.min(normHeight - 1, Math.round((physY * normHeight) / Math.max(1, physHeight))));
    }

    /**
     * Maps a 1280x720 (or phone-native) ROI onto the current phone's normWidth x 720 canvas.
     * On ultra-wide phones (19.5:9 / 20:9), automatically spans both edge-anchored HUD positions
     * AND safe-area centered menu containers so buttons match cleanly without stretching!
     */
    public BotConfig.Roi resolveRoi(BotConfig.TemplateSlot slot) {
        BotConfig.Roi r = slot.roi;
        if (slot.phoneNative) {
            int deltaW = normWidth - Math.max(960, slot.phoneNormW);
            int shift = "RIGHT".equalsIgnoreCase(slot.anchor)
                    ? deltaW
                    : ("CENTER".equalsIgnoreCase(slot.anchor) ? deltaW / 2 : 0);
            int x = clampX(r.x + shift);
            int y = clampY(r.y);
            int w = Math.max(16, Math.min(normWidth - x, r.w));
            int h = Math.max(16, Math.min(normHeight - y, r.h));
            return new BotConfig.Roi(x, y, w, h);
        }

        int extraW = extraWidthOver16x9();
        String anchor = slot.anchor != null ? slot.anchor.toUpperCase() : "RIGHT";

        int rx, rw;
        if ("LEFT".equals(anchor)) {
            rx = Math.max(0, r.x - 6);
            int spanBoost = Math.max(insetLeftNorm + 32, (extraW / 2) + 36);
            rw = Math.min(normWidth - rx, r.w + spanBoost);
        } else if ("RIGHT".equals(anchor)) {
            int rightMargin1280 = Math.max(0, BASE_16_9_WIDTH - (r.x + r.w));
            int rightEdgePhone = normWidth - Math.max(0, rightMargin1280 - 8);
            int leftReach = Math.max(insetRightNorm + 32, (extraW / 2) + 36);
            rx = Math.max(0, rightEdgePhone - r.w - leftReach);
            rw = Math.max(16, Math.min(normWidth - rx, rightEdgePhone - rx));
        } else {
            // CENTER (signed delta: also correct on screens narrower than 16:9)
            int shiftedX = r.x + (widthDeltaVs16x9() / 2) - 24;
            rx = Math.max(0, shiftedX);
            rw = Math.max(16, Math.min(normWidth - rx, r.w + 48));
        }

        int ry = clampY(Math.max(0, r.y - 6));
        int rh = Math.max(16, Math.min(normHeight - ry, r.h + 12));
        return new BotConfig.Roi(rx, ry, rw, rh);
    }

    /**
     * Resolves active HUD control coordinates (Joystick, A, B, C, SkipTap) in normWidth x 720 space.
     */
    public ResolvedControls resolveControls(BotConfig cfg) {
        ResolvedControls out = new ResolvedControls();
        if (cfg.calibratedOnPhone) {
            int deltaW = normWidth - Math.max(960, cfg.calibratedNormW);
            out.joystick = new BotConfig.ControlPoint(clampX(cfg.joystick.x), clampY(cfg.joystick.y), cfg.joystick.radius);
            out.buttonB = new BotConfig.ControlPoint(clampX(cfg.buttonB.x + deltaW), clampY(cfg.buttonB.y), 0);
            out.buttonA = new BotConfig.ControlPoint(clampX(cfg.buttonA.x + deltaW), clampY(cfg.buttonA.y), 0);
            out.buttonC = new BotConfig.ControlPoint(clampX(cfg.buttonC.x + deltaW), clampY(cfg.buttonC.y), 0);
            out.skipTap = new BotConfig.ControlPoint(normWidth / 2, clampY(cfg.skipTap.y), 0);
            return out;
        }

        // Automatic multi-aspect-ratio Android anchor positioning:
        // Joystick anchors bottom-left (+ left cutout inset); A/B/C anchor bottom-right (- right cutout inset)
        out.joystick = new BotConfig.ControlPoint(
                clampX(cfg.joystick.x + insetLeftNorm),
                clampY(cfg.joystick.y),
                Math.max(52, cfg.joystick.radius)
        );
        int rightShift = widthDeltaVs16x9() - insetRightNorm;
        out.buttonB = new BotConfig.ControlPoint(clampX(cfg.buttonB.x + rightShift), clampY(cfg.buttonB.y), 0);
        out.buttonA = new BotConfig.ControlPoint(clampX(cfg.buttonA.x + rightShift), clampY(cfg.buttonA.y), 0);
        out.buttonC = new BotConfig.ControlPoint(clampX(cfg.buttonC.x + rightShift), clampY(cfg.buttonC.y), 0);
        out.skipTap = new BotConfig.ControlPoint(normWidth / 2, clampY(cfg.skipTap.y), 0);
        return out;
    }

    /**
     * Computes the canonical centered 2D Radar rectangle (width=189, height=127) on normWidth x 720.
     */
    public int[] defaultRadarBounds() {
        int rw = 189;
        int x0 = (normWidth - rw) / 2;
        int x1 = x0 + rw;
        return new int[] { x0, 578, x1, 705 };
    }

    private int clampX(int x) {
        return Math.max(0, Math.min(normWidth - 1, x));
    }

    private int clampY(int y) {
        return Math.max(0, Math.min(normHeight - 1, y));
    }

    public static final class ResolvedControls {
        public BotConfig.ControlPoint joystick;
        public BotConfig.ControlPoint buttonB;
        public BotConfig.ControlPoint buttonA;
        public BotConfig.ControlPoint buttonC;
        public BotConfig.ControlPoint skipTap;
    }
}
