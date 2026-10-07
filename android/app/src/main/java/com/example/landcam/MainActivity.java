package com.example.landcam;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.view.TextureRegistry;

/**
 * LANDCAM Android entry point.
 *
 * Responsibilities are intentionally limited to:
 *   - Android/Flutter lifecycle
 *   - MethodChannel / EventChannel bridge
 *   - Advanced Settings bridge state
 *   - NFC foreground dispatch
 *   - permission requests
 *   - delegating camera work to CameraEngine
 *
 * Camera networking, discovery, image processing, NDVI, autofocus,
 * live-view and capture logic remain in CameraEngine and the
 * dedicated native components.
 *
 * This class must stay a thin integration layer.
 */
public class MainActivity extends FlutterActivity
        implements CameraEngine.Listener {

    private static final String TAG =
            "LandCamMonitor";

    private static final String METHOD_CHANNEL =
            "landcam/native";

    private static final String EVENT_CHANNEL =
            "landcam/events";

    private static final int WIFI_PERMISSION_REQUEST =
            100;

    private static final int GPU_NDVI_TEXTURE_WIDTH =
            960;

    private static final int GPU_NDVI_TEXTURE_HEIGHT =
            960;

    // ---------------------------------------------------------------------
    // Native / Flutter bridge state
    // ---------------------------------------------------------------------

    /**
     * EventSink is written from Flutter's stream lifecycle callbacks and read
     * from CameraEngine/background callbacks, so it must be safely published.
     */
    private volatile EventChannel.EventSink eventSink;

    /**
     * Non-liveview events can arrive before Flutter subscribes to the
     * EventChannel. Keep a bounded queue so startup/control events are not
     * lost.
     *
     * High-frequency preview frames are never buffered.
     */
    private final List<Map<String, Object>> pendingEvents =
            new ArrayList<>();

    private CameraEngine cameraEngine;

    /**
     * Owned by Flutter's TextureRegistry.
     *
     * CameraEngine receives only the underlying SurfaceTexture and owns
     * its Surface/GL resources.
     */
    private TextureRegistry.SurfaceTextureEntry ndviTextureEntry;

    private NfcAdapter nfcAdapter;

    private PendingIntent nfcPendingIntent;

    /**
     * Foreground dispatch is enabled only after Flutter explicitly requests
     * NFC listening.
     */
    private boolean nfcDispatchEnabled =
            false;

    // ---------------------------------------------------------------------
    // Advanced Settings native-side bridge state
    // ---------------------------------------------------------------------

    /**
     * Domain-level capture-output state.
     *
     * Accepted values:
     *   RAW
     *   PROCESSED
     *   RAW_AND_PROCESSED
     */
    private String advancedCaptureOutput =
            "RAW_AND_PROCESSED";

    /**
     * Domain-level performance state.
     *
     * Accepted values:
     *   PERFORMANCE
     *   BALANCED
     *   HIGH_QUALITY
     */
    private String advancedPerformance =
            "BALANCED";

    /**
     * Resolved performance configuration.
     *
     * This payload is prepared by Flutter and retained here so the native
     * engine can later consume the same configuration without Flutter having
     * to reconstruct it.
     */
    private Map<String, Object> advancedPerformanceConfig =
            defaultPerformanceConfig(
                    "BALANCED"
            );

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(
                savedInstanceState
        );

        setupFullscreen();
        setupNfc();
        checkPermissions();

        /*
         * Create the engine before handling an NFC intent so a launch caused
         * by an NFC tap cannot lose credentials before Flutter finishes
         * wiring the EventChannel.
         */
        cameraEngine =
                new CameraEngine(
                        this,
                        this
                );

        handleNfcIntent(
                getIntent()
        );
    }

    @Override
    public void configureFlutterEngine(
            FlutterEngine flutterEngine
    ) {
        super.configureFlutterEngine(
                flutterEngine
        );

        ensureEngine();

        ensureNdviTexture(
                flutterEngine
        );

        new MethodChannel(
                flutterEngine
                        .getDartExecutor()
                        .getBinaryMessenger(),
                METHOD_CHANNEL
        ).setMethodCallHandler(
                this::handleMethodCall
        );

        new EventChannel(
                flutterEngine
                        .getDartExecutor()
                        .getBinaryMessenger(),
                EVENT_CHANNEL
        ).setStreamHandler(
                new EventChannel.StreamHandler() {

                    @Override
                    public void onListen(
                            Object arguments,
                            EventChannel.EventSink events
                    ) {
                        eventSink =
                                events;

                        /*
                         * Deterministic bootstrap notification.
                         */
                        sendEventNow(
                                "ready",
                                null
                        );

                        if (ndviTextureEntry != null) {
                            sendEventNow(
                                    "gpuTextureReady",
                                    mapOf(
                                            "textureId",
                                            ndviTextureEntry.id(),
                                            "width",
                                            GPU_NDVI_TEXTURE_WIDTH,
                                            "height",
                                            GPU_NDVI_TEXTURE_HEIGHT
                                    )
                            );
                        } else {
                            sendEventNow(
                                    "gpuTextureUnavailable",
                                    mapOf(
                                            "reason",
                                            "TEXTURE_SETUP_FAILED"
                                    )
                            );
                        }

                        /*
                         * Also expose the current native-side Advanced
                         * Settings after the Flutter stream becomes active.
                         */
                        sendEventNow(
                                "advancedSettingsChanged",
                                buildAdvancedSettingsPayload()
                        );

                        flushPendingEvents();
                    }

                    @Override
                    public void onCancel(
                            Object arguments
                    ) {
                        eventSink =
                                null;
                    }
                }
        );
    }

    // ---------------------------------------------------------------------
    // MethodChannel
    // ---------------------------------------------------------------------

    private void handleMethodCall(
            MethodCall call,
            MethodChannel.Result result
    ) {
        try {
            switch (call.method) {

                // ---------------------------------------------------------
                // Core lifecycle
                // ---------------------------------------------------------

                case "initialize":
                    ensureEngine()
                            .initialize();

                    result.success(
                            true
                    );
                    return;

                // ---------------------------------------------------------
                // NFC
                // ---------------------------------------------------------

                case "startNfc":
                    result.success(
                            startNfcListening()
                    );
                    return;

                case "stopNfc":
                    stopNfcListening();

                    result.success(
                            true
                    );
                    return;

                // ---------------------------------------------------------
                // Network / camera
                // ---------------------------------------------------------

                case "connectLastWifi":
                    result.success(
                            ensureEngine()
                                    .connectLastWifi()
                    );
                    return;

                case "probeCurrentNetwork":
                    ensureEngine()
                            .probeCurrentNetwork();

                    result.success(
                            true
                    );
                    return;

                case "refreshLiveview":
                    ensureEngine()
                            .refreshLiveview();

                    result.success(
                            true
                    );
                    return;

                case "capture":
                    ensureEngine()
                            .capture();

                    result.success(
                            true
                    );
                    return;

                /**
                 * Backward-compatible manual AF entry point.
                 */
                case "autofocus":
                    ensureEngine()
                            .triggerAutoFocus();

                    result.success(
                            true
                    );
                    return;

                // ---------------------------------------------------------
                // Legacy compatibility
                // ---------------------------------------------------------

                case "toggleViewMode":
                    result.success(
                            ensureEngine()
                                    .toggleViewMode()
                    );
                    return;

                case "setGrayscale":
                    result.success(
                            ensureEngine()
                                    .setGrayscale(
                                            call.arguments
                                                    instanceof Boolean
                                                    && (Boolean)
                                                    call.arguments
                                    )
                    );
                    return;

                // ---------------------------------------------------------
                // Spectral
                // ---------------------------------------------------------

                case "setSpectralBand":
                    result.success(
                            ensureEngine()
                                    .setSpectralBand(
                                            call.arguments == null
                                                    ? null
                                                    : String.valueOf(
                                                            call.arguments
                                                    )
                                    )
                    );
                    return;

                // ---------------------------------------------------------
                // Legacy capture mode
                // ---------------------------------------------------------

                case "setCaptureMode": {
                    String mode =
                            call.arguments == null
                                    ? null
                                    : String.valueOf(
                                            call.arguments
                                    );

                    if (mode == null) {
                        result.success(
                                false
                        );
                        return;
                    }

                    mode =
                            normalizeCaptureMode(
                                    mode
                            );

                    if (mode == null) {
                        result.success(
                                false
                        );
                        return;
                    }

                    final boolean accepted =
                            ensureEngine()
                                    .setCaptureMode(
                                            mode
                                    );

                    if (accepted) {
                        synchronizeAdvancedCaptureOutputFromLegacyMode(
                                mode
                        );
                    }

                    result.success(
                            accepted
                    );
                    return;
                }

                case "getCaptureMode":
                    result.success(
                            ensureEngine()
                                    .getCaptureMode()
                    );
                    return;

                // ---------------------------------------------------------
                // NDVI
                // ---------------------------------------------------------

                case "setNdviEnabled":
                    if (!(call.arguments
                            instanceof Boolean)) {
                        result.success(
                                false
                        );
                        return;
                    }

                    result.success(
                            ensureEngine()
                                    .setNdviEnabled(
                                            (Boolean)
                                                    call.arguments
                                    )
                    );
                    return;

                case "getNdviEnabled":
                    result.success(
                            ensureEngine()
                                    .isNdviEnabled()
                    );
                    return;

                // ---------------------------------------------------------
                // Advanced Settings
                // ---------------------------------------------------------

                case "applyAdvancedSettings": {
                    final Map<String, Object> payload =
                            readMapArguments(
                                    call.arguments
                            );

                    if (payload == null) {
                        result.success(
                                false
                        );
                        return;
                    }

                    final boolean accepted =
                            applyAdvancedSettingsPayload(
                                    payload
                            );

                    result.success(
                            accepted
                    );
                    return;
                }

                case "setCaptureOutput": {
                    final String captureOutput =
                            normalizeCaptureOutput(
                                    call.arguments == null
                                            ? null
                                            : String.valueOf(
                                                    call.arguments
                                            )
                            );

                    if (captureOutput == null) {
                        result.success(
                                false
                        );
                        return;
                    }

                    final boolean accepted =
                            applyCaptureOutput(
                                    captureOutput
                            );

                    result.success(
                            accepted
                    );
                    return;
                }

                case "setPerformance": {
                    final PerformancePayload payload =
                            parsePerformancePayload(
                                    call.arguments
                            );

                    if (payload == null) {
                        result.success(
                                false
                        );
                        return;
                    }

                    final boolean accepted =
                            applyPerformance(
                                    payload
                            );

                    result.success(
                            accepted
                    );
                    return;
                }

                case "getAdvancedSettings":
                    result.success(
                            buildAdvancedSettingsPayload()
                    );
                    return;

                // ---------------------------------------------------------
                // Session
                // ---------------------------------------------------------

                case "disconnect":
                    ensureEngine()
                            .disconnect();

                    result.success(
                            true
                    );
                    return;

                default:
                    result.notImplemented();
            }

        } catch (Exception e) {
            Log.e(
                    TAG,
                    "Method call failed: "
                            + (
                            call == null
                                    ? "null"
                                    : call.method
                    ),
                    e
            );

            result.error(
                    "NATIVE_ERROR",
                    safeMessage(
                            e
                    ),
                    null
            );
        }
    }

    // ---------------------------------------------------------------------
    // Advanced Settings implementation
    // ---------------------------------------------------------------------

    /**
     * Applies the full Advanced Settings payload atomically at the bridge
     * level.
     *
     * The payload is validated before any native state is changed.
     */
    private boolean applyAdvancedSettingsPayload(
            Map<String, Object> payload
    ) {
        final String captureOutput =
                normalizeCaptureOutput(
                        payload.get(
                                "captureOutput"
                        )
                );

        final String performance =
                normalizePerformance(
                        payload.get(
                                "performance"
                        )
                );

        if (captureOutput == null
                || performance == null) {
            Log.w(
                    TAG,
                    "Invalid Advanced Settings payload"
            );

            sendEvent(
                    "error",
                    "INVALID ADVANCED SETTINGS"
            );

            return false;
        }

        /*
         * Validate the performance config if Flutter supplied one.
         * If not supplied, use the deterministic native default for the
         * selected performance mode.
         */
        final Map<String, Object> performanceConfig =
                readPerformanceConfig(
                        payload.get(
                                "performanceConfig"
                        ),
                        performance
                );

        if (performanceConfig == null) {
            Log.w(
                    TAG,
                    "Invalid performance configuration"
            );

            sendEvent(
                    "error",
                    "INVALID PERFORMANCE CONFIG"
            );

            return false;
        }

        /*
         * Store the complete bridge state first.
         *
         * Actual pixel processing remains in the dedicated native classes.
         */
        advancedCaptureOutput =
                captureOutput;

        advancedPerformance =
                performance;

        advancedPerformanceConfig =
                performanceConfig;

        /*
         * Keep the legacy native capture mode synchronized where possible.
         *
         * The old CameraEngine API supports only RAW / PROCESSED.
         * Therefore RAW_AND_PROCESSED is represented as PROCESSED at the
         * compatibility layer. The complete capture-output state remains
         * available through advancedCaptureOutput.
         *
         * This is intentionally best-effort so Advanced Settings can be
         * selected even before a camera is connected.
         */
        synchronizeLegacyCaptureModeBestEffort(
                captureOutput
        );

        /*
         * Tell Flutter that the complete native bridge state has been
         * accepted.
         */
        sendEvent(
                "advancedSettingsChanged",
                buildAdvancedSettingsPayload()
        );

        sendEvent(
                "captureOutputChanged",
                advancedCaptureOutput
        );

        sendEvent(
                "performanceChanged",
                mapOf(
                        "mode",
                        advancedPerformance,
                        "performance",
                        advancedPerformance,
                        "config",
                        advancedPerformanceConfig
                )
        );

        Log.i(
                TAG,
                "Advanced Settings applied: "
                        + advancedCaptureOutput
                        + " / "
                        + advancedPerformance
        );

        return true;
    }

    /**
     * Applies only capture-output state.
     */
    private boolean applyCaptureOutput(
            String captureOutput
    ) {
        advancedCaptureOutput =
                captureOutput;

        synchronizeLegacyCaptureModeBestEffort(
                captureOutput
        );

        sendEvent(
                "captureOutputChanged",
                advancedCaptureOutput
        );

        sendEvent(
                "advancedSettingsChanged",
                buildAdvancedSettingsPayload()
        );

        Log.i(
                TAG,
                "Capture output: "
                        + advancedCaptureOutput
        );

        return true;
    }

    /**
     * Applies only performance state.
     */
    private boolean applyPerformance(
            PerformancePayload payload
    ) {
        advancedPerformance =
                payload.mode;

        advancedPerformanceConfig =
                payload.config;

        sendEvent(
                "performanceChanged",
                mapOf(
                        "mode",
                        advancedPerformance,
                        "performance",
                        advancedPerformance,
                        "config",
                        advancedPerformanceConfig
                )
        );

        sendEvent(
                "advancedSettingsChanged",
                buildAdvancedSettingsPayload()
        );

        Log.i(
                TAG,
                "Performance: "
                        + advancedPerformance
        );

        return true;
    }

    /**
     * Best-effort compatibility synchronization with the current
     * CameraEngine API.
     *
     * IMPORTANT:
     * CameraEngine currently exposes only setCaptureMode(RAW/PROCESSED).
     * Therefore:
     *
     * RAW
     *   -> native legacy mode RAW
     *
     * PROCESSED
     *   -> native legacy mode PROCESSED
     *
     * RAW_AND_PROCESSED
     *   -> compatibility mode PROCESSED
     *
     * The actual dual-save behavior must be implemented later inside
     * CaptureManager/CameraEngine using advancedCaptureOutput.
     */
    private void synchronizeLegacyCaptureModeBestEffort(
            String captureOutput
    ) {
        final String legacyMode =
                "RAW".equals(
                        captureOutput
                )
                        ? "RAW"
                        : "PROCESSED";

        try {
            ensureEngine()
                    .setCaptureMode(
                            legacyMode
                    );
        } catch (Exception e) {
            /*
             * Settings remain valid even if there is currently no connected
             * camera. This is intentionally not a bridge failure.
             */
            Log.i(
                    TAG,
                    "Legacy capture mode not applied yet: "
                            + safeMessage(e)
            );
        }
    }

    /**
     * Synchronizes Advanced Settings when an older caller changes the
     * two-state capture mode directly.
     */
    private void synchronizeAdvancedCaptureOutputFromLegacyMode(
            String mode
    ) {
        final String normalized =
                normalizeCaptureMode(
                        mode
                );

        if (normalized == null) {
            return;
        }

        advancedCaptureOutput =
                "RAW".equals(
                        normalized
                )
                        ? "RAW"
                        : "PROCESSED";

        sendEvent(
                "captureOutputChanged",
                advancedCaptureOutput
        );

        sendEvent(
                "advancedSettingsChanged",
                buildAdvancedSettingsPayload()
        );
    }

    private Map<String, Object> buildAdvancedSettingsPayload() {
        return mapOf(
                "captureOutput",
                advancedCaptureOutput,

                "performance",
                advancedPerformance,

                "performanceConfig",
                new HashMap<>(
                        advancedPerformanceConfig
                )
        );
    }

    // ---------------------------------------------------------------------
    // Advanced Settings parsing
    // ---------------------------------------------------------------------

    private Map<String, Object> readMapArguments(
            Object arguments
    ) {
        if (!(arguments instanceof Map)) {
            return null;
        }

        Map<?, ?> source =
                (Map<?, ?>) arguments;

        HashMap<String, Object> result =
                new HashMap<>();

        for (Map.Entry<?, ?> entry :
                source.entrySet()) {

            if (entry.getKey() == null) {
                continue;
            }

            result.put(
                    String.valueOf(
                            entry.getKey()
                    ),
                    entry.getValue()
            );
        }

        return result;
    }

    private String normalizeCaptureOutput(
            Object value
    ) {
        if (value == null) {
            return null;
        }

        String normalized =
                String.valueOf(
                        value
                )
                        .trim()
                        .toUpperCase(
                                Locale.US
                        );

        switch (normalized) {
            case "RAW":
                return "RAW";

            case "PROCESSED":
                return "PROCESSED";

            case "RAW_AND_PROCESSED":
            case "RAW + PROCESSED":
            case "RAW+PROCESSED":
                return "RAW_AND_PROCESSED";

            default:
                return null;
        }
    }

    private String normalizeCaptureMode(
            String value
    ) {
        if (value == null) {
            return null;
        }

        String normalized =
                value.trim()
                        .toUpperCase(
                                Locale.US
                        );

        if ("RAW".equals(
                normalized
        )) {
            return "RAW";
        }

        if ("PROCESSED".equals(
                normalized
        )) {
            return "PROCESSED";
        }

        return null;
    }

    private String normalizePerformance(
            Object value
    ) {
        if (value == null) {
            return null;
        }

        String normalized =
                String.valueOf(
                        value
                )
                        .trim()
                        .toUpperCase(
                                Locale.US
                        );

        switch (normalized) {
            case "PERFORMANCE":
                return "PERFORMANCE";

            case "BALANCED":
                return "BALANCED";

            case "HIGH_QUALITY":
            case "HIGH QUALITY":
                return "HIGH_QUALITY";

            default:
                return null;
        }
    }

    private Map<String, Object> readPerformanceConfig(
            Object rawConfig,
            String performanceMode
    ) {
        if (rawConfig == null) {
            return defaultPerformanceConfig(
                    performanceMode
            );
        }

        if (!(rawConfig instanceof Map)) {
            return null;
        }

        Map<?, ?> source =
                (Map<?, ?>) rawConfig;

        Double previewScale =
                parseDouble(
                        source.get(
                                "previewScale"
                        )
                );

        Double processingScale =
                parseDouble(
                        source.get(
                                "processingScale"
                        )
                );

        Integer processingEveryNFrames =
                parseInt(
                        source.get(
                                "processingEveryNFrames"
                        )
                );

        if (previewScale == null
                || processingScale == null
                || processingEveryNFrames == null) {
            return null;
        }

        if (!isValidScale(
                previewScale
        )) {
            return null;
        }

        if (!isValidScale(
                processingScale
        )) {
            return null;
        }

        if (processingEveryNFrames < 1
                || processingEveryNFrames > 60) {
            return null;
        }

        return mapOf(
                "previewScale",
                previewScale,

                "processingScale",
                processingScale,

                "processingEveryNFrames",
                processingEveryNFrames
        );
    }

    private boolean isValidScale(
            double value
    ) {
        return !Double.isNaN(value)
                && !Double.isInfinite(value)
                && value > 0.0
                && value <= 1.0;
    }

    private Map<String, Object> defaultPerformanceConfig(
            String mode
    ) {
        switch (mode) {
            case "PERFORMANCE":
                return mapOf(
                        "previewScale",
                        0.50d,

                        "processingScale",
                        0.50d,

                        "processingEveryNFrames",
                        2
                );

            case "HIGH_QUALITY":
                return mapOf(
                        "previewScale",
                        1.00d,

                        "processingScale",
                        1.00d,

                        "processingEveryNFrames",
                        1
                );

            case "BALANCED":
            default:
                return mapOf(
                        "previewScale",
                        0.75d,

                        "processingScale",
                        0.75d,

                        "processingEveryNFrames",
                        1
                );
        }
    }

    private Integer parseInt(
            Object value
    ) {
        if (value instanceof Integer) {
            return (Integer) value;
        }

        if (value instanceof Number) {
            return ((Number) value)
                    .intValue();
        }

        if (value == null) {
            return null;
        }

        try {
            return Integer.parseInt(
                    String.valueOf(
                            value
                    )
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double parseDouble(
            Object value
    ) {
        if (value instanceof Double) {
            return (Double) value;
        }

        if (value instanceof Number) {
            return ((Number) value)
                    .doubleValue();
        }

        if (value == null) {
            return null;
        }

        try {
            return Double.parseDouble(
                    String.valueOf(
                            value
                    )
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Camera engine
    // ---------------------------------------------------------------------

    private CameraEngine ensureEngine() {
        if (cameraEngine == null) {
            cameraEngine =
                    new CameraEngine(
                            this,
                            this
                    );
        }

        return cameraEngine;
    }

    // ---------------------------------------------------------------------
    // GPU NDVI texture
    // ---------------------------------------------------------------------

    /**
     * Creates the single Flutter SurfaceTexture used by the native GPU NDVI
     * renderer.
     *
     * IMPORTANT:
     * Texture creation belongs to TextureRegistry, not FlutterRenderer.
     */
    private void ensureNdviTexture(
            FlutterEngine flutterEngine
    ) {
        if (ndviTextureEntry != null) {
            return;
        }

        try {
            ndviTextureEntry =
                    flutterEngine
                            .getRenderer()
                            .createSurfaceTexture();

            ensureEngine()
                    .attachGpuNdviSurfaceTexture(
                            ndviTextureEntry.surfaceTexture(),
                            GPU_NDVI_TEXTURE_WIDTH,
                            GPU_NDVI_TEXTURE_HEIGHT
                    );

        } catch (Exception e) {
            Log.e(
                    TAG,
                    "GPU NDVI texture setup failed",
                    e
            );

            if (ndviTextureEntry != null) {
                try {
                    ndviTextureEntry.release();
                } catch (Exception ignored) {
                }

                ndviTextureEntry =
                        null;
            }
        }
    }

    // ---------------------------------------------------------------------
    // NFC
    // ---------------------------------------------------------------------

    private void setupNfc() {
        nfcAdapter =
                NfcAdapter.getDefaultAdapter(
                        this
                );

        if (nfcAdapter == null) {
            return;
        }

        Intent intent =
                new Intent(
                        this,
                        MainActivity.class
                )
                        .addFlags(
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        );

        int flags =
                PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S) {
            flags |=
                    PendingIntent.FLAG_MUTABLE;
        }

        nfcPendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        flags
                );
    }

    private boolean startNfcListening() {
        if (nfcAdapter == null) {
            sendEvent(
                    "nfcUnavailable",
                    "NFC NOT SUPPORTED"
            );

            return false;
        }

        if (!nfcAdapter.isEnabled()) {
            sendEvent(
                    "nfcUnavailable",
                    "NFC IS DISABLED"
            );

            return false;
        }

        try {
            ensureEngine()
                    .onNfcListeningStarted();

            enableNfcForegroundDispatch();

            nfcDispatchEnabled =
                    true;

            return true;

        } catch (Exception e) {
            nfcDispatchEnabled =
                    false;

            try {
                nfcAdapter
                        .disableForegroundDispatch(
                                this
                        );
            } catch (Exception ignored) {
            }

            Log.e(
                    TAG,
                    "NFC foreground dispatch failed",
                    e
            );

            sendEvent(
                    "error",
                    "NFC FOREGROUND DISPATCH FAILED"
            );

            return false;
        }
    }

    private void stopNfcListening() {
        nfcDispatchEnabled =
                false;

        try {
            ensureEngine()
                    .onNfcListeningStopped();

        } catch (Exception e) {
            Log.w(
                    TAG,
                    "Stopping NFC engine state failed",
                    e
            );
        }

        disableNfcForegroundDispatch();
    }

    private void enableNfcForegroundDispatch() {
        if (nfcAdapter == null
                || nfcPendingIntent == null) {
            throw new IllegalStateException(
                    "NFC DISPATCH NOT READY"
            );
        }

        nfcAdapter.enableForegroundDispatch(
                this,
                nfcPendingIntent,
                null,
                null
        );
    }

    private void disableNfcForegroundDispatch() {
        if (nfcAdapter == null) {
            return;
        }

        try {
            nfcAdapter
                    .disableForegroundDispatch(
                            this
                    );
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!nfcDispatchEnabled) {
            return;
        }

        try {
            enableNfcForegroundDispatch();
        } catch (Exception e) {
            Log.e(
                    TAG,
                    "NFC foreground dispatch failed",
                    e
            );

            sendEvent(
                    "error",
                    "NFC FOREGROUND DISPATCH FAILED"
            );
        }
    }

    @Override
    protected void onPause() {
        if (nfcDispatchEnabled) {
            disableNfcForegroundDispatch();
        }

        super.onPause();
    }

    @Override
    protected void onNewIntent(
            Intent intent
    ) {
        super.onNewIntent(
                intent
        );

        setIntent(
                intent
        );

        handleNfcIntent(
                intent
        );
    }

    private void handleNfcIntent(
            Intent intent
    ) {
        if (intent == null) {
            return;
        }

        String action =
                intent.getAction();

        if (!NfcAdapter.ACTION_NDEF_DISCOVERED
                .equals(action)
                && !NfcAdapter.ACTION_TAG_DISCOVERED
                .equals(action)) {
            return;
        }

        android.os.Parcelable[] rawMessages;

        try {
            rawMessages =
                    intent.getParcelableArrayExtra(
                            NfcAdapter
                                    .EXTRA_NDEF_MESSAGES
                    );
        } catch (Exception e) {
            rawMessages =
                    null;
        }

        if (rawMessages == null
                || rawMessages.length == 0) {
            sendEvent(
                    "nfcDetected",
                    mapOf(
                            "payloadDetected",
                            false
                    )
            );

            return;
        }

        CameraEngine engine =
                ensureEngine();

        boolean payloadForwarded =
                false;

        for (
                android.os.Parcelable raw :
                rawMessages
        ) {
            if (!(raw instanceof NdefMessage)) {
                continue;
            }

            NdefMessage message =
                    (NdefMessage) raw;

            for (
                    NdefRecord record :
                    message.getRecords()
            ) {
                if (record == null) {
                    continue;
                }

                byte[] payload =
                        record.getPayload();

                if (payload == null
                        || payload.length == 0) {
                    continue;
                }

                payloadForwarded =
                        true;

                engine.handleNfcPayload(
                        payload
                );
            }
        }

        sendEvent(
                "nfcDetected",
                mapOf(
                        "payloadDetected",
                        payloadForwarded
                )
        );
    }

    // ---------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU) {

            if (checkSelfPermission(
                    Manifest.permission.NEARBY_WIFI_DEVICES
            ) != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(
                        new String[]{
                                Manifest.permission
                                        .NEARBY_WIFI_DEVICES
                        },
                        WIFI_PERMISSION_REQUEST
                );
            }

            return;
        }

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M
                && checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission
                                    .ACCESS_FINE_LOCATION
                    },
                    WIFI_PERMISSION_REQUEST
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode !=
                WIFI_PERMISSION_REQUEST) {
            return;
        }

        boolean granted =
                grantResults != null
                        && grantResults.length > 0;

        if (granted) {
            for (int value :
                    grantResults) {

                if (value !=
                        PackageManager
                                .PERMISSION_GRANTED) {
                    granted =
                            false;
                    break;
                }
            }
        }

        if (granted) {
            sendEvent(
                    "wifiPermission",
                    true
            );
        } else {
            sendEvent(
                    "wifiPermission",
                    false
            );

            sendEvent(
                    "networkUnavailable",
                    "WIFI PERMISSION NOT GRANTED"
            );
        }
    }

    // ---------------------------------------------------------------------
    // Fullscreen
    // ---------------------------------------------------------------------

    private void setupFullscreen() {
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.R) {

            getWindow()
                    .setDecorFitsSystemWindows(
                            false
                    );

            if (getWindow()
                    .getInsetsController() != null) {

                getWindow()
                        .getInsetsController()
                        .hide(
                                android.view
                                        .WindowInsets
                                        .Type
                                        .statusBars()
                                        |
                                android.view
                                        .WindowInsets
                                        .Type
                                        .navigationBars()
                        );
            }

            return;
        }

        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
    }

    // ---------------------------------------------------------------------
    // CameraEngine.Listener
    // ---------------------------------------------------------------------

    /**
     * CameraEngine emits native events through this callback.
     *
     * MainActivity remains the sole owner of Flutter's EventSink.
     */
    @Override
    public void onNativeEvent(
            String type,
            Object data
    ) {
        /*
         * Keep the Flutter Advanced Settings state synchronized with legacy
         * native capture-mode events.
         */
        if ("captureModeChanged".equals(
                type
        )) {
            synchronizeAdvancedCaptureOutputFromEvent(
                    data
            );
        }

        sendEvent(
                type,
                data
        );
    }

    @Override
    public void onEngineError(
            String message,
            Throwable error
    ) {
        if (error != null) {
            Log.e(
                    TAG,
                    message,
                    error
            );
        } else {
            Log.e(
                    TAG,
                    message
            );
        }

        sendEvent(
                "error",
                message
        );
    }

    private void synchronizeAdvancedCaptureOutputFromEvent(
            Object data
    ) {
        String mode = null;

        if (data instanceof Map) {
            Object value =
                    ((Map<?, ?>) data)
                            .get(
                                    "mode"
                            );

            if (value == null) {
                value =
                        ((Map<?, ?>) data)
                                .get(
                                        "captureMode"
                                );
            }

            if (value != null) {
                mode =
                        String.valueOf(
                                value
                        );
            }
        } else if (data != null) {
            mode =
                    String.valueOf(
                            data
                    );
        }

        mode =
                normalizeCaptureMode(
                        mode
                );

        if (mode == null) {
            return;
        }

        synchronizeAdvancedCaptureOutputFromLegacyMode(
                mode
        );
    }

    // ---------------------------------------------------------------------
    // Flutter EventChannel
    // ---------------------------------------------------------------------

    private void sendEvent(
            String type,
            Object data
    ) {
        if (type == null
                || type.trim().isEmpty()) {
            return;
        }

        /*
         * Preview frames are high-frequency data-plane messages.
         * Never buffer them.
         */
        if ("liveviewFrame".equals(type)
                || "spectralFrame".equals(type)) {

            sendEventNow(
                    type,
                    data
            );

            return;
        }

        EventChannel.EventSink sink =
                eventSink;

        if (sink == null) {
            synchronized (pendingEvents) {
                HashMap<String, Object> event =
                        new HashMap<>();

                event.put(
                        "type",
                        type
                );

                event.put(
                        "data",
                        data
                );

                pendingEvents.add(
                        event
                );

                while (pendingEvents.size()
                        > 256) {
                    pendingEvents.remove(
                            0
                    );
                }
            }

            return;
        }

        sendEventNow(
                type,
                data
        );
    }

    private void sendEventNow(
            String type,
            Object data
    ) {
        final EventChannel.EventSink sink =
                eventSink;

        if (sink == null) {
            return;
        }

        final HashMap<String, Object> event =
                new HashMap<>();

        event.put(
                "type",
                type
        );

        event.put(
                "data",
                data
        );

        runOnUiThread(
                () -> {
                    EventChannel.EventSink current =
                            eventSink;

                    if (current == null
                            || current != sink) {
                        return;
                    }

                    try {
                        current.success(
                                event
                        );
                    } catch (Exception e) {
                        Log.e(
                                TAG,
                                "Event delivery failed",
                                e
                        );
                    }
                }
        );
    }

    private void flushPendingEvents() {
        List<Map<String, Object>> snapshot;

        synchronized (pendingEvents) {
            if (pendingEvents.isEmpty()) {
                return;
            }

            snapshot =
                    new ArrayList<>(
                            pendingEvents
                    );

            pendingEvents.clear();
        }

        for (
                Map<String, Object> event :
                snapshot
        ) {
            Object type =
                    event.get(
                            "type"
                    );

            Object data =
                    event.get(
                            "data"
                    );

            sendEventNow(
                    type == null
                            ? ""
                            : String.valueOf(
                                    type
                            ),
                    data
            );
        }
    }

    // ---------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------

    private static HashMap<String, Object> mapOf(
            Object... entries
    ) {
        if (entries == null
                || (entries.length & 1) != 0) {
            throw new IllegalArgumentException(
                    "mapOf requires key/value pairs"
            );
        }

        HashMap<String, Object> map =
                new HashMap<>();

        for (
                int i = 0;
                i < entries.length;
                i += 2
        ) {
            Object key =
                    entries[i];

            if (key == null) {
                throw new IllegalArgumentException(
                        "mapOf key must not be null"
                );
            }

            map.put(
                    String.valueOf(
                            key
                    ),
                    entries[i + 1]
            );
        }

        return map;
    }

    private static String safeMessage(
            Throwable error
    ) {
        if (error == null) {
            return "UNKNOWN ERROR";
        }

        String message =
                error.getMessage();

        if (message == null
                || message.trim().isEmpty()) {
            return error.getClass()
                    .getSimpleName();
        }

        return message;
    }

    // ---------------------------------------------------------------------
    // Performance payload
    // ---------------------------------------------------------------------

    private static final class PerformancePayload {

        final String mode;

        final Map<String, Object> config;

        PerformancePayload(
                String mode,
                Map<String, Object> config
        ) {
            this.mode =
                    mode;

            this.config =
                    config;
        }
    }

    private PerformancePayload parsePerformancePayload(
            Object arguments
    ) {
        /*
         * NativeBridge.setPerformance() currently sends:
         *
         * {
         *   "mode": "...",
         *   "config": {...}
         * }
         */
        if (arguments instanceof Map) {
            Map<?, ?> map =
                    (Map<?, ?>) arguments;

            String mode =
                    normalizePerformance(
                            map.get(
                                    "mode"
                            ) != null
                                    ? map.get(
                                            "mode"
                                    )
                                    : map.get(
                                            "performance"
                                    )
                    );

            if (mode == null) {
                return null;
            }

            Map<String, Object> config =
                    readPerformanceConfig(
                            map.get(
                                    "config"
                            ),
                            mode
                    );

            if (config == null) {
                return null;
            }

            return new PerformancePayload(
                    mode,
                    config
            );
        }

        /*
         * Also accept a plain String for forward/backward compatibility.
         */
        String mode =
                normalizePerformance(
                        arguments
                );

        if (mode == null) {
            return null;
        }

        return new PerformancePayload(
                mode,
                defaultPerformanceConfig(
                        mode
                )
        );
    }

    // ---------------------------------------------------------------------
    // Android lifecycle
    // ---------------------------------------------------------------------

    @Override
    protected void onDestroy() {
        nfcDispatchEnabled =
                false;

        disableNfcForegroundDispatch();

        if (cameraEngine != null) {
            try {
                cameraEngine
                        .detachGpuNdviSurfaceTexture();
            } catch (Exception e) {
                Log.w(
                        TAG,
                        "GPU NDVI detach failed",
                        e
                );
            }

            try {
                cameraEngine.destroy();
            } catch (Exception e) {
                Log.e(
                        TAG,
                        "CameraEngine destroy failed",
                        e
                );
            }

            cameraEngine =
                    null;
        }

        synchronized (pendingEvents) {
            pendingEvents.clear();
        }

        if (ndviTextureEntry != null) {
            try {
                ndviTextureEntry.release();
            } catch (Exception ignored) {
            }

            ndviTextureEntry =
                    null;
        }

        eventSink =
                null;

        super.onDestroy();
    }
}