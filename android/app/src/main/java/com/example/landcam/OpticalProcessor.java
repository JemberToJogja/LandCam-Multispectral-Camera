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

    /** Minimum encoded signal below which channel values are noise dominated. */

    private static final int NDVI_MIN_CHANNEL_SIGNAL = 6;

    /** Near-clipped JPEG values are unreliable for index calculation. */

    private static final int NDVI_SATURATION_LEVEL = 250;

    private static final double NDVI_EPSILON = 1e-9;

    /** Upper bound for CPU false-colour preview work per frame. */

    private static final int NDVI_PREVIEW_MAX_PIXELS = 160_000;

    /**
     * Fixed neutral gain. This is a digital display/processing choice, not
     * radiometric calibration. Do not adapt it to the current scene: doing so
     * makes the same pixel produce a different index as objects enter a frame.
     */
    private static final float NDVI_FIXED_NIR_GAIN = 1.0f;

    private static final int NDVI_NODATA_COLOR = android.graphics.Color.BLACK;
    private static final int NDVI_COLOR_RED = android.graphics.Color.rgb(201, 20, 36);
    private static final int NDVI_COLOR_ORANGE = android.graphics.Color.rgb(255, 101, 0);
    private static final int NDVI_COLOR_YELLOW = android.graphics.Color.rgb(255, 213, 42);
    private static final int NDVI_COLOR_GREEN_START = android.graphics.Color.rgb(184, 230, 41);
    private static final int NDVI_COLOR_GREEN_END = android.graphics.Color.rgb(13, 168, 59);

    /** Lookup table for inverse sRGB transfer; avoids pow() per sampled pixel. */
    private static final double[] SRGB_TO_LINEAR_LUT = createSrgbToLinearLut();

    private static double[] createSrgbToLinearLut() {
        final double[] table = new double[256];
        for (int i = 0; i < table.length; i++) {
            final double encoded = i / 255.0d;
            table[i] = encoded <= 0.04045d
                    ? encoded / 12.92d
                    : Math.pow((encoded + 0.055d) / 1.055d, 2.4d);
        }
        return table;
    }

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

    /** True when ROI coordinates had to use the fixed geometry fallback. */

    private volatile boolean roiFallbackUsed;

    /** NIR-to-Red inverse affine registration; defaults are identity only. */

    private volatile RegistrationTransform nirRegistration = RegistrationTransform.identity();

    /** Cached because the NIR encoding of a camera stream is normally stable. */
    private volatile Boolean nirLikelyColorEncodedCache;

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
        roiFallbackUsed = false;
        nirRegistration = RegistrationTransform.identity();
        nirLikelyColorEncodedCache = null;
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
                processingScale = 0.25d;
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

    /**
     * Supplies a measured residual mapping from Red crop coordinates to NIR.
     *
     * Scale/translation values operate in normalized crop coordinates. A shift
     * of 0.01 means 1% of the corresponding crop dimension. Values are
     * intentionally not auto-estimated from the scene: scene content is not a
     * calibration target and must not change the geometry between frames.
     *
     * Default identity values are a fallback, NOT proof of optical alignment.
     */

    public synchronized boolean setNirRegistration(
            float scaleX,
            float scaleY,
            float rotationDegrees,
            float shiftXNormalized,
            float shiftYNormalized
    ) {
        if (!isFinite(scaleX) || !isFinite(scaleY)
                || !isFinite(rotationDegrees)
                || !isFinite(shiftXNormalized) || !isFinite(shiftYNormalized)) {
            return false;
        }
        if (scaleX < 0.50f || scaleX > 2.00f
                || scaleY < 0.50f || scaleY > 2.00f
                || Math.abs(rotationDegrees) > 45.0f
                || Math.abs(shiftXNormalized) > 0.25f
                || Math.abs(shiftYNormalized) > 0.25f) {
            return false;
        }
        final double radians = Math.toRadians(rotationDegrees);
        nirRegistration = new RegistrationTransform(
                scaleX,
                scaleY,
                (float) Math.cos(radians),
                (float) Math.sin(radians),
                shiftXNormalized,
                shiftYNormalized,
                true
        );
        return true;
    }

    /** Restores identity registration and explicitly marks it uncalibrated. */

    public synchronized void clearNirRegistration() {
        nirRegistration = RegistrationTransform.identity();
    }

    public boolean isNirRegistrationCalibrated() {
        return nirRegistration.calibrated;
    }

    public boolean isRoiFallbackUsed() {
        return roiFallbackUsed;
    }

    /**
     * A green-colour threshold is not a semantic vegetation classifier.
     * Reject enabling the mask unless a trained, validated model is bundled.
     */
    public synchronized boolean setVegetationMaskEnabled(boolean enabled) {
        if (enabled) {
            Log.w(TAG, "Vegetation mask unavailable: no trained semantic classifier is loaded.");
            return false;
        }
        return true;
    }

    public boolean isVegetationMaskEnabled() {
        return false;
    }

    public boolean isSemanticVegetationClassificationAvailable() {
        return false;
    }

    private boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
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

    /** Cache-aware calibration for steady live-view frames. */
    public synchronized void ensureDualOpticalCalibration(Bitmap source) {
        ensureDualOpticalCalibrationInternal(source, false);
    }

    /** Force ROI detection for still capture without changing source dimensions. */
    public synchronized void forceDualOpticalCalibration(Bitmap source) {
        ensureDualOpticalCalibrationInternal(source, true);
    }

    /** Invalidate coordinates but keep an explicitly supplied registration transform. */
    public synchronized void invalidateCalibration() {
        calibratedSourceWidth = -1;
        calibratedSourceHeight = -1;
        rgbCropRect = null;
        nirCropRect = null;
        realNirAvailable = false;
        roiFallbackUsed = false;
        nirLikelyColorEncodedCache = null;
    }

    public synchronized Rect getRgbCropRect() {
        return rgbCropRect == null ? null : new Rect(rgbCropRect);
    }

    public synchronized Rect getNirCropRect() {
        return nirCropRect == null ? null : new Rect(nirCropRect);
    }

    public synchronized int[] getCalibratedSourceSize() {
        return calibratedSourceWidth > 0 && calibratedSourceHeight > 0
                ? new int[]{calibratedSourceWidth, calibratedSourceHeight}
                : new int[]{0, 0};
    }

    private void ensureDualOpticalCalibrationInternal(Bitmap source, boolean force) {
        if (source == null || source.isRecycled()) {
            return;
        }
        final int width = source.getWidth();
        final int height = source.getHeight();
        if (!force
                && width == calibratedSourceWidth
                && height == calibratedSourceHeight
                && rgbCropRect != null
                && nirCropRect != null) {
            return;
        }

        nirLikelyColorEncodedCache = null;
        final int opticalSplit = detectOpticalSplit(source);
        Rect detectedRgb = detectOpticalRoi(source, 0, opticalSplit);
        Rect detectedNir = detectOpticalRoi(source, opticalSplit, width);
        boolean fallback = false;

        if (!isUsableCrop(detectedRgb, width, height)) {
            fallback = true;
            detectedRgb = fallbackSquareCrop(width, height,
                    FALLBACK_RGB_LEFT, FALLBACK_RGB_TOP,
                    FALLBACK_RGB_RIGHT, FALLBACK_RGB_BOTTOM);
        }
        if (!isUsableCrop(detectedNir, width, height)) {
            fallback = true;
            detectedNir = fallbackSquareCrop(width, height,
                    FALLBACK_NIR_LEFT, FALLBACK_NIR_TOP,
                    FALLBACK_NIR_RIGHT, FALLBACK_NIR_BOTTOM);
        }

        // Automatic detection and fallback must both pass the same bounds/size checks.
        if (!isUsableCrop(detectedRgb, width, height)) {
            detectedRgb = null;
        }
        if (!isUsableCrop(detectedNir, width, height)) {
            detectedNir = null;
        }

        rgbCropRect = detectedRgb;
        nirCropRect = detectedNir;
        calibratedSourceWidth = width;
        calibratedSourceHeight = height;
        realNirAvailable = detectedRgb != null && detectedNir != null;
        roiFallbackUsed = fallback;

        Log.i(TAG, "Dual optical calibration: RGB=" + rectToString(rgbCropRect)
                + " NIR=" + rectToString(nirCropRect)
                + " split=" + opticalSplit
                + " source=" + width + "x" + height
                + " forced=" + force
                + " fallback=" + roiFallbackUsed
                + " registration=" + (nirRegistration.calibrated
                    ? "CALIBRATED" : "IDENTITY_UNCALIBRATED")
                + " metric=RELATIVE_DIGITAL_NDVI");
    }

    public synchronized byte[] processBandJpeg(Bitmap source, String rawBand) {
        return processBandJpegInternal(source, rawBand);
    }

    /** Process a still with fresh ROI detection, then restore live-view calibration. */
    public synchronized byte[] processCapturedStillBandJpeg(Bitmap source, String rawBand) {
        CalibrationSnapshot previous = snapshotCalibrationState();
        try {
            ensureDualOpticalCalibrationInternal(source, true);
            return processBandJpegInternal(source, rawBand);
        } finally {
            restoreCalibrationState(previous);
        }
    }

    private byte[] processBandJpegInternal(Bitmap source, String rawBand) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        ensureDualOpticalCalibrationInternal(source, false);
        final String band = normalizeBand(rawBand);
        final Rect rect = "NIR".equals(band) ? nirCropRect : rgbCropRect;
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
                transformed = grayscaleNirBitmap(crop);
            }
            if (transformed == null || transformed.isRecycled()) {
                return null;
            }

            scaled = scaleForProcessing(transformed);
            final Bitmap output = scaled != null ? scaled : transformed;
            currentOutputWidth = output.getWidth();
            currentOutputHeight = output.getHeight();
            return bitmapToJpeg(output, IMAGE_JPEG_QUALITY);
        } finally {
            if (scaled != null && scaled != crop && scaled != transformed && !scaled.isRecycled()) {
                scaled.recycle();
            }
            if (transformed != null && transformed != crop && !transformed.isRecycled()) {
                transformed.recycle();
            }
            if (crop != null && !crop.isRecycled()) {
                crop.recycle();
            }
            if (scaled != null && !scaled.isRecycled()) {
                scaled.recycle();
            }
        }
    }

    /**
     * Calculates a relative digital NDVI summary over all valid overlapping
     * Red/NIR samples. Values are derived from JPEG-encoded pixels, so this is
     * not radiometrically calibrated reflectance NDVI.
     */

    public synchronized NdviResult calculateNdvi(Bitmap source) {
        return calculateNdviInternal(source, false);
    }

    /** Semantic vegetation-only NDVI is unavailable without a trained classifier. */
    public synchronized NdviResult calculateVegetationNdvi(Bitmap source) {
        Log.w(TAG, "Vegetation-only NDVI unavailable: no trained semantic classifier is loaded.");
        return null;
    }

    /** Calculate still NDVI with fresh ROIs, then restore the live-view cache. */
    public synchronized NdviResult calculateCapturedStillNdvi(Bitmap source) {
        CalibrationSnapshot previous = snapshotCalibrationState();
        try {
            return calculateNdviInternal(source, true);
        } finally {
            restoreCalibrationState(previous);
        }
    }

    private NdviResult calculateNdviInternal(Bitmap source, boolean forceCalibration) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        ensureDualOpticalCalibrationInternal(source, forceCalibration);
        final Rect redRect = rgbCropRect;
        final Rect nirRect = nirCropRect;
        if (!hasNirRoi()
                || !isUsableCrop(redRect, source.getWidth(), source.getHeight())) {
            return null;
        }

        final int redWidth = redRect.width();
        final int redHeight = redRect.height();
        final int nirWidth = nirRect.width();
        final int nirHeight = nirRect.height();
        final RegistrationTransform registration = nirRegistration;
        if (redWidth <= 0 || redHeight <= 0 || nirWidth <= 0 || nirHeight <= 0) {
            return null;
        }

        final boolean colorEncodedNir = isNirLikelyColorEncoded(source);
        final int sampleAxisLimit = ndviSampleAxisLimit();
        final int sampleWidth = Math.max(1, Math.min(sampleAxisLimit, redWidth));
        final int sampleHeight = Math.max(1, Math.min(sampleAxisLimit, redHeight));
        double sum = 0.0d;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int validPixels = 0;
        int sampledPixels = 0;

        for (int sy = 0; sy < sampleHeight; sy++) {
            final float v = sampleHeight <= 1 ? 0.5f : sy / (float) (sampleHeight - 1);
            final int redY = redRect.top + clampInt(Math.round(v * (redHeight - 1)), 0, redHeight - 1);
            for (int sx = 0; sx < sampleWidth; sx++) {
                sampledPixels++;
                final float u = sampleWidth <= 1 ? 0.5f : sx / (float) (sampleWidth - 1);
                final int redX = redRect.left + clampInt(Math.round(u * (redWidth - 1)), 0, redWidth - 1);
                final long mapped = mapRedUvToNirPixel(u, v, nirWidth, nirHeight, registration);
                if (mapped < 0L) {
                    continue;
                }
                final int nirX = nirRect.left + (int) (mapped >>> 32);
                final int nirY = nirRect.top + (int) mapped;
                final int red8 = android.graphics.Color.red(source.getPixel(redX, redY));
                final int nir8 = pixelNirIntensity(source.getPixel(nirX, nirY));
                if (!isValidSignal(red8, nir8)) {
                    continue;
                }
                final double red = srgbToLinear(red8);
                final double nir = srgbToLinear(nir8) * NDVI_FIXED_NIR_GAIN;
                final double denominator = red + nir;
                if (!(denominator > NDVI_EPSILON)) {
                    continue;
                }
                final double value = clampDouble((nir - red) / denominator, -1.0d, 1.0d);
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    continue;
                }
                sum += value;
                min = Math.min(min, value);
                max = Math.max(max, value);
                validPixels++;
            }
        }

        if (validPixels == 0) {
            return new NdviResult(Double.NaN, Double.NaN, Double.NaN, 0,
                    sampledPixels, 0.0d, registration.calibrated,
                    colorEncodedNir, false);
        }
        final double validFraction = sampledPixels <= 0 ? 0.0d : validPixels / (double) sampledPixels;
        return new NdviResult(sum / validPixels, min, max, validPixels,
                sampledPixels, validFraction, registration.calibrated,
                colorEncodedNir, false);
    }

    /** CPU false-colour NDVI preview; no semantic vegetation mask is applied. */
    public synchronized byte[] processNdviPreviewJpeg(Bitmap source) {
        return processNdviPreviewJpegInternal(source, false);
    }

    /** Render a still with fresh ROIs and restore the live-view calibration afterward. */
    public synchronized byte[] processCapturedStillNdviPreviewJpeg(Bitmap source) {
        CalibrationSnapshot previous = snapshotCalibrationState();
        try {
            return processNdviPreviewJpegInternal(source, true);
        } finally {
            restoreCalibrationState(previous);
        }
    }

    private byte[] processNdviPreviewJpegInternal(Bitmap source, boolean forceCalibration) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        ensureDualOpticalCalibrationInternal(source, forceCalibration);
        final Rect redRect = rgbCropRect;
        final Rect nirRect = nirCropRect;
        if (!hasNirRoi() || !isUsableCrop(redRect, source.getWidth(), source.getHeight())) {
            return null;
        }

        final int redWidth = redRect.width();
        final int redHeight = redRect.height();
        final int nirWidth = nirRect.width();
        final int nirHeight = nirRect.height();
        final RegistrationTransform registration = nirRegistration;
        if (redWidth < 2 || redHeight < 2 || nirWidth < 2 || nirHeight < 2) {
            return null;
        }

        double scale = Math.max(MIN_PROCESSING_SCALE, Math.min(MAX_PROCESSING_SCALE, processingScale));
        int outWidth = Math.max(16, (int) Math.round(redWidth * scale));
        int outHeight = Math.max(16, (int) Math.round(redHeight * scale));
        final double pixelBudgetScale = Math.sqrt(
                NDVI_PREVIEW_MAX_PIXELS / (double) Math.max(1L, (long) outWidth * outHeight));
        if (pixelBudgetScale < 1.0d) {
            outWidth = Math.max(16, (int) Math.floor(outWidth * pixelBudgetScale));
            outHeight = Math.max(16, (int) Math.floor(outHeight * pixelBudgetScale));
        }
        outWidth = Math.min(outWidth, MAX_PROCESSING_DIMENSION);
        outHeight = Math.min(outHeight, MAX_PROCESSING_DIMENSION);

        Bitmap output = null;
        try {
            output = Bitmap.createBitmap(outWidth, outHeight, Config.ARGB_8888);
            final int[] outputPixels = new int[outWidth * outHeight];
            final int[] redRow = new int[redWidth];
            final int nirCacheSize = 16;
            final int[][] nirRowCache = new int[nirCacheSize][nirWidth];
            final int[] nirCachedSourceRows = new int[nirCacheSize];
            java.util.Arrays.fill(nirCachedSourceRows, -1);
            int nextNirCacheSlot = 0;
            int validPixels = 0;
            int nodataPixels = 0;

            for (int y = 0; y < outHeight; y++) {
                final float v = (y + 0.5f) / (float) outHeight;
                final int redLocalY = clampInt((int) Math.floor(v * redHeight), 0, redHeight - 1);
                source.getPixels(redRow, 0, redWidth, redRect.left, redRect.top + redLocalY, redWidth, 1);

                for (int x = 0; x < outWidth; x++) {
                    final int outputIndex = y * outWidth + x;
                    final float u = (x + 0.5f) / (float) outWidth;
                    final int redLocalX = clampInt((int) Math.floor(u * redWidth), 0, redWidth - 1);
                    final long mapped = mapRedUvToNirPixel(u, v, nirWidth, nirHeight, registration);
                    if (mapped < 0L) {
                        outputPixels[outputIndex] = NDVI_NODATA_COLOR;
                        nodataPixels++;
                        continue;
                    }

                    final int nirLocalX = (int) (mapped >>> 32);
                    final int nirLocalY = (int) mapped;
                    int cacheSlot = -1;
                    for (int i = 0; i < nirCacheSize; i++) {
                        if (nirCachedSourceRows[i] == nirLocalY) {
                            cacheSlot = i;
                            break;
                        }
                    }
                    if (cacheSlot < 0) {
                        cacheSlot = nextNirCacheSlot;
                        nextNirCacheSlot = (nextNirCacheSlot + 1) % nirCacheSize;
                        source.getPixels(nirRowCache[cacheSlot], 0, nirWidth,
                                nirRect.left, nirRect.top + nirLocalY, nirWidth, 1);
                        nirCachedSourceRows[cacheSlot] = nirLocalY;
                    }

                    final int red8 = android.graphics.Color.red(redRow[redLocalX]);
                    final int nir8 = pixelNirIntensity(nirRowCache[cacheSlot][nirLocalX]);
                    if (!isValidSignal(red8, nir8)) {
                        outputPixels[outputIndex] = NDVI_NODATA_COLOR;
                        nodataPixels++;
                        continue;
                    }
                    final double red = srgbToLinear(red8);
                    final double nir = srgbToLinear(nir8) * NDVI_FIXED_NIR_GAIN;
                    final double denominator = red + nir;
                    if (!(denominator > NDVI_EPSILON)) {
                        outputPixels[outputIndex] = NDVI_NODATA_COLOR;
                        nodataPixels++;
                        continue;
                    }
                    final double value = clampDouble((nir - red) / denominator, -1.0d, 1.0d);
                    outputPixels[outputIndex] = ndviColor(value);
                    validPixels++;
                }
            }

            output.setPixels(outputPixels, 0, outWidth, 0, 0, outWidth, outHeight);
            currentOutputWidth = outWidth;
            currentOutputHeight = outHeight;
            if (validPixels == 0) {
                Log.w(TAG, "NDVI output has no valid pixels; source=" + source.getWidth() + "x"
                        + source.getHeight() + ", RGB ROI=" + rectToString(redRect)
                        + ", NIR ROI=" + rectToString(nirRect));
                return null;
            }
            final int totalPixels = outWidth * outHeight;
            if (validPixels < Math.max(64, totalPixels / 20)) {
                Log.w(TAG, "NDVI valid coverage is low: " + validPixels + "/" + totalPixels
                        + " (" + nodataPixels + " nodata), registration="
                        + (registration.calibrated ? "CALIBRATED" : "IDENTITY_UNCALIBRATED"));
            }
            return bitmapToJpeg(output, 90);
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "NDVI preview allocation OOM", error);
            return null;
        } catch (Exception error) {
            Log.e(TAG, "NDVI preview generation failed", error);
            return null;
        } finally {
            if (output != null && !output.isRecycled()) {
                output.recycle();
            }
        }
    }

    /**
     * The current composite sample shows the NIR field in magenta. Luma
     * underweights that encoding because its green component is deliberately
     * low. Use max(R,B) for pixels with a magenta signature and normal luma for
     * neutral/grayscale pixels. This extracts display intensity, not radiance.
     */
    private int pixelNirIntensity(int color) {
        final int r = android.graphics.Color.red(color);
        final int g = android.graphics.Color.green(color);
        final int b = android.graphics.Color.blue(color);
        if (r > g + 12 && b > g + 12) {
            return Math.max(r, b);
        }
        return pixelLuma(color);
    }

    /**
     * Estimate one preview-only channel balance, then hold it stable until reset
     * or source geometry changes. This is not radiometric calibration.
     */
    private Bitmap grayscaleNirBitmap(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        final int width = source.getWidth();
        final int height = source.getHeight();
        final long pixelCountLong = (long) width * height;
        if (width <= 0 || height <= 0 || pixelCountLong > Integer.MAX_VALUE) {
            return null;
        }
        final int[] output = new int[(int) pixelCountLong];
        final int[] row = new int[width];
        try {
            for (int y = 0; y < height; y++) {
                source.getPixels(row, 0, width, 0, y, width, 1);
                final int offset = y * width;
                for (int x = 0; x < width; x++) {
                    final int value = pixelNirIntensity(row[x]);
                    output[offset + x] = android.graphics.Color.rgb(value, value, value);
                }
            }
            return Bitmap.createBitmap(output, 0, width, width, height, Config.ARGB_8888);
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "NIR grayscale allocation OOM", error);
            return null;
        } catch (Exception error) {
            Log.e(TAG, "NIR grayscale conversion failed", error);
            return null;
        }
    }

    private boolean isValidSignal(int red8, int nir8) {
        return red8 >= NDVI_MIN_CHANNEL_SIGNAL
                && nir8 >= NDVI_MIN_CHANNEL_SIGNAL
                && red8 < NDVI_SATURATION_LEVEL
                && nir8 < NDVI_SATURATION_LEVEL;
    }

    /**
     * Inverse sRGB transfer approximates linear light for a JPEG preview. It
     * does not undo camera tone curves or convert JPEG values to reflectance.
     */

    private double srgbToLinear(int encoded8) {
        return SRGB_TO_LINEAR_LUT[clampInt(encoded8, 0, 255)];
    }

    /** Continuous false-colour palette: red (-1), yellow (0), green (+1). */
    private int ndviColor(double value) {
        final double v = clampDouble(value, -1.0d, 1.0d);
        if (v <= 0.0d) {
            return interpolateColor(NDVI_COLOR_RED, NDVI_COLOR_YELLOW, v + 1.0d);
        }
        return interpolateColor(NDVI_COLOR_YELLOW, NDVI_COLOR_GREEN_END, v);
    }

    private int interpolateColor(int from, int to, double fraction) {
        final double t = clampDouble(fraction, 0.0d, 1.0d);
        final int r = (int) Math.round(android.graphics.Color.red(from)
                + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * t);
        final int g = (int) Math.round(android.graphics.Color.green(from)
                + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * t);
        final int b = (int) Math.round(android.graphics.Color.blue(from)
                + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * t);
        return android.graphics.Color.rgb(r, g, b);
    }

    /** Returns true if more than 25% of sampled NIR-crop pixels are strongly chromatic. */

    public synchronized boolean isNirLikelyColorEncoded(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return false;
        }
        ensureDualOpticalCalibration(source);
        if (nirLikelyColorEncodedCache != null) {
            return nirLikelyColorEncodedCache;
        }
        final Rect rect = nirCropRect;
        if (!isUsableCrop(rect, source.getWidth(), source.getHeight())) {
            nirLikelyColorEncodedCache = false;
            return false;
        }
        int examined = 0;
        int chromatic = 0;
        final int stepX = Math.max(1, rect.width() / 24);
        final int stepY = Math.max(1, rect.height() / 24);
        for (int y = rect.top + stepY / 2; y < rect.bottom; y += stepY) {
            for (int x = rect.left + stepX / 2; x < rect.right; x += stepX) {
                int c = source.getPixel(x, y);
                int r = android.graphics.Color.red(c);
                int g = android.graphics.Color.green(c);
                int b = android.graphics.Color.blue(c);
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 24) {
                    chromatic++;
                }
                examined++;
            }
        }
        boolean result = examined > 0 && chromatic / (double) examined > 0.25d;
        nirLikelyColorEncodedCache = result;
        if (result) {
            Log.w(TAG, "NIR crop is chromatic; grayscale luma is only a digital approximation, not calibrated NIR intensity.");
        }
        return result;
    }

    /**
     * Maps a Red crop UV to the nearest valid NIR crop pixel. The packed long
     * avoids allocating a float array for every preview pixel. -1L means the
     * transform falls outside the physical overlap.
     */
    private long mapRedUvToNirPixel(
            float u,
            float v,
            int nirWidth,
            int nirHeight,
            RegistrationTransform transform
    ) {
        final float safeU = clampFloat(u, 0.0f, 1.0f);
        final float safeV = clampFloat(v, 0.0f, 1.0f);
        final float dx = safeU - 0.5f - transform.shiftX;
        final float dy = safeV - 0.5f - transform.shiftY;
        final float mappedU = 0.5f
                + (dx * transform.cos + dy * transform.sin) / transform.scaleX;
        final float mappedV = 0.5f
                + (-dx * transform.sin + dy * transform.cos) / transform.scaleY;
        if (mappedU < 0.0f || mappedU > 1.0f
                || mappedV < 0.0f || mappedV > 1.0f) {
            return -1L;
        }
        final int x = clampInt(Math.round(mappedU * (nirWidth - 1)), 0, nirWidth - 1);
        final int y = clampInt(Math.round(mappedV * (nirHeight - 1)), 0, nirHeight - 1);
        return (((long) x) << 32) | (y & 0xFFFFFFFFL);
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private int detectOpticalSplit(Bitmap source) {
        final int width = source.getWidth();
        final int height = source.getHeight();
        final float threshold = opticalBlackThreshold(source);
        final int yStart = Math.max(0, Math.round(height * 0.22f));
        final int yEnd = Math.min(height, Math.round(height * 0.78f));
        if (yEnd <= yStart) {
            return width / 2;
        }

        final float[] darkProfile = new float[width];
        final int[] darkCounts = new int[width];
        final int[] sampledRow = new int[width];
        final int samples = Math.max(1, (yEnd - yStart + ROI_SCAN_STEP - 1) / ROI_SCAN_STEP);
        for (int i = 0; i < samples; i++) {
            final int y = Math.min(yEnd - 1, yStart + i * ROI_SCAN_STEP);
            source.getPixels(sampledRow, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                if (pixelLuma(sampledRow[x]) <= threshold) {
                    darkCounts[x]++;
                }
            }
        }
        for (int x = 0; x < width; x++) {
            darkProfile[x] = darkCounts[x] / (float) samples;
        }
        smoothProfile(darkProfile, 12);

        final int searchStart = Math.max(1, Math.round(width * 0.35f));
        final int searchEnd = Math.min(width - 1, Math.round(width * 0.65f));
        final int minimumRun = Math.max(16, Math.round(width * 0.015f));
        int bestStart = -1;
        int bestEnd = -1;
        int runStart = -1;
        for (int x = searchStart; x <= searchEnd; x++) {
            final boolean dark = darkProfile[x] >= 0.82f;
            if (dark) {
                if (runStart < 0) runStart = x;
            } else if (runStart >= 0) {
                if (x - runStart >= minimumRun && isBetterSplitRun(
                        runStart, x - 1, bestStart, bestEnd, width)) {
                    bestStart = runStart;
                    bestEnd = x - 1;
                }
                runStart = -1;
            }
        }
        if (runStart >= 0 && searchEnd + 1 - runStart >= minimumRun
                && isBetterSplitRun(runStart, searchEnd, bestStart, bestEnd, width)) {
            bestStart = runStart;
            bestEnd = searchEnd;
        }

        final int split = bestStart >= 0 && bestEnd >= bestStart
                ? Math.round((bestStart + bestEnd) * 0.5f)
                : width / 2;
        return Math.max(Math.round(width * 0.42f),
                Math.min(Math.round(width * 0.58f), split));
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

    private CalibrationSnapshot snapshotCalibrationState() {
        return new CalibrationSnapshot(
                calibratedSourceWidth,
                calibratedSourceHeight,
                rgbCropRect == null ? null : new Rect(rgbCropRect),
                nirCropRect == null ? null : new Rect(nirCropRect),
                realNirAvailable,
                roiFallbackUsed,
                nirLikelyColorEncodedCache,
                currentOutputWidth,
                currentOutputHeight
        );
    }

    private void restoreCalibrationState(CalibrationSnapshot snapshot) {
        if (snapshot == null) {
            invalidateCalibration();
            return;
        }
        calibratedSourceWidth = snapshot.width;
        calibratedSourceHeight = snapshot.height;
        rgbCropRect = snapshot.rgb == null ? null : new Rect(snapshot.rgb);
        nirCropRect = snapshot.nir == null ? null : new Rect(snapshot.nir);
        realNirAvailable = snapshot.nirAvailable;
        roiFallbackUsed = snapshot.fallbackUsed;
        nirLikelyColorEncodedCache = snapshot.nirColorEncoded;
        currentOutputWidth = snapshot.outputWidth;
        currentOutputHeight = snapshot.outputHeight;
    }

    private static final class CalibrationSnapshot {
        final int width;
        final int height;
        final Rect rgb;
        final Rect nir;
        final boolean nirAvailable;
        final boolean fallbackUsed;
        final Boolean nirColorEncoded;
        final int outputWidth;
        final int outputHeight;

        CalibrationSnapshot(int width, int height, Rect rgb, Rect nir,
                            boolean nirAvailable, boolean fallbackUsed,
                            Boolean nirColorEncoded, int outputWidth, int outputHeight) {
            this.width = width;
            this.height = height;
            this.rgb = rgb;
            this.nir = nir;
            this.nirAvailable = nirAvailable;
            this.fallbackUsed = fallbackUsed;
            this.nirColorEncoded = nirColorEncoded;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
        }
    }

    private static final class RegistrationTransform {
        final float scaleX;
        final float scaleY;
        final float cos;
        final float sin;
        final float shiftX;
        final float shiftY;
        final boolean calibrated;

        RegistrationTransform(
                float scaleX,
                float scaleY,
                float cos,
                float sin,
                float shiftX,
                float shiftY,
                boolean calibrated
        ) {
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.cos = cos;
            this.sin = sin;
            this.shiftX = shiftX;
            this.shiftY = shiftY;
            this.calibrated = calibrated;
        }

        static RegistrationTransform identity() {
            return new RegistrationTransform(1.0f, 1.0f, 1.0f, 0.0f,
                    0.0f, 0.0f, false);
        }
    }

    public static final class NdviResult {
        public final double value;
        public final double min;
        public final double max;
        public final int validPixels;
        public final int sampledPixels;
        public final double validFraction;
        public final boolean registrationCalibrated;
        public final boolean nirInputLikelyColorEncoded;
        public final boolean vegetationFiltered;
        /** Backwards-compatible constructor for existing callers. */
        public NdviResult(double value, double min, double max, int validPixels) {
            this(value, min, max, validPixels, validPixels,
                    validPixels > 0 ? 1.0d : 0.0d, false, false, false);
        }
        public NdviResult(
                double value,
                double min,
                double max,
                int validPixels,
                int sampledPixels,
                double validFraction,
                boolean registrationCalibrated,
                boolean nirInputLikelyColorEncoded,
                boolean vegetationFiltered
        ) {
            this.value = value;
            this.min = min;
            this.max = max;
            this.validPixels = validPixels;
            this.sampledPixels = sampledPixels;
            this.validFraction = validFraction;
            this.registrationCalibrated = registrationCalibrated;
            this.nirInputLikelyColorEncoded = nirInputLikelyColorEncoded;
            this.vegetationFiltered = vegetationFiltered;
        }
        public boolean hasUsableMeasurement() {
            return validPixels > 0 && !Double.isNaN(value) && !Double.isInfinite(value);
        }

        /**
         * This processor consumes encoded composite JPEG pixels and does not
         * perform reflectance calibration. A finite result must not be reported
         * as scientifically calibrated NDVI.
         */
        public boolean isRadiometricallyCalibrated() {
            return false;
        }

        public boolean isSuitableForQuantitativeInterpretation() {
            return isRadiometricallyCalibrated()
                    && registrationCalibrated
                    && !nirInputLikelyColorEncoded
                    && hasUsableMeasurement();
        }
    }
}
