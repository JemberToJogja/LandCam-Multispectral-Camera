package com.example.landcam;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * LANDCAM capture-output policy.
 *
 * This class does not own camera transport, shutter control, decoding, image
 * processing, or file I/O. CameraEngine remains responsible for executing a
 * single still capture and saving the requested outputs from the returned bytes.
 *
 * Canonical output values shared by CaptureManager, CameraEngine, MainActivity,
 * and Flutter:
 *   RAW
 *   PROCESSED
 *   RAW + PROCESSED
 *
 * RAW means the original bytes returned by the camera; it does not imply a
 * sensor RAW format unless the camera actually returns one.
 */
public final class CaptureManager {

    public static final String OUTPUT_RAW = "RAW";
    public static final String OUTPUT_PROCESSED = "PROCESSED";

    /** Canonical spelling used by CameraEngine and MainActivity. */
    public static final String OUTPUT_RAW_AND_PROCESSED = "RAW + PROCESSED";

    /** Accepted for compatibility with older clients; never emitted as canonical. */
    public static final String LEGACY_OUTPUT_RAW_AND_PROCESSED = "RAW_AND_PROCESSED";

    /** Keep the policy default consistent with CameraEngine and persisted settings. */
    public static final String DEFAULT_CAPTURE_OUTPUT = OUTPUT_PROCESSED;

    private String captureOutput = DEFAULT_CAPTURE_OUTPUT;

    public CaptureManager() {
    }

    /** Returns the current canonical capture-output policy. */
    public synchronized String getCaptureOutput() {
        return captureOutput;
    }

    /** Sets the policy. Returns false without changing state when input is invalid. */
    public synchronized boolean setCaptureOutput(String output) {
        String normalized = normalizeCaptureOutput(output);
        if (normalized == null) {
            return false;
        }
        captureOutput = normalized;
        return true;
    }

    public synchronized boolean shouldSaveRaw() {
        return OUTPUT_RAW.equals(captureOutput)
                || OUTPUT_RAW_AND_PROCESSED.equals(captureOutput);
    }

    public synchronized boolean shouldSaveProcessed() {
        return OUTPUT_PROCESSED.equals(captureOutput)
                || OUTPUT_RAW_AND_PROCESSED.equals(captureOutput);
    }

    public synchronized boolean saveBothOutputs() {
        return OUTPUT_RAW_AND_PROCESSED.equals(captureOutput);
    }

    /**
     * Maps the three-state policy to the legacy two-state mode. The dual-output
     * mode maps to PROCESSED for old APIs; use CapturePlan for actual save flags.
     */
    public synchronized String getLegacyCaptureMode() {
        return OUTPUT_RAW.equals(captureOutput) ? OUTPUT_RAW : OUTPUT_PROCESSED;
    }

    /** Returns an immutable snapshot suitable for one in-flight capture. */
    public synchronized CapturePlan snapshot() {
        return new CapturePlan(captureOutput);
    }

    /** Returns a bridge-safe representation of current state. */
    public synchronized Map<String, Object> toMap() {
        return toMap(captureOutput);
    }

    /**
     * Builds a map for a supplied policy. Invalid values fail closed to the
     * ordinary PROCESSED default, and the "valid" field tells callers that the
     * original input was invalid. A malformed setting must never unexpectedly
     * trigger saving both outputs.
     */
    public static Map<String, Object> toMap(String output) {
        String normalized = normalizeCaptureOutput(output);
        boolean valid = normalized != null;
        if (!valid) {
            normalized = DEFAULT_CAPTURE_OUTPUT;
        }
        return buildMap(normalized, valid);
    }

    private static Map<String, Object> buildMap(String normalized, boolean valid) {
        boolean saveRaw = OUTPUT_RAW.equals(normalized)
                || OUTPUT_RAW_AND_PROCESSED.equals(normalized);
        boolean saveProcessed = OUTPUT_PROCESSED.equals(normalized)
                || OUTPUT_RAW_AND_PROCESSED.equals(normalized);
        boolean saveBoth = OUTPUT_RAW_AND_PROCESSED.equals(normalized);

        HashMap<String, Object> map = new HashMap<>();
        map.put("captureOutput", normalized);
        map.put("captureOutputMode", normalized);
        // Backward-compatible aliases consumed by older Flutter/native callers.
        map.put("captureMode", normalized);
        map.put("saveRaw", saveRaw);
        map.put("saveProcessed", saveProcessed);
        map.put("saveBoth", saveBoth);
        map.put("legacyCaptureMode", OUTPUT_RAW.equals(normalized)
                ? OUTPUT_RAW : OUTPUT_PROCESSED);
        map.put("valid", valid);
        return map;
    }

    /**
     * Normalizes input into the exact canonical spelling used across the native
     * bridge. Accepted aliases include RAW_AND_PROCESSED, RAW AND PROCESSED,
     * RAW + PROCESSED, RAW+PROCESSED, RAW PROCESSED, and BOTH.
     */
    public static String normalizeCaptureOutput(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim()
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

    /** Immutable policy snapshot for a single capture operation. */
    public static final class CapturePlan {
        private final String captureOutput;

        private CapturePlan(String captureOutput) {
            this.captureOutput = captureOutput;
        }

        public String getCaptureOutput() {
            return captureOutput;
        }

        public boolean shouldSaveRaw() {
            return OUTPUT_RAW.equals(captureOutput)
                    || OUTPUT_RAW_AND_PROCESSED.equals(captureOutput);
        }

        public boolean shouldSaveProcessed() {
            return OUTPUT_PROCESSED.equals(captureOutput)
                    || OUTPUT_RAW_AND_PROCESSED.equals(captureOutput);
        }

        public boolean saveBothOutputs() {
            return OUTPUT_RAW_AND_PROCESSED.equals(captureOutput);
        }

        public String getLegacyCaptureMode() {
            return OUTPUT_RAW.equals(captureOutput)
                    ? OUTPUT_RAW : OUTPUT_PROCESSED;
        }

        public Map<String, Object> toMap() {
            return buildMap(captureOutput, true);
        }
    }
}
