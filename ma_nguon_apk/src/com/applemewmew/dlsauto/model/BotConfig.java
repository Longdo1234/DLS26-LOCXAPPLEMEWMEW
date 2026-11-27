package com.applemewmew.dlsauto.model;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent configuration, template slot definitions, Android aspect-ratio anchor settings,
 * and single-file (.dlsauto) Preset Export/Import for easy DLS25 <-> DLS26 switching.
 */
public final class BotConfig {

    public static final String[] SLOT_ORDER = new String[] {
            "online_live_guard",
            "replay_exit",
            "popup_close",
            "sub_confirm",
            "half_time_resume",
            "pause_resume",
            "post_match_continue",
            "pre_match_play",
            "lobby_play",
            "home_career",
            "start_new_division",
            "cutscene_skip",
            "in_match_hud",
            "nav_back",
            "btn_a_glyph",
            "btn_b_glyph"
    };

    public String mode = "career";
    public int maxMatches = 100;
    public int loopIntervalMs = 390;
    public int fastSkipIntervalMs = 450;
    public int jitterPx = 3;
    public boolean keepScreenAwake = true;
    public boolean autoSnapGlyphButtons = true;
    public int safeMarginOverridePx = -1; // -1 = auto from DisplayCutout

    public String gameplayStyle = "smart_radar";
    public int actionCooldownMs = 360;
    public int skipReplayIntervalMs = 480;

    public boolean calibratedOnPhone = false;
    public int calibratedNormW = 1280;
    public ControlPoint joystick = new ControlPoint(164, 546, 78);
    public ControlPoint buttonB = new ControlPoint(1046, 652, 0);
    public ControlPoint buttonA = new ControlPoint(1197, 652, 0);
    public ControlPoint buttonC = new ControlPoint(1197, 486, 0);
    public ControlPoint skipTap = new ControlPoint(640, 250, 0);

    public final Map<String, TemplateSlot> templates = new LinkedHashMap<>();

    public static final class ControlPoint {
        public int x;
        public int y;
        public int radius;

        public ControlPoint(int x, int y, int radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }

        public ControlPoint copy() {
            return new ControlPoint(x, y, radius);
        }
    }

    public static final class Roi {
        public int x;
        public int y;
        public int w;
        public int h;

        public Roi(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    public static final class TemplateSlot {
        public String name;
        public String label;
        public String file;
        public String anchor = "RIGHT"; // LEFT, RIGHT, CENTER
        public boolean phoneNative = false;
        public int phoneNormW = 1280;
        public Roi roi = new Roi(0, 0, 1280, 720);
        public double threshold = 0.80;
        public double maxColorDiff = 52.0;
        public int tapDx = 0;
        public int tapDy = 0;
    }

    public static File getTemplatesDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "templates");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getConfigFile(Context ctx) {
        return new File(ctx.getFilesDir(), "user_config.json");
    }

    public static File getBrainFile(Context ctx) {
        return new File(ctx.getFilesDir(), "brain_weights.json");
    }

    public static void ensureSeededFromAssets(Context ctx) {
        try {
            File tplDir = getTemplatesDir(ctx);
            String[] assetTemplates = ctx.getAssets().list("templates");
            if (assetTemplates != null) {
                for (String name : assetTemplates) {
                    if (!name.toLowerCase().endsWith(".png")) continue;
                    File dst = new File(tplDir, name);
                    if (!dst.exists()) {
                        copyAssetToFile(ctx, "templates/" + name, dst);
                    }
                }
            }
            File brainDst = getBrainFile(ctx);
            if (!brainDst.exists()) {
                try {
                    copyAssetToFile(ctx, "brain_weights.json", brainDst);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static void resetTemplatesToDefaultDls25(Context ctx) throws Exception {
        File tplDir = getTemplatesDir(ctx);
        File[] existing = tplDir.listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (f.getName().endsWith(".png")) f.delete();
            }
        }
        String[] assetTemplates = ctx.getAssets().list("templates");
        if (assetTemplates != null) {
            for (String name : assetTemplates) {
                if (name.toLowerCase().endsWith(".png")) {
                    copyAssetToFile(ctx, "templates/" + name, new File(tplDir, name));
                }
            }
        }
        File cfgFile = getConfigFile(ctx);
        if (cfgFile.exists()) cfgFile.delete();
    }

    public static BotConfig load(Context ctx) {
        ensureSeededFromAssets(ctx);
        try {
            String defaultJson = readAssetText(ctx, "default_config.json");
            BotConfig cfg = fromJsonString(defaultJson);
            File userFile = getConfigFile(ctx);
            if (userFile.exists()) {
                String userJson = readFileText(userFile);
                BotConfig userCfg = fromJsonString(userJson);
                return userCfg;
            }
            cfg.save(ctx);
            return cfg;
        } catch (Throwable t) {
            return new BotConfig();
        }
    }

    public void save(Context ctx) {
        try {
            String json = toJson().toString(2);
            try (FileOutputStream fos = new FileOutputStream(getConfigFile(ctx))) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    public static BotConfig fromJsonString(String jsonStr) throws Exception {
        JSONObject root = new JSONObject(jsonStr);
        BotConfig c = new BotConfig();

        JSONObject run = root.optJSONObject("run");
        if (run != null) {
            c.mode = run.optString("mode", "career");
            c.maxMatches = Math.max(0, run.optInt("maxMatches", 100));
            c.loopIntervalMs = Math.max(220, run.optInt("loopIntervalMs", 390));
            c.fastSkipIntervalMs = Math.max(260, run.optInt("fastSkipIntervalMs", 450));
            c.jitterPx = Math.max(0, Math.min(16, run.optInt("jitterPx", 3)));
            c.keepScreenAwake = run.optBoolean("keepScreenAwake", true);
            c.autoSnapGlyphButtons = run.optBoolean("autoSnapGlyphButtons", true);
            c.safeMarginOverridePx = run.optInt("safeMarginOverridePx", -1);
        }

        JSONObject gp = root.optJSONObject("gameplay");
        if (gp != null) {
            c.gameplayStyle = gp.optString("style", "smart_radar");
            c.actionCooldownMs = Math.max(200, gp.optInt("actionCooldownMs", 360));
            c.skipReplayIntervalMs = Math.max(280, gp.optInt("skipReplayIntervalMs", 480));
        }

        JSONObject ctrl = root.optJSONObject("controls");
        if (ctrl != null) {
            c.calibratedOnPhone = ctrl.optBoolean("calibratedOnPhone", false);
            c.calibratedNormW = ctrl.optInt("calibratedNormW", 1280);
            c.joystick = parsePt(ctrl.optJSONObject("joystick"), 164, 546, 78);
            c.buttonB = parsePt(ctrl.optJSONObject("buttonB"), 1046, 652, 0);
            c.buttonA = parsePt(ctrl.optJSONObject("buttonA"), 1197, 652, 0);
            c.buttonC = parsePt(ctrl.optJSONObject("buttonC"), 1197, 486, 0);
            c.skipTap = parsePt(ctrl.optJSONObject("skipTap"), 640, 250, 0);
        }

        JSONObject tpls = root.optJSONObject("templates");
        if (tpls != null) {
            Iterator<String> keys = tpls.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject o = tpls.optJSONObject(k);
                if (o == null) continue;
                TemplateSlot slot = new TemplateSlot();
                slot.name = k;
                slot.label = o.optString("label", k);
                slot.file = o.optString("file", k + ".png");
                slot.anchor = o.optString("anchor", "RIGHT");
                slot.phoneNative = o.optBoolean("phoneNative", false);
                slot.phoneNormW = o.optInt("phoneNormW", 1280);
                JSONObject r = o.optJSONObject("roi");
                if (r != null) {
                    slot.roi = new Roi(
                            r.optInt("x", 0),
                            r.optInt("y", 0),
                            r.optInt("w", 200),
                            r.optInt("h", 120)
                    );
                }
                slot.threshold = o.optDouble("threshold", 0.78);
                slot.maxColorDiff = o.optDouble("maxColorDiff", 52.0);
                JSONObject off = o.optJSONObject("tapOffset");
                if (off != null) {
                    slot.tapDx = off.optInt("dx", 0);
                    slot.tapDy = off.optInt("dy", 0);
                }
                c.templates.put(k, slot);
            }
        }
        return c;
    }

    public JSONObject toJson() throws Exception {
        JSONObject root = new JSONObject();

        JSONObject run = new JSONObject();
        run.put("mode", mode);
        run.put("maxMatches", maxMatches);
        run.put("loopIntervalMs", loopIntervalMs);
        run.put("fastSkipIntervalMs", fastSkipIntervalMs);
        run.put("jitterPx", jitterPx);
        run.put("keepScreenAwake", keepScreenAwake);
        run.put("autoSnapGlyphButtons", autoSnapGlyphButtons);
        run.put("safeMarginOverridePx", safeMarginOverridePx);
        root.put("run", run);

        JSONObject gp = new JSONObject();
        gp.put("style", gameplayStyle);
        gp.put("actionCooldownMs", actionCooldownMs);
        gp.put("skipReplayIntervalMs", skipReplayIntervalMs);
        root.put("gameplay", gp);

        JSONObject ctrl = new JSONObject();
        ctrl.put("calibratedOnPhone", calibratedOnPhone);
        ctrl.put("calibratedNormW", calibratedNormW);
        ctrl.put("joystick", ptJson(joystick, true));
        ctrl.put("buttonB", ptJson(buttonB, false));
        ctrl.put("buttonA", ptJson(buttonA, false));
        ctrl.put("buttonC", ptJson(buttonC, false));
        ctrl.put("skipTap", ptJson(skipTap, false));
        root.put("controls", ctrl);

        JSONObject tpls = new JSONObject();
        for (Map.Entry<String, TemplateSlot> e : templates.entrySet()) {
            TemplateSlot s = e.getValue();
            JSONObject o = new JSONObject();
            o.put("label", s.label);
            o.put("file", s.file);
            o.put("anchor", s.anchor);
            o.put("phoneNative", s.phoneNative);
            o.put("phoneNormW", s.phoneNormW);
            JSONObject r = new JSONObject();
            r.put("x", s.roi.x);
            r.put("y", s.roi.y);
            r.put("w", s.roi.w);
            r.put("h", s.roi.h);
            o.put("roi", r);
            o.put("threshold", s.threshold);
            o.put("maxColorDiff", s.maxColorDiff);
            JSONObject off = new JSONObject();
            off.put("dx", s.tapDx);
            off.put("dy", s.tapDy);
            o.put("tapOffset", off);
            tpls.put(e.getKey(), o);
        }
        root.put("templates", tpls);
        return root;
    }

    /**
     * Bundle config + all PNG templates into a single portable JSON (.dlsauto) string
     * so users can save/share DLS25 vs DLS26 profiles right from their phone!
     */
    public String exportBundleString(Context ctx) throws Exception {
        JSONObject bundle = new JSONObject();
        bundle.put("format", "dlsauto-v1");
        bundle.put("config", toJson());
        JSONObject filesObj = new JSONObject();
        File tplDir = getTemplatesDir(ctx);
        File[] list = tplDir.listFiles();
        if (list != null) {
            for (File f : list) {
                if (!f.getName().toLowerCase().endsWith(".png")) continue;
                byte[] bytes = readFileBytes(f);
                filesObj.put(f.getName(), Base64.encodeToString(bytes, Base64.NO_WRAP));
            }
        }
        bundle.put("templatesBase64", filesObj);
        return bundle.toString(2);
    }

    public static BotConfig importBundleString(Context ctx, String raw) throws Exception {
        JSONObject bundle = new JSONObject(raw);
        JSONObject cfgJson = bundle.has("config") ? bundle.getJSONObject("config") : bundle;
        BotConfig cfg = fromJsonString(cfgJson.toString());
        JSONObject filesObj = bundle.optJSONObject("templatesBase64");
        if (filesObj != null) {
            File tplDir = getTemplatesDir(ctx);
            Iterator<String> keys = filesObj.keys();
            while (keys.hasNext()) {
                String fileName = keys.next();
                if (!fileName.endsWith(".png") || fileName.contains("/") || fileName.contains("\\")) continue;
                byte[] bytes = Base64.decode(filesObj.getString(fileName), Base64.DEFAULT);
                try (FileOutputStream fos = new FileOutputStream(new File(tplDir, fileName))) {
                    fos.write(bytes);
                }
            }
        }
        cfg.save(ctx);
        return cfg;
    }

    private static ControlPoint parsePt(JSONObject o, int defX, int defY, int defR) {
        if (o == null) return new ControlPoint(defX, defY, defR);
        return new ControlPoint(o.optInt("x", defX), o.optInt("y", defY), o.optInt("radius", defR));
    }

    private static JSONObject ptJson(ControlPoint p, boolean withRadius) throws Exception {
        JSONObject o = new JSONObject();
        o.put("x", p.x);
        o.put("y", p.y);
        if (withRadius) o.put("radius", p.radius);
        return o;
    }

    private static void copyAssetToFile(Context ctx, String assetPath, File dst) throws Exception {
        try (InputStream is = ctx.getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
    }

    private static String readAssetText(Context ctx, String assetPath) throws Exception {
        try (InputStream is = ctx.getAssets().open(assetPath)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static byte[] readFileBytes(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    public static String readFileText(File f) throws Exception {
        return new String(readFileBytes(f), StandardCharsets.UTF_8);
    }
}
