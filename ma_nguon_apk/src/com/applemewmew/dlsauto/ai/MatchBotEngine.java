package com.applemewmew.dlsauto.ai;

import com.applemewmew.dlsauto.model.BotConfig;
import com.applemewmew.dlsauto.vision.RgbaImage;
import com.applemewmew.dlsauto.vision.ScreenGeometry;
import com.applemewmew.dlsauto.vision.ZnccMatcher;

import java.io.File;
import java.util.Map;

/**
 * Complete 100-Match AFK State Machine & Auto-Button-Snapping Orchestrator for Android.
 */
public final class MatchBotEngine {

    private static final String[][] PRIORITY_SLOTS = new String[][] {
            { "online_live_guard", "ONLINE_BLOCKED" },
            { "replay_exit", "REPLAY_VIEWER" },
            { "popup_close", "POPUP" },
            { "sub_confirm", "SUB_REQUIRED" },
            { "half_time_resume", "HALF_TIME" },
            { "pause_resume", "PAUSED_MENU" },
            { "post_match_continue", "POST_MATCH" },
            { "pre_match_play", "PRE_MATCH" },
            { "lobby_play", "LOBBY" },
            { "home_career", "LOBBY" },
            { "start_new_division", "LOBBY" },
            { "cutscene_skip", "CUTSCENE_REPLAY" },
            { "in_match_hud", "IN_MATCH" },
            { "nav_back", "SUB_MENU_ESCAPE" }
    };

    public static final class StepSnapshot {
        public String state = "IDLE";
        public String matchedSlot = null;
        public double matchedScore = 0;
        public String actionDetail = "Đang chờ...";
        public int matchesCompleted = 0;
        public int maxMatches = 100;
        public int avgMatchSec = 0;
        public long latencyMs = 0;
        public boolean limitOrOnlineStopped = false;
        public String stopReason = null;
    }

    private BotConfig config;
    private final ZnccMatcher matcher;
    private final BrainLearner learner;
    private final TacticsEngine.TacticalMemory tacticalMemory = new TacticsEngine.TacticalMemory();

    public int matchesCompleted = 0;
    private long totalMatchDurationSec = 0;
    private int recordedDurationCount = 0;

    private boolean inActiveMatch = false;
    private int currentHalf = 1;
    private long matchStartedAt = 0;
    private boolean halfTriggeredInMatch = false;
    private boolean postMatchCounted = false;

    private String lastState = "IDLE";
    private long lastActionAt = 0;
    private long lastSkipTapAt = 0;
    private int subMenuStreak = 0;

    // Live auto-snapped A/B/C centers detected via btn_a_glyph & btn_b_glyph on user's Android screen
    private int snappedAx = -1, snappedAy = -1;
    private int snappedBx = -1, snappedBy = -1;

    public MatchBotEngine(BotConfig config, File templatesDir, File brainFile) {
        this.config = config;
        this.matcher = new ZnccMatcher(templatesDir);
        this.learner = new BrainLearner(brainFile);
    }

    public synchronized void updateConfig(BotConfig next) {
        this.config = next;
        this.matcher.clearCache();
        this.snappedAx = -1;
        this.snappedBx = -1;
    }

    public BrainLearner getLearner() {
        return learner;
    }

    public synchronized void resetSessionStats() {
        matchesCompleted = 0;
        totalMatchDurationSec = 0;
        recordedDurationCount = 0;
        inActiveMatch = false;
        currentHalf = 1;
        halfTriggeredInMatch = false;
        postMatchCounted = false;
        lastState = "IDLE";
        subMenuStreak = 0;
        tacticalMemory.resetForMatch(false, false);
    }

    public int getAverageMatchSec() {
        return recordedDurationCount > 0 ? Math.round((float) totalMatchDurationSec / recordedDurationCount) : 0;
    }

    public synchronized String runDryDiagnostic(RgbaImage normFrame, ScreenGeometry geom) {
        Map<String, ZnccMatcher.MatchResult> dets = matcher.detectAll(normFrame, config, geom);
        StringBuilder sb = new StringBuilder();
        sb.append("Khung hình chuẩn hóa: ").append(normFrame.width).append("×").append(normFrame.height).append("\n");
        String topState = "UNKNOWN";
        for (String[] item : PRIORITY_SLOTS) {
            ZnccMatcher.MatchResult r = dets.get(item[0]);
            if (r != null && r.configured && r.matched) {
                topState = item[1] + " (" + item[0] + " " + Math.round(r.score * 100) + "%)";
                break;
            }
        }
        sb.append("Kết luận trạng thái: ").append(topState).append("\n\nĐiểm khớp các mẫu:\n");
        for (String slot : BotConfig.SLOT_ORDER) {
            ZnccMatcher.MatchResult r = dets.get(slot);
            if (r == null || !r.configured) continue;
            sb.append(r.matched ? "✔ " : "• ")
              .append(slot).append(": ")
              .append(Math.round(r.score * 100)).append("% (lệch màu ")
              .append(Math.round(r.colorDiff)).append(")\n");
        }
        return sb.toString();
    }

    public synchronized StepSnapshot step(
            RgbaImage normFrame,
            ScreenGeometry geom,
            TouchDispatcher touch,
            boolean dryRun
    ) {
        long t0 = System.currentTimeMillis();
        StepSnapshot snap = new StepSnapshot();
        snap.maxMatches = config.maxMatches;

        Map<String, ZnccMatcher.MatchResult> dets = matcher.detectAll(normFrame, config, geom);

        // Auto-snap A/B/C button centers when glyphs match strongly on uncalibrated phones!
        if (!config.calibratedOnPhone && config.autoSnapGlyphButtons) {
            ZnccMatcher.MatchResult ga = dets.get("btn_a_glyph");
            ZnccMatcher.MatchResult gb = dets.get("btn_b_glyph");
            if (ga != null && ga.configured && ga.score >= 0.76 && ga.colorDiff <= 150) {
                snappedAx = ga.centerX;
                snappedAy = ga.centerY;
            }
            if (gb != null && gb.configured && gb.score >= 0.76 && gb.colorDiff <= 150) {
                snappedBx = gb.centerX;
                snappedBy = gb.centerY;
            }
        }

        ScreenGeometry.ResolvedControls ctrl = geom.resolveControls(config);
        if (!config.calibratedOnPhone && snappedAx > 0 && snappedBx > 0) {
            ctrl.buttonA.x = snappedAx;
            ctrl.buttonA.y = snappedAy;
            ctrl.buttonB.x = snappedBx;
            ctrl.buttonB.y = snappedBy;
            ctrl.buttonC.x = snappedAx;
            ctrl.buttonC.y = Math.max(260, snappedAy - 166);
        }

        String state = "UNKNOWN";
        String matchedSlot = null;
        ZnccMatcher.MatchResult matchInfo = null;

        for (String[] item : PRIORITY_SLOTS) {
            ZnccMatcher.MatchResult d = dets.get(item[0]);
            if (d != null && d.configured && d.matched) {
                state = item[1];
                matchedSlot = item[0];
                matchInfo = d;
                break;
            }
        }

        long now = System.currentTimeMillis();
        if (!state.equals(lastState)) {
            if ("IN_MATCH".equals(lastState) && !"IN_MATCH".equals(state)) {
                touch.releaseAll();
            }
            lastState = state;
        }

        if (!"SUB_MENU_ESCAPE".equals(state)) {
            subMenuStreak = 0;
        }

        snap.state = state;
        snap.matchedSlot = matchedSlot;
        snap.matchedScore = matchInfo != null ? matchInfo.score : 0;

        if ("ONLINE_BLOCKED".equals(state)) {
            touch.releaseAll();
            snap.actionDetail = "Phát hiện đá Online (Dream League Live)! Tự động dừng an toàn.";
            snap.limitOrOnlineStopped = true;
            snap.stopReason = snap.actionDetail;
            snap.matchesCompleted = matchesCompleted;
            snap.latencyMs = System.currentTimeMillis() - t0;
            return snap;
        }

        if (dryRun) {
            snap.actionDetail = "Chế độ Xem thử: " + state + (matchedSlot != null ? " (" + matchedSlot + ")" : "");
            snap.matchesCompleted = matchesCompleted;
            snap.avgMatchSec = getAverageMatchSec();
            snap.latencyMs = System.currentTimeMillis() - t0;
            return snap;
        }

        int jitter = config.jitterPx;

        switch (state) {
            case "REPLAY_VIEWER":
            case "POPUP":
            case "SUB_REQUIRED":
            case "PAUSED_MENU": {
                long gap = "REPLAY_VIEWER".equals(state) ? 620L : 1050L;
                if (now - lastActionAt >= gap && matchInfo != null) {
                    touch.tap(matchInfo.tapX, matchInfo.tapY, 55, jitter);
                    lastActionAt = now;
                    snap.actionDetail = "REPLAY_VIEWER".equals(state)
                            ? "Bấm < góc trên trái thoát Replay"
                            : "Bấm " + matchedSlot + " (" + Math.round(matchInfo.score * 100) + "%)";
                } else {
                    snap.actionDetail = "Chờ chuyển cảnh (" + state + ")";
                }
                break;
            }

            case "HALF_TIME": {
                if (!halfTriggeredInMatch) {
                    currentHalf = 2;
                    halfTriggeredInMatch = true;
                    tacticalMemory.resetForMatch(true, true);
                }
                if (now - lastActionAt >= 1250L && matchInfo != null) {
                    touch.tap(matchInfo.tapX, matchInfo.tapY, 55, jitter);
                    lastActionAt = now;
                    snap.actionDetail = "Nghỉ giữa hiệp -> Bấm sang Hiệp 2";
                } else {
                    snap.actionDetail = "Đang vào Hiệp 2...";
                }
                break;
            }

            case "POST_MATCH": {
                if (inActiveMatch && !postMatchCounted) {
                    inActiveMatch = false;
                    postMatchCounted = true;
                    matchesCompleted++;
                    long dur = matchStartedAt > 0 ? Math.round((now - matchStartedAt) / 1000.0) : 0;
                    if (dur > 20) {
                        totalMatchDurationSec += dur;
                        recordedDurationCount++;
                    }
                    learner.save();
                }

                if (config.maxMatches > 0 && matchesCompleted >= config.maxMatches) {
                    touch.releaseAll();
                    snap.matchesCompleted = matchesCompleted;
                    snap.avgMatchSec = getAverageMatchSec();
                    snap.limitOrOnlineStopped = true;
                    snap.stopReason = "Đã hoàn thành đủ " + matchesCompleted + "/" + config.maxMatches + " trận mục tiêu!";
                    snap.actionDetail = snap.stopReason;
                    snap.latencyMs = System.currentTimeMillis() - t0;
                    return snap;
                }

                if (now - lastActionAt >= 2200L && matchInfo != null) {
                    touch.releaseAll();
                    touch.tap(matchInfo.tapX, matchInfo.tapY, 55, jitter);
                    lastActionAt = now;
                    snap.actionDetail = "Hết trận #" + matchesCompleted + " -> Bấm Continue";
                } else {
                    snap.actionDetail = "Tổng kết sau trận (#" + matchesCompleted + "/" + (config.maxMatches > 0 ? config.maxMatches : "∞") + ")";
                }
                break;
            }

            case "SUB_MENU_ESCAPE": {
                inActiveMatch = false;
                subMenuStreak++;
                if (subMenuStreak >= 4 && now - lastActionAt >= 1500L && matchInfo != null) {
                    subMenuStreak = 0;
                    touch.tap(matchInfo.tapX, matchInfo.tapY, 55, jitter);
                    lastActionAt = now;
                    snap.actionDetail = "Lạc vào menu phụ -> Tự bấm ← về Sảnh chính";
                } else {
                    snap.actionDetail = "Kiểm tra menu phụ (" + subMenuStreak + "/4)";
                }
                break;
            }

            case "PRE_MATCH":
            case "LOBBY": {
                if (config.maxMatches > 0 && matchesCompleted >= config.maxMatches) {
                    touch.releaseAll();
                    snap.matchesCompleted = matchesCompleted;
                    snap.limitOrOnlineStopped = true;
                    snap.stopReason = "Đã hoàn thành đủ " + matchesCompleted + "/" + config.maxMatches + " trận!";
                    snap.actionDetail = snap.stopReason;
                    snap.latencyMs = System.currentTimeMillis() - t0;
                    return snap;
                }
                if (now - lastActionAt >= 1450L && matchInfo != null) {
                    touch.tap(matchInfo.tapX, matchInfo.tapY, 60, jitter);
                    lastActionAt = now;
                    if ("PRE_MATCH".equals(state)) {
                        inActiveMatch = true;
                        currentHalf = 1;
                        halfTriggeredInMatch = false;
                        postMatchCounted = false;
                        matchStartedAt = now;
                        tacticalMemory.resetForMatch(false, false);
                        snap.actionDetail = "Bấm PLAY bắt đầu trận #" + (matchesCompleted + 1);
                    } else {
                        inActiveMatch = false;
                        snap.actionDetail = "Sảnh chính -> Bấm vào trận (" + matchedSlot + ")";
                    }
                } else {
                    snap.actionDetail = "Đang chuyển màn hình sảnh...";
                }
                break;
            }

            case "CUTSCENE_REPLAY": {
                if (now - lastSkipTapAt >= 900L) {
                    int tx = matchInfo != null ? matchInfo.tapX : ctrl.skipTap.x;
                    int ty = matchInfo != null ? matchInfo.tapY : ctrl.skipTap.y;
                    touch.tap(tx, ty, 45, jitter);
                    lastSkipTapAt = now;
                    snap.actionDetail = "Bỏ qua nhanh Replay / Ăn mừng";
                }
                break;
            }

            case "IN_MATCH": {
                inActiveMatch = true;
                postMatchCounted = false;
                if (matchStartedAt == 0) matchStartedAt = now;

                TacticsEngine.TacticalState tac = TacticsEngine.analyze(normFrame, tacticalMemory, ctrl, dets);
                if (tac.radarVisible && tac.me != null) {
                    learner.observeOutcome(tac.possession, tac.progressX, false);
                }
                long cd = config.actionCooldownMs + (long) (Math.random() * 40);
                if (now - lastActionAt >= cd) {
                    lastActionAt = now;
                    snap.actionDetail = TacticsEngine.executePlay(tac, learner, touch, geom, ctrl, config, tacticalMemory);
                } else {
                    snap.actionDetail = "Đang giữ nhịp điều khiển trên sân...";
                }
                break;
            }

            default: {
                touch.releaseAll();
                if (inActiveMatch) {
                    learner.observeOutcome("attack", 1.0, true);
                    if (now - lastSkipTapAt >= config.skipReplayIntervalMs) {
                        touch.tap(ctrl.skipTap.x, ctrl.skipTap.y, 45, jitter);
                        lastSkipTapAt = now;
                        snap.actionDetail = "Tua nhanh Intro / Ăn mừng bàn thắng";
                    } else {
                        snap.actionDetail = "Đang chờ qua cảnh...";
                    }
                } else {
                    snap.actionDetail = "Đang quan sát màn hình (Hãy mở sảnh DLS)";
                }
                break;
            }
        }

        snap.matchesCompleted = matchesCompleted;
        snap.avgMatchSec = getAverageMatchSec();
        snap.latencyMs = System.currentTimeMillis() - t0;
        return snap;
    }

    public boolean isFastSkipMode() {
        return inActiveMatch && "UNKNOWN".equals(lastState);
    }
}
