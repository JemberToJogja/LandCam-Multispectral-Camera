package com.example.landcam;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * USB Still Image/PTP transport for LANDCAM.
 *
 * <p>This implementation owns the USB permission flow, interface claim, PTP
 * session, device-info interrogation and a small set of standard PTP commands.
 * It deliberately does not claim live-view or calibrated NIR support: USB PTP
 * does not, by itself, provide a generic live-view stream or a multispectral
 * channel. Whether capture and file transfer are available is based on the
 * connected device's advertised PTP operation list.</p>
 *
 * <p>Call {@link #connect(CameraTransport.ConnectionRequest, CameraTransport.Listener)}
 * from the app. The returned boolean means the request was accepted; final
 * connection status is delivered asynchronously to the listener. Methods that
 * perform object transfers can block on USB I/O and must not be called on the UI
 * thread.</p>
 */
public final class UsbPtpTransport implements CameraTransport {
    private static final String TAG = "LandCamUsbPtp";

    private static final int USB_CLASS_STILL_IMAGE = 0x06;
    private static final int USB_SUBCLASS_STILL_IMAGE = 0x01;
    private static final int USB_PROTOCOL_PTP = 0x01;

    private static final int PTP_CONTAINER_COMMAND = 1;
    private static final int PTP_CONTAINER_DATA = 2;
    private static final int PTP_CONTAINER_RESPONSE = 3;
    private static final int PTP_HEADER_LENGTH = 12;
    private static final int PTP_RESPONSE_OK = 0x2001;
    private static final int PTP_RESPONSE_SESSION_ALREADY_OPEN = 0x201E;

    private static final int OP_GET_DEVICE_INFO = 0x1001;
    private static final int OP_OPEN_SESSION = 0x1002;
    private static final int OP_CLOSE_SESSION = 0x1003;
    private static final int OP_GET_STORAGE_IDS = 0x1004;
    private static final int OP_GET_OBJECT_HANDLES = 0x1007;
    private static final int OP_GET_OBJECT_INFO = 0x1008;
    private static final int OP_GET_OBJECT = 0x1009;
    private static final int OP_INITIATE_CAPTURE = 0x100E;

    private static final int RESPONSE_TIMEOUT_MS = 5_000;
    private static final int DATA_TIMEOUT_MS = 12_000;
    private static final int MAX_USB_CHUNK = 16 * 1024;
    private static final int MAX_PTP_DEVICE_INFO_BYTES = 1024 * 1024;
    private static final int MAX_PTP_OBJECT_INFO_BYTES = 1024 * 1024;
    private static final int MAX_PTP_OBJECT_BYTES = 128 * 1024 * 1024;

    private final Context appContext;
    private final UsbManager usbManager;
    private final ExecutorService ioExecutor;
    private final AtomicLong generation = new AtomicLong(0L);
    private final Object stateLock = new Object();
    private final Object ptpIoLock = new Object();

    private volatile Listener listener;
    private volatile Status status = Status.DISCONNECTED;
    private volatile Capabilities capabilities = Capabilities.unsupported(
            Type.USB_PTP, "NO USB PTP DEVICE CONNECTED");
    private volatile String statusMessage = "NO USB PTP DEVICE CONNECTED";
    private volatile boolean closed;
    private volatile UsbDevice selectedDevice;
    private volatile UsbDeviceConnection deviceConnection;
    private volatile UsbInterface claimedInterface;
    private volatile UsbEndpoint bulkInEndpoint;
    private volatile UsbEndpoint bulkOutEndpoint;
    private volatile UsbEndpoint interruptInEndpoint;
    private volatile boolean sessionOpen;
    private volatile int nextTransactionId = 1;
    private volatile Map<String, Object> deviceInfo = Collections.emptyMap();
    private volatile Set<Integer> supportedOperations = Collections.emptySet();
    private volatile BroadcastReceiver usbReceiver;
    private volatile boolean receiverRegistered;

    private final String permissionAction;

    public UsbPtpTransport(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        appContext = context.getApplicationContext();
        UsbManager manager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
        usbManager = manager;
        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LANDCAM-USB-PTP");
            thread.setDaemon(true);
            return thread;
        });
        permissionAction = appContext.getPackageName() + ".USB_PTP_PERMISSION";
        registerUsbReceiver();
    }

    @Override
    public Type getType() {
        return Type.USB_PTP;
    }

    @Override
    public Status getStatus() {
        return status;
    }

    @Override
    public Capabilities getCapabilities() {
        return capabilities;
    }

    /** Enumerates attached USB devices without requesting permission. */
    public static List<Map<String, Object>> scanDevices(Context context) {
        if (context == null) return Collections.emptyList();
        Context appContext = context.getApplicationContext();
        UsbManager manager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
        if (manager == null) return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();
        try {
            for (UsbDevice device : manager.getDeviceList().values()) {
                InterfaceEndpoints endpoints = findPtpInterface(device, false);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("transport", Type.USB_PTP.nativeName());
                item.put("deviceId", device.getDeviceId());
                item.put("deviceName", safe(device.getDeviceName()));
                item.put("vendorId", device.getVendorId());
                item.put("productId", device.getProductId());
                item.put("deviceClass", device.getDeviceClass());
                item.put("deviceSubclass", device.getDeviceSubclass());
                item.put("deviceProtocol", device.getDeviceProtocol());
                item.put("interfaceCount", device.getInterfaceCount());
                item.put("hasPtpInterface", endpoints != null);
                item.put("hasPermission", manager.hasPermission(device));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    item.put("manufacturer", safeUsbName(device, true));
                    item.put("product", safeUsbName(device, false));
                }
                result.add(Collections.unmodifiableMap(item));
            }
        } catch (Exception e) {
            Log.w(TAG, "USB device enumeration failed", e);
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean connect(ConnectionRequest request, Listener newListener) {
        if (request == null || request.getType() != Type.USB_PTP) {
            return reject(newListener, "INVALID_TRANSPORT_REQUEST",
                    "UsbPtpTransport requires a USB_PTP ConnectionRequest");
        }
        if (closed) {
            return reject(newListener, "TRANSPORT_CLOSED", "USB PTP transport is closed");
        }
        if (usbManager == null) {
            setStatus(Status.UNSUPPORTED, "ANDROID USB HOST SERVICE IS UNAVAILABLE",
                    Capabilities.unsupported(Type.USB_PTP, "USB HOST SERVICE UNAVAILABLE"));
            return reject(newListener, "USB_HOST_UNAVAILABLE", statusMessage);
        }

        synchronized (stateLock) {
            if (status.isBusy() || status.isConnected()) {
                return reject(newListener, "TRANSPORT_BUSY",
                        "Disconnect the current USB device before connecting another");
            }
            listener = newListener;
            capabilities = Capabilities.unsupported(Type.USB_PTP, "PTP DEVICE NOT YET VALIDATED");
            deviceInfo = Collections.emptyMap();
            supportedOperations = Collections.emptySet();
        }

        UsbDevice device = findRequestedDevice(request);
        if (device == null) {
            boolean anyAttached = !usbManager.getDeviceList().isEmpty();
            String message = anyAttached
                    ? "NO MATCHING USB DEVICE WITH A PTP INTERFACE WAS FOUND"
                    : "NO USB DEVICE IS ATTACHED; CONNECT THE CAMERA IN A USB PTP MODE";
            setStatus(anyAttached ? Status.UNSUPPORTED : Status.ERROR, message,
                    Capabilities.unsupported(Type.USB_PTP, message));
            emitError("USB_DEVICE_NOT_FOUND", message, null);
            return false;
        }

        InterfaceEndpoints endpoints = findPtpInterface(device, false);
        if (endpoints == null) {
            String message = "THE SELECTED USB DEVICE DOES NOT EXPOSE A STANDARD PTP STILL-IMAGE INTERFACE";
            setStatus(Status.UNSUPPORTED, message,
                    Capabilities.unsupported(Type.USB_PTP, message));
            emitError("PTP_INTERFACE_NOT_FOUND", message, null);
            return false;
        }

        final long requestGeneration = generation.incrementAndGet();
        selectedDevice = device;
        setStatus(Status.CHECKING_PERMISSION, "CHECKING USB DEVICE PERMISSION",
                Capabilities.unsupported(Type.USB_PTP, "WAITING FOR USB PERMISSION"));

        if (usbManager.hasPermission(device)) {
            scheduleOpen(device, requestGeneration);
            return true;
        }

        try {
            requestDevicePermission(device);
            emitEvent("usbPermissionRequested", mapOf(
                    "deviceId", device.getDeviceId(),
                    "deviceName", safe(device.getDeviceName()),
                    "vendorId", device.getVendorId(),
                    "productId", device.getProductId()));
            return true;
        } catch (Exception e) {
            setStatus(Status.ERROR, "USB PERMISSION REQUEST FAILED",
                    Capabilities.unsupported(Type.USB_PTP, "USB PERMISSION REQUEST FAILED"));
            emitError("USB_PERMISSION_REQUEST_FAILED", safeMessage(e), e);
            return false;
        }
    }

    /** Current USB PTP device metadata, empty until GetDeviceInfo succeeds. */
    public Map<String, Object> getDeviceInfo() {
        return deviceInfo;
    }

    /**
     * Sends PTP GetStorageIDs. Call from a worker thread, not the Android UI
     * thread. Empty array means the device does not expose the operation.
     */
    public long[] getStorageIds() throws PtpException {
        requireOperation(OP_GET_STORAGE_IDS, "GetStorageIDs");
        synchronized (ptpIoLock) {
            byte[] payload = executeDataCommandLocked(OP_GET_STORAGE_IDS);
            return parseUInt32Array(payload, "GetStorageIDs");
        }
    }

    /**
     * Sends PTP GetObjectHandles for a storage/format/association tuple.
     * Use 0 for storageId, formatCode or associationHandle to request all values,
     * according to the PTP specification.
     */
    public long[] getObjectHandles(long storageId, int formatCode, long associationHandle)
            throws PtpException {
        requireOperation(OP_GET_OBJECT_HANDLES, "GetObjectHandles");
        synchronized (ptpIoLock) {
            byte[] payload = executeDataCommandLocked(
                    OP_GET_OBJECT_HANDLES,
                    (int) storageId,
                    formatCode,
                    (int) associationHandle);
            return parseUInt32Array(payload, "GetObjectHandles");
        }
    }

    /** Returns parsed standard PTP ObjectInfo metadata for an object handle. */
    public Map<String, Object> getObjectInfo(long objectHandle) throws PtpException {
        requireOperation(OP_GET_OBJECT_INFO, "GetObjectInfo");
        synchronized (ptpIoLock) {
            byte[] payload = executeDataCommandLocked(OP_GET_OBJECT_INFO, (int) objectHandle);
            return parseObjectInfo(payload);
        }
    }

    /** Downloads one full PTP object into memory. Call from a worker thread. */
    public byte[] getObject(long objectHandle) throws PtpException {
        requireOperation(OP_GET_OBJECT, "GetObject");
        synchronized (ptpIoLock) {
            return executeDataCommandWithLimitLocked(OP_GET_OBJECT, MAX_PTP_OBJECT_BYTES, (int) objectHandle);
        }
    }

    /**
     * Requests standard PTP capture when the connected device advertises
     * InitiateCapture. This does not imply that the camera sends a preview; it
     * typically creates a still object that can be retrieved with PTP.
     */
    public boolean captureStill() throws PtpException {
        requireOperation(OP_INITIATE_CAPTURE, "InitiateCapture");
        synchronized (ptpIoLock) {
            executeCommandLocked(OP_INITIATE_CAPTURE, 0, 0);
            emitEvent("ptpCaptureRequested", mapOf("accepted", true));
            return true;
        }
    }

    @Override
    public void disconnect() {
        if (closed) return;
        final long disconnectGeneration = generation.incrementAndGet();
        if (status == Status.DISCONNECTED) return;
        setStatus(Status.DISCONNECTING, "DISCONNECTING USB PTP SESSION", capabilities);
        submitWorker(() -> {
            closeConnectionResources(true);
            if (generation.get() == disconnectGeneration && !closed) {
                selectedDevice = null;
                deviceInfo = Collections.emptyMap();
                supportedOperations = Collections.emptySet();
                capabilities = Capabilities.unsupported(Type.USB_PTP, "USB PTP DISCONNECTED");
                setStatus(Status.DISCONNECTED, "USB PTP DISCONNECTED", capabilities);
            }
        });
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        final long closeGeneration = generation.incrementAndGet();
        unregisterUsbReceiver();
        try {
            ioExecutor.execute(() -> {
                closeConnectionResources(true);
                if (generation.get() == closeGeneration) {
                    selectedDevice = null;
                    deviceInfo = Collections.emptyMap();
                    supportedOperations = Collections.emptySet();
                    capabilities = Capabilities.unsupported(Type.USB_PTP, "TRANSPORT CLOSED");
                    setStatus(Status.DISCONNECTED, "USB PTP TRANSPORT CLOSED", capabilities);
                }
            });
        } catch (RejectedExecutionException ignored) {
            closeConnectionResources(false);
        } finally {
            ioExecutor.shutdown();
        }
    }

    private void registerUsbReceiver() {
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (permissionAction.equals(action)) {
                    UsbDevice device = getUsbDeviceExtra(intent);
                    if (device == null) return;
                    boolean granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    emitEvent("usbPermissionResult", mapOf(
                            "deviceId", device.getDeviceId(), "granted", granted,
                            "deviceName", safe(device.getDeviceName())));
                    if (!sameDevice(selectedDevice, device)) return;
                    // Only an outstanding permission request may continue a connection.
                    // A late permission broadcast must not revive a cancelled/disconnected session.
                    if (status != Status.CHECKING_PERMISSION) return;
                    long currentGeneration = generation.get();
                    if (!granted) {
                        setStatus(Status.ERROR, "USB DEVICE PERMISSION DENIED",
                                Capabilities.unsupported(Type.USB_PTP, "USB PERMISSION DENIED"));
                        emitError("USB_PERMISSION_DENIED", "USB device permission was denied", null);
                        return;
                    }
                    scheduleOpen(device, currentGeneration);
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    UsbDevice device = getUsbDeviceExtra(intent);
                    if (device != null && sameDevice(selectedDevice, device)) {
                        final long detachGeneration = generation.incrementAndGet();
                        setStatus(Status.DISCONNECTING, "USB DEVICE DETACHED", capabilities);
                        submitWorker(() -> {
                            closeConnectionResources(false);
                            if (generation.get() == detachGeneration && !closed) {
                                selectedDevice = null;
                                deviceInfo = Collections.emptyMap();
                                supportedOperations = Collections.emptySet();
                                capabilities = Capabilities.unsupported(Type.USB_PTP, "USB DEVICE DETACHED");
                                setStatus(Status.DISCONNECTED, "USB DEVICE DETACHED", capabilities);
                                emitEvent("usbDeviceDetached", mapOf(
                                        "deviceId", device.getDeviceId(),
                                        "deviceName", safe(device.getDeviceName())));
                            }
                        });
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appContext.registerReceiver(usbReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception e) {
            receiverRegistered = false;
            Log.w(TAG, "Could not register USB transport receiver", e);
        }
    }

    private void unregisterUsbReceiver() {
        BroadcastReceiver receiver = usbReceiver;
        usbReceiver = null;
        if (receiver == null || !receiverRegistered) return;
        receiverRegistered = false;
        try {
            appContext.unregisterReceiver(receiver);
        } catch (Exception e) {
            Log.w(TAG, "USB receiver cleanup failed", e);
        }
    }

    private void requestDevicePermission(UsbDevice device) {
        if (!receiverRegistered) {
            throw new IllegalStateException("USB permission receiver is not registered");
        }
        Intent intent = new Intent(permissionAction).setPackage(appContext.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        // USB service fills EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED in the result intent.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent permissionPendingIntent = PendingIntent.getBroadcast(
                appContext, device.getDeviceId(), intent, flags);
        usbManager.requestPermission(device, permissionPendingIntent);
    }

    private void scheduleOpen(UsbDevice device, long requestGeneration) {
        setStatus(Status.CONNECTING, "OPENING USB PTP DEVICE", capabilities);
        submitWorker(() -> openAndInitialize(device, requestGeneration));
    }

    private void openAndInitialize(UsbDevice device, long requestGeneration) {
        UsbDeviceConnection localConnection = null;
        UsbInterface localInterface = null;
        InterfaceEndpoints endpoints = null;
        try {
            if (!isCurrent(requestGeneration) || closed) return;
            if (usbManager == null || !usbManager.hasPermission(device)) {
                throw new PtpException("USB_PERMISSION_REQUIRED", "USB permission is not granted");
            }

            endpoints = findPtpInterface(device, false);
            if (endpoints == null) {
                setStatus(Status.UNSUPPORTED,
                        "USB DEVICE DOES NOT HAVE A STANDARD PTP INTERFACE",
                        Capabilities.unsupported(Type.USB_PTP, "NO PTP BULK ENDPOINTS"));
                emitError("PTP_INTERFACE_NOT_FOUND", statusMessage, null);
                selectedDevice = null;
                return;
            }

            localConnection = usbManager.openDevice(device);
            if (localConnection == null) {
                throw new PtpException("USB_OPEN_FAILED", "Android could not open the USB device");
            }
            localInterface = endpoints.usbInterface;
            if (!localConnection.claimInterface(localInterface, true)) {
                throw new PtpException("USB_CLAIM_FAILED", "Could not claim the PTP USB interface");
            }
            if (!isCurrent(requestGeneration) || closed) {
                try { localConnection.releaseInterface(localInterface); } catch (Exception ignored) { }
                localConnection.close();
                return;
            }

            synchronized (stateLock) {
                // Publish only after openDevice and claimInterface both succeeded.
                deviceConnection = localConnection;
                claimedInterface = localInterface;
                bulkInEndpoint = endpoints.bulkIn;
                bulkOutEndpoint = endpoints.bulkOut;
                interruptInEndpoint = endpoints.interruptIn;
                selectedDevice = device;
                nextTransactionId = 1;
                sessionOpen = false;
            }
            localConnection = null;
            localInterface = null;

            setStatus(Status.VALIDATING, "OPENING PTP SESSION AND READING DEVICE INFO", capabilities);
            synchronized (ptpIoLock) {
                if (!isCurrent(requestGeneration) || closed) return;
                try {
                    executeCommandWithTransactionLocked(OP_OPEN_SESSION, 0, 1);
                    sessionOpen = true;
                } catch (PtpException e) {
                    if (e.responseCode == PTP_RESPONSE_SESSION_ALREADY_OPEN) {
                        // A previously opened device-side session is valid for this connection.
                        sessionOpen = true;
                    } else {
                        throw e;
                    }
                }

                byte[] payload = executeDataCommandLocked(OP_GET_DEVICE_INFO);
                if (payload.length > MAX_PTP_DEVICE_INFO_BYTES) {
                    throw new PtpException("PTP_DEVICE_INFO_TOO_LARGE", "PTP DeviceInfo exceeded size limit");
                }
                DeviceInfo parsed = parseDeviceInfo(payload);
                if (!isCurrent(requestGeneration) || closed) return;

                deviceInfo = Collections.unmodifiableMap(parsed.values);
                supportedOperations = Collections.unmodifiableSet(parsed.operations);
                capabilities = deriveCapabilities(parsed.operations);
            }

            if (!isCurrent(requestGeneration) || closed) return;
            Map<String, Object> infoEvent = new LinkedHashMap<>(deviceInfo);
            infoEvent.put("deviceId", device.getDeviceId());
            infoEvent.put("deviceName", safe(device.getDeviceName()));
            infoEvent.put("vendorId", device.getVendorId());
            infoEvent.put("productId", device.getProductId());
            infoEvent.put("transport", Type.USB_PTP.nativeName());
            emitEvent("ptpDeviceInfo", infoEvent);
            emitEvent("usbDeviceConnected", mapOf(
                    "deviceId", device.getDeviceId(),
                    "deviceName", safe(device.getDeviceName()),
                    "vendorId", device.getVendorId(),
                    "productId", device.getProductId(),
                    "hasPtpSession", true));
            setStatus(Status.CONNECTED, "USB PTP SESSION OPEN", capabilities);
            emitLog("INFO", "USB PTP session opened; model=" + valueAsText(deviceInfo.get("model"))
                    + ", operations=" + supportedOperations.size());
        } catch (PtpException e) {
            Log.w(TAG, "USB PTP connection failed [" + e.code + "]: " + e.getMessage());
            if (localConnection != null) {
                if (localInterface != null) {
                    try { localConnection.releaseInterface(localInterface); } catch (Exception ignored) { }
                }
                try { localConnection.close(); } catch (Exception ignored) { }
            }
            closeConnectionResources(false);
            if (isCurrent(requestGeneration) && !closed) {
                Status errorStatus = e.unsupported ? Status.UNSUPPORTED : Status.ERROR;
                capabilities = Capabilities.unsupported(Type.USB_PTP, e.getMessage());
                setStatus(errorStatus, e.getMessage(), capabilities);
                emitError(e.code, e.getMessage(), e);
            }
        } catch (Exception e) {
            Log.e(TAG, "USB PTP initialization failed", e);
            if (localConnection != null) {
                if (localInterface != null) {
                    try { localConnection.releaseInterface(localInterface); } catch (Exception ignored) { }
                }
                try { localConnection.close(); } catch (Exception ignored) { }
            }
            closeConnectionResources(false);
            if (isCurrent(requestGeneration) && !closed) {
                String message = "USB PTP INITIALIZATION FAILED: " + safeMessage(e);
                capabilities = Capabilities.unsupported(Type.USB_PTP, message);
                setStatus(Status.ERROR, message, capabilities);
                emitError("PTP_INITIALIZATION_FAILED", message, e);
            }
        }
    }

    private Capabilities deriveCapabilities(Set<Integer> operations) {
        boolean canCapture = operations.contains(OP_INITIATE_CAPTURE);
        boolean canTransfer = operations.contains(OP_GET_STORAGE_IDS)
                && operations.contains(OP_GET_OBJECT_HANDLES)
                && operations.contains(OP_GET_OBJECT_INFO)
                && operations.contains(OP_GET_OBJECT);
        return new Capabilities(
                Type.USB_PTP,
                true,
                canCapture,
                false, // PTP does not inherently define a generic live-view stream.
                canCapture,
                canTransfer,
                false, // This is a conventional USB PTP endpoint, not a separate red spectral sensor.
                false,
                false,
                canTransfer ? "PTP SESSION ACTIVE; OBJECT TRANSFER OPERATIONS AVAILABLE"
                        : (canCapture
                                ? "PTP SESSION ACTIVE; CAPTURE OPERATION ADVERTISED"
                                : "PTP SESSION ACTIVE; BASIC DEVICE INFORMATION AVAILABLE")
        );
    }

    private UsbDevice findRequestedDevice(ConnectionRequest request) {
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        if (devices == null || devices.isEmpty()) return null;
        String requestedId = trimToNull(request.getDeviceId());
        Integer requestedVendor = request.getVendorId();
        Integer requestedProduct = request.getProductId();
        Object optionDeviceName = request.getOptions().get("deviceName");
        if (requestedId == null && optionDeviceName != null) {
            requestedId = trimToNull(String.valueOf(optionDeviceName));
        }

        for (UsbDevice device : devices.values()) {
            if (requestedVendor != null && requestedVendor != device.getVendorId()) continue;
            if (requestedProduct != null && requestedProduct != device.getProductId()) continue;
            if (requestedId != null && !matchesDeviceId(device, requestedId)) continue;
            if (findPtpInterface(device, false) != null) return device;
        }
        return null;
    }

    private static boolean matchesDeviceId(UsbDevice device, String requestedId) {
        if (requestedId == null) return true;
        if (requestedId.equals(device.getDeviceName())) return true;
        try {
            if (Integer.parseInt(requestedId) == device.getDeviceId()) return true;
        } catch (NumberFormatException ignored) { }
        return false;
    }

    private static InterfaceEndpoints findPtpInterface(UsbDevice device, boolean allowVendorSpecific) {
        if (device == null) return null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            int deviceClass = usbInterface.getInterfaceClass();
            int subclass = usbInterface.getInterfaceSubclass();
            int protocol = usbInterface.getInterfaceProtocol();
            boolean standardPtp = deviceClass == USB_CLASS_STILL_IMAGE
                    && subclass == USB_SUBCLASS_STILL_IMAGE
                    && (protocol == USB_PROTOCOL_PTP || protocol == 0);
            boolean vendorPtp = allowVendorSpecific && deviceClass == 0xFF;
            if (!standardPtp && !vendorPtp) continue;

            UsbEndpoint bulkIn = null;
            UsbEndpoint bulkOut = null;
            UsbEndpoint interruptIn = null;
            for (int e = 0; e < usbInterface.getEndpointCount(); e++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(e);
                int type = endpoint.getType();
                int direction = endpoint.getDirection();
                if (type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (direction == UsbConstants.USB_DIR_IN && bulkIn == null) bulkIn = endpoint;
                    if (direction == UsbConstants.USB_DIR_OUT && bulkOut == null) bulkOut = endpoint;
                } else if (type == UsbConstants.USB_ENDPOINT_XFER_INT
                        && direction == UsbConstants.USB_DIR_IN && interruptIn == null) {
                    interruptIn = endpoint;
                }
            }
            if (bulkIn != null && bulkOut != null) {
                return new InterfaceEndpoints(usbInterface, bulkIn, bulkOut, interruptIn);
            }
        }
        return null;
    }

    private static String safeUsbName(UsbDevice device, boolean manufacturer) {
        try {
            String value = manufacturer ? device.getManufacturerName() : device.getProductName();
            return value == null ? "" : value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static UsbDevice getUsbDeviceExtra(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            }
            //noinspection deprecation
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void closeConnectionResources(boolean tryCloseSession) {
        synchronized (ptpIoLock) {
            UsbDeviceConnection connection = deviceConnection;
            UsbInterface usbInterface = claimedInterface;
            boolean hadSession = sessionOpen;
            sessionOpen = false;
            if (tryCloseSession && hadSession && connection != null
                    && bulkInEndpoint != null && bulkOutEndpoint != null) {
                try {
                    executeCommandLocked(OP_CLOSE_SESSION);
                } catch (Exception e) {
                    Log.d(TAG, "PTP CloseSession did not complete: " + safeMessage(e));
                }
            }
            deviceConnection = null;
            claimedInterface = null;
            bulkInEndpoint = null;
            bulkOutEndpoint = null;
            interruptInEndpoint = null;
            if (connection != null) {
                if (usbInterface != null) {
                    try { connection.releaseInterface(usbInterface); } catch (Exception ignored) { }
                }
                try { connection.close(); } catch (Exception ignored) { }
            }
        }
    }

    private void requireOperation(int operation, String operationName) throws PtpException {
        if (closed) throw new PtpException("TRANSPORT_CLOSED", "USB PTP transport is closed");
        if (!sessionOpen || deviceConnection == null) {
            throw new PtpException("PTP_SESSION_NOT_OPEN", "USB PTP session is not open");
        }
        if (!supportedOperations.contains(operation)) {
            throw new PtpException("PTP_OPERATION_UNSUPPORTED",
                    "Connected device does not advertise " + operationName, true);
        }
    }

    private byte[] executeDataCommandLocked(int operation, int... parameters) throws PtpException {
        return executeDataCommandWithLimitLocked(operation, MAX_PTP_OBJECT_INFO_BYTES, parameters);
    }

    private byte[] executeDataCommandWithLimitLocked(int operation, int maxBytes, int... parameters)
            throws PtpException {
        if (deviceConnection == null || bulkInEndpoint == null || bulkOutEndpoint == null) {
            throw new PtpException("PTP_USB_CLOSED", "USB PTP endpoints are not open");
        }
        int transactionId = nextTransactionId++;
        writeCommandLocked(operation, transactionId, parameters);
        PtpContainer first = readContainerLocked(DATA_TIMEOUT_MS, maxBytes);
        if (first.type == PTP_CONTAINER_RESPONSE) {
            validateResponse(first, transactionId, operation);
            throw new PtpException("PTP_NO_DATA_CONTAINER",
                    "PTP operation returned no data container (operation 0x"
                            + Integer.toHexString(operation) + ")");
        }
        if (first.type != PTP_CONTAINER_DATA || first.code != operation) {
            throw new PtpException("PTP_INVALID_DATA_CONTAINER",
                    "Unexpected PTP data container for operation 0x" + Integer.toHexString(operation));
        }
        if (first.transactionId != transactionId) {
            throw new PtpException("PTP_TRANSACTION_MISMATCH", "PTP data transaction ID did not match command");
        }
        PtpContainer response = readContainerLocked(RESPONSE_TIMEOUT_MS, 1024);
        validateResponse(response, transactionId, operation);
        return first.payload;
    }

    private void executeCommandLocked(int operation, int... parameters) throws PtpException {
        int transactionId = nextTransactionId++;
        executeCommandWithTransactionLocked(operation, transactionId, parameters);
    }

    private void executeCommandWithTransactionLocked(int operation, int transactionId, int... parameters)
            throws PtpException {
        if (deviceConnection == null || bulkInEndpoint == null || bulkOutEndpoint == null) {
            throw new PtpException("PTP_USB_CLOSED", "USB PTP endpoints are not open");
        }
        writeCommandLocked(operation, transactionId, parameters);
        PtpContainer response = readContainerLocked(RESPONSE_TIMEOUT_MS, 4096);
        if (response.type != PTP_CONTAINER_RESPONSE) {
            throw new PtpException("PTP_INVALID_RESPONSE", "Expected a PTP response container");
        }
        if (response.transactionId != transactionId) {
            throw new PtpException("PTP_TRANSACTION_MISMATCH", "PTP response transaction ID did not match command");
        }
        if (response.code == PTP_RESPONSE_OK) return;
        if (operation == OP_OPEN_SESSION && response.code == PTP_RESPONSE_SESSION_ALREADY_OPEN) return;
        throw new PtpException(response.code, "PTP operation failed with response 0x"
                + Integer.toHexString(response.code), false, response.code);
    }

    private void writeCommandLocked(int operation, int transactionId, int... parameters)
            throws PtpException {
        if (parameters == null) parameters = new int[0];
        ByteBuffer buffer = ByteBuffer.allocate(PTP_HEADER_LENGTH + parameters.length * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(buffer.capacity());
        buffer.putShort((short) PTP_CONTAINER_COMMAND);
        buffer.putShort((short) operation);
        buffer.putInt(transactionId);
        for (int parameter : parameters) buffer.putInt(parameter);
        writeExactLocked(buffer.array(), DATA_TIMEOUT_MS);
    }

    private void writeExactLocked(byte[] bytes, int timeoutMs) throws PtpException {
        UsbDeviceConnection connection = deviceConnection;
        UsbEndpoint endpoint = bulkOutEndpoint;
        if (connection == null || endpoint == null) {
            throw new PtpException("PTP_USB_CLOSED", "USB OUT endpoint is not open");
        }
        int offset = 0;
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (offset < bytes.length) {
            if (Thread.currentThread().isInterrupted()) {
                throw new PtpException("PTP_INTERRUPTED", "USB PTP transfer interrupted");
            }
            int chunk = Math.min(MAX_USB_CHUNK, bytes.length - offset);
            int remainingMs = (int) Math.max(1L, deadline - SystemClock.elapsedRealtime());
            int written = connection.bulkTransfer(endpoint, bytes, offset, chunk, remainingMs);
            if (written <= 0) {
                throw new PtpException("PTP_USB_WRITE_TIMEOUT", "USB bulk OUT transfer failed or timed out");
            }
            offset += written;
            if (SystemClock.elapsedRealtime() > deadline) {
                throw new PtpException("PTP_USB_WRITE_TIMEOUT", "USB bulk OUT transfer timed out");
            }
        }
    }

    private PtpContainer readContainerLocked(int timeoutMs, int maxPayloadBytes) throws PtpException {
        byte[] header = readExactLocked(PTP_HEADER_LENGTH, timeoutMs);
        ByteBuffer headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        long length = Integer.toUnsignedLong(headerBuffer.getInt());
        int type = Short.toUnsignedInt(headerBuffer.getShort());
        int code = Short.toUnsignedInt(headerBuffer.getShort());
        long transactionId = Integer.toUnsignedLong(headerBuffer.getInt());
        if (length < PTP_HEADER_LENGTH) {
            throw new PtpException("PTP_INVALID_CONTAINER_LENGTH", "PTP container length is smaller than its header");
        }
        long payloadLength = length - PTP_HEADER_LENGTH;
        if (payloadLength > maxPayloadBytes || payloadLength > Integer.MAX_VALUE) {
            throw new PtpException("PTP_CONTAINER_TOO_LARGE", "PTP container exceeds configured size limit");
        }
        byte[] payload = payloadLength == 0 ? new byte[0] : readExactLocked((int) payloadLength, timeoutMs);
        return new PtpContainer(type, code, transactionId, payload);
    }

    private byte[] readExactLocked(int length, int timeoutMs) throws PtpException {
        UsbDeviceConnection connection = deviceConnection;
        UsbEndpoint endpoint = bulkInEndpoint;
        if (connection == null || endpoint == null) {
            throw new PtpException("PTP_USB_CLOSED", "USB IN endpoint is not open");
        }
        byte[] result = new byte[length];
        int offset = 0;
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (offset < length) {
            if (Thread.currentThread().isInterrupted()) {
                throw new PtpException("PTP_INTERRUPTED", "USB PTP transfer interrupted");
            }
            int chunk = Math.min(MAX_USB_CHUNK, length - offset);
            int remainingMs = (int) Math.max(1L, deadline - SystemClock.elapsedRealtime());
            int count = connection.bulkTransfer(endpoint, result, offset, chunk, remainingMs);
            if (count <= 0) {
                throw new PtpException("PTP_USB_READ_TIMEOUT",
                        "USB bulk IN transfer failed or timed out (" + offset + "/" + length + " bytes)");
            }
            offset += count;
            if (SystemClock.elapsedRealtime() > deadline) {
                throw new PtpException("PTP_USB_READ_TIMEOUT", "USB bulk IN transfer timed out");
            }
        }
        return result;
    }

    private static void validateResponse(PtpContainer response, int transactionId, int operation)
            throws PtpException {
        if (response.type != PTP_CONTAINER_RESPONSE) {
            throw new PtpException("PTP_INVALID_RESPONSE", "Expected a PTP response container");
        }
        if (response.transactionId != transactionId) {
            throw new PtpException("PTP_TRANSACTION_MISMATCH", "PTP response transaction ID did not match command");
        }
        if (response.code == PTP_RESPONSE_OK) return;
        throw new PtpException(response.code, "PTP operation 0x" + Integer.toHexString(operation)
                + " returned response 0x" + Integer.toHexString(response.code), false, response.code);
    }

    private static long[] parseUInt32Array(byte[] payload, String name) throws PtpException {
        if (payload == null || payload.length < 4) {
            throw new PtpException("PTP_INVALID_ARRAY", name + " returned an invalid data array");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long count = Integer.toUnsignedLong(buffer.getInt());
        if (count > (payload.length - 4L) / 4L || count > 1_000_000L) {
            throw new PtpException("PTP_INVALID_ARRAY", name + " returned an invalid item count");
        }
        long[] values = new long[(int) count];
        for (int i = 0; i < values.length; i++) values[i] = Integer.toUnsignedLong(buffer.getInt());
        return values;
    }

    private static Map<String, Object> parseObjectInfo(byte[] payload) throws PtpException {
        if (payload == null || payload.length < 52 || payload.length > MAX_PTP_OBJECT_INFO_BYTES) {
            throw new PtpException("PTP_INVALID_OBJECT_INFO", "PTP ObjectInfo payload has an invalid size");
        }
        PtpDataCursor cursor = new PtpDataCursor(payload);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storageId", cursor.u32());
        result.put("objectFormat", cursor.u16());
        result.put("protectionStatus", cursor.u16());
        result.put("compressedSize", cursor.u32());
        result.put("thumbnailFormat", cursor.u16());
        result.put("thumbnailCompressedSize", cursor.u32());
        result.put("thumbnailWidth", cursor.u32());
        result.put("thumbnailHeight", cursor.u32());
        result.put("imageWidth", cursor.u32());
        result.put("imageHeight", cursor.u32());
        result.put("imageBitDepth", cursor.u32());
        result.put("parentObject", cursor.u32());
        result.put("associationType", cursor.u16());
        result.put("associationDescription", cursor.u32());
        result.put("sequenceNumber", cursor.u32());
        result.put("filename", cursor.ptpString());
        result.put("dateCreated", cursor.ptpString());
        result.put("dateModified", cursor.ptpString());
        result.put("keywords", cursor.ptpString());
        return Collections.unmodifiableMap(result);
    }

    private static DeviceInfo parseDeviceInfo(byte[] payload) throws PtpException {
        if (payload == null || payload.length < 16 || payload.length > MAX_PTP_DEVICE_INFO_BYTES) {
            throw new PtpException("PTP_INVALID_DEVICE_INFO", "PTP GetDeviceInfo payload has an invalid size");
        }
        PtpDataCursor cursor = new PtpDataCursor(payload);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("standardVersion", cursor.u16());
        values.put("vendorExtensionId", cursor.u32());
        values.put("vendorExtensionVersion", cursor.u16());
        values.put("vendorExtensionDescription", cursor.ptpString());
        values.put("functionalMode", cursor.u16());

        Set<Integer> operations = cursor.u16ArraySet();
        values.put("supportedOperationCount", operations.size());
        List<Integer> operationList = new ArrayList<>(operations);
        values.put("supportedOperations", Collections.unmodifiableList(operationList));

        values.put("supportedEventCount", cursor.skipU16Array());
        values.put("supportedPropertyCount", cursor.skipU16Array());
        values.put("captureFormatCount", cursor.skipU16Array());
        values.put("imageFormatCount", cursor.skipU16Array());
        values.put("manufacturer", cursor.ptpString());
        values.put("model", cursor.ptpString());
        values.put("deviceVersion", cursor.ptpString());
        values.put("serialNumber", cursor.ptpString());
        return new DeviceInfo(values, operations);
    }

    private static final class PtpDataCursor {
        private final ByteBuffer buffer;
        PtpDataCursor(byte[] data) { buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); }
        int u16() throws PtpException {
            require(2);
            return Short.toUnsignedInt(buffer.getShort());
        }
        long u32() throws PtpException {
            require(4);
            return Integer.toUnsignedLong(buffer.getInt());
        }
        String ptpString() throws PtpException {
            require(1);
            int count = Byte.toUnsignedInt(buffer.get());
            if (count == 0) return "";
            int byteCount = count * 2;
            require(byteCount);
            byte[] text = new byte[byteCount];
            buffer.get(text);
            int actualLength = text.length;
            if (actualLength >= 2 && text[actualLength - 1] == 0 && text[actualLength - 2] == 0) {
                actualLength -= 2; // PTP strings include a UTF-16 NUL terminator.
            }
            return new String(text, 0, actualLength, StandardCharsets.UTF_16LE).trim();
        }
        Set<Integer> u16ArraySet() throws PtpException {
            int count = arrayCount();
            require(count * 2);
            Set<Integer> result = new HashSet<>();
            for (int i = 0; i < count; i++) result.add(u16());
            return result;
        }
        int skipU16Array() throws PtpException {
            int count = arrayCount();
            require(count * 2);
            buffer.position(buffer.position() + count * 2);
            return count;
        }
        private int arrayCount() throws PtpException {
            long count = u32();
            if (count > 65_536L) throw new PtpException("PTP_ARRAY_TOO_LARGE", "PTP DeviceInfo array is too large");
            return (int) count;
        }
        private void require(int size) throws PtpException {
            if (size < 0 || buffer.remaining() < size) {
                throw new PtpException("PTP_TRUNCATED_DATA", "PTP data payload ended unexpectedly");
            }
        }
    }

    private static final class InterfaceEndpoints {
        final UsbInterface usbInterface;
        final UsbEndpoint bulkIn;
        final UsbEndpoint bulkOut;
        final UsbEndpoint interruptIn;
        InterfaceEndpoints(UsbInterface usbInterface, UsbEndpoint bulkIn,
                           UsbEndpoint bulkOut, UsbEndpoint interruptIn) {
            this.usbInterface = usbInterface;
            this.bulkIn = bulkIn;
            this.bulkOut = bulkOut;
            this.interruptIn = interruptIn;
        }
    }

    private static final class PtpContainer {
        final int type;
        final int code;
        final long transactionId;
        final byte[] payload;
        PtpContainer(int type, int code, long transactionId, byte[] payload) {
            this.type = type;
            this.code = code;
            this.transactionId = transactionId;
            this.payload = payload;
        }
    }

    private static final class DeviceInfo {
        final Map<String, Object> values;
        final Set<Integer> operations;
        DeviceInfo(Map<String, Object> values, Set<Integer> operations) {
            this.values = values;
            this.operations = operations;
        }
    }

    /** Exception with a stable machine-readable code for callers. */
    public static final class PtpException extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code;
        public final boolean unsupported;
        public final int responseCode;
        PtpException(String code, String message) { this(code, message, false, -1); }
        PtpException(String code, String message, boolean unsupported) { this(code, message, unsupported, -1); }
        PtpException(int responseCode, String message, boolean unsupported, int ignored) {
            this("PTP_RESPONSE_" + Integer.toHexString(responseCode), message, unsupported, responseCode);
        }
        private PtpException(String code, String message, boolean unsupported, int responseCode) {
            super(message);
            this.code = code;
            this.unsupported = unsupported;
            this.responseCode = responseCode;
        }
    }

    private boolean isCurrent(long value) {
        return !closed && generation.get() == value;
    }

    private void setStatus(Status next, String message, Capabilities nextCapabilities) {
        status = next == null ? Status.ERROR : next;
        statusMessage = message == null ? "" : message;
        if (nextCapabilities != null) capabilities = nextCapabilities;
        Listener current = listener;
        if (current != null) {
            try {
                current.onStatusChanged(Type.USB_PTP, status, statusMessage, capabilities);
            } catch (Exception e) {
                Log.w(TAG, "Transport status listener failed", e);
            }
        }
        emitEvent("transportStatus", mapOf(
                "transport", Type.USB_PTP.nativeName(),
                "status", status.nativeName(),
                "message", statusMessage,
                "capabilities", capabilities.toMap()));
    }

    private boolean reject(Listener target, String code, String message) {
        Listener current = target != null ? target : listener;
        if (current != null) {
            try { current.onError(Type.USB_PTP, code, message, null); }
            catch (Exception e) { Log.w(TAG, "Transport error listener failed", e); }
        }
        emitLog("WARN", message);
        return false;
    }

    private void emitError(String code, String message, Throwable cause) {
        Listener current = listener;
        if (current != null) {
            try { current.onError(Type.USB_PTP, code, message, cause); }
            catch (Exception e) { Log.w(TAG, "Transport error listener failed", e); }
        }
        emitEvent("transportError", mapOf("transport", Type.USB_PTP.nativeName(),
                "code", code, "message", message == null ? "" : message));
    }

    private void emitLog(String level, String message) {
        Listener current = listener;
        if (current != null) {
            try { current.onLog(Type.USB_PTP, level, message); }
            catch (Exception e) { Log.w(TAG, "Transport log listener failed", e); }
        }
        Log.println("ERROR".equals(level) ? Log.ERROR : "WARN".equals(level) ? Log.WARN : Log.INFO,
                TAG, message == null ? "" : message);
    }

    private void emitEvent(String event, Map<String, Object> payload) {
        Listener current = listener;
        if (current == null) return;
        try { current.onEvent(Type.USB_PTP, event, payload == null
                ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(payload))); }
        catch (Exception e) { Log.w(TAG, "Transport event listener failed: " + event, e); }
    }

    private void submitWorker(Runnable task) {
        try { ioExecutor.execute(task); }
        catch (RejectedExecutionException e) {
            if (!closed) {
                setStatus(Status.ERROR, "USB PTP WORKER IS UNAVAILABLE",
                        Capabilities.unsupported(Type.USB_PTP, "USB PTP WORKER IS UNAVAILABLE"));
            }
        }
    }

    private static boolean sameDevice(UsbDevice left, UsbDevice right) {
        return left != null && right != null && left.getDeviceId() == right.getDeviceId()
                && left.getVendorId() == right.getVendorId() && left.getProductId() == right.getProductId();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String valueAsText(Object value) { return value == null ? "UNKNOWN" : String.valueOf(value); }
    private static String safeMessage(Throwable error) {
        if (error == null) return "UNKNOWN ERROR";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (values == null) return result;
        if ((values.length & 1) != 0) throw new IllegalArgumentException("mapOf requires key/value pairs");
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }
}
    