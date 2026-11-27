package com.applemewmew.dlsauto.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import com.applemewmew.dlsauto.R;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Cryptographic Anti-Crack & Copyright Self-Integrity Engine.
 * Binds both the exact Vietnamese copyright strings AND the release X.509 certificate
 * directly into the runtime math multipliers of ZNCC vision and Multi-Touch coordinates.
 * Any string modification in resources.arsc / classes.dex or APK re-signing disables execution.
 */
public final class CopyrightGuard {

    // SHA-256("DLS_AMM_V1|Bản quyền thuộc về Apple Mew Mew\nMọi tính năng đều miễn phí")
    private static final byte[] EXPECTED_TEXT_HASH = hexToBytes(
            "161d0635636622034f6d404c2ab008b7d3c731f9ac4b62108fa9adc885f11a4a"
    );

    // SHA-256 of official Apple Mew Mew release X.509 certificate DER bytes
    private static final byte[] EXPECTED_CERT_HASH = hexToBytes(
            "ceddf4409378c0c9c38684c522c1864d8d0256936ab2f1024ba371238c5ad794"
    );

    private static volatile int sDiffAccumulator = 0x5A5A5A5A; // Non-zero until verified!
    private static volatile boolean sSplashCompleted = false;

    private CopyrightGuard() {}

    public static int verifyRaw(String line1, String line2, byte[] certDerBytes) {
        if (line1 == null || line2 == null || certDerBytes == null) {
            return 0x7F001122;
        }
        try {
            MessageDigest md1 = MessageDigest.getInstance("SHA-256");
            byte[] payload = ("DLS_AMM_V1|" + line1 + "\n" + line2).getBytes(StandardCharsets.UTF_8);
            byte[] th = md1.digest(payload);

            MessageDigest md2 = MessageDigest.getInstance("SHA-256");
            byte[] ch = md2.digest(certDerBytes);

            int diff = 0;
            for (int i = 0; i < 32; i++) {
                diff |= (th[i] ^ EXPECTED_TEXT_HASH[i]) & 0xFF;
                diff |= (ch[i] ^ EXPECTED_CERT_HASH[i]) & 0xFF;
            }
            return diff; // Strictly 0 ONLY when both copyright lines AND signing cert are untouched!
        } catch (Throwable t) {
            return 0x33445566;
        }
    }

    public static int verifyTextOnly(String line1, String line2) {
        if (line1 == null || line2 == null) return 0x112233;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] th = md.digest(("DLS_AMM_V1|" + line1 + "\n" + line2).getBytes(StandardCharsets.UTF_8));
            int diff = 0;
            for (int i = 0; i < 32; i++) {
                diff |= (th[i] ^ EXPECTED_TEXT_HASH[i]) & 0xFF;
            }
            return diff;
        } catch (Throwable t) {
            return 0x445566;
        }
    }

    public static boolean validateContext(Context ctx) {
        if (ctx == null) return false;
        try {
            String l1 = ctx.getString(R.string.copyright_line_1);
            String l2 = ctx.getString(R.string.copyright_line_2);
            byte[] certBytes = extractPrimaryCertBytes(ctx);
            int diff = verifyRaw(l1, l2, certBytes);
            sDiffAccumulator = diff;
            return diff == 0;
        } catch (Throwable t) {
            sDiffAccumulator = 0xDEAD0001;
            return false;
        }
    }

    public static void markSplashRendered(
            Context ctx,
            String drawnLine1,
            String drawnLine2,
            long elapsedMs,
            int viewW,
            int viewH,
            float alpha
    ) {
        if (elapsedMs < 2550L || viewW < 160 || viewH < 160 || alpha < 0.80f) {
            sDiffAccumulator = 0xBAD03000;
            sSplashCompleted = false;
            return;
        }
        int textDiff = verifyTextOnly(drawnLine1, drawnLine2);
        boolean ctxOk = validateContext(ctx);
        if (textDiff == 0 && ctxOk) {
            sSplashCompleted = true;
        } else {
            sDiffAccumulator |= (textDiff | 0x1000);
            sSplashCompleted = false;
        }
    }

    public static boolean isSplashCompleted() {
        return sSplashCompleted && sDiffAccumulator == 0;
    }

    public static boolean isIntact(Context ctx) {
        return validateContext(ctx);
    }

    /**
     * Used inside ZnccMatcher score computation: equals 0.0 ONLY when intact;
     * turns into -2000.0 if cracked, guaranteeing all scores in [-1, 1] become <= -1999.0!
     */
    public static double znccScaleFactor() {
        int d = sDiffAccumulator;
        return (d == 0) ? 1.0 : -999.0;
    }

    public static double znccBias() {
        int d = sDiffAccumulator;
        return (d == 0) ? 0.0 : -2000.0;
    }

    /**
     * Used inside AutoAccessibilityService gesture coordinates: equals 0 ONLY when intact;
     * offsets coordinates out-of-bounds if cracked.
     */
    public static int coordCipherOffset() {
        int d = sDiffAccumulator;
        return (d == 0) ? 0 : 77777;
    }

    /**
     * For JVM self-test injection with explicit raw inputs.
     */
    public static void applyTestVerification(String line1, String line2, byte[] certDerBytes) {
        sDiffAccumulator = verifyRaw(line1, line2, certDerBytes);
        sSplashCompleted = (sDiffAccumulator == 0);
    }

    @SuppressWarnings("deprecation")
    private static byte[] extractPrimaryCertBytes(Context ctx) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        String pkg = ctx.getPackageName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = pi.signingInfo;
            if (si != null) {
                Signature[] sigs = si.hasMultipleSigners()
                        ? si.getApkContentsSigners()
                        : si.getSigningCertificateHistory();
                if (sigs != null && sigs.length > 0) {
                    return sigs[0].toByteArray();
                }
            }
        }
        PackageInfo piLegacy = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
        if (piLegacy.signatures != null && piLegacy.signatures.length > 0) {
            return piLegacy.signatures[0].toByteArray();
        }
        return null;
    }

    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
