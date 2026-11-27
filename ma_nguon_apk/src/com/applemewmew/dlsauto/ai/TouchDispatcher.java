package com.applemewmew.dlsauto.ai;

/**
 * Abstraction for single-touch & genuine 2-finger multi-touch actions on Android.
 * Implemented in production by AutoAccessibilityService and in unit tests by a mock recorder.
 */
public interface TouchDispatcher {

    boolean isReady();

    void tap(int normX, int normY, int durationMs, int jitterPx);

    void holdJoystick(int joyCx, int joyCy, int targetX, int targetY, int jitterPx);

    void aimAndPressButton(
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
    );

    void holdDefendContain(int joyCx, int joyCy, int joyTx, int joyTy, int btnBX, int btnBY, int jitterPx);

    void performSkillSwipe(
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
    );

    void releaseAll();
}
