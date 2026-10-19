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
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LANDCAM USB Video Class (UVC) video source for HDMI capture devices.
 *
 * <p>This class implements the shared CameraTransport contract, performs UVC
 * device discovery and permission handling, negotiates a standard MJPEG video
 * format when available, and emits complete JPEG frames through FrameListener.
 * It deliberately does not claim camera-control, still-photo capture, PTP, or
 * multispectral/NIR capabilities: an HDMI/UVC capture card is a video input.</p>
 *
 * <p>Important Android limitation: the public Java USB host API exposes bulk and
 * interrupt transfers, but not a general isochronous streaming API. Therefore,
 * this pure-Java implementation can stream only UVC devices that expose a bulk
 * IN endpoint and an MJPEG format. Many UVC capture cards use isochronous
 * endpoints and require a native/libusb UVC backend for actual streaming. Such
 * devices are reported as unsupported rather than falsely marked as streaming.</p>
 *
 * <p>Frame callbacks occur on a worker thread. Copy or consume the provided JPEG
 * promptly; the byte array is a standalone frame and is not reused by this class.</p>
 */
public final class UvcVideoSource implements CameraTransport {
    private static final String TAG = "LandCamUvc";

    private static final int USB_CLASS_VIDEO = 0x0E;
    private static final int USB_SUBCLASS_VIDEO_CONTROL = 0x01;
    private static final int USB_SUBCLASS_VIDEO_STREAMING = 0x02;

    private static final int UVC_CS_INTERFACE = 0x24;
    private static final int UVC_VS_FORMAT_MJPEG = 0x06;
    private static final int UVC_VS_FRAME_MJPEG = 0x07;
    private static final int UVC_VC_HEADER = 0x01;

    private static final int UVC_SET_CUR = 0x01;
    private static final int UVC_GET_CUR = 0x81;
    private static final int UVC_VS_PROBE_CONTROL = 0x01;
    private static final int UVC_VS_COMMIT_CONTROL = 0x02;

    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    private static final int DEFAULT_FRAME_INTERVAL_100NS = 333333;
    private static final int CONTROL_TIMEOUT_MS = 1500;
    private static final int STREAM_TIMEOUT_MS = 1000;
    private static final int MAX_CONTROL_LENGTH = 48;
    private static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;
    private static final int TRANSFER_BUFFER_SIZE = 256 * 1024;

    private final Context appContext;
    private final UsbManager usbManager;
    private final ExecutorService ioExecutor;
    private final AtomicLong generation = new AtomicLong(0L);
    private final Object stateLock = new Object();
    private final String permissionAction;

    private volatile Listener listener;
    private volatile FrameListener frameListener;
    private volatile Status status = Status.DISCONNECTED;
    private volatile Capabilities capabilities = Capabilities.unsupported(
            Type.HDMI_UVC, "NO UVC VIDEO SOURCE CONNECTED");
    private volatile String statusMessage = "NO UVC VIDEO SOURCE CONNECTED";
    private volatile boolean closed;
    private volatile boolean receiverRegistered;
    private volatile boolean streamRunning;

    private volatile UsbDevice selectedDevice;
    private volatile UsbDeviceConnection connection;
    private volatile UsbInterface claimedControlInterface;
    private volatile UsbInterface claimedStreamingInterface;
    private volatile UsbEndpoint bulkInEndpoint;
    private volatile int streamWidth;
    private volatile int streamHeight;
    private volatile int streamFrameInterval = DEFAULT_FRAME_INTERVAL_100NS;
    private volatile String streamFormat = "MJPEG";
    private volatile long framesDelivered;
    private volatile long lastFrameTimestampMs;
    private volatile BroadcastReceiver usbReceiver;
    private volatile ConnectionRequest pendingRequest;

    /** Receives complete MJPEG/JPEG frames from the capture source. */
    public interface FrameListener {
        void onFrame(byte[] jpegBytes, int width, int height, long timestampMillis);

        default void onFrameError(String code, String message, Throwable cause) { }
    }

    private static final class UvcInterfaceInfo {
        final UsbInterface controlInterface;
        final UsbInterface streamingInterface;
        final UsbEndpoint bulkIn;
        final boolean hasIsochronousIn;

        UvcInterfaceInfo(
                UsbInterface controlInterface,
                UsbInterface streamingInterface,
                UsbEndpoint bulkIn,
                boolean hasIsochronousIn
        ) {
            this.controlInterface = controlInterface;
            this.streamingInterface = streamingInterface;
            this.bulkIn = bulkIn;
            this.hasIsochronousIn = hasIsochronousIn;
        }

        boolean supportsJavaBulkStreaming() {
            return streamingInterface != null && bulkIn != null;
        }
    }

    private static final class MjpegFrameMode {
        final int formatIndex;
        final int frameIndex;
        final int width;
        final int height;
        final int defaultInterval;
        final int maxFrameSize;

        MjpegFrameMode(int formatIndex, int frameIndex, int width, int height,
                       int defaultInterval, int maxFrameSize) {
            this.formatIndex = formatIndex;
            this.frameIndex = frameIndex;
            this.width = width;
            this.height = height;
            this.defaultInterval = defaultInterval;
            this.maxFrameSize = maxFrameSize;
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("format", "MJPEG");
            result.put("formatIndex", formatIndex);
            result.put("frameIndex", frameIndex);
            result.put("width", width);
            result.put("height", height);
            result.put("frameInterval100ns", defaultInterval);
            result.put("maxFrameSize", maxFrameSize);
            return result;
        }
    }

    public UvcVideoSource(Context context) {
        this(context, null);
    }

    public UvcVideoSource(Context context, FrameListener frameListener) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        appContext = context.getApplicationContext();
        usbManager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
        this.frameListener = frameListener;
        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LANDCAM-UVC-IO");
            thread.setDaemon(true);
            return thread;
        });
        permissionAction = appContext.getPackageName() + ".UVC_PERMISSION";
        registerUsbReceiver();
    }

    public void setFrameListener(FrameListener listener) {
        frameListener = listener;
    }

    @Override
    public Type getType() {
        return Type.HDMI_UVC;
    }

    @Override
    public Status getStatus() {
        return status;
    }

    @Override
    public Capabilities getCapabilities() {
        return capabilities;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public long getFramesDelivered() {
        return framesDelivered;
    }

    public int getStreamWidth() {
        return streamWidth;
    }

    public int getStreamHeight() {
        return streamHeight;
    }

    /**
     * Enumerates attached USB devices and identifies possible UVC interfaces.
     * This does not request USB permission and does not open devices.
     */
    public static List<Map<String, Object>> scanDevices(Context context) {
        if (context == null) return Collections.emptyList();
        Context appContext = context.getApplicationContext();
        UsbManager manager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);
        if (manager == null) return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();
        try {
            for (UsbDevice device : manager.getDeviceList().values()) {
                UvcInterfaceInfo info = findUvcInterfaces(device);
                boolean hasVideoControl = hasInterface(device,
                        USB_CLASS_VIDEO, USB_SUBCLASS_VIDEO_CONTROL);
                boolean hasVideoStreaming = hasInterface(device,
                        USB_CLASS_VIDEO, USB_SUBCLASS_VIDEO_STREAMING);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("transport", Type.HDMI_UVC.nativeName());
                item.put("deviceId", device.getDeviceId());
                item.put("deviceName", safe(device.getDeviceName()));
                item.put("vendorId", device.getVendorId());
                item.put("productId", device.getProductId());
                item.put("deviceClass", device.getDeviceClass());
                item.put("deviceSubclass", device.getDeviceSubclass());
                item.put("deviceProtocol", device.getDeviceProtocol());
                item.put("interfaceCount", device.getInterfaceCount());
                item.put("hasUvcControlInterface", hasVideoControl);
                item.put("hasUvcStreamingInterface", hasVideoStreaming);
                item.put("hasBulkVideoIn", info != null && info.bulkIn != null);
                item.put("hasIsochronousVideoIn", info != null && info.hasIsochronousIn);
                item.put("hasPermission", manager.hasPermission(device));
                item.put("javaBulkMjpegPossible", info != null && info.supportsJavaBulkStreaming());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    item.put("manufacturer", safe(device.getManufacturerName()));
                    item.put("product", safe(device.getProductName()));
                }
                result.add(Collections.unmodifiableMap(item));
            }
        } catch (Exception e) {
            Log.w(TAG, "USB/UVC device scan failed", e);
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean connect(ConnectionRequest request, Listener newListener) {
        if (request == null || request.getType() != Type.HDMI_UVC) {
            return reject(newListener, "INVALID_TRANSPORT_REQUEST",
                    "UvcVideoSource requires an HDMI_UVC ConnectionRequest");
        }
        if (closed) {
            return reject(newListener, "SOURCE_CLOSED", "UVC source is closed");
        }
        if (usbManager == null) {
            setStatus(Status.UNSUPPORTED, "ANDROID USB HOST SERVICE IS UNAVAILABLE",
                    Capabilities.unsupported(Type.HDMI_UVC, "USB HOST SERVICE UNAVAILABLE"));
            return reject(newListener, "USB_HOST_UNAVAILABLE", statusMessage);
        }

        synchronized (stateLock) {
            if (status.isBusy() || status.isConnected()) {
                return reject(newListener, "SOURCE_BUSY",
                        "Disconnect the current UVC source before connecting another");
            }
            listener = newListener;
            capabilities = Capabilities.unsupported(Type.HDMI_UVC, "UVC SOURCE NOT YET VALIDATED");
        }

        UsbDevice device = findRequestedDevice(request);
        if (device == null) {
            String message = usbManager.getDeviceList().isEmpty()
                    ? "NO USB DEVICE ATTACHED"
                    : "NO MATCHING USB VIDEO CLASS DEVICE FOUND";
            setStatus(Status.UNSUPPORTED, message,
                    Capabilities.unsupported(Type.HDMI_UVC, message));
            emitError("UVC_DEVICE_NOT_FOUND", message, null);
            return false;
        }

        UvcInterfaceInfo info = findUvcInterfaces(device);
        if (info == null || info.streamingInterface == null) {
            String message = "SELECTED USB DEVICE DOES NOT EXPOSE A UVC VIDEO STREAMING INTERFACE";
            setStatus(Status.UNSUPPORTED, message,
                    Capabilities.unsupported(Type.HDMI_UVC, message));
            emitError("UVC_INTERFACE_NOT_FOUND", message, null);
            return false;
        }
        if (!info.supportsJavaBulkStreaming()) {
            String message = info.hasIsochronousIn
                    ? "UVC SOURCE USES ISOCHRONOUS VIDEO; A NATIVE UVC/LIBUSB BACKEND IS REQUIRED"
                    : "UVC SOURCE HAS NO SUPPORTED BULK VIDEO-IN ENDPOINT";
            setStatus(Status.UNSUPPORTED, message,
                    Capabilities.unsupported(Type.HDMI_UVC, message));
            emitEvent("uvcDeviceDetected", deviceDetails(device, info));
            emitError("UVC_STREAMING_BACKEND_UNAVAILABLE", message, null);
            return false;
        }

        selectedDevice = device;
        pendingRequest = request;
        long requestedGeneration = generation.incrementAndGet();
        setStatus(Status.CHECKING_PERMISSION, "CHECKING USB VIDEO SOURCE PERMISSION",
                Capabilities.unsupported(Type.HDMI_UVC, "WAITING FOR USB PERMISSION"));

        if (usbManager.hasPermission(device)) {
            scheduleOpen(device, requestedGeneration, request);
            return true;
        }

        try {
            requestDevicePermission(device);
            emitEvent("uvcPermissionRequested", deviceDetails(device, info));
            return true;
        } catch (Exception e) {
            setStatus(Status.ERROR, "USB VIDEO PERMISSION REQUEST FAILED",
                    Capabilities.unsupported(Type.HDMI_UVC, safeMessage(e)));
            emitError("UVC_PERMISSION_REQUEST_FAILED", statusMessage, e);
            return false;
        }
    }

    /** Stop video streaming and release all USB resources; safe to call repeatedly. */
    @Override
    public void disconnect() {
        generation.incrementAndGet();
        streamRunning = false;
        closeUsbResources();
        selectedDevice = null;
        pendingRequest = null;
        streamWidth = 0;
        streamHeight = 0;
        framesDelivered = 0L;
        lastFrameTimestampMs = 0L;
        capabilities = Capabilities.unsupported(Type.HDMI_UVC, "UVC SOURCE DISCONNECTED");
        setStatus(Status.DISCONNECTED, "UVC SOURCE DISCONNECTED", capabilities);
    }

    @Override
    public void close() {
        if (closed) return;
        disconnect();
        closed = true;
        unregisterUsbReceiver();
        ioExecutor.shutdownNow();
    }

    private UsbDevice findRequestedDevice(ConnectionRequest request) {
        Map<String, UsbDevice> devices = usbManager.getDeviceList();
        String requestedId = request.getDeviceId();
        Integer requestedVendor = request.getVendorId();
        Integer requestedProduct = request.getProductId();

        for (UsbDevice device : devices.values()) {
            if (!hasInterface(device, USB_CLASS_VIDEO, USB_SUBCLASS_VIDEO_STREAMING)) continue;
            if (requestedId != null && !requestedId.isEmpty()) {
                String idText = String.valueOf(device.getDeviceId());
                String name = safe(device.getDeviceName());
                if (!requestedId.equals(idText) && !requestedId.equals(name)) continue;
            }
            if (requestedVendor != null && requestedVendor != device.getVendorId()) continue;
            if (requestedProduct != null && requestedProduct != device.getProductId()) continue;
            return device;
        }
        return null;
    }

    private void scheduleOpen(UsbDevice device, long requestedGeneration, ConnectionRequest request) {
        try {
            ioExecutor.execute(() -> openAndStream(device, requestedGeneration, request));
        } catch (RejectedExecutionException e) {
            setStatus(Status.ERROR, "UVC WORKER IS NOT AVAILABLE",
                    Capabilities.unsupported(Type.HDMI_UVC, "UVC WORKER IS NOT AVAILABLE"));
            emitError("UVC_WORKER_UNAVAILABLE", statusMessage, e);
        }
    }

    private void openAndStream(UsbDevice device, long requestedGeneration, ConnectionRequest request) {
        boolean readLoopStarted = false;
        UsbDeviceConnection opened = null;
        UvcInterfaceInfo info = findUvcInterfaces(device);
        if (info == null || !info.supportsJavaBulkStreaming()) return;

        try {
            if (!isCurrent(requestedGeneration)) return;
            if (!usbManager.hasPermission(device)) {
                setStatus(Status.CHECKING_PERMISSION, "WAITING FOR USB VIDEO PERMISSION",
                        Capabilities.unsupported(Type.HDMI_UVC, "WAITING FOR USB PERMISSION"));
                return;
            }

            opened = usbManager.openDevice(device);
            if (opened == null) {
                throw new IllegalStateException("ANDROID COULD NOT OPEN THE UVC DEVICE");
            }
            synchronized (stateLock) {
                if (!isCurrent(requestedGeneration)) {
                    opened.close();
                    return;
                }
                connection = opened;
            }

            if (info.controlInterface != null && info.controlInterface.getId() != info.streamingInterface.getId()) {
                if (!opened.claimInterface(info.controlInterface, true)) {
                    throw new IllegalStateException("COULD NOT CLAIM UVC VIDEO-CONTROL INTERFACE");
                }
                claimedControlInterface = info.controlInterface;
            }
            if (!opened.claimInterface(info.streamingInterface, true)) {
                throw new IllegalStateException("COULD NOT CLAIM UVC VIDEO-STREAMING INTERFACE");
            }
            claimedStreamingInterface = info.streamingInterface;
            bulkInEndpoint = info.bulkIn;

            List<MjpegFrameMode> modes = parseMjpegFrameModes(opened);
            if (modes.isEmpty()) {
                throw new UnsupportedOperationException(
                        "UVC MJPEG FRAME DESCRIPTORS ARE NOT EXPOSED; THIS SOURCE CURRENTLY REQUIRES MJPEG");
            }
            int preferredWidth = intOption(request, "width", DEFAULT_WIDTH);
            int preferredHeight = intOption(request, "height", DEFAULT_HEIGHT);
            MjpegFrameMode selectedMode = chooseFrameMode(modes, preferredWidth, preferredHeight);
            int payloadSize = Math.max(1024, info.bulkIn.getMaxPacketSize());
            int[] negotiated = negotiateMjpeg(opened, info.streamingInterface.getId(),
                    selectedMode, payloadSize, parseUvcVersion(opened));
            if (negotiated == null) {
                throw new UnsupportedOperationException("UVC MJPEG PROBE/COMMIT NEGOTIATION FAILED");
            }
            MjpegFrameMode negotiatedMode = findFrameMode(modes, negotiated[1], negotiated[2]);
            if (negotiatedMode == null) {
                throw new UnsupportedOperationException(
                        "UVC DEVICE NEGOTIATED AN MJPEG FORMAT/FRAME NOT PRESENT IN ITS DESCRIPTORS");
            }
            selectedMode = negotiatedMode;

            if (!opened.setInterface(info.streamingInterface)) {
                throw new IllegalStateException("COULD NOT SELECT UVC STREAMING INTERFACE SETTING");
            }

            if (!isCurrent(requestedGeneration)) return;
            streamWidth = selectedMode.width;
            streamHeight = selectedMode.height;
            streamFrameInterval = negotiated[3] > 0 ? negotiated[3]
                    : (selectedMode.defaultInterval > 0
                    ? selectedMode.defaultInterval : DEFAULT_FRAME_INTERVAL_100NS);
            streamFormat = "MJPEG";
            framesDelivered = 0L;
            lastFrameTimestampMs = 0L;
            capabilities = new Capabilities(Type.HDMI_UVC, true, false, true,
                    false, false, false, false, false,
                    "UVC MJPEG VIDEO INPUT; NO CAMERA CONTROL, CAPTURE, OR NIR CLAIMED");
            setStatus(Status.CONNECTED, "UVC VIDEO SOURCE CONFIGURED",
                    capabilities);
            emitEvent("uvcVideoConfigured", mapOf(
                    "device", deviceDetails(device, info),
                    "width", streamWidth,
                    "height", streamHeight,
                    "format", streamFormat,
                    "frameInterval100ns", streamFrameInterval,
                    "frameRate", streamFrameInterval > 0 ? 10000000.0d / streamFrameInterval : 0.0d,
                    "frameModes", modesToMaps(modes),
                    "streamingInterface", info.streamingInterface.getId(),
                    "alternateSetting", info.streamingInterface.getAlternateSetting(),
                    "endpointAddress", info.bulkIn.getAddress()));

            streamRunning = true;
            readLoopStarted = true;
            readBulkMjpegFrames(opened, info.bulkIn, requestedGeneration, streamWidth, streamHeight);
        } catch (UnsupportedOperationException e) {
            if (isCurrent(requestedGeneration)) {
                String message = safeMessage(e);
                setStatus(Status.UNSUPPORTED, message,
                        Capabilities.unsupported(Type.HDMI_UVC, message));
                emitError("UVC_FORMAT_UNSUPPORTED", message, e);
            }
        } catch (Exception e) {
            if (isCurrent(requestedGeneration)) {
                String message = "UVC SETUP/STREAM FAILED: " + safeMessage(e);
                setStatus(Status.ERROR, message,
                        Capabilities.unsupported(Type.HDMI_UVC, message));
                emitError("UVC_SETUP_FAILED", message, e);
            }
        } finally {
            streamRunning = false;
            if (readLoopStarted && isCurrent(requestedGeneration)) {
                String message = "UVC VIDEO STREAM STOPPED";
                Capabilities unavailable = Capabilities.unsupported(Type.HDMI_UVC, message);
                capabilities = unavailable;
                setStatus(Status.ERROR, message, unavailable);
                emitError("UVC_STREAM_STOPPED", message, null);
            }
            // Always close resources when this worker exits. On an ordinary
            // disconnect the generation has already changed; on setup failure
            // this also prevents permission/claim leaks. The single-threaded
            // executor serializes the next open operation after this cleanup.
            closeUsbResources();
        }
    }

    private void readBulkMjpegFrames(UsbDeviceConnection activeConnection, UsbEndpoint endpoint,
                                     long requestedGeneration, int width, int height) {
        byte[] transfer = new byte[TRANSFER_BUFFER_SIZE];
        ByteArrayOutputBuilder frame = new ByteArrayOutputBuilder(MAX_FRAME_BYTES);
        int currentFid = -1;
        long streamStartedAt = System.currentTimeMillis();
        long lastProgressAt = streamStartedAt;

        while (streamRunning && isCurrent(requestedGeneration)) {
            int count;
            try {
                count = activeConnection.bulkTransfer(endpoint, transfer, 0, transfer.length,
                        STREAM_TIMEOUT_MS);
            } catch (Exception e) {
                if (isCurrent(requestedGeneration)) {
                    emitFrameError("UVC_BULK_READ_FAILED", safeMessage(e), e);
                }
                break;
            }
            if (!streamRunning || !isCurrent(requestedGeneration)) break;
            if (count <= 0) {
                long now = System.currentTimeMillis();
                long lastValidFrame = lastFrameTimestampMs > 0L
                        ? lastFrameTimestampMs : streamStartedAt;
                if (now - lastValidFrame > 10_000L) {
                    emitLog("ERROR", "No valid UVC MJPEG frame received for 10 seconds");
                    break;
                }
                if (now - lastProgressAt > 10_000L) {
                    emitLog("WARN", "No UVC bulk data received for 10 seconds");
                    lastProgressAt = now;
                }
                continue;
            }
            lastProgressAt = System.currentTimeMillis();

            // A bulkTransfer usually returns one UVC bulk payload transfer. Parse
            // the UVC header, collect payload bytes, and commit only at EOF.
            int offset = 0;
            boolean consumedPayload = false;
            while (offset + 2 <= count) {
                int headerLength = transfer[offset] & 0xFF;
                if (headerLength < 2 || headerLength > count - offset) {
                    // Some USB stacks may split a payload header across reads;
                    // without a framing boundary it is not safe to decode bytes.
                    frame.reset();
                    break;
                }
                int flags = transfer[offset + 1] & 0xFF;
                int fid = flags & 0x01;
                if (currentFid != -1 && fid != currentFid && frame.size() > 0) {
                    // FID changed before EOF: retire the incomplete frame.
                    frame.reset();
                }
                currentFid = fid;
                if ((flags & 0x40) != 0) {
                    frame.reset();
                    offset += Math.max(2, headerLength);
                    continue;
                }
                int payloadOffset = offset + headerLength;
                int payloadLength = count - payloadOffset;
                if (payloadLength > 0 && !frame.append(transfer, payloadOffset, payloadLength)) {
                    frame.reset();
                    emitFrameError("UVC_FRAME_TOO_LARGE", "UVC MJPEG frame exceeded the configured size limit", null);
                } else if (payloadLength > 0) {
                    consumedPayload = true;
                }
                if ((flags & 0x02) != 0) {
                    byte[] completed = frame.toByteArray();
                    frame.reset();
                    if (isJpeg(completed)) {
                        deliverFrame(completed, width, height, requestedGeneration);
                    } else if (completed.length > 0) {
                        emitLog("WARN", "Dropped UVC MJPEG payload without JPEG SOI/EOI markers");
                    }
                }
                // On Android's bulk endpoint API a transfer usually returns one
                // UVC payload. Avoid inventing packet boundaries if it does not.
                break;
            }
            if (!consumedPayload && count > 0 && (transfer[0] & 0xFF) < 2) {
                frame.reset();
            }
            long lastValidFrame = lastFrameTimestampMs > 0L
                    ? lastFrameTimestampMs : streamStartedAt;
            if (System.currentTimeMillis() - lastValidFrame > 10_000L) {
                emitLog("ERROR", "No valid UVC MJPEG frame received for 10 seconds");
                break;
            }
        }

        if (isCurrent(requestedGeneration) && status == Status.STREAMING) {
            setStatus(Status.CONNECTED, "UVC SOURCE CONNECTED; STREAM ENDED",
                    capabilities);
        }
    }

    private void deliverFrame(byte[] jpeg, int width, int height, long requestedGeneration) {
        if (!isCurrent(requestedGeneration) || jpeg == null || jpeg.length == 0) return;
        long timestamp = System.currentTimeMillis();
        framesDelivered++;
        lastFrameTimestampMs = timestamp;
        FrameListener currentFrameListener = frameListener;
        if (currentFrameListener != null) {
            try {
                currentFrameListener.onFrame(jpeg, width, height, timestamp);
            } catch (Exception e) {
                Log.e(TAG, "UVC frame listener failed", e);
            }
        }
        if (framesDelivered == 1L) {
            setStatus(Status.STREAMING, "UVC VIDEO STREAMING",
                    capabilities);
            emitEvent("uvcFirstFrame", mapOf(
                    "width", width,
                    "height", height,
                    "format", "MJPEG",
                    "timestampMillis", timestamp));
        }
        if (framesDelivered % 120L == 0L) {
            emitEvent("uvcStreamStats", mapOf(
                    "frames", framesDelivered,
                    "width", width,
                    "height", height,
                    "timestampMillis", timestamp,
                    "lastFrameTimestampMillis", lastFrameTimestampMs));
        }
    }

    private int[] negotiateMjpeg(UsbDeviceConnection activeConnection, int streamingInterfaceId,
                                 MjpegFrameMode mode, int payloadSize, int uvcVersion) {
        List<Integer> lengths = new ArrayList<>();
        if (uvcVersion >= 0x0150) {
            lengths.add(48);
            lengths.add(34);
            lengths.add(26);
        } else if (uvcVersion >= 0x0110) {
            lengths.add(34);
            lengths.add(26);
            lengths.add(48);
        } else {
            lengths.add(26);
            lengths.add(34);
            lengths.add(48);
        }

        for (Integer length : lengths) {
            byte[] probe = createProbeBlock(length, mode, payloadSize);
            int setProbe = controlTransfer(activeConnection, false, UVC_SET_CUR,
                    UVC_VS_PROBE_CONTROL, streamingInterfaceId, probe, length);
            if (setProbe < 0) continue;

            byte[] negotiated = new byte[length];
            int getProbe = controlTransfer(activeConnection, true, UVC_GET_CUR,
                    UVC_VS_PROBE_CONTROL, streamingInterfaceId, negotiated, length);
            if (getProbe < 4) continue;

            // Devices may return a short payload; preserve the requested values
            // for fields that were not returned while using the negotiated block.
            if (getProbe < length) {
                System.arraycopy(probe, getProbe, negotiated, getProbe, length - getProbe);
            }
            int returnedFormat = negotiated[2] & 0xFF;
            int returnedFrame = negotiated[3] & 0xFF;
            if (returnedFormat == 0 || returnedFrame == 0) continue;
            int committed = controlTransfer(activeConnection, false, UVC_SET_CUR,
                    UVC_VS_COMMIT_CONTROL, streamingInterfaceId, negotiated, length);
            if (committed >= 0) {
                int interval = u32(negotiated, 4) > Integer.MAX_VALUE
                        ? 0 : (int) u32(negotiated, 4);
                return new int[]{length, returnedFormat, returnedFrame, interval};
            }
        }
        return null;
    }

    private byte[] createProbeBlock(int length, MjpegFrameMode mode, int payloadSize) {
        byte[] data = new byte[length];
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(0, (short) 1); // bmHint: prefer the requested frame interval.
        data[2] = (byte) mode.formatIndex;
        data[3] = (byte) mode.frameIndex;
        buffer.putInt(4, mode.defaultInterval > 0
                ? mode.defaultInterval : DEFAULT_FRAME_INTERVAL_100NS);
        buffer.putInt(18, Math.max(1, mode.maxFrameSize));
        buffer.putInt(22, Math.max(1, payloadSize));
        return data;
    }

    private int controlTransfer(UsbDeviceConnection activeConnection, boolean input, int request,
                                int selector, int interfaceId, byte[] buffer, int length) {
        int requestType = UsbConstants.USB_TYPE_CLASS | 0x01;
        if (input) requestType |= UsbConstants.USB_DIR_IN;
        return activeConnection.controlTransfer(requestType, request, selector << 8,
                interfaceId, buffer, length, CONTROL_TIMEOUT_MS);
    }

    private int parseUvcVersion(UsbDeviceConnection activeConnection) {
        byte[] raw;
        try {
            raw = activeConnection.getRawDescriptors();
        } catch (Exception e) {
            return 0;
        }
        if (raw == null) return 0;
        int offset = 0;
        while (offset + 3 <= raw.length) {
            int length = raw[offset] & 0xFF;
            if (length < 2 || offset + length > raw.length) break;
            int type = raw[offset + 1] & 0xFF;
            if (type == UVC_CS_INTERFACE && length >= 5
                    && (raw[offset + 2] & 0xFF) == UVC_VC_HEADER) {
                return u16(raw, offset + 3);
            }
            offset += length;
        }
        return 0;
    }

    /** Parses MJPEG frame descriptors exposed in the raw USB descriptor blob. */
    private List<MjpegFrameMode> parseMjpegFrameModes(UsbDeviceConnection activeConnection) {
        List<MjpegFrameMode> modes = new ArrayList<>();
        byte[] raw;
        try {
            raw = activeConnection.getRawDescriptors();
        } catch (Exception e) {
            return modes;
        }
        if (raw == null || raw.length < 5) return modes;

        int offset = 0;
        int currentMjpegFormat = 0;
        while (offset + 2 <= raw.length) {
            int length = raw[offset] & 0xFF;
            if (length < 2 || offset + length > raw.length) break;
            int descriptorType = raw[offset + 1] & 0xFF;
            if (descriptorType == UVC_CS_INTERFACE && length >= 3) {
                int subtype = raw[offset + 2] & 0xFF;
                if (subtype == UVC_VS_FORMAT_MJPEG && length >= 5) {
                    currentMjpegFormat = raw[offset + 3] & 0xFF;
                } else if (subtype == UVC_VS_FRAME_MJPEG && length >= 26
                        && currentMjpegFormat > 0) {
                    int frameIndex = raw[offset + 3] & 0xFF;
                    int width = u16(raw, offset + 4);
                    int height = u16(raw, offset + 6);
                    long rawMaxSize = u32(raw, offset + 16);
                    long defaultInterval = u32(raw, offset + 20);
                    if (frameIndex > 0 && width > 0 && height > 0) {
                        int maxSize = rawMaxSize > 0 && rawMaxSize <= Integer.MAX_VALUE
                                ? (int) rawMaxSize : Math.min(MAX_FRAME_BYTES, width * height * 2);
                        int interval = defaultInterval > 0 && defaultInterval <= Integer.MAX_VALUE
                                ? (int) defaultInterval : DEFAULT_FRAME_INTERVAL_100NS;
                        modes.add(new MjpegFrameMode(currentMjpegFormat, frameIndex,
                                width, height, interval, maxSize));
                    }
                } else if (subtype == 0x01) {
                    // A Video Control header separates the VC descriptors from
                    // VS descriptors; don't carry an MJPEG format across it.
                    currentMjpegFormat = 0;
                }
            }
            offset += length;
        }

        // Remove malformed duplicates while preserving descriptor order.
        Map<String, MjpegFrameMode> unique = new LinkedHashMap<>();
        for (MjpegFrameMode mode : modes) {
            unique.put(mode.formatIndex + ":" + mode.frameIndex, mode);
        }
        return new ArrayList<>(unique.values());
    }

    private static MjpegFrameMode chooseFrameMode(List<MjpegFrameMode> modes,
                                                   int preferredWidth, int preferredHeight) {
        return Collections.min(modes, Comparator.comparingLong(mode ->
                Math.abs((long) mode.width - preferredWidth)
                        + Math.abs((long) mode.height - preferredHeight)));
    }

    private static MjpegFrameMode findFrameMode(List<MjpegFrameMode> modes,
                                                 int formatIndex, int frameIndex) {
        for (MjpegFrameMode mode : modes) {
            if (mode.formatIndex == formatIndex && mode.frameIndex == frameIndex) return mode;
        }
        return null;
    }

    private static List<Map<String, Object>> modesToMaps(List<MjpegFrameMode> modes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MjpegFrameMode mode : modes) result.add(mode.toMap());
        return Collections.unmodifiableList(result);
    }

    private void requestDevicePermission(UsbDevice device) {
        Intent intent = new Intent(permissionAction).setPackage(appContext.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(appContext,
                device.getDeviceId(), intent, flags);
        usbManager.requestPermission(device, pendingIntent);
    }

    private void registerUsbReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                String action = intent.getAction();
                UsbDevice device = getUsbDeviceExtra(intent);
                if (permissionAction.equals(action)) {
                    if (device == null) {
                        failPermission("USB PERMISSION RESPONSE DID NOT INCLUDE A DEVICE");
                        return;
                    }
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (!granted) {
                        setStatus(Status.ERROR, "USB VIDEO PERMISSION DENIED",
                                Capabilities.unsupported(Type.HDMI_UVC, "USB VIDEO PERMISSION DENIED"));
                        emitError("UVC_PERMISSION_DENIED", statusMessage, null);
                        return;
                    }
                    UsbDevice expected = selectedDevice;
                    if (expected == null || expected.getDeviceId() != device.getDeviceId()) return;
                    long currentGeneration = generation.get();
                    ConnectionRequest request = pendingRequest;
                    if (request == null) {
                        request = ConnectionRequest.hdmiUvc(String.valueOf(device.getDeviceId()));
                    }
                    scheduleOpen(device, currentGeneration, request);
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    UsbDevice selected = selectedDevice;
                    if (device != null && selected != null
                            && device.getDeviceId() == selected.getDeviceId()) {
                        generation.incrementAndGet();
                        streamRunning = false;
                        closeUsbResources();
                        selectedDevice = null;
                        Capabilities noSource = Capabilities.unsupported(Type.HDMI_UVC,
                                "UVC DEVICE DETACHED");
                        capabilities = noSource;
                        setStatus(Status.DISCONNECTED, "UVC DEVICE DETACHED", noSource);
                        emitEvent("uvcDeviceDetached", deviceDetails(device, null));
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) && device != null) {
                    UvcInterfaceInfo info = findUvcInterfaces(device);
                    if (info != null) {
                        emitEvent("uvcDeviceAttached", deviceDetails(device, info));
                    }
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appContext.registerReceiver(usbReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "Could not register UVC USB receiver", e);
        }
    }

    private void unregisterUsbReceiver() {
        if (!receiverRegistered || usbReceiver == null) return;
        try {
            appContext.unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
        usbReceiver = null;
    }

    private void failPermission(String message) {
        setStatus(Status.ERROR, message, Capabilities.unsupported(Type.HDMI_UVC, message));
        emitError("UVC_PERMISSION_RESPONSE_INVALID", message, null);
    }

    private void closeUsbResources() {
        UsbDeviceConnection activeConnection;
        UsbInterface streaming;
        UsbInterface control;
        synchronized (stateLock) {
            activeConnection = connection;
            streaming = claimedStreamingInterface;
            control = claimedControlInterface;
            connection = null;
            claimedStreamingInterface = null;
            claimedControlInterface = null;
            bulkInEndpoint = null;
        }
        if (activeConnection != null) {
            try {
                if (streaming != null) activeConnection.releaseInterface(streaming);
            } catch (Exception ignored) { }
            try {
                if (control != null) activeConnection.releaseInterface(control);
            } catch (Exception ignored) { }
            try {
                activeConnection.close();
            } catch (Exception ignored) { }
        }
    }

    private boolean isCurrent(long requestedGeneration) {
        return !closed && generation.get() == requestedGeneration;
    }

    private void setStatus(Status newStatus, String message, Capabilities newCapabilities) {
        if (newStatus == null) return;
        status = newStatus;
        statusMessage = message == null ? "" : message;
        if (newCapabilities != null) capabilities = newCapabilities;
        Listener currentListener = listener;
        if (currentListener != null) {
            try {
                currentListener.onStatusChanged(Type.HDMI_UVC, newStatus,
                        statusMessage, capabilities);
            } catch (Exception e) {
                Log.w(TAG, "Status listener failed", e);
            }
        }
        emitEvent("transportStatus", mapOf(
                "transport", Type.HDMI_UVC.nativeName(),
                "status", newStatus.nativeName(),
                "message", statusMessage,
                "capabilities", capabilities.toMap()));
    }

    private boolean reject(Listener callback, String code, String message) {
        if (callback != null) {
            try {
                callback.onError(Type.HDMI_UVC, code, message, null);
            } catch (Exception ignored) { }
        }
        return false;
    }

    private void emitLog(String level, String message) {
        Listener currentListener = listener;
        if (currentListener != null) {
            try { currentListener.onLog(Type.HDMI_UVC, level, message); }
            catch (Exception ignored) { }
        }
        if ("ERROR".equalsIgnoreCase(level)) Log.e(TAG, message);
        else if ("WARN".equalsIgnoreCase(level)) Log.w(TAG, message);
        else Log.i(TAG, message);
    }

    private void emitError(String code, String message, Throwable cause) {
        Listener currentListener = listener;
        if (currentListener != null) {
            try { currentListener.onError(Type.HDMI_UVC, code, message, cause); }
            catch (Exception ignored) { }
        }
        emitLog("ERROR", code + ": " + message);
    }

    private void emitFrameError(String code, String message, Throwable cause) {
        FrameListener callback = frameListener;
        if (callback != null) {
            try { callback.onFrameError(code, message, cause); }
            catch (Exception ignored) { }
        }
        emitError(code, message, cause);
    }

    private void emitEvent(String eventName, Map<String, Object> data) {
        Listener currentListener = listener;
        if (currentListener != null) {
            try { currentListener.onEvent(Type.HDMI_UVC, eventName, data); }
            catch (Exception e) { Log.w(TAG, "UVC event listener failed", e); }
        }
    }

    private static UvcInterfaceInfo findUvcInterfaces(UsbDevice device) {
        if (device == null) return null;
        UsbInterface control = null;
        UsbInterface bulkStream = null;
        UsbInterface isoStream = null;
        UsbEndpoint bulkIn = null;
        boolean hasIsoIn = false;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() != USB_CLASS_VIDEO) continue;
            if (intf.getInterfaceSubclass() == USB_SUBCLASS_VIDEO_CONTROL && control == null) {
                control = intf;
            }
            if (intf.getInterfaceSubclass() != USB_SUBCLASS_VIDEO_STREAMING) continue;
            for (int j = 0; j < intf.getEndpointCount(); j++) {
                UsbEndpoint endpoint = intf.getEndpoint(j);
                if (endpoint.getDirection() != UsbConstants.USB_DIR_IN) continue;
                if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (bulkStream == null
                            || bulkStream.getAlternateSetting() < intf.getAlternateSetting()) {
                        bulkStream = intf;
                        bulkIn = endpoint;
                    }
                } else if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                    hasIsoIn = true;
                    if (isoStream == null
                            || isoStream.getAlternateSetting() < intf.getAlternateSetting()) {
                        isoStream = intf;
                    }
                }
            }
        }
        UsbInterface selectedStream = bulkStream != null ? bulkStream : isoStream;
        if (control == null && selectedStream == null) return null;
        return new UvcInterfaceInfo(control, selectedStream, bulkIn, hasIsoIn);
    }

    private static boolean hasInterface(UsbDevice device, int interfaceClass, int interfaceSubclass) {
        if (device == null) return false;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == interfaceClass
                    && intf.getInterfaceSubclass() == interfaceSubclass) return true;
        }
        return false;
    }

    private static Map<String, Object> deviceDetails(UsbDevice device, UvcInterfaceInfo info) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (device != null) {
            result.put("deviceId", device.getDeviceId());
            result.put("deviceName", safe(device.getDeviceName()));
            result.put("vendorId", device.getVendorId());
            result.put("productId", device.getProductId());
            result.put("interfaceCount", device.getInterfaceCount());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                result.put("manufacturer", safe(device.getManufacturerName()));
                result.put("product", safe(device.getProductName()));
            }
        }
        if (info != null) {
            result.put("hasUvcControlInterface", info.controlInterface != null);
            result.put("hasUvcStreamingInterface", info.streamingInterface != null);
            result.put("hasBulkVideoIn", info.bulkIn != null);
            result.put("hasIsochronousVideoIn", info.hasIsochronousIn);
        }
        return Collections.unmodifiableMap(result);
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

    private static int intOption(ConnectionRequest request, String key, int fallback) {
        try {
            Object value = request.getOptions().get(key);
            int parsed = value instanceof Number ? ((Number) value).intValue()
                    : Integer.parseInt(String.valueOf(value));
            return parsed >= 16 && parsed <= 8192 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[bytes.length - 2] & 0xFF) == 0xFF
                && (bytes[bytes.length - 1] & 0xFF) == 0xD9;
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 0xFFL)
                | (((long) bytes[offset + 1] & 0xFFL) << 8)
                | (((long) bytes[offset + 2] & 0xFFL) << 16)
                | (((long) bytes[offset + 3] & 0xFFL) << 24);
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (values == null || values.length % 2 != 0) return result;
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return Collections.unmodifiableMap(result);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "UNKNOWN ERROR";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    /** Small bounded append-only buffer for UVC payloads. */
    private static final class ByteArrayOutputBuilder {
        private final int maximumBytes;
        private byte[] data = new byte[32 * 1024];
        private int count;

        ByteArrayOutputBuilder(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        int size() { return count; }

        boolean append(byte[] source, int offset, int length) {
            if (source == null || offset < 0 || length < 0
                    || offset + length > source.length || count + length > maximumBytes) return false;
            int required = count + length;
            if (required > data.length) {
                int next = data.length;
                while (next < required) {
                    next = Math.min(maximumBytes, Math.max(next * 2, required));
                    if (next == data.length) return false;
                }
                byte[] expanded = new byte[next];
                System.arraycopy(data, 0, expanded, 0, count);
                data = expanded;
            }
            System.arraycopy(source, offset, data, count, length);
            count = required;
            return true;
        }

        byte[] toByteArray() {
            byte[] result = new byte[count];
            System.arraycopy(data, 0, result, 0, count);
            return result;
        }

        void reset() { count = 0; }
    }
}
