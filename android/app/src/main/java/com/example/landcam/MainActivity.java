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
 * LANDCAM Android entry point and Flutter/native bridge.
 * Camera processing and persistence are delegated to CameraEngine.
 */
public class MainActivity extends FlutterActivity implements CameraEngine.Listener {

    private static final String TAG = "LandCamMonitor";
    private static final String METHOD_CHANNEL = "landcam/native";
    private static final String EVENT_CHANNEL = "landcam/events";
    private static final int WIFI_PERMISSION_REQUEST = 100;
    private static final int GPU_NDVI_TEXTURE_WIDTH = 960;
    private static final int GPU_NDVI_TEXTURE_HEIGHT = 960;

    private volatile EventChannel.EventSink eventSink;
    private final List<Map<String, Object>> pendingEvents = new ArrayList<>();
    private CameraEngine cameraEngine;
    private TextureRegistry.SurfaceTextureEntry ndviTextureEntry;
    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;
    private boolean nfcDispatchEnabled = false;

    // CameraEngine is the source of truth; these fields form the bridge payload.
    private String advancedCaptureOutput = "PROCESSED";
    private String advancedPerformance = "PERFORMANCE";
    private Map<String, Object> advancedPerformanceConfig =
            defaultPerformanceConfig("PERFORMANCE");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupFullscreen();
        setupNfc();
        checkPermissions();

        cameraEngine = new CameraEngine(this, this);
        syncAdvancedSettingsFromEngine();
        handleNfcIntent(getIntent());
    }

    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        ensureEngine();
        ensureNdviTexture(flutterEngine);

        new MethodChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(),
                METHOD_CHANNEL
        ).setMethodCallHandler(this::handleMethodCall);

        new EventChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(),
                EVENT_CHANNEL
        ).setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                eventSink = events;
                sendEventNow("ready", null);

                if (ndviTextureEntry != null) {
                    sendEventNow("gpuTextureReady", mapOf(
                            "textureId", ndviTextureEntry.id(),
                            "width", GPU_NDVI_TEXTURE_WIDTH,
                            "height", GPU_NDVI_TEXTURE_HEIGHT
                    ));
                } else {
                    sendEventNow("gpuTextureUnavailable", mapOf(
                            "reason", "TEXTURE_SETUP_FAILED"
                    ));
                }

                syncAdvancedSettingsFromEngine();
                sendEventNow("advancedSettingsChanged", buildAdvancedSettingsPayload());
                sendEventNow("previewSettingsChanged", ensureEngine().getPreviewSettings());
                flushPendingEvents();
            }

            @Override
            public void onCancel(Object arguments) {
                eventSink = null;
            }
        });
    }

    // ---------------------------------------------------------------------
    // MethodChannel
    // ---------------------------------------------------------------------

    private void handleMethodCall(MethodCall call, MethodChannel.Result result) {
        try {
            switch (call.method) {
                case "initialize":
                    ensureEngine().initialize();
                    syncAdvancedSettingsFromEngine();
                    result.success(true);
                    return;

                case "startNfc":
                    result.success(startNfcListening());
                    return;

                case "stopNfc":
                    stopNfcListening();
                    result.success(true);
                    return;

                case "connectLastWifi":
                    result.success(ensureEngine().connectLastWifi());
                    return;

                case "probeCurrentNetwork":
                    ensureEngine().probeCurrentNetwork();
                    result.success(true);
                    return;

                case "refreshLiveview":
                    ensureEngine().refreshLiveview();
                    result.success(true);
                    return;

                case "capture":
                    ensureEngine().capture();
                    result.success(true);
                    return;

                case "autofocus":
                    ensureEngine().triggerAutoFocus();
                    result.success(true);
                    return;

                case "toggleViewMode":
                    result.success(ensureEngine().toggleViewMode());
                    return;

                case "setGrayscale":
                    result.success(ensureEngine().setGrayscale(
                            call.arguments instanceof Boolean && (Boolean) call.arguments
                    ));
                    return;

                case "setSpectralBand":
                    result.success(ensureEngine().setSpectralBand(
                            call.arguments == null ? null : String.valueOf(call.arguments)
                    ));
                    return;

                // Legacy two-state capture control; kept for existing Flutter callers.
                case "setCaptureMode": {
                    String mode = normalizeCaptureMode(
                            call.arguments == null ? null : String.valueOf(call.arguments)
                    );
                    if (mode == null) {
                        result.success(false);
                        return;
                    }
                    boolean accepted = ensureEngine().setCaptureMode(mode);
                    if (accepted) {
                        synchronizeAdvancedCaptureOutputFromLegacyMode(mode);
                    }
                    result.success(accepted);
                    return;
                }

                case "getCaptureMode":
                    result.success(ensureEngine().getCaptureMode());
                    return;

                // Preview layout is independent of the output saved by Capture.
                case "setPreviewDisplayMode": {
                    Map<String, Object> payload = readMapArguments(call.arguments);
                    Object rawMode = payload == null
                            ? call.arguments
                            : (payload.containsKey("mode")
                                    ? payload.get("mode")
                                    : payload.get("previewDisplayMode"));
                    if (rawMode == null) {
                        result.success(false);
                        return;
                    }
                    boolean accepted = ensureEngine().setPreviewDisplayMode(
                            String.valueOf(rawMode)
                    );
                    if (accepted) {
                        sendEvent("previewSettingsChanged", ensureEngine().getPreviewSettings());
                    }
                    result.success(accepted);
                    return;
                }

                case "getPreviewDisplayMode":
                    result.success(ensureEngine().getPreviewDisplayMode());
                    return;

                case "getPreviewSettings":
                    result.success(ensureEngine().getPreviewSettings());
                    return;

                case "setNdviEnabled":
                    if (!(call.arguments instanceof Boolean)) {
                        result.success(false);
                        return;
                    }
                    result.success(ensureEngine().setNdviEnabled((Boolean) call.arguments));
                    return;

                case "getNdviEnabled":
                    result.success(ensureEngine().isNdviEnabled());
                    return;

                case "applyAdvancedSettings": {
                    Map<String, Object> payload = readMapArguments(call.arguments);
                    if (payload == null) {
                        result.success(false);
                        return;
                    }
                    result.success(applyAdvancedSettingsPayload(payload));
                    return;
                }

                case "setCaptureOutput": {
                    String mode = normalizeCaptureOutput(call.arguments);
                    result.success(mode != null && applyCaptureOutput(mode));
                    return;
                }

                case "setPerformance": {
                    PerformancePayload payload = parsePerformancePayload(call.arguments);
                    result.success(payload != null && applyPerformance(payload));
                    return;
                }

                case "getAdvancedSettings":
                    syncAdvancedSettingsFromEngine();
                    result.success(buildAdvancedSettingsPayload());
                    return;

                case "disconnect":
                    ensureEngine().disconnect();
                    result.success(true);
                    return;

                default:
                    result.notImplemented();
            }
        } catch (Exception e) {
            Log.e(TAG, "Method call failed: " + (call == null ? "null" : call.method), e);
            result.error("NATIVE_ERROR", safeMessage(e), null);
        }
    }

    // ---------------------------------------------------------------------
    // Advanced Settings
    // ---------------------------------------------------------------------

    private boolean applyAdvancedSettingsPayload(Map<String, Object> payload) {
        String captureOutput = normalizeCaptureOutput(payload.get("captureOutput"));
        if (captureOutput == null) {
            captureOutput = normalizeCaptureOutput(payload.get("captureMode"));
        }
        String performance = normalizePerformance(
                payload.containsKey("performance")
                        ? payload.get("performance")
                        : payload.get("performanceMode")
        );

        if (captureOutput == null || performance == null) {
            Log.w(TAG, "Invalid Advanced Settings payload");
            sendEvent("error", "INVALID ADVANCED SETTINGS");
            return false;
        }

        Map<String, Object> config = readPerformanceConfig(
                payload.get("performanceConfig"), performance
        );
        if (config == null) {
            Log.w(TAG, "Invalid performance configuration");
            sendEvent("error", "INVALID PERFORMANCE CONFIG");
            return false;
        }

        boolean accepted = ensureEngine().applyAdvancedSettings(
                captureOutput,
                performance,
                config
        );
        if (!accepted) {
            Log.w(TAG, "CameraEngine rejected Advanced Settings");
            return false;
        }

        advancedCaptureOutput = captureOutput;
        advancedPerformance = performance;
        advancedPerformanceConfig = new HashMap<>(config);
        sendEvent("advancedSettingsChanged", buildAdvancedSettingsPayload());
        sendEvent("captureOutputChanged", advancedCaptureOutput);
        sendEvent("performanceChanged", mapOf(
                "mode", advancedPerformance,
                "performance", advancedPerformance,
                "config", new HashMap<>(advancedPerformanceConfig)
        ));
        Log.i(TAG, "Advanced Settings applied: " + advancedCaptureOutput + " / " + advancedPerformance);
        return true;
    }

    private boolean applyCaptureOutput(String captureOutput) {
        boolean accepted = ensureEngine().setCaptureOutput(captureOutput);
        if (!accepted) {
            return false;
        }
        advancedCaptureOutput = captureOutput;
        syncAdvancedSettingsFromEngine();
        sendEvent("captureOutputChanged", advancedCaptureOutput);
        sendEvent("advancedSettingsChanged", buildAdvancedSettingsPayload());
        Log.i(TAG, "Capture output: " + advancedCaptureOutput);
        return true;
    }

    private boolean applyPerformance(PerformancePayload payload) {
        boolean accepted = ensureEngine().setPerformance(payload.mode, payload.config);
        if (!accepted) {
            return false;
        }
        advancedPerformance = payload.mode;
        advancedPerformanceConfig = new HashMap<>(payload.config);
        syncAdvancedSettingsFromEngine();
        sendEvent("performanceChanged", mapOf(
                "mode", advancedPerformance,
                "performance", advancedPerformance,
                "config", new HashMap<>(advancedPerformanceConfig)
        ));
        sendEvent("advancedSettingsChanged", buildAdvancedSettingsPayload());
        Log.i(TAG, "Performance: " + advancedPerformance);
        return true;
    }

    private void synchronizeAdvancedCaptureOutputFromLegacyMode(String mode) {
        String normalized = normalizeCaptureMode(mode);
        if (normalized == null) {
            return;
        }
        advancedCaptureOutput = "RAW".equals(normalized) ? "RAW" : "PROCESSED";
        advancedPerformanceConfig = defaultPerformanceConfig(advancedPerformance);
        sendEvent("captureOutputChanged", advancedCaptureOutput);
        sendEvent("advancedSettingsChanged", buildAdvancedSettingsPayload());
    }

    private void syncAdvancedSettingsFromEngine() {
        if (cameraEngine == null) {
            return;
        }
        try {
            Map<String, Object> nativeSettings = cameraEngine.getAdvancedSettings();
            String output = normalizeCaptureOutput(nativeSettings.get("captureOutput"));
            if (output == null) {
                output = normalizeCaptureOutput(nativeSettings.get("captureMode"));
            }
            String performance = normalizePerformance(
                    nativeSettings.containsKey("performance")
                            ? nativeSettings.get("performance")
                            : nativeSettings.get("performanceMode")
            );
            if (output != null) {
                advancedCaptureOutput = output;
            }
            if (performance != null) {
                advancedPerformance = performance;
            }
            advancedPerformanceConfig = defaultPerformanceConfig(advancedPerformance);
        } catch (Exception e) {
            Log.w(TAG, "Could not read settings from CameraEngine", e);
        }
    }

    private Map<String, Object> buildAdvancedSettingsPayload() {
        HashMap<String, Object> payload = new HashMap<>();
        payload.put("captureOutput", advancedCaptureOutput);
        payload.put("captureMode", advancedCaptureOutput);
        payload.put("performance", advancedPerformance);
        payload.put("performanceMode", advancedPerformance);
        payload.put("performanceConfig", new HashMap<>(advancedPerformanceConfig));
        return payload;
    }

    private Map<String, Object> readMapArguments(Object arguments) {
        if (!(arguments instanceof Map)) {
            return null;
        }
        Map<?, ?> source = (Map<?, ?>) arguments;
        HashMap<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private String normalizeCaptureOutput(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value)
                .trim().toUpperCase(Locale.US)
                .replace("+", " + ")
                .replaceAll("\\s+", " ")
                .trim();
        switch (normalized) {
            case "RAW":
                return "RAW";
            case "PROCESSED":
                return "PROCESSED";
            case "RAW_AND_PROCESSED":
            case "RAW + PROCESSED":
            case "RAW PROCESSED":
            case "BOTH":
                return "RAW_AND_PROCESSED";
            default:
                return null;
        }
    }

    private String normalizeCaptureMode(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.US);
        if ("RAW".equals(normalized)) {
            return "RAW";
        }
        if ("PROCESSED".equals(normalized)) {
            return "PROCESSED";
        }
        return null;
    }

    private String normalizePerformance(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value)
                .trim().toUpperCase(Locale.US)
                .replace("_", " ")
                .replaceAll("\\s+", " ");
        switch (normalized) {
            case "PERFORMANCE":
                return "PERFORMANCE";
            case "BALANCED":
                return "BALANCED";
            case "HIGH QUALITY":
            case "HIGH RESOLUTION":
            case "QUALITY":
                return "HIGH_QUALITY";
            default:
                return null;
        }
    }

    private Map<String, Object> readPerformanceConfig(Object rawConfig, String performanceMode) {
        if (rawConfig == null) {
            return defaultPerformanceConfig(performanceMode);
        }
        if (!(rawConfig instanceof Map)) {
            return null;
        }
        Map<?, ?> source = (Map<?, ?>) rawConfig;
        Double previewScale = parseDouble(source.get("previewScale"));
        Double processingScale = parseDouble(source.get("processingScale"));
        Integer everyN = parseInt(source.get("processingEveryNFrames"));
        if (previewScale == null || processingScale == null || everyN == null) {
            return null;
        }
        if (!isValidScale(previewScale) || !isValidScale(processingScale)
                || everyN < 1 || everyN > 60) {
            return null;
        }
        return mapOf(
                "previewScale", previewScale,
                "processingScale", processingScale,
                "processingEveryNFrames", everyN
        );
    }

    private boolean isValidScale(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value)
                && value > 0.0 && value <= 1.0;
    }

    private Map<String, Object> defaultPerformanceConfig(String mode) {
        if ("PERFORMANCE".equals(mode)) {
            return mapOf("previewScale", 0.50d, "processingScale", 0.50d,
                    "processingEveryNFrames", 2);
        }
        if ("HIGH_QUALITY".equals(mode)) {
            return mapOf("previewScale", 1.00d, "processingScale", 1.00d,
                    "processingEveryNFrames", 1);
        }
        return mapOf("previewScale", 0.75d, "processingScale", 0.75d,
                "processingEveryNFrames", 1);
    }

    private Integer parseInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double parseDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private PerformancePayload parsePerformancePayload(Object arguments) {
        if (arguments instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) arguments;
            Object rawMode = map.containsKey("mode") ? map.get("mode") : map.get("performance");
            String mode = normalizePerformance(rawMode);
            if (mode == null) {
                return null;
            }
            Map<String, Object> config = readPerformanceConfig(map.get("config"), mode);
            if (config == null) {
                return null;
            }
            return new PerformancePayload(mode, config);
        }
        String mode = normalizePerformance(arguments);
        return mode == null ? null : new PerformancePayload(mode, defaultPerformanceConfig(mode));
    }

    private static final class PerformancePayload {
        final String mode;
        final Map<String, Object> config;
        PerformancePayload(String mode, Map<String, Object> config) {
            this.mode = mode;
            this.config = config;
        }
    }

    // ---------------------------------------------------------------------
    // CameraEngine and GPU texture
    // ---------------------------------------------------------------------

    private CameraEngine ensureEngine() {
        if (cameraEngine == null) {
            cameraEngine = new CameraEngine(this, this);
            syncAdvancedSettingsFromEngine();
        }
        return cameraEngine;
    }

    private void ensureNdviTexture(FlutterEngine flutterEngine) {
        if (ndviTextureEntry != null) {
            return;
        }
        try {
            ndviTextureEntry = flutterEngine.getRenderer().createSurfaceTexture();
            ensureEngine().attachGpuNdviSurfaceTexture(
                    ndviTextureEntry.surfaceTexture(),
                    GPU_NDVI_TEXTURE_WIDTH,
                    GPU_NDVI_TEXTURE_HEIGHT
            );
        } catch (Exception e) {
            Log.e(TAG, "GPU NDVI texture setup failed", e);
            if (ndviTextureEntry != null) {
                try {
                    ndviTextureEntry.release();
                } catch (Exception ignored) {
                }
                ndviTextureEntry = null;
            }
        }
    }

    // ---------------------------------------------------------------------
    // NFC
    // ---------------------------------------------------------------------

    private void setupNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            return;
        }
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        nfcPendingIntent = PendingIntent.getActivity(this, 0, intent, flags);
    }

    private boolean startNfcListening() {
        if (nfcAdapter == null) {
            sendEvent("nfcUnavailable", "NFC NOT SUPPORTED");
            return false;
        }
        if (!nfcAdapter.isEnabled()) {
            sendEvent("nfcUnavailable", "NFC IS DISABLED");
            return false;
        }
        try {
            ensureEngine().onNfcListeningStarted();
            enableNfcForegroundDispatch();
            nfcDispatchEnabled = true;
            return true;
        } catch (Exception e) {
            nfcDispatchEnabled = false;
            try {
                nfcAdapter.disableForegroundDispatch(this);
            } catch (Exception ignored) {
            }
            Log.e(TAG, "NFC foreground dispatch failed", e);
            sendEvent("error", "NFC FOREGROUND DISPATCH FAILED");
            return false;
        }
    }

    private void stopNfcListening() {
        nfcDispatchEnabled = false;
        try {
            ensureEngine().onNfcListeningStopped();
        } catch (Exception e) {
            Log.w(TAG, "Stopping NFC engine state failed", e);
        }
        disableNfcForegroundDispatch();
    }

    private void enableNfcForegroundDispatch() {
        if (nfcAdapter == null || nfcPendingIntent == null) {
            throw new IllegalStateException("NFC DISPATCH NOT READY");
        }
        nfcAdapter.enableForegroundDispatch(this, nfcPendingIntent, null, null);
    }

    private void disableNfcForegroundDispatch() {
        if (nfcAdapter == null) {
            return;
        }
        try {
            nfcAdapter.disableForegroundDispatch(this);
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
            Log.e(TAG, "NFC foreground dispatch failed", e);
            sendEvent("error", "NFC FOREGROUND DISPATCH FAILED");
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNfcIntent(intent);
    }

    private void handleNfcIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {
            return;
        }

        android.os.Parcelable[] rawMessages;
        try {
            rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        } catch (Exception e) {
            rawMessages = null;
        }

        if (rawMessages == null || rawMessages.length == 0) {
            sendEvent("nfcDetected", mapOf("payloadDetected", false));
            return;
        }

        CameraEngine engine = ensureEngine();
        boolean payloadForwarded = false;
        for (android.os.Parcelable raw : rawMessages) {
            if (!(raw instanceof NdefMessage)) {
                continue;
            }
            NdefMessage message = (NdefMessage) raw;
            for (NdefRecord record : message.getRecords()) {
                if (record == null) {
                    continue;
                }
                byte[] payload = record.getPayload();
                if (payload == null || payload.length == 0) {
                    continue;
                }
                payloadForwarded = true;
                engine.handleNfcPayload(payload);
            }
        }
        sendEvent("nfcDetected", mapOf("payloadDetected", payloadForwarded));
    }

    // ---------------------------------------------------------------------
    // Permissions and display setup
    // ---------------------------------------------------------------------

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},
                        WIFI_PERMISSION_REQUEST
                );
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    WIFI_PERMISSION_REQUEST
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != WIFI_PERMISSION_REQUEST) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0;
        if (granted) {
            for (int value : grantResults) {
                if (value != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
        }
        sendEvent("wifiPermission", granted);
        if (!granted) {
            sendEvent("networkUnavailable", "WIFI PERMISSION NOT GRANTED");
        }
    }

    private void setupFullscreen() {
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            if (getWindow().getInsetsController() != null) {
                getWindow().getInsetsController().hide(
                        android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars()
                );
            }
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(
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

    @Override
    public void onNativeEvent(String type, Object data) {
        if ("captureModeChanged".equals(type)) {
            synchronizeAdvancedCaptureOutputFromEvent(data);
        }
        sendEvent(type, data);
    }

    @Override
    public void onEngineError(String message, Throwable error) {
        if (error != null) {
            Log.e(TAG, message, error);
        } else {
            Log.e(TAG, message);
        }
        sendEvent("error", message);
    }

    private void synchronizeAdvancedCaptureOutputFromEvent(Object data) {
        String mode = null;
        if (data instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) data;
            Object value = values.get("captureOutput");
            if (value == null) {
                value = values.get("mode");
            }
            if (value == null) {
                value = values.get("captureMode");
            }
            if (value != null) {
                mode = String.valueOf(value);
            }
        } else if (data != null) {
            mode = String.valueOf(data);
        }

        String normalized = normalizeCaptureOutput(mode);
        if (normalized == null) {
            normalized = normalizeCaptureMode(mode);
        }
        if (normalized == null) {
            return;
        }
        advancedCaptureOutput = normalized;
        advancedPerformanceConfig = defaultPerformanceConfig(advancedPerformance);
        sendEvent("advancedSettingsChanged", buildAdvancedSettingsPayload());
    }

    // ---------------------------------------------------------------------
    // Flutter EventChannel
    // ---------------------------------------------------------------------

    private void sendEvent(String type, Object data) {
        if (type == null || type.trim().isEmpty()) {
            return;
        }
        if ("liveviewFrame".equals(type) || "spectralFrame".equals(type)) {
            sendEventNow(type, data);
            return;
        }

        EventChannel.EventSink sink = eventSink;
        if (sink == null) {
            synchronized (pendingEvents) {
                HashMap<String, Object> event = new HashMap<>();
                event.put("type", type);
                event.put("data", data);
                pendingEvents.add(event);
                while (pendingEvents.size() > 256) {
                    pendingEvents.remove(0);
                }
            }
            return;
        }
        sendEventNow(type, data);
    }

    private void sendEventNow(String type, Object data) {
        final EventChannel.EventSink sink = eventSink;
        if (sink == null) {
            return;
        }
        final HashMap<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("data", data);
        runOnUiThread(() -> {
            EventChannel.EventSink current = eventSink;
            if (current == null || current != sink) {
                return;
            }
            try {
                current.success(event);
            } catch (Exception e) {
                Log.e(TAG, "Event delivery failed", e);
            }
        });
    }

    private void flushPendingEvents() {
        List<Map<String, Object>> snapshot;
        synchronized (pendingEvents) {
            if (pendingEvents.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(pendingEvents);
            pendingEvents.clear();
        }
        for (Map<String, Object> event : snapshot) {
            Object type = event.get("type");
            sendEventNow(type == null ? "" : String.valueOf(type), event.get("data"));
        }
    }

    // ---------------------------------------------------------------------
    // Utility
    // ---------------------------------------------------------------------

    private static HashMap<String, Object> mapOf(Object... entries) {
        if (entries == null || (entries.length & 1) != 0) {
            throw new IllegalArgumentException("mapOf requires key/value pairs");
        }
        HashMap<String, Object> map = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            Object key = entries[i];
            if (key == null) {
                throw new IllegalArgumentException("mapOf key must not be null");
            }
            map.put(String.valueOf(key), entries[i + 1]);
        }
        return map;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "UNKNOWN ERROR";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    // ---------------------------------------------------------------------
    // Lifecycle cleanup
    // ---------------------------------------------------------------------

    @Override
    protected void onDestroy() {
        nfcDispatchEnabled = false;
        disableNfcForegroundDispatch();

        if (cameraEngine != null) {
            try {
                cameraEngine.detachGpuNdviSurfaceTexture();
            } catch (Exception e) {
                Log.w(TAG, "GPU NDVI detach failed", e);
            }
            try {
                cameraEngine.destroy();
            } catch (Exception e) {
                Log.e(TAG, "CameraEngine destroy failed", e);
            }
            cameraEngine = null;
        }

        synchronized (pendingEvents) {
            pendingEvents.clear();
        }
        if (ndviTextureEntry != null) {
            try {
                ndviTextureEntry.release();
            } catch (Exception ignored) {
            }
            ndviTextureEntry = null;
        }
        eventSink = null;
        super.onDestroy();
    }
}
