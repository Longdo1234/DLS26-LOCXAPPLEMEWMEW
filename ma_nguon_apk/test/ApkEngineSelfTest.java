import com.applemewmew.dlsauto.ai.BrainLearner;
import com.applemewmew.dlsauto.ai.TacticsEngine;
import com.applemewmew.dlsauto.ai.TouchDispatcher;
import com.applemewmew.dlsauto.model.BotConfig;
import com.applemewmew.dlsauto.security.CopyrightGuard;
import com.applemewmew.dlsauto.vision.RgbaImage;
import com.applemewmew.dlsauto.vision.ScreenGeometry;
import com.applemewmew.dlsauto.vision.ZnccMatcher;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public final class ApkEngineSelfTest {

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("SELF-TEST FAILED: " + msg);
    }

    private static RgbaImage loadPngDesktop(File f) throws Exception {
        BufferedImage bi = ImageIO.read(f);
        int w = bi.getWidth();
        int h = bi.getHeight();
        int[] argb = bi.getRGB(0, 0, w, h, null, 0, w);
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            int p = i * 4;
            rgba[p] = (byte) ((c >> 16) & 0xFF);
            rgba[p + 1] = (byte) ((c >> 8) & 0xFF);
            rgba[p + 2] = (byte) (c & 0xFF);
            rgba[p + 3] = (byte) ((c >>> 24) & 0xFF);
        }
        return new RgbaImage(w, h, rgba);
    }

    private static RgbaImage convert16x9To20x9PhoneFrame(RgbaImage src1280) {
        RgbaImage out = new RgbaImage(1600, 720);
        for (int i = 0; i < 1600 * 720; i++) {
            out.data[i * 4] = 28;
            out.data[i * 4 + 1] = 58;
            out.data[i * 4 + 2] = 32;
            out.data[i * 4 + 3] = (byte) 255;
        }
        for (int y = 0; y < 720; y++) {
            System.arraycopy(src1280.data, (y * 1280) * 4, out.data, (y * 1600) * 4, 420 * 4);
            System.arraycopy(src1280.data, (y * 1280 + 420) * 4, out.data, (y * 1600 + 580) * 4, 440 * 4);
            System.arraycopy(src1280.data, (y * 1280 + 860) * 4, out.data, (y * 1600 + 1180) * 4, 420 * 4);
        }
        return out;
    }


    // ---------------------------------------------------------------------------------------------
    // Tactical regression tests (pure logic, no screenshots needed)
    // ---------------------------------------------------------------------------------------------
    private static final class Rec implements TouchDispatcher {
        String kind = "";
        double ux, uy;
        int hold = -1, bx, by;
        @Override public boolean isReady() { return true; }
        @Override public void tap(int x, int y, int d, int j) { kind = "tap"; }
        @Override public void holdJoystick(int cx, int cy, int tx, int ty, int j) { kind = "joy"; ux = (tx - 164) / 78.0; uy = (ty - 546) / 78.0; }
        @Override public void aimAndPressButton(int cx, int cy, int tx, int ty, boolean u, int bx, int by, int h, boolean r, int j) {
            kind = "btn"; ux = (tx - 164) / 78.0; uy = (ty - 546) / 78.0; hold = h; this.bx = bx; this.by = by;
        }
        @Override public void holdDefendContain(int cx, int cy, int tx, int ty, int bx, int by, int j) {
            kind = "press"; ux = (tx - 164) / 78.0; uy = (ty - 546) / 78.0; this.bx = bx; this.by = by;
        }
        @Override public void performSkillSwipe(int cx, int cy, int tx, int ty, int s1x, int s1y, int s2x, int s2y, int d, int j) { kind = "skill"; }
        @Override public void releaseAll() {}
    }

    private static TacticsEngine.PlayerDot dot(double x, double y) {
        TacticsEngine.PlayerDot d = new TacticsEngine.PlayerDot();
        d.normX = x; d.normY = y; d.x = (int) (x * 189); d.y = (int) (y * 127);
        return d;
    }

    private static TacticsEngine.TacticalState tacState(double mx, double my, String possession) {
        TacticsEngine.TacticalState t = new TacticsEngine.TacticalState();
        t.radarVisible = true; t.kickButtonsVisible = true; t.possession = possession; t.attackDir = 1;
        t.me = dot(mx, my); t.progressX = mx;
        return t;
    }

    private static ScreenGeometry.ResolvedControls tacCtrl() {
        ScreenGeometry.ResolvedControls rc = new ScreenGeometry.ResolvedControls();
        rc.joystick = new BotConfig.ControlPoint(164, 546, 78);
        rc.buttonB = new BotConfig.ControlPoint(1046, 652, 0);
        rc.buttonA = new BotConfig.ControlPoint(1197, 652, 0);
        rc.buttonC = new BotConfig.ControlPoint(1197, 486, 0);
        rc.skipTap = new BotConfig.ControlPoint(640, 250, 0);
        return rc;
    }

    private static String tacRun(TacticsEngine.TacticalState t, Rec r, TacticsEngine.TacticalMemory mem) {
        BrainLearner learner = new BrainLearner(null);
        learner.setEpsilon(0.0);
        return TacticsEngine.executePlay(t, learner, r, new ScreenGeometry(2400, 1080, 0, 0, -1), tacCtrl(), new BotConfig(), mem);
    }

    private static double angleDeg(double ux, double uy) { return Math.toDegrees(Math.atan2(uy, ux)); }

    private static void runTacticalRegression() {
        // A. Throw-in at either touchline must go INTO the pitch (screen y grows downward)
        for (double edgeY : new double[] { 0.02, 0.98 }) {
            int inward = edgeY < 0.5 ? 1 : -1;
            Rec r = new Rec();
            TacticsEngine.TacticalState t = tacState(0.50, edgeY, "attack");
            t.isDeadBall = true;
            t.teammates.add(dot(0.58, 0.30)); t.teammates.add(dot(0.42, 0.62)); t.teammates.add(dot(0.62, 0.80));
            t.opponents.add(dot(0.60, 0.45));
            tacRun(t, r, new TacticsEngine.TacticalMemory());
            check(r.kind.equals("btn") && r.uy * inward >= 0.44, "Throw-in with teammates must aim into the pitch (edgeY=" + edgeY + ")");

            r = new Rec();
            t = tacState(0.50, edgeY, "attack");
            t.isDeadBall = true;
            tacRun(t, r, new TacticsEngine.TacticalMemory());
            check(r.kind.equals("btn") && r.uy * inward >= 0.44, "Throw-in with NO teammates must still aim into the pitch (edgeY=" + edgeY + ")");
        }

        // B. Dribbling next to the touchline must never push toward the line
        for (double y : new double[] { 0.03, 0.05, 0.07, 0.93, 0.96 }) {
            int inward = y < 0.5 ? 1 : -1;
            for (double x : new double[] { 0.30, 0.45, 0.55 }) {
                Rec r = new Rec();
                TacticsEngine.TacticalState t = tacState(x, y, "attack");
                t.opponents.add(dot(x + 0.20, 0.5));
                TacticsEngine.TacticalMemory m = new TacticsEngine.TacticalMemory();
                m.lastPassAt = System.currentTimeMillis();
                tacRun(t, r, m);
                check(r.uy * inward >= -0.01, "Dribble must not run out of bounds (y=" + y + ", x=" + x + ")");
            }
        }

        // C. Diagonal pass direction is measured in radar PIXELS (189x127), not normalized units
        {
            Rec r = new Rec();
            TacticsEngine.TacticalState t = tacState(0.40, 0.30, "attack");
            t.teammates.add(dot(0.60, 0.50));
            t.opponents.add(dot(0.90, 0.90));
            TacticsEngine.TacticalMemory m = new TacticsEngine.TacticalMemory();
            m.dribbleStreak = 3;
            tacRun(t, r, m);
            double want = Math.toDegrees(Math.atan2((0.50 - 0.30) * 127, (0.63 - 0.40) * 189));
            check(r.kind.equals("btn") && Math.abs(angleDeg(r.ux, r.uy) - want) < 3.0,
                    "Diagonal pass must point at the teammate (got " + angleDeg(r.ux, r.uy) + " want " + want + ")");
        }

        // D. Wing cross: strong C press aimed at the man in the box, then a header window opens
        {
            Rec r = new Rec();
            TacticsEngine.TacticalState t = tacState(0.90, 0.10, "attack");
            t.teammates.add(dot(0.84, 0.50));
            t.opponents.add(dot(0.85, 0.52));
            t.opponents.add(dot(0.93, 0.18));
            TacticsEngine.TacticalMemory m = new TacticsEngine.TacticalMemory();
            tacRun(t, r, m);
            check(r.kind.equals("btn") && r.bx == 1197 && r.by == 486, "Wing cross must use button C");
            check(r.hold >= 290 && r.hold <= 400, "Cross power must be 290-400ms (was 205ms): " + r.hold);
            double want = Math.toDegrees(Math.atan2((0.50 - 0.10) * 127, (0.855 - 0.90) * 189));
            check(Math.abs(angleDeg(r.ux, r.uy) - want) < 3.0, "Cross must be aimed at the teammate in the box");
            check(m.crossHeaderUntil > System.currentTimeMillis(), "Cross must open the header window");
        }

        // E. Header: exactly one A press when the cross arrives, never before, never spammed
        {
            long n = System.currentTimeMillis();
            TacticsEngine.TacticalState t = tacState(0.88, 0.48, "attack");
            TacticsEngine.TacticalMemory m = new TacticsEngine.TacticalMemory();
            m.crossArriveFrom = n - 10; m.crossHeaderUntil = n + 1500; m.ballInFlightUntil = n + 500;
            Rec r = new Rec();
            tacRun(t, r, m);
            check(r.kind.equals("btn") && r.bx == 1197 && r.by == 652 && r.hold == 130, "Header = one short A press");
            Rec r2 = new Rec();
            tacRun(t, r2, m);
            check(!(r2.kind.equals("btn") && r2.bx == 1197 && r2.by == 652), "Header must not be spammed");
            TacticsEngine.TacticalMemory early = new TacticsEngine.TacticalMemory();
            early.crossArriveFrom = n + 600; early.crossHeaderUntil = n + 2000; early.ballInFlightUntil = n + 900;
            Rec r3 = new Rec();
            tacRun(t, r3, early);
            check(r3.kind.equals("joy"), "No A press while the cross is still flying");
        }

        // F. Pressing: close -> joystick AT the carrier + B held; far -> just run at him (no B)
        {
            Rec r = new Rec();
            TacticsEngine.TacticalState t = tacState(0.30, 0.50, "defend");
            t.opponents.add(dot(0.40, 0.58));
            t.teammates.add(dot(0.20, 0.50));
            tacRun(t, r, new TacticsEngine.TacticalMemory());
            double want = Math.toDegrees(Math.atan2((0.58 - 0.50) * 127, (0.40 - 0.30) * 189));
            check(r.kind.equals("press") && r.bx == 1046 && r.by == 652, "Close defending must hold B (holdDefendContain)");
            check(Math.abs(angleDeg(r.ux, r.uy) - want) < 3.0, "Joystick must point straight at the ball carrier");
            Rec r2 = new Rec();
            TacticsEngine.TacticalState t2 = tacState(0.20, 0.50, "defend");
            t2.opponents.add(dot(0.42, 0.55));
            t2.teammates.add(dot(0.10, 0.90));
            tacRun(t2, r2, new TacticsEngine.TacticalMemory());
            check(r2.kind.equals("joy") && r2.ux > 0.8, "Far defending must chase with the joystick only");
        }

        // G. Lost radar dot must not spam blind B passes
        {
            TacticsEngine.TacticalMemory m = new TacticsEngine.TacticalMemory();
            int presses = 0;
            for (int i = 0; i < 10; i++) {
                Rec r = new Rec();
                TacticsEngine.TacticalState t = tacState(0.5, 0.5, "attack");
                t.me = null;
                tacRun(t, r, m);
                if (r.kind.equals("btn")) presses++;
            }
            check(presses <= 2, "Lost radar dot: at most 2 unstick passes in 10 ticks, got " + presses);
        }
    }


    // ---------------------------------------------------------------------------------------------
    // Screen-ratio regression: every phone/tablet ratio must keep circles round, radar centered,
    // joystick angles intact and A/B/C anchored to the right edge.
    // ---------------------------------------------------------------------------------------------
    private static void runScreenRatioRegression() {
        int[][] devices = new int[][] {
                { 1280, 720 }, { 1920, 1080 }, { 2160, 1080 }, { 2280, 1080 }, { 2310, 1080 }, { 2340, 1080 },
                { 2400, 1080 }, { 3200, 1440 }, { 2520, 1080 }, { 2560, 1080 }, { 2560, 1600 }, { 2048, 1536 }
        };
        for (int[] d : devices) {
            String tag = d[0] + "x" + d[1];
            // Deliberately swapped (portrait-style) args: the class must normalise to landscape
            ScreenGeometry g = new ScreenGeometry(d[1], d[0], 0, 0, -1);
            double sx = g.physWidth / (double) g.normWidth;
            double sy = g.physHeight / (double) g.normHeight;
            check(g.physWidth >= g.physHeight, tag + ": must normalise to landscape");
            check(Math.abs(sx / sy - 1.0) < 0.001, tag + ": X/Y scale must match (round buttons), got " + sx + "/" + sy);

            ScreenGeometry.ResolvedControls rc = g.resolveControls(new BotConfig());
            int[] radar = g.defaultRadarBounds();
            check(radar[2] - radar[0] == 189 && radar[3] - radar[1] == 127, tag + ": radar is always 189x127 in normalized space");
            check(Math.abs((radar[0] + radar[2]) / 2.0 - g.normWidth / 2.0) <= 1.0, tag + ": radar must stay centered");
            check(rc.buttonA.x < g.normWidth - 40 && rc.buttonC.x < g.normWidth - 40, tag + ": A/C must not sit on the screen edge");
            check(rc.buttonB.x < rc.buttonA.x - 100, tag + ": B must stay clearly left of A");
            check(rc.joystick.x - rc.joystick.radius >= 0, tag + ": joystick must stay on screen");
            // A is anchored 83 normalized px from the right edge on EVERY ratio (as on 16:9)
            check(Math.abs((g.normWidth - rc.buttonA.x) - 83) <= 1, tag + ": A must keep its right-edge distance, got " + (g.normWidth - rc.buttonA.x));

            // Joystick direction survives the normalized -> physical mapping (needed for correct passes/crosses)
            for (int deg = 0; deg < 360; deg += 15) {
                double r = Math.toRadians(deg);
                int tx = (int) Math.round(rc.joystick.x + Math.cos(r) * rc.joystick.radius);
                int ty = (int) Math.round(rc.joystick.y + Math.sin(r) * rc.joystick.radius);
                double want = Math.toDegrees(Math.atan2(ty - rc.joystick.y, tx - rc.joystick.x));
                double got = Math.toDegrees(Math.atan2(
                        g.toPhysY(ty) - g.toPhysY(rc.joystick.y), g.toPhysX(tx) - g.toPhysX(rc.joystick.x)));
                double diff = Math.abs(((got - want + 540.0) % 360.0) - 180.0);
                check(diff < 1.5, tag + ": joystick angle drift " + diff + " deg at " + deg);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] certDer = Files.readAllBytes(Paths.get(args[0]));
        String validLine1 = "Bản quyền thuộc về Apple Mew Mew";
        String validLine2 = "Mọi tính năng đều miễn phí";

        // 1. Verify Brand & Certificate Binding
        CopyrightGuard.applyTestVerification(validLine1, validLine2, certDer);
        check(CopyrightGuard.isSplashCompleted(), "Valid author signature must unlock");
        check(CopyrightGuard.znccBias() == 0.0, "Valid znccBias must be 0.0");
        check(CopyrightGuard.coordCipherOffset() == 0, "Valid coordCipherOffset must be 0");

        CopyrightGuard.applyTestVerification("Edited Author", validLine2, certDer);
        check(!CopyrightGuard.isSplashCompleted(), "Modified line 1 must lock");
        check(CopyrightGuard.znccBias() <= -1000.0, "Modified line 1 must disable ZNCC");

        CopyrightGuard.applyTestVerification(validLine1, "Edited Line 2", certDer);
        check(!CopyrightGuard.isSplashCompleted(), "Modified line 2 must lock");

        byte[] alteredCert = certDer.clone();
        alteredCert[alteredCert.length / 2] ^= 0xFF;
        CopyrightGuard.applyTestVerification(validLine1, validLine2, alteredCert);
        check(!CopyrightGuard.isSplashCompleted(), "Modified signing certificate must lock");

        CopyrightGuard.applyTestVerification(validLine1, validLine2, certDer);

        // 1b. Tactical regression suite (pressing, crosses/headers, passing, throw-ins, touchline dribbling)
        runTacticalRegression();
        runScreenRatioRegression();

        // 2. Verify all 12 Bundled Templates (including start_new_division.png)
        // Project folder is passed by build-apk.ps1 (args[1]); falls back to the old fixed path for manual runs.
        String projDir = args.length > 1 ? args[1] : "D:/DLS25/dls-auto-apk";
        File tplDir = new File(projDir, "assets/templates");
        Map<String, ZnccMatcher.PreparedTemplate> tpls = new HashMap<>();
        for (File f : tplDir.listFiles()) {
            if (f.getName().endsWith(".png")) {
                String slotName = f.getName().substring(0, f.getName().length() - 4);
                tpls.put(slotName, new ZnccMatcher.PreparedTemplate(loadPngDesktop(f)));
            }
        }
        check(tpls.size() == 12 && tpls.containsKey("start_new_division"),
                "All 12 PNG templates including start_new_division must load: found " + tpls.size());

        ScreenGeometry geom20x9 = new ScreenGeometry(2400, 1080, 0, 0, -1);
        check(geom20x9.normWidth == 1600 && geom20x9.normHeight == 720, "2400x1080 -> 1600x720");

        // 3. Test Real 20:9 In-Match Frame: Immediate Frame-1 AttackDir Lock (-1), Glyph Snap & Human Play
        File liveFile = new File(projDir, "../dls26-auto/captures/latest-studio.png");
        String realMatchPlay = "OK";
        if (liveFile.exists()) {
            RgbaImage phone20x9Live = convert16x9To20x9PhoneFrame(loadPngDesktop(liveFile));
            ZnccMatcher.FrameFeatures ff = ZnccMatcher.prepareFrame(phone20x9Live);

            BotConfig.TemplateSlot sHud = new BotConfig.TemplateSlot();
            sHud.anchor = "LEFT";
            sHud.roi = new BotConfig.Roi(0, 0, 92, 82);
            ZnccMatcher.MatchResult mrHud = ZnccMatcher.matchInRoi(
                    ff, tpls.get("in_match_hud"), geom20x9.resolveRoi(sHud), 0.75, 95.0, 0, 0);
            check(mrHud.matched && mrHud.score >= 0.98, "20:9 Real In-Match HUD || matched");

            BotConfig cfg = new BotConfig();
            ScreenGeometry.ResolvedControls rc = geom20x9.resolveControls(cfg);
            TacticsEngine.TacticalMemory mem = new TacticsEngine.TacticalMemory();
            TacticsEngine.TacticalState tac = TacticsEngine.analyze(phone20x9Live, mem, rc, null);
            check(tac.radarVisible && tac.attackDir == -1,
                    "20:9 Centered Radar must lock attackDir = -1 on Frame 1! got " + tac.attackDir);

            final int[] gestures = new int[] { 0 };
            final int[] buttonAPresses = new int[] { 0 };
            TouchDispatcher mockTouch = new TouchDispatcher() {
                @Override public boolean isReady() { return true; }
                @Override public void tap(int x, int y, int d, int j) { gestures[0]++; }
                @Override public void holdJoystick(int cx, int cy, int tx, int ty, int j) { gestures[0]++; }
                @Override public void aimAndPressButton(int cx, int cy, int tx, int ty, boolean u, int bx, int by, int h, boolean r, int j) {
                    gestures[0]++;
                    if (bx == rc.buttonA.x && by == rc.buttonA.y) buttonAPresses[0]++;
                }
                @Override public void holdDefendContain(int cx, int cy, int tx, int ty, int bx, int by, int j) { gestures[0]++; }
                @Override public void performSkillSwipe(int cx, int cy, int tx, int ty, int s1x, int s1y, int s2x, int s2y, int d, int j) { gestures[0]++; }
                @Override public void releaseAll() {}
            };
            BrainLearner learner = new BrainLearner(null);
            realMatchPlay = TacticsEngine.executePlay(tac, learner, mockTouch, geom20x9, rc, cfg, mem);
            check(gestures[0] == 1 && buttonAPresses[0] == 0,
                    "Midfield kickoff on 20:9 must dribble/pass smoothly with ZERO A-button spam!");

            // Verify Defense NEVER presses button A (zero slide-tackle spam!)
            tac.possession = "defend";
            TacticsEngine.executePlay(tac, learner, mockTouch, geom20x9, rc, cfg, mem);
            check(buttonAPresses[0] == 0, "Defense must NEVER press button A!");
        }

        System.out.println("PASS ALL V3 HUMAN-STYLE 20:9 ANDROID APK TESTS! (" + realMatchPlay + ")");
    }
}
