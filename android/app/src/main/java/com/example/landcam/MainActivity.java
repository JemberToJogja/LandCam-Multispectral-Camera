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
 *   - NFC foreground dispatch
 *   - permission requests
 *   - delegating camera work to CameraEngine
 *
 * Camera networking, discovery, image processing, NDVI, autofocus,
 * live-view and capture logic belong to CameraEngine and the other
 * dedicated native components.
 *
 * This class must stay a thin integration layer. It must not contain
 * camera-protocol or pixel-processing logic.
 */
public class MainActivity extends FlutterActivity
        implements CameraEngine.Listener {

    private static final String TAG = "LandCamMonitor";

    private static final String METHOD_CHANNEL =
            "landcam/native";

    private static final String EVENT_CHANNEL =
            "landcam/events";

    private static final int WIFI_PERMISSION_REQUEST = 100;

    private static final int GPU_NDVI_TEXTURE_WIDTH = 960;
    private static final int GPU_NDVI_TEXTURE_HEIGHT = 960;

    /**
     * EventSink is written from Flutter's stream lifecycle callbacks and read
     * from CameraEngine/background callbacks, so it must be safely published.
     */
    private volatile EventChannel.EventSink eventSink;

    /**
     * Non-liveview events can arrive before Flutter subscribes to the
     * EventChannel. Keep a bounded queue so startup/control events are not
     * lost. High-frequency preview frames are never buffered.
     */
    private final List<Map<String, Object>> pendingEvents =
            new ArrayList<>();

    private CameraEngine cameraEngine;

    /**
     * Owned by Flutter's TextureRegistry. CameraEngine receives only the
     * underlying SurfaceTexture and owns its Surface/GL resources.
     */
    private TextureRegistry.SurfaceTextureEntry ndviTextureEntry;

    private NfcAdapter nfcAdapter;

    private PendingIntent nfcPendingIntent;

    /**
     * Foreground dispatch is enabled only after Flutter explicitly requests
     * NFC listening. This avoids silently changing NFC state from onResume.
     */
    private boolean nfcDispatchEnabled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupFullscreen();
        setupNfc();
        checkPermissions();

        // Create the engine before handling an NFC intent so a launch caused
        // by an NFC tap cannot lose credentials before Flutter finishes
        // wiring the EventChannel.
        cameraEngine = new CameraEngine(this, this);

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
            public void onListen(
                    Object arguments,
                    EventChannel.EventSink events
            ) {
                eventSink = events;

                // These are deterministic channel/bootstrap notifications and
                // should be emitted before buffered application events.
                sendEventNow("ready", null);

                if (ndviTextureEntry != null) {
                    sendEventNow(
                            "gpuTextureReady",
                            mapOf(
                                    "textureId", ndviTextureEntry.id(),
                                    "width", GPU_NDVI_TEXTURE_WIDTH,
                                    "height", GPU_NDVI_TEXTURE_HEIGHT
                            )
                    );
                } else {
                    sendEventNow(
                            "gpuTextureUnavailable",
                            mapOf(
                                    "reason", "TEXTURE_SETUP_FAILED"
                            )
                    );
                }

                flushPendingEvents();
            }

            @Override
            public void onCancel(Object arguments) {
                eventSink = null;
            }
        });
    }

    private void handleMethodCall(
            MethodCall call,
            MethodChannel.Result result
    ) {
        try {
            switch (call.method) {
                case "initialize":
                    ensureEngine().initialize();
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

                /**
                 * Backward-compatible manual AF entry point.
                 * The current UI does not expose it because continuous AF is
                 * configured by CameraEngine when the camera connects.
                 */
                case "autofocus":
                    ensureEngine().triggerAutoFocus();
                    result.success(true);
                    return;

                /**
                 * Legacy compatibility methods. New Flutter code uses the
                 * explicit spectral/NDVI preview APIs instead.
                 */
                case "toggleViewMode":
                    result.success(ensureEngine().toggleViewMode());
                    return;

                case "setGrayscale":
                    result.success(
                            ensureEngine().setGrayscale(
                                    call.arguments instanceof Boolean
                                            && (Boolean) call.arguments
                            )
                    );
                    return;

                case "setSpectralBand":
                    result.success(
                            ensureEngine().setSpectralBand(
                                    call.arguments == null
                                            ? null
                                            : String.valueOf(call.arguments)
                            )
                    );
                    return;

                case "setCaptureMode": {
                    String mode = call.arguments == null
                            ? null
                            : String.valueOf(call.arguments);

                    if (mode == null) {
                        result.success(false);
                        return;
                    }

                    mode = mode.trim().toUpperCase(java.util.Locale.US);

                    if (!"RAW".equals(mode)
                            && !"PROCESSED".equals(mode)) {
                        result.success(false);
                        return;
                    }

                    result.success(
                            ensureEngine().setCaptureMode(mode)
                    );
                    return;
                }

                case "getCaptureMode":
                    result.success(ensureEngine().getCaptureMode());
                    return;

                case "setNdviEnabled":
                    if (!(call.arguments instanceof Boolean)) {
                        result.success(false);
                        return;
                    }

                    result.success(
                            ensureEngine().setNdviEnabled(
                                    (Boolean) call.arguments
                            )
                    );
                    return;

                case "getNdviEnabled":
                    result.success(ensureEngine().isNdviEnabled());
                    return;

                case "disconnect":
                    ensureEngine().disconnect();
                    result.success(true);
                    return;

                default:
                    result.notImplemented();
            }
        } catch (Exception e) {
            Log.e(TAG, "Method call failed: "
                    + (call == null ? "null" : call.method), e);

            result.error(
                    "NATIVE_ERROR",
                    safeMessage(e),
                    null
            );
        }
    }

    private CameraEngine ensureEngine() {
        if (cameraEngine == null) {
            cameraEngine = new CameraEngine(this, this);
        }
        return cameraEngine;
    }

    /**
     * Creates the single Flutter SurfaceTexture used by the native GPU NDVI
     * renderer.
     *
     * IMPORTANT:
     * Texture creation belongs to TextureRegistry, not FlutterRenderer itself.
     */
    private void ensureNdviTexture(FlutterEngine flutterEngine) {
        if (ndviTextureEntry != null) {
            return;
        }

        try {
            ndviTextureEntry =
                    flutterEngine
                            .getRenderer()
                            .createSurfaceTexture();

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

        nfcPendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                flags
        );
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
            sendEvent(
                    "error",
                    "NFC FOREGROUND DISPATCH FAILED"
            );
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
            rawMessages = intent.getParcelableArrayExtra(
                    NfcAdapter.EXTRA_NDEF_MESSAGES
            );
        } catch (Exception e) {
            rawMessages = null;
        }

        if (rawMessages == null || rawMessages.length == 0) {
            sendEvent(
                    "nfcDetected",
                    mapOf("payloadDetected", false)
            );
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

        sendEvent(
                "nfcDetected",
                mapOf("payloadDetected", payloadForwarded)
        );
    }

    // ---------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(
                    Manifest.permission.NEARBY_WIFI_DEVICES
            ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{
                                Manifest.permission.NEARBY_WIFI_DEVICES
                        },
                        WIFI_PERMISSION_REQUEST
                );
            }
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION
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

        if (requestCode != WIFI_PERMISSION_REQUEST) {
            return;
        }

        boolean granted =
                grantResults != null && grantResults.length > 0;

        if (granted) {
            for (int value : grantResults) {
                if (value != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
        }

        if (granted) {
            sendEvent("wifiPermission", true);
        } else {
            sendEvent("wifiPermission", false);
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

    /**
     * CameraEngine emits native events through this callback. MainActivity
     * remains the sole owner of Flutter's EventSink.
     */
    @Override
    public void onNativeEvent(String type, Object data) {
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

    // ---------------------------------------------------------------------
    // Flutter event bridge
    // ---------------------------------------------------------------------

    private void sendEvent(String type, Object data) {
        if (type == null || type.trim().isEmpty()) {
            return;
        }

        /**
         * Preview frames are high-frequency data-plane messages. Never buffer
         * them because queueing old frames would reintroduce visual lag and
         * can make a newly selected mode appear to flicker.
         */
        if ("liveviewFrame".equals(type)
                || "spectralFrame".equals(type)) {
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
            Object data = event.get("data");

            sendEventNow(
                    type == null ? "" : String.valueOf(type),
                    data
            );
        }
    }

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

        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }

        return message;
    }

    // ---------------------------------------------------------------------
    // Android lifecycle
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
