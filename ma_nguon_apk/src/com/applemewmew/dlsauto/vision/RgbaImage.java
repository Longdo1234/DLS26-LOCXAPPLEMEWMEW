package com.applemewmew.dlsauto.vision;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;

/**
 * Lightweight RGBA image container normalized to height = 720p while preserving
 * the exact Android phone aspect ratio (e.g. 1280x720 for 16:9, 1560x720 for 19.5:9, 1600x720 for 20:9).
 */
public final class RgbaImage {

    public final int width;
    public final int height;
    public final byte[] data; // [R, G, B, A, ...] length = width * height * 4

    public RgbaImage(int width, int height) {
        this.width = width;
        this.height = height;
        this.data = new byte[width * height * 4];
    }

    public RgbaImage(int width, int height, byte[] data) {
        this.width = width;
        this.height = height;
        this.data = data;
    }

    public static RgbaImage fromImagePlaneBuffer(
            ByteBuffer buffer,
            int srcWidth,
            int srcHeight,
            int pixelStride,
            int rowStride,
            int dstWidth,
            int dstHeight
    ) {
        RgbaImage out = new RgbaImage(dstWidth, dstHeight);
        byte[] dst = out.data;
        buffer.position(0);

        if (srcWidth == dstWidth && srcHeight == dstHeight && pixelStride == 4) {
            int rowBytes = dstWidth * 4;
            if (rowStride == rowBytes) {
                buffer.get(dst, 0, dst.length);
            } else {
                for (int y = 0; y < dstHeight; y++) {
                    buffer.position(y * rowStride);
                    buffer.get(dst, y * rowBytes, rowBytes);
                }
            }
            return out;
        }

        for (int dy = 0; dy < dstHeight; dy++) {
            int sy = Math.min(srcHeight - 1, (dy * srcHeight) / dstHeight);
            int rowBase = sy * rowStride;
            int dstRow = dy * dstWidth * 4;
            for (int dx = 0; dx < dstWidth; dx++) {
                int sx = Math.min(srcWidth - 1, (dx * srcWidth) / dstWidth);
                int srcPos = rowBase + sx * pixelStride;
                int dp = dstRow + dx * 4;
                dst[dp] = buffer.get(srcPos);
                dst[dp + 1] = buffer.get(srcPos + 1);
                dst[dp + 2] = buffer.get(srcPos + 2);
                dst[dp + 3] = buffer.get(srcPos + 3);
            }
        }
        return out;
    }

    public static RgbaImage fromBitmap(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] argb = new int[w * h];
        bmp.getPixels(argb, 0, w, 0, 0, w, h);
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            int p = i * 4;
            rgba[p] = (byte) ((c >> 16) & 0xFF);     // R
            rgba[p + 1] = (byte) ((c >> 8) & 0xFF);  // G
            rgba[p + 2] = (byte) (c & 0xFF);         // B
            rgba[p + 3] = (byte) ((c >>> 24) & 0xFF);// A
        }
        return new RgbaImage(w, h, rgba);
    }

    public Bitmap toBitmap() {
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++) {
            int p = i * 4;
            int r = data[p] & 0xFF;
            int g = data[p + 1] & 0xFF;
            int b = data[p + 2] & 0xFF;
            int a = data[p + 3] & 0xFF;
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
    }

    public RgbaImage crop(int x, int y, int w, int h) {
        int cx = Math.max(0, Math.min(width - 1, x));
        int cy = Math.max(0, Math.min(height - 1, y));
        int cw = Math.max(1, Math.min(width - cx, w));
        int ch = Math.max(1, Math.min(height - cy, h));
        RgbaImage sub = new RgbaImage(cw, ch);
        for (int row = 0; row < ch; row++) {
            int srcOff = ((cy + row) * width + cx) * 4;
            int dstOff = (row * cw) * 4;
            System.arraycopy(this.data, srcOff, sub.data, dstOff, cw * 4);
        }
        return sub;
    }

    /**
     * Detects whether a frame is completely black/empty (e.g. PC OpenGL emulator bypassing VirtualDisplay
     * or user selecting single-app capture instead of Entire Screen).
     */
    public boolean isBlankOrBlack() {
        int step = Math.max(4, (width * height) / 180) * 4;
        int nonBlack = 0;
        for (int i = 0; i + 2 < data.length; i += step) {
            int r = data[i] & 0xFF;
            int g = data[i + 1] & 0xFF;
            int b = data[i + 2] & 0xFF;
            if (r > 10 || g > 10 || b > 10) {
                nonBlack++;
                if (nonBlack >= 4) return false;
            }
        }
        return true;
    }
}
