package com.example.landcam;

import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Adapter that exposes CameraEngine's existing Sony Wi-Fi implementation
 * through the common CameraTransport contract.
 *
 * <p>Important architectural choice: this class does not create a second
 * WifiNetworkSpecifier, perform a second discovery scan, bind the process to
 * another Network, or start a competing live-view session. CameraEngine remains
 * the single owner of Wi-Fi, discovery, the Sony Remote API, live view, capture,
 * optical processing and NDVI.</p>
 *
 * <p>The Android activity should forward CameraEngine.Listener events to
 * {@link #onCameraEngineEvent(String, Object)} so that transport state follows
 * the real native connection rather than merely the MethodChannel acceptance.</p>
 */
public final class SonyWifiTransport implements CameraTransport {
    private static final String TAG = "LandCamSonyWifi";

    private static final Pattern SONY_DIRECT_SSID = Pattern.compile(
            "DIRECT-[A-Za-z0-9_-]+:[A-Za-z0-9_.-]{1,48}"
    );
    private static final Pattern SONY_PASSWORD = Pattern.compile("[A-Za-z0-9]{8,63}");

    private final Object lock = new Object();
    private final CameraEngine engine;

    private volatile Listener listener;
    private volatile Status status = Status.DISCONNECTED;
    private volatile Capabilities capabilities = Capabilities.unsupported(
            Type.WIFI, "NO ACTIVE CAMERA CONNECTION"
    );
    private volatile String lastMessage = "NO ACTIVE CAMERA CONNECTION";
    private volatile Map<String, Object> lastEndpoint = Collections.emptyMap();
    private volatile Map<String, Object> lastCapabilityPayload = Collections.emptyMap();
    private volatile String lastRequestedSsid;
    private volatile boolean closed;

    public SonyWifiTransport(CameraEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("CameraEngine must not be null");
        }
        this.engine = engine;
    }

    @Override
    public Type getType() {
        return Type.WIFI;
    }

    @Override
    public Status getStatus() {
        return status;
    }

    @Override
    public Capabilities getCapabilities() {
        return capabilities;
    }

    /**
     * Starts the existing CameraEngine Wi-Fi flow.
     *
     * <p>Return value means that the request was accepted for processing, not
     * that the camera is already connected. Actual progress is reported from
     * CameraEngine events forwarded to {@link #onCameraEngineEvent}.</p>
     */
    @Override
    public boolean connect(ConnectionRequest request, Listener newListener) {
        if (request == null || request.getType() != Type.WIFI) {
            return reject(newListener, "INVALID_TRANSPORT_REQUEST",
                    "SonyWifiTransport requires a WIFI ConnectionRequest");
        }
        if (closed) {
            return reject(newListener, "TRANSPORT_CLOSED", "Sony Wi-Fi transport is closed");
        }
        if (engine == null) {
            return reject(newListener, "ENGINE_UNAVAILABLE", "CameraEngine is unavailable");
        }

        synchronized (lock) {
            if (status.isBusy() || status.isConnected()) {
                return reject(newListener, "TRANSPORT_BUSY",
                        "Disconnect the current camera session before reconnecting");
            }
            listener = newListener;
            capabilities = Capabilities.unsupported(Type.WIFI, "CAMERA CAPABILITIES NOT YET CONFIRMED");
            lastEndpoint = Collections.emptyMap();
            lastCapabilityPayload = Collections.emptyMap();
        }

        String pairingMethod = normalizeIdentifier(request.getPairingMethod());
        boolean accepted;
        if ("SAVED_WIFI".equals(pairingMethod) || "SAVED".equals(pairingMethod)) {
            setStatus(Status.CONNECTING, "REQUESTING SAVED CAMERA WI-FI", capabilities);
            try {
                accepted = engine.connectLastWifi();
            } catch (Exception e) {
                return failImmediately("SAVED_WIFI_FAILED",
                        "Saved Wi-Fi request failed: " + safeMessage(e), e);
            }
            if (!accepted) {
                // CameraEngine emits a networkUnavailable diagnostic when no
                // in-memory saved credentials exist. Keep this adapter honest.
                if (status != Status.ERROR) {
                    setStatus(Status.ERROR, "SAVED CAMERA WI-FI WAS NOT AVAILABLE", capabilities);
                }
                return false;
            }
            emitEvent("connectRequestAccepted", mapOf(
                    "transport", Type.WIFI.nativeName(),
                    "pairingMethod", "SAVED_WIFI"
            ));
            return true;
        }

        String ssid = trimToNull(request.getSsid());
        String password = request.getPassword();
        String security = normalizeSecurity(request.getSecurityType());
        String validationError = validateCredentials(ssid, password, security);
        if (validationError != null) {
            synchronized (lock) {
                listener = newListener;
            }
            return reject(newListener, "INVALID_WIFI_CREDENTIALS", validationError);
        }

        synchronized (lock) {
            lastRequestedSsid = ssid;
        }
        setStatus(Status.CONNECTING, "REQUESTING SONY CAMERA WI-FI", capabilities);
        try {
            accepted = engine.connectWifiCredentialsFromTransport(ssid, password);
        } catch (Exception e) {
            return failImmediately("WIFI_REQUEST_FAILED",
                    "CameraEngine could not start Wi-Fi: " + safeMessage(e), e);
        }
        if (!accepted) {
            if (status != Status.ERROR) {
                setStatus(Status.ERROR, "CAMERA ENGINE REJECTED THE WI-FI REQUEST", capabilities);
            }
            return false;
        }
        emitEvent("connectRequestAccepted", mapOf(
                "transport", Type.WIFI.nativeName(),
                "ssid", ssid,
                "pairingMethod", request.getPairingMethod() == null ? "MANUAL_WIFI" : request.getPairingMethod()
        ));
        return true;
    }

    /**
     * Must be called by MainActivity from CameraEngine.Listener.onNativeEvent.
     * Frame events are deliberately not copied through the transport listener;
     * CameraEngine already sends them to Flutter and they may contain large data.
     */
    public void onCameraEngineEvent(String event, Object data) {
        if (closed || event == null || event.trim().isEmpty()) return;

        switch (event) {
            case "wifiConnecting":
                lastRequestedSsid = eventText(data, "ssid");
                setStatus(Status.CONNECTING, "CONNECTING CAMERA WI-FI", capabilities);
                forwardEvent(event, data);
                return;

            case "wifiConnected":
                lastRequestedSsid = eventText(data, "ssid");
                setStatus(Status.DISCOVERING, "WI-FI AVAILABLE; DISCOVERING CAMERA", capabilities);
                forwardEvent(event, data);
                return;

            case "cameraProbe": {
                String probe = eventText(data, "state");
                if ("DISCOVERY_STARTED".equalsIgnoreCase(probe)) {
                    setStatus(Status.DISCOVERING, "DISCOVERING CAMERA ENDPOINT", capabilities);
                } else if ("CAMERA_API_REACHED".equalsIgnoreCase(probe)) {
                    setStatus(Status.VALIDATING, "VALIDATING CAMERA API", capabilities);
                } else if ("LIVEVIEW_REQUEST".equalsIgnoreCase(probe)) {
                    setStatus(Status.CONNECTED, "CAMERA API REACHED; STARTING LIVE VIEW", capabilities);
                }
                forwardEvent(event, data);
                return;
            }

            case "cameraEndpointFound":
            case "cameraIdentified":
            case "cameraIdentity":
                if (data instanceof Map) {
                    lastEndpoint = immutableStringMap((Map<?, ?>) data);
                }
                // Identity/endpoint discovery is not proof that live view started.
                if (status != Status.STREAMING) {
                    setStatus(Status.CONNECTED, "CAMERA ENDPOINT FOUND", capabilities);
                }
                forwardEvent(event, data);
                return;

            case "cameraCapabilities":
                if (data instanceof Map) {
                    lastCapabilityPayload = immutableStringMap((Map<?, ?>) data);
                    capabilities = capabilitiesFromEnginePayload((Map<?, ?>) data);
                    notifyStatus(status, lastMessage, capabilities);
                }
                forwardEvent(event, data);
                return;

            case "liveviewActive":
                if (Boolean.TRUE.equals(data)) {
                    setStatus(Status.STREAMING, "LIVE VIEW ACTIVE", capabilities);
                }
                forwardEvent(event, data);
                return;

            case "firstLiveviewFrame":
                setStatus(Status.STREAMING, "FIRST LIVE-VIEW FRAME RECEIVED", capabilities);
                forwardEvent(event, data);
                return;

            case "networkUnavailable":
            case "networkLost":
            case "cameraError":
            case "streamLost":
                setStatus(Status.ERROR, safeEventMessage(data, event),
                        Capabilities.unsupported(Type.WIFI, safeEventMessage(data, event)));
                forwardEvent(event, data);
                return;

            case "disconnected":
                capabilities = Capabilities.unsupported(Type.WIFI, "CAMERA DISCONNECTED");
                lastEndpoint = Collections.emptyMap();
                lastCapabilityPayload = Collections.emptyMap();
                setStatus(Status.DISCONNECTED, "CAMERA DISCONNECTED", capabilities);
                forwardEvent(event, data);
                return;

            case "log":
                forwardLog(data);
                return;

            case "error":
                // Engine errors may refer to capture or settings rather than
                // connectivity. Only fail the transport while connecting or
                // discovering; otherwise preserve the confirmed connection.
                if (status.isBusy() || status == Status.CONNECTING || status == Status.DISCOVERING
                        || status == Status.VALIDATING) {
                    setStatus(Status.ERROR, safeEventMessage(data, "NATIVE ERROR"), capabilities);
                }
                forwardEvent(event, data);
                return;

            default:
                // Keep the adapter useful for future native diagnostics, but do
                // not forward high-volume preview frames through this interface.
                if (!"liveviewFrame".equals(event) && !"spectralFrame".equals(event)) {
                    forwardEvent(event, data);
                }
        }
    }

    /** Returns the last endpoint event as a safe immutable snapshot. */
    public Map<String, Object> getLastEndpoint() {
        return lastEndpoint;
    }

    /** Returns the last native capability payload. */
    public Map<String, Object> getLastCapabilityPayload() {
        return lastCapabilityPayload;
    }

    public String getLastRequestedSsid() {
        return lastRequestedSsid;
    }

    @Override
    public void disconnect() {
        if (closed) return;
        setStatus(Status.DISCONNECTING, "DISCONNECTING CAMERA WI-FI", capabilities);
        try {
            engine.disconnect();
            // The engine normally emits 'disconnected' synchronously. Set a
            // fallback state in case a listener was detached during teardown.
            if (status == Status.DISCONNECTING) {
                capabilities = Capabilities.unsupported(Type.WIFI, "CAMERA DISCONNECTED");
                setStatus(Status.DISCONNECTED, "CAMERA DISCONNECTED", capabilities);
            }
        } catch (Exception e) {
            setStatus(Status.ERROR, "DISCONNECT FAILED: " + safeMessage(e), capabilities);
            emitError("DISCONNECT_FAILED", lastMessage, e);
        }
    }

    /**
     * Closes this adapter only. The CameraEngine is owned by MainActivity and
     * must be destroyed by its owner, not by the transport.
     */
    @Override
    public void close() {
        if (closed) return;
        disconnect();
        closed = true;
        listener = null;
    }

    private static String validateCredentials(String ssid, String password, String security) {
        if (ssid == null || ssid.isEmpty()) return "SSID IS REQUIRED";
        if (ssid.getBytes(StandardCharsets.UTF_8).length > 32) return "SSID MUST BE AT MOST 32 UTF-8 BYTES";
        if (!SONY_DIRECT_SSID.matcher(ssid).matches()) {
            return "CURRENT CAMERA ENGINE SUPPORTS SONY DIRECT-* SSIDS ONLY";
        }
        // This CameraEngine version hard-codes WPA2 in WifiNetworkSpecifier and
        // parses 8-63 alphanumeric characters as the Sony password. Do not claim
        // WPA3/open-network support until that engine API is generalized.
        if (!("WPA2".equals(security) || "WPA2_PSK".equals(security) || "PSK".equals(security) || security.isEmpty())) {
            return "CURRENT CAMERA ENGINE SUPPORTS WPA2-PERSONAL ONLY";
        }
        if (password == null || !SONY_PASSWORD.matcher(password).matches()) {
            return "SONY CAMERA PASSWORD MUST BE 8-63 ALPHANUMERIC CHARACTERS";
        }
        return null;
    }

    private static String normalizeSecurity(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "WPA2";
        return raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private boolean reject(Listener target, String code, String message) {
        // A rejected new request must not overwrite the state/listener of an
        // already-running transport session (for example, a second tap while
        // the camera is connected).
        Capabilities rejectedCapabilities = Capabilities.unsupported(Type.WIFI, message);
        Log.w(TAG, code + ": " + message);
        if (target != null) {
            try {
                target.onStatusChanged(Type.WIFI, Status.ERROR, message, rejectedCapabilities);
            } catch (Exception e) {
                Log.e(TAG, "Rejected-request status callback failed", e);
            }
            try {
                target.onError(Type.WIFI, code, message, null);
            } catch (Exception e) {
                Log.e(TAG, "Rejected-request error callback failed", e);
            }
            try {
                target.onEvent(Type.WIFI, "error", mapOf(
                        "transport", Type.WIFI.nativeName(), "code", code, "message", message));
            } catch (Exception e) {
                Log.e(TAG, "Rejected-request event callback failed", e);
            }
        }
        return false;
    }

    private boolean failImmediately(String code, String message, Throwable error) {
        setStatus(Status.ERROR, message, Capabilities.unsupported(Type.WIFI, message));
        emitError(code, message, error);
        emitEvent("error", mapOf("transport", Type.WIFI.nativeName(), "code", code, "message", message));
        return false;
    }

    private void setStatus(Status next, String message, Capabilities nextCapabilities) {
        if (next == null) return;
        synchronized (lock) {
            status = next;
            lastMessage = message == null ? "" : message;
            if (nextCapabilities != null) capabilities = nextCapabilities;
        }
        notifyStatus(next, lastMessage, capabilities);
        emitEvent("transportStatus", statusPayload());
    }

    private void notifyStatus(Status next, String message, Capabilities currentCapabilities) {
        Listener current = listener;
        if (current == null) return;
        try {
            current.onStatusChanged(Type.WIFI, next, message, currentCapabilities);
        } catch (Exception e) {
            Log.e(TAG, "Transport status callback failed", e);
        }
    }

    private Map<String, Object> statusPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transport", Type.WIFI.nativeName());
        payload.put("status", status.nativeName());
        payload.put("message", lastMessage);
        payload.put("capabilities", capabilities.toMap());
        if (lastRequestedSsid != null) payload.put("ssid", lastRequestedSsid);
        Map<String, Object> endpoint = lastEndpoint;
        if (!endpoint.isEmpty()) {
            Object host = endpoint.get("host");
            Object port = endpoint.get("port");
            if (host != null) payload.put("host", host);
            if (port != null) payload.put("port", port);
        }
        return payload;
    }

    private void forwardEvent(String event, Object data) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("nativeEvent", event);
        if (data instanceof Map) payload.put("data", immutableStringMap((Map<?, ?>) data));
        else if (data != null && !(data instanceof byte[])) payload.put("data", data);
        emitEvent(event, payload);
    }

    private void forwardLog(Object data) {
        Listener current = listener;
        if (current == null) return;
        String level = "INFO";
        String message = data == null ? "" : String.valueOf(data);
        if (data instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) data;
            Object rawLevel = map.get("level");
            Object rawMessage = map.get("message");
            if (rawLevel != null) level = String.valueOf(rawLevel);
            if (rawMessage != null) message = String.valueOf(rawMessage);
        }
        try {
            current.onLog(Type.WIFI, level, message);
        } catch (Exception e) {
            Log.e(TAG, "Transport log callback failed", e);
        }
    }

    private void emitError(String code, String message, Throwable cause) {
        Listener current = listener;
        if (current == null) return;
        try {
            current.onError(Type.WIFI, code, message, cause);
        } catch (Exception e) {
            Log.e(TAG, "Transport error callback failed", e);
        }
    }

    private void emitEvent(String event, Map<String, Object> data) {
        Listener current = listener;
        if (current == null || event == null) return;
        try {
            current.onEvent(Type.WIFI, event,
                    Collections.unmodifiableMap(new HashMap<>(data == null ? Collections.emptyMap() : data)));
        } catch (Exception e) {
            Log.e(TAG, "Transport event callback failed: " + event, e);
        }
    }

    private static Capabilities capabilitiesFromEnginePayload(Map<?, ?> data) {
        boolean liveView = bool(data.get("liveView"), false);
        boolean capture = bool(data.get("capture"), false);
        boolean autofocus = bool(data.get("autofocus"), false);
        boolean supported = liveView || capture || autofocus || data.containsKey("protocol");
        Object realNirValue = data.containsKey("realNirAvailable")
                ? data.get("realNirAvailable") : data.get("nir");
        boolean nir = bool(realNirValue, false);
        boolean unified = bool(data.get("unifiedSpectralCrop"), false)
                || bool(data.get("dualOpticalRoi"), false);
        boolean alignedPair = nir && unified;
        boolean red = true; // CameraEngine advertises RGB/R/G/B from the camera's RGB stream.
        String reason = supported
                ? (nir ? "CAMERA CAPABILITIES CONFIRMED; NIR REPORTED BY ENGINE" : "RGB CAPABILITIES CONFIRMED; TRUE NIR NOT REPORTED")
                : "CAMERA CAPABILITIES NOT CONFIRMED";
        return new Capabilities(Type.WIFI, supported, liveView || capture || autofocus,
                liveView, capture, false, red, nir, alignedPair, reason);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value != null) {
            String text = String.valueOf(value).trim();
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        }
        return fallback;
    }

    private static String eventText(Object data, String key) {
        if (data instanceof Map) {
            Object value = ((Map<?, ?>) data).get(key);
            if (value != null) return String.valueOf(value);
        }
        return data == null ? "" : String.valueOf(data);
    }

    private static String safeEventMessage(Object data, String fallback) {
        if (data instanceof Map) {
            Object message = ((Map<?, ?>) data).get("message");
            if (message != null && !String.valueOf(message).trim().isEmpty()) return String.valueOf(message);
            Object reason = ((Map<?, ?>) data).get("reason");
            if (reason != null && !String.valueOf(reason).trim().isEmpty()) return String.valueOf(reason);
            Object code = ((Map<?, ?>) data).get("code");
            if (code != null && !String.valueOf(code).trim().isEmpty()) return String.valueOf(code);
        }
        if (data != null && !String.valueOf(data).trim().isEmpty()) return String.valueOf(data);
        return fallback;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeIdentifier(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static Map<String, Object> immutableStringMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new HashMap<>();
        if (values == null) return map;
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i] != null) map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "UNKNOWN ERROR";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
