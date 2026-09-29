package com.example.landcam;

/**
 * LANDCAM capture compatibility shell.
 *
 * The current LANDCAM architecture keeps the complete capture pipeline
 * inside {@link CameraEngine}. CameraEngine already owns:
 *   - shutter / actTakePicture
 *   - capture-image URL discovery
 *   - Network-bound image download
 *   - RAW full-composite saving
 *   - PROCESSED optical-band extraction
 *   - MediaStore / legacy gallery saving
 *   - captureSaved / captureError / shutterAck events
 *
 * Keeping a second capture pipeline here would create two sources of truth
 * and, in the previous version, introduced a dependency on the removed
 * CameraEngine.ApiTransport type.
 *
 * This class therefore intentionally has no transport, camera endpoint,
 * network, or optical-processing dependency. It exists only to preserve the
 * five-file Android project layout without introducing a second pipeline.
 */
public final class CaptureManager {

    /**
     * No-op constructor retained for source compatibility with projects that
     * still instantiate CaptureManager during the transition to the unified
     * CameraEngine capture pipeline.
     */
    public CaptureManager() {
    }
}
