package com.applemewmew.dlsauto.ai;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Human-Style Tiki-Taka & Composed Finishing Contextual Bandit Learner for Android.
 */
public final class BrainLearner {

    public static final class Candidate {
        public final String name;
        public final double utility;
        public final double ux;
        public final double uy;
        public final double dist;
        public final boolean flag; // isWallPass / useWhippedC / isSupportRecycle

        public Candidate(String name, double utility, double ux, double uy, double dist, boolean flag) {
            this.name = name;
            this.utility = utility;
            this.ux = ux;
            this.uy = uy;
            this.dist = dist;
            this.flag = flag;
        }
    }

    private final File file;
    private final Random rng = new Random();
    private final double alpha = 0.15;
    private double epsilon = 0.05;
    private final Map<String, Map<String, Double>> qTable = new HashMap<>();

    public int totalUpdates = 0;
    public int positiveOutcomes = 0;
    public int shotsTaken = 0;
    public int passChainsCompleted = 0;

    private String pendingZone = null;
    private String pendingAction = null;
    private double pendingProgressX = 0.5;
    private long pendingTime = 0;
    private int attackChainLength = 0;

    /** Exploration rate (0 = fully greedy). Self-tests set 0 so results are deterministic. */
    public void setEpsilon(double e) {
        this.epsilon = Math.max(0.0, Math.min(0.5, e));
    }

    public BrainLearner(File file) {
        this.file = file;
        seedDefaults();
        load();
    }

    private void putDefault(String zone, String action, double val) {
        Map<String, Double> m = qTable.get(zone);
        if (m == null) {
            m = new HashMap<>();
            qTable.put(zone, m);
        }
        m.put(action, val);
    }

    private void seedDefaults() {
        putDefault("own_box_build", "pass_to_teammate", 0.88);
        putDefault("own_box_build", "dribble_corridor", 0.35);

        putDefault("build_up_open", "dribble_corridor", 0.84);
        putDefault("build_up_open", "pass_to_teammate", 0.80);
        putDefault("build_up_open", "killer_through_pass", 0.78);

        putDefault("build_up_pressured", "pass_to_teammate", 0.90);
        putDefault("build_up_pressured", "dribble_corridor", 0.45);

        putDefault("midfield_open", "dribble_corridor", 0.86);
        putDefault("midfield_open", "pass_to_teammate", 0.82);
        putDefault("midfield_open", "killer_through_pass", 0.86);
        putDefault("midfield_open", "knock_on_burst", 0.72);

        putDefault("midfield_pressured", "pass_to_teammate", 0.90);
        putDefault("midfield_pressured", "killer_through_pass", 0.86);
        putDefault("midfield_pressured", "dribble_corridor", 0.48);

        putDefault("counter_attack_space", "dribble_corridor", 0.86);
        putDefault("counter_attack_space", "killer_through_pass", 0.90);
        putDefault("counter_attack_space", "pass_to_teammate", 0.82);
        putDefault("counter_attack_space", "knock_on_burst", 0.85);

        putDefault("box_approach_play", "killer_through_pass", 0.90);
        putDefault("box_approach_play", "pass_to_teammate", 0.88);
        putDefault("box_approach_play", "dribble_corridor", 0.82);

        putDefault("final_third_wing", "dribble_corridor", 0.85);
        putDefault("final_third_wing", "sweaty_cutback_b", 0.92);
        putDefault("final_third_wing", "pass_to_teammate", 0.80);

        putDefault("byline_cutback_zone", "sweaty_cutback_b", 0.95);
        putDefault("byline_cutback_zone", "wing_cross_c", 0.78);

        putDefault("final_third_center", "shoot_goal_a", 0.94);
        putDefault("final_third_center", "placed_shot_b", 0.94);
        putDefault("final_third_center", "sweaty_cutback_b", 0.88);

        putDefault("one_on_one_keeper", "placed_shot_b", 0.96);
        putDefault("one_on_one_keeper", "shoot_goal_a", 0.94);
    }

    public synchronized void load() {
        if (file == null || !file.exists()) return;
        try (FileInputStream fis = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            JSONObject root = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            JSONObject qObj = root.optJSONObject("qTable");
            if (qObj != null) {
                Iterator<String> zones = qObj.keys();
                while (zones.hasNext()) {
                    String z = zones.next();
                    JSONObject acts = qObj.optJSONObject(z);
                    if (acts == null) continue;
                    Map<String, Double> target = qTable.get(z);
                    if (target == null) {
                        target = new HashMap<>();
                        qTable.put(z, target);
                    }
                    Iterator<String> ak = acts.keys();
                    while (ak.hasNext()) {
                        String a = ak.next();
                        target.put(a, acts.optDouble(a, 0.65));
                    }
                }
            }
            // Protect shooting Q-values in finishing zones
            for (String z : new String[] { "final_third_center", "one_on_one_keeper" }) {
                Map<String, Double> m = qTable.get(z);
                if (m != null) {
                    if (!m.containsKey("shoot_goal_a") || m.get("shoot_goal_a") < 0.80) m.put("shoot_goal_a", 0.92);
                    if (!m.containsKey("placed_shot_b") || m.get("placed_shot_b") < 0.80) m.put("placed_shot_b", 0.94);
                }
            }
            JSONObject st = root.optJSONObject("stats");
            if (st != null) {
                totalUpdates = st.optInt("totalUpdates", totalUpdates);
                positiveOutcomes = st.optInt("positiveOutcomes", positiveOutcomes);
                shotsTaken = st.optInt("shotsTaken", shotsTaken);
                passChainsCompleted = st.optInt("passChainsCompleted", passChainsCompleted);
            }
        } catch (Throwable ignored) {}
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            JSONObject root = new JSONObject();
            JSONObject st = new JSONObject();
            st.put("totalUpdates", totalUpdates);
            st.put("positiveOutcomes", positiveOutcomes);
            st.put("shotsTaken", shotsTaken);
            st.put("passChainsCompleted", passChainsCompleted);
            root.put("stats", st);

            JSONObject qObj = new JSONObject();
            for (Map.Entry<String, Map<String, Double>> ze : qTable.entrySet()) {
                JSONObject ao = new JSONObject();
                for (Map.Entry<String, Double> ae : ze.getValue().entrySet()) {
                    ao.put(ae.getKey(), Math.round(ae.getValue() * 10000.0) / 10000.0);
                }
                qObj.put(ze.getKey(), ao);
            }
            root.put("qTable", qObj);

            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    public synchronized Candidate selectAction(String zone, List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        boolean lethal = "one_on_one_keeper".equals(zone) || "final_third_center".equals(zone);
        if (!lethal && candidates.size() > 1 && rng.nextDouble() < epsilon) {
            // Random exploration must never pick position-specific moves (cutback / cross): a "random" cutback
            // is exactly what made attackers suddenly pass the ball backwards.
            List<Candidate> pool = new ArrayList<>();
            for (Candidate c : candidates) {
                if (!"sweaty_cutback_b".equals(c.name) && !"wing_cross_c".equals(c.name)) pool.add(c);
            }
            if (pool.size() > 1) return pool.get(rng.nextInt(pool.size()));
        }
        Map<String, Double> zoneQ = qTable.get(zone);
        Candidate best = candidates.get(0);
        double bestScore = -1e9;
        for (Candidate c : candidates) {
            double q = (zoneQ != null && zoneQ.containsKey(c.name)) ? zoneQ.get(c.name) : 0.65;
            double combined = c.utility * 0.72 + q * 0.28;
            if (combined > bestScore) {
                bestScore = combined;
                best = c;
            }
        }
        return best;
    }

    public synchronized void recordDecision(String zone, String actionName, double progressX) {
        if ("shoot_goal_a".equals(actionName) || "placed_shot_b".equals(actionName)) {
            shotsTaken++;
        }
        pendingZone = zone;
        pendingAction = actionName;
        pendingProgressX = progressX;
        pendingTime = System.currentTimeMillis();
    }

    public synchronized void observeOutcome(String possession, double progressX, boolean enteredCutsceneAfterShot) {
        if ("attack".equals(possession)) {
            attackChainLength++;
            if (attackChainLength == 4) passChainsCompleted++;
        } else {
            attackChainLength = 0;
        }

        if (pendingZone == null || pendingAction == null) return;
        String z = pendingZone;
        String a = pendingAction;
        double prevProg = pendingProgressX;
        long t = pendingTime;
        pendingZone = null;
        pendingAction = null;

        if (System.currentTimeMillis() - t > 6000L) return;

        boolean isShot = "shoot_goal_a".equals(a) || "placed_shot_b".equals(a);
        double reward = 0.0;

        if (isShot) {
            reward = enteredCutsceneAfterShot ? 1.95 : 0.75;
        } else if ("attack".equals(possession)) {
            double delta = progressX - prevProg;
            if (prevProg >= 0.48 && delta < -0.045) {
                reward = -0.55;
            } else {
                reward = 0.25 + Math.max(-0.30, Math.min(1.10, delta * 3.5));
                if (progressX >= 0.75) reward += 0.50;
                if (attackChainLength >= 3 && delta >= 0.01) reward += 0.30;
            }
        } else if ("defend".equals(possession)) {
            reward = prevProg < 0.42 ? -0.75 : -0.30;
        }

        Map<String, Double> zoneQ = qTable.get(z);
        if (zoneQ == null) {
            zoneQ = new HashMap<>();
            qTable.put(z, zoneQ);
        }
        double oldQ = zoneQ.containsKey(a) ? zoneQ.get(a) : 0.65;
        double nextQ = Math.max(-1.0, Math.min(2.0, oldQ + alpha * (reward - oldQ)));
        if (isShot && nextQ < 0.80) nextQ = 0.80;
        zoneQ.put(a, nextQ);

        totalUpdates++;
        if (reward > 0.1) positiveOutcomes++;
        if (totalUpdates % 10 == 0) save();
    }

    public synchronized int getSuccessRatePct() {
        if (totalUpdates <= 0) return 0;
        return Math.round((positiveOutcomes * 100.0f) / totalUpdates);
    }
}
