package com.example.landcam;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Stateless image-processing module for LANDCAM's dual-optical camera.
 *
 * <p>This class processes an already-decoded composite camera frame. It does not
 * discover cameras, open network connections, request NIR camera API methods,
 * manage live-view sessions, persist settings, send Flutter events, or save files.
 * Those responsibilities remain in CameraEngine / CaptureManager.</p>
 *
 * <p>RGB is the master output coordinate plane. NIR is sampled from its own
 * optical crop and mapped into the RGB crop using the configured affine transform.
 * NIR intensity is intentionally extracted from the composite image's Blue
 * channel only, matching the existing LANDCAM implementation.</p>
 *
 * <p>The returned NDVI is a relative/reference-normalized digital estimate from
 * 8-bit JPEG values. It is not laboratory-grade radiometric reflectance NDVI.</p>
 */
public final class SpectralProcessor {

    private static final String TAG = "LandCamSpectral";

    // Keep these thresholds aligned with the established CameraEngine pipeline.
    private static final int NDVI_MIN_VALID_SIGNAL = 6;
    private static final int NDVI_MAX_ANALYSIS_PIXELS = Integer.MAX_VALUE;
    private static final float NDVI_FIXED_NIR_GAIN = 1.0f;
    private static final float NDVI_VEG_GREEN_RED_RATIO = 0.62f;
    private static final float NDVI_VEG_GREEN_BLUE_RATIO = 1.10f;
    private static final float NDVI_VEG_GREEN_EXCESS_MIN = 0.015f;
    
    private static final float NDVI_TARGET_ROI_MIN_X = 0.02f;
    private static final float NDVI_TARGET_ROI_MAX_X = 0.98f;
    private static final float NDVI_TARGET_ROI_MIN_Y = 0.02f;
    private static final float NDVI_TARGET_ROI_MAX_Y = 0.98f;
    private static final int NDVI_FOCUS_MASK_MAX_PIXELS = 250000;
    private static final int NDVI_FOCUS_MIN_COMPONENT_PIXELS = 5;
    private static final float NDVI_TARGET_MIN_INDEX_THRESHOLD = -0.10f;
    private static final float NDVI_TARGET_COLOR_FALLBACK_THRESHOLD = -0.10f;
    private static final float NDVI_YELLOW_MAX_RED_GREEN_DIFF = 0.24f;
    private static final float NDVI_YELLOW_MIN_GREEN_SHARE = 0.22f;
    private static final float NDVI_MIN_LINEAR_DENOMINATOR = 0.0035f;
    private static final float NDVI_MIN_RGB_TOTAL = 0.06f;
    private static final int NDVI_PALETTE_RED = 0xFFC91424;
    private static final int NDVI_PALETTE_YELLOW = 0xFFFFD52A;
    private static final int NDVI_PALETTE_GREEN = 0xFF0DA83B;
    private static final int NDVI_DISPLAY_TARGET_LONG_EDGE = 1024;
    private static final int NDVI_DISPLAY_MAX_SCALE = 4;
    private static final int NDVI_SMOOTH_MAX_PIXELS = 1500000;
    private static final int NDVI_PALETTE_LUT_SIZE = 2048;
    private static final int NDVI_PREVIEW_JPEG_QUALITY = 95;
    private static final int NDVI_CAPTURE_JPEG_QUALITY = 96;
    private static final int NIR_JPEG_QUALITY = 94;

    private static final float[] SRGB_TO_LINEAR_LUT = createSrgbToLinearLut();
    private static final int[] NDVI_PALETTE_LUT = buildNdviPaletteLut();

    /**
     * Per-call processing settings. CameraEngine should create a snapshot of its
     * current native settings and pass it to each processing call.
     */
    public static final class Config {
        /** Fixed digital gain. Keep 1.0 unless calibrated using a reference target. */
        public float nirGain = NDVI_FIXED_NIR_GAIN;

        /** NIR-to-RGB affine registration parameters. */
        public float registrationScaleX = 1.0f;
        public float registrationScaleY = 1.0f;
        public float registrationRotationDegrees = 0.0f;
        public float registrationShiftXPixels = 0.0f;
        public float registrationShiftYPixels = 0.0f;

        /** Two-point per-band reference normalization. Disabled by default. */
        public boolean referenceCalibrationEnabled = false;
        public int redDarkSample8Bit = 8;
        public int redWhiteSample8Bit = 220;
        public float redDarkReferenceReflectance = 0.03f;
        public float redWhiteReferenceReflectance = 0.99f;
        public int nirDarkSample8Bit = 8;
        public int nirWhiteSample8Bit = 220;
        public float nirDarkReferenceReflectance = 0.03f;
        public float nirWhiteReferenceReflectance = 0.99f;

        /** Used for NIR preview output; NDVI retains its scalar-field display scale. */
        public double outputScale = 1.0d;
        public int nirJpegQuality = NIR_JPEG_QUALITY;

        public Config() { }

        public Config(Config other) {
            if (other == null) return;
            nirGain = other.nirGain;
            registrationScaleX = other.registrationScaleX;
            registrationScaleY = other.registrationScaleY;
            registrationRotationDegrees = other.registrationRotationDegrees;
            registrationShiftXPixels = other.registrationShiftXPixels;
            registrationShiftYPixels = other.registrationShiftYPixels;
            referenceCalibrationEnabled = other.referenceCalibrationEnabled;
            redDarkSample8Bit = other.redDarkSample8Bit;
            redWhiteSample8Bit = other.redWhiteSample8Bit;
            redDarkReferenceReflectance = other.redDarkReferenceReflectance;
            redWhiteReferenceReflectance = other.redWhiteReferenceReflectance;
            nirDarkSample8Bit = other.nirDarkSample8Bit;
            nirWhiteSample8Bit = other.nirWhiteSample8Bit;
            nirDarkReferenceReflectance = other.nirDarkReferenceReflectance;
            nirWhiteReferenceReflectance = other.nirWhiteReferenceReflectance;
            outputScale = other.outputScale;
            nirJpegQuality = other.nirJpegQuality;
        }

        public Config copy() {
            return new Config(this);
        }

        private boolean isUsable() {
            final boolean baseValid = Float.isFinite(nirGain)
                    && nirGain >= 0.0f
                    && Float.isFinite(registrationScaleX)
                    && Float.isFinite(registrationScaleY)
                    && Math.abs(registrationScaleX) >= 0.001f
                    && Math.abs(registrationScaleY) >= 0.001f
                    && Float.isFinite(registrationRotationDegrees)
                    && Float.isFinite(registrationShiftXPixels)
                    && Float.isFinite(registrationShiftYPixels)
                    && Double.isFinite(outputScale);
            if (!baseValid) return false;
            return !referenceCalibrationEnabled || isValidReferenceCalibration(this);
        }
    }

    /** Immutable output of one spectral processing operation. */
    public static final class Result {
        public final byte[] jpeg;
        /** NaN for NIR-only processing or when no target-leaf NDVI statistic exists. */
        public final double ndvi;
        /** Number of valid paired samples inside the selected target component. */
        public final int validPixels;
        /** Number of valid paired NDVI samples across the full analysis crop. */
        public final int totalValidPixels;
        public final int width;
        public final int height;

        private Result(
                byte[] jpeg,
                double ndvi,
                int validPixels,
                int totalValidPixels,
                int width,
                int height
        ) {
            this.jpeg = jpeg;
            this.ndvi = ndvi;
            this.validPixels = validPixels;
            this.totalValidPixels = totalValidPixels;
            this.width = width;
            this.height = height;
        }

        public boolean hasImage() {
            return jpeg != null && jpeg.length > 0;
        }

        public boolean hasNdviStatistic() {
            return Double.isFinite(ndvi) && validPixels > 0;
        }
    }

    public SpectralProcessor() { }

    /**
     * Build the false-colour NDVI JPEG and its selected-leaf statistics.
     *
     * @param source decoded composite RGB/NIR frame
     * @param rgbCrop final square RGB master crop in source coordinates
     * @param nirCrop NIR optical crop in source coordinates
     * @param config current NIR gain, registration and reference calibration
     * @param preview true for preview JPEG quality; false for capture JPEG quality
     * @return processing result, or null when the input crops/signals are unusable
     */
    public Result processNdvi(
            Bitmap source,
            Rect rgbCrop,
            Rect nirCrop,
            Config config,
            boolean preview
    ) {
        final Config snapshot = config == null ? new Config() : config.copy();
        if (!snapshot.isUsable()) {
            Log.w(TAG, "NDVI rejected: invalid spectral configuration");
            return null;
        }
        try {
            return buildNdviJpeg(source, rgbCrop, nirCrop, snapshot, preview);
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "NDVI processing ran out of memory", error);
            return null;
        } catch (RuntimeException error) {
            Log.e(TAG, "NDVI processing failed", error);
            return null;
        }
    }

    /**
     * Build a unified grayscale NIR JPEG using the NIR Blue channel only.
     * Output coordinates match the supplied RGB master crop.
     */
    public Result processNir(
            Bitmap source,
            Rect rgbCrop,
            Rect nirCrop,
            Config config
    ) {
        final Config snapshot = config == null ? new Config() : config.copy();
        if (!snapshot.isUsable()
                || source == null || source.isRecycled()
                || !isUsableCrop(rgbCrop, source.getWidth(), source.getHeight())
                || !isUsableCrop(nirCrop, source.getWidth(), source.getHeight())) {
            return null;
        }

        final int outputWidth = rgbCrop.width();
        final int outputHeight = rgbCrop.height();
        final int nirWidth = nirCrop.width();
        final int nirHeight = nirCrop.height();
        final long nirCount = (long) nirWidth * (long) nirHeight;
        if (nirCount <= 0L || nirCount > Integer.MAX_VALUE) {
            Log.w(TAG, "NIR rejected: crop is too large");
            return null;
        }

        int[] nirPixels;
        Bitmap output = null;
        Bitmap scaledOutput = null;
        try {
            nirPixels = new int[(int) nirCount];
            source.getPixels(
                    nirPixels, 0, nirWidth,
                    nirCrop.left, nirCrop.top, nirWidth, nirHeight
            );

            final int[] outputRow = new int[outputWidth];
            output = Bitmap.createBitmap(
                    outputWidth, outputHeight, Bitmap.Config.ARGB_8888
            );
            int mappedPixels = 0;
            for (int y = 0; y < outputHeight; y++) {
                for (int x = 0; x < outputWidth; x++) {
                    final long mapped = mapUnifiedPixelToNir(
                            x, y, outputWidth, outputHeight,
                            nirWidth, nirHeight, snapshot
                    );
                    if (mapped < 0L) {
                        outputRow[x] = Color.BLACK;
                        continue;
                    }
                    final int sourceX = (int) (mapped >> 32);
                    final int sourceY = (int) mapped;
                    final int value = pixelNirIntensity(nirPixels[sourceY * nirWidth + sourceX]);
                    outputRow[x] = Color.rgb(value, value, value);
                    mappedPixels++;
                }
                output.setPixels(outputRow, 0, outputWidth, 0, y, outputWidth, 1);
            }

            scaledOutput = scaleBitmap(output, snapshot.outputScale);
            final byte[] jpeg = bitmapToJpeg(scaledOutput, snapshot.nirJpegQuality);
            if (jpeg == null || jpeg.length == 0) return null;
            return new Result(
                    jpeg, Double.NaN, 0, mappedPixels,
                    scaledOutput.getWidth(), scaledOutput.getHeight()
            );
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "NIR processing ran out of memory", error);
            return null;
        } catch (RuntimeException error) {
            Log.e(TAG, "NIR processing failed", error);
            return null;
        } finally {
            if (scaledOutput != null && scaledOutput != output && !scaledOutput.isRecycled()) {
                scaledOutput.recycle();
            }
            if (output != null && !output.isRecycled()) {
                output.recycle();
            }
        }
    }

    /**
     * Exposes the same per-pixel digital NDVI calculation for focused unit tests.
     * Values use the Config calibration settings and the existing sRGB conversion.
     */
    public float calculateNdviSample(
            int redEncoded,
            int nirEncoded,
            Config config
    ) {
        final Config snapshot = config == null ? new Config() : config.copy();
        if (!snapshot.isUsable()) return Float.NaN;
        return calculateRelativeNdvi(redEncoded, nirEncoded, snapshot);
    }

    private Result buildNdviJpeg(
            Bitmap source,
            Rect rgbRect,
            Rect nirRect,
            Config config,
            boolean preview
    ) {
        if (source == null || source.isRecycled()
                || !isUsableCrop(rgbRect, source.getWidth(), source.getHeight())
                || !isUsableCrop(nirRect, source.getWidth(), source.getHeight())) {
            return null;
        }

        final int width = rgbRect.width();
        final int height = rgbRect.height();
        if (width <= 1 || height <= 1) return null;

        final long pixelCountLong = (long) width * (long) height;
        final int step = pixelCountLong > NDVI_MAX_ANALYSIS_PIXELS
                ? Math.max(2, (int) Math.ceil(Math.sqrt(
                        (double) pixelCountLong / (double) NDVI_MAX_ANALYSIS_PIXELS)))
                : 1;
        final int sampledWidth = (width + step - 1) / step;
        final int sampledHeight = (height + step - 1) / step;
        final int nirWidth = nirRect.width();
        final int nirHeight = nirRect.height();
        final int focusStep = Math.max(step, (int) Math.ceil(Math.sqrt(
                (double) pixelCountLong / (double) NDVI_FOCUS_MASK_MAX_PIXELS)));
        final int focusWidth = (width + focusStep - 1) / focusStep;
        final int focusHeight = (height + focusStep - 1) / focusStep;
        final long analysisCount = (long) sampledWidth * (long) sampledHeight;
        final long nirCount = (long) nirWidth * (long) nirHeight;
        if (analysisCount <= 0L || analysisCount > Integer.MAX_VALUE
                || nirCount <= 0L || nirCount > Integer.MAX_VALUE) {
            Log.w(TAG, "NDVI rejected: analysis buffers exceed Java array limits");
            return null;
        }

        final int[] rgbRow = new int[width];
        final int[] nirPixels = new int[(int) nirCount];
        final int analysisPixelCount = (int) analysisCount;
        final float[] ndviField = new float[analysisPixelCount];
        Arrays.fill(ndviField, Float.NaN);
        final byte[] foliageField = new byte[analysisPixelCount];
        final long[] redSumsByCell = new long[sampledWidth];
        final long[] visibleGreenSumsByCell = new long[sampledWidth];
        final long[] visibleBlueSumsByCell = new long[sampledWidth];
        final long[] nirBlueSumsByCell = new long[sampledWidth];
        final int[] pairedSamplesByCell = new int[sampledWidth];

        Bitmap denoisedOutput = null;
        Bitmap fullSizeOutput = null;
        try {
            source.getPixels(
                    nirPixels, 0, nirWidth,
                    nirRect.left, nirRect.top, nirWidth, nirHeight
            );

            final byte[] targetLeafMask = buildSingleTargetLeafMask(
                    source, rgbRect, width, height,
                    nirWidth, nirHeight, nirPixels,
                    focusStep, focusWidth, focusHeight,
                    config
            );

            double ndviSum = 0.0d;
            int valid = 0;
            int targetLeafValid = 0;

            for (int sy = 0; sy < sampledHeight; sy++) {
                final int yStart = sy * step;
                final int yEnd = Math.min(height, yStart + step);
                Arrays.fill(redSumsByCell, 0L);
                Arrays.fill(visibleGreenSumsByCell, 0L);
                Arrays.fill(visibleBlueSumsByCell, 0L);
                Arrays.fill(nirBlueSumsByCell, 0L);
                Arrays.fill(pairedSamplesByCell, 0);

                for (int blockY = yStart; blockY < yEnd; blockY++) {
                    source.getPixels(
                            rgbRow, 0, width,
                            rgbRect.left, rgbRect.top + blockY, width, 1
                    );
                    for (int blockX = 0; blockX < width; blockX++) {
                        final int cellX = blockX / step;
                        final long mappedUv = mapUnifiedPixelToNirUv(
                                blockX, blockY, width, height,
                                nirWidth, nirHeight, config
                        );
                        if (mappedUv < 0L) continue;

                        final int nirEncoded = sampleNirBlueBilinear(
                                nirPixels, nirWidth, nirHeight, mappedUv
                        );
                        if (nirEncoded < 0) continue;

                        final int visiblePixel = rgbRow[blockX];
                        redSumsByCell[cellX] += Color.red(visiblePixel);
                        visibleGreenSumsByCell[cellX] += Color.green(visiblePixel);
                        visibleBlueSumsByCell[cellX] += Color.blue(visiblePixel);
                        nirBlueSumsByCell[cellX] += nirEncoded;
                        pairedSamplesByCell[cellX]++;
                    }
                }

                for (int sx = 0; sx < sampledWidth; sx++) {
                    final int pairedSamples = pairedSamplesByCell[sx];
                    if (pairedSamples == 0) continue;

                    final int redEncoded = (int) ((redSumsByCell[sx] + pairedSamples / 2L)
                            / pairedSamples);
                    final int visibleGreenEncoded = (int) ((visibleGreenSumsByCell[sx]
                            + pairedSamples / 2L) / pairedSamples);
                    final int visibleBlueEncoded = (int) ((visibleBlueSumsByCell[sx]
                            + pairedSamples / 2L) / pairedSamples);
                    final int nirEncoded = (int) ((nirBlueSumsByCell[sx] + pairedSamples / 2L)
                            / pairedSamples);
                    final boolean visibleFoliage = isVisibleFoliageColorCandidate(
                            Color.rgb(redEncoded, visibleGreenEncoded, visibleBlueEncoded)
                    );
                    final float ndvi = calculateRelativeNdvi(redEncoded, nirEncoded, config);

                    final int xStart = sx * step;
                    final int xEnd = Math.min(width, xStart + step);
                    final int sampleCenterX = xStart + (xEnd - xStart) / 2;
                    final int sampleCenterY = yStart + (yEnd - yStart) / 2;
                    final int maskX = clampInt(sampleCenterX / focusStep, 0, focusWidth - 1);
                    final int maskY = clampInt(sampleCenterY / focusStep, 0, focusHeight - 1);
                    final boolean isSelectedTargetLeaf =
                            targetLeafMask[maskY * focusWidth + maskX] != 0;

                    final int pixelIndex = sy * sampledWidth + sx;
                    if (Float.isFinite(ndvi)) ndviField[pixelIndex] = ndvi;
                    foliageField[pixelIndex] = (byte) (visibleFoliage ? 1 : 0);

                    if (Float.isFinite(ndvi)) {
                        valid++;
                        if (isSelectedTargetLeaf) {
                            ndviSum += ndvi;
                            targetLeafValid++;
                        }
                    }
                }
            }

            if (valid < 64) {
                Log.w(TAG, "NDVI output rejected: too few valid registered samples (" + valid + ")");
                return null;
            }

            final double meanNdvi = targetLeafValid > 0
                    ? ndviSum / (double) targetLeafValid
                    : Double.NaN;

            final int displayScale = step == 1
                    ? chooseNdviDisplayScale(sampledWidth, sampledHeight)
                    : 1;
            denoisedOutput = renderSmoothNdvi(
                    ndviField, foliageField,
                    sampledWidth, sampledHeight, displayScale
            );
            if (denoisedOutput == null || denoisedOutput.isRecycled()) return null;

            Bitmap bitmapToEncode = denoisedOutput;
            if (sampledWidth != width || sampledHeight != height) {
                fullSizeOutput = Bitmap.createScaledBitmap(
                        denoisedOutput, width, height, true
                );
                bitmapToEncode = fullSizeOutput;
            }

            if (!preview || step > 1) {
                Log.i(TAG, "NDVI " + (preview ? "preview" : "capture")
                        + " processed: source=" + source.getWidth() + "x" + source.getHeight()
                        + " rgbCrop=" + rectToString(rgbRect)
                        + " nirCrop=" + rectToString(nirRect)
                        + " analysis=" + sampledWidth + "x" + sampledHeight
                        + " output=" + bitmapToEncode.getWidth() + "x" + bitmapToEncode.getHeight()
                        + " step=" + step + " NIR=BLUE_BILINEAR");
            }

            final byte[] jpeg = bitmapToJpeg(
                    bitmapToEncode,
                    preview ? NDVI_PREVIEW_JPEG_QUALITY : NDVI_CAPTURE_JPEG_QUALITY
            );
            if (jpeg == null || jpeg.length == 0) return null;

            return new Result(
                    jpeg, meanNdvi, targetLeafValid, valid,
                    bitmapToEncode.getWidth(), bitmapToEncode.getHeight()
            );
        } catch (OutOfMemoryError error) {
            Log.e(TAG, "NDVI processing ran out of memory", error);
            return null;
        } catch (RuntimeException error) {
            Log.e(TAG, "NDVI processing failed", error);
            return null;
        } finally {
            if (fullSizeOutput != null
                    && fullSizeOutput != denoisedOutput
                    && !fullSizeOutput.isRecycled()) {
                fullSizeOutput.recycle();
            }
            if (denoisedOutput != null && !denoisedOutput.isRecycled()) {
                denoisedOutput.recycle();
            }
        }
    }

    private static byte[] buildSingleTargetLeafMask(
            Bitmap source,
            Rect rgbRect,
            int width,
            int height,
            int nirWidth,
            int nirHeight,
            int[] nirPixels,
            int focusStep,
            int focusWidth,
            int focusHeight,
            Config config
    ) {
        final int count = focusWidth * focusHeight;
        final byte[] candidates = new byte[count];
        final int[] rgbRow = new int[width];

        for (int fy = 0; fy < focusHeight; fy++) {
            final int y = Math.min(height - 1, fy * focusStep + focusStep / 2);
            source.getPixels(rgbRow, 0, width, rgbRect.left, rgbRect.top + y, width, 1);
            final float normalizedY = ((float) y + 0.5f) / (float) height;

            for (int fx = 0; fx < focusWidth; fx++) {
                final int x = Math.min(width - 1, fx * focusStep + focusStep / 2);
                final float normalizedX = ((float) x + 0.5f) / (float) width;
                if (!isInsideNdviTargetRoi(normalizedX, normalizedY)) continue;

                final int rgbColor = rgbRow[x];
                if (!isVisibleFoliageColorCandidate(rgbColor)) continue;

                final long mappedUv = mapUnifiedPixelToNirUv(
                        x, y, width, height, nirWidth, nirHeight, config
                );
                if (mappedUv < 0L) continue;

                final int redEncoded = Color.red(rgbColor);
                final int nirEncoded = sampleNirBlueBilinear(
                        nirPixels, nirWidth, nirHeight, mappedUv
                );
                if (nirEncoded < 0) continue;

                final float ndvi = calculateRelativeNdvi(redEncoded, nirEncoded, config);
                final boolean signalClipped =
                        (redEncoded >= 250 && nirEncoded >= NDVI_MIN_VALID_SIGNAL)
                                || (nirEncoded >= 250 && redEncoded >= NDVI_MIN_VALID_SIGNAL);
                if (signalClipped || (Float.isFinite(ndvi)
                        && ndvi >= NDVI_TARGET_COLOR_FALLBACK_THRESHOLD)) {
                    candidates[fy * focusWidth + fx] = 1;
                }
            }
        }

        closeSmallMaskGaps(candidates, focusWidth, focusHeight);
        closeSmallMaskGaps(candidates, focusWidth, focusHeight);
        return selectSingleConnectedComponentNearCenter(candidates, focusWidth, focusHeight);
    }

    private static boolean isValidReferenceCalibration(Config config) {
        return isValidCalibrationBand(
                config.redDarkSample8Bit,
                config.redWhiteSample8Bit,
                config.redDarkReferenceReflectance,
                config.redWhiteReferenceReflectance
        ) && isValidCalibrationBand(
                config.nirDarkSample8Bit,
                config.nirWhiteSample8Bit,
                config.nirDarkReferenceReflectance,
                config.nirWhiteReferenceReflectance
        );
    }

    private static boolean isValidCalibrationBand(
            int darkSample8Bit,
            int whiteSample8Bit,
            float darkReferenceReflectance,
            float whiteReferenceReflectance
    ) {
        if (darkSample8Bit < 0 || darkSample8Bit >= 250
                || whiteSample8Bit < 0 || whiteSample8Bit >= 250
                || whiteSample8Bit <= darkSample8Bit
                || !Float.isFinite(darkReferenceReflectance)
                || !Float.isFinite(whiteReferenceReflectance)
                || darkReferenceReflectance < 0.0f
                || whiteReferenceReflectance > 1.0f
                || whiteReferenceReflectance <= darkReferenceReflectance) {
            return false;
        }
        final float darkLinear = decodeSrgb(darkSample8Bit);
        final float whiteLinear = decodeSrgb(whiteSample8Bit);
        return Float.isFinite(darkLinear) && Float.isFinite(whiteLinear)
                && (whiteLinear - darkLinear) >= 0.02f;
    }

    /** NIR is intentionally read from the composite pixel's Blue channel only. */
    private static int pixelNirIntensity(int color) {
        return Color.blue(color);
    }

    private static long mapUnifiedPixelToNir(
            int outputX,
            int outputY,
            int outputWidth,
            int outputHeight,
            int nirWidth,
            int nirHeight,
            Config config
    ) {
        final long mappedUv = mapUnifiedPixelToNirUv(
                outputX, outputY, outputWidth, outputHeight,
                nirWidth, nirHeight, config
        );
        if (mappedUv < 0L) return -1L;
        final float nirU = Float.intBitsToFloat((int) (mappedUv >> 32));
        final float nirV = Float.intBitsToFloat((int) mappedUv);
        final int mappedX = clampInt((int) Math.floor(nirU * nirWidth), 0, nirWidth - 1);
        final int mappedY = clampInt((int) Math.floor(nirV * nirHeight), 0, nirHeight - 1);
        return (((long) mappedX) << 32) | (mappedY & 0xFFFFFFFFL);
    }

    private static long mapUnifiedPixelToNirUv(
            int outputX,
            int outputY,
            int outputWidth,
            int outputHeight,
            int nirWidth,
            int nirHeight,
            Config config
    ) {
        if (outputWidth <= 0 || outputHeight <= 0 || nirWidth <= 0 || nirHeight <= 0) {
            return -1L;
        }

        final float rotation = (float) Math.toRadians(config.registrationRotationDegrees);
        final float cosine = (float) Math.cos(rotation);
        final float sine = (float) Math.sin(rotation);
        final float u = ((float) outputX + 0.5f) / (float) outputWidth;
        final float v = ((float) outputY + 0.5f) / (float) outputHeight;
        final float shiftedX = u - 0.5f
                - config.registrationShiftXPixels / (float) outputWidth;
        final float shiftedY = v - 0.5f
                - config.registrationShiftYPixels / (float) outputHeight;
        final float safeScaleX = Math.max(0.001f, Math.abs(config.registrationScaleX));
        final float safeScaleY = Math.max(0.001f, Math.abs(config.registrationScaleY));

        final float rotatedX = shiftedX * cosine + shiftedY * sine;
        final float rotatedY = -shiftedX * sine + shiftedY * cosine;
        final float nirU = rotatedX / safeScaleX + 0.5f;
        final float nirV = rotatedY / safeScaleY + 0.5f;

        if (!Float.isFinite(nirU) || !Float.isFinite(nirV)
                || nirU < 0.0f || nirV < 0.0f || nirU > 1.0f || nirV > 1.0f) {
            return -1L;
        }
        return (((long) Float.floatToIntBits(nirU)) << 32)
                | (Float.floatToIntBits(nirV) & 0xFFFFFFFFL);
    }

    private static float calculateRelativeNdvi(
            int redEncoded,
            int nirEncoded,
            Config config
    ) {
        if (redEncoded < NDVI_MIN_VALID_SIGNAL || nirEncoded < NDVI_MIN_VALID_SIGNAL
                || redEncoded >= 250 || nirEncoded >= 250) {
            return Float.NaN;
        }

        final float red;
        final float nir;
        if (config.referenceCalibrationEnabled) {
            red = normalizeReferenceReflectance(
                    redEncoded,
                    config.redDarkSample8Bit,
                    config.redWhiteSample8Bit,
                    config.redDarkReferenceReflectance,
                    config.redWhiteReferenceReflectance
            );
            nir = normalizeReferenceReflectance(
                    nirEncoded,
                    config.nirDarkSample8Bit,
                    config.nirWhiteSample8Bit,
                    config.nirDarkReferenceReflectance,
                    config.nirWhiteReferenceReflectance
            );
        } else {
            red = decodeSrgb(redEncoded);
            nir = decodeSrgb(nirEncoded) * config.nirGain;
        }

        if (!Float.isFinite(red) || !Float.isFinite(nir)) return Float.NaN;
        final float denominator = red + nir;
        if (!Float.isFinite(denominator) || denominator < NDVI_MIN_LINEAR_DENOMINATOR) {
            return Float.NaN;
        }
        final float value = (nir - red) / denominator;
        return Float.isFinite(value) ? clampFloat(value, -1.0f, 1.0f) : Float.NaN;
    }

    private static boolean isUsableCrop(Rect rect, int sourceWidth, int sourceHeight) {
        return rect != null
                && rect.width() >= 16
                && rect.height() >= 16
                && rect.left >= 0
                && rect.top >= 0
                && rect.right <= sourceWidth
                && rect.bottom <= sourceHeight;
    }

    private static String rectToString(Rect rect) {
        if (rect == null) return "null";
        return "[" + rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom + "]";
    }

    private static Bitmap scaleBitmap(Bitmap source, double scale) {
        if (source == null || source.isRecycled()) return source;
        final double finiteScale = Double.isFinite(scale) ? scale : 1.0d;
        final double safeScale = Math.max(0.25d, Math.min(1.0d, finiteScale));
        if (safeScale >= 0.999d) return source;
        final int width = Math.max(1, (int) Math.round(source.getWidth() * safeScale));
        final int height = Math.max(1, (int) Math.round(source.getHeight() * safeScale));
        if (width == source.getWidth() && height == source.getHeight()) return source;
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    private static byte[] bitmapToJpeg(Bitmap bitmap, int quality) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final int safeQuality = clampInt(quality, 1, 100);
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, safeQuality, output)) return null;
        return output.toByteArray();
    }

    private static void closeSmallMaskGaps(byte[] mask, int width, int height) {
        if (mask == null || width < 3 || height < 3) return;
        final byte[] dilated = new byte[mask.length];
        final byte[] closed = new byte[mask.length];
        // 3x3 dilation joins one-pixel breaks in a leaf's candidate mask.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean on = false;
                for (int oy = -1; oy <= 1 && !on; oy++) {
                    final int yy = y + oy;
                    if (yy < 0 || yy >= height) continue;
                    for (int ox = -1; ox <= 1; ox++) {
                        final int xx = x + ox;
                        if (xx >= 0 && xx < width && mask[yy * width + xx] != 0) {
                            on = true;
                            break;
                        }
                    }
                }
                if (on) dilated[y * width + x] = 1;
            }
        }
        // 3x3 erosion after dilation fills small internal gaps while retaining
        // the approximate outer contour of the leaf.
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                boolean all = true;
                for (int oy = -1; oy <= 1 && all; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (dilated[(y + oy) * width + x + ox] == 0) {
                            all = false;
                            break;
                        }
                    }
                }
                if (all) closed[y * width + x] = 1;
            }
        }
        System.arraycopy(closed, 0, mask, 0, mask.length);
    }

    private static byte[] selectSingleConnectedComponentNearCenter(
            byte[] candidates,
            int width,
            int height
    ) {
        final int count = width * height;
        final int[] labels = new int[count];
        final int[] queue = new int[count];
        final byte[] selected = new byte[count];
        final int minArea = Math.max(
                NDVI_FOCUS_MIN_COMPONENT_PIXELS,
                count / 20000
        );

        int nextLabel = 0;
        int bestLabel = 0;
        int bestArea = 0;
        double bestScore = Double.POSITIVE_INFINITY;
        // Do not impose a fixed maximum area: a close leaf may occupy most of
        // the frame. Geometry and foliage-candidate checks below still penalize
        // solid box-like regions, but cannot replace semantic segmentation.

        for (int start = 0; start < count; start++) {
            if (candidates[start] == 0 || labels[start] != 0) {
                continue;
            }

            nextLabel++;
            int head = 0;
            int tail = 0;
            int area = 0;
            int minX = width, minY = height, maxX = -1, maxY = -1;
            long sumX = 0L, sumY = 0L;
            double nearestDistance = Double.POSITIVE_INFINITY;
            queue[tail++] = start;
            labels[start] = nextLabel;

            while (head < tail) {
                final int index = queue[head++];
                final int x = index % width;
                final int y = index / width;
                area++;
                sumX += x;
                sumY += y;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);

                final double nx = ((double) x + 0.5d) / (double) width;
                final double ny = ((double) y + 0.5d) / (double) height;
                final double dx = nx - 0.5d;
                final double dy = ny - 0.5d;
                nearestDistance = Math.min(nearestDistance, dx * dx + dy * dy);

                // 8-connectivity keeps small gaps caused by JPEG noise from splitting one leaf.
                for (int oy = -1; oy <= 1; oy++) {
                    final int yy = y + oy;
                    if (yy < 0 || yy >= height) continue;
                    for (int ox = -1; ox <= 1; ox++) {
                        if (ox == 0 && oy == 0) continue;
                        final int xx = x + ox;
                        if (xx < 0 || xx >= width) continue;
                        final int neighbor = yy * width + xx;
                        if (candidates[neighbor] != 0 && labels[neighbor] == 0) {
                            labels[neighbor] = nextLabel;
                            queue[tail++] = neighbor;
                        }
                    }
                }
            }

            if (area < minArea) {
                continue;
            }

            // Prefer the largest coherent foliage candidate so the leaf can be
            // placed below/aside the center. Center distance is only a weak tie-breaker.
            final double centroidX = (sumX / (double) area + 0.5d) / (double) width;
            final double centroidY = (sumY / (double) area + 0.5d) / (double) height;
            final double dx = centroidX - 0.5d;
            final double dy = centroidY - 0.5d;
            final double centroidDistance = dx * dx + dy * dy;
            final double boxArea = Math.max(1.0d,
                    (double) (maxX - minX + 1) * (double) (maxY - minY + 1));
            final double fillRatio = area / boxArea;
            // Near-rectangular blocks are unlikely to be a single leaf. This is
            // a soft penalty because leaf shapes and occlusion vary considerably.
            final double boxLikePenalty = Math.max(0.0d, fillRatio - 0.58d) * 0.90d;
            final double largeFramePenalty = area > count * 0.42d
                    ? ((area / (double) count) - 0.42d) * 1.25d
                    : 0.0d;
            final double score = (0.08d * centroidDistance)
                    + (0.10d / Math.sqrt(Math.max(1, area)))
                    + Math.max(0.0d, 0.10d - fillRatio) * 0.04d
                    + boxLikePenalty
                    + largeFramePenalty;
            if (score < bestScore - 1.0e-9d
                    || (Math.abs(score - bestScore) <= 1.0e-9d && area > bestArea)) {
                bestScore = score;
                bestArea = area;
                bestLabel = nextLabel;
            }
        }

        if (bestLabel == 0) {
            return selected;
        }
        for (int i = 0; i < count; i++) {
            if (labels[i] == bestLabel) {
                selected[i] = 1;
            }
        }
        return selected;
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float[] createSrgbToLinearLut() {
        final float[] lut = new float[256];
        for (int i = 0; i < lut.length; i++) {
            final float c = i / 255.0f;
            lut[i] = c <= 0.04045f
                    ? c / 12.92f
                    : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
        }
        return lut;
    }

    private static float decodeSrgb(int encodedByte) {
        return SRGB_TO_LINEAR_LUT[clampInt(encodedByte, 0, 255)];
    }

    private static float normalizeReferenceReflectance(
            int sampleEncoded,
            int darkSampleEncoded,
            int whiteSampleEncoded,
            float darkReferenceReflectance,
            float whiteReferenceReflectance
    ) {
        final float sampleLinear = decodeSrgb(sampleEncoded);
        final float darkLinear = decodeSrgb(darkSampleEncoded);
        final float whiteLinear = decodeSrgb(whiteSampleEncoded);
        final float signalSpan = whiteLinear - darkLinear;
        if (!Float.isFinite(sampleLinear) || !Float.isFinite(signalSpan) || signalSpan < 0.02f) {
            return Float.NaN;
        }
        final float fraction = clampFloat((sampleLinear - darkLinear) / signalSpan, 0.0f, 1.0f);
        final float reflectance = darkReferenceReflectance
                + fraction * (whiteReferenceReflectance - darkReferenceReflectance);
        return Float.isFinite(reflectance) ? clampFloat(reflectance, 0.0f, 1.0f) : Float.NaN;
    }

    private static boolean isInsideNdviTargetRoi(float normalizedX, float normalizedY) {
        return normalizedX >= NDVI_TARGET_ROI_MIN_X
                && normalizedX <= NDVI_TARGET_ROI_MAX_X
                && normalizedY >= NDVI_TARGET_ROI_MIN_Y
                && normalizedY <= NDVI_TARGET_ROI_MAX_Y;
    }

    private static boolean isVisibleFoliageColorCandidate(int rgbColor) {
        final float red = Color.red(rgbColor) / 255.0f;
        final float green = Color.green(rgbColor) / 255.0f;
        final float blue = Color.blue(rgbColor) / 255.0f;
        final float total = red + green + blue;
        if (total < NDVI_MIN_RGB_TOTAL) {
            return false;
        }

        final float nr = red / total;
        final float ng = green / total;
        final float nb = blue / total;
        final float excessGreen = 2.0f * ng - nr - nb;

        final boolean greenFoliage =
                green > red * NDVI_VEG_GREEN_RED_RATIO
                        && green > blue * NDVI_VEG_GREEN_BLUE_RATIO
                        && excessGreen >= NDVI_VEG_GREEN_EXCESS_MIN;

        // Keep the warm-foliage gate deliberately narrow. A broad orange/brown
        // test also accepts sunlit floors, skin and clothing, which caused large
        // background blobs to be painted green in earlier previews. Yellow leaves
        // are supported, but red-dominant orange regions are not enough by themselves.
        final float maxShare = Math.max(nr, Math.max(ng, nb));
        final float minShare = Math.min(nr, Math.min(ng, nb));
        final boolean chromatic = (maxShare - minShare) >= 0.085f;
        final boolean yellowOrWarmFoliage = chromatic
                && nr >= 0.28f
                && ng >= 0.27f
                && (nr - ng) <= 0.16f
                && ng >= nr * 0.62f
                && nr > nb * 1.20f
                && ng > nb * 1.15f;

        return greenFoliage || yellowOrWarmFoliage;
    }

    private static int chooseNdviDisplayScale(int width, int height) {
        final int longest = Math.max(width, height);
        if (longest <= 0 || longest >= NDVI_DISPLAY_TARGET_LONG_EDGE) {
            return 1;
        }
        return clampInt(
                (int) Math.ceil(NDVI_DISPLAY_TARGET_LONG_EDGE / (double) longest),
                1, NDVI_DISPLAY_MAX_SCALE);
    }

    private static Bitmap renderSmoothNdvi(
            float[] ndvi,
            byte[] foliage,
            int width,
            int height,
            int scale
    ) {
        final int count = width * height;
        double sum = 0.0d;
        int validCount = 0;
        for (int i = 0; i < count; i++) {
            if (Float.isFinite(ndvi[i])) {
                sum += ndvi[i];
                validCount++;
            }
        }
        final float globalMean = validCount > 0 ? (float) (sum / validCount) : 0.0f;

        float[] field = new float[count];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int i = y * width + x;
                final float v = ndvi[i];
                if (Float.isFinite(v)) {
                    field[i] = v;
                    continue;
                }
                float acc = 0.0f;
                int n = 0;
                for (int pass = 0; pass < 2 && n == 0; pass++) {
                    final int r = pass == 0 ? 2 : 6;
                    for (int oy = -r; oy <= r; oy++) {
                        final int yy = y + oy;
                        if (yy < 0 || yy >= height) continue;
                        for (int ox = -r; ox <= r; ox++) {
                            final int xx = x + ox;
                            if (xx < 0 || xx >= width) continue;
                            final float nv = ndvi[yy * width + xx];
                            if (Float.isFinite(nv)) {
                                acc += nv;
                                n++;
                            }
                        }
                    }
                }
                field[i] = n > 0 ? acc / n : globalMean;
            }
        }

        if (count <= NDVI_SMOOTH_MAX_PIXELS) {
            field = median3x3(field, width, height);
            boxBlur(field, width, height, 1, 2);
        }

        final float[] weight = new float[count];
        for (int i = 0; i < count; i++) {
            weight[i] = foliage[i] != 0 ? 1.0f : 0.0f;
        }
        boxBlur(weight, width, height, Math.max(1, Math.min(width, height) / 160), 2);

        for (int i = 0; i < count; i++) {
            final float s = clampFloat(field[i], -1.0f, 1.0f);
            final float w = clampFloat(weight[i], 0.0f, 1.0f);
            field[i] = w * s + (1.0f - w) * ((s - 1.0f) * 0.5f);
        }

        final int outW = width * scale;
        final int outH = height * scale;
        final int[] xIdx = new int[outW * 4];
        final float[] xWts = new float[outW * 4];
        final int[] yIdx = new int[outH * 4];
        final float[] yWts = new float[outH * 4];
        fillCubicTaps(width, scale, xIdx, xWts);
        fillCubicTaps(height, scale, yIdx, yWts);

        // Two-stage (horizontal, then vertical) bicubic enlargement: ~4x cheaper than 4x4 taps.
        final float[] horizontal = new float[height * outW];
        for (int y = 0; y < height; y++) {
            final int srcBase = y * width;
            final int dstBase = y * outW;
            for (int ox = 0; ox < outW; ox++) {
                final int o4 = ox * 4;
                horizontal[dstBase + ox] =
                        xWts[o4] * field[srcBase + xIdx[o4]]
                                + xWts[o4 + 1] * field[srcBase + xIdx[o4 + 1]]
                                + xWts[o4 + 2] * field[srcBase + xIdx[o4 + 2]]
                                + xWts[o4 + 3] * field[srcBase + xIdx[o4 + 3]];
            }
        }

        final Bitmap result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        final int[] row = new int[outW];
        final int lutMax = NDVI_PALETTE_LUT.length - 1;
        for (int oy = 0; oy < outH; oy++) {
            final int o4 = oy * 4;
            final int r0 = yIdx[o4] * outW;
            final int r1 = yIdx[o4 + 1] * outW;
            final int r2 = yIdx[o4 + 2] * outW;
            final int r3 = yIdx[o4 + 3] * outW;
            final float w0 = yWts[o4];
            final float w1 = yWts[o4 + 1];
            final float w2 = yWts[o4 + 2];
            final float w3 = yWts[o4 + 3];
            for (int ox = 0; ox < outW; ox++) {
                final float v = w0 * horizontal[r0 + ox] + w1 * horizontal[r1 + ox]
                        + w2 * horizontal[r2 + ox] + w3 * horizontal[r3 + ox];
                final int lutIndex = clampInt(
                        Math.round((clampFloat(v, -1.0f, 1.0f) + 1.0f) * 0.5f * lutMax),
                        0, lutMax);
                row[ox] = NDVI_PALETTE_LUT[lutIndex];
            }
            result.setPixels(row, 0, outW, 0, oy, outW, 1);
        }
        return result;
    }

    private static void fillCubicTaps(int srcLen, int scale, int[] idx, float[] wts) {
        final int outLen = srcLen * scale;
        for (int o = 0; o < outLen; o++) {
            final float s = (o + 0.5f) / scale - 0.5f;
            final int s0 = (int) Math.floor(s);
            final float f = s - s0;
            final float f2 = f * f;
            final float f3 = f2 * f;
            wts[o * 4] = -0.5f * f3 + f2 - 0.5f * f;
            wts[o * 4 + 1] = 1.5f * f3 - 2.5f * f2 + 1.0f;
            wts[o * 4 + 2] = -1.5f * f3 + 2.0f * f2 + 0.5f * f;
            wts[o * 4 + 3] = 0.5f * f3 - 0.5f * f2;
            for (int k = 0; k < 4; k++) {
                idx[o * 4 + k] = clampInt(s0 - 1 + k, 0, srcLen - 1);
            }
        }
    }

    private static float[] median3x3(float[] src, int width, int height) {
        final float[] dst = new float[src.length];
        final float[] window = new float[9];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int n = 0;
                for (int oy = -1; oy <= 1; oy++) {
                    final int yy = clampInt(y + oy, 0, height - 1) * width;
                    for (int ox = -1; ox <= 1; ox++) {
                        window[n++] = src[yy + clampInt(x + ox, 0, width - 1)];
                    }
                }
                Arrays.sort(window, 0, 9);
                dst[y * width + x] = window[4];
            }
        }
        return dst;
    }

    private static void boxBlur(float[] data, int width, int height, int radius, int passes) {
        final int longest = Math.max(width, height);
        final float[] line = new float[longest];
        final float[] out = new float[longest];
        final float inv = 1.0f / (2 * radius + 1);
        for (int pass = 0; pass < passes; pass++) {
            for (int y = 0; y < height; y++) {
                final int base = y * width;
                System.arraycopy(data, base, line, 0, width);
                blurLine(line, out, width, radius, inv);
                System.arraycopy(out, 0, data, base, width);
            }
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) line[y] = data[y * width + x];
                blurLine(line, out, height, radius, inv);
                for (int y = 0; y < height; y++) data[y * width + x] = out[y];
            }
        }
    }

    private static void blurLine(float[] in, float[] out, int len, int radius, float inv) {
        float sum = 0.0f;
        for (int k = -radius; k <= radius; k++) {
            sum += in[clampInt(k, 0, len - 1)];
        }
        for (int i = 0; i < len; i++) {
            out[i] = sum * inv;
            sum += in[clampInt(i + radius + 1, 0, len - 1)] - in[clampInt(i - radius, 0, len - 1)];
        }
    }

    private static int ndviPaletteColor(float ndvi) {
        final float value = clampFloat(ndvi, -1.0f, 1.0f);
        if (value <= 0.0f) {
            return blendColor(
                    NDVI_PALETTE_RED,
                    NDVI_PALETTE_YELLOW,
                    value + 1.0f
            );
        }
        return blendColor(
                NDVI_PALETTE_YELLOW,
                NDVI_PALETTE_GREEN,
                value
        );
    }

    private static int blendColor(int colorA, int colorB, float amount) {
        final float t = clampFloat(amount, 0.0f, 1.0f);
        final int r = Math.round(Color.red(colorA) + (Color.red(colorB) - Color.red(colorA)) * t);
        final int g = Math.round(Color.green(colorA) + (Color.green(colorB) - Color.green(colorA)) * t);
        final int b = Math.round(Color.blue(colorA) + (Color.blue(colorB) - Color.blue(colorA)) * t);
        return Color.rgb(r, g, b);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int sampleNirBlueBilinear(
            int[] nirPixels,
            int nirWidth,
            int nirHeight,
            long mappedUv
    ) {
        if (mappedUv < 0L || nirPixels == null || nirWidth <= 0 || nirHeight <= 0
                || (long) nirPixels.length < (long) nirWidth * (long) nirHeight) {
            return -1;
        }
        final float nirU = Float.intBitsToFloat((int) (mappedUv >> 32));
        final float nirV = Float.intBitsToFloat((int) mappedUv);
        if (!Float.isFinite(nirU) || !Float.isFinite(nirV)) {
            return -1;
        }

        final float px = clampFloat(nirU * nirWidth - 0.5f, 0.0f, nirWidth - 1.0f);
        final float py = clampFloat(nirV * nirHeight - 0.5f, 0.0f, nirHeight - 1.0f);
        final int x0 = (int) Math.floor(px);
        final int y0 = (int) Math.floor(py);
        final int x1 = Math.min(nirWidth - 1, x0 + 1);
        final int y1 = Math.min(nirHeight - 1, y0 + 1);
        final float fx = px - x0;
        final float fy = py - y0;

        final float p00 = pixelNirIntensity(nirPixels[y0 * nirWidth + x0]);
        final float p10 = pixelNirIntensity(nirPixels[y0 * nirWidth + x1]);
        final float p01 = pixelNirIntensity(nirPixels[y1 * nirWidth + x0]);
        final float p11 = pixelNirIntensity(nirPixels[y1 * nirWidth + x1]);
        final float top = p00 + (p10 - p00) * fx;
        final float bottom = p01 + (p11 - p01) * fx;
        return clampInt(Math.round(top + (bottom - top) * fy), 0, 255);
    }

    private static int[] buildNdviPaletteLut() {
        final int[] lut = new int[NDVI_PALETTE_LUT_SIZE];
        for (int i = 0; i < lut.length; i++) {
            lut[i] = ndviPaletteColor(-1.0f + 2.0f * i / (lut.length - 1));
        }
        return lut;
    }
}
