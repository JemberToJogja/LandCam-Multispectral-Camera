package com.example.landcam;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * LANDCAM capture-output policy.
 *
 * <p>This class owns only the selection policy. It does not own camera
 * transport, shutter control, response decoding, optical processing, or file
 * I/O. CameraEngine remains responsible for executing one still-capture
 * operation and using the returned bytes to produce the requested outputs.</p>
 *
 * <p>Canonical values shared by CaptureManager, CapturePlan, CameraEngine,
 * MainActivity, and Flutter:</p>
 * <ul>
 *     <li>{@code RAW}</li>
 *     <li>{@code PROCESSED}</li>
 *     <li>{@code RAW + PROCESSED}</li>
 * </ul>
 *
 * <p>{@code RAW} means the original bytes returned by the camera. It does not
 * imply a sensor RAW format unless the camera actually returns one.</p>
 *
 * <p>Call {@link #snapshot()} once at the beginning of each capture and use
 * that immutable plan for every output decision during that capture. This
 * prevents a UI settings change from changing the save policy halfway through
 * an in-flight capture.</p>
 */
public final class CaptureManager {

    public static final String OUTPUT_RAW = "RAW";
    public static final String OUTPUT_PROCESSED = "PROCESSED";
    public static final String OUTPUT_RAW_AND_PROCESSED = "RAW + PROCESSED";

    /** Accepted for compatibility; canonical output never uses this spelling. */
    public static final String LEGACY_OUTPUT_RAW_AND_PROCESSED = "RAW_AND_PROCESSED";

    /** Must match CameraEngine's persisted setting default and Flutter's default. */
    public static final String DEFAULT_CAPTURE_OUTPUT = OUTPUT_PROCESSED;

    private String captureOutput = DEFAULT_CAPTURE_OUTPUT;

    public CaptureManager() {
    }

    /** Returns the currently selected canonical capture-output policy. */
    public synchronized String getCaptureOutput() {
        return captureOutput;
    }

    /**
     * Sets the capture-output policy.
     *
     * @param output canonical value or a supported compatibility alias
     * @return {@code true} if accepted; otherwise leaves the current policy unchanged
     */
    public synchronized boolean setCaptureOutput(String output) {
        final String normalized = normalizeCaptureOutput(output);
        if (normalized == null) {
            return false;
        }
        captureOutput = normalized;
        return true;
    }

    public synchronized boolean shouldSaveRaw() {
        return shouldSaveRaw(captureOutput);
    }

    public synchronized boolean shouldSaveProcessed() {
        return shouldSaveProcessed(captureOutput);
    }

    public synchronized boolean saveBothOutputs() {
        return OUTPUT_RAW_AND_PROCESSED.equals(captureOutput);
    }

    /**
     * Maps the three-state policy onto the legacy two-state API. The dual-output
     * policy maps to PROCESSED for compatibility; use {@link #snapshot()} for
     * the actual save flags.
     */
    public synchronized String getLegacyCaptureMode() {
        return OUTPUT_RAW.equals(captureOutput) ? OUTPUT_RAW : OUTPUT_PROCESSED;
    }

    /** Returns an immutable policy snapshot for a single in-flight capture. */
    public synchronized CapturePlan snapshot() {
        return new CapturePlan(captureOutput);
    }

    /** Returns a bridge-friendly map of the current setting. */
    public synchronized Map<String, Object> toMap() {
        return toMap(captureOutput);
    }

    /**
     * Converts the supplied value into a bridge-friendly policy map. Invalid
     * input fails closed to PROCESSED and is marked with {@code valid=false}; a
     * malformed value can never accidentally enable dual-output saving.
     */
    public static Map<String, Object> toMap(String output) {
        final String normalized = normalizeCaptureOutput(output);
        final boolean valid = normalized != null;
        return buildMap(valid ? normalized : DEFAULT_CAPTURE_OUTPUT, valid);
    }

    private static Map<String, Object> buildMap(String normalized, boolean valid) {
        final boolean saveRaw = shouldSaveRaw(normalized);
        final boolean saveProcessed = shouldSaveProcessed(normalized);
        final boolean saveBoth = OUTPUT_RAW_AND_PROCESSED.equals(normalized);

        HashMap<String, Object> map = new HashMap<>();
        map.put("captureOutput", normalized);
        map.put("captureOutputMode", normalized);
        // Backward-compatible aliases used by older native and Flutter callers.
        map.put("captureMode", normalized);
        map.put("saveRaw", saveRaw);
        map.put("saveProcessed", saveProcessed);
        map.put("saveBoth", saveBoth);
        map.put("legacyCaptureMode", OUTPUT_RAW.equals(normalized)
                ? OUTPUT_RAW : OUTPUT_PROCESSED);
        map.put("valid", valid);
        return map;
    }

    private static boolean shouldSaveRaw(String normalized) {
        return OUTPUT_RAW.equals(normalized)
                || OUTPUT_RAW_AND_PROCESSED.equals(normalized);
    }

    private static boolean shouldSaveProcessed(String normalized) {
        return OUTPUT_PROCESSED.equals(normalized)
                || OUTPUT_RAW_AND_PROCESSED.equals(normalized);
    }

    /**
     * Normalizes supported values to the exact shared canonical spelling.
     * Examples accepted include RAW_AND_PROCESSED, RAW AND PROCESSED,
     * RAW + PROCESSED, RAW+PROCESSED, RAW PROCESSED, and BOTH.
     * Returns {@code null} for unknown values so callers can reject them.
     */
    public static String normalizeCaptureOutput(String value) {
        if (value == null) {
            return null;
        }

        final String normalized = value.trim()
                .toUpperCase(Locale.US)
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('+', ' ')
                .replaceAll("\\s+", " ")
                .trim();

        switch (normalized) {
            case "RAW":
                return OUTPUT_RAW;
            case "PROCESSED":
                return OUTPUT_PROCESSED;
            case "RAW AND PROCESSED":
            case "RAW PROCESSED":
            case "BOTH":
                return OUTPUT_RAW_AND_PROCESSED;
            default:
                return null;
        }
    }

    /** Immutable policy snapshot for one capture operation. */
    public static final class CapturePlan {
        private final String captureOutput;
        private final boolean saveRaw;
        private final boolean saveProcessed;

        private CapturePlan(String canonicalCaptureOutput) {
            // Only the manager constructs plans, and it holds canonical values.
            this.captureOutput = canonicalCaptureOutput;
            this.saveRaw = CaptureManager.shouldSaveRaw(canonicalCaptureOutput);
            this.saveProcessed = CaptureManager.shouldSaveProcessed(canonicalCaptureOutput);
        }

        public String getCaptureOutput() {
            return captureOutput;
        }

        public boolean shouldSaveRaw() {
            return saveRaw;
        }

        public boolean shouldSaveProcessed() {
            return saveProcessed;
        }

        public boolean saveBothOutputs() {
            return saveRaw && saveProcessed;
        }

        public String getLegacyCaptureMode() {
            return saveRaw && !saveProcessed ? OUTPUT_RAW : OUTPUT_PROCESSED;
        }

        /** Returns a new map; mutations cannot alter this immutable plan. */
        public Map<String, Object> toMap() {
            return buildMap(captureOutput, true);
        }

        @Override
        public String toString() {
            return "CapturePlan{" +
                    "captureOutput='" + captureOutput + '\'' +
                    ", saveRaw=" + saveRaw +
                    ", saveProcessed=" + saveProcessed +
                    '}';
        }
    }
}
