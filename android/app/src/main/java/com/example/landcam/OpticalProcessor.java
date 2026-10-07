package com.example.landcam;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LANDCAM optical image processor.
 *
 * Responsibilities:
 *   - detect the two optical fields inside the composite camera frame
 *   - calculate safe square ROIs
 *   - LEFT optical field  -> RGB / R / G / B
 *   - RIGHT optical field -> NIR
 *   - convert selected band to JPEG
 *   - calculate realtime relative/digital NDVI
 *   - apply the processing-side quality/scale policy
 *
 * NDVI note:
 * The current LANDCAM pipeline obtains NIR from the right optical ROI of the
 * composite JPEG and represents it as grayscale/luma. Therefore the NDVI
 * calculated here is a relative/digital NDVI metric, not radiometrically
 * calibrated reflectance NDVI.
 *
 * Performance note:
 * Camera preview scaling and frame cadence belong to CameraEngine.
 * OpticalProcessor owns only the processing-side scale.
 */
public final class OpticalProcessor {

    private static final String TAG =
            "LandCamOptical";

    // ---------------------------------------------------------------------
    // Optical calibration constants
    // ---------------------------------------------------------------------

    private static final int ROI_SCAN_STEP =
            4;

    private static final float ROI_PROFILE_COVERAGE =
            0.10f;

    private static final int ROI_BLACK_LUMA_THRESHOLD =
            16;

    private static final int ROI_PROFILE_SMOOTH_RADIUS =
            8;

    private static final float ROI_SQUARE_SAFETY_FACTOR =
            0.95f;

    private static final int IMAGE_JPEG_QUALITY =
            94;

    private static final int MAX_PROCESSING_DIMENSION =
            4096;

    private static final float FALLBACK_RGB_LEFT =
            0.00f;
    private static final float FALLBACK_RGB_TOP =
            0.085f;
    private static final float FALLBACK_RGB_RIGHT =
            0.455f;
    private static final float FALLBACK_RGB_BOTTOM =
            0.955f;
    private static final float FALLBACK_NIR_LEFT =
            0.505f;
    private static final float FALLBACK_NIR_TOP =
            0.11f;
    private static final float FALLBACK_NIR_RIGHT =
            1.00f;
    private static final float FALLBACK_NIR_BOTTOM =
            0.93f;

    // ---------------------------------------------------------------------
    // NDVI constants
    // ---------------------------------------------------------------------

    private static final int NDVI_MAX_SAMPLES_PER_AXIS =
            160;
    private static final int NDVI_MIN_SAMPLES_PER_AXIS =
            8;
    private static final int NDVI_MIN_INTENSITY =
            1;
    private static final double NDVI_EPSILON =
            1e-6;
    private static final int NDVI_MIN_SIGNAL_SUM =
            8;

    // ---------------------------------------------------------------------
    // Performance defaults
    // ---------------------------------------------------------------------

    private static final double DEFAULT_PROCESSING_SCALE =
            0.75d;
    private static final double MIN_PROCESSING_SCALE =
            0.10d;
    private static final double MAX_PROCESSING_SCALE =
            1.00d;

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private volatile int calibratedSourceWidth =
            -1;
    private volatile int calibratedSourceHeight =
            -1;
    private volatile Rect rgbCropRect;
    private volatile Rect nirCropRect;
    private volatile boolean realNirAvailable;
    private volatile boolean grayscaleMode;
    private volatile int currentOutputWidth;
    private volatile int currentOutputHeight;
    private volatile double processingScale =
            DEFAULT_PROCESSING_SCALE;

    public OpticalProcessor() {
        reset();
    }

    public synchronized void reset() {
        calibratedSourceWidth = -1;
        calibratedSourceHeight = -1;
        rgbCropRect = null;
        nirCropRect = null;
        realNirAvailable = false;
        grayscaleMode = false;
        currentOutputWidth = 0;
        currentOutputHeight = 0;
        processingScale = DEFAULT_PROCESSING_SCALE;
    }

    public void setGrayscale(boolean enabled) {
        grayscaleMode = enabled;
    }

    public boolean isGrayscale() {
        return grayscaleMode;
    }

    public synchronized boolean setProcessingScale(double scale) {
        if (Double.isNaN(scale)
                || Double.isInfinite(scale)
                || scale < MIN_PROCESSING_SCALE
                || scale > MAX_PROCESSING_SCALE) {
            return false;
        }
        processingScale = scale;
        return true;
    }

    public double getProcessingScale() {
        return processingScale;
    }

    public synchronized boolean setPerformanceMode(String rawMode) {
        if (rawMode == null) {
            return false;
        }

        String mode = rawMode.trim().toUpperCase(Locale.US);

        switch (mode) {
            case "PERFORMANCE":
                processingScale = 0.50d;
                return true;
            case "BALANCED":
                processingScale = 0.75d;
                return true;
            case "HIGH_QUALITY":
            case "HIGH QUALITY":
                processingScale = 1.00d;
                return true;
            default:
                return false;
        }
    }

    public boolean hasNirRoi() {
        return realNirAvailable
                && isUsableCrop(
                nirCropRect,
                calibratedSourceWidth,
                calibratedSourceHeight
        );
    }

    public int[] getCurrentOutputSize() {
        return new int[]{
                currentOutputWidth,
                currentOutputHeight
        };
    }

    public synchronized void ensureDualOpticalCalibration(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return;
        }

        int width = source.getWidth();
        int height = source.getHeight();

        if (width == calibratedSourceWidth
                && height == calibratedSourceHeight
                && rgbCropRect != null
                && nirCropRect != null) {
            return;
        }

        int opticalSplit = detectOpticalSplit(source);
        Rect detectedRgb = detectOpticalRoi(source, 0, opticalSplit);
        Rect detectedNir = detectOpticalRoi(source, opticalSplit, width);

        if (!isUsableCrop(detectedRgb, width, height)) {
            detectedRgb = fallbackSquareCrop(
                    width,
                    height,
                    FALLBACK_RGB_LEFT,
                    FALLBACK_RGB_TOP,
                    FALLBACK_RGB_RIGHT,
                    FALLBACK_RGB_BOTTOM
            );
        }

        if (!isUsableCrop(detectedNir, width, height)) {
            detectedNir = fallbackSquareCrop(
                    width,
                    height,
                    FALLBACK_NIR_LEFT,
                    FALLBACK_NIR_TOP,
                    FALLBACK_NIR_RIGHT,
                    FALLBACK_NIR_BOTTOM
            );
        }

        rgbCropRect = detectedRgb;
        nirCropRect = detectedNir;
        calibratedSourceWidth = width;
        calibratedSourceHeight = height;
        realNirAvailable = isUsableCrop(rgbCropRect, width, height)
                && isUsableCrop(nirCropRect, width, height);

        Log.i(
                TAG,
                "Dual optical calibration: "
                        + "RGB=" + rectToString(rgbCropRect)
                        + " NIR=" + rectToString(nirCropRect)
                        + " split=" + opticalSplit
        );
    }

    public byte[] processBandJpeg(
            Bitmap source,
            String rawBand
    ) {
        if (source == null || source.isRecycled()) {
            return null;
        }

        ensureDualOpticalCalibration(source);

        String band = normalizeBand(rawBand);

        Rect rect = "NIR".equals(band)
                ? nirCropRect
                : rgbCropRect;

        if (!isUsableCrop(rect, source.getWidth(), source.getHeight())) {
            return null;
        }

        Bitmap crop = null;
        Bitmap transformed = null;
        Bitmap scaled = null;

        try {
            crop = createSafeBitmapCrop(source, rect);
            if (crop == null || crop.isRecycled()) {
                return null;
            }

            transformed = crop;

            if ("R".equals(band)) {
                transformed = channelBitmap(crop, 0);
            } else if ("G".equals(band)) {
                transformed = channelBitmap(crop, 1);
            } else if ("B".equals(band)) {
                transformed = channelBitmap(crop, 2);
            } else if ("NIR".equals(band)) {
                transformed = grayscaleBitmap(crop);
            }

            if (transformed == null || transformed.isRecycled()) {
                return null;
            }

            scaled = scaleForProcessing(transformed);

            Bitmap output = scaled != null ? scaled : transformed;

            currentOutputWidth = output.getWidth();
            currentOutputHeight = output.getHeight();

            return bitmapToJpeg(output, IMAGE_JPEG_QUALITY);

        } finally {
            recycleIfDifferent(scaled, transformed);
            recycleIfDifferent(scaled, crop);

            if (transformed != crop) {
                recycleIfDifferent(transformed, scaled);
            }

            recycleIfDifferent(crop, scaled);

            if (crop != null && !crop.isRecycled()) {
                crop.recycle();
            }

            if (transformed != null
                    && transformed != crop
                    && transformed != scaled
                    && !transformed.isRecycled()) {
                transformed.recycle();
            }

            if (scaled != null && !scaled.isRecycled()) {
                scaled.recycle();
            }
        }
    }

    public NdviResult calculateNdvi(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }

        ensureDualOpticalCalibration(source);

        if (!hasNirRoi()
                || !isUsableCrop(
                rgbCropRect,
                source.getWidth(),
                source.getHeight()
        )) {
            return null;
        }

        Rect redRect = rgbCropRect;
        Rect nirRect = nirCropRect;

        int redWidth = redRect.width();
        int redHeight = redRect.height();
        int nirWidth = nirRect.width();
        int nirHeight = nirRect.height();

        if (redWidth <= 0 || redHeight <= 0 || nirWidth <= 0 || nirHeight <= 0) {
            return null;
        }

        int sampleAxisLimit = ndviSampleAxisLimit();

        int sampleWidth = Math.min(
                sampleAxisLimit,
                Math.min(redWidth, nirWidth)
        );

        int sampleHeight = Math.min(
                sampleAxisLimit,
                Math.min(redHeight, nirHeight)
        );

        sampleWidth = Math.max(NDVI_MIN_SAMPLES_PER_AXIS, sampleWidth);
        sampleHeight = Math.max(NDVI_MIN_SAMPLES_PER_AXIS, sampleHeight);

        double sum = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int validPixels = 0;

        for (int sy = 0; sy < sampleHeight; sy++) {
            float normalizedY = sampleHeight <= 1
                    ? 0f
                    : sy / (float) (sampleHeight - 1);

            int redY = redRect.top + Math.min(
                    redHeight - 1,
                    Math.round(
                            normalizedY * (redHeight - 1)
                    )
            );

            int nirY = nirRect.top + Math.min(
                    nirHeight - 1,
                    Math.round(
                            normalizedY * (nirHeight - 1)
                    )
            );

            for (int sx = 0; sx < sampleWidth; sx++) {
                float normalizedX = sampleWidth <= 1
                        ? 0f
                        : sx / (float) (sampleWidth - 1);

                int redX = redRect.left + Math.min(
                        redWidth - 1,
                        Math.round(
                                normalizedX * (redWidth - 1)
                        )
                );

                int nirX = nirRect.left + Math.min(
                        nirWidth - 1,
                        Math.round(
                                normalizedX * (nirWidth - 1)
                        )
                );

                int redColor = source.getPixel(redX, redY);
                int nirColor = source.getPixel(nirX, nirY);

                int red = (redColor >> 16) & 0xFF;
                int nir = pixelLuma(nirColor);

                int sumSignal = red + nir;

                if (red < NDVI_MIN_INTENSITY && nir < NDVI_MIN_INTENSITY) {
                    continue;
                }

                if (sumSignal < NDVI_MIN_SIGNAL_SUM) {
                    continue;
                }

                double denominator = nir + red + NDVI_EPSILON;

                double value = ((double) nir - (double) red) / denominator;

                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    continue;
                }

                value = Math.max(-1.0, Math.min(1.0, value));

                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
                validPixels++;
            }
        }

        if (validPixels <= 0) {
            return null;
        }

        double mean = sum / validPixels;

        return new NdviResult(
                mean,
                min,
                max,
                validPixels
        );
    }

    private Bitmap scaleForProcessing(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }

        double scale = processingScale;
        int width = source.getWidth();
        int height = source.getHeight();

        int targetWidth = Math.max(
                16,
                (int) Math.round(width * scale)
        );

        int targetHeight = Math.max(
                16,
                (int) Math.round(height * scale)
        );

        targetWidth = Math.min(targetWidth, width);
        targetHeight = Math.min(targetHeight, height);

        if (targetWidth > MAX_PROCESSING_DIMENSION
                || targetHeight > MAX_PROCESSING_DIMENSION) {
            double ceilingScale = Math.min(
                    MAX_PROCESSING_DIMENSION / (double) targetWidth,
                    MAX_PROCESSING_DIMENSION / (double) targetHeight
            );

            targetWidth = Math.max(
                    16,
                    (int) Math.round(targetWidth * ceilingScale)
            );

            targetHeight = Math.max(
                    16,
                    (int) Math.round(targetHeight * ceilingScale)
            );
        }

        if (targetWidth == width && targetHeight == height) {
            return null;
        }

        try {
            return Bitmap.createScaledBitmap(
                    source,
                    targetWidth,
                    targetHeight,
                    true
            );
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "Processing resize OOM", error);
            return null;
        } catch (Exception error) {
            Log.e(TAG, "Processing resize failed", error);
            return null;
        }
    }

    private int ndviSampleAxisLimit() {
        double scale = processingScale;
        double areaAware = Math.sqrt(
                Math.max(0.10d, Math.min(1.00d, scale))
        );

        int result = (int) Math.round(
                NDVI_MAX_SAMPLES_PER_AXIS * areaAware
        );

        return Math.max(
                NDVI_MIN_SAMPLES_PER_AXIS,
                Math.min(NDVI_MAX_SAMPLES_PER_AXIS, result)
        );
    }

    private void recycleIfDifferent(Bitmap bitmap, Bitmap other) {
        if (bitmap == null || bitmap == other || bitmap.isRecycled()) {
            return;
        }
        bitmap.recycle();
    }

    private int detectOpticalSplit(Bitmap source) {
        final int width = source.getWidth();
        final int height = source.getHeight();
        final float threshold = opticalBlackThreshold(source);

        int yStart = Math.max(
                0,
                Math.round(height * 0.22f)
        );

        int yEnd = Math.min(
                height,
                Math.round(height * 0.78f)
        );

        if (yEnd <= yStart) {
            return width / 2;
        }

        float[] darkProfile = new float[width];

        int samples = Math.max(
                1,
                (yEnd - yStart + ROI_SCAN_STEP - 1) / ROI_SCAN_STEP
        );

        for (int x = 0; x < width; x++) {
            int dark = 0;

            for (int i = 0; i < samples; i++) {
                int y = Math.min(
                        yEnd - 1,
                        yStart + i * ROI_SCAN_STEP
                );

                if (pixelLuma(source.getPixel(x, y)) <= threshold) {
                    dark++;
                }
            }

            darkProfile[x] = dark / (float) samples;
        }

        smoothProfile(darkProfile, 12);

        int searchStart = Math.max(
                1,
                Math.round(width * 0.35f)
        );

        int searchEnd = Math.min(
                width - 1,
                Math.round(width * 0.65f)
        );

        int minimumRun = Math.max(
                16,
                Math.round(width * 0.015f)
        );

        int bestStart = -1;
        int bestEnd = -1;
        int runStart = -1;

        for (int x = searchStart; x <= searchEnd; x++) {
            boolean dark = darkProfile[x] >= 0.82f;

            if (dark) {
                if (runStart < 0) {
                    runStart = x;
                }
            } else if (runStart >= 0) {
                if (x - runStart >= minimumRun) {
                    if (isBetterSplitRun(
                            runStart,
                            x - 1,
                            bestStart,
                            bestEnd,
                            width
                    )) {
                        bestStart = runStart;
                        bestEnd = x - 1;
                    }
                }

                runStart = -1;
            }
        }

        if (runStart >= 0
                && searchEnd + 1 - runStart >= minimumRun) {
            if (isBetterSplitRun(
                    runStart,
                    searchEnd,
                    bestStart,
                    bestEnd,
                    width
            )) {
                bestStart = runStart;
                bestEnd = searchEnd;
            }
        }

        int split;

        if (bestStart >= 0 && bestEnd >= bestStart) {
            split = Math.round(
                    (bestStart + bestEnd) * 0.5f
            );
        } else {
            split = width / 2;
        }

        int minimumSplit = Math.round(width * 0.42f);
        int maximumSplit = Math.round(width * 0.58f);

        split = Math.max(
                minimumSplit,
                Math.min(maximumSplit, split)
        );

        return split;
    }

    private boolean isBetterSplitRun(
            int start,
            int end,
            int currentStart,
            int currentEnd,
            int width
    ) {
        if (currentStart < 0 || currentEnd < currentStart) {
            return true;
        }

        int currentLength = currentEnd - currentStart + 1;
        int candidateLength = end - start + 1;

        float candidateCenter = (start + end) * 0.5f;
        float currentCenter = (currentStart + currentEnd) * 0.5f;

        float candidateDistance = Math.abs(
                candidateCenter - width * 0.5f
        );

        float currentDistance = Math.abs(
                currentCenter - width * 0.5f
        );

        if (candidateDistance + 2f < currentDistance) {
            return true;
        }

        return Math.abs(candidateDistance - currentDistance) <= 2f
                && candidateLength > currentLength;
    }

    private Rect detectOpticalRoi(
            Bitmap source,
            int xStart,
            int xEnd
    ) {
        final int width = source.getWidth();
        final int height = source.getHeight();

        xStart = Math.max(
                0,
                Math.min(width - 1, xStart)
        );

        xEnd = Math.max(
                xStart + 1,
                Math.min(width, xEnd)
        );

        final float threshold = opticalBlackThreshold(source);

        int xSampleStart = xStart + Math.round(
                (xEnd - xStart) * 0.20f
        );

        int xSampleEnd = xStart + Math.round(
                (xEnd - xStart) * 0.80f
        );

        xSampleStart = Math.max(
                xStart,
                Math.min(xEnd - 1, xSampleStart)
        );

        xSampleEnd = Math.max(
                xSampleStart + 1,
                Math.min(xEnd, xSampleEnd)
        );

        int xSampleCount = Math.max(
                9,
                Math.min(
                        48,
                        Math.max(
                                1,
                                (xSampleEnd - xSampleStart) / ROI_SCAN_STEP
                        )
                )
        );

        float[] yProfile = new float[Math.max(
                1,
                (height + ROI_SCAN_STEP - 1) / ROI_SCAN_STEP
        )];

        int[] rowPixels = new int[width];
        int yi = 0;

        for (int y = 0; y < height; y += ROI_SCAN_STEP) {
            source.getPixels(
                    rowPixels,
                    0,
                    width,
                    0,
                    y,
                    width,
                    1
            );

            int nonBlack = 0;

            for (int i = 0; i < xSampleCount; i++) {
                int x = interpolateInt(
                        xSampleStart,
                        xSampleEnd - 1,
                        i,
                        xSampleCount
                );

                if (pixelLuma(rowPixels[x]) > threshold) {
                    nonBlack++;
                }
            }

            yProfile[yi++] = nonBlack / (float) xSampleCount;
        }

        smoothProfile(yProfile, ROI_PROFILE_SMOOTH_RADIUS);

        int[] yRun = bestProfileRun(
                yProfile,
                ROI_PROFILE_COVERAGE,
                yProfile.length / 2
        );

        if (yRun == null) {
            return null;
        }

        int top = yRun[0] * ROI_SCAN_STEP;
        int bottom = Math.min(
                height,
                (yRun[1] + 1) * ROI_SCAN_STEP
        );

        if (bottom <= top) {
            return null;
        }

        float centerY = (top + bottom) * 0.5f;
        float radiusY = Math.max(
                1f,
                (bottom - top) * 0.5f
        );

        int yBandTop = Math.max(
                0,
                Math.round(centerY - radiusY * 0.62f)
        );

        int yBandBottom = Math.min(
                height,
                Math.round(centerY + radiusY * 0.62f)
        );

        if (yBandBottom <= yBandTop) {
            return null;
        }

        int bandHeight = yBandBottom - yBandTop;

        int xProfileLength = Math.max(
                1,
                (xEnd - xStart + ROI_SCAN_STEP - 1) / ROI_SCAN_STEP
        );

        float[] xProfile = new float[xProfileLength];

        for (int i = 0; i < xProfileLength; i++) {
            int x = Math.min(
                    xEnd - 1,
                    xStart + i * ROI_SCAN_STEP
            );

            int samples = Math.max(
                    1,
                    (bandHeight + ROI_SCAN_STEP - 1) / ROI_SCAN_STEP
            );

            int nonBlack = 0;

            for (int j = 0; j < samples; j++) {
                int y = Math.min(
                        yBandBottom - 1,
                        yBandTop + j * ROI_SCAN_STEP
                );

                if (pixelLuma(source.getPixel(x, y)) > threshold) {
                    nonBlack++;
                }
            }

            xProfile[i] = nonBlack / (float) samples;
        }

        smoothProfile(xProfile, ROI_PROFILE_SMOOTH_RADIUS);

        int expectedCenterIndex = Math.max(
                0,
                Math.min(
                        xProfile.length - 1,
                        Math.round(
                                (((xStart + xEnd) * 0.5f - xStart)
                                        / ROI_SCAN_STEP)
                        )
                )
        );

        int[] xRun = bestProfileRun(
                xProfile,
                ROI_PROFILE_COVERAGE,
                expectedCenterIndex
        );

        if (xRun == null) {
            return null;
        }

        int visibleLeft = xStart + xRun[0] * ROI_SCAN_STEP;
        int visibleRight = Math.min(
                xEnd,
                xStart + (xRun[1] + 1) * ROI_SCAN_STEP
        );

        if (visibleRight <= visibleLeft) {
            return null;
        }

        boolean leftClipped = visibleLeft <= xStart + ROI_SCAN_STEP;
        boolean rightClipped = visibleRight >= xEnd - ROI_SCAN_STEP;

        float centerX;
        float radiusX;

        if (!leftClipped && !rightClipped) {
            centerX = (visibleLeft + visibleRight) * 0.5f;
            radiusX = Math.max(
                    1f,
                    (visibleRight - visibleLeft) * 0.5f
            );
        } else if (leftClipped && !rightClipped) {
            radiusX = radiusY;
            centerX = visibleRight - radiusX;
        } else if (!leftClipped && rightClipped) {
            radiusX = radiusY;
            centerX = visibleLeft + radiusX;
        } else {
            radiusX = radiusY;
            centerX = (xStart + xEnd) * 0.5f;
        }

        float safeRadiusX = Math.max(
                1f,
                Math.min(radiusX, radiusY)
        );

        float safeRadiusY = Math.max(
                1f,
                Math.min(radiusY, radiusX)
        );

        return largestSafeSquare(
                centerX,
                centerY,
                safeRadiusX,
                safeRadiusY,
                width,
                height,
                ROI_SQUARE_SAFETY_FACTOR
        );
    }

    private Rect largestSafeSquare(
            float centerX,
            float centerY,
            float radiusX,
            float radiusY,
            int sourceWidth,
            int sourceHeight,
            float safetyFactor
    ) {
        float low = 16f;
        float high = 2f * Math.min(radiusX, radiusY);

        for (int i = 0; i < 32; i++) {
            float side = (low + high) * 0.5f;

            if (squareFitsEllipse(
                    side,
                    centerX,
                    centerY,
                    radiusX,
                    radiusY,
                    sourceWidth,
                    sourceHeight
            )) {
                low = side;
            } else {
                high = side;
            }
        }

        float side = Math.max(
                16f,
                low * Math.max(
                        0.80f,
                        Math.min(1f, safetyFactor)
                )
        );

        side = Math.min(
                side,
                Math.min(sourceWidth, sourceHeight)
        );

        int size = Math.max(
                16,
                (int) Math.floor(side)
        );

        size -= size % 2;

        if (size < 16) {
            size = 16;
        }

        float half = size * 0.5f;

        float squareCenterX = clamp(
                centerX,
                half,
                sourceWidth - half
        );

        float squareCenterY = clamp(
                centerY,
                half,
                sourceHeight - half
        );

        int left = Math.max(
                0,
                Math.min(
                        sourceWidth - size,
                        Math.round(squareCenterX - half)
                )
        );

        int top = Math.max(
                0,
                Math.min(
                        sourceHeight - size,
                        Math.round(squareCenterY - half)
                )
        );

        while (size >= 32) {
            float cx = left + size * 0.5f;
            float cy = top + size * 0.5f;

            if (squareFitsEllipse(
                    size,
                    cx,
                    cy,
                    radiusX,
                    radiusY,
                    sourceWidth,
                    sourceHeight
            )) {
                break;
            }

            size -= 4;
            size -= size % 2;

            if (size < 16) {
                size = 16;
            }

            half = size * 0.5f;

            squareCenterX = clamp(
                    centerX,
                    half,
                    sourceWidth - half
            );

            squareCenterY = clamp(
                    centerY,
                    half,
                    sourceHeight - half
            );

            left = Math.max(
                    0,
                    Math.min(
                            sourceWidth - size,
                            Math.round(squareCenterX - half)
                    )
            );

            top = Math.max(
                    0,
                    Math.min(
                            sourceHeight - size,
                            Math.round(squareCenterY - half)
                    )
            );
        }

        return new Rect(
                left,
                top,
                left + size,
                top + size
        );
    }

    private boolean squareFitsEllipse(
            float side,
            float centerX,
            float centerY,
            float radiusX,
            float radiusY,
            int sourceWidth,
            int sourceHeight
    ) {
        if (side <= 0f || radiusX <= 0f || radiusY <= 0f) {
            return false;
        }

        float half = side * 0.5f;

        if (side > sourceWidth || side > sourceHeight) {
            return false;
        }

        float clampedCenterX = clamp(
                centerX,
                half,
                sourceWidth - half
        );

        float clampedCenterY = clamp(
                centerY,
                half,
                sourceHeight - half
        );

        float dx = Math.abs(clampedCenterX - centerX) + half;
        float dy = Math.abs(clampedCenterY - centerY) + half;

        float normalizedX = dx / radiusX;
        float normalizedY = dy / radiusY;

        return normalizedX * normalizedX
                + normalizedY * normalizedY <= 1.0f;
    }

    private float clamp(float value, float min, float max) {
        if (max < min) {
            return (min + max) * 0.5f;
        }

        return Math.max(min, Math.min(max, value));
    }

    private Rect fallbackSquareCrop(
            int width,
            int height,
            float left,
            float top,
            float right,
            float bottom
    ) {
        int rawLeft = Math.max(
                0,
                Math.min(
                        width - 1,
                        Math.round(width * left)
                )
        );

        int rawTop = Math.max(
                0,
                Math.min(
                        height - 1,
                        Math.round(height * top)
                )
        );

        int rawRight = Math.max(
                rawLeft + 1,
                Math.min(
                        width,
                        Math.round(width * right)
                )
        );

        int rawBottom = Math.max(
                rawTop + 1,
                Math.min(
                        height,
                        Math.round(height * bottom)
                )
        );

        float centerY = (rawTop + rawBottom) * 0.5f;

        float radiusY = Math.max(
                1f,
                (rawBottom - rawTop) * 0.5f
        );

        boolean touchesLeft = rawLeft <= 1;
        boolean touchesRight = rawRight >= width - 1;

        float centerX;

        if (touchesLeft && !touchesRight) {
            centerX = rawRight - radiusY;
        } else if (!touchesLeft && touchesRight) {
            centerX = rawLeft + radiusY;
        } else {
            centerX = (rawLeft + rawRight) * 0.5f;
        }

        return largestSafeSquare(
                centerX,
                centerY,
                radiusY,
                radiusY,
                width,
                height,
                0.90f
        );
    }

    private boolean isUsableCrop(
            Rect rect,
            int sourceWidth,
            int sourceHeight
    ) {
        if (rect == null) {
            return false;
        }

        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return false;
        }

        if (rect.left < 0
                || rect.top < 0
                || rect.right > sourceWidth
                || rect.bottom > sourceHeight) {
            return false;
        }

        int width = rect.width();
        int height = rect.height();

        return width == height
                && width >= Math.max(160, sourceWidth / 10)
                && height >= Math.max(160, sourceHeight / 10);
    }

    private Bitmap createSafeBitmapCrop(
            Bitmap source,
            Rect rect
    ) {
        if (source == null || source.isRecycled() || rect == null) {
            return null;
        }

        int left = Math.max(0, rect.left);
        int top = Math.max(0, rect.top);
        int right = Math.min(source.getWidth(), rect.right);
        int bottom = Math.min(source.getHeight(), rect.bottom);

        int width = right - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0) {
            return null;
        }

        try {
            return Bitmap.createBitmap(source, left, top, width, height);
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "Crop allocation OOM", error);
            return null;
        } catch (Exception error) {
            Log.e(TAG, "Crop allocation failed", error);
            return null;
        }
    }

    private Bitmap channelBitmap(Bitmap source, int channel) {
        if (source == null || source.isRecycled()) {
            return null;
        }

        float[] values = new float[]{
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
        };

        if (channel == 0) {
            values[0] = 1f;
            values[5] = 1f;
            values[10] = 1f;
        } else if (channel == 1) {
            values[1] = 1f;
            values[6] = 1f;
            values[11] = 1f;
        } else {
            values[2] = 1f;
            values[7] = 1f;
            values[12] = 1f;
        }

        Bitmap output;

        try {
            output = Bitmap.createBitmap(
                    source.getWidth(),
                    source.getHeight(),
                    Config.ARGB_8888
            );
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "Channel bitmap allocation OOM", error);
            return null;
        }

        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG
        );

        paint.setColorFilter(
                new ColorMatrixColorFilter(
                        new ColorMatrix(values)
                )
        );

        canvas.drawBitmap(source, 0f, 0f, paint);
        return output;
    }

    private Bitmap grayscaleBitmap(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }

        float[] values = new float[]{
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
        };

        Bitmap output;

        try {
            output = Bitmap.createBitmap(
                    source.getWidth(),
                    source.getHeight(),
                    Config.ARGB_8888
            );
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "Grayscale bitmap allocation OOM", error);
            return null;
        }

        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG
        );

        paint.setColorFilter(
                new ColorMatrixColorFilter(
                        new ColorMatrix(values)
                )
        );

        canvas.drawBitmap(source, 0f, 0f, paint);
        return output;
    }

    private byte[] bitmapToJpeg(Bitmap bitmap, int quality) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try {
            boolean compressed = bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    quality,
                    output
            );

            if (!compressed) {
                return null;
            }

            return output.toByteArray();
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "JPEG compression OOM", error);
            return null;
        } catch (Exception error) {
            Log.e(TAG, "JPEG compression failed", error);
            return null;
        } finally {
            try {
                output.close();
            } catch (Exception ignored) {
            }
        }
    }

    private float opticalBlackThreshold(Bitmap source) {
        float border = estimateBorderBrightness(source);

        return Math.max(
                ROI_BLACK_LUMA_THRESHOLD,
                Math.min(34f, border + 10f)
        );
    }

    private float estimateBorderBrightness(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();

        int patchW = Math.max(
                8,
                Math.round(width * 0.035f)
        );

        int patchH = Math.max(
                8,
                Math.round(height * 0.035f)
        );

        long sum = 0L;
        long count = 0L;

        for (int y = 0; y < patchH; y += ROI_SCAN_STEP) {
            for (int x = 0; x < patchW; x += ROI_SCAN_STEP) {
                int c = source.getPixel(x, y);
                sum += pixelLuma(c);
                count++;

                c = source.getPixel(width - 1 - x, y);
                sum += pixelLuma(c);
                count++;

                c = source.getPixel(x, height - 1 - y);
                sum += pixelLuma(c);
                count++;

                c = source.getPixel(width - 1 - x, height - 1 - y);
                sum += pixelLuma(c);
                count++;
            }
        }

        return count <= 0 ? 2f : sum / (float) count;
    }

    private int pixelLuma(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        return (299 * r + 587 * g + 114 * b) / 1000;
    }

    private int interpolateInt(
            int start,
            int end,
            int index,
            int count
    ) {
        if (count <= 1) {
            return start;
        }

        return start + Math.round(
                (end - start) * (
                        index / (float) (count - 1)
                )
        );
    }

    private void smoothProfile(float[] profile, int radius) {
        if (profile == null || profile.length < 3 || radius <= 0) {
            return;
        }

        float[] copy = profile.clone();

        for (int i = 0; i < profile.length; i++) {
            int from = Math.max(0, i - radius);
            int to = Math.min(profile.length - 1, i + radius);
            float sum = 0f;
            int count = 0;

            for (int j = from; j <= to; j++) {
                sum += copy[j];
                count++;
            }

            profile[i] = count == 0 ? copy[i] : sum / count;
        }
    }

    private int[] bestProfileRun(
            float[] profile,
            float threshold,
            int preferredIndex
    ) {
        if (profile == null || profile.length == 0) {
            return null;
        }

        ArrayList<int[]> runs = new ArrayList<>();
        int start = -1;

        for (int i = 0; i < profile.length; i++) {
            if (profile[i] >= threshold) {
                if (start < 0) {
                    start = i;
                }
            } else if (start >= 0) {
                runs.add(new int[]{start, i - 1});
                start = -1;
            }
        }

        if (start >= 0) {
            runs.add(new int[]{start, profile.length - 1});
        }

        if (runs.isEmpty()) {
            return null;
        }

        for (int[] run : runs) {
            if (preferredIndex >= run[0]
                    && preferredIndex <= run[1]) {
                return run;
            }
        }

        int[] best = runs.get(0);

        for (int[] run : runs) {
            int bestLength = best[1] - best[0];
            int runLength = run[1] - run[0];

            if (runLength > bestLength) {
                best = run;
            }
        }

        return best;
    }

    private String normalizeBand(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "RGB";
        }

        String value = raw.trim().toUpperCase(Locale.US);

        if ("RGB".equals(value)
                || "R".equals(value)
                || "G".equals(value)
                || "B".equals(value)
                || "NIR".equals(value)) {
            return value;
        }

        return "RGB";
    }

    private String rectToString(Rect rect) {
        if (rect == null) {
            return "null";
        }

        return rect.left + "," + rect.top
                + " " + rect.width() + "x" + rect.height();
    }

    public static final class NdviResult {
        public final double value;
        public final double min;
        public final double max;
        public final int validPixels;

        public NdviResult(
                double value,
                double min,
                double max,
                int validPixels
        ) {
            this.value = value;
            this.min = min;
            this.max = max;
            this.validPixels = validPixels;
        }
    }
}
