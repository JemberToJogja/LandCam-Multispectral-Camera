package com.example.landcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bluetooth discovery and capability diagnostics for LANDCAM.
 *
 * <p>This service discovers bonded/classic/BLE devices and reports Android
 * permission/adapter state. It intentionally does not pretend that finding a
 * Bluetooth device means Sony camera control, capture, or live view is
 * supported. Those operations require a camera-specific protocol implementation
 * which is not provided by the generic Android Bluetooth APIs.</p>
 *
 * <p>Runtime permissions are not requested here because permission dialogs must
 * be launched from an Activity. Call {@link #getRequiredRuntimePermissions()}
 * and request any missing permissions in the Activity before scanning.</p>
 */
public final class BluetoothCapabilityService implements AutoCloseable {
    private static final String TAG = "LandCamBluetooth";
    private static final long DEFAULT_SCAN_DURATION_MS = 12_000L;
    private static final long MIN_SCAN_DURATION_MS = 1_000L;
    private static final long MAX_SCAN_DURATION_MS = 30_000L;

    private final Context appContext;
    private final BluetoothAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();
    private final Object devicesLock = new Object();
    private final AtomicLong generation = new AtomicLong(0L);
    private final LinkedHashMap<String, Map<String, Object>> foundDevices = new LinkedHashMap<>();

    private volatile Listener listener;
    private volatile String state = "IDLE";
    private volatile String stateMessage = "BLUETOOTH SCAN NOT STARTED";
    private volatile boolean scanning;
    private volatile boolean closed;
    private volatile long activeGeneration;
    private volatile long scanStartedAtMs;
    private volatile boolean classicDiscoveryStarted;
    private volatile boolean bleScanStarted;
    private volatile BluetoothLeScanner activeLeScanner;
    private volatile ScanCallback activeScanCallback;
    private volatile BroadcastReceiver activeReceiver;
    private volatile boolean receiverRegistered;
    private volatile Runnable timeoutRunnable;

    public interface Listener {
        default void onStatusChanged(String state, String message, Map<String, Object> diagnostics) { }
        default void onDeviceFound(Map<String, Object> device) { }
        default void onDevicesChanged(List<Map<String, Object>> devices) { }
        default void onScanFinished(List<Map<String, Object>> devices, String message) { }
        default void onLog(String level, String message) { }
        default void onError(String code, String message, Throwable cause) { }
    }

    public BluetoothCapabilityService(Context context) {
        this(context, null);
    }

    public BluetoothCapabilityService(Context context, Listener listener) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        BluetoothAdapter resolved = null;
        try {
            BluetoothManager manager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager != null) {
                resolved = manager.getAdapter();
            }
        } catch (Exception ignored) {
            // The public feature/diagnostics methods report the unavailable state.
        }
        this.adapter = resolved;
    }

    /** Replace or clear the callback without recreating the service. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Runtime permissions needed for scanning and resolving device names on the
     * current Android version. These permissions must also exist in Manifest.
     */
    public static String[] getRequiredRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new String[] { Manifest.permission.ACCESS_FINE_LOCATION };
        }
        return new String[0];
    }

    public boolean hasRequiredRuntimePermissions() {
        for (String permission : getRequiredRuntimePermissions()) {
            if (appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public List<String> getMissingRuntimePermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : getRequiredRuntimePermissions()) {
            if (appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        return Collections.unmodifiableList(missing);
    }

    /**
     * Intent the owning Activity may launch to ask the user to enable Bluetooth.
     * This service deliberately does not launch activities itself.
     */
    public Intent createEnableBluetoothIntent() {
        return new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
    }

    public String getState() {
        return state;
    }

    public String getStateMessage() {
        return stateMessage;
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean isBluetoothSupported() {
        return adapter != null && hasBluetoothFeature();
    }

    public boolean isBluetoothEnabled() {
        if (adapter == null) return false;
        try {
            return adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * A Bluetooth device being visible or paired is not proof of a camera
     * control protocol. Keep camera capabilities explicitly unsupported until a
     * transport validates and implements the actual camera protocol.
     */
    public CameraTransport.Capabilities getCameraCapabilities() {
        String reason;
        if (adapter == null || !hasBluetoothFeature()) {
            reason = "BLUETOOTH HARDWARE NOT AVAILABLE";
        } else if (!hasRequiredRuntimePermissions()) {
            reason = "BLUETOOTH RUNTIME PERMISSIONS REQUIRED";
        } else if (!isBluetoothEnabled()) {
            reason = "BLUETOOTH IS DISABLED";
        } else {
            reason = "BLUETOOTH DISCOVERY ONLY; SONY CAMERA CONTROL AND LIVE VIEW PROTOCOL NOT IMPLEMENTED";
        }
        return CameraTransport.Capabilities.unsupported(CameraTransport.Type.BLUETOOTH, reason);
    }

    /** Returns a diagnostic snapshot suitable for a Flutter MethodChannel map. */
    public Map<String, Object> getDiagnostics() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        boolean classicFeature = hasBluetoothFeature();
        boolean bleFeature = hasBleFeature();
        result.put("transport", CameraTransport.Type.BLUETOOTH.nativeName());
        result.put("state", state);
        result.put("stateMessage", stateMessage);
        result.put("supported", adapter != null && classicFeature);
        result.put("adapterAvailable", adapter != null);
        result.put("enabled", isBluetoothEnabled());
        result.put("classicSupported", classicFeature);
        result.put("bleSupported", bleFeature);
        result.put("scanPermissionGranted", hasScanPermission());
        result.put("connectPermissionGranted", hasConnectPermission());
        result.put("runtimePermissionsGranted", hasRequiredRuntimePermissions());
        result.put("missingPermissions", getMissingRuntimePermissions());
        result.put("scanning", scanning);
        result.put("classicDiscoveryStarted", classicDiscoveryStarted);
        result.put("bleScanStarted", bleScanStarted);
        result.put("deviceCount", getDiscoveredDevices().size());
        result.put("cameraCapabilities", getCameraCapabilities().toMap());
        return Collections.unmodifiableMap(result);
    }

    /** Returns a stable snapshot of devices found in the current/last scan. */
    public List<Map<String, Object>> getDiscoveredDevices() {
        synchronized (devicesLock) {
            List<Map<String, Object>> result = new ArrayList<>(foundDevices.size());
            for (Map<String, Object> device : foundDevices.values()) {
                result.add(Collections.unmodifiableMap(new LinkedHashMap<>(device)));
            }
            return Collections.unmodifiableList(result);
        }
    }

    /** Returns paired devices. Reading Bluetooth names on Android 12+ requires CONNECT. */
    @SuppressLint("MissingPermission")
    public List<Map<String, Object>> getBondedDevices() {
        if (adapter == null) return Collections.emptyList();
        if (!hasConnectPermission()) {
            reportError("BLUETOOTH_PERMISSION_REQUIRED",
                    "BLUETOOTH_CONNECT permission is required to read paired devices", null);
            return Collections.emptyList();
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) return Collections.emptyList();
            List<Map<String, Object>> result = new ArrayList<>(bonded.size());
            for (BluetoothDevice device : bonded) {
                Map<String, Object> item = deviceToMap(device, null, "BONDED");
                if (item != null) result.add(item);
            }
            return Collections.unmodifiableList(result);
        } catch (SecurityException e) {
            reportError("BLUETOOTH_PERMISSION_REQUIRED", "Bluetooth paired-device access was denied", e);
            return Collections.emptyList();
        } catch (Exception e) {
            reportError("BLUETOOTH_BONDED_DEVICES_FAILED", safeMessage(e), e);
            return Collections.emptyList();
        }
    }

    public boolean startScan() {
        return startScan(DEFAULT_SCAN_DURATION_MS);
    }

    /**
     * Starts BLE advertisement scanning and classic Bluetooth discovery when
     * supported. Returns true if at least one scan path was accepted.
     */
    @SuppressLint("MissingPermission")
    public boolean startScan(long durationMs) {
        if (closed) {
            reportError("BLUETOOTH_SERVICE_CLOSED", "Bluetooth capability service is closed", null);
            return false;
        }
        if (adapter == null || !hasBluetoothFeature()) {
            setState("UNSUPPORTED", "BLUETOOTH HARDWARE NOT AVAILABLE");
            reportError("BLUETOOTH_UNSUPPORTED", stateMessage, null);
            return false;
        }
        if (!hasRequiredRuntimePermissions()) {
            setState("PERMISSION_REQUIRED", "BLUETOOTH RUNTIME PERMISSIONS REQUIRED");
            reportError("BLUETOOTH_PERMISSION_REQUIRED",
                    "Grant the missing Bluetooth runtime permissions before scanning", null);
            return false;
        }
        if (!isBluetoothEnabled()) {
            setState("BLUETOOTH_DISABLED", "ENABLE BLUETOOTH TO SCAN FOR DEVICES");
            reportError("BLUETOOTH_DISABLED", stateMessage, null);
            return false;
        }

        final long token;
        synchronized (stateLock) {
            if (scanning) {
                reportLog("WARN", "Bluetooth scan request ignored because a scan is already active");
                return false;
            }
            scanning = true;
            token = generation.incrementAndGet();
            activeGeneration = token;
            scanStartedAtMs = SystemClock.elapsedRealtime();
            classicDiscoveryStarted = false;
            bleScanStarted = false;
            synchronized (devicesLock) {
                foundDevices.clear();
            }
        }

        setState("SCANNING", "SCANNING BONDED, CLASSIC, AND BLE BLUETOOTH DEVICES");
        for (Map<String, Object> bonded : getBondedDevices()) {
            addOrUpdateDevice(bonded);
        }

        long safeDuration = Math.max(MIN_SCAN_DURATION_MS,
                Math.min(MAX_SCAN_DURATION_MS, durationMs));
        Runnable timeout = () -> finishScan(token, true, false, "BLUETOOTH SCAN COMPLETED", null);
        synchronized (stateLock) {
            if (!isActiveScan(token)) return false;
            timeoutRunnable = timeout;
        }
        mainHandler.postDelayed(timeout, safeDuration);

        boolean classicStarted = startClassicDiscovery(token);
        boolean bleStarted = startBleScan(token);
        synchronized (stateLock) {
            if (isActiveScan(token)) {
                classicDiscoveryStarted = classicStarted;
                bleScanStarted = bleStarted;
            }
        }

        if (!classicStarted && !bleStarted) {
            finishScan(token, false, false,
                    "NO BLUETOOTH SCAN METHOD COULD START; CHECK PERMISSIONS AND DEVICE SUPPORT", null);
            return false;
        }

        reportLog("INFO", "Bluetooth scan started (classic=" + classicStarted + ", BLE=" + bleStarted + ")");
        notifyDevicesChanged();
        return true;
    }

    public void cancelScan() {
        long token = activeGeneration;
        finishScan(token, false, true, "BLUETOOTH SCAN CANCELLED", null);
    }

    @SuppressLint("MissingPermission")
    private boolean startClassicDiscovery(long token) {
        if (adapter == null || !hasScanPermission() || !hasConnectPermission()) return false;
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !isActiveScan(token)) return;
                    String action = intent.getAction();
                    if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        Short rssi = null;
                        if (intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) {
                            rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                        }
                        Map<String, Object> item = deviceToMap(device,
                                rssi == null || rssi == Short.MIN_VALUE ? null : (int) rssi,
                                "CLASSIC_DISCOVERY");
                        if (item != null) addOrUpdateDevice(item);
                    } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                        reportLog("INFO", "Classic Bluetooth inquiry finished; BLE scan may continue");
                    } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                            finishScan(token, false, false, "BLUETOOTH WAS DISABLED DURING SCAN", null);
                        }
                    }
                }
            };

            synchronized (stateLock) {
                if (!isActiveScan(token)) return false;
                activeReceiver = receiver;
                receiverRegistered = false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(receiver, filter);
            }
            synchronized (stateLock) {
                if (isActiveScan(token)) receiverRegistered = true;
                else {
                    try { appContext.unregisterReceiver(receiver); } catch (Exception ignored) { }
                    return false;
                }
            }

            boolean accepted = adapter.startDiscovery();
            if (!accepted) {
                unregisterActiveReceiver(receiver);
                reportLog("WARN", "Classic Bluetooth discovery could not start");
            }
            return accepted;
        } catch (SecurityException e) {
            reportLog("WARN", "Classic Bluetooth discovery permission denied: " + safeMessage(e));
            return false;
        } catch (Exception e) {
            reportLog("WARN", "Classic Bluetooth discovery failed: " + safeMessage(e));
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private boolean startBleScan(long token) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || adapter == null || !hasScanPermission()) {
            return false;
        }
        if (!hasBleFeature()) {
            reportLog("INFO", "Bluetooth LE is not supported by this device; classic discovery remains available");
            return false;
        }

        BluetoothLeScanner scanner;
        try {
            scanner = adapter.getBluetoothLeScanner();
        } catch (SecurityException e) {
            reportLog("WARN", "Could not access BLE scanner: " + safeMessage(e));
            return false;
        }
        if (scanner == null) {
            reportLog("WARN", "BLE scanner unavailable");
            return false;
        }

        ScanCallback callback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (result == null || !isActiveScan(token)) return;
                Map<String, Object> item = bleResultToMap(result);
                if (item != null) addOrUpdateDevice(item);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                if (results == null || !isActiveScan(token)) return;
                for (ScanResult result : results) {
                    Map<String, Object> item = bleResultToMap(result);
                    if (item != null) addOrUpdateDevice(item);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                reportLog("WARN", "BLE scan failed with Android error code " + errorCode);
                synchronized (stateLock) {
                    if (activeGeneration == token) bleScanStarted = false;
                }
                if (!classicDiscoveryStarted) {
                    finishScan(token, false, false, "BLE SCAN FAILED: " + errorCode, null);
                }
            }
        };

        try {
            synchronized (stateLock) {
                if (!isActiveScan(token)) return false;
                activeLeScanner = scanner;
                activeScanCallback = callback;
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, callback);
            return true;
        } catch (SecurityException e) {
            reportLog("WARN", "BLE scan permission denied: " + safeMessage(e));
            return false;
        } catch (Exception e) {
            reportLog("WARN", "BLE scanner could not start: " + safeMessage(e));
            return false;
        }
    }

    private Map<String, Object> bleResultToMap(ScanResult result) {
        if (result == null) return null;
        BluetoothDevice device = result.getDevice();
        String advertisedName = null;
        try {
            if (result.getScanRecord() != null) advertisedName = result.getScanRecord().getDeviceName();
        } catch (Exception ignored) { }
        return deviceToMap(device, result.getRssi(), "BLE_SCAN", advertisedName);
    }

    @SuppressLint("MissingPermission")
    private Map<String, Object> deviceToMap(BluetoothDevice device, Integer rssi, String source) {
        return deviceToMap(device, rssi, source, null);
    }

    @SuppressLint("MissingPermission")
    private Map<String, Object> deviceToMap(
            BluetoothDevice device, Integer rssi, String source, String advertisedName) {
        if (device == null) return null;
        try {
            String address = device.getAddress();
            if (address == null || address.trim().isEmpty()) return null;
            String name = nonEmpty(advertisedName);
            if (name == null) {
                try { name = nonEmpty(device.getName()); } catch (SecurityException ignored) { }
            }
            int deviceType = BluetoothDevice.DEVICE_TYPE_UNKNOWN;
            int bondState = BluetoothDevice.BOND_NONE;
            try { deviceType = device.getType(); } catch (SecurityException ignored) { }
            try { bondState = device.getBondState(); } catch (SecurityException ignored) { }

            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("transport", CameraTransport.Type.BLUETOOTH.nativeName());
            item.put("deviceId", address);
            item.put("address", address);
            item.put("name", name == null ? "Unknown Bluetooth device" : name);
            item.put("hasName", name != null);
            item.put("deviceType", deviceTypeName(deviceType));
            item.put("deviceTypeCode", deviceType);
            item.put("paired", bondState == BluetoothDevice.BOND_BONDED);
            item.put("bondState", bondStateName(bondState));
            item.put("source", source == null ? "UNKNOWN" : source);
            item.put("possibleCameraCandidate", couldBeCameraName(name));
            item.put("cameraProtocolVerified", false);
            item.put("canControlCamera", false);
            item.put("canReceiveLiveView", false);
            item.put("canCapturePhoto", false);
            item.put("canTransferFiles", false);
            if (rssi != null) item.put("rssi", rssi);
            return Collections.unmodifiableMap(item);
        } catch (SecurityException e) {
            reportLog("WARN", "Bluetooth device details unavailable because permission was denied");
            return null;
        } catch (Exception e) {
            reportLog("WARN", "Could not read Bluetooth device: " + safeMessage(e));
            return null;
        }
    }

    private void addOrUpdateDevice(Map<String, Object> item) {
        if (item == null) return;
        Object rawAddress = item.get("address");
        if (rawAddress == null) return;
        String address = String.valueOf(rawAddress).trim().toUpperCase(Locale.ROOT);
        if (address.isEmpty()) return;

        Map<String, Object> snapshot;
        synchronized (devicesLock) {
            Map<String, Object> old = foundDevices.get(address);
            LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
            if (old != null) merged.putAll(old);
            merged.putAll(item);
            if (old != null) {
                // Prefer an actual advertised/device name over the fallback label.
                if (Boolean.TRUE.equals(item.get("hasName"))) {
                    merged.put("name", item.get("name"));
                    merged.put("hasName", true);
                } else if (Boolean.TRUE.equals(old.get("hasName"))) {
                    merged.put("name", old.get("name"));
                    merged.put("hasName", true);
                }
                if (Boolean.TRUE.equals(old.get("paired"))) merged.put("paired", true);
            }
            snapshot = Collections.unmodifiableMap(merged);
            foundDevices.put(address, snapshot);
        }
        Listener callback = listener;
        if (callback != null) {
            mainHandler.post(() -> {
                if (listener != callback) return;
                try { callback.onDeviceFound(snapshot); } catch (Exception ignored) { }
                try { callback.onDevicesChanged(getDiscoveredDevices()); } catch (Exception ignored) { }
            });
        }
    }

    private void notifyDevicesChanged() {
        Listener callback = listener;
        if (callback == null) return;
        List<Map<String, Object>> devices = getDiscoveredDevices();
        mainHandler.post(() -> {
            if (listener != callback) return;
            try { callback.onDevicesChanged(devices); } catch (Exception ignored) { }
        });
    }

    private boolean isActiveScan(long token) {
        return !closed && scanning && activeGeneration == token;
    }

    private void finishScan(long token, boolean success, boolean cancelled, String message, Throwable cause) {
        BluetoothLeScanner scanner;
        ScanCallback callback;
        BroadcastReceiver receiver;
        boolean registered;
        boolean stopClassic;
        Runnable timeout;
        synchronized (stateLock) {
            if (!scanning || activeGeneration != token) return;
            scanning = false;
            scanner = activeLeScanner;
            callback = activeScanCallback;
            receiver = activeReceiver;
            registered = receiverRegistered;
            stopClassic = classicDiscoveryStarted;
            timeout = timeoutRunnable;
            activeLeScanner = null;
            activeScanCallback = null;
            activeReceiver = null;
            receiverRegistered = false;
            classicDiscoveryStarted = false;
            bleScanStarted = false;
            timeoutRunnable = null;
        }

        if (timeout != null) mainHandler.removeCallbacks(timeout);
        if (scanner != null && callback != null) {
            try { scanner.stopScan(callback); } catch (Exception ignored) { }
        }
        if (stopClassic && adapter != null && hasConnectPermission()) {
            try { if (adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (Exception ignored) { }
        }
        if (receiver != null && registered) {
            try { appContext.unregisterReceiver(receiver); } catch (Exception ignored) { }
        }

        long elapsed = scanStartedAtMs == 0L ? 0L : Math.max(0L, SystemClock.elapsedRealtime() - scanStartedAtMs);
        List<Map<String, Object>> devices = getDiscoveredDevices();
        String completionText = message == null ? "" : message;
        if (success && devices.isEmpty()) completionText += "; NO BLUETOOTH DEVICES FOUND";
        final String finalMessage = completionText;
        if (cancelled) setState("CANCELLED", finalMessage);
        else if (success) setState("COMPLETE", finalMessage);
        else setState("ERROR", finalMessage);
        reportLog(success ? "INFO" : (cancelled ? "INFO" : "ERROR"),
                finalMessage + " (devices=" + devices.size() + ", elapsedMs=" + elapsed + ")");

        Listener callbackListener = listener;
        if (callbackListener != null) {
            mainHandler.post(() -> {
                if (listener != callbackListener) return;
                try { callbackListener.onScanFinished(devices, finalMessage); } catch (Exception ignored) { }
                if (!success && !cancelled) {
                    try { callbackListener.onError("BLUETOOTH_SCAN_FAILED", finalMessage, cause); } catch (Exception ignored) { }
                }
            });
        }
    }

    private void unregisterActiveReceiver(BroadcastReceiver receiver) {
        if (receiver == null) return;
        boolean registered = false;
        synchronized (stateLock) {
            if (activeReceiver == receiver && receiverRegistered) {
                receiverRegistered = false;
                activeReceiver = null;
                registered = true;
            }
        }
        if (registered) {
            try { appContext.unregisterReceiver(receiver); } catch (Exception ignored) { }
        }
    }

    private boolean hasBluetoothFeature() {
        try {
            return appContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
        } catch (Exception ignored) {
            return adapter != null;
        }
    }

    private boolean hasBleFeature() {
        try {
            return appContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void setState(String nextState, String message) {
        state = nextState == null ? "UNKNOWN" : nextState;
        stateMessage = message == null ? "" : message;
        Listener callback = listener;
        if (callback == null) return;
        Map<String, Object> diagnostics = getDiagnostics();
        String safeState = state;
        String safeMessage = stateMessage;
        mainHandler.post(() -> {
            if (listener != callback) return;
            try { callback.onStatusChanged(safeState, safeMessage, diagnostics); } catch (Exception ignored) { }
        });
    }

    private void reportError(String code, String message, Throwable cause) {
        Listener callback = listener;
        if (callback == null) return;
        String safeCode = code == null ? "BLUETOOTH_ERROR" : code;
        String safeMessage = message == null ? "UNKNOWN BLUETOOTH ERROR" : message;
        mainHandler.post(() -> {
            if (listener != callback) return;
            try { callback.onError(safeCode, safeMessage, cause); } catch (Exception ignored) { }
        });
    }

    private void reportLog(String level, String message) {
        Listener callback = listener;
        if (callback == null) return;
        String safeLevel = level == null ? "INFO" : level;
        String safeMessage = message == null ? "" : message;
        mainHandler.post(() -> {
            if (listener != callback) return;
            try { callback.onLog(safeLevel, safeMessage); } catch (Exception ignored) { }
        });
    }

    private static String deviceTypeName(int type) {
        switch (type) {
            case BluetoothDevice.DEVICE_TYPE_CLASSIC: return "CLASSIC";
            case BluetoothDevice.DEVICE_TYPE_LE: return "BLE";
            case BluetoothDevice.DEVICE_TYPE_DUAL: return "DUAL";
            default: return "UNKNOWN";
        }
    }

    private static String bondStateName(int state) {
        switch (state) {
            case BluetoothDevice.BOND_BONDED: return "BONDED";
            case BluetoothDevice.BOND_BONDING: return "BONDING";
            default: return "NOT_PAIRED";
        }
    }

    private static boolean couldBeCameraName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("sony") || normalized.contains("camera")
                || normalized.contains("ilce-") || normalized.contains("alpha")
                || normalized.matches(".*\\ba6[0-9]{3}\\b.*");
    }

    private static String nonEmpty(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "UNKNOWN ERROR";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message.trim();
    }

    @Override
    public void close() {
        if (closed) return;
        cancelScan();
        closed = true;
        state = "CLOSED";
        stateMessage = "BLUETOOTH CAPABILITY SERVICE CLOSED";
        Listener oldListener = listener;
        listener = null;
        mainHandler.removeCallbacksAndMessages(null);
        if (oldListener != null) {
            try { oldListener.onStatusChanged(state, stateMessage, getDiagnostics()); } catch (Exception ignored) { }
        }
    }
}
