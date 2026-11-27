package com.applemewmew.dlsauto.vision;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.applemewmew.dlsauto.model.BotConfig;
import com.applemewmew.dlsauto.security.CopyrightGuard;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * High-speed Integral-Image ZNCC (TM_CCOEFF_NORMED) + RGB Color-Gate Template Matcher.
 * Cryptographically coupled to CopyrightGuard.znccScaleFactor().
 */
public final class ZnccMatcher {

    public static final class FrameFeatures {
        public final int width;
        public final int height;
        public final byte[] data;
        public final short[] gray;
        public final int stride;
        public final double[] sum;
        public final double[] sumSq;
        public final double[] sumR;
        public final double[] sumG;
        public final double[] sumB;

        private FrameFeatures(RgbaImage img) {
            this.width = img.width;
            this.height = img.height;
            this.data = img.data;
            int total = width * height;
            this.gray = new short[total];
            this.stride = width + 1;
            int tableLen = stride * (height + 1);
            this.sum = new double[tableLen];
            this.sumSq = new double[tableLen];
            this.sumR = new double[tableLen];
            this.sumG = new double[tableLen];
            this.sumB = new double[tableLen];

            for (int y = 0; y < height; y++) {
                double rowLum = 0, rowLumSq = 0, rowR = 0, rowG = 0, rowB = 0;
                int prevOff = y * stride;
                int curOff = (y + 1) * stride;
                int pixRow = y * width;

                for (int x = 0; x < width; x++) {
                    int p = (pixRow + x) * 4;
                    int r = data[p] & 0xFF;
                    int g = data[p + 1] & 0xFF;
                    int b = data[p + 2] & 0xFF;
                    int lum = (77 * r + 150 * g + 29 * b) >> 8;
                    gray[pixRow + x] = (short) lum;

                    rowLum += lum;
                    rowLumSq += lum * lum;
                    rowR += r;
                    rowG += g;
                    rowB += b;

                    int idx = curOff + x + 1;
                    int above = prevOff + x + 1;
                    sum[idx] = sum[above] + rowLum;
                    sumSq[idx] = sumSq[above] + rowLumSq;
                    sumR[idx] = sumR[above] + rowR;
                    sumG[idx] = sumG[above] + rowG;
                    sumB[idx] = sumB[above] + rowB;
                }
            }
        }
    }

    public static final class PreparedTemplate {
        public final int width;
        public final int height;
        public final boolean isOpaque;
        public final int activeCount;
        public final double mean;
        public final double norm;
        public final float[] zeroMean;
        public final byte[] mask;
        public final double meanR;
        public final double meanG;
        public final double meanB;

        public PreparedTemplate(RgbaImage img) {
            this.width = img.width;
            this.height = img.height;
            int total = width * height;
            float[] lum = new float[total];
            this.mask = new byte[total];
            int count = 0;
            double lSum = 0, rSum = 0, gSum = 0, bSum = 0;

            for (int i = 0; i < total; i++) {
                int p = i * 4;
                int a = img.data[p + 3] & 0xFF;
                if (a >= 128) {
                    int r = img.data[p] & 0xFF;
                    int g = img.data[p + 1] & 0xFF;
                    int b = img.data[p + 2] & 0xFF;
                    int y = (77 * r + 150 * g + 29 * b) >> 8;
                    lum[i] = y;
                    mask[i] = 1;
                    lSum += y;
                    rSum += r;
                    gSum += g;
                    bSum += b;
                    count++;
                }
            }
            if (count == 0) {
                throw new IllegalArgumentException("Template PNG is completely transparent");
            }
            this.activeCount = count;
            this.isOpaque = (count == total);
            this.mean = lSum / count;
            this.meanR = rSum / count;
            this.meanG = gSum / count;
            this.meanB = bSum / count;

            this.zeroMean = new float[total];
            double normSq = 0;
            for (int i = 0; i < total; i++) {
                if (mask[i] != 0) {
                    float d = (float) (lum[i] - mean);
                    zeroMean[i] = d;
                    normSq += d * d;
                }
            }
            this.norm = Math.sqrt(normSq);
        }
    }

    public static final class MatchResult {
        public boolean configured;
        public boolean matched;
        public double score;
        public double colorDiff;
        public int x, y, w, h;
        public int centerX, centerY;
        public int tapX, tapY;
    }

    private final File templatesDir;
    private final Map<String, CacheEntry> cache = new HashMap<>();

    private static final class CacheEntry {
        long mtime;
        long size;
        PreparedTemplate tpl;
    }

    public ZnccMatcher(File templatesDir) {
        this.templatesDir = templatesDir;
    }

    public synchronized void clearCache() {
        cache.clear();
    }

    public static FrameFeatures prepareFrame(RgbaImage frame) {
        return new FrameFeatures(frame);
    }

    private static double rectSum(double[] table, int stride, int x, int y, int w, int h) {
        int x2 = x + w;
        int y2 = y + h;
        return table[y2 * stride + x2]
                - table[y * stride + x2]
                - table[y2 * stride + x]
                + table[y * stride + x];
    }

    public static double scoreAt(FrameFeatures f, PreparedTemplate tpl, int ox, int oy) {
        int tw = tpl.width;
        int th = tpl.height;
        int fw = f.width;
        short[] gray = f.gray;
        float[] zm = tpl.zeroMean;

        if (tpl.norm < 1e-5) {
            double s = rectSum(f.sum, f.stride, ox, oy, tw, th) / (tw * th);
            return Math.max(0.0, 1.0 - Math.abs(s - tpl.mean) / 64.0) + CopyrightGuard.znccBias();
        }

        if (tpl.isOpaque) {
            double n = tw * th;
            double winSum = rectSum(f.sum, f.stride, ox, oy, tw, th);
            double winSumSq = rectSum(f.sumSq, f.stride, ox, oy, tw, th);
            double varTimesN = winSumSq - (winSum * winSum) / n;
            if (varTimesN <= 1e-5) return 0.0 + CopyrightGuard.znccBias();

            double dot = 0;
            for (int y = 0; y < th; y++) {
                int fIdx = (oy + y) * fw + ox;
                int tIdx = y * tw;
                for (int x = 0; x < tw; x++) {
                    dot += gray[fIdx + x] * zm[tIdx + x];
                }
            }
            return (dot / (Math.sqrt(varTimesN) * tpl.norm)) + CopyrightGuard.znccBias();
        }

        byte[] mask = tpl.mask;
        double winSum = 0, winSumSq = 0, dot = 0;
        for (int y = 0; y < th; y++) {
            int fRow = (oy + y) * fw + ox;
            int tRow = y * tw;
            for (int x = 0; x < tw; x++) {
                if (mask[tRow + x] != 0) {
                    int v = gray[fRow + x];
                    winSum += v;
                    winSumSq += v * v;
                    dot += v * zm[tRow + x];
                }
            }
        }
        double varTimesN = winSumSq - (winSum * winSum) / tpl.activeCount;
        if (varTimesN <= 1e-5) return 0.0 + CopyrightGuard.znccBias();
        return (dot / (Math.sqrt(varTimesN) * tpl.norm)) + CopyrightGuard.znccBias();
    }

    private static double windowColorDiff(FrameFeatures f, PreparedTemplate tpl, int ox, int oy) {
        double area = tpl.width * tpl.height;
        double mr = rectSum(f.sumR, f.stride, ox, oy, tpl.width, tpl.height) / area;
        double mg = rectSum(f.sumG, f.stride, ox, oy, tpl.width, tpl.height) / area;
        double mb = rectSum(f.sumB, f.stride, ox, oy, tpl.width, tpl.height) / area;
        return Math.max(
                Math.abs(mr - tpl.meanR),
                Math.max(Math.abs(mg - tpl.meanG), Math.abs(mb - tpl.meanB))
        );
    }

    public static MatchResult matchInRoi(
            FrameFeatures frame,
            PreparedTemplate tpl,
            BotConfig.Roi roi,
            double threshold,
            double maxColorDiff,
            int tapDx,
            int tapDy
    ) {
        MatchResult res = new MatchResult();
        res.configured = true;

        int rx = roi != null ? Math.max(0, Math.min(frame.width - 1, roi.x)) : 0;
        int ry = roi != null ? Math.max(0, Math.min(frame.height - 1, roi.y)) : 0;
        int rw = roi != null ? Math.max(1, Math.min(frame.width - rx, roi.w)) : frame.width - rx;
        int rh = roi != null ? Math.max(1, Math.min(frame.height - ry, roi.h)) : frame.height - ry;

        if (tpl.width > rw || tpl.height > rh) {
            res.matched = false;
            res.score = 0;
            res.colorDiff = 255;
            return res;
        }

        int maxX = rx + rw - tpl.width;
        int maxY = ry + rh - tpl.height;

        int bestX = rx;
        int bestY = ry;
        double bestScore = -9999.0;

        // O(1) integral-image RGB gate filters ~97% of positions in nanoseconds,
        // enabling exact 1-pixel ZNCC peak localization with zero sub-sampling aliasing!
        for (int y = ry; y <= maxY; y++) {
            for (int x = rx; x <= maxX; x++) {
                if (windowColorDiff(frame, tpl, x, y) > maxColorDiff) {
                    continue;
                }
                double s = scoreAt(frame, tpl, x, y);
                if (s > bestScore) {
                    bestScore = s;
                    bestX = x;
                    bestY = y;
                }
            }
        }

        double cDiff = windowColorDiff(frame, tpl, bestX, bestY);
        res.score = Math.round(bestScore * 10000.0) / 10000.0;
        res.colorDiff = Math.round(cDiff * 10.0) / 10.0;
        res.x = bestX;
        res.y = bestY;
        res.w = tpl.width;
        res.h = tpl.height;
        res.centerX = bestX + tpl.width / 2;
        res.centerY = bestY + tpl.height / 2;
        res.tapX = res.centerX + tapDx;
        res.tapY = res.centerY + tapDy;
        res.matched = (bestScore >= threshold) && (cDiff <= maxColorDiff);
        return res;
    }

    public synchronized PreparedTemplate loadSlotTemplate(BotConfig.TemplateSlot slot) {
        if (slot == null || slot.file == null || templatesDir == null) return null;
        File f = new File(templatesDir, slot.file);
        if (!f.exists() || f.length() < 32) return null;

        String key = f.getAbsolutePath();
        CacheEntry ce = cache.get(key);
        if (ce != null && ce.mtime == f.lastModified() && ce.size == f.length()) {
            return ce.tpl;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeFile(key, opts);
        if (bmp == null) return null;
        PreparedTemplate pt = new PreparedTemplate(RgbaImage.fromBitmap(bmp));
        bmp.recycle();

        CacheEntry next = new CacheEntry();
        next.mtime = f.lastModified();
        next.size = f.length();
        next.tpl = pt;
        cache.put(key, next);
        return pt;
    }

    public Map<String, MatchResult> detectAll(RgbaImage normFrame, BotConfig cfg, ScreenGeometry geom) {
        FrameFeatures ff = prepareFrame(normFrame);
        Map<String, MatchResult> out = new HashMap<>();
        for (Map.Entry<String, BotConfig.TemplateSlot> entry : cfg.templates.entrySet()) {
            String slotName = entry.getKey();
            BotConfig.TemplateSlot slot = entry.getValue();
            PreparedTemplate tpl = loadSlotTemplate(slot);
            if (tpl == null) {
                MatchResult empty = new MatchResult();
                empty.configured = false;
                empty.matched = false;
                out.put(slotName, empty);
                continue;
            }
            BotConfig.Roi roi = geom.resolveRoi(slot);
            MatchResult mr = matchInRoi(ff, tpl, roi, slot.threshold, slot.maxColorDiff, slot.tapDx, slot.tapDy);
            out.put(slotName, mr);
        }
        return out;
    }
}
