package com.example.landcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.view.TextureRegistry;

/**
 * LANDCAM Android entry point and Flutter/native bridge.
 * Camera processing and persistence are delegated to CameraEngine.
 *
 * Wi-Fi pairing currently uses CameraEngine's existing Sony credential parser
 * because its Wi-Fi request method is private in the existing engine snapshot.
 * The adapter deliberately accepts only Sony DIRECT-* networks with supported
 * WPA credentials; unsupported transport implementations are reported as such
 * instead of being reported as connected.
 */
public class MainActivity extends FlutterActivity implements CameraEngine.Listener {
    private static final String TAG = "LandCamMonitor";
    private static final String METHOD_CHANNEL = "landcam/native";
    private static final String EVENT_CHANNEL = "landcam/events";
    private static final String PREFS_NAME = "landcam_connection";
    private static final String PREF_PREFERRED_TRANSPORT = "preferred_transport";
    private static final int WIFI_PERMISSION_REQUEST = 100;
    private static final int BLUETOOTH_PERMISSION_REQUEST = 101;
    private static final int BLUETOOTH_ENABLE_REQUEST = 102;
    private static final long BLUETOOTH_SCAN_TIMEOUT_MS = 7000L;
    private static final long BLUETOOTH_CONNECT_TIMEOUT_MS = 18000L;
    private static final int GPU_NDVI_TEXTURE_WIDTH = 960;
    private static final int GPU_NDVI_TEXTURE_HEIGHT = 960;
    private static final String ACTION_USB_PERMISSION_SUFFIX = ".USB_PERMISSION";

    private static final Pattern SONY_SSID_PATTERN = Pattern.compile(
            "DIRECT-[A-Za-z0-9_-]+:[A-Za-z0-9_.-]{1,48}"
    );
    private static final Pattern SONY_PASSWORD_PATTERN = Pattern.compile("[A-Za-z0-9]{8,63}");

    private volatile EventChannel.EventSink eventSink;
    private final List<Map<String, Object>> pendingEvents = new ArrayList<>();
    private final AtomicReference<Map<String, Object>> pendingPreviewEvent = new AtomicReference<>();
    private final AtomicBoolean previewEventDispatchScheduled = new AtomicBoolean(false);

    private CameraEngine cameraEngine;
    private TextureRegistry.SurfaceTextureEntry ndviTextureEntry;
    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;
    private UsbManager usbManager;
    private BroadcastReceiver usbPermissionReceiver;
    private boolean usbPermissionReceiverRegistered;
    private volatile boolean nfcDispatchEnabled = false;
    private volatile boolean nfcStartRequested = false;
    private volatile boolean handlingProgrammaticWifiPairing = false;

    // CameraEngine is the source of truth; these fields form the bridge payload.
    private String advancedCaptureOutput = "PROCESSED";
    private String advancedPerformance = "PERFORMANCE";
    private Map<String, Object> advancedPerformanceConfig = defaultPerformanceConfig("PERFORMANCE");

    // Transport status is a bridge snapshot, not proof of hardware capability.
    private volatile String preferredTransport = "WIFI";
    private volatile String activeTransport = "WIFI";
    private volatile String transportStatus = "DISCONNECTED";
    private volatile String transportStatusReason = "NO ACTIVE CAMERA CONNECTION";

    // BLE transport state. A GATT link is transport connectivity only; it does
    // not imply that a camera-control protocol or live-view endpoint exists.
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private String bluetoothConnectedAddress;
    private final Handler bluetoothHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Map<String, Object>> bluetoothScanResults =
            new LinkedHashMap<>();

    private ScanCallback bluetoothScanCallback;
    private MethodChannel.Result pendingBluetoothScanResult;
    private Runnable bluetoothScanTimeout;

    private MethodChannel.Result pendingBluetoothConnectResult;
    private Runnable bluetoothConnectTimeout;

    // Permission/adapter prompts are asynchronous MethodChannel operations.
    private MethodChannel.Result pendingBluetoothPermissionResult;
    private Runnable pendingBluetoothPermissionAction;
    private Object pendingBluetoothPermissionFallback;

    private MethodChannel.Result pendingBluetoothEnableResult;
    private Runnable pendingBluetoothEnableAction;
    private Object pendingBluetoothEnableFallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupFullscreen();
        loadPreferredTransport();
        setupNfc();
        setupUsbPermissionReceiver();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
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
                flutterEngine.getDartExecutor().getBinaryMessenger(), METHOD_CHANNEL
        ).setMethodCallHandler(this::handleMethodCall);
        new EventChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(), EVENT_CHANNEL
        ).setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink events) {
                // Clear stale image metadata before publishing the new sink so a
                // fresh frame queued by the engine cannot be erased afterwards.
                pendingPreviewEvent.set(null);
                eventSink = events;
                sendEventNow("ready", null);
                if (ndviTextureEntry != null) {
                    sendEventNow("gpuTextureReady", mapOf(
                            "textureId", ndviTextureEntry.id(),
                            "width", GPU_NDVI_TEXTURE_WIDTH,
                            "height", GPU_NDVI_TEXTURE_HEIGHT
                    ));
                } else {
                    sendEventNow("gpuTextureUnavailable", mapOf("reason", "TEXTURE_SETUP_FAILED"));
                }
                syncAdvancedSettingsFromEngine();
                sendEvent("advancedSettingsChanged", buildAdvancedSettingsPayload());
                sendEvent("previewSettingsChanged", ensureEngine().getPreviewSettings());
                sendEvent("transportStatus", getTransportStatusPayload());
                flushPendingEvents();
            }

            @Override
            public void onCancel(Object arguments) {
                eventSink = null;
                pendingPreviewEvent.set(null);
            }
        });
    }

    // ---------------------------------------------------------------------
    // MethodChannel
    // ---------------------------------------------------------------------

    private void handleMethodCall(MethodCall call, MethodChannel.Result result) {
        if (call == null) {
            result.error("INVALID_CALL", "Method call was null", null);
            return;
        }
        try {
            switch (call.method) {
                case "initialize":
                    ensureEngine().initialize();
                    syncAdvancedSettingsFromEngine();
                    result.success(true);
                    return;
                case "startNfc":
                    // Quiet startup check: never open Android Settings automatically.
                    result.success(startNfcListening());
                    return;
                case "requestNfcPairing":
                    // Explicit user action: start NFC or open system NFC settings.
                    result.success(requestNfcPairing());
                    return;
                case "isNfcEnabled":
                    result.success(nfcAdapter != null && nfcAdapter.isEnabled());
                    return;
                case "isNfcListening":
                    result.success(nfcDispatchEnabled);
                    return;
                case "stopNfc":
                    stopNfcListening();
                    result.success(true);
                    return;
                case "connectWifi":
                case "connectWifiFromQr":
                case "connectWifiManually":
                    result.success(connectWifiFromArguments(call.arguments));
                    return;
                case "connectLastWifi":
                    result.success(connectLastWifi());
                    return;
                case "probeCurrentNetwork":
                case "scanNetwork":
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
                // Legacy two-state capture control remains for older Flutter callers.
                case "setCaptureMode": {
                    String mode = normalizeCaptureMode(
                            call.arguments == null ? null : String.valueOf(call.arguments)
                    );
                    if (mode == null) {
                        result.success(false);
                        return;
                    }
                    boolean accepted = ensureEngine().setCaptureMode(mode);
                    if (accepted) synchronizeAdvancedCaptureOutputFromLegacyMode(mode);
                    result.success(accepted);
                    return;
                }
                case "getCaptureMode":
                    result.success(ensureEngine().getCaptureMode());
                    return;
                // Preview layout is independent of the capture output saved to disk.
                case "setPreviewDisplayMode": {
                    Map<String, Object> payload = readMapArguments(call.arguments);
                    Object rawMode = payload == null ? call.arguments
                            : (payload.containsKey("mode") ? payload.get("mode")
                            : payload.get("previewDisplayMode"));
                    if (rawMode == null) {
                        result.success(false);
                        return;
                    }
                    boolean accepted = ensureEngine().setPreviewDisplayMode(String.valueOf(rawMode));
                    if (accepted) sendEvent("previewSettingsChanged", ensureEngine().getPreviewSettings());
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
                    result.success(payload != null && applyAdvancedSettingsPayload(payload));
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
                case "setPreferredTransport": {
                    String transport = readTransportArgument(call.arguments);
                    if (!isKnownTransport(transport)) {
                        result.success(false);
                        return;
                    }
                    preferredTransport = transport;
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putString(PREF_PREFERRED_TRANSPORT, preferredTransport).apply();
                    sendEvent("preferredTransportChanged", preferredTransport);
                    result.success(true);
                    return;
                }
                case "connectBluetooth":
                    connectBluetoothDevice(call.arguments, result);
                    return;
                case "connectUsbPtp":
                    result.success(reportUnsupportedTransport("USB_PTP", "USB_PTP_SESSION_NOT_INTEGRATED"));
                    return;
                case "connectHdmiUvc":
                    result.success(reportUnsupportedTransport("HDMI_UVC", "UVC_VIDEO_PIPELINE_NOT_INTEGRATED"));
                    return;
                case "scanBluetoothDevices":
                    scanBluetoothDevices(result);
                    return;
                case "scanUsbDevices":
                    result.success(scanUsbDevices());
                    return;
                case "requestUsbPermission":
                    result.success(requestUsbPermission(call.arguments));
                    return;
                case "disconnectTransport": {
                    String transport = readTransportArgument(call.arguments);
                    if (transport == null || "WIFI".equals(transport)) {
                        ensureEngine().disconnect();
                        activeTransport = "WIFI";
                        transportStatus = "DISCONNECTING";
                        transportStatusReason = "DISCONNECT REQUESTED";
                        sendEvent("transportStatus", getTransportStatusPayload());
                        result.success(true);
                    } else if ("BLUETOOTH".equals(transport)) {
                        result.success(disconnectBluetooth());
                    } else {
                        result.success(reportUnsupportedTransport(transport, "TRANSPORT_DISCONNECT_NOT_INTEGRATED"));
                    }
                    return;
                }
                case "getTransportStatus":
                    result.success(getTransportStatusPayload());
                    return;
                case "getTransportCapabilities":
                    result.success(getTransportCapabilities(call.arguments));
                    return;
                case "getConnectionDiagnostics":
                    result.success(getConnectionDiagnostics());
                    return;
                case "disconnect":
                    if ("BLUETOOTH".equals(activeTransport)) {
                        result.success(disconnectBluetooth());
                    } else {
                        ensureEngine().disconnect();
                        activeTransport = "WIFI";
                        transportStatus = "DISCONNECTING";
                        transportStatusReason = "DISCONNECT REQUESTED";
                        sendEvent("transportStatus", getTransportStatusPayload());
                        result.success(true);
                    }
                    return;
                default:
                    result.notImplemented();
            }
        } catch (Exception e) {
            Log.e(TAG, "Method call failed: " + call.method, e);
            result.error("NATIVE_ERROR", safeMessage(e), null);
        }
    }

    // ---------------------------------------------------------------------
    // Bluetooth Low Energy (BLE) discovery and GATT connection
    // ---------------------------------------------------------------------

    private BluetoothAdapter getBluetoothAdapterSafe() {
        if (bluetoothAdapter != null) return bluetoothAdapter;

        BluetoothManager manager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager != null
                ? manager.getAdapter()
                : BluetoothAdapter.getDefaultAdapter();
        return bluetoothAdapter;
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBluetoothScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isBluetoothEnabledSafe() {
        BluetoothAdapter adapter = getBluetoothAdapterSafe();
        if (adapter == null) return false;
        try {
            return adapter.isEnabled();
        } catch (SecurityException error) {
            return false;
        }
    }

    /** Request the API-level-appropriate runtime permissions before using BLE. */
    private void runBluetoothOperationWithPermissions(
            MethodChannel.Result result,
            Object fallback,
            boolean needsScan,
            Runnable onGranted
    ) {
        List<String> missing = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (needsScan
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else if (needsScan
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (missing.isEmpty()) {
            try {
                onGranted.run();
            } catch (Exception error) {
                Log.e(TAG, "Bluetooth operation could not start", error);
                sendEvent("engineWarning",
                        "BLUETOOTH OPERATION FAILED: " + safeMessage(error));
                // Most actions either finish synchronously or retain the result
                // for an asynchronous scan/GATT callback. Do not complete it a
                // second time here if an async operation has already retained it.
            }
            return;
        }

        if (pendingBluetoothPermissionResult != null) {
            sendEvent("engineWarning", "BLUETOOTH PERMISSION REQUEST ALREADY IN PROGRESS");
            result.success(fallback);
            return;
        }

        pendingBluetoothPermissionResult = result;
        pendingBluetoothPermissionAction = onGranted;
        pendingBluetoothPermissionFallback = fallback;

        try {
            requestPermissions(
                    missing.toArray(new String[0]),
                    BLUETOOTH_PERMISSION_REQUEST
            );
        } catch (Exception error) {
            pendingBluetoothPermissionResult = null;
            pendingBluetoothPermissionAction = null;
            pendingBluetoothPermissionFallback = null;
            sendEvent("engineWarning",
                    "COULD NOT REQUEST BLUETOOTH PERMISSIONS: " + safeMessage(error));
            result.success(fallback);
        }
    }

    /** Android does not allow apps to silently switch Bluetooth on. */
    private void runWhenBluetoothEnabled(
            MethodChannel.Result result,
            Object fallback,
            Runnable onEnabled
    ) {
        BluetoothAdapter adapter = getBluetoothAdapterSafe();
        if (adapter == null) {
            sendEvent("engineWarning", "THIS DEVICE DOES NOT SUPPORT BLUETOOTH");
            result.success(fallback);
            return;
        }

        final boolean enabled;
        try {
            enabled = adapter.isEnabled();
        } catch (SecurityException error) {
            sendEvent("engineWarning", "BLUETOOTH CONNECT PERMISSION IS REQUIRED");
            result.success(fallback);
            return;
        }

        if (enabled) {
            onEnabled.run();
            return;
        }

        if (pendingBluetoothEnableResult != null) {
            sendEvent("engineWarning", "BLUETOOTH ACTIVATION REQUEST ALREADY IN PROGRESS");
            result.success(fallback);
            return;
        }

        pendingBluetoothEnableResult = result;
        pendingBluetoothEnableAction = onEnabled;
        pendingBluetoothEnableFallback = fallback;

        try {
            startActivityForResult(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    BLUETOOTH_ENABLE_REQUEST
            );
        } catch (Exception error) {
            pendingBluetoothEnableResult = null;
            pendingBluetoothEnableAction = null;
            pendingBluetoothEnableFallback = null;
            sendEvent("engineWarning",
                    "COULD NOT REQUEST BLUETOOTH ACTIVATION: " + safeMessage(error));
            result.success(fallback);
        }
    }

    private void scanBluetoothDevices(MethodChannel.Result result) {
        List<Map<String, Object>> fallback = new ArrayList<>();
        runBluetoothOperationWithPermissions(
                result,
                fallback,
                true,
                () -> runWhenBluetoothEnabled(
                        result,
                        fallback,
                        () -> beginBluetoothScan(result)
                )
        );
    }

    @SuppressLint("MissingPermission")
    private void beginBluetoothScan(MethodChannel.Result result) {
        BluetoothAdapter adapter = getBluetoothAdapterSafe();
        if (adapter == null) {
            sendEvent("engineWarning", "THIS DEVICE DOES NOT SUPPORT BLUETOOTH");
            result.success(new ArrayList<Map<String, Object>>());
            return;
        }
        if (pendingBluetoothScanResult != null) {
            sendEvent("engineWarning", "A BLUETOOTH SCAN IS ALREADY RUNNING");
            result.success(snapshotBluetoothScanResults());
            return;
        }

        synchronized (bluetoothScanResults) {
            bluetoothScanResults.clear();
        }

        // Include already-bonded LE and dual-mode devices. Classic-only devices
        // are omitted because this implementation connects with BLE/GATT.
        try {
            Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
            for (BluetoothDevice device : bondedDevices) {
                Map<String, Object> item = bluetoothDevicePayload(
                        device, false, null, null);
                if (item != null) {
                    synchronized (bluetoothScanResults) {
                        bluetoothScanResults.put(
                                String.valueOf(item.get("address")), item);
                    }
                }
            }
        } catch (SecurityException error) {
            sendEvent("engineWarning",
                    "COULD NOT READ PAIRED BLUETOOTH DEVICES: " + safeMessage(error));
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            List<Map<String, Object>> devices = snapshotBluetoothScanResults();
            sendEvent("bluetoothDevices", devices);
            result.success(devices);
            return;
        }

        final BluetoothLeScanner scanner;
        try {
            scanner = adapter.getBluetoothLeScanner();
        } catch (SecurityException error) {
            sendEvent("engineWarning", "BLUETOOTH SCAN PERMISSION IS REQUIRED");
            List<Map<String, Object>> devices = snapshotBluetoothScanResults();
            sendEvent("bluetoothDevices", devices);
            result.success(devices);
            return;
        }

        if (scanner == null) {
            sendEvent("engineWarning", "BLUETOOTH LE SCANNER IS UNAVAILABLE");
            List<Map<String, Object>> devices = snapshotBluetoothScanResults();
            sendEvent("bluetoothDevices", devices);
            result.success(devices);
            return;
        }

        bluetoothLeScanner = scanner;
        pendingBluetoothScanResult = result;

        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult scanResult) {
                if (scanResult == null || scanResult.getDevice() == null) return;
                bluetoothHandler.post(() -> {
                    if (bluetoothScanCallback != this) return;
                    String advertisedName = null;
                    if (scanResult.getScanRecord() != null) {
                        advertisedName = scanResult.getScanRecord().getDeviceName();
                    }
                    Map<String, Object> item = bluetoothDevicePayload(
                            scanResult.getDevice(), true,
                            scanResult.getRssi(), advertisedName);
                    if (item == null) return;
                    synchronized (bluetoothScanResults) {
                        bluetoothScanResults.put(
                                String.valueOf(item.get("address")), item);
                    }
                });
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                if (results == null) return;
                for (ScanResult scanResult : results) {
                    onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, scanResult);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                bluetoothHandler.post(() -> {
                    if (bluetoothScanCallback != this) return;
                    finishBluetoothScan("BLUETOOTH LE SCAN FAILED: " + errorCode);
                });
            }
        };

        bluetoothScanCallback = callback;
        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, callback);
            sendEvent("log", mapOf(
                    "level", "INFO",
                    "message", "Bluetooth LE scan started"
            ));
            bluetoothScanTimeout = () -> finishBluetoothScan(null);
            bluetoothHandler.postDelayed(
                    bluetoothScanTimeout, BLUETOOTH_SCAN_TIMEOUT_MS);
        } catch (Exception error) {
            Log.e(TAG, "Bluetooth LE scan could not start", error);
            finishBluetoothScan(
                    "BLUETOOTH SCAN ERROR: " + safeMessage(error));
        }
    }

    @SuppressLint("MissingPermission")
    private Map<String, Object> bluetoothDevicePayload(
            BluetoothDevice device,
            boolean foundByBleScan,
            Integer rssi,
            String advertisedName
    ) {
        if (device == null) return null;
        try {
            int type = device.getType();
            if (!foundByBleScan && type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                return null;
            }

            String address = device.getAddress();
            if (address == null || address.trim().isEmpty()) return null;

            String name = safeBluetoothDeviceName(device);
            if (name == null || name.trim().isEmpty()) name = advertisedName;
            if (name == null || name.trim().isEmpty()) {
                name = "BLE device " + address.substring(
                        Math.max(0, address.length() - 5));
            }

            String deviceType;
            switch (type) {
                case BluetoothDevice.DEVICE_TYPE_LE:
                    deviceType = "LE";
                    break;
                case BluetoothDevice.DEVICE_TYPE_DUAL:
                    deviceType = "DUAL";
                    break;
                case BluetoothDevice.DEVICE_TYPE_CLASSIC:
                    deviceType = "CLASSIC";
                    break;
                default:
                    deviceType = "UNKNOWN";
                    break;
            }

            Map<String, Object> item = new HashMap<>();
            item.put("deviceId", address);
            item.put("address", address);
            item.put("name", name);
            item.put("paired", device.getBondState() == BluetoothDevice.BOND_BONDED);
            item.put("deviceType", deviceType);
            item.put("transport", "BLE");
            item.put("discoveredByScan", foundByBleScan);
            if (rssi != null) item.put("rssi", rssi);
            return item;
        } catch (SecurityException error) {
            sendEvent("engineWarning",
                    "BLUETOOTH PERMISSION ERROR WHILE READING DEVICE: " + safeMessage(error));
            return null;
        } catch (Exception error) {
            Log.w(TAG, "Could not read Bluetooth device", error);
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private String safeBluetoothDeviceName(BluetoothDevice device) {
        try {
            String name = device == null ? null : device.getName();
            return name == null ? "" : name;
        } catch (Exception ignored) {
            return "";
        }
    }

    private List<Map<String, Object>> snapshotBluetoothScanResults() {
        synchronized (bluetoothScanResults) {
            return new ArrayList<>(bluetoothScanResults.values());
        }
    }

    @SuppressLint("MissingPermission")
    private void finishBluetoothScan(String warning) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            bluetoothHandler.post(() -> finishBluetoothScan(warning));
            return;
        }

        if (bluetoothScanTimeout != null) {
            bluetoothHandler.removeCallbacks(bluetoothScanTimeout);
            bluetoothScanTimeout = null;
        }

        BluetoothLeScanner scanner = bluetoothLeScanner;
        ScanCallback callback = bluetoothScanCallback;
        bluetoothLeScanner = null;
        bluetoothScanCallback = null;

        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback);
            } catch (Exception error) {
                Log.w(TAG, "Stopping Bluetooth scan failed", error);
            }
        }

        MethodChannel.Result result = pendingBluetoothScanResult;
        pendingBluetoothScanResult = null;
        List<Map<String, Object>> devices = snapshotBluetoothScanResults();

        if (warning != null && !warning.trim().isEmpty()) {
            sendEvent("engineWarning", warning);
        } else if (devices.isEmpty()) {
            sendEvent("engineWarning",
                    "NO BLE DEVICES FOUND. PUT THE CAMERA IN BLUETOOTH PAIRING MODE AND SCAN AGAIN.");
        }

        sendEvent("bluetoothDevices", devices);
        sendEvent("log", mapOf(
                "level", devices.isEmpty() ? "WARN" : "INFO",
                "message", "Bluetooth LE scan finished: " + devices.size() + " device(s)"
        ));
        if (result != null) result.success(devices);
    }

    private void connectBluetoothDevice(Object arguments, MethodChannel.Result result) {
        Map<String, Object> payload = readMapArguments(arguments);
        Object rawId = payload == null ? arguments : payload.get("deviceId");
        if (rawId == null && payload != null) rawId = payload.get("address");
        String address = stringValue(rawId);

        if (address == null || !address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
            sendEvent("engineWarning", "BLUETOOTH CONNECTION REQUIRES A VALID DEVICE ADDRESS");
            result.success(false);
            return;
        }

        runBluetoothOperationWithPermissions(
                result,
                false,
                false,
                () -> runWhenBluetoothEnabled(
                        result,
                        false,
                        () -> beginBluetoothGattConnection(address, result)
                )
        );
    }

    @SuppressLint("MissingPermission")
    private void beginBluetoothGattConnection(
            String address,
            MethodChannel.Result result
    ) {
        if (pendingBluetoothConnectResult != null) {
            sendEvent("engineWarning", "A BLUETOOTH CONNECTION ATTEMPT IS ALREADY IN PROGRESS");
            result.success(false);
            return;
        }

        BluetoothAdapter adapter = getBluetoothAdapterSafe();
        if (adapter == null) {
            sendEvent("engineWarning", "THIS DEVICE DOES NOT SUPPORT BLUETOOTH");
            result.success(false);
            return;
        }

        final BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (Exception error) {
            sendEvent("engineWarning", "INVALID BLUETOOTH DEVICE: " + safeMessage(error));
            result.success(false);
            return;
        }

        // Replacing a previous BLE link is explicit; it does not disconnect the
        // independent CameraEngine/Wi-Fi session.
        closeBluetoothGattQuietly();
        pendingBluetoothConnectResult = result;
        activeTransport = "BLUETOOTH";
        transportStatus = "CONNECTING";
        transportStatusReason = "CONNECTING TO BLE DEVICE " + address;
        sendEvent("transportStatus", getTransportStatusPayload());
        sendEvent("log", mapOf(
                "level", "INFO",
                "message", "Connecting to Bluetooth LE device " + address
        ));

        BluetoothGattCallback callback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(
                    BluetoothGatt gatt,
                    int status,
                    int newState
            ) {
                bluetoothHandler.post(() -> {
                    if (bluetoothGatt != gatt) {
                        try { gatt.close(); } catch (Exception ignored) { }
                        return;
                    }

                    if (status == BluetoothGatt.GATT_SUCCESS
                            && newState == BluetoothProfile.STATE_CONNECTED) {
                        bluetoothConnectedAddress = address;
                        Log.i(TAG, "BLE GATT connected: address=" + address
                                + ", name=" + safeBluetoothDeviceName(device));
                        activeTransport = "BLUETOOTH";
                        transportStatus = "CONNECTED";
                        transportStatusReason =
                                "BLE LINK CONNECTED; CAMERA CONTROL NOT VERIFIED";
                        sendEvent("transportStatus", getTransportStatusPayload());
                        sendEvent("transportCapabilities", getTransportCapabilities("BLUETOOTH"));
                        sendEvent("bluetoothConnected", mapOf(
                                "address", address,
                                "name", safeBluetoothDeviceName(device),
                                "gattConnected", true,
                                "bleConnected", true,
                                "connectionMode", "BLE_GATT",
                                "cameraControlVerified", false
                        ));
                        sendEvent("log", mapOf(
                                "level", "INFO",
                                "message", "BLE GATT link connected; this does not confirm Sony camera-control services"
                        ));
                        finishPendingBluetoothConnect(true);

                        try {
                            gatt.discoverServices();
                        } catch (Exception error) {
                            sendEvent("engineWarning",
                                    "BLE CONNECTED, BUT SERVICE DISCOVERY FAILED: " + safeMessage(error));
                        }
                        return;
                    }

                    if (newState == BluetoothProfile.STATE_DISCONNECTED
                            || status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "BLE GATT state changed: address=" + address
                                + ", status=" + status + ", newState=" + newState);
                        sendEvent("log", mapOf(
                                "level", status == BluetoothGatt.GATT_SUCCESS ? "INFO" : "ERROR",
                                "message", "BLE GATT state change: address=" + address
                                        + ", status=" + status + ", newState=" + newState
                        ));
                        boolean wasConnecting = pendingBluetoothConnectResult != null;
                        cancelBluetoothConnectTimeout();
                        bluetoothGatt = null;
                        bluetoothConnectedAddress = null;
                        try { gatt.close(); } catch (Exception ignored) { }

                        activeTransport = "BLUETOOTH";
                        transportStatus = wasConnecting ? "ERROR" : "DISCONNECTED";
                        transportStatusReason = wasConnecting
                                ? "BLE CONNECTION FAILED (GATT STATUS " + status + ")"
                                : "BLE CONNECTION LOST (GATT STATUS " + status + ")";
                        sendEvent("transportStatus", getTransportStatusPayload());
                        sendEvent("transportCapabilities", getTransportCapabilities("BLUETOOTH"));
                        sendEvent("bluetoothDisconnected", mapOf(
                                "address", address,
                                "status", status,
                                "wasConnecting", wasConnecting
                        ));
                        sendEvent("engineWarning", transportStatusReason);
                        if (wasConnecting) finishPendingBluetoothConnect(false);
                    }
                });
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                bluetoothHandler.post(() -> {
                    if (bluetoothGatt != gatt) return;
                    List<String> serviceUuids = new ArrayList<>();
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        for (BluetoothGattService service : gatt.getServices()) {
                            serviceUuids.add(service.getUuid().toString());
                        }
                    }
                    sendEvent("bluetoothServices", mapOf(
                            "address", address,
                            "success", status == BluetoothGatt.GATT_SUCCESS,
                            "status", status,
                            "services", serviceUuids,
                            "connectionMode", "BLE_GATT",
                            "gattConnected", bluetoothGatt == gatt
                    ));
                    sendEvent("log", mapOf(
                            "level", status == BluetoothGatt.GATT_SUCCESS ? "INFO" : "WARN",
                            "message", "BLE GATT service discovery status=" + status
                                    + ", services=" + serviceUuids.size()
                    ));
                });
            }
        };

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bluetoothGatt = device.connectGatt(
                        this, false, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                bluetoothGatt = device.connectGatt(this, false, callback);
            }

            if (bluetoothGatt == null) {
                finishPendingBluetoothConnect(false);
                transportStatus = "ERROR";
                transportStatusReason = "BLUETOOTH GATT CONNECTION COULD NOT START";
                sendEvent("transportStatus", getTransportStatusPayload());
                sendEvent("engineWarning", transportStatusReason);
                return;
            }

            bluetoothConnectTimeout = () -> {
                if (pendingBluetoothConnectResult != result) return;
                finishPendingBluetoothConnect(false);
                closeBluetoothGattQuietly();
                activeTransport = "BLUETOOTH";
                transportStatus = "ERROR";
                transportStatusReason = "BLUETOOTH GATT CONNECTION TIMED OUT";
                sendEvent("transportStatus", getTransportStatusPayload());
                sendEvent("transportCapabilities", getTransportCapabilities("BLUETOOTH"));
                sendEvent("engineWarning", transportStatusReason);
            };
            bluetoothHandler.postDelayed(
                    bluetoothConnectTimeout, BLUETOOTH_CONNECT_TIMEOUT_MS);
        } catch (Exception error) {
            finishPendingBluetoothConnect(false);
            closeBluetoothGattQuietly();
            activeTransport = "BLUETOOTH";
            transportStatus = "ERROR";
            transportStatusReason = "BLUETOOTH GATT CONNECTION FAILED: " + safeMessage(error);
            sendEvent("transportStatus", getTransportStatusPayload());
            sendEvent("transportCapabilities", getTransportCapabilities("BLUETOOTH"));
            sendEvent("engineWarning", transportStatusReason);
        }
    }

    private void cancelBluetoothConnectTimeout() {
        if (bluetoothConnectTimeout != null) {
            bluetoothHandler.removeCallbacks(bluetoothConnectTimeout);
            bluetoothConnectTimeout = null;
        }
    }

    private void finishPendingBluetoothConnect(boolean connected) {
        cancelBluetoothConnectTimeout();
        MethodChannel.Result result = pendingBluetoothConnectResult;
        pendingBluetoothConnectResult = null;
        if (result != null) result.success(connected);
    }

    @SuppressLint("MissingPermission")
    private void closeBluetoothGattQuietly() {
        BluetoothGatt current = bluetoothGatt;
        bluetoothGatt = null;
        bluetoothConnectedAddress = null;
        if (current == null) return;
        try { current.disconnect(); } catch (Exception ignored) { }
        try { current.close(); } catch (Exception ignored) { }
    }

    private boolean disconnectBluetooth() {
        if (pendingBluetoothScanResult != null) {
            finishBluetoothScan("BLUETOOTH SCAN CANCELLED BY DISCONNECT");
        }
        if (pendingBluetoothConnectResult != null) {
            finishPendingBluetoothConnect(false);
        }
        closeBluetoothGattQuietly();
        activeTransport = "BLUETOOTH";
        transportStatus = "DISCONNECTED";
        transportStatusReason = "BLUETOOTH LE DISCONNECTED";
        sendEvent("transportStatus", getTransportStatusPayload());
        sendEvent("transportCapabilities", getTransportCapabilities("BLUETOOTH"));
        sendEvent("bluetoothDisconnected", mapOf(
                "address", "",
                "status", BluetoothGatt.GATT_SUCCESS,
                "wasConnecting", false
        ));
        sendEvent("log", mapOf(
                "level", "INFO",
                "message", "Bluetooth LE disconnected"
        ));
        return true;
    }

    private void cleanupBluetooth() {
        if (bluetoothScanTimeout != null) {
            bluetoothHandler.removeCallbacks(bluetoothScanTimeout);
            bluetoothScanTimeout = null;
        }
        BluetoothLeScanner scanner = bluetoothLeScanner;
        ScanCallback callback = bluetoothScanCallback;
        bluetoothLeScanner = null;
        bluetoothScanCallback = null;
        if (scanner != null && callback != null) {
            try { scanner.stopScan(callback); } catch (Exception ignored) { }
        }
        MethodChannel.Result scanResult = pendingBluetoothScanResult;
        pendingBluetoothScanResult = null;
        if (scanResult != null) {
            try { scanResult.success(new ArrayList<Map<String, Object>>()); }
            catch (Exception ignored) { }
        }
        if (pendingBluetoothConnectResult != null) {
            try { finishPendingBluetoothConnect(false); } catch (Exception ignored) { }
        } else {
            cancelBluetoothConnectTimeout();
        }
        closeBluetoothGattQuietly();

        MethodChannel.Result permissionResult = pendingBluetoothPermissionResult;
        Object permissionFallback = pendingBluetoothPermissionFallback;
        pendingBluetoothPermissionResult = null;
        pendingBluetoothPermissionAction = null;
        pendingBluetoothPermissionFallback = null;
        if (permissionResult != null) {
            try { permissionResult.success(permissionFallback); }
            catch (Exception ignored) { }
        }

        MethodChannel.Result enableResult = pendingBluetoothEnableResult;
        Object enableFallback = pendingBluetoothEnableFallback;
        pendingBluetoothEnableResult = null;
        pendingBluetoothEnableAction = null;
        pendingBluetoothEnableFallback = null;
        if (enableResult != null) {
            try { enableResult.success(enableFallback); }
            catch (Exception ignored) { }
        }
        bluetoothHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != BLUETOOTH_ENABLE_REQUEST) return;

        MethodChannel.Result result = pendingBluetoothEnableResult;
        Runnable action = pendingBluetoothEnableAction;
        Object fallback = pendingBluetoothEnableFallback;
        pendingBluetoothEnableResult = null;
        pendingBluetoothEnableAction = null;
        pendingBluetoothEnableFallback = null;

        boolean enabled = resultCode == RESULT_OK && isBluetoothEnabledSafe();
        if (enabled && action != null) {
            try {
                action.run();
            } catch (Exception error) {
                sendEvent("engineWarning",
                        "BLUETOOTH OPERATION FAILED AFTER ENABLE: " + safeMessage(error));
                if (result != null) result.success(fallback);
            }
        } else {
            sendEvent("engineWarning", "BLUETOOTH WAS NOT ENABLED");
            if (result != null) result.success(fallback);
        }
    }

    private boolean connectLastWifi() {
        boolean accepted = ensureEngine().connectLastWifi();
        if (accepted) {
            activeTransport = "WIFI";
            transportStatus = "CONNECTING";
            transportStatusReason = "SAVED CAMERA WI-FI REQUESTED";
            sendEvent("transportStatus", getTransportStatusPayload());
        } else {
            transportStatus = "ERROR";
            transportStatusReason = "NO SAVED CAMERA NETWORK OR REQUEST REJECTED";
            sendEvent("transportStatus", getTransportStatusPayload());
        }
        return accepted;
    }

    /**
     * Reuses the current engine's Sony credential parser, which saves the
     * credentials for connectLastWifi() and starts the existing Wi-Fi flow.
     * The synthetic payload uses an explicit "password:" field accepted by
     * CameraEngine.parseCredentialsFromText().
     */
    private boolean connectWifiFromArguments(Object arguments) {
        Map<String, Object> payload = readMapArguments(arguments);
        if (payload == null) {
            sendEvent("engineWarning", "WI-FI CREDENTIALS MUST BE A MAP");
            return false;
        }

        String pairingMethod = stringValue(payload.get("pairingMethod"));
        if ("SAVED_WIFI".equals(normalizeIdentifier(pairingMethod))) {
            return connectLastWifi();
        }

        String ssid = stringValue(payload.get("ssid"));
        String password = stringValue(payload.get("password"));
        String security = normalizeIdentifier(stringValue(payload.get("securityType")));

        if (ssid == null || ssid.trim().isEmpty() || ssid.length() > 32) {
            sendEvent("engineWarning", "INVALID CAMERA SSID");
            return false;
        }
        if (!SONY_SSID_PATTERN.matcher(ssid).matches()) {
            sendEvent("engineWarning", "CURRENT CAMERA ENGINE ACCEPTS SONY DIRECT-* SSIDS ONLY");
            return false;
        }
        if (security != null && !security.isEmpty()
                && !("WPA2".equals(security) || "WPA".equals(security)
                || "WPA_PSK".equals(security) || "WPA2_PSK".equals(security)
                || "PSK".equals(security))) {
            sendEvent("engineWarning", "UNSUPPORTED CAMERA WI-FI SECURITY TYPE");
            return false;
        }
        if (password == null || !SONY_PASSWORD_PATTERN.matcher(password).matches()) {
            sendEvent("engineWarning", "SONY CAMERA PASSWORD MUST BE 8-63 ALPHANUMERIC CHARACTERS");
            return false;
        }

        String credentialPayload = ssid + " password:" + password;
        try {
            // Avoid presenting a programmatic QR/manual pairing as an NFC tap.
            handlingProgrammaticWifiPairing = true;
            ensureEngine().handleNfcPayload(credentialPayload.getBytes(StandardCharsets.UTF_8));
            activeTransport = "WIFI";
            transportStatus = "CONNECTING";
            transportStatusReason = "CAMERA WI-FI REQUESTED";
            sendEvent("transportStatus", getTransportStatusPayload());
            return true; // Request accepted; connection readiness arrives asynchronously.
        } catch (Exception e) {
            Log.e(TAG, "Could not start Sony Wi-Fi connection", e);
            transportStatus = "ERROR";
            transportStatusReason = safeMessage(e);
            sendEvent("transportStatus", getTransportStatusPayload());
            sendEvent("error", "WI-FI CONNECTION REQUEST FAILED: " + safeMessage(e));
            return false;
        } finally {
            handlingProgrammaticWifiPairing = false;
        }
    }

    private boolean reportUnsupportedTransport(String transport, String reason) {
        String normalized = normalizeTransport(transport);
        if (normalized == null) normalized = "UNKNOWN";
        activeTransport = normalized;
        transportStatus = "UNSUPPORTED";
        transportStatusReason = reason;
        Map<String, Object> state = getTransportStatusPayload();
        state.put("transport", normalized);
        state.put("reason", reason);
        sendEvent("transportStatus", state);
        sendEvent("engineWarning", reason);
        Log.w(TAG, "Transport not available: " + normalized + " (" + reason + ")");
        return false;
    }

    private Map<String, Object> getTransportStatusPayload() {
        HashMap<String, Object> payload = new HashMap<>();
        payload.put("preferredTransport", preferredTransport);
        payload.put("activeTransport", activeTransport);
        payload.put("transport", activeTransport);
        payload.put("status", transportStatus);
        payload.put("connected", "CONNECTED".equals(transportStatus)
                || "STREAMING".equals(transportStatus) || "READY".equals(transportStatus));
        payload.put("reason", transportStatusReason);
        payload.put("connectionMode", "BLUETOOTH".equals(activeTransport)
                ? "BLE_GATT" : "WIFI_CAMERA_API");
        payload.put("bleConnected", "BLUETOOTH".equals(activeTransport)
                && bluetoothGatt != null && bluetoothConnectedAddress != null
                && "CONNECTED".equals(transportStatus));
        if (bluetoothConnectedAddress != null) {
            payload.put("bluetoothAddress", bluetoothConnectedAddress);
        }
        return payload;
    }

    private Map<String, Object> getTransportCapabilities(Object arguments) {
        String transport = readTransportArgument(arguments);
        if (transport == null) transport = activeTransport;
        HashMap<String, Object> capabilities = new HashMap<>();
        capabilities.put("transport", transport);
        boolean wifi = "WIFI".equals(transport);
        boolean bluetooth = "BLUETOOTH".equals(transport)
                && bluetoothGatt != null
                && bluetoothConnectedAddress != null
                && "CONNECTED".equals(transportStatus);
        capabilities.put("supported", wifi || bluetooth);
        capabilities.put("canControlCamera", wifi);
        capabilities.put("canReceiveLiveView", wifi);
        capabilities.put("canCapturePhoto", wifi);
        capabilities.put("bleConnected", bluetooth);
        capabilities.put("bluetoothAddress", bluetoothConnectedAddress == null ? "" : bluetoothConnectedAddress);
        capabilities.put("canTransferFiles", false);
        // The stock Sony RGB live-view pipeline does not prove that a real NIR
        // channel or aligned RGB/NIR optical ROI exists.
        capabilities.put("hasRedChannel", wifi);
        capabilities.put("hasNirChannel", false);
        capabilities.put("hasAlignedRedNirPair", false);
        capabilities.put("canCalculateNdvi", false);
        capabilities.put("reason", wifi
                ? "CAPABILITIES DEPEND ON THE CONNECTED CAMERA API"
                : bluetooth
                    ? "BLE LINK CONNECTED; CAMERA CONTROL AND LIVE VIEW ARE NOT VERIFIED"
                    : "BLUETOOTH".equals(transport)
                        ? "BLE DISCOVERY AND GATT CONNECTION ARE AVAILABLE; NO BLE LINK IS CONNECTED"
                        : "TRANSPORT IMPLEMENTATION NOT INTEGRATED");
        return capabilities;
    }

    private Map<String, Object> getConnectionDiagnostics() {
        HashMap<String, Object> result = new HashMap<>();
        result.put("sdkInt", Build.VERSION.SDK_INT);
        result.put("preferredTransport", preferredTransport);
        result.put("activeTransport", activeTransport);
        result.put("transportStatus", transportStatus);
        result.put("transportStatusReason", transportStatusReason);
        result.put("nfcSupported", nfcAdapter != null);
        result.put("nfcEnabled", nfcAdapter != null && nfcAdapter.isEnabled());
        result.put("usbHostSupported", getPackageManager().hasSystemFeature("android.hardware.usb.host"));
        result.put("nearbyWifiPermissionGranted", hasWifiPermission());
        result.put("bluetoothSupported", getBluetoothAdapterSafe() != null);
        result.put("bluetoothEnabled", isBluetoothEnabledSafe());
        result.put("bluetoothScanPermissionGranted", hasBluetoothScanPermission());
        result.put("bluetoothConnectPermissionGranted", hasBluetoothConnectPermission());
        result.put("bluetoothGattConnected", bluetoothGatt != null && bluetoothConnectedAddress != null);
        result.put("bluetoothAddress", bluetoothConnectedAddress == null ? "" : bluetoothConnectedAddress);
        result.put("cameraEngineInitialized", cameraEngine != null);
        result.put("ndviTextureAvailable", ndviTextureEntry != null);
        synchronized (pendingEvents) {
            result.put("pendingEventCount", pendingEvents.size());
        }
        return result;
    }

    private String readTransportArgument(Object arguments) {
        if (arguments instanceof Map) {
            Map<String, Object> map = readMapArguments(arguments);
            Object value = map.get("transport");
            if (value == null) value = map.get("type");
            if (value == null) value = map.get("preferredTransport");
            return normalizeTransport(stringValue(value));
        }
        return normalizeTransport(stringValue(arguments));
    }

    private String normalizeTransport(String value) {
        if (value == null) return null;
        String normalized = normalizeIdentifier(value);
        switch (normalized) {
            case "WIFI":
            case "BLUETOOTH":
                return normalized;
            case "USB":
            case "USBPTP":
            case "USB_PTP":
            case "PTP":
                return "USB_PTP";
            case "HDMI":
            case "UVC":
            case "HDMIUVC":
            case "HDMI_UVC":
                return "HDMI_UVC";
            default:
                return null;
        }
    }

    private boolean isKnownTransport(String value) {
        return normalizeTransport(value) != null;
    }

    private String normalizeIdentifier(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.US)
                .replace('-', '_').replace(' ', '_').replaceAll("_+", "_");
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private List<Map<String, Object>> scanUsbDevices() {
        List<Map<String, Object>> result = new ArrayList<>();
        UsbManager manager = usbManager != null ? usbManager
                : (UsbManager) getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            sendEvent("engineWarning", "USB HOST SERVICE UNAVAILABLE");
            return result;
        }
        try {
            for (UsbDevice device : manager.getDeviceList().values()) {
                HashMap<String, Object> item = new HashMap<>();
                item.put("deviceId", device.getDeviceId());
                item.put("deviceName", device.getDeviceName());
                item.put("vendorId", device.getVendorId());
                item.put("productId", device.getProductId());
                item.put("deviceClass", device.getDeviceClass());
                item.put("deviceSubclass", device.getDeviceSubclass());
                item.put("deviceProtocol", device.getDeviceProtocol());
                item.put("interfaceCount", device.getInterfaceCount());
                item.put("looksLikeStillImageDevice", device.getDeviceClass() == 6);
                item.put("hasPermission", manager.hasPermission(device));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    item.put("manufacturer", safeUsbString(device, true));
                    item.put("product", safeUsbString(device, false));
                }
                result.add(item);
            }
        } catch (Exception e) {
            Log.w(TAG, "USB enumeration failed", e);
            sendEvent("engineWarning", "USB ENUMERATION FAILED: " + safeMessage(e));
        }
        return result;
    }

    private String safeUsbString(UsbDevice device, boolean manufacturer) {
        try {
            String value = manufacturer ? device.getManufacturerName() : device.getProductName();
            return value == null ? "" : value;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private boolean requestUsbPermission(Object arguments) {
        Map<String, Object> payload = readMapArguments(arguments);
        Integer deviceId = payload == null ? parseInt(arguments) : parseInt(payload.get("deviceId"));
        if (deviceId == null) {
            sendEvent("engineWarning", "USB PERMISSION REQUIRES deviceId");
            return false;
        }
        UsbManager manager = usbManager != null ? usbManager
                : (UsbManager) getSystemService(Context.USB_SERVICE);
        if (manager == null) return false;
        UsbDevice requested = null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (device.getDeviceId() == deviceId) {
                requested = device;
                break;
            }
        }
        if (requested == null) {
            sendEvent("engineWarning", "USB DEVICE NOT ATTACHED");
            return false;
        }
        if (manager.hasPermission(requested)) {
            sendEvent("usbPermissionResult", mapOf(
                    "deviceId", requested.getDeviceId(), "granted", true
            ));
            return true;
        }
        try {
            Intent intent = new Intent(getPackageName() + ACTION_USB_PERMISSION_SUFFIX)
                    .setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this, requested.getDeviceId(), intent, flags
            );
            manager.requestPermission(requested, permissionIntent);
            sendEvent("usbPermissionRequested", mapOf("deviceId", requested.getDeviceId()));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "USB permission request failed", e);
            sendEvent("engineWarning", "USB PERMISSION REQUEST FAILED: " + safeMessage(e));
            return false;
        }
    }

    private Integer parseInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return null;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private boolean hasWifiPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void setupUsbPermissionReceiver() {
        usbPermissionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !((getPackageName() + ACTION_USB_PERMISSION_SUFFIX)
                        .equals(intent.getAction()))) return;
                UsbDevice device = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                } else {
                    //noinspection deprecation
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                }
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                HashMap<String, Object> data = new HashMap<>();
                data.put("deviceId", device == null ? -1 : device.getDeviceId());
                data.put("granted", granted);
                data.put("deviceName", device == null ? "" : device.getDeviceName());
                sendEvent("usbPermissionResult", data);
            }
        };
        IntentFilter filter = new IntentFilter(getPackageName() + ACTION_USB_PERMISSION_SUFFIX);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(usbPermissionReceiver, filter);
            }
            usbPermissionReceiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "USB permission receiver registration failed", e);
            usbPermissionReceiverRegistered = false;
        }
    }

    private void loadPreferredTransport() {
        String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_PREFERRED_TRANSPORT, "WIFI");
        String normalized = normalizeTransport(saved);
        preferredTransport = normalized == null ? "WIFI" : normalized;
    }

    // ---------------------------------------------------------------------
    // Advanced Settings
    // ---------------------------------------------------------------------

    private boolean applyAdvancedSettingsPayload(Map<String, Object> payload) {
        String captureOutput = normalizeCaptureOutput(payload.get("captureOutput"));
        if (captureOutput == null) captureOutput = normalizeCaptureOutput(payload.get("captureMode"));
        String performance = normalizePerformance(payload.containsKey("performance")
                ? payload.get("performance") : payload.get("performanceMode"));
        if (captureOutput == null || performance == null) {
            Log.w(TAG, "Invalid Advanced Settings payload");
            sendEvent("error", "INVALID ADVANCED SETTINGS");
            return false;
        }
        boolean accepted = ensureEngine().applyAdvancedSettings(captureOutput, performance);
        if (!accepted) {
            Log.w(TAG, "CameraEngine rejected Advanced Settings");
            return false;
        }
        syncAdvancedSettingsFromEngine();
        sendEvent("advancedSettingsChanged", buildAdvancedSettingsPayload());
        sendEvent("captureOutputChanged", advancedCaptureOutput);
        sendEvent("performanceChanged", mapOf(
                "mode", advancedPerformance, "performance", advancedPerformance,
                "config", new HashMap<>(advancedPerformanceConfig)
        ));
        Log.i(TAG, "Advanced Settings applied: " + advancedCaptureOutput + " / " + advancedPerformance);
        return true;
    }

    private boolean applyCaptureOutput(String captureOutput) {
        boolean accepted = ensureEngine().setCaptureOutput(captureOutput);
        if (!accepted) return false;
        advancedCaptureOutput = captureOutput;
        syncAdvancedSettingsFromEngine();
        sendEvent("captureOutputChanged", advancedCaptureOutput);
        sendEvent("advancedSettingsChanged", buildAdvancedSettingsPayload());
        Log.i(TAG, "Capture output: " + advancedCaptureOutput);
        return true;
    }

    private boolean applyPerformance(PerformancePayload payload) {
        boolean accepted = ensureEngine().setPerformance(payload.mode);
        if (!accepted) return false;
        syncAdvancedSettingsFromEngine();
        sendEvent("performanceChanged", mapOf(
                "mode", advancedPerformance, "performance", advancedPerformance,
                "config", new HashMap<>(advancedPerformanceConfig)
        ));
        sendEvent("advancedSettingsChanged", buildAdvancedSettingsPayload());
        Log.i(TAG, "Performance: " + advancedPerformance);
        return true;
    }

    private void synchronizeAdvancedCaptureOutputFromLegacyMode(String mode) {
        if (normalizeCaptureMode(mode) == null) return;
        syncAdvancedSettingsFromEngine();
        sendEvent("captureOutputChanged", advancedCaptureOutput);
        sendEvent("advancedSettingsChanged", buildAdvancedSettingsPayload());
    }

    private void syncAdvancedSettingsFromEngine() {
        CameraEngine engine = cameraEngine;
        if (engine == null) return;
        try {
            Map<String, Object> nativeSettings = engine.getAdvancedSettings();
            String output = normalizeCaptureOutput(nativeSettings.get("captureOutput"));
            if (output == null) output = normalizeCaptureOutput(nativeSettings.get("captureMode"));
            String performance = normalizePerformance(nativeSettings.containsKey("performance")
                    ? nativeSettings.get("performance") : nativeSettings.get("performanceMode"));
            if (output != null) advancedCaptureOutput = output;
            if (performance != null) advancedPerformance = performance;
            advancedPerformanceConfig = performanceConfigFromNativeSettings(nativeSettings, advancedPerformance);
        } catch (Exception e) {
            Log.w(TAG, "Could not read settings from CameraEngine", e);
        }
    }

    private Map<String, Object> performanceConfigFromNativeSettings(
            Map<String, Object> nativeSettings, String mode) {
        Double previewScale = parseDouble(nativeSettings.get("previewScale"));
        Double processingScale = parseDouble(nativeSettings.get("processingScale"));
        Integer everyN = parseInt(nativeSettings.get("processingEveryNFrames"));
        if (previewScale == null || processingScale == null || everyN == null
                || !isValidScale(previewScale) || !isValidScale(processingScale)
                || everyN < 1 || everyN > 60) return defaultPerformanceConfig(mode);
        return mapOf("previewScale", previewScale, "processingScale", processingScale,
                "processingEveryNFrames", everyN);
    }

    private Map<String, Object> buildAdvancedSettingsPayload() {
        HashMap<String, Object> payload = new HashMap<>();
        Map<String, Object> config = new HashMap<>(advancedPerformanceConfig);
        payload.put("captureOutput", advancedCaptureOutput);
        payload.put("captureMode", advancedCaptureOutput);
        payload.put("performance", advancedPerformance);
        payload.put("performanceMode", advancedPerformance);
        payload.put("previewScale", config.get("previewScale"));
        payload.put("processingScale", config.get("processingScale"));
        payload.put("processingEveryNFrames", config.get("processingEveryNFrames"));
        double previewScale = ((Number) config.get("previewScale")).doubleValue();
        double processingScale = ((Number) config.get("processingScale")).doubleValue();
        int everyN = ((Number) config.get("processingEveryNFrames")).intValue();
        payload.put("previewPolicy", previewScale >= 0.999d ? "SOURCE" : (previewScale <= 0.50d ? "LOW" : "MEDIUM"));
        payload.put("processingPolicy", processingScale >= 0.999d ? "SOURCE" : (processingScale <= 0.50d ? "LOW" : "MEDIUM"));
        payload.put("framePolicy", everyN > 1 ? "SKIP" : "FULL");
        payload.put("performanceConfig", config);
        return payload;
    }

    private Map<String, Object> readMapArguments(Object arguments) {
        if (!(arguments instanceof Map)) return null;
        Map<?, ?> source = (Map<?, ?>) arguments;
        HashMap<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private String normalizeCaptureOutput(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim().toUpperCase(Locale.US)
                .replace('_', ' ').replace("+", " + ").replaceAll("\\s+", " ").trim();
        switch (normalized) {
            case "RAW": return "RAW";
            case "PROCESSED": return "PROCESSED";
            case "RAW AND PROCESSED":
            case "RAW + PROCESSED":
            case "RAW PROCESSED":
            case "BOTH": return "RAW + PROCESSED";
            default: return null;
        }
    }

    private String normalizeCaptureMode(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.US);
        if ("RAW".equals(normalized)) return "RAW";
        if ("PROCESSED".equals(normalized)) return "PROCESSED";
        return null;
    }

    private String normalizePerformance(Object value) {
        if (value == null) return null;
        String normalized = String.valueOf(value).trim().toUpperCase(Locale.US)
                .replace("_", " ").replaceAll("\\s+", " ");
        switch (normalized) {
            case "PERFORMANCE": return "PERFORMANCE";
            case "BALANCED": return "BALANCED";
            case "HIGH QUALITY":
            case "HIGH RESOLUTION":
            case "QUALITY": return "HIGH_QUALITY";
            default: return null;
        }
    }

    private Map<String, Object> readPerformanceConfig(Object rawConfig, String performanceMode) {
        // The native engine supports authoritative named presets, not arbitrary scales.
        return defaultPerformanceConfig(performanceMode);
    }

    private boolean isValidScale(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0.0 && value <= 1.0;
    }

    private Map<String, Object> defaultPerformanceConfig(String mode) {
        if ("PERFORMANCE".equals(mode)) {
            return mapOf("previewScale", 0.25d, "processingScale", 0.25d, "processingEveryNFrames", 1);
        }
        if ("HIGH_QUALITY".equals(mode)) {
            return mapOf("previewScale", 1.00d, "processingScale", 1.00d, "processingEveryNFrames", 1);
        }
        return mapOf("previewScale", 0.75d, "processingScale", 0.75d, "processingEveryNFrames", 1);
    }

    private Double parseDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value == null) return null;
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private PerformancePayload parsePerformancePayload(Object arguments) {
        if (arguments instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) arguments;
            Object rawMode = map.containsKey("mode") ? map.get("mode")
                    : (map.containsKey("performance") ? map.get("performance") : map.get("performanceMode"));
            String mode = normalizePerformance(rawMode);
            if (mode == null) return null;
            Object rawConfig = map.containsKey("config") ? map.get("config") : map.get("performanceConfig");
            return new PerformancePayload(mode, readPerformanceConfig(rawConfig, mode));
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
        if (ndviTextureEntry != null) return;
        try {
            ndviTextureEntry = flutterEngine.getRenderer().createSurfaceTexture();
            ensureEngine().attachGpuNdviSurfaceTexture(
                    ndviTextureEntry.surfaceTexture(), GPU_NDVI_TEXTURE_WIDTH, GPU_NDVI_TEXTURE_HEIGHT
            );
        } catch (Exception e) {
            Log.e(TAG, "GPU NDVI texture setup failed", e);
            if (ndviTextureEntry != null) {
                try { ndviTextureEntry.release(); } catch (Exception ignored) { }
                ndviTextureEntry = null;
            }
        }
    }

    // ---------------------------------------------------------------------
    // NFC
    // ---------------------------------------------------------------------

    private void setupNfc() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) return;
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        nfcPendingIntent = PendingIntent.getActivity(this, 0, intent, flags);
    }

    /**
     * Handles an explicit tap on the NFC pairing action.
     * Android does not permit apps to silently enable NFC; when disabled, the
     * system NFC settings screen is opened and listening starts after return.
     */
    private boolean requestNfcPairing() {
        if (nfcAdapter == null) {
            nfcStartRequested = false;
            nfcDispatchEnabled = false;
            sendEvent("nfcListeningChanged", false);
            sendEvent("nfcUnavailable", "NFC NOT SUPPORTED ON THIS DEVICE");
            return false;
        }

        if (nfcAdapter.isEnabled()) {
            nfcStartRequested = false;
            return startNfcListening();
        }

        nfcStartRequested = true;
        try {
            startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
            sendEvent("nfcListeningChanged", false);
            return true; // Settings was opened; user must enable NFC there.
        } catch (Exception nfcSettingsError) {
            Log.w(TAG, "Could not open NFC settings; trying wireless settings", nfcSettingsError);
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                sendEvent("nfcListeningChanged", false);
                return true;
            } catch (Exception wirelessSettingsError) {
                nfcStartRequested = false;
                nfcDispatchEnabled = false;
                Log.e(TAG, "Could not open Android NFC settings", wirelessSettingsError);
                sendEvent("nfcListeningChanged", false);
                sendEvent("nfcUnavailable", "CANNOT OPEN NFC SETTINGS");
                return false;
            }
        }
    }

    private boolean startNfcListening() {
        if (nfcAdapter == null) {
            nfcDispatchEnabled = false;
            sendEvent("nfcListeningChanged", false);
            sendEvent("nfcUnavailable", "NFC NOT SUPPORTED");
            return false;
        }
        if (!nfcAdapter.isEnabled()) {
            nfcDispatchEnabled = false;
            sendEvent("nfcListeningChanged", false);
            sendEvent("nfcUnavailable", "NFC IS DISABLED");
            return false;
        }
        try {
            ensureEngine().onNfcListeningStarted();
            enableNfcForegroundDispatch();
            nfcDispatchEnabled = true;
            sendEvent("nfcListeningChanged", true);
            Log.i(TAG, "NFC foreground dispatch active");
            return true;
        } catch (Exception e) {
            nfcDispatchEnabled = false;
            try { nfcAdapter.disableForegroundDispatch(this); } catch (Exception ignored) { }
            Log.e(TAG, "NFC foreground dispatch failed", e);
            sendEvent("nfcListeningChanged", false);
            sendEvent("error", "NFC FOREGROUND DISPATCH FAILED");
            return false;
        }
    }

    private void stopNfcListening() {
        nfcStartRequested = false;
        nfcDispatchEnabled = false;
        try { ensureEngine().onNfcListeningStopped(); }
        catch (Exception e) { Log.w(TAG, "Stopping NFC engine state failed", e); }
        disableNfcForegroundDispatch();
        sendEvent("nfcListeningChanged", false);
    }

    private void enableNfcForegroundDispatch() {
        if (nfcAdapter == null || nfcPendingIntent == null) {
            throw new IllegalStateException("NFC DISPATCH NOT READY");
        }
        nfcAdapter.enableForegroundDispatch(this, nfcPendingIntent, null, null);
    }

    private void disableNfcForegroundDispatch() {
        if (nfcAdapter == null) return;
        try { nfcAdapter.disableForegroundDispatch(this); } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (nfcStartRequested) {
            nfcStartRequested = false;
            if (nfcAdapter != null && nfcAdapter.isEnabled()) {
                // startNfcListening emits nfcListeningChanged with its confirmed state.
                startNfcListening();
            } else {
                nfcDispatchEnabled = false;
                sendEvent("nfcListeningChanged", false);
                sendEvent("nfcUnavailable", nfcAdapter == null
                        ? "NFC NOT SUPPORTED ON THIS DEVICE"
                        : "NFC STILL DISABLED — ENABLE IT IN ANDROID SETTINGS");
            }
            return;
        }

        if (nfcDispatchEnabled) {
            try {
                enableNfcForegroundDispatch();
                sendEvent("nfcListeningChanged", true);
            } catch (Exception e) {
                nfcDispatchEnabled = false;
                Log.e(TAG, "NFC foreground dispatch failed", e);
                sendEvent("nfcListeningChanged", false);
                sendEvent("error", "NFC FOREGROUND DISPATCH FAILED");
            }
        }
    }

    @Override
    protected void onPause() {
        if (nfcDispatchEnabled) disableNfcForegroundDispatch();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNfcIntent(intent);
    }

    private void handleNfcIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) return;

        android.os.Parcelable[] rawMessages;
        try { rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES); }
        catch (Exception e) { rawMessages = null; }
        if (rawMessages == null || rawMessages.length == 0) {
            sendEvent("nfcDetected", mapOf("payloadDetected", false));
            return;
        }
        CameraEngine engine = ensureEngine();
        boolean payloadForwarded = false;
        for (android.os.Parcelable raw : rawMessages) {
            if (!(raw instanceof NdefMessage)) continue;
            NdefMessage message = (NdefMessage) raw;
            for (NdefRecord record : message.getRecords()) {
                if (record == null) continue;
                byte[] payload = record.getPayload();
                if (payload == null || payload.length == 0) continue;
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
                requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, WIFI_PERMISSION_REQUEST);
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, WIFI_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            boolean granted = grantResults != null && grantResults.length > 0;
            if (granted) {
                for (int value : grantResults) {
                    if (value != PackageManager.PERMISSION_GRANTED) {
                        granted = false;
                        break;
                    }
                }
            }

            MethodChannel.Result result = pendingBluetoothPermissionResult;
            Runnable action = pendingBluetoothPermissionAction;
            Object fallback = pendingBluetoothPermissionFallback;
            pendingBluetoothPermissionResult = null;
            pendingBluetoothPermissionAction = null;
            pendingBluetoothPermissionFallback = null;

            sendEvent("bluetoothPermission", granted);
            if (granted && action != null) {
                try {
                    action.run();
                } catch (Exception error) {
                    Log.e(TAG, "Bluetooth operation after permission grant failed", error);
                    sendEvent("engineWarning",
                            "BLUETOOTH OPERATION FAILED: " + safeMessage(error));
                    if (result != null) result.success(fallback);
                }
            } else {
                sendEvent("engineWarning", "BLUETOOTH PERMISSION DENIED");
                if (result != null) result.success(fallback);
            }
            return;
        }
        if (requestCode != WIFI_PERMISSION_REQUEST) return;
        boolean granted = grantResults != null && grantResults.length > 0;
        if (granted) {
            for (int value : grantResults) {
                if (value != PackageManager.PERMISSION_GRANTED) { granted = false; break; }
            }
        }
        sendEvent("wifiPermission", granted);
        if (!granted) sendEvent("networkUnavailable", "WIFI PERMISSION NOT GRANTED");
    }

    private void setupFullscreen() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            if (getWindow().getInsetsController() != null) {
                getWindow().getInsetsController().hide(
                        android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars()
                );
            }
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    // ---------------------------------------------------------------------
    // CameraEngine.Listener
    // ---------------------------------------------------------------------

    @Override
    public void onNativeEvent(String type, Object data) {
        if ("nfcCredentials".equals(type) && handlingProgrammaticWifiPairing) {
            // This event is internal bookkeeping for a QR/manual connection. Do
            // not let Flutter mistake it for an NFC tap.
            return;
        }
        if ("wifiConnecting".equals(type)) {
            activeTransport = "WIFI";
            transportStatus = "CONNECTING";
            transportStatusReason = "CAMERA WI-FI CONNECTING";
            sendEvent("transportStatus", getTransportStatusPayload());
        } else if ("wifiConnected".equals(type)) {
            activeTransport = "WIFI";
            transportStatus = "CONNECTED";
            transportStatusReason = "CAMERA WI-FI NETWORK AVAILABLE; API DISCOVERY CONTINUES";
            sendEvent("transportStatus", getTransportStatusPayload());
        } else if ("cameraEndpointFound".equals(type) || "cameraIdentified".equals(type)) {
            activeTransport = "WIFI";
            transportStatus = "CONNECTED";
            transportStatusReason = "CAMERA ENDPOINT FOUND";
            sendEvent("transportStatus", getTransportStatusPayload());
        } else if ("liveviewActive".equals(type)) {
            activeTransport = "WIFI";
            transportStatus = "STREAMING";
            transportStatusReason = "LIVE VIEW ACTIVE";
            sendEvent("transportStatus", getTransportStatusPayload());
        } else if ("firstLiveviewFrame" .equals(type)) {
            activeTransport = "WIFI";
            transportStatus = "READY";
            transportStatusReason = "FIRST LIVE VIEW FRAME RECEIVED";
            sendEvent("transportStatus", getTransportStatusPayload());
        } else if ("networkUnavailable".equals(type) || "networkLost".equals(type)
                || "cameraError".equals(type) || "streamLost".equals(type)) {
            transportStatus = "ERROR";
            transportStatusReason = data == null ? type : String.valueOf(data);
            sendEvent("transportStatus", getTransportStatusPayload());
        } else if ("disconnected".equals(type)) {
            transportStatus = "DISCONNECTED";
            transportStatusReason = "CAMERA DISCONNECTED";
            sendEvent("transportStatus", getTransportStatusPayload());
        }

        if ("captureModeChanged".equals(type)) {
            synchronizeAdvancedCaptureOutputFromEvent(data);
            Map<String, Object> event = readMapArguments(data);
            if (event == null) event = new HashMap<>();
            event.put("mode", advancedCaptureOutput);
            event.put("captureOutput", advancedCaptureOutput);
            event.put("captureMode", advancedCaptureOutput);
            sendEvent(type, event);
            return;
        }
        if ("advancedSettingsChanged".equals(type)) {
            syncAdvancedSettingsFromEngine();
            sendEvent(type, buildAdvancedSettingsPayload());
            return;
        }
        if ("performanceChanged".equals(type)) {
            syncAdvancedSettingsFromEngine();
            sendEvent(type, mapOf("mode", advancedPerformance, "performance", advancedPerformance,
                    "config", new HashMap<>(advancedPerformanceConfig)));
            return;
        }
        if ("captureOutputChanged".equals(type)) {
            syncAdvancedSettingsFromEngine();
            sendEvent(type, advancedCaptureOutput);
            return;
        }
        sendEvent(type, data);
    }

    @Override
    public void onEngineError(String message, Throwable error) {
        if (error != null) Log.e(TAG, message, error); else Log.e(TAG, message);
        transportStatus = "ERROR";
        transportStatusReason = message == null ? "NATIVE ENGINE ERROR" : message;
        sendEvent("transportStatus", getTransportStatusPayload());
        sendEvent("error", message);
    }

    private void synchronizeAdvancedCaptureOutputFromEvent(Object data) {
        String mode = null;
        if (data instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) data;
            Object value = values.get("captureOutput");
            if (value == null) value = values.get("mode");
            if (value == null) value = values.get("captureMode");
            if (value != null) mode = String.valueOf(value);
        } else if (data != null) mode = String.valueOf(data);
        String normalized = normalizeCaptureOutput(mode);
        if (normalized == null) normalized = normalizeCaptureMode(mode);
        if (normalized != null) advancedCaptureOutput = normalized;
        syncAdvancedSettingsFromEngine();
    }

    // ---------------------------------------------------------------------
    // Flutter EventChannel
    // ---------------------------------------------------------------------

    private void sendEvent(String type, Object data) {
        if (type == null || type.trim().isEmpty()) return;
        if ("liveviewFrame".equals(type) || "spectralFrame".equals(type)) {
            queueLatestPreviewEvent(type, data);
            return;
        }
        EventChannel.EventSink sink = eventSink;
        if (sink == null) {
            synchronized (pendingEvents) {
                HashMap<String, Object> event = new HashMap<>();
                event.put("type", type);
                event.put("data", data);
                pendingEvents.add(event);
                while (pendingEvents.size() > 256) pendingEvents.remove(0);
            }
            return;
        }
        sendEventNow(type, data);
    }

    private void queueLatestPreviewEvent(String type, Object data) {
        if (eventSink == null) return;
        HashMap<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("data", data);
        pendingPreviewEvent.set(event);
        schedulePreviewEventDrain();
    }

    private void schedulePreviewEventDrain() {
        if (previewEventDispatchScheduled.compareAndSet(false, true)) {
            runOnUiThread(this::drainLatestPreviewEvent);
        }
    }

    private void drainLatestPreviewEvent() {
        try {
            Map<String, Object> event = pendingPreviewEvent.getAndSet(null);
            EventChannel.EventSink current = eventSink;
            if (event != null && current != null) {
                try { current.success(event); }
                catch (Exception e) { Log.e(TAG, "Preview event delivery failed", e); }
            }
        } finally {
            previewEventDispatchScheduled.set(false);
            if (pendingPreviewEvent.get() != null && eventSink != null) schedulePreviewEventDrain();
        }
    }

    private void sendEventNow(String type, Object data) {
        final EventChannel.EventSink sink = eventSink;
        if (sink == null) return;
        final HashMap<String, Object> event = new HashMap<>();
        event.put("type", type);
        event.put("data", data);
        runOnUiThread(() -> {
            EventChannel.EventSink current = eventSink;
            if (current == null || current != sink) return;
            try { current.success(event); }
            catch (Exception e) { Log.e(TAG, "Event delivery failed", e); }
        });
    }

    private void flushPendingEvents() {
        List<Map<String, Object>> snapshot;
        synchronized (pendingEvents) {
            if (pendingEvents.isEmpty()) return;
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
            if (entries[i] == null) throw new IllegalArgumentException("mapOf key must not be null");
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "UNKNOWN ERROR";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    // ---------------------------------------------------------------------
    // Lifecycle cleanup
    // ---------------------------------------------------------------------

    @Override
    protected void onDestroy() {
        nfcStartRequested = false;
        nfcDispatchEnabled = false;
        disableNfcForegroundDispatch();
        cleanupBluetooth();
        if (usbPermissionReceiverRegistered && usbPermissionReceiver != null) {
            try { unregisterReceiver(usbPermissionReceiver); }
            catch (Exception e) { Log.w(TAG, "USB permission receiver cleanup failed", e); }
            usbPermissionReceiverRegistered = false;
        }
        if (cameraEngine != null) {
            try { cameraEngine.detachGpuNdviSurfaceTexture(); }
            catch (Exception e) { Log.w(TAG, "GPU NDVI detach failed", e); }
            try { cameraEngine.destroy(); }
            catch (Exception e) { Log.e(TAG, "CameraEngine destroy failed", e); }
            cameraEngine = null;
        }
        synchronized (pendingEvents) { pendingEvents.clear(); }
        pendingPreviewEvent.set(null);
        if (ndviTextureEntry != null) {
            try { ndviTextureEntry.release(); } catch (Exception ignored) { }
            ndviTextureEntry = null;
        }
        eventSink = null;
        super.onDestroy();
    }
}
