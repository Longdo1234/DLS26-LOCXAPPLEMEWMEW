package com.applemewmew.dlsauto.ai;

import com.applemewmew.dlsauto.model.BotConfig;
import com.applemewmew.dlsauto.vision.RgbaImage;
import com.applemewmew.dlsauto.vision.ScreenGeometry;
import com.applemewmew.dlsauto.vision.ZnccMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Human-Like Multi-Aspect-Ratio Android Tactical Vision & Tiki-Taka 2-Finger Football AI.
 * Supports 16:9, 18:9, 19.5:9, 20:9, and 21:9 Android screens:
 * - Percentile formation direction lock + Half-Time inversion + white radar line rejection
 * - First-touch ball-in-flight cushion (no button spam while passes travel!)
 * - Smooth multi-stride dribbling into open space + crisp ground `B` Tiki-Taka & through-balls
 * - Disciplined `JOY+B` standing-tackle defense (ZERO reckless `A` slide-tackles!)
 * - Composed box-only clinical finishing (`B` finesse in 1-on-1s, controlled low `A` dead center)
 *
 * V3.1 fixes:
 * - Aspect-correct aiming: radar is 189x127 px, so pass/shot/dribble directions are now computed in
 *   radar PIXEL space (not normalized 0..1 space) -> no more ~11deg skew on diagonal passes.
 * - Pressing: within PRESS_DIST of the carrier the joystick is dragged straight at him with B held
 *   continuously (see AutoAccessibilityService.holdDefendContain).
 * - Crosses: much stronger C power, aimed at a teammate in the box (or penalty spot) + one header (A) window.
 * - Passing: no backward cutbacks away from the byline, no blind B spam when the radar dot is lost,
 *   shorter max pass range matched to pass power.
 * - Throw-ins / touchline restarts: always aimed INTO the pitch; dribbling never runs out of bounds.
 */
public final class TacticsEngine {

    // ---------------------------------------------------------------------------------------------
    // Tuning constants (edit here if the feel in-game needs adjusting)
    // ---------------------------------------------------------------------------------------------
    /** Radar rectangle is 189 x 127 px: one normalized X unit is 189px, one normalized Y unit is 127px. */
    private static final double RADAR_ASPECT = 189.0 / 127.0;
    /** Cross (C) power: hold time = CROSS_BASE_MS + distance * CROSS_SLOPE_MS, clamped to [MIN, MAX]. */
    private static final int CROSS_BASE_MS = 250;
    private static final double CROSS_SLOPE_MS = 330.0;
    private static final int CROSS_MIN_MS = 290;
    private static final int CROSS_MAX_MS = 400;
    /** Header / volley press (A) when a cross arrives. */
    private static final int HEADER_HOLD_MS = 130;
    /** Normalized distance to the ball carrier below which we press (hold B + drag joystick at him). */
    private static final double PRESS_DIST = 0.22;
    /** Longest pass we allow (normalized X units, ~38m). Pass power curve is tuned up to this range. */
    private static final double MAX_PASS_RANGE = 0.42;
    /** Touchline margin: a player this close to the top/bottom edge is treated as taking a restart. */
    private static final double EDGE_RESTART_MARGIN = 0.10;

    public static final class PlayerDot {
        public int x, y;
        public double normX, normY;
        public int r, g, b;
        public boolean extrapolated;
    }

    public static final class TacticalMemory {
        public int[] myShirtColor = null;
        public final List<Integer> dirVotes = new ArrayList<>();
        public int lockedAttackDir = 0;
        public boolean hardDirLocked = false;
        public PlayerDot prevMe = null;
        public double lastCarryUx = 0, lastCarryUy = 0;
        public boolean hasLastCarry = false;
        public int staticTicks = 0;
        public long lastSwitchAt = 0;
        public long lastPassAt = 0;
        public long ballInFlightUntil = 0;
        public PlayerDot lastPassOrigin = null;
        public long pendingCutbackFinishUntil = 0;
        public long lastShotAt = 0;
        public long lastSkillAt = 0;
        public int dribbleStreak = 0;
        public String prevPossession = "unknown";
        public int justWonBallTicks = 0;
        public int setPieceCounter = 0;
        public long crossHeaderUntil = 0;
        public long crossArriveFrom = 0;
        public int noMeTicks = 0;

        public void resetForMatch(boolean keepShirtColor, boolean flipHalfDirection) {
            int prevDir = lockedAttackDir;
            if (!keepShirtColor) myShirtColor = null;
            dirVotes.clear();
            if (flipHalfDirection && prevDir != 0) {
                int flipped = -prevDir;
                lockedAttackDir = flipped;
                dirVotes.add(flipped);
                dirVotes.add(flipped);
                dirVotes.add(flipped);
                hardDirLocked = false;
            } else {
                lockedAttackDir = 0;
                hardDirLocked = false;
            }
            prevMe = null;
            hasLastCarry = false;
            staticTicks = 0;
            ballInFlightUntil = 0;
            lastPassOrigin = null;
            pendingCutbackFinishUntil = 0;
            dribbleStreak = 0;
            justWonBallTicks = 0;
            crossHeaderUntil = 0;
            crossArriveFrom = 0;
            noMeTicks = 0;
        }
    }

    public static final class TacticalState {
        public boolean radarVisible;
        public boolean kickButtonsVisible;
        public boolean isFirstPersonSetPiece;
        public String possession = "attack";
        public boolean hasGreenPin3D;
        public int attackDir = 1;
        public PlayerDot me;
        public double progressX = 0.5;
        public final List<PlayerDot> teammates = new ArrayList<>();
        public final List<PlayerDot> opponents = new ArrayList<>();
        public boolean isDeadBall;
    }

    private static final class PassOption {
        PlayerDot teammate;
        double dist;
        double forwardGain;
        double laneClearance;
        double receiverSpace;
        boolean isWallPass;
        boolean isSupportRecycle;
        boolean useWhippedC;
        double score;
        double unitX;
        double unitY;
    }

    private static boolean isRadarGrass(int r, int g, int b) {
        return g >= 52 && g > r * 1.07 && g > b * 1.09 && (g - b) >= 8;
    }

    private static double colorDist(int r1, int g1, int b1, int[] c2) {
        double dr = r1 - c2[0];
        double dg = g1 - c2[1];
        double db = b1 - c2[2];
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /**
     * Converts a normalized radar displacement into a unit joystick direction measured in radar PIXELS
     * (so a 45deg pitch diagonal is really 45deg on the joystick). Returns {ux, uy, worldDist} where
     * worldDist is the distance expressed in normalized-X units (what the pass-power curve is tuned for).
     */
    private static double[] aimUnit(double dxNorm, double dyNorm) {
        double px = dxNorm * RADAR_ASPECT;
        double len = Math.max(1e-6, Math.hypot(px, dyNorm));
        double worldDist = Math.hypot(dxNorm, dyNorm / RADAR_ASPECT);
        return new double[] { px / len, dyNorm / len, worldDist };
    }

    /** -1 / +1 = which way is INTO the pitch when standing at a touchline; 0 = not at a touchline. */
    private static int touchlineInward(PlayerDot p) {
        if (p == null) return 0;
        if (p.normY <= EDGE_RESTART_MARGIN) return 1;
        if (p.normY >= 1.0 - EDGE_RESTART_MARGIN) return -1;
        return 0;
    }

    private static double[] ensureInward(double ux, double uy, int inwardY, double minInward, int dir) {
        if (inwardY == 0 || uy * inwardY >= minInward) return new double[] { ux, uy };
        double sx = Math.abs(ux) < 1e-3 ? dir : Math.signum(ux);
        return new double[] { sx * Math.sqrt(1.0 - minInward * minInward), inwardY * minInward };
    }

    private static double percentileSorted(List<Double> sorted, double q) {
        if (sorted == null || sorted.isEmpty()) return 0.5;
        double pos = (sorted.size() - 1) * q;
        int base = (int) Math.floor(pos);
        double rest = pos - base;
        if (base + 1 < sorted.size()) {
            return sorted.get(base) + rest * (sorted.get(base + 1) - sorted.get(base));
        }
        return sorted.get(base);
    }

    private static boolean isPaintedRadarPitchLine(int x, int y, int x0, int y0, double rw, double rh, int sat) {
        if (sat >= 28) return false;
        double relX = (x - x0) / rw;
        double relY = (y - y0) / rh;

        if (relX < 0.025 || relX > 0.975 || relY < 0.032 || relY > 0.968) return true;
        if (Math.abs(relX - 0.50) <= 0.024) return true;

        double cx = x0 + rw * 0.5;
        double cy = y0 + rh * 0.5;
        if (Math.abs(Math.hypot(x - cx, y - cy) - 19.5) <= 2.6) return true;

        if (relX <= 0.18 || relX >= 0.82) {
            if (Math.abs(relX - 0.165) <= 0.020
                    || Math.abs(relX - 0.835) <= 0.020
                    || (relY >= 0.19 && relY <= 0.81 && (Math.abs(relY - 0.21) <= 0.024 || Math.abs(relY - 0.79) <= 0.024))) {
                return true;
            }
        }
        return false;
    }

    private static int[] detectAdaptiveRadarRect(RgbaImage frame) {
        int baseRw = 189;
        int baseRh = 127;
        int baseX0 = (frame.width - baseRw) / 2;
        int baseY0 = 578;

        double bestScore = -1.0;
        int bestX0 = baseX0;
        int bestY0 = baseY0;

        int[] dyOffsets = new int[] { 0, -3, 3, -6, 6 };
        for (int dy : dyOffsets) {
            int y0 = Math.max(480, Math.min(frame.height - baseRh - 4, baseY0 + dy));
            int y1 = y0 + baseRh;
            double s = scoreRadarEdges(frame, baseX0, y0, baseX0 + baseRw, y1);
            if (s > bestScore) {
                bestScore = s;
                bestX0 = baseX0;
                bestY0 = y0;
            }
            if (s >= 0.72) break;
        }

        if (bestScore < 0.46) return null;
        return new int[] { bestX0, bestY0, bestX0 + baseRw, bestY0 + baseRh };
    }

    private static double scoreRadarEdges(RgbaImage frame, int x0, int y0, int x1, int y1) {
        double top = horizEdgeRatio(frame, x0, x1, y0, y0 - 2);
        double bottom = horizEdgeRatio(frame, x0, x1, y1 - 1, y1 + 2);
        double left = vertEdgeRatio(frame, y0, y1, x0, x0 - 2);
        double right = vertEdgeRatio(frame, y0, y1, x1 - 1, x1 + 2);
        if (top < 0.44 || bottom < 0.44 || left < 0.44 || right < 0.44) return 0.0;
        return (top + bottom + left + right) * 0.25;
    }

    private static double horizEdgeRatio(RgbaImage f, int x0, int x1, int by, int outY) {
        if (by < 1 || by + 1 >= f.height || outY < 0 || outY >= f.height) return 0;
        int hits = 0, total = 0;
        byte[] d = f.data;
        int w = f.width;
        for (int x = x0 + 8; x <= x1 - 8; x += 4) {
            total++;
            int b0 = (by * w + x) * 4;
            int b1 = ((by + 1) * w + x) * 4;
            int bo = (outY * w + x) * 4;
            int lum0 = ((d[b0] & 0xFF) + (d[b0 + 1] & 0xFF) + (d[b0 + 2] & 0xFF)) / 3;
            int lum1 = ((d[b1] & 0xFF) + (d[b1 + 1] & 0xFF) + (d[b1 + 2] & 0xFF)) / 3;
            int lumOut = ((d[bo] & 0xFF) + (d[bo + 1] & 0xFF) + (d[bo + 2] & 0xFF)) / 3;
            if (Math.max(lum0, lum1) - lumOut >= 8) hits++;
        }
        return total > 0 ? (double) hits / total : 0;
    }

    private static double vertEdgeRatio(RgbaImage f, int y0, int y1, int bx, int outX) {
        if (bx < 1 || bx + 1 >= f.width || outX < 0 || outX >= f.width) return 0;
        int hits = 0, total = 0;
        byte[] d = f.data;
        int w = f.width;
        for (int y = y0 + 8; y <= y1 - 8; y += 4) {
            total++;
            int b0 = (y * w + bx) * 4;
            int b1 = (y * w + bx + 1) * 4;
            int bo = (y * w + outX) * 4;
            int lum0 = ((d[b0] & 0xFF) + (d[b0 + 1] & 0xFF) + (d[b0 + 2] & 0xFF)) / 3;
            int lum1 = ((d[b1] & 0xFF) + (d[b1 + 1] & 0xFF) + (d[b1 + 2] & 0xFF)) / 3;
            int lumOut = ((d[bo] & 0xFF) + (d[bo + 1] & 0xFF) + (d[bo + 2] & 0xFF)) / 3;
            if (Math.max(lum0, lum1) - lumOut >= 8) hits++;
        }
        return total > 0 ? (double) hits / total : 0;
    }

    public static String detectHudPossessionMode(RgbaImage frame, BotConfig.ControlPoint buttonC) {
        int cx = buttonC.x;
        int cy = buttonC.y;
        double leftWing = localStrokeEnergy(frame, cx - 56, cx - 40, cy - 24, cy - 2);
        double rightWing = localStrokeEnergy(frame, cx + 40, cx + 56, cy - 24, cy - 2);
        double topArc = localStrokeEnergy(frame, cx - 28, cx + 28, cy - 58, cy - 40);
        double wingScore = (leftWing + rightWing) * 0.5;
        if (topArc < 0.014 && wingScore < 0.014) return "unknown";
        return wingScore >= 0.044 ? "defend" : "attack";
    }

    private static double localStrokeEnergy(RgbaImage f, int xStart, int xEnd, int yStart, int yEnd) {
        int high = 0, total = 0;
        int w = f.width, h = f.height;
        byte[] d = f.data;
        int ys = Math.max(2, yStart), ye = Math.min(h - 3, yEnd);
        int xs = Math.max(2, xStart), xe = Math.min(w - 3, xEnd);
        for (int y = ys; y <= ye; y++) {
            for (int x = xs; x <= xe; x++) {
                int i = (y * w + x) * 4;
                int lum = ((d[i] & 0xFF) + (d[i + 1] & 0xFF) + (d[i + 2] & 0xFF)) / 3;
                int left = ((d[i - 8] & 0xFF) + (d[i - 7] & 0xFF) + (d[i - 6] & 0xFF)) / 3;
                int right = ((d[i + 8] & 0xFF) + (d[i + 9] & 0xFF) + (d[i + 10] & 0xFF)) / 3;
                total++;
                if (lum >= 165 && (lum - Math.min(left, right)) >= 26) high++;
            }
        }
        return total > 0 ? (double) high / total : 0;
    }

    private static boolean detect3DGreenCarrierPin(RgbaImage frame) {
        int w = frame.width;
        byte[] d = frame.data;
        int count = 0;
        int maxX = Math.max(200, w - 90);
        for (int y = 90; y <= 545; y += 4) {
            for (int x = 90; x <= maxX; x += 4) {
                int i = (y * w + x) * 4;
                int r = d[i] & 0xFF, g = d[i + 1] & 0xFF, b = d[i + 2] & 0xFF;
                if (g >= 210 && r <= 80 && b >= 75 && b <= 165 && (g - r) >= 135) {
                    count++;
                    if (count >= 3) return true;
                }
            }
        }
        return false;
    }

    public static TacticalState analyze(
            RgbaImage frame,
            TacticalMemory mem,
            ScreenGeometry.ResolvedControls ctrl,
            Map<String, ZnccMatcher.MatchResult> detections
    ) {
        TacticalState st = new TacticalState();
        int[] radarBounds = detectAdaptiveRadarRect(frame);
        String hudMode = detectHudPossessionMode(frame, ctrl.buttonC);
        st.hasGreenPin3D = detect3DGreenCarrierPin(frame);
        st.possession = "defend".equals(hudMode) ? "defend" : "attack";

        if ("attack".equals(st.possession) && "defend".equals(mem.prevPossession)) {
            mem.justWonBallTicks = 3;
            mem.dribbleStreak = 0;
            mem.ballInFlightUntil = 0;
        } else if ("attack".equals(st.possession) && mem.justWonBallTicks > 0) {
            mem.justWonBallTicks--;
        } else if ("defend".equals(st.possession)) {
            mem.justWonBallTicks = 0;
            mem.ballInFlightUntil = 0;
            mem.pendingCutbackFinishUntil = 0;
        }
        mem.prevPossession = st.possession;

        ZnccMatcher.MatchResult bGlyph = detections != null ? detections.get("btn_b_glyph") : null;
        ZnccMatcher.MatchResult aGlyph = detections != null ? detections.get("btn_a_glyph") : null;
        if (bGlyph != null && aGlyph != null && bGlyph.configured && aGlyph.configured) {
            st.kickButtonsVisible = (bGlyph.score >= 0.62 && aGlyph.score >= 0.62);
        } else {
            st.kickButtonsVisible = !"unknown".equals(hudMode);
        }

        if (radarBounds == null) {
            st.radarVisible = false;
            st.isFirstPersonSetPiece = st.kickButtonsVisible;
            st.attackDir = mem.lockedAttackDir != 0 ? mem.lockedAttackDir : 1;
            return st;
        }

        st.radarVisible = true;
        int x0 = radarBounds[0], y0 = radarBounds[1], x1 = radarBounds[2], y1 = radarBounds[3];
        double rw = x1 - x0;
        double rh = y1 - y0;
        int w = frame.width;
        byte[] d = frame.data;

        int haloCount = 0, haloSumX = 0, haloSumY = 0;
        int[][] dirs = new int[][] { {-3, 0}, {3, 0}, {0, -3}, {0, 3}, {-2, -2}, {2, 2} };
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int i = (y * w + x) * 4;
                int r = d[i] & 0xFF, g = d[i + 1] & 0xFF, b = d[i + 2] & 0xFF;
                if (isRadarGrass(r, g, b)) continue;
                if (r < 35 && g < 35 && b < 35) {
                    boolean valid = false;
                    for (int[] off : dirs) {
                        int nx = Math.max(0, Math.min(w - 1, x + off[0]));
                        int ny = Math.max(0, Math.min(frame.height - 1, y + off[1]));
                        int ni = (ny * w + nx) * 4;
                        int nr = d[ni] & 0xFF, ng = d[ni + 1] & 0xFF, nb = d[ni + 2] & 0xFF;
                        if (isRadarGrass(nr, ng, nb) || Math.max(nr, Math.max(ng, nb)) >= 120) {
                            valid = true;
                            break;
                        }
                    }
                    if (valid) {
                        haloSumX += x;
                        haloSumY += y;
                        haloCount++;
                    }
                }
            }
        }

        if (haloCount >= 3) {
            int cx = Math.round((float) haloSumX / haloCount);
            int cy = Math.round((float) haloSumY / haloCount);
            PlayerDot me = new PlayerDot();
            me.x = cx;
            me.y = cy;
            me.normX = Math.max(0.0, Math.min(1.0, (cx - x0) / rw));
            me.normY = Math.max(0.0, Math.min(1.0, (cy - y0) / rh));
            st.me = me;

            double bestSat = -1;
            int[] shirt = null;
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int idx = ((cy + dy) * w + (cx + dx)) * 4;
                    int r = d[idx] & 0xFF, g = d[idx + 1] & 0xFF, b = d[idx + 2] & 0xFF;
                    if (r < 40 && g < 40 && b < 40) continue;
                    if (isRadarGrass(r, g, b)) continue;
                    double sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) + (r + g + b) * 0.25;
                    if (sat > bestSat) {
                        bestSat = sat;
                        shirt = new int[] { r, g, b };
                    }
                }
            }
            if (shirt != null && (mem.myShirtColor == null || (me.normX >= 0.12 && me.normX <= 0.88))) {
                if (mem.myShirtColor == null) {
                    mem.myShirtColor = shirt;
                } else {
                    mem.myShirtColor = new int[] {
                            (int) Math.round(mem.myShirtColor[0] * 0.82 + shirt[0] * 0.18),
                            (int) Math.round(mem.myShirtColor[1] * 0.82 + shirt[1] * 0.18),
                            (int) Math.round(mem.myShirtColor[2] * 0.82 + shirt[2] * 0.18)
                    };
                }
            }
        } else if (mem.prevMe != null && st.hasGreenPin3D) {
            PlayerDot ex = new PlayerDot();
            ex.x = mem.prevMe.x;
            ex.y = mem.prevMe.y;
            ex.normX = Math.max(0.02, Math.min(0.98, mem.prevMe.normX + (mem.hasLastCarry ? mem.lastCarryUx * 0.03 : 0)));
            ex.normY = Math.max(0.05, Math.min(0.95, mem.prevMe.normY + (mem.hasLastCarry ? mem.lastCarryUy * 0.03 : 0)));
            ex.extrapolated = true;
            st.me = ex;
        }

        List<double[]> clusters = new ArrayList<>();
        for (int y = y0 + 2; y <= y1 - 2; y += 2) {
            for (int x = x0 + 2; x <= x1 - 2; x += 2) {
                int idx = (y * w + x) * 4;
                int r = d[idx] & 0xFF, g = d[idx + 1] & 0xFF, b = d[idx + 2] & 0xFF;
                if (isRadarGrass(r, g, b)) continue;
                if (r < 42 && g < 42 && b < 42) continue;

                int idx2 = ((y + 1) * w + (x + 1)) * 4;
                if (isRadarGrass(d[idx2] & 0xFF, d[idx2 + 1] & 0xFF, d[idx2 + 2] & 0xFF)) continue;

                int maxC = Math.max(r, Math.max(g, b));
                int minC = Math.min(r, Math.min(g, b));
                int sat = maxC - minC;

                if (isPaintedRadarPitchLine(x, y, x0, y0, rw, rh, sat)) continue;
                if (sat < 22 && maxC < 195) continue;

                boolean merged = false;
                for (double[] cl : clusters) {
                    if (Math.hypot(x - cl[0], y - cl[1]) <= 6.5) {
                        cl[2] += x;
                        cl[3] += y;
                        cl[4] += r;
                        cl[5] += g;
                        cl[6] += b;
                        cl[7] += 1;
                        cl[0] = cl[2] / cl[7];
                        cl[1] = cl[3] / cl[7];
                        merged = true;
                        break;
                    }
                }
                if (!merged) {
                    clusters.add(new double[] { x, y, x, y, r, g, b, 1 });
                }
            }
        }

        if (mem.myShirtColor != null) {
            for (double[] cl : clusters) {
                int count = (int) cl[7];
                if (count < 2 || count > 22) continue;
                PlayerDot p = new PlayerDot();
                p.x = (int) Math.round(cl[0]);
                p.y = (int) Math.round(cl[1]);
                p.normX = (cl[0] - x0) / rw;
                p.normY = (cl[1] - y0) / rh;
                p.r = (int) Math.round(cl[4] / count);
                p.g = (int) Math.round(cl[5] / count);
                p.b = (int) Math.round(cl[6] / count);

                if (st.me != null && Math.hypot(p.x - st.me.x, p.y - st.me.y) <= 6.0) continue;
                if (colorDist(p.r, p.g, p.b, mem.myShirtColor) <= 72.0) {
                    st.teammates.add(p);
                } else {
                    st.opponents.add(p);
                }
            }
        }

        if (!mem.hardDirLocked) {
            List<Double> myX = new ArrayList<>();
            for (PlayerDot p : st.teammates) {
                if (p.normX >= 0.09 && p.normX <= 0.91) myX.add(p.normX);
            }
            if (st.me != null && st.me.normX >= 0.09 && st.me.normX <= 0.91) myX.add(st.me.normX);

            List<Double> oppX = new ArrayList<>();
            for (PlayerDot p : st.opponents) {
                if (p.normX >= 0.09 && p.normX <= 0.91) oppX.add(p.normX);
            }

            int vote = 0;
            if (myX.size() >= 3 && oppX.size() >= 3) {
                Collections.sort(myX);
                Collections.sort(oppX);
                double myCenter = (percentileSorted(myX, 0.25) + percentileSorted(myX, 0.50) + percentileSorted(myX, 0.75)) / 3.0;
                double oppCenter = (percentileSorted(oppX, 0.25) + percentileSorted(oppX, 0.50) + percentileSorted(oppX, 0.75)) / 3.0;
                if (myCenter < oppCenter - 0.055) vote = 1;
                else if (myCenter > oppCenter + 0.055) vote = -1;
            } else if (myX.size() >= 4) {
                double sum = 0;
                for (double v : myX) sum += v;
                double mean = sum / myX.size();
                if (mean < 0.42) vote = 1;
                else if (mean > 0.58) vote = -1;
            }

            if (vote != 0) {
                mem.dirVotes.add(vote);
                if (mem.dirVotes.size() > 9) mem.dirVotes.remove(0);
                int sum = 0;
                for (int v : mem.dirVotes) sum += v;
                if (sum != 0) mem.lockedAttackDir = sum > 0 ? 1 : -1;
                if (Math.abs(sum) >= 6) mem.hardDirLocked = true;
            }
        }

        st.attackDir = mem.lockedAttackDir != 0 ? mem.lockedAttackDir : 1;

        double ownGoalX = st.attackDir == 1 ? 0.0 : 1.0;
        for (int i = st.opponents.size() - 1; i >= 0; i--) {
            PlayerDot p = st.opponents.get(i);
            if (Math.abs(p.normX - ownGoalX) <= 0.085 && p.normY >= 0.32 && p.normY <= 0.68) {
                st.opponents.remove(i);
                st.teammates.add(p);
            }
        }

        if (st.me != null && !st.me.extrapolated && mem.prevMe != null) {
            double move = Math.hypot(st.me.normX - mem.prevMe.normX, st.me.normY - mem.prevMe.normY);
            if (move < 0.009) mem.staticTicks++;
            else mem.staticTicks = 0;
            if (mem.staticTicks >= 3 && !"defend".equals(hudMode) && System.currentTimeMillis() > mem.ballInFlightUntil) {
                st.isDeadBall = true;
            }
        }
        if (st.me != null && !st.me.extrapolated) {
            mem.prevMe = st.me;
        }

        st.progressX = st.me != null
                ? (st.attackDir == 1 ? st.me.normX : 1.0 - st.me.normX)
                : 0.5;
        return st;
    }

    private static double pointToSegmentDist(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax, vy = by - ay;
        double wx = px - ax, wy = py - ay;
        double lenSq = vx * vx + vy * vy;
        if (lenSq <= 1e-6) return Math.hypot(px - ax, py - ay);
        double t = Math.max(0.08, Math.min(0.95, (wx * vx + wy * vy) / lenSq));
        return Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
    }

    /**
     * Returns [bestPass, killerThrough, cutbackTarget, supportPass].
     * All unit vectors are aspect-correct (radar pixels); `dist` is in normalized-X units.
     */
    private static PassOption[] evaluatePasses(PlayerDot me, List<PlayerDot> teammates, List<PlayerDot> opponents, int dir, TacticalMemory mem) {
        PassOption[] out = new PassOption[4];
        if (me == null || teammates.isEmpty()) return out;

        List<Double> oppProg = new ArrayList<>();
        for (PlayerDot o : opponents) oppProg.add(dir == 1 ? o.normX : 1.0 - o.normX);
        Collections.sort(oppProg, Collections.reverseOrder());
        double offsideLine = oppProg.size() >= 2 ? oppProg.get(1) : 0.86;
        double myProg = dir == 1 ? me.normX : 1.0 - me.normX;
        boolean meWide = me.normY < 0.30 || me.normY > 0.70;

        long now = System.currentTimeMillis();

        for (PlayerDot tm : teammates) {
            double tmProg = dir == 1 ? tm.normX : 1.0 - tm.normX;
            double forwardGain = tmProg - myProg;
            double rawDx = tm.normX - me.normX;
            double rawDy = tm.normY - me.normY;
            double rawDist = Math.hypot(rawDx, rawDy);
            // Pass power curve is only tuned up to ~MAX_PASS_RANGE: longer "passes" died short and fed the bot
            if (rawDist < 0.06 || rawDist > MAX_PASS_RANGE) continue;
            if (myProg < 0.28 && tmProg < 0.25 && tm.normY > 0.30 && tm.normY < 0.70) continue;

            double minLane = 0.28, minRec = 0.28;
            for (PlayerDot opp : opponents) {
                double ld = pointToSegmentDist(opp.normX, opp.normY, me.normX, me.normY, tm.normX, tm.normY);
                if (ld < minLane) minLane = ld;
                double rd = Math.hypot(opp.normX - tm.normX, opp.normY - tm.normY);
                if (rd < minRec) minRec = rd;
            }

            // 1. Cutback target. ONLY a real cutback: the carrier is out wide near the byline and the pass goes
            //    across/back into the box; a central carrier may only "cut" to a teammate who is not behind him.
            boolean cutbackShape = (meWide && myProg >= 0.78 && tmProg >= myProg - 0.24
                    && Math.abs(rawDy) >= 0.12)
                    || (myProg >= 0.75 && forwardGain >= -0.025);
            if (cutbackShape && myProg >= 0.75 && tmProg >= 0.72 && tm.normY >= 0.28 && tm.normY <= 0.72 && minRec >= 0.065) {
                double leadX = Math.max(0.03, Math.min(0.97, tm.normX + dir * 0.02));
                double[] au = aimUnit(leadX - me.normX, tm.normY - me.normY);
                double sc = minRec * 3.2 + minLane * 2.6 + tmProg;
                if (out[2] == null || sc > out[2].score) {
                    PassOption c = new PassOption();
                    c.teammate = tm;
                    c.dist = au[2];
                    c.laneClearance = minLane;
                    c.score = sc;
                    c.unitX = au[0];
                    c.unitY = au[1];
                    c.useWhippedC = minLane < 0.050 && myProg >= 0.82;
                    out[2] = c;
                }
            }

            // 2. Lateral Support Tiki-Taka Recycle: strictly sideways (never a real backward pass)
            if (myProg >= 0.35 && forwardGain >= -0.012 && forwardGain < 0.022
                    && Math.abs(rawDy) >= 0.10 && rawDist <= 0.28 && minLane >= 0.085 && minRec >= 0.14) {
                double[] au = aimUnit(rawDx, rawDy);
                double sSc = minRec * 2.5 + minLane * 2.0 - rawDist;
                if (out[3] == null || sSc > out[3].score) {
                    PassOption sp = new PassOption();
                    sp.teammate = tm;
                    sp.dist = au[2];
                    sp.laneClearance = minLane;
                    sp.score = sSc;
                    sp.isSupportRecycle = true;
                    sp.unitX = au[0];
                    sp.unitY = au[1];
                    out[3] = sp;
                }
            }

            if (myProg >= 0.46) {
                if (forwardGain < 0.022) continue;
            } else if (forwardGain < -0.01) {
                continue;
            }
            if (minLane < 0.052) continue;

            // 3. Killer Ground Through-Ball (`15-32m` range)
            if (myProg >= 0.46 && rawDist <= 0.35 && forwardGain >= 0.09
                    && tmProg >= offsideLine - 0.14 && tmProg <= offsideLine + 0.015
                    && minRec >= 0.085 && minLane >= 0.068) {
                double aimX = Math.max(0.03, Math.min(0.97, tm.normX + dir * 0.055));
                double[] au = aimUnit(aimX - me.normX, tm.normY - me.normY);
                double sc = forwardGain * 3.0 + minRec * 2.5 + minLane * 2.4;
                if (out[1] == null || sc > out[1].score) {
                    PassOption kt = new PassOption();
                    kt.teammate = tm;
                    kt.dist = au[2];
                    kt.score = sc;
                    kt.unitX = au[0];
                    kt.unitY = au[1];
                    out[1] = kt;
                }
            }

            // 4. Crisp Ground Tiki-Taka Pass
            double leadX = Math.max(0.03, Math.min(0.97, tm.normX + dir * 0.03));
            double dx = leadX - me.normX, dy = tm.normY - me.normY;
            double normDist = Math.max(1e-4, Math.hypot(dx, dy));   // scoring metric (unchanged tuning)
            double[] au = aimUnit(dx, dy);
            double wallBonus = (mem != null && mem.lastPassOrigin != null && (now - mem.lastPassAt) < 2800L && forwardGain >= 0.05) ? 0.30 : 0.0;
            double sweetBonus = (normDist >= 0.10 && normDist <= 0.30) ? 0.25 : -Math.abs(normDist - 0.22) * 0.85;
            double sc = forwardGain * 3.2 + minLane * 2.8 + minRec * 1.8 + wallBonus + sweetBonus;

            if (out[0] == null || sc > out[0].score) {
                PassOption bp = new PassOption();
                bp.teammate = tm;
                bp.dist = au[2];
                bp.forwardGain = forwardGain;
                bp.laneClearance = minLane;
                bp.receiverSpace = minRec;
                bp.isWallPass = wallBonus > 0;
                bp.score = sc;
                bp.unitX = au[0];
                bp.unitY = au[1];
                out[0] = bp;
            }
        }
        return out;
    }

    /**
     * Throw-in / touchline restart target: ONLY teammates that are INTO the pitch from the thrower.
     * Backward/lateral is fine here (a throw-in is a restart, not an attack), forward is a bonus.
     */
    private static PassOption evaluateThrowInTarget(PlayerDot me, List<PlayerDot> teammates, List<PlayerDot> opponents, int dir, int inwardY) {
        PassOption best = null;
        double myProg = dir == 1 ? me.normX : 1.0 - me.normX;
        for (PlayerDot tm : teammates) {
            double rawDx = tm.normX - me.normX;
            double rawDy = tm.normY - me.normY;
            if (rawDy * inwardY < 0.05) continue;                 // must be inside the pitch from the thrower
            double[] au0 = aimUnit(rawDx, rawDy);
            if (au0[2] < 0.07 || au0[2] > 0.40) continue;
            double tmProg = dir == 1 ? tm.normX : 1.0 - tm.normX;
            double minLane = 0.28, minRec = 0.28;
            for (PlayerDot opp : opponents) {
                double ld = pointToSegmentDist(opp.normX, opp.normY, me.normX, me.normY, tm.normX, tm.normY);
                if (ld < minLane) minLane = ld;
                double rd = Math.hypot(opp.normX - tm.normX, opp.normY - tm.normY);
                if (rd < minRec) minRec = rd;
            }
            if (minLane < 0.040) continue;
            double sc = minRec * 2.2 + minLane * 2.0 + (tmProg - myProg) * 1.2 - au0[2] * 0.9;
            if (best == null || sc > best.score) {
                double leadX = Math.max(0.03, Math.min(0.97, tm.normX + dir * 0.015));
                double[] au = aimUnit(leadX - me.normX, tm.normY - me.normY);
                PassOption o = new PassOption();
                o.teammate = tm;
                o.dist = au[2];
                o.laneClearance = minLane;
                o.receiverSpace = minRec;
                o.score = sc;
                o.unitX = au[0];
                o.unitY = au[1];
                best = o;
            }
        }
        return best;
    }

    /** Picks where a wing cross should land: best-spaced teammate in the box, else the penalty spot. Returns {x, y}. */
    private static double[] pickCrossTarget(PlayerDot me, List<PlayerDot> teammates, List<PlayerDot> opponents, int dir) {
        double bestSpace = -1;
        double[] best = null;
        for (PlayerDot tm : teammates) {
            double tmProg = dir == 1 ? tm.normX : 1.0 - tm.normX;
            if (tmProg < 0.76 || tmProg > 0.97 || tm.normY < 0.22 || tm.normY > 0.78) continue;
            if (Math.abs(tm.normY - me.normY) < 0.12) continue;
            double space = 0.30;
            for (PlayerDot opp : opponents) {
                double rd = Math.hypot(opp.normX - tm.normX, opp.normY - tm.normY);
                if (rd < space) space = rd;
            }
            if (space > bestSpace) {
                bestSpace = space;
                best = new double[] { Math.max(0.03, Math.min(0.97, tm.normX + dir * 0.015)), tm.normY };
            }
        }
        if (best != null) return best;
        return new double[] { dir == 1 ? 0.88 : 0.12, 0.50 };
    }

    private static double[] evaluateDribble(PlayerDot me, List<PlayerDot> opponents, int dir, double progressX, TacticalMemory mem) {
        int[] angles = new int[] { 0, -14, 14, -28, 28, -44, 44, -62, 62 };
        double bestUx = dir, bestUy = 0, bestUtil = 0.62, bestScore = -1e9;
        double goalX = dir == 1 ? 0.96 : 0.04;

        for (int deg : angles) {
            double rad = (deg * Math.PI) / 180.0;
            double ux = Math.cos(rad) * dir;
            double uy = Math.sin(rad);
            // Step is measured in radar pixels (aspect-correct): same length whatever the angle
            double stepX = me.normX + ux * 0.12;
            double stepY = me.normY + uy * 0.12 * RADAR_ASPECT;

            // Never carry the ball OUT: skip steps that leave the pitch unless they move back inward
            if (stepY < 0.08 || stepY > 0.92) {
                boolean inwardMove = stepY < 0.08 ? uy > 0.05 : uy < -0.05;
                if (!inwardMove) continue;
            }
            if (stepX < 0.03 || stepX > 0.97) {
                boolean inwardMove = stepX < 0.03 ? ux > 0.05 : ux < -0.05;
                if (!inwardMove) continue;
            }

            double minOpp = 0.32, rep = 0;
            for (PlayerDot opp : opponents) {
                double d = pointToSegmentDist(opp.normX, opp.normY, me.normX, me.normY, stepX, stepY);
                if (d < minOpp) minOpp = d;
                double pd = Math.hypot(opp.normX - stepX, opp.normY - stepY);
                if (pd < 0.17) rep += 0.018 / Math.max(0.022, pd * pd);
            }

            double[] gu = aimUnit(goalX - me.normX, 0.50 - me.normY);
            double align = ux * gu[0] + uy * gu[1];
            boolean wingFinal = progressX >= 0.62 && (me.normY < 0.26 || me.normY > 0.74);
            double cutWeight = wingFinal ? 1.20 : (progressX >= 0.58 ? 0.85 : 0.50);
            double mom = (mem != null && mem.hasLastCarry && minOpp >= 0.09)
                    ? (ux * mem.lastCarryUx + uy * mem.lastCarryUy) * 0.26
                    : 0.0;

            // Gentle push away from the touchline so wingers hug it less and never drift over it
            double edge = Math.min(stepY, 1.0 - stepY);
            double edgePenalty = edge < 0.10 ? (0.10 - edge) * 6.0 : 0.0;

            double sc = minOpp * 3.5 + align * cutWeight + mom - rep * 0.13 - Math.abs(deg) * 0.002 - edgePenalty;
            if (sc > bestScore) {
                bestScore = sc;
                bestUx = ux;
                bestUy = uy;
                bestUtil = Math.max(0.22, Math.min(0.94, minOpp * 4.5));
            }
        }

        if (bestScore <= -1e8) {
            // Boxed against the line: head back into the pitch instead of straight along / over it
            int inward = me.normY < 0.5 ? 1 : -1;
            double[] fu = aimUnit(dir * 0.30, inward * 0.30);
            return new double[] { fu[0], fu[1], 0.50 };
        }
        return new double[] { bestUx, bestUy, bestUtil };
    }

    private static String determineZone(double progressX, double normY, double nearestOppAhead, TacticalMemory mem) {
        if (progressX < 0.20) return "own_box_build";
        if (progressX >= 0.81 && normY >= 0.27 && normY <= 0.73) return "one_on_one_keeper";
        if (progressX >= 0.78 && (normY < 0.25 || normY > 0.75)) return "byline_cutback_zone";
        if (progressX >= 0.77 && normY >= 0.25 && normY <= 0.75) return "final_third_center";
        if (progressX >= 0.65 && (normY < 0.25 || normY > 0.75)) return "final_third_wing";
        if (progressX >= 0.65) return "box_approach_play";
        if (mem != null && mem.justWonBallTicks > 0 && nearestOppAhead >= 0.14) return "counter_attack_space";
        boolean pressured = nearestOppAhead < 0.125;
        if (progressX < 0.38) return pressured ? "build_up_pressured" : "build_up_open";
        return pressured ? "midfield_pressured" : "midfield_open";
    }

    private static int calcPassPowerMs(double dist, boolean isLofted) {
        if (isLofted) return (int) Math.round(Math.max(185, Math.min(340, 140 + dist * 460)));
        return (int) Math.round(Math.max(105, Math.min(240, 82 + dist * 390)));
    }

    /** Cross power (lofted C). Previously a flat 205ms, which died before reaching anyone in the box. */
    private static int calcCrossPowerMs(double dist) {
        return (int) Math.round(Math.max(CROSS_MIN_MS, Math.min(CROSS_MAX_MS, CROSS_BASE_MS + dist * CROSS_SLOPE_MS)));
    }

    private static double[] computeSmartGoalAim(PlayerDot me, List<PlayerDot> opponents, int dir) {
        double goalX = dir == 1 ? 1.0 : 0.0;
        PlayerDot oppGk = null;
        for (PlayerDot o : opponents) {
            double op = dir == 1 ? o.normX : 1.0 - o.normX;
            if (op >= 0.86 && o.normY >= 0.34 && o.normY <= 0.66) {
                if (oppGk == null || op > (dir == 1 ? oppGk.normX : 1.0 - oppGk.normX)) oppGk = o;
            }
        }
        double goalY = oppGk != null ? (oppGk.normY <= 0.50 ? 0.56 : 0.44) : (me.normY < 0.50 ? 0.55 : 0.45);
        return aimUnit(goalX - me.normX, goalY - me.normY);
    }

    public static String executePlay(
            TacticalState tac,
            BrainLearner learner,
            TouchDispatcher touch,
            ScreenGeometry geom,
            ScreenGeometry.ResolvedControls ctrl,
            BotConfig cfg,
            TacticalMemory mem
    ) {
        BotConfig.ControlPoint joy = ctrl.joystick;
        int jitter = cfg.jitterPx;
        int dir = tac.attackDir != 0 ? tac.attackDir : 1;
        long now = System.currentTimeMillis();

        // CASE 0: 1st-Person Set Piece (Goal Kick, Corner, Free Kick)
        if (!tac.radarVisible && tac.isFirstPersonSetPiece) {
            mem.setPieceCounter++;
            double[] sides = new double[] { -0.38, 0.38, -0.20, 0.20 };
            double sideX = sides[mem.setPieceCounter % sides.length];
            int jx = (int) Math.round(joy.x + sideX * joy.radius);
            int jy = (int) Math.round(joy.y - 0.45 * joy.radius);
            touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, ctrl.buttonC.x, ctrl.buttonC.y, 405, true, jitter);
            return "Bóng chết góc nhìn 1: Phát bóng C (405ms)";
        }

        // CASE 1: Cutscene / Celebration / Replay -> Skip
        if (!tac.radarVisible) {
            touch.releaseAll();
            touch.tap(ctrl.skipTap.x, ctrl.skipTap.y, 45, jitter);
            return "Tua nhanh cảnh quay chậm / ăn mừng";
        }

        if (tac.me == null) {
            mem.noMeTicks++;
            // Last known position tells us if we were at a touchline: aim INTO the pitch, never along/over it
            int inwardY = touchlineInward(mem.prevMe);
            double[] mv = inwardY != 0 ? aimUnit(dir * 0.35, inwardY * 0.30) : new double[] { dir, 0.0 };
            int jx = (int) Math.round(joy.x + mv[0] * joy.radius);
            int jy = (int) Math.round(joy.y + mv[1] * joy.radius);
            // Only an occasional unstick pass after ~2s without finding our player (was a blind B every 3rd tick)
            if (mem.noMeTicks >= 5 && mem.noMeTicks % 5 == 0) {
                touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, ctrl.buttonB.x, ctrl.buttonB.y, 155, true, jitter);
                return "Mất dấu cầu thủ: Chuyền sệt B gỡ kẹt (155ms)";
            }
            touch.holdJoystick(joy.x, joy.y, jx, jy, 2);
            return "Mất dấu cầu thủ: Giữ cần hướng vào sân";
        }
        mem.noMeTicks = 0;

        PlayerDot me = tac.me;

        // CASE 2: Goalkeeper Distribution (Prefer short build-up B to open fullback!)
        if ("attack".equals(tac.possession) && tac.progressX <= 0.12 && me.normY >= 0.26 && me.normY <= 0.74) {
            PassOption bp = evaluatePasses(me, tac.teammates, tac.opponents, dir, mem)[0];
            if (bp != null && bp.receiverSpace >= 0.14 && bp.laneClearance >= 0.08 && bp.dist <= 0.34) {
                int jx = (int) Math.round(joy.x + bp.unitX * joy.radius);
                int jy = (int) Math.round(joy.y + bp.unitY * joy.radius);
                mem.ballInFlightUntil = now + 1050L;
                touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, ctrl.buttonB.x, ctrl.buttonB.y, 175, true, jitter);
                return "GK triển khai sệt cho hậu vệ biên (B 175ms)";
            }
            double wingSign = me.normY < 0.5 ? 0.26 : -0.26;
            int jx = joy.x + dir * joy.radius;
            int jy = (int) Math.round(joy.y + wingSign * joy.radius);
            mem.ballInFlightUntil = now + 1300L;
            touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, ctrl.buttonC.x, ctrl.buttonC.y, 390, true, jitter);
            return "GK phát bóng dài C lên tuyến trên (390ms)";
        }

        // CASE 3: Top-down Dead Ball (Kick-off, Throw-in, Midfield Free Kick -> Crisp Ground Pass B!)
        if (tac.isDeadBall) {
            mem.staticTicks = 0;
            int inwardY = touchlineInward(me);   // != 0 -> throw-in: ball MUST go into the pitch
            PassOption bp = inwardY != 0
                    ? evaluateThrowInTarget(me, tac.teammates, tac.opponents, dir, inwardY)
                    : evaluatePasses(me, tac.teammates, tac.opponents, dir, mem)[0];
            double ux, uy, dist;
            if (bp != null) {
                ux = bp.unitX;
                uy = bp.unitY;
                dist = bp.dist;
            } else if (inwardY != 0) {
                double[] fu = aimUnit(dir * 0.30, inwardY * 0.30);
                ux = fu[0];
                uy = fu[1];
                dist = 0.20;
            } else {
                ux = dir;
                uy = -0.20;
                dist = 0.20;
            }
            if (inwardY != 0) {
                double[] fixed = ensureInward(ux, uy, inwardY, 0.45, dir);
                ux = fixed[0];
                uy = fixed[1];
            }
            int holdMs = calcPassPowerMs(dist, false);
            int jx = (int) Math.round(joy.x + ux * joy.radius);
            int jy = (int) Math.round(joy.y + uy * joy.radius);
            mem.ballInFlightUntil = now + 950L;
            touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, ctrl.buttonB.x, ctrl.buttonB.y, holdMs, true, jitter);
            return (inwardY != 0 ? "Ném biên vào trong sân cho đồng đội (B " : "Giao bóng / Đá phạt sệt cho đồng đội (B ") + holdMs + "ms)";
        }

        // CASE 4: Disciplined Human-Like Defense (ZERO reckless `A` slide tackles!)
        if ("defend".equals(tac.possession)) {
            mem.dribbleStreak = 0;
            mem.hasLastCarry = false;
            double ownGoalX = dir == 1 ? 0.02 : 0.98;
            double carrierDist = 1.0;
            PlayerDot carrierOpp = null;
            for (PlayerDot o : tac.opponents) {
                double d = Math.hypot(o.normX - me.normX, o.normY - me.normY);
                if (d < carrierDist) {
                    carrierDist = d;
                    carrierOpp = o;
                }
            }

            boolean beaten = carrierOpp != null && ((me.normX - carrierOpp.normX) * dir > 0.045);
            boolean hasGoalSideCover = false;
            if (carrierOpp != null) {
                double oppDistGoal = Math.abs(carrierOpp.normX - ownGoalX);
                for (PlayerDot tm : tac.teammates) {
                    double tmDistGoal = Math.abs(tm.normX - ownGoalX);
                    double tmDistOpp = Math.hypot(tm.normX - carrierOpp.normX, tm.normY - carrierOpp.normY);
                    if (tmDistGoal > 0.07 && tmDistGoal < oppDistGoal + 0.01 && tmDistOpp < carrierDist + 0.04 && tmDistOpp < 0.24) {
                        hasGoalSideCover = true;
                        break;
                    }
                }
            }

            if (((beaten && hasGoalSideCover) || carrierDist > 0.25) && (now - mem.lastSwitchAt) >= 1350L) {
                mem.lastSwitchAt = now;
                touch.aimAndPressButton(0, 0, 0, 0, false, ctrl.buttonC.x, ctrl.buttonC.y, 65, true, jitter);
                return "Thủ [Đổi người C]: Chuyển sang hậu vệ đứng trước mặt bóng";
            }

            if (carrierOpp == null) {
                double[] hu = aimUnit((dir == 1 ? 0.30 : 0.70) - me.normX, 0.50 - me.normY);
                int jx = (int) Math.round(joy.x + hu[0] * joy.radius);
                int jy = (int) Math.round(joy.y + hu[1] * joy.radius);
                touch.holdJoystick(joy.x, joy.y, jx, jy, 2);
                return "Thủ: Giữ cự ly bọc lót trung lộ";
            }

            // Drag the joystick straight AT the ball carrier. Far away: run to a point just goal-side of him
            // (cut the lane). Beaten: chase toward his goal-side. Close: go right through him.
            double tx = carrierOpp.normX, ty = carrierOpp.normY;
            if (carrierDist > 0.26) {
                tx -= dir * 0.03;
                ty = ty * 0.92 + 0.50 * 0.08;
            } else if (beaten) {
                tx -= dir * 0.04;
            }
            double[] cu = aimUnit(tx - me.normX, ty - me.normY);
            int jx = (int) Math.round(joy.x + cu[0] * joy.radius);
            int jy = (int) Math.round(joy.y + cu[1] * joy.radius);

            if (carrierDist <= PRESS_DIST) {
                // Close enough: joystick toward the carrier AND B held down without lifting between ticks
                touch.holdDefendContain(joy.x, joy.y, jx, jy, ctrl.buttonB.x, ctrl.buttonB.y, 2);
                return carrierDist <= 0.095
                        ? "Thủ [Áp sát giữ B]: Kéo cần vào chân đối phương, tì đè cướp bóng"
                        : "Thủ [Áp sát giữ B]: Dí sát người cầm bóng + giữ B";
            }
            touch.holdJoystick(joy.x, joy.y, jx, jy, 2);
            return "Thủ [Đuổi người cầm bóng]: Kéo cần chặn đường đối phương";
        }

        // CASE 5: Human-Like Tiki-Taka, First-Touch Cushion, Dribbling & Composed Box Finishing
        // 5-H. Header / volley when our own cross is arriving and we are the man in the box (one A press, 900ms gap)
        if (now <= mem.crossHeaderUntil && now >= mem.crossArriveFrom
                && tac.progressX >= 0.76 && me.normY >= 0.22 && me.normY <= 0.78
                && (now - mem.lastShotAt) >= 900L) {
            mem.lastShotAt = now;
            mem.ballInFlightUntil = 0;
            double[] aim = computeSmartGoalAim(me, tac.opponents, dir);
            int jx = (int) Math.round(joy.x + aim[0] * joy.radius);
            int jy = (int) Math.round(joy.y + aim[1] * joy.radius);
            touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, ctrl.buttonA.x, ctrl.buttonA.y, HEADER_HOLD_MS, true, jitter);
            return "Công [Đánh đầu]: Đón quả tạt, đánh đầu / vô lê (A " + HEADER_HOLD_MS + "ms)";
        }

        // 5A. Close-Range Tap-In after Sweaty Cutback
        if (tac.progressX >= 0.80 && me.normY >= 0.28 && me.normY <= 0.72
                && now <= mem.pendingCutbackFinishUntil && (now - mem.lastShotAt) >= 1400L) {
            mem.pendingCutbackFinishUntil = 0;
            mem.ballInFlightUntil = 0;
            mem.lastShotAt = now;
            double[] aim = computeSmartGoalAim(me, tac.opponents, dir);
            int jx = (int) Math.round(joy.x + aim[0] * joy.radius);
            int jy = (int) Math.round(joy.y + aim[1] * joy.radius);
            touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, ctrl.buttonB.x, ctrl.buttonB.y, 175, true, jitter);
            return "Công [Đệm bóng 1 chạm]: Đặt lòng B cận thành!";
        }

        // 5B. First-Touch Cushion while pass rolls to teammate!
        if (now < mem.ballInFlightUntil) {
            double[] drib;
            if (now <= mem.crossHeaderUntil) {
                // Our cross is in the air: move toward the landing zone (goal mouth) instead of wandering off
                double[] bu = aimUnit((dir == 1 ? 0.90 : 0.10) - me.normX, 0.50 - me.normY);
                drib = bu[2] < 0.04 ? new double[] { dir, 0.0, 0.5 } : new double[] { bu[0], bu[1], 0.5 };
            } else {
                drib = evaluateDribble(me, tac.opponents, dir, tac.progressX, mem);
            }
            mem.lastCarryUx = drib[0];
            mem.lastCarryUy = drib[1];
            mem.hasLastCarry = true;
            mem.dribbleStreak = 1;
            int jx = (int) Math.round(joy.x + drib[0] * joy.radius);
            int jy = (int) Math.round(joy.y + drib[1] * joy.radius);
            touch.holdJoystick(joy.x, joy.y, jx, jy, 2);
            return "Công [Đỡ bước một]: Khống chế bóng êm hướng khoảng trống";
        }

        double nearestAhead = 0.35;
        int closeCount = 0;
        for (PlayerDot o : tac.opponents) {
            double d = Math.hypot(o.normX - me.normX, o.normY - me.normY);
            if (d < 0.11) closeCount++;
            if ((o.normX - me.normX) * dir >= -0.02 && d < nearestAhead) nearestAhead = d;
        }

        String zone = determineZone(tac.progressX, me.normY, nearestAhead, mem);
        PassOption[] passes = evaluatePasses(me, tac.teammates, tac.opponents, dir, mem);
        PassOption bestPass = passes[0], killer = passes[1], cutback = passes[2], support = passes[3];
        double[] drib = evaluateDribble(me, tac.opponents, dir, tac.progressX, mem);

        // 5C. Composed Clinical Box Finishing (`>= 0.77` ONLY, 1600ms cooldown -> ZERO A-spam!)
        if (("one_on_one_keeper".equals(zone) || "final_third_center".equals(zone)) && (now - mem.lastShotAt) >= 1600L) {
            if ("final_third_center".equals(zone) && nearestAhead < 0.055 && cutback != null && cutback.laneClearance >= 0.07) {
                mem.dribbleStreak = 0;
                mem.lastPassAt = now;
                mem.ballInFlightUntil = now + 850L;
                mem.pendingCutbackFinishUntil = now + 1900L;
                int jx = (int) Math.round(joy.x + cutback.unitX * joy.radius);
                int jy = (int) Math.round(joy.y + cutback.unitY * joy.radius);
                touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, ctrl.buttonB.x, ctrl.buttonB.y, 155, true, jitter);
                return "Công [Dọn cỗ ngang]: Nhả B cho đồng đội trống trải đệm lưới!";
            }

            mem.dribbleStreak = 0;
            mem.hasLastCarry = false;
            mem.lastShotAt = now;
            mem.ballInFlightUntil = now + 1100L;
            double[] aim = computeSmartGoalAim(me, tac.opponents, dir);
            int jx = (int) Math.round(joy.x + aim[0] * joy.radius);
            int jy = (int) Math.round(joy.y + aim[1] * joy.radius);
            boolean useFinesseB = tac.progressX >= 0.82 || me.normY < 0.34 || me.normY > 0.66;
            int holdMs = useFinesseB ? 185 : 142;
            BotConfig.ControlPoint btn = useFinesseB ? ctrl.buttonB : ctrl.buttonA;
            learner.recordDecision(zone, useFinesseB ? "placed_shot_b" : "shoot_goal_a", tac.progressX);
            touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, btn.x, btn.y, holdMs, true, jitter);
            return useFinesseB
                    ? "Công [Dứt điểm tinh tế]: Sửa lòng sệt B góc xa (" + holdMs + "ms)!"
                    : "Công [Dứt điểm vòng cấm]: Sút chìm A gọn gàng (" + holdMs + "ms)!";
        }

        List<BrainLearner.Candidate> cands = new ArrayList<>();
        boolean canPass = (now - mem.lastPassAt) >= 950L;

        // A cutback that travels BACKWARD is only sensible from the byline or when we are actually blocked;
        // otherwise keep carrying the ball forward (this was the "attacker suddenly passes it back" bug).
        boolean cutbackOk = cutback != null && canPass
                && (cutback.unitX * dir > -0.10 || tac.progressX >= 0.86 || nearestAhead < 0.13);
        if (cutbackOk) {
            cands.add(new BrainLearner.Candidate("sweaty_cutback_b", 0.95, cutback.unitX, cutback.unitY, cutback.dist, cutback.useWhippedC));
        } else if ("byline_cutback_zone".equals(zone) && canPass && tac.progressX >= 0.83) {
            double[] ct = pickCrossTarget(me, tac.teammates, tac.opponents, dir);
            double[] cu = aimUnit(ct[0] - me.normX, ct[1] - me.normY);
            cands.add(new BrainLearner.Candidate("wing_cross_c", 0.82, cu[0], cu[1], cu[2], false));
        }

        if (killer != null && canPass) {
            boolean ready = mem.dribbleStreak >= 2 || nearestAhead < 0.155 || tac.progressX >= 0.62;
            cands.add(new BrainLearner.Candidate("killer_through_pass", ready ? Math.min(0.95, 0.76 + killer.score * 0.18) : 0.72, killer.unitX, killer.unitY, killer.dist, false));
        }

        if (bestPass != null && canPass) {
            double pu = Math.max(0.45, Math.min(0.88, 0.54 + bestPass.score * 0.25));
            if (nearestAhead < 0.150 || closeCount >= 1) pu = Math.min(0.94, pu + 0.22);
            if (mem.dribbleStreak >= 3) pu = Math.min(0.94, pu + 0.20);
            if ("box_approach_play".equals(zone)) pu = Math.min(0.95, pu + 0.16);
            cands.add(new BrainLearner.Candidate("pass_to_teammate", pu, bestPass.unitX, bestPass.unitY, bestPass.dist, bestPass.isWallPass));
        } else if (support != null && canPass && (nearestAhead < 0.115 || closeCount >= 2)) {
            cands.add(new BrainLearner.Candidate("pass_to_teammate", 0.86, support.unitX, support.unitY, support.dist, true));
        }

        if ((now - mem.lastSkillAt) >= 6000L && nearestAhead >= 0.21 && tac.progressX >= 0.42 && tac.progressX <= 0.72 && mem.dribbleStreak >= 2) {
            cands.add(new BrainLearner.Candidate("knock_on_burst", 0.84, drib[0], drib[1], 0, false));
        }

        double dribUtil;
        if (nearestAhead >= 0.155 && mem.dribbleStreak < 3) dribUtil = 0.90;
        else if (nearestAhead >= 0.125) dribUtil = Math.min(0.84, drib[2] + 0.12);
        else dribUtil = drib[2] * 0.52;
        cands.add(new BrainLearner.Candidate("dribble_corridor", dribUtil, drib[0], drib[1], 0, false));

        BrainLearner.Candidate chosen = learner.selectAction(zone, cands);
        learner.recordDecision(zone, chosen.name, tac.progressX);

        int jx = (int) Math.round(joy.x + chosen.ux * joy.radius);
        int jy = (int) Math.round(joy.y + chosen.uy * joy.radius);
        int skillBaseX = Math.round(geom.normWidth * 0.65f);

        switch (chosen.name) {
            case "sweaty_cutback_b": {
                mem.dribbleStreak = 0;
                mem.hasLastCarry = false;
                mem.lastPassAt = now;
                mem.ballInFlightUntil = now + 900L;
                mem.pendingCutbackFinishUntil = now + 2000L;
                int hold = chosen.flag ? 195 : 165;
                BotConfig.ControlPoint btn = chosen.flag ? ctrl.buttonC : ctrl.buttonB;
                touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, btn.x, btn.y, hold, true, jitter);
                return "Công [Căng ngang dọn cỗ]: Chuyền cắt mặt vòng cấm (" + (chosen.flag ? "C " : "B ") + hold + "ms)";
            }
            case "wing_cross_c": {
                mem.dribbleStreak = 0;
                mem.hasLastCarry = false;
                mem.lastPassAt = now;
                int crossMs = calcCrossPowerMs(chosen.dist);
                mem.ballInFlightUntil = now + 1150L;
                mem.crossArriveFrom = now + Math.round(450 + chosen.dist * 900);
                mem.crossHeaderUntil = now + 2300L;
                touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, ctrl.buttonC.x, ctrl.buttonC.y, crossMs, true, jitter);
                return "Công [Tạt cánh đáy biên]: Tạt C căng vào vòng cấm (" + crossMs + "ms)";
            }
            case "killer_through_pass": {
                mem.dribbleStreak = 0;
                mem.hasLastCarry = false;
                mem.lastPassAt = now;
                mem.lastPassOrigin = me;
                int hold = calcPassPowerMs(chosen.dist, false) + 15;
                mem.ballInFlightUntil = now + Math.round(750 + chosen.dist * 1300);
                touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, ctrl.buttonB.x, ctrl.buttonB.y, hold, false, jitter);
                return "Công [Chọc khe sệt]: Đẩy bóng B đón bước chạy (" + hold + "ms)";
            }
            case "pass_to_teammate": {
                mem.dribbleStreak = 0;
                mem.hasLastCarry = false;
                mem.lastPassAt = now;
                mem.lastPassOrigin = me;
                int hold = calcPassPowerMs(chosen.dist, false);
                mem.ballInFlightUntil = now + Math.round(700 + chosen.dist * 1250);
                touch.aimAndPressButton(joy.x, joy.y, jx, jy, true, ctrl.buttonB.x, ctrl.buttonB.y, hold, false, jitter);
                return chosen.flag
                        ? "Công [Đập nhả 1-2]: Phối hợp tam giác B (" + hold + "ms)"
                        : "Công [Tiki-Taka]: Chuyền sệt đồng đội B (" + hold + "ms)";
            }
            case "knock_on_burst": {
                mem.lastSkillAt = now;
                mem.dribbleStreak++;
                mem.lastCarryUx = chosen.ux;
                mem.lastCarryUy = chosen.uy;
                mem.hasLastCarry = true;
                touch.performSkillSwipe(
                        joy.x, joy.y, jx, jy,
                        skillBaseX, 360,
                        (int) Math.round(skillBaseX + chosen.ux * 110),
                        (int) Math.round(360 + chosen.uy * 80),
                        85, jitter
                );
                return "Công [Đẩy bóng dài]: Bứt tốc vào khoảng trống!";
            }
            default: {
                mem.dribbleStreak++;
                mem.lastCarryUx = chosen.ux;
                mem.lastCarryUy = chosen.uy;
                mem.hasLastCarry = true;
                touch.holdJoystick(joy.x, joy.y, jx, jy, 2);
                return "Công [Dẫn bóng nhịp #" + mem.dribbleStreak + "]: Rê dắt mượt hướng gôn (" + (dir > 0 ? "Phải" : "Trái") + ")";
            }
        }
    }
}
