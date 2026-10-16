package com.example.landcam;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Common contract for LANDCAM camera transports.
 *
 * <p>A transport represents one way of communicating with a camera or receiving
 * a camera video signal. It is deliberately separate from CameraEngine's image
 * processing pipeline: the transport establishes/releases the connection and
 * reports what the connected hardware can actually do.</p>
 *
 * <p>Implementations must not report a connection as successful merely because
 * a request was accepted. {@link #connect(ConnectionRequest, Listener)} returns
 * whether the request was accepted for processing; actual state changes must be
 * reported through {@link Listener#onStatusChanged(Type, Status, String,
 * Capabilities)}. Callbacks may arrive on a worker thread. Consumers that update
 * Android or Flutter UI must dispatch them to the appropriate thread.</p>
 *
 * <p>This interface is a contract only. It does not itself implement Wi-Fi,
 * Bluetooth, USB/PTP, or UVC protocols.</p>
 */
public interface CameraTransport extends AutoCloseable {

    /** Identifies the mechanism used by the transport. */
    Type getType();

    /** Current transport/session state. */
    Status getStatus();

    /** Capabilities confirmed by the active implementation/device. */
    Capabilities getCapabilities();

    /**
     * Starts a connection attempt.
     *
     * @param request transport-specific request; must match {@link #getType()}
     * @param listener receives asynchronous status, diagnostic, and error events
     * @return {@code true} when the request was accepted, not when the camera is
     *         already connected; {@code false} when it was rejected immediately
     */
    boolean connect(ConnectionRequest request, Listener listener);

    /** Requests disconnection and releases transport-owned resources. */
    void disconnect();

    /** Optional lifecycle alias; implementations should release owned resources. */
    @Override
    default void close() {
        disconnect();
    }

    default boolean isConnected() {
        Status status = getStatus();
        return status != null && status.isConnected();
    }

    default boolean isBusy() {
        Status status = getStatus();
        return status != null && status.isBusy();
    }

    /**
     * Receives status updates. Default methods keep transports lightweight when a
     * caller only needs a subset of the available notifications.
     */
    interface Listener {
        default void onStatusChanged(
                Type type,
                Status status,
                String message,
                Capabilities capabilities
        ) { }

        default void onLog(Type type, String level, String message) { }

        default void onError(Type type, String code, String message, Throwable cause) { }

        /**
         * Optional transport-specific event for discovery/diagnostic data. The
         * payload should contain only values suitable for crossing the platform
         * channel; never include Wi-Fi passwords or other secrets.
         */
        default void onEvent(Type type, String event, Map<String, Object> data) { }
    }

    enum Type {
        WIFI("WIFI"),
        BLUETOOTH("BLUETOOTH"),
        USB_PTP("USB_PTP"),
        HDMI_UVC("HDMI_UVC");

        private final String nativeName;

        Type(String nativeName) {
            this.nativeName = nativeName;
        }

        public String nativeName() {
            return nativeName;
        }

        /** Parses native/Dart names and common aliases without throwing. */
        public static Type fromNativeName(String value) {
            if (value == null) return null;
            String normalized = value.trim()
                    .toUpperCase(java.util.Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            switch (normalized) {
                case "WIFI":
                case "WI_FI":
                case "WIRELESS":
                    return WIFI;
                case "BLUETOOTH":
                case "BT":
                    return BLUETOOTH;
                case "USB_PTP":
                case "USBPTP":
                case "PTP":
                case "USB":
                    return USB_PTP;
                case "HDMI_UVC":
                case "HDMI":
                case "UVC":
                case "VIDEO_CAPTURE":
                    return HDMI_UVC;
                default:
                    return null;
            }
        }
    }

    enum Status {
        DISCONNECTED,
        CHECKING_PERMISSION,
        CONNECTING,
        DISCOVERING,
        VALIDATING,
        CONNECTED,
        STREAMING,
        RECONNECTING,
        DISCONNECTING,
        UNSUPPORTED,
        ERROR;

        public boolean isBusy() {
            switch (this) {
                case CHECKING_PERMISSION:
                case CONNECTING:
                case DISCOVERING:
                case VALIDATING:
                case RECONNECTING:
                case DISCONNECTING:
                    return true;
                default:
                    return false;
            }
        }

        public boolean isConnected() {
            return this == CONNECTED || this == STREAMING;
        }

        public boolean hasError() {
            return this == ERROR || this == UNSUPPORTED;
        }

        public String nativeName() {
            return name();
        }

        public static Status fromNativeName(String value) {
            if (value == null) return null;
            String normalized = value.trim()
                    .toUpperCase(java.util.Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            if ("READY".equals(normalized)) return CONNECTED;
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    /** Immutable, transport-neutral connection input. */
    final class ConnectionRequest {
        private final Type type;
        private final String ssid;
        private final String password;
        private final String securityType;
        private final String pairingMethod;
        private final String deviceId;
        private final Integer vendorId;
        private final Integer productId;
        private final Map<String, Object> options;

        public ConnectionRequest(
                Type type,
                String ssid,
                String password,
                String securityType,
                String pairingMethod,
                String deviceId,
                Integer vendorId,
                Integer productId,
                Map<String, ?> options
        ) {
            if (type == null) {
                throw new IllegalArgumentException("Transport type must not be null");
            }
            this.type = type;
            this.ssid = clean(ssid);
            this.password = password;
            this.securityType = clean(securityType);
            this.pairingMethod = clean(pairingMethod);
            this.deviceId = clean(deviceId);
            this.vendorId = vendorId;
            this.productId = productId;

            LinkedHashMap<String, Object> copiedOptions = new LinkedHashMap<>();
            if (options != null) {
                for (Map.Entry<String, ?> entry : options.entrySet()) {
                    if (entry.getKey() != null) {
                        copiedOptions.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            this.options = Collections.unmodifiableMap(copiedOptions);
        }

        public static ConnectionRequest wifi(
                String ssid,
                String password,
                String securityType,
                String pairingMethod
        ) {
            return new ConnectionRequest(
                    Type.WIFI, ssid, password, securityType, pairingMethod,
                    null, null, null, null
            );
        }

        public static ConnectionRequest savedWifi() {
            return new ConnectionRequest(
                    Type.WIFI, null, null, null, "SAVED_WIFI",
                    null, null, null, null
            );
        }

        public static ConnectionRequest bluetooth(String deviceId) {
            return new ConnectionRequest(
                    Type.BLUETOOTH, null, null, null, null,
                    deviceId, null, null, null
            );
        }

        public static ConnectionRequest usbPtp(
                String deviceId,
                Integer vendorId,
                Integer productId
        ) {
            return new ConnectionRequest(
                    Type.USB_PTP, null, null, null, null,
                    deviceId, vendorId, productId, null
            );
        }

        public static ConnectionRequest hdmiUvc(String deviceId) {
            return new ConnectionRequest(
                    Type.HDMI_UVC, null, null, null, null,
                    deviceId, null, null, null
            );
        }

        public Type getType() { return type; }
        public String getSsid() { return ssid; }
        public String getPassword() { return password; }
        public String getSecurityType() { return securityType; }
        public String getPairingMethod() { return pairingMethod; }
        public String getDeviceId() { return deviceId; }
        public Integer getVendorId() { return vendorId; }
        public Integer getProductId() { return productId; }
        public Map<String, Object> getOptions() { return options; }

        /** Builds a platform-channel-friendly map with absent values omitted. */
        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("transport", type.nativeName());
            if (ssid != null) result.put("ssid", ssid);
            if (password != null) result.put("password", password);
            if (securityType != null) result.put("securityType", securityType);
            if (pairingMethod != null) result.put("pairingMethod", pairingMethod);
            if (deviceId != null) result.put("deviceId", deviceId);
            if (vendorId != null) result.put("vendorId", vendorId);
            if (productId != null) result.put("productId", productId);
            if (!options.isEmpty()) result.put("options", options);
            return result;
        }

        /** Parses a MethodChannel-like map, preserving unknown options. */
        public static ConnectionRequest fromMap(Type defaultType, Map<?, ?> raw) {
            if (raw == null) {
                throw new IllegalArgumentException("Connection request map must not be null");
            }
            Object rawType = first(raw, "transport", "type", "preferredTransport");
            Type type = Type.fromNativeName(rawType == null ? null : String.valueOf(rawType));
            if (type == null) type = defaultType;
            if (type == null) {
                throw new IllegalArgumentException("Unknown or missing transport type");
            }

            Object rawOptions = raw.get("options");
            LinkedHashMap<String, Object> options = new LinkedHashMap<>();
            if (rawOptions instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawOptions).entrySet()) {
                    if (entry.getKey() != null) {
                        options.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            }
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() == null) continue;
                String key = String.valueOf(entry.getKey());
                if (!"transport".equals(key) && !"type".equals(key)
                        && !"preferredTransport".equals(key) && !"options".equals(key)
                        && !"ssid".equals(key) && !"password".equals(key)
                        && !"securityType".equals(key) && !"pairingMethod".equals(key)
                        && !"deviceId".equals(key) && !"vendorId".equals(key)
                        && !"productId".equals(key)) {
                    options.put(key, entry.getValue());
                }
            }

            return new ConnectionRequest(
                    type,
                    string(first(raw, "ssid")),
                    string(first(raw, "password")),
                    string(first(raw, "securityType", "security")),
                    string(first(raw, "pairingMethod", "method")),
                    string(first(raw, "deviceId", "id")),
                    integer(first(raw, "vendorId", "vendor_id")),
                    integer(first(raw, "productId", "product_id")),
                    options
            );
        }

        /** Avoid leaking Wi-Fi credentials into logs. */
        @Override
        public String toString() {
            return "ConnectionRequest{" +
                    "type=" + type +
                    ", ssid='" + (ssid == null ? "" : ssid) + '\'' +
                    ", password=" + (password == null || password.isEmpty() ? "<empty>" : "<redacted>") +
                    ", securityType='" + (securityType == null ? "" : securityType) + '\'' +
                    ", pairingMethod='" + (pairingMethod == null ? "" : pairingMethod) + '\'' +
                    ", deviceId='" + (deviceId == null ? "" : deviceId) + '\'' +
                    ", vendorId=" + vendorId +
                    ", productId=" + productId +
                    '}';
        }

        private static Object first(Map<?, ?> map, String... keys) {
            for (String key : keys) {
                Object value = map.get(key);
                if (value != null) return value;
            }
            return null;
        }

        private static String string(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private static Integer integer(Object value) {
            if (value instanceof Number) return ((Number) value).intValue();
            if (value == null) return null;
            try {
                return Integer.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static String clean(String value) {
            if (value == null) return null;
            String cleaned = value.trim();
            return cleaned.isEmpty() ? null : cleaned;
        }
    }

    /** Immutable capability snapshot for the current transport/device. */
    final class Capabilities {
        private final Type type;
        private final boolean supported;
        private final boolean canControlCamera;
        private final boolean canReceiveLiveView;
        private final boolean canCapturePhoto;
        private final boolean canTransferFiles;
        private final boolean hasRedChannel;
        private final boolean hasNirChannel;
        private final boolean hasAlignedRedNirPair;
        private final String reason;

        public Capabilities(
                Type type,
                boolean supported,
                boolean canControlCamera,
                boolean canReceiveLiveView,
                boolean canCapturePhoto,
                boolean canTransferFiles,
                boolean hasRedChannel,
                boolean hasNirChannel,
                boolean hasAlignedRedNirPair,
                String reason
        ) {
            if (type == null) {
                throw new IllegalArgumentException("Capability transport type must not be null");
            }
            this.type = type;
            this.supported = supported;
            this.canControlCamera = canControlCamera;
            this.canReceiveLiveView = canReceiveLiveView;
            this.canCapturePhoto = canCapturePhoto;
            this.canTransferFiles = canTransferFiles;
            this.hasRedChannel = hasRedChannel;
            this.hasNirChannel = hasNirChannel;
            this.hasAlignedRedNirPair = hasAlignedRedNirPair;
            this.reason = reason == null ? "" : reason.trim();
        }

        public static Capabilities unsupported(Type type, String reason) {
            return new Capabilities(
                    type, false, false, false, false, false,
                    false, false, false,
                    reason == null || reason.trim().isEmpty()
                            ? "TRANSPORT NOT SUPPORTED" : reason
            );
        }

        public Type getType() { return type; }
        public boolean isSupported() { return supported; }
        public boolean canControlCamera() { return canControlCamera; }
        public boolean canReceiveLiveView() { return canReceiveLiveView; }
        public boolean canCapturePhoto() { return canCapturePhoto; }
        public boolean canTransferFiles() { return canTransferFiles; }
        public boolean hasRedChannel() { return hasRedChannel; }
        public boolean hasNirChannel() { return hasNirChannel; }
        public boolean hasAlignedRedNirPair() { return hasAlignedRedNirPair; }
        public boolean canCalculateNdvi() { return hasRedChannel && hasNirChannel && hasAlignedRedNirPair; }
        public String getReason() { return reason; }

        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("transport", type.nativeName());
            map.put("supported", supported);
            map.put("canControlCamera", canControlCamera);
            map.put("canReceiveLiveView", canReceiveLiveView);
            map.put("canCapturePhoto", canCapturePhoto);
            map.put("canTransferFiles", canTransferFiles);
            map.put("hasRedChannel", hasRedChannel);
            map.put("hasNirChannel", hasNirChannel);
            map.put("hasAlignedRedNirPair", hasAlignedRedNirPair);
            map.put("canCalculateNdvi", canCalculateNdvi());
            map.put("reason", reason);
            return Collections.unmodifiableMap(map);
        }

        public static Capabilities fromMap(Type fallbackType, Map<?, ?> map) {
            Type type = Type.fromNativeName(stringValue(map, "transport", "type"));
            if (type == null) type = fallbackType;
            if (type == null) throw new IllegalArgumentException("Unknown capability transport type");
            return new Capabilities(
                    type,
                    boolValue(map, "supported", false),
                    boolValue(map, "canControlCamera", false),
                    boolValue(map, "canReceiveLiveView", false),
                    boolValue(map, "canCapturePhoto", false),
                    boolValue(map, "canTransferFiles", false),
                    boolValue(map, "hasRedChannel", false),
                    boolValue(map, "hasNirChannel", false),
                    boolValue(map, "hasAlignedRedNirPair", false),
                    stringValue(map, "reason")
            );
        }

        private static boolean boolValue(Map<?, ?> map, String key, boolean fallback) {
            if (map == null || !map.containsKey(key)) return fallback;
            Object value = map.get(key);
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).intValue() != 0;
            if (value != null) {
                String text = String.valueOf(value).trim();
                if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
                if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
            }
            return fallback;
        }

        private static String stringValue(Map<?, ?> map, String... keys) {
            if (map == null) return null;
            for (String key : keys) {
                Object value = map.get(key);
                if (value != null) return String.valueOf(value);
            }
            return null;
        }
    }
}
