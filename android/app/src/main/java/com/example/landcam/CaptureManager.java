package com.example.landcam;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * LANDCAM capture policy manager.
 *
 * This class does NOT own camera transport or create a second capture
 * pipeline.
 *
 * CameraEngine remains responsible for:
 *   - shutter / actTakePicture
 *   - capture-image URL discovery
 *   - network-bound image download
 *   - decoding / frame acquisition
 *   - actual capture execution
 *
 * CaptureManager owns only the capture policy:
 *
 *   RAW
 *       Save the original camera frame without crop/processing.
 *
 *   PROCESSED
 *       Save the frame after LANDCAM crop and processing.
 *
 *   RAW_AND_PROCESSED
 *       Save both results from the same source capture.
 *
 * This keeps the capture-output decision in one native component while
 * avoiding a second transport or processing pipeline.
 */
public final class CaptureManager {

    public static final String OUTPUT_RAW =
            "RAW";

    public static final String OUTPUT_PROCESSED =
            "PROCESSED";

    public static final String OUTPUT_RAW_AND_PROCESSED =
            "RAW_AND_PROCESSED";

    private String captureOutput =
            OUTPUT_RAW_AND_PROCESSED;

    public CaptureManager() {
    }

    // ---------------------------------------------------------------------
    // Capture output policy
    // ---------------------------------------------------------------------

    /**
     * Returns the currently selected capture-output policy.
     */
    public synchronized String getCaptureOutput() {
        return captureOutput;
    }

    /**
     * Sets the capture-output policy.
     *
     * Returns false when the value is invalid.
     */
    public synchronized boolean setCaptureOutput(
            String output
    ) {
        String normalized =
                normalizeCaptureOutput(
                        output
                );

        if (normalized == null) {
            return false;
        }

        captureOutput =
                normalized;

        return true;
    }

    /**
     * Returns true when the selected policy requires the original frame
     * to be saved.
     */
    public synchronized boolean shouldSaveRaw() {
        return OUTPUT_RAW.equals(
                captureOutput
        )
                || OUTPUT_RAW_AND_PROCESSED.equals(
                captureOutput
        );
    }

    /**
     * Returns true when the selected policy requires a processed frame
     * to be saved.
     */
    public synchronized boolean shouldSaveProcessed() {
        return OUTPUT_PROCESSED.equals(
                captureOutput
        )
                || OUTPUT_RAW_AND_PROCESSED.equals(
                captureOutput
        );
    }

    /**
     * Returns true when both outputs must be saved from the same source
     * capture.
     */
    public synchronized boolean saveBothOutputs() {
        return OUTPUT_RAW_AND_PROCESSED.equals(
                captureOutput
        );
    }

    /**
     * Returns the legacy two-state capture mode expected by older
     * CameraEngine APIs.
     *
     * RAW
     *     -> RAW
     *
     * PROCESSED
     *     -> PROCESSED
     *
     * RAW_AND_PROCESSED
     *     -> PROCESSED
     *
     * RAW_AND_PROCESSED deliberately maps to PROCESSED here because the
     * legacy API has no third state. The actual dual-save decision is still
     * available through shouldSaveRaw() / shouldSaveProcessed().
     */
    public synchronized String getLegacyCaptureMode() {
        if (OUTPUT_RAW.equals(
                captureOutput
        )) {
            return OUTPUT_RAW;
        }

        return OUTPUT_PROCESSED;
    }

    // ---------------------------------------------------------------------
    // Capture decision helpers
    // ---------------------------------------------------------------------

    /**
     * Returns a compact immutable snapshot of the current capture policy.
     *
     * Useful for passing a stable policy into a capture operation so a user
     * changing settings during an already-running capture cannot partially
     * change that operation.
     */
    public synchronized CapturePlan snapshot() {
        return new CapturePlan(
                captureOutput
        );
    }

    /**
     * Builds a Flutter/native-safe map describing the current capture policy.
     */
    public synchronized Map<String, Object> toMap() {
        return toMap(
                captureOutput
        );
    }

    /**
     * Builds a Flutter/native-safe map for a supplied capture policy.
     */
    public static Map<String, Object> toMap(
            String output
    ) {
        String normalized =
                normalizeCaptureOutput(
                        output
                );

        if (normalized == null) {
            normalized =
                    OUTPUT_RAW_AND_PROCESSED;
        }

        HashMap<String, Object> map =
                new HashMap<>();

        map.put(
                "captureOutput",
                normalized
        );

        map.put(
                "saveRaw",
                OUTPUT_RAW.equals(
                        normalized
                )
                        || OUTPUT_RAW_AND_PROCESSED.equals(
                        normalized
                )
        );

        map.put(
                "saveProcessed",
                OUTPUT_PROCESSED.equals(
                        normalized
                )
                        || OUTPUT_RAW_AND_PROCESSED.equals(
                        normalized
                )
        );

        map.put(
                "saveBoth",
                OUTPUT_RAW_AND_PROCESSED.equals(
                        normalized
                )
        );

        map.put(
                "legacyCaptureMode",
                OUTPUT_RAW.equals(
                        normalized
                )
                        ? OUTPUT_RAW
                        : OUTPUT_PROCESSED
        );

        return map;
    }

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    /**
     * Normalizes a capture-output value coming from Flutter/native code.
     *
     * Accepted:
     *   RAW
     *   PROCESSED
     *   RAW_AND_PROCESSED
     *   RAW + PROCESSED
     *   RAW+PROCESSED
     */
    public static String normalizeCaptureOutput(
            String value
    ) {
        if (value == null) {
            return null;
        }

        String normalized =
                value
                        .trim()
                        .toUpperCase(
                                Locale.US
                        );

        switch (normalized) {
            case OUTPUT_RAW:
                return OUTPUT_RAW;

            case OUTPUT_PROCESSED:
                return OUTPUT_PROCESSED;

            case OUTPUT_RAW_AND_PROCESSED:
            case "RAW + PROCESSED":
            case "RAW+PROCESSED":
                return OUTPUT_RAW_AND_PROCESSED;

            default:
                return null;
        }
    }

    /**
     * Immutable capture-policy snapshot.
     *
     * CameraEngine can acquire this at capture start and use the same policy
     * for the entire operation.
     */
    public static final class CapturePlan {

        private final String captureOutput;

        private CapturePlan(
                String captureOutput
        ) {
            this.captureOutput =
                    captureOutput;
        }

        public String getCaptureOutput() {
            return captureOutput;
        }

        public boolean shouldSaveRaw() {
            return OUTPUT_RAW.equals(
                    captureOutput
            )
                    || OUTPUT_RAW_AND_PROCESSED.equals(
                    captureOutput
            );
        }

        public boolean shouldSaveProcessed() {
            return OUTPUT_PROCESSED.equals(
                    captureOutput
            )
                    || OUTPUT_RAW_AND_PROCESSED.equals(
                    captureOutput
            );
        }

        public boolean saveBothOutputs() {
            return OUTPUT_RAW_AND_PROCESSED.equals(
                    captureOutput
            );
        }

        public String getLegacyCaptureMode() {
            return OUTPUT_RAW.equals(
                    captureOutput
            )
                    ? OUTPUT_RAW
                    : OUTPUT_PROCESSED;
        }

        public Map<String, Object> toMap() {
            return CaptureManager.toMap(
                    captureOutput
            );
        }
    }
}